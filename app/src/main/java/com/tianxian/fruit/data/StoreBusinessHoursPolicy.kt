package com.tianxian.fruit.data

/**
 * A saved selling shift belongs to its opening business date.
 * End times earlier than the opening time mean the NEXT calendar day.
 * "24:00" is the end of the opening calendar day, not 00:00 at its start.
 */
internal object StoreBusinessHoursPolicy {
    private fun minutes(time: String, isEnd: Boolean): Int? {
        if (time == "24:00") return if (isEnd) 24 * 60 else null
        val parts = time.split(':')
        if (parts.size != 2 || parts[0].length != 2 || parts[1].length != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    fun durationMinutes(start: String, end: String): Int? {
        val startMinute = minutes(start, false) ?: return null
        val endMinute = minutes(end, true) ?: return null
        val duration = if (endMinute < startMinute) endMinute + 1440 - startMinute
            else endMinute - startMinute
        return duration.takeIf { it in 1..1440 }
    }

    fun valid(start: String, end: String): Boolean = durationMinutes(start, end) != null

    fun endsNextDay(start: String, end: String): Boolean {
        if (!valid(start, end) || end == "24:00") return false
        val startMinute = minutes(start, false) ?: return false
        val endMinute = minutes(end, true) ?: return false
        return endMinute < startMinute
    }
}
