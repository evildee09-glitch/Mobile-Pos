@file:OptIn(ExperimentalMaterial3Api::class)

package ph.sarisari.mpos.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ph.sarisari.mpos.data.*
import ph.sarisari.mpos.domain.ZReadCalculator
import ph.sarisari.mpos.sync.SyncScheduler
import ph.sarisari.mpos.util.Manila
import ph.sarisari.mpos.util.Money
import ph.sarisari.mpos.util.Qty
import java.util.UUID

private fun <T> List<T>.next(cur: T): T = this[(indexOf(cur) + 1) % size]

// ---------- products / inventory ----------
@Composable
fun ProductsScreen(vm: PosViewModel) {
    val products by vm.allProducts.collectAsState(); val low by vm.lowStock.collectAsState(); val units by vm.units.collectAsState()
    var edit by remember { mutableStateOf<Product?>(null) }; var adding by remember { mutableStateOf(false) }
    var receive by remember { mutableStateOf<Product?>(null) }; var adjust by remember { mutableStateOf<Product?>(null) }
    var filter by remember { mutableStateOf("") }
    Column(Modifier.padding(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (vm.can(Perm.MANAGE_CATALOG)) Button({ adding = true }) { Text("+ Product") }
            Field("Filter", filter, { filter = it }, Modifier.weight(1f))
        }
        if (low.isNotEmpty()) Text("⚠ Low stock: " + low.joinToString { it.name }, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(vertical = 4.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(products.filter { filter.isBlank() || it.name.contains(filter, true) || it.barcode == filter }, key = { it.id }) { p ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        Row {
                            Text(p.name + if (!p.active) " (inactive)" else "", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                            Text(Money.format(p.priceCentavos))
                        }
                        Text("Stock ${Qty.format(p.stockMilli)} ${p.baseUnit} • cost ${Money.format(p.costCentavos)}" +
                            (units[p.id]?.joinToString(prefix = " • ") { "${it.unitName}=${Qty.format(it.factorMilli)}" } ?: ""), fontSize = 12.sp)
                        Row {
                            if (vm.can(Perm.MANAGE_CATALOG)) TextButton({ edit = p }) { Text("Edit") }
                            if (vm.can(Perm.STOCK_ADJUST)) { TextButton({ receive = p }) { Text("Receive") }; TextButton({ adjust = p }) { Text("Adjust") } }
                        }
                    }
                }
            }
        }
    }
    if (adding) ProductDialog(vm, null, emptyList()) { adding = false }
    edit?.let { ProductDialog(vm, it, units[it.id] ?: emptyList()) { edit = null } }
    receive?.let { ReceiveDialog(vm, it, units[it.id] ?: emptyList()) { receive = null } }
    adjust?.let { AdjustDialog(vm, it) { adjust = null } }
}

@Composable
fun ProductDialog(vm: PosViewModel, p: Product?, units: List<ProductUnit>, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(p?.name ?: "") }; var barcode by remember { mutableStateOf(p?.barcode ?: "") }
    var sku by remember { mutableStateOf(p?.sku ?: "") }; var cat by remember { mutableStateOf(p?.category ?: "") }
    var unit by remember { mutableStateOf(p?.baseUnit ?: "piece") }
    var cost by remember { mutableStateOf(p?.let { Money.format(it.costCentavos).removePrefix("₱").replace(",", "") } ?: "") }
    var price by remember { mutableStateOf(p?.let { Money.format(it.priceCentavos).removePrefix("₱").replace(",", "") } ?: "") }
    var reorder by remember { mutableStateOf(p?.let { Qty.format(it.reorderMilli) } ?: "0") }
    var frac by remember { mutableStateOf(p?.allowsFraction ?: false) }; var fav by remember { mutableStateOf(p?.favorite ?: false) }
    var active by remember { mutableStateOf(p?.active ?: true) }
    val u0 = units.firstOrNull()
    var caseName by remember { mutableStateOf(u0?.unitName ?: "") }; var caseFactor by remember { mutableStateOf(u0?.let { Qty.format(it.factorMilli) } ?: "") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (p == null) "New product" else "Edit product") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Field("Name", name, { name = it }); Field("Barcode", barcode, { barcode = it }); Field("SKU", sku, { sku = it }); Field("Category", cat, { cat = it })
            Field("Base unit (piece, kg, sachet…)", unit, { unit = it })
            Field("Cost per base unit ₱", cost, { cost = it }, number = true); Field("Selling price per base unit ₱", price, { price = it }, number = true)
            Field("Reorder level", reorder, { reorder = it }, number = true)
            Row { Switch(frac, { frac = it }); Text("  Sold by weight/volume (decimals)") }
            Row { Switch(fav, { fav = it }); Text("  Favorite (quick button)") }
            if (p != null) Row { Switch(active, { active = it }); Text("  Active") }
            Text("Wholesale unit (optional): e.g. case = 24", fontSize = 12.sp)
            Field("Wholesale unit name", caseName, { caseName = it }); Field("Base units per wholesale unit", caseFactor, { caseFactor = it }, number = true)
        }
    }, confirmButton = {
        Button({
            val now = System.currentTimeMillis()
            val prod = Product(p?.id ?: UUID.randomUUID().toString(), sku.ifBlank { null }, barcode.ifBlank { null }, name.trim(), cat.ifBlank { null },
                Money.parse(cost) ?: 0, Money.parse(price) ?: 0, p?.stockMilli ?: 0, unit.trim().ifBlank { "piece" }, frac, Qty.parse(reorder) ?: 0,
                p?.supplierId, fav, active, p?.expiryDate, p?.batchNo, p?.createdAt ?: now, now)
            val extra = Qty.parse(caseFactor)?.takeIf { caseName.isNotBlank() && it > 0 }?.let { listOf(ProductUnit(prod.id, caseName.trim(), it)) } ?: emptyList()
            vm.saveProduct(prod, extra); onDismiss()
        }, enabled = name.isNotBlank() && Money.parse(price) != null) { Text("Save") }
    }, dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

@Composable
fun ReceiveDialog(vm: PosViewModel, p: Product, units: List<ProductUnit>, onDismiss: () -> Unit) {
    val opts = listOf(p.baseUnit) + units.map { it.unitName }
    var unit by remember { mutableStateOf(p.baseUnit) }; var qty by remember { mutableStateOf("") }; var cost by remember { mutableStateOf("") }; var ref by remember { mutableStateOf("") }
    val suppliers by vm.suppliers.collectAsState(); var sup by remember { mutableStateOf<Supplier?>(null) }
    var newSup by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Receive: ${p.name}") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton({ unit = opts.next(unit) }) { Text("Unit: $unit (tap to change)") }
            Field("Quantity received", qty, { qty = it }, number = true); Field("Cost per $unit ₱ (optional)", cost, { cost = it }, number = true)
            OutlinedButton({ sup = (listOf<Supplier?>(null) + suppliers).next(sup) }) { Text("Supplier: ${sup?.name ?: "none"} (tap to change)") }
            Field("New supplier name", newSup, { newSup = it }); if (newSup.isNotBlank()) TextButton({ vm.saveSupplier(newSup, null); newSup = "" }) { Text("Add supplier") }
            Field("Delivery receipt / reference", ref, { ref = it })
        }
    }, confirmButton = {
        Button({ vm.receive(p.id, unit, Qty.parse(qty) ?: 0, Money.parse(cost) ?: 0, sup?.id, ref.ifBlank { null }); onDismiss() }, enabled = (Qty.parse(qty) ?: 0) > 0) { Text("Receive") }
    }, dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

@Composable
fun AdjustDialog(vm: PosViewModel, p: Product, onDismiss: () -> Unit) {
    val types = listOf(MoveType.ADJUSTMENT, MoveType.DAMAGED, MoveType.EXPIRED, MoveType.LOST, MoveType.RETURN)
    var type by remember { mutableStateOf(MoveType.ADJUSTMENT) }; var qty by remember { mutableStateOf("") }; var reason by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Adjust: ${p.name} (now ${Qty.format(p.stockMilli)})") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton({ type = types.next(type) }) { Text("Type: $type (tap to change)") }
            Field("Quantity (base units; damaged/expired/lost reduce stock)", qty, { qty = it }, number = true); Field("Reason (required)", reason, { reason = it })
        }
    }, confirmButton = {
        Button({
            val q = Qty.parse(qty) ?: 0
            val signed = if (type in listOf(MoveType.DAMAGED, MoveType.EXPIRED, MoveType.LOST)) -Math.abs(q) else q
            vm.adjust(p.id, type, signed, reason); onDismiss()
        }, enabled = (Qty.parse(qty) ?: 0) != 0L && reason.isNotBlank()) { Text("Apply") }
    }, dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

// ---------- customers / Utang ----------
@Composable
fun CustomersScreen(vm: PosViewModel) {
    val customers by vm.customers.collectAsState(); val balances by vm.balances.collectAsState()
    var detail by remember { mutableStateOf<Customer?>(null) }; var edit by remember { mutableStateOf<Customer?>(null) }; var adding by remember { mutableStateOf(false) }
    Column(Modifier.padding(8.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Button({ adding = true }) { Text("+ Customer") }
            Spacer(Modifier.width(12.dp)); Text("Total Utang: ${Money.format(balances.values.sum())}", fontWeight = FontWeight.Bold)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
            items(customers, key = { it.id }) { c ->
                val b = balances[c.id] ?: 0
                ElevatedCard(Modifier.fillMaxWidth().clickable { detail = c }) {
                    Row(Modifier.padding(12.dp)) {
                        Column(Modifier.weight(1f)) { Text(c.name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold); Text("Limit ${Money.format(c.creditLimitCentavos)}", fontSize = 12.sp) }
                        Text(Money.format(b), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = if (b > c.creditLimitCentavos) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
    if (adding) CustomerEditDialog(vm, null) { adding = false }
    edit?.let { CustomerEditDialog(vm, it) { edit = null } }
    detail?.let { c -> CustomerDetailDialog(vm, c, balances[c.id] ?: 0, { edit = c; detail = null }) { detail = null } }
}

@Composable
fun CustomerEditDialog(vm: PosViewModel, c: Customer?, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(c?.name ?: "") }; var phone by remember { mutableStateOf(c?.phone ?: "") }
    var limit by remember { mutableStateOf(Money.format(c?.creditLimitCentavos ?: 100_000).removePrefix("₱").replace(",", "")) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (c == null) "New customer" else "Edit customer") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { Field("Name", name, { name = it }); Field("Phone (optional)", phone, { phone = it }); Field("Credit limit ₱", limit, { limit = it }, number = true) }
    }, confirmButton = { Button({ vm.saveCustomer(c?.id, name, phone.ifBlank { null }, Money.parse(limit) ?: 0); onDismiss() }, enabled = name.isNotBlank() && Money.parse(limit) != null) { Text("Save") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

@Composable
fun CustomerDetailDialog(vm: PosViewModel, c: Customer, balance: Long, onEdit: () -> Unit, onDismiss: () -> Unit) {
    val rows by produceState<List<StatementRow>>(emptyList(), balance) { value = vm.statement(c.id) }
    var amt by remember { mutableStateOf("") }; var method by remember { mutableStateOf(PaymentMethod.CASH) }; var ref by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("${c.name} — owes ${Money.format(balance)}") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            rows.forEach { Text("${it.whenText}  ${it.label}  ${Money.format(it.amount)}  → ${Money.format(it.running)}", fontSize = 12.sp) }
            HorizontalDivider()
            Field("Payment amount ₱", amt, { amt = it }, number = true)
            OutlinedButton({ method = PaymentMethod.values().toList().next(method) }) { Text("Method: $method (tap to change)") }
            if (method != PaymentMethod.CASH) Field("Reference #", ref, { ref = it })
        }
    }, confirmButton = {
        Row {
            TextButton(onEdit) { Text("Edit") }
            Button({ vm.collect(c.id, method, Money.parse(amt) ?: 0, ref.ifBlank { null }); amt = "" }, enabled = (Money.parse(amt) ?: 0) > 0 && balance > 0) { Text("Collect") }
        }
    }, dismissButton = { TextButton(onDismiss) { Text("Close") } })
}

// ---------- shift / Z-read ----------
@Composable
fun ShiftScreen(vm: PosViewModel) {
    val shift by vm.shift.collectAsState(); val today by vm.today.collectAsState(); val zText by vm.zText.collectAsState(); val staff by vm.staff.collectAsState()
    var float by remember { mutableStateOf("") }; var counted by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(CashMoveType.EXPENSE) }; var amt by remember { mutableStateOf("") }; var reason by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }
    Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val s = shift
        if (s == null) {
            Text("No open shift", style = MaterialTheme.typography.titleLarge)
            Field("Opening cash float ₱", float, { float = it }, number = true)
            Button({ vm.openShift(Money.parse(float) ?: 0) }, enabled = Money.parse(float) != null) { Text("Open shift") }
        } else {
            val preview by produceState("", s, today, tick, staff) {
                val z = vm.zPreview(null)
                value = if (z == null) "" else ZReadCalculator.text(z, vm.setting("store_name").ifBlank { "Store" }, s, null, staff.orEmpty().associate { it.id to it.name })
            }
            Text("Shift open since ${Manila.fmt(s.openedAt)}", fontWeight = FontWeight.Bold)
            Text(preview, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 11.sp)
            HorizontalDivider()
            Text("Cash in / out", fontWeight = FontWeight.Bold)
            OutlinedButton({ type = CashMoveType.values().toList().next(type) }) { Text("Type: $type (tap to change)") }
            Field("Amount ₱", amt, { amt = it }, number = true); Field("Reason", reason, { reason = it })
            Button({ vm.cashMove(type, Money.parse(amt) ?: 0, reason); amt = ""; reason = ""; tick++ }, enabled = (Money.parse(amt) ?: 0) > 0 && reason.isNotBlank()) { Text("Record") }
            HorizontalDivider()
            Text("Close shift", fontWeight = FontWeight.Bold)
            Field("Counted cash in drawer ₱", counted, { counted = it }, number = true)
            Button({ vm.closeShift(Money.parse(counted) ?: 0) }, enabled = Money.parse(counted) != null) { Text("Close shift & print Z-Read") }
        }
    }
    zText?.let { z -> TextDialog("Z-Read", z, { vm.zText.value = null }) { TextButton({ vm.printReceipt(z, false) }) { Text("Print") } } }
}

// ---------- reports ----------
@Composable
fun ReportsScreen(vm: PosViewModel) {
    LaunchedEffect(Unit) { vm.refreshDay() }
    val today by vm.today.collectAsState(); val profit by vm.profit.collectAsState(); val low by vm.lowStock.collectAsState()
    val sales by vm.recentSales.collectAsState(); val pending by vm.pendingSync.collectAsState(); val balances by vm.balances.collectAsState()
    val ctx = LocalContext.current
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
        uri?.let { ctx.contentResolver.openOutputStream(it, "wt")?.use { o -> o.write(vm.productsCsv().toByteArray()) } }
    }
    var voiding by remember { mutableStateOf<Sale?>(null) }; var why by remember { mutableStateOf("") }
    LazyColumn(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Text("Today's sales (completed): ${Money.format(today.total)}  •  ${today.count} transactions", fontWeight = FontWeight.Bold)
            Text("Estimated gross profit (before discounts, not realized cash): ${Money.format(profit)}", fontSize = 12.sp)
            Text("Total Utang outstanding: ${Money.format(balances.values.sum())}  •  Pending sync: $pending", fontSize = 12.sp)
            if (low.isNotEmpty()) Text("Low stock: " + low.joinToString { "${it.name} (${Qty.format(it.stockMilli)})" }, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            OutlinedButton({ export.launch("products.csv") }) { Text("Export products CSV") }
            Text("Recent sales", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        }
        items(sales, key = { it.id }) { s ->
            Row {
                Column(Modifier.weight(1f)) {
                    Text("${s.receiptNumber}  ${Money.format(s.totalCentavos)}${if (s.status == SaleStatus.VOIDED) "  VOIDED" else ""}", fontSize = 13.sp)
                    Text(Manila.fmt(s.createdAt), fontSize = 11.sp)
                }
                TextButton({ vm.reprint(s.id) }) { Text("Receipt") }
                if (s.status == SaleStatus.COMPLETED && vm.can(Perm.VOID)) TextButton({ voiding = s }) { Text("Void") }
            }
        }
    }
    voiding?.let { s ->
        AlertDialog(onDismissRequest = { voiding = null }, title = { Text("Void ${s.receiptNumber}?") },
            text = { Field("Reason (required)", why, { why = it }) },
            confirmButton = { Button({ vm.voidSale(s.id, why); voiding = null; why = "" }, enabled = why.isNotBlank()) { Text("Void sale") } },
            dismissButton = { TextButton({ voiding = null }) { Text("Cancel") } })
    }
}

// ---------- settings / backup ----------
fun restartApp(ctx: Context) {
    ctx.startActivity(ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
    Runtime.getRuntime().exit(0)
}

@Composable
fun SettingsScreen(vm: PosViewModel) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    val keys = listOf("store_name" to "Store name", "store_phone" to "Store phone", "receipt_width" to "Receipt width (32 = 58 mm, 48 = 80 mm)",
        "printer_mac" to "Bluetooth printer MAC (paired)", "sync_url" to "Cloud sync URL (optional, https)", "sync_token" to "Sync token")
    val values = remember { mutableStateMapOf<String, String>() }
    LaunchedEffect(Unit) { keys.forEach { (k, _) -> values[k] = vm.setting(k) } }
    var pass by remember { mutableStateOf("") }; var name by remember { mutableStateOf("") }; var pin by remember { mutableStateOf("") }; var role by remember { mutableStateOf(Role.CASHIER) }
    val lastBackup by produceState("never") { value = vm.setting("last_backup_at").toLongOrNull()?.let { Manila.fmt(it) } ?: "never" }
    val me = vm.user.value
    val exportL = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null && me != null) scope.launch { try { vm.c.backup.exportTo(uri, pass.toCharArray(), me.id); vm.toast.value = "Backup saved" } catch (e: Exception) { vm.toast.value = e.message } }
    }
    val restoreL = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && me != null) scope.launch { try { vm.c.backup.restoreFrom(uri, pass.toCharArray(), me.id); restartApp(ctx) } catch (e: Exception) { vm.toast.value = e.message } }
    }
    Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Store settings", fontWeight = FontWeight.Bold)
        keys.forEach { (k, label) -> Field(label, values[k] ?: "", { values[k] = it }) }
        Button({ keys.forEach { (k, _) -> vm.putSetting(k, values[k] ?: "") }; vm.toast.value = "Settings saved" }) { Text("Save settings") }
        OutlinedButton({ SyncScheduler.syncNow(ctx); vm.toast.value = "Sync queued (needs internet + URL)" }) { Text("Sync now") }
        HorizontalDivider()
        Text("Backup & restore  (last backup: $lastBackup)", fontWeight = FontWeight.Bold)
        Text("Data lives only on this device. Back up regularly to a USB drive / cloud folder you control.", fontSize = 12.sp)
        Field("Backup passphrase (min 6 chars — don't lose it)", pass, { pass = it })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ exportL.launch("sarisari-backup-${Manila.fmt(System.currentTimeMillis(), "yyyyMMdd-HHmm")}.ssmb") }, enabled = pass.length >= 6) { Text("Export backup") }
            OutlinedButton({ restoreL.launch(arrayOf("*/*")) }, enabled = pass.length >= 6) { Text("Restore…") }
        }
        HorizontalDivider()
        Text("Add staff", fontWeight = FontWeight.Bold)
        Field("Name", name, { name = it }); Field("4-digit PIN", pin, { if (it.length <= 4) pin = it.filter(Char::isDigit) }, number = true, decimal = false)
        OutlinedButton({ role = Role.values().toList().next(role) }) { Text("Role: $role (tap to change)") }
        Button({ vm.addStaff(name, role, pin); name = ""; pin = "" }, enabled = name.isNotBlank() && pin.length == 4) { Text("Add staff") }
    }
}
