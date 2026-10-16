package com.tianxian.fruit.data

import kotlin.math.round

/**
 * Advisory comparison between manually saved daily takings and what has been
 * OBSERVED in locally imported payment receipts. It must never change the
 * authoritative business record or claim a missing receipt proves lost money.
 */
data class CustomerPaymentReconciliation(
    val date: String,
    val storeId: Long,
    val storeName: String,
    val wechatBusiness: Double,
    val wechatObserved: Double,
    val alipayBusiness: Double,
    val alipayObserved: Double,
    val wechatPayments: Int,
    val alipayPayments: Int,
    val unmatchedPaymentsForDay: Int,
    val sharedWechatPayments: Int = 0,
    val sharedAlipayPayments: Int = 0
) {
    val observedPayments: Int get() = wechatPayments + alipayPayments
    val hasSharedAccount: Boolean get() = sharedWechatPayments > 0 || sharedAlipayPayments > 0
    val businessTotal: Double get() = roundMoney(wechatBusiness + alipayBusiness)
    val observedTotal: Double get() = roundMoney(wechatObserved + alipayObserved)
    val difference: Double get() = roundMoney(observedTotal - businessTotal)

    private fun roundMoney(v: Double): Double = round(v * 100.0) / 100.0
}

internal object CustomerPaymentReconciliationEngine {
    fun inspect(
        records: List<StoreDailyRecord>,
        receipts: List<CustomerPaymentRecord>,
        date: String,
        selectedStoreId: Long = 0L,
        sharedCollectors: List<SharedCollectorTraffic> = emptyList()
    ): List<CustomerPaymentReconciliation> {
        val positive = receipts.filter {
            it.businessDate == date && it.amount > 0.005 && it.netAmount > 0.005
        }
        val unmatched = positive.count { it.storeId <= 0L &&
            it.storeName != CustomerCollectorStoreAttribution.SHARED_STORE_NAME }
        return records.filter { it.date == date && it.storeId > 0L &&
            (selectedStoreId == 0L || it.storeId == selectedStoreId) }
            .map { saved ->
                val payments = positive.filter { it.storeId == saved.storeId }
                val wechat = payments.filter { it.platform == "WECHAT" }
                val alipay = payments.filter { it.platform == "ALIPAY" }
                CustomerPaymentReconciliation(
                    date = date,
                    storeId = saved.storeId,
                    storeName = saved.storeName,
                    wechatBusiness = saved.wechatIncome,
                    wechatObserved = wechat.sumOf { it.netAmount },
                    alipayBusiness = saved.alipayIncome,
                    alipayObserved = alipay.sumOf { it.netAmount },
                    wechatPayments = wechat.size,
                    alipayPayments = alipay.size,
                    unmatchedPaymentsForDay = unmatched,
                    sharedWechatPayments = sharedCollectors.filter {
                        it.date == date && saved.storeId in it.storeIds && it.platform == "WECHAT"
                    }.sumOf { it.payments },
                    sharedAlipayPayments = sharedCollectors.filter {
                        it.date == date && saved.storeId in it.storeIds && it.platform == "ALIPAY"
                    }.sumOf { it.payments }
                )
            }
    }
}
