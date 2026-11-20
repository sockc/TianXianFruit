package com.tianxian.fruit.data

/**
 * Read-only inventory valuation safety: a carried positive quantity with
 * no traceable prior inventory snapshot has unknown opening cost, not zero.
 * Does not participate in formal daily settlement calculations.
 */
internal object InventoryOpeningCostEvidencePolicy {
    fun sourceMissing(openingQuantity: Double, previousSnapshotDate: String?): Boolean =
        openingQuantity.isFinite() && openingQuantity > 0.000001 &&
            previousSnapshotDate.isNullOrBlank()
}
