package com.tianxian.fruit.ui

import com.tianxian.fruit.sync.WeatherDay
import com.tianxian.fruit.sync.WeatherHour
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class HomeRainReminderPolicyTest {
    private val day = LocalDate.parse("2026-10-12")
    private val now = LocalDateTime.parse("2026-10-12T13:00")
    private fun hour(time: String, text: String = "晴", pop: Double? = 0.0, rain: Double? = 0.0) =
        WeatherHour(
            time = "2026-10-12T$time:00",
            text = text,
            code = if (text.contains("雨")) "305" else "100",
            temperature = 26.0,
            feelsLike = null,
            humidity = null,
            precipitation = rain,
            precipitationProbability = pop,
            windDirection = "",
            windScale = "",
            windSpeed = null
        )
    private fun daily(text: String = "多云", pop: Double? = 0.0) =
        WeatherDay(
            date = "2026-10-12", textDay = text, textNight = "晴",
            codeDay = if ("雨" in text) "305" else "101", codeNight = "100",
            tempMax = 29.0, tempMin = 22.0,
            precipitation = 0.0, precipitationProbability = pop,
            humidity = null, sunrise = "06:00", sunset = "18:00"
        )

    @Test fun businessRainGetsPriorityOverSunnyCurrentWeather() {
        val target = hour("19:00", text = "小雨", pop = 75.0, rain = 2.5)
        val pre = hour("14:00", text = "阵雨", pop = 45.0, rain = 0.4)
        val result = HomeRainReminderPolicy.evaluate(day, now, listOf(pre, target), listOf(pre), listOf(target), daily())
        assertNotNull(result)
        assertTrue(result!!.headline.contains("营业时段"))
        assertTrue(result.strong)
        assertTrue(result.detail.contains("19:00"))
    }

    @Test fun preOpenRainGetsPriorityOverOtherTimeRain() {
        val early = hour("09:00", text = "小雨")
        val pre = hour("15:00", pop = 50.0)
        val result = HomeRainReminderPolicy.evaluate(day, now, listOf(early, pre), listOf(pre), emptyList(), daily())
        assertNotNull(result)
        assertTrue(result!!.headline.contains("开摊前"))
    }

    @Test fun pastRainIsNotAdvertisedAsFutureWeather() {
        val morningRain = hour("09:00", text = "小雨")
        assertNull(HomeRainReminderPolicy.evaluate(day, now, listOf(morningRain), emptyList(), emptyList(), daily()))
    }

    @Test fun dailyRainFallbackDoesNotMakeUpHourlyTiming() {
        val result = HomeRainReminderPolicy.evaluate(day, now, emptyList(), emptyList(), emptyList(), daily("小雨", 60.0))
        assertNotNull(result)
        assertTrue(result!!.detail.contains("暂无可靠逐小时"))
        assertFalse(result.detail.contains("19:00"))
    }

    @Test fun clearForecastDoesNotCreateWarning() {
        val sunny = hour("17:00")
        assertNull(HomeRainReminderPolicy.evaluate(day, now, listOf(sunny), emptyList(), listOf(sunny), daily()))
    }

    @Test fun pastDateDoesNotShowFutureRainReminder() {
        val old = LocalDate.parse("2026-10-11")
        assertNull(HomeRainReminderPolicy.evaluate(old, now, listOf(hour("19:00", "暴雨", 90.0, 6.0)), emptyList(), emptyList(), daily("暴雨", 90.0)))
    }
}
