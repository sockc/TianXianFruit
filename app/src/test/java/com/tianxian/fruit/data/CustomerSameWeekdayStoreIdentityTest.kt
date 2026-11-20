package com.tianxian.fruit.data

import org.junit.Assert.*
import org.junit.Test

class CustomerSameWeekdayStoreIdentityTest {
    private fun receipt(
        id: Long, day: String, storeId: Long, storeName: String,
        customer: String = "customer-$id", confidence: String = "HIGH"
    ): CustomerPaymentRecord = CustomerPaymentRecord(
        id = id, platform = "ALIPAY", tradeTime = "$day 18:00:00",
        businessDate = day, amount = 20.0, refundAmount = 0.0,
        netAmount = 20.0, customerKey = customer,
        customerName = "顾客", identityConfidence = confidence,
        transactionType = "经营码交易", tradeStatus = "交易成功",
        storeId = storeId, storeName = storeName
    )

    @Test fun twoStallsWithSameNameMustNotMixTheirSameWeekdayHistory() {
        val target = "2026-11-20"
        val records = listOf(
            receipt(1, target, 10L, "同名位置"),
            receipt(2, target, 20L, "同名位置"),
            receipt(3, "2026-11-13", 10L, "同名位置"),
            receipt(4, "2026-11-13", 10L, "同名位置"),
            receipt(5, "2026-11-13", 20L, "同名位置"),
            receipt(6, "2026-11-13", 20L, "同名位置"),
            receipt(7, "2026-11-13", 20L, "同名位置"),
            receipt(8, "2026-11-06", 10L, "同名位置"),
            receipt(9, "2026-11-06", 20L, "同名位置")
        )
        val comparisons = CustomerAnalyticsEngine.sameWeekdayComparisons(records, target)
        assertEquals(2, comparisons.size)
        assertEquals(listOf(1.5, 2.0), comparisons.map { it.averageCustomers })
        assertEquals(listOf(2, 2), comparisons.map { it.sampleDays })
    }

    @Test fun renamedStallKeepsHistoricIdentityAcrossDisplayNames() {
        val records = listOf(
            receipt(1, "2026-11-20", 10L, "新名称"),
            receipt(2, "2026-11-13", 10L, "旧名称"),
            receipt(3, "2026-11-06", 10L, "旧名称")
        )
        val comparisons = CustomerAnalyticsEngine.sameWeekdayComparisons(
            records, "2026-11-20"
        )
        assertEquals(1, comparisons.size)
        assertEquals("新名称", comparisons.single().storeName)
        assertEquals(2, comparisons.single().sampleDays)
        assertEquals(1.0, comparisons.single().averageCustomers, 0.001)
    }

    @Test fun anonymousReceiptsNeverBecomeIdentifiedCustomerBaseline() {
        val records = listOf(
            receipt(1, "2026-11-20", 10L, "九龙公园"),
            receipt(2, "2026-11-13", 10L, "九龙公园", customer = ""),
            receipt(3, "2026-11-13", 10L, "九龙公园", confidence = "LOW"),
            receipt(4, "2026-11-13", 10L, "九龙公园")
        )
        val result = CustomerAnalyticsEngine.sameWeekdayComparisons(records, "2026-11-20")
        assertEquals(1, result.size)
        assertEquals(1.0, result.single().averageCustomers, 0.001)
    }
}
