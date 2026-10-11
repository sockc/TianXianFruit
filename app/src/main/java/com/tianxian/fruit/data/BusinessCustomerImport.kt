package com.tianxian.fruit.data

/**
 * 从已经导入的电子支付账单中提取选中摊位的顾客数。
 *
 * 不写营业记录、不修改电子收款。只有 HIGH/MEDIUM 的稳定标识可用于判断新老客，
 * LOW/匿名、未归属位置以及排除的大额收款均不自动计入，以免误报老客流失。
 */
data class BusinessCustomerImportPreview(
    val newCustomers: Int,
    val oldCustomers: Int,
    val uniqueCustomers: Int,
    val paymentCount: Int,
    val unknownIdentityPayments: Int,
    val unmatchedStorePayments: Int,
    val valid: Boolean,
    val explanation: String
)

object BusinessCustomerImportEngine {
    fun preview(
        date: String,
        storeId: Long,
        includedRecords: List<CustomerPaymentRecord>
    ): BusinessCustomerImportPreview {
        if (storeId <= 0 || date.isBlank()) {
            return empty("请先选择营业位置")
        }
        val onDay = includedRecords.filter { it.businessDate == date && it.netAmount > 0.005 }
        val assigned = onDay.filter { it.storeId == storeId }
        val eligible = assigned.filter {
            it.customerKey.isNotBlank() &&
                it.identityConfidence in setOf("HIGH", "MEDIUM")
        }
        val unknown = assigned.size - eligible.size
        val unmatched = onDay.count { it.storeId <= 0L }
        val unique = eligible.map { it.customerKey }.distinct()
        val previousKeys = includedRecords.asSequence()
            .filter {
                it.storeId == storeId &&
                    it.businessDate < date &&
                    it.netAmount > 0.005 &&
                    it.customerKey.isNotBlank() &&
                    it.identityConfidence in setOf("HIGH", "MEDIUM")
            }
            .map { it.customerKey }
            .toSet()
        val old = unique.count { it in previousKeys }
        val fresh = unique.size - old
        val explanation = when {
            onDay.isEmpty() -> "当天没有可用的已导入经营收款，请先在采购页导入微信/支付宝账单"
            assigned.isEmpty() -> "当天账单没有匹配到当前营业位置的收款；请先核对位置归属"
            unique.isEmpty() -> "当天客户标识无法可靠识别，不自动把匿名交易当成新客"
            else -> "可识别电子支付客户 ${unique.size} 人（新客 ${fresh}、老客 ${old}）。" +
                (if (unknown > 0) "另有 ${unknown} 笔客户标识不可靠，未计入。" else "") +
                (if (unmatched > 0) "另有 ${unmatched} 笔账单位置未匹配，未计入。" else "") +
                "现金客户不在本次导入范围内。"
        }
        return BusinessCustomerImportPreview(
            newCustomers = fresh,
            oldCustomers = old,
            uniqueCustomers = unique.size,
            paymentCount = eligible.size,
            unknownIdentityPayments = unknown,
            unmatchedStorePayments = unmatched,
            valid = unique.isNotEmpty(),
            explanation = explanation
        )
    }

    private fun empty(reason: String) = BusinessCustomerImportPreview(
        newCustomers = 0,
        oldCustomers = 0,
        uniqueCustomers = 0,
        paymentCount = 0,
        unknownIdentityPayments = 0,
        unmatchedStorePayments = 0,
        valid = false,
        explanation = reason
    )
}
