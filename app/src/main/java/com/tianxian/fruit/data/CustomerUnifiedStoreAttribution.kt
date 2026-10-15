package com.tianxian.fruit.data

/**
 * A user-declared analytical grouping, not a verified payment/store attribution.
 * An explicit saved store is never overwritten. Undo is represented by an empty
 * sync ID, so a second device can receive the removal.
 */
internal object CustomerUnifiedStoreAttribution {
    fun apply(
        rows: List<CustomerPaymentRecord>,
        assignments: Map<String, String>,
        stores: List<StoreOption>
    ): List<CustomerPaymentRecord> {
        if (assignments.isEmpty()) return rows
        val bySyncId = stores.filter { it.syncId.isNotBlank() }.associateBy { it.syncId }
        return rows.map { row ->
            if (row.storeId > 0L || row.amount <= 0.005) row
            else {
                val store = assignments[row.businessDate]?.let(bySyncId::get)
                if (store == null) row else row.copy(storeId = store.id, storeName = store.name)
            }
        }
    }

    fun unmatchedOnDate(rows: List<CustomerPaymentRecord>, date: String): Int =
        rows.count { it.storeId <= 0L && it.businessDate == date && it.amount > 0.005 }
}
