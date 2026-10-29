package ph.sarisari.mpos.domain

import androidx.room.withTransaction
import org.json.JSONArray
import org.json.JSONObject
import ph.sarisari.mpos.data.*
import ph.sarisari.mpos.sync.SyncCodec
import ph.sarisari.mpos.util.Expiry
import ph.sarisari.mpos.util.Manila
import ph.sarisari.mpos.util.Money
import ph.sarisari.mpos.util.Qty
import java.util.UUID

data class CartLineInput(val productId: String, val unitName: String?, val qtyMilli: Long)
data class PaymentInput(val method: PaymentMethod, val amountCentavos: Long, val reference: String? = null)

data class CheckoutRequest(
    val transactionId: String,              // generated once per cart; resubmitting the same id never creates a second sale
    val cashierId: String,
    val shiftId: String,
    val customerId: String?,
    val lines: List<CartLineInput>,
    val discountCentavos: Long = 0,
    val payments: List<PaymentInput> = emptyList(),   // cash amount = amount TENDERED
    val creditCentavos: Long = 0,
    val approverId: String? = null,                   // owner who approved a discount / credit-limit override
    val applyPromos: Boolean = false                  // Premium: apply active promos/bundles automatically
)

data class CheckoutResult(val sale: Sale, val payments: List<Payment>, val changeCentavos: Long, val duplicate: Boolean)

private data class Resolved(val product: Product, val unit: String, val factor: Long, val price: Long, val qty: Long, val base: Long, val total: Long)

class SalesService(private val db: AppDatabase, private val clock: () -> Long = { System.currentTimeMillis() }) {

    /** One Room transaction: any exception rolls back sale, items, payments, ledger, stock, movements and outbox. */
    suspend fun checkout(req: CheckoutRequest): CheckoutResult = db.withTransaction {
        val sales = db.sales(); val catalog = db.catalog(); val admin = db.admin()
        sales.sale(req.transactionId)?.let { return@withTransaction CheckoutResult(it, sales.paymentsForSale(it.id), 0, true) }
        if (req.lines.isEmpty()) throw PosException.InvalidCart("Cart is empty")
        val cashier = db.requirePerm(req.cashierId, Perm.SELL)
        val shift = sales.shift(req.shiftId)
        if (shift == null || shift.closedAt != null) throw PosException.InvalidCart("No open shift")
        val approver = req.approverId?.let { admin.staff(it) }?.takeIf { it.active }
        fun authorized(p: Perm) = Rbac.can(cashier.role, p) || (approver != null && Rbac.can(approver.role, p))
        val now = clock(); val saleId = req.transactionId

        val resolved = req.lines.map { l ->
            val p = catalog.get(l.productId)?.takeIf { it.active } ?: throw PosException.InvalidCart("Unknown or inactive product")
            Expiry.daysLeft(p.expiryDate)?.let { if (it < 0) throw PosException.InvalidCart("${p.name} expired on ${p.expiryDate} — take it off the shelf") }
            if (l.qtyMilli <= 0) throw PosException.InvalidCart("Quantity must be positive")
            if (!p.allowsFraction && l.qtyMilli % 1000 != 0L) throw PosException.InvalidCart("${p.name} cannot be sold in fractions")
            val unit: String; val factor: Long; val price: Long
            if (l.unitName == null || l.unitName == p.baseUnit) { unit = p.baseUnit; factor = 1000; price = p.priceCentavos }
            else {
                val u = catalog.unit(p.id, l.unitName) ?: throw PosException.InvalidCart("Unknown unit ${l.unitName}")
                unit = u.unitName; factor = u.factorMilli; price = u.priceCentavos ?: Qty.lineTotal(p.priceCentavos, u.factorMilli)
            }
            val base = Qty.toBase(l.qtyMilli, factor)
            if (base <= 0) throw PosException.InvalidCart("Invalid quantity")
            Resolved(p, unit, factor, price, l.qtyMilli, base, Qty.lineTotal(price, l.qtyMilli))
        }
        val subtotal = resolved.sumOf { it.total }
        val promo = if (req.applyPromos) PromoEngine.compute(resolved.filter { it.unit == it.product.baseUnit }.map { PromoLine(it.product.id, it.qty, it.price) }, catalog.activePromos(), Expiry.today().toString()) else PromoResult(0, emptyList())
        val promoDisc = promo.totalDiscount
        if (req.discountCentavos < 0 || req.discountCentavos > subtotal - promoDisc) throw PosException.InvalidCart("Invalid discount")
        if (req.discountCentavos > 0 && !authorized(Perm.DISCOUNT)) throw PosException.PermissionDenied(Perm.DISCOUNT)
        val total = subtotal - req.discountCentavos - promoDisc
        if (total <= 0) throw PosException.InvalidCart("Total must be above zero")

        // payments
        if (req.payments.any { it.amountCentavos <= 0 } || req.creditCentavos < 0) throw PosException.PaymentMismatch("Invalid payment amount")
        val cashTendered = req.payments.filter { it.method == PaymentMethod.CASH }.sumOf { it.amountCentavos }
        val digital = req.payments.filter { it.method != PaymentMethod.CASH }
        val digitalTotal = digital.sumOf { it.amountCentavos }
        if (digitalTotal + req.creditCentavos > total) throw PosException.PaymentMismatch("Digital + credit exceed the total")
        val cashDue = total - digitalTotal - req.creditCentavos
        if (cashTendered < cashDue) throw PosException.PaymentMismatch("Short by ${Money.format(cashDue - cashTendered)}")
        val change = cashTendered - cashDue

        var customerId: String? = null
        if (req.creditCentavos > 0) {
            val c = req.customerId?.let { admin.customer(it) }?.takeIf { it.active } ?: throw PosException.InvalidCart("Select a customer for Utang")
            customerId = c.id
            val bal = sales.balance(c.id)
            if (bal + req.creditCentavos > c.creditLimitCentavos) {
                if (!authorized(Perm.CREDIT_OVERRIDE)) throw PosException.CreditLimitExceeded(bal, c.creditLimitCentavos)
                db.audit(approver?.id ?: cashier.id, "CREDIT_LIMIT_OVERRIDE", "customer", c.id, "balance $bal + ${req.creditCentavos} > ${c.creditLimitCentavos}", now)
            }
        }
        if (req.discountCentavos > 0) db.audit(approver?.id ?: cashier.id, "DISCOUNT", "sale", saleId, Money.format(req.discountCentavos), now)
        if (promoDisc > 0) db.audit(cashier.id, "PROMO", "sale", saleId, promo.applied.joinToString() + " " + Money.format(promoDisc), now)

        val seq = (admin.setting("receipt_seq")?.toLongOrNull() ?: 0L) + 1
        admin.putSetting(AppSetting("receipt_seq", seq.toString()))
        val dev = (admin.setting("device_id") ?: "0000").take(4).uppercase()
        val receipt = "$dev-${Manila.dayKey(now)}-${"%05d".format(seq)}"

        val sale = Sale(saleId, receipt, customerId ?: req.customerId, cashier.id, shift.id, subtotal, req.discountCentavos + promoDisc, total,
            req.creditCentavos, SaleStatus.COMPLETED, now)
        sales.insertSale(sale)
        val itemRows = resolved.map {
            SaleItem(UUID.randomUUID().toString(), saleId, it.product.id, it.product.name, it.unit, it.qty, it.price, it.total,
                it.factor, it.base, it.product.costCentavos)
        }
        sales.insertItems(itemRows)
        val payRows = mutableListOf<Payment>()
        if (cashDue > 0) payRows += Payment(UUID.randomUUID().toString(), saleId, null, shift.id, PaymentKind.SALE, PaymentMethod.CASH, cashDue, cashTendered, PaymentStatus.VERIFIED, null, now)
        digital.forEach { payRows += Payment(UUID.randomUUID().toString(), saleId, null, shift.id, PaymentKind.SALE, it.method, it.amountCentavos, null, PaymentStatus.RECORDED, it.reference, now) }
        payRows.forEach { sales.insertPayment(it) }
        val ledgerRows = mutableListOf<LedgerEntry>()
        if (req.creditCentavos > 0) LedgerEntry(UUID.randomUUID().toString(), customerId!!, saleId, null, shift.id, LedgerType.CHARGE, req.creditCentavos, receipt, now).also { sales.insertLedger(it); ledgerRows += it }
        val moveRows = mutableListOf<InventoryMovement>()

        for (r in resolved) {
            if (catalog.deductStock(r.product.id, r.base, now) == 0) throw PosException.InsufficientStock(r.product.name)
            InventoryMovement(UUID.randomUUID().toString(), r.product.id, MoveType.SALE, -r.base, saleId, cashier.id, null, now).also { catalog.insertMovement(it); moveRows += it }
        }
        db.enqueue("SALE", saleId, "CREATE", SyncCodec.saleBundle(sale, itemRows, payRows, ledgerRows, moveRows), now)
        CheckoutResult(sale, payRows, change, false)
    }

    suspend fun voidSale(saleId: String, staffId: String, reason: String) = db.withTransaction {
        val staff = db.requirePerm(staffId, Perm.VOID)
        if (reason.isBlank()) throw PosException.Invalid("A reason is required")
        val shift = db.sales().openShiftNow() ?: throw PosException.Invalid("Open a shift before voiding")
        val sale = db.sales().sale(saleId) ?: throw PosException.Invalid("Sale not found")
        val now = clock()
        if (sale.creditCentavos > 0 && db.sales().saleBalance(saleId) != sale.creditCentavos)
            throw PosException.Invalid("Utang payments were already applied to this sale; use a ledger adjustment instead")
        if (db.sales().markVoided(saleId, reason, now, shift.id) == 0) throw PosException.Invalid("Sale already voided")
        val moves = mutableListOf<InventoryMovement>(); val refunds = mutableListOf<Payment>(); val rev = mutableListOf<LedgerEntry>()
        db.sales().items(saleId).forEach {
            db.catalog().addStock(it.productId, it.baseQtyMilli, now)
            InventoryMovement(UUID.randomUUID().toString(), it.productId, MoveType.VOID_RESTOCK, it.baseQtyMilli, saleId, staff.id, reason, now).also { m -> db.catalog().insertMovement(m); moves += m }
        }
        db.sales().paymentsForSale(saleId).filter { it.kind == PaymentKind.SALE }.forEach {
            Payment(UUID.randomUUID().toString(), saleId, null, shift.id, PaymentKind.REFUND, it.method, it.amountCentavos, null, PaymentStatus.RECORDED, "void", now).also { p -> db.sales().insertPayment(p); refunds += p }
        }
        if (sale.creditCentavos > 0 && sale.customerId != null)
            LedgerEntry(UUID.randomUUID().toString(), sale.customerId, saleId, null, shift.id, LedgerType.REVERSAL, -sale.creditCentavos, "void ${sale.receiptNumber}", now).also { db.sales().insertLedger(it); rev += it }
        db.audit(staff.id, "VOID_SALE", "sale", saleId, reason, now)
        db.enqueue("SALE", saleId, "VOID", JSONObject().put("id", saleId).put("reason", reason).put("at", now).put("voidShiftId", shift.id)
            .put("payments", JSONArray(refunds.map(SyncCodec::payment))).put("ledger", JSONArray(rev.map(SyncCodec::ledger))).put("moves", JSONArray(moves.map(SyncCodec::move))), now)
    }

    /** Utang repayment. Over-payment is rejected; the same paymentId submitted twice is a no-op. */
    suspend fun recordDebtPayment(
        paymentId: String, customerId: String, method: PaymentMethod, amount: Long, reference: String?,
        staffId: String, shiftId: String, allocateSaleId: String? = null
    ): Payment = db.withTransaction {
        db.sales().payment(paymentId)?.let { return@withTransaction it }
        val staff = db.requirePerm(staffId, Perm.COLLECT_DEBT)
        val shift = db.sales().shift(shiftId)
        if (shift == null || shift.closedAt != null) throw PosException.Invalid("No open shift")
        if (amount <= 0) throw PosException.Invalid("Amount must be above zero")
        val outstanding = if (allocateSaleId != null) db.sales().saleBalance(allocateSaleId) else db.sales().balance(customerId)
        if (amount > outstanding) throw PosException.Invalid("Payment exceeds outstanding ${Money.format(outstanding)}")
        val now = clock()
        val status = if (method == PaymentMethod.CASH) PaymentStatus.VERIFIED else PaymentStatus.RECORDED
        val p = Payment(paymentId, null, customerId, shift.id, PaymentKind.DEBT_PAYMENT, method, amount, if (method == PaymentMethod.CASH) amount else null, status, reference, now)
        db.sales().insertPayment(p)
        val le = LedgerEntry(UUID.randomUUID().toString(), customerId, allocateSaleId, paymentId, shift.id, LedgerType.PAYMENT, -amount, reference, now)
        db.sales().insertLedger(le)
        db.enqueue("DEBT_PAYMENT", paymentId, "CREATE", JSONObject().put("payment", SyncCodec.payment(p)).put("ledger", SyncCodec.ledger(le)), now)
        db.audit(staff.id, "DEBT_PAYMENT", "customer", customerId, Money.format(amount), now)
        p
    }

    suspend fun saveCustomer(c: Customer, actorId: String) = db.withTransaction {
        db.requirePerm(actorId, Perm.COLLECT_DEBT)
        if (c.name.isBlank()) throw PosException.Invalid("Name required")
        if (c.creditLimitCentavos < 0) throw PosException.Invalid("Invalid credit limit")
        if (db.admin().customer(c.id) == null) db.admin().insertCustomer(c) else db.admin().updateCustomer(c.copy(updatedAt = clock()))
        db.enqueue("CUSTOMER", c.id, "UPSERT", SyncCodec.customer(db.admin().customer(c.id)!!), clock())
    }
}

class InventoryService(private val db: AppDatabase, private val clock: () -> Long = { System.currentTimeMillis() }) {

    suspend fun saveProduct(p: Product, units: List<ProductUnit>, actorId: String) = db.withTransaction {
        val actor = db.requirePerm(actorId, Perm.MANAGE_CATALOG)
        if (p.name.isBlank() || p.priceCentavos < 0 || p.costCentavos < 0) throw PosException.Invalid("Invalid product")
        units.forEach { if (it.factorMilli <= 0 || it.unitName.isBlank() || it.unitName == p.baseUnit) throw PosException.Invalid("Invalid unit conversion") }
        if (p.expiryDate != null && Expiry.parse(p.expiryDate) == null) throw PosException.Invalid("Expiry date must look like 2026-12-31")
        val old = db.catalog().get(p.id)
        val now = clock()
        if (old == null) db.catalog().insert(p.copy(createdAt = now, updatedAt = now))
        else {
            if (old.priceCentavos != p.priceCentavos) db.requirePerm(actorId, Perm.PRICE_CHANGE)
            // stock only changes through movements, never through the edit form
            db.catalog().update(p.copy(stockMilli = old.stockMilli, createdAt = old.createdAt, updatedAt = now))
        }
        units.forEach { db.catalog().upsertUnit(it.copy(productId = p.id)) }
        db.audit(actor.id, if (old == null) "PRODUCT_CREATE" else "PRODUCT_EDIT", "product", p.id, null, now)
        db.enqueue("PRODUCT", p.id, "UPSERT", SyncCodec.product(db.catalog().get(p.id)!!, db.catalog().unitsFor(p.id)), now)
    }

    /** Stock rises only when goods are actually received and this transaction commits. */
    suspend fun receiveStock(productId: String, unitName: String?, qtyMilli: Long, unitCostCentavos: Long, supplierId: String?, reference: String?, staffId: String, poId: String? = null, expiryDate: String? = null, batchNo: String? = null): StockReceipt =
        db.withTransaction {
            val staff = db.requirePerm(staffId, Perm.STOCK_ADJUST)
            val p = db.catalog().get(productId) ?: throw PosException.Invalid("Unknown product")
            if (expiryDate != null && Expiry.parse(expiryDate) == null) throw PosException.Invalid("Expiry date must look like 2026-12-31")
            if (qtyMilli <= 0) throw PosException.Invalid("Quantity must be positive")
            val unit: String; val factor: Long
            if (unitName == null || unitName == p.baseUnit) { unit = p.baseUnit; factor = 1000 }
            else { val u = db.catalog().unit(productId, unitName) ?: throw PosException.Invalid("Unknown unit"); unit = u.unitName; factor = u.factorMilli }
            val base = Qty.toBase(qtyMilli, factor); val now = clock()
            val r = StockReceipt(UUID.randomUUID().toString(), supplierId, productId, unit, qtyMilli, factor, base, unitCostCentavos, reference, staff.id, now, poId)
            db.catalog().insertReceipt(r)
            db.catalog().addStock(productId, base, now)
            val mv = InventoryMovement(UUID.randomUUID().toString(), productId, MoveType.RECEIPT, base, r.id, staff.id, reference, now)
            db.catalog().insertMovement(mv)
            // nearest expiry wins (sell oldest first): a new delivery only replaces the date when the shelf is empty or the new date is earlier-or-equal
            val cur = db.catalog().get(productId)!!
            val newExp = when { expiryDate == null -> cur.expiryDate; p.stockMilli <= 0 || cur.expiryDate == null -> expiryDate; else -> minOf(cur.expiryDate!!, expiryDate) }
            val upd = cur.copy(costCentavos = if (unitCostCentavos > 0) (unitCostCentavos * 1000 + factor / 2) / factor else cur.costCentavos, expiryDate = newExp,
                batchNo = if (expiryDate != null && newExp == expiryDate) (batchNo ?: cur.batchNo) else cur.batchNo, updatedAt = now)
            if (upd != cur) { db.catalog().update(upd); db.enqueue("PRODUCT", productId, "UPSERT", SyncCodec.product(upd, db.catalog().unitsFor(productId)), now) }
            db.enqueue("STOCK", mv.id, "DELTA", JSONObject().put("move", SyncCodec.move(mv)).put("receipt", SyncCodec.receipt(r)), now)
            r
        }

    suspend fun adjust(productId: String, type: MoveType, deltaMilli: Long, reason: String, staffId: String) = db.withTransaction {
        val staff = db.requirePerm(staffId, Perm.STOCK_ADJUST)
        if (reason.isBlank()) throw PosException.Invalid("A reason is required")
        if (deltaMilli == 0L) throw PosException.Invalid("Nothing to adjust")
        if (type in setOf(MoveType.DAMAGED, MoveType.EXPIRED, MoveType.LOST) && deltaMilli > 0) throw PosException.Invalid("$type must reduce stock")
        applyDelta(productId, type, deltaMilli, reason, staff.id)
    }

    private suspend fun applyDelta(productId: String, type: MoveType, delta: Long, reason: String, staffId: String) {
        val p = db.catalog().get(productId) ?: throw PosException.Invalid("Unknown product")
        val now = clock()
        val ok = if (delta < 0) db.catalog().deductStock(productId, -delta, now) else db.catalog().addStock(productId, delta, now)
        if (ok == 0) throw PosException.InsufficientStock(p.name)
        val mv = InventoryMovement(UUID.randomUUID().toString(), productId, type, delta, null, staffId, reason, now)
        db.catalog().insertMovement(mv)
        db.audit(staffId, "STOCK_$type", "product", productId, reason, now)
        db.enqueue("STOCK", mv.id, "DELTA", JSONObject().put("move", SyncCodec.move(mv)), now)
    }

    /** Bulk stock update: each entry is a signed delta in base units (milli). All-or-nothing. */
    suspend fun bulkAdjust(deltas: Map<String, Long>, reason: String, staffId: String): Int = db.withTransaction {
        val staff = db.requirePerm(staffId, Perm.STOCK_ADJUST)
        if (reason.isBlank()) throw PosException.Invalid("A reason is required")
        var n = 0
        deltas.forEach { (id, d) -> if (d != 0L) { applyDelta(id, MoveType.ADJUSTMENT, d, reason, staff.id); n++ } }
        n
    }

    /** Stock-take: set counted quantities (base units, milli); stores the difference as an ADJUSTMENT. */
    suspend fun bulkSetCounts(counts: Map<String, Long>, staffId: String): Int = db.withTransaction {
        val deltas = counts.mapNotNull { (id, c) -> db.catalog().get(id)?.let { id to (c - it.stockMilli) } }.toMap()
        bulkAdjust(deltas, "Stock count", staffId)
    }

    /** Bulk price update. PERCENT_BP: value in basis points (500 = +5%, -1000 = -10%). */
    suspend fun bulkPrice(ids: List<String>, mode: BulkPriceMode, value: Long, staffId: String): Int = db.withTransaction {
        val staff = db.requirePerm(staffId, Perm.PRICE_CHANGE); db.requirePerm(staffId, Perm.MANAGE_CATALOG)
        val now = clock(); var n = 0
        for (id in ids) {
            val p = db.catalog().get(id) ?: continue
            val np = when (mode) {
                BulkPriceMode.PERCENT_BP -> (p.priceCentavos * (10_000 + value) + 5_000) / 10_000
                BulkPriceMode.ADD_CENTAVOS -> p.priceCentavos + value
                BulkPriceMode.SET_CENTAVOS -> value
            }
            if (np < 0) throw PosException.Invalid("${p.name}: price would be negative")
            if (np == p.priceCentavos) continue
            db.catalog().update(p.copy(priceCentavos = np, updatedAt = now)); n++
            db.audit(staff.id, "BULK_PRICE", "product", id, "${p.priceCentavos} -> $np", now)
            db.enqueue("PRODUCT", id, "UPSERT", SyncCodec.product(db.catalog().get(id)!!, db.catalog().unitsFor(id)), now)
        }
        n
    }

    /** CSV import: matches by barcode, then SKU; creates or updates. apply=false is a dry run (no writes). One bad row never blocks others. */
    suspend fun importProducts(rows: List<ImportRow>, staffId: String, apply: Boolean): ImportReport = db.withTransaction {
        val staff = db.requirePerm(staffId, Perm.MANAGE_CATALOG); db.requirePerm(staffId, Perm.PRICE_CHANGE); db.requirePerm(staffId, Perm.STOCK_ADJUST)
        val now = clock(); var created = 0; var updated = 0; var stock = 0
        val errors = mutableListOf<String>(); val seen = HashSet<String>()
        for (r in rows) {
            val key = r.barcode ?: r.sku
            if (key != null && !seen.add(key)) { errors += "Line ${r.line}: duplicate barcode/SKU $key in file"; continue }
            val existing = r.barcode?.let { db.catalog().byBarcodeAny(it) } ?: r.sku?.let { db.catalog().bySku(it) }
            if (existing == null) {
                if (r.name.isNullOrBlank() || r.price == null) { errors += "Line ${r.line}: new product needs a name and price"; continue }
                val p = Product(UUID.randomUUID().toString(), r.sku, r.barcode, r.name.trim(), r.category, r.cost ?: 0, r.price, 0, r.unit ?: "piece", false, r.reorderMilli ?: 0, null, false, true, r.expiry, null, now, now)
                if (apply) { db.catalog().insert(p); db.audit(staff.id, "PRODUCT_IMPORT", "product", p.id, null, now)
                    db.enqueue("PRODUCT", p.id, "UPSERT", SyncCodec.product(p, emptyList()), now)
                    if ((r.stockMilli ?: 0) > 0) applyDelta(p.id, MoveType.ADJUSTMENT, r.stockMilli!!, "CSV import opening stock", staff.id) }
                created++; if ((r.stockMilli ?: 0) > 0) stock++
            } else {
                val np = existing.copy(name = r.name?.takeIf { it.isNotBlank() } ?: existing.name, category = r.category ?: existing.category, baseUnit = r.unit ?: existing.baseUnit,
                    costCentavos = r.cost ?: existing.costCentavos, priceCentavos = r.price ?: existing.priceCentavos, reorderMilli = r.reorderMilli ?: existing.reorderMilli,
                    sku = existing.sku ?: r.sku, barcode = existing.barcode ?: r.barcode, expiryDate = r.expiry ?: existing.expiryDate, updatedAt = now)
                val delta = r.stockMilli?.let { it - existing.stockMilli } ?: 0
                if (np.copy(updatedAt = existing.updatedAt) != existing) {
                    if (apply) { db.catalog().update(np); db.audit(staff.id, "PRODUCT_IMPORT", "product", np.id, null, now)
                        db.enqueue("PRODUCT", np.id, "UPSERT", SyncCodec.product(np, db.catalog().unitsFor(np.id)), now) }
                    updated++
                }
                if (delta != 0L) { if (apply) applyDelta(np.id, MoveType.ADJUSTMENT, delta, "CSV import stock count", staff.id); stock++ }
            }
        }
        ImportReport(created, updated, stock, errors)
    }
}

enum class BulkPriceMode { PERCENT_BP, ADD_CENTAVOS, SET_CENTAVOS }
data class ImportRow(val line: Int, val sku: String?, val barcode: String?, val name: String?, val category: String?, val unit: String?,
                     val cost: Long?, val price: Long?, val stockMilli: Long?, val reorderMilli: Long?, val expiry: String? = null)
data class ImportReport(val created: Int, val updated: Int, val stockChanged: Int, val errors: List<String>)

class ShiftService(private val db: AppDatabase, private val clock: () -> Long = { System.currentTimeMillis() }) {

    suspend fun open(staffId: String, floatCentavos: Long): Shift = db.withTransaction {
        db.requirePerm(staffId, Perm.SELL)
        if (db.sales().openShiftNow() != null) throw PosException.Invalid("A shift is already open")
        if (floatCentavos < 0) throw PosException.Invalid("Invalid float")
        Shift(UUID.randomUUID().toString(), staffId, clock(), floatCentavos).also { db.sales().insertShift(it) }
    }

    suspend fun cashMove(shiftId: String, type: CashMoveType, amount: Long, reason: String, staffId: String) = db.withTransaction {
        db.requirePerm(staffId, if (type == CashMoveType.EXPENSE) Perm.EXPENSE else Perm.CLOSE_SHIFT)
        val shift = db.sales().shift(shiftId)
        if (shift == null || shift.closedAt != null) throw PosException.Invalid("No open shift")
        if (amount <= 0 || reason.isBlank()) throw PosException.Invalid("Amount and reason required")
        db.sales().insertCashMovement(CashMovement(UUID.randomUUID().toString(), shiftId, type, amount, reason, staffId, clock()))
        db.audit(staffId, "CASH_$type", "shift", shiftId, "${Money.format(amount)} $reason", clock())
    }

    suspend fun preview(shiftId: String, counted: Long?): ZRead {
        val s = db.sales(); val sv = s.serviceSums(shiftId)
        return ZReadCalculator.compute(ZInputs(
            s.shift(shiftId)!!.openingFloatCentavos, s.gross(shiftId), s.discounts(shiftId), s.voidedTotal(shiftId),
            s.paymentTotals(shiftId), s.creditIssued(shiftId), s.movementTotals(shiftId), s.perCashier(shiftId), counted,
            sv.cashIn, sv.cashOut, sv.fees, sv.count))
    }

    /** Freezes the Z-Read. The closing row is never updated; later corrections must be new audited entries. */
    suspend fun close(shiftId: String, counted: Long, staffId: String): String = db.withTransaction {
        val staff = db.requirePerm(staffId, Perm.CLOSE_SHIFT)
        val shift = db.sales().shift(shiftId)
        if (shift == null || shift.closedAt != null) throw PosException.Invalid("Shift already closed")
        if (counted < 0) throw PosException.Invalid("Invalid count")
        val now = clock()
        val z = preview(shiftId, counted)
        val names = db.admin().activeStaffNow().associate { it.id to it.name }
        val text = ZReadCalculator.text(z, db.admin().setting("store_name") ?: "Store", shift, now, names)
        db.sales().insertClosing(ShiftClosing(UUID.randomUUID().toString(), shiftId, staff.id, z.expectedCash, counted, z.variance!!, text, now))
        db.sales().closeShift(shiftId, now)
        db.audit(staff.id, "SHIFT_CLOSE", "shift", shiftId, "variance ${z.variance}", now)
        text
    }
}

/** GCash/Maya cash-in/out, e-load and bills. Drawer effect: cash-in +amount(+fee), cash-out -amount(+fee if paid in cash), load/bills +amount(+fee). */
class CashServiceService(private val db: AppDatabase, private val clock: () -> Long = { System.currentTimeMillis() }) {
    companion object {
        fun drawerEffect(type: ServiceType, amount: Long, fee: Long, feeInCash: Boolean): Pair<Long, Long> {   // (cashIn, cashOut)
            val f = if (feeInCash) fee else 0
            return when (type) { ServiceType.CASH_IN, ServiceType.LOAD, ServiceType.BILLS -> (amount + f) to 0L; ServiceType.CASH_OUT -> f to amount }
        }
    }

    suspend fun record(id: String, shiftId: String, staffId: String, type: ServiceType, provider: String, amount: Long, fee: Long, feeInCash: Boolean, customer: String?, ref: String?): ServiceTxn = db.withTransaction {
        db.sales().service(id)?.let { return@withTransaction it }                                // idempotent
        val staff = db.requirePerm(staffId, Perm.SELL)
        val shift = db.sales().shift(shiftId)
        if (shift == null || shift.closedAt != null) throw PosException.Invalid("Open a shift first")
        if (amount <= 0 || fee < 0) throw PosException.Invalid("Enter the amount (and a fee of 0 or more)")
        if (provider.isBlank()) throw PosException.Invalid("Choose the provider")
        val (cin, cout) = drawerEffect(type, amount, fee, feeInCash); val now = clock()
        val t = ServiceTxn(id, shiftId, staff.id, type, provider.trim(), amount, fee, feeInCash, cin, cout, customer?.takeIf { it.isNotBlank() }, ref?.takeIf { it.isNotBlank() }, now)
        db.sales().insertService(t)
        db.audit(staff.id, "SERVICE_$type", "service_txn", id, "${Money.format(amount)} fee ${Money.format(fee)}", now)
        db.enqueue("SERVICE", id, "CREATE", SyncCodec.service(t), now)
        t
    }

    suspend fun void(id: String, reason: String, staffId: String) = db.withTransaction {
        val staff = db.requirePerm(staffId, Perm.VOID)
        if (reason.isBlank()) throw PosException.Invalid("A reason is required")
        if (db.sales().markServiceVoided(id, reason) == 0) throw PosException.Invalid("Already voided or not found")
        db.audit(staff.id, "SERVICE_VOID", "service_txn", id, reason, clock())
        db.enqueue("SERVICE", id, "VOID", org.json.JSONObject().put("id", id).put("reason", reason), clock())
    }
}
