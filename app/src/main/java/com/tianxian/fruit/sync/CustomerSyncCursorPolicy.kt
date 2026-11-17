package com.tianxian.fruit.sync

/**
 * Read-only download cursor invariant.
 *
 * A nonempty page must advance its cursor. A server that claims more pages
 * but returns no cursor movement must fail fast rather than re-requesting
 * the same page hundreds of times. No local ACK or cursor is modified here.
 */
internal object CustomerSyncCursorPolicy {
    fun progresses(before: Long, next: Long, changeCount: Int, hasMore: Boolean): Boolean =
        before >= 0L && next >= before && changeCount >= 0 &&
            !(changeCount > 0 && next == before) &&
            !(hasMore && next == before)
}
