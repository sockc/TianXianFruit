package com.tianxian.fruit.data

import org.junit.Assert.*
import org.junit.Test

class InventoryAutoSavePolicyTest {
    @Test fun fractionalInventoryAndLossAreSavedInPurchaseUnits() {
        assertEquals(InventoryAutoSaveValues(0.5, 0.25),
            InventoryAutoSavePolicy.validate("0.5", "0.25", 1.0))
    }

    @Test fun emptyInputMeansZeroButIncompleteDecimalIsNotCommitted() {
        assertEquals(InventoryAutoSaveValues(0.0, 0.0),
            InventoryAutoSavePolicy.validate("", "", 1.0))
        assertNull(InventoryAutoSavePolicy.validate(".", "0", 1.0))
        assertNull(InventoryAutoSavePolicy.validate("1.", "0", 2.0))
    }

    @Test fun rejectsInventoryExceedingAvailableAndInvalidNumbers() {
        assertNull(InventoryAutoSavePolicy.validate("1", "0.01", 1.0))
        assertNull(InventoryAutoSavePolicy.validate("-1", "0", 2.0))
        assertNull(InventoryAutoSavePolicy.validate("NaN", "0", 2.0))
        assertNull(InventoryAutoSavePolicy.validate("0.123", "0", 2.0))
        assertNull(InventoryAutoSavePolicy.validate("0", "0", -1.0))
    }

    @Test fun doesNotOverwriteOtherProductsByDesign() {
        val singleProduct = InventoryAutoSavePolicy.validate("0.5", "0", 3.0)
        assertEquals(0.5, singleProduct!!.remaining, 0.000001)
    }
}
