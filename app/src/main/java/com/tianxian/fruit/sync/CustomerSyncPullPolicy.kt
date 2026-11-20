package com.tianxian.fruit.sync

/**
 * Initial GET is always required. If no local change was pushed, a second
 * GET immediately afterward would ask for the same unchanged cursor.
 * Any attempted POST, including a metadata conflict/unchanged response,
 * retains the post-upload pull for read-after-write convergence.
 */
internal object CustomerSyncPullPolicy {
    fun needsPostUploadPull(pushBatches: Int): Boolean = pushBatches > 0
}
