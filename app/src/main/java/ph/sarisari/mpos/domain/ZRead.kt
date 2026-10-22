package ph.sarisari.mpos.domain

import ph.sarisari.mpos.data.*
import ph.sarisari.mpos.util.Manila
import ph.sarisari.mpos.util.Money

data class ZInputs(
    val openingFloat: Long, val gross: Long, val discounts: Long, val refunds: Long,
    val payments: List<PaymentTotal>, val creditIssued: Long, val moves: List<MovementTotal>,
    val perCashier: List<CashierTotal>, val counted: Long? = null
)

data class ZRead(
    val openingFloat: Long, val gross: Long, val discounts: Long, val refunds: Long, val net: Long,
    val cashSales: Long, val digitalSales: Map<PaymentMethod, Long>,
    val creditIssued: Long, val debtCashCollected: Long, val debtDigitalCollected: Map<PaymentMethod, Long>,
    val cashAdded: Long, val cashWithdrawn: Long, val expenses: Long, val cashRefunds: Long,
    val expectedCash: Long, val counted: Long?, val variance: Long?, val perCashier: List<CashierTotal>
)

object ZReadCalculator {
    /**
     * expected drawer cash = opening float + cash sales + cash debt collections + cash additions
     *                        - cash refunds - cash expenses - cash withdrawals
     * Digital payments and unpaid credit never touch the drawer. Debt collections are NOT counted as sales.
     */
    fun compute(i: ZInputs): ZRead {
        fun total(kind: PaymentKind, cash: Boolean) =
            i.payments.filter { it.kind == kind && (it.method == PaymentMethod.CASH) == cash }.sumOf { it.total }
        fun byMethod(kind: PaymentKind) =
            i.payments.filter { it.kind == kind && it.method != PaymentMethod.CASH }.groupBy { it.method }.mapValues { e -> e.value.sumOf { it.total } }
        fun move(t: CashMoveType) = i.moves.filter { it.type == t }.sumOf { it.total }

        val cashSales = total(PaymentKind.SALE, true)
        val debtCash = total(PaymentKind.DEBT_PAYMENT, true)
        val cashRefunds = total(PaymentKind.REFUND, true)
        val adds = move(CashMoveType.ADD); val wd = move(CashMoveType.WITHDRAW); val exp = move(CashMoveType.EXPENSE)
        val expected = i.openingFloat + cashSales + debtCash + adds - cashRefunds - exp - wd
        return ZRead(
            i.openingFloat, i.gross, i.discounts, i.refunds, i.gross - i.discounts - i.refunds,
            cashSales, byMethod(PaymentKind.SALE), i.creditIssued, debtCash, byMethod(PaymentKind.DEBT_PAYMENT),
            adds, wd, exp, cashRefunds, expected, i.counted, i.counted?.minus(expected), i.perCashier
        )
    }

    fun text(z: ZRead, store: String, shift: Shift, closedAt: Long?, names: Map<String, String>): String = buildString {
        fun l(a: String, b: Long) = appendLine(a.padEnd(30) + Money.format(b).padStart(14))
        appendLine("$store — Z-READ")
        appendLine("Shift opened ${Manila.fmt(shift.openedAt)}" + (closedAt?.let { ", closed ${Manila.fmt(it)}" } ?: " (preview)"))
        appendLine("-".repeat(44))
        l("Opening float", z.openingFloat)
        l("Gross sales", z.gross); l("Discounts", -z.discounts); l("Refunds/voids", -z.refunds); l("NET SALES", z.net)
        appendLine("-".repeat(44))
        l("Cash sales (drawer)", z.cashSales)
        z.digitalSales.forEach { (m, v) -> l("${m.name} sales (not in drawer)", v) }
        l("New Utang issued (not cash)", z.creditIssued)
        l("Utang collected - cash", z.debtCashCollected)
        z.debtDigitalCollected.forEach { (m, v) -> l("Utang collected - ${m.name}", v) }
        l("Cash added", z.cashAdded); l("Cash withdrawn", -z.cashWithdrawn)
        l("Expenses/payouts", -z.expenses); l("Cash refunds", -z.cashRefunds)
        appendLine("-".repeat(44))
        l("EXPECTED DRAWER CASH", z.expectedCash)
        z.counted?.let { l("Counted cash", it); l(if ((z.variance ?: 0) < 0) "SHORT" else "OVER", z.variance ?: 0) }
        appendLine("-".repeat(44))
        z.perCashier.forEach { appendLine("${names[it.cashierId] ?: it.cashierId}: ${it.count} sales, ${Money.format(it.total)}") }
    }
}
