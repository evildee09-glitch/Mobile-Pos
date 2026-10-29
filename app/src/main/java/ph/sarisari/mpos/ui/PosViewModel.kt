package ph.sarisari.mpos.ui

import android.app.Application
import android.net.Uri
import org.json.JSONObject
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import ph.sarisari.mpos.PosApp
import ph.sarisari.mpos.data.*
import ph.sarisari.mpos.backup.DriveBackup
import ph.sarisari.mpos.backup.DriveFile
import ph.sarisari.mpos.domain.*
import ph.sarisari.mpos.util.Expiry
import ph.sarisari.mpos.util.Manila
import ph.sarisari.mpos.util.Money
import ph.sarisari.mpos.util.Qty
import java.util.UUID

data class UiLine(val product: Product, val unitName: String, val factorMilli: Long, val unitPrice: Long, val qtyMilli: Long) {
    val total get() = Qty.lineTotal(unitPrice, qtyMilli)
}
data class StatementRow(val whenText: String, val label: String, val amount: Long, val running: Long)

@OptIn(ExperimentalCoroutinesApi::class)
class PosViewModel(app: Application) : AndroidViewModel(app) {
    val c = (app as PosApp).container
    private val db = c.db

    val toast = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)

    // ---- session ----
    val staff: StateFlow<List<Staff>?> = db.admin().activeStaff().stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val user = MutableStateFlow<Staff?>(null)
    val shift: StateFlow<Shift?> = db.sales().openShift().stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val pendingSync: StateFlow<Int> = db.admin().pendingCount().stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    val lastSync: StateFlow<String?> = db.admin().settingFlow("last_sync_at").stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val syncError: StateFlow<String?> = db.admin().settingFlow("last_sync_error").stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private fun fail(e: Throwable) { toast.value = e.message ?: "Something went wrong" }
    private fun go(block: suspend () -> Unit) = viewModelScope.launch { try { block() } catch (e: Exception) { fail(e) } }
    fun can(p: Perm) = user.value?.let { Rbac.can(it.role, p) } ?: false

    val showTips = MutableStateFlow(false)
    private suspend fun checkTips() { if (db.admin().setting("tips_seen") != "1") showTips.value = true }
    fun finishTips() { showTips.value = false; go { db.admin().putSetting(AppSetting("tips_seen", "1")) } }
    fun replayTips() { showTips.value = true }

    fun setupOwner(name: String, pin: String) = go { user.value = c.auth.createStaff(name, Role.OWNER, pin, null); checkTips() }
    fun login(s: Staff, pin: String) = go { user.value = c.auth.authenticate(s.id, pin); clearCart(); license.value = c.license.state(); checkTips() }
    fun logout() { user.value = null; clearCart() }          // unsynced sales stay in the DB/outbox; logout never deletes data
    fun addStaff(name: String, role: Role, pin: String) = go { c.auth.createStaff(name, role, pin, user.value!!.id); toast.value = "Staff added" }

    // ---- approval (owner PIN) ----
    val approvalNeeded = MutableStateFlow<String?>(null)
    private var approverId: String? = null
    private var retry: (() -> Unit)? = null
    fun approve(pin: String) = go {
        val owner = db.admin().activeStaffNow().filter { Rbac.can(it.role, Perm.DISCOUNT) && Rbac.can(it.role, Perm.CREDIT_OVERRIDE) }
            .firstNotNullOfOrNull { try { c.auth.authenticate(it.id, pin) } catch (e: PosException.Auth) { null } }
        if (owner == null) toast.value = "Wrong owner PIN" else { approverId = owner.id; approvalNeeded.value = null; retry?.invoke() }
    }

    // ---- catalog / search ----
    val query = MutableStateFlow("")
    val results: StateFlow<List<Product>> = query.flatMapLatest { q ->
        if (q.isBlank()) db.catalog().favorites() else db.catalog().search(q.trim())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val allProducts = db.catalog().all().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val lowStock = db.catalog().lowStock().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val units: StateFlow<Map<String, List<ProductUnit>>> = db.catalog().allUnits().map { l -> l.groupBy { it.productId } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    val suppliers = db.catalog().suppliers().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Enter on a keyboard-wedge (USB/Bluetooth) scanner submits the barcode as text + Enter. */
    fun submitSearch() = go {
        val q = query.value.trim()
        if (q.isEmpty()) return@go
        val p = db.catalog().byBarcode(q) ?: results.value.singleOrNull()
        if (p != null) { tryAdd(p); query.value = "" } else toast.value = "No match for \"$q\""
    }

    // ---- cart ----
    val cart = MutableStateFlow<List<UiLine>>(emptyList())
    val discount = MutableStateFlow(0L)
    val customerId = MutableStateFlow<String?>(null)
    val lastReceipt = MutableStateFlow<String?>(null)
    private var txId = UUID.randomUUID().toString()

    fun addProduct(p: Product) {
        val cur = cart.value
        val i = cur.indexOfFirst { it.product.id == p.id && it.unitName == p.baseUnit }
        cart.value = if (i >= 0) cur.mapIndexed { n, l -> if (n == i) l.copy(qtyMilli = l.qtyMilli + 1000) else l }
        else cur + UiLine(p, p.baseUnit, 1000, p.priceCentavos, 1000)
    }
    fun setQty(i: Int, milli: Long) { cart.value = cart.value.mapIndexed { n, l -> if (n == i) l.copy(qtyMilli = milli) else l } }
    fun removeLine(i: Int) { cart.value = cart.value.filterIndexed { n, _ -> n != i } }
    fun cycleUnit(i: Int) {
        val l = cart.value[i]; val opts = listOf(l.product.baseUnit) + (units.value[l.product.id]?.map { it.unitName } ?: emptyList())
        val next = opts[(opts.indexOf(l.unitName) + 1) % opts.size]
        val u = units.value[l.product.id]?.firstOrNull { it.unitName == next }
        cart.value = cart.value.mapIndexed { n, x ->
            if (n != i) x else if (u == null) x.copy(unitName = l.product.baseUnit, factorMilli = 1000, unitPrice = l.product.priceCentavos)
            else x.copy(unitName = u.unitName, factorMilli = u.factorMilli, unitPrice = u.priceCentavos ?: Qty.lineTotal(l.product.priceCentavos, u.factorMilli))
        }
    }
    fun clearCart() { cart.value = emptyList(); discount.value = 0; customerId.value = null; approverId = null; txId = UUID.randomUUID().toString() }
    val subtotal get() = cart.value.sumOf { it.total }

    fun checkout(payments: List<PaymentInput>, credit: Long) {
        if (busy.value) return                                   // blocks double-tap; txId also makes retries idempotent
        val u = user.value ?: return; val s = shift.value
        if (s == null) { toast.value = "Open a shift first (Shift tab)"; return }
        val req = CheckoutRequest(txId, u.id, s.id, customerId.value, cart.value.map { CartLineInput(it.product.id, it.unitName, it.qtyMilli) },
            discount.value, payments, credit, approverId, allows(Feature.PROMOS))
        busy.value = true
        viewModelScope.launch {
            try {
                val r = c.sales.checkout(req)
                val text = ReceiptFormatter.format(buildReceipt(db, r.sale.id, allows(Feature.RECEIPT_BRANDING)), (db.admin().setting("receipt_width") ?: "32").toInt())
                lastReceipt.value = text + (if (r.changeCentavos > 0) "\nCHANGE DUE: ${Money.format(r.changeCentavos)}" else "")
                clearCart()
            } catch (e: PosException.PermissionDenied) { approvalNeeded.value = e.message; retry = { checkout(payments, credit) }
            } catch (e: PosException.CreditLimitExceeded) { approvalNeeded.value = e.message; retry = { checkout(payments, credit) }
            } catch (e: Exception) { fail(e) } finally { busy.value = false }
        }
    }
    fun printReceipt(text: String, drawer: Boolean) = go {
        val p = c.printer() ?: run { toast.value = "No printer set (Settings). Showing preview only."; return@go }
        p.print(text, drawer).onFailure { toast.value = "Printer error: ${it.message}. Sale is saved; reprint from History." }
    }

    // ---- products & inventory ----
    fun saveProduct(p: Product, extra: List<ProductUnit>) = go { c.inventory.saveProduct(p, extra, user.value!!.id); toast.value = "Saved ${p.name}" }
    fun receive(productId: String, unit: String?, qty: Long, cost: Long, supplier: String?, ref: String?, expiry: String? = null, batch: String? = null) =
        go { c.inventory.receiveStock(productId, unit, qty, cost, supplier, ref, user.value!!.id, null, expiry, batch); toast.value = "Stock received" }
    fun adjust(productId: String, type: MoveType, delta: Long, reason: String) =
        go { c.inventory.adjust(productId, type, delta, reason, user.value!!.id); toast.value = "Stock adjusted" }
    fun saveSupplier(name: String, phone: String?) = go { db.catalog().upsertSupplier(Supplier(UUID.randomUUID().toString(), name, phone, null, true, System.currentTimeMillis())) }

    // ---- customers / Utang ----
    val customers = db.admin().customers().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val balances: StateFlow<Map<String, Long>> = db.sales().balances().map { l -> l.associate { it.customerId to it.balance } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    fun saveCustomer(id: String?, name: String, phone: String?, limit: Long) = go {
        val now = System.currentTimeMillis()
        c.sales.saveCustomer(Customer(id ?: UUID.randomUUID().toString(), name.trim(), phone, null, limit, true, now, now), user.value!!.id)
    }
    fun collect(customerId: String, method: PaymentMethod, amount: Long, ref: String?) = go {
        val s = shift.value ?: throw PosException.Invalid("Open a shift first")
        c.sales.recordDebtPayment(UUID.randomUUID().toString(), customerId, method, amount, ref, user.value!!.id, s.id)
        toast.value = "Payment recorded"
    }
    suspend fun statement(customerId: String): List<StatementRow> {
        var bal = 0L
        return db.sales().ledger(customerId).map {
            bal += it.amountCentavos
            val receipt = it.saleId?.let { s -> db.sales().sale(s)?.receiptNumber }
            StatementRow(Manila.fmt(it.createdAt), "${it.type}${receipt?.let { r -> " $r" } ?: ""}", it.amountCentavos, bal)
        }
    }

    // ---- shift / Z-read ----
    fun openShift(float: Long) = go { c.shifts.open(user.value!!.id, float) }
    fun cashMove(type: CashMoveType, amount: Long, reason: String) = go { c.shifts.cashMove(shift.value!!.id, type, amount, reason, user.value!!.id); toast.value = "Recorded" }
    suspend fun zPreview(counted: Long?): ZRead? = shift.value?.let { c.shifts.preview(it.id, counted) }
    val zText = MutableStateFlow<String?>(null)
    fun closeShift(counted: Long) = go { zText.value = c.shifts.close(shift.value!!.id, counted, user.value!!.id) }

    // ---- reports ----
    private val day = MutableStateFlow(Manila.dayRange(System.currentTimeMillis()))
    fun refreshDay() { day.value = Manila.dayRange(System.currentTimeMillis()) }
    val today: StateFlow<DayTotals> = day.flatMapLatest { db.sales().dayTotals(it.first, it.last) }.stateIn(viewModelScope, SharingStarted.Eagerly, DayTotals(0, 0))
    val profit: StateFlow<Long> = day.flatMapLatest { db.sales().grossProfit(it.first, it.last) }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    val recentSales = db.sales().recentSales(30).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    fun voidSale(id: String, reason: String) = go { c.sales.voidSale(id, user.value!!.id, reason); toast.value = "Sale voided" }
    fun reprint(id: String) = go { lastReceipt.value = ReceiptFormatter.format(buildReceipt(db, id, allows(Feature.RECEIPT_BRANDING)), (db.admin().setting("receipt_width") ?: "32").toInt()) }
    fun productsCsv(): String = ProductCsv.export(allProducts.value)

    // ---- scanning ----
    val expiryWarnDays: StateFlow<Int> = db.admin().settingFlow("expiry_warn_days").map { it?.toIntOrNull()?.coerceIn(1, 365) ?: 30 }.stateIn(viewModelScope, SharingStarted.Eagerly, 30)
    /** Expired + expiring-soon products that still have stock (re-queried when the warning window changes). */
    val expiring: StateFlow<List<Product>> = expiryWarnDays.flatMapLatest { d -> db.catalog().expiring(Expiry.today().plusDays(d.toLong()).toString()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Why a product cannot go in the cart (null = fine). The service layer re-checks expiry at checkout. */
    fun blockReason(p: Product): String? = when {
        p.stockMilli <= 0 -> "${p.name} is out of stock"
        (Expiry.daysLeft(p.expiryDate) ?: 0) < 0 -> "${p.name} expired on ${p.expiryDate} — take it off the shelf"
        else -> null
    }
    fun tryAdd(p: Product): Boolean { val why = blockReason(p); if (why != null) toast.value = why else addProduct(p); return why == null }
    fun writeOffExpired(p: Product) = go { c.inventory.adjust(p.id, MoveType.EXPIRED, -p.stockMilli, "Expired ${p.expiryDate}", user.value!!.id); toast.value = "Written off ${p.name}" }

    /** Camera / keyboard barcode -> (product, reason it was not added). Unknown barcode = (null, null). */
    suspend fun scanBarcode(code: String): Pair<Product?, String?> {
        val p = db.catalog().byBarcode(code.trim()) ?: return null to null
        val why = blockReason(p); if (why == null) addProduct(p); return p to why
    }
    val categories: StateFlow<List<String>> = allProducts.map { l -> l.mapNotNull { it.category?.takeIf { c -> c.isNotBlank() } }.distinct().sorted() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ---- purchasing ----
    val purchaseOrders = db.catalog().pos().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    fun poLines(id: String) = db.catalog().poLines(id)
    fun createPo(supplierId: String?, lines: List<NewPoLine>, notes: String?) = go { c.purchasing.createDraft(supplierId, lines, notes, user.value!!.id); toast.value = "Draft order created" }
    fun createSuggestedPo(supplierId: String?) = go {
        val l = c.purchasing.suggestFromLowStock()
        if (l.isEmpty()) toast.value = "Nothing is below its reorder level" else { c.purchasing.createDraft(supplierId, l, "Auto-suggested from low stock", user.value!!.id); toast.value = "Draft with ${l.size} items created" }
    }
    fun orderPo(id: String) = go { c.purchasing.markOrdered(id, user.value!!.id); toast.value = "Marked as ordered" }
    fun cancelPo(id: String) = go { c.purchasing.cancel(id, user.value!!.id); toast.value = "Order cancelled" }
    fun receivePo(id: String, qty: Map<String, Long>, ref: String?) = go { c.purchasing.receive(id, qty, ref, user.value!!.id); toast.value = "Stock received" }
    suspend fun poText(id: String) = c.purchasing.asText(db.admin().setting("store_name") ?: "Store", id)
    suspend fun supplierName(id: String?): String = id?.let { db.catalog().supplier(it)?.name } ?: "No supplier"

    // ---- CSV import / bulk ----
    data class ImportPreview(val rows: List<ImportRow>, val parseErrors: List<String>, val report: ImportReport)
    val importPreview = MutableStateFlow<ImportPreview?>(null)
    fun previewImport(text: String) = go {
        val (rows, errs) = ProductCsv.parse(text)
        importPreview.value = ImportPreview(rows, errs, c.inventory.importProducts(rows, user.value!!.id, apply = false))
    }
    fun applyImport() = go {
        val p = importPreview.value ?: return@go
        val r = c.inventory.importProducts(p.rows, user.value!!.id, apply = true)
        importPreview.value = null; toast.value = "Imported: ${r.created} new, ${r.updated} updated, ${r.stockChanged} stock changes"
    }
    fun bulkPrice(ids: List<String>, mode: BulkPriceMode, value: Long) = go { toast.value = "${c.inventory.bulkPrice(ids, mode, value, user.value!!.id)} prices updated" }
    fun bulkAdjust(deltas: Map<String, Long>, reason: String) = go { toast.value = "${c.inventory.bulkAdjust(deltas, reason, user.value!!.id)} products adjusted" }
    fun bulkCounts(counts: Map<String, Long>) = go { toast.value = "${c.inventory.bulkSetCounts(counts, user.value!!.id)} counts saved" }

    // ---- reminders ----
    suspend fun prepareReminder(customerId: String, channel: Channel) = c.reminders.prepare(customerId, channel)
    fun logReminder(customerId: String, channel: Channel) = go { c.reminders.log(customerId, channel, user.value!!.id) }

    // ---- payment QR codes (display only; payments are never auto-verified) ----
    private fun qrKey(m: PaymentMethod) = "qr_${m.name}"
    val qrVersion = MutableStateFlow(0)
    suspend fun qrBitmap(m: PaymentMethod): ImageBitmap? = withContext(Dispatchers.Default) {
        val b64 = db.admin().setting(qrKey(m))?.takeIf { it.isNotBlank() } ?: return@withContext null
        val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }
    suspend fun walletInfo(m: PaymentMethod) = WalletInfo(db.admin().setting("wallet_${m.name}_number") ?: "", db.admin().setting("wallet_${m.name}_name") ?: "")
    fun saveWalletInfo(m: PaymentMethod, number: String, name: String) = go {
        if (!can(Perm.MANAGE_STAFF)) throw PosException.PermissionDenied(Perm.MANAGE_STAFF)
        val digits = number.filter { it.isDigit() }
        if (digits.isNotEmpty() && Reminders.normalizePhone(digits) == null) throw PosException.Invalid("Enter an 11-digit mobile number like 0917 123 4567")
        db.admin().putSetting(AppSetting("wallet_${m.name}_number", digits)); db.admin().putSetting(AppSetting("wallet_${m.name}_name", name.trim()))
        qrVersion.value++; toast.value = "${m.name} details saved"
    }
    fun saveQr(m: PaymentMethod, uri: Uri) = go {
        if (!can(Perm.MANAGE_STAFF)) throw PosException.PermissionDenied(Perm.MANAGE_STAFF)
        val (b64, found) = QrImport.process(c.ctx, uri)
        db.admin().putSetting(AppSetting(qrKey(m), b64)); qrVersion.value++
        toast.value = if (found) "${m.name} QR saved" else "Saved, but no QR code was detected in that picture — please check it"
    }
    fun removeQr(m: PaymentMethod) = go {
        if (!can(Perm.MANAGE_STAFF)) throw PosException.PermissionDenied(Perm.MANAGE_STAFF)
        db.admin().putSetting(AppSetting(qrKey(m), "")); qrVersion.value++
    }

    // ---- Google Drive backup (owner's own Drive, appDataFolder) ----
    val driveConnected: StateFlow<Boolean> = db.admin().settingFlow("drive_connected").map { it == "1" }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val driveAuto: StateFlow<Boolean> = db.admin().settingFlow("drive_auto").map { it != "0" }.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val lastDriveBackup: StateFlow<String?> = db.admin().settingFlow("last_drive_backup_at").stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val driveError: StateFlow<String?> = db.admin().settingFlow("last_drive_error").stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private fun requireOwner() { if (!can(Perm.RESTORE)) throw PosException.PermissionDenied(Perm.RESTORE) }
    fun setDriveConnected(ok: Boolean, passphrase: String) = go {
        requireOwner()
        db.admin().putSetting(AppSetting("drive_connected", if (ok) "1" else "0"))
        if (ok) { db.admin().putSetting(AppSetting("backup_pass", passphrase)); toast.value = "Google Drive connected" }
    }
    fun setDriveAuto(on: Boolean) = go { requireOwner(); db.admin().putSetting(AppSetting("drive_auto", if (on) "1" else "0")) }
    fun driveBackupNow() = go { requireOwner(); toast.value = "Backing up…"; toast.value = DriveBackup.backupNow(c) ?: "Backed up to your Google Drive ✓" }
    suspend fun driveList(): List<DriveFile> { requireOwner(); return DriveBackup.list(c) }
    fun driveRestore(f: DriveFile, pass: String, done: () -> Unit) = go { requireOwner(); DriveBackup.restore(c, f.id, pass, user.value!!.id); done() }

    // ---- GCash / load services ----
    val serviceDay: StateFlow<ServiceSums> = day.flatMapLatest { db.sales().serviceDay(it.first, it.last) }.stateIn(viewModelScope, SharingStarted.Eagerly, ServiceSums(0, 0, 0, 0))
    val recentServices = db.sales().recentServices(40).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    fun recordService(type: ServiceType, provider: String, amount: Long, fee: Long, feeInCash: Boolean, customer: String?, ref: String?, done: () -> Unit) = go {
        val s = shift.value ?: throw PosException.Invalid("Open a shift first (Shift tab)")
        c.services.record(UUID.randomUUID().toString(), s.id, user.value!!.id, type, provider, amount, fee, feeInCash, customer, ref); toast.value = "Recorded ✓"; done()
    }
    fun voidService(id: String, reason: String) = go { c.services.void(id, reason, user.value!!.id); toast.value = "Voided" }

    // ---- insights ----
    val insightDays = MutableStateFlow(30)
    private val insightRange = insightDays.map { d -> val n = System.currentTimeMillis(); (n - d * 86_400_000L) to n }
    val dailySales = insightRange.flatMapLatest { db.sales().dailySales(it.first, it.second) }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val dailyProfit = insightRange.flatMapLatest { db.sales().dailyProfit(it.first, it.second) }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val topProducts = insightRange.flatMapLatest { db.sales().topProducts(it.first, it.second, 10) }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val slowMovers = insightDays.flatMapLatest { d -> db.catalog().slowMovers(System.currentTimeMillis() - d * 86_400_000L, 10) }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    suspend fun smartSuggestions(): List<ReorderSuggestion> = c.purchasing.suggestSmart()
    fun createSmartPo(l: List<ReorderSuggestion>) = go {
        if (!need(Feature.SMART_REORDER)) return@go
        c.purchasing.createDraft(null, l.map { NewPoLine(it.product.id, null, it.suggestMilli, it.product.costCentavos) }, "Smart reorder (based on recent sales)", user.value!!.id)
        toast.value = "Draft order created — see Purchase orders"
    }
    suspend fun agingRows(): List<AgingRow> = UtangAging.compute(db.sales().allLedger(), System.currentTimeMillis())

    // ---- plan & license ----
    val license = MutableStateFlow(LicenseState(Plan.UNRESTRICTED))
    /** Live promo savings shown in the cart. The service layer recomputes the same thing at checkout, so this is only a preview. */
    val activePromos = db.catalog().activePromosFlow().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val promoPreview: StateFlow<PromoResult> = combine(cart, activePromos, license) { lines, promos, lic ->
        if (!lic.allows(Feature.PROMOS)) PromoResult(0, emptyList())
        else PromoEngine.compute(lines.filter { it.unitName == it.product.baseUnit }.map { PromoLine(it.product.id, it.qtyMilli, it.unitPrice) }, promos, Expiry.today().toString())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, PromoResult(0, emptyList()))
    val paywall = MutableStateFlow<Feature?>(null)
    fun refreshLicense() = go { license.value = c.license.state() }
    fun allows(f: Feature) = license.value.allows(f)
    /** true = go ahead; false = the paywall dialog has been opened. Selling itself is never gated. */
    fun need(f: Feature): Boolean { if (allows(f)) return true; paywall.value = f; return false }
    fun activateLicense(token: String) = go { license.value = c.license.activate(token); paywall.value = null; toast.value = "License activated ✓" }
    init { refreshLicense() }

    // ---- promos & bundles ----
    val promos = db.catalog().allPromos().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    fun savePromo(p: Promo) = go { if (!can(Perm.MANAGE_CATALOG)) throw PosException.PermissionDenied(Perm.MANAGE_CATALOG); db.catalog().upsertPromo(p); toast.value = "Promo saved" }

    // ---- supplier payables ----
    val billRows = db.catalog().billRows().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    fun addBill(supplierId: String?, ref: String?, amount: Long, due: String?, note: String?, done: () -> Unit) = go { c.payables.addBill(supplierId, null, ref, amount, due, note, user.value!!.id); toast.value = "Bill added"; done() }
    fun billFromPo(poId: String, due: String?) = go { c.payables.billFromPo(poId, due, user.value!!.id); toast.value = "Bill created from the order" }
    fun payBill(id: String, amount: Long, method: String) = go { c.payables.pay(id, amount, method, user.value!!.id); toast.value = "Payment recorded" }
    fun cancelBill(id: String) = go { c.payables.cancel(id, user.value!!.id); toast.value = "Bill cancelled" }

    // ---- labels ----
    fun assignBarcodes(ids: List<String>) = go { toast.value = "${c.labels.assignBarcodes(ids, user.value!!.id)} in-store barcodes assigned" }
    fun printLabels(items: List<Pair<LabelItem, Int>>) = go {
        val p = c.printer() ?: throw PosException.Invalid("Choose a printer in Settings first")
        p.printRaw(LabelEscPos.build(items)).fold({ toast.value = "Labels sent to printer" }, { toast.value = "Printer error: ${it.message}" })
    }
    fun labelsPdf(uri: Uri, items: List<Pair<LabelItem, Int>>) = go {
        withContext(Dispatchers.IO) { c.ctx.contentResolver.openOutputStream(uri, "wt")!!.use { LabelSheetPdf.write(items, it) } }; toast.value = "Label sheet saved"
    }

    // ---- PDF reports ----
    fun exportPdf(kind: String, uri: Uri, fromMs: Long, toMs: Long, customerId: String?) = go {
        val rb = ReportBuilder(db)
        val doc = when (kind) { "sales" -> rb.sales(fromMs, toMs); "inventory" -> rb.inventory(); "receivables" -> rb.receivables(); "payables" -> rb.payables()
            else -> rb.customerStatement(customerId ?: throw PosException.Invalid("Choose a customer")) }
        withContext(Dispatchers.IO) { c.ctx.contentResolver.openOutputStream(uri, "wt")!!.use { PdfWriter.write(doc, it) } }; toast.value = "PDF saved ✓"
    }

    // ---- hardware ----
    fun testPrint() = go {
        val p = c.printer() ?: run { toast.value = "Choose a printer first"; return@go }
        p.print(Hardware.TEST_TEXT, openDrawer = true).fold({ toast.value = "Test sent to printer" }, { toast.value = "Printer error: ${it.message}" })
    }
    fun openDrawer() = go {
        val p = c.printer() ?: run { toast.value = "Choose a printer first"; return@go }
        p.print("", openDrawer = true).onFailure { toast.value = "Drawer error: ${it.message}" }
    }

    // ---- settings ----
    suspend fun setting(k: String) = db.admin().setting(k) ?: ""
    fun putSetting(k: String, v: String) = go {
        db.requirePermPublic(user.value!!.id)
        val old = db.admin().setting(k); db.admin().putSetting(AppSetting(k, v))
        if (k == "store_name" && v.isNotBlank() && v != old) db.enqueue("STORE", "info", "INFO", JSONObject().put("name", v), System.currentTimeMillis())   // lets the owner web view show the shop name
    }
}

private suspend fun AppDatabase.requirePermPublic(staffId: String) {
    val s = admin().staff(staffId)
    if (s == null || !Rbac.can(s.role, Perm.MANAGE_STAFF)) throw PosException.PermissionDenied(Perm.MANAGE_STAFF)
}
