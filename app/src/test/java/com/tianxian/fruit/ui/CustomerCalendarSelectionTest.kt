package com.tianxian.fruit.ui

import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Test

class CustomerCalendarSelectionTest {
    @Test fun oneMonthIncludesFirstAndLastDayEvenCurrentMonth() {
        val month = CustomerCalendarSelection.month(YearMonth.of(2026, 10))
        assertEquals(CustomerCalendarChoice.Kind.MONTH, month.kind)
        assertEquals(LocalDate.of(2026, 10, 1), month.start)
        assertEquals(LocalDate.of(2026, 10, 31), month.end)
    }

    @Test fun februaryThenMayCoversFourCompleteMonths() {
        val range = CustomerCalendarSelection.month(
            YearMonth.of(2026, 2), YearMonth.of(2026, 5)
        )
        assertEquals(CustomerCalendarChoice.Kind.MONTHS, range.kind)
        assertEquals(LocalDate.of(2026, 2, 1), range.start)
        assertEquals(LocalDate.of(2026, 5, 31), range.end)
    }

    @Test fun selectingReverseOrderWorksAndLeapFebruaryIsIncluded() {
        val range = CustomerCalendarSelection.month(
            YearMonth.of(2028, 5), YearMonth.of(2028, 2)
        )
        assertEquals(LocalDate.of(2028, 2, 1), range.start)
        assertEquals(LocalDate.of(2028, 5, 31), range.end)
    }

    @Test fun selectingSpecificDayIsExactlyThatDay() {
        val selected = LocalDate.of(2026, 10, 13)
        val choice = CustomerCalendarSelection.day(selected)
        assertEquals(CustomerCalendarChoice.Kind.DAY, choice.kind)
        assertEquals(selected, choice.start)
        assertEquals(selected, choice.end)
    }

    @Test fun canSelectMonthsAcrossCalendarYears() {
        val range = CustomerCalendarSelection.month(
            YearMonth.of(2025, 11), YearMonth.of(2026, 2)
        )
        assertEquals(LocalDate.of(2025, 11, 1), range.start)
        assertEquals(LocalDate.of(2026, 2, 28), range.end)
    }
}
