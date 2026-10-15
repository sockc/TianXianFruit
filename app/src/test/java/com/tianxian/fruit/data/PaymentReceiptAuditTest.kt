package com.tianxian.fruit.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentReceiptAuditTest {
    private fun receipt(
        id: Long, time: String, store: Long = 1L,
        ref: String = "order-$id", platform: String = "WECHAT",
        amount: Double = 16.0, refund: Double = 0.0
    ) = CustomerPaymentRecord(
        id = id, platform = platform, tradeTime = time,
        businessDate = time.take(10), amount = amount,
        refundAmount = refund, netAmount = amount - refund,
        customerKey = "", customerName = "匿名",
        identityConfidence = "LOW", transactionType = "经营收款",
        tradeStatus = "成功", storeId = store,
        storeName = if (store == 1L) "九龙公园" else "",
        transactionRef = ref
    )

    private fun shift(day: String, store: Long = 1L) =
        CustomerTrafficSession(day, store, "九龙公园", "16:00", "24:00")

    @Test fun flagsUnmatchedButDoesNotCountThemAsVerifiedStorePayments() {
        val rows = listOf(
            receipt(1, "2026-10-14 18:00:00", store = 0L),
            receipt(2, "2026-10-14 18:01:00")
        )
        val audit = PaymentReceiptAuditor.inspect(rows, listOf(shift("2026-10-14")))
        assertEquals(1, audit.unmatchedPayments)
        assertEquals(0, audit.sessionsWithoutMatchedReceipts.size)
        assertTrue(audit.needsReview)
    }

    @Test fun detectsMissingSessionEvidenceWithoutInventingZeroSales() {
        val audit = PaymentReceiptAuditor.inspect(
            listOf(receipt(1, "2026-10-14 18:01:00")),
            listOf(shift("2026-10-14"), shift("2026-10-15")),
            startDate = "2026-10-14", endDate = "2026-10-15"
        )
        assertEquals(listOf("2026-10-15 · 九龙公园"), audit.sessionsWithoutMatchedReceipts)
    }

    @Test fun repeatedPositiveTransactionReferenceIsReviewOnlyNotDeleted() {
        val rows = listOf(
            receipt(1, "2026-10-14 18:00:00", ref = "same"),
            receipt(2, "2026-10-14 18:01:00", ref = "same"),
            receipt(3, "2026-10-14 18:02:00", ref = "same", platform = "ALIPAY"),
            receipt(4, "2026-10-14 19:00:00", ref = "same", amount = 0.0, refund = 6.0)
        )
        val audit = PaymentReceiptAuditor.inspect(rows, listOf(shift("2026-10-14")))
        assertEquals(1, audit.repeatedTransactionRefs)
        assertEquals(1, audit.refundRows)
    }

    @Test fun flagsSavedStoreOutsideShiftButDoesNotReattributeIt() {
        val audit = PaymentReceiptAuditor.inspect(
            listOf(receipt(1, "2026-10-14 09:05:00")),
            listOf(shift("2026-10-14"))
        )
        assertEquals(1, audit.assignedOutsideSavedWindow)
        assertTrue(audit.needsReview)
    }

    @Test fun emptyInputWithoutSessionsHasNoFalseReview() {
        val audit = PaymentReceiptAuditor.inspect(emptyList(), emptyList())
        assertEquals(0, audit.unmatchedPayments)
        assertFalse(audit.needsReview)
    }

    @Test fun selectedStoreNeverInheritsOtherStoresRows() {
        val audit = PaymentReceiptAuditor.inspect(
            listOf(receipt(1, "2026-10-14 18:00:00", store = 2L)),
            listOf(shift("2026-10-14",1)),
            storeId = 1L
        )
        assertEquals(listOf("2026-10-14 · 九龙公园"), audit.sessionsWithoutMatchedReceipts)
    }
}
