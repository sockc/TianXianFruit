package com.tianxian.fruit.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class BusinessRhythmAnalyzerTest {
    private fun day(
        date: String,
        revenue: Double,
        customers: Int = 0,
        newCustomers: Int = 0,
        oldCustomers: Int = 0
    ) = RhythmBusinessDay(LocalDate.parse(date), revenue, customers, newCustomers, oldCustomers)

    @Test fun continuousFourthDayDoesNotAutomaticallyProveSaturation() {
        val history = listOf(
            day("2026-09-06", 2880.0, 170, 60, 110),
            day("2026-09-13", 3000.0, 175, 65, 110),
            day("2026-09-20", 2924.0, 171, 61, 110),
            day("2026-10-08", 3762.0, 210, 120, 90),
            day("2026-10-09", 4000.0), // 客户未归属
            day("2026-10-10", 2452.0, 138, 85, 53),
            day("2026-10-11", 2305.0, 159, 111, 48)
        )
        val result = BusinessRhythmAnalyzer.evaluate(LocalDate.parse("2026-10-11"), history)
        assertTrue(result.hasActual)
        assertEquals(4, result.consecutiveDays)
        assertEquals(3, result.comparableDays)
        assertTrue(result.revenueChangePercent!! < -20.0)
        assertTrue(result.oldCustomerChangePercent!! < -20.0)
        assertEquals("疑似老客需求提前释放", result.headline)
        assertEquals(111.0 / 159.0, result.newCustomerShare!!, 0.001)
    }

    @Test fun missingCustomerBreakdownDoesNotInventLossOfOldCustomers() {
        val history = listOf(
            day("2026-09-05", 3358.0, 152, 62, 90),
            day("2026-09-12", 4831.0, 239, 100, 139),
            day("2026-09-19", 4199.0, 223, 93, 130),
            day("2026-10-08", 3762.0, 210),
            day("2026-10-09", 4000.0),
            day("2026-10-10", 2452.0, 138)
        )
        val result = BusinessRhythmAnalyzer.evaluate(LocalDate.parse("2026-10-10"), history)
        assertEquals(3, result.consecutiveDays)
        assertEquals("营业额低于历史同星期", result.headline)
        assertNull(result.newCustomerShare)
        assertNull(result.oldCustomerChangePercent)
    }

    @Test fun nextDayPreviewDoesNotUseFutureActuals() {
        val history = listOf(
            day("2026-10-08", 3762.0),
            day("2026-10-09", 4000.0),
            day("2026-10-10", 2452.0),
            day("2026-10-11", 2305.0),
            day("2026-10-13", 9000.0)
        )
        val report = BusinessRhythmAnalyzer.evaluate(LocalDate.parse("2026-10-12"), history)
        assertFalse(report.hasActual)
        assertEquals(5, report.consecutiveDays)
        assertNull(report.revenue)
        assertNull(report.revenueChangePercent)
        assertEquals("连续经营风险待观察", report.headline)
    }

    @Test fun breaksResetStreakAndSparseWeekdayHistoryIsInsufficient() {
        val history = listOf(
            day("2026-10-08", 3762.0),
            day("2026-10-09", 4000.0),
            day("2026-10-11", 2305.0)
        )
        val report = BusinessRhythmAnalyzer.evaluate(LocalDate.parse("2026-10-11"), history)
        assertEquals(1, report.consecutiveDays)
        assertEquals("INSUFFICIENT", report.evidence)
        assertNull(report.revenueChangePercent)
    }

    @Test fun isolatedStoreInputDoesNotBorrowOtherStoreHistory() {
        val onlyTargetStore = listOf(day("2026-10-11", 2305.0, 159, 111, 48))
        val report = BusinessRhythmAnalyzer.evaluate(LocalDate.parse("2026-10-11"), onlyTargetStore)
        assertEquals(0, report.comparableDays)
        assertNull(report.oldCustomerChangePercent)
        assertNotEquals("疑似老客需求提前释放", report.headline)
    }
}
