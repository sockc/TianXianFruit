package com.tianxian.fruit.data

import org.junit.Assert.*
import org.junit.Test

class OvernightReceiptAuditPolicyTest {
    private val day = "2026-11-16"
    private fun morning(date: String, wx: Double, partner: Long = 5L) =
        OvernightReceiptSnapshot(date, 1L, partner, wx,
            wechatRecorded = true, alipayRecorded = true)
    private fun sale(store: Long, wx: Double, partner: Long = 5L) =
        OvernightCollectorSale(store, partner, wx, 0.0, 0.0)
    private fun original(store: Long = 1L, wx: Double = 1120.0) =
        NaturalDayReceiptDraft(day, store, 5L, wechat = wx, alipay = 0.0,
            cash = 0.0, savedWechat = wx - 47.0,
            savedAlipay = 0.0, savedCash = 0.0)

    @Test fun cleanSinglePositionProducesNoFalseAlarm() {
        val issues = OvernightReceiptAuditPolicy.inspect(
            day, listOf(sale(1L, 1073.0)), listOf(morning(day, 47.0)),
            listOf(original()), emptySet())
        assertTrue(issues.isEmpty())
    }

    @Test fun lateMorningChangeTriggersReviewAndDifferenceWithoutMutatingLedger() {
        val before = sale(1L, 1073.0)
        val issues = OvernightReceiptAuditPolicy.inspect(
            day, listOf(before), listOf(morning(day, 60.0)),
            listOf(original()), setOf(5L))
        assertTrue(issues.any { it.reason == OvernightReviewReason.MORNING_CHANGED })
        assertTrue(issues.any { it.reason == OvernightReviewReason.SAVED_TOTAL_DIFFERS })
        assertEquals(1073.0, before.wechat, 0.001)
        assertEquals(1060.0, OvernightReceiptPolicy.adjustment(
            original(), listOf(morning(day, 60.0)))!!.adjustedWechat, 0.001)
    }

    @Test fun deletingDeductionOwnerLeavesRemainingPositionForManualReview() {
        val removed = OvernightReceiptDeletionPolicy.clearedNatural(original(), day, 1L)
        val issues = OvernightReceiptAuditPolicy.inspect(
            day, listOf(sale(2L, 300.0)), listOf(morning(day, 47.0)),
            listOf(removed), setOf(5L))
        assertTrue(issues.any { it.reason == OvernightReviewReason.DELETED_OWNER })
        assertTrue(issues.any { it.reason == OvernightReviewReason.MORNING_CHANGED })
        assertEquals(300.0, sale(2L, 300.0).wechat, 0.001)
    }

    @Test fun twoPositionsOneCollectorFlagPotentialOfflineDuplicate() {
        val issues = OvernightReceiptAuditPolicy.inspect(
            day, listOf(sale(1L, 1073.0), sale(2L, 300.0)),
            listOf(morning(day, 47.0)), listOf(original()), emptySet())
        assertTrue(issues.any { it.reason == OvernightReviewReason.SHARED_COLLECTOR })
        assertFalse(issues.any { it.reason == OvernightReviewReason.SAVED_TOTAL_DIFFERS })
    }

    @Test fun anotherCollectorIsNeverAssignedPreviousOnesMorning() {
        val issues = OvernightReceiptAuditPolicy.inspect(
            day, listOf(sale(1L, 100.0, 6L)),
            listOf(morning(day, 47.0, 5L)), emptyList(), emptySet())
        assertTrue(issues.isEmpty())
    }

    @Test fun invalidDateDoesNotCrashAudit() {
        assertTrue(OvernightReceiptAuditPolicy.inspect(
            "not-a-date", listOf(sale(1L, 500.0)), emptyList(),
            emptyList(), setOf(5L)).isEmpty())
    }

    @Test fun followingMorningIsAddedToPriorBusinessDate() {
        val original = NaturalDayReceiptDraft(
            day, 1L, 5L, wechat = 100.0, savedWechat = 120.0)
        val following = morning("2026-11-17", 20.0)
        val actual = sale(1L, 120.0)
        assertTrue(OvernightReceiptAuditPolicy.inspect(
            day, listOf(actual), listOf(following), listOf(original),
            emptySet()).isEmpty())
        assertTrue(OvernightReceiptAuditPolicy.inspect(
            day, listOf(actual), listOf(morning("2026-11-17", 30.0)),
            listOf(original), emptySet()).any {
                it.reason == OvernightReviewReason.SAVED_TOTAL_DIFFERS
            })
    }
}
