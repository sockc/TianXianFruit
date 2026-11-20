package com.tianxian.fruit.sync

import java.util.concurrent.ConcurrentHashMap

/**
 * Serializes customer analytics sync per local ledger to avoid two Android
 * triggers racing on the same ACK table and download cursor.
 *
 * This is NOT a server-side/multi-device mutex. Cross-device revisions still
 * require backend conflict checks, and the original records remain intact.
 */
internal object CustomerSyncSerialPolicy {
    private val bookMonitors = ConcurrentHashMap<String, Any>()

    fun <T> forBook(bookId: String, action: () -> T): T {
        val monitor = bookMonitors.computeIfAbsent(bookId) { Any() }
        return synchronized(monitor) { action() }
    }
}
