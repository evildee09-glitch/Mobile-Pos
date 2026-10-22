@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package ph.sarisari.mpos.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ph.sarisari.mpos.data.Role
import ph.sarisari.mpos.domain.Hardware
import ph.sarisari.mpos.sync.SyncScheduler
import ph.sarisari.mpos.util.Manila

fun restartApp(ctx: Context) {
    ctx.startActivity(ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
    Runtime.getRuntime().exit(0)
}

@Composable
private fun Card2(title: String, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); content() }
    }
}

@Composable
fun SettingsScreen(vm: PosViewModel) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    val v: SnapshotStateMap<String, String> = remember { mutableStateMapOf() }
    val keys = listOf("store_name", "store_phone", "receipt_width", "printer_type", "printer_mac", "printer_host", "sync_url", "sync_token")
    LaunchedEffect(Unit) { keys.forEach { v[it] = vm.setting(it) }; if (v["printer_type"].isNullOrBlank()) v["printer_type"] = "NONE"; if (v["receipt_width"].isNullOrBlank()) v["receipt_width"] = "32" }
    fun save() { keys.forEach { vm.putSetting(it, v[it] ?: "") }; vm.toast.value = "Settings saved" }
    var pass by remember { mutableStateOf("") }; var name by remember { mutableStateOf("") }; var pin by remember { mutableStateOf("") }; var role by remember { mutableStateOf(Role.CASHIER) }
    val lastBackup by produceState("never") { value = vm.setting("last_backup_at").toLongOrNull()?.let { Manila.fmt(it) } ?: "never" }
    val lastSync by vm.lastSync.collectAsState(); val syncErr by vm.syncError.collectAsState(); val pending by vm.pendingSync.collectAsState()
    var paired by remember { mutableStateOf(Hardware.pairedDevices(ctx)) }
    val btPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { paired = Hardware.pairedDevices(ctx) }
    val me = vm.user.value
    val exportL = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null && me != null) scope.launch { try { vm.c.backup.exportTo(uri, pass.toCharArray(), me.id); vm.toast.value = "Backup saved" } catch (e: Exception) { vm.toast.value = e.message } }
    }
    val restoreL = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && me != null) scope.launch { try { vm.c.backup.restoreFrom(uri, pass.toCharArray(), me.id); restartApp(ctx) } catch (e: Exception) { vm.toast.value = e.message } }
    }
    Column(Modifier.padding(14.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card2("🏪 Store") {
            Field("Store name", v["store_name"] ?: "", { v["store_name"] = it }); Field("Phone", v["store_phone"] ?: "", { v["store_phone"] = it })
        }
        Card2("🖨 Printer & cash drawer") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("NONE" to "None", "BLUETOOTH" to "Bluetooth", "NETWORK" to "Network (LAN)").forEach { (k, l) -> FilterChip(v["printer_type"] == k, { v["printer_type"] = k }, label = { Text(l) }) }
            }
            if (v["printer_type"] == "BLUETOOTH") {
                if (Build.VERSION.SDK_INT >= 31) OutlinedButton({ btPerm.launch(Manifest.permission.BLUETOOTH_CONNECT) }) { Text("Allow Bluetooth access / refresh list") }
                if (paired.isEmpty()) Text("Pair the printer in Android Bluetooth settings first, then refresh.", fontSize = 12.sp)
                paired.forEach { (n, mac) -> FilterChip(v["printer_mac"] == mac, { v["printer_mac"] = mac }, label = { Text("$n  ($mac)") }) }
            }
            if (v["printer_type"] == "NETWORK") Field("Printer IP[:port]  e.g. 192.168.1.50:9100", v["printer_host"] ?: "", { v["printer_host"] = it })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("32" to "58 mm paper", "48" to "80 mm paper").forEach { (k, l) -> FilterChip(v["receipt_width"] == k, { v["receipt_width"] = k }, label = { Text(l) }) } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton({ save(); vm.testPrint() }) { Text("Test print") }; FilledTonalButton({ save(); vm.openDrawer() }) { Text("Open drawer") }
            }
            Text("Printer and drawer support is generic ESC/POS and has NOT been verified on real terminals. Use Test print on your device; built-in printers of some terminals need the vendor SDK.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Card2("☁ Cloud sync (optional)") {
            Field("Server URL (https://…)", v["sync_url"] ?: "", { v["sync_url"] = it }); Field("Store token", v["sync_token"] ?: "", { v["sync_token"] = it })
            Text(buildString { append("Pending: $pending"); lastSync?.toLongOrNull()?.let { append(" · last sync ${Manila.fmt(it)}") }; if (!syncErr.isNullOrBlank()) append("\nLast error: $syncErr") }, fontSize = 12.sp)
            FilledTonalButton({ save(); SyncScheduler.syncNow(ctx); vm.toast.value = "Sync queued (needs internet)" }) { Text("Save & sync now") }
            Text("Link a second phone/tablet by giving it the same URL and token. Sales, payments, Utang and stock changes are merged; conflicts are logged for the owner, never discarded.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Button({ save() }, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) { Text("Save settings") }
        Card2("💾 Backup & restore") {
            Text("Last backup: $lastBackup. Data lives on this device — back up regularly to a place you control.", fontSize = 12.sp)
            Field("Backup passphrase (min 6 — don't lose it)", pass, { pass = it })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ exportL.launch("sarisari-backup-${Manila.fmt(System.currentTimeMillis(), "yyyyMMdd-HHmm")}.ssmb") }, enabled = pass.length >= 6) { Text("Export") }
                OutlinedButton({ restoreL.launch(arrayOf("*/*")) }, enabled = pass.length >= 6) { Text("Restore…") }
            }
        }
        Card2("👥 Staff") {
            Field("Name", name, { name = it }); Field("4-digit PIN", pin, { if (it.length <= 4) pin = it.filter(Char::isDigit) }, number = true, decimal = false)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Role.values().forEach { r -> FilterChip(role == r, { role = r }, label = { Text(r.name.lowercase()) }) } }
            Button({ vm.addStaff(name, role, pin); name = ""; pin = "" }, enabled = name.isNotBlank() && pin.length == 4) { Text("Add staff") }
        }
    }
}
