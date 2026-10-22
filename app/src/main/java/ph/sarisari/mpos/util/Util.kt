package ph.sarisari.mpos.util

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** All money is stored as Long centavos. Never use Float/Double for money. */
object Money {
    fun format(centavos: Long): String {
        val a = Math.abs(centavos)
        val s = String.format(Locale.US, "%,d.%02d", a / 100, a % 100)
        return (if (centavos < 0) "-" else "") + "₱" + s
    }

    fun parse(text: String): Long? = try {
        val t = text.trim().replace(",", "").removePrefix("₱")
        if (t.isEmpty()) null
        else BigDecimal(t).setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact()
    } catch (e: Exception) { null }
}

/** Quantities are stored as Long thousandths ("milli") of a unit: 1.25 kg = 1250. Decimal-safe. */
object Qty {
    fun parse(text: String): Long? = try {
        val t = text.trim()
        if (t.isEmpty()) null
        else BigDecimal(t).setScale(3, RoundingMode.HALF_UP).movePointRight(3).longValueExact()
    } catch (e: Exception) { null }

    fun format(milli: Long): String =
        if (milli % 1000 == 0L) (milli / 1000).toString()
        else BigDecimal.valueOf(milli).movePointLeft(3).stripTrailingZeros().toPlainString()

    /** price (centavos per 1 unit) x qty (milli) -> centavos, rounded half-up. */
    fun lineTotal(unitPriceCentavos: Long, qtyMilli: Long): Long = (unitPriceCentavos * qtyMilli + 500) / 1000

    /** qty in a sale/receiving unit -> qty in the product's base unit (both milli; factor is base-units-per-unit x1000). */
    fun toBase(qtyMilli: Long, factorMilli: Long): Long = (qtyMilli * factorMilli + 500) / 1000
}

object Manila {
    val tz: TimeZone = TimeZone.getTimeZone("Asia/Manila")
    fun fmt(epochMs: Long, pattern: String = "yyyy-MM-dd HH:mm"): String =
        SimpleDateFormat(pattern, Locale.US).apply { timeZone = tz }.format(Date(epochMs))
    fun dayKey(epochMs: Long) = fmt(epochMs, "yyyyMMdd")

    /** [startOfDay, endOfDay] in UTC epoch ms for the Manila day containing [epochMs]. */
    fun dayRange(epochMs: Long): LongRange {
        val cal = java.util.Calendar.getInstance(tz).apply {
            timeInMillis = epochMs
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
        return start until cal.timeInMillis
    }
}
