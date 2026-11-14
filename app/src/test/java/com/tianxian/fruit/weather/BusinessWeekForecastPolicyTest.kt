package com.tianxian.fruit.weather

import com.tianxian.fruit.sync.WeatherDay
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class BusinessWeekForecastPolicyTest {
    private val today = LocalDate.parse("2026-11-15")
    private fun day(offset: Long, text: String = "晴", pop: Double? = null,
                    mm: Double? = null, high: Double = 30.0) = WeatherDay(
        today.plusDays(offset).toString(), text, "晴",
        if (text.contains("雨")) "305" else "100", "100",
        high, high - 6, mm, pop, null, "", ""
    )

    @Test fun onlyFutureSevenDays() {
        val risk = BusinessWeekForecastPolicy.nextSevenDays(
            listOf(day(-1), day(0), day(6), day(7, "阵雨")), today
        )
        assertEquals(listOf(today, today.plusDays(6)), risk.map { it.date })
        assertNull(BusinessWeekForecastPolicy.headline(risk))
    }
    @Test fun detectsRainHeatAndCooling() {
        val risk = BusinessWeekForecastPolicy.nextSevenDays(
            listOf(day(0, high = 36.0), day(1, "阵雨", 75.0, 2.5, 28.0)),
            today
        )
        assertTrue(risk[0].heat)
        assertTrue(risk[1].rain)
        assertTrue(risk[1].cooling)
        assertTrue(risk[1].severity >= 2)
        assertTrue(BusinessWeekForecastPolicy.headline(risk)!!.contains("可能有雨"))
    }
    @Test fun missingPreviousDayIsNotCooling() {
        assertFalse(BusinessWeekForecastPolicy.nextSevenDays(
            listOf(day(3, high = 19.0)), today
        ).single().cooling)
    }
    @Test fun dailyRainNeverInventsHours() {
        val risk = BusinessWeekForecastPolicy.nextSevenDays(
            listOf(day(2, "雷阵雨")), today
        ).single()
        assertTrue(risk.rain)
        assertFalse(risk.label.contains(":"))
    }
}
