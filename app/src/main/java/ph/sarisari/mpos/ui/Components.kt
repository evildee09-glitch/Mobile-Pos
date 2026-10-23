@file:OptIn(ExperimentalMaterial3Api::class)

package ph.sarisari.mpos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------- theme ----------
private val Light = lightColorScheme(
    primary = Color(0xFF0B7A5A), onPrimary = Color.White, primaryContainer = Color(0xFFCFF0E3), onPrimaryContainer = Color(0xFF00382A),
    secondary = Color(0xFF4A635A), secondaryContainer = Color(0xFFDCE9E2), tertiary = Color(0xFFE8A200), tertiaryContainer = Color(0xFFFFEFC7),
    background = Color(0xFFF6F8F6), surface = Color(0xFFFFFFFF), surfaceVariant = Color(0xFFE9EFEB), error = Color(0xFFBA1A1A)
)
private val Dark = darkColorScheme(
    primary = Color(0xFF6FD9B5), onPrimary = Color(0xFF00382A), primaryContainer = Color(0xFF00513E), onPrimaryContainer = Color(0xFFCFF0E3),
    secondaryContainer = Color(0xFF32493F), tertiary = Color(0xFFFFC857), tertiaryContainer = Color(0xFF5B4300),
    background = Color(0xFF101412), surface = Color(0xFF181D1A), surfaceVariant = Color(0xFF2A322E)
)

/** Cards use surfaceContainerLow / surfaceContainerHighest; making those translucent lets the background logo show through them. */
private fun ColorScheme.glass() = copy(
    surfaceContainerLow = surface.copy(alpha = 0.80f),
    surfaceContainerHighest = surfaceVariant.copy(alpha = 0.80f)
)

@Composable
fun PosTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = (if (isSystemInDarkTheme()) Dark else Light).glass(),
    shapes = Shapes(small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp)),
    content = content
)

// ---------- building blocks ----------
@Composable
fun ProductAvatar(name: String, size: Dp = 44.dp) {
    val hues = listOf(0xFF0B7A5A, 0xFF2A6FDB, 0xFFB3541E, 0xFF8E44AD, 0xFFC0392B, 0xFF16808D, 0xFF6C7A1B)
    Box(Modifier.size(size).clip(CircleShape).background(Color(hues[(name.hashCode() and 0x7fffffff) % hues.size])), contentAlignment = Alignment.Center) {
        Text(name.trim().take(1).uppercase().ifEmpty { "?" }, color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.42f).sp)
    }
}

@Composable
fun Pill(text: String, container: Color = MaterialTheme.colorScheme.secondaryContainer, content: Color = MaterialTheme.colorScheme.onSurface) =
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(50)) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 3.dp), fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }

@Composable fun SectionTitle(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier.padding(top = 12.dp, bottom = 4.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

@Composable
fun EmptyState(emoji: String, title: String, hint: String) = Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
    Text(emoji, fontSize = 44.sp); Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Text(hint, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
}

@Composable
fun StatCard(label: String, value: String, modifier: Modifier = Modifier, accent: Color = MaterialTheme.colorScheme.primaryContainer) =
    Card(modifier, colors = CardDefaults.cardColors(containerColor = accent)) {
        Column(Modifier.padding(14.dp)) { Text(label, fontSize = 12.sp); Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
    }

@Composable
fun BigButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) =
    Button(onClick, modifier.fillMaxWidth().height(56.dp), enabled = enabled, shape = RoundedCornerShape(16.dp)) { Text(text, fontSize = 17.sp, fontWeight = FontWeight.Bold) }

@Composable
fun Field(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, number: Boolean = false, decimal: Boolean = true) =
    OutlinedTextField(
        value, onChange, modifier = modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true, shape = RoundedCornerShape(14.dp),
        keyboardOptions = KeyboardOptions(keyboardType = if (number) (if (decimal) KeyboardType.Decimal else KeyboardType.Number) else KeyboardType.Text)
    )

@Composable
fun PinDialog(title: String, onDismiss: () -> Unit, onPin: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        OutlinedTextField(pin, { if (it.length <= 4 && it.all(Char::isDigit)) { pin = it; if (it.length == 4) { onPin(it); pin = "" } } },
            label = { Text("4-digit PIN") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
    }, confirmButton = {}, dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

@Composable
fun TextDialog(title: String, text: String, onDismiss: () -> Unit, extra: (@Composable () -> Unit)? = null) =
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp) } },
        confirmButton = { Row { extra?.invoke(); TextButton(onDismiss) { Text("Close") } } })

/** Generic "pick one product" dialog with search. */
@Composable
fun ProductPickerDialog(products: List<ph.sarisari.mpos.data.Product>, onPick: (ph.sarisari.mpos.data.Product) -> Unit, onDismiss: () -> Unit) {
    var q by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Choose a product") }, text = {
        Column {
            Field("Search", q, { q = it })
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(products.filter { q.isBlank() || it.name.contains(q, true) || it.barcode == q }.take(60), key = { it.id }) { p ->
                    ListItem(headlineContent = { Text(p.name) }, leadingContent = { ProductAvatar(p.name, 36.dp) },
                        modifier = Modifier.clickableNoRipple { onPick(p); onDismiss() })
                }
            }
        }
    }, confirmButton = {}, dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = this.then(Modifier.clickable(onClick = onClick))
