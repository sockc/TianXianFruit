package com.tianxian.fruit.sync

import org.junit.Assert.*
import org.junit.Test

class CustomerReceiptReferencePolicyTest {
    @Test fun missingRemoteStoreMustNotInsertAnUnassignedReceipt() {
        assertEquals("营业位置尚未恢复",
            CustomerReceiptReferencePolicy.unresolved(
                "remote-store-uuid", 0L, "", 0L))
        assertEquals("营业位置尚未恢复",
            CustomerReceiptReferencePolicy.unresolved(
                "remote-store-uuid", 0L, "source-hash", 0L))
    }

    @Test fun missingRemoteBatchMustNotLoseImportLineage() {
        assertEquals("原始账单批次尚未恢复",
            CustomerReceiptReferencePolicy.unresolved(
                "remote-store-uuid", 12L, "source-hash", 0L))
        assertEquals("原始账单批次尚未恢复",
            CustomerReceiptReferencePolicy.unresolved("", 0L, "source-hash", 0L))
    }

    @Test fun referencesResolvedAndLegacyBlankRefsMayProceed() {
        assertNull(CustomerReceiptReferencePolicy.unresolved(
            "remote-store-uuid", 12L, "source-hash", 9L))
        assertNull(CustomerReceiptReferencePolicy.unresolved(
            "", 0L, "", 0L))
        assertNull(CustomerReceiptReferencePolicy.unresolved(
            "remote-store-uuid", 12L, "", 0L))
        assertNull(CustomerReceiptReferencePolicy.unresolved(
            "", 0L, "source-hash", 9L))
    }
}
