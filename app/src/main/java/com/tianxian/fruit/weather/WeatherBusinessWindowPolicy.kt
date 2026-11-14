package com.tianxian.fruit.weather

import java.time.LocalDate
import java.time.LocalDateTime

/** Overnight hours belong to the business day when the stall opened. */
internal object WeatherBusinessWindowPolicy {
    private fun clockMinutes(raw: String): Int? {
        val parts = raw.trim().split(":")
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        if (hour == 24 && minute == 0) return 1440
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }
    fun window(date: LocalDate, startTime: String, endTime: String): Pair<LocalDateTime, LocalDateTime>? {
        val startMinutes = clockMinutes(startTime) ?: return null
        val endMinutes = clockMinutes(endTime) ?: return null
        if (startMinutes >= 1440) return null
        val start = date.atStartOfDay().plusMinutes(startMinutes.toLong())
        var end = date.atStartOfDay().plusMinutes(endMinutes.toLong())
        if (!end.isAfter(start)) end = end.plusDays(1)
        return start to end
    }
    fun activePreviousDate(now: LocalDateTime, startTime: String, endTime: String): LocalDate? {
        val previous = now.toLocalDate().minusDays(1)
        val window = window(previous, startTime, endTime) ?: return null
        return previous.takeIf {
            window.second.toLocalDate() == now.toLocalDate() &&
                now >= window.first && now.isBefore(window.second)
        }
    }
}
