package com.tianxian.fruit.data

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.round

/** A business day can extend past midnight; timestamps are compared in China time. */
internal data class BusinessWeatherWindow(
    val start: LocalDateTime,
    val end: LocalDateTime
) {
    val preStart: LocalDateTime get() = start.minusHours(3)

    fun duringBusiness(timestamp: String): Boolean = parse(timestamp)?.let {
        !it.isBefore(start) && it.isBefore(end)
    } ?: false

    fun beforeBusiness(timestamp: String): Boolean = parse(timestamp)?.let {
        !it.isBefore(preStart) && it.isBefore(start)
    } ?: false

    private fun parse(value: String): LocalDateTime? =
        runCatching {
            OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.of("Asia/Shanghai")).toLocalDateTime()
        }.getOrNull() ?: runCatching { LocalDateTime.parse(value) }.getOrNull()

    companion object {
        fun forDay(date: LocalDate, startTime: String, endTime: String): BusinessWeatherWindow {
            fun time(value: String, fallback: LocalTime): LocalTime =
                runCatching { LocalTime.parse(value) }.getOrDefault(fallback)
            val start = date.atTime(time(startTime, LocalTime.of(16, 0)))
            val end = if (endTime == "24:00") date.plusDays(1).atStartOfDay()
                else date.atTime(time(endTime, LocalTime.MIDNIGHT)).let {
                    if (it.isAfter(start)) it else it.plusDays(1)
                }
            return BusinessWeatherWindow(start, end)
        }
    }
}

internal object WeatherDisplayPolicy {
    const val MODE_HISTORICAL = "HISTORICAL"
    const val MODE_HOURLY = "HOURLY"
    const val MODE_TREND = "TREND"

    data class HourlyCoverage(
        val expected: Int,
        val actual: Int
    ) {
        val ratio: Double
            get() =
                if (expected <= 0) {
                    0.0
                } else {
                    (actual.toDouble() / expected.toDouble())
                        .coerceIn(0.0, 1.0)
                }
    }

    fun forecastDetailMode(
        selectedDate: LocalDate,
        today: LocalDate
    ): String {
        if (selectedDate.isBefore(today)) {
            return MODE_HISTORICAL
        }
        val daysAhead =
            ChronoUnit.DAYS.between(
                today,
                selectedDate
            )
        return if (daysAhead in 0L..4L) {
            MODE_HOURLY
        } else {
            MODE_TREND
        }
    }

    fun hourBucketOverlaps(
        window: BusinessWeatherWindow,
        timestamp: String
    ): Boolean {
        val bucketStart =
            parseLocal(timestamp)
                ?.withMinute(0)
                ?.withSecond(0)
                ?.withNano(0)
                ?: return false
        val bucketEnd =
            bucketStart.plusHours(1)
        return bucketEnd.isAfter(window.start) &&
            bucketStart.isBefore(window.end)
    }

    fun preOpenHourBucketOverlaps(
        window: BusinessWeatherWindow,
        timestamp: String
    ): Boolean {
        val bucketStart =
            parseLocal(timestamp)
                ?.withMinute(0)
                ?.withSecond(0)
                ?.withNano(0)
                ?: return false
        val bucketEnd =
            bucketStart.plusHours(1)
        return bucketEnd.isAfter(window.preStart) &&
            bucketStart.isBefore(window.start)
    }

    fun hourlyCoverage(
        window: BusinessWeatherWindow,
        timestamps: Iterable<String>
    ): HourlyCoverage {
        var expected = 0
        var cursor =
            window.start
                .withMinute(0)
                .withSecond(0)
                .withNano(0)
        while (cursor.isBefore(window.end)) {
            expected += 1
            cursor = cursor.plusHours(1)
        }

        val actual =
            timestamps
                .mapNotNull(::parseLocal)
                .map {
                    it.withMinute(0)
                        .withSecond(0)
                        .withNano(0)
                }
                .filter { bucketStart ->
                    val bucketEnd =
                        bucketStart.plusHours(1)
                    bucketEnd.isAfter(window.start) &&
                        bucketStart.isBefore(window.end)
                }
                .distinct()
                .size

        return HourlyCoverage(
            expected = expected,
            actual = actual
        )
    }

    fun alertRelevant(
        startTime: String,
        endTime: String,
        window: BusinessWeatherWindow
    ): Boolean {
        val start = parseLocal(startTime)
        val end = parseLocal(endTime)
        if (start == null && end == null) {
            return true
        }

        val relevantStart = window.preStart
        val relevantEnd = window.end
        return when {
            start != null && end != null ->
                !end.isBefore(relevantStart) &&
                    start.isBefore(relevantEnd)
            start != null ->
                start.isBefore(relevantEnd)
            else ->
                !end!!.isBefore(relevantStart)
        }
    }

    fun cacheTtlMillis(
        date: LocalDate,
        today: LocalDate,
        now: LocalDateTime,
        window: BusinessWeatherWindow,
        rainRisk: Boolean,
        hasAlert: Boolean
    ): Long {
        if (date.isBefore(today)) {
            return 24L * 60L * 60L * 1000L
        }

        val daysAhead =
            ChronoUnit.DAYS.between(
                today,
                date
            )
        if (daysAhead >= 5) {
            return 3L * 60L * 60L * 1000L
        }
        if (daysAhead >= 2) {
            return 60L * 60L * 1000L
        }
        if (daysAhead == 1L) {
            return 30L * 60L * 1000L
        }

        if (rainRisk || hasAlert) {
            return 5L * 60L * 1000L
        }

        val nearBusiness =
            !now.isBefore(window.preStart.minusHours(1)) &&
                now.isBefore(window.end.plusHours(1))
        return if (nearBusiness) {
            10L * 60L * 1000L
        } else {
            20L * 60L * 1000L
        }
    }

    fun splitBusinessWindow(
        window: BusinessWeatherWindow
    ): Pair<BusinessWeatherWindow, BusinessWeatherWindow> {
        val minutes =
            Duration.between(
                window.start,
                window.end
            ).toMinutes()
        val midpoint =
            window.start.plusMinutes(
                (minutes / 2L)
                    .coerceAtLeast(1L)
            )
        return BusinessWeatherWindow(
            window.start,
            midpoint
        ) to BusinessWeatherWindow(
            midpoint,
            window.end
        )
    }

    private fun parseLocal(
        value: String
    ): LocalDateTime? =
        runCatching {
            OffsetDateTime.parse(value)
                .atZoneSameInstant(
                    ZoneId.of("Asia/Shanghai")
                )
                .toLocalDateTime()
        }.getOrNull()
            ?: runCatching {
                LocalDateTime.parse(value)
            }.getOrNull()
}

/** Unknown batch costs stay unknown until stock reaches zero or that batch is corrected. */
internal class InventoryCostTracker {
    var quantity = 0.0
        private set
    var cost = 0.0
        private set
    var complete = true
        private set
    private var lastUnitCost: Double? = null
    val unitCost: Double? get() = when {
        quantity > 0.000001 && cost > 0.000001 -> cost / quantity
        quantity <= 0.000001 -> lastUnitCost
        else -> lastUnitCost
    }

    fun purchase(quantity: Double, cost: Double, costKnown: Boolean) {
        this.quantity += quantity
        this.cost += cost
        if (quantity > 0.000001 && !costKnown) complete = false
        if (this.quantity > 0.000001 && this.cost > 0.000001) {
            lastUnitCost = this.cost / this.quantity
        }
    }

    fun snapshot(remaining: Double) {
        if (remaining <= 0.000001) {
            quantity = 0.0
            cost = 0.0
            lastUnitCost = null
            complete = true
        } else {
            val basis = unitCost
            if (basis == null) complete = false
            quantity = remaining
            cost = remaining * (basis ?: 0.0)
            if (basis != null) lastUnitCost = basis
        }
    }
}

internal object StoreDailyRecoveryPolicy {
    fun logicalKey(
        date: String,
        storeName: String,
        storeSyncId: String,
        syncId: String
    ): String {
        val cleanDate =
            date.trim()
        val cleanName =
            storeName
                .trim()
                .lowercase()
        val cleanStoreSyncId =
            storeSyncId.trim()

        return when {
            cleanDate.isNotBlank() &&
                cleanName.isNotBlank() ->
                "$cleanDate|name:$cleanName"

            cleanDate.isNotBlank() &&
                cleanStoreSyncId.isNotBlank() ->
                "$cleanDate|store:$cleanStoreSyncId"

            else ->
                "sync:$syncId"
        }
    }

    fun preferCandidate(
        currentUpdatedAt: Long,
        currentSequence: Long,
        candidateUpdatedAt: Long,
        candidateSequence: Long
    ): Boolean =
        when {
            candidateUpdatedAt >
                currentUpdatedAt ->
                true

            candidateUpdatedAt <
                currentUpdatedAt ->
                false

            else ->
                candidateSequence >
                    currentSequence
        }
}

internal object SyncDeletePolicy {
    // Derived rows do not turn a single source-document deletion into a bulk deletion.
    val derivedTables = setOf(
        "profit_distribution", "daily_cash_settlement", "settlement_partner", "settlement_transfer",
        "business_weather_history", "daily_business_score", "weather_snapshot",
        "fruit_season_catalog", "fruit_alias", "fruit_season_region", "fruit_profile"
    )

    fun businessKey(table: String, syncId: String, parentSyncId: String? = null): String? {
        if (table in derivedTables) return null
        val parentTable = when (table) {
            "purchase_item", "purchase_activity" -> "purchase_order"
            "purchase_plan_item", "purchase_collaboration" -> "purchase_plan"
            "profit_settlement_item" -> "profit_settlement_batch"
            else -> null
        }
        return if (parentTable != null && !parentSyncId.isNullOrBlank()) "$parentTable|$parentSyncId"
            else "$table|$syncId"
    }

    fun requiresConfirmation(keys: Collection<String?>): Boolean =
        keys.filterNotNull().distinct().size >= 2
}

internal object DraftRefreshPolicy {
    fun sameQuantity(a: String?, b: String?): Boolean {
        if (a == b) return true
        val left = a?.toDoubleOrNull() ?: return false
        val right = b?.toDoubleOrNull() ?: return false
        return left.isFinite() && right.isFinite() && kotlin.math.abs(left - right) < 0.000001
    }

    /** Update clean fields only. Retain edited, removed fields so a deletion cannot erase a draft. */
    fun merge(inputs: MutableMap<String, String>, baseline: MutableMap<String, String>, latest: Map<String, String>) {
        inputs.keys.toList().forEach { key ->
            if (key !in latest && sameQuantity(inputs[key], baseline[key])) inputs.remove(key)
        }
        latest.forEach { (key, value) ->
            if (key !in inputs || sameQuantity(inputs[key], baseline[key])) inputs[key] = value
        }
        baseline.clear()
        baseline.putAll(latest)
    }
}

internal object SyncStatusPolicy {
    fun error(globalError: String, tableError: String): String = tableError.ifBlank { globalError }
}

internal object BusinessAdvicePolicy {
    const val MIN_HISTORY_FOR_CONCLUSION = 3
    const val MIN_HISTORY_FOR_MEDIUM_CONFIDENCE = 7

    private val abnormalHistoryTags = setOf(
        "短时营业",
        "提前收摊",
        "缺货",
        "临时换位"
    )

    fun weatherEvidenceStatus(
        coverageRatio: Double,
        stale: Boolean
    ): String = when {
        stale -> "STALE"
        coverageRatio >= 0.85 -> "COMPLETE"
        coverageRatio >= 0.50 -> "PARTIAL"
        coverageRatio > 0.0 -> "SPARSE"
        else -> "MISSING"
    }

    fun recommendation(
        score: Int,
        validHistoryCount: Int,
        similarCount: Int,
        weatherStatus: String
    ): String {
        if (validHistoryCount < MIN_HISTORY_FOR_CONCLUSION) return "资料不足"
        if (
            weatherStatus in setOf("MISSING", "SPARSE", "STALE") &&
            validHistoryCount < MIN_HISTORY_FOR_MEDIUM_CONFIDENCE
        ) {
            return "资料不足"
        }
        if (similarCount <= 0 && validHistoryCount < MIN_HISTORY_FOR_MEDIUM_CONFIDENCE) {
            return "资料不足"
        }
        return if (score >= 75) "建议正常营业" else "谨慎营业"
    }

    fun confidence(
        validHistoryCount: Int,
        similarCount: Int,
        weatherStatus: String
    ): String = when {
        validHistoryCount < MIN_HISTORY_FOR_CONCLUSION -> "INSUFFICIENT"
        weatherStatus in setOf("MISSING", "SPARSE", "STALE") &&
            validHistoryCount < MIN_HISTORY_FOR_MEDIUM_CONFIDENCE -> "INSUFFICIENT"
        similarCount >= 10 &&
            validHistoryCount >= 20 &&
            weatherStatus == "COMPLETE" -> "HIGH"
        similarCount >= 5 &&
            validHistoryCount >= 10 &&
            weatherStatus in setOf("COMPLETE", "PARTIAL") -> "MEDIUM"
        else -> "LOW"
    }

    fun historyUsable(
        durationRatio: Double?,
        specialTag: String
    ): Boolean {
        if (specialTag.trim() in abnormalHistoryTags) return false
        if (durationRatio != null && durationRatio < 0.70) return false
        return true
    }

    fun snapshotStage(
        targetDate: LocalDate,
        now: LocalDateTime,
        window: BusinessWeatherWindow
    ): String = when {
        targetDate.isAfter(now.toLocalDate()) -> "PRE_OPEN"
        targetDate.isBefore(now.toLocalDate()) -> "FINAL"
        now.isBefore(window.start) -> "PRE_OPEN"
        now.isBefore(window.end) -> "LIVE"
        else -> "FINAL"
    }
}

internal object SyncTablePolicy {
    // Forecast snapshots and calculated operating advice are disposable local data.
    // Actual business weather history remains syncable because it is an accounting input.
    val localOnlyTables = setOf("weather_snapshot", "daily_business_score")

    fun isLocalOnly(table: String): Boolean = table in localOnlyTables
}

internal object FundBalancePolicy {
    /**
     * The settlement center is a clearing account. Its outstanding net balance
     * must be the exact opposite of all non-center partners, never an independently
     * accumulated historical balance.
     */
    fun centerBalance(counterpartyBalances: Iterable<Double>): Double =
        round(-counterpartyBalances.sum() * 100.0) / 100.0
}

internal object SyncQueueSql {
    /** Select one current change per row, retaining original parent-before-child ordering. */
    const val LATEST_PENDING = """
        NOT EXISTS (
            SELECT 1 FROM sync_change_log newer
            WHERE newer.uploaded=0 AND newer.table_name=q.table_name
              AND newer.record_sync_id=q.record_sync_id AND newer.id>q.id
        )
    """
    const val ORIGINAL_ORDER = """
        (SELECT MIN(first.id) FROM sync_change_log first
         WHERE first.uploaded=0 AND first.table_name=q.table_name
           AND first.record_sync_id=q.record_sync_id)
    """
}
