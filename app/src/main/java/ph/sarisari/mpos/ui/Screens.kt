@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package ph.sarisari.mpos.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import ph.sarisari.mpos.data.*
import ph.sarisari.mpos.domain.PaymentInput
import ph.sarisari.mpos.domain.Rbac
import ph.sarisari.mpos.util.Manila
import ph.sarisari.mpos.util.Money
import ph.sarisari.mpos.util.Qty

// ---------- root ----------
@Composable
fun PosRoot(vm: PosViewModel = viewModel()) {
    val user by vm.user.collectAsState(); val staff by vm.staff.collectAsState(); val toast by vm.toast.collectAsState()
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(toast) { toast?.let { snack.showSnackbar(it); vm.toast.value = null } }
    Scaffold(snackbarHost = { SnackbarHost(snack) }, containerColor = MaterialTheme.colorScheme.background) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            val list = staff
            when {
                list == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                list.isEmpty() -> SetupScreen(vm)
                user == null -> LoginScreen(vm, list)
                else -> MainScreen(vm, user!!)
            }
        }
    }
}

@Composable
fun SetupScreen(vm: PosViewModel) {
    var name by remember { mutableStateOf("") }; var pin by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(28.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("🏪", fontSize = 56.sp)
        Text("Welcome!", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Create the Owner account to begin. Works fully offline.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 20.dp))
        Column(Modifier.widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Field("Your name", name, { name = it })
            OutlinedTextField(pin, { if (it.length <= 4 && it.all(Char::isDigit)) pin = it }, Modifier.fillMaxWidth(), label = { Text("Choose a 4-digit PIN") }, singleLine = true,
                shape = RoundedCornerShape(14.dp), visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
            BigButton("Create account", { vm.setupOwner(name, pin) }, enabled = name.isNotBlank() && pin.length == 4)
        }
    }
}

@Composable
fun LoginScreen(vm: PosViewModel, staff: List<Staff>) {
    var picked by remember { mutableStateOf<Staff?>(null) }
    Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("🛒", fontSize = 48.sp, modifier = Modifier.padding(top = 24.dp))
        Text("Who's working today?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Sino ang naka-duty?", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 16.dp))
        Column(Modifier.widthIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            staff.forEach { s ->
                ElevatedCard(Modifier.fillMaxWidth().clickable { picked = s }, shape = RoundedCornerShape(18.dp)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        ProductAvatar(s.name, 48.dp); Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) { Text(s.name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold); Text(s.role.name.lowercase().replaceFirstChar { it.uppercase() }, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Icon(Icons.Filled.Lock, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    picked?.let { s -> PinDialog("PIN for ${s.name}", { picked = null }) { pin -> vm.login(s, pin); picked = null } }
}

// ---------- shell ----------
private data class Dest(val key: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@Composable
fun MainScreen(vm: PosViewModel, user: Staff) {
    val can = { p: Perm -> Rbac.can(user.role, p) }
    val dests = buildList {
        if (can(Perm.SELL)) add(Dest("sell", "Sell", Icons.Filled.ShoppingCart))
        if (can(Perm.MANAGE_CATALOG) || can(Perm.STOCK_ADJUST)) add(Dest("stock", "Stock", Icons.Filled.Home))
        if (can(Perm.COLLECT_DEBT)) add(Dest("utang", "Utang", Icons.Filled.Person))
        if (can(Perm.CLOSE_SHIFT)) add(Dest("shift", "Shift", Icons.Filled.DateRange))
        add(Dest("more", "More", Icons.Filled.Menu))
    }
    var sel by rememberSaveable { mutableIntStateOf(0) }
    val cur = dests[sel.coerceIn(0, dests.size - 1)]
    val receipt by vm.lastReceipt.collectAsState(); val approval by vm.approvalNeeded.collectAsState()
    Column(Modifier.fillMaxSize()) {
        TopBar(vm, user)
        Box(Modifier.weight(1f)) {
            when (cur.key) {
                "sell" -> CheckoutScreen(vm)
                "stock" -> StockScreen(vm)
                "utang" -> CustomersScreen(vm)
                "shift" -> ShiftScreen(vm)
                "more" -> MoreScreen(vm, user)
            }
        }
        NavigationBar {
            dests.forEachIndexed { i, d -> NavigationBarItem(selected = dests.indexOf(cur) == i, onClick = { sel = i }, icon = { Icon(d.icon, d.label) }, label = { Text(d.label) }) }
        }
    }
    receipt?.let { r -> TextDialog("Receipt", r, { vm.lastReceipt.value = null }) { TextButton({ vm.printReceipt(r, drawer = true) }) { Text("Print") } } }
    approval?.let { PinDialog("$it\nOwner PIN:", { vm.approvalNeeded.value = null }) { pin -> vm.approve(pin) } }
}

@Composable
fun TopBar(vm: PosViewModel, user: Staff) {
    val pending by vm.pendingSync.collectAsState(); val last by vm.lastSync.collectAsState(); val err by vm.syncError.collectAsState(); val shift by vm.shift.collectAsState()
    var menu by remember { mutableStateOf(false) }
    val (dot, text) = when {
        !err.isNullOrBlank() -> Color(0xFFD93025) to "Sync problem · sales safe on device"
        pending > 0 -> Color(0xFFE8A200) to "$pending waiting to sync"
        last != null -> Color(0xFF1E9E5A) to "Synced ${Manila.fmt(last!!.toLong(), "MMM d HH:mm")}"
        else -> Color(0xFF1E9E5A) to "Offline-ready · saved on this device"
    }
    Surface(color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (shift != null) "Shift open" else "No shift open", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(Modifier.size(9.dp), shape = androidx.compose.foundation.shape.CircleShape, color = dot) {}
                    Spacer(Modifier.width(6.dp)); Text(text, fontSize = 12.sp)
                }
            }
            Box {
                Row(Modifier.clickable { menu = true }, verticalAlignment = Alignment.CenterVertically) { Text(user.name, Modifier.padding(end = 8.dp)); ProductAvatar(user.name, 34.dp) }
                DropdownMenu(menu, { menu = false }) { DropdownMenuItem(text = { Text("Log out") }, onClick = { menu = false; vm.logout() }) }
            }
        }
    }
}

// ---------- checkout ----------
@Composable
fun CheckoutScreen(vm: PosViewModel) {
    val wide = LocalConfiguration.current.screenWidthDp >= 720
    val cart by vm.cart.collectAsState()
    var showCart by remember { mutableStateOf(false) }; var showScan by remember { mutableStateOf(false) }
    val total = (cart.sumOf { it.total } - vm.discount.collectAsState().value).coerceAtLeast(0)
    if (wide) Row(Modifier.fillMaxSize()) {
        CatalogPane(vm, { showScan = true }, Modifier.weight(1.3f).fillMaxHeight())
        Surface(Modifier.weight(1f).fillMaxHeight(), tonalElevation = 2.dp) { CartPane(vm, Modifier.fillMaxSize()) }
    } else Box(Modifier.fillMaxSize()) {
        CatalogPane(vm, { showScan = true }, Modifier.fillMaxSize().padding(bottom = if (cart.isEmpty()) 0.dp else 76.dp))
        if (cart.isNotEmpty()) Card(Modifier.align(Alignment.BottomCenter).padding(12.dp).fillMaxWidth().clickable { showCart = true }, shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary)) {
            Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.ShoppingCart, null, tint = Color.White); Spacer(Modifier.width(10.dp))
                Text("${cart.size} item${if (cart.size > 1) "s" else ""}", Modifier.weight(1f), color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(Money.format(total), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
    if (showCart && !wide) ModalBottomSheet(onDismissRequest = { showCart = false }) { CartPane(vm, Modifier.fillMaxWidth().heightIn(max = 640.dp)) }
    if (showScan) ScannerDialog("Scan items", { code ->
        vm.scanBarcode(code)?.let { "✓ ${it.name} added · ${Money.format(it.priceCentavos)}" } ?: "Unknown barcode $code"
    }, { showScan = false }) {
        val c by vm.cart.collectAsState()
        Text("Cart: ${c.size} items · ${Money.format(c.sumOf { it.total })}", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun CatalogPane(vm: PosViewModel, onScan: () -> Unit, modifier: Modifier) {
    val products by vm.allProducts.collectAsState(); val cats by vm.categories.collectAsState()
    var q by remember { mutableStateOf("") }; var cat by remember { mutableStateOf("★") }
    val shown = remember(products, q, cat) {
        val base = products.filter { it.active }
        if (q.isNotBlank()) base.filter { it.name.contains(q.trim(), true) || it.barcode == q.trim() || it.sku.equals(q.trim(), true) }
        else when (cat) { "★" -> base.filter { it.favorite }.ifEmpty { base }; "All" -> base; else -> base.filter { it.category == cat } }
    }
    Column(modifier.padding(horizontal = 12.dp)) {
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(q, { q = it }, Modifier.weight(1f), placeholder = { Text("Search or scan barcode") }, singleLine = true, shape = RoundedCornerShape(28.dp),
                leadingIcon = { Icon(Icons.Filled.Search, null) }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                trailingIcon = { if (q.isNotEmpty()) IconButton({ q = "" }) { Icon(Icons.Filled.Close, "Clear") } },
                keyboardActions = KeyboardActions(onSearch = { vm.query.value = q; vm.submitSearch(); q = "" }, onDone = { vm.query.value = q; vm.submitSearch(); q = "" }))
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(onScan, Modifier.height(56.dp), shape = RoundedCornerShape(18.dp)) { Text("📷 Scan") }
        }
        LazyRow(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOf("★", "All") + cats) { c -> FilterChip(cat == c && q.isBlank(), { cat = c; q = "" }, label = { Text(if (c == "★") "★ Favorites" else c) }) }
        }
        if (shown.isEmpty()) EmptyState("🔎", "No products here", "Add products in the Stock tab, or import a CSV from More.")
        LazyVerticalGrid(GridCells.Adaptive(150.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
            items(shown, key = { it.id }) { p ->
                val out = p.stockMilli <= 0
                Card(onClick = { if (out) vm.toast.value = "${p.name} is out of stock" else vm.addProduct(p) }, Modifier.alpha(if (out) 0.5f else 1f), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ProductAvatar(p.name, 38.dp); Spacer(Modifier.weight(1f))
                            if (out) Pill("Out", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
                            else if (p.reorderMilli > 0 && p.stockMilli <= p.reorderMilli) Pill("Low", MaterialTheme.colorScheme.tertiaryContainer)
                        }
                        Text(p.name, Modifier.padding(top = 8.dp), fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, minLines = 2)
                        Text(Money.format(p.priceCentavos), fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
                        Text("${Qty.format(p.stockMilli)} ${p.baseUnit} left", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
fun CartPane(vm: PosViewModel, modifier: Modifier) {
    val cart by vm.cart.collectAsState(); val discount by vm.discount.collectAsState()
    val customers by vm.customers.collectAsState(); val balances by vm.balances.collectAsState(); val custId by vm.customerId.collectAsState()
    val subtotal = cart.sumOf { it.total }; val total = (subtotal - discount).coerceAtLeast(0)
    var showPay by remember { mutableStateOf(false) }; var custMenu by remember { mutableStateOf(false) }; var discDialog by remember { mutableStateOf(false) }
    var editQty by remember { mutableStateOf<Int?>(null) }
    Column(modifier.padding(12.dp)) {
        Text("Current sale", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (cart.isEmpty()) EmptyState("🧺", "Cart is empty", "Tap a product or scan a barcode")
        LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(cart, key = { _, l -> l.product.id + l.unitName }) { i, l ->
                val step = if (l.product.allowsFraction) 500L else 1000L
                Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        ProductAvatar(l.product.name, 36.dp); Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(l.product.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            AssistChip({ vm.cycleUnit(i) }, label = { Text("${l.unitName} · ${Money.format(l.unitPrice)}", fontSize = 11.sp) })
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(Money.format(l.total), fontWeight = FontWeight.Bold)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                FilledTonalIconButton({ val n = l.qtyMilli - step; if (n <= 0) vm.removeLine(i) else vm.setQty(i, n) }, Modifier.size(34.dp)) { Text("−", fontSize = 18.sp) }
                                Text(Qty.format(l.qtyMilli), Modifier.clickable { editQty = i }.padding(horizontal = 10.dp), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                FilledTonalIconButton({ vm.setQty(i, l.qtyMilli + step) }, Modifier.size(34.dp)) { Text("+", fontSize = 18.sp) }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Box {
            OutlinedButton({ custMenu = true }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                val c = customers.firstOrNull { it.id == custId }
                Icon(Icons.Filled.Person, null); Spacer(Modifier.width(8.dp)); Text(c?.let { "${it.name} · owes ${Money.format(balances[it.id] ?: 0)}" } ?: "Customer (needed for Utang)", maxLines = 1)
            }
            DropdownMenu(custMenu, { custMenu = false }) {
                DropdownMenuItem(text = { Text("— none —") }, onClick = { vm.customerId.value = null; custMenu = false })
                customers.forEach { c -> DropdownMenuItem(text = { Text("${c.name}  ·  ${Money.format(balances[c.id] ?: 0)}") }, onClick = { vm.customerId.value = c.id; custMenu = false }) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Subtotal ${Money.format(subtotal)}", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton({ discDialog = true }, enabled = cart.isNotEmpty()) { Text(if (discount > 0) "Discount −${Money.format(discount)}" else "+ Discount") }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("TOTAL", Modifier.weight(1f), fontSize = 20.sp); Text(Money.format(total), fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
        }
        BigButton("Charge ${Money.format(total)}", { showPay = true }, enabled = cart.isNotEmpty() && total > 0)
        if (cart.isNotEmpty()) TextButton({ vm.clearCart() }, Modifier.align(Alignment.CenterHorizontally)) { Text("Clear cart") }
    }
    if (showPay) PaymentSheet(vm, total, custId, { showPay = false })
    editQty?.let { i -> cart.getOrNull(i)?.let { l ->
        var t by remember { mutableStateOf(Qty.format(l.qtyMilli)) }
        AlertDialog(onDismissRequest = { editQty = null }, title = { Text("Quantity · ${l.product.name}") }, text = { Field("Quantity (${l.unitName})", t, { t = it }, number = true) },
            confirmButton = { Button({ Qty.parse(t)?.takeIf { it > 0 }?.let { vm.setQty(i, it) }; editQty = null }) { Text("OK") } }, dismissButton = { TextButton({ editQty = null }) { Text("Cancel") } })
    } }
    if (discDialog) {
        var t by remember { mutableStateOf(if (discount > 0) Money.format(discount).removePrefix("₱") else "") }
        AlertDialog(onDismissRequest = { discDialog = false }, title = { Text("Discount") }, text = { Field("Amount ₱ (owner approval may be asked)", t, { t = it }, number = true) },
            confirmButton = { Button({ vm.discount.value = (Money.parse(t) ?: 0).coerceIn(0, subtotal); discDialog = false }) { Text("Apply") } }, dismissButton = { TextButton({ vm.discount.value = 0; discDialog = false }) { Text("Remove") } })
    }
}

@Composable
fun PaymentSheet(vm: PosViewModel, total: Long, customerId: String?, onDismiss: () -> Unit) {
    var useCash by remember { mutableStateOf(true) }; var useG by remember { mutableStateOf(false) }; var useM by remember { mutableStateOf(false) }
    var useS by remember { mutableStateOf(false) }; var useU by remember { mutableStateOf(false) }
    var cash by remember { mutableStateOf("") }; var gcash by remember { mutableStateOf("") }; var maya by remember { mutableStateOf("") }
    var shopee by remember { mutableStateOf("") }; var utang by remember { mutableStateOf("") }; var ref by remember { mutableStateOf("") }
    fun v(s: String) = Money.parse(s) ?: 0L
    val cashV = if (useCash) v(cash) else 0; val g = if (useG) v(gcash) else 0; val m = if (useM) v(maya) else 0; val s = if (useS) v(shopee) else 0; val u = if (useU) v(utang) else 0
    val due = total - g - m - s - u
    val valid = g + m + s + u <= total && cashV >= due && (u == 0L || customerId != null) && (cashV > 0 || due <= 0)
    fun pesos(c: Long) = Money.format(c).removePrefix("₱").replace(",", "")
    fun remainingExcept(cur: Long) = (total - (g + m + s + u + cashV) + cur).coerceAtLeast(0)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Amount to pay", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(Money.format(total), fontSize = 40.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(useCash, { useCash = !useCash; if (useCash && cash.isBlank()) cash = pesos(remainingExcept(0)) }, label = { Text("💵 Cash") })
                FilterChip(useG, { useG = !useG; if (useG && gcash.isBlank()) gcash = pesos(remainingExcept(0)) }, label = { Text("GCash") })
                FilterChip(useM, { useM = !useM; if (useM && maya.isBlank()) maya = pesos(remainingExcept(0)) }, label = { Text("Maya") })
                FilterChip(useS, { useS = !useS; if (useS && shopee.isBlank()) shopee = pesos(remainingExcept(0)) }, label = { Text("ShopeePay") })
                FilterChip(useU, { useU = !useU; if (useU && utang.isBlank()) utang = pesos(remainingExcept(0)) }, label = { Text("📒 Utang") })
            }
            if (useCash) {
                Field("Cash received ₱", cash, { cash = it }, number = true)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip({ cash = pesos(due.coerceAtLeast(0)) }, label = { Text("Exact") })
                    listOf(20L, 50L, 100L, 200L, 500L, 1000L).forEach { a -> AssistChip({ cash = pesos(a * 100) }, label = { Text("₱$a") }) }
                }
            }
            if (useG) Field("GCash ₱", gcash, { gcash = it }, number = true)
            if (useM) Field("Maya ₱", maya, { maya = it }, number = true)
            if (useS) Field("ShopeePay ₱", shopee, { shopee = it }, number = true)
            if (useG || useM || useS) { Field("E-wallet reference # (optional)", ref, { ref = it }); Text("E-wallet amounts are recorded as typed; they are not verified with GCash/Maya.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (useU) { Field("Utang ₱", utang, { utang = it }, number = true); if (customerId == null) Text("Pick a customer in the cart first.", color = MaterialTheme.colorScheme.error) }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.fillMaxWidth().padding(14.dp)) {
                    Column(Modifier.weight(1f)) { Text("Cash due"); Text(Money.format(due.coerceAtLeast(0)), fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                    Column(horizontalAlignment = Alignment.End) { Text("Change"); Text(Money.format((cashV - due).coerceAtLeast(0)), fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                }
            }
            BigButton("Complete sale", {
                val list = buildList {
                    if (cashV > 0) add(PaymentInput(PaymentMethod.CASH, cashV))
                    if (g > 0) add(PaymentInput(PaymentMethod.GCASH, g, ref.ifBlank { null })); if (m > 0) add(PaymentInput(PaymentMethod.MAYA, m, ref.ifBlank { null }))
                    if (s > 0) add(PaymentInput(PaymentMethod.SHOPEEPAY, s, ref.ifBlank { null }))
                }
                vm.checkout(list, u); onDismiss()
            }, enabled = valid)
        }
    }
}
