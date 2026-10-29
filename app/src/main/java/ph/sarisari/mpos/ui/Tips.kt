package ph.sarisari.mpos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import ph.sarisari.mpos.data.Perm

private data class Tip(val emoji: String, val title: String, val body: String, val needs: List<Perm> = emptyList())

private val ALL_TIPS = listOf(
    Tip("👋", "Welcome!", "This app keeps working with no internet. Every sale is saved on this device first. Cloud sync is optional and can be turned on later in Settings."),
    Tip("🌅", "1 · Open your shift", "Go to the Shift tab, count the cash in the drawer, and enter it as the opening float. You can't sell until a shift is open.", listOf(Perm.SELL)),
    Tip("🛒", "2 · Make a sale", "Tap a product to add it, or tap 📷 Scan and point the camera at the barcode. Use the cart bar at the bottom to review.\n\n• − / + change the quantity; tap the number to type 0.5 kg\n• Tap the unit chip to switch piece ↔ case", listOf(Perm.SELL)),
    Tip("💵", "3 · Take payment", "Tap Charge. Use the quick-cash buttons (₱20, ₱50, ₱100…) for fast change.\n\nPay with cash, GCash, Maya or ShopeePay — or split them. Pick a customer first to put part of the sale on Utang.\n\nImport your own GCash/Maya QR once in Settings → Payment QR codes; it then appears here for the customer to scan.\n\nE-wallet amounts are recorded as you type them: always check the real GCash/Maya app before handing over goods.", listOf(Perm.SELL)),
    Tip("📦", "4 · Stock & deliveries", "In Stock, tap + New or 📷 to add products. When a delivery arrives, tap Receive — this is what increases stock. Add the expiry date there; expired items are blocked at the till and listed under ⏳ Expiring.\n\nUnder More you can import products from a CSV, make purchase orders, and tick ☑ Select to change many prices or do a stock count at once.", listOf(Perm.MANAGE_CATALOG, Perm.STOCK_ADJUST)),
    Tip("📒", "5 · Utang (pa-utang)", "Add regular customers with their mobile number and credit limit. Tap a customer to collect a payment (partial payments are fine) or send a friendly reminder by SMS, Viber or WhatsApp.\n\nIf a sale goes over the credit limit, the owner's PIN is asked.", listOf(Perm.COLLECT_DEBT)),
    Tip("🔒", "6 · End of day", "In Shift, count the drawer and tap Close shift & Z-Read. The app shows if you are over or short.\n\nOnly the owner can void a sale or give discounts. Back up regularly: Settings → Backup & restore (keep your passphrase safe!).", listOf(Perm.CLOSE_SHIFT)),
    Tip("✅", "You're all set", "You can read these tips again anytime from More → Show tips. Happy selling! Salamat po!")
)

@Composable
fun TipsDialog(can: (Perm) -> Boolean, onFinish: () -> Unit) {
    val steps = remember { ALL_TIPS.filter { t -> t.needs.isEmpty() || t.needs.any(can) } }
    var i by remember { mutableIntStateOf(0) }
    val tip = steps[i]; val last = i == steps.lastIndex
    Dialog(onDismissRequest = onFinish) {
        Card(shape = RoundedCornerShape(28.dp), modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(tip.emoji, fontSize = 52.sp)
                Text(tip.title, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Column(Modifier.heightIn(min = 140.dp, max = 300.dp).padding(top = 10.dp).verticalScroll(rememberScrollState())) {
                    Text(tip.body, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(Modifier.padding(vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    steps.indices.forEach { n ->
                        Box(Modifier.size(if (n == i) 10.dp else 7.dp).background(if (n == i) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape))
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (!last) TextButton(onFinish) { Text("Skip") } else Spacer(Modifier.width(8.dp))
                    Spacer(Modifier.weight(1f))
                    if (i > 0) TextButton({ i-- }) { Text("Back") }
                    Spacer(Modifier.width(4.dp))
                    Button({ if (last) onFinish() else i++ }, shape = RoundedCornerShape(14.dp)) { Text(if (last) "Get started" else "Next") }
                }
            }
        }
    }
}
