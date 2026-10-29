package ph.sarisari.mpos.domain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.util.Base64
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * Imports a picture of the shop's own payment QR (GCash / Maya / ShopeePay) from the gallery.
 * The QR is located with ML Kit, cropped out of screenshots, put on a clean white background (so it scans reliably)
 * and stored as base64 PNG in the app settings, which means it is part of the encrypted backup.
 * The image is only DISPLAYED to customers; the app cannot tell whether they actually paid.
 */
object QrImport {
    /** Returns (base64 PNG, qrWasDetected). */
    suspend fun process(ctx: Context, uri: Uri): Pair<String, Boolean> = withContext(Dispatchers.Default) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2400) sample *= 2
        val src = ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: error("Could not read that image")
        val box = detectQrBox(src)
        var out = src
        if (box != null) {
            val r = Rect(maxOf(0, box.left), maxOf(0, box.top), minOf(src.width, box.right), minOf(src.height, box.bottom))
            val tight = Bitmap.createBitmap(src, r.left, r.top, r.width(), r.height())
            val pad = (maxOf(tight.width, tight.height) * 0.12f).toInt()                    // quiet zone
            out = Bitmap.createBitmap(tight.width + 2 * pad, tight.height + 2 * pad, Bitmap.Config.ARGB_8888)
            Canvas(out).apply { drawColor(Color.WHITE); drawBitmap(tight, pad.toFloat(), pad.toFloat(), null) }
        }
        if (maxOf(out.width, out.height) > 800) {
            val k = 800f / maxOf(out.width, out.height)
            out = Bitmap.createScaledBitmap(out, (out.width * k).toInt(), (out.height * k).toInt(), true)
        }
        val bytes = ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        Base64.encodeToString(bytes, Base64.NO_WRAP) to (box != null)
    }

    private suspend fun detectQrBox(bmp: Bitmap): Rect? = suspendCancellableCoroutine { cont ->
        val scanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
        scanner.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { list -> if (cont.isActive) cont.resume(list.firstNotNullOfOrNull { it.boundingBox }) }
            .addOnFailureListener { if (cont.isActive) cont.resume(null) }
            .addOnCompleteListener { scanner.close() }
    }
}
