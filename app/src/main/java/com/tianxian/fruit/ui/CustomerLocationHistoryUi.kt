package com.tianxian.fruit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tianxian.fruit.data.*
import java.time.LocalDate
import java.util.Locale

private val currentGreen = Color(0xFF13865A)
private val pastGray = Color(0xFF9CA8B2)
private fun amount(v: Double) = String.format(Locale.CHINA, "¥%.2f", v)
private fun percent(now: Double, past: Double): String =
    if (past <= 0.001) "—" else String.format(Locale.CHINA, "%+.1f%%", (now / past - 1) * 100)

@Composable
internal fun CustomerLocationHistoryContent(
    records: List<CustomerPaymentRecord>,
    sessions: List<CustomerTrafficSession>,
    stores: List<Pair<Long, String>>,
    selectedStoreId: Long,
    onStoreChange: (Long) -> Unit,
    selectedPlatform: CustomerPaymentPlatform,
    onPlatformChange: (CustomerPaymentPlatform) -> Unit,
    anchorDate: String,
    onDateChange: (String) -> Unit,
    metric: CustomerDailyMetric,
    onMetricChange: (CustomerDailyMetric) -> Unit
) {
    var chosen by remember { mutableStateOf(0L) }
    val storeId = if (selectedStoreId > 0) selectedStoreId else chosen.takeIf { it > 0 }
        ?: stores.firstOrNull { it.first > 0 }?.first ?: 0L
    val storeName = stores.firstOrNull { it.first == storeId }?.second ?: "暂无位置"
    val eligible = remember(records, storeId, selectedPlatform) {
        records.filter { it.storeId == storeId && it.netAmount > 0.005 &&
            (selectedPlatform == CustomerPaymentPlatform.ALL ||
                it.platform == selectedPlatform.name) }
    }
    val days = remember(sessions, storeId) {
        sessions.filter { it.storeId == storeId }.distinctBy { it.date }
            .sortedByDescending { it.date }
    }
    val datesWithPayments = remember(eligible) { eligible.groupBy { it.businessDate } }
    val selectedDate = remember(anchorDate, days) {
        if (days.any { it.date == anchorDate }) anchorDate
        else days.firstOrNull()?.date ?: anchorDate
    }
    val selectedIndex = days.indexOfFirst { it.date == selectedDate }
    val compare = remember(eligible, sessions, selectedDate, storeId, selectedPlatform) {
        CustomerTrafficDeepDive.compare(eligible, sessions, selectedDate,
            selectedPlatform.name, storeId).firstOrNull()
    }
    val historicDays = compare?.historicDates.orEmpty()
    val currentRows = datesWithPayments[selectedDate].orEmpty()
    val historicRows = historicDays.flatMap { datesWithPayments[it].orEmpty() }
    val enough = compare?.sampleSufficient == true
    val todayCount = currentRows.size.toDouble()
    val todayAmount = currentRows.sumOf { it.netAmount }
    val historicalCount = if (enough) historicDays.map { datesWithPayments[it].orEmpty().size.toDouble() }.average() else null
    val historicalAmount = if (enough) historicDays.map { date ->
        datesWithPayments[date].orEmpty().sumOf { it.netAmount }
    }.average() else null
    var hourlyAmount by remember { mutableStateOf(false) }
    var selectedHour by remember(storeId, selectedDate) { mutableIntStateOf(19) }
    var showDetails by remember { mutableStateOf(false) }
    val hours = compare?.hourRows?.map { it.hour }.orEmpty()
    val rowsByHour = currentRows.groupBy { it.tradeTime.drop(11).take(2).toIntOrNull() }
    val historicalByDayHour = historicDays.associateWith { date ->
        datesWithPayments[date].orEmpty().groupBy { it.tradeTime.drop(11).take(2).toIntOrNull() }
    }
    fun now(hour: Int): Double = rowsByHour[hour].orEmpty().let { if (hourlyAmount) it.sumOf { r -> r.netAmount } else it.size.toDouble() }
    fun before(hour: Int): Double? = if (!enough) null else historicDays.map { date ->
        historicalByDayHour[date]?.get(hour).orEmpty().let { if (hourlyAmount) it.sumOf { r -> r.netAmount } else it.size.toDouble() }
    }.average()
    val max = hours.maxOfOrNull { maxOf(now(it), before(it) ?: 0.0) }?.coerceAtLeast(1.0) ?: 1.0

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("位置历史对比", fontWeight = FontWeight.Bold)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        stores.filter { it.first > 0 }.forEach { (id, name) ->
                            FilterChip(selected = id == storeId, onClick = {
                                chosen = id; onStoreChange(id)
                            }, label = { Text(name) })
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CustomerPaymentPlatform.entries.forEach { option ->
                            FilterChip(selected = selectedPlatform == option,
                                onClick = { onPlatformChange(option) }, label = { Text(option.label) })
                        }
                    }
                    Text("$storeName · ${selectedDate} · 同星期最近4个可比营业日",
                        style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(enabled = selectedIndex >= 0 && selectedIndex < days.lastIndex,
                            onClick = { onDateChange(days[selectedIndex + 1].date) }) { Text("‹ 上次营业") }
                        TextButton(enabled = selectedIndex > 0,
                            onClick = { onDateChange(days[selectedIndex - 1].date) }) { Text("下次营业 ›") }
                    }
                    if (!enough) Text("可比历史仅${historicDays.size}天（至少3天）；不显示未经验证的升降百分比。",
                        color = Color(0xFF986200), style = MaterialTheme.typography.labelSmall)
                    if (currentRows.isEmpty()) Text("本日未找到可归属账单；不代表实际零收款。",
                        color = Color(0xFF986200), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("本次 vs 历史同期", fontWeight = FontWeight.Bold)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        @Composable fun RowScope.Summary(label: String, value: String, baseline: String, change: String) {
                            Column(Modifier.weight(1f)) {
                                Text(label, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                Text(baseline, style = MaterialTheme.typography.labelSmall)
                                Text(change, style = MaterialTheme.typography.labelSmall,
                                    color = if (change.startsWith("-")) Color(0xFFC14D46) else currentGreen)
                            }
                        }
                        Summary("电子收款", amount(todayAmount),
                            historicalAmount?.let { "历史${amount(it)}" } ?: "历史不足",
                            historicalAmount?.let { percent(todayAmount,it) } ?: "—")
                        Summary("付款笔数", "${todayCount.toInt()}笔",
                            historicalCount?.let { "历史${String.format(Locale.CHINA,"%.1f",it)}" } ?: "历史不足",
                            historicalCount?.let { percent(todayCount,it) } ?: "—")
                        val avg = if (todayCount > 0) todayAmount/todayCount else 0.0
                        val pastAvg = if ((historicalCount ?: 0.0)>0) historicalAmount!!/historicalCount!! else null
                        Summary("笔均收款", amount(avg),
                            pastAvg?.let { "历史${amount(it)}" } ?: "历史不足",
                            pastAvg?.let { percent(avg,it) } ?: "—")
                    }
                    Text("仅计独立归属到本位置的电子收款；共享账号交易不分摊。付款笔数不等于独立顾客人数。",
                        style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("逐小时收款 · 双柱对照", fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = !hourlyAmount, onClick = { hourlyAmount = false }, label = { Text("笔数") })
                        FilterChip(selected = hourlyAmount, onClick = { hourlyAmount = true }, label = { Text("金额") })
                    }
                    Text("绿色本次 · 灰色历史同小时均值；每小时并排双柱，不使用山形面积图。",
                        style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    if (hours.isEmpty()) Text("没有可用的正式营业时段，暂不生成小时对比。")
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        hours.forEach { hour ->
                            val value = now(hour); val past = before(hour)
                            Column(Modifier.width(54.dp).clickable {
                                selectedHour = hour; showDetails = true
                            }, horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(if (hourlyAmount) String.format(Locale.CHINA,"%.0f",value) else value.toInt().toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (selectedHour == hour) currentGreen else Color.DarkGray)
                                Row(Modifier.height(100.dp), verticalAlignment = Alignment.Bottom,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Box(Modifier.width(18.dp).height((4 + 90*value/max).toFloat().dp)
                                        .background(currentGreen, RoundedCornerShape(topStart=3.dp,topEnd=3.dp)))
                                    Box(Modifier.width(18.dp).height((if(past==null) 2.0 else 4+90*past/max).toFloat().dp)
                                        .background(pastGray, RoundedCornerShape(topStart=3.dp,topEnd=3.dp)))
                                }
                                Text("${hour}时", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    HorizontalDivider()
                    val value = now(selectedHour)
                    val past = before(selectedHour)
                    Text("${selectedHour}:00–${selectedHour}:59 · 本次${if(hourlyAmount) amount(value) else "${value.toInt()}笔"}" +
                        " · 历史${past?.let { if(hourlyAmount) amount(it) else String.format(Locale.CHINA,"%.1f笔",it) } ?: "样本不足"}" +
                        " · ${past?.let { percent(value,it) } ?: "—"}",
                        style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { showDetails = !showDetails }) {
                        Text(if(showDetails) "收起该小时交易" else "查看该小时交易 ›")
                    }
                    if(showDetails) rowsByHour[selectedHour].orEmpty().take(30).forEach { row ->
                        Text("${row.tradeTime.takeLast(8)} · ${if(row.platform=="WECHAT") "微信" else "支付宝"} · ${amount(row.netAmount)}",
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("最近8个营业日 · 收款走势", fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        CustomerDailyMetric.entries.forEach { option ->
                            FilterChip(selected = metric == option, onClick = { onMetricChange(option) },
                                label = { Text(option.label) })
                        }
                    }
                    Text("按实际营业日排列；点击某日切换上方小时对比。",
                        style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    days.take(8).reversed().forEach { day ->
                        val payments = datesWithPayments[day.date].orEmpty()
                        val value = when(metric) {
                            CustomerDailyMetric.PAYMENTS -> "${payments.size}笔"
                            CustomerDailyMetric.AMOUNT -> amount(payments.sumOf { it.netAmount })
                            CustomerDailyMetric.AVERAGE -> amount(if(payments.isEmpty()) 0.0 else payments.sumOf { it.netAmount }/payments.size)
                        }
                        Row(Modifier.fillMaxWidth().clickable { onDateChange(day.date) }
                            .padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(day.date, style = MaterialTheme.typography.bodySmall)
                            Text(if(payments.isEmpty()) "待补账单" else value,
                                color = if(day.date==selectedDate) currentGreen else Color.DarkGray,
                                fontWeight = if(day.date==selectedDate) FontWeight.Bold else FontWeight.Normal)
                        }
                        HorizontalDivider(color = Color(0xFFEEEEEE))
                    }
                }
            }
        }
    }
}
