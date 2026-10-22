package com.tianxian.fruit.data

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomerLocationHistoryAnalyzerTest {
    private fun session(date: String, start: String = "16:00",
                        end: String = "24:00", store: Long = 7L) =
        CustomerTrafficSession(date, store, "九龙公园", start, end)

    private fun payment(id: Long, time: String, date: String = time.take(10),
                        amount: Double = 10.0, platform: String = "WECHAT",
                        store: Long = 7L) = CustomerPaymentRecord(
        id = id, platform = platform, tradeTime = time, businessDate = date,
        amount = amount, refundAmount = 0.0, netAmount = amount,
        customerKey = "payer-$id", customerName = "匿名",
        identityConfidence = "LOW", transactionType = "收款",
        tradeStatus = "成功", storeId = store, storeName = "九龙公园"
    )

    private val wed = listOf("2026-09-02","2026-09-09","2026-09-16",
        "2026-09-23","2026-09-30","2026-10-07","2026-10-14","2026-10-21")
    private val future = LocalDateTime.parse("2026-10-23T01:00:00")

    @Test fun fullDayAmountsNeverLeakIntoCompletedHourBaseline() {
        val shifts = wed.takeLast(4).map { session(it, end = "20:00") }
        val rows = shifts.flatMapIndexed { i, day ->
            listOf(payment((i * 10 + 1).toLong(), "${day.date} 19:10:00", amount=20.0),
                // Same recorded store, but outside official selling hours.
                payment((i * 10 + 2).toLong(), "${day.date} 22:10:00", amount=500.0))
        }
        val result = CustomerLocationHistoryAnalyzer.analyze(
            rows, shifts, "2026-10-21", 7, now=future
        )!!
        assertEquals(listOf(16,17,18,19), result.comparableHours)
        assertEquals(1, result.current.count)
        assertEquals(20.0, result.current.amount, 0.001)
        assertEquals(3, result.historicDays.size)
        assertEquals(1.0, result.historicCount!!, 0.001)
        assertEquals(20.0, result.historicAmount!!, 0.001)
        assertEquals(1, result.hours.single { it.hour == 19 }.count)
        assertEquals(20.0, result.hours.single { it.hour == 19 }.historicAmount!!, 0.001)
        assertFalse(result.period.sufficient)
    }

    @Test fun fourRecentVersusFourEarlierCompareOnlySameWeekdayDays() {
        val shifts = wed.map { session(it) }
        var id=1L
        val rows = wed.flatMapIndexed { i, date ->
            List(if (i >= 4) 2 else 4) {
                payment(id++, "$date 19:10:00", amount = 10.0)
            }
        }
        val result = CustomerLocationHistoryAnalyzer.analyze(
            rows, shifts, "2026-10-21",7, now=future
        )!!
        assertEquals(4,result.historicDays.size)
        assertEquals(2.5,result.historicCount!!,0.001)
        assertTrue(result.period.sufficient)
        assertEquals(4,result.period.recent.size)
        assertEquals(4,result.period.earlier.size)
        assertEquals(2.0,result.period.recentDailyCount!!,0.001)
        assertEquals(4.0,result.period.earlierDailyCount!!,0.001)
        assertEquals(20.0,result.period.recentDailyAmount!!,0.001)
        assertEquals(40.0,result.period.earlierDailyAmount!!,0.001)
        assertEquals(10.0,result.period.recentAveragePayment!!,0.001)
        assertEquals(listOf("2026-10-21","2026-10-14","2026-10-07","2026-09-30"),
            result.period.recent.map { it.date })
    }

    @Test fun missingBillsOrChannelMismatchNeverBecomeZeroComparables() {
        val shifts = wed.takeLast(5).map { session(it) }
        val rows = listOf(
            payment(1, "2026-10-21 19:00:00"),
            payment(2, "2026-10-14 19:00:00"),
            payment(3, "2026-10-14 19:05:00", platform="ALIPAY"),
            payment(4, "2026-10-07 19:00:00"),
            // 2026-09-30 has no imported file.
            payment(5, "2026-09-23 19:00:00")
        )
        val report = CustomerLocationHistoryAnalyzer.analyze(
            rows, shifts, "2026-10-21",7, now=future
        )!!
        assertEquals(listOf("2026-10-07","2026-09-23"),
            report.historicDays.map { it.date })
        assertFalse(report.baselineSufficient)
        assertNull(report.historicCount)
        assertNull(report.hours.single { it.hour == 19 }.historicCount)
        assertTrue(report.warnings.any { it.contains("至少3天") })
        val wechat = CustomerLocationHistoryAnalyzer.analyze(
            rows, shifts, "2026-10-21",7, platform="WECHAT", now=future
        )!!
        assertEquals(3,wechat.historicDays.size)
        assertTrue(wechat.baselineSufficient)
    }

    @Test fun currentUnfinishedHourNotCountedAndAfterMidnightUsesBusinessDate() {
        val shifts = wed.takeLast(4).map { session(it, "16:00","02:00") }
        val rows = shifts.flatMapIndexed { i, day ->
            listOf(payment((i * 10 + 1).toLong(),
                "${day.date} 19:10:00"),
                payment((i * 10 + 2).toLong(),
                    java.time.LocalDate.parse(day.date).plusDays(1).toString() +
                    " 00:15:00", date = day.date))
        }
        val full = CustomerLocationHistoryAnalyzer.analyze(
            rows, shifts,"2026-10-21",7,now=future
        )!!
        assertEquals(2,full.current.count)
        assertTrue(0 in full.comparableHours)
        assertEquals(1,full.hours.single { it.hour == 0 }.count)

        val partial = CustomerLocationHistoryAnalyzer.analyze(
            rows,shifts,"2026-10-21",7,
            now = LocalDateTime.parse("2026-10-21T20:15:00")
        )!!
        assertEquals(1,partial.current.count)
        assertFalse(0 in partial.comparableHours)
        assertEquals(listOf(16,17,18,19),partial.comparableHours)
    }

    @Test fun sharedOrUnmatchedReceiptsNeverCountTowardsStore() {
        val shifts = wed.takeLast(4).map { session(it) }
        val rows = shifts.mapIndexed { i, day ->
            payment((i + 1).toLong(), "${day.date} 19:00:00")
        } + payment(100,"2026-10-21 19:00:00",store=0) +
            payment(101,"2026-10-21 19:00:00",store=8)
        val r = CustomerLocationHistoryAnalyzer.analyze(rows,shifts,"2026-10-21",7,
            now=future)!!
        assertEquals(1,r.current.count)
        assertEquals(10.0,r.current.amount,0.001)
        assertNull(CustomerLocationHistoryAnalyzer.analyze(rows,shifts,
            "2026-10-21",0,now=future))
    }

    @Test fun biggestHourChangesRequireMeaningfulHistoricalBaseline() {
        val shifts = wed.takeLast(4).map { session(it) }
        var id = 1L
        val rows = shifts.flatMapIndexed { i, day ->
            val current = i == 3
            List(if(current) 2 else 10) { payment(id++,"${day.date} 19:11:00") } +
                List(if(current) 12 else 6) { payment(id++,"${day.date} 20:11:00") }
        }
        val result=CustomerLocationHistoryAnalyzer.analyze(
            rows,shifts,"2026-10-21",7,now=future)!!
        assertEquals(19,result.biggestChanges(false,true).first().hour)
        assertEquals(20,result.biggestChanges(false,false).first().hour)
    }
}
