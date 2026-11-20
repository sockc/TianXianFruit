package com.tianxian.fruit.sync

import org.junit.Assert.*
import org.junit.Test

class CustomerCloudEventKeyPolicyTest {
    private val hash = "a".repeat(64)
    private val order = "receipt-abc"

    @Test fun immutableCloudEventsRequireKeyAndPayloadAgreement() {
        assertTrue(CustomerCloudEventKeyPolicy.batch(
            "ALIPAY|$hash", "ALIPAY", hash))
        assertTrue(CustomerCloudEventKeyPolicy.receipt(
            "WECHAT|$order", "WECHAT", order))
        assertTrue(CustomerCloudEventKeyPolicy.sourceLink(
            "ALIPAY|$order|$hash", "ALIPAY", order, hash))
    }

    @Test fun malformedBatchNeverSilentlyAdvancesCursor() {
        assertFalse(CustomerCloudEventKeyPolicy.batch(
            "ALIPAY|$hash", "WECHAT", hash))
        assertFalse(CustomerCloudEventKeyPolicy.batch(
            "ALIPAY|$hash", "ALIPAY", "unknown"))
        assertFalse(CustomerCloudEventKeyPolicy.batch(
            "ALIPAY|${"B".repeat(64)}", "ALIPAY", "B".repeat(64)))
        assertFalse(CustomerCloudEventKeyPolicy.batch(
            "ALIPAY|$hash", "OTHER", hash))
    }

    @Test fun invalidReceiptIdentityCannotBeSkipped() {
        assertFalse(CustomerCloudEventKeyPolicy.receipt(
            "ALIPAY|$order", "ALIPAY", "other"))
        assertFalse(CustomerCloudEventKeyPolicy.receipt(
            "ALIPAY|", "ALIPAY", ""))
        assertFalse(CustomerCloudEventKeyPolicy.receipt(
            "OTHER|$order", "OTHER", order))
    }

    @Test fun invalidSourceLinkCannotCreateOrphanedReference() {
        assertFalse(CustomerCloudEventKeyPolicy.sourceLink(
            "WECHAT|$order|$hash", "ALIPAY", order, hash))
        assertFalse(CustomerCloudEventKeyPolicy.sourceLink(
            "ALIPAY|$order|short", "ALIPAY", order, "short"))
        assertFalse(CustomerCloudEventKeyPolicy.sourceLink(
            "ALIPAY||$hash", "ALIPAY", "", hash))
    }
}
