package com.tianxian.fruit.ui

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Presentation-only labels for the customer analysis header. Business dates
 * and the range itself are never changed by formatting.
 */
internal object CustomerAnalysisDisplayPolicy {
    private val dayFormatter = DateTimeFormatter.ofPattern("yyyy年M月d日 E", Locale.CHINA)
    private val compactDay = DateTimeFormatter.ofPattern("yyyy/M/d", Locale.CHINA)
    private val monthFormatter = DateTimeFormatter.ofPattern("yyyy年M月", Locale.CHINA)

    fun dateLabel(
        range: String,
        day: LocalDate,
        month: LocalDate,
        start: LocalDate,
        end: LocalDate
    ): String = when (range) {
        "TODAY" -> day.format(dayFormatter)
        "THIS_MONTH" -> month.format(monthFormatter)
        "CUSTOM" -> when {
            start == end -> start.format(dayFormatter)
            start.dayOfMonth == 1 && end.dayOfMonth == end.lengthOfMonth() &&
                (start.year != end.year || start.month != end.month) ->
                "${start.format(monthFormatter)}—${end.format(monthFormatter)}"
            else -> "${start.format(compactDay)}—${end.format(compactDay)}"
        }
        "LAST_7" -> "近7天 · 截至${day.format(compactDay)}"
        "LAST_30" -> "近30天 · 截至${day.format(compactDay)}"
        "ALL" -> "全部日期"
        else -> day.format(dayFormatter)
    }

    fun detailContext(
        day: LocalDate,
        storeName: String,
        platform: String,
        collector: String
    ): String = listOf(
        storeName.ifBlank { "全部位置" },
        day.format(compactDay),
        platform,
        collector
    ).filter { it.isNotBlank() }.joinToString(" · ")

    fun percentageChange(actual: Double, baseline: Double?, samples: Int): String? {
        if (samples < 3 || baseline == null || baseline <= 0.005 ||
            !actual.isFinite() || !baseline.isFinite()) return null
        return String.format(Locale.CHINA, "%+.1f%%", (actual / baseline - 1.0) * 100)
    }
}
