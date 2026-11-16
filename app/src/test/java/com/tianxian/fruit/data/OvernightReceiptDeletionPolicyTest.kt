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

    @Test fun deletingSaleNeverInvalidatesCollectorMorningMoney() {
        val morning = morning("2026-11-15", 1L)
        assertEquals(120.0, morning.amount("WECHAT")!!, 0.001)
        assertEquals(65.0, morning.amount("ALIPAY")!!, 0.001)
        // Deletion policy deliberately exposes no clearedSnapshot method.
        // A deleted store can only clear its position-owned natural draft.
        val draft=NaturalDayReceiptDraft("2026-11-14", 1L, 5L, 1000.0)
        OvernightReceiptDeletionPolicy.clearedNatural(draft, "2026-11-14", 1L)
        assertEquals(120.0, morning.amount("WECHAT")!!, 0.001)
    }

    @Test fun deletedStoreClearsNaturalDraftOnlyForSameDateAndPosition() {
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
        assertTrue(OvernightReceiptDeletionPolicy.isClearedNatural(cleared))
        assertEquals(original,
            OvernightReceiptDeletionPolicy.clearedNatural(original, "2026-11-13", 1L))
        assertEquals(original,
            OvernightReceiptDeletionPolicy.clearedNatural(original, "2026-11-14", 2L))
    }

    @Test fun sharedCollectorDeductionIsOwnedByOnlyOneActivePosition() {
        val records = listOf(1L to 5L, 2L to 5L, 1L to 6L)
        val source = NaturalDayReceiptDraft(
            businessDate = "2026-11-16", storeId = 1L, partnerId = 5L,
            wechat = 1000.0
        )
        assertTrue(OvernightReceiptAllocationPolicy.ownsAdjustment(
            "2026-11-16", 1L, 5L, listOf(source), records))
        assertFalse(OvernightReceiptAllocationPolicy.ownsAdjustment(
            "2026-11-16", 2L, 5L, listOf(source), records))
        assertTrue(OvernightReceiptAllocationPolicy.ownsAdjustment(
            "2026-11-16", 1L, 6L, listOf(source), records))
    }

    @Test fun newPositionDoesNotDeductAgainWhenCollectorAlreadySoldElsewhere() {
        val existing = listOf(1L to 5L)
        assertFalse(OvernightReceiptAllocationPolicy.ownsAdjustment(
            "2026-11-16", 2L, 5L, emptyList(), existing))
        assertTrue(OvernightReceiptAllocationPolicy.ownsAdjustment(
            "2026-11-16", 2L, 6L, emptyList(), existing))
    }

    @Test fun staleDeletedLocationSourceDoesNotBlockValidNewOwner() {
        val old = NaturalDayReceiptDraft("2026-11-16", 1L, 5L, 100.0)
        assertTrue(OvernightReceiptAllocationPolicy.ownsAdjustment(
            "2026-11-16", 2L, 5L, listOf(old), emptyList()))
        val cleared = OvernightReceiptDeletionPolicy.clearedNatural(old,"2026-11-16",1L)
        assertTrue(OvernightReceiptAllocationPolicy.ownsAdjustment(
            "2026-11-16", 2L, 5L, listOf(cleared), emptyList()))
    }

    @Test fun confirmedZeroAndEmptyMorningAreDifferent() {
        val untouched=morning("2026-11-17",1L).withAmount("WECHAT",null)
        val zero=untouched.withAmount("WECHAT",0.0)
        assertNull(untouched.amount("WECHAT"))
        assertEquals(0.0,zero.amount("WECHAT")!!,0.0)
    }
}
