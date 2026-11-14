package com.tianxian.fruit.data

import com.tianxian.fruit.sync.WeatherHour
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.*
import org.junit.Test

class CustomerWeatherImpactAnalyzerTest {
    private fun hour(date: String, time: String, rain: Boolean, observed: Boolean = true) =
        WeatherHour(
            time = "${date}T${time}:00", text = if (rain) "小雨" else "晴",
            code = if (rain) "305" else "100", temperature = 25.0,
            feelsLike = null, humidity = null, precipitation = if (rain) 1.2 else 0.0,
            precipitationProbability = null, windDirection = "", windScale = "",
            windSpeed = null, actualAvailable = observed
        )
    private fun day(date: String, rainy: Boolean, observed: Boolean = true,
                    store: Long = 1L): CustomerWeatherImpactDay {
        val s = CustomerTrafficSession(date, store, "九龙公园", "17:00", "21:00")
        return CustomerWeatherImpactDay(s, listOf(
            hour(date, "17:00", false, observed),
            hour(date, "18:00", rainy, observed),
            hour(date, "19:00", rainy, observed),
            hour(date, "20:00", false, observed)
        ), observed)
    }
    private fun receipt(date: String, hour: Int, id: Long, platform: String = "WECHAT",
                        store: Long = 1L) = CustomerPaymentRecord(
        id = id, platform = platform,
        tradeTime = "$date ${hour.toString().padStart(2, '0')}:20:00",
        businessDate = date, amount = 20.0, refundAmount = 0.0, netAmount = 20.0,
        customerKey = "anonymous-$id", customerName = "匿名", identityConfidence = "LOW",
        transactionType = "经营收款", tradeStatus = "SUCCESS",
        storeId = store, storeName = "九龙公园"
    )
    private val now = LocalDateTime.parse("2026-11-20T12:00:00")

    @Test fun beforeDuringAfterAndSharedAreExcluded() {
        val date = "2026-11-11"
        val payments = listOf(receipt(date,17,1), receipt(date,18,2),
            receipt(date,19,3), receipt(date,20,4), receipt(date,18,5,store=2),
            receipt(date,18,6,store=0))
        val result = CustomerWeatherImpactAnalyzer.analyze(
            selected=listOf(day(date,true)), historical=emptyList(),
            receipts=payments, platform="WECHAT", sharedPaymentCount=1,now=now
        )
        assertEquals(1,result.before.paymentCount)
        assertEquals(2,result.rainy.paymentCount)
        assertEquals(1,result.after.paymentCount)
        assertEquals(4,result.observedHours)
        assertEquals(4,result.hourly.size)
        assertTrue(result.warnings.any { it.contains("共享") })
        assertNull(result.history.paymentDifference)
    }

    @Test fun missingArchiveCannotCreateFalseRainComparison() {
        val date="2026-11-11"
        val result = CustomerWeatherImpactAnalyzer.analyze(
            listOf(day(date,true,observed=false)), emptyList(),
            listOf(receipt(date,18,1)), "WECHAT", now=now
        )
        assertEquals(0,result.observedHours)
        assertEquals(0,result.rainy.paymentCount)
        assertTrue(result.warnings.any { it.contains("预报") })
    }

    @Test fun sameWeekdaySameHourWithThreeRainAndThreeDryDays() {
        val current="2026-11-11"
        val historyDates=(1..6).map { LocalDate.parse(current).minusWeeks(it.toLong()).toString() }
        val baseline=historyDates.mapIndexed { i,date -> day(date,i<3) }
        val pay=mutableListOf(receipt(current,18,1))
        historyDates.forEachIndexed { i,date ->
            val n=if(i<3) 2 else 4
            repeat(n) { k -> pay.add(receipt(date,18,100L+i*10+k)) }
        }
        val result=CustomerWeatherImpactAnalyzer.analyze(
            listOf(day(current,true)),baseline,pay,"WECHAT",now=now
        )
        assertEquals(3,result.history.rainyDays)
        assertEquals(3,result.history.dryDays)
        assertTrue(result.history.reliable)
        assertEquals(-0.5,result.history.paymentDifference!!,0.001)
    }

    @Test fun combinedChannelMustBeCompleteBeforePercentage() {
        val current="2026-11-11"
        val dates=(1..6).map { LocalDate.parse(current).minusWeeks(it.toLong()).toString() }
        val baseline=dates.mapIndexed { index,date->day(date,index<3) }
        val payments=(listOf(receipt(current,18,1)) +
            dates.mapIndexed { i,date -> receipt(date,18,(i+10).toLong()) })
        val result=CustomerWeatherImpactAnalyzer.analyze(
            listOf(day(current,true)),baseline,payments,"ALL",now=now
        )
        assertFalse(result.history.reliable)
        assertNull(result.history.paymentDifference)
        assertTrue(result.warnings.any { it.contains("支付宝") })
    }

    @Test fun futureAndForecastHoursDoNotCountAsObservations() {
        val date="2026-11-19"
        val result=CustomerWeatherImpactAnalyzer.analyze(
            listOf(day(date,true)),emptyList(),listOf(receipt(date,18,1)),
            "WECHAT",now=LocalDateTime.parse("2026-11-19T18:20")
        )
        assertEquals(1,result.observedHours)
        assertEquals(0,result.rainy.observedHours)
    }
}
