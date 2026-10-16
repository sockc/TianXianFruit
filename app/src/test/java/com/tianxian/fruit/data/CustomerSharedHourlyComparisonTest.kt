package com.tianxian.fruit.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

class CustomerSharedHourlyComparisonTest {
    private val stores = listOf(1L to "游乐场", 2L to "里水")
    private fun group(date: String, ids: Set<Long>, platform: String = "WECHAT", twoStores: Boolean = true) =
        SharedCollectorTraffic(
            date = date, platform = platform, partnerId = 7L,
            partnerName = "子琳",
            stores = if (twoStores) stores else listOf(1L to "游乐场"),
            receiptIds = ids, payments = ids.size, amount = 15.0 * ids.size
        )
    private fun row(id: Long, date: String) = CustomerPaymentRecord(
        id = id, platform = "WECHAT", tradeTime = "$date 18:25:00",
        businessDate = date, amount = 15.0, refundAmount = 0.0, netAmount = 15.0,
        customerKey = "", customerName = "", identityConfidence = "NONE",
        transactionType = "经营收款", tradeStatus = "成功",
        storeId = 0L, storeName = CustomerCollectorStoreAttribution.SHARED_STORE_NAME
    )
    private fun sessions(date: String, two: Boolean) =
        listOf(CustomerTrafficSession(date, 1L, "游乐场", "16:00", "20:00")) +
            if (two) listOf(CustomerTrafficSession(date, 2L, "里水", "16:00", "20:00"))
            else emptyList()

    @Test fun currentCombinedHourIsShownEvenWhenNoHistoricalSample() {
        val g = group("2026-10-16", setOf(1L, 2L))
        val cmp = CustomerSharedHourlyComparison.compare(g, listOf(g),
            listOf(row(1, g.date), row(2, g.date)),
            sessions(g.date, true),
            LocalDateTime.parse("2026-10-16T22:00:00"))
        assertNotNull(cmp)
        assertEquals(2, cmp!!.currentPayments)
        assertNull(cmp.baselinePayments)
        assertEquals(2, cmp.hourRows.first { it.hour == 18 }.payments)
    }

    @Test fun referenceUsesSameAccountChannelAndFullStoreSetOnly() {
        val current = group("2026-10-16", setOf(1L, 2L))
        val prior = group("2026-10-09", setOf(3L, 4L))
        val wrongStore = group("2026-10-02", setOf(5L), twoStores = false)
        val wrongChannel = group("2026-09-25", setOf(6L), "ALIPAY")
        val cmp = CustomerSharedHourlyComparison.compare(
            current, listOf(current, prior, wrongStore, wrongChannel),
            listOf(row(1, current.date), row(2, current.date), row(3, prior.date),
                row(4, prior.date), row(5, wrongStore.date), row(6, wrongChannel.date)),
            sessions(current.date, true) + sessions(prior.date, true) +
                sessions(wrongStore.date, false) + sessions(wrongChannel.date, true),
            LocalDateTime.parse("2026-10-17T02:00:00"))
        assertNotNull(cmp)
        assertEquals(listOf("2026-10-09"), cmp!!.historicDates)
        assertEquals(2.0, cmp.baselinePayments!!, 0.001)
        assertFalse(cmp.sampleSufficient)
    }
}
