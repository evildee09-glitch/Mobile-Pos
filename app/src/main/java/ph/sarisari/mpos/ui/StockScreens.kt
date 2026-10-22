@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package ph.sarisari.mpos.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import ph.sarisari.mpos.data.*
import ph.sarisari.mpos.domain.BulkPriceMode
import ph.sarisari.mpos.domain.NewPoLine
import ph.sarisari.mpos.util.Manila
import ph.sarisari.mpos.util.Money
import ph.sarisari.mpos.util.Qty
import java.util.UUID

private fun <T> List<T>.next(cur: T): T = this[(indexOf(cur) + 1) % size]
private fun pesos(c: Long) = Money.format(c).removePrefix("₱").replace(",", "")

// ---------- stock ----------
@Composable
fun StockScreen(vm: PosViewModel) {
    val products by vm.allProducts.collectAsState(); val low by vm.lowStock.collectAsState(); val units by vm.units.collectAsState()
    var filter by remember { mutableStateOf("") }; var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var edit by remember { mutableStateOf<Product?>(null) }; var adding by remember { mutableStateOf<String?>(null) }; var newOpen by remember { mutableStateOf(false) }
    var receive by remember { mutableStateOf<Product?>(null) }; var adjust by remember { mutableStateOf<Product?>(null) }
    var scan by remember { mutableStateOf(false) }; var bulk by remember { mutableStateOf<String?>(null) }
    val list = products.filter { filter.isBlank() || it.name.contains(filter, true) || it.barcode == filter || it.sku.equals(filter, true) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 12.dp)) {
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("Search products", filter, { filter = it }, Modifier.weight(1f))
                FilledTonalButton({ scan = true }, Modifier.height(56.dp), shape = RoundedCornerShape(16.dp)) { Text("📷") }
                if (vm.can(Perm.MANAGE_CATALOG)) Button({ newOpen = true }, Modifier.height(56.dp), shape = RoundedCornerShape(16.dp)) { Text("+ New") }
            }
            Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (vm.can(Perm.MANAGE_CATALOG) || vm.can(Perm.STOCK_ADJUST)) FilterChip(selecting, { selecting = !selecting; selected = emptySet() }, label = { Text("☑ Select for bulk edit") })
                Text("${products.size} products", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (low.isNotEmpty()) Card(Modifier.fillMaxWidth().padding(bottom = 6.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Text("⚠ Low stock: " + low.joinToString { it.name }, Modifier.padding(12.dp), fontSize = 13.sp)
            }
            if (list.isEmpty()) EmptyState("📦", "No products yet", "Tap + New, scan a barcode, or import a CSV from More.")
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = if (selecting) 90.dp else 12.dp)) {
                items(list, key = { it.id }) { p ->
                    val out = p.stockMilli <= 0; val lowStock = !out && p.reorderMilli > 0 && p.stockMilli <= p.reorderMilli
                    ElevatedCard(Modifier.fillMaxWidth().clickable { if (selecting) selected = if (p.id in selected) selected - p.id else selected + p.id else if (vm.can(Perm.MANAGE_CATALOG)) edit = p }, shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (selecting) Checkbox(p.id in selected, { selected = if (it) selected + p.id else selected - p.id })
                                ProductAvatar(p.name, 42.dp); Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(p.name + if (!p.active) "  (inactive)" else "", fontWeight = FontWeight.SemiBold)
                                    Text(listOfNotNull(p.category, p.barcode).joinToString(" · ").ifBlank { "No barcode" }, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    units[p.id]?.let { u -> Text(u.joinToString { "1 ${it.unitName} = ${Qty.format(it.factorMilli)} ${p.baseUnit}" }, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(Money.format(p.priceCentavos), fontWeight = FontWeight.Bold)
                                    Pill("${Qty.format(p.stockMilli)} ${p.baseUnit}", when { out -> MaterialTheme.colorScheme.errorContainer; lowStock -> MaterialTheme.colorScheme.tertiaryContainer; else -> MaterialTheme.colorScheme.primaryContainer })
                                }
                            }
                            if (!selecting) Row(Modifier.padding(top = 4.dp)) {
                                if (vm.can(Perm.STOCK_ADJUST)) { TextButton({ receive = p }) { Text("Receive") }; TextButton({ adjust = p }) { Text("Adjust") } }
                                if (vm.can(Perm.MANAGE_CATALOG)) TextButton({ edit = p }) { Text("Edit") }
                            }
                        }
                    }
                }
            }
        }
        if (selecting && selected.isNotEmpty()) Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), tonalElevation = 8.dp, shadowElevation = 8.dp) {
            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${selected.size} selected", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                if (vm.can(Perm.PRICE_CHANGE)) Button({ bulk = "price" }) { Text("Prices") }
                if (vm.can(Perm.STOCK_ADJUST)) { FilledTonalButton({ bulk = "add" }) { Text("+/− Stock") }; FilledTonalButton({ bulk = "count" }) { Text("Count") } }
            }
        }
    }
    if (newOpen) ProductDialog(vm, null, emptyList(), null) { newOpen = false }
    adding?.let { code -> ProductDialog(vm, null, emptyList(), code) { adding = null } }
    edit?.let { ProductDialog(vm, it, units[it.id] ?: emptyList(), null) { edit = null } }
    receive?.let { ReceiveDialog(vm, it, units[it.id] ?: emptyList()) { receive = null } }
    adjust?.let { AdjustDialog(vm, it) { adjust = null } }
    val chosen = products.filter { it.id in selected }
    when (bulk) {
        "price" -> BulkPriceDialog(chosen, { mode, v -> vm.bulkPrice(chosen.map { it.id }, mode, v); bulk = null; selecting = false; selected = emptySet() }) { bulk = null }
        "add", "count" -> BulkStockDialog(chosen, bulk == "count", { map, reason ->
            if (bulk == "count") vm.bulkCounts(map) else vm.bulkAdjust(map, reason); bulk = null; selecting = false; selected = emptySet() }) { bulk = null }
    }
    if (scan) ScannerDialog("Scan a product", { code ->
        val p = products.firstOrNull { it.barcode == code }
        if (p != null) { filter = p.barcode ?: ""; "✓ ${p.name}" } else { adding = code; "New barcode $code" }
    }, { scan = false }, closeOnFirst = true)
}

@Composable
fun ProductDialog(vm: PosViewModel, p: Product?, units: List<ProductUnit>, presetBarcode: String?, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(p?.name ?: "") }; var barcode by remember { mutableStateOf(p?.barcode ?: presetBarcode ?: "") }
    var sku by remember { mutableStateOf(p?.sku ?: "") }; var cat by remember { mutableStateOf(p?.category ?: "") }
    var unit by remember { mutableStateOf(p?.baseUnit ?: "piece") }
    var cost by remember { mutableStateOf(p?.let { pesos(it.costCentavos) } ?: "") }; var price by remember { mutableStateOf(p?.let { pesos(it.priceCentavos) } ?: "") }
    var reorder by remember { mutableStateOf(p?.let { Qty.format(it.reorderMilli) } ?: "0") }
    var frac by remember { mutableStateOf(p?.allowsFraction ?: false) }; var fav by remember { mutableStateOf(p?.favorite ?: false) }; var active by remember { mutableStateOf(p?.active ?: true) }
    val u0 = units.firstOrNull(); var caseName by remember { mutableStateOf(u0?.unitName ?: "") }; var caseFactor by remember { mutableStateOf(u0?.let { Qty.format(it.factorMilli) } ?: "") }
    var scan by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (p == null) "New product" else "Edit product") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Field("Name", name, { name = it })
            Row(verticalAlignment = Alignment.CenterVertically) { Field("Barcode", barcode, { barcode = it }, Modifier.weight(1f)); TextButton({ scan = true }) { Text("📷") } }
            Field("Category (Drinks, Snacks…)", cat, { cat = it }); Field("SKU (optional)", sku, { sku = it }); Field("Base unit (piece, kg, sachet…)", unit, { unit = it })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Field("Cost ₱", cost, { cost = it }, Modifier.weight(1f), number = true); Field("Price ₱", price, { price = it }, Modifier.weight(1f), number = true) }
            Field("Reorder level", reorder, { reorder = it }, number = true)
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(frac, { frac = it }); Text("  Sold by weight/volume (decimals)") }
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(fav, { fav = it }); Text("  Show in Favorites") }
            if (p != null) Row(verticalAlignment = Alignment.CenterVertically) { Switch(active, { active = it }); Text("  Active") }
            Text("Wholesale unit (optional) — e.g. case = 24 pieces", fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Field("Unit name", caseName, { caseName = it }, Modifier.weight(1f)); Field("Base units in it", caseFactor, { caseFactor = it }, Modifier.weight(1f), number = true) }
            if (p == null) Text("Add opening stock afterwards with Receive.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }, confirmButton = {
        Button({
            val now = System.currentTimeMillis()
            val prod = Product(p?.id ?: UUID.randomUUID().toString(), sku.ifBlank { null }, barcode.ifBlank { null }, name.trim(), cat.ifBlank { null }, Money.parse(cost) ?: 0, Money.parse(price) ?: 0,
                p?.stockMilli ?: 0, unit.trim().ifBlank { "piece" }, frac, Qty.parse(reorder) ?: 0, p?.supplierId, fav, active, p?.expiryDate, p?.batchNo, p?.createdAt ?: now, now)
            val extra = Qty.parse(caseFactor)?.takeIf { caseName.isNotBlank() && it > 0 }?.let { listOf(ProductUnit(prod.id, caseName.trim(), it)) } ?: emptyList()
            vm.saveProduct(prod, extra); onDismiss()
        }, enabled = name.isNotBlank() && Money.parse(price) != null) { Text("Save") }
    }, dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
    if (scan) ScannerDialog("Scan barcode", { code -> barcode = code; "✓ $code" }, { scan = false }, closeOnFirst = true)
}

@Composable
fun ReceiveDialog(vm: PosViewModel, p: Product, units: List<ProductUnit>, onDismiss: () -> Unit) {
    val opts = listOf(p.baseUnit) + units.map { it.unitName }
    var unit by remember { mutableStateOf(p.baseUnit) }; var qty by remember { mutableStateOf("") }; var cost by remember { mutableStateOf("") }; var ref by remember { mutableStateOf("") }
    val suppliers by vm.suppliers.collectAsState(); var sup by remember { mutableStateOf<Supplier?>(null) }; var newSup by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Receive: ${p.name}") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { opts.forEach { o -> FilterChip(unit == o, { unit = o }, label = { Text(o) }) } }
            Field("Quantity received ($unit)", qty, { qty = it }, number = true); Field("Cost per $unit ₱ (optional)", cost, { cost = it }, number = true)
            OutlinedButton({ sup = (listOf<Supplier?>(null) + suppliers).next(sup) }) { Text("Supplier: ${sup?.name ?: "none"} (tap to change)") }
            Row(verticalAlignment = Alignment.CenterVertically) { Field("New supplier", newSup, { newSup = it }, Modifier.weight(1f)); TextButton({ vm.saveSupplier(newSup, null); newSup = "" }, enabled = newSup.isNotBlank()) { Text("Add") } }
            Field("Delivery receipt / reference", ref, { ref = it })
        }
    }, confirmButton = { Button({ vm.receive(p.id, unit, Qty.parse(qty) ?: 0, Money.parse(cost) ?: 0, sup?.id, ref.ifBlank { null }); onDismiss() }, enabled = (Qty.parse(qty) ?: 0) > 0) { Text("Receive") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

@Composable
fun AdjustDialog(vm: PosViewModel, p: Product, onDismiss: () -> Unit) {
    val types = listOf(MoveType.ADJUSTMENT, MoveType.DAMAGED, MoveType.EXPIRED, MoveType.LOST, MoveType.RETURN)
    var type by remember { mutableStateOf(MoveType.ADJUSTMENT) }; var qty by remember { mutableStateOf("") }; var reason by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Adjust: ${p.name} (now ${Qty.format(p.stockMilli)})") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { types.forEach { t -> FilterChip(type == t, { type = t }, label = { Text(t.name.lowercase()) }) } }
            Field("Quantity (base units; damaged/expired/lost reduce stock)", qty, { qty = it }, number = true); Field("Reason (required)", reason, { reason = it })
        }
    }, confirmButton = {
        Button({
            val q = Qty.parse(qty) ?: 0
            vm.adjust(p.id, type, if (type in listOf(MoveType.DAMAGED, MoveType.EXPIRED, MoveType.LOST)) -Math.abs(q) else q, reason); onDismiss()
        }, enabled = (Qty.parse(qty) ?: 0) != 0L && reason.isNotBlank()) { Text("Apply") }
    }, dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

@Composable
fun BulkPriceDialog(products: List<Product>, onApply: (BulkPriceMode, Long) -> Unit, onDismiss: () -> Unit) {
    var mode by remember { mutableStateOf(BulkPriceMode.PERCENT_BP) }; var text by remember { mutableStateOf("") }
    val value: Long? = when (mode) { BulkPriceMode.PERCENT_BP -> Qty.parse(text)?.div(10); else -> Money.parse(text) }
    val sample = products.firstOrNull()
    val example = if (sample != null && value != null) when (mode) {
        BulkPriceMode.PERCENT_BP -> (sample.priceCentavos * (10_000 + value) + 5_000) / 10_000
        BulkPriceMode.ADD_CENTAVOS -> sample.priceCentavos + value; BulkPriceMode.SET_CENTAVOS -> value } else null
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Change ${products.size} prices") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(mode == BulkPriceMode.PERCENT_BP, { mode = BulkPriceMode.PERCENT_BP }, label = { Text("% change") })
                FilterChip(mode == BulkPriceMode.ADD_CENTAVOS, { mode = BulkPriceMode.ADD_CENTAVOS }, label = { Text("+/− ₱") })
                FilterChip(mode == BulkPriceMode.SET_CENTAVOS, { mode = BulkPriceMode.SET_CENTAVOS }, label = { Text("Set to ₱") })
            }
            Field(when (mode) { BulkPriceMode.PERCENT_BP -> "Percent (e.g. 5 or -10)"; BulkPriceMode.ADD_CENTAVOS -> "Pesos to add (e.g. 1 or -0.50)"; else -> "New price ₱" }, text, { text = it }, number = true)
            if (sample != null && example != null) Text("Example: ${sample.name}  ${Money.format(sample.priceCentavos)} → ${Money.format(example)}", fontSize = 13.sp)
        }
    }, confirmButton = { Button({ value?.let { onApply(mode, it) } }, enabled = value != null && value != 0L || mode == BulkPriceMode.SET_CENTAVOS && value != null) { Text("Apply") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

/** count=true: type what you counted on the shelf; count=false: type how many to add (or negative to remove). */
@Composable
fun BulkStockDialog(products: List<Product>, count: Boolean, onApply: (Map<String, Long>, String) -> Unit, onDismiss: () -> Unit) {
    val vals = remember { mutableStateMapOf<String, String>() }; var reason by remember { mutableStateOf(if (count) "Stock count" else "") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (count) "Stock count" else "Add / remove stock") }, text = {
        Column {
            if (!count) Field("Reason (required)", reason, { reason = it })
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                items(products, key = { it.id }) { p ->
                    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(p.name, fontWeight = FontWeight.SemiBold, maxLines = 1); Text("now ${Qty.format(p.stockMilli)} ${p.baseUnit}", fontSize = 11.sp) }
                        Field(if (count) "Counted" else "± qty", vals[p.id] ?: "", { vals[p.id] = it }, Modifier.width(120.dp), number = true)
                    }
                }
            }
        }
    }, confirmButton = {
        Button({
            val m = products.mapNotNull { p -> Qty.parse(vals[p.id] ?: "")?.let { p.id to it } }.toMap(); onApply(m, reason)
        }, enabled = vals.values.any { Qty.parse(it) != null } && (count || reason.isNotBlank())) { Text("Save") }
    }, dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

// ---------- import / export ----------
@Composable
fun ImportExportScreen(vm: PosViewModel) {
    val ctx = LocalContext.current; val preview by vm.importPreview.collectAsState()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { vm.previewImport(ctx.contentResolver.openInputStream(it)!!.bufferedReader().use { r -> r.readText() }) }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
        uri?.let { ctx.contentResolver.openOutputStream(it, "wt")?.use { o -> o.write(vm.productsCsv().toByteArray()) } }
    }
    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Import products from CSV", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Columns (any order): ${ph.sarisari.mpos.domain.ProductCsv.HEADER}\nRows are matched by barcode, then SKU. Existing products are updated; new ones need a name and price. A stock value sets the count and is recorded as an adjustment.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        BigButton("Choose CSV file…", { pick.launch(arrayOf("text/*", "application/vnd.ms-excel", "*/*")) })
        preview?.let { p ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Preview — nothing saved yet", fontWeight = FontWeight.Bold)
                    Text("${p.report.created} new · ${p.report.updated} updated · ${p.report.stockChanged} stock changes")
                    (p.parseErrors + p.report.errors).take(8).forEach { Text("⚠ $it", fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ vm.applyImport() }, enabled = p.report.created + p.report.updated + p.report.stockChanged > 0) { Text("Import now") }
                        TextButton({ vm.importPreview.value = null }) { Text("Cancel") }
                    }
                }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("Export", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        OutlinedButton({ export.launch("products-${Manila.fmt(System.currentTimeMillis(), "yyyyMMdd")}.csv") }, Modifier.fillMaxWidth().height(52.dp)) { Text("Export all products (CSV)") }
    }
}

// ---------- purchasing ----------
private fun share(ctx: android.content.Context, text: String) =
    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Send order"))

@Composable
fun PurchasingScreen(vm: PosViewModel) {
    val pos by vm.purchaseOrders.collectAsState(); val suppliers by vm.suppliers.collectAsState()
    var open by remember { mutableStateOf<PurchaseOrder?>(null) }; var creating by remember { mutableStateOf(false) }
    Column(Modifier.padding(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ creating = true }, Modifier.weight(1f).height(52.dp)) { Text("+ New order") }
            FilledTonalButton({ vm.createSuggestedPo(null) }, Modifier.weight(1f).height(52.dp)) { Text("⚡ From low stock") }
        }
        if (pos.isEmpty()) EmptyState("🚚", "No purchase orders", "Create one to track what you ordered from suppliers and receive deliveries in parts.")
        LazyColumn(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(pos, key = { it.id }) { po ->
                val name by produceState("…", po.supplierId, suppliers) { value = vm.supplierName(po.supplierId) }
                ElevatedCard(Modifier.fillMaxWidth().clickable { open = po }, shape = RoundedCornerShape(18.dp)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text("PO ${po.id.take(6).uppercase()} · $name", fontWeight = FontWeight.SemiBold); Text(Manila.fmt(po.createdAt, "MMM d, yyyy"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Pill(po.status.name.lowercase(), when (po.status) { PoStatus.RECEIVED -> MaterialTheme.colorScheme.primaryContainer; PoStatus.CANCELLED -> MaterialTheme.colorScheme.errorContainer; PoStatus.DRAFT -> MaterialTheme.colorScheme.surfaceVariant; else -> MaterialTheme.colorScheme.tertiaryContainer })
                    }
                }
            }
        }
    }
    if (creating) NewPoDialog(vm) { creating = false }
    open?.let { PoDetailDialog(vm, it) { open = null } }
}

@Composable
fun NewPoDialog(vm: PosViewModel, onDismiss: () -> Unit) {
    val products by vm.allProducts.collectAsState(); val suppliers by vm.suppliers.collectAsState()
    var sup by remember { mutableStateOf<Supplier?>(null) }; var notes by remember { mutableStateOf("") }; var picking by remember { mutableStateOf(false) }
    val picked = remember { mutableStateListOf<Product>() }; val qtys = remember { mutableStateMapOf<String, String>() }; val costs = remember { mutableStateMapOf<String, String>() }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("New purchase order") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton({ sup = (listOf<Supplier?>(null) + suppliers).next(sup) }) { Text("Supplier: ${sup?.name ?: "none"} (tap to change)") }
            picked.forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(p.name, Modifier.weight(1.2f), fontSize = 13.sp, maxLines = 2)
                    Field("Qty", qtys[p.id] ?: "", { qtys[p.id] = it }, Modifier.weight(0.8f), number = true)
                    Field("Cost ₱", costs[p.id] ?: "", { costs[p.id] = it }, Modifier.weight(0.9f), number = true)
                }
            }
            OutlinedButton({ picking = true }) { Text("+ Add product") }
            Field("Notes (optional)", notes, { notes = it })
        }
    }, confirmButton = {
        Button({
            vm.createPo(sup?.id, picked.map { NewPoLine(it.id, null, Qty.parse(qtys[it.id] ?: "") ?: 0, Money.parse(costs[it.id] ?: "") ?: 0) }, notes.ifBlank { null }); onDismiss()
        }, enabled = picked.isNotEmpty() && picked.all { (Qty.parse(qtys[it.id] ?: "") ?: 0) > 0 }) { Text("Create draft") }
    }, dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
    if (picking) ProductPickerDialog(products.filter { it.active && picked.none { x -> x.id == it.id } }, { p -> picked.add(p); qtys[p.id] = "1"; costs[p.id] = pesos(p.costCentavos) }, { picking = false })
}

@Composable
fun PoDetailDialog(vm: PosViewModel, po: PurchaseOrder, onDismiss: () -> Unit) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    val lines by remember(po.id) { vm.poLines(po.id) }.collectAsState(emptyList())
    val fresh by vm.purchaseOrders.collectAsState(); val cur = fresh.firstOrNull { it.id == po.id } ?: po
    val recv = remember { mutableStateMapOf<String, String>() }; var ref by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("PO ${po.id.take(6).uppercase()}", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Pill(cur.status.name.lowercase()); TextButton(onDismiss) { Text("Close") }
                }
                val receiving = cur.status == PoStatus.ORDERED || cur.status == PoStatus.PARTIAL
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(lines, key = { it.id }) { l ->
                        val remaining = l.qtyMilli - l.receivedMilli
                        Card(shape = RoundedCornerShape(16.dp)) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(l.productName, fontWeight = FontWeight.SemiBold)
                                    Text("Ordered ${Qty.format(l.qtyMilli)} ${l.unitName} · received ${Qty.format(l.receivedMilli)}" + if (l.unitCostCentavos > 0) " · ${Money.format(l.unitCostCentavos)} each" else "", fontSize = 12.sp)
                                }
                                if (receiving && remaining > 0) Field("Receive", recv[l.id] ?: Qty.format(remaining), { recv[l.id] = it }, Modifier.width(110.dp), number = true)
                                else if (remaining <= 0) Pill("✓ done", MaterialTheme.colorScheme.primaryContainer)
                            }
                        }
                    }
                }
                if (receiving) Field("Delivery receipt / reference", ref, { ref = it }, Modifier.padding(top = 8.dp))
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ scope.launch { share(ctx, vm.poText(po.id)) } }, Modifier.weight(1f)) { Text("Share") }
                    if (cur.status == PoStatus.DRAFT) Button({ vm.orderPo(po.id) }, Modifier.weight(1f)) { Text("Mark ordered") }
                    if (receiving) Button({
                        vm.receivePo(po.id, lines.associate { l -> l.id to (Qty.parse(recv[l.id] ?: Qty.format(l.qtyMilli - l.receivedMilli)) ?: 0) }, ref.ifBlank { null })
                    }, Modifier.weight(1f)) { Text("Receive") }
                    if (cur.status != PoStatus.RECEIVED && cur.status != PoStatus.CANCELLED) TextButton({ vm.cancelPo(po.id) }) { Text("Cancel PO") }
                }
            }
        }
    }
}
