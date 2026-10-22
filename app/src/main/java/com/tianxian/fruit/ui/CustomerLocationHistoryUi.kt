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
    sharedGroups: List<SharedCollectorTraffic>,
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
        LocationHistoryComparisonEngine.inspect(
            eligible, sessions, storeId, selectedDate, selectedPlatform.name
        )
    }
    val sharedThisDate = remember(sharedGroups, storeId, selectedDate, selectedPlatform) {
        sharedGroups.filter { group ->
            group.date == selectedDate && storeId in group.storeIds &&
                (selectedPlatform == CustomerPaymentPlatform.ALL ||
                    group.platform == selectedPlatform.name)
        }
    }
    val historicDays = compare?.historicDates.orEmpty()
    val currentRows = compare?.currentRecords.orEmpty()
    val enough = compare?.sufficient == true
    val todayCount = compare?.currentPayments?.toDouble() ?: 0.0
    val todayAmount = compare?.currentAmount ?: 0.0
    val historicalCount = compare?.baselinePayments
    val historicalAmount = compare?.baselineAmount
    val trend = compare?.recentTrend
    var hourlyAmount by remember { mutableStateOf(false) }
    var selectedHour by remember(storeId, selectedDate) {
        mutableIntStateOf(compare?.hourRows?.maxByOrNull { it.payments }?.hour ?: 19)
    }
    var showDetails by remember(storeId, selectedDate) { mutableStateOf(false) }
    val hours = compare?.hourRows?.map { it.hour }.orEmpty()
    val hourRows = compare?.hourRows?.associateBy { it.hour }.orEmpty()
    val rowsByHour = currentRows.groupBy { it.tradeTime.drop(11).take(2).toIntOrNull() }
    fun now(hour: Int): Double = hourRows[hour]?.let {
        if (hourlyAmount) it.amount else it.payments.toDouble()
    } ?: 0.0
    fun before(hour: Int): Double? = hourRows[hour]?.let {
        if (hourlyAmount) it.historicalAmount else it.historicalPayments
    }
    val max = hours.maxOfOrNull { maxOf(now(it), before(it) ?: 0.0) }
        ?.coerceAtLeast(1.0) ?: 1.0

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
                    Text(compare?.coverageNote ?: "该日期暂无有效营业时段，不能按零客流处理。",
                        color = Color(0xFF986200), style = MaterialTheme.typography.labelSmall)
                    if (sharedThisDate.isNotEmpty()) {
                        Text("另有${sharedThisDate.size}组跨位置共享收款，未分摊给该摊位；上方金额仅为独立归属记录。",
                            color = Color(0xFF986200), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("本次 vs 历史同期 · 相同已完成小时", fontWeight = FontWeight.Bold)
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
                    Text("总额和小时图均按同一组已完成营业小时统计；共享账号不分摊，付款笔数不等于顾客人数。",
                        style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("近期4次 vs 此前4次 · 同星期同小时", fontWeight = FontWeight.Bold)
                    if (trend == null) {
                        Text("尚不足8个有可比账单的营业日，暂不计算近期升降；不把缺失日当作0。",
                            color = Color(0xFF986200), style = MaterialTheme.typography.labelSmall)
                    } else {
                        Text("近期：\${trend.recentDates.last()}～\${trend.recentDates.first()} · 此前：\${trend.earlierDates.last()}～\${trend.earlierDates.first()}",
                            style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        @Composable fun TrendLine(label: String, latest: Double, earlier: Double, moneyFormat: Boolean) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(74.dp))
                                Text((if (moneyFormat) amount(latest) else String.format(Locale.CHINA, "%.1f笔",latest)) +
                                    " / " + (if (moneyFormat) amount(earlier) else String.format(Locale.CHINA,"%.1f笔",earlier)),
                                    style = MaterialTheme.typography.bodySmall)
                                Text(percent(latest, earlier), style = MaterialTheme.typography.labelSmall,
                                    color = if(latest < earlier) Color(0xFFB74E45) else currentGreen)
                            }
                        }
                        TrendLine("日均电子收款",trend.recentAmount,trend.earlierAmount,true)
                        TrendLine("日均付款笔数",trend.recentPayments,trend.earlierPayments,false)
                        val recentAvg = if (trend.recentPayments > 0.0) trend.recentAmount/trend.recentPayments else 0.0
                        val earlierAvg = if (trend.earlierPayments > 0.0) trend.earlierAmount/trend.earlierPayments else 0.0
                        TrendLine("笔均收款",recentAvg,earlierAvg,true)
                        Text("只比较位置、星期、已完成小时及观察到的支付渠道一致的营业日；账单完整性仍需核验。",
                            style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    }
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
                    Text("绿色本次 · 灰色历史同小时均值；按同一营业时段并排双柱。",
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
                    val declines = compare?.decliningHours.orEmpty()
                    val growth = compare?.growingHours.orEmpty()
                    if (declines.isNotEmpty() || growth.isNotEmpty()) {
                        Text("小时变化较明显", fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodySmall)
                        declines.forEach { h ->
                            Text("${h.hour}:00 付款下降 · 本次${h.payments}笔 / 历史${String.format(Locale.CHINA,"%.1f",h.historicalPayments ?: 0.0)}笔",
                                style = MaterialTheme.typography.labelSmall, color = Color(0xFFB74E45))
                        }
                        growth.forEach { h ->
                            Text("${h.hour}:00 付款增长 · 本次${h.payments}笔 / 历史${String.format(Locale.CHINA,"%.1f",h.historicalPayments ?: 0.0)}笔",
                                style = MaterialTheme.typography.labelSmall, color = currentGreen)
                        }
                    }
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
                    val display = compare?.operatingDays.orEmpty().reversed()
                    val barMax = display.maxOfOrNull { day ->
                        when (metric) {
                            CustomerDailyMetric.PAYMENTS -> day.payments.toDouble()
                            CustomerDailyMetric.AMOUNT -> day.amount
                            CustomerDailyMetric.AVERAGE -> if(day.payments>0) day.amount/day.payments else 0.0
                        }
                    }?.coerceAtLeast(1.0) ?: 1.0
                    display.forEach { day ->
                        val value = when (metric) {
                            CustomerDailyMetric.PAYMENTS -> day.payments.toDouble()
                            CustomerDailyMetric.AMOUNT -> day.amount
                            CustomerDailyMetric.AVERAGE -> if (day.payments>0) day.amount/day.payments else 0.0
                        }
                        val label = when (metric) {
                            CustomerDailyMetric.PAYMENTS -> "\${day.payments}笔"
                            else -> amount(value)
                        }
                        Row(Modifier.fillMaxWidth().clickable { onDateChange(day.date) }
                            .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(day.date.takeLast(5), style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.width(43.dp))
                            Box(Modifier.weight(1f).height(9.dp).background(Color(0xFFEDF1F2),
                                RoundedCornerShape(3.dp))) {
                                if (day.payments > 0) {
                                    Box(Modifier.fillMaxWidth((value/barMax).toFloat().coerceIn(0f,1f))
                                        .fillMaxHeight()
                                        .background(if(day.date==selectedDate) currentGreen else Color(0xFF7EBFA5),
                                            RoundedCornerShape(3.dp)))
                                }
                            }
                            Text(if(day.payments == 0) "待核验" else label,
                                modifier = Modifier.width(77.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = if(day.date==selectedDate) currentGreen else Color.DarkGray)
                        }
                    }
                }
            }
        }
    }
}
