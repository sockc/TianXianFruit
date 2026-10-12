package com.tianxian.fruit.data

import com.tianxian.fruit.sync.WeatherHour
import com.tianxian.fruit.sync.WeatherClient
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * 使用真实营业时段内的历史逐小时实况与已导入电子支付交易，判断雨前/雨中/雨后收款变化。
 * 完全只读；不将单笔收款当成唯一顾客，也不把预报天气当作历史实况。
 */
data class RainImpactPeriod(
    val label: String,
    val observedHours: Int,
    val paymentCount: Int,
    val electronicRevenue: Double,
    val identifiedCustomers: Int
) {
    val revenuePerObservedHour: Double?
        get() = if (observedHours > 0) electronicRevenue / observedHours else null
}

data class RainImpactReport(
    val date: LocalDate,
    val storeId: Long,
    val totalOperatingRevenue: Double,
    val expectedHours: Int,
    val actualWeatherHours: Int,
    val wetHours: Int,
    val firstRainHour: String?,
    val lastRainHour: String?,
    val before: RainImpactPeriod,
    val rainy: RainImpactPeriod,
    val after: RainImpactPeriod,
    val dry: RainImpactPeriod,
    val assignedPaymentCount: Int,
    val identifiedCustomers: Int,
    val unassignedPaymentCount: Int,
    val hasActualArchive: Boolean,
    val observations: List<String>,
    val missing: List<String>
)

object RainImpactAnalyzer {
    private fun timestamp(input: String): LocalDateTime? {
        val normalized = input.trim().replace(' ', 'T')
        if (normalized.length < 16) return null
        return runCatching { LocalDateTime.parse(normalized.take(16)) }.getOrNull()
    }

    private fun window(date: LocalDate, startTime: String, endTime: String): Pair<LocalDateTime, LocalDateTime> {
        val start = runCatching { LocalTime.parse(startTime) }.getOrDefault(LocalTime.of(16, 0))
        val startAt = date.atTime(start)
        val endAt = if (endTime == "24:00") {
            date.plusDays(1).atStartOfDay()
        } else {
            val parsed = runCatching { LocalTime.parse(endTime) }.getOrDefault(LocalTime.MIDNIGHT)
            date.atTime(parsed).let { if (it.isAfter(startAt)) it else it.plusDays(1) }
        }
        return startAt to endAt
    }

    fun analyze(
        date: LocalDate,
        storeId: Long,
        startTime: String,
        endTime: String,
        storeRevenue: Double,
        hourly: List<WeatherHour>,
        payments: List<CustomerPaymentRecord>,
        hasActualArchive: Boolean
    ): RainImpactReport {
        val (start, end) = window(date, startTime, endTime)
        val expectedHours = Duration.between(start, end).toHours().toInt().coerceIn(1, 24)
        val actualHours = hourly.asSequence()
            .filter { it.actualAvailable }
            .mapNotNull { hour ->
                val time = timestamp(hour.time)?.truncatedTo(ChronoUnit.HOURS) ?: return@mapNotNull null
                if (time.isBefore(start) || !time.isBefore(end)) null else time to hour
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, records) -> records.last() }

        fun wet(h: WeatherHour): Boolean {
            val label = h.text
            return (h.precipitation ?: 0.0) > 0.05 ||
                label.contains("雨") ||
                label.contains("雷暴") ||
                (h.code.toIntOrNull() ?: -1) in 300..399
        }
        val wetKeys = actualHours.filterValues(::wet).keys.sorted()
        val first = wetKeys.firstOrNull()
        val last = wetKeys.lastOrNull()
        val positivePayments = payments.filter {
            it.netAmount > 0.005 && it.businessDate == date.toString()
        }
        val assigned = positivePayments.filter { it.storeId == storeId }
        val excludedStore = positivePayments.count { it.storeId <= 0L }
        val matched = assigned.mapNotNull { receipt ->
            val at = timestamp(receipt.tradeTime) ?: return@mapNotNull null
            val bucket = at.truncatedTo(ChronoUnit.HOURS)
            if (!at.isBefore(start) && at.isBefore(end) && actualHours.containsKey(bucket)) {
                bucket to receipt
            } else null
        }
        val withoutTimestamp = assigned.count {
            timestamp(it.tradeTime) == null
        }
        fun segment(label: String, hours: Set<LocalDateTime>): RainImpactPeriod {
            val matchedPayments = matched.filter { it.first in hours }.map { it.second }
            val stable = matchedPayments
                .filter { it.identityConfidence == "HIGH" || it.identityConfidence == "MEDIUM" }
                .map { it.customerKey }
                .filter { it.isNotBlank() }.distinct().size
            return RainImpactPeriod(
                label = label,
                observedHours = hours.size,
                paymentCount = matchedPayments.size,
                electronicRevenue = matchedPayments.sumOf { it.netAmount },
                identifiedCustomers = stable
            )
        }
        val allKeys = actualHours.keys
        val beforeKeys = if (first == null) emptySet() else allKeys.filter { it.isBefore(first) }.toSet()
        val rainyKeys = wetKeys.toSet()
        val afterKeys = if (last == null) emptySet() else allKeys.filter { it.isAfter(last) }.toSet()
        val dryKeys = allKeys - rainyKeys
        val before = segment("雨前", beforeKeys)
        val rainy = segment("降雨小时", rainyKeys)
        val after = segment("雨后", afterKeys)
        val dry = segment("无雨小时", dryKeys)
        val observations = mutableListOf<String>()
        val missing = mutableListOf<String>()

        if (!hasActualArchive) missing += "该位置当天尚无归档的逐小时实际天气，暂不把天气预报当作历史降雨"
        if (actualHours.size < expectedHours) {
            missing += "营业时段逐小时实况仅覆盖${actualHours.size}/${expectedHours}小时"
        }
        if (assigned.isEmpty()) missing += "没有归属本位置的已导入微信/支付宝收款，无法比较雨前雨后交易"
        if (withoutTimestamp > 0) missing += "${withoutTimestamp}笔账单缺少有效交易时间"
        if (excludedStore > 0) missing += "${excludedStore}笔电子收款未归属营业位置，未纳入本位置分析"
        if (positivePayments.isNotEmpty() && assigned.isNotEmpty()) {
            observations += "已匹配本位置${assigned.size}笔电子收款；不含现金，不能代替营业总额和全部顾客数"
        }
        if (actualHours.isEmpty()) {
            observations += "缺少可靠的营业时段逐小时实况，尚不能判断降雨影响"
        } else if (wetKeys.isEmpty()) {
            observations += "已记录的${actualHours.size}个营业小时未发现实况降雨；不能据此认定全天绝对无雨"
        } else {
            observations += "营业时段有${wetKeys.size}小时记录到降雨，首个雨小时${first.toString().replace('T', ' ')}"
            val rainRate = rainy.revenuePerObservedHour
            val dryRate = dry.revenuePerObservedHour
            if (rainRate != null && dryRate != null && rainy.paymentCount > 0 && dry.paymentCount > 0) {
                val percent = if (dryRate > 0.005) (rainRate / dryRate - 1.0) * 100 else null
                if (percent != null) {
                    observations += "同日电子收款：有雨时段每小时${money(rainRate)}，无雨时段每小时${money(dryRate)}（相差${String.format(Locale.CHINA, "%+.0f", percent)}%）"
                    observations += "这只是同期收款对比，不能单凭差异断定降雨造成营业额变化"
                }
            } else {
                missing += "雨小时和无雨小时的已匹配电子收款不足，无法做可靠的每小时收入对比"
            }
            if (before.observedHours == 0 || after.observedHours == 0) {
                missing += "雨前或雨后缺少可比的已观测小时，无法完成三段对照"
            }
        }
        if (actualHours.isNotEmpty() && matched.size < assigned.size) {
            missing += "部分电子收款没有落在已归档的营业小时内，未参与分时比较"
        }
        observations += "商品结构、售价、工厂排班及连续经营都可能同时影响营业额，不能只归因于天气"
        return RainImpactReport(
            date = date,
            storeId = storeId,
            totalOperatingRevenue = storeRevenue,
            expectedHours = expectedHours,
            actualWeatherHours = actualHours.size,
            wetHours = wetKeys.size,
            firstRainHour = first?.toString(),
            lastRainHour = last?.toString(),
            before = before, rainy = rainy, after = after, dry = dry,
            assignedPaymentCount = assigned.size,
            identifiedCustomers = assigned.filter {
                it.identityConfidence in setOf("HIGH", "MEDIUM")
            }.map { it.customerKey }.filter { it.isNotBlank() }.distinct().size,
            unassignedPaymentCount = excludedStore,
            hasActualArchive = hasActualArchive,
            observations = observations,
            missing = missing.distinct()
        )
    }

    fun fromDatabase(db: AppDatabase, date: LocalDate, storeId: Long): RainImpactReport {
        val records = db.getDailyRecords(date.toString()).filter { it.storeId == storeId }
        val start = records.firstOrNull()?.actualStartTime.orEmpty().ifBlank { "16:00" }
        val end = records.firstOrNull()?.actualEndTime.orEmpty().ifBlank { "24:00" }
        val revenue = records.sumOf { it.revenue }
        val archive = db.getBusinessWeatherHistory(date.toString(), storeId)
        val hours = archive?.let { record ->
            runCatching { WeatherClient.parseOverview(record.payloadJson, historical = true).hourly }
                .getOrElse { emptyList() }
        }.orEmpty()
        val payments = CustomerAnalyticsEngine.filterRecords(
            db.getCustomerPayments(date.toString(), date.toString()),
            db.getCustomerAnalysisSettings()
        ).included
        return analyze(date, storeId, start, end, revenue, hours, payments, archive != null)
    }

    private fun money(value: Double): String =
        "¥" + String.format(Locale.CHINA, "%.0f", value)
}
