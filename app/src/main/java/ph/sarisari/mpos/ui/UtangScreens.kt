@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package ph.sarisari.mpos.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import ph.sarisari.mpos.domain.AgingRow
import ph.sarisari.mpos.domain.Channel
import ph.sarisari.mpos.domain.Feature
import ph.sarisari.mpos.domain.Reminders
import ph.sarisari.mpos.util.Money

/** Who owes, and for how long — plus a one-by-one reminder queue (the phone's own SMS/Viber/WhatsApp app opens for each; nothing is sent silently). */
@Composable
fun AgingDialog(vm: PosViewModel, onDismiss: () -> Unit) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    val customers by vm.customers.collectAsState()
    val rows by produceState<List<AgingRow>>(emptyList()) { value = vm.agingRows() }
    val names = customers.associate { it.id to it.name }
    var minDays by remember { mutableIntStateOf(0) }; var selected by remember { mutableStateOf(setOf<String>()) }
    var queue by remember { mutableStateOf<List<String>?>(null) }; var idx by remember { mutableIntStateOf(0) }
    var channel by remember { mutableStateOf(Channel.SMS) }; var confirmRecent by remember { mutableStateOf(false) }
    val shown = rows.filter { it.oldestDays >= minDays }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Text("⏳ Utang aging", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); TextButton(onDismiss) { Text("Close") } }
                val q = queue
                if (q == null) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(0 to "All", 30 to "30+ days", 60 to "60+ days", 90 to "90+ days").forEach { (d, l) -> FilterChip(minDays == d, { minDays = d }, label = { Text(l) }) } }
                    Text("Total shown: ${Money.format(shown.sumOf { it.balance })} · payments are applied to the oldest charges first", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(shown, key = { it.customerId }) { r ->
                            Card(shape = RoundedCornerShape(14.dp)) {
                                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(r.customerId in selected, { selected = if (it) selected + r.customerId else selected - r.customerId })
                                    Column(Modifier.weight(1f)) {
                                        Text(names[r.customerId] ?: "Customer", fontWeight = FontWeight.SemiBold)
                                        Text("oldest ${r.oldestDays}d · " + listOfNotNull(r.d90.takeIf { it > 0 }?.let { "90+: ${Money.format(it)}" }, r.d60.takeIf { it > 0 }?.let { "60+: ${Money.format(it)}" }, r.d30.takeIf { it > 0 }?.let { "30+: ${Money.format(it)}" }, r.current.takeIf { it > 0 }?.let { "new: ${Money.format(it)}" }).joinToString(" · "), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Text(Money.format(r.balance), fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(Channel.SMS, Channel.VIBER, Channel.WHATSAPP, Channel.OTHER).forEach { c -> FilterChip(channel == c, { channel = c }, label = { Text(c.label) }) } }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton({ selected = shown.map { it.customerId }.toSet() }) { Text("Select all") }
                        Button({ if (vm.need(Feature.BULK_REMINDERS)) { queue = shown.map { it.customerId }.filter { it in selected }; idx = 0; confirmRecent = false } }, Modifier.weight(1f), enabled = selected.isNotEmpty()) { Text("Remind ${selected.size} selected") }
                    }
                } else {
                    val cid = q.getOrNull(idx)
                    if (cid == null) { Text("✅ Done — all reminders in the queue were handled.", fontSize = 18.sp, modifier = Modifier.padding(vertical = 24.dp)); Button({ queue = null }) { Text("Back to list") } }
                    else Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 16.dp)) {
                        Text("Reminder ${idx + 1} of ${q.size}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(names[cid] ?: "Customer", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                        Text("Owes ${Money.format(rows.firstOrNull { it.customerId == cid }?.balance ?: 0)}", fontSize = 18.sp)
                        if (confirmRecent) Text("⚠ Already reminded in the last 20 hours. Tap again to send anyway, or Skip.", color = MaterialTheme.colorScheme.error)
                        BigButton("Open ${channel.label} for ${names[cid] ?: "customer"}", {
                            scope.launch {
                                try {
                                    val (cust, text, recent) = vm.prepareReminder(cid, channel)
                                    if (recent && !confirmRecent) { confirmRecent = true; return@launch }
                                    val msg = Reminders.launch(ctx, channel, cust.phone, text)
                                    if (msg != null) vm.toast.value = msg
                                    if (msg == null || msg.startsWith("Message copied")) { vm.logReminder(cid, channel); idx++; confirmRecent = false }
                                } catch (e: Exception) { vm.toast.value = e.message }
                            }
                        })
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton({ idx++; confirmRecent = false }, Modifier.weight(1f)) { Text("Skip") }
                            OutlinedButton({ queue = null }, Modifier.weight(1f)) { Text("Stop") }
                        }
                        Text("Send the message in the app that opens, then come back here for the next one.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
