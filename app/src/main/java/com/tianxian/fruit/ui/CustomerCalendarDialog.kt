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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth

/** A single day, complete month, or inclusive interval of complete months. */
internal data class CustomerCalendarChoice(
    val start: LocalDate,
    val end: LocalDate,
    val kind: Kind
) {
    enum class Kind { DAY, MONTH, MONTHS }
    val label: String get() = when (kind) {
        Kind.DAY -> "${start.monthValue}月${start.dayOfMonth}日"
        Kind.MONTH -> "${start.year}年${start.monthValue}月"
        Kind.MONTHS -> "${start.year}年${start.monthValue}月—${end.year}年${end.monthValue}月"
    }
}

internal object CustomerCalendarSelection {
    /** Tapping February then May yields the complete February-May range. */
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
}

/**
 * Compact anchored-by-header month calendar.
 * First tap on a month chooses that whole month; second month chooses an
 * inclusive month interval; tapping a numbered day instead chooses one day.
 * Nothing changes until the user presses 确定.
 */
@Composable
internal fun CustomerCalendarDialog(
    initialDate: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (CustomerCalendarChoice) -> Unit,
    onQuickRange: (String) -> Unit
) {
    var year by remember { mutableIntStateOf(initialDate.year) }
    var first by remember { mutableStateOf<YearMonth?>(null) }
    var last by remember { mutableStateOf<YearMonth?>(null) }
    var day by remember { mutableStateOf<LocalDate?>(null) }
    val selectedMonth = if (day == null && last == null) first else null
    val selected = day?.let(CustomerCalendarSelection::day)
        ?: first?.let { CustomerCalendarSelection.month(it, last) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { year-- }) { Text("‹") }
                Text("${year}年", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { year++ }) { Text("›") }
            }
        },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 485.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("点击月份选整月，再点击另一月选起止月份；点击具体日期选单日。",
                    style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                (0..2).forEach { rowIndex ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        (1..4).forEach { col ->
                            val month = YearMonth.of(year, rowIndex * 4 + col)
                            val highlighted = day == null && first != null &&
                                month >= minOf(first!!, last ?: first!!) &&
                                month <= maxOf(first!!, last ?: first!!)
                            Surface(
                                modifier = Modifier.weight(1f).clickable {
                                    if (first == null || last != null || day != null) {
                                        first = month
                                        last = null
                                    } else if (first == month) {
                                        last = null
                                    } else {
                                        last = month
                                    }
                                    day = null
                                },
                                color = if (highlighted) Color(0xFFDEF2E9) else Color(0xFFF4F6F5),
                                shape = RoundedCornerShape(7.dp)
                            ) {
                                Box(Modifier.height(36.dp), contentAlignment = Alignment.Center) {
                                    Text("${month.monthValue}月",
                                        color = if (highlighted) Color(0xFF087D4E) else Color.DarkGray,
                                        fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Normal)
                                }
                            }
                        }
                    }
                }
                if (selectedMonth != null) {
                    val month = selectedMonth
                    HorizontalDivider()
                    Text("${month.monthValue}月 · 点击某一天可改为单日", style = MaterialTheme.typography.labelSmall)
                    Row(Modifier.fillMaxWidth()) {
                        listOf("一","二","三","四","五","六","日").forEach { name ->
                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                Text(name, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                            }
                        }
                    }
                    val offset = month.atDay(1).dayOfWeek.value - 1
                    val cells = (offset + month.lengthOfMonth() + 6) / 7 * 7
                    (0 until cells step 7).forEach { startIndex ->
                        Row(Modifier.fillMaxWidth()) {
                            (startIndex until startIndex + 7).forEach { index ->
                                val n = index - offset + 1
                                Box(
                                    Modifier.weight(1f).height(33.dp).then(
                                        if (n in 1..month.lengthOfMonth()) Modifier.clickable {
                                            day = month.atDay(n)
                                        } else Modifier
                                    ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (n in 1..month.lengthOfMonth()) Text(
                                        "$n", style = MaterialTheme.typography.bodySmall,
                                        color = Color.DarkGray
                                    )
                                }
                            }
                        }
                    }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    listOf("近7天" to "LAST_7", "近30天" to "LAST_30", "全部" to "ALL").forEach { (label, range) ->
                        TextButton(onClick = { onQuickRange(range) }) {
                            Text(label, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Text(selected?.label?.let { "当前选择：$it" } ?: "请选择月份或日期",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF087D4E), fontWeight = FontWeight.SemiBold)
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected != null,
                onClick = { selected?.let(onConfirm) }
            ) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
