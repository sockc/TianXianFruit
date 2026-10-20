package com.tianxian.fruit.data

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale

/** Payment exports describe observed receipts, not certified complete business days. */
internal enum class PaymentDayCoverage { NO_BUSINESS, NEED_IMPORT, PARTIAL, OBSERVED }

internal data class CustomerStoreDailyPerformance(
    val storeId: Long,
    val storeName: String,
    val observedBusinessDays: Int,
    val operatingDays: Int,
    val dailyPayments: Double,
    val dailyRevenue: Double,
    val averagePayment: Double,
    val hourlyPayments: Double?
)

internal object CustomerOverviewPolicies {
    fun coverage(
        hasBusiness: Boolean,
        dayRows: List<CustomerPaymentRecord>,
        platform: String = "ALL"
    ): PaymentDayCoverage {
        if (!hasBusiness) return PaymentDayCoverage.NO_BUSINESS
        val payments = dayRows.filter { it.netAmount > 0.005 }
        if (payments.isEmpty()) return PaymentDayCoverage.NEED_IMPORT
        if (platform == "ALL" && payments.map { it.platform }.distinct().size < 2)
            return PaymentDayCoverage.PARTIAL
        // OBSERVED still means only "some transactions seen", never complete coverage.
        return PaymentDayCoverage.OBSERVED
    }

    fun coverageLabel(coverage: PaymentDayCoverage): String = when (coverage) {
        PaymentDayCoverage.NO_BUSINESS -> "无营业记录"
        PaymentDayCoverage.NEED_IMPORT -> "待补"
        PaymentDayCoverage.PARTIAL -> "部分"
        PaymentDayCoverage.OBSERVED -> "待核验"
    }

    /** For stable comparisons, preserve the exact completed-hour set and sample dates. */
    fun historicHourAmount(
        comparison: CustomerTrafficComparison,
        rows: List<CustomerPaymentRecord>,
        hour: Int
    ): Double? {
        if (!comparison.sampleSufficient ||
            comparison.hourRows.none { it.hour == hour }) return null
        val dates = comparison.historicDates.toSet()
        if (dates.isEmpty()) return null
        val amount = rows.asSequence().filter { row ->
            row.storeId == comparison.storeId && row.businessDate in dates &&
                row.netAmount > 0.005 && tradeHour(row.tradeTime) == hour
        }.sumOf { it.netAmount }
        return amount / dates.size
    }

    fun historicTotalAmount(
        comparison: CustomerTrafficComparison,
        rows: List<CustomerPaymentRecord>
    ): Double? {
        if (!comparison.sampleSufficient) return null
        return comparison.hourRows.sumOf { historicHourAmount(comparison, rows, it.hour) ?: 0.0 }
    }

    fun headline(
        comparisons: List<CustomerTrafficComparison>,
        selectedStoreId: Long,
        rows: List<CustomerPaymentRecord>,
        platform: String = "ALL"
    ): String {
        if (selectedStoreId <= 0L)
            return "请选择单个营业位置，才能按同位置、同星期比较"
        val comparison = comparisons.singleOrNull { it.storeId == selectedStoreId }
            ?: return "缺少可验证的完整营业时段，暂不能做小时对比"
        if (!comparison.sampleSufficient)
            return "同位置同星期仅${comparison.historicDates.size}个可比营业日（至少3个），暂不判断变化"
        if (comparison.sourceCoverage.contains("没有"))
            return "当前时段账单未覆盖，暂不能判断付款是否下降"
        if (platform == "ALL" && !comparison.sourceCoverage.contains("＋"))
            return "微信与支付宝账单覆盖未齐，暂不比较合并收款走势"
        val baseline = comparison.baselinePayments?.takeIf { it > 0.0 }
            ?: return "历史可比付款样本不足，暂不能判断下降"
        val percent = (comparison.currentPayments - baseline) / baseline * 100.0
        val total = rows.filter { row ->
            row.businessDate == comparison.date && row.storeId == comparison.storeId &&
                row.netAmount > 0.005 && tradeHour(row.tradeTime) in
                    comparison.hourRows.map { it.hour }
        }.sumOf { it.netAmount }
        val historicAmount = historicTotalAmount(comparison, rows)
        val historicTicket = if (historicAmount != null && historicAmount > 0.0)
            historicAmount / baseline else null
        val currentTicket = if (comparison.currentPayments > 0)
            total / comparison.currentPayments else null
        val ticket = if (historicTicket == null || currentTicket == null) "笔均收款无法判断"
            else {
                val change = (currentTicket - historicTicket) / historicTicket * 100.0
                if (kotlin.math.abs(change) < 5) "笔均收款基本持平"
                else String.format(Locale.CHINA, "笔均收款%+.0f%%", change)
            }
        return String.format(Locale.CHINA,
            "同位置、同星期：已观察付款笔数%+.0f%%，%s；账单覆盖仍需核验",
            percent, ticket)
    }

    private fun tradeHour(value: String): Int? =
        value.drop(11).take(2).toIntOrNull()?.takeIf { it in 0..23 }

    fun performance(
        rows: List<CustomerPaymentRecord>,
        sessions: List<CustomerTrafficSession>,
        startDate: String?,
        endDate: String?
    ): List<CustomerStoreDailyPerformance> {
        val validSessions = sessions.filter { session ->
            session.storeId > 0L &&
                (startDate == null || session.date >= startDate) &&
                (endDate == null || session.date <= endDate)
        }.distinctBy { it.storeId to it.date }
        val positive = rows.filter {
            it.storeId > 0L && it.netAmount > 0.005 &&
                (startDate == null || it.businessDate >= startDate) &&
                (endDate == null || it.businessDate <= endDate)
        }.groupBy { it.storeId to it.businessDate }
        return validSessions.groupBy { it.storeId }.map { (id, days) ->
            val visibleDays = days.filter { positive[id to it.date].orEmpty().isNotEmpty() }
            val dayRows = visibleDays.flatMap { positive[id to it.date].orEmpty() }
            // Exclude shifts with unknown or invalid time instead of fabricating an hourly rate.
            val durations = visibleDays.map { shift ->
                val start = parseTime(shift.startTime)
                val end = parseTime(shift.endTime)
                if (start == null || end == null) null else {
                    val hours = Duration.between(start, if (end.isAfter(start)) end
                        else end.plusDays(1)).toMinutes() / 60.0
                    hours.takeIf { it in 1.0..24.0 }
                }
            }
            val hours = if (durations.all { it != null } && durations.isNotEmpty())
                durations.filterNotNull().sum() else 0.0
            CustomerStoreDailyPerformance(
                storeId = id, storeName = days.first().storeName,
                observedBusinessDays = visibleDays.size,
                operatingDays = days.size,
                dailyPayments = if (visibleDays.isEmpty()) 0.0
                    else dayRows.size.toDouble() / visibleDays.size,
                dailyRevenue = if (visibleDays.isEmpty()) 0.0
                    else dayRows.sumOf { it.netAmount } / visibleDays.size,
                averagePayment = if (dayRows.isEmpty()) 0.0
                    else dayRows.sumOf { it.netAmount } / dayRows.size,
                hourlyPayments = if (hours > 0.0) dayRows.size / hours else null
            )
        }.sortedByDescending { it.dailyPayments }
    }

    private fun parseTime(value: String): LocalDateTime? {
        val clock = if (value == "24:00") "00:00" else value
        val time = runCatching { LocalTime.parse(clock) }.getOrNull() ?: return null
        return LocalDate.of(2026, 1, 1).atTime(time)
    }
}
