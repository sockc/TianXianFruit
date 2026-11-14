package com.tianxian.fruit.weather

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class WeatherBusinessWindowPolicyTest {
    @Test fun earlyMorningStillBelongsToPreviousBusinessDate() {
        assertEquals(LocalDate.parse("2026-11-15"),
            WeatherBusinessWindowPolicy.activePreviousDate(
                LocalDateTime.parse("2026-11-16T00:30:00"), "16:00", "02:00"))
        assertNull(WeatherBusinessWindowPolicy.activePreviousDate(
            LocalDateTime.parse("2026-11-16T02:00:00"), "16:00", "02:00"))
    }
    @Test fun midnightBoundaryAndNormalHoursDoNotExtendSession() {
        assertNull(WeatherBusinessWindowPolicy.activePreviousDate(
            LocalDateTime.parse("2026-11-16T00:10:00"), "16:00", "24:00"))
        assertNull(WeatherBusinessWindowPolicy.activePreviousDate(
            LocalDateTime.parse("2026-11-16T00:10:00"), "16:00", "22:00"))
    }
    @Test fun oneAmBusinessShiftAndInvalidClock() {
        assertEquals(LocalDate.parse("2026-11-15"),
            WeatherBusinessWindowPolicy.activePreviousDate(
                LocalDateTime.parse("2026-11-16T01:05:00"), "22:00", "01:30"))
        assertNull(WeatherBusinessWindowPolicy.window(
            LocalDate.parse("2026-11-15"), "invalid", "02:00"))
    }
}
