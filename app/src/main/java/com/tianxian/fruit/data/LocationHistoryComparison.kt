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

    fun inspect(
        payments: List<CustomerPaymentRecord>,
        sessions: List<CustomerTrafficSession>,
        storeId: Long,
        date: String,
        platform: String = "ALL",
        now: LocalDateTime = LocalDateTime.now()
    ): LocationHistoryResult? {
        if (storeId <= 0) return null
        val dateValue = runCatching { LocalDate.parse(date) }.getOrNull() ?: return null
        val shifts = sessions.asSequence().filter { it.storeId == storeId }
            .distinctBy { it.date }.sortedByDescending { it.date }.toList()
        val active = shifts.firstOrNull { it.date == date } ?: return null
        val targetSlots = completeSlots(active, now)
        val targetHours = targetSlots.map { it.hour }
        val targetSlotsSet = targetSlots.toSet()
        val rows = payments.asSequence().filter {
            it.storeId == storeId && it.netAmount > 0.005 &&
                (platform == "ALL" || it.platform == platform)
        }.toList()
        val byDay = rows.groupBy { it.businessDate }
        val stampCache = rows.associate { it.id to stamp(it) }
        fun scoped(day: String, slots: List<LocalDateTime>): List<CustomerPaymentRecord> {
            val set = slots.toHashSet()
            return byDay[day].orEmpty().filter { r ->
                stampCache[r.id]?.truncatedTo(ChronoUnit.HOURS) in set
            }
        }
        val current = scoped(date, targetSlots)
        val sources = current.map { it.platform }.toSet()

        // Comparable shifts must contain every target hour, share its weekday
        // and have receipts in the same observed payment channels.
        data class Historical(val date: String, val rows: List<CustomerPaymentRecord>)
        val comparable = if (targetHours.isEmpty() || current.isEmpty()) emptyList() else shifts
            .asSequence().filter { s ->
                s.date <= date &&
                    runCatching { LocalDate.parse(s.date).dayOfWeek == dateValue.dayOfWeek }
                        .getOrDefault(false) &&
                    completeSlots(s, now).map { it.hour }.containsAll(targetHours)
            }.mapNotNull { s ->
                val daySlots = completeSlots(s, now).filter { it.hour in targetHours }
                val observed = scoped(s.date, daySlots)
                if (observed.isEmpty() || observed.map { it.platform }.toSet() != sources) null
                else Historical(s.date, observed)
            }.take(9).toList()
        val history = comparable.filter { it.date < date }.take(4)
        val sufficient = history.size >= 3
        val hourRows = targetSlots.map { slot ->
            val perHour = current.filter {
                stampCache[it.id]?.truncatedTo(ChronoUnit.HOURS) == slot
            }
            val fromHistory = history.map { h ->
                h.rows.filter { stampCache[it.id]?.hour == slot.hour }
            }
            LocationHourComparison(
                hour = slot.hour,
                payments = perHour.size,
                amount = perHour.sumOf { it.netAmount },
                historicalPayments = if (sufficient) fromHistory.map { it.size.toDouble() }.average() else null,
                historicalAmount = if (sufficient) fromHistory.map { h -> h.sumOf { it.netAmount } }.average() else null
            )
        }
        val recent = comparable.take(4)
        val earlier = comparable.drop(4).take(4)
        val trend = if (recent.size == 4 && earlier.size == 4) {
            LocationRecentTrend(
                recentDates = recent.map { it.date },
                earlierDates = earlier.map { it.date },
                recentPayments = recent.map { it.rows.size.toDouble() }.average(),
                earlierPayments = earlier.map { it.rows.size.toDouble() }.average(),
                recentAmount = recent.map { d -> d.rows.sumOf { it.netAmount } }.average(),
                earlierAmount = earlier.map { d -> d.rows.sumOf { it.netAmount } }.average()
            )
        } else null

        val operatingDays = shifts.filter { it.date <= date }.take(8).map { s ->
            val dayRows = scoped(s.date, completeSlots(s, now))
            LocationComparableDay(s.date, dayRows.size, dayRows.sumOf { it.netAmount })
        }
        val coverage = when {
            current.isEmpty() -> "本日无已归属收款，无法判断是零客流还是账单未导入"
            platform == "ALL" && sources.size == 1 ->
                "仅观察到${if ("WECHAT" in sources) "微信" else "支付宝"}；另一渠道是否完整未知"
            else -> "已观察到收款记录，导出账单的完整性尚未核验"
        }
        return LocationHistoryResult(
            date = date,
            hourRows = hourRows,
            currentPayments = current.size,
            currentAmount = current.sumOf { it.netAmount },
            currentRecords = current,
            historicDates = history.map { it.date },
            baselinePayments = if (sufficient) history.map { it.rows.size.toDouble() }.average() else null,
            baselineAmount = if (sufficient) history.map { it.rows.sumOf { r -> r.netAmount } }.average() else null,
            recentTrend = trend,
            operatingDays = operatingDays,
            observedSources = sources,
            coverageNote = coverage
        )
    }
}
