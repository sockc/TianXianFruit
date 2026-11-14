package com.tianxian.fruit.weather

import com.tianxian.fruit.sync.WeatherDay
import com.tianxian.fruit.weather.WeatherRainRisk
import java.time.LocalDate

/** Daily forecast risks, never a claim about specific hours of rainfall. */
internal data class BusinessForecastRisk(
    val date: LocalDate,
    val day: WeatherDay,
    val rain: Boolean,
    val heat: Boolean,
    val cooling: Boolean,
    val severity: Int,
    val label: String
)

internal object BusinessWeekForecastPolicy {
    fun nextSevenDays(
        daily: List<WeatherDay>,
        today: LocalDate,
        rainThreshold: Double = WeatherRainRisk.DEFAULT_PROBABILITY_THRESHOLD,
        heatThresholdC: Double = 35.0,
        coolingThresholdC: Double = 5.0
    ): List<BusinessForecastRisk> {
        val days = daily.associateBy { it.date }
        return (0L..6L).mapNotNull { offset ->
            val date = today.plusDays(offset)
            val day = days[date.toString()] ?: return@mapNotNull null
            val previous = days[date.minusDays(1).toString()]
            val rain = WeatherRainRisk.dayHasRain(day, rainThreshold)
            val heat = (day.tempMax ?: Double.NEGATIVE_INFINITY) >= heatThresholdC
            // Daily maxima compare trends only; no implied business-hour temperature.
            val cooling = previous?.tempMax != null && day.tempMax != null &&
                previous.tempMax - day.tempMax >= coolingThresholdC
            val severity = maxOf(
                if (!rain) 0 else when {
                    (day.precipitation ?: 0.0) >= 5.0 ||
                        (day.precipitationProbability ?: 0.0) >= 85.0 -> 3
                    (day.precipitation ?: 0.0) >= 2.0 ||
                        (day.precipitationProbability ?: 0.0) >= 70.0 -> 2
                    else -> 1
                },
                if (heat) if ((day.tempMax ?: 0.0) >= 38.0) 3 else 2 else 0,
                if (cooling) 2 else 0
            )
            val label = buildList {
                if (rain) add("有雨")
                if (heat) add("高温")
                if (cooling) add("降温")
            }.joinToString("·").ifBlank { day.textDay.ifBlank { "暂无风险" } }
            BusinessForecastRisk(date, day, rain, heat, cooling, severity, label)
        }
    }
    fun headline(days: List<BusinessForecastRisk>): String? {
        val rain = days.count { it.rain }
        val heat = days.count { it.heat }
        val cooling = days.count { it.cooling }
        if (rain + heat + cooling == 0) return null
        val facts = buildList {
            if (rain > 0) add("${rain}天可能有雨")
            if (heat > 0) add("${heat}天高温")
            if (cooling > 0) add("${cooling}天明显降温")
        }
        return "未来7天：" + facts.joinToString("、")
    }
}
