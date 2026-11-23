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
    @Test fun businessFingerprintIgnoresTransportStore() {
        val first = CustomerManualSyncPolicy.overnightBusinessFingerprint(
            original, "stable-collector-1"
        )
        // A different transport store is not a manual customer edit.
        val second = CustomerManualSyncPolicy.overnightBusinessFingerprint(
            original.copy(storeId = 301L), "stable-collector-1"
        )
        assertTrue(first == second)
    }

    @Test fun businessFingerprintProtectsAmountsAndFlags() {
        val original = CustomerManualSyncPolicy.overnightBusinessFingerprint(
            "2026-11-16", "stable-collector-1", 78.25, 12.0, 5.0,
            true, true, true
        )
        val changedMoney = CustomerManualSyncPolicy.overnightBusinessFingerprint(
            "2026-11-16", "stable-collector-1", 78.26, 12.0, 5.0,
            true, true, true
        )
        val changedFlag = CustomerManualSyncPolicy.overnightBusinessFingerprint(
            "2026-11-16", "stable-collector-1", 78.25, 12.0, 5.0,
            true, true, false
        )
        assertFalse(original == changedMoney)
        assertFalse(original == changedFlag)
    }

    @Test fun businessFingerprintNormalizesSignedZero() {
        val positive = CustomerManualSyncPolicy.overnightBusinessFingerprint(
            "2026-11-16", "stable-collector-1", 0.0, 0.0, 0.0,
            true, false, false
        )
        val negative = CustomerManualSyncPolicy.overnightBusinessFingerprint(
            "2026-11-16", "stable-collector-1", -0.0, 0.0, 0.0,
            true, false, false
        )
        assertTrue(positive == negative)
    }
}
