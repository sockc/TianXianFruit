package com.tianxian.fruit.data

import java.time.LocalDate
import kotlin.math.round

/**
 * Collector-level calendar-morning receipt. Identity is ledger (in the owning
 * SharedPreferences) + calendarDate + partnerId. storeId is inert legacy
 * transport metadata for servers requiring store_sync_id; it MUST NOT decide
 * read/write ownership, deductions or business-record deletion.
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
    val savedCash: Double? = null,
    // Opt-in status per calendar day. This defaults to deduction for NEW
    // drafts; persisted pre-123 cash drafts default false during migration.
    val deductCash: Boolean = true
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
    ): Boolean = snapshot.storeId >= 0 && snapshot.partnerId > 0 &&
        runCatching { LocalDate.parse(snapshot.calendarDate) }.isSuccess &&
        listOf(snapshot.wechat, snapshot.alipay, snapshot.cash).all(::validAmount)

    fun replace(
        snapshot: OvernightReceiptSnapshot,
        saved: List<OvernightReceiptSnapshot>
    ): List<OvernightReceiptSnapshot>? {
        if (!canRegister(snapshot, saved)) return null
        return saved.filterNot {
            it.calendarDate == snapshot.calendarDate && it.partnerId == snapshot.partnerId
        } + snapshot
    }

    /**
     * Legacy devices could retain conflicting morning rows for the same
     * calendar date and collector. Never silently interpret a duplicate
     * (singleOrNull()==null) as an absent/zero midnight receipt.
     */
    fun hasDuplicateMorning(
        draft: NaturalDayReceiptDraft,
        saved: List<OvernightReceiptSnapshot>
    ): Boolean {
        val day = runCatching { LocalDate.parse(draft.businessDate) }.getOrNull()
            ?: return false
        if (draft.partnerId <= 0L) return false
        val dates = setOf(day.toString(), day.plusDays(1).toString())
        return saved.filter {
            it.partnerId == draft.partnerId && it.calendarDate in dates
        }.groupingBy { it.calendarDate }.eachCount().values.any { it > 1 }
    }

    fun adjustment(
        draft: NaturalDayReceiptDraft,
        saved: List<OvernightReceiptSnapshot>
    ): OvernightReceiptAdjustment? {
        val day = runCatching { LocalDate.parse(draft.businessDate) }.getOrNull() ?: return null
        if (draft.storeId <= 0 || draft.partnerId <= 0 ||
            listOf(draft.wechat, draft.alipay, draft.cash).any { !validAmount(it) } ||
            hasDuplicateMorning(draft, saved)) return null
        val previous = saved.filter {
            it.calendarDate == day.toString() && it.partnerId == draft.partnerId
        }.singleOrNull()
        val following = saved.filter {
            it.calendarDate == day.plusDays(1).toString() &&
                it.partnerId == draft.partnerId
        }.singleOrNull()
        val previousWechat = if (draft.deductWechat) previous?.amount("WECHAT") ?: 0.0 else 0.0
        val previousAlipay = if (draft.deductAlipay) previous?.amount("ALIPAY") ?: 0.0 else 0.0
        val nextWechat = following?.amount("WECHAT") ?: 0.0
        val nextAlipay = following?.amount("ALIPAY") ?: 0.0
        val previousCash = if (draft.deductCash) previous?.amount("CASH") ?: 0.0 else 0.0
        val nextCash = following?.amount("CASH") ?: 0.0
        val wx = money(draft.wechat - previousWechat + nextWechat)
        val ali = money(draft.alipay - previousAlipay + nextAlipay)
        val cash = money(draft.cash - previousCash + nextCash)
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


/**
 * A seller's calendar-morning receipt is independent of where that seller
 * works. Deleting one stall's sale must never clear a shared morning receipt.
 * Natural-day drafts remain the per-sale provenance for the one adjustment.
 */
internal object OvernightReceiptDeletionPolicy {
    fun isClearedSnapshot(row: OvernightReceiptSnapshot): Boolean =
        !row.wechatRecorded && !row.alipayRecorded && !row.cashRecorded &&
            row.wechat == 0.0 && row.alipay == 0.0 && row.cash == 0.0

    fun isClearedNatural(row: NaturalDayReceiptDraft): Boolean =
        row.wechat == 0.0 && row.alipay == 0.0 && row.cash == 0.0 &&
            row.savedWechat == null && row.savedAlipay == null && row.savedCash == null &&
            row.deductWechat && row.deductAlipay && row.deductCash

    fun clearedNatural(
        row: NaturalDayReceiptDraft,
        businessDate: String,
        storeId: Long
    ): NaturalDayReceiptDraft {
        if (row.businessDate != businessDate || row.storeId != storeId) return row
        return row.copy(
            wechat = 0.0, alipay = 0.0, cash = 0.0,
            deductWechat = true, deductAlipay = true, deductCash = true,
            savedWechat = null, savedAlipay = null, savedCash = null
        )
    }
}

/**
 * A collector's morning adjustment may be applied to only ONE position on
 * any given business date. Actual business receipts remain position-specific.
 *
 * Existing, nonempty natural-day sources retain their original owner. For new
 * records, a previously saved active sale by this collector takes priority.
 * Unrelated collectors and deleted/blank sources never block another stall.
 */
internal object OvernightReceiptAllocationPolicy {
    fun ownsAdjustment(
        businessDate: String,
        storeId: Long,
        partnerId: Long,
        drafts: List<NaturalDayReceiptDraft>,
        activeStoreCollectors: List<Pair<Long, Long>>
    ): Boolean {
        if (storeId <= 0L || partnerId <= 0L) return false
        val claimed = drafts.firstOrNull {
            it.businessDate == businessDate && it.partnerId == partnerId &&
                !OvernightReceiptDeletionPolicy.isClearedNatural(it) &&
                // A stale old position's source should not reserve the
                // collector forever after that position ceased trading.
                (it.storeId == storeId ||
                    activeStoreCollectors.contains(it.storeId to partnerId))
        }
        if (claimed != null) return claimed.storeId == storeId
        return activeStoreCollectors.none { (otherStore, otherPartner) ->
            otherStore != storeId && otherPartner == partnerId
        }
    }
}
