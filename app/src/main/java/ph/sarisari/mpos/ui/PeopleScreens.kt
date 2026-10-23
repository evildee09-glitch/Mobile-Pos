@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package ph.sarisari.mpos.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ph.sarisari.mpos.data.*
import ph.sarisari.mpos.domain.Channel
import ph.sarisari.mpos.domain.Rbac
import ph.sarisari.mpos.domain.Reminders
import ph.sarisari.mpos.domain.ZReadCalculator
import ph.sarisari.mpos.util.Manila
import ph.sarisari.mpos.util.Money
import ph.sarisari.mpos.util.Qty

private fun pesos(c: Long) = Money.format(c).removePrefix("₱").replace(",", "")

// ---------- customers / Utang ----------
@Composable
fun CustomersScreen(vm: PosViewModel) {
    val customers by vm.customers.collectAsState(); val balances by vm.balances.collectAsState()
    var detail by remember { mutableStateOf<Customer?>(null) }; var edit by remember { mutableStateOf<Customer?>(null) }; var adding by remember { mutableStateOf(false) }
    var q by remember { mutableStateOf("") }
    Column(Modifier.padding(12.dp)) {
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(16.dp)) { Text("Total Utang (pa-utang)", fontSize = 12.sp); Text(Money.format(balances.values.sum()), fontSize = 28.sp, fontWeight = FontWeight.ExtraBold) }
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Field("Search customer", q, { q = it }, Modifier.weight(1f)); Button({ adding = true }, Modifier.height(56.dp), shape = RoundedCornerShape(16.dp)) { Text("+ New") }
        }
        val list = customers.filter { q.isBlank() || it.name.contains(q, true) }.sortedByDescending { balances[it.id] ?: 0 }
        if (list.isEmpty()) EmptyState("📒", "No customers yet", "Add regulars to track their utang.")
        LazyVerticalGrid(GridCells.Adaptive(340.dp), Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list, key = { it.id }) { c ->
                val b = balances[c.id] ?: 0
                ElevatedCard(Modifier.fillMaxWidth().clickable { detail = c }, shape = RoundedCornerShape(18.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        ProductAvatar(c.name, 44.dp); Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) { Text(c.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold); Text("Limit ${Money.format(c.creditLimitCentavos)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
    var limit by remember { mutableStateOf(pesos(c?.creditLimitCentavos ?: 100_000)) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (c == null) "New customer" else "Edit customer") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Field("Name", name, { name = it }); Field("Mobile (for reminders, e.g. 0917…)", phone, { phone = it }); Field("Credit limit ₱", limit, { limit = it }, number = true)
        }
    }, confirmButton = { Button({ vm.saveCustomer(c?.id, name, phone.ifBlank { null }, Money.parse(limit) ?: 0); onDismiss() }, enabled = name.isNotBlank() && Money.parse(limit) != null) { Text("Save") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

@Composable
fun CustomerDetailDialog(vm: PosViewModel, c: Customer, balance: Long, onEdit: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    val rows by produceState<List<StatementRow>>(emptyList(), balance) { value = vm.statement(c.id) }
    var amt by remember { mutableStateOf("") }; var method by remember { mutableStateOf(PaymentMethod.CASH) }; var ref by remember { mutableStateOf("") }
    var dup by remember { mutableStateOf<Pair<String, Channel>?>(null) }
    fun send(text: String, ch: Channel) {
        val msg = Reminders.launch(ctx, ch, c.phone, text); if (msg != null) vm.toast.value = msg
        if (msg == null || msg.startsWith("Message copied")) vm.logReminder(c.id, ch)
    }
    fun remind(ch: Channel) = scope.launch {
        try { val (_, text, recent) = vm.prepareReminder(c.id, ch); if (recent) dup = text to ch else send(text, ch) } catch (e: Exception) { vm.toast.value = e.message }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Column { Text(c.name); Text("Owes ${Money.format(balance)}", fontSize = 15.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) } }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Collect payment", fontWeight = FontWeight.Bold)
            Field("Amount ₱", amt, { amt = it }, number = true)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PaymentMethod.values().forEach { m -> FilterChip(method == m, { method = m }, label = { Text(m.name.lowercase().replaceFirstChar { it.uppercase() }) }) }
                AssistChip({ amt = pesos(balance) }, label = { Text("Full balance") })
            }
            if (method != PaymentMethod.CASH) Field("Reference #", ref, { ref = it })
            Button({ vm.collect(c.id, method, Money.parse(amt) ?: 0, ref.ifBlank { null }); amt = "" }, Modifier.fillMaxWidth(), enabled = (Money.parse(amt) ?: 0) > 0 && balance > 0) { Text("Collect") }
            if (balance > 0) {
                Text("Send reminder", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                if (Reminders.normalizePhone(c.phone) == null) Text("Add a valid mobile number (Edit) to send reminders.", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Channel.values().forEach { ch -> AssistChip({ remind(ch) }, label = { Text(ch.label) }) } }
            }
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Text("Statement", fontWeight = FontWeight.Bold)
            rows.forEach { Text("${it.whenText}  ${it.label}  ${Money.format(it.amount)}  → ${Money.format(it.running)}", fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
        }
    }, confirmButton = { TextButton(onEdit) { Text("Edit") } }, dismissButton = { TextButton(onDismiss) { Text("Close") } })
    dup?.let { (text, ch) ->
        AlertDialog(onDismissRequest = { dup = null }, title = { Text("Already reminded") }, text = { Text("${c.name} got a ${ch.label} reminder in the last 20 hours. Send another?") },
            confirmButton = { Button({ send(text, ch); dup = null }) { Text("Send again") } }, dismissButton = { TextButton({ dup = null }) { Text("Cancel") } })
    }
}

// ---------- shift / Z-read ----------
@Composable
fun ShiftScreen(vm: PosViewModel) {
    val shift by vm.shift.collectAsState(); val today by vm.today.collectAsState(); val zText by vm.zText.collectAsState(); val staff by vm.staff.collectAsState()
    var float by remember { mutableStateOf("") }; var counted by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(CashMoveType.EXPENSE) }; var amt by remember { mutableStateOf("") }; var reason by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }
    Column(Modifier.padding(14.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val s = shift
        if (s == null) {
            EmptyState("🌅", "Start your day", "Count the cash in the drawer and open a shift to begin selling.")
            Field("Opening cash float ₱", float, { float = it }, number = true)
            BigButton("Open shift", { vm.openShift(Money.parse(float) ?: 0) }, enabled = Money.parse(float) != null)
        } else {
            val preview by produceState("", s, today, tick, staff) {
                val z = vm.zPreview(null)
                value = if (z == null) "" else ZReadCalculator.text(z, vm.setting("store_name").ifBlank { "Store" }, s, null, staff.orEmpty().associate { it.id to it.name })
            }
            val expected by produceState(0L, s, today, tick) { value = vm.zPreview(null)?.expectedCash ?: 0 }
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.padding(18.dp)) {
                    Text("Cash that should be in the drawer", fontSize = 12.sp); Text(Money.format(expected), fontSize = 34.sp, fontWeight = FontWeight.ExtraBold)
                    Text("Shift open since ${Manila.fmt(s.openedAt)}", fontSize = 12.sp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("Today's sales", Money.format(today.total), Modifier.weight(1f)); StatCard("Transactions", today.count.toString(), Modifier.weight(1f), MaterialTheme.colorScheme.secondaryContainer)
            }
            SectionTitle("Cash in / out")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CashMoveType.values().forEach { t -> FilterChip(type == t, { type = t }, label = { Text(when (t) { CashMoveType.ADD -> "➕ Add cash"; CashMoveType.WITHDRAW -> "➖ Withdraw"; else -> "🧾 Expense" }) }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Field("Amount ₱", amt, { amt = it }, Modifier.weight(1f), number = true); Field("Reason", reason, { reason = it }, Modifier.weight(1.3f)) }
            FilledTonalButton({ vm.cashMove(type, Money.parse(amt) ?: 0, reason); amt = ""; reason = ""; tick++ }, Modifier.fillMaxWidth(), enabled = (Money.parse(amt) ?: 0) > 0 && reason.isNotBlank()) { Text("Record") }
            SectionTitle("Close shift")
            Field("Counted cash in drawer ₱", counted, { counted = it }, number = true)
            BigButton("Close shift & Z-Read", { vm.closeShift(Money.parse(counted) ?: 0) }, enabled = Money.parse(counted) != null)
            Text(preview, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    var voiding by remember { mutableStateOf<Sale?>(null) }; var why by remember { mutableStateOf("") }
    LazyColumn(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { StatCard("Sales today", Money.format(today.total), Modifier.weight(1f)); StatCard("Transactions", today.count.toString(), Modifier.weight(1f), MaterialTheme.colorScheme.secondaryContainer) }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { StatCard("Est. gross profit", Money.format(profit), Modifier.weight(1f), MaterialTheme.colorScheme.tertiaryContainer); StatCard("Utang owed", Money.format(balances.values.sum()), Modifier.weight(1f), MaterialTheme.colorScheme.tertiaryContainer) }
            Text("Profit is an estimate before discounts, not cash collected. Pending sync: $pending", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            if (low.isNotEmpty()) { SectionTitle("Low stock"); Text(low.joinToString { "${it.name} (${Qty.format(it.stockMilli)})" }, fontSize = 13.sp) }
            SectionTitle("Recent sales")
        }
        items(sales, key = { it.id }) { s ->
            Card(shape = RoundedCornerShape(14.dp)) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("${s.receiptNumber}${if (s.status == SaleStatus.VOIDED) "  · VOIDED" else ""}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold); Text(Manila.fmt(s.createdAt), fontSize = 11.sp) }
                    Text(Money.format(s.totalCentavos), fontWeight = FontWeight.Bold)
                    TextButton({ vm.reprint(s.id) }) { Text("Receipt") }
                    if (s.status == SaleStatus.COMPLETED && vm.can(Perm.VOID)) TextButton({ voiding = s }) { Text("Void") }
                }
            }
        }
    }
    voiding?.let { s ->
        AlertDialog(onDismissRequest = { voiding = null }, title = { Text("Void ${s.receiptNumber}?") }, text = { Field("Reason (required)", why, { why = it }) },
            confirmButton = { Button({ vm.voidSale(s.id, why); voiding = null; why = "" }, enabled = why.isNotBlank()) { Text("Void sale") } }, dismissButton = { TextButton({ voiding = null }) { Text("Cancel") } })
    }
}

// ---------- more ----------
@Composable
fun MoreScreen(vm: PosViewModel, user: Staff) {
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    val can = { p: Perm -> Rbac.can(user.role, p) }
    if (page == null) {
        Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            fun item(key: String, icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String) = key to Triple(icon, title, sub)
            listOfNotNull(
                if (can(Perm.CLOSE_SHIFT)) item("reports", Icons.Filled.Info, "Reports", "Sales, profit, low stock, void a sale") else null,
                if (can(Perm.STOCK_ADJUST)) item("purchasing", Icons.Filled.Send, "Purchase orders", "Order from suppliers, receive deliveries") else null,
                if (can(Perm.MANAGE_CATALOG)) item("import", Icons.Filled.Share, "Import / export CSV", "Bulk-load or export your product list") else null,
                if (can(Perm.MANAGE_STAFF)) item("settings", Icons.Filled.Settings, "Settings", "Store, printer, cloud sync, backup, staff") else null
            ).forEach { (key, t) ->
                ElevatedCard(Modifier.fillMaxWidth().clickable { page = key }, shape = RoundedCornerShape(18.dp)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(t.first, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(14.dp))
                        Column { Text(t.second, fontSize = 17.sp, fontWeight = FontWeight.SemiBold); Text(t.third, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
            OutlinedButton({ vm.logout() }, Modifier.fillMaxWidth().height(52.dp)) { Text("Log out ${user.name}") }
        }
    } else Column {
        TextButton({ page = null }, Modifier.padding(horizontal = 8.dp)) { Text("← Back") }
        Box(Modifier.weight(1f)) {
            when (page) { "reports" -> ReportsScreen(vm); "purchasing" -> PurchasingScreen(vm); "import" -> ImportExportScreen(vm); "settings" -> SettingsScreen(vm) }
        }
    }
}
