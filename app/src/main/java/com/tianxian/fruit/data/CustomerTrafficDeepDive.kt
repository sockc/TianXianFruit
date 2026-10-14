package com.tianxian.fruit.data

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/** Recorded selling day, not a calendar day. Times remain untouched in the source bill. */
data class CustomerTrafficSession(
    val date: String,
    val storeId: Long,
    val storeName: String,
    val startTime: String,
    val endTime: String
)

data class CustomerTrafficHourComparison(
    val hour: Int,
    val payments: Int,
    val historicAverage: Double?,
    val historicDays: Int
) {
    val differencePercent: Double?
        get() = historicAverage?.takeIf { it > 0.001 }
            ?.let { (payments - it) / it }
}

data class CustomerTrafficComparison(
    val storeId: Long,
    val storeName: String,
    val date: String,
    val hourRows: List<CustomerTrafficHourComparison>,
    val historicDates: List<String>,
    val currentPayments: Int,
    val baselinePayments: Double?,
    val peakHour: Int?,
    val decliningHours: List<Int>,
    val sourceCoverage: String,
    val warnings: List<String>
) {
    val sampleSufficient: Boolean get() = historicDates.size >= 3
}

data class CustomerPaymentAmountBand(
    val title: String,
    val payments: Int,
    val share: Double
)

data class CustomerStoreTrafficPattern(
    val storeId: Long,
    val storeName: String,
    val operatingDays: Int,
    val averagePayments: Double,
    val averageRevenue: Double,
    val peakHour: Int?
)

/**
 * Read-only inference over payment receipts. A verified store business record
 * is required before declaring days comparable. Never infer buyer identity.
 */
internal object CustomerTrafficDeepDive {
    private fun parseTime(date: String, text: String, isEnd: Boolean): LocalDateTime? {
        val d = runCatching { LocalDate.parse(date) }.getOrNull() ?: return null
        val input = text.trim()
        if (input == "24:00") return d.plusDays(1).atStartOfDay()
        val clock = runCatching { LocalTime.parse(input) }.getOrNull() ?: return null
        val value = d.atTime(clock)
        return value // bounds() adds a day only when closing is not after opening
    }

    private fun bounds(session: CustomerTrafficSession): Pair<LocalDateTime, LocalDateTime>? {
        val start = parseTime(session.date, session.startTime, false) ?: return null
        var end = parseTime(session.date, session.endTime, true) ?: return null
        if (!end.isAfter(start)) end = end.plusDays(1)
        if (ChronoUnit.HOURS.between(start, end) !in 1..24) return null
        return start to end
    }

    private fun tradeTime(record: CustomerPaymentRecord): LocalDateTime? =
        runCatching { LocalDateTime.parse(record.tradeTime.replace(" ", "T")) }.getOrNull()

    /** Only unambiguous matches; preserve original receipt timestamps and transaction IDs. */
    fun attributeBusinessDays(
        rows: List<CustomerPaymentRecord>,
        sessions: List<CustomerTrafficSession>
    ): List<CustomerPaymentRecord> {
        val windows = sessions.mapNotNull { session ->
            bounds(session)?.let { (start, end) -> Triple(session, start, end) }
        }
        return rows.map { record ->
            val moment = tradeTime(record) ?: return@map record
            // Only change business-day attribution for receipts after midnight.
            // A normal daytime receipt keeps its original store, date and sync data.
            if (moment.toLocalDate().toString() == record.businessDate &&
                moment.hour >= 12) return@map record
            val matches = windows.filter { (session, start, end) ->
                !moment.isBefore(start) && moment.isBefore(end) &&
                    (record.storeId <= 0L || record.storeId == session.storeId)
            }
            if (matches.size != 1) return@map record
            val session = matches.single().first
            // Do not overwrite a known but conflicting store assignment.
            if (record.storeId > 0L && record.storeId != session.storeId) return@map record
            record.copy(
                businessDate = session.date,
                storeId = session.storeId,
                storeName = session.storeName
            )
        }
    }

    fun amountBands(rows: List<CustomerPaymentRecord>): List<CustomerPaymentAmountBand> {
        val amounts = rows.filter { it.netAmount > 0.005 }.map { it.netAmount }
        val limits = listOf(10.0, 20.0, 30.0, 50.0)
        val names = listOf("¥0–10", "¥10–20", "¥20–30", "¥30–50", "¥50以上")
        val counts = IntArray(5)
        amounts.forEach { sum ->
            val index = limits.indexOfFirst { sum < it }.let { if (it < 0) 4 else it }
            counts[index]++
        }
        return names.mapIndexed { index, name ->
            CustomerPaymentAmountBand(
                title = name,
                payments = counts[index],
                share = if (amounts.isEmpty()) 0.0 else counts[index].toDouble() / amounts.size
            )
        }
    }

    /** Finished clock-hour windows only: today's unfinished hour is never compared. */
    private fun finishedHours(
        session: CustomerTrafficSession,
        now: LocalDateTime
    ): List<Int> {
        val (start, end) = bounds(session) ?: return emptyList()
        val completeUntil = if (now.isBefore(end)) now else end
        var hourStart = start.truncatedTo(ChronoUnit.HOURS)
        if (hourStart.isBefore(start)) hourStart = hourStart.plusHours(1)
        return buildList {
            while (!hourStart.plusHours(1).isAfter(completeUntil) && size < 24) {
                add(hourStart.hour)
                hourStart = hourStart.plusHours(1)
            }
        }
    }

    fun compare(
        rows: List<CustomerPaymentRecord>,
        sessions: List<CustomerTrafficSession>,
        targetDate: String,
        platform: String = "ALL",
        selectedStoreId: Long = 0L,
        now: LocalDateTime = LocalDateTime.now()
    ): List<CustomerTrafficComparison> {
        val target = runCatching { LocalDate.parse(targetDate) }.getOrNull()
            ?: return emptyList()
        val positive = rows.filter { it.netAmount > 0.005 && 
            (platform == "ALL" || it.platform == platform) }
        val todaySessions = sessions.filter { it.date == targetDate &&
            (selectedStoreId == 0L || it.storeId == selectedStoreId) }
        val results = todaySessions.mapNotNull { active ->
            val hours = finishedHours(active, now)
            if (hours.isEmpty()) return@mapNotNull null
            val today = positive.filter { it.businessDate == targetDate &&
                it.storeId == active.storeId }
            val currentSources = today.map { it.platform }.toSet()
            val currentCounts = today.groupingBy { tradeTime(it)?.hour }.eachCount()
            val previous = sessions.asSequence().filter { session ->
                session.storeId == active.storeId && session.date < targetDate &&
                    runCatching { LocalDate.parse(session.date).dayOfWeek == target.dayOfWeek }
                        .getOrDefault(false) &&
                    finishedHours(session, now).containsAll(hours)
            }.mapNotNull { session ->
                val dayRows = positive.filter { it.storeId == session.storeId &&
                    it.businessDate == session.date }
                val sources = dayRows.map { it.platform }.toSet()
                // Do not compare WeChat-only with WeChat+Alipay when combined.
                if (dayRows.isEmpty() || sources != currentSources) null
                else session.date to dayRows
            }.sortedByDescending { it.first }.take(4).toList()

            val historicDates = previous.map { it.first }
            val byHour = previous.map { (_, dayRows) ->
                dayRows.groupingBy { tradeTime(it)?.hour }.eachCount()
            }
            val hourRows = hours.map { hour ->
                CustomerTrafficHourComparison(
                    hour = hour,
                    payments = currentCounts[hour] ?: 0,
                    historicAverage = byHour.takeIf { it.isNotEmpty() }
                        ?.map { it[hour] ?: 0 }?.average(),
                    historicDays = previous.size
                )
            }
            val targetTotal = hourRows.sumOf { it.payments }
            val historicAverage = byHour.takeIf { it.isNotEmpty() }
                ?.map { counts -> hours.sumOf { counts[it] ?: 0 } }?.average()
            val reliable = previous.size >= 3
            val declining = if (reliable) hourRows.filter {
                it.historicAverage != null && it.historicAverage >= 5 &&
                    it.payments.toDouble() <= it.historicAverage * 0.8
            }.map { it.hour } else emptyList()
            val coverage = if (currentSources.isEmpty()) "当前时段没有已导入的付款记录"
                else currentSources.sorted().joinToString("＋") {
                    if (it == "WECHAT") "微信" else if (it == "ALIPAY") "支付宝" else it
                }
            val warnings = buildList {
                if (currentSources.isEmpty()) add("当前营业日没有收款账单，不能判断是否实际无客流。")
                if (previous.size < 3) add("最近同星期可比营业日仅${previous.size}天（至少需要3天），暂不判断持续下降。")
                if (platform == "ALL" && currentSources.size < 2) add("只有${coverage}记录，缺失另一支付渠道时，合并客流可能不完整。")
                add("无法仅凭已导入交易证明账单覆盖整个营业时段；比对仅作经营线索。")
            }
            CustomerTrafficComparison(
                storeId = active.storeId,
                storeName = active.storeName,
                date = targetDate,
                hourRows = hourRows,
                historicDates = historicDates,
                currentPayments = targetTotal,
                baselinePayments = historicAverage,
                peakHour = hourRows.maxByOrNull { it.payments }?.takeIf { it.payments > 0 }?.hour,
                decliningHours = declining,
                sourceCoverage = coverage,
                warnings = warnings
            )
        }
        return results.sortedBy { it.storeName }
    }

    fun storePatterns(
        rows: List<CustomerPaymentRecord>,
        sessions: List<CustomerTrafficSession>,
        platform: String,
        endDate: String
    ): List<CustomerStoreTrafficPattern> {
        val included = rows.filter { it.netAmount > 0.005 &&
            (platform == "ALL" || it.platform == platform) }
        return sessions.filter { it.date <= endDate }.groupBy { it.storeId }
            .mapNotNull { (id, days) ->
                val latest = days.distinctBy { it.date }.sortedByDescending { it.date }
                    .map { day -> day to included.filter { it.storeId == id && it.businessDate == day.date } }
                    .filter { it.second.isNotEmpty() }.take(7)
                if (latest.isEmpty()) return@mapNotNull null
                val values = latest.flatMap { it.second }
                CustomerStoreTrafficPattern(
                    storeId = id,
                    storeName = latest.first().first.storeName,
                    operatingDays = latest.size,
                    averagePayments = latest.map { it.second.size }.average(),
                    averageRevenue = latest.map { rowsForDay -> rowsForDay.second.sumOf { it.netAmount } }.average(),
                    peakHour = values.groupingBy { tradeTime(it)?.hour }.eachCount()
                        .maxByOrNull { it.value }?.key
                )
            }.sortedByDescending { it.averagePayments }
    }
}
