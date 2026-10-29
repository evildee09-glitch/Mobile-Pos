package ph.sarisari.mpos.domain

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import kotlinx.coroutines.flow.first
import ph.sarisari.mpos.data.*
import ph.sarisari.mpos.util.Expiry
import ph.sarisari.mpos.util.Manila
import ph.sarisari.mpos.util.Money
import ph.sarisari.mpos.util.Qty
import java.io.OutputStream

sealed class PdfBlock {
    data class Heading(val text: String) : PdfBlock()
    data class Line(val text: String, val bold: Boolean = false) : PdfBlock()
    /** widths are relative weights; right-aligned column indexes are for numbers. */
    data class Table(val headers: List<String>, val rows: List<List<String>>, val widths: List<Float>, val right: Set<Int> = emptySet()) : PdfBlock()
}
data class PdfDoc(val title: String, val subtitle: String, val blocks: List<PdfBlock>)

/** Plain A4 report writer on top of android.graphics.pdf.PdfDocument (no libraries). Tables continue on the next page with the header repeated. */
object PdfWriter {
    private const val W = 595; private const val H = 842; private const val M = 36f; private const val ROW = 15f

    fun write(doc: PdfDoc, out: OutputStream) {
        val pdf = PdfDocument(); var no = 1
        var page = pdf.startPage(PdfDocument.PageInfo.Builder(W, H, no).create()); var c = page.canvas; var y = M
        val ptitle = Paint().apply { textSize = 18f; isFakeBoldText = true }; val psub = Paint().apply { textSize = 9f; color = Color.DKGRAY }
        val body = Paint().apply { textSize = 9.5f }; val bold = Paint(body).apply { isFakeBoldText = true }; val head = Paint().apply { textSize = 12f; isFakeBoldText = true }
        val shade = Paint().apply { color = 0xFFE3ECE7.toInt() }; val rule = Paint().apply { color = 0xFFDDDDDD.toInt(); strokeWidth = 0.5f }
        fun footer() { c.drawText("Page $no", W / 2f - 18, H - 18f, psub) }
        fun newPage() { footer(); pdf.finishPage(page); no++; page = pdf.startPage(PdfDocument.PageInfo.Builder(W, H, no).create()); c = page.canvas; y = M }
        fun need(h: Float) { if (y + h > H - 36) newPage() }
        fun fit(t: String, p: Paint, w: Float): String { if (p.measureText(t) <= w) return t; var s = t; while (s.length > 1 && p.measureText("$s…") > w) s = s.dropLast(1); return "$s…" }

        c.drawText(doc.title, M, y + 16, ptitle); y += 24; c.drawText(doc.subtitle, M, y + 8, psub); y += 20
        for (b in doc.blocks) when (b) {
            is PdfBlock.Heading -> { need(34f); y += 10; c.drawText(b.text, M, y + 12, head); y += 20 }
            is PdfBlock.Line -> { need(16f); c.drawText(b.text, M, y + 11, if (b.bold) bold else body); y += 15 }
            is PdfBlock.Table -> {
                val total = W - 2 * M; val sum = b.widths.sum(); val xs = b.widths.runningFold(0f) { a, w -> a + w / sum * total }
                fun row(cells: List<String>, p: Paint, fill: Boolean) {
                    if (fill) c.drawRect(M, y, W - M, y + ROW, shade)
                    cells.forEachIndexed { i, t ->
                        val cw = xs[i + 1] - xs[i] - 6; val s = fit(t, p, cw)
                        c.drawText(s, if (i in b.right) M + xs[i + 1] - 3 - p.measureText(s) else M + xs[i] + 3, y + 11, p)
                    }
                    y += ROW; c.drawLine(M, y, W - M, y, rule)
                }
                need(ROW * 2); row(b.headers, bold, true)
                for (r in b.rows) { if (y + ROW > H - 36) { newPage(); row(b.headers, bold, true) }; row(r, body, false) }
                y += 4
            }
        }
        footer(); pdf.finishPage(page); pdf.writeTo(out); pdf.close()
    }
}

/** Reads the database and lays out each report. Money is always integer centavos until formatting. */
class ReportBuilder(private val db: AppDatabase) {
    private suspend fun store() = db.admin().setting("store_name")?.takeIf { it.isNotBlank() } ?: "My Store"
    private fun stamp() = "Generated ${Manila.fmt(System.currentTimeMillis())}"

    suspend fun sales(fromMs: Long, toMs: Long): PdfDoc {
        val daily = db.sales().dailySales(fromMs, toMs).first(); val profit = db.sales().dailyProfit(fromMs, toMs).first().associate { it.day to it.profit }
        val top = db.sales().topProducts(fromMs, toMs, 15).first(); val svc = db.sales().serviceDay(fromMs, toMs).first()
        val sum = daily.sumOf { it.total }; val tx = daily.sumOf { it.count }; val pf = profit.values.sum()
        return PdfDoc("Sales report — ${store()}", "${Manila.fmt(fromMs, "MMM d, yyyy")} to ${Manila.fmt(toMs, "MMM d, yyyy")} · ${stamp()}", listOf(
            PdfBlock.Line("Total sales ${Money.format(sum)}   ·   Transactions $tx   ·   Estimated profit ${Money.format(pf)}", true),
            PdfBlock.Line("GCash/load service fees earned: ${Money.format(svc.fees)} (${svc.count} transactions)"),
            PdfBlock.Heading("Sales per day"),
            PdfBlock.Table(listOf("Date", "Transactions", "Sales", "Est. profit"), daily.map { listOf(it.day, it.count.toString(), Money.format(it.total), Money.format(profit[it.day] ?: 0)) }, listOf(2f, 1.5f, 2f, 2f), setOf(1, 2, 3)),
            PdfBlock.Heading("Best sellers"),
            PdfBlock.Table(listOf("Product", "Qty sold", "Revenue", "Est. profit"), top.map { listOf(it.name, Qty.format(it.qtyMilli), Money.format(it.revenue), Money.format(it.profit)) }, listOf(4f, 1.5f, 2f, 2f), setOf(1, 2, 3)),
            PdfBlock.Line("Profit is estimated from the cost entered per product, before discounts. It is not cash collected.")))
    }

    suspend fun inventory(): PdfDoc {
        val ps = db.catalog().activeNow().sortedBy { it.name.lowercase() }; val value = ps.sumOf { Qty.lineTotal(it.costCentavos, it.stockMilli) }
        val low = ps.filter { it.reorderMilli > 0 && it.stockMilli <= it.reorderMilli }
        return PdfDoc("Inventory — ${store()}", stamp(), listOf(
            PdfBlock.Line("${ps.size} products · stock value at cost ${Money.format(value)}", true),
            PdfBlock.Table(listOf("Product", "Stock", "Cost", "Value", "Expiry"), ps.map { listOf(it.name, "${Qty.format(it.stockMilli)} ${it.baseUnit}", Money.format(it.costCentavos), Money.format(Qty.lineTotal(it.costCentavos, it.stockMilli)), it.expiryDate ?: "") }, listOf(4f, 2f, 1.6f, 2f, 1.8f), setOf(1, 2, 3)),
            PdfBlock.Heading("Low stock"),
            PdfBlock.Table(listOf("Product", "Stock", "Reorder level"), low.map { listOf(it.name, "${Qty.format(it.stockMilli)} ${it.baseUnit}", Qty.format(it.reorderMilli)) }, listOf(4f, 2f, 2f), setOf(1, 2))))
    }

    suspend fun receivables(): PdfDoc {
        val names = db.admin().customers().first().associate { it.id to it.name }
        val rows = UtangAging.compute(db.sales().allLedger(), System.currentTimeMillis())
        return PdfDoc("Utang receivables — ${store()}", stamp(), listOf(
            PdfBlock.Line("Total owed ${Money.format(rows.sumOf { it.balance })} by ${rows.size} customers. Payments are applied to the oldest charges first.", true),
            PdfBlock.Table(listOf("Customer", "Balance", "0–29 d", "30–59 d", "60–89 d", "90+ d"),
                rows.map { listOf(names[it.customerId] ?: "Customer", Money.format(it.balance), Money.format(it.current), Money.format(it.d30), Money.format(it.d60), Money.format(it.d90)) }, listOf(3.5f, 2f, 1.8f, 1.8f, 1.8f, 1.8f), setOf(1, 2, 3, 4, 5))))
    }

    suspend fun payables(): PdfDoc {
        val sup = db.catalog().suppliers().first().associate { it.id to it.name }; val rows = db.catalog().billRowsNow().filter { it.bill.amountCentavos > it.paid }
        val today = Expiry.today().toString()
        return PdfDoc("Supplier payables — ${store()}", stamp(), listOf(
            PdfBlock.Line("Total owed to suppliers ${Money.format(rows.sumOf { it.bill.amountCentavos - it.paid })} · ${rows.count { (it.bill.dueDate ?: "9999") < today }} overdue", true),
            PdfBlock.Table(listOf("Supplier", "Reference", "Due", "Amount", "Paid", "Balance"),
                rows.map { listOf(sup[it.bill.supplierId] ?: "—", it.bill.reference ?: "", (it.bill.dueDate ?: "") + if ((it.bill.dueDate ?: "9999") < today) " !" else "", Money.format(it.bill.amountCentavos), Money.format(it.paid), Money.format(it.bill.amountCentavos - it.paid)) },
                listOf(3f, 2.2f, 2f, 1.8f, 1.8f, 1.8f), setOf(3, 4, 5))))
    }

    suspend fun customerStatement(customerId: String): PdfDoc {
        val c = db.admin().customer(customerId) ?: throw PosException.Invalid("Customer not found"); var run = 0L
        val rows = db.sales().ledger(customerId).map { e -> run += e.amountCentavos; listOf(Manila.fmt(e.createdAt), e.type.name.lowercase() + (e.notes?.let { " $it" } ?: ""), Money.format(e.amountCentavos), Money.format(run)) }
        return PdfDoc("Utang statement — ${c.name}", "${store()} · ${stamp()}", listOf(
            PdfBlock.Line("Balance ${Money.format(run)} (credit limit ${Money.format(c.creditLimitCentavos)})", true),
            PdfBlock.Table(listOf("Date", "Entry", "Amount", "Running balance"), rows, listOf(2.2f, 3.5f, 1.8f, 2f), setOf(2, 3))))
    }
}
