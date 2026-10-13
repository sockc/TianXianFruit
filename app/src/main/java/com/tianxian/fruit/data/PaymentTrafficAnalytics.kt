package com.tianxian.fruit.data

/**
 * Presentation-only views of imported QR/business receipts.
 * Does not modify the original bill or the official business record.
 */
data class PaymentPlatformHour(
    val hour: Int,
    val wechatPayments: Int,
    val alipayPayments: Int,
    val wechatCustomers: Int,
    val alipayCustomers: Int,
    val wechatAmount: Double,
    val alipayAmount: Double
)

data class PaymentPlatformCoverage(
    val platform: String,
    val paymentCount: Int,
    val firstDate: String?,
    val lastDate: String?
)

internal object PaymentTrafficAnalytics {
    /** 0 means all stores; -1 means explicitly unmatched (store id <= 0). */
    fun select(
        records: List<CustomerPaymentRecord>,
        platform: String = "ALL",
        storeId: Long = 0L
    ): List<CustomerPaymentRecord> = records.filter { record ->
        (platform == "ALL" || record.platform == platform) &&
            (storeId == 0L ||
                (storeId == -1L && record.storeId <= 0L) ||
                (storeId > 0L && record.storeId == storeId))
    }

    fun coverage(records: List<CustomerPaymentRecord>): List<PaymentPlatformCoverage> =
        listOf("WECHAT", "ALIPAY").map { platform ->
            val payments = records.filter { it.platform == platform && it.amount > 0.005 }
            PaymentPlatformCoverage(
                platform = platform,
                paymentCount = payments.size,
                firstDate = payments.minOfOrNull { it.businessDate },
                lastDate = payments.maxOfOrNull { it.businessDate }
            )
        }

    fun hourly(
        records: List<CustomerPaymentRecord>,
        date: String
    ): List<PaymentPlatformHour> {
        val day = records.filter { it.businessDate == date }
        val grouped = day.groupBy { hourOf(it.tradeTime) }
        return (0..23).map { hour ->
            val values = grouped[hour].orEmpty()
            val wechat = values.filter { it.platform == "WECHAT" }
            val alipay = values.filter { it.platform == "ALIPAY" }
            PaymentPlatformHour(
                hour = hour,
                wechatPayments = wechat.count { it.amount > 0.005 },
                alipayPayments = alipay.count { it.amount > 0.005 },
                wechatCustomers = identifiedCount(wechat),
                alipayCustomers = identifiedCount(alipay),
                wechatAmount = round(wechat.sumOf { it.netAmount }),
                alipayAmount = round(alipay.sumOf { it.netAmount })
            )
        }
    }

    fun isIdentified(record: CustomerPaymentRecord): Boolean =
        record.customerKey.isNotBlank() &&
            record.identityConfidence in setOf("HIGH", "MEDIUM")

    private fun identifiedCount(rows: List<CustomerPaymentRecord>): Int =
        rows.asSequence()
            .filter { it.amount > 0.005 && isIdentified(it) }
            .map { it.customerKey }
            .distinct()
            .count()

    private fun hourOf(raw: String): Int? =
        raw.takeIf { it.length >= 13 }
            ?.substring(11, 13)
            ?.toIntOrNull()
            ?.takeIf { it in 0..23 }

    private fun round(n: Double): Double = kotlin.math.round(n * 100.0) / 100.0
}
