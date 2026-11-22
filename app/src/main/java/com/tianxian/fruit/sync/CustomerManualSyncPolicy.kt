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
}
