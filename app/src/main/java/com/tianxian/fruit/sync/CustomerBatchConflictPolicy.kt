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
