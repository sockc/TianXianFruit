package com.tianxian.fruit.sync

/**
 * Scan recent cloud changes instead of restarting at cursor zero for every CAS
 * conflict. Each candidate window is read completely to the server's current
 * end before it can supply the newest revision of a key *found* in the window.
 *
 * If the key is older than the largest window, fail closed. Do not guess an ACK
 * revision, overwrite financial values, or move the normal incremental cursor.
 *
 * Cursors are monotonically increasing server event IDs (not row counts).
 */
internal object CustomerManualReplayWindowPolicy {
    private val lookbacks = listOf(2_048L, 8_192L, 16_000L)

    fun startCursors(currentCursor: Long): List<Long> {
        require(currentCursor >= 0L)
        return lookbacks.map { (currentCursor - it).coerceAtLeast(0L) }.distinct()
    }
}
