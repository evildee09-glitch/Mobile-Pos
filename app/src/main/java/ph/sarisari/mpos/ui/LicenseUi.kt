@file:OptIn(ExperimentalMaterial3Api::class)

package ph.sarisari.mpos.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ph.sarisari.mpos.BuildConfig
import ph.sarisari.mpos.domain.Feature
import ph.sarisari.mpos.domain.Plan

/** Shown when someone taps a Premium feature without a plan. Never shown for selling. */
@Composable
fun PaywallDialog(vm: PosViewModel, feature: Feature, onDismiss: () -> Unit) {
    val st by vm.license.collectAsState(); var code by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("🔒 Premium feature") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(feature.label, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(when (st.plan) { Plan.FREE -> "Your free trial has ended. Selling, receipts, stock and Utang keep working — this extra needs Premium."; else -> "This extra is part of Premium." })
            if (BuildConfig.PURCHASE_INFO.isNotBlank()) Text("To get Premium: ${BuildConfig.PURCHASE_INFO}", fontWeight = FontWeight.SemiBold)
            Field("Have a license code? Paste it here", code, { code = it })
        }
    }, confirmButton = { Button({ vm.activateLicense(code.trim()) }, enabled = code.length > 20) { Text("Activate") } }, dismissButton = { TextButton(onDismiss) { Text("Not now") } })
}

@Composable
fun PlanCard(vm: PosViewModel) {
    val st by vm.license.collectAsState(); var code by remember { mutableStateOf("") }
    ElevatedCard(Modifier.fillMaxWidth(), shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("⭐ Plan & license", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(st.title, fontWeight = FontWeight.SemiBold)
            st.expiresAt?.let { Text("License valid until ${ph.sarisari.mpos.util.Manila.fmt(it * 1000, "MMM d, yyyy")}", fontSize = 12.sp) }
            if (st.plan == Plan.FREE || st.plan == Plan.TRIAL || st.plan == Plan.GRACE) {
                if (BuildConfig.PURCHASE_INFO.isNotBlank()) Text("To buy or renew: ${BuildConfig.PURCHASE_INFO}", fontSize = 13.sp)
                Field("License code", code, { code = it })
                Button({ vm.activateLicense(code.trim()); code = "" }, enabled = code.length > 20) { Text("Activate") }
            }
            Text("Selling, receipts, basic stock/expiry/Utang, QR display and manual backup are always free. A lapsed license only turns the extras off after a 14-day grace period.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
