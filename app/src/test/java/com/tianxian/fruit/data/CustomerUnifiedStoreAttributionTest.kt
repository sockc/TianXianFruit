package com.tianxian.fruit.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CustomerUnifiedStoreAttributionTest {
    private val park = StoreOption(9L, "游乐场", "", syncId = "a89159b0-74ac-4401-9158-d6656ed78966")
    private val other = StoreOption(8L, "九龙公园", "", syncId = "20a8aa25-7649-4c3f-a713-5d0aaacdd5d2")

    private fun receipt(id: Long, date: String, existing: Long = 0L, amount: Double = 15.0) =
        CustomerPaymentRecord(
            id = id, platform = "WECHAT", tradeTime = "$date 18:00:00",
            businessDate = date, amount = amount, refundAmount = 0.0,
            netAmount = amount, customerKey = "", customerName = "",
            identityConfidence = "LOW", transactionType = "经营收款",
            tradeStatus = "成功", storeId = existing,
            storeName = if (existing == other.id) other.name else ""
        )

    @Test fun onlyUnmatchedPaymentsOnChosenDateMoveToDefaultStore() {
        val rows = listOf(
            receipt(1, "2026-10-15", 0),
            receipt(2, "2026-10-15", other.id),
            receipt(3, "2026-10-16", 0),
            receipt(4, "2026-10-15", 0, amount = -10.0)
        )
        val result = CustomerUnifiedStoreAttribution.apply(
            rows, mapOf("2026-10-15" to park.syncId), listOf(park, other)
        )
        assertEquals(park.id, result[0].storeId)
        assertEquals(other.id, result[1].storeId)
        assertEquals(0L, result[2].storeId)
        assertEquals(0L, result[3].storeId)
        assertEquals(0, CustomerUnifiedStoreAttribution.unmatchedOnDate(result, "2026-10-15"))
    }

    @Test fun emptyOrUnresolvedStableIdNeverGuessesAStore() {
        val rows = listOf(receipt(1, "2026-10-15"))
        assertEquals(0L, CustomerUnifiedStoreAttribution.apply(
            rows, mapOf("2026-10-15" to ""), listOf(park)
        ).single().storeId)
        assertEquals(0L, CustomerUnifiedStoreAttribution.apply(
            rows, mapOf("2026-10-15" to "missing"), listOf(park)
        ).single().storeId)
    }
}
