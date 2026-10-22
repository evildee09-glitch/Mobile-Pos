package ph.sarisari.mpos.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

data class PaymentTotal(val kind: PaymentKind, val method: PaymentMethod, val total: Long)
data class MovementTotal(val type: CashMoveType, val total: Long)
data class CashierTotal(val cashierId: String, val count: Int, val total: Long)
data class DayTotals(val total: Long, val count: Int)
data class CustomerBalance(val customerId: String, val balance: Long)
data class SaleBalance(val saleId: String, val balance: Long)

@Dao
interface CatalogDao {
    @Insert suspend fun insert(p: Product)
    @Update suspend fun update(p: Product)
    @Query("SELECT * FROM products WHERE id=:id") suspend fun get(id: String): Product?
    @Query("SELECT * FROM products WHERE barcode=:code AND active=1 LIMIT 1") suspend fun byBarcode(code: String): Product?
    @Query("SELECT * FROM products WHERE barcode=:code LIMIT 1") suspend fun byBarcodeAny(code: String): Product?
    @Query("SELECT * FROM products WHERE sku=:sku LIMIT 1") suspend fun bySku(sku: String): Product?
    @Query("SELECT * FROM products WHERE active=1 AND reorderMilli>0 AND stockMilli<=reorderMilli ORDER BY name") suspend fun lowStockNow(): List<Product>
    @Upsert suspend fun upsertProduct(p: Product)
    @Query("SELECT * FROM products ORDER BY name") fun all(): Flow<List<Product>>
    @Query("SELECT * FROM products WHERE active=1 AND favorite=1 ORDER BY name") fun favorites(): Flow<List<Product>>
    @Query(
        "SELECT * FROM products WHERE active=1 AND (name LIKE '%'||:q||'%' OR sku LIKE '%'||:q||'%' " +
            "OR barcode=:q OR category LIKE '%'||:q||'%') ORDER BY name LIMIT 100"
    ) fun search(q: String): Flow<List<Product>>
    @Query("SELECT * FROM products WHERE active=1 AND reorderMilli>0 AND stockMilli<=reorderMilli ORDER BY name")
    fun lowStock(): Flow<List<Product>>

    /** Conditional update: returns 0 rows if stock is insufficient -> no overselling, even under concurrency. */
    @Query("UPDATE products SET stockMilli=stockMilli-:qty, updatedAt=:now WHERE id=:id AND stockMilli>=:qty")
    suspend fun deductStock(id: String, qty: Long, now: Long): Int
    @Query("UPDATE products SET stockMilli=stockMilli+:qty, updatedAt=:now WHERE id=:id")
    suspend fun addStock(id: String, qty: Long, now: Long): Int

    @Query("SELECT * FROM product_units WHERE productId=:id AND unitName=:unit") suspend fun unit(id: String, unit: String): ProductUnit?
    @Query("SELECT * FROM product_units WHERE productId=:id") suspend fun unitsFor(id: String): List<ProductUnit>
    @Query("SELECT * FROM product_units") fun allUnits(): Flow<List<ProductUnit>>
    @Upsert suspend fun upsertUnit(u: ProductUnit)

    @Insert suspend fun insertMovement(m: InventoryMovement)
    @Insert suspend fun insertReceipt(r: StockReceipt)
    @Query("SELECT * FROM inventory_movements WHERE productId=:id ORDER BY createdAt DESC LIMIT 100")
    fun movements(id: String): Flow<List<InventoryMovement>>

    @Insert suspend fun insertPo(po: PurchaseOrder)
    @Update suspend fun updatePo(po: PurchaseOrder)
    @Insert suspend fun insertPoLines(l: List<PurchaseOrderLine>)
    @Update suspend fun updatePoLine(l: PurchaseOrderLine)
    @Query("SELECT * FROM purchase_orders WHERE id=:id") suspend fun po(id: String): PurchaseOrder?
    @Query("SELECT * FROM purchase_orders ORDER BY createdAt DESC") fun pos(): Flow<List<PurchaseOrder>>
    @Query("SELECT * FROM purchase_order_lines WHERE poId=:id") suspend fun poLinesNow(id: String): List<PurchaseOrderLine>
    @Query("SELECT * FROM purchase_order_lines WHERE poId=:id") fun poLines(id: String): Flow<List<PurchaseOrderLine>>
    @Query("SELECT * FROM suppliers WHERE id=:id") suspend fun supplier(id: String): Supplier?

    @Upsert suspend fun upsertSupplier(s: Supplier)
    @Query("SELECT * FROM suppliers WHERE active=1 ORDER BY name") fun suppliers(): Flow<List<Supplier>>
}

@Dao
interface SalesDao {
    @Insert suspend fun insertSale(s: Sale)
    @Insert suspend fun insertItems(items: List<SaleItem>)
    @Insert suspend fun insertPayment(p: Payment)
    @Insert suspend fun insertLedger(e: LedgerEntry)
    @Insert suspend fun insertShift(s: Shift)
    @Insert suspend fun insertCashMovement(m: CashMovement)
    @Insert suspend fun insertClosing(c: ShiftClosing)

    @Query("SELECT * FROM sales WHERE id=:id") suspend fun sale(id: String): Sale?
    @Query("SELECT * FROM sale_items WHERE saleId=:id") suspend fun items(id: String): List<SaleItem>
    @Query("SELECT * FROM payments WHERE saleId=:id") suspend fun paymentsForSale(id: String): List<Payment>
    @Query("SELECT * FROM payments WHERE id=:id") suspend fun payment(id: String): Payment?
    @Query("SELECT * FROM sales ORDER BY createdAt DESC LIMIT :n") fun recentSales(n: Int): Flow<List<Sale>>
    @Query(
        "UPDATE sales SET status='VOIDED', voidReason=:reason, voidedAt=:at, voidShiftId=:shiftId " +
            "WHERE id=:id AND status='COMPLETED'"
    ) suspend fun markVoided(id: String, reason: String, at: Long, shiftId: String): Int

    // ledger
    @Query("SELECT COALESCE(SUM(amountCentavos),0) FROM customer_ledger_entries WHERE customerId=:c")
    suspend fun balance(c: String): Long
    @Query("SELECT COALESCE(SUM(amountCentavos),0) FROM customer_ledger_entries WHERE saleId=:s")
    suspend fun saleBalance(s: String): Long
    @Query("SELECT * FROM customer_ledger_entries WHERE customerId=:c ORDER BY createdAt, rowid")
    suspend fun ledger(c: String): List<LedgerEntry>
    @Query(
        "SELECT c.id AS customerId, COALESCE(SUM(l.amountCentavos),0) AS balance FROM customers c " +
            "LEFT JOIN customer_ledger_entries l ON l.customerId=c.id GROUP BY c.id"
    ) fun balances(): Flow<List<CustomerBalance>>
    @Query(
        "SELECT saleId, SUM(amountCentavos) AS balance FROM customer_ledger_entries " +
            "WHERE customerId=:c AND saleId IS NOT NULL GROUP BY saleId HAVING SUM(amountCentavos)>0"
    ) suspend fun openCharges(c: String): List<SaleBalance>

    // shifts / cash
    @Query("SELECT * FROM shifts WHERE closedAt IS NULL LIMIT 1") fun openShift(): Flow<Shift?>
    @Query("SELECT * FROM shifts WHERE closedAt IS NULL LIMIT 1") suspend fun openShiftNow(): Shift?
    @Query("SELECT * FROM shifts WHERE id=:id") suspend fun shift(id: String): Shift?
    @Query("UPDATE shifts SET closedAt=:at WHERE id=:id AND closedAt IS NULL") suspend fun closeShift(id: String, at: Long): Int

    // Z-Read building blocks
    @Query("SELECT kind, method, COALESCE(SUM(amountCentavos),0) AS total FROM payments WHERE shiftId=:s GROUP BY kind, method")
    suspend fun paymentTotals(s: String): List<PaymentTotal>
    @Query("SELECT type, COALESCE(SUM(amountCentavos),0) AS total FROM cash_movements WHERE shiftId=:s GROUP BY type")
    suspend fun movementTotals(s: String): List<MovementTotal>
    @Query("SELECT COALESCE(SUM(subtotalCentavos),0) FROM sales WHERE shiftId=:s") suspend fun gross(s: String): Long
    @Query("SELECT COALESCE(SUM(discountCentavos),0) FROM sales WHERE shiftId=:s") suspend fun discounts(s: String): Long
    @Query("SELECT COALESCE(SUM(totalCentavos),0) FROM sales WHERE voidShiftId=:s") suspend fun voidedTotal(s: String): Long
    @Query("SELECT COALESCE(SUM(amountCentavos),0) FROM customer_ledger_entries WHERE shiftId=:s AND type='CHARGE'")
    suspend fun creditIssued(s: String): Long
    @Query(
        "SELECT cashierId, COUNT(*) AS count, COALESCE(SUM(totalCentavos),0) AS total FROM sales " +
            "WHERE shiftId=:s AND status='COMPLETED' GROUP BY cashierId"
    ) suspend fun perCashier(s: String): List<CashierTotal>

    // reports
    @Query("SELECT COALESCE(SUM(totalCentavos),0) AS total, COUNT(*) AS count FROM sales WHERE status='COMPLETED' AND createdAt BETWEEN :from AND :to")
    fun dayTotals(from: Long, to: Long): Flow<DayTotals>
    @Query(
        "SELECT COALESCE(SUM(si.subtotalCentavos - (si.costCentavosSnapshot * si.baseQtyMilli + 500)/1000),0) " +
            "FROM sale_items si JOIN sales s ON s.id=si.saleId WHERE s.status='COMPLETED' AND s.createdAt BETWEEN :from AND :to"
    ) fun grossProfit(from: Long, to: Long): Flow<Long>
}

@Dao
interface AdminDao {
    @Insert suspend fun insertStaff(s: Staff)
    @Update suspend fun updateStaff(s: Staff)
    @Query("SELECT * FROM staff WHERE id=:id") suspend fun staff(id: String): Staff?
    @Query("SELECT * FROM staff WHERE active=1 ORDER BY name") fun activeStaff(): Flow<List<Staff>>
    @Query("SELECT * FROM staff WHERE active=1") suspend fun activeStaffNow(): List<Staff>

    @Insert suspend fun insertCustomer(c: Customer)
    @Update suspend fun updateCustomer(c: Customer)
    @Query("SELECT * FROM customers WHERE id=:id") suspend fun customer(id: String): Customer?
    @Query("SELECT * FROM customers WHERE active=1 ORDER BY name") fun customers(): Flow<List<Customer>>

    @Insert suspend fun insertAudit(a: AuditLog)
    @Insert suspend fun insertOutbox(o: SyncOutbox)
    @Query("SELECT * FROM sync_outbox WHERE syncStatus IN ('PENDING','FAILED') ORDER BY createdAt LIMIT :n")
    suspend fun pending(n: Int): List<SyncOutbox>
    @Query("UPDATE sync_outbox SET syncStatus='SYNCED', lastError=NULL WHERE id IN (:ids)") suspend fun markSynced(ids: List<String>)
    @Query("UPDATE sync_outbox SET syncStatus='FAILED', attemptCount=attemptCount+1, lastError=:err WHERE id IN (:ids)")
    suspend fun markFailed(ids: List<String>, err: String)
    @Query("SELECT COUNT(*) FROM sync_outbox WHERE syncStatus<>'SYNCED'") fun pendingCount(): Flow<Int>

    @Insert suspend fun insertReminder(r: ReminderLog)
    @Query("SELECT MAX(createdAt) FROM reminder_log WHERE customerId=:c AND channel=:ch") suspend fun lastReminder(c: String, ch: String): Long?
    /** Returns -1 when the event id was already applied. */
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun markApplied(a: AppliedEvent): Long

    @Upsert suspend fun putSetting(s: AppSetting)
    @Query("SELECT settingValue FROM app_settings WHERE settingKey=:k") suspend fun setting(k: String): String?
    @Query("SELECT settingValue FROM app_settings WHERE settingKey=:k") fun settingFlow(k: String): Flow<String?>
}
