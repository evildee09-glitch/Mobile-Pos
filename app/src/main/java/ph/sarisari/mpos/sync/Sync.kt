package ph.sarisari.mpos.sync

import android.content.Context
import androidx.room.withTransaction
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ph.sarisari.mpos.PosApp
import ph.sarisari.mpos.data.*
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit

data class RemoteEvent(val seq: Long, val id: String, val deviceId: String, val entityType: String, val operation: String, val createdAt: Long, val payload: JSONObject)
data class PullResult(val events: List<RemoteEvent>, val cursor: String)

/**
 * Cloud contract (see /server):
 *   POST /v1/sync/push  {deviceId, events:[{id,entityType,entityId,operation,createdAt,payload}]} -> {"acked":[ids]}   (dedupes by event id)
 *   GET  /v1/sync/pull?cursor=<n>&deviceId=<id>  -> {"events":[{seq,id,deviceId,...}], "cursor":"<n>"}  (never returns this device's own events)
 * The bearer token identifies the STORE; the server keeps every store's events separate.
 */
interface RemoteApi {
    suspend fun push(deviceId: String, batch: List<SyncOutbox>): Set<String>
    suspend fun pull(deviceId: String, cursor: String): PullResult
}

class HttpRemoteApi(private val baseUrl: String, private val token: String) : RemoteApi {
    private fun open(path: String, post: Boolean): HttpURLConnection =
        (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = if (post) "POST" else "GET"; connectTimeout = 15_000; readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer $token"); setRequestProperty("Content-Type", "application/json")
            if (post) doOutput = true
        }

    private fun read(c: HttpURLConnection): JSONObject {
        if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
        return JSONObject(c.inputStream.bufferedReader().readText())
    }

    override suspend fun push(deviceId: String, batch: List<SyncOutbox>): Set<String> = withContext(Dispatchers.IO) {
        val body = JSONObject().put("deviceId", deviceId).put("events", JSONArray(batch.map {
            JSONObject().put("id", it.id).put("entityType", it.entityType).put("entityId", it.entityId)
                .put("operation", it.operation).put("createdAt", it.createdAt).put("payload", JSONObject(it.payload))
        }))
        val c = open("/v1/sync/push", true)
        try {
            c.setRequestProperty("Idempotency-Key", batch.first().id)
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val acked = read(c).getJSONArray("acked")
            (0 until acked.length()).map { acked.getString(it) }.toSet()
        } finally { c.disconnect() }
    }

    override suspend fun pull(deviceId: String, cursor: String): PullResult = withContext(Dispatchers.IO) {
        val c = open("/v1/sync/pull?cursor=${URLEncoder.encode(cursor, "UTF-8")}&deviceId=${URLEncoder.encode(deviceId, "UTF-8")}", false)
        try {
            val j = read(c); val a = j.getJSONArray("events")
            PullResult((0 until a.length()).map { i -> a.getJSONObject(i).let { e ->
                RemoteEvent(e.getLong("seq"), e.getString("id"), e.getString("deviceId"), e.getString("entityType"), e.getString("operation"), e.getLong("createdAt"), e.getJSONObject("payload"))
            } }, j.getString("cursor"))
        } finally { c.disconnect() }
    }
}

/**
 * Applies other devices' events locally, idempotently (AppliedEvent), WITHOUT re-queuing them.
 * Conflict policy (never silently discard a sale or payment):
 *  - sales, payments, stock movements are append-only facts -> always applied; stock/credit are additive deltas, so devices converge.
 *  - if applying drives stock below zero or a customer over their credit limit, the fact is kept and a SYNC_CONFLICT audit row is written for the owner.
 *  - products/customers: last-writer-wins by updatedAt (stock itself is never overwritten, only moved by deltas).
 */
class SyncApplier(private val db: AppDatabase, private val clock: () -> Long = { System.currentTimeMillis() }) {
    suspend fun apply(events: List<RemoteEvent>) {
        for (e in events) db.withTransaction {
            if (db.admin().markApplied(AppliedEvent(e.id, clock())) == -1L) return@withTransaction
            try { handle(e) } catch (ex: Exception) { conflict("Could not apply ${e.entityType}/${e.operation} ${e.id}: ${ex.message}") }
        }
    }

    private suspend fun conflict(msg: String) = db.admin().insertAudit(AuditLog(UUID.randomUUID().toString(), "sync", "SYNC_CONFLICT", "sync", null, msg, clock()))

    private suspend fun ensureCustomer(id: String?) {
        if (id == null || db.admin().customer(id) != null) return
        db.admin().insertCustomer(Customer(id, "(customer from another device)", null, null, 0, true, clock(), clock()))
    }
    private suspend fun ensureProduct(id: String, name: String, price: Long) {
        if (db.catalog().get(id) != null) return
        db.catalog().insert(Product(id, null, null, name, null, 0, price, 0, "piece", false, 0, null, false, false, null, null, clock(), clock()))
    }

    private suspend fun handle(e: RemoteEvent) {
        val j = e.payload; val c = db.catalog(); val s = db.sales()
        when (e.entityType to e.operation) {
            "PRODUCT" to "UPSERT" -> {
                val local = c.get(j.getString("id"))
                if (local == null || j.getLong("updatedAt") >= local.updatedAt) {
                    c.upsertProduct(SyncCodec.toProduct(j, local?.stockMilli ?: 0).copy(supplierId = local?.supplierId, expiryDate = local?.expiryDate, batchNo = local?.batchNo))
                    SyncCodec.toUnits(j).forEach { c.upsertUnit(it) }
                }
            }
            "CUSTOMER" to "UPSERT" -> {
                val inc = SyncCodec.toCustomer(j); val local = db.admin().customer(inc.id)
                if (local == null) db.admin().insertCustomer(inc) else if (inc.updatedAt >= local.updatedAt) db.admin().updateCustomer(inc.copy(notes = local.notes))
            }
            "SALE" to "CREATE" -> {
                val sale = SyncCodec.toSale(j.getJSONObject("sale"))
                if (s.sale(sale.id) != null) return
                ensureCustomer(sale.customerId)
                val items = SyncCodec.list(j, "items", SyncCodec::toItem)
                items.forEach { ensureProduct(it.productId, it.productNameSnapshot, it.unitPriceCentavos) }
                s.insertSale(sale); s.insertItems(items)
                SyncCodec.list(j, "payments", SyncCodec::toPayment).forEach { s.insertPayment(it) }
                SyncCodec.list(j, "ledger", SyncCodec::toLedger).forEach { s.insertLedger(it) }
                SyncCodec.list(j, "moves", SyncCodec::toMove).forEach { c.insertMovement(it) }
                items.forEach { i ->
                    c.addStock(i.productId, -i.baseQtyMilli, clock())
                    c.get(i.productId)?.takeIf { it.stockMilli < 0 }?.let { conflict("Stock for ${it.name} is negative after sale ${sale.receiptNumber} from another device") }
                }
                sale.customerId?.takeIf { sale.creditCentavos > 0 }?.let { cid ->
                    val cust = db.admin().customer(cid)!!; val bal = s.balance(cid)
                    if (cust.creditLimitCentavos > 0 && bal > cust.creditLimitCentavos) conflict("${cust.name} is over the credit limit after sale ${sale.receiptNumber} from another device")
                }
            }
            "SALE" to "VOID" -> {
                if (s.markVoided(j.getString("id"), j.getString("reason"), j.getLong("at"), j.getString("voidShiftId")) == 0) return
                SyncCodec.list(j, "payments", SyncCodec::toPayment).forEach { s.insertPayment(it) }
                SyncCodec.list(j, "ledger", SyncCodec::toLedger).forEach { s.insertLedger(it) }
                SyncCodec.list(j, "moves", SyncCodec::toMove).forEach { m -> c.insertMovement(m); c.addStock(m.productId, m.qtyDeltaMilli, clock()) }
            }
            "DEBT_PAYMENT" to "CREATE" -> {
                val p = SyncCodec.toPayment(j.getJSONObject("payment"))
                if (s.payment(p.id) != null) return
                ensureCustomer(p.customerId)
                s.insertPayment(p); s.insertLedger(SyncCodec.toLedger(j.getJSONObject("ledger")))
            }
            "STOCK" to "DELTA" -> {
                val m = SyncCodec.toMove(j.getJSONObject("move"))
                ensureProduct(m.productId, "(product from another device)", 0)
                j.optJSONObject("receipt")?.let { c.insertReceipt(SyncCodec.toReceipt(it)) }
                c.insertMovement(m); c.addStock(m.productId, m.qtyDeltaMilli, clock())
            }
            else -> conflict("Unknown event ${e.entityType}/${e.operation}")
        }
    }
}

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val db = (applicationContext as PosApp).container.db
        val admin = db.admin()
        val url = admin.setting("sync_url")?.takeIf { it.isNotBlank() } ?: return Result.success()   // sync is optional
        val api = HttpRemoteApi(url, admin.setting("sync_token") ?: "")
        val device = admin.setting("device_id") ?: "unknown"
        return try {
            // 1) upload local facts
            while (true) {
                val batch = admin.pending(50)
                if (batch.isEmpty()) break
                val acked = api.push(device, batch)
                admin.markSynced(batch.map { it.id }.filter { it in acked })              // only server-acknowledged rows
                val missed = batch.map { it.id }.filter { it !in acked }
                if (missed.isNotEmpty()) { admin.markFailed(missed, "not acknowledged"); return Result.retry() }
            }
            // 2) download other devices' facts and apply them
            var cursor = admin.setting("sync_cursor") ?: "0"
            val applier = SyncApplier(db)
            var rounds = 0
            while (rounds++ < 50) {
                val r = api.pull(device, cursor)
                if (r.events.isEmpty()) break
                applier.apply(r.events); cursor = r.cursor
                admin.putSetting(AppSetting("sync_cursor", cursor))
            }
            admin.putSetting(AppSetting("last_sync_at", System.currentTimeMillis().toString()))
            admin.putSetting(AppSetting("last_sync_error", ""))
            Result.success()
        } catch (e: Exception) {
            admin.putSetting(AppSetting("last_sync_error", e.message ?: "sync failed"))
            admin.pending(50).takeIf { it.isNotEmpty() }?.let { admin.markFailed(it.map { o -> o.id }, e.message ?: "error") }
            Result.retry()                                                              // WorkManager exponential backoff
        }
    }
}

object SyncScheduler {
    private val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    fun schedule(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("sync-periodic", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(net)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }
    fun syncNow(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniqueWork("sync-now", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(net).build())
    }
}
