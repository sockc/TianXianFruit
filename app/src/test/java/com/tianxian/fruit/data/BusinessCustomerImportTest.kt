package com.tianxian.fruit.data

import org.junit.Assert.*
import org.junit.Test

class BusinessCustomerImportTest {
    private fun pay(
        id: Long,
        date: String,
        store: Long,
        customer: String,
        quality: String = "HIGH",
        amount: Double = 18.0
    ) = CustomerPaymentRecord(
        id = id, platform = "WECHAT", tradeTime = "${date} 19:00:00",
        businessDate = date, amount = amount, refundAmount = 0.0,
        netAmount = amount, customerKey = customer, customerName = customer,
        identityConfidence = quality, transactionType = "经营收款",
        tradeStatus = "SUCCESS", storeId = store,
        storeName = if (store == 1L) "游乐场" else "九龙公园"
    )

    @Test fun countsNewAndReturningOnlyForSelectedStore() {
        val records = listOf(
            pay(1, "2026-10-08", 1, "old"),
            pay(2, "2026-10-09", 2, "other_store"),
            pay(3, "2026-10-11", 1, "old"),
            pay(4, "2026-10-11", 1, "new"),
            pay(5, "2026-10-11", 1, "new"), // 同一顾客多笔收款
            pay(6, "2026-10-11", 2, "unrelated")
        )
        val result = BusinessCustomerImportEngine.preview("2026-10-11", 1, records)
        assertTrue(result.valid)
        assertEquals(1, result.newCustomers)
        assertEquals(1, result.oldCustomers)
        assertEquals(2, result.uniqueCustomers)
        assertEquals(3, result.paymentCount)
    }

    @Test fun refusesAnonymousAndUnknownLocation() {
        val records = listOf(
            pay(1, "2026-10-11", 1, "untrusted", "LOW"),
            pay(2, "2026-10-11", 0, "unmatched"),
            pay(3, "2026-10-11", 2, "another")
        )
        val result = BusinessCustomerImportEngine.preview("2026-10-11", 1, records)
        assertFalse(result.valid)
        assertEquals(0, result.newCustomers)
        assertEquals(0, result.oldCustomers)
        assertEquals(1, result.unknownIdentityPayments)
        assertEquals(1, result.unmatchedStorePayments)
    }

    @Test fun doesNotTreatLaterTransactionsAsHistory() {
        val records = listOf(
            pay(1, "2026-10-12", 1, "future"),
            pay(2, "2026-10-11", 1, "future")
        )
        val result = BusinessCustomerImportEngine.preview("2026-10-11", 1, records)
        assertEquals(1, result.newCustomers)
        assertEquals(0, result.oldCustomers)
    }

    @Test fun ignoresRefundRowsAndCountsMediumConfidence() {
        val records = listOf(
            pay(1, "2026-10-11", 1, "refund", "HIGH", -19.0),
            pay(2, "2026-10-11", 1, "recognized", "MEDIUM", 20.0)
        )
        val result = BusinessCustomerImportEngine.preview("2026-10-11", 1, records)
        assertEquals(1, result.uniqueCustomers)
        assertEquals(1, result.paymentCount)
    }
}
