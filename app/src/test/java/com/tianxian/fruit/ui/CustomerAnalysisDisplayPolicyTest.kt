package com.tianxian.fruit.ui

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class CustomerAnalysisDisplayPolicyTest {
    private val day = LocalDate.of(2026, 10, 15)

    @Test fun dayLabelNeverDropsDayOrWeekday() {
        val label = CustomerAnalysisDisplayPolicy.dateLabel(
            "TODAY", day, day.withDayOfMonth(1), day, day)
        assertEquals("2026年10月15日 周四", label)
    }

    @Test fun monthLabelKeepsYearAndMonth() {
        assertEquals("2026年10月",
            CustomerAnalysisDisplayPolicy.dateLabel(
                "THIS_MONTH", day, day.withDayOfMonth(1), day, day))
    }

    @Test fun customRangeShowsBothDatesWithYears() {
        assertEquals("2026/10/1—2026/10/15",
            CustomerAnalysisDisplayPolicy.dateLabel(
                "CUSTOM", day, day, LocalDate.of(2026, 10, 1), day))
        assertEquals("2025年12月—2026年2月",
            CustomerAnalysisDisplayPolicy.dateLabel(
                "CUSTOM", day, day, LocalDate.of(2025, 12, 1),
                LocalDate.of(2026, 2, 28)))
    }

    @Test fun customSingleDayShowsActualDate() {
        assertEquals("2026年10月15日 周四",
            CustomerAnalysisDisplayPolicy.dateLabel(
                "CUSTOM", day, day, day, day))
    }

    @Test fun hourlyContextAlwaysHasDateStoreAndChannel() {
        assertEquals("九龙公园 · 2026/10/15 · 合并",
            CustomerAnalysisDisplayPolicy.detailContext(day, "九龙公园", "合并", ""))
        assertEquals("全部位置 · 2026/10/15 · 微信 · 收款人甲",
            CustomerAnalysisDisplayPolicy.detailContext(day, "", "微信", "收款人甲"))
    }

    @Test fun sampleLimitAndZeroBaselineNeverProduceMisleadingPercent() {
        assertNull(CustomerAnalysisDisplayPolicy.percentageChange(25.0, 20.0, 2))
        assertNull(CustomerAnalysisDisplayPolicy.percentageChange(25.0, 0.0, 5))
        assertNull(CustomerAnalysisDisplayPolicy.percentageChange(25.0, null, 5))
        assertEquals("-20.0%",
            CustomerAnalysisDisplayPolicy.percentageChange(80.0, 100.0, 4))
        assertEquals("+20.0%",
            CustomerAnalysisDisplayPolicy.percentageChange(120.0, 100.0, 4))
    }
}
