package ph.sarisari.mpos.domain

import androidx.room.withTransaction
import ph.sarisari.mpos.data.*
import ph.sarisari.mpos.util.Expiry
import ph.sarisari.mpos.util.Manila
import ph.sarisari.mpos.util.Money
import ph.sarisari.mpos.util.Qty
import java.util.UUID

data class NewPoLine(val productId: String, val unitName: String?, val qtyMilli: Long, val unitCostCentavos: Long)

class PurchasingService(private val db: AppDatabase, private val inventory: InventoryService, private val clock: () -> Long = { System.currentTimeMillis() }) {

    /** Reorder suggestion: bring each low-stock product back up to 2x its reorder level (in base units). */
    suspend fun suggestFromLowStock(): List<NewPoLine> = db.catalog().lowStockNow().mapNotNull {
        val need = it.reorderMilli * 2 - it.stockMilli
        if (need > 0) NewPoLine(it.id, null, need, it.costCentavos) else null
    }

    /** Based on how fast each product actually sells (last 30 days). */
    suspend fun suggestSmart(now: Long = System.currentTimeMillis()): List<ReorderSuggestion> {
        val sold = db.sales().soldQty(now - 30 * 86_400_000L).associate { it.productId to it.qtyMilli }
        return SmartReorder.suggest(db.catalog().activeNow(), sold)
    }

    suspend fun createDraft(supplierId: String?, lines: List<NewPoLine>, notes: String?, staffId: String): PurchaseOrder = db.withTransaction {
        val staff = db.requirePerm(staffId, Perm.STOCK_ADJUST)
        if (lines.isEmpty()) throw PosException.Invalid("Add at least one item")
        val now = clock()
        val po = PurchaseOrder(UUID.randomUUID().toString(), supplierId, PoStatus.DRAFT, notes, staff.id, now, null, now)
        val rows = lines.map { l ->
            val p = db.catalog().get(l.productId) ?: throw PosException.Invalid("Unknown product")
            if (l.qtyMilli <= 0) throw PosException.Invalid("Quantity must be positive")
            val (unit, factor) = if (l.unitName == null || l.unitName == p.baseUnit) p.baseUnit to 1000L
            else (db.catalog().unit(p.id, l.unitName) ?: throw PosException.Invalid("Unknown unit")).let { it.unitName to it.factorMilli }
            PurchaseOrderLine(UUID.randomUUID().toString(), po.id, p.id, p.name, unit, l.qtyMilli, 0, l.unitCostCentavos, factor)
        }
        db.catalog().insertPo(po); db.catalog().insertPoLines(rows)
        db.audit(staff.id, "PO_CREATE", "purchase_order", po.id, null, now)
        po
    }

    suspend fun markOrdered(poId: String, staffId: String) = db.withTransaction {
        val staff = db.requirePerm(staffId, Perm.STOCK_ADJUST)
        val po = db.catalog().po(poId) ?: throw PosException.Invalid("PO not found")
        if (po.status != PoStatus.DRAFT) throw PosException.Invalid("Only a draft can be marked as ordered")
        db.catalog().updatePo(po.copy(status = PoStatus.ORDERED, orderedAt = clock(), updatedAt = clock()))
        db.audit(staff.id, "PO_ORDERED", "purchase_order", poId, null, clock())
    }

    suspend fun cancel(poId: String, staffId: String) = db.withTransaction {
        val staff = db.requirePerm(staffId, Perm.STOCK_ADJUST)
        val po = db.catalog().po(poId) ?: throw PosException.Invalid("PO not found")
        if (po.status == PoStatus.RECEIVED || po.status == PoStatus.CANCELLED) throw PosException.Invalid("Cannot cancel a ${po.status} order")
        db.catalog().updatePo(po.copy(status = PoStatus.CANCELLED, updatedAt = clock()))
        db.audit(staff.id, "PO_CANCEL", "purchase_order", poId, null, clock())
    }

    /** Partial receiving: qty per line (in that line's unit). Stock rises only here, and over-receiving is rejected. */
    suspend fun receive(poId: String, received: Map<String, Long>, reference: String?, staffId: String) = db.withTransaction {
        db.requirePerm(staffId, Perm.STOCK_ADJUST)
        val po = db.catalog().po(poId) ?: throw PosException.Invalid("PO not found")
        if (po.status != PoStatus.ORDERED && po.status != PoStatus.PARTIAL) throw PosException.Invalid("Mark the order as ordered before receiving")
        val lines = db.catalog().poLinesNow(poId).associateBy { it.id }
        for ((lineId, qty) in received) {
            if (qty <= 0) continue
            val l = lines[lineId] ?: throw PosException.Invalid("Unknown line")
            if (qty > l.qtyMilli - l.receivedMilli) throw PosException.Invalid("${l.productName}: only ${Qty.format(l.qtyMilli - l.receivedMilli)} ${l.unitName} still expected")
            inventory.receiveStock(l.productId, l.unitName, qty, l.unitCostCentavos, po.supplierId, reference ?: "PO ${poId.take(6)}", staffId, poId)
            db.catalog().updatePoLine(l.copy(receivedMilli = l.receivedMilli + qty))
        }
        val after = db.catalog().poLinesNow(poId)
        val status = if (after.all { it.receivedMilli >= it.qtyMilli }) PoStatus.RECEIVED else PoStatus.PARTIAL
        db.catalog().updatePo(po.copy(status = status, updatedAt = clock()))
    }

    suspend fun asText(store: String, poId: String): String {
        val po = db.catalog().po(poId)!!; val sup = po.supplierId?.let { db.catalog().supplier(it) }
        val lines = db.catalog().poLinesNow(poId)
        return buildString {
            appendLine("PURCHASE ORDER — $store"); appendLine("PO ${poId.take(6).uppercase()}  ${Manila.fmt(po.createdAt, "yyyy-MM-dd")}")
            sup?.let { appendLine("Supplier: ${it.name}") }; appendLine()
            lines.forEach { appendLine("• ${it.productName}: ${Qty.format(it.qtyMilli)} ${it.unitName}" + if (it.unitCostCentavos > 0) " @ ${Money.format(it.unitCostCentavos)}" else "") }
            val total = lines.sumOf { Qty.lineTotal(it.unitCostCentavos, it.qtyMilli) }
            if (total > 0) appendLine("\nEstimated total: ${Money.format(total)}")
            po.notes?.let { appendLine("Notes: $it") }
        }
    }
}

/** Minimal RFC-4180 style CSV reader/writer (quotes, escaped quotes, commas/newlines inside quotes, BOM). */
object Csv {
    fun parse(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>(); var row = mutableListOf<String>(); val f = StringBuilder(); var q = false; var i = 0
        val t = text.removePrefix("\uFEFF")
        while (i < t.length) {
            val c = t[i]
            if (q) { if (c == '"') { if (i + 1 < t.length && t[i + 1] == '"') { f.append('"'); i++ } else q = false } else f.append(c) }
            else when (c) {
                '"' -> q = true
                ',' -> { row.add(f.toString()); f.clear() }
                '\r' -> {}
                '\n' -> { row.add(f.toString()); f.clear(); rows.add(row); row = mutableListOf() }
                else -> f.append(c)
            }
            i++
        }
        if (f.isNotEmpty() || row.isNotEmpty()) { row.add(f.toString()); rows.add(row) }
        return rows.filter { r -> r.any { it.isNotBlank() } }
    }
    fun cell(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
}

object ProductCsv {
    const val HEADER = "sku,barcode,name,category,unit,cost,price,stock,reorder,expiry"   // expiry as yyyy-MM-dd
    /** Header names are case-insensitive; order is free. Returns rows plus per-line parse errors. */
    fun parse(text: String): Pair<List<ImportRow>, List<String>> {
        val table = Csv.parse(text); if (table.isEmpty()) return emptyList<ImportRow>() to listOf("File is empty")
        val h = table[0].map { it.trim().lowercase() }
        fun idx(vararg n: String) = n.firstNotNullOfOrNull { k -> h.indexOf(k).takeIf { it >= 0 } }
        val iSku = idx("sku"); val iBar = idx("barcode"); val iName = idx("name"); val iCat = idx("category"); val iUnit = idx("unit"); val iCost = idx("cost")
        val iPrice = idx("price"); val iStock = idx("stock"); val iRe = idx("reorder"); val iExp = idx("expiry", "expiry_date", "exp")
        if (iName == null && iBar == null && iSku == null) return emptyList<ImportRow>() to listOf("Header row needs at least name, barcode or sku")
        val errors = mutableListOf<String>(); val rows = mutableListOf<ImportRow>()
        table.drop(1).forEachIndexed { n, r ->
            val line = n + 2
            fun g(i: Int?) = i?.let { r.getOrNull(it)?.trim() }?.ifBlank { null }
            fun money(i: Int?, label: String): Long? { val v = g(i) ?: return null; return Money.parse(v) ?: run { errors += "Line $line: bad $label '$v'"; null } }
            fun qty(i: Int?, label: String): Long? { val v = g(i) ?: return null; return Qty.parse(v) ?: run { errors += "Line $line: bad $label '$v'"; null } }
            val before = errors.size
            val exp = g(iExp)?.let { v -> if (Expiry.parse(v) != null) v.trim() else { errors += "Line $line: bad expiry '$v' (use yyyy-MM-dd)"; null } }
            val row = ImportRow(line, g(iSku), g(iBar), g(iName), g(iCat), g(iUnit), money(iCost, "cost"), money(iPrice, "price"), qty(iStock, "stock"), qty(iRe, "reorder"), exp)
            if (errors.size == before) rows += row
        }
        return rows to errors
    }
    fun export(products: List<Product>): String = HEADER + "\n" + products.joinToString("\n") {
        listOf(it.sku ?: "", it.barcode ?: "", it.name, it.category ?: "", it.baseUnit, Money.format(it.costCentavos).removePrefix("₱").replace(",", ""),
            Money.format(it.priceCentavos).removePrefix("₱").replace(",", ""), Qty.format(it.stockMilli), Qty.format(it.reorderMilli), it.expiryDate ?: "").joinToString(",") { c -> Csv.cell(c) }
    }
}
