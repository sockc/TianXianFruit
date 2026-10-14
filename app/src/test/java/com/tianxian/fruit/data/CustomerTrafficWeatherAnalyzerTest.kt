package com.tianxian.fruit.data

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomerTrafficWeatherAnalyzerTest {
    private fun payment(id: Long, date: String, hour: Int, store: Long = 1L): CustomerPaymentRecord =
        CustomerPaymentRecord(
            id=id,platform="WECHAT",tradeTime="$date ${hour.toString().padStart(2,'0')}:20:00",
            businessDate=date,amount=20.0,refundAmount=0.0,netAmount=20.0,
            customerKey="anon-${id}",customerName="来自*雨",
            identityConfidence="LOW",transactionType="经营收款",tradeStatus="SUCCESS",
            storeId=store,storeName="九龙公园",transactionRef="wx-$id"
        )
    @Test fun comparesWeatherWithRealHourReceiptsWithoutClaimingCausation() {
        val dates=listOf("2026-09-16","2026-09-23","2026-09-30","2026-10-07","2026-10-14")
        val sessions=dates.map { CustomerTrafficSession(it,1,"九龙公园","16:00","24:00") }
        val payments=dates.flatMapIndexed { i,date ->
            listOf(payment((i*10+1).toLong(),date,19),payment((i*10+2).toLong(),date,20))
        }
        val baseline=CustomerTrafficDeepDive.compare(
            rows=payments,sessions=sessions,targetDate="2026-10-14",
            now=LocalDateTime.parse("2026-10-15T01:00:00")
        )
        assertEquals(4, baseline.single().historicDates.size)
        val weather=dates.flatMapIndexed { i,date ->
            listOf(
                CustomerObservedWeatherHour(1,date,19,"小雨",1.2,true),
                CustomerObservedWeatherHour(1,date,20,
                    if (i%2==0) "小雨" else "晴",
                    if (i%2==0) 0.5 else 0.0,i%2==0)
            )
        }
        val result=CustomerTrafficWeatherAnalyzer.analyze(
            baseline,payments,weather,"WECHAT"
        ).single()
        assertEquals(2,result.observed.size)
        assertEquals(1,result.observed[0].paymentCount)
        assertEquals(true,result.observed[0].raining)
        assertTrue(result.rainDry != null)
        assertTrue(result.rainDry!!.rainySamples > 0)
        assertTrue(result.notes.any { it.contains("因果") })
    }

    @Test fun missingWeatherNeverGetsInferredFromPayments() {
        val date="2026-10-14"
        val sessions=listOf(CustomerTrafficSession(date,1,"九龙公园","16:00","24:00"))
        val rows=listOf(payment(1,date,18))
        val comparison=CustomerTrafficDeepDive.compare(rows,sessions,date,
            now=LocalDateTime.parse("2026-10-15T01:00:00"))
        val analyzed=CustomerTrafficWeatherAnalyzer.analyze(comparison,rows,emptyList())
        assertTrue(analyzed.single().observed.isEmpty())
        assertNull(analyzed.single().rainDry)
        assertFalse(analyzed.single().notes.isEmpty())
    }
}
