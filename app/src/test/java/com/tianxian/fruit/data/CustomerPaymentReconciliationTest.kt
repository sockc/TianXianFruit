package com.tianxian.fruit.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CustomerPaymentReconciliationTest {
    private fun business(id: Long, storeId: Long, wechat: Double, alipay: Double) =
        StoreDailyRecord(
            id = id, date = "2026-10-14", storeId = storeId,
            storeName = if (storeId == 1L) "九龙公园" else "凰岗村",
            wechatIncome = wechat, wechatCollectorId = 0L,
            wechatCollectorName = "", alipayIncome = alipay,
            alipayCollectorId = 0L, alipayCollectorName = "",
            cashIncome = 0.0, cashCollectorId = 0L, cashCollectorName = "",
            receiptSplits = emptyList(), revenue = wechat + alipay,
            expense = 0.0, expensePayerId = 0L, expensePayerName = "",
            openingStockValue = 0.0, stockLeftValue = 0.0,
            purchaseCost = 0.0, profit = 0.0,
            newCustomer = 0, oldCustomer = 0
        )

    private fun receipt(
        id: Long, storeId: Long, platform: String,
        amount: Double, refund: Double = 0.0
    ) = CustomerPaymentRecord(
        id = id, platform = platform, tradeTime = "2026-10-14 18:00:00",
        businessDate = "2026-10-14", amount = amount,
        refundAmount = refund, netAmount = amount - refund,
        customerKey = "", customerName = "",
        identityConfidence = "LOW", transactionType = "经营收款",
        tradeStatus = "成功", storeId = storeId, storeName = ""
    )

    @Test fun onlyVerifiedStoreReceiptsEnterThatStoresComparison() {
        val saved = listOf(business(1, 1, 100.0, 50.0), business(2, 2, 40.0, 30.0))
        val receipts = listOf(
            receipt(1, 1, "WECHAT", 100.0),
            receipt(2, 1, "ALIPAY", 40.0),
            receipt(3, 2, "WECHAT", 40.0),
            receipt(4, 0, "ALIPAY", 10.0)
        )
        val compared = CustomerPaymentReconciliationEngine.inspect(
            saved, receipts, "2026-10-14", selectedStoreId = 1L
        ).single()
        assertEquals(140.0, compared.observedTotal, 0.001)
        assertEquals(150.0, compared.businessTotal, 0.001)
        assertEquals(-10.0, compared.difference, 0.001)
        assertEquals(1, compared.unmatchedPaymentsForDay)
    }

    @Test fun noBillIsStillUnknownNotEvidenceOfZeroTakings() {
        val compared = CustomerPaymentReconciliationEngine.inspect(
            listOf(business(1, 1, 250.0, 30.0)),
            emptyList(), "2026-10-14"
        ).single()
        assertEquals(0, compared.observedPayments)
        assertEquals(280.0, compared.businessTotal, 0.001)
    }

    @Test fun refundOnlyRowIsNotCountedAsAnExtraSale() {
        val compared = CustomerPaymentReconciliationEngine.inspect(
            listOf(business(1, 1, 100.0, 0.0)),
            listOf(receipt(1, 1, "WECHAT", 100.0),
                receipt(2, 1, "WECHAT", 0.0, refund = 20.0)),
            "2026-10-14"
        ).single()
        assertEquals(1, compared.wechatPayments)
        assertEquals(100.0, compared.wechatObserved, 0.001)
    }
}
