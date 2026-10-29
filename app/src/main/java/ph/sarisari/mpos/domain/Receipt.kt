package ph.sarisari.mpos.domain

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import java.net.InetSocketAddress
import java.net.Socket
import android.content.Context
import ph.sarisari.mpos.data.*
import ph.sarisari.mpos.util.*
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ReceiptData(
    val storeName: String, val storePhone: String?, val sale: Sale, val items: List<SaleItem>,
    val payments: List<Payment>, val cashierName: String, val customerName: String?, val customerBalance: Long?,
    val header: String? = null, val footer: String? = null      // Premium: receipt branding
)

object ReceiptFormatter {
    /** width: 32 chars for 58 mm paper, 48 for 80 mm. */
    fun format(d: ReceiptData, width: Int = 32): String = buildString {
        fun center(s: String) = appendLine(s.take(width).let { " ".repeat(((width - it.length) / 2).coerceAtLeast(0)) + it })
        fun lr(l: String, r: String) {
            val left = l.take((width - r.length - 1).coerceAtLeast(1))
            appendLine(left + " ".repeat((width - left.length - r.length).coerceAtLeast(1)) + r)
        }
        val line = "-".repeat(width)
        center(d.storeName); d.storePhone?.let { center(it) }
        d.header?.lines()?.filter { it.isNotBlank() }?.forEach { center(it.trim()) }
        center("SALES RECEIPT")
        appendLine(line)
        lr("No: ${d.sale.receiptNumber}", ""); lr(Manila.fmt(d.sale.createdAt), "Cashier: ${d.cashierName}".take(width / 2))
        if (d.sale.status == SaleStatus.VOIDED) center("*** VOIDED ***")
        appendLine(line)
        d.items.forEach {
            appendLine(it.productNameSnapshot.take(width))
            lr("  ${Qty.format(it.qtyMilli)} ${it.unitSnapshot} x ${Money.format(it.unitPriceCentavos)}", Money.format(it.subtotalCentavos))
        }
        appendLine(line)
        lr("Subtotal", Money.format(d.sale.subtotalCentavos))
        if (d.sale.discountCentavos > 0) lr("Discount", "-" + Money.format(d.sale.discountCentavos))
        lr("TOTAL", Money.format(d.sale.totalCentavos))
        d.payments.filter { it.kind == PaymentKind.SALE }.forEach { p ->
            if (p.method == PaymentMethod.CASH) {
                lr("Cash received", Money.format(p.tenderedCentavos ?: p.amountCentavos))
                lr("Change", Money.format((p.tenderedCentavos ?: p.amountCentavos) - p.amountCentavos))
            } else lr(p.method.name + (p.reference?.let { " #$it" } ?: ""), Money.format(p.amountCentavos))
        }
        if (d.sale.creditCentavos > 0) {
            lr("On Utang (${d.customerName ?: ""})", Money.format(d.sale.creditCentavos))
            d.customerBalance?.let { lr("Utang balance", Money.format(it)) }
        }
        appendLine(line)
        val foot = d.footer?.lines()?.filter { it.isNotBlank() }
        if (foot.isNullOrEmpty()) center("Thank you! Salamat po!") else foot.forEach { center(it.trim()) }
        center("Not an official BIR receipt")
    }
}

suspend fun buildReceipt(db: AppDatabase, saleId: String, brand: Boolean = false): ReceiptData {
    val sale = db.sales().sale(saleId) ?: throw PosException.Invalid("Sale not found")
    val cust = sale.customerId?.let { db.admin().customer(it) }
    return ReceiptData(
        db.admin().setting("store_name") ?: "My Sari-Sari Store", db.admin().setting("store_phone"),
        sale, db.sales().items(saleId), db.sales().paymentsForSale(saleId),
        db.admin().staff(sale.cashierId)?.name ?: "-", cust?.name, cust?.let { db.sales().balance(it.id) },
        if (brand) db.admin().setting("receipt_header")?.takeIf { it.isNotBlank() } else null, if (brand) db.admin().setting("receipt_footer")?.takeIf { it.isNotBlank() } else null
    )
}

/** Hardware adapter: add USB / network / vendor-SDK printers by implementing this interface. */
interface ReceiptPrinter {
    suspend fun print(text: String, openDrawer: Boolean = false): Result<Unit>
    /** Raw ESC/POS bytes (used for barcode labels). */
    suspend fun printRaw(bytes: ByteArray): Result<Unit> = Result.failure(UnsupportedOperationException("This printer does not support raw printing"))
}

/** Generic ESC/POS over Bluetooth SPP (classic). Printer must be paired in Android settings first. UNTESTED on real hardware. */
class EscPosBluetoothPrinter(private val ctx: Context, private val mac: String) : ReceiptPrinter {
    @SuppressLint("MissingPermission")
    override suspend fun printRaw(bytes: ByteArray): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter ?: error("Bluetooth not available")
            val socket = adapter.getRemoteDevice(mac).createRfcommSocketToServiceRecord(UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"))
            adapter.cancelDiscovery()
            socket.use { s -> s.connect(); s.outputStream.apply { write(bytes); flush() } }
            Unit        // runCatching must return Unit, not the stream
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun print(text: String, openDrawer: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                ?: error("Bluetooth not available")
            val dev = adapter.getRemoteDevice(mac)
            val socket = dev.createRfcommSocketToServiceRecord(UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"))
            adapter.cancelDiscovery()
            socket.use { s ->
                s.connect()
                val out = s.outputStream
                out.write(byteArrayOf(0x1B, 0x40))                          // init
                out.write(text.replace("₱", "P").toByteArray(Charsets.ISO_8859_1)) // peso sign is not in the default printer code page
                out.write("\n\n\n".toByteArray())
                if (openDrawer) out.write(byteArrayOf(0x1B, 0x70, 0x00, 0x19, 0xFA.toByte())) // drawer kick (RJ11 via printer)
                out.write(byteArrayOf(0x1D, 0x56, 0x42, 0x00))              // partial cut (ignored by printers without cutter)
                out.flush()
            }
        }
    }
}

/** ESC/POS over LAN (port 9100) — most Ethernet/Wi-Fi receipt printers. UNTESTED on real hardware. */
class NetworkEscPosPrinter(private val host: String, private val port: Int = 9100) : ReceiptPrinter {
    override suspend fun printRaw(bytes: ByteArray): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { Socket().use { s -> s.connect(InetSocketAddress(host, port), 4000); s.getOutputStream().apply { write(bytes); flush() } }; Unit }
    }

    override suspend fun print(text: String, openDrawer: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), 4000)
                val out = s.getOutputStream()
                out.write(byteArrayOf(0x1B, 0x40)); out.write(text.replace("₱", "P").toByteArray(Charsets.ISO_8859_1)); out.write("\n\n\n".toByteArray())
                if (openDrawer) out.write(byteArrayOf(0x1B, 0x70, 0x00, 0x19, 0xFA.toByte()))
                out.write(byteArrayOf(0x1D, 0x56, 0x42, 0x00)); out.flush()
            }
        }
    }
}

object Hardware {
    /** Paired Bluetooth devices (name to MAC). Needs the BLUETOOTH_CONNECT permission on Android 12+. */
    @SuppressLint("MissingPermission")
    fun pairedDevices(ctx: Context): List<Pair<String, String>> = try {
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.bondedDevices?.map { (it.name ?: "Unnamed") to it.address } ?: emptyList()
    } catch (e: SecurityException) { emptyList() }

    const val TEST_TEXT = "PRINTER TEST\n--------------------------------\nABC abc 123 ₱ 45.50\nIf you can read this, text works.\nThe cash drawer should open now.\n"
}
