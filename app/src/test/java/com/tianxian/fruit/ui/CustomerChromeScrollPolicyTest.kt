package com.tianxian.fruit.ui

import org.junit.Assert.*
import org.junit.Test

class CustomerChromeScrollPolicyTest {
    @Test fun shortDragsDoNotToggleHeader() {
        val p = CustomerChromeScrollPolicy(72f, 44f)
        assertNull(p.record(-20f, true))
        assertNull(p.record(-20f, true))
        assertNull(p.record(-20f, true))
        assertNull(p.record(9f, true))
        assertNull(p.record(-40f, true))
        assertNull(p.record(-25f, true))
    }

    @Test fun sustainedUpwardDragCollapsesOnlyOnce() {
        val p = CustomerChromeScrollPolicy(72f, 44f)
        assertNull(p.record(-50f, true))
        assertEquals(false, p.record(-23f, true))
        assertNull(p.record(-100f, false))
        assertNull(p.record(-100f, false))
    }

    @Test fun downwardDragRestoresAndOppositeDirectionResets() {
        val p = CustomerChromeScrollPolicy(72f, 44f)
        assertEquals(false, p.record(-90f, true))
        assertNull(p.record(25f, false))
        assertNull(p.record(-10f, false))
        assertNull(p.record(30f, false))
        assertEquals(true, p.record(20f, false))
    }

    @Test fun tinyJitterAndInvalidDeltasDoNotToggle() {
        val p = CustomerChromeScrollPolicy(72f, 44f)
        assertNull(p.record(0.1f, false))
        assertNull(p.record(Float.NaN, false))
        assertNull(p.record(Float.POSITIVE_INFINITY, false))
        assertNull(p.record(-0.1f, true))
    }

    @Test fun resetClearsOldGestureDistance() {
        val p = CustomerChromeScrollPolicy(72f, 44f)
        assertNull(p.record(-65f, true))
        p.reset()
        assertNull(p.record(-20f, true))
        assertNull(p.record(-45f, true))
        assertEquals(false, p.record(-10f, true))
    }
}
