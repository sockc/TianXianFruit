package com.tianxian.fruit.data

import org.junit.Assert.*
import org.junit.Test

class OvernightReceiptPolicyTest {
    private fun morning(date: String, store: Long = 1, partner: Long = 5,
        wx: Double = 0.0, ali: Double = 0.0, cash: Double = 0.0) =
        OvernightReceiptSnapshot(date, store, partner, wx, ali, cash, cashRecorded = true)
    private fun day(date: String = "2026-11-15", store: Long = 1,
        wx: Double = 2800.0, ali: Double = 1400.0, cash: Double = 200.0,
        deductWechat: Boolean = true, deductAlipay: Boolean = true,
        deductCash: Boolean = true) =
        NaturalDayReceiptDraft(date, store, 5L, wx, ali, cash,
            deductWechat, deductAlipay, deductCash = deductCash)

    @Test fun crossMidnightAddsNextMorningAndSubtractsPriorOneOnce() {
        val m=listOf(morning("2026-11-15", 2, wx=180.0,ali=85.0,cash=0.0),
            morning("2026-11-16", 1, wx=120.0,ali=65.0,cash=20.0))
        val result=OvernightReceiptPolicy.adjustment(day(),m)!!
        assertEquals(2740.0,result.adjustedWechat,0.001)
        assertEquals(1380.0,result.adjustedAlipay,0.001)
        assertEquals(220.0,result.adjustedCash,0.001)
        assertTrue(result.fullyConfirmed)
    }
    @Test fun disableWechatDeductionDoesNotDisableAlipay() {
        val m=listOf(morning("2026-11-15", 2,wx=180.0,ali=85.0),
            morning("2026-11-16", 1,wx=120.0,ali=0.0))
        val result=OvernightReceiptPolicy.adjustment(day(deductWechat=false),m)!!
        assertEquals(2920.0,result.adjustedWechat,0.001)
        assertEquals(1315.0,result.adjustedAlipay,0.001)
    }
    @Test fun doesNotCreditOtherStoreFromFollowingMorning() {
        val result=OvernightReceiptPolicy.adjustment(day(),
            listOf(morning("2026-11-15",2,wx=180.0),
                morning("2026-11-16",3,wx=120.0)))!!
        assertEquals(2620.0,result.adjustedWechat,0.001)
        assertFalse(result.followingConfirmed)
    }
    @Test fun sameCollectorMorningCannotBelongToTwoStores() {
        val first=morning("2026-11-16",1,wx=150.0)
        assertNull(OvernightReceiptPolicy.replace(morning("2026-11-16",2,wx=150.0),listOf(first)))
        val changed=OvernightReceiptPolicy.replace(morning("2026-11-16",1,wx=175.0),listOf(first))!!
        assertEquals(1,changed.size)
        assertEquals(175.0,changed.single().wechat,0.001)
    }
    @Test fun blankAndConfirmedZeroAreDistinguished() {
        val empty=OvernightReceiptSnapshot("2026-11-16",1,5,wechatRecorded=false,
            alipayRecorded=false,cashRecorded=false)
        assertNull(empty.amount("WECHAT"))
        val confirmed=empty.withAmount("WECHAT",0.0)
        assertEquals(0.0,confirmed.amount("WECHAT")!!,0.0001)
        assertNull(confirmed.amount("CASH"))
    }
    @Test fun editingOneChannelDoesNotChangeOtherChannels() {
        val first=morning("2026-11-16",wx=100.0,ali=30.0,cash=5.0)
        val change=first.withAmount("WECHAT",200.0)
        assertEquals(30.0,change.alipay,0.001)
        assertEquals(5.0,change.cash,0.001)
        assertEquals(200.0,change.wechat,0.001)
    }
    @Test fun calendarDayIsExactAndNeverSearchesPreviousBusinessDay() {
        val saved=listOf(
            morning("2026-11-14",2,wx=90.0),
            morning("2026-11-15",2,wx=180.0),
            morning("2026-11-16",1,wx=120.0))
        val actual=OvernightReceiptPolicy.adjustment(day("2026-11-15"),saved)!!
        assertEquals(2740.0,actual.adjustedWechat,0.001)
        assertEquals(180.0,actual.previousWechat,0.001)
        // 14 Nov 00:00 (which belongs to Nov 13) must never be deducted.
        val no14=OvernightReceiptPolicy.adjustment(day("2026-11-15"),
            listOf(saved[0], saved[2]))!!
        assertEquals(2920.0,no14.adjustedWechat,0.001)
    }
    @Test fun previousDayCashDefaultsToDeductAndCanBeTurnedOff() {
        val saved=listOf(morning("2026-11-15",2,cash=15.0),
            morning("2026-11-16",1,cash=20.0))
        assertEquals(205.0,OvernightReceiptPolicy.adjustment(day(),saved)!!.adjustedCash,0.001)
        assertEquals(220.0,OvernightReceiptPolicy.adjustment(
            day(deductCash=false),saved)!!.adjustedCash,0.001)
    }
    @Test fun manualMorningWithNoMainAmountUpdatesEffectiveReceipt() {
        val saved=listOf(morning("2026-11-16",1,wx=120.0,ali=65.0,cash=20.0))
        val result=OvernightReceiptPolicy.adjustment(day(wx=0.0,ali=0.0,cash=0.0),saved)!!
        assertEquals(205.0,result.adjustedTotal,0.001)
    }
    @Test fun previousMorningFromNonAdjacentSellingDayMustNotBeUsed() {
        val saved=listOf(morning("2026-11-14",1,wx=500.0))
        val result=OvernightReceiptPolicy.adjustment(day("2026-11-15"),saved)!!
        assertEquals(2800.0,result.adjustedWechat,0.001)
        assertEquals(0.0,result.previousWechat,0.001)
    }
    @Test fun negativeAdjustmentIsRejected() {
        assertNull(OvernightReceiptPolicy.adjustment(day(wx=10.0),
            listOf(morning("2026-11-15",2,wx=20.0))))
    }
    @Test fun sourceCalendarTotalsNeverMutateOrDoubleAdjust() {
        val source=day()
        val m=listOf(morning("2026-11-15",2,wx=100.0),
            morning("2026-11-16",1,wx=50.0))
        val a=OvernightReceiptPolicy.adjustment(source,m)!!
        val b=OvernightReceiptPolicy.adjustment(source,m)!!
        assertEquals(a,b)
        assertEquals(2750.0,a.adjustedWechat,0.001)
        assertEquals(2800.0,source.wechat,0.001)
    }
}
