package ph.sarisari.mpos.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class Role { OWNER, CASHIER, HELPER, INVENTORY }
enum class Perm { SELL, DISCOUNT, VOID, REFUND, CREDIT_OVERRIDE, STOCK_ADJUST, EXPENSE, PRICE_CHANGE, CLOSE_SHIFT, RESTORE, MANAGE_STAFF, MANAGE_CATALOG, COLLECT_DEBT }
enum class PaymentMethod { CASH, GCASH, MAYA, SHOPEEPAY, OTHER_EWALLET }
enum class PaymentStatus { RECORDED, VERIFIED }          // RECORDED = typed in by cashier, NOT confirmed by the e-wallet
enum class PaymentKind { SALE, DEBT_PAYMENT, REFUND }
enum class SaleStatus { COMPLETED, VOIDED }
enum class LedgerType { CHARGE, PAYMENT, ADJUSTMENT, REVERSAL }
enum class MoveType { SALE, RECEIPT, ADJUSTMENT, DAMAGED, EXPIRED, LOST, RETURN, VOID_RESTOCK }
enum class CashMoveType { ADD, WITHDRAW, EXPENSE }
enum class SyncStatus { PENDING, SYNCED, FAILED }
enum class PromoType { BUNDLE, BUY_X_GET_Y, PERCENT_OFF, VOLUME_PRICE }
enum class ServiceType { CASH_IN, CASH_OUT, LOAD, BILLS }
enum class PoStatus { DRAFT, ORDERED, PARTIAL, RECEIVED, CANCELLED }

@Entity(
    tableName = "products",
    indices = [Index("sku", unique = true), Index("barcode", unique = true), Index("name"), Index("active")]
)
data class Product(
    @PrimaryKey val id: String,
    val sku: String? = null,
    val barcode: String? = null,
    val name: String,
    val category: String? = null,
    val costCentavos: Long = 0,            // cost per BASE unit
    val priceCentavos: Long,               // price per BASE unit
    val stockMilli: Long = 0,              // stock in base units x1000
    val baseUnit: String = "piece",
    val allowsFraction: Boolean = false,
    val reorderMilli: Long = 0,
    val supplierId: String? = null,
    val favorite: Boolean = false,
    val active: Boolean = true,
    val expiryDate: String? = null,
    val batchNo: String? = null,
    val createdAt: Long,
    val updatedAt: Long
)

/** Alternate selling/receiving unit, e.g. "case" = 24 pieces -> factorMilli = 24000. */
@Entity(
    tableName = "product_units",
    primaryKeys = ["productId", "unitName"],
    foreignKeys = [ForeignKey(entity = Product::class, parentColumns = ["id"], childColumns = ["productId"])],
    indices = [Index("productId")]
)
data class ProductUnit(
    val productId: String,
    val unitName: String,
    val factorMilli: Long,
    val priceCentavos: Long? = null        // null => base price x factor
)

@Entity(tableName = "customers", indices = [Index("name")])
data class Customer(
    @PrimaryKey val id: String,
    val name: String,
    val phone: String? = null,
    val notes: String? = null,
    val creditLimitCentavos: Long = 100_000,
    val active: Boolean = true,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(tableName = "staff")
data class Staff(
    @PrimaryKey val id: String,
    val name: String,
    val role: Role,
    val pinHash: String,
    val pinSalt: String,
    val active: Boolean = true,
    val failedAttempts: Int = 0,
    val lockedUntil: Long = 0,
    val createdAt: Long
)

@Entity(tableName = "shifts", indices = [Index("staffId"), Index("closedAt")])
data class Shift(
    @PrimaryKey val id: String,
    val staffId: String,
    val openedAt: Long,
    val openingFloatCentavos: Long,
    val closedAt: Long? = null
)

@Entity(
    tableName = "cash_movements",
    foreignKeys = [ForeignKey(entity = Shift::class, parentColumns = ["id"], childColumns = ["shiftId"])],
    indices = [Index("shiftId")]
)
data class CashMovement(
    @PrimaryKey val id: String,
    val shiftId: String,
    val type: CashMoveType,
    val amountCentavos: Long,
    val reason: String,
    val staffId: String,
    val createdAt: Long
)

@Entity(
    tableName = "sales",
    foreignKeys = [ForeignKey(entity = Customer::class, parentColumns = ["id"], childColumns = ["customerId"])],
    indices = [Index("receiptNumber", unique = true), Index("customerId"), Index("shiftId"), Index("createdAt"), Index("voidShiftId")]
)
data class Sale(
    @PrimaryKey val id: String,             // = client transaction id (idempotency key)
    val receiptNumber: String,
    val customerId: String?,
    val cashierId: String,
    val shiftId: String,
    val subtotalCentavos: Long,
    val discountCentavos: Long,
    val totalCentavos: Long,
    val creditCentavos: Long,               // portion put on Utang
    val status: SaleStatus,
    val createdAt: Long,
    val voidReason: String? = null,
    val voidedAt: Long? = null,
    val voidShiftId: String? = null
)

@Entity(
    tableName = "sale_items",
    foreignKeys = [
        ForeignKey(entity = Sale::class, parentColumns = ["id"], childColumns = ["saleId"]),
        ForeignKey(entity = Product::class, parentColumns = ["id"], childColumns = ["productId"])
    ],
    indices = [Index("saleId"), Index("productId")]
)
data class SaleItem(
    @PrimaryKey val id: String,
    val saleId: String,
    val productId: String,
    val productNameSnapshot: String,
    val unitSnapshot: String,
    val qtyMilli: Long,                     // in the unit sold
    val unitPriceCentavos: Long,            // snapshot price per sold unit
    val subtotalCentavos: Long,
    val conversionFactorMilli: Long,        // base units per sold unit x1000
    val baseQtyMilli: Long,                 // qty deducted from stock
    val costCentavosSnapshot: Long          // cost per BASE unit at time of sale
)

@Entity(
    tableName = "payments",
    indices = [Index("saleId"), Index("customerId"), Index("shiftId"), Index("kind")]
)
data class Payment(
    @PrimaryKey val id: String,
    val saleId: String?,
    val customerId: String?,
    val shiftId: String,
    val kind: PaymentKind,
    val method: PaymentMethod,
    val amountCentavos: Long,               // amount applied (cash: excludes change)
    val tenderedCentavos: Long? = null,     // cash handed over by customer
    val status: PaymentStatus,
    val reference: String? = null,
    val createdAt: Long
)

/** Signed ledger: CHARGE/ADJUSTMENT(+) increase debt, PAYMENT/REVERSAL(-) decrease it. Balance = SUM(amount). */
@Entity(
    tableName = "customer_ledger_entries",
    foreignKeys = [ForeignKey(entity = Customer::class, parentColumns = ["id"], childColumns = ["customerId"])],
    indices = [Index("customerId"), Index("saleId"), Index("shiftId")]
)
data class LedgerEntry(
    @PrimaryKey val id: String,
    val customerId: String,
    val saleId: String?,
    val paymentId: String?,
    val shiftId: String?,
    val type: LedgerType,
    val amountCentavos: Long,
    val notes: String? = null,
    val createdAt: Long
)

@Entity(
    tableName = "inventory_movements",
    foreignKeys = [ForeignKey(entity = Product::class, parentColumns = ["id"], childColumns = ["productId"])],
    indices = [Index("productId"), Index("createdAt")]
)
data class InventoryMovement(
    @PrimaryKey val id: String,
    val productId: String,
    val type: MoveType,
    val qtyDeltaMilli: Long,                // base units x1000, signed
    val referenceId: String?,
    val staffId: String,
    val notes: String?,
    val createdAt: Long
)

@Entity(tableName = "suppliers")
data class Supplier(
    @PrimaryKey val id: String,
    val name: String,
    val phone: String? = null,
    val notes: String? = null,
    val active: Boolean = true,
    val createdAt: Long
)

@Entity(tableName = "stock_receipts", indices = [Index("productId"), Index("supplierId")])
data class StockReceipt(
    @PrimaryKey val id: String,
    val supplierId: String?,
    val productId: String,
    val unitName: String,
    val qtyMilli: Long,
    val conversionFactorMilli: Long,
    val baseQtyMilli: Long,
    val unitCostCentavos: Long,
    val reference: String?,
    val staffId: String,
    val createdAt: Long,
    val poId: String? = null
)

@Entity(tableName = "shift_closings", indices = [Index("shiftId", unique = true)])
data class ShiftClosing(
    @PrimaryKey val id: String,
    val shiftId: String,
    val staffId: String,
    val expectedCashCentavos: Long,
    val countedCashCentavos: Long,
    val varianceCentavos: Long,
    val reportText: String,                 // frozen Z-Read snapshot; never edited
    val createdAt: Long
)

@Entity(tableName = "audit_log", indices = [Index("createdAt")])
data class AuditLog(
    @PrimaryKey val id: String,
    val staffId: String,
    val action: String,
    val entityType: String,
    val entityId: String?,
    val reason: String?,
    val createdAt: Long
)

@Entity(tableName = "sync_outbox", indices = [Index("syncStatus", "createdAt")])
data class SyncOutbox(
    @PrimaryKey val id: String,             // idempotency key
    val entityType: String,
    val entityId: String,
    val operation: String,
    val payload: String,
    val createdAt: Long,
    val attemptCount: Int = 0,
    val syncStatus: SyncStatus = SyncStatus.PENDING,
    val lastError: String? = null
)

@Entity(tableName = "app_settings")
data class AppSetting(@PrimaryKey val settingKey: String, val settingValue: String)

@Entity(tableName = "purchase_orders", indices = [Index("supplierId"), Index("status")])
data class PurchaseOrder(
    @PrimaryKey val id: String, val supplierId: String?, val status: PoStatus, val notes: String?,
    val staffId: String, val createdAt: Long, val orderedAt: Long? = null, val updatedAt: Long
)

@Entity(
    tableName = "purchase_order_lines",
    foreignKeys = [ForeignKey(entity = PurchaseOrder::class, parentColumns = ["id"], childColumns = ["poId"])],
    indices = [Index("poId"), Index("productId")]
)
data class PurchaseOrderLine(
    @PrimaryKey val id: String, val poId: String, val productId: String,
    val productName: String,                // snapshot
    val unitName: String, val qtyMilli: Long, val receivedMilli: Long = 0, val unitCostCentavos: Long, val factorMilli: Long
)

/** One row per reminder launched; lets the UI warn about duplicate reminders. */
@Entity(tableName = "reminder_log", indices = [Index("customerId")])
data class ReminderLog(
    @PrimaryKey val id: String, val customerId: String, val channel: String,
    val balanceCentavos: Long, val staffId: String, val createdAt: Long
)

/** Remote sync events already applied on this device (idempotent apply). */
@Entity(tableName = "applied_events")
data class AppliedEvent(@PrimaryKey val id: String, val appliedAt: Long)

/** GCash/Maya cash-in, cash-out, e-load and bills payment done at the counter. Only the DRAWER effect and the shop's fee are tracked (not wallet balances). */
@Entity(tableName = "service_txns", indices = [Index("shiftId"), Index("createdAt")])
data class ServiceTxn(
    @PrimaryKey val id: String, val shiftId: String, val staffId: String, val type: ServiceType, val provider: String,
    val amountCentavos: Long, val feeCentavos: Long, val feeInCash: Boolean,
    val cashInCentavos: Long, val cashOutCentavos: Long,        // drawer effect, computed once at record time
    val customerName: String?, val reference: String?, val createdAt: Long,
    val voided: Boolean = false, val voidReason: String? = null
)

/** Automatic price rule for ONE product, in its base unit. BUNDLE: qtyMilli pieces for valueCentavos. BUY_X_GET_Y: buy qtyMilli, get freeQtyMilli free.
 *  PERCENT_OFF: percentBp/100 %. VOLUME_PRICE: from qtyMilli pieces, each costs valueCentavos. Dates are ISO yyyy-MM-dd (inclusive), null = open. */
@Entity(tableName = "promos", indices = [Index("productId"), Index("active")])
data class Promo(
    @PrimaryKey val id: String, val name: String, val type: PromoType, val productId: String,
    val qtyMilli: Long = 0, val valueCentavos: Long = 0, val freeQtyMilli: Long = 0, val percentBp: Long = 0,
    val startDate: String? = null, val endDate: String? = null, val active: Boolean = true, val createdAt: Long
)

/** A bill from a supplier. Balance = amount - payments. */
@Entity(tableName = "supplier_bills", indices = [Index("supplierId"), Index("dueDate")])
data class SupplierBill(
    @PrimaryKey val id: String, val supplierId: String?, val poId: String?, val reference: String?,
    val amountCentavos: Long, val dueDate: String?, val note: String?, val staffId: String, val createdAt: Long, val cancelled: Boolean = false
)

@Entity(
    tableName = "supplier_payments",
    foreignKeys = [ForeignKey(entity = SupplierBill::class, parentColumns = ["id"], childColumns = ["billId"])],
    indices = [Index("billId")]
)
data class SupplierPayment(
    @PrimaryKey val id: String, val billId: String, val amountCentavos: Long,
    val method: String,                       // CASH_DRAWER (also recorded as a shift expense), GCASH, BANK, OTHER
    val staffId: String, val createdAt: Long, val shiftId: String? = null
)
