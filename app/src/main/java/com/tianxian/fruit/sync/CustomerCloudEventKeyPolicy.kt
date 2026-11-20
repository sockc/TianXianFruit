package com.tianxian.fruit.sync

/**
 * Cloud event identifiers must agree with their payload before a downloaded
 * page can advance the local cursor. Silently ignoring a malformed immutable
 * receipt/batch/source link would permanently discard that event.
 */
internal object CustomerCloudEventKeyPolicy {
    private val platforms = setOf("WECHAT", "ALIPAY")
    private fun fileHashValid(hash: String) =
        hash.length == 64 && hash.all { it in '0'..'9' || it in 'a'..'f' }

    fun batch(key: String, platform: String, fileHash: String): Boolean =
        platform in platforms && fileHashValid(fileHash) &&
            key == "$platform|$fileHash"

    fun receipt(key: String, platform: String, dedupeKey: String): Boolean =
        platform in platforms && dedupeKey.isNotBlank() &&
            key == "$platform|$dedupeKey"

    fun sourceLink(key: String, platform: String, dedupeKey: String,
                   fileHash: String): Boolean =
        platform in platforms && dedupeKey.isNotBlank() &&
            fileHashValid(fileHash) && key == "$platform|$dedupeKey|$fileHash"
}
