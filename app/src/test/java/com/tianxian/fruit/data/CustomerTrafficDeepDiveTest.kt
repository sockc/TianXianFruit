package com.tianxian.fruit.data

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomerTrafficDeepDiveTest {
    private fun record(
        id: Long,
        time: String,
        storeId: Long = 1L,
        platform: String = "WECHAT",
        amount: Double = 16.0
    ) = CustomerPaymentRecord(
        id = id, platform = platform, tradeTime = time,
        businessDate = time.take(10), amount = amount,
        refundAmount = 0.0, netAmount = amount,
        customerKey = "unknown-$id", customerName = "来自*某",
        identityConfidence = "LOW", transactionType = "经营收款",
        tradeStatus = "成功", storeId = storeId,
        storeName = if (storeId == 1L) "九龙公园" else "",
        transactionRef = "order-$id"
    )

    private fun session(date: String, storeId: Long = 1L,
        start: String = "16:00", end: String = "24:00"
    ) = CustomerTrafficSession(
        date, storeId, if (storeId == 1L) "九龙公园" else "其他位置", start, end
    )

    @Test fun sameStoreWeekdayUsesActualBusinessDaysAndCompletedHours() {
        val days = listOf("2026-09-16", "2026-09-23",
            "2026-09-30", "2026-10-07", "2026-10-14")
        val sessions = days.map { session(it) } + session("2026-10-14", 2)
        // 09-23 has no imported bills: never count it as zero or a comparison day.
        val records = listOf(
            record(1,"2026-09-16 19:08:00"),
            record(2,"2026-09-30 19:08:00"),
            record(3,"2026-10-07 19:08:00"),
            record(4,"2026-10-14 19:08:00"),
            record(5,"2026-10-14 20:15:00"),
            record(6,"2026-10-14 19:10:00",storeId = 2)
        )
        val comparisons = CustomerTrafficDeepDive.compare(
            records, sessions, "2026-10-14", "WECHAT",
            selectedStoreId = 1L, now = LocalDateTime.parse("2026-10-14T20:30:00")
        )
        val result = comparisons.single()
        assertEquals(listOf("2026-10-07","2026-09-30","2026-09-16"),result.historicDates)
        assertTrue(result.sampleSufficient)
        assertEquals(listOf(16,17,18,19),result.hourRows.map { it.hour })
        assertEquals(1,result.currentPayments) // 20:15 is unfinished hour
        assertEquals(1.0,result.baselinePayments!!,0.001)
        assertEquals(1.0,result.hourRows.single { it.hour == 19 }.historicAverage!!,0.001)
    }

    @Test fun combinedSourceMismatchIsNotSilentlyCompared() {
        val sessions = listOf(session("2026-10-07"),session("2026-10-14"))
        val records = listOf(
            record(1,"2026-10-07 18:00:00"),
            record(2,"2026-10-07 19:00:00",platform = "ALIPAY"),
            record(3,"2026-10-14 18:00:00")
        )
        val all = CustomerTrafficDeepDive.compare(records,sessions,"2026-10-14",
            now=LocalDateTime.parse("2026-10-15T01:00:00")).single()
        assertEquals(0,all.historicDates.size)
        assertFalse(all.sampleSufficient)
        val wechat = CustomerTrafficDeepDive.compare(records,sessions,"2026-10-14",
            platform="WECHAT",now=LocalDateTime.parse("2026-10-15T01:00:00")).single()
        assertEquals(1,wechat.historicDates.size)
    }

    @Test fun ambiguousStoresAreNotGuessedAndMidnightIsAssignedOnlyFromSavedShift() {
        val afterMidnight = record(1,"2026-10-15 00:08:00",storeId=0L)
        val a = session("2026-10-14", 1, "16:00","02:00")
        val b = session("2026-10-14", 2, "16:00","02:00")
        val identified = CustomerTrafficDeepDive.attributeBusinessDays(
            listOf(afterMidnight),listOf(a)
        ).single()
        assertEquals("2026-10-14",identified.businessDate)
        assertEquals(1L,identified.storeId)
        assertEquals("2026-10-15",afterMidnight.businessDate)
        val ambiguous = CustomerTrafficDeepDive.attributeBusinessDays(
            listOf(afterMidnight),listOf(a,b)
        ).single()
        assertEquals("2026-10-15",ambiguous.businessDate)
        assertEquals(0L,ambiguous.storeId)
        val knownStore = afterMidnight.copy(storeId=2L)
        val kept = CustomerTrafficDeepDive.attributeBusinessDays(
            listOf(knownStore),listOf(a)
        ).single()
        assertEquals("2026-10-15",kept.businessDate)
    }

    @Test fun uniqueDaytimeShiftCanSafelyMatchPreviouslyUnassignedPayment() {
        val receipt = record(40, "2026-10-14 18:05:00", storeId = 0L)
        val sessions = listOf(
            session("2026-10-14", 1, "16:00", "20:00"),
            session("2026-10-14", 2, "20:00", "24:00")
        )
        val assigned = CustomerTrafficDeepDive.attributeBusinessDays(
            listOf(receipt), sessions
        ).single()
        assertEquals(1L, assigned.storeId)
        assertEquals("2026-10-14", assigned.businessDate)
        assertEquals(0L, receipt.storeId)
        assertEquals(1L, CustomerTrafficDeepDive.matchSession(
            receipt.tradeTime, sessions
        )?.storeId)
    }

    @Test fun soleStoreWithoutTimestampEvidenceIsNotGuessed() {
        val receipt = record(41, "2026-10-14 11:05:00", storeId = 0L)
        val sessions = listOf(session("2026-10-14", 1, "16:00", "23:00"))
        assertNull(CustomerTrafficDeepDive.matchSession(receipt.tradeTime, sessions))
        assertEquals(0L, CustomerTrafficDeepDive.attributeBusinessDays(
            listOf(receipt), sessions
        ).single().storeId)
    }

    @Test fun overlappingWindowsAndInvalidTimesRemainUnmatched() {
        val receipt = record(42, "2026-10-14 19:05:00", storeId = 0L)
        val sessions = listOf(
            session("2026-10-14", 1, "16:00", "23:00"),
            session("2026-10-14", 2, "18:00", "24:00")
        )
        assertNull(CustomerTrafficDeepDive.matchSession(receipt.tradeTime, sessions))
        assertEquals(0L, CustomerTrafficDeepDive.attributeBusinessDays(
            listOf(receipt), sessions
        ).single().storeId)
        assertNull(CustomerTrafficDeepDive.matchSession("invalid", sessions))
    }

    @Test fun existingStoreAndRefundKeepTheirOriginalEvidence() {
        val original = record(43, "2026-10-14 18:01:00", storeId = 2L)
        val refund = record(44, "2026-10-14 18:02:00", storeId = 0L, amount = -12.0)
        val records = CustomerTrafficDeepDive.attributeBusinessDays(
            listOf(original, refund), listOf(session("2026-10-14", 1))
        )
        assertEquals(2L, records[0].storeId)
        assertEquals(0L, records[1].storeId)
    }

    @Test fun positivePaymentsOnlyDetermineAmountBands() {
        val amounts=listOf(9.0,10.0,19.0,20.0,29.0,30.0,49.0,50.0)
        val records=amounts.mapIndexed { index, amt ->
            record((index+1).toLong(),"2026-10-14 18:00:00",amount=amt)
        } + record(99,"2026-10-14 18:00:00", amount=-10.0)
        val bands=CustomerTrafficDeepDive.amountBands(records)
        assertEquals(listOf(1,2,2,2,1),bands.map { it.payments })
        assertEquals(1.0,bands.sumOf { it.share },0.00001)
    }
}
