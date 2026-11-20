package com.tianxian.fruit.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomerReceiptIdentityPolicyTest {
    private fun sample() = CustomerReceiptFinancialIdentity(
        platform = "ALIPAY",
        dedupeKey = "alipay-receipt-key",
        transactionRef = "synthetic-order-001",
        tradeTime = "2026-10-15 00:25:00",
        businessDate = "2026-10-15",
        amount = 26.50, refundAmount = 0.0, netAmount = 26.50,
        storeSyncId = "synthetic-store-a"
    )

    @Test fun existingIdenticalReceiptMayBeAcknowledgedWithoutReimport() {
        val local = sample()
        val remote = local.copy()
        assertTrue(CustomerReceiptIdentityPolicy.matches(local, remote))
    }

    @Test fun moneyOrRefundDisagreementNeverBecomesAnAck() {
        val local = sample()
        assertFalse(CustomerReceiptIdentityPolicy.matches(
            local, local.copy(amount = 36.50)))
        assertFalse(CustomerReceiptIdentityPolicy.matches(
            local, local.copy(refundAmount = 1.00)))
        assertFalse(CustomerReceiptIdentityPolicy.matches(
            local, local.copy(netAmount = 26.49)))
        assertFalse(CustomerReceiptIdentityPolicy.matches(
            local, local.copy(amount = Double.NaN)))
    }

    @Test fun midnightDateOrStoreConflictCannotSilentlyMoveMoney() {
        val local = sample()
        assertFalse(CustomerReceiptIdentityPolicy.matches(
            local, local.copy(tradeTime = "2026-10-15 01:25:00")))
        assertFalse(CustomerReceiptIdentityPolicy.matches(
            local, local.copy(businessDate = "2026-10-14")))
        assertFalse(CustomerReceiptIdentityPolicy.matches(
            local, local.copy(storeSyncId = "synthetic-store-b")))
    }

    @Test fun onlySameProviderOrderAndDedupeKeyMayMerge() {
        val local = sample()
        assertFalse(CustomerReceiptIdentityPolicy.matches(
            local, local.copy(platform = "WECHAT")))
        assertFalse(CustomerReceiptIdentityPolicy.matches(
            local, local.copy(transactionRef = "other-order")))
        assertFalse(CustomerReceiptIdentityPolicy.matches(
            local, local.copy(dedupeKey = "other-receipt")))
        assertFalse(CustomerReceiptIdentityPolicy.matches(
            local, local.copy(dedupeKey = "")))
    }
}
