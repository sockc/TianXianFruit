package com.tianxian.fruit.ui

import kotlin.math.abs

/**
 * Gesture hysteresis for the customer-analysis chrome. Called only on user
 * vertical scroll, never during layout, programmatic scroll or animation.
 *
 * Returning null means keep the current state. Opposite-direction movement
 * must reach its own threshold before changing anything, avoiding jitter.
 */
internal class CustomerChromeScrollPolicy(
    private val hideThresholdPx: Float,
    private val showThresholdPx: Float
) {
    private var direction = 0
    private var distance = 0f

    init {
        require(hideThresholdPx > 0f && showThresholdPx > 0f)
    }

    fun reset() {
        direction = 0
        distance = 0f
    }

    fun record(deltaYPx: Float, expanded: Boolean): Boolean? {
        if (!deltaYPx.isFinite() || abs(deltaYPx) < 0.5f) return null
        val nextDirection = if (deltaYPx < 0f) -1 else 1
        if (nextDirection != direction) {
            direction = nextDirection
            distance = 0f
        }
        distance += abs(deltaYPx)
        val threshold = if (expanded) hideThresholdPx else showThresholdPx
        val shouldChange = (expanded && nextDirection == -1 ||
            !expanded && nextDirection == 1) && distance >= threshold
        if (!shouldChange) return null
        reset()
        return !expanded
    }
}
