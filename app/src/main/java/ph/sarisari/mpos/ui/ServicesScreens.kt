@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package ph.sarisari.mpos.ui

import androidx.compose.foundation.layout.*
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
import ph.sarisari.mpos.data.Perm
import ph.sarisari.mpos.data.ServiceTxn
import ph.sarisari.mpos.data.ServiceType
import ph.sarisari.mpos.domain.CashServiceService
import ph.sarisari.mpos.util.Manila
import ph.sarisari.mpos.util.Money

private fun pesos(c: Long) = Money.format(c).removePrefix("₱").replace(",", "")
private fun typeLabel(t: ServiceType) = when (t) { ServiceType.CASH_IN -> "💵→📱 Cash-in"; ServiceType.CASH_OUT -> "📱→💵 Cash-out"; ServiceType.LOAD -> "📶 E-load"; ServiceType.BILLS -> "🧾 Bills" }

@Composable
fun ServicesScreen(vm: PosViewModel) {
    val shift by vm.shift.collectAsState(); val day by vm.serviceDay.collectAsState(); val recent by vm.recentServices.collectAsState()
    val pct by produceState(0.0) { value = vm.setting("service_fee_pct").toDoubleOrNull() ?: 0.0 }
    var type by remember { mutableStateOf(ServiceType.CASH_IN) }; var provider by remember { mutableStateOf("GCash") }
    var amount by remember { mutableStateOf("") }; var fee by remember { mutableStateOf("") }; var feeTouched by remember { mutableStateOf(false) }
    var feeInCash by remember { mutableStateOf(true) }; var customer by remember { mutableStateOf("") }; var ref by remember { mutableStateOf("") }
    var voiding by remember { mutableStateOf<ServiceTxn?>(null) }; var why by remember { mutableStateOf("") }
    val amt = Money.parse(amount) ?: 0; val feeV = Money.parse(fee) ?: 0
    LaunchedEffect(amount, pct) { if (!feeTouched && pct > 0 && amt > 0) fee = pesos(Math.round(amt * pct / 100.0 / 100.0) * 100) }
    val (cin, cout) = CashServiceService.drawerEffect(type, amt, feeV, feeInCash)
    Column(Modifier.padding(14.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard("Today's transactions", day.count.toString(), Modifier.weight(1f)); StatCard("Fees earned", Money.format(day.fees), Modifier.weight(1f), MaterialTheme.colorScheme.tertiaryContainer)
        }
        if (shift == null) EmptyState("🌅", "Open a shift first", "Cash-in/out moves the drawer, so it is recorded inside a shift (Shift tab).")
        else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { ServiceType.values().forEach { t -> FilterChip(type == t, { type = t }, label = { Text(typeLabel(t)) }) } }
            Text(when (type) { ServiceType.CASH_IN -> "Customer hands you cash; you send it to their wallet."; ServiceType.CASH_OUT -> "You hand the customer cash; they send it to your wallet."
                ServiceType.LOAD -> "Customer pays cash for e-load."; ServiceType.BILLS -> "Customer pays cash for a bill you pay online." }, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("GCash", "Maya", "Globe", "Smart", "Other").forEach { p -> FilterChip(provider == p, { provider = p }, label = { Text(p) }) } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("Amount ₱", amount, { amount = it }, Modifier.weight(1f), number = true); Field("Your fee ₱", fee, { fee = it; feeTouched = true }, Modifier.weight(1f), number = true)
            }
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(feeInCash, { feeInCash = it }); Text("  Fee is paid in cash") }
            Field("Customer name (optional)", customer, { customer = it }); Field("Reference # (optional)", ref, { ref = it })
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.fillMaxWidth().padding(14.dp)) {
                    Column(Modifier.weight(1f)) { Text("Cash into drawer"); Text(Money.format(cin), fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                    Column(horizontalAlignment = Alignment.End) { Text("Cash out of drawer"); Text(Money.format(cout), fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                }
            }
            BigButton("Record transaction", { vm.recordService(type, provider, amt, feeV, feeInCash, customer, ref) { amount = ""; fee = ""; feeTouched = false; customer = ""; ref = "" } }, enabled = amt > 0)
            Text("Only the drawer effect and your fee are tracked — not your GCash/Maya wallet balance. Check your wallet app for that.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SectionTitle("Recent")
        recent.forEach { t ->
            Card(shape = RoundedCornerShape(14.dp)) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${typeLabel(t.type)} · ${t.provider}${if (t.voided) "  · VOIDED" else ""}", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text("${Manila.fmt(t.createdAt)}${t.customerName?.let { " · $it" } ?: ""}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(horizontalAlignment = Alignment.End) { Text(Money.format(t.amountCentavos), fontWeight = FontWeight.Bold); Text("fee ${Money.format(t.feeCentavos)}", fontSize = 11.sp) }
                    if (!t.voided && vm.can(Perm.VOID)) TextButton({ voiding = t }) { Text("Void") }
                }
            }
        }
    }
    voiding?.let { t ->
        AlertDialog(onDismissRequest = { voiding = null }, title = { Text("Void this transaction?") }, text = { Field("Reason (required)", why, { why = it }) },
            confirmButton = { Button({ vm.voidService(t.id, why); voiding = null; why = "" }, enabled = why.isNotBlank()) { Text("Void") } }, dismissButton = { TextButton({ voiding = null }) { Text("Cancel") } })
    }
}
