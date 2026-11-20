package com.tianxian.fruit.data

import org.junit.Assert.*
import org.junit.Test

class InventoryOpeningCostEvidencePolicyTest {
    @Test fun positiveOpeningWithoutPriorSnapshotMustNotBeCostedAsFree() {
        assertTrue(InventoryOpeningCostEvidencePolicy.sourceMissing(3.5, null))
        assertTrue(InventoryOpeningCostEvidencePolicy.sourceMissing(1.0, ""))
    }

    @Test fun emptyOpeningMayUseZeroCost() {
        assertFalse(InventoryOpeningCostEvidencePolicy.sourceMissing(0.0, null))
        assertFalse(InventoryOpeningCostEvidencePolicy.sourceMissing(0.0000001, null))
    }

    @Test fun validSourcePermitsHistoricalCostReconstruction() {
        assertFalse(InventoryOpeningCostEvidencePolicy.sourceMissing(3.5, "2026-11-17"))
    }
}
