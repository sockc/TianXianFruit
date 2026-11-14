package com.tianxian.fruit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
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
import com.tianxian.fruit.sync.WeatherClient
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val impactGreen = Color(0xFF13865A)
private val impactOrange = Color(0xFF9D6719)

private fun impactRate(value: Double?): String =
    value?.let { String.format(Locale.CHINA, "%.1f", it) } ?: "—"
private fun impactMoney(value: Double): String =
    String.format(Locale.CHINA, "¥%.0f", value)
private fun impactPercent(value: Double?): String =
    value?.let { String.format(Locale.CHINA, "%+.1f%%", it * 100) } ?: "—"

@Composable
private fun RowScope.ImpactPeriodCell(title: String, part: RainImpactPeriod) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        Text(if (part.observedHours == 0) "—" else
            impactRate(part.paymentCount.toDouble() / part.observedHours) + "笔/时",
            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(if (part.observedHours == 0) "无实况小时" else
            "${part.observedHours}小时 · ${impactMoney(part.electronicRevenue)}",
            style = MaterialTheme.typography.labelSmall, color = Color.Gray)
    }
}

/** Collapsed by default; no historical-weather SQLite read until opened. */
@Composable
internal fun CustomerWeatherImpactSection(
    db: AppDatabase,
    dataVersion: Int,
    storeId: Long,
    storeName: String,
    anchorDate: String,
    records: List<CustomerPaymentRecord>,
    sessions: List<CustomerTrafficSession>,
    sharedGroups: List<SharedCollectorTraffic>,
    selectedPlatform: CustomerPaymentPlatform
) {
    var expanded by remember(storeId) { mutableStateOf(false) }
    var periodMode by remember(storeId) { mutableStateOf("DAY") }
    val selectedDate = remember(anchorDate) {
        runCatching { LocalDate.parse(anchorDate) }.getOrDefault(LocalDate.now())
    }
    var chosenDay by remember(storeId, selectedDate) { mutableStateOf(selectedDate) }
    var customStart by remember(storeId) { mutableStateOf(selectedDate.minusDays(6)) }
    var customEnd by remember(storeId) { mutableStateOf(selectedDate) }
    var calendarOpen by remember(storeId) { mutableStateOf(false) }
    val today = LocalDate.now()
    val requestedStart = when (periodMode) {
        "WEEK" -> today.minusDays(6)
        "MONTH" -> today.minusDays(29)
        "CUSTOM" -> minOf(customStart, customEnd)
        else -> chosenDay
    }
    val requestedEnd = when (periodMode) {
        "WEEK", "MONTH" -> today
        "CUSTOM" -> maxOf(customStart, customEnd)
        else -> chosenDay
    }
    // Bound the number of SQLite archive reads in an unusual multi-year range.
    val effectiveEnd = requestedEnd
    val effectiveStart = maxOf(requestedStart, effectiveEnd.minusDays(89))
    val truncated = requestedStart < effectiveStart
    val sourceDays = remember(sessions, storeId) {
        sessions.filter { it.storeId == storeId }.distinctBy { it.date }
    }
    val shareCount = remember(sharedGroups, storeId, effectiveStart, effectiveEnd, selectedPlatform) {
        sharedGroups.filter { group ->
            storeId in group.storeIds &&
                group.date >= effectiveStart.toString() && group.date <= effectiveEnd.toString() &&
                (selectedPlatform == CustomerPaymentPlatform.ALL ||
                    selectedPlatform.name == group.platform)
        }.flatMap { it.receiptIds }.toSet().size
    }

    val result by produceState<CustomerWeatherImpactSummary?>(
        initialValue = null, expanded, db, dataVersion, storeId,
        effectiveStart, effectiveEnd, selectedPlatform, records, sessions, shareCount
    ) {
        value = null
        if (!expanded || storeId <= 0L) return@produceState
        value = withContext(Dispatchers.IO) {
            val selected = sourceDays.filter { it.date >= effectiveStart.toString() &&
                it.date <= effectiveEnd.toString() }
            val weekDays = selected.mapNotNull {
                runCatching { LocalDate.parse(it.date).dayOfWeek }.getOrNull()
            }.toSet()
            val referenceStart = effectiveStart.minusDays(84).toString()
            val reference = sourceDays.filter { it.date < effectiveStart.toString() &&
                it.date >= referenceStart && runCatching {
                    LocalDate.parse(it.date).dayOfWeek in weekDays
                }.getOrDefault(false)
            }
            val inputs = (selected + reference).distinctBy { it.date }.associate { session ->
                val history = db.getBusinessWeatherHistory(session.date, storeId)
                val hours = history?.let {
                    runCatching {
                        WeatherClient.parseOverview(it.payloadJson, historical = true).hourly
                    }.getOrNull()
                }.orEmpty()
                session.date to CustomerWeatherImpactDay(session, hours, history != null)
            }
            CustomerWeatherImpactAnalyzer.analyze(
                selected = selected.mapNotNull { inputs[it.date] },
                historical = reference.mapNotNull { inputs[it.date] },
                receipts = records,
                platform = selectedPlatform.name,
                sharedPaymentCount = shareCount,
                now = LocalDateTime.now()
            )
        }
    }

    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("天气影响分析", fontWeight = FontWeight.SemiBold)
                    Text("$storeName · 仅历史实际天气与电子收款",
                        style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                }
                Text(if (expanded) "收起 ⌃" else "天气影响 ›",
                    color = impactGreen, style = MaterialTheme.typography.labelSmall)
            }
            if (expanded) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("DAY" to "单日", "WEEK" to "近7天", "MONTH" to "近30天",
                        "CUSTOM" to "自定义").forEach { (key, label) ->
                        FilterChip(selected = periodMode == key,
                            onClick = {
                                if (key == "CUSTOM") calendarOpen = true
                                else periodMode = key
                            },
                            label = { Text(label) })
                    }
                }
                TextButton(onClick = { calendarOpen = true },
                    contentPadding = PaddingValues(0.dp)) {
                    Text("${effectiveStart} — ${effectiveEnd} ▾",
                        style = MaterialTheme.typography.bodySmall)
                }
                if (truncated) Text("范围超过90天：为避免卡顿，只分析结束日期前最近90天。",
                    color = impactOrange, style = MaterialTheme.typography.labelSmall)
                Text("渠道：${selectedPlatform.label}（与顶部筛选同步） · 同位置、同星期、同小时比较",
                    style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                val summary = result
                if (summary == null) {
                    Box(Modifier.fillMaxWidth().height(54.dp),
                        contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                } else {
                    Text("正式营业${summary.sessionCount}天 · 实况归档${summary.archiveCount}天 · " +
                        "覆盖${summary.observedHours}/${summary.expectedHours}小时",
                        style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    HorizontalDivider()
                    Text("雨前 / 雨中 / 雨后", fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodySmall)
                    Text("仅汇总有实况的营业小时；无数据显示“—”。",
                        style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ImpactPeriodCell("雨前", summary.before)
                        ImpactPeriodCell("雨中", summary.rainy)
                        ImpactPeriodCell("雨后", summary.after)
                    }
                    HorizontalDivider()
                    Text("本期雨时与无雨时", fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ImpactPeriodCell("雨时", summary.rainy)
                        ImpactPeriodCell("无雨", summary.dry)
                    }
                    if (summary.hourly.isNotEmpty()) {
                        Text("单日逐小时收款（雨时橙色）", style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold)
                        val maxPayments = summary.hourly.maxOfOrNull { it.payments }
                            ?.coerceAtLeast(1) ?: 1
                        summary.hourly.forEach { hour ->
                            Row(Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(7.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text("${hour.hour}时 ${if (hour.rainy) "雨" else "晴"}",
                                    modifier = Modifier.width(48.dp),
                                    style = MaterialTheme.typography.labelSmall)
                                Box(Modifier.weight(1f).height(8.dp)
                                    .background(Color(0xFFF0F2F3), RoundedCornerShape(4.dp))) {
                                    Box(Modifier.fillMaxHeight()
                                        .fillMaxWidth(hour.payments.toFloat()/maxPayments)
                                        .background(if (hour.rainy) impactOrange else impactGreen,
                                            RoundedCornerShape(4.dp)))
                                }
                                Text("${hour.payments}笔",
                                    modifier = Modifier.width(32.dp),
                                    style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    HorizontalDivider()
                    val hist = summary.history
                    Text("历史雨/无雨同期对比", fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodySmall)
                    Text("仅取该位置往前12周同星期、同完整小时及相同支付渠道的数据",
                        style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    Text("雨时${hist.rainyDays}个营业日 · 无雨${hist.dryDays}个营业日 · " +
                        "可配对小时段${hist.comparableSlots}组",
                        style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    if (hist.reliable) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text("历史雨时", style = MaterialTheme.typography.labelSmall)
                                Text("${impactRate(hist.rainyPaymentsPerHour)}笔/时",
                                    fontWeight = FontWeight.SemiBold)
                                Text(hist.rainyRevenuePerHour?.let(::impactMoney) ?: "—",
                                    style = MaterialTheme.typography.labelSmall)
                            }
                            Column(Modifier.weight(1f)) {
                                Text("历史无雨", style = MaterialTheme.typography.labelSmall)
                                Text("${impactRate(hist.dryPaymentsPerHour)}笔/时",
                                    fontWeight = FontWeight.SemiBold)
                                Text(hist.dryRevenuePerHour?.let(::impactMoney) ?: "—",
                                    style = MaterialTheme.typography.labelSmall)
                            }
                            Column(Modifier.weight(1f)) {
                                Text("同期差异", style = MaterialTheme.typography.labelSmall)
                                Text(impactPercent(hist.paymentDifference),
                                    fontWeight = FontWeight.SemiBold,
                                    color = if ((hist.paymentDifference ?: 0.0) < 0) impactOrange
                                        else impactGreen)
                                Text("付款笔数/小时", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    } else {
                        Text("可比样本不足或支付渠道不完整，不计算变化百分比。",
                            color = impactOrange, style = MaterialTheme.typography.labelSmall)
                    }
                    summary.warnings.forEach { note ->
                        Text("· $note", style = MaterialTheme.typography.labelSmall,
                            color = if (note.contains("不足") || note.contains("缺失") ||
                                note.contains("不完整") || note.contains("归档"))
                                impactOrange else Color.Gray)
                    }
                }
            }
        }
    }
    if (calendarOpen) {
        CustomerCalendarDialog(
            initialDate = if (periodMode == "CUSTOM") customStart else chosenDay,
            initialChoice = if (periodMode == "CUSTOM")
                CustomerCalendarSelection.days(customStart, customEnd)
                else CustomerCalendarSelection.day(chosenDay),
            onDismiss = { calendarOpen = false },
            onConfirm = { choice ->
                if (choice.start == choice.end) {
                    chosenDay = choice.start
                    periodMode = "DAY"
                } else {
                    customStart = choice.start
                    customEnd = choice.end
                    periodMode = "CUSTOM"
                }
                calendarOpen = false
            },
            onQuickRange = { quick ->
                when (quick) {
                    "TODAY", "YESTERDAY" -> {
                        chosenDay = if (quick == "TODAY") today else today.minusDays(1)
                        periodMode = "DAY"
                    }
                    "LAST_7" -> periodMode = "WEEK"
                    "LAST_30" -> periodMode = "MONTH"
                    "THIS_MONTH" -> {
                        customStart = today.withDayOfMonth(1)
                        customEnd = today
                        periodMode = "CUSTOM"
                    }
                    "LAST_MONTH" -> {
                        val month = today.minusMonths(1)
                        customStart = month.withDayOfMonth(1)
                        customEnd = customStart.withDayOfMonth(customStart.lengthOfMonth())
                        periodMode = "CUSTOM"
                    }
                    "ALL" -> {
                        customStart = sourceDays.mapNotNull {
                            runCatching { LocalDate.parse(it.date) }.getOrNull()
                        }.minOrNull() ?: today
                        customEnd = today
                        periodMode = "CUSTOM"
                    }
                }
                calendarOpen = false
            },
            businessDates = sourceDays.mapNotNull {
                runCatching { LocalDate.parse(it.date) }.getOrNull()
            }.toSet(),
            singleDayOnly = false
        )
    }
}
