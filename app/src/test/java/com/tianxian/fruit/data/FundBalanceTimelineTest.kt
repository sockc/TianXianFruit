package com.tianxian.fruit.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class FundBalanceTimelineTest {
    @Test fun recentSevenDaysStillCarryOlderUnsettledLoss() {
        val start = LocalDate.of(2026, 9, 1)
        val ledger = (0L..59L).map { offset ->
            start.plusDays(offset).toString() to
                (if (offset < 30) -100.0 else -50.0)
        }
        val indexed = FundBalanceTimeline.indexedBalances(ledger, false)
        val lastSeven = ledger.takeLast(7).map { indexed[it.first] }
        assertEquals(60, indexed.size)
        assertEquals(7, lastSeven.size)
        assertTrue(lastSeven.all { it == -50.0 })
        assertEquals(-100.0, indexed[start.toString()]!!, 0.001)
    }

    @Test fun completedClearCheckpointIsNotReopenedByOlderPurchases() {
        val full = listOf(
            "2026-10-01" to -150.0,
            "2026-10-02" to -120.0,
            "2026-10-03" to 0.0,
            "2026-10-04" to 30.0
        )
        val index = FundBalanceTimeline.indexedBalances(full, false)
        assertEquals(0.0, index["2026-10-03"]!!, 0.001)
        assertEquals(30.0, index["2026-10-04"]!!, 0.001)
    }

    @Test fun settlementCenterMirrorsAllOtherPartnerBalances() {
        val days = listOf("2026-10-01" to 999.0, "2026-10-02" to 999.0)
        val others = listOf(
            mapOf("2026-10-01" to 70.0, "2026-10-02" to 20.0),
            mapOf("2026-10-01" to -10.0, "2026-10-02" to -45.0),
            mapOf("2026-10-01" to 3.33, "2026-10-02" to 3.33)
        )
        val center = FundBalanceTimeline.indexedBalances(days, true, others)
        assertEquals(-63.33, center["2026-10-01"]!!, 0.001)
        assertEquals(21.67, center["2026-10-02"]!!, 0.001)
    }

    @Test fun largeHistoricalIndexContainsExactlyOneResultPerDay() {
        val start = LocalDate.of(2015, 1, 1)
        val records = (0L until 5000L).map { index ->
            start.plusDays(index).toString() to (index / 100).toDouble()
        }
        val result = FundBalanceTimeline.indexedBalances(records, false)
        assertEquals(5000, result.size)
        assertEquals(49.0, result[start.plusDays(4999).toString()]!!, 0.001)
    }
}
