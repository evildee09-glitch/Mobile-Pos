package ph.sarisari.mpos

import org.junit.Assert.*
import org.junit.Test
import ph.sarisari.mpos.backup.BackupCrypto
import ph.sarisari.mpos.sync.SyncCodec
import ph.sarisari.mpos.data.*
import ph.sarisari.mpos.domain.*
import ph.sarisari.mpos.util.Money
import ph.sarisari.mpos.util.Qty

class MoneyQtyTest {
    @Test fun formatsCentavos() { assertEquals("₱125.50", Money.format(12550)); assertEquals("-₱0.05", Money.format(-5)); assertEquals("₱1,234.00", Money.format(123400)) }
    @Test fun parsesPesos() { assertEquals(12550L, Money.parse("125.50")); assertEquals(12550L, Money.parse("₱125.5")); assertNull(Money.parse("abc")); assertNull(Money.parse("")) }
    @Test fun parsesAndFormatsQty() { assertEquals(1250L, Qty.parse("1.25")); assertEquals("1.25", Qty.format(1250)); assertEquals("3", Qty.format(3000)) }
    @Test fun lineTotalRoundsHalfUp() {
        assertEquals(1875L, Qty.lineTotal(7500, 250))      // 0.25 kg of ₱75/kg
        assertEquals(2L, Qty.lineTotal(3, 500))           // 1.5 -> 2
        assertEquals(0L, Qty.lineTotal(3, 100))           // 0.3 centavo -> 0
    }
    @Test fun caseToBase() { assertEquals(24_000L, Qty.toBase(1000, 24_000)); assertEquals(12_000L, Qty.toBase(500, 24_000)) }
}

class PinHasherTest {
    @Test fun hashVerifyAndSaltUniqueness() {
        val s1 = PinHasher.newSalt(); val s2 = PinHasher.newSalt()
        val h = PinHasher.hash("1234", s1)
        assertTrue(PinHasher.verify("1234", s1, h)); assertFalse(PinHasher.verify("1235", s1, h))
        assertNotEquals(s1, s2); assertNotEquals(h, PinHasher.hash("1234", s2))
    }
    @Test fun pinValidation() { assertTrue(PinHasher.isValidPin("0123")); assertFalse(PinHasher.isValidPin("123")); assertFalse(PinHasher.isValidPin("12a4")) }
}

class RbacTest {
    @Test fun helperCanOnlySell() { assertTrue(Rbac.can(Role.HELPER, Perm.SELL)); assertFalse(Rbac.can(Role.HELPER, Perm.DISCOUNT)); assertFalse(Rbac.can(Role.HELPER, Perm.COLLECT_DEBT)) }
    @Test fun cashierNeedsApprovalForDiscountAndVoid() { assertFalse(Rbac.can(Role.CASHIER, Perm.DISCOUNT)); assertFalse(Rbac.can(Role.CASHIER, Perm.VOID)); assertTrue(Rbac.can(Role.OWNER, Perm.RESTORE)) }
}

class ZReadTest {
    @Test fun expectedCashFollowsFormula() {
        val z = ZReadCalculator.compute(ZInputs(
            openingFloat = 100_000, gross = 15_000, discounts = 0, refunds = 0,
            payments = listOf(
                PaymentTotal(PaymentKind.SALE, PaymentMethod.CASH, 5_000), PaymentTotal(PaymentKind.SALE, PaymentMethod.GCASH, 10_000),
                PaymentTotal(PaymentKind.DEBT_PAYMENT, PaymentMethod.CASH, 20_000), PaymentTotal(PaymentKind.DEBT_PAYMENT, PaymentMethod.MAYA, 7_000),
                PaymentTotal(PaymentKind.REFUND, PaymentMethod.CASH, 1_000)),
            creditIssued = 30_000,
            moves = listOf(MovementTotal(CashMoveType.ADD, 1_000), MovementTotal(CashMoveType.EXPENSE, 3_000), MovementTotal(CashMoveType.WITHDRAW, 2_000)),
            perCashier = emptyList(), counted = 119_000))
        assertEquals(100_000L + 5_000 + 20_000 + 1_000 - 1_000 - 3_000 - 2_000, z.expectedCash)   // 120_000
        assertEquals(-1_000L, z.variance)                       // short ₱10
        assertEquals(mapOf(PaymentMethod.GCASH to 10_000L), z.digitalSales)
        assertEquals(15_000L, z.net)                            // debt collections are NOT sales
    }
}

class ReceiptTest {
    private fun data() = ReceiptData("Aling Nena Store", "0917", Sale("s1", "ABCD-20261020-00001", null, "c", "sh", 15_000, 0, 15_000, 0, SaleStatus.COMPLETED, 0),
        listOf(SaleItem("i", "s1", "p", "Coke 330ml", "can", 2000, 5_000, 10_000, 1000, 2000, 3_000), SaleItem("i2", "s1", "p2", "Rice", "kg", 500, 10_000, 5_000, 1000, 500, 8_000)),
        listOf(Payment("p1", "s1", null, "sh", PaymentKind.SALE, PaymentMethod.CASH, 5_000, 10_000, PaymentStatus.VERIFIED, null, 0),
            Payment("p2", "s1", null, "sh", PaymentKind.SALE, PaymentMethod.GCASH, 10_000, null, PaymentStatus.RECORDED, "123", 0)), "Ana", null, null)
    @Test fun noLineExceedsPaperWidth() { for (w in listOf(32, 48)) ReceiptFormatter.format(data(), w).lines().forEach { assertTrue("$w: '$it'", it.length <= w) } }
    @Test fun showsCashChangeAndWallet() {
        val t = ReceiptFormatter.format(data(), 32)
        assertTrue(t.contains("Cash received") && t.contains("₱100.00")); assertTrue(t.contains("Change") && t.contains("₱50.00")); assertTrue(t.contains("GCASH #123"))
    }
}

class BackupCryptoTest {
    @Test fun roundTrip() { val d = ByteArray(5000) { it.toByte() }; assertArrayEquals(d, BackupCrypto.decrypt(BackupCrypto.encrypt(d, "secret1".toCharArray()), "secret1".toCharArray())) }
    @Test fun wrongPassphraseFails() { val e = BackupCrypto.encrypt(ByteArray(100), "secret1".toCharArray()); assertThrows(IllegalArgumentException::class.java) { BackupCrypto.decrypt(e, "nope123".toCharArray()) } }
    @Test fun tamperingDetected() { val e = BackupCrypto.encrypt(ByteArray(100), "secret1".toCharArray()); e[e.size - 1] = (e[e.size - 1] + 1).toByte(); assertThrows(IllegalArgumentException::class.java) { BackupCrypto.decrypt(e, "secret1".toCharArray()) } }
}

class CsvTest {
    @Test fun parsesQuotesCommasAndBom() {
        val t = Csv.parse("\uFEFFname,price\n\"Coke, 330ml\",20\n\"He said \"\"hi\"\"\",5\n\n")
        assertEquals(listOf("Coke, 330ml", "20"), t[1]); assertEquals("He said \"hi\"", t[2][0]); assertEquals(3, t.size)
    }
    @Test fun productCsvParsesAnyColumnOrderAndReportsBadRows() {
        val (rows, errs) = ProductCsv.parse("price,name,barcode,stock\n12.50,Noodles,4800,24\nabc,Bad,111,1\n")
        assertEquals(1, rows.size); assertEquals(1250L, rows[0].price); assertEquals(24_000L, rows[0].stockMilli); assertEquals(1, errs.size)
    }
    @Test fun exportThenParseRoundTrips() {
        val p = Product("1", "S1", "4800", "Soda, cold", "Drinks", 1000, 1500, 3500, "bottle", false, 2000, null, false, true, null, null, 0, 0)
        val (rows, errs) = ProductCsv.parse(ProductCsv.export(listOf(p)))
        assertTrue(errs.isEmpty()); assertEquals("Soda, cold", rows[0].name); assertEquals(1500L, rows[0].price); assertEquals(3500L, rows[0].stockMilli)
    }
}

class ReminderTest {
    @Test fun normalizesPhilippineNumbers() {
        assertEquals("+639171234567", Reminders.normalizePhone("0917 123 4567")); assertEquals("+639171234567", Reminders.normalizePhone("+63 917-123-4567"))
        assertEquals("+639171234567", Reminders.normalizePhone("9171234567")); assertNull(Reminders.normalizePhone("12345")); assertNull(Reminders.normalizePhone(null))
    }
    @Test fun messageMentionsAmount() { assertTrue(Reminders.message("Nena Store", "Rosa", 25_050).contains("₱250.50")) }
}

class SyncCodecTest {
    @Test fun saleBundleRoundTrips() {
        val sale = Sale("s1", "A-1", null, "c", "sh", 10_000, 500, 9_500, 0, SaleStatus.COMPLETED, 123)
        val item = SaleItem("i1", "s1", "p1", "Coke", "can", 2000, 5_000, 10_000, 1000, 2000, 3_000)
        val pay = Payment("pay1", "s1", null, "sh", PaymentKind.SALE, PaymentMethod.CASH, 9_500, 10_000, PaymentStatus.VERIFIED, null, 123)
        val j = org.json.JSONObject(SyncCodec.saleBundle(sale, listOf(item), listOf(pay), emptyList(), emptyList()).toString())
        assertEquals(sale, SyncCodec.toSale(j.getJSONObject("sale")))
        assertEquals(item, SyncCodec.list(j, "items", SyncCodec::toItem)[0]); assertEquals(pay, SyncCodec.list(j, "payments", SyncCodec::toPayment)[0])
    }
    @Test fun productRoundTripKeepsNullsAndUnits() {
        val p = Product("p", null, "123", "Rice", null, 100, 200, 5000, "kg", true, 1000, null, true, true, null, null, 1, 2)
        val j = org.json.JSONObject(SyncCodec.product(p, listOf(ProductUnit("p", "sack", 25_000, null))).toString())
        assertEquals(p.copy(stockMilli = 0), SyncCodec.toProduct(j, 0)); assertEquals(ProductUnit("p", "sack", 25_000, null), SyncCodec.toUnits(j)[0])
    }
}
