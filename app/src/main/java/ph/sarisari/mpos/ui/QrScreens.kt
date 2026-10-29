@file:OptIn(ExperimentalMaterial3Api::class)

package ph.sarisari.mpos.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ph.sarisari.mpos.data.PaymentMethod
import ph.sarisari.mpos.util.Money

fun walletLabel(m: PaymentMethod) = when (m) { PaymentMethod.GCASH -> "GCash"; PaymentMethod.MAYA -> "Maya"; PaymentMethod.SHOPEEPAY -> "ShopeePay"; else -> "E-wallet" }

data class WalletInfo(val number: String, val name: String)
/** 09171234567 -> "0917 123 4567" */
fun fmtPhone(n: String) = if (n.length == 11) "${n.take(4)} ${n.substring(4, 7)} ${n.substring(7)}" else n

/** Shown inside the payment sheet when a wallet is selected: the shop's QR and/or number for the customer. */
@Composable
fun QrBlock(vm: PosViewModel, method: PaymentMethod, amount: Long) {
    val ver by vm.qrVersion.collectAsState()
    val qr by produceState<ImageBitmap?>(null, method, ver) { value = vm.qrBitmap(method) }
    val info by produceState(WalletInfo("", ""), method, ver) { value = vm.walletInfo(method) }
    var big by remember { mutableStateOf(false) }
    val img = qr
    if (img == null && info.number.isBlank()) {
        Text("No ${walletLabel(method)} QR or number saved yet. Add them in More → Settings → Payment QR codes.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        Card(Modifier.fillMaxWidth().clickable { big = true }, shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (img != null) { Image(img, "${walletLabel(method)} QR", Modifier.size(110.dp).clip(RoundedCornerShape(10.dp)).background(Color.White), contentScale = ContentScale.Fit); Spacer(Modifier.width(12.dp)) }
                Column {
                    Text(if (img != null) "Customer scans this QR" else "Customer sends to", fontWeight = FontWeight.SemiBold)
                    if (info.number.isNotBlank()) Text(fmtPhone(info.number) + (if (info.name.isNotBlank()) " · ${info.name}" else ""), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text("and pays ${Money.format(amount)}", fontSize = 13.sp)
                    TextButton({ big = true }) { Text("Show full screen") }
                }
            }
        }
    }
    if (big) QrFullScreen(vm, img, info, method, amount) { big = false }
}

@Composable
fun QrFullScreen(vm: PosViewModel, img: ImageBitmap?, info: WalletInfo, method: PaymentMethod, amount: Long, onClose: () -> Unit) {
    val store by produceState("") { value = vm.setting("store_name") }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color.White, contentColor = Color.Black) {      // always white: best for scanning
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (store.isNotBlank()) Text(store, fontSize = 16.sp, color = Color.DarkGray)
                Text("Pay with ${walletLabel(method)}", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text(Money.format(amount), fontSize = 44.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF0B7A5A))
                if (img != null) Image(img, "${walletLabel(method)} QR", Modifier.padding(vertical = 12.dp).widthIn(max = 420.dp).fillMaxWidth(0.9f).aspectRatio(1f), contentScale = ContentScale.Fit)
                if (info.number.isNotBlank()) {
                    Text(if (img != null) "or send to" else "Send to", fontSize = 15.sp, color = Color.DarkGray, modifier = Modifier.padding(top = 8.dp))
                    Text(fmtPhone(info.number), fontSize = 34.sp, fontWeight = FontWeight.ExtraBold)
                    if (info.name.isNotBlank()) Text(info.name, fontSize = 18.sp)
                }
                Text(if (img != null) "Scan with ${walletLabel(method)}, then type ${Money.format(amount)} as the amount." else "Open ${walletLabel(method)} → Send Money, enter the number and ${Money.format(amount)}.", fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp))
                Text("Please show me the success screen. Salamat po!", fontSize = 13.sp, color = Color.DarkGray, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
                Button(onClose, Modifier.padding(top = 18.dp).fillMaxWidth(0.7f).height(52.dp), shape = RoundedCornerShape(16.dp)) { Text("Close") }
            }
        }
    }
}

/** Settings: the number (and the name shown in the wallet app) that customers should send money to. */
@Composable
fun WalletInfoFields(vm: PosViewModel, method: PaymentMethod) {
    val ver by vm.qrVersion.collectAsState()
    val saved by produceState(WalletInfo("", ""), method, ver) { value = vm.walletInfo(method) }
    var number by remember(saved) { mutableStateOf(saved.number) }; var name by remember(saved) { mutableStateOf(saved.name) }
    Column(Modifier.padding(start = 4.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Field("${walletLabel(method)} number (e.g. 0917 123 4567)", number, { number = it }, number = true, decimal = false)
        Field("Name shown in ${walletLabel(method)} (so customers can check)", name, { name = it })
        OutlinedButton({ vm.saveWalletInfo(method, number, name) }, enabled = number != saved.number || name != saved.name) { Text("Save ${walletLabel(method)} details") }
    }
}

/** One row in Settings: thumbnail + Import/Replace + Remove. */
@Composable
fun QrSettingsRow(vm: PosViewModel, method: PaymentMethod, onPick: () -> Unit) {
    val ver by vm.qrVersion.collectAsState()
    val qr by produceState<ImageBitmap?>(null, method, ver) { value = vm.qrBitmap(method) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            qr?.let { Image(it, null, Modifier.fillMaxSize().background(Color.White), contentScale = ContentScale.Fit) } ?: Text("QR", fontSize = 12.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) { Text(walletLabel(method), fontWeight = FontWeight.SemiBold); Text(if (qr != null) "Saved" else "Not set", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        FilledTonalButton(onPick) { Text(if (qr != null) "Replace" else "Import") }
        if (qr != null) TextButton({ vm.removeQr(method) }) { Text("Remove") }
    }
}
