package com.tianxian.fruit.data

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentTrafficAnalyticsTest {
    private fun payment(
        id: Long,
        platform: String,
        time: String,
        person: String,
        confidence: String = "HIGH",
        storeId: Long = 1L,
        amount: Double = 15.0
    ) = CustomerPaymentRecord(
        id = id,
        platform = platform,
        tradeTime = time,
        businessDate = time.take(10),
        amount = if (amount > 0) amount else 0.0,
        refundAmount = if (amount < 0) -amount else 0.0,
        netAmount = amount,
        customerKey = person,
        customerName = person,
        identityConfidence = confidence,
        transactionType = "经营收款",
        tradeStatus = "SUCCESS",
        storeId = storeId,
        storeName = if (storeId > 0) "九龙公园" else ""
    )

    @Test fun combinedHourlyCountsPaymentsButNotAnonymousCustomers() {
        val rows = listOf(
            payment(1, "WECHAT", "2026-10-12 19:03:00", "wx-a"),
            payment(2, "WECHAT", "2026-10-12 19:08:00", "wx-a"),
            payment(3, "WECHAT", "2026-10-12 19:09:00", "anon", "LOW"),
            payment(4, "ALIPAY", "2026-10-12 19:11:00", "ali-b", amount = 20.0),
            payment(5, "ALIPAY", "2026-10-12 20:00:00", "ali-c", amount = 30.0)
        )
        val hour = PaymentTrafficAnalytics.hourly(rows, "2026-10-12")[19]
        assertEquals(3, hour.wechatPayments)
        assertEquals(1, hour.alipayPayments)
        assertEquals(1, hour.wechatCustomers)
        assertEquals(1, hour.alipayCustomers)
        assertEquals(45.0, hour.wechatAmount, 0.001)
        assertEquals(20.0, hour.alipayAmount, 0.001)

        val combined = CustomerAnalyticsEngine.hourlyTraffic(rows, "2026-10-12")[19]
        assertEquals(4, combined.paymentCount)
        assertEquals(2, combined.customerCount)
        assertEquals(2, combined.newCustomerCount)
        assertEquals(65.0, combined.amount, 0.001)
    }

    @Test fun scopePreservesUnassignedPaymentsAndProviderIsolation() {
        val rows = listOf(
            payment(1, "WECHAT", "2026-10-12 18:00:00", "a", storeId = 1),
            payment(2, "ALIPAY", "2026-10-12 18:10:00", "b", storeId = 1),
            payment(3, "ALIPAY", "2026-10-12 19:00:00", "c", storeId = 0),
            payment(4, "WECHAT", "2026-10-12 20:00:00", "d", storeId = 2)
        )
        assertEquals(2, PaymentTrafficAnalytics.select(rows, storeId = 1).size)
        assertEquals(1, PaymentTrafficAnalytics.select(rows, storeId = -1).size)
        assertEquals(1, PaymentTrafficAnalytics.select(rows, "ALIPAY", 1).size)
        val coverage = PaymentTrafficAnalytics.coverage(
            PaymentTrafficAnalytics.select(rows, storeId = 1)
        )
        assertEquals(1, coverage[0].paymentCount)
        assertEquals(1, coverage[1].paymentCount)
        assertEquals("2026-10-12", coverage[0].firstDate)
    }

    @Test fun anonymousRevenueDoesNotCreateNewCustomerOrIncreaseIdentifiedCustomerAverage() {
        val rows = listOf(
            payment(1, "WECHAT", "2026-10-10 19:00:00", "old", amount = 12.0),
            payment(2, "WECHAT", "2026-10-12 19:00:00", "old", amount = 18.0),
            payment(3, "ALIPAY", "2026-10-12 19:10:00", "new", amount = 22.0),
            payment(4, "ALIPAY", "2026-10-12 19:15:00", "anonymous", "LOW", amount = 90.0)
        )
        val result = CustomerAnalyticsEngine.analyze(
            rows, "2026-10-12", "2026-10-12", LocalDate.parse("2026-10-12")
        )
        assertEquals(2, result.summary.customerCount)
        assertEquals(1, result.summary.newCustomerCount)
        assertEquals(1, result.summary.oldCustomerCount)
        assertEquals(3, result.summary.paymentCount)
        assertEquals(130.0, result.summary.netRevenue, 0.001)
        assertEquals(20.0, result.summary.averageCustomerValue, 0.001)
        assertEquals(1, result.summary.lowConfidenceCount)
        assertFalse(result.change.available)
    }

    @Test fun legacyNameOnlyPaymentsDoNotBecomeNewOrReturningCustomers() {
        // Old imports marked masked names MEDIUM. The analysis must exclude
        // them immediately, even without reimport or altering original bills.
        val rows = listOf(
            payment(1, "WECHAT", "2026-10-13 18:00:00", "masked-shared-key", "MEDIUM", amount = 20.0),
            payment(2, "WECHAT", "2026-10-14 18:20:00", "masked-shared-key", "MEDIUM", amount = 35.0),
            payment(3, "WECHAT", "2026-10-14 19:30:00", "anon", "LOW", amount = 10.0)
        )
        assertFalse(PaymentTrafficAnalytics.isIdentified(rows[0]))
        assertFalse(PaymentTrafficAnalytics.isIdentified(rows[1]))
        val result = CustomerAnalyticsEngine.analyze(
            rows, "2026-10-14", "2026-10-14", LocalDate.parse("2026-10-14")
        )
        assertEquals(2, result.summary.paymentCount)
        assertEquals(45.0, result.summary.netRevenue, 0.001)
        assertEquals(0, result.summary.customerCount)
        assertEquals(0, result.summary.newCustomerCount)
        assertEquals(0, result.summary.oldCustomerCount)
        assertEquals(0, result.summary.repeatCustomerCount)
        val daily = CustomerAnalyticsEngine.dailyTraffic(
            rows, "2026-10-13", "2026-10-14"
        )
        assertEquals(listOf(1, 2), daily.map { it.paymentCount })
        assertEquals(listOf(0, 0), daily.map { it.customerCount })
        assertEquals(listOf(20.0, 45.0), daily.map { it.amount })
    }

    @Test fun crossPlatformIdentitiesAreNotSilentlyMerged() {
        val rows = listOf(
            payment(1, "WECHAT", "2026-10-12 19:00:00", "wechat-person"),
            payment(2, "ALIPAY", "2026-10-12 19:05:00", "alipay-person")
        )
        val combined = CustomerAnalyticsEngine.hourlyTraffic(rows, "2026-10-12")[19]
        assertEquals(2, combined.customerCount)
        assertTrue(PaymentTrafficAnalytics.isIdentified(rows[0]))
        assertFalse(PaymentTrafficAnalytics.isIdentified(payment(
            3, "WECHAT", "2026-10-12 19:06:00", "anonymous", "LOW"
        )))
    }
}
