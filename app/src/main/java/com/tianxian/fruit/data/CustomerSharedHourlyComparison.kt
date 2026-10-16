package com.tianxian.fruit.data

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * Hourly comparison for a shared collecting account.
 * The baseline MUST use the same collector, payment channel and full set of
 * stores, rather than comparing a combined number to a single store.
 */
internal object CustomerSharedHourlyComparison {
    private fun interval(session: CustomerTrafficSession): Pair<LocalDateTime, LocalDateTime>? {
        val date = runCatching { LocalDate.parse(session.date) }.getOrNull() ?: return null
        val start = runCatching { LocalTime.parse(session.startTime) }.getOrNull()
            ?: return null
        val end = if (session.endTime.trim() == "24:00") {
            date.plusDays(1).atStartOfDay()
        } else {
            val clock = runCatching { LocalTime.parse(session.endTime) }.getOrNull()
                ?: return null
            var time = date.atTime(clock)
            if (!time.isAfter(date.atTime(start))) time = time.plusDays(1)
            time
        }
        val opening = date.atTime(start)
        if (ChronoUnit.HOURS.between(opening, end) !in 1L..24L) return null
        return opening to end
    }

    /** Every published hour must be fully covered by at least one known selling shift. */
    private fun completedHours(
        date: String, stores: Set<Long>,
        sessions: List<CustomerTrafficSession>, now: LocalDateTime
    ): List<Int> {
        val active = sessions.filter { it.date == date && it.storeId in stores }
            .mapNotNull(::interval)
        if (active.isEmpty()) return emptyList()
        val beginning = active.minOf { it.first }
        val ending = active.maxOf { it.second }
        var cursor = beginning.truncatedTo(ChronoUnit.HOURS)
        if (cursor.isBefore(beginning)) cursor = cursor.plusHours(1)
        return buildList {
            while (cursor.plusHours(1) <= ending && cursor.plusHours(1) <= now &&
                size < 24) {
                val hourEnd = cursor.plusHours(1)
                if (active.any { (start, end) -> cursor >= start && hourEnd <= end }) {
                    add(cursor.hour)
                }
                cursor = hourEnd
            }
        }
    }

    private fun observed(
        group: SharedCollectorTraffic, validIds: Set<Long>,
        receipts: Map<Long, CustomerPaymentRecord>, hours: Set<Int>
    ): List<CustomerPaymentRecord> =
        group.receiptIds.filter { it in validIds }.mapNotNull(receipts::get).filter {
            it.netAmount > 0.005 && it.businessDate == group.date &&
                it.tradeTime.substringAfter(' ').take(2).toIntOrNull() in hours
        }

    fun compare(
        selected: SharedCollectorTraffic,
        groups: List<SharedCollectorTraffic>,
        included: List<CustomerPaymentRecord>,
        sessions: List<CustomerTrafficSession>,
        now: LocalDateTime = LocalDateTime.now()
    ): CustomerTrafficComparison? {
        val target = runCatching { LocalDate.parse(selected.date) }.getOrNull() ?: return null
        val hours = completedHours(selected.date, selected.storeIds, sessions, now)
        if (hours.isEmpty()) return null
        val allReceipts = included.associateBy { it.id }
        val validIds = allReceipts.keys
        val current = observed(selected, validIds, allReceipts, hours.toSet())
        val sources = current.map { it.platform }.toSet()
        val history = groups.asSequence().filter { other ->
            other.date < selected.date &&
                other.partnerId == selected.partnerId &&
                other.platform == selected.platform &&
                other.storeIds == selected.storeIds &&
                runCatching {
                    LocalDate.parse(other.date).dayOfWeek == target.dayOfWeek
                }.getOrDefault(false) &&
                completedHours(other.date, other.storeIds, sessions, now).containsAll(hours)
        }.mapNotNull { other ->
            val dayRows = observed(other, validIds, allReceipts, hours.toSet())
            if (dayRows.isEmpty() || dayRows.map { it.platform }.toSet() != sources) null
            else other.date to dayRows
        }.sortedByDescending { it.first }.take(4).toList()
        val counts = current.groupingBy { it.tradeTime.substringAfter(' ').take(2).toIntOrNull() }
            .eachCount()
        val historicCounts = history.map { (_, list) ->
            list.groupingBy { it.tradeTime.substringAfter(' ').take(2).toIntOrNull() }.eachCount()
        }
        val hourRows = hours.map { hour ->
            CustomerTrafficHourComparison(
                hour = hour,
                payments = counts[hour] ?: 0,
                historicAverage = if (historicCounts.isEmpty()) null
                    else historicCounts.map { it[hour] ?: 0 }.average(),
                historicDays = history.size
            )
        }
        val baseline = if (historicCounts.isEmpty()) null else historicCounts.map { day ->
            hours.sumOf { day[it] ?: 0 }
        }.average()
        val declines = if (history.size < 3) emptyList() else hourRows.filter { row ->
            row.historicAverage != null && row.historicAverage >= 5.0 &&
                row.payments <= row.historicAverage * 0.8
        }.map { it.hour }
        return CustomerTrafficComparison(
            storeId = selected.storeIds.minOrNull() ?: 0L,
            storeName = "${selected.partnerName} · ${selected.storeNames}（${if (selected.platform == "WECHAT") "微信" else "支付宝"}共享）",
            date = selected.date,
            hourRows = hourRows,
            historicDates = history.map { it.first },
            currentPayments = hourRows.sumOf { it.payments },
            baselinePayments = baseline,
            peakHour = hourRows.maxByOrNull { it.payments }?.takeIf { it.payments > 0 }?.hour,
            decliningHours = declines,
            sourceCoverage = selected.platform,
            warnings = buildList {
                add("这是${selected.stores.size}个营业位置共用收款账号的合并笔数，不能当作任意单摊客流。")
                add("历史基线仅采用同一收款人、同一支付渠道、完全相同位置组合、同星期且营业小时覆盖的日期。")
                if (history.size < 3) add("完全可比的历史营业日只有${history.size}天；样本不足，不判断持续变化。")
                add("账单存在不代表导出时间范围完整；逐小时付款笔数不代表独立顾客人数。")
            }
        )
    }
}
