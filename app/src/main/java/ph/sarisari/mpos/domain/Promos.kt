package ph.sarisari.mpos.domain

import ph.sarisari.mpos.data.Promo
import ph.sarisari.mpos.data.PromoType
import ph.sarisari.mpos.util.Qty

data class PromoLine(val productId: String, val qtyMilli: Long, val unitPriceCentavos: Long)    // base-unit lines only
data class PromoResult(val totalDiscount: Long, val applied: List<String>)

/**
 * Automatic discounts. Per product, the single best active promo wins (promos never stack, so owners can't accidentally give a product away).
 * Only lines sold in the product's BASE unit take part (a "case" line is never discounted by a per-piece rule).
 */
object PromoEngine {
    fun isActive(p: Promo, todayIso: String) = p.active && (p.startDate == null || p.startDate <= todayIso) && (p.endDate == null || todayIso <= p.endDate)

    fun discountFor(p: Promo, qtyMilli: Long, price: Long): Long {
        val gross = Qty.lineTotal(price, qtyMilli)
        val d = when (p.type) {
            PromoType.BUNDLE -> if (p.qtyMilli <= 0) 0 else { val sets = qtyMilli / p.qtyMilli; Qty.lineTotal(price, sets * p.qtyMilli) - sets * p.valueCentavos }
            PromoType.BUY_X_GET_Y -> { val g = p.qtyMilli + p.freeQtyMilli; if (g <= 0) 0 else (qtyMilli / g) * Qty.lineTotal(price, p.freeQtyMilli) }
            PromoType.PERCENT_OFF -> (gross * p.percentBp + 5_000) / 10_000
            PromoType.VOLUME_PRICE -> if (qtyMilli >= p.qtyMilli && price > p.valueCentavos) Qty.lineTotal(price - p.valueCentavos, qtyMilli) else 0
        }
        return d.coerceIn(0, gross)
    }

    fun compute(lines: List<PromoLine>, promos: List<Promo>, todayIso: String): PromoResult {
        var total = 0L; val names = mutableListOf<String>()
        for ((pid, ls) in lines.groupBy { it.productId }) {
            val qty = ls.sumOf { it.qtyMilli }; val price = ls.first().unitPriceCentavos
            val best = promos.filter { it.productId == pid && isActive(it, todayIso) }.map { it to discountFor(it, qty, price) }.filter { it.second > 0 }.maxByOrNull { it.second } ?: continue
            total += best.second; names += best.first.name
        }
        return PromoResult(total, names)
    }

    fun describe(p: Promo): String = when (p.type) {
        PromoType.BUNDLE -> "${Qty.format(p.qtyMilli)} for ₱${ph.sarisari.mpos.util.Money.format(p.valueCentavos).removePrefix("₱")}"
        PromoType.BUY_X_GET_Y -> "Buy ${Qty.format(p.qtyMilli)} get ${Qty.format(p.freeQtyMilli)} free"
        PromoType.PERCENT_OFF -> "${Qty.format(p.percentBp * 10)}% off"
        PromoType.VOLUME_PRICE -> "${Qty.format(p.qtyMilli)}+ pcs at ${ph.sarisari.mpos.util.Money.format(p.valueCentavos)} each"
    }
}
