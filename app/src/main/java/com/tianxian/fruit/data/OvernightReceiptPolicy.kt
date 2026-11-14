package com.tianxian.fruit.data

import java.time.LocalDate
import kotlin.math.round

/**
 * Store/day/collector auxiliary metadata. "calendarDate" is the NEXT calendar
 * morning; a snapshot for D belongs to the selling session on D-1.
 *
 * Recorded flags distinguish untouched "-" from a confirmed zero.
 */
internal data class OvernightReceiptSnapshot(
    val calendarDate: String,
    val storeId: Long,
    val partnerId: Long,
    val wechat: Double = 0.0,
    val alipay: Double = 0.0,
    val cash: Double = 0.0,
    val wechatRecorded: Boolean = true,
    val alipayRecorded: Boolean = true,
    val cashRecorded: Boolean = false
) {
    fun amount(channel: String): Double? = when(channel) {
        "WECHAT" -> wechat.takeIf { wechatRecorded }
        "ALIPAY" -> alipay.takeIf { alipayRecorded }
        "CASH" -> cash.takeIf { cashRecorded }
        else -> null
    }

    fun withAmount(channel: String, value: Double?): OvernightReceiptSnapshot = when(channel) {
        "WECHAT" -> copy(wechat = value ?: 0.0, wechatRecorded = value != null)
        "ALIPAY" -> copy(alipay = value ?: 0.0, alipayRecorded = value != null)
        "CASH" -> copy(cash = value ?: 0.0, cashRecorded = value != null)
        else -> this
    }
}

internal data class NaturalDayReceiptDraft(
    val businessDate: String,
    val storeId: Long,
    val partnerId: Long,
    val wechat: Double = 0.0,
    val alipay: Double = 0.0,
    val cash: Double = 0.0,
    val deductWechat: Boolean = true,
    val deductAlipay: Boolean = true,
    // Snapshot of the formal saved channel totals, for stale-source detection.
    // Without these fields an older copied record may be already adjusted.
    val savedWechat: Double? = null,
    val savedAlipay: Double? = null,
    val savedCash: Double? = null
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
    val adjustedCash: Double,
    val previousConfirmed: Boolean,
    val followingConfirmed: Boolean
) {
    val fullyConfirmed: Boolean get() = previousConfirmed && followingConfirmed
    val adjustedTotal: Double get() = adjustedWechat + adjustedAlipay + adjustedCash
}

/**
 * Pure, deterministic calculation. Form input contains ORIGINAL calendar-day
 * totals, never the already adjusted amount. Apply exactly once at SAVE.
 *
 * WeChat/Alipay: natural day - last shift's early morning + this shift's
 * following morning. Cash: manually counted pre-midnight + following morning.
 */
internal object OvernightReceiptPolicy {
    private fun money(value: Double): Double = round(value * 100.0) / 100.0
    private fun validAmount(v: Double) = v.isFinite() && v >= 0.0 && v <= 100000000.0

    fun canRegister(
        snapshot: OvernightReceiptSnapshot,
        saved: List<OvernightReceiptSnapshot>
    ): Boolean = snapshot.storeId > 0 && snapshot.partnerId > 0 &&
        runCatching { LocalDate.parse(snapshot.calendarDate) }.isSuccess &&
        listOf(snapshot.wechat, snapshot.alipay, snapshot.cash).all(::validAmount) &&
        saved.none {
            it.calendarDate == snapshot.calendarDate &&
                it.partnerId == snapshot.partnerId && it.storeId != snapshot.storeId
        }

    fun replace(
        snapshot: OvernightReceiptSnapshot,
        saved: List<OvernightReceiptSnapshot>
    ): List<OvernightReceiptSnapshot>? {
        if (!canRegister(snapshot, saved)) return null
        return saved.filterNot {
            it.calendarDate == snapshot.calendarDate && it.partnerId == snapshot.partnerId
        } + snapshot
    }

    fun adjustment(
        draft: NaturalDayReceiptDraft,
        saved: List<OvernightReceiptSnapshot>
    ): OvernightReceiptAdjustment? {
        val day = runCatching { LocalDate.parse(draft.businessDate) }.getOrNull() ?: return null
        if (draft.storeId <= 0 || draft.partnerId <= 0 ||
            listOf(draft.wechat, draft.alipay, draft.cash).any { !validAmount(it) }) return null
        val previous = saved.filter {
            it.calendarDate == day.toString() && it.partnerId == draft.partnerId
        }.singleOrNull()
        val following = saved.filter {
            it.calendarDate == day.plusDays(1).toString() &&
                it.partnerId == draft.partnerId && it.storeId == draft.storeId
        }.singleOrNull()
        val previousWechat = if (draft.deductWechat) previous?.amount("WECHAT") ?: 0.0 else 0.0
        val previousAlipay = if (draft.deductAlipay) previous?.amount("ALIPAY") ?: 0.0 else 0.0
        val nextWechat = following?.amount("WECHAT") ?: 0.0
        val nextAlipay = following?.amount("ALIPAY") ?: 0.0
        val nextCash = following?.amount("CASH") ?: 0.0
        val wx = money(draft.wechat - previousWechat + nextWechat)
        val ali = money(draft.alipay - previousAlipay + nextAlipay)
        val cash = money(draft.cash + nextCash)
        if (wx < -0.005 || ali < -0.005 || cash < -0.005) return null
        return OvernightReceiptAdjustment(
            naturalWechat = money(draft.wechat),
            naturalAlipay = money(draft.alipay),
            previousWechat = money(previousWechat),
            previousAlipay = money(previousAlipay),
            followingWechat = money(nextWechat),
            followingAlipay = money(nextAlipay),
            adjustedWechat = wx.coerceAtLeast(0.0),
            adjustedAlipay = ali.coerceAtLeast(0.0),
            adjustedCash = cash.coerceAtLeast(0.0),
            previousConfirmed = (!draft.deductWechat || previous?.wechatRecorded == true) &&
                (!draft.deductAlipay || previous?.alipayRecorded == true),
            followingConfirmed = following?.wechatRecorded == true &&
                following?.alipayRecorded == true && following?.cashRecorded == true
        )
    }
}
