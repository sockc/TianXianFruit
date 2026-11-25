package com.tianxian.fruit.data

import java.util.UUID

/**
 * The Android test namespace [2^48, 2^49) is disjoint from the server
 * test namespace [2^50, 2^51). Both fit lossless JSON/JS integer encoding.
 * Random IDs reduce independent-device collisions; they are not a proof
 * of global uniqueness. The server must reject a conflicting identity.
 */
internal object MasterRecordIdentity {
    val tables = setOf("fruit", "store", "partner")
    const val ANDROID_MIN: Long = 1L shl 48
    const val ANDROID_MAX_EXCLUSIVE: Long = 1L shl 49
    const val SERVER_MIN: Long = 1L shl 50
    const val SERVER_MAX_EXCLUSIVE: Long = 1L shl 51

    fun pilotEnabled(ledgerName: String): Boolean =
        ledgerName.startsWith("iOS_TEST_")

    fun androidId(uuid: UUID): Long =
        ANDROID_MIN or (uuid.leastSignificantBits and (ANDROID_MIN - 1L))
}
