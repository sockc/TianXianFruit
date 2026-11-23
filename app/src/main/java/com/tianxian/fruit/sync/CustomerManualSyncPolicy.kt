package com.tianxian.fruit.sync

import com.tianxian.fruit.data.OvernightReceiptSnapshot

/**
 * A calendar-morning receipt belongs to (ledger, date, collector).
 * storeId is legacy API transport metadata, NOT a business edit or owner.
 * Comparing data-class equality here would treat a harmless transport-store
 * change as a competing manual modification and trigger a false CAS conflict.
 */
internal object CustomerManualSyncPolicy {
    fun sameOvernightBusinessValues(
        local: OvernightReceiptSnapshot,
        cloud: OvernightReceiptSnapshot
    ): Boolean =
        local.calendarDate == cloud.calendarDate &&
            local.partnerId == cloud.partnerId &&
            local.wechat == cloud.wechat &&
            local.alipay == cloud.alipay &&
            local.cash == cloud.cash &&
            local.wechatRecorded == cloud.wechatRecorded &&
            local.alipayRecorded == cloud.alipayRecorded &&
            local.cashRecorded == cloud.cashRecorded
    /**
     * Stable identity for the monetary/confirmation fields of a collector's
     * natural-calendar-morning entry. The transport-only store ID can change
     * with position ordering and must never make a clean row dirty.
     *
     * This is a client-side ACK fingerprint only; it does not change the
     * payload sent to the server or its authoritative row revisions.
     */
    fun overnightBusinessFingerprint(
        date: String,
        partnerSyncId: String,
        wechat: Double,
        alipay: Double,
        cash: Double,
        wechatRecorded: Boolean,
        alipayRecorded: Boolean,
        cashRecorded: Boolean
    ): String {
        require(date.isNotBlank() && partnerSyncId.isNotBlank())
        require(wechat.isFinite() && alipay.isFinite() && cash.isFinite())
        fun stableAmount(v: Double) = if (v == 0.0) "0" else v.toString()
        return listOf(
            "OVERNIGHT-ACK-V2", date, partnerSyncId,
            stableAmount(wechat), stableAmount(alipay), stableAmount(cash),
            wechatRecorded.toString(), alipayRecorded.toString(),
            cashRecorded.toString()
        ).joinToString("\u001f")
    }
}
