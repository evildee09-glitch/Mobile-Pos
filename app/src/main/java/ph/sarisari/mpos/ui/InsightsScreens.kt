@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package ph.sarisari.mpos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ph.sarisari.mpos.domain.ReorderSuggestion
import ph.sarisari.mpos.util.Expiry
import ph.sarisari.mpos.util.Money
import ph.sarisari.mpos.util.Qty

@Composable
fun BarChart(values: List<Long>, firstLabel: String, lastLabel: String, color: Color) {
    val max = (values.maxOrNull() ?: 0L).coerceAtLeast(1)
    Row(Modifier.fillMaxWidth().height(110.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        values.forEach { v -> Box(Modifier.weight(1f).fillMaxHeight((v.toFloat() / max).coerceIn(0.02f, 1f)).background(color, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))) }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(firstLabel, fontSize = 10.sp); Text(lastLabel, fontSize = 10.sp) }
}

@Composable
fun InsightsScreen(vm: PosViewModel) {
    val days by vm.insightDays.collectAsState()
    val sales by vm.dailySales.collectAsState(); val profit by vm.dailyProfit.collectAsState()
    val top by vm.topProducts.collectAsState(); val slow by vm.slowMovers.collectAsState()
    val suggestions by produceState<List<ReorderSuggestion>>(emptyList(), days) { value = vm.smartSuggestions() }
    val today = Expiry.today()
    val salesMap = sales.associate { it.day to it.total }; val profitMap = profit.associate { it.day to it.profit }
    val range = (days - 1 downTo 0).map { today.minusDays(it.toLong()).toString() }
    val totalSales = sales.sumOf { it.total }; val totalProfit = profit.sumOf { it.profit }; val tx = sales.sumOf { it.count }
    val best = sales.maxByOrNull { it.total }
    Column(Modifier.padding(14.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(7, 30, 90).forEach { d -> FilterChip(days == d, { vm.insightDays.value = d }, label = { Text("Last $d days") }) } }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { StatCard("Sales", Money.format(totalSales), Modifier.weight(1f)); StatCard("Est. profit", Money.format(totalProfit), Modifier.weight(1f), MaterialTheme.colorScheme.tertiaryContainer) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard("Transactions", tx.toString(), Modifier.weight(1f), MaterialTheme.colorScheme.secondaryContainer)
            StatCard("Average / day", Money.format(totalSales / days), Modifier.weight(1f), MaterialTheme.colorScheme.secondaryContainer)
        }
        best?.let { Text("Best day: ${it.day} · ${Money.format(it.total)} (${it.count} sales)", fontSize = 13.sp) }
        SectionTitle("Sales per day")
        BarChart(range.map { salesMap[it] ?: 0L }, range.first().drop(5), "today", MaterialTheme.colorScheme.primary)
        SectionTitle("Estimated profit per day")
        BarChart(range.map { (profitMap[it] ?: 0L).coerceAtLeast(0) }, range.first().drop(5), "today", Color(0xFFE8A200))
        Text("Profit = selling price − cost you entered, before discounts. Products with no cost entered look 100% profit.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        SectionTitle("🏆 Best sellers")
        if (top.isEmpty()) Text("No sales in this period yet.", fontSize = 13.sp)
        top.forEachIndexed { i, t ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", Modifier.width(24.dp), fontWeight = FontWeight.Bold)
                Column(Modifier.weight(1f)) { Text(t.name, fontWeight = FontWeight.SemiBold, maxLines = 1); Text("${Qty.format(t.qtyMilli)} sold · profit ${Money.format(t.profit)}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text(Money.format(t.revenue), fontWeight = FontWeight.Bold)
            }
        }
        SectionTitle("🐌 Slow movers (nothing sold in $days days)")
        if (slow.isEmpty()) Text("None — everything on the shelf is selling.", fontSize = 13.sp)
        slow.forEach { p -> Row { Text(p.name, Modifier.weight(1f), maxLines = 1); Text("${Qty.format(p.stockMilli)} ${p.baseUnit} · ${Money.format(Qty.lineTotal(p.costCentavos, p.stockMilli))} tied up", fontSize = 12.sp) } }

        SectionTitle("⚡ Smart reorder (from the last 30 days of sales)")
        if (suggestions.isEmpty()) Text("Nothing will run out within 10 days at the current selling speed.", fontSize = 13.sp)
        suggestions.take(20).forEach { s ->
            Row { Column(Modifier.weight(1f)) { Text(s.product.name, fontWeight = FontWeight.SemiBold, maxLines = 1); Text("sells ~${Qty.format(Math.round(s.perDayMilli))}/day · lasts ${"%.1f".format(s.daysLeft)} days", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text("order ${Qty.format(s.suggestMilli)} ${s.product.baseUnit}", fontWeight = FontWeight.Bold) }
        }
        if (suggestions.isNotEmpty()) BigButton("Create purchase order draft", { vm.createSmartPo(suggestions) })
    }
}
