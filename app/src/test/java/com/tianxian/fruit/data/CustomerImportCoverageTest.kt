package com.tianxian.fruit.data

import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomerImportCoverageTest {
    private fun receipt(
        id: Long, date: String, platform: String = "WECHAT",
        amount: Double = 12.0
    ) = CustomerPaymentRecord(
        id = id, platform = platform, tradeTime = "$date 18:05:00",
        businessDate = date, amount = amount, refundAmount = 0.0,
        netAmount = amount, customerKey = "", customerName = "匿名",
        identityConfidence = "LOW", transactionType = "经营收款",
        tradeStatus = "成功", storeId = 0L, storeName = ""
    )

    @Test fun providerSummariesNeverClaimGapDaysAreComplete() {
        val records = listOf(
            receipt(1, "2026-10-01"), receipt(2, "2026-10-14"),
            receipt(3, "2026-10-10", platform = "ALIPAY")
        )
        val summaries = CustomerImportCoverageEngine.overview(records)
        assertEquals("2026-10-01", summaries[0].firstDate)
        assertEquals("2026-10-14", summaries[0].lastDate)
        assertEquals(2, summaries[0].observedDays) // NOT 14 complete days
        assertEquals(2, summaries[0].payments)
        assertEquals("2026-10-10", summaries[1].firstDate)
        assertEquals(1, summaries[1].observedDays)
    }

    @Test fun refundDoesNotCountAsPositivePaymentCoverage() {
        val data = listOf(
            receipt(1, "2026-10-01", amount = 16.0),
            receipt(2, "2026-10-02", amount = -5.0)
        )
        val coverage = CustomerImportCoverageEngine.overview(data)
        assertEquals("2026-10-01", coverage[0].lastDate)
        assertNull(coverage[1].firstDate)
    }

    @Test fun monthDailyCoverageShowsUnknownRatherThanInventedZero() {
        val records = listOf(
            receipt(1, "2026-10-14"),
            receipt(2, "2026-10-14", platform = "ALIPAY")
        )
        val sessions = listOf(
            CustomerTrafficSession("2026-10-13", 1, "九龙公园", "16:00", "24:00")
        )
        val days = CustomerImportCoverageEngine.days(records, sessions, YearMonth.of(2026, 10))
        assertEquals(31, days.size)
        assertEquals(1, days[13].wechatPayments)
        assertEquals(1, days[13].alipayPayments)
        assertTrue(days[12].hasSavedBusiness)
        assertEquals(0, days[12].wechatPayments)
    }

    @Test fun billFileRangeDoesNotExpandDatesWithoutBusinessPayments() {
        val parsed = listOf(
            parsed("2026-10-03 16:30:00"),
            parsed("2026-10-07 18:00:00"),
            parsed("2026-10-10 18:00:00", amount = -3.0)
        )
        assertEquals(
            Triple("2026-10-03", "2026-10-07", 2),
            CustomerImportCoverageEngine.importRange(parsed)
        )
    }

    private fun parsed(time: String, amount: Double = 15.0) =
        ParsedBusinessReceipt(
            platform = PaymentBillPlatform.WECHAT,
            tradeTime = time, businessDate = time.take(10),
            amount = amount, refundAmount = 0.0, netAmount = amount,
            transactionRef = "ref-$time", dedupeKey = "key-$time",
            customerKey = "", customerName = "",
            identityConfidence = "LOW", transactionType = "经营收款",
            tradeStatus = "成功"
        )
}
