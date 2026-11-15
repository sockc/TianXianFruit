package com.tianxian.fruit.ui

/**
 * The draft calculations remain strict. Only delay the *visual* overdraw warning
 * on an untouched, unsaved selling form: the previous calendar morning may
 * have receipts even when this selling day has not started yet.
 *
 * Save-time validation is deliberately NOT routed through this object.
 */
internal object BusinessWarningDisplayPolicy {
    const val OVERNIGHT_OVERDRAW =
        "凌晨扣减超过今日收款金额，请核对本日微信/支付宝总额与凌晨金额"

    fun visibleError(
        computedError: String?,
        editingExistingRecord: Boolean,
        hasEnteredReceiptAmount: Boolean
    ): String? {
        if (computedError == OVERNIGHT_OVERDRAW &&
            !editingExistingRecord && !hasEnteredReceiptAmount) return null
        return computedError
    }
}
