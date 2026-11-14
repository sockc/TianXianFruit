package com.tianxian.fruit.data

import org.junit.Assert.*
import org.junit.Test

class OvernightReceiptPolicyTest {
    private fun item(date: String, store: Long = 1L, partner: Long = 5L,
                     wx: Double = 0.0, ali: Double = 0.0) =
        OvernightReceiptSnapshot(date, store, partner, wx, ali)
    private fun draft(date: String = "2026-11-15", store: Long = 1L,
                      wx: Double = 2800.0, ali: Double = 1400.0) =
        NaturalDayReceiptDraft(date, store, 5L, wx, ali)

    @Test fun calendarTotalSubtractsYesterdayShiftAndAddsNextMorning() {
        val result=OvernightReceiptPolicy.adjustment(draft(),
            listOf(item("2026-11-15",store=2,wx=180.0,ali=85.0),
                item("2026-11-16",store=1,wx=120.0,ali=65.0)))!!
        assertEquals(2740.0,result.adjustedWechat,0.001)
        assertEquals(1380.0,result.adjustedAlipay,0.001)
        assertTrue(result.fullyConfirmed)
    }

    @Test fun snapshotForOtherStoreDoesNotCreditThisStore() {
        val result=OvernightReceiptPolicy.adjustment(draft(),
            listOf(item("2026-11-15",store=2,wx=180.0),
                item("2026-11-16",store=3,wx=120.0)))!!
        assertEquals(2620.0,result.adjustedWechat,0.001)
        assertFalse(result.followingConfirmed)
    }

    @Test fun sameAccountCannotBeAssignedToTwoStoresOnOneMorning() {
        val first=item("2026-11-16",store=1,wx=150.0)
        assertNull(OvernightReceiptPolicy.replace(item("2026-11-16",store=2,wx=150.0),listOf(first)))
        val replaced=OvernightReceiptPolicy.replace(item("2026-11-16",store=1,wx=175.0),
            listOf(first))!!
        assertEquals(1,replaced.size)
        assertEquals(175.0,replaced.single().wechat,0.001)
    }

    @Test fun zeroConfirmationIsDifferentFromMissingSnapshot() {
        val result=OvernightReceiptPolicy.adjustment(draft(),
            listOf(item("2026-11-15"),item("2026-11-16")))!!
        assertTrue(result.fullyConfirmed)
        assertEquals(2800.0,result.adjustedWechat,0.001)
    }

    @Test fun negativeResultIsRejectedRatherThanSavedAsSales() {
        assertNull(OvernightReceiptPolicy.adjustment(draft(wx=10.0),
            listOf(item("2026-11-15",wx=20.0))))
    }

    @Test fun editsPreserveOriginalNaturalTotalsAndCalculationIsIdempotent() {
        val snapshots=listOf(item("2026-11-15",wx=100.0),
            item("2026-11-16",wx=50.0))
        val first=OvernightReceiptPolicy.adjustment(draft(),snapshots)!!
        val again=OvernightReceiptPolicy.adjustment(draft(),snapshots)!!
        assertEquals(first,again)
        assertEquals(2750.0,first.adjustedWechat,0.001)
    }
}
