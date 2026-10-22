package ph.sarisari.mpos.sync

import org.json.JSONArray
import org.json.JSONObject
import ph.sarisari.mpos.data.*

/** Full-fidelity JSON for every row that crosses devices. Field names == column names. */
object SyncCodec {
    private fun JSONObject.str(k: String): String? = if (isNull(k)) null else getString(k)
    private fun JSONObject.lng(k: String): Long? = if (isNull(k)) null else getLong(k)
    private fun <T> arr(a: JSONArray?, f: (JSONObject) -> T): List<T> = if (a == null) emptyList() else (0 until a.length()).map { f(a.getJSONObject(it)) }

    fun product(p: Product, units: List<ProductUnit>) = JSONObject()
        .put("id", p.id).put("sku", p.sku).put("barcode", p.barcode).put("name", p.name).put("category", p.category)
        .put("cost", p.costCentavos).put("price", p.priceCentavos).put("baseUnit", p.baseUnit).put("fraction", p.allowsFraction)
        .put("reorder", p.reorderMilli).put("favorite", p.favorite).put("active", p.active).put("createdAt", p.createdAt).put("updatedAt", p.updatedAt)
        .put("units", JSONArray(units.map { JSONObject().put("name", it.unitName).put("factor", it.factorMilli).put("price", it.priceCentavos) }))

    fun toProduct(j: JSONObject, stock: Long) = Product(j.getString("id"), j.str("sku"), j.str("barcode"), j.getString("name"), j.str("category"),
        j.getLong("cost"), j.getLong("price"), stock, j.getString("baseUnit"), j.getBoolean("fraction"), j.getLong("reorder"), null,
        j.getBoolean("favorite"), j.getBoolean("active"), null, null, j.getLong("createdAt"), j.getLong("updatedAt"))

    fun toUnits(j: JSONObject): List<ProductUnit> = arr(j.optJSONArray("units")) { ProductUnit(j.getString("id"), it.getString("name"), it.getLong("factor"), it.lng("price")) }

    fun customer(c: Customer) = JSONObject().put("id", c.id).put("name", c.name).put("phone", c.phone).put("limit", c.creditLimitCentavos)
        .put("active", c.active).put("createdAt", c.createdAt).put("updatedAt", c.updatedAt)
    fun toCustomer(j: JSONObject) = Customer(j.getString("id"), j.getString("name"), j.str("phone"), null, j.getLong("limit"), j.getBoolean("active"), j.getLong("createdAt"), j.getLong("updatedAt"))

    fun sale(s: Sale) = JSONObject().put("id", s.id).put("receipt", s.receiptNumber).put("customerId", s.customerId).put("cashierId", s.cashierId).put("shiftId", s.shiftId)
        .put("subtotal", s.subtotalCentavos).put("discount", s.discountCentavos).put("total", s.totalCentavos).put("credit", s.creditCentavos)
        .put("status", s.status.name).put("createdAt", s.createdAt).put("voidReason", s.voidReason).put("voidedAt", s.voidedAt).put("voidShiftId", s.voidShiftId)
    fun toSale(j: JSONObject) = Sale(j.getString("id"), j.getString("receipt"), j.str("customerId"), j.getString("cashierId"), j.getString("shiftId"),
        j.getLong("subtotal"), j.getLong("discount"), j.getLong("total"), j.getLong("credit"), SaleStatus.valueOf(j.getString("status")), j.getLong("createdAt"),
        j.str("voidReason"), j.lng("voidedAt"), j.str("voidShiftId"))

    fun item(i: SaleItem) = JSONObject().put("id", i.id).put("saleId", i.saleId).put("productId", i.productId).put("name", i.productNameSnapshot).put("unit", i.unitSnapshot)
        .put("qty", i.qtyMilli).put("price", i.unitPriceCentavos).put("subtotal", i.subtotalCentavos).put("factor", i.conversionFactorMilli).put("base", i.baseQtyMilli).put("cost", i.costCentavosSnapshot)
    fun toItem(j: JSONObject) = SaleItem(j.getString("id"), j.getString("saleId"), j.getString("productId"), j.getString("name"), j.getString("unit"), j.getLong("qty"),
        j.getLong("price"), j.getLong("subtotal"), j.getLong("factor"), j.getLong("base"), j.getLong("cost"))

    fun payment(p: Payment) = JSONObject().put("id", p.id).put("saleId", p.saleId).put("customerId", p.customerId).put("shiftId", p.shiftId).put("kind", p.kind.name)
        .put("method", p.method.name).put("amount", p.amountCentavos).put("tendered", p.tenderedCentavos).put("status", p.status.name).put("ref", p.reference).put("createdAt", p.createdAt)
    fun toPayment(j: JSONObject) = Payment(j.getString("id"), j.str("saleId"), j.str("customerId"), j.getString("shiftId"), PaymentKind.valueOf(j.getString("kind")),
        PaymentMethod.valueOf(j.getString("method")), j.getLong("amount"), j.lng("tendered"), PaymentStatus.valueOf(j.getString("status")), j.str("ref"), j.getLong("createdAt"))

    fun ledger(e: LedgerEntry) = JSONObject().put("id", e.id).put("customerId", e.customerId).put("saleId", e.saleId).put("paymentId", e.paymentId).put("shiftId", e.shiftId)
        .put("type", e.type.name).put("amount", e.amountCentavos).put("notes", e.notes).put("createdAt", e.createdAt)
    fun toLedger(j: JSONObject) = LedgerEntry(j.getString("id"), j.getString("customerId"), j.str("saleId"), j.str("paymentId"), j.str("shiftId"),
        LedgerType.valueOf(j.getString("type")), j.getLong("amount"), j.str("notes"), j.getLong("createdAt"))

    fun move(m: InventoryMovement) = JSONObject().put("id", m.id).put("productId", m.productId).put("type", m.type.name).put("delta", m.qtyDeltaMilli)
        .put("ref", m.referenceId).put("staffId", m.staffId).put("notes", m.notes).put("createdAt", m.createdAt)
    fun toMove(j: JSONObject) = InventoryMovement(j.getString("id"), j.getString("productId"), MoveType.valueOf(j.getString("type")), j.getLong("delta"),
        j.str("ref"), j.getString("staffId"), j.str("notes"), j.getLong("createdAt"))

    fun receipt(r: StockReceipt) = JSONObject().put("id", r.id).put("supplierId", r.supplierId).put("productId", r.productId).put("unit", r.unitName).put("qty", r.qtyMilli)
        .put("factor", r.conversionFactorMilli).put("base", r.baseQtyMilli).put("cost", r.unitCostCentavos).put("ref", r.reference).put("staffId", r.staffId).put("createdAt", r.createdAt).put("poId", r.poId)
    fun toReceipt(j: JSONObject) = StockReceipt(j.getString("id"), j.str("supplierId"), j.getString("productId"), j.getString("unit"), j.getLong("qty"), j.getLong("factor"),
        j.getLong("base"), j.getLong("cost"), j.str("ref"), j.getString("staffId"), j.getLong("createdAt"), j.str("poId"))

    fun saleBundle(s: Sale, items: List<SaleItem>, pays: List<Payment>, ledgerRows: List<LedgerEntry>, moves: List<InventoryMovement>) = JSONObject()
        .put("sale", sale(s)).put("items", JSONArray(items.map { item(it) })).put("payments", JSONArray(pays.map { payment(it) }))
        .put("ledger", JSONArray(ledgerRows.map { ledger(it) })).put("moves", JSONArray(moves.map { move(it) }))

    fun <T> list(j: JSONObject, key: String, f: (JSONObject) -> T): List<T> = arr(j.optJSONArray(key), f)
}
