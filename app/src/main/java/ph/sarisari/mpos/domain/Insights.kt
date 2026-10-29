package ph.sarisari.mpos.domain

import ph.sarisari.mpos.data.LedgerEntry
import ph.sarisari.mpos.data.LedgerType
import ph.sarisari.mpos.data.Product

data class ReorderSuggestion(val product: Product, val perDayMilli: Double, val daysLeft: Double, val suggestMilli: Long)

/** Reorder from real sales speed instead of a fixed level. Pure function, easy to test. */
object SmartReorder {
    /**
     * @param sold base-unit quantity sold per product over the last [windowDays] days (milli)
     * Suggests products that will run out within [warnDays] and orders enough to cover [coverDays] of sales.
     */
    fun suggest(products: List<Product>, sold: Map<String, Long>, windowDays: Int = 30, coverDays: Int = 14, warnDays: Int = 10): List<ReorderSuggestion> =
        products.mapNotNull { p ->
            val perDay = (sold[p.id] ?: 0L).toDouble() / windowDays
            if (perDay <= 0.0) return@mapNotNull null
            val daysLeft = p.stockMilli / perDay
            if (daysLeft > warnDays) return@mapNotNull null
            val need = perDay * coverDays - p.stockMilli
            if (need <= 0) return@mapNotNull null
            val rounded = if (p.allowsFraction) (Math.ceil(need / 100.0) * 100).toLong() else (Math.ceil(need / 1000.0) * 1000).toLong()   // whole pieces unless sold by weight
            ReorderSuggestion(p, perDay, daysLeft, rounded)
        }.sortedBy { it.daysLeft }
}

data class AgingRow(val customerId: String, val balance: Long, val current: Long, val d30: Long, val d60: Long, val d90: Long, val oldestDays: Long)

/** How long each customer's unpaid Utang has been outstanding. Payments are applied to the OLDEST charges first (FIFO). */
object UtangAging {
    private const val DAY = 86_400_000L
    fun compute(entries: List<LedgerEntry>, now: Long): List<AgingRow> =
        entries.groupBy { it.customerId }.mapNotNull { (cid, list) ->
            val open = ArrayDeque<LongArray>()                      // [chargeTime, remaining]
            for (e in list.sortedBy { it.createdAt }) {
                if (e.amountCentavos > 0) open.addLast(longArrayOf(e.createdAt, e.amountCentavos))
                else { var credit = -e.amountCentavos; while (credit > 0 && open.isNotEmpty()) { val f = open.first(); val used = minOf(credit, f[1]); f[1] -= used; credit -= used; if (f[1] == 0L) open.removeFirst() } }
            }
            if (open.isEmpty()) return@mapNotNull null
            var cur = 0L; var d30 = 0L; var d60 = 0L; var d90 = 0L
            for (c in open) { val age = (now - c[0]) / DAY; when { age >= 90 -> d90 += c[1]; age >= 60 -> d60 += c[1]; age >= 30 -> d30 += c[1]; else -> cur += c[1] } }
            AgingRow(cid, cur + d30 + d60 + d90, cur, d30, d60, d90, (now - open.first()[0]) / DAY)
        }.sortedByDescending { it.oldestDays }
}
