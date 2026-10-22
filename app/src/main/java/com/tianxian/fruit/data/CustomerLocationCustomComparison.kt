package com.tianxian.fruit.data

import java.time.LocalDate
import java.time.LocalDateTime

/** Compare only verified selling sessions; never infer operating days from receipts. */
internal enum class LocationCompareMode(val label: String) {
    SMART("智能对比"), DAY("单日"), RANGE("日期区间"), SESSIONS("营业次数")
}

internal data class LocationManualRequest(
    val mode: LocationCompareMode,
    val anchorDate: String,
    val referenceDate: String,
    val currentStart: String,
    val currentEnd: String,
    val referenceStart: String,
    val referenceEnd: String,
    val sessionCount: Int = 4
)

internal data class LocationManualHour(
    val hour: Int,
    val currentCount: Double,
    val referenceCount: Double,
    val currentAmount: Double,
    val referenceAmount: Double
)

internal data class LocationManualResult(
    val currentDates: List<String>,
    val referenceDates: List<String>,
    val currentObserved: Int,
    val referenceObserved: Int,
    val comparableHours: List<Int>,
    val currentAmount: Double?,
    val referenceAmount: Double?,
    val currentCount: Double?,
    val referenceCount: Double?,
    val currentAverage: Double?,
    val referenceAverage: Double?,
    val hours: List<LocationManualHour>,
    val comparable: Boolean,
    val warnings: List<String>
) {
    val isPerDay: Boolean get() = currentDates.size > 1 || referenceDates.size > 1
}

/** Compare mean per observed operating day over the INTERSECTION of completed clock hours. */
internal object CustomerLocationManualAnalyzer {
    fun analyze(
        rows: List<CustomerPaymentRecord>,
        sessions: List<CustomerTrafficSession>,
        storeId: Long,
        platform: String,
        request: LocationManualRequest,
        now: LocalDateTime = LocalDateTime.now()
    ): LocationManualResult {
        val all = sessions.asSequence().filter { it.storeId == storeId && storeId > 0 }
            .distinctBy { it.date }.sortedByDescending { it.date }.toList()
        val beforeAnchor = all.filter { it.date <= request.anchorDate }
        val (current, reference) = when (request.mode) {
            LocationCompareMode.DAY -> Pair(
                all.filter { it.date == request.anchorDate },
                all.filter { it.date == request.referenceDate }
            )
            LocationCompareMode.RANGE -> Pair(
                all.filter { it.date in request.currentStart..request.currentEnd },
                all.filter { it.date in request.referenceStart..request.referenceEnd }
            )
            LocationCompareMode.SESSIONS -> {
                val count = request.sessionCount.coerceIn(1, 30)
                val recent = beforeAnchor.take(count)
                recent to beforeAnchor.drop(recent.size).take(count)
            }
            LocationCompareMode.SMART -> emptyList<CustomerTrafficSession>() to emptyList()
        }
        val currentDates = current.map { it.date }.sorted()
        val referenceDates = reference.map { it.date }.sorted()
        val selectedSessions = current + reference
        val hours = selectedSessions.map {
            CustomerTrafficDeepDive.finishedHours(it, now).toSet()
        }.reduceOrNull { acc, next -> acc intersect next }.orEmpty().sorted()
        val included = rows.asSequence().filter {
            it.storeId == storeId && it.netAmount > 0.005 &&
                (platform == "ALL" || it.platform == platform)
        }.groupBy { it.businessDate }
        fun dayRows(day: String) = included[day].orEmpty().filter {
            it.tradeTime.drop(11).take(2).toIntOrNull() in hours
        }
        val currentRows = currentDates.associateWith(::dayRows)
        val referenceRows = referenceDates.associateWith(::dayRows)
        val activeCurrent = currentRows.filterValues { it.isNotEmpty() }
        val activeReference = referenceRows.filterValues { it.isNotEmpty() }
        fun avgCount(group: Map<String, List<CustomerPaymentRecord>>): Double? =
            group.takeIf { it.isNotEmpty() }?.values?.sumOf { it.size }?.toDouble()
                ?.div(group.size)
        fun avgAmount(group: Map<String, List<CustomerPaymentRecord>>): Double? =
            group.takeIf { it.isNotEmpty() }?.values?.sumOf { records ->
                records.sumOf { it.netAmount }
            }?.div(group.size)
        fun avgPayment(group: Map<String, List<CustomerPaymentRecord>>): Double? {
            val count = group.values.sumOf { it.size }
            return if (count == 0) null else group.values.sumOf { day ->
                day.sumOf { it.netAmount }
            } / count
        }
        val currentSources = activeCurrent.values.map { it.map { row -> row.platform }.toSet() }.toSet()
        val referenceSources = activeReference.values.map { it.map { row -> row.platform }.toSet() }.toSet()
        val hasFullSamples = current.isNotEmpty() && reference.isNotEmpty() &&
            activeCurrent.size == current.size && activeReference.size == reference.size
        val channelsMatch = currentSources.size == 1 && referenceSources.size == 1 &&
            currentSources.first() == referenceSources.first()
        val comparable = hasFullSamples && channelsMatch && hours.isNotEmpty()
        val perHour = hours.map { hour ->
            fun count(group: Map<String, List<CustomerPaymentRecord>>) =
                group.values.sumOf { day ->
                    day.count { it.tradeTime.drop(11).take(2).toIntOrNull() == hour }
                }.toDouble() / group.size.coerceAtLeast(1)
            fun amount(group: Map<String, List<CustomerPaymentRecord>>) =
                group.values.sumOf { day ->
                    day.filter { it.tradeTime.drop(11).take(2).toIntOrNull() == hour }
                        .sumOf { it.netAmount }
                } / group.size.coerceAtLeast(1)
            LocationManualHour(hour, count(activeCurrent), count(activeReference),
                amount(activeCurrent), amount(activeReference))
        }
        val warnings = buildList {
            if (current.isEmpty()) add("本期范围内没有该位置的正式营业记录。")
            if (reference.isEmpty()) add("对比期范围内没有该位置的正式营业记录。")
            if (hours.isEmpty()) add("两期没有相同的已结束完整营业小时，暂不计算变化。")
            if (current.isNotEmpty() && activeCurrent.size < current.size)
                add("本期有${current.size - activeCurrent.size}个营业日缺少该时段账单，不能当作零收款。")
            if (reference.isNotEmpty() && activeReference.size < reference.size)
                add("对比期有${reference.size - activeReference.size}个营业日缺少该时段账单，不能当作零收款。")
            if (hasFullSamples && !channelsMatch)
                add("两期微信／支付宝渠道覆盖不同，暂不计算百分比。")
            if (request.mode == LocationCompareMode.SESSIONS &&
                (current.size < request.sessionCount || reference.size < request.sessionCount))
                add("可比营业次数不足，需前后各${request.sessionCount}次。")
            add("只计可归属本位置的已导入正向交易；共享收款不分摊，付款笔数不等于独立顾客数。")
            add("账单导入不等于覆盖完整，区间数值按有流水营业日日均展示。")
        }
        return LocationManualResult(currentDates, referenceDates, activeCurrent.size,
            activeReference.size, hours, avgAmount(activeCurrent), avgAmount(activeReference),
            avgCount(activeCurrent), avgCount(activeReference),
            avgPayment(activeCurrent), avgPayment(activeReference),
            perHour, comparable, warnings)
    }
}

internal object CustomerBusinessDayNavigation {
    fun shift(day: LocalDate, direction: Int, businessDates: Set<LocalDate>,
              onlyBusinessDays: Boolean): LocalDate {
        if (!onlyBusinessDays) return day.plusDays(if (direction < 0) -1 else 1)
        return if (direction < 0)
            businessDates.filter { it.isBefore(day) }.maxOrNull() ?: day
        else businessDates.filter { it.isAfter(day) }.minOrNull() ?: day
    }
}
