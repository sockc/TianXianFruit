package com.tianxian.fruit.data

/**
 * The business form's customer total is NOT inferred from imported receipts.
 * The user must explicitly enter both new and returning customer counts,
 * including zero when a category had no customers. Otherwise the total is
 * unknown, not zero and not the count of identifiable payer accounts.
 */
internal object BusinessManualCustomerTotals {
    fun total(newText: String, returningText: String): Int? {
        if (newText.isBlank() || returningText.isBlank()) return null
        val fresh = newText.toIntOrNull() ?: return null
        val old = returningText.toIntOrNull() ?: return null
        if (fresh < 0 || old < 0) return null
        val combined = fresh.toLong() + old.toLong()
        return combined.takeIf { it <= Int.MAX_VALUE }?.toInt()
    }
}
