package com.tianxian.fruit.data

import org.junit.Assert.*
import org.junit.Test

class CustomerOverviewPoliciesTest {
    private fun row(id: Long, day: String, platform: String = "WECHAT", value: Double = 20.0,
                    store: Long = 1L, hour: Int = 18) =
        CustomerPaymentRecord(id, platform, "$day ${hour.toString().padStart(2, '0')}:00:00",
            day, value, 0.0, value, "", "", "NONE", "二维码收款",
            "成功", store, "九龙公园")

    @Test fun missingExportIsNotZero() {
        assertEquals(PaymentDayCoverage.NEED_IMPORT,
            CustomerOverviewPolicies.coverage(true, emptyList()))
        assertEquals(PaymentDayCoverage.NO_BUSINESS,
            CustomerOverviewPolicies.coverage(false, emptyList()))
        assertEquals(PaymentDayCoverage.PARTIAL,
            CustomerOverviewPolicies.coverage(true,listOf(row(1,"2026-10-19"))))
        assertEquals(PaymentDayCoverage.OBSERVED,
            CustomerOverviewPolicies.coverage(true,listOf(
                row(1,"2026-10-19"),row(2,"2026-10-19","ALIPAY"))))
    }

    @Test fun storeComparisonUsesObservedBusinessDayAveragesNotGrandTotal() {
        val shifts = listOf(
            CustomerTrafficSession("2026-10-12",1,"九龙公园","16:00","24:00"),
            CustomerTrafficSession("2026-10-19",1,"九龙公园","16:00","24:00"),
            CustomerTrafficSession("2026-10-19",2,"凰岗村","16:00","24:00"))
        val result = CustomerOverviewPolicies.performance(listOf(
            row(1,"2026-10-12"),row(2,"2026-10-19"),
            row(3,"2026-10-19",store=2),row(4,"2026-10-19",store=2)),
            shifts,"2026-10-01","2026-10-31")
        val one = result.first { it.storeId == 1L }
        val two = result.first { it.storeId == 2L }
        assertEquals(2, one.observedBusinessDays)
        assertEquals(1.0, one.dailyPayments,0.0001)
        assertEquals(2.0,two.dailyPayments,0.0001)
        assertEquals(0.125,one.hourlyPayments!!,0.0001)
    }

    @Test fun comparisonsWithoutSamplesDoNotClaimDecline() {
        val c = CustomerTrafficComparison(1,"九龙公园","2026-10-19",
            listOf(CustomerTrafficHourComparison(18,2,null,0)),emptyList(),2,null,18,
            emptyList(),"微信",emptyList())
        assertTrue(CustomerOverviewPolicies.headline(listOf(c),1,emptyList()).contains("至少3个"))
        assertTrue(CustomerOverviewPolicies.headline(listOf(c),0,emptyList()).contains("单个"))
    }
}
