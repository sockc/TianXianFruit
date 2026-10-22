package com.tianxian.fruit.data

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/** Payment count means transaction count, never unique visitors. */
internal data class LocationHourComparison(
    val hour: Int,
    val payments: Int,
    val amount: Double,
    val historicalPayments: Double?,
    val historicalAmount: Double?
)

internal data class LocationComparableDay(
    val date: String,
    val payments: Int,
    val amount: Double
)

internal data class LocationRecentTrend(
    val recentDates: List<String>,
    val earlierDates: List<String>,
    val recentPayments: Double,
    val earlierPayments: Double,
    val recentAmount: Double,
    val earlierAmount: Double
)

internal data class LocationHistoryResult(
    val date: String,
    val hourRows: List<LocationHourComparison>,
    val currentPayments: Int,
    val currentAmount: Double,
    val currentRecords: List<CustomerPaymentRecord>,
    val historicDates: List<String>,
    val baselinePayments: Double?,
    val baselineAmount: Double?,
    val recentTrend: LocationRecentTrend?,
    val operatingDays: List<LocationComparableDay>,
    val observedSources: Set<String>,
    val coverageNote: String
) {
    val sufficient: Boolean get() = historicDates.size >= 3

    val decliningHours: List<LocationHourComparison>
        get() = if (!sufficient) emptyList() else hourRows
            .filter { (it.historicalPayments ?: 0.0) >= 1.0 &&
                it.payments < (it.historicalPayments ?: 0.0) }
            .sortedBy { it.payments - (it.historicalPayments ?: 0.0) }.take(3)

    val growingHours: List<LocationHourComparison>
        get() = if (!sufficient) emptyList() else hourRows
            .filter { (it.historicalPayments ?: 0.0) >= 1.0 &&
                it.payments > (it.historicalPayments ?: 0.0) }
            .sortedByDescending { it.payments - (it.historicalPayments ?: 0.0) }.take(3)
}

/**
 * Exactly the same completed clock-hours are used for the current totals,
 * previous same-weekday baseline, hourly bars and 4-vs-4 period trend.
 *
 * A day with no receipts is UNKNOWN, never an inferred zero. Comparing
 * combined channels requires the same *observed* channel set; it does not
 * establish that the underlying exports are complete.
 */
internal object LocationHistoryComparisonEngine {
    private fun bounds(s: CustomerTrafficSession): Pair<LocalDateTime,LocalDateTime>? {
        val date = runCatching { LocalDate.parse(s.date) }.getOrNull() ?: return null
        val startClock = runCatching { LocalTime.parse(s.startTime.trim()) }.getOrNull()
            ?: return null
        val begin = date.atTime(startClock)
        var end = if (s.endTime.trim() == "24:00") date.plusDays(1).atStartOfDay()
            else date.atTime(runCatching { LocalTime.parse(s.endTime.trim()) }.getOrNull()
                ?: return null)
        if (!end.isAfter(begin)) end = end.plusDays(1)
        if (ChronoUnit.MINUTES.between(begin,end) !in 60L..1440L) return null
        return begin to end
    }

    private fun completeSlots(s: CustomerTrafficSession, now: LocalDateTime): List<LocalDateTime> {
        val (begin,end) = bounds(s) ?: return emptyList()
        val until = if (now.isBefore(end)) now else end
        var slot = begin.truncatedTo(ChronoUnit.HOURS)
        if (slot.isBefore(begin)) slot = slot.plusHours(1)
        return buildList {
            while (!slot.plusHours(1).isAfter(until) && size < 24) {
                add(slot)
                slot = slot.plusHours(1)
            }
        }
    }

    private fun stamp(row: CustomerPaymentRecord): LocalDateTime? =
        runCatching { LocalDateTime.parse(row.tradeTime.replace(" ", "T")) }.getOrNull()

    /**
     * Compatibility facade. The position page and historical consumers MUST
     * share the one canonical same-hour/channel baseline calculator. Previously
     * there were two implementations that could disagree on historical amounts.
     */
    fun inspect(
        payments: List<CustomerPaymentRecord>,
        sessions: List<CustomerTrafficSession>,
        storeId: Long,
        date: String,
        platform: String = "ALL",
        now: LocalDateTime = LocalDateTime.now()
    ): LocationHistoryResult? {
        val report = CustomerLocationHistoryAnalyzer.analyze(
            payments, sessions, date, storeId, platform, now
        ) ?: return null
        val eligible = payments.filter { row ->
            row.storeId == storeId && row.netAmount > 0.005 &&
                (platform == "ALL" || row.platform == platform)
        }
        val current = eligible.filter { row ->
            row.businessDate == date && stamp(row)?.hour in report.comparableHours
        }
        val fromDay = eligible.groupBy { it.businessDate }
        val daily = sessions.filter { it.storeId == storeId && it.date <= date }
            .distinctBy { it.date }.sortedByDescending { it.date }.take(8)
            .map { shift ->
                val completed = completeSlots(shift, now)
                    .toSet()
                val dayPayments = fromDay[shift.date].orEmpty().filter { row ->
                    stamp(row)?.truncatedTo(ChronoUnit.HOURS) in completed
                }
                LocationComparableDay(
                    shift.date, dayPayments.size, dayPayments.sumOf { it.netAmount }
                )
            }
        val period = report.period
        val trend = if (period.sufficient) LocationRecentTrend(
            recentDates = period.recent.map { it.date },
            earlierDates = period.earlier.map { it.date },
            recentPayments = period.recentDailyCount!!,
            earlierPayments = period.earlierDailyCount!!,
            recentAmount = period.recentDailyAmount!!,
            earlierAmount = period.earlierDailyAmount!!
        ) else null
        val sources = report.current.sources
        val coverage = when {
            !report.current.observed ->
                "本日无已归属收款，无法判断是零客流还是账单未导入"
            platform == "ALL" && sources.size == 1 ->
                "仅观察到${if ("WECHAT" in sources) "微信" else "支付宝"}；另一渠道是否完整未知"
            else -> "已观察到收款记录，导出账单的完整性尚未核验"
        }
        return LocationHistoryResult(
            date = date,
            hourRows = report.hours.map { hour ->
                LocationHourComparison(
                    hour.hour, hour.count, hour.amount,
                    hour.historicCount, hour.historicAmount
                )
            },
            currentPayments = report.current.count,
            currentAmount = report.current.amount,
            currentRecords = current,
            historicDates = report.historicDays.map { it.date },
            baselinePayments = report.historicCount,
            baselineAmount = report.historicAmount,
            recentTrend = trend,
            operatingDays = daily,
            observedSources = sources,
            coverageNote = coverage
        )
    }

}
