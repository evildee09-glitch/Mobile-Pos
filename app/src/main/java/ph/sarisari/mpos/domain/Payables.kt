package ph.sarisari.mpos.domain

import androidx.room.withTransaction
import ph.sarisari.mpos.data.*
import ph.sarisari.mpos.util.Expiry
import ph.sarisari.mpos.util.Money
import ph.sarisari.mpos.util.Qty
import java.util.UUID

class PayablesService(private val db: AppDatabase, private val clock: () -> Long = { System.currentTimeMillis() }) {

    suspend fun addBill(supplierId: String?, poId: String?, reference: String?, amount: Long, dueDate: String?, note: String?, staffId: String): SupplierBill = db.withTransaction {
        val staff = db.requirePerm(staffId, Perm.STOCK_ADJUST)
        if (amount <= 0) throw PosException.Invalid("Enter the bill amount")
        if (dueDate != null && Expiry.parse(dueDate) == null) throw PosException.Invalid("Due date must look like 2026-12-31")
        val now = clock()
        val b = SupplierBill(UUID.randomUUID().toString(), supplierId, poId, reference?.takeIf { it.isNotBlank() }, amount, dueDate, note?.takeIf { it.isNotBlank() }, staff.id, now)
        db.catalog().insertBill(b); db.audit(staff.id, "BILL_ADD", "supplier_bill", b.id, Money.format(amount), now); b
    }

    /** Bill for what was actually received on a purchase order (received qty x unit cost). */
    suspend fun billFromPo(poId: String, dueDate: String?, staffId: String): SupplierBill {
        val po = db.catalog().po(poId) ?: throw PosException.Invalid("PO not found")
        val total = db.catalog().poLinesNow(poId).sumOf { Qty.lineTotal(it.unitCostCentavos, it.receivedMilli) }
        if (total <= 0) throw PosException.Invalid("Nothing received yet, or no costs entered on this order")
        return addBill(po.supplierId, poId, "PO ${poId.take(6).uppercase()}", total, dueDate, null, staffId)
    }

    /** method CASH_DRAWER also records a shift expense, so the Z-Read drawer count stays right. Overpaying is rejected. */
    suspend fun pay(billId: String, amount: Long, method: String, staffId: String): SupplierPayment = db.withTransaction {
        val staff = db.requirePerm(staffId, Perm.EXPENSE)
        val bill = db.catalog().bill(billId)?.takeIf { !it.cancelled } ?: throw PosException.Invalid("Bill not found")
        val balance = bill.amountCentavos - db.catalog().billPaid(billId)
        if (amount <= 0) throw PosException.Invalid("Enter the amount")
        if (amount > balance) throw PosException.Invalid("Only ${Money.format(balance)} is still owed on this bill")
        val now = clock(); var shiftId: String? = null
        if (method == "CASH_DRAWER") {
            val shift = db.sales().openShiftNow() ?: throw PosException.Invalid("Open a shift to pay from the drawer")
            db.sales().insertCashMovement(CashMovement(UUID.randomUUID().toString(), shift.id, CashMoveType.EXPENSE, amount, "Supplier bill ${bill.reference ?: bill.id.take(6)}", staff.id, now)); shiftId = shift.id
        }
        val p = SupplierPayment(UUID.randomUUID().toString(), billId, amount, method, staff.id, now, shiftId)
        db.catalog().insertBillPayment(p); db.audit(staff.id, "BILL_PAY", "supplier_bill", billId, "${Money.format(amount)} via $method", now); p
    }

    suspend fun cancel(billId: String, staffId: String) = db.withTransaction {
        val staff = db.requirePerm(staffId, Perm.MANAGE_STAFF)                       // owner only
        val b = db.catalog().bill(billId) ?: throw PosException.Invalid("Bill not found")
        if (db.catalog().billPaid(billId) > 0) throw PosException.Invalid("This bill already has payments")
        db.catalog().updateBill(b.copy(cancelled = true)); db.audit(staff.id, "BILL_CANCEL", "supplier_bill", billId, null, clock())
    }
}
