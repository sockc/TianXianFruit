package com.tianxian.fruit.sync

import kotlin.math.abs

/**
 * Identity of an immutable merchant receipt already stored locally.
 *
 * Different CSV exports may contain the same payment with a different file
 * hash, file name, or customer display label. Those are not financial
 * differences. The receipt time, business day, amounts, stable order ID and
 * recorded store identity must agree before acknowledging a cloud duplicate.
 */
internal data class CustomerReceiptFinancialIdentity(
    val platform: String,
    val dedupeKey: String,
    val transactionRef: String,
    val tradeTime: String,
    val businessDate: String,
    val amount: Double,
    val refundAmount: Double,
    val netAmount: Double,
    val storeSyncId: String
)

internal object CustomerReceiptIdentityPolicy {
    private fun sameCents(a: Double, b: Double): Boolean =
        a.isFinite() && b.isFinite() && abs(a - b) < 0.005

    fun matches(
        local: CustomerReceiptFinancialIdentity,
        remote: CustomerReceiptFinancialIdentity
    ): Boolean =
        local.platform == remote.platform &&
            local.dedupeKey.isNotBlank() && local.dedupeKey == remote.dedupeKey &&
            local.transactionRef == remote.transactionRef &&
            local.tradeTime == remote.tradeTime &&
            local.businessDate == remote.businessDate &&
            local.storeSyncId == remote.storeSyncId &&
            sameCents(local.amount, remote.amount) &&
            sameCents(local.refundAmount, remote.refundAmount) &&
            sameCents(local.netAmount, remote.netAmount)
}
