package com.tianxian.fruit.data

/**
 * Inventory sold-cost estimates cannot assume that stock with positive
 * purchased quantity was free merely because its matching source purchase
 * row is missing. Never alters actual sales/purchases or settlement entries.
 */
internal object InventoryPurchaseCostEvidencePolicy {
    fun isAvailable(
        inventoryPurchasedQuantity: Double,
        matchingPurchaseExists: Boolean,
        matchingPurchaseCostAvailable: Boolean
    ): Boolean {
        if (!inventoryPurchasedQuantity.isFinite() || inventoryPurchasedQuantity < 0.0)
            return false
        return if (matchingPurchaseExists) matchingPurchaseCostAvailable
        else inventoryPurchasedQuantity <= 0.000001
    }
}
