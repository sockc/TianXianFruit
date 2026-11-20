package com.tianxian.fruit.sync

/**
 * An import batch is an immutable reference to the original statement file.
 * Different parser versions may count or classify its rows differently even
 * when the underlying file bytes are exactly the same.
 *
 * With Server V1.0.22 an existing immutable BATCH with different derived
 * metadata returns "conflict". A strong, identity-matched file SHA-256
 * permits keeping the server's original BATCH while independently syncing
 * newly recognized RECEIPT events. Financial receipts are never exempted.
 */
internal object CustomerBatchConflictPolicy {
    /**
     * Two devices may import the identical original CSV with different parser
     * revisions. The bytes' SHA-256 and payment provider establish file
     * identity; derived receipt counts and display name are NOT authoritative.
     * Never overwrite local metadata when acknowledging that remote batch.
     */
    fun sameImportedFile(
        remotePlatform: String,
        remoteFileHash: String,
        localPlatform: String?,
        localFileHash: String?
    ): Boolean =
        remotePlatform in setOf("WECHAT", "ALIPAY") &&
            remoteFileHash.length == 64 &&
            remoteFileHash.all { it in '0'..'9' || it in 'a'..'f' } &&
            localPlatform == remotePlatform &&
            localFileHash == remoteFileHash

    fun existingFileMetadata(
        kind: String,
        key: String,
        platform: String,
        fileHash: String,
        status: String,
        revision: Long
    ): Boolean {
        if (kind != "BATCH" || status != "conflict" || revision <= 0L) return false
        if (platform !in setOf("WECHAT", "ALIPAY")) return false
        if (fileHash.length != 64 || fileHash.any { it !in '0'..'9' && it !in 'a'..'f' })
            return false
        return key == "$platform|$fileHash"
    }
}
