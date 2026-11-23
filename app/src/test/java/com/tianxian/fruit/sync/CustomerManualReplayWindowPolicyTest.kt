package com.tianxian.fruit.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class CustomerManualReplayWindowPolicyTest {
    @Test fun largeHistoryStartsFromRecentTail() {
        assertEquals(
            listOf(55_196L, 49_052L, 41_244L),
            CustomerManualReplayWindowPolicy.startCursors(57_244L)
        )
    }

    @Test fun shortHistoryCanStartAtZero() {
        assertEquals(
            listOf(0L),
            CustomerManualReplayWindowPolicy.startCursors(300L)
        )
    }

    @Test fun cursorWindowsExpandWithoutDuplicates() {
        assertEquals(
            listOf(6_144L, 0L),
            CustomerManualReplayWindowPolicy.startCursors(8_192L)
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectNegativeCursor() {
        CustomerManualReplayWindowPolicy.startCursors(-1L)
    }
}
