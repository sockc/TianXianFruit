package com.tianxian.fruit.sync

import com.tianxian.fruit.data.OvernightReceiptSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomerManualSyncPolicyTest {
    private val original = OvernightReceiptSnapshot(
        calendarDate = "2026-11-16",
        storeId = 0L,
        partnerId = 8L,
        wechat = 78.25,
        alipay = 12.0,
        cash = 5.0,
        wechatRecorded = true,
        alipayRecorded = true,
        cashRecorded = true
    )

    @Test fun ignoresOnlyInertTransportStore() {
        val fromCloud = original.copy(storeId = 301L)
        assertTrue(CustomerManualSyncPolicy.sameOvernightBusinessValues(original, fromCloud))
        assertTrue(CustomerManualSyncPolicy.sameOvernightBusinessValues(fromCloud, original))
    }

    @Test fun doesNotIgnoreAmountOrRecordedFlags() {
        assertFalse(CustomerManualSyncPolicy.sameOvernightBusinessValues(
            original, original.copy(wechat = 78.26)))
        assertFalse(CustomerManualSyncPolicy.sameOvernightBusinessValues(
            original, original.copy(cashRecorded = false)))
        assertFalse(CustomerManualSyncPolicy.sameOvernightBusinessValues(
            original, original.copy(alipayRecorded = false)))
    }

    @Test fun identityUsesCalendarDateAndCollector() {
        assertFalse(CustomerManualSyncPolicy.sameOvernightBusinessValues(
            original, original.copy(calendarDate = "2026-11-17")))
        assertFalse(CustomerManualSyncPolicy.sameOvernightBusinessValues(
            original, original.copy(partnerId = 9L)))
    }
}
