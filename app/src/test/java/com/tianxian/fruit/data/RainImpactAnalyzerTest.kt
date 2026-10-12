package com.tianxian.fruit.data

import com.tianxian.fruit.sync.WeatherHour
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class RainImpactAnalyzerTest {
    private val date = LocalDate.parse("2026-10-11")

    private fun weather(time: String, rain: Double, actual: Boolean = true) = WeatherHour(
        time = "2026-10-11T$time:00",
        text = if (rain > 0.05) "小雨" else "晴",
        code = if (rain > 0.05) "305" else "100",
        temperature = 26.0, feelsLike = null, humidity = null,
        precipitation = rain, precipitationProbability = if (rain > 0.0) 75.0 else 10.0,
        windDirection = "", windScale = "", windSpeed = null, actualAvailable = actual
    )

    private fun payment(
        id: Long,
        time: String,
        amount: Double = 20.0,
        store: Long = 1,
        key: String = "a"
    ) = CustomerPaymentRecord(
        id = id, platform = "WECHAT", tradeTime = "2026-10-11 $time:00",
        businessDate = "2026-10-11",
        amount = amount, refundAmount = 0.0, netAmount = amount,
        customerKey = key, customerName = key, identityConfidence = "HIGH",
        transactionType = "经营收款", tradeStatus = "SUCCESS",
        storeId = store, storeName = if (store == 1L) "游乐场" else "九龙公园"
    )

    @Test fun beforeDuringAfterRainUseActualWeatherAndElectronicReceipts() {
        val hours = listOf(
            weather("17:00", 0.0), weather("18:00", 2.0),
            weather("19:00", 1.0), weather("20:00", 0.0)
        )
        val payments = listOf(
            payment(1, "17:15"), payment(2, "18:30"), payment(3, "19:20"),
            payment(4, "20:18"), payment(5, "18:50", store = 2),
            payment(6, "20:40", -20.0), payment(7, "18:21", store = 0)
        )
        val report = RainImpactAnalyzer.analyze(
            date, 1, "17:00", "21:00", 2600.0, hours, payments, true
        )
        assertEquals(4, report.actualWeatherHours)
        assertEquals(2, report.wetHours)
        assertEquals(1, report.before.paymentCount)
        assertEquals(2, report.rainy.paymentCount)
        assertEquals(1, report.after.paymentCount)
        assertEquals(1, report.unassignedPaymentCount)
        assertEquals(2600.0, report.totalOperatingRevenue, 0.01)
        assertEquals(40.0, report.rainy.electronicRevenue, 0.01)
        assertTrue(report.observations.any { it.contains("同日电子收款") })
    }

    @Test fun forecastOnlyHoursCannotBeCalledActualRain() {
        val report = RainImpactAnalyzer.analyze(
            date, 1, "16:00", "20:00", 1900.0,
            listOf(weather("18:00", 3.0, actual = false)),
            listOf(payment(1, "18:10")), false
        )
        assertEquals(0, report.actualWeatherHours)
        assertEquals(0, report.wetHours)
        assertTrue(report.missing.any { it.contains("历史降雨") })
        assertEquals(1900.0, report.totalOperatingRevenue, 0.01)
    }

    @Test fun missingReceiptsAndSparseWeatherStillDescribeAvailableFacts() {
        val report = RainImpactAnalyzer.analyze(
            date, 1, "16:00", "24:00", 2305.0,
            listOf(weather("18:00", 1.0)), emptyList(), true
        )
        assertEquals(1, report.wetHours)
        assertEquals(1, report.actualWeatherHours)
        assertEquals(0, report.rainy.paymentCount)
        assertTrue(report.missing.any { it.contains("7/8") || it.contains("1/8") })
        assertTrue(report.missing.any { it.contains("电子收款") })
    }

    @Test fun unassignedAndOtherStoreReceiptsAreNeverMixed() {
        val report = RainImpactAnalyzer.analyze(
            date, 1, "16:00", "20:00", 800.0,
            listOf(weather("17:00", 1.0)),
            listOf(payment(1, "17:00", store = 2), payment(2, "17:12", store = 0)), true
        )
        assertEquals(0, report.assignedPaymentCount)
        assertEquals(1, report.unassignedPaymentCount)
        assertEquals(0, report.rainy.paymentCount)
    }

    @Test fun midnightBoundaryIsSupported() {
        val overnight = weather("23:00", 1.0)
        val report = RainImpactAnalyzer.analyze(
            date, 1, "22:00", "02:00", 1200.0,
            listOf(overnight), listOf(payment(1, "23:55")), true
        )
        assertEquals(4, report.expectedHours)
        assertEquals(1, report.rainy.paymentCount)
    }

    @Test fun onlyPastObservedHoursCanSupportPastDateDiagnosis() {
        val report = RainImpactAnalyzer.analyze(
            date, 1, "16:00", "20:00", 1800.0,
            listOf(weather("16:00", 0.0), weather("17:00", 0.0)),
            listOf(payment(1, "16:30")), true
        )
        assertEquals(0, report.wetHours)
        assertEquals(2, report.actualWeatherHours)
        assertTrue(report.observations.any { it.contains("未发现实况降雨") })
    }
}
