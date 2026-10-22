package com.tianxian.fruit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tianxian.fruit.data.*
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

private val locationGreen = Color(0xFF13865A)
private val locationGray = Color(0xFFA4AFB9)
private val locationOrange = Color(0xFF976300)
private val locationRed = Color(0xFFBE4B43)
private fun locationMoney(v: Double) = String.format(Locale.CHINA, "¥%.2f", v)
private fun locationPercent(now: Double, past: Double?): String =
    if (past == null || past <= 0.001) "—"
    else String.format(Locale.CHINA, "%+.1f%%", (now / past - 1) * 100)
private fun locationNum(v: Double): String = String.format(Locale.CHINA, "%.1f", v)

private data class LocationShownHour(
    val hour: Int, val count: Double, val amount: Double,
    val historicCount: Double?, val historicAmount: Double?
) {
    fun delta(amountMetric: Boolean): Double? =
        if (amountMetric) historicAmount?.let { amount - it }
        else historicCount?.let { count - it }
}

@Composable
private fun RowScope.LocationComparisonMetric(
    title: String,
    current: String,
    previous: String,
    difference: String
) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = MaterialTheme.typography.labelSmall, color = Color.Gray, maxLines = 1)
        Text(current, style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold, maxLines = 1)
        Text(previous, style = MaterialTheme.typography.labelSmall, maxLines = 2)
        Text(difference, style = MaterialTheme.typography.labelSmall,
            color = when {
                difference.startsWith("-") -> locationRed
                difference.startsWith("+") -> locationGreen
                else -> Color.Gray
            }, fontWeight = FontWeight.SemiBold)
    }
}

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
    businessDayOnly: Boolean,
    onBusinessDayOnlyChange: (Boolean) -> Unit,
    metric: CustomerDailyMetric,
    onMetricChange: (CustomerDailyMetric) -> Unit
) {
    val storeId = selectedStoreId.takeIf { it > 0 }
        ?: stores.firstOrNull { it.first > 0 }?.first ?: 0L
    val storeName = stores.firstOrNull { it.first == storeId }?.second ?: "暂无位置"
    val ownRows = remember(records, storeId, selectedPlatform) {
        records.filter { it.storeId == storeId && it.netAmount > 0.005 &&
            (selectedPlatform == CustomerPaymentPlatform.ALL ||
                it.platform == selectedPlatform.name) }
    }
    val storeSessions = remember(sessions, storeId) {
        sessions.filter { it.storeId == storeId }.distinctBy { it.date }
            .sortedByDescending { it.date }
    }
    val byDay = remember(ownRows) { ownRows.groupBy { it.businessDate } }
    // A selected non-operating date must stay selected; do not silently jump.
    val selectedDate = anchorDate
    val previousSession = storeSessions.firstOrNull { it.date < selectedDate }
    val nextSession = storeSessions.lastOrNull { it.date > selectedDate }
    var compareMode by remember(storeId) { mutableStateOf(LocationCompareMode.SMART) }
    var referenceDate by remember(storeId) {
        mutableStateOf(storeSessions.firstOrNull { it.date < selectedDate }?.date ?: selectedDate)
    }
    var currentStart by remember(storeId) {
        mutableStateOf(runCatching { LocalDate.parse(selectedDate).minusDays(7).toString() }
            .getOrDefault(selectedDate))
    }
    var currentEnd by remember(storeId) { mutableStateOf(selectedDate) }
    var referenceStart by remember(storeId) {
        mutableStateOf(runCatching { LocalDate.parse(selectedDate).minusDays(14).toString() }
            .getOrDefault(selectedDate))
    }
    var referenceEnd by remember(storeId) {
        mutableStateOf(runCatching { LocalDate.parse(selectedDate).minusDays(8).toString() }
            .getOrDefault(selectedDate))
    }
    var comparisonCount by remember(storeId) { mutableIntStateOf(4) }
    var editingCompareDate by remember { mutableStateOf<String?>(null) }
    val sharedToday = remember(sharedGroups, selectedDate, storeId, selectedPlatform) {
        sharedGroups.filter { group ->
            group.date == selectedDate && storeId in group.storeIds &&
                (selectedPlatform == CustomerPaymentPlatform.ALL ||
                    selectedPlatform.name == group.platform)
        }
    }
    val report = remember(ownRows, sessions, selectedDate, storeId, selectedPlatform) {
        CustomerLocationHistoryAnalyzer.analyze(
            ownRows, sessions, selectedDate, storeId, selectedPlatform.name
        )
    }
    val customReport = remember(ownRows, sessions, storeId, selectedDate,
        selectedPlatform, compareMode, referenceDate, currentStart, currentEnd,
        referenceStart, referenceEnd, comparisonCount) {
        if (compareMode == LocationCompareMode.SMART) null
        else CustomerLocationManualAnalyzer.analyze(ownRows, sessions, storeId,
            selectedPlatform.name, LocationManualRequest(compareMode, selectedDate,
                referenceDate, minOf(currentStart, currentEnd), maxOf(currentStart, currentEnd),
                minOf(referenceStart, referenceEnd), maxOf(referenceStart, referenceEnd),
                comparisonCount))
    }
    var hourlyAmount by remember { mutableStateOf(false) }
    var selectedHour by remember(storeId, selectedDate) { mutableIntStateOf(19) }
    var expandedHour by remember(storeId, selectedDate) { mutableStateOf(false) }

    // Trend bars use only the actually completed selling hours for each day.
    // Different opening hours are visible as observed figures but do NOT enter
    // the historical comparison without matching the reference hour window.
    val recentDays = storeSessions.filter { it.date <= selectedDate }.take(8).reversed()
    val recentObservations = remember(ownRows, recentDays) {
        recentDays.associate { day ->
            val hours = CustomerTrafficDeepDive.finishedHours(day, LocalDateTime.now()).toSet()
            val accepted = byDay[day.date].orEmpty().filter {
                it.tradeTime.drop(11).take(2).toIntOrNull() in hours
            }
            day.date to LocationHistoryDay(day.date, accepted.size,
                accepted.sumOf { it.netAmount }, accepted.map { it.platform }.toSet())
        }
    }
    fun trendMetric(day: LocationHistoryDay): Double = when (metric) {
        CustomerDailyMetric.PAYMENTS -> day.count.toDouble()
        CustomerDailyMetric.AMOUNT -> day.amount
        CustomerDailyMetric.AVERAGE -> day.average
    }
    fun metricText(value: Double, isAmount: Boolean): String =
        if (isAmount) locationMoney(value) else locationNum(value) + "笔"
    fun baselineText(value: Double?, isAmount: Boolean): String =
        value?.let { "历史" + metricText(it, isAmount) } ?: "历史不足"

    val chartHours: List<LocationShownHour> = if (customReport != null)
        customReport.hours.map { hour ->
            LocationShownHour(hour.hour, hour.currentCount, hour.currentAmount,
                hour.referenceCount.takeIf { customReport.comparable },
                hour.referenceAmount.takeIf { customReport.comparable })
        }
    else report?.hours.orEmpty().map { hour ->
        LocationShownHour(hour.hour, hour.count.toDouble(), hour.amount,
            hour.historicCount, hour.historicAmount)
    }
    val chartMax = chartHours.maxOfOrNull { item ->
        maxOf(if (hourlyAmount) item.amount else item.count.toDouble(),
            if (hourlyAmount) item.historicAmount ?: 0.0 else item.historicCount ?: 0.0)
    }?.coerceAtLeast(1.0) ?: 1.0
    val highlightedHour = chartHours.firstOrNull { it.hour == selectedHour }
        ?: chartHours.maxByOrNull { it.count }
    val detailDates = customReport?.currentDates?.toSet() ?: setOf(selectedDate)
    val detailRows = remember(ownRows, detailDates, highlightedHour?.hour, report, customReport) {
        if ((report == null && customReport == null) || highlightedHour == null) emptyList()
        else ownRows.filter {
            it.businessDate in detailDates &&
                it.tradeTime.drop(11).take(2).toIntOrNull() == highlightedHour.hour
        }.sortedByDescending { it.tradeTime }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("位置历史对比", fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("仅看营业日", style = MaterialTheme.typography.labelSmall)
                            Switch(checked = businessDayOnly, onCheckedChange = onBusinessDayOnlyChange,
                                modifier = Modifier.height(28.dp))
                        }
                    }
                    Text("$storeName · $selectedDate",
                        color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = compareMode == LocationCompareMode.SMART,
                            onClick = { compareMode = LocationCompareMode.SMART },
                            label = { Text("智能对比") })
                        FilterChip(selected = compareMode != LocationCompareMode.SMART,
                            onClick = { if (compareMode == LocationCompareMode.SMART)
                                compareMode = LocationCompareMode.DAY },
                            label = { Text("自定义对比") })
                    }
                    if (compareMode != LocationCompareMode.SMART) {
                        Row(Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            listOf(LocationCompareMode.DAY, LocationCompareMode.RANGE,
                                LocationCompareMode.SESSIONS).forEach { mode ->
                                FilterChip(selected = compareMode == mode,
                                    onClick = { compareMode = mode },
                                    label = { Text(mode.label) })
                            }
                        }
                        when (compareMode) {
                            LocationCompareMode.DAY -> {
                                Text("本期：$selectedDate（顶部日期可左右滑动）",
                                    style = MaterialTheme.typography.labelSmall)
                                TextButton(onClick = { editingCompareDate = "reference" }) {
                                    Text("对比日：$referenceDate ▾")
                                }
                            }
                            LocationCompareMode.RANGE -> {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement =
                                    Arrangement.spacedBy(2.dp)) {
                                    TextButton(onClick = { editingCompareDate = "currentStart" },
                                        modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(1.dp)) {
                                        Text("本期始 " + currentStart.drop(5),
                                            style = MaterialTheme.typography.labelSmall)
                                    }
                                    TextButton(onClick = { editingCompareDate = "currentEnd" },
                                        modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(1.dp)) {
                                        Text("止 " + currentEnd.drop(5),
                                            style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement =
                                    Arrangement.spacedBy(2.dp)) {
                                    TextButton(onClick = { editingCompareDate = "referenceStart" },
                                        modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(1.dp)) {
                                        Text("对比始 " + referenceStart.drop(5),
                                            style = MaterialTheme.typography.labelSmall)
                                    }
                                    TextButton(onClick = { editingCompareDate = "referenceEnd" },
                                        modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(1.dp)) {
                                        Text("止 " + referenceEnd.drop(5),
                                            style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                            LocationCompareMode.SESSIONS -> {
                                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                    listOf(4, 7, 10).forEach { count ->
                                        FilterChip(selected = comparisonCount == count,
                                            onClick = { comparisonCount = count },
                                            label = { Text("前后各${count}次") })
                                    }
                                }
                                Text("截至$selectedDate，最近营业次数与此前相同次数对比",
                                    style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                            }
                            else -> Unit
                        }
                        Text("两期仅比较相同完整营业小时；有缺失账单不推断为零。",
                            style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(enabled = previousSession != null,
                            onClick = { previousSession?.let { onDateChange(it.date) } }) {
                            Text("‹ 上次营业")
                        }
                        TextButton(enabled = nextSession != null,
                            onClick = { nextSession?.let { onDateChange(it.date) } }) {
                            Text("下次营业 ›")
                        }
                    }
                    if (sharedToday.isNotEmpty()) {
                        val totalPayments = sharedToday.sumOf { it.payments }
                        val totalAmount = sharedToday.sumOf { it.amount }
                        Text("本日存在多位置共享收款：${totalPayments}笔 / " +
                            locationMoney(totalAmount) +
                            "；已单独列示，不计入该位置的历史比较。",
                            color = locationOrange, style = MaterialTheme.typography.labelSmall)
                    }
                    if (customReport != null) {
                        customReport.warnings.forEach { note ->
                            Text(note, color = locationOrange,
                                style = MaterialTheme.typography.labelSmall)
                        }
                    } else if (report == null) {
                        Text(if (storeSessions.none { it.date == selectedDate })
                            "该位置当天没有营业记录，可在月历选有圆点的日期。"
                            else "没有完整的正式营业小时；暂无可靠的历史同期对比。",
                            color = locationOrange, style = MaterialTheme.typography.labelSmall)
                    } else {
                        report.warnings.filterNot { it.startsWith("近期/过去") }.forEach { note ->
                            Text(note, color = if (note.contains("缺") || note.contains("仅有") ||
                                    note.contains("没有")) locationOrange else Color.Gray,
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
        item {
            val current = report?.current
            val amountBaseline = report?.historicAmount
            val countBaseline = report?.historicCount
            val avgBaseline = report?.historicAveragePayment
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("本次 vs 历史同期", fontWeight = FontWeight.Bold)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        LocationComparisonMetric("电子收款",
                            current?.takeIf { it.observed }?.let { locationMoney(it.amount) } ?: "待补账单",
                            baselineText(amountBaseline, true),
                            current?.takeIf { it.observed }?.let { locationPercent(it.amount, amountBaseline) } ?: "—")
                        LocationComparisonMetric("付款笔数",
                            current?.takeIf { it.observed }?.let { "${it.count}笔" } ?: "—",
                            baselineText(countBaseline, false),
                            current?.takeIf { it.observed }?.let {
                                locationPercent(it.count.toDouble(), countBaseline)
                            } ?: "—")
                        LocationComparisonMetric("笔均收款",
                            current?.takeIf { it.observed }?.let { locationMoney(it.average) } ?: "—",
                            baselineText(avgBaseline, true),
                            current?.takeIf { it.observed }?.let {
                                locationPercent(it.average, avgBaseline)
                            } ?: "—")
                    }
                    Text("同口径：本次与历史均仅计${report?.comparableHours?.size ?: 0}个相同的完整小时；" +
                        "可比历史${report?.historicDays?.size ?: 0}天。",
                        style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                }
            }
        }
        item {
            val period = report?.period
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("近期4次 vs 此前4次", fontWeight = FontWeight.Bold)
                    if (period?.sufficient != true) {
                        Text("可比样本不足：近期${period?.recent?.size ?: 0}天 / 此前${period?.earlier?.size ?: 0}天。" +
                            "需同位置、同星期、同完整营业小时及相同渠道，各4天才计算变化。",
                            style = MaterialTheme.typography.bodySmall, color = locationOrange)
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            LocationComparisonMetric("日均收款",
                                locationMoney(period.recentDailyAmount!!),
                                "以前" + locationMoney(period.earlierDailyAmount!!),
                                locationPercent(period.recentDailyAmount!!, period.earlierDailyAmount))
                            LocationComparisonMetric("日均笔数",
                                locationNum(period.recentDailyCount!!) + "笔",
                                "以前" + locationNum(period.earlierDailyCount!!) + "笔",
                                locationPercent(period.recentDailyCount!!, period.earlierDailyCount))
                            LocationComparisonMetric("笔均收款",
                                locationMoney(period.recentAveragePayment!!),
                                "以前" + locationMoney(period.earlierAveragePayment!!),
                                locationPercent(period.recentAveragePayment!!, period.earlierAveragePayment))
                        }
                        Text("近期：${period.recent.last().date}～${period.recent.first().date} · " +
                            "此前：${period.earlier.last().date}～${period.earlier.first().date}",
                            color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("逐小时付款 · 并排双柱图", fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = !hourlyAmount, onClick = { hourlyAmount = false },
                            label = { Text("付款笔数") })
                        FilterChip(selected = hourlyAmount, onClick = { hourlyAmount = true },
                            label = { Text("收款金额") })
                    }
                    Text("绿色：本次 · 灰色：历史同小时均值 · 可点击小时看明细",
                        style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    if (chartHours.isEmpty())
                        Text("该营业日暂无已完成小时数据，不能画成零客流。",
                            color = locationOrange, style = MaterialTheme.typography.labelSmall)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        chartHours.forEach { h ->
                            val actual = if (hourlyAmount) h.amount else h.count.toDouble()
                            val previous = if (hourlyAmount) h.historicAmount else h.historicCount
                            Column(Modifier.width(54.dp).clickable {
                                selectedHour = h.hour
                                expandedHour = true
                            }, horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(if (hourlyAmount) String.format(Locale.CHINA, "%.0f", actual)
                                     else h.count.toString(),
                                    color = if (highlightedHour?.hour == h.hour) locationGreen else Color.DarkGray,
                                    style = MaterialTheme.typography.labelSmall)
                                Row(Modifier.height(96.dp), verticalAlignment = Alignment.Bottom,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Box(Modifier.width(18.dp)
                                        .height(if (actual == 0.0) 2.dp
                                            else (4f + (actual / chartMax * 84f).toFloat()).dp)
                                        .background(locationGreen,
                                            RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)))
                                    Box(Modifier.width(18.dp)
                                        .height(if (previous == null || previous <= 0.0) 2.dp
                                            else (4f + (previous / chartMax * 84f).toFloat()).dp)
                                        .background(locationGray,
                                            RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)))
                                }
                                Text("${h.hour}时", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    highlightedHour?.let { h ->
                        val actual = if (hourlyAmount) h.amount else h.count.toDouble()
                        val previous = if (hourlyAmount) h.historicAmount else h.historicCount
                        HorizontalDivider()
                        Text("${h.hour}:00–${h.hour}:59 · 本次${metricText(actual, hourlyAmount)} · " +
                            "历史${previous?.let { metricText(it, hourlyAmount) } ?: "样本不足"} · " +
                            locationPercent(actual, previous),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = { expandedHour = !expandedHour }) {
                            Text(if (expandedHour) "收起交易明细" else "查看该小时交易 ›")
                        }
                        if (expandedHour) {
                            detailRows.take(30).forEach { payment ->
                                Text("${payment.tradeTime.takeLast(8)} · " +
                                    "${if (payment.platform == "WECHAT") "微信" else "支付宝"} · " +
                                    locationMoney(payment.netAmount),
                                    style = MaterialTheme.typography.labelSmall)
                            }
                            if (detailRows.size > 30)
                                Text("仅显示前30笔，可在账单页查看全部记录。",
                                    style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        }
                    }
                }
            }
        }
        item {
            val declining = report?.biggestChanges(hourlyAmount, true).orEmpty()
            val growing = report?.biggestChanges(hourlyAmount, false).orEmpty()
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("小时变化排行", fontWeight = FontWeight.Bold)
                    if (report?.baselineSufficient != true)
                        Text("历史可比样本至少3天后，才统计小时升降排行。",
                            color = locationOrange, style = MaterialTheme.typography.labelSmall)
                    else if (declining.isEmpty() && growing.isEmpty())
                        Text("无达到分析门槛的明显小时变化。",
                            style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    else {
                        listOf("下降最多" to declining, "增长最多" to growing).forEach { (label, rows) ->
                            if (rows.isNotEmpty()) {
                                Text(label, style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (label == "下降最多") locationRed else locationGreen)
                                rows.forEach { row ->
                                    val actual = if (hourlyAmount) row.amount else row.count.toDouble()
                                    val historic = if (hourlyAmount) row.historicAmount else row.historicCount
                                    Row(Modifier.fillMaxWidth().clickable {
                                        selectedHour = row.hour
                                        expandedHour = true
                                    }, horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("${row.hour}:00–${row.hour}:59",
                                            style = MaterialTheme.typography.bodySmall)
                                        Text("${metricText(actual, hourlyAmount)} / 历史" +
                                            "${historic?.let { metricText(it, hourlyAmount) } ?: "—"}  " +
                                            locationPercent(actual, historic),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (label == "下降最多") locationRed else locationGreen)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("最近8个营业日 · 横向短柱", fontWeight = FontWeight.Bold)
                    Row(Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CustomerDailyMetric.entries.forEach { choice ->
                            FilterChip(selected = metric == choice,
                                onClick = { onMetricChange(choice) },
                                label = { Text(choice.label) })
                        }
                    }
                    val topValue = recentObservations.values.filter { it.observed }
                        .maxOfOrNull { trendMetric(it) }?.coerceAtLeast(1.0) ?: 1.0
                    recentDays.forEach { day ->
                        val observed = recentObservations[day.date] ?: return@forEach
                        val formatted = when (metric) {
                            CustomerDailyMetric.PAYMENTS -> "${observed.count}笔"
                            CustomerDailyMetric.AMOUNT -> locationMoney(observed.amount)
                            CustomerDailyMetric.AVERAGE -> locationMoney(observed.average)
                        }
                        Row(Modifier.fillMaxWidth().clickable { onDateChange(day.date) }
                            .padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text(day.date.drop(5), modifier = Modifier.width(42.dp),
                                style = MaterialTheme.typography.labelSmall)
                            Box(Modifier.weight(1f).height(11.dp)
                                .background(Color(0xFFF0F2F4), RoundedCornerShape(3.dp))) {
                                if (observed.observed)
                                    Box(Modifier.fillMaxHeight()
                                        .fillMaxWidth((trendMetric(observed)/topValue).toFloat()
                                            .coerceIn(0.02f,1f))
                                        .background(if(day.date == selectedDate) locationGreen
                                            else Color(0xFF79B59B), RoundedCornerShape(3.dp)))
                            }
                            Text(if (observed.observed) formatted else "待补账单",
                                modifier = Modifier.width(95.dp),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if(day.date == selectedDate) FontWeight.Bold
                                    else FontWeight.Normal,
                                color = if(day.date == selectedDate) locationGreen else Color.DarkGray)
                        }
                    }
                    Text("各日期短柱只展示实际已导入的完整营业小时，不做跨日时长校正；" +
                        "仅上方可比样本用于计算变化比例。点击日期可查看当天小时明细。",
                        style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                }
            }
        }
    }
}
