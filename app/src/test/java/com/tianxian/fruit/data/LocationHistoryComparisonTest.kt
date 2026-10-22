package com.tianxian.fruit.data

import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationHistoryComparisonTest {
    private fun shift(date: String, start: String = "16:00", end: String = "24:00",
                      store: Long = 1L) =
        CustomerTrafficSession(date,store,"九龙公园",start,end)

    private fun payment(id: Long, business: String, clock: String,
                        amount: Double = 20.0, platform: String = "WECHAT",
                        store: Long = 1L): CustomerPaymentRecord {
        val date = LocalDate.parse(business)
        val tradeDate = if (clock.substringBefore(':').toInt() < 6) date.plusDays(1) else date
        return CustomerPaymentRecord(
            id, platform,"$tradeDate $clock",business,amount,0.0,amount,
            "anon-$id","匿名","LOW","经营收款","成功",store,"九龙公园"
        )
    }

    @Test fun sameCompletedHoursGovernTotalsHourlyAndRecentPeriods() {
        val date = LocalDate.parse("2026-10-21")
        val days = (0..8).map { date.minusWeeks(it.toLong()).toString() }
        val sessions = days.map { shift(it) }
        val receipts = days.mapIndexed { index, day ->
            payment(index.toLong()+1,day,"19:05:00", if(index < 4) 20.0 else 40.0)
        } + payment(100,"2026-10-21","20:15:00",99.0) +
            payment(101,"2026-10-21","14:15:00",300.0)
        val inspected = LocationHistoryComparisonEngine.inspect(
            receipts,sessions,1L,"2026-10-21","WECHAT",
            LocalDateTime.parse("2026-10-21T20:30:00")
        )!!
        assertEquals(listOf(16,17,18,19),inspected.hourRows.map { it.hour })
        assertEquals(1,inspected.currentPayments) // unfinished 20:00, outside-shift 14:00 excluded
        assertEquals(20.0,inspected.currentAmount,0.001)
        assertEquals(4,inspected.historicDates.size)
        assertEquals(1.0,inspected.baselinePayments!!,0.001)
        assertEquals(25.0,inspected.baselineAmount!!,0.001)
        assertEquals(1,inspected.hourRows.first { it.hour==19 }.payments)
        assertEquals(1.0,inspected.hourRows.first { it.hour==19 }.historicalPayments!!,0.001)
        assertEquals(4, inspected.recentTrend!!.recentDates.size)
        assertEquals(4, inspected.recentTrend!!.earlierDates.size)
    }

    @Test fun historicalFourVsFourUsesSameWeekdayAndActualHours() {
        val date = LocalDate.parse("2026-10-21")
        val days = (0..8).map { date.minusWeeks(it.toLong()).toString() }
        val sessions = days.map { shift(it,"16:00","22:00") } +
            shift("2026-10-20","16:00","22:00")
        val receipts = days.mapIndexed { index,day ->
            payment(index.toLong()+1,day,"19:20:00",if(index < 4) 10.0 else 30.0)
        } + payment(99,"2026-10-20","19:10:00",500.0)
        val result = LocationHistoryComparisonEngine.inspect(
            receipts,sessions,1L,days[0],"WECHAT",
            LocalDateTime.parse("2026-10-22T03:00:00")
        )!!
        val trend = result.recentTrend!!
        assertEquals(days.take(4),trend.recentDates)
        assertEquals(days.drop(4).take(4),trend.earlierDates)
        assertEquals(10.0,trend.recentAmount,0.001)
        assertEquals(30.0,trend.earlierAmount,0.001)
        assertEquals(1.0,trend.recentPayments,0.001)
        assertEquals(1.0,trend.earlierPayments,0.001)
        assertEquals(10.0,result.currentAmount,0.001)
        assertEquals(15.0,result.baselineAmount!!,0.001)
    }

    @Test fun absentReceiptOrDifferentChannelIsNotFakeZero() {
        val d = LocalDate.parse("2026-10-21")
        val days=(0..9).map { d.minusWeeks(it.toLong()).toString() }
        val sessions=days.map { shift(it) }
        val receipts=days.mapIndexedNotNull { index,day ->
            when(index) {
                3 -> null // missing export
                4 -> payment(4,day,"19:12:00",platform="ALIPAY")
                else -> payment(index.toLong()+20,day,"19:12:00")
            }
        }
        val res=LocationHistoryComparisonEngine.inspect(
            receipts,sessions,1,days[0],"ALL",
            LocalDateTime.parse("2026-10-22T02:00:00")
        )!!
        assertFalse(res.historicDates.contains(days[3]))
        assertFalse(res.historicDates.contains(days[4]))
        assertEquals(4,res.historicDates.size)
        assertTrue(res.coverageNote.contains("另一渠道"))
        assertEquals(8,res.operatingDays.size)
        assertEquals(0,res.operatingDays.first {it.date == days[3]}.payments)
        // The UI must render that no-payment day as "待核验" rather than 0.
    }

    @Test fun overnightWindowUsesPreviousBusinessDateAndExcludesOutsideReceipts() {
        val target="2026-10-21"
        val days=(0..3).map { LocalDate.parse(target).minusWeeks(it.toLong()).toString() }
        val receipts=days.mapIndexed { i,day ->
            payment(i.toLong()+1,day,"00:15:00")
        } + payment(10,target,"03:30:00")
        val result=LocationHistoryComparisonEngine.inspect(
            receipts,days.map { shift(it,"18:00","02:00") },1,target,"WECHAT",
            LocalDateTime.parse("2026-10-22T03:00:00")
        )!!
        assertEquals(1,result.currentPayments)
        assertEquals(1.0,result.baselinePayments!!,0.001)
        assertTrue(result.hourRows.any { it.hour==0 && it.payments==1 })
        assertFalse(result.hourRows.any { it.hour==3 })
    }

    @Test fun differentOpeningTimeCannotEstablishSameHours() {
        val d=LocalDate.parse("2026-10-21")
        val days=(0..3).map { d.minusWeeks(it.toLong()).toString() }
        val sessions=listOf(shift(days[0],"16:00","22:00")) +
            days.drop(1).map { shift(it,"18:00","22:00") }
        val payments=days.mapIndexed { i,day -> payment(i.toLong()+1,day,"19:00:00") }
        val result=LocationHistoryComparisonEngine.inspect(
            payments,sessions,1,days[0],"WECHAT",
            LocalDateTime.parse("2026-10-22T01:00:00")
        )!!
        assertEquals(0,result.historicDates.size)
        assertNull(result.baselineAmount)
        assertNull(result.recentTrend)
    }

    @Test fun positiveAndNegativeHoursAreRankedOnlyWithSufficientSamples() {
        val d=LocalDate.parse("2026-10-21")
        val days=(0..3).map {d.minusWeeks(it.toLong()).toString()}
        var i=0L
        val rows=buildList {
            // Target has 1 receipt at 19 and 5 receipts at 20.
            add(payment(++i,days[0],"19:05:00"))
            repeat(5) { add(payment(++i,days[0],"20:05:00")) }
            for (day in days.drop(1)) {
                repeat(4) { add(payment(++i,day,"19:05:00")) }
                add(payment(++i,day,"20:05:00"))
            }
        }
        val result=LocationHistoryComparisonEngine.inspect(
            rows,days.map {shift(it)},1,days[0],"WECHAT",
            LocalDateTime.parse("2026-10-22T01:00:00")
        )!!
        assertEquals(19,result.decliningHours.first().hour)
        assertEquals(20,result.growingHours.first().hour)
        assertEquals(3,result.historicDates.size)
    }
}
