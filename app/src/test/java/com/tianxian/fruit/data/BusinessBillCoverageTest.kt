package com.tianxian.fruit.data

import org.junit.Assert.*
import org.junit.Test

class BusinessBillCoverageTest {
    private fun business(id: Long, cashier: Long, name: String, wechat: Double, alipay: Double) =
        StoreDailyRecord(
            id = id, date = "2026-10-10", storeId = id, storeName = "摊位$id",
            wechatIncome = wechat, wechatCollectorId = cashier, wechatCollectorName = name,
            alipayIncome = alipay, alipayCollectorId = cashier, alipayCollectorName = name,
            cashIncome = 0.0, cashCollectorId = 0L, cashCollectorName = "",
            receiptSplits = listOf(ReceiptSplitRecord(
                partnerId = cashier, partnerName = name, amount = wechat + alipay,
                wechatIncome = wechat, alipayIncome = alipay
            )), revenue = wechat + alipay, expense = 0.0,
            expensePayerId = 0L, expensePayerName = "", openingStockValue = 0.0,
            stockLeftValue = 0.0, purchaseCost = 0.0, profit = 0.0,
            newCustomer = 0, oldCustomer = 0
        )
    private fun payment(id: Long, platform: String, batch: Long, money: Double) =
        CustomerPaymentRecord(
            id = id, platform = platform,
            tradeTime = "2026-10-10 19:00:00", businessDate = "2026-10-10",
            amount = money, refundAmount = 0.0, netAmount = money,
            customerKey = "", customerName = "", identityConfidence = "NONE",
            transactionType = "经营收款", tradeStatus = "成功",
            storeId = 0L, storeName = "", sourceBatchId = batch
        )

    @Test fun importForOneCashierDoesNotCreateFalseDeficitForOtherStall() {
        val review = BusinessBillCoverageEngine.review(
            date = "2026-10-10",
            savedRecords = listOf(
                business(1L, 7L, "子琳", 2112.0, 270.0),
                business(2L, 8L, "子鸣", 1553.0, 64.0)),
            payments = listOf(payment(1, "WECHAT", 1, 2112.8)),
            byBatch = mapOf(1L to "cashier7"),
            partnerBySyncId = mapOf("cashier7" to 7L)
        )
        assertEquals(4, review.rows.size)
        assertEquals(3, review.pendingChannels)
        assertEquals(1, review.observedChannels)
        assertEquals("待补齐3项", review.status)
        assertEquals(2112.8,
            review.rows.first { it.partnerId == 7L && it.platform == "WECHAT" }
                .importedAmount, 0.001)
    }

    @Test fun oneAccountAcrossTwoLocationsShowsSingleSharedGroup() {
        val review = BusinessBillCoverageEngine.review(
            "2026-10-10",
            listOf(business(1L, 7L, "子琳", 100.0, 0.0),
                business(2L, 7L, "子琳", 200.0, 0.0)),
            listOf(payment(1, "WECHAT", 1, 300.0)),
            mapOf(1L to "account"), mapOf("account" to 7L)
        )
        assertEquals(1, review.rows.size)
        assertTrue(review.rows.single().shared)
        assertEquals(2, review.rows.single().storeNames.size)
        assertEquals(300.0, review.rows.single().importedAmount, 0.001)
        assertEquals(300.0, review.rows.single().businessAmount, 0.001)
    }

    @Test fun unassignedImportIsNotMatchedToKnownAccount() {
        val review = BusinessBillCoverageEngine.review(
            "2026-10-10", listOf(business(1L, 7L, "子琳", 500.0, 0.0)),
            listOf(payment(1, "WECHAT", 2, 90.0)), emptyMap(), emptyMap()
        )
        assertEquals(0, review.observedChannels)
        assertEquals(1, review.importedUnassigned)
        assertEquals(1, review.pendingChannels)
    }
}
