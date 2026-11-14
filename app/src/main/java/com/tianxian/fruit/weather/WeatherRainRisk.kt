package com.tianxian.fruit.weather

import com.tianxian.fruit.sync.WeatherDay
import com.tianxian.fruit.sync.WeatherHour

/** Forecast-based rain risk shared by homepage, week view and notifications. */
internal object WeatherRainRisk {
    const val DEFAULT_PROBABILITY_THRESHOLD = 60.0

    fun hourHasRain(hour: WeatherHour, probabilityThreshold: Double = DEFAULT_PROBABILITY_THRESHOLD): Boolean {
        val forecastLabel = hour.forecastText.ifBlank { hour.text }
        val forecastCode = hour.forecastCode.takeUnless { it.isBlank() || it == "999" } ?: hour.code
        return (hour.forecastPrecipitation ?: hour.precipitation ?: 0.0) > 0.05 ||
            (hour.precipitationProbability ?: 0.0) >= probabilityThreshold ||
            forecastLabel.contains("雨") || forecastLabel.contains("雷暴") ||
            (forecastCode.toIntOrNull() ?: -1) in 300..399
    }

    fun dayHasRain(day: WeatherDay, probabilityThreshold: Double = DEFAULT_PROBABILITY_THRESHOLD): Boolean {
        val label = day.textDay + " " + day.textNight
        return (day.precipitation ?: 0.0) > 0.05 ||
            (day.precipitationProbability ?: 0.0) >= probabilityThreshold ||
            label.contains("雨") || label.contains("雷暴") ||
            (day.codeDay.toIntOrNull() ?: -1) in 300..399 ||
            (day.codeNight.toIntOrNull() ?: -1) in 300..399
    }
}
