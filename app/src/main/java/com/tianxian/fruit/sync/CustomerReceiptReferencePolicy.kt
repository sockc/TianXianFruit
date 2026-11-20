package com.tianxian.fruit.sync

/**
 * Foreign references on a remote merchant receipt are not optional if the
 * server actually supplies them. Otherwise a new local receipt might be
 * permanently acknowledged with store_id=0 or source_batch_id=0, making
 * customer/location and exported-bill ownership analyses inaccurate.
 *
 * Keep older receipts without these references compatible: absence of a
 * reference never creates a new synthetic store or source batch.
 */
internal object CustomerReceiptReferencePolicy {
    fun unresolved(
        storeSyncId: String,
        localStoreId: Long,
        sourceFileHash: String,
        localBatchId: Long
    ): String? = when {
        storeSyncId.isNotBlank() && localStoreId <= 0L -> "营业位置尚未恢复"
        sourceFileHash.isNotBlank() && localBatchId <= 0L -> "原始账单批次尚未恢复"
        else -> null
    }
}
