package com.tianxian.fruit.data

import java.time.LocalDate
import kotlin.math.abs

/**
 * 只读取现有的每日零售价、库存结转及采购；不额外要求水果品质/降价原因等输入。
 * 今日确实有单独保存的零售价格才参与比较；历史沿用价不是“今天重新调价”。
 * 注意零售价格是挂牌价，不代表每笔实际成交价格。
 */
data class RetailPriceSignal(
    val fruitId: Long,
    val fruitName: String,
    val oldPrice: Double,
    val newPrice: Double,
    val priceUnit: String,
    val hasCarryover: Boolean,
    val hasNewPurchase: Boolean
) {
    val priceChangePercent: Double
        get() = if (oldPrice > 0.0) (newPrice / oldPrice - 1.0) * 100.0 else 0.0

    val isReduced: Boolean get() = newPrice < oldPrice
}

object RetailPriceSignalAnalyzer {
    fun analyze(
        date: LocalDate,
        inventory: List<InventoryDayItemRecord>,
        priceAt: (date: String, fruitId: Long, fruitName: String) -> DailyRetailPriceRecord?
    ): List<RetailPriceSignal> {
        val previousDate = date.minusDays(1).toString()
        val today = date.toString()
        return inventory
            .filter { it.fruitId > 0 }
            .groupBy { it.fruitId }
            .mapNotNull { (fruitId, items) ->
                val name = items.first().fruitName
                val current = priceAt(today, fruitId, name) ?: return@mapNotNull null
                // Only explicitly recorded today's price is a reliable change event.
                if (current.inherited || current.pricePerJin <= 0.0) return@mapNotNull null
                val previous = priceAt(previousDate, fruitId, name) ?: return@mapNotNull null
                if (previous.pricePerJin <= 0.0 ||
                    previous.priceUnit != current.priceUnit ||
                    abs(previous.pricePerJin - current.pricePerJin) < 0.005
                ) return@mapNotNull null

                RetailPriceSignal(
                    fruitId = fruitId,
                    fruitName = name,
                    oldPrice = previous.pricePerJin,
                    newPrice = current.pricePerJin,
                    priceUnit = current.priceUnit,
                    hasCarryover = items.any { it.openingQuantity > 0.000001 },
                    hasNewPurchase = items.any { it.purchasedQuantity > 0.000001 }
                )
            }
            .sortedBy { it.priceChangePercent }
    }

    fun fromDatabase(db: AppDatabase, date: LocalDate): List<RetailPriceSignal> {
        val inventory = db.getInventoryDayItems(date.toString())
        return analyze(date, inventory) { day, fruitId, name ->
            db.getDailyRetailPrice(day, fruitId, name)
        }
    }
}
