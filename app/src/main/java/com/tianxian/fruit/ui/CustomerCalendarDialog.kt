package com.tianxian.fruit.ui

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.YearMonth

/** Shared return contract: day, whole month, consecutive whole months, or arbitrary dates. */
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

/**
 * Mirrors the Operating Analysis > Business Statistics StatsRangePickerDialog:
 * tap the month header for a whole month (two taps for consecutive months),
 * or tap a day for a single day (two taps for a date interval).
 * No extra mode selector, date input rows or redundant top cards.
 */
@Composable
internal fun CustomerCalendarDialog(
    initialDate: LocalDate,
    initialChoice: CustomerCalendarChoice? = null,
    onDismiss: () -> Unit,
    onConfirm: (CustomerCalendarChoice) -> Unit,
    onQuickRange: (String) -> Unit
) {
    val initial = initialChoice ?: CustomerCalendarSelection.day(initialDate)
    var visibleMonth by remember(initialDate, initialChoice) {
        mutableStateOf(initial.start.withDayOfMonth(1))
    }
    var draftStart by remember(initialDate, initialChoice) { mutableStateOf(initial.start) }
    var draftEnd by remember(initialDate, initialChoice) { mutableStateOf<LocalDate?>(initial.end) }
    var resultKind by remember(initialDate, initialChoice) { mutableStateOf(initial.kind) }
    var mode by remember(initialDate, initialChoice) { mutableStateOf<CustomerCalendarChoice.Kind?>(null) }
    var anchor by remember(initialDate, initialChoice) { mutableStateOf<LocalDate?>(null) }

    fun chooseMonth(month: LocalDate) {
        val first = month.withDayOfMonth(1)
        if (mode != CustomerCalendarChoice.Kind.MONTH || anchor == null) {
            mode = CustomerCalendarChoice.Kind.MONTH
            anchor = first
            draftStart = first
            draftEnd = null
            resultKind = CustomerCalendarChoice.Kind.MONTH
            return
        }
        val other = anchor!!
        draftStart = minOf(other, first)
        draftEnd = maxOf(other, first).let { it.withDayOfMonth(it.lengthOfMonth()) }
        resultKind = if (other == first) CustomerCalendarChoice.Kind.MONTH
            else CustomerCalendarChoice.Kind.MONTHS
        anchor = null
    }

    fun chooseDay(day: LocalDate) {
        if (mode != CustomerCalendarChoice.Kind.DAY || anchor == null) {
            mode = CustomerCalendarChoice.Kind.DAY
            anchor = day
            draftStart = day
            draftEnd = null
            resultKind = CustomerCalendarChoice.Kind.DAY
            return
        }
        val other = anchor!!
        draftStart = minOf(other, day)
        draftEnd = maxOf(other, day)
        resultKind = if (other == day) CustomerCalendarChoice.Kind.DAY
            else CustomerCalendarChoice.Kind.DAYS
        anchor = null
    }

    val chosen = when {
        draftEnd != null -> CustomerCalendarChoice(draftStart, draftEnd!!, resultKind)
        mode == CustomerCalendarChoice.Kind.MONTH ->
            CustomerCalendarSelection.month(YearMonth.from(draftStart))
        else -> CustomerCalendarSelection.day(draftStart)
    }
    val accent = Color(0xFF087D4E)
    val offset = visibleMonth.dayOfWeek.value - 1
    val length = visibleMonth.lengthOfMonth()
    val cells = ((offset + length + 6) / 7) * 7
    val visibleMonthSelected =
        !visibleMonth.plusMonths(1).minusDays(1).isBefore(chosen.start) &&
            !visibleMonth.isAfter(chosen.end.withDayOfMonth(1))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    modifier = Modifier.size(38.dp),
                    onClick = { visibleMonth = visibleMonth.minusMonths(1) }
                ) { Text("‹", fontSize = 28.sp, color = accent) }
                FilterChip(
                    selected = visibleMonthSelected,
                    onClick = { chooseMonth(visibleMonth) },
                    modifier = Modifier.weight(1f),
                    label = {
                        Text("${visibleMonth.year}年${visibleMonth.monthValue}月",
                            fontWeight = FontWeight.Bold)
                    }
                )
                IconButton(
                    modifier = Modifier.size(38.dp),
                    onClick = { visibleMonth = visibleMonth.plusMonths(1) }
                ) { Text("›", fontSize = 28.sp, color = accent) }
            }
        },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 510.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(
                    "点月份选择整月；连续点两个不同月份选择多月。点日期一次选单日，连续点两个日期选择日期范围。",
                    style = MaterialTheme.typography.labelSmall, color = Color.Gray
                )
                Row(Modifier.fillMaxWidth()) {
                    listOf("一", "二", "三", "四", "五", "六", "日").forEach { weekday ->
                        Text(weekday, modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                    }
                }
                repeat(cells / 7) { row ->
                    Row(Modifier.fillMaxWidth()) {
                        repeat(7) { col ->
                            val number = row * 7 + col - offset + 1
                            val cellDate = if (number in 1..length)
                                visibleMonth.withDayOfMonth(number) else null
                            val selected = cellDate != null &&
                                !cellDate.isBefore(chosen.start) && !cellDate.isAfter(chosen.end)
                            val endpoint = cellDate != null &&
                                (cellDate == draftStart || cellDate == draftEnd)
                            Box(
                                Modifier.weight(1f).aspectRatio(1f).padding(2.dp)
                                    .clip(RoundedCornerShape(9.dp))
                                    .then(when {
                                        endpoint -> Modifier.background(Color(0xFFCDEEDC))
                                        selected -> Modifier.background(Color(0xFFE9F8F0))
                                        else -> Modifier
                                    })
                                    .clickable(enabled = cellDate != null) {
                                        cellDate?.let(::chooseDay)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                if (cellDate != null) {
                                    Text(cellDate.dayOfMonth.toString(),
                                        color = if (selected) accent else Color.DarkGray,
                                        fontWeight = if (endpoint) FontWeight.Bold
                                            else FontWeight.Normal)
                                }
                            }
                        }
                    }
                }
                Text("已选：${chosen.label}",
                    style = MaterialTheme.typography.bodySmall, color = accent,
                    fontWeight = FontWeight.SemiBold)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(chosen) }) { Text("确定") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onQuickRange("ALL") }) { Text("全部日期") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}
