package com.tianxian.fruit.weather

import com.tianxian.fruit.sync.WeatherDay
import com.tianxian.fruit.sync.WeatherHour
import org.junit.Assert.*
import org.junit.Test

class WeatherRainRiskTest {
    private fun hour(pop: Double?, text: String = "晴", forecastText: String = "") =
        WeatherHour(
            time = "2026-11-15T19:00:00", text = text, code = "100",
            temperature = 25.0, feelsLike = null, humidity = null,
            precipitation = null, precipitationProbability = pop,
            windDirection = "", windScale = "", windSpeed = null,
            forecastText = forecastText
        )
    private fun day(pop: Double?, text: String = "晴") =
        WeatherDay(
            "2026-11-15", text, "晴", "100", "100", 30.0, 24.0,
            null, pop, null, "", ""
        )
    @Test fun dailyAndHourlyThresholdAreTheSame() {
        assertFalse(WeatherRainRisk.hourHasRain(hour(59.9)))
        assertFalse(WeatherRainRisk.dayHasRain(day(59.9)))
        assertTrue(WeatherRainRisk.hourHasRain(hour(60.0)))
        assertTrue(WeatherRainRisk.dayHasRain(day(60.0)))
        assertFalse(WeatherRainRisk.hourHasRain(hour(65.0), 70.0))
        assertFalse(WeatherRainRisk.dayHasRain(day(65.0), 70.0))
    }
    @Test fun futureForecastTextWinsOverPastObservedLabel() {
        assertFalse(WeatherRainRisk.hourHasRain(hour(0.0, text = "大雨", forecastText = "晴")))
        assertTrue(WeatherRainRisk.hourHasRain(hour(null, forecastText = "阵雨")))
        assertTrue(WeatherRainRisk.dayHasRain(day(null, text = "小雨")))
    }
}
