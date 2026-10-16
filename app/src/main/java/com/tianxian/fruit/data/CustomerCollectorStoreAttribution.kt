package com.tianxian.fruit.data

import java.time.LocalDateTime

/**
 * A cashier/account can receive payments for multiple stores.
 * A shared receipt belongs to one collector/day group, NEVER to each store separately.
 * All changes here are analytical projections; imported bills and official sales stay intact.
 */
internal data class SharedCollectorTraffic(
    val date: String,
    val platform: String,
    val partnerId: Long,
    val partnerName: String,
    val stores: List<Pair<Long, String>>,
    val receiptIds: Set<Long>,
    val payments: Int,
    val amount: Double
) {
    val storeIds: Set<Long> get() = stores.map { it.first }.toSet()
    val storeNames: String get() = stores.joinToString("、") { it.second }
}

internal data class CollectorStoreAttributionResult(
    val rows: List<CustomerPaymentRecord>,
    val shared: List<SharedCollectorTraffic>,
    /** Prevent legacy whole-day manual rules from assigning recognized collector receipts. */
    val collectorMatchedIds: Set<Long>
)

internal object CustomerCollectorStoreAttribution {
    private data class Key(val date: String, val platform: String, val partnerId: Long)
    private data class Match(
        val key: Key,
        val records: List<StoreDailyRecord>,
        val receipt: CustomerPaymentRecord
    )

    fun resolve(
        receipts: List<CustomerPaymentRecord>,
        daily: List<StoreDailyRecord>,
        collectorByBatchId: Map<Long, String>,
        partnerIdBySyncId: Map<String, Long>
    ): CollectorStoreAttributionResult {
        if (receipts.isEmpty() || daily.isEmpty() || collectorByBatchId.isEmpty()) {
            return CollectorStoreAttributionResult(receipts, emptyList(), emptySet())
        }

        // Match by DATE + receiving partner + payment channel. A collector name
        // is not a stable identifier and must never be used as a join key.
        val storesByKey = HashMap<Key, MutableList<StoreDailyRecord>>()
        daily.filter { it.storeId > 0L }.forEach { record ->
            record.receiptSplits.forEach { split ->
                if (split.partnerId <= 0L) return@forEach
                if (split.wechatIncome > 0.005) {
                    storesByKey.getOrPut(Key(record.date, "WECHAT", split.partnerId)) {
                        mutableListOf()
                    }.add(record)
                }
                if (split.alipayIncome > 0.005) {
                    storesByKey.getOrPut(Key(record.date, "ALIPAY", split.partnerId)) {
                        mutableListOf()
                    }.add(record)
                }
            }
        }
        val sharedMatches = linkedMapOf<Key, MutableList<Match>>()
        val matchedIds = mutableSetOf<Long>()
        val attributed = receipts.map { receipt ->
            if (receipt.netAmount <= 0.005 || receipt.amount <= 0.005) {
                return@map receipt
            }
            val collectorSyncId = collectorByBatchId[receipt.sourceBatchId].orEmpty()
            val partnerId = partnerIdBySyncId[collectorSyncId] ?: return@map receipt
            if (partnerId <= 0L) return@map receipt

            val tradeDay = receipt.tradeTime.take(10)
            val originalKey = Key(receipt.businessDate, receipt.platform, partnerId)
            // An overnight payment may have the calendar date of the next day.
            // Prefer a previous shift only if its actual selling window contains
            // the timestamp; otherwise use the persisted business date.
            val previousDay = runCatching {
                java.time.LocalDate.parse(tradeDay).minusDays(1).toString()
            }.getOrNull()
            val previousKey = previousDay?.let { Key(it, receipt.platform, partnerId) }
            val previous = previousKey?.let(storesByKey::get).orEmpty()
            val inPreviousShift = previous.any { saved ->
                CustomerTrafficDeepDive.matchSession(
                    receipt.tradeTime,
                    listOf(CustomerTrafficSession(saved.date, saved.storeId,
                        saved.storeName, saved.actualStartTime.ifBlank { "16:00" },
                        saved.actualEndTime.ifBlank { "24:00" }))
                ) != null
            }
            val key = if (inPreviousShift && previousKey != null) previousKey else originalKey
            val candidates = storesByKey[key].orEmpty().distinctBy { it.storeId }
            if (candidates.isEmpty()) return@map receipt

            matchedIds.add(receipt.id)
            if (candidates.size == 1) {
                val store = candidates.single()
                receipt.copy(businessDate = key.date, storeId = store.storeId,
                    storeName = store.storeName)
            } else {
                sharedMatches.getOrPut(key) { mutableListOf() }
                    .add(Match(key, candidates, receipt))
                // Do not double-count this payment in several location totals.
                receipt.copy(businessDate = key.date, storeId = 0L,
                    storeName = "多位置共享收款")
            }
        }

        val groups = sharedMatches.map { (key, matches) ->
            val first = matches.first().records
            val people = first.asSequence().flatMap { saved ->
                saved.receiptSplits.asSequence()
                    .filter { it.partnerId == key.partnerId }
                    .map { it.partnerName }
            }.firstOrNull { it.isNotBlank() } ?: "收款人"
            val distinct = matches.map { it.receipt }.distinctBy { it.id }
            SharedCollectorTraffic(
                date = key.date, platform = key.platform,
                partnerId = key.partnerId, partnerName = people,
                stores = first.map { it.storeId to it.storeName },
                receiptIds = distinct.map { it.id }.toSet(),
                payments = distinct.size,
                amount = distinct.sumOf { it.netAmount }
            )
        }.sortedWith(compareByDescending<SharedCollectorTraffic> { it.date }
            .thenBy { it.partnerName }.thenBy { it.platform })

        return CollectorStoreAttributionResult(attributed, groups, matchedIds)
    }
}
