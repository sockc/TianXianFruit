package com.tianxian.fruit.data

import org.json.JSONObject

/**
 * Read only the archived *observed* hourly segment. Forecasts and missing
 * 'actual_available' flags are intentionally excluded rather than guessed.
 */
internal object CustomerObservedWeatherParser {
    fun fromHistory(
        history: BusinessWeatherHistoryRecord
    ): List<CustomerObservedWeatherHour> {
        val root = runCatching { JSONObject(history.payloadJson) }.getOrNull()
            ?: return emptyList()
        val hours = root.optJSONArray("hourly")
            ?: root.optJSONObject("data")?.optJSONArray("hourly")
            ?: return emptyList()
        return buildList {
            for (i in 0 until hours.length()) {
                val row = hours.optJSONObject(i) ?: continue
                if (!row.optBoolean("actual_available", false)) continue
                val rawTime = row.optString("time")
                val clock = rawTime.substringAfter('T',
                    rawTime.substringAfter(' ', rawTime))
                val hour = clock.take(2).toIntOrNull()?.takeIf { it in 0..23 } ?: continue
                val condition = row.optString("text").trim()
                val precipitation = if (row.has("precipitation") &&
                    !row.isNull("precipitation")
                ) row.optDouble("precipitation", Double.NaN).takeIf {
                    it.isFinite() && it >= 0.0
                } else null
                // Keep actual weather from the selected date/shift only. Snapshots
                // that mix forecast hours and observed hours are safe to consume.
                val rain = condition.contains("雨") ||
                    (precipitation != null && precipitation >= 0.1)
                if (condition.isBlank() && precipitation == null) continue
                add(CustomerObservedWeatherHour(
                    storeId=history.storeId,
                    businessDate=history.date,
                    hour=hour,
                    condition=condition.ifBlank { if (rain) "降水" else "实况" },
                    precipitationMm=precipitation,
                    raining=rain
                ))
            }
        }.distinctBy { it.hour }
    }
}
