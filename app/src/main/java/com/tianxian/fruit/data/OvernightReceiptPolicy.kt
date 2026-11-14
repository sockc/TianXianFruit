package com.tianxian.fruit.data

import java.time.LocalDate
import kotlin.math.round

/**
 * A close-of-shift snapshot of the payment provider's CURRENT CALENDAR DAY
 * total after midnight. A snapshot for date D belongs to selling day D-1.
 *
 * These are auxiliary drafts: never write directly to official daily records.
 */
internal data class OvernightReceiptSnapshot(
    val calendarDate: String,
    val storeId: Long,
    val partnerId: Long,
    val wechat: Double,
    val alipay: Double
)

internal data class NaturalDayReceiptDraft(
    val businessDate: String,
    val storeId: Long,
    val partnerId: Long,
    val wechat: Double,
    val alipay: Double
)

internal data class OvernightReceiptAdjustment(
    val naturalWechat: Double,
    val naturalAlipay: Double,
    val previousWechat: Double,
    val previousAlipay: Double,
    val followingWechat: Double,
    val followingAlipay: Double,
    val adjustedWechat: Double,
    val adjustedAlipay: Double,
    val previousConfirmed: Boolean,
    val followingConfirmed: Boolean
) {
    val fullyConfirmed: Boolean get() = previousConfirmed && followingConfirmed
    val adjustedTotal: Double get() = adjustedWechat + adjustedAlipay
}

internal object OvernightReceiptPolicy {
    private fun money(value: Double): Double = round(value * 100.0) / 100.0

    /** Strictly one partner/account snapshot per calendar date; never double count. */
    fun canRegister(
        snapshot: OvernightReceiptSnapshot,
        saved: List<OvernightReceiptSnapshot>
    ): Boolean = snapshot.storeId > 0 && snapshot.partnerId > 0 &&
        runCatching { LocalDate.parse(snapshot.calendarDate) }.isSuccess &&
        listOf(snapshot.wechat, snapshot.alipay).all { it.isFinite() && it >= 0.0 } &&
        saved.none { it.calendarDate == snapshot.calendarDate &&
            it.partnerId == snapshot.partnerId && it.storeId != snapshot.storeId }

    fun adjustment(
        draft: NaturalDayReceiptDraft,
        saved: List<OvernightReceiptSnapshot>
    ): OvernightReceiptAdjustment? {
        val day = runCatching { LocalDate.parse(draft.businessDate) }.getOrNull()
            ?: return null
        if (draft.storeId <= 0 || draft.partnerId <= 0 ||
            listOf(draft.wechat, draft.alipay).any { !it.isFinite() || it < 0.0 }) return null

        // The PREVIOUS shift may be at another store but use the same merchant
        // account. Subtract it once, independent of its actual store.
        val previous = saved.filter {
            it.calendarDate == day.toString() && it.partnerId == draft.partnerId
        }.singleOrNull()
        // Following calendar day's early-morning takings MUST be attributed to
        // this selected store and collector; never add another store's money.
        val following = saved.filter {
            it.calendarDate == day.plusDays(1).toString() &&
                it.partnerId == draft.partnerId &&
                it.storeId == draft.storeId
        }.singleOrNull()
        val w = money(draft.wechat - (previous?.wechat ?: 0.0) +
            (following?.wechat ?: 0.0))
        val a = money(draft.alipay - (previous?.alipay ?: 0.0) +
            (following?.alipay ?: 0.0))
        if (w < 0.0 || a < 0.0) return null
        return OvernightReceiptAdjustment(
            naturalWechat = money(draft.wechat),
            naturalAlipay = money(draft.alipay),
            previousWechat = money(previous?.wechat ?: 0.0),
            previousAlipay = money(previous?.alipay ?: 0.0),
            followingWechat = money(following?.wechat ?: 0.0),
            followingAlipay = money(following?.alipay ?: 0.0),
            adjustedWechat = w,
            adjustedAlipay = a,
            previousConfirmed = previous != null,
            followingConfirmed = following != null
        )
    }

    /** Enforce one snapshot for the given merchant and calendar date. */
    fun replace(
        snapshot: OvernightReceiptSnapshot,
        saved: List<OvernightReceiptSnapshot>
    ): List<OvernightReceiptSnapshot>? {
        if (!canRegister(snapshot, saved)) return null
        return saved.filterNot {
            it.calendarDate == snapshot.calendarDate &&
                it.partnerId == snapshot.partnerId
        } + snapshot
    }
}
