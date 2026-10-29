package ph.sarisari.mpos

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ph.sarisari.mpos.data.*
import ph.sarisari.mpos.domain.*
import ph.sarisari.mpos.sync.*
import org.json.JSONObject
import java.util.UUID

/** Run on a device/emulator: ./gradlew connectedDebugAndroidTest */
@RunWith(AndroidJUnit4::class)
class SalesServiceTest {
    private lateinit var db: AppDatabase
    private lateinit var sales: SalesService
    private lateinit var inv: InventoryService
    private lateinit var shifts: ShiftService
    private lateinit var owner: Staff
    private lateinit var cashier: Staff
    private lateinit var helper: Staff
    private lateinit var shift: Shift

    private fun staff(name: String, role: Role): Staff { val salt = PinHasher.newSalt(); return Staff(UUID.randomUUID().toString(), name, role, PinHasher.hash("1234", salt), salt, createdAt = 0) }
    private fun product(name: String, price: Long, stock: Long, unit: String = "piece", frac: Boolean = false, cost: Long = 0) =
        Product(UUID.randomUUID().toString(), null, null, name, null, cost, price, stock, unit, frac, 0, null, false, true, null, null, 0, 0)

    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).allowMainThreadQueries().build()
        sales = SalesService(db); inv = InventoryService(db); shifts = ShiftService(db)
        owner = staff("Owner", Role.OWNER); cashier = staff("Cashier", Role.CASHIER); helper = staff("Helper", Role.HELPER)
        listOf(owner, cashier, helper).forEach { db.admin().insertStaff(it) }
        shift = shifts.open(cashier.id, 100_000)
    }
    @After fun tearDown() { db.close() }

    private suspend fun add(p: Product) = db.catalog().insert(p)
    private fun req(lines: List<CartLineInput>, pay: List<PaymentInput> = emptyList(), credit: Long = 0, cust: String? = null, discount: Long = 0,
                    by: Staff = cashier, approver: String? = null, id: String = UUID.randomUUID().toString()) =
        CheckoutRequest(id, by.id, shift.id, cust, lines, discount, pay, credit, approver)
    private suspend fun customer(limit: Long = 100_000) = Customer(UUID.randomUUID().toString(), "Aling Rosa", null, null, limit, true, 0, 0).also { db.admin().insertCustomer(it) }

    @Test fun cashSale_deductsStock_andComputesChange() = runBlocking {
        val p = product("Noodles", 1_500, 10_000).also { add(it) }
        val r = sales.checkout(req(listOf(CartLineInput(p.id, null, 2000)), listOf(PaymentInput(PaymentMethod.CASH, 5_000))))
        assertEquals(3_000L, r.sale.totalCentavos); assertEquals(2_000L, r.changeCentavos)
        assertEquals(8_000L, db.catalog().get(p.id)!!.stockMilli)
        assertEquals(1, db.admin().pending(10).size)
    }

    @Test fun duplicateSubmission_createsOneSale() = runBlocking {
        val p = product("Candy", 100, 100_000).also { add(it) }
        val id = UUID.randomUUID().toString()
        val q = req(listOf(CartLineInput(p.id, null, 1000)), listOf(PaymentInput(PaymentMethod.CASH, 100)), id = id)
        sales.checkout(q); val again = sales.checkout(q)
        assertTrue(again.duplicate); assertEquals(99_000L, db.catalog().get(p.id)!!.stockMilli)
    }

    @Test fun splitPayment_recordsTwoPayments() = runBlocking {
        val p = product("Item", 15_000, 5_000).also { add(it) }
        val r = sales.checkout(req(listOf(CartLineInput(p.id, null, 1000)), listOf(PaymentInput(PaymentMethod.CASH, 5_000), PaymentInput(PaymentMethod.GCASH, 10_000, "ref1"))))
        assertEquals(2, r.payments.size)
        assertEquals(PaymentStatus.RECORDED, r.payments.first { it.method == PaymentMethod.GCASH }.status)   // never auto-"verified"
    }

    @Test fun fractionalSale() = runBlocking {
        val rice = product("Rice", 5_000, 10_000, "kg", frac = true).also { add(it) }
        val r = sales.checkout(req(listOf(CartLineInput(rice.id, null, 1250)), listOf(PaymentInput(PaymentMethod.CASH, 10_000))))
        assertEquals(6_250L, r.sale.totalCentavos); assertEquals(8_750L, db.catalog().get(rice.id)!!.stockMilli)
    }

    @Test fun wholesaleToPieceConversion() = runBlocking {
        val coke = product("Coke", 2_000, 0).also { add(it) }
        db.catalog().upsertUnit(ProductUnit(coke.id, "case", 24_000))
        inv.receiveStock(coke.id, "case", 1000, 40_000, null, "DR-1", owner.id)
        assertEquals(24_000L, db.catalog().get(coke.id)!!.stockMilli)
        sales.checkout(req(listOf(CartLineInput(coke.id, null, 1000)), listOf(PaymentInput(PaymentMethod.CASH, 2_000))))
        assertEquals(23_000L, db.catalog().get(coke.id)!!.stockMilli)
        assertEquals(1_667L, db.catalog().get(coke.id)!!.costCentavos)   // ₱400/case ÷ 24 = ₱16.67 per can
    }

    @Test fun utang_limit_override_and_partialRepayments() = runBlocking {
        val p = product("Sack", 50_000, 10_000).also { add(it) }
        val c = customer(limit = 60_000)
        sales.checkout(req(listOf(CartLineInput(p.id, null, 1000)), credit = 50_000, cust = c.id))
        assertEquals(50_000L, db.sales().balance(c.id))
        // second purchase would exceed the limit
        assertThrows(PosException.CreditLimitExceeded::class.java) { runBlocking { sales.checkout(req(listOf(CartLineInput(p.id, null, 1000)), credit = 50_000, cust = c.id)) } }
        // owner approval allows it
        sales.checkout(req(listOf(CartLineInput(p.id, null, 1000)), credit = 50_000, cust = c.id, approver = owner.id))
        assertEquals(100_000L, db.sales().balance(c.id))
        sales.recordDebtPayment(UUID.randomUUID().toString(), c.id, PaymentMethod.CASH, 20_000, null, cashier.id, shift.id)
        sales.recordDebtPayment(UUID.randomUUID().toString(), c.id, PaymentMethod.GCASH, 30_000, "g1", cashier.id, shift.id)
        assertEquals(50_000L, db.sales().balance(c.id))
        assertThrows(PosException.Invalid::class.java) { runBlocking { sales.recordDebtPayment(UUID.randomUUID().toString(), c.id, PaymentMethod.CASH, 50_001, null, cashier.id, shift.id) } }
    }

    @Test fun repaymentRetry_isIdempotent() = runBlocking {
        val p = product("X", 10_000, 10_000).also { add(it) }; val c = customer()
        sales.checkout(req(listOf(CartLineInput(p.id, null, 1000)), credit = 10_000, cust = c.id))
        val pid = UUID.randomUUID().toString()
        sales.recordDebtPayment(pid, c.id, PaymentMethod.CASH, 4_000, null, cashier.id, shift.id)
        sales.recordDebtPayment(pid, c.id, PaymentMethod.CASH, 4_000, null, cashier.id, shift.id)
        assertEquals(6_000L, db.sales().balance(c.id))
    }

    @Test fun failureMidCart_rollsBackEverything() = runBlocking {
        val a = product("A", 1_000, 10_000).also { add(it) }; val b = product("B", 1_000, 1_000).also { add(it) }
        val id = UUID.randomUUID().toString()
        assertThrows(PosException.InsufficientStock::class.java) {
            runBlocking { sales.checkout(req(listOf(CartLineInput(a.id, null, 1000), CartLineInput(b.id, null, 2000)), listOf(PaymentInput(PaymentMethod.CASH, 5_000)), id = id)) }
        }
        assertNull(db.sales().sale(id)); assertEquals(10_000L, db.catalog().get(a.id)!!.stockMilli); assertTrue(db.admin().pending(10).isEmpty())
    }

    @Test fun unauthorizedDiscount_rejected_butOwnerApprovalAllows() = runBlocking {
        val p = product("P", 10_000, 10_000).also { add(it) }
        val lines = listOf(CartLineInput(p.id, null, 1000)); val pay = listOf(PaymentInput(PaymentMethod.CASH, 10_000))
        assertThrows(PosException.PermissionDenied::class.java) { runBlocking { sales.checkout(req(lines, pay, discount = 1_000, by = helper)) } }
        val r = sales.checkout(req(lines, pay, discount = 1_000, approver = owner.id)); assertEquals(9_000L, r.sale.totalCentavos)
    }

    @Test fun historicalReceipt_unchangedAfterPriceEdit() = runBlocking {
        val p = product("Soda", 2_000, 10_000).also { add(it) }
        val r = sales.checkout(req(listOf(CartLineInput(p.id, null, 1000)), listOf(PaymentInput(PaymentMethod.CASH, 2_000))))
        db.catalog().update(db.catalog().get(p.id)!!.copy(priceCentavos = 9_999, name = "Renamed"))
        val item = db.sales().items(r.sale.id).single(); assertEquals(2_000L, item.unitPriceCentavos); assertEquals("Soda", item.productNameSnapshot)
    }

    @Test fun concurrentSales_neverOversell() = runBlocking {
        val p = product("Last5", 100, 5_000).also { add(it) }
        val results = coroutineScope { (1..12).map { async(Dispatchers.IO) { runCatching { sales.checkout(req(listOf(CartLineInput(p.id, null, 1000)), listOf(PaymentInput(PaymentMethod.CASH, 100)))) } } }.awaitAll() }
        assertEquals(5, results.count { it.isSuccess }); assertEquals(0L, db.catalog().get(p.id)!!.stockMilli)
    }

    @Test fun voidSale_restocksAndRefunds_thenZReadReflectsIt() = runBlocking {
        val p = product("V", 1_000, 10_000).also { add(it) }
        val r = sales.checkout(req(listOf(CartLineInput(p.id, null, 3000)), listOf(PaymentInput(PaymentMethod.CASH, 3_000))))
        assertThrows(PosException.PermissionDenied::class.java) { runBlocking { sales.voidSale(r.sale.id, cashier.id, "oops") } }
        sales.voidSale(r.sale.id, owner.id, "customer returned")
        assertEquals(10_000L, db.catalog().get(p.id)!!.stockMilli)
        val z = shifts.preview(shift.id, null)
        assertEquals(100_000L, z.expectedCash)      // sale cash in, refund out
        assertEquals(0L, z.net)
    }

    @Test fun zRead_closeFreezesAndMatchesCount() = runBlocking {
        val p = product("Z", 15_000, 10_000).also { add(it) }; val c = customer()
        sales.checkout(req(listOf(CartLineInput(p.id, null, 1000)), listOf(PaymentInput(PaymentMethod.CASH, 5_000), PaymentInput(PaymentMethod.GCASH, 10_000))))
        sales.checkout(req(listOf(CartLineInput(p.id, null, 1000)), credit = 15_000, cust = c.id))
        sales.recordDebtPayment(UUID.randomUUID().toString(), c.id, PaymentMethod.CASH, 4_000, null, cashier.id, shift.id)
        shifts.cashMove(shift.id, CashMoveType.EXPENSE, 1_000, "ice", cashier.id)
        val z = shifts.preview(shift.id, null)
        assertEquals(100_000L + 5_000 + 4_000 - 1_000, z.expectedCash); assertEquals(15_000L, z.creditIssued); assertEquals(30_000L, z.net)
        shifts.close(shift.id, 108_000, cashier.id)
        assertThrows(PosException.Invalid::class.java) { runBlocking { shifts.close(shift.id, 108_000, cashier.id) } }
    }

    @Test fun pinLockout_afterFiveFailures() = runBlocking {
        val auth = AuthService(db)
        repeat(5) { assertThrows(PosException.Auth::class.java) { runBlocking { auth.authenticate(cashier.id, "0000") } } }
        assertThrows(PosException.Auth::class.java) { runBlocking { auth.authenticate(cashier.id, "1234") } }   // locked even with the right PIN
    }

    // ---------- purchasing / import / bulk / multi-device ----------
    @Test fun purchaseOrder_partialReceiving_andOverReceiptRejected() = runBlocking {
        val p = product("Sugar", 5_000, 0, "kg", frac = true).also { add(it) }
        val po = PurchasingService(db, inv)
        val order = po.createDraft(null, listOf(NewPoLine(p.id, null, 10_000, 4_000)), null, owner.id)
        assertThrows(PosException.Invalid::class.java) { runBlocking { po.receive(order.id, emptyMap(), null, owner.id) } }   // still a draft
        po.markOrdered(order.id, owner.id)
        val line = db.catalog().poLinesNow(order.id).single()
        po.receive(order.id, mapOf(line.id to 6_000L), "DR-1", owner.id)
        assertEquals(PoStatus.PARTIAL, db.catalog().po(order.id)!!.status); assertEquals(6_000L, db.catalog().get(p.id)!!.stockMilli)
        assertThrows(PosException.Invalid::class.java) { runBlocking { po.receive(order.id, mapOf(line.id to 5_000L), null, owner.id) } }
        po.receive(order.id, mapOf(line.id to 4_000L), null, owner.id)
        assertEquals(PoStatus.RECEIVED, db.catalog().po(order.id)!!.status); assertEquals(10_000L, db.catalog().get(p.id)!!.stockMilli)
    }

    @Test fun csvImport_dryRunWritesNothing_thenApplyCreatesAndUpdates() = runBlocking {
        val existing = product("Old", 1_000, 5_000).copy(barcode = "111").also { add(it) }
        val (rows, errs) = ProductCsv.parse("barcode,name,price,stock\n111,Old,12.00,8\n222,New Item,5.00,10\n")
        assertTrue(errs.isEmpty())
        val dry = inv.importProducts(rows, owner.id, apply = false)
        assertEquals(1, dry.created); assertEquals(1, dry.updated); assertNull(db.catalog().byBarcodeAny("222")); assertEquals(1_000L, db.catalog().get(existing.id)!!.priceCentavos)
        inv.importProducts(rows, owner.id, apply = true)
        assertEquals(1_200L, db.catalog().get(existing.id)!!.priceCentavos); assertEquals(8_000L, db.catalog().get(existing.id)!!.stockMilli)
        assertEquals(10_000L, db.catalog().byBarcodeAny("222")!!.stockMilli)
        assertThrows(PosException.PermissionDenied::class.java) { runBlocking { inv.importProducts(rows, cashier.id, apply = true) } }
    }

    @Test fun bulkPriceAndCounts() = runBlocking {
        val a = product("A", 1_000, 5_000).also { add(it) }; val b = product("B", 2_000, 3_000).also { add(it) }
        assertEquals(2, inv.bulkPrice(listOf(a.id, b.id), BulkPriceMode.PERCENT_BP, 1_000, owner.id))      // +10%
        assertEquals(1_100L, db.catalog().get(a.id)!!.priceCentavos); assertEquals(2_200L, db.catalog().get(b.id)!!.priceCentavos)
        inv.bulkSetCounts(mapOf(a.id to 2_000L), owner.id); assertEquals(2_000L, db.catalog().get(a.id)!!.stockMilli)
        assertThrows(PosException.PermissionDenied::class.java) { runBlocking { inv.bulkPrice(listOf(a.id), BulkPriceMode.SET_CENTAVOS, 1, cashier.id) } }
    }

    @Test fun twoDevices_exchangeSale_idempotently_andFlagStockConflict() = runBlocking {
        val p = product("Shared", 1_000, 1_000).also { add(it) }                  // 1 piece in stock on device A
        val r = sales.checkout(req(listOf(CartLineInput(p.id, null, 1000)), listOf(PaymentInput(PaymentMethod.CASH, 1_000))))
        val outbox = db.admin().pending(10).single { it.entityType == "SALE" }
        // "device B": a second empty database receives the same events (twice, to prove idempotence)
        val dbB = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            dbB.catalog().insert(p.copy(stockMilli = 0))                          // B already sold its last piece too -> will go negative
            val ev = RemoteEvent(1, outbox.id, "devA", "SALE", "CREATE", outbox.createdAt, JSONObject(outbox.payload))
            val applier = SyncApplier(dbB); applier.apply(listOf(ev)); applier.apply(listOf(ev))
            assertNotNull(dbB.sales().sale(r.sale.id)); assertEquals(-1_000L, dbB.catalog().get(p.id)!!.stockMilli)    // applied once, not twice
            assertEquals(1, dbB.sales().recentSales(10).first().size)
        } finally { dbB.close() }
    }

    @Test fun expiredProduct_cannotBeSold_butLastDayCan() = runBlocking {
        val today = ph.sarisari.mpos.util.Expiry.today()
        val old = product("Old milk", 5_000, 5_000).copy(expiryDate = today.minusDays(1).toString()).also { add(it) }
        val ok = product("Fresh milk", 5_000, 5_000).copy(expiryDate = today.toString()).also { add(it) }
        val pay = listOf(PaymentInput(PaymentMethod.CASH, 5_000))
        assertThrows(PosException.InvalidCart::class.java) { runBlocking { sales.checkout(req(listOf(CartLineInput(old.id, null, 1000)), pay)) } }
        sales.checkout(req(listOf(CartLineInput(ok.id, null, 1000)), pay))
        assertEquals(5_000L, db.catalog().get(old.id)!!.stockMilli)                 // nothing deducted for the rejected sale
    }

    @Test fun receiving_keepsNearestExpiry_andRejectsBadDate() = runBlocking {
        val p = product("Yogurt", 2_000, 0).also { add(it) }
        inv.receiveStock(p.id, null, 5_000, 0, null, null, owner.id, null, "2026-12-01", "A")
        assertEquals("2026-12-01", db.catalog().get(p.id)!!.expiryDate)
        inv.receiveStock(p.id, null, 5_000, 0, null, null, owner.id, null, "2027-02-01", "B")          // later delivery: keep the nearer date
        assertEquals("2026-12-01", db.catalog().get(p.id)!!.expiryDate)
        inv.receiveStock(p.id, null, 5_000, 0, null, null, owner.id, null, "2026-11-10", "C")          // earlier date wins
        assertEquals("2026-11-10", db.catalog().get(p.id)!!.expiryDate)
        assertThrows(PosException.Invalid::class.java) { runBlocking { inv.receiveStock(p.id, null, 1_000, 0, null, null, owner.id, null, "31/12/2026", null) } }
    }

    @Test fun gcashServices_moveTheDrawer_andVoidReverses() = runBlocking {
        val svc = CashServiceService(db)
        svc.record(UUID.randomUUID().toString(), shift.id, cashier.id, ServiceType.CASH_IN, "GCash", 100_000, 2_000, true, "Rosa", null)    // +1,020
        val out = svc.record(UUID.randomUUID().toString(), shift.id, cashier.id, ServiceType.CASH_OUT, "GCash", 50_000, 1_000, true, null, "ref1")  // -500 +10
        assertEquals(100_000L + 102_000 - 50_000 + 1_000, shifts.preview(shift.id, null).expectedCash)
        assertEquals(3_000L, shifts.preview(shift.id, null).serviceFees)
        assertThrows(PosException.PermissionDenied::class.java) { runBlocking { svc.void(out.id, "typo", cashier.id) } }
        svc.void(out.id, "typo", owner.id)
        assertEquals(100_000L + 102_000, shifts.preview(shift.id, null).expectedCash)
        val id = UUID.randomUUID().toString()                                                                                           // idempotent record
        svc.record(id, shift.id, cashier.id, ServiceType.LOAD, "Globe", 10_000, 500, true, null, null); svc.record(id, shift.id, cashier.id, ServiceType.LOAD, "Globe", 10_000, 500, true, null, null)
        assertEquals(100_000L + 102_000 + 10_500, shifts.preview(shift.id, null).expectedCash)
    }

    @Test fun promo_appliesAtCheckout_onlyWhenEnabled_andManualDiscountStillNeedsApproval() = runBlocking {
        val p = product("Candy", 2_000, 100_000).also { add(it) }
        db.catalog().upsertPromo(Promo("pr1", "3 for 50", PromoType.BUNDLE, p.id, 3_000, 5_000, 0, 0, null, null, true, 0))
        val lines = listOf(CartLineInput(p.id, null, 3000))
        val on = sales.checkout(req(lines, listOf(PaymentInput(PaymentMethod.CASH, 5_000))).copy(applyPromos = true))
        assertEquals(5_000L, on.sale.totalCentavos); assertEquals(1_000L, on.sale.discountCentavos)
        val off = sales.checkout(req(lines, listOf(PaymentInput(PaymentMethod.CASH, 6_000))))
        assertEquals(6_000L, off.sale.totalCentavos)
        assertThrows(PosException.PermissionDenied::class.java) { runBlocking { sales.checkout(req(lines, listOf(PaymentInput(PaymentMethod.CASH, 6_000)), discount = 500).copy(applyPromos = true)) } }
    }

    @Test fun supplierBills_partialPayments_overpayRejected_drawerPaymentHitsZRead() = runBlocking {
        val pay = PayablesService(db)
        val bill = pay.addBill(null, null, "DR-77", 100_000, "2026-12-01", null, owner.id)
        assertThrows(PosException.PermissionDenied::class.java) { runBlocking { pay.addBill(null, null, "x", 100, null, null, helper.id) } }
        pay.pay(bill.id, 30_000, "GCASH", cashier.id)
        assertEquals(100_000L, shifts.preview(shift.id, null).expectedCash)                          // GCash payment does not touch the drawer
        pay.pay(bill.id, 20_000, "CASH_DRAWER", cashier.id)
        assertEquals(80_000L, shifts.preview(shift.id, null).expectedCash)                           // 100,000 float - 20,000 paid from drawer
        assertEquals(50_000L, 100_000L - db.catalog().billPaid(bill.id))
        assertThrows(PosException.Invalid::class.java) { runBlocking { pay.pay(bill.id, 50_001, "BANK", owner.id) } }
        assertThrows(PosException.Invalid::class.java) { runBlocking { pay.cancel(bill.id, owner.id) } }  // has payments
    }

    @Test fun assignBarcodes_isUniqueValid_andNeverOverwrites() = runBlocking {
        val a = product("A", 100, 1).also { add(it) }; val b = product("B", 100, 1).copy(barcode = "4800123").also { add(it) }; val c = product("C", 100, 1).also { add(it) }
        assertEquals(2, LabelService(db).assignBarcodes(listOf(a.id, b.id, c.id), owner.id))
        val ca = db.catalog().get(a.id)!!.barcode!!; val cc = db.catalog().get(c.id)!!.barcode!!
        assertTrue(Ean.isValid(ca) && Ean.isValid(cc)); assertNotEquals(ca, cc); assertEquals("4800123", db.catalog().get(b.id)!!.barcode)
    }
}
