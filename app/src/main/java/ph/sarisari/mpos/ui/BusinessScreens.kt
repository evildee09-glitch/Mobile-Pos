@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package ph.sarisari.mpos.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ph.sarisari.mpos.data.*
import ph.sarisari.mpos.domain.Ean
import ph.sarisari.mpos.domain.LabelItem
import ph.sarisari.mpos.domain.PromoEngine
import ph.sarisari.mpos.util.Expiry
import ph.sarisari.mpos.util.Manila
import ph.sarisari.mpos.util.Money
import ph.sarisari.mpos.util.Qty
import java.util.UUID

private fun pesos(c: Long) = Money.format(c).removePrefix("₱").replace(",", "")
private fun <T> List<T>.nextOf(cur: T): T = this[(indexOf(cur) + 1) % size]

// ---------- supplier payables ----------
@Composable
fun PayablesScreen(vm: PosViewModel) {
    val rows by vm.billRows.collectAsState(); val suppliers by vm.suppliers.collectAsState(); val pos by vm.purchaseOrders.collectAsState()
    var filter by remember { mutableStateOf("open") }; var adding by remember { mutableStateOf(false) }; var fromPo by remember { mutableStateOf(false) }
    var paying by remember { mutableStateOf<BillRow?>(null) }
    val today = Expiry.today().toString()
    val open = rows.filter { it.bill.amountCentavos > it.paid }
    val shown = when (filter) { "overdue" -> open.filter { (it.bill.dueDate ?: "9999") < today }; "soon" -> open.filter { val d = Expiry.daysLeft(it.bill.dueDate); d != null && d in 0..7 }; "paid" -> rows.filter { it.bill.amountCentavos <= it.paid }; else -> open }
    val owed = open.sumOf { it.bill.amountCentavos - it.paid }
    Column(Modifier.padding(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard("You owe suppliers", Money.format(owed), Modifier.weight(1f), MaterialTheme.colorScheme.tertiaryContainer)
            StatCard("Overdue bills", open.count { (it.bill.dueDate ?: "9999") < today }.toString(), Modifier.weight(1f), MaterialTheme.colorScheme.errorContainer)
        }
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ adding = true }, Modifier.weight(1f).height(48.dp)) { Text("+ New bill") }
            FilledTonalButton({ fromPo = true }, Modifier.weight(1f).height(48.dp)) { Text("From an order") }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("open" to "Unpaid", "overdue" to "Overdue", "soon" to "Due in 7 days", "paid" to "Paid").forEach { (k, l) -> FilterChip(filter == k, { filter = k }, label = { Text(l) }) } }
        if (shown.isEmpty()) EmptyState("🧾", "Nothing here", "Add the bills your suppliers give you so you never miss a due date.")
        LazyColumn(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(shown, key = { it.bill.id }) { r ->
                val b = r.bill; val bal = b.amountCentavos - r.paid; val d = Expiry.daysLeft(b.dueDate)
                val name by produceState("…", b.supplierId, suppliers) { value = vm.supplierName(b.supplierId) }
                ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text(name, fontWeight = FontWeight.SemiBold); Text(b.reference ?: "No reference", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            if (bal <= 0) Pill("Paid ✓", MaterialTheme.colorScheme.primaryContainer)
                            else if (d != null) Pill(if (d < 0) "Overdue ${-d}d" else if (d == 0L) "Due today" else "Due in ${d}d", if (d < 0) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer)
                        }
                        Text("Bill ${Money.format(b.amountCentavos)} · paid ${Money.format(r.paid)} · balance ${Money.format(bal)}", Modifier.padding(top = 4.dp), fontSize = 13.sp)
                        if (bal > 0) Row { TextButton({ paying = r }) { Text("Pay") }; if (r.paid == 0L && vm.can(Perm.MANAGE_STAFF)) TextButton({ vm.cancelBill(b.id) }) { Text("Cancel bill") } }
                    }
                }
            }
        }
    }
    if (adding) NewBillDialog(vm, suppliers) { adding = false }
    if (fromPo) AlertDialog(onDismissRequest = { fromPo = false }, title = { Text("Bill for a received order") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            val cand = pos.filter { it.status == PoStatus.PARTIAL || it.status == PoStatus.RECEIVED }
            if (cand.isEmpty()) Text("No orders have been received yet.")
            cand.forEach { po -> TextButton({ vm.billFromPo(po.id, null); fromPo = false }) { Text("PO ${po.id.take(6).uppercase()} · ${Manila.fmt(po.createdAt, "MMM d")} · ${po.status.name.lowercase()}") } }
        }
    }, confirmButton = {}, dismissButton = { TextButton({ fromPo = false }) { Text("Close") } })
    paying?.let { r -> PayBillDialog(vm, r) { paying = null } }
}

@Composable
fun NewBillDialog(vm: PosViewModel, suppliers: List<Supplier>, onDismiss: () -> Unit) {
    var sup by remember { mutableStateOf<Supplier?>(null) }; var ref by remember { mutableStateOf("") }; var amt by remember { mutableStateOf("") }
    var due by remember { mutableStateOf("") }; var note by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("New supplier bill") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton({ sup = (listOf<Supplier?>(null) + suppliers).nextOf(sup) }) { Text("Supplier: ${sup?.name ?: "none"} (tap to change)") }
            Field("Invoice / DR number", ref, { ref = it }); Field("Amount ₱", amt, { amt = it }, number = true)
            DateField("Due date (yyyy-MM-dd)", due) { due = it }; Field("Note (optional)", note, { note = it })
        }
    }, confirmButton = { Button({ vm.addBill(sup?.id, ref.ifBlank { null }, Money.parse(amt) ?: 0, due.ifBlank { null }, note.ifBlank { null }) { onDismiss() } }, enabled = (Money.parse(amt) ?: 0) > 0 && (due.isBlank() || Expiry.parse(due) != null)) { Text("Save") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

@Composable
fun PayBillDialog(vm: PosViewModel, r: BillRow, onDismiss: () -> Unit) {
    val bal = r.bill.amountCentavos - r.paid
    var amt by remember { mutableStateOf(pesos(bal)) }; var method by remember { mutableStateOf("CASH_DRAWER") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Pay supplier · balance ${Money.format(bal)}") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Field("Amount ₱", amt, { amt = it }, number = true)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("CASH_DRAWER" to "💵 From drawer", "GCASH" to "GCash", "BANK" to "Bank", "OTHER" to "Other").forEach { (k, l) -> FilterChip(method == k, { method = k }, label = { Text(l) }) } }
            if (method == "CASH_DRAWER") Text("Also recorded as a shift expense so your drawer count stays correct (needs an open shift).", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }, confirmButton = { Button({ vm.payBill(r.bill.id, Money.parse(amt) ?: 0, method); onDismiss() }, enabled = (Money.parse(amt) ?: 0) in 1..bal) { Text("Pay") } }, dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

// ---------- promos & bundles ----------
@Composable
fun PromosScreen(vm: PosViewModel) {
    val promos by vm.promos.collectAsState(); val products by vm.allProducts.collectAsState(); var adding by remember { mutableStateOf(false) }
    val names = products.associate { it.id to it.name }
    Column(Modifier.padding(12.dp)) {
        BigButton("+ New promo", { adding = true })
        Text("Promos apply automatically at checkout to items sold in their base unit (e.g. per piece). Only the best promo per product applies — they never stack. A manual discount still needs owner approval.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp))
        if (promos.isEmpty()) EmptyState("🏷", "No promos yet", "Try \"3 for ₱50\" or \"Buy 5 get 1 free\".")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(promos, key = { it.id }) { p ->
                ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, fontWeight = FontWeight.SemiBold); Text("${names[p.productId] ?: "?"} · ${PromoEngine.describe(p)}", fontSize = 13.sp)
                            Text(listOfNotNull(p.startDate?.let { "from $it" }, p.endDate?.let { "until $it" }).joinToString(" ").ifBlank { "No dates (always on)" }, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(p.active, { vm.savePromo(p.copy(active = it)) })
                    }
                }
            }
        }
    }
    if (adding) NewPromoDialog(vm, products.filter { it.active }) { adding = false }
}

@Composable
fun NewPromoDialog(vm: PosViewModel, products: List<Product>, onDismiss: () -> Unit) {
    var prod by remember { mutableStateOf<Product?>(null) }; var pick by remember { mutableStateOf(false) }; var type by remember { mutableStateOf(PromoType.BUNDLE) }
    var name by remember { mutableStateOf("") }; var a by remember { mutableStateOf("") }; var b by remember { mutableStateOf("") }
    var start by remember { mutableStateOf("") }; var end by remember { mutableStateOf("") }
    val q = Qty.parse(a) ?: 0; val v = Qty.parse(b) ?: 0; val m = Money.parse(b) ?: 0
    val valid = prod != null && when (type) { PromoType.BUNDLE -> q > 0 && m > 0; PromoType.BUY_X_GET_Y -> q > 0 && v > 0; PromoType.PERCENT_OFF -> q in 1..100_000; PromoType.VOLUME_PRICE -> q > 0 && m > 0 } &&
        (start.isBlank() || Expiry.parse(start) != null) && (end.isBlank() || Expiry.parse(end) != null)
    AlertDialog(onDismissRequest = onDismiss, title = { Text("New promo") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton({ pick = true }) { Text(prod?.let { "Product: ${it.name} (${it.baseUnit})" } ?: "Choose product") }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(PromoType.BUNDLE to "N for ₱", PromoType.BUY_X_GET_Y to "Buy X get Y free", PromoType.PERCENT_OFF to "% off", PromoType.VOLUME_PRICE to "Volume price").forEach { (t, l) -> FilterChip(type == t, { type = t }, label = { Text(l) }) }
            }
            when (type) {
                PromoType.BUNDLE -> { Field("How many pieces in the bundle", a, { a = it }, number = true); Field("Bundle price ₱", b, { b = it }, number = true) }
                PromoType.BUY_X_GET_Y -> { Field("Buy this many", a, { a = it }, number = true); Field("Get this many free", b, { b = it }, number = true) }
                PromoType.PERCENT_OFF -> Field("Percent off (e.g. 10)", a, { a = it }, number = true)
                PromoType.VOLUME_PRICE -> { Field("From this many pieces", a, { a = it }, number = true); Field("Price each ₱", b, { b = it }, number = true) }
            }
            Field("Name (optional)", name, { name = it }); DateField("Start date (optional)", start) { start = it }; DateField("End date (optional)", end) { end = it }
        }
    }, confirmButton = {
        Button({
            val p = Promo(UUID.randomUUID().toString(), "", type, prod!!.id,
                qtyMilli = if (type == PromoType.PERCENT_OFF) 0 else q, valueCentavos = if (type == PromoType.BUNDLE || type == PromoType.VOLUME_PRICE) m else 0,
                freeQtyMilli = if (type == PromoType.BUY_X_GET_Y) v else 0, percentBp = if (type == PromoType.PERCENT_OFF) q / 10 else 0,
                startDate = start.ifBlank { null }, endDate = end.ifBlank { null }, active = true, createdAt = System.currentTimeMillis())
            vm.savePromo(p.copy(name = name.ifBlank { "${prod!!.name}: ${PromoEngine.describe(p)}" })); onDismiss()
        }, enabled = valid) { Text("Save") }
    }, dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
    if (pick) ProductPickerDialog(products, { prod = it }, { pick = false })
}

// ---------- barcode labels ----------
@Composable
fun LabelsScreen(vm: PosViewModel) {
    val products by vm.allProducts.collectAsState(); var q by remember { mutableStateOf("") }; var sel by remember { mutableStateOf(setOf<String>()) }; var copies by remember { mutableStateOf("1") }
    val list = products.filter { it.active && (q.isBlank() || it.name.contains(q, true)) }
    val chosen = products.filter { it.id in sel }; val n = (copies.toIntOrNull() ?: 1).coerceIn(1, 50)
    val items = chosen.filter { it.barcode != null && Ean.isValid(it.barcode!!) }.map { LabelItem(it.name, Money.format(it.priceCentavos), it.barcode!!) to n }
    val pdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri: Uri? -> uri?.let { vm.labelsPdf(it, items) } }
    Column(Modifier.padding(12.dp)) {
        Field("Search products", q, { q = it })
        Text("Labels can be drawn for EAN-13, UPC-A and EAN-8 barcodes. Products without one can get a free in-store EAN-13 (prefix 200).", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(list, key = { it.id }) { p ->
                val ok = p.barcode != null && Ean.isValid(p.barcode!!)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(p.id in sel, { sel = if (it) sel + p.id else sel - p.id })
                    Column(Modifier.weight(1f)) { Text(p.name, maxLines = 1); Text(p.barcode ?: "no barcode", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Pill(if (ok) "✓ label-ready" else if (p.barcode == null) "no barcode" else "unsupported", if (ok) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.tertiaryContainer)
                }
            }
        }
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Field("Copies each", copies, { copies = it.filter(Char::isDigit) }, Modifier.width(120.dp), number = true, decimal = false)
            OutlinedButton({ sel = list.map { it.id }.toSet() }) { Text("Select shown") }
            OutlinedButton({ vm.assignBarcodes(chosen.filter { it.barcode == null }.map { it.id }) }, enabled = chosen.any { it.barcode == null }) { Text("Make barcodes") }
        }
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ vm.printLabels(items) }, Modifier.weight(1f), enabled = items.isNotEmpty()) { Text("Print (thermal)") }
            Button({ pdf.launch("labels.pdf") }, Modifier.weight(1f), enabled = items.isNotEmpty()) { Text("A4 sheet (PDF)") }
        }
        if (chosen.size > items.size) Text("${chosen.size - items.size} selected product(s) skipped: no valid EAN/UPC barcode.", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
    }
}

// ---------- PDF reports ----------
private data class PdfReq(val kind: String, val from: Long, val to: Long, val customerId: String?)

@Composable
fun PdfReportsScreen(vm: PosViewModel) {
    var days by remember { mutableIntStateOf(30) }; var cust by remember { mutableStateOf<Customer?>(null) }; var pending by remember { mutableStateOf<PdfReq?>(null) }
    val customers by vm.customers.collectAsState()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri: Uri? -> val r = pending; if (uri != null && r != null) vm.exportPdf(r.kind, uri, r.from, r.to, r.customerId) }
    fun go(kind: String, file: String, c: Customer? = null) { val now = System.currentTimeMillis(); pending = PdfReq(kind, now - days * 86_400_000L, now, c?.id); launcher.launch("$file-${Manila.fmt(now, "yyyyMMdd")}.pdf") }
    Column(Modifier.padding(14.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Create a PDF to keep, print, or send to your accountant.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SectionTitle("Sales report")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(7, 30, 90, 365).forEach { d -> FilterChip(days == d, { days = d }, label = { Text(if (d == 365) "1 year" else "Last $d days") }) } }
        BigButton("Sales & best sellers (PDF)", { go("sales", "sales-report") })
        SectionTitle("Stock & money")
        OutlinedButton({ go("inventory", "inventory") }, Modifier.fillMaxWidth().height(52.dp)) { Text("Inventory & stock value") }
        OutlinedButton({ go("receivables", "utang-receivables") }, Modifier.fillMaxWidth().height(52.dp)) { Text("Utang receivables (aging)") }
        OutlinedButton({ go("payables", "supplier-payables") }, Modifier.fillMaxWidth().height(52.dp)) { Text("Supplier payables") }
        SectionTitle("Customer Utang statement")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { customers.forEach { c -> FilterChip(cust?.id == c.id, { cust = c }, label = { Text(c.name) }) } }
        OutlinedButton({ cust?.let { go("statement", "statement-${it.name.replace(" ", "_")}", it) } }, Modifier.fillMaxWidth().height(52.dp), enabled = cust != null) { Text("Statement for ${cust?.name ?: "…"}") }
    }
}
