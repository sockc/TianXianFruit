package com.tianxian.fruit.data

/**
 * Checks which declared receiving accounts/channels have OBSERVED bill rows.
 * No amount discrepancy is classified as missing revenue without complete
 * export-period evidence. A shared account is never split among stores.
 */
internal data class BusinessBillChannelRow(
    val partnerId: Long,
    val collectorName: String,
    val platform: String,
    val storeNames: List<String>,
    val businessAmount: Double,
    val importedAmount: Double,
    val importedPayments: Int
) {
    val observed: Boolean get() = importedPayments > 0
    val shared: Boolean get() = storeNames.size > 1
}

internal data class BusinessBillCoverage(
    val rows: List<BusinessBillChannelRow>,
    val importedUnassigned: Int,
    val importedUnassignedAmount: Double,
    val importedOutsideBusiness: Int
) {
    val pendingChannels: Int get() = rows.count { !it.observed }
    val sharedChannels: Int get() = rows.count { it.shared }
    val observedChannels: Int get() = rows.count { it.observed }
    val status: String get() = when {
        rows.isEmpty() -> "收款人待核对"
        pendingChannels > 0 -> "待补齐${pendingChannels}项"
        importedUnassigned > 0 -> "有未指定收款人"
        else -> "账单覆盖待核验"
    }
}

internal object BusinessBillCoverageEngine {
    fun review(
        date: String,
        savedRecords: List<StoreDailyRecord>,
        payments: List<CustomerPaymentRecord>,
        byBatch: Map<Long, String>,
        partnerBySyncId: Map<String, Long>
    ): BusinessBillCoverage {
        data class Key(val partnerId: Long, val platform: String)
        data class Actual(val name: String, val stores: MutableMap<Long, String> = linkedMapOf(),
            var amount: Double = 0.0)
        val expected = linkedMapOf<Key, Actual>()
        savedRecords.filter { it.date == date && it.storeId > 0L }.forEach { record ->
            record.receiptSplits.forEach { split ->
                if (split.partnerId <= 0) return@forEach
                listOf("WECHAT" to split.wechatIncome, "ALIPAY" to split.alipayIncome)
                    .filter { it.second > 0.005 }
                    .forEach { (platform, money) ->
                        val key = Key(split.partnerId, platform)
                        val group = expected.getOrPut(key) { Actual(split.partnerName) }
                        group.stores[record.storeId] = record.storeName
                        group.amount += money
                    }
            }
        }
        val observed = payments.filter {
            it.businessDate == date && it.amount > 0.005 && it.netAmount > 0.005 &&
                it.platform in setOf("WECHAT", "ALIPAY")
        }.groupBy { payment ->
            val id = byBatch[payment.sourceBatchId].orEmpty()
            Key(partnerBySyncId[id] ?: 0L, payment.platform)
        }
        val covered = expected.map { (key, group) ->
            val receipts = observed[key].orEmpty()
            BusinessBillChannelRow(
                partnerId = key.partnerId, collectorName = group.name,
                platform = key.platform, storeNames = group.stores.values.toList(),
                businessAmount = group.amount,
                importedAmount = receipts.sumOf { it.netAmount },
                importedPayments = receipts.size
            )
        }.sortedWith(compareBy({ it.collectorName }, { it.platform }))
        val unknown = observed.filterKeys { it.partnerId == 0L }.values.flatten()
        val outside = observed.filterKeys { it.partnerId > 0L && it !in expected }.values.flatten()
        return BusinessBillCoverage(
            rows = covered,
            importedUnassigned = unknown.size,
            importedUnassignedAmount = unknown.sumOf { it.netAmount },
            importedOutsideBusiness = outside.size
        )
    }
}
