package com.tianxian.fruit.data

import java.time.LocalDateTime

/**
 * All comparable totals use the SAME finished clock-hours and receipt channels.
 * A day with no imported receipts is unknown, never a confirmed zero.
 * Shared/unmatched receipts are never assigned to this store.
 */
internal data class LocationHistoryDay(
    val date: String,
    val count: Int,
    val amount: Double,
    val sources: Set<String>
) {
    val observed: Boolean get() = count > 0
    val average: Double get() = if (count > 0) amount / count else 0.0
}

internal data class LocationHistoryHour(
    val hour: Int,
    val count: Int,
    val amount: Double,
    val historicCount: Double?,
    val historicAmount: Double?
) {
    fun delta(amountMetric: Boolean): Double? = if (amountMetric)
        historicAmount?.let { amount - it }
    else historicCount?.let { count - it }
}

internal data class LocationHistoryPeriod(
    val recent: List<LocationHistoryDay>,
    val earlier: List<LocationHistoryDay>
) {
    // Four eligible, fully observed days in both groups are needed to draw
    // a quantitative trend. Do not interpret absent files as zero takings.
    val sufficient: Boolean get() = recent.size == 4 && earlier.size == 4 &&
        (recent + earlier).all { it.observed }
    val recentDailyAmount: Double? get() =
        recent.takeIf { sufficient }?.map { it.amount }?.average()
    val earlierDailyAmount: Double? get() =
        earlier.takeIf { sufficient }?.map { it.amount }?.average()
    val recentDailyCount: Double? get() =
        recent.takeIf { sufficient }?.map { it.count.toDouble() }?.average()
    val earlierDailyCount: Double? get() =
        earlier.takeIf { sufficient }?.map { it.count.toDouble() }?.average()
    val recentAveragePayment: Double? get() =
        if (!sufficient) null else recent.sumOf { it.amount } / recent.sumOf { it.count }
    val earlierAveragePayment: Double? get() =
        if (!sufficient) null else earlier.sumOf { it.amount } / earlier.sumOf { it.count }
}

internal data class LocationHistoryAnalysis(
    val date: String,
    val comparableHours: List<Int>,
    val current: LocationHistoryDay,
    val historicDays: List<LocationHistoryDay>,
    val period: LocationHistoryPeriod,
    val hours: List<LocationHistoryHour>,
    val warnings: List<String>
) {
    val baselineSufficient: Boolean get() = historicDays.size >= 3 && current.observed
    val historicAmount: Double? get() =
        historicDays.takeIf { baselineSufficient }?.map { it.amount }?.average()
    val historicCount: Double? get() =
        historicDays.takeIf { baselineSufficient }?.map { it.count.toDouble() }?.average()
    val historicAveragePayment: Double? get() {
        if (!baselineSufficient) return null
        val count = historicDays.sumOf { it.count }
        return if (count > 0) historicDays.sumOf { it.amount } / count else null
    }

    /** Biggest losses/gains in absolute count or amount. Require a meaningful baseline. */
    fun biggestChanges(amountMetric: Boolean, declining: Boolean): List<LocationHistoryHour> {
        if (!baselineSufficient) return emptyList()
        return hours.filter { hour ->
            val baseline = if (amountMetric) hour.historicAmount else hour.historicCount
            baseline != null && baseline >= (if (amountMetric) 50.0 else 5.0) &&
                hour.delta(amountMetric)?.let { if (declining) it < -0.005 else it > 0.005 } == true
        }.sortedWith(if (declining) compareBy<LocationHistoryHour> { it.delta(amountMetric) }
            else compareByDescending<LocationHistoryHour> { it.delta(amountMetric) }).take(3)
    }
}

internal object CustomerLocationHistoryAnalyzer {
    /**
     * CustomerTrafficDeepDive supplies the same-store/weekday/channel and
     * completed-hour eligibility rules already used by customer analysis.
     * Request seven past eligible sessions: four + three for the 8-day trend
     * including the selected day. Default other screens remain at four.
     */
    fun analyze(
        rows: List<CustomerPaymentRecord>,
        sessions: List<CustomerTrafficSession>,
        date: String,
        storeId: Long,
        platform: String = "ALL",
        now: LocalDateTime = LocalDateTime.now()
    ): LocationHistoryAnalysis? {
        if (storeId <= 0 || platform !in setOf("ALL", "WECHAT", "ALIPAY")) return null
        val comparison = CustomerTrafficDeepDive.compare(
            rows, sessions, date, platform, storeId, now, maxHistoricDays = 7
        ).firstOrNull() ?: return null
        val hours = comparison.hourRows.map { it.hour }.toSet()
        if (hours.isEmpty()) return null
        val scoped = rows.asSequence()
            .filter { it.storeId == storeId && it.netAmount > 0.005 &&
                (platform == "ALL" || it.platform == platform) }
            .groupBy { it.businessDate }
        val selected = (listOf(date) + comparison.historicDates).distinct()
        val observedDays = selected.associateWith { day ->
            val matching = scoped[day].orEmpty().filter { record ->
                // An after-midnight receipt belongs to the businessDate assigned
                // by the session matcher, not its calendar transaction date.
                val hour = record.tradeTime.drop(11).take(2).toIntOrNull()
                hour != null && hour in hours
            }
            LocationHistoryDay(
                day, matching.size, matching.sumOf { it.netAmount },
                matching.map { it.platform }.toSet()
            )
        }
        val current = observedDays.getValue(date)
        val historic = comparison.historicDates.take(4).map { observedDays.getValue(it) }
        val periodDates = selected.take(8)
        val period = LocationHistoryPeriod(
            recent = periodDates.take(4).map { observedDays.getValue(it) },
            earlier = periodDates.drop(4).take(4).map { observedDays.getValue(it) }
        )
        val historicHourly = historic.map { day ->
            scoped[day.date].orEmpty().filter { record ->
                record.tradeTime.drop(11).take(2).toIntOrNull() in hours
            }.groupBy { it.tradeTime.drop(11).take(2).toIntOrNull() }
        }
        val currentHourly = scoped[date].orEmpty().filter {
            it.tradeTime.drop(11).take(2).toIntOrNull() in hours
        }.groupBy { it.tradeTime.drop(11).take(2).toIntOrNull() }
        val confident = historic.size >= 3 && current.observed
        val hourly = comparison.hourRows.map { row ->
            val own = currentHourly[row.hour].orEmpty()
            LocationHistoryHour(
                hour = row.hour, count = own.size, amount = own.sumOf { it.netAmount },
                historicCount = if (!confident) null else
                    historicHourly.map { it[row.hour].orEmpty().size.toDouble() }.average(),
                historicAmount = if (!confident) null else
                    historicHourly.map { sample -> sample[row.hour].orEmpty()
                        .sumOf { it.netAmount } }.average()
            )
        }
        val warnings = buildList {
            if (!current.observed)
                add("本次营业日没有已导入收款，不能将缺失当作0。")
            if (platform == "ALL" && current.observed &&
                current.sources != setOf("WECHAT", "ALIPAY"))
                add("本次仅有${current.sources.joinToString("、") { if (it == "WECHAT") "微信" else "支付宝" }}账单，另一渠道需核验。")
            if (historic.size < 3)
                add("同位置、同星期、同营业时段及支付渠道的可比历史仅${historic.size}天（至少3天）。")
            if (!period.sufficient)
                add("近期/过去各需4个可比营业日；目前为${period.recent.size}天/${period.earlier.size}天。")
            add("仅统计可归属到本位置、处于相同已结束整点小时的付款；共享收款不分摊。")
            add("已导入交易不代表账单完整；付款笔数并非独立顾客数。")
        }
        return LocationHistoryAnalysis(date, comparison.hourRows.map { it.hour },
            current, historic, period, hourly, warnings)
    }
}
