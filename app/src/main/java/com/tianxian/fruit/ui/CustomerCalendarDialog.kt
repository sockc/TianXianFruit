package com.tianxian.fruit.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth

/** One date, one complete month, a complete-month interval, or an arbitrary date interval. */
internal data class CustomerCalendarChoice(
    val start: LocalDate,
    val end: LocalDate,
    val kind: Kind
) {
    enum class Kind { DAY, MONTH, MONTHS, DAYS }
    val label: String get() = when (kind) {
        Kind.DAY -> "${start.year}年${start.monthValue}月${start.dayOfMonth}日"
        Kind.MONTH -> "${start.year}年${start.monthValue}月"
        Kind.MONTHS -> "${start.year}/${start.monthValue}—${end.year}/${end.monthValue}月"
        Kind.DAYS -> "${start} — ${end}"
    }
}

internal object CustomerCalendarSelection {
    /** Selecting February and then May includes every day in those four months. */
    fun month(startMonth: YearMonth, endMonth: YearMonth? = null): CustomerCalendarChoice {
        val first = minOf(startMonth, endMonth ?: startMonth)
        val last = maxOf(startMonth, endMonth ?: startMonth)
        return CustomerCalendarChoice(
            first.atDay(1), last.atEndOfMonth(),
            if (first == last) CustomerCalendarChoice.Kind.MONTH else CustomerCalendarChoice.Kind.MONTHS
        )
    }

    fun day(date: LocalDate) =
        CustomerCalendarChoice(date, date, CustomerCalendarChoice.Kind.DAY)

    fun days(start: LocalDate, end: LocalDate): CustomerCalendarChoice =
        CustomerCalendarChoice(minOf(start, end), maxOf(start, end), CustomerCalendarChoice.Kind.DAYS)
}

private enum class CustomerDateMode(val label: String) {
    DAY("单日"), MONTH("月份"), INTERVAL("日期区间")
}

/**
 * Business-statistics-style date selector: common periods first, then an
 * immediately visible calendar. Month mode preserves the two-month shortcut.
 */
@Composable
internal fun CustomerCalendarDialog(
    initialDate: LocalDate,
    initialChoice: CustomerCalendarChoice? = null,
    onDismiss: () -> Unit,
    onConfirm: (CustomerCalendarChoice) -> Unit,
    onQuickRange: (String) -> Unit
) {
    // Opening the picker reflects the period already shown, rather than
    // silently defaulting to the first day of an actively selected month.
    var mode by remember(initialChoice) {
        mutableStateOf(when (initialChoice?.kind) {
            CustomerCalendarChoice.Kind.MONTH, CustomerCalendarChoice.Kind.MONTHS -> CustomerDateMode.MONTH
            CustomerCalendarChoice.Kind.DAYS -> CustomerDateMode.INTERVAL
            else -> CustomerDateMode.DAY
        })
    }
    var visibleMonth by remember(initialDate, initialChoice) {
        mutableStateOf(YearMonth.from(initialChoice?.start ?: initialDate))
    }
    var day by remember(initialDate, initialChoice) {
        mutableStateOf<LocalDate?>(initialChoice?.takeIf { it.kind == CustomerCalendarChoice.Kind.DAY }?.start
            ?: initialDate)
    }
    var firstMonth by remember(initialChoice) {
        mutableStateOf(initialChoice?.takeIf { it.kind == CustomerCalendarChoice.Kind.MONTH ||
            it.kind == CustomerCalendarChoice.Kind.MONTHS }?.start?.let(YearMonth::from))
    }
    var secondMonth by remember(initialChoice) {
        mutableStateOf(initialChoice?.takeIf { it.kind == CustomerCalendarChoice.Kind.MONTHS }
            ?.end?.let(YearMonth::from))
    }
    var firstDay by remember(initialChoice) {
        mutableStateOf(initialChoice?.takeIf { it.kind == CustomerCalendarChoice.Kind.DAYS }?.start)
    }
    var secondDay by remember(initialChoice) {
        mutableStateOf(initialChoice?.takeIf { it.kind == CustomerCalendarChoice.Kind.DAYS }?.end)
    }

    val choice = when (mode) {
        CustomerDateMode.DAY -> day?.let(CustomerCalendarSelection::day)
        CustomerDateMode.MONTH -> firstMonth?.let { CustomerCalendarSelection.month(it, secondMonth) }
        CustomerDateMode.INTERVAL -> if (firstDay != null && secondDay != null)
            CustomerCalendarSelection.days(firstDay!!, secondDay!!) else null
    }
    val accent = Color(0xFF087D4E)
    val today = LocalDate.now()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择统计日期", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 510.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(
                        "今天" to "TODAY", "昨天" to "YESTERDAY",
                        "本月" to "THIS_MONTH", "上月" to "LAST_MONTH",
                        "近7天" to "LAST_7", "近30天" to "LAST_30",
                        "全部" to "ALL"
                    ).forEach { (label, key) ->
                        AssistChip(onClick = { onQuickRange(key) },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) })
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    CustomerDateMode.entries.forEach { option ->
                        FilterChip(
                            modifier = Modifier.weight(1f),
                            selected = mode == option,
                            onClick = { mode = option },
                            label = {
                                Text(option.label, modifier = Modifier.fillMaxWidth(),
                                    textAlign = TextAlign.Center,
                                    style = MaterialTheme.typography.labelSmall)
                            }
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = {
                        visibleMonth = if (mode == CustomerDateMode.MONTH)
                            visibleMonth.minusYears(1) else visibleMonth.minusMonths(1)
                    }) { Text("‹") }
                    Text(
                        if (mode == CustomerDateMode.MONTH) "${visibleMonth.year}年"
                        else "${visibleMonth.year}年${visibleMonth.monthValue}月",
                        fontWeight = FontWeight.SemiBold, color = accent
                    )
                    TextButton(onClick = {
                        visibleMonth = if (mode == CustomerDateMode.MONTH)
                            visibleMonth.plusYears(1) else visibleMonth.plusMonths(1)
                    }) { Text("›") }
                }

                if (mode == CustomerDateMode.MONTH) {
                    (0..2).forEach { line ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            (1..4).forEach { col ->
                                val month = YearMonth.of(visibleMonth.year, line * 4 + col)
                                val selected = firstMonth != null &&
                                    month >= minOf(firstMonth!!, secondMonth ?: firstMonth!!) &&
                                    month <= maxOf(firstMonth!!, secondMonth ?: firstMonth!!)
                                Surface(
                                    modifier = Modifier.weight(1f).clickable {
                                        if (firstMonth == null || secondMonth != null) {
                                            firstMonth = month
                                            secondMonth = null
                                        } else if (month != firstMonth) {
                                            secondMonth = month
                                        }
                                    },
                                    color = if (selected) Color(0xFFDEF2E9) else Color(0xFFF4F6F5),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Box(Modifier.height(39.dp), contentAlignment = Alignment.Center) {
                                        Text("${month.monthValue}月",
                                            color = if (selected) accent else Color.DarkGray)
                                    }
                                }
                            }
                        }
                    }
                    Text("点击一个月份统计整月；再点另一个月份统计两月之间的所有月份。",
                        style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                } else {
                    Row(Modifier.fillMaxWidth()) {
                        listOf("一", "二", "三", "四", "五", "六", "日").forEach { weekday ->
                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                Text(weekday, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                            }
                        }
                    }
                    val offset = visibleMonth.atDay(1).dayOfWeek.value - 1
                    val cells = ((offset + visibleMonth.lengthOfMonth() + 6) / 7) * 7
                    (0 until cells step 7).forEach { start ->
                        Row(Modifier.fillMaxWidth()) {
                            (start until start + 7).forEach { index ->
                                val number = index - offset + 1
                                val date = if (number in 1..visibleMonth.lengthOfMonth())
                                    visibleMonth.atDay(number) else null
                                val selected = date != null && when (mode) {
                                    CustomerDateMode.DAY -> date == day
                                    CustomerDateMode.INTERVAL -> firstDay != null &&
                                        date >= minOf(firstDay!!, secondDay ?: firstDay!!) &&
                                        date <= maxOf(firstDay!!, secondDay ?: firstDay!!)
                                    else -> false
                                }
                                Surface(
                                    modifier = Modifier.weight(1f).then(if (date != null) {
                                        Modifier.clickable {
                                            if (mode == CustomerDateMode.DAY) {
                                                day = date
                                            } else if (firstDay == null || secondDay != null) {
                                                firstDay = date
                                                secondDay = null
                                            } else {
                                                secondDay = date
                                            }
                                        }
                                    } else Modifier),
                                    color = if (selected) Color(0xFFDEF2E9) else Color.Transparent,
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Box(Modifier.height(34.dp), contentAlignment = Alignment.Center) {
                                        if (date != null) Text(
                                            number.toString(),
                                            color = if (selected) accent else
                                                if (date == today) accent else Color.DarkGray,
                                            fontWeight = if (selected || date == today)
                                                FontWeight.Bold else FontWeight.Normal,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (mode == CustomerDateMode.INTERVAL) {
                        Text("依次点击开始日期和结束日期，可跨月份；使用左右箭头切换月份。",
                            style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    }
                }
                Text(choice?.let { "已选择：${it.label}" } ?: "请选择日期",
                    style = MaterialTheme.typography.bodySmall,
                    color = accent, fontWeight = FontWeight.SemiBold)
            }
        },
        confirmButton = {
            TextButton(enabled = choice != null, onClick = { choice?.let(onConfirm) }) {
                Text("确定")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
