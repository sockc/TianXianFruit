package com.tianxian.fruit.data

import org.junit.Assert.*
import org.junit.Test

class StoreBusinessHoursPolicyTest {
    @Test fun closesNextMorningWithoutChangingBusinessDate() {
        assertTrue(StoreBusinessHoursPolicy.valid("15:00", "01:30"))
        assertTrue(StoreBusinessHoursPolicy.endsNextDay("15:00", "01:30"))
        assertEquals(630, StoreBusinessHoursPolicy.durationMinutes("15:00", "01:30"))
    }

    @Test fun normalSameDayAndMidnightCloseStillWork() {
        assertTrue(StoreBusinessHoursPolicy.valid("16:00", "23:30"))
        assertFalse(StoreBusinessHoursPolicy.endsNextDay("16:00", "23:30"))
        assertEquals(450, StoreBusinessHoursPolicy.durationMinutes("16:00", "23:30"))
        assertTrue(StoreBusinessHoursPolicy.valid("16:00", "24:00"))
        assertEquals(480, StoreBusinessHoursPolicy.durationMinutes("16:00", "24:00"))
        assertFalse(StoreBusinessHoursPolicy.endsNextDay("16:00", "24:00"))
    }

    @Test fun sameTimeAndInvalidTimesRemainBlocked() {
        assertFalse(StoreBusinessHoursPolicy.valid("15:00", "15:00"))
        assertFalse(StoreBusinessHoursPolicy.valid("24:00", "01:30"))
        assertFalse(StoreBusinessHoursPolicy.valid("15:00", "25:00"))
        assertFalse(StoreBusinessHoursPolicy.valid("15:00", "01:60"))
        assertFalse(StoreBusinessHoursPolicy.valid("bad", "01:30"))
        assertFalse(StoreBusinessHoursPolicy.valid("01:30", "bad"))
    }

    @Test fun nextDayEndMayBeAfterMidnightForAnyStartTime() {
        assertEquals(120, StoreBusinessHoursPolicy.durationMinutes("23:30", "01:30"))
        assertTrue(StoreBusinessHoursPolicy.endsNextDay("23:30", "01:30"))
        assertEquals(1440, StoreBusinessHoursPolicy.durationMinutes("00:00", "24:00"))
    }
}
