package com.tianxian.fruit.data

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
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
