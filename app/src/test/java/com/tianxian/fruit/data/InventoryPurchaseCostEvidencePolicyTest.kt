package com.tianxian.fruit.data

import org.junit.Assert.*
import org.junit.Test

class InventoryPurchaseCostEvidencePolicyTest {
    @Test fun positivePurchasedStockWithoutMatchingSourceMustNotBeFree() {
        assertFalse(InventoryPurchaseCostEvidencePolicy.isAvailable(5.0, false, false))
        assertFalse(InventoryPurchaseCostEvidencePolicy.isAvailable(0.5, false, false))
    }

    @Test fun emptyPurchasesRequireNoCostSource() {
        assertTrue(InventoryPurchaseCostEvidencePolicy.isAvailable(0.0, false, false))
        assertTrue(InventoryPurchaseCostEvidencePolicy.isAvailable(0.0000001, false, false))
    }

    @Test fun matchingSourceMustHaveValidPrice() {
        assertTrue(InventoryPurchaseCostEvidencePolicy.isAvailable(5.0, true, true))
        assertFalse(InventoryPurchaseCostEvidencePolicy.isAvailable(5.0, true, false))
    }

    @Test fun corruptQuantityNeverBecomesKnownCost() {
        assertFalse(InventoryPurchaseCostEvidencePolicy.isAvailable(Double.NaN, true, true))
        assertFalse(InventoryPurchaseCostEvidencePolicy.isAvailable(-1.0, true, true))
    }
}
