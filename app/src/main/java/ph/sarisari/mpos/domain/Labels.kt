package ph.sarisari.mpos.domain

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.room.withTransaction
import ph.sarisari.mpos.data.*
import ph.sarisari.mpos.sync.SyncCodec
import java.io.ByteArrayOutputStream
import java.io.OutputStream

data class LabelItem(val name: String, val price: String, val code: String)

object LabelEscPos {
    private fun ByteArrayOutputStream.b(vararg v: Int) = write(ByteArray(v.size) { v[it].toByte() })
    private fun ByteArrayOutputStream.t(s: String) = write(s.replace("\u20B1", "P").toByteArray(Charsets.ISO_8859_1))

    /** Thermal labels using the PRINTER'S OWN barcode generator (GS k, function B). Works on most ESC/POS printers; unverified on real hardware. */
    fun build(items: List<Pair<LabelItem, Int>>): ByteArray = ByteArrayOutputStream().also { o ->
        for ((l, copies) in items) repeat(copies) {
            val type = when (l.code.length) { 13 -> 67; 12 -> 65; 8 -> 68; else -> return@repeat }
            o.b(0x1B, 0x40); o.b(0x1B, 0x61, 1)                       // init, center
            o.b(0x1B, 0x45, 1); o.t(l.name.take(32) + "\n"); o.b(0x1B, 0x45, 0)
            o.b(0x1D, 0x21, 0x11); o.t(l.price + "\n"); o.b(0x1D, 0x21, 0x00)   // double-size price
            o.b(0x1D, 0x68, 60); o.b(0x1D, 0x77, 2); o.b(0x1D, 0x48, 2)          // height, width, digits under bars
            o.b(0x1D, 0x6B, type, l.code.length); o.t(l.code)
            o.t("\n\n\n"); o.b(0x1D, 0x56, 0x42, 0x00)                         // feed + partial cut
        }
    }.toByteArray()
}

/** A4 sheet of 3 x 8 labels with real drawn EAN bars (labels with unsupported codes are skipped by the caller). */
object LabelSheetPdf {
    fun write(items: List<Pair<LabelItem, Int>>, out: OutputStream) {
        val labels = items.flatMap { (l, n) -> List(n) { l } }
        val pdf = PdfDocument(); val W = 595; val H = 842; val cols = 3; val rows = 8; val m = 20f
        val cw = (W - 2 * m) / cols; val ch = (H - 2 * m) / rows
        val name = Paint().apply { textSize = 9f }; val price = Paint().apply { textSize = 16f; isFakeBoldText = true }
        val small = Paint().apply { textSize = 8f }; val bar = Paint().apply { color = Color.BLACK }; val box = Paint().apply { style = Paint.Style.STROKE; color = 0xFFCCCCCC.toInt(); strokeWidth = 0.5f }
        var page = 0
        labels.chunked(cols * rows).ifEmpty { listOf(emptyList()) }.forEach { chunk ->
            val pg = pdf.startPage(PdfDocument.PageInfo.Builder(W, H, ++page).create()); val c = pg.canvas
            chunk.forEachIndexed { i, l ->
                val x = m + (i % cols) * cw; val y = m + (i / cols) * ch
                c.drawRect(x, y, x + cw, y + ch, box)
                var t = l.name; while (t.length > 1 && name.measureText(t) > cw - 12) t = t.dropLast(1)
                c.drawText(t, x + 6, y + 13, name); c.drawText(l.price, x + 6, y + 32, price)
                val mods = Ean.modules(l.code)
                if (mods != null) {
                    val mw = minOf(1.4f, (cw - 16) / mods.length); val bx = x + (cw - mw * mods.length) / 2
                    mods.forEachIndexed { k, ch2 -> if (ch2 == '1') c.drawRect(bx + k * mw, y + 38, bx + (k + 1) * mw, y + ch - 16, bar) }
                    c.drawText(l.code, x + (cw - small.measureText(l.code)) / 2, y + ch - 5, small)
                }
            }
            pdf.finishPage(pg)
        }
        pdf.writeTo(out); pdf.close()
    }
}

class LabelService(private val db: AppDatabase) {
    /** Give every selected product that has no barcode a unique in-store EAN-13. Existing barcodes are never touched. Returns how many were assigned. */
    suspend fun assignBarcodes(ids: List<String>, staffId: String): Int = db.withTransaction {
        val staff = db.requirePerm(staffId, Perm.MANAGE_CATALOG); val now = System.currentTimeMillis(); var n = 0
        var seq = db.admin().setting("ean_seq")?.toLongOrNull() ?: 0L
        for (id in ids) {
            val p = db.catalog().get(id) ?: continue
            if (!p.barcode.isNullOrBlank()) continue
            var code: String; do { seq++; code = Ean.inStore(seq) } while (db.catalog().byBarcodeAny(code) != null)
            val np = p.copy(barcode = code, updatedAt = now); db.catalog().update(np); n++
            db.audit(staff.id, "BARCODE_ASSIGN", "product", id, code, now)
            db.enqueue("PRODUCT", id, "UPSERT", SyncCodec.product(np, db.catalog().unitsFor(id)), now)
        }
        db.admin().putSetting(AppSetting("ean_seq", seq.toString())); n
    }
}
