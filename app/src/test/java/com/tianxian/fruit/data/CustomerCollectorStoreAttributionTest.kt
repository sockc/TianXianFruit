package com.tianxian.fruit.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomerCollectorStoreAttributionTest {
    private fun store(
        id: Long, name: String, date: String = "2026-10-16",
        cashier: Long = 7L, platform: String = "WECHAT"
    ) = StoreDailyRecord(
        id = id, date = date, storeId = id, storeName = name,
        wechatIncome = if (platform == "WECHAT") 120.0 else 0.0,
        wechatCollectorId = if (platform == "WECHAT") cashier else 0L,
        wechatCollectorName = "张三",
        alipayIncome = if (platform == "ALIPAY") 120.0 else 0.0,
        alipayCollectorId = if (platform == "ALIPAY") cashier else 0L,
        alipayCollectorName = "张三",
        cashIncome = 0.0, cashCollectorId = 0L, cashCollectorName = "未指定",
        receiptSplits = listOf(
            ReceiptSplitRecord(cashier, "张三", 120.0,
                wechatIncome = if (platform == "WECHAT") 120.0 else 0.0,
                alipayIncome = if (platform == "ALIPAY") 120.0 else 0.0)
        ), revenue = 120.0, expense = 0.0, expensePayerId = 0L,
        expensePayerName = "", openingStockValue = 0.0, stockLeftValue = 0.0,
        purchaseCost = 0.0, profit = 0.0, newCustomer = 0, oldCustomer = 0,
        actualStartTime = "16:00", actualEndTime = "24:00"
    )

    private fun payment(
        id: Long, batch: Long = 1, platform: String = "WECHAT",
        date: String = "2026-10-16"
    ) = CustomerPaymentRecord(
        id = id, platform = platform, tradeTime = "$date 18:00:00",
        businessDate = date, amount = 15.0, refundAmount = 0.0, netAmount = 15.0,
        customerKey = "", customerName = "", identityConfidence = "NONE",
        transactionType = "二维码收款", tradeStatus = "成功",
        storeId = 0L, storeName = "", sourceBatchId = batch
    )

    private val receiptCollectors = mapOf(1L to "partner-zhang", 2L to "partner-zhang", 3L to "partner-li")
    private val people = mapOf("partner-zhang" to 7L, "partner-li" to 8L)

    @Test fun overlappingExportsWithDifferentCollectorsStayUnassigned() {
        assertEquals(ReceiptCollectorPolicy.CONFLICT,
            ReceiptCollectorPolicy.resolve(listOf("partner-zhang", "partner-li")))
        assertEquals("partner-zhang",
            ReceiptCollectorPolicy.resolve(listOf("partner-zhang", "partner-zhang", "")))
        val resolved = CustomerCollectorStoreAttribution.resolve(
            listOf(payment(1)), listOf(store(10, "九龙公园")),
            mapOf(1L to ReceiptCollectorPolicy.CONFLICT), people
        )
        assertEquals(0L, resolved.rows.single().storeId)
        assertTrue(resolved.collectorMatchedIds.isEmpty())
    }

    @Test fun oneCollectorAtOneStoreAutomaticallyJoinsHistoricalStore() {
        val result = CustomerCollectorStoreAttribution.resolve(
            listOf(payment(1)), listOf(store(10, "九龙公园")), receiptCollectors, people
        )
        assertEquals(10L, result.rows.single().storeId)
        assertTrue(result.shared.isEmpty())
        assertTrue(1L in result.collectorMatchedIds)
    }

    @Test fun sharedCashierAcrossTwoStoresIsCountedOnlyOnce() {
        val result = CustomerCollectorStoreAttribution.resolve(
            listOf(payment(1), payment(2)), listOf(
                store(10, "九龙公园"), store(20, "凰岗村")
            ), receiptCollectors, people
        )
        assertTrue(result.rows.all { it.storeId == 0L })
        assertEquals(1, result.shared.size)
        val shared = result.shared.single()
        assertEquals(setOf(10L, 20L), shared.storeIds)
        assertEquals(2, shared.payments)
        assertEquals(30.0, shared.amount, 0.001)
    }

    @Test fun bankChannelAndCashierMustBothMatch() {
        val result = CustomerCollectorStoreAttribution.resolve(
            listOf(payment(1, platform = "WECHAT"), payment(2, platform = "ALIPAY"),
                payment(3, batch = 2)), listOf(
                store(10, "九龙公园", platform = "WECHAT"),
                store(20, "凰岗村", platform = "ALIPAY")
            ), receiptCollectors, people
        )
        assertEquals(10L, result.rows[0].storeId)
        assertEquals(20L, result.rows[1].storeId)
        assertEquals(0L, result.rows[2].storeId)
        assertEquals(setOf(1L, 2L), result.collectorMatchedIds)
    }

    @Test fun aDifferentDayAndMissingAccountStayUnmatched() {
        val result = CustomerCollectorStoreAttribution.resolve(
            listOf(payment(1, date = "2026-10-17"), payment(99, batch = 99)),
            listOf(store(10, "九龙公园")), receiptCollectors, people
        )
        assertTrue(result.rows.all { it.storeId == 0L })
        assertTrue(result.shared.isEmpty())
    }

    @Test fun recomputingAfterHistoryChangesSwitchesFromSingleToSharedWithoutBillReimport() {
        val receipts = listOf(payment(1))
        val single = CustomerCollectorStoreAttribution.resolve(
            receipts, listOf(store(10, "九龙公园")), receiptCollectors, people
        )
        val expanded = CustomerCollectorStoreAttribution.resolve(
            receipts, listOf(store(10, "九龙公园"), store(20, "凰岗村")), receiptCollectors, people
        )
        assertEquals(10L, single.rows.single().storeId)
        assertEquals(0L, expanded.rows.single().storeId)
        assertEquals(2, expanded.shared.single().stores.size)
    }

    @Test fun legacyDayWideManualAssignmentCannotOverrideRecognizedSharedPayments() {
        val result = CustomerCollectorStoreAttribution.resolve(
            listOf(payment(1), payment(2)), listOf(
                store(10, "九龙公园"), store(20, "凰岗村")
            ), receiptCollectors, people
        )
        val overlaid = CustomerUnifiedStoreAttribution.apply(
            result.rows, mapOf("2026-10-16" to "store-10"),
            listOf(StoreOption(10L, "九龙公园", "", syncId = "store-10")),
            result.collectorMatchedIds
        )
        assertTrue(overlaid.all { it.storeId == 0L })
        assertEquals(0, CustomerUnifiedStoreAttribution.unmatchedOnDate(
            result.rows, "2026-10-16", result.collectorMatchedIds))
    }
    @Test fun differentDatesFollowTheRecordedHistoricalCollectorsWithoutReimport() {
        val result = CustomerCollectorStoreAttribution.resolve(
            listOf(payment(1, date = "2026-10-16"),
                payment(2, date = "2026-10-17")),
            listOf(store(10, "九龙公园", date = "2026-10-16"),
                store(20, "凰岗村", date = "2026-10-17")), receiptCollectors, people
        )
        assertEquals(listOf(10L, 20L), result.rows.map { it.storeId })
        assertTrue(result.shared.isEmpty())
    }

    @Test fun nextMorningPaymentUsesPreviousDayWhenTheStoreStayedOpen() {
        val late = payment(1, date = "2026-10-17")
            .copy(tradeTime = "2026-10-17 00:25:00")
        val result = CustomerCollectorStoreAttribution.resolve(
            listOf(late),
            listOf(store(10, "九龙公园", date = "2026-10-16")),
            receiptCollectors, people,
            listOf(CustomerTrafficSession("2026-10-16", 10L, "九龙公园",
                "16:00", "02:00"))
        )
        assertEquals("2026-10-16", result.rows.single().businessDate)
        assertEquals(10L, result.rows.single().storeId)
    }

}
