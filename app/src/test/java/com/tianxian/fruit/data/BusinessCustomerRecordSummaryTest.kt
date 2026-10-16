package com.tianxian.fruit.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BusinessCustomerRecordSummaryTest {
    private fun saved(date: String, storeId: Long, new: Int, old: Int) =
        StoreDailyRecord(
            id = storeId, date = date, storeId = storeId, storeName = "摊位$storeId",
            wechatIncome = 0.0, wechatCollectorId = 0L, wechatCollectorName = "",
            alipayIncome = 0.0, alipayCollectorId = 0L, alipayCollectorName = "",
            cashIncome = 0.0, cashCollectorId = 0L, cashCollectorName = "",
            receiptSplits = emptyList(), revenue = 0.0, expense = 0.0,
            expensePayerId = 0L, expensePayerName = "",
            openingStockValue = 0.0, stockLeftValue = 0.0, purchaseCost = 0.0,
            profit = 0.0, newCustomer = new, oldCustomer = old
        )

    @Test fun sumsOnlySavedCustomerCountsForSelectedBusinessDateAndStore() {
        val rows = listOf(
            saved("2026-10-15", 1L, 10, 30),
            saved("2026-10-15", 2L, 7, 8),
            saved("2026-10-16", 1L, 5, 20),
            saved("2026-10-17", 1L, 1, 1)
        )
        val one = BusinessCustomerRecordSummaryEngine.summarize(
            rows, "2026-10-15", "2026-10-16", 1L)
        assertEquals(15L, one.newCustomers)
        assertEquals(50L, one.returningCustomers)
        assertEquals(65L, one.total)
        assertEquals(2, one.operatingDays)
        assertEquals(1, one.storeCount)
        val all = BusinessCustomerRecordSummaryEngine.summarize(
            rows, "2026-10-15", "2026-10-16")
        assertEquals(22L, all.newCustomers)
        assertEquals(58L, all.returningCustomers)
        assertEquals(2, all.storeCount)
    }

    @Test fun noBusinessRecordIsMissingRatherThanClaimedZero() {
        val all = listOf(saved("2026-10-15", 1L, 0, 0))
        val missing = BusinessCustomerRecordSummaryEngine.summarize(
            all, "2026-10-16", "2026-10-16")
        assertFalse(missing.available)
        assertEquals(0, missing.recordCount)
        val zero = BusinessCustomerRecordSummaryEngine.summarize(
            all, "2026-10-15", "2026-10-15")
        assertTrue(zero.available)
        assertEquals(0L, zero.total)
    }

    @Test fun unmatchedVirtualStoreHasNoAuthoritativeBusinessData() {
        val totals = BusinessCustomerRecordSummaryEngine.summarize(
            listOf(saved("2026-10-16", 1L, 3, 9)), null, null, -1L)
        assertFalse(totals.available)
    }

    @Test fun crossYearPeriodIncludesOnlySelectedRange() {
        val totals = BusinessCustomerRecordSummaryEngine.summarize(
            listOf(saved("2026-12-31", 1L, 2, 4),
                saved("2027-01-01", 2L, 3, 6),
                saved("2027-02-01", 1L, 5, 9)),
            "2026-12-31", "2027-01-01")
        assertEquals(5L, totals.newCustomers)
        assertEquals(10L, totals.returningCustomers)
        assertEquals(2, totals.operatingDays)
    }
}
