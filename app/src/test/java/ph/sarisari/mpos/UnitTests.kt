package ph.sarisari.mpos

import org.junit.Assert.*
import org.junit.Test
import ph.sarisari.mpos.backup.BackupCrypto
import ph.sarisari.mpos.sync.SyncCodec
import ph.sarisari.mpos.data.*
import ph.sarisari.mpos.domain.*
import ph.sarisari.mpos.util.Money
import ph.sarisari.mpos.util.Expiry
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

class ExpiryTest {
    private val now = java.time.LocalDate.parse("2026-10-25").atStartOfDay(java.time.ZoneId.of("Asia/Manila")).plusHours(10).toInstant().toEpochMilli()
    @Test fun daysLeftUsesManilaDate() {
        assertEquals(0L, Expiry.daysLeft("2026-10-25", now)); assertEquals(7L, Expiry.daysLeft("2026-11-01", now)); assertEquals(-3L, Expiry.daysLeft("2026-10-22", now))
        assertNull(Expiry.daysLeft(null, now)); assertNull(Expiry.daysLeft("31/12/2026", now)); assertNull(Expiry.daysLeft("", now))
    }
    @Test fun lastDayIsStillSellable() { assertTrue(Expiry.daysLeft("2026-10-25", now)!! >= 0); assertTrue(Expiry.daysLeft("2026-10-24", now)!! < 0) }
    @Test fun labels() { assertEquals("Expires today", Expiry.label(0)); assertEquals("Expires in 12d", Expiry.label(12)); assertEquals("Expired 2d ago", Expiry.label(-2)) }
    @Test fun csvCarriesExpiryAndRejectsBadDates() {
        val (rows, errs) = ProductCsv.parse("name,price,expiry\nMilk,50,2026-12-31\nBad,10,12/31/2026\n")
        assertEquals("2026-12-31", rows.single().expiry); assertEquals(1, errs.size)
        val p = Product("1", null, "48", "Milk", null, 0, 5000, 1000, "pc", false, 0, null, false, true, "2026-12-31", null, 0, 0)
        assertEquals("2026-12-31", ProductCsv.parse(ProductCsv.export(listOf(p))).first.single().expiry)
    }
    @Test fun syncCodecKeepsExpiryAndBatch() {
        val p = Product("p", null, null, "Yogurt", null, 1, 2, 3, "cup", false, 0, null, false, true, "2026-11-05", "B7", 1, 2)
        assertEquals(p.copy(stockMilli = 0), ph.sarisari.mpos.sync.SyncCodec.toProduct(org.json.JSONObject(ph.sarisari.mpos.sync.SyncCodec.product(p, emptyList()).toString()), 0))
    }
}

class LicenseTest {
    private val kp = java.security.KeyPairGenerator.getInstance("EC").apply { initialize(java.security.spec.ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val pubB64 = java.util.Base64.getEncoder().encodeToString(kp.public.encoded)
    private fun token(expSec: Long, plan: String = "PREMIUM"): String {
        val b64 = java.util.Base64.getUrlEncoder().withoutPadding()
        val body = b64.encodeToString("""{"v":1,"id":"t1","sub":"Test Store","plan":"$plan","iat":1,"exp":$expSec}""".toByteArray())
        val sig = java.security.Signature.getInstance("SHA256withECDSA").run { initSign(kp.private); update(body.toByteArray()); sign() }
        return body + "." + b64.encodeToString(sig)
    }
    private val day = 86_400L

    @Test fun validTokenVerifies_tamperedOrWrongKeyRejected() {
        val t = token(2_000_000_000)
        assertEquals("Test Store", LicenseVerifier.verify(t, pubB64).holder)
        val (b, sg) = t.split("."); val forged = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(String(java.util.Base64.getUrlDecoder().decode(b)).replace("2000000000", "2999999999").toByteArray()) + "." + sg
        assertThrows(PosException.Invalid::class.java) { LicenseVerifier.verify(forged, pubB64) }
        val other = java.security.KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        assertThrows(PosException.Invalid::class.java) { LicenseVerifier.verify(t, java.util.Base64.getEncoder().encodeToString(other.public.encoded)) }
        assertThrows(PosException.Invalid::class.java) { LicenseVerifier.verify("garbage", pubB64) }
    }
    private fun derToRaw(der: ByteArray): ByteArray {            // what Node's ieee-p1363 produces
        var i = 3; val rl = der[i - 1].toInt(); var r = der.copyOfRange(i, i + rl); i += rl + 2; val sl = der[i - 1].toInt(); var s = der.copyOfRange(i, i + sl)
        fun fix(b: ByteArray): ByteArray { var x = b; while (x.size > 32 && x[0] == 0.toByte()) x = x.copyOfRange(1, x.size); return ByteArray(32 - x.size) + x }
        return fix(r) + fix(s)
    }
    private fun shortToken(day: Int, holder: String = "Aling Nena Store"): String {
        val payload = byteArrayOf(1, (day shr 8).toByte(), day.toByte(), 0x0b, 0xea.toByte(), 0xa2.toByte()) + holder.toByteArray()
        val der = java.security.Signature.getInstance("SHA256withECDSA").run { initSign(kp.private); update(payload); sign() }
        return "SS-" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(payload + derToRaw(der))
    }
    @Test fun shortCodeVerifies_andRejectsTampering() {
        repeat(20) {                                                    // repeat: signatures vary in size, exercising the raw->DER padding logic
            val l = LicenseVerifier.verify(shortToken(20_000), pubB64)
            assertEquals("Aling Nena Store", l.holder); assertEquals("lic-0beaa2", l.id); assertEquals(20_000L * 86_400 + 86_399, l.expiresAt)
        }
        val t = shortToken(20_000); val flipped = t.dropLast(2) + (if (t.last() == 'A') "BB" else "AA")
        assertThrows(PosException.Invalid::class.java) { LicenseVerifier.verify(flipped, pubB64) }
        assertThrows(PosException.Invalid::class.java) { LicenseVerifier.verify("SS-abc", pubB64) }
        val moreDays = java.util.Base64.getUrlDecoder().decode(t.removePrefix("SS-")).also { it[2] = (it[2] + 1).toByte() }
        assertThrows(PosException.Invalid::class.java) { LicenseVerifier.verify("SS-" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(moreDays), pubB64) }   // edited expiry
    }
    @Test fun policy_trial_premium_grace_free() {
        val lic = LicenseVerifier.Lic("t", "S", "PREMIUM", 0, 1000 * day)
        assertEquals(Plan.UNRESTRICTED, LicensePolicy.evaluate(5, null, null, configured = false).plan)
        assertEquals(Plan.TRIAL, LicensePolicy.evaluate(10 * day, 0, null, true).plan); assertEquals(20L, LicensePolicy.evaluate(10 * day, 0, null, true).daysLeft)
        assertEquals(Plan.FREE, LicensePolicy.evaluate(31 * day, 0, null, true).plan)
        assertEquals(Plan.PREMIUM, LicensePolicy.evaluate(500 * day, 0, lic, true).plan)
        assertEquals(Plan.GRACE, LicensePolicy.evaluate(1005 * day, 0, lic, true).plan)
        assertEquals(Plan.FREE, LicensePolicy.evaluate(1015 * day, 0, lic, true).plan)
        assertEquals(Plan.PREMIUM, LicensePolicy.evaluate(1005 * day, null, lic.copy(expiresAt = 2000 * day), true).plan)
    }
    @Test fun freePlanBlocksEveryPremiumFeature_butTrialAllows() {
        assertTrue(Feature.values().all { !LicenseState(Plan.FREE).allows(it) }); assertTrue(Feature.values().all { LicenseState(Plan.TRIAL, 5).allows(it) })
    }
}

class SmartReorderTest {
    private fun prod(id: String, stock: Long, frac: Boolean = false) = Product(id, null, null, id, null, 100, 200, stock, "pc", frac, 0, null, false, true, null, null, 0, 0)
    @Test fun ordersByDaysLeft_andRoundsUpWholePieces() {
        val r = SmartReorder.suggest(listOf(prod("a", 3000), prod("b", 100_000), prod("c", 0)), mapOf("a" to 30_000L, "b" to 30_000L, "c" to 15_000L))
        assertEquals(listOf("c", "a"), r.map { it.product.id })            // c sells 0.5/day, stock 0 -> first; b has 100 days left -> not suggested
        val a = r.first { it.product.id == "a" }                           // 1/day (1000 milli), stock 3 -> 14 days need 14 - 3 = 11 pieces
        assertEquals(11_000L, a.suggestMilli); assertEquals(3.0, a.daysLeft, 0.001)
    }
    @Test fun noSalesMeansNoSuggestion() { assertTrue(SmartReorder.suggest(listOf(prod("x", 0)), emptyMap()).isEmpty()) }
}

class UtangAgingTest {
    private val d = 86_400_000L
    private fun e(c: String, t: Long, amt: Long) = LedgerEntry("$c$t$amt", c, null, null, null, if (amt > 0) LedgerType.CHARGE else LedgerType.PAYMENT, amt, null, t)
    @Test fun paymentsClearOldestChargesFirst() {
        val now = 100 * d
        val rows = UtangAging.compute(listOf(e("rosa", 0, 500), e("rosa", 50 * d, 300), e("rosa", 95 * d, 200), e("rosa", 96 * d, -600)), now)
        val r = rows.single()                                              // 600 paid: clears 500 (100d old) + 100 of the 300 (50d old)
        assertEquals(400L, r.balance); assertEquals(200L, r.d30 + r.d60); assertEquals(200L, r.current); assertEquals(0L, r.d90); assertEquals(50L, r.oldestDays)
    }
    @Test fun settledCustomersAreOmitted_andOldestFirst() {
        val rows = UtangAging.compute(listOf(e("a", 0, 100), e("a", 1, -100), e("b", 10 * d, 50), e("c", 80 * d, 70)), 100 * d)
        assertEquals(listOf("b", "c"), rows.map { it.customerId })
    }
}

class CashServiceTest {
    @Test fun drawerEffects() {
        assertEquals(1_020_00L to 0L, CashServiceService.drawerEffect(ServiceType.CASH_IN, 1_000_00, 20_00, true))
        assertEquals(1_000_00L to 0L, CashServiceService.drawerEffect(ServiceType.CASH_IN, 1_000_00, 20_00, false))
        assertEquals(20_00L to 1_000_00L, CashServiceService.drawerEffect(ServiceType.CASH_OUT, 1_000_00, 20_00, true))
        assertEquals(0L to 1_000_00L, CashServiceService.drawerEffect(ServiceType.CASH_OUT, 1_000_00, 20_00, false))
        assertEquals(105_00L to 0L, CashServiceService.drawerEffect(ServiceType.LOAD, 100_00, 5_00, true))
    }
    @Test fun zReadExpectedCashIncludesServices() {
        val z = ZReadCalculator.compute(ZInputs(100_000, 0, 0, 0, emptyList(), 0, emptyList(), emptyList(), null, serviceCashIn = 50_000, serviceCashOut = 30_000, serviceFees = 2_000, serviceCount = 2))
        assertEquals(120_000L, z.expectedCash); assertEquals(2_000L, z.serviceFees)
    }
}

class ReceiptBrandingTest {
    @Test fun headerFooterAppearOnlyWhenGiven() {
        val sale = Sale("s", "R-1", null, "c", "sh", 100, 0, 100, 0, SaleStatus.COMPLETED, 0)
        val base = ReceiptData("Store", null, sale, emptyList(), emptyList(), "Ana", null, null)
        val plain = ReceiptFormatter.format(base, 32); assertTrue(plain.contains("Thank you! Salamat po!"))
        val branded = ReceiptFormatter.format(base.copy(header = "123 Rizal St, Malay", footer = "Suki ka namin!"), 32)
        assertTrue(branded.contains("123 Rizal St, Malay") && branded.contains("Suki ka namin!")); assertFalse(branded.contains("Thank you! Salamat po!"))
    }
}

class PromoEngineTest {
    private val today = "2026-10-28"
    private fun promo(type: PromoType, qty: Long = 0, value: Long = 0, free: Long = 0, bp: Long = 0, start: String? = null, end: String? = null, active: Boolean = true, name: String = "p") =
        Promo(java.util.UUID.randomUUID().toString(), name, type, "coke", qty, value, free, bp, start, end, active, 0)
    private fun line(q: Long, price: Long = 2000) = PromoLine("coke", q, price)

    @Test fun bundleAppliesPerCompleteSet() {          // 3 for P50, normally P20 each
        val r = PromoEngine.compute(listOf(line(7_000)), listOf(promo(PromoType.BUNDLE, 3_000, 5_000)), today)
        assertEquals(2_000L, r.totalDiscount)           // 2 sets: 12,000 -> 10,000 ; the 7th piece pays full price
        assertEquals(0L, PromoEngine.compute(listOf(line(2_000)), listOf(promo(PromoType.BUNDLE, 3_000, 5_000)), today).totalDiscount)
    }
    @Test fun buyXGetY() { assertEquals(2_000L, PromoEngine.compute(listOf(line(7_000, 1_000)), listOf(promo(PromoType.BUY_X_GET_Y, 2_000, free = 1_000)), today).totalDiscount) }   // 2 groups of 3 -> 2 free
    @Test fun percentOff() { assertEquals(450L, PromoEngine.compute(listOf(line(3_000, 1_500)), listOf(promo(PromoType.PERCENT_OFF, bp = 1_000)), today).totalDiscount) }
    @Test fun volumePrice() {
        val v = promo(PromoType.VOLUME_PRICE, 5_000, 800)
        assertEquals(1_200L, PromoEngine.compute(listOf(line(6_000, 1_000)), listOf(v), today).totalDiscount); assertEquals(0L, PromoEngine.compute(listOf(line(4_000, 1_000)), listOf(v), today).totalDiscount)
    }
    @Test fun bestPromoWinsAndNeverStacks() {
        val r = PromoEngine.compute(listOf(line(6_000)), listOf(promo(PromoType.PERCENT_OFF, bp = 500, name = "5%"), promo(PromoType.BUNDLE, 3_000, 4_000, name = "bundle")), today)
        assertEquals(listOf("bundle"), r.applied); assertEquals(4_000L, r.totalDiscount)        // 2 sets save 2*(6000-4000)=4000 vs 5% = 600
    }
    @Test fun datesAndSwitchRespected() {
        assertEquals(0L, PromoEngine.compute(listOf(line(3_000)), listOf(promo(PromoType.PERCENT_OFF, bp = 1_000, end = "2026-10-27")), today).totalDiscount)
        assertEquals(0L, PromoEngine.compute(listOf(line(3_000)), listOf(promo(PromoType.PERCENT_OFF, bp = 1_000, start = "2026-10-29")), today).totalDiscount)
        assertEquals(0L, PromoEngine.compute(listOf(line(3_000)), listOf(promo(PromoType.PERCENT_OFF, bp = 1_000, active = false)), today).totalDiscount)
        assertTrue(PromoEngine.compute(listOf(line(3_000)), listOf(promo(PromoType.PERCENT_OFF, bp = 1_000, start = today, end = today)), today).totalDiscount > 0)   // inclusive
    }
    @Test fun linesOfSameProductAreCombined() { assertEquals(1_000L, PromoEngine.compute(listOf(line(1_000, 2000), line(2_000, 2000)), listOf(promo(PromoType.BUNDLE, 3_000, 5_000)), today).totalDiscount) }
}

class EanTest {
    @Test fun knownCodesAreValid() { listOf("4006381333931", "5901234123457", "73513537", "036000291452").forEach { assertTrue(it, Ean.isValid(it)) }; assertFalse(Ean.isValid("4006381333932")); assertFalse(Ean.isValid("abc")) }
    @Test fun moduleShape() {
        val m = Ean.modules("4006381333931")!!
        assertEquals(95, m.length); assertTrue(m.startsWith("101") && m.endsWith("101")); assertEquals("01010", m.substring(45, 50)); assertEquals(67, Ean.modules("73513537")!!.length)
        assertEquals(Ean.modules("0036000291452"), Ean.modules("036000291452"))           // UPC-A is EAN-13 with a leading 0
        assertNull(Ean.modules("1234")); assertNull(Ean.modules("4006381333932"))
    }
    @Test fun firstDigitSelectsLeftParity() { assertNotEquals(Ean.modules("4006381333931")!!.substring(3, 45), Ean.modules("5006381333937".let { it.dropLast(1) + Ean.checkDigit(it.dropLast(1)) })!!.substring(3, 45)) }
    @Test fun inStoreCodesAreValidAndDistinct() { val a = Ean.inStore(1); val b = Ean.inStore(2); assertTrue(Ean.isValid(a) && Ean.isValid(b)); assertNotEquals(a, b); assertTrue(a.startsWith("200")); assertEquals(13, a.length) }
}

class LabelEscPosTest {
    @Test fun printsBarcodeCommandWithCopies() {
        val bytes = LabelEscPos.build(listOf(LabelItem("Coke 330ml", "₱20.00", "4006381333931") to 2, LabelItem("Odd", "₱1.00", "123") to 1))
        val s = String(bytes, Charsets.ISO_8859_1)
        assertEquals(2, Regex("4006381333931").findAll(s).count())             // 2 copies; the unsupported code is skipped
        assertTrue(s.contains("\u001D" + "k" + "C" + "\r" + "4006381333931")); assertTrue(s.contains("P20.00")); assertFalse(s.contains("123\n"))
    }
}
