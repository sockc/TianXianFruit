package com.tianxian.fruit.data

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class RetailPriceSignalAnalyzerTest {
    private val date = LocalDate.parse("2026-10-11")

    private fun stock(opening: Double, purchased: Double, fruitId: Long = 1) =
        InventoryDayItemRecord(
            fruitId = fruitId,
            fruitName = "红提",
            unit = "箱",
            openingQuantity = opening,
            purchasedQuantity = purchased,
            lossQuantity = 0.0,
            remainingQuantity = 0.5,
            saved = true
        )

    private fun price(date: String, value: Double, unit: String = "斤", inherited: Boolean = false) =
        DailyRetailPriceRecord(date, 1, "红提", value, inherited, unit)

    @Test fun explicitlyReducedPriceAndCarryoverAreReported() {
        val prices = mapOf(
            "2026-10-10" to price("2026-10-10", 9.0),
            "2026-10-11" to price("2026-10-11", 7.0)
        )
        val result = RetailPriceSignalAnalyzer.analyze(date, listOf(stock(1.0, 2.0))) { day, _, _ ->
            prices[day]
        }
        assertEquals(1, result.size)
        assertTrue(result.single().isReduced)
        assertTrue(result.single().hasCarryover)
        assertTrue(result.single().hasNewPurchase)
        assertEquals(-22.222, result.single().priceChangePercent, 0.01)
    }

    @Test fun inheritedTodayPriceIsNotTreatedAsNewMarkdown() {
        val prices = mapOf(
            "2026-10-10" to price("2026-10-10", 9.0),
            "2026-10-11" to price("2026-10-11", 7.0, inherited = true)
        )
        val result = RetailPriceSignalAnalyzer.analyze(date, listOf(stock(1.0, 0.0))) { day, _, _ ->
            prices[day]
        }
        assertTrue(result.isEmpty())
    }

    @Test fun differentRetailUnitsCannotBeCompared() {
        val prices = mapOf(
            "2026-10-10" to price("2026-10-10", 9.0, "斤"),
            "2026-10-11" to price("2026-10-11", 7.0, "箱")
        )
        val result = RetailPriceSignalAnalyzer.analyze(date, listOf(stock(1.0, 0.0))) { day, _, _ ->
            prices[day]
        }
        assertTrue(result.isEmpty())
    }

    @Test fun unchangedRetailDoesNotInventPriceChange() {
        val prices = mapOf(
            "2026-10-10" to price("2026-10-10", 7.0),
            "2026-10-11" to price("2026-10-11", 7.0)
        )
        val result = RetailPriceSignalAnalyzer.analyze(date, listOf(stock(0.0, 2.0))) { day, _, _ ->
            prices[day]
        }
        assertTrue(result.isEmpty())
    }

    @Test fun duplicateUnitsForOneFruitRemainOnePriceSignal() {
        val prices = mapOf(
            "2026-10-10" to price("2026-10-10", 9.0),
            "2026-10-11" to price("2026-10-11", 7.0)
        )
        val result = RetailPriceSignalAnalyzer.analyze(
            date,
            listOf(stock(1.0, 0.0), stock(0.0, 1.0).copy(unit = "筐"))
        ) { day, _, _ -> prices[day] }
        assertEquals(1, result.size)
        assertTrue(result.single().hasCarryover)
        assertTrue(result.single().hasNewPurchase)
    }
}
