package com.tianxian.fruit.data

import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.*
import org.junit.Test

class CustomerLocationCustomComparisonTest {
    private fun session(day: String, end: String = "24:00", store: Long = 5L) =
        CustomerTrafficSession(day, store, "游乐场", "16:00", end)

    private fun payment(id: Long, day: String, hour: Int = 19, amount: Double = 10.0,
                        platform: String = "WECHAT", store: Long = 5L) =
        CustomerPaymentRecord(
            id = id, platform = platform,
            tradeTime = "$day ${hour.toString().padStart(2, '0')}:10:00",
            businessDate = day, amount = amount, refundAmount = 0.0,
            netAmount = amount, customerKey = "user-$id", customerName = "匿名",
            identityConfidence = "LOW", transactionType = "收款",
            tradeStatus = "成功", storeId = store, storeName = "游乐场"
        )

    private val now = LocalDateTime.parse("2026-10-23T06:00:00")
    private fun request(mode: LocationCompareMode, anchor: String = "2026-10-21",
                        reference: String = "2026-10-14", count: Int = 4) =
        LocationManualRequest(mode, anchor, reference, "2026-10-15", "2026-10-21",
            "2026-10-08", "2026-10-14", count)

    @Test fun selectedBusinessDayNavigationSkipsGaps() {
        val dates = setOf(LocalDate.parse("2026-10-14"), LocalDate.parse("2026-10-21"))
        val anchor = LocalDate.parse("2026-10-21")
        assertEquals(LocalDate.parse("2026-10-14"),
            CustomerBusinessDayNavigation.shift(anchor, -1, dates, true))
        assertEquals(LocalDate.parse("2026-10-20"),
            CustomerBusinessDayNavigation.shift(anchor, -1, dates, false))
        assertEquals(anchor, CustomerBusinessDayNavigation.shift(anchor, 1, dates, true))
    }

    @Test fun singleDayComparisonRespectsStoreAndCalendarDays() {
        val days = listOf(session("2026-10-14"), session("2026-10-21"))
        val rows = listOf(payment(1, "2026-10-14", amount = 10.0),
            payment(2, "2026-10-21", amount = 20.0),
            payment(3, "2026-10-21", amount = 500.0, store = 6))
        val report = CustomerLocationManualAnalyzer.analyze(
            rows, days, 5, "ALL", request(LocationCompareMode.DAY), now)
        assertTrue(report.comparable)
        assertEquals(20.0, report.currentAmount!!, 0.001)
        assertEquals(10.0, report.referenceAmount!!, 0.001)
        assertEquals(1.0, report.hours.first { it.hour == 19 }.currentCount, 0.001)
    }

    @Test fun differentSellingHoursUseOnlyFinishedIntersection() {
        val days = listOf(session("2026-10-14", "20:00"), session("2026-10-21", "24:00"))
        val rows = listOf(payment(1, "2026-10-14", hour = 19),
            payment(2, "2026-10-21", hour = 19),
            payment(3, "2026-10-21", hour = 21, amount = 1000.0))
        val report = CustomerLocationManualAnalyzer.analyze(
            rows, days, 5, "ALL", request(LocationCompareMode.DAY), now)
        assertEquals(listOf(16, 17, 18, 19), report.comparableHours)
        assertEquals(10.0, report.currentAmount!!, 0.001)
    }

    @Test fun missingReceiptsAndPlatformMismatchDoNotClaimChange() {
        val days = listOf(session("2026-10-14"), session("2026-10-21"))
        val missing = CustomerLocationManualAnalyzer.analyze(
            listOf(payment(1, "2026-10-21")), days, 5, "ALL",
            request(LocationCompareMode.DAY), now)
        assertFalse(missing.comparable)
        assertNull(missing.referenceCount)
        assertTrue(missing.warnings.any { it.contains("缺少") })
        val mixed = CustomerLocationManualAnalyzer.analyze(
            listOf(payment(1, "2026-10-21", platform = "WECHAT"),
                payment(2, "2026-10-14", platform = "ALIPAY")),
            days, 5, "ALL", request(LocationCompareMode.DAY), now)
        assertFalse(mixed.comparable)
        assertTrue(mixed.warnings.any { it.contains("渠道") })
    }

    @Test fun periodsAreDailyAveragesNotRawTotals() {
        val sessions = listOf(session("2026-10-14"), session("2026-10-15"),
            session("2026-10-19"), session("2026-10-21"))
        val rows = listOf(payment(1, "2026-10-14", amount = 10.0),
            payment(2, "2026-10-15", amount = 30.0),
            payment(3, "2026-10-19", amount = 20.0),
            payment(4, "2026-10-21", amount = 40.0))
        val r = CustomerLocationManualAnalyzer.analyze(rows, sessions, 5, "ALL",
            request(LocationCompareMode.RANGE), now)
        assertTrue(r.comparable)
        assertEquals(30.0, r.currentAmount!!, 0.001)
        assertEquals(20.0, r.referenceAmount!!, 0.001)
        assertEquals(2, r.currentObserved)
        assertEquals(2, r.referenceObserved)
    }

    @Test fun sessionModeUsesPriorDisjointTradingDays() {
        val sessions = listOf("2026-10-07", "2026-10-10", "2026-10-14", "2026-10-21")
            .map { session(it) }
        val rows = sessions.mapIndexed { i, day -> payment((i + 1).toLong(), day.date) }
        val r = CustomerLocationManualAnalyzer.analyze(rows, sessions, 5, "WECHAT",
            request(LocationCompareMode.SESSIONS, count = 2), now)
        assertEquals(listOf("2026-10-14", "2026-10-21"), r.currentDates)
        assertEquals(listOf("2026-10-07", "2026-10-10"), r.referenceDates)
        assertTrue(r.comparable)
    }
}
