package com.tianxian.fruit.data

import org.junit.Assert.*
import org.junit.Test

class OvernightReceiptDeletionPolicyTest {
    private fun morning(date: String, store: Long, partner: Long = 5L) =
        OvernightReceiptSnapshot(
            calendarDate = date, storeId = store, partnerId = partner,
            wechat = 120.0, alipay = 65.0, cash = 20.0,
            wechatRecorded = true, alipayRecorded = true, cashRecorded = true
        )

    @Test fun deletingFourteenthClearsFifteenthMorningNotFourteenthMorning() {
        val previous = morning("2026-11-14", 1L)
        val target = morning("2026-11-15", 1L)
        val following = morning("2026-11-16", 1L)
        assertEquals(previous,
            OvernightReceiptDeletionPolicy.clearedSnapshot(previous, "2026-11-14", 1L))
        assertEquals(following,
            OvernightReceiptDeletionPolicy.clearedSnapshot(following, "2026-11-14", 1L))
        val cleared = OvernightReceiptDeletionPolicy.clearedSnapshot(target, "2026-11-14", 1L)
        assertNull(cleared.amount("WECHAT"))
        assertNull(cleared.amount("ALIPAY"))
        assertNull(cleared.amount("CASH"))
        assertEquals(0.0, cleared.wechat, 0.001)
        assertEquals(0.0, cleared.alipay, 0.001)
        assertEquals(0.0, cleared.cash, 0.001)
    }

    @Test fun deletionDoesNotClearDifferentStoreOrCollectorOnSameMorning() {
        val otherStore = morning("2026-11-15", 2L, 5L)
        val differentDay = morning("2026-11-16", 1L, 6L)
        assertEquals(otherStore,
            OvernightReceiptDeletionPolicy.clearedSnapshot(otherStore, "2026-11-14", 1L))
        assertEquals(differentDay,
            OvernightReceiptDeletionPolicy.clearedSnapshot(differentDay, "2026-11-14", 1L))
    }

    @Test fun deletionResetsNaturalDaySourceAndSavedNetButNotDifferentDay() {
        val original = NaturalDayReceiptDraft(
            businessDate = "2026-11-14", storeId = 1L, partnerId = 5L,
            wechat = 1000.0, alipay = 400.0, cash = 30.0,
            deductWechat = false, deductAlipay = false, deductCash = false,
            savedWechat = 880.0, savedAlipay = 335.0, savedCash = 10.0
        )
        val cleared = OvernightReceiptDeletionPolicy.clearedNatural(
            original, "2026-11-14", 1L)
        assertEquals(0.0, cleared.wechat, 0.001)
        assertEquals(0.0, cleared.alipay, 0.001)
        assertEquals(0.0, cleared.cash, 0.001)
        assertNull(cleared.savedWechat)
        assertNull(cleared.savedAlipay)
        assertNull(cleared.savedCash)
        assertTrue(cleared.deductWechat)
        assertTrue(cleared.deductAlipay)
        assertTrue(cleared.deductCash)
        assertEquals(original,
            OvernightReceiptDeletionPolicy.clearedNatural(original, "2026-11-13", 1L))
        assertEquals(original,
            OvernightReceiptDeletionPolicy.clearedNatural(original, "2026-11-14", 2L))
    }

    @Test fun blankTombstoneIsUnrecordedAndBelongsToNextCalendarMorning() {
        val blank = OvernightReceiptDeletionPolicy.emptySnapshot("2026-11-14", 1L, 5L)!!
        assertEquals("2026-11-15", blank.calendarDate)
        assertEquals(1L, blank.storeId)
        assertEquals(5L, blank.partnerId)
        assertNull(blank.amount("WECHAT"))
        assertNull(blank.amount("ALIPAY"))
        assertNull(blank.amount("CASH"))
        assertNull(OvernightReceiptDeletionPolicy.emptySnapshot("invalid", 1L, 5L))
    }

    @Test fun emptyStateRemainsEmptyWhenAppliedRepeatedly() {
        val once = OvernightReceiptDeletionPolicy.clearedSnapshot(
            morning("2026-11-15", 1L), "2026-11-14", 1L)
        assertEquals(once,
            OvernightReceiptDeletionPolicy.clearedSnapshot(once, "2026-11-14", 1L))
    }
}
