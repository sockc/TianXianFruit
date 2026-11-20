package com.tianxian.fruit.sync

import org.junit.Assert.*
import org.junit.Test

class CustomerSyncCursorPolicyTest {
    @Test fun emptyFinalPageMayKeepItsCursor() {
        assertTrue(CustomerSyncCursorPolicy.progresses(42, 42, 0, false))
    }

    @Test fun repeatedCursorWithMorePagesFailsFast() {
        assertFalse(CustomerSyncCursorPolicy.progresses(42, 42, 0, true))
        assertFalse(CustomerSyncCursorPolicy.progresses(42, 42, 200, true))
        assertFalse(CustomerSyncCursorPolicy.progresses(42, 42, 1, false))
    }

    @Test fun backwardOrNegativeCursorFails() {
        assertFalse(CustomerSyncCursorPolicy.progresses(42, 41, 1, true))
        assertFalse(CustomerSyncCursorPolicy.progresses(-1, 0, 0, false))
        assertFalse(CustomerSyncCursorPolicy.progresses(0, -1, 0, false))
        assertFalse(CustomerSyncCursorPolicy.progresses(0, 1, -1, false))
    }

    @Test fun validPartialAndFullPageAdvances() {
        assertTrue(CustomerSyncCursorPolicy.progresses(0, 100, 100, true))
        assertTrue(CustomerSyncCursorPolicy.progresses(100, 101, 1, false))
    }
}
