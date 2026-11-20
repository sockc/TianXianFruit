package com.tianxian.fruit.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomerBatchConflictPolicyTest {
    private val hash = "a".repeat(64)
    private fun compatible(kind: String = "BATCH", key: String = "ALIPAY|$hash",
                           platform: String = "ALIPAY", fileHash: String = hash,
                           status: String = "conflict", revision: Long = 1) =
        CustomerBatchConflictPolicy.existingFileMetadata(
            kind, key, platform, fileHash, status, revision
        )

    @Test fun identicalFileAcrossDevicesCanBeAcknowledgedDespiteParserCountChanges() {
        assertTrue(CustomerBatchConflictPolicy.sameImportedFile(
            "ALIPAY", hash, "ALIPAY", hash
        ))
        assertTrue(CustomerBatchConflictPolicy.sameImportedFile(
            "WECHAT", hash, "WECHAT", hash
        ))
        // Counts, imported timestamps and display filenames are not part
        // of immutable file identity and should never be overwritten.
    }

    @Test fun missingOrDifferentLocalFileIdentityMustNeverBeAcknowledged() {
        assertFalse(CustomerBatchConflictPolicy.sameImportedFile(
            "ALIPAY", hash, null, null
        ))
        assertFalse(CustomerBatchConflictPolicy.sameImportedFile(
            "ALIPAY", hash, "WECHAT", hash
        ))
        assertFalse(CustomerBatchConflictPolicy.sameImportedFile(
            "ALIPAY", hash, "ALIPAY", "b".repeat(64)
        ))
        assertFalse(CustomerBatchConflictPolicy.sameImportedFile(
            "ALIPAY", "INVALID", "ALIPAY", "INVALID"
        ))
        assertFalse(CustomerBatchConflictPolicy.sameImportedFile(
            "ALIPAY", "A".repeat(64), "ALIPAY", "A".repeat(64)
        ))
    }

    @Test fun acceptsOnlySameFileWithExistingServerRevision() {
        assertTrue(compatible())
        assertTrue(compatible(platform = "WECHAT", key = "WECHAT|$hash"))
        assertFalse(compatible(revision = 0))
        assertFalse(compatible(key = "ALIPAY|" + "b".repeat(64)))
        assertFalse(compatible(fileHash = "not-a-sha256"))
        assertFalse(compatible(status = "accepted"))
    }

    @Test fun cannotSilenceRealReceiptOrArtificialMetadataConflict() {
        assertFalse(compatible(kind = "RECEIPT"))
        assertFalse(compatible(kind = "SOURCE_LINK"))
        assertFalse(compatible(kind = "ANALYSIS"))
        assertFalse(compatible(platform = "OTHER", key = "OTHER|$hash"))
        assertFalse(compatible(fileHash = "A".repeat(64), key = "ALIPAY|" + "A".repeat(64)))
        assertFalse(compatible(key = "ALIPAY|" + "a".repeat(63)))
    }
}
