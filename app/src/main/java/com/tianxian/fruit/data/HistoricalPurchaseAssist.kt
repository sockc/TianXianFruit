package com.tianxian.fruit.data

import kotlin.math.round

data class ParsedPurchaseCandidate(
    val platform: PaymentBillPlatform,
    val tradeTime: String,
    val businessDate: String,
    val amount: Double,
    val transactionRef: String,
    val dedupeKey: String,
    val counterpartyKey: String,
    val counterpartyName: String,
    val identityConfidence: String,
    val transactionType: String,
    val tradeStatus: String
)

data class HistoricalPurchaseCandidateRecord(
    val id: Long,
    val platform: String,
    val tradeTime: String,
    val businessDate: String,
    val amount: Double,
    val transactionRef: String,
    val counterpartyKey: String,
    val counterpartyName: String,
    val identityConfidence: String,
    val transactionType: String,
    val tradeStatus: String,
    val status: String,
    val sourceFile: String,
    val createdAt: Long,
    val updatedAt: Long
)

data class HistoricalPurchaseCandidateAnalysis(
    val record: HistoricalPurchaseCandidateRecord,
    val score: Int,
    val confidence: String,
    val reasons: List<String>,
    val knownSupplier: Boolean,
    val confirmedHistoryCount: Int,
    val occurrenceCount: Int,
    val hasBusinessEvidence: Boolean
)

data class HistoricalPurchaseDaySummary(
    val date: String,
    val pendingCount: Int,
    val pendingAmount: Double,
    val highConfidenceCount: Int,
    val highConfidenceAmount: Double,
    val confirmedCount: Int,
    val confirmedAmount: Double,
    val ignoredCount: Int
)

internal object HistoricalPurchaseScoring {
    const val HIGH_SCORE = 75
    const val MEDIUM_SCORE = 55

    fun score(
        record: HistoricalPurchaseCandidateRecord,
        hasBusinessEvidence: Boolean,
        knownSupplier: Boolean,
        confirmedHistoryCount: Int,
        occurrenceCount: Int
    ): HistoricalPurchaseCandidateAnalysis {
        var score = 0
        val reasons = mutableListOf<String>()

        score += 25
        reasons += "06:00–14:00采购时段"

        when {
            record.amount >= 800.0 -> {
                score += 33
                reasons += "金额较大"
            }
            record.amount >= 300.0 -> {
                score += 28
                reasons += "金额符合常见采购"
            }
            record.amount >= 100.0 -> {
                score += 20
                reasons += "金额≥¥100"
            }
            else -> {
                score += 10
                reasons += "金额≥¥40"
            }
        }

        val evidence =
            (record.transactionType + "|" + record.tradeStatus)
                .lowercase()

        when {
            listOf("二维码", "扫码", "付款码", "商户消费", "消费")
                .any { evidence.contains(it) } -> {
                score += 8
                reasons += "扫码/商户付款"
            }
            evidence.contains("转账") -> {
                score += 3
                reasons += "转账付款"
            }
        }

        if (hasBusinessEvidence) {
            score += 30
            reasons += "当天有营业/经营收款"
        }

        if (knownSupplier) {
            score += 35
            reasons += "已标记供应商"
        }

        if (confirmedHistoryCount >= 3) {
            score += 20
            reasons += "该对象已有多次确认采购"
        } else if (confirmedHistoryCount >= 1) {
            score += 12
            reasons += "该对象已有确认采购"
        }

        if (occurrenceCount >= 8) {
            score += 10
            reasons += "早间付款长期重复出现"
        } else if (occurrenceCount >= 3) {
            score += 5
            reasons += "早间付款重复出现"
        }

        val bounded = score.coerceIn(0, 100)
        val confidence =
            when {
                bounded >= HIGH_SCORE -> "HIGH"
                bounded >= MEDIUM_SCORE -> "MEDIUM"
                else -> "LOW"
            }

        return HistoricalPurchaseCandidateAnalysis(
            record = record,
            score = bounded,
            confidence = confidence,
            reasons = reasons,
            knownSupplier = knownSupplier,
            confirmedHistoryCount = confirmedHistoryCount,
            occurrenceCount = occurrenceCount,
            hasBusinessEvidence = hasBusinessEvidence
        )
    }

    fun roundMoney(value: Double): Double =
        round(value * 100.0) / 100.0
}
