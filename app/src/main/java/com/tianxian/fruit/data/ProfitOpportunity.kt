package com.tianxian.fruit.data

/** Read-only opportunity amounts. Never feed these estimates into settlement. */
data class ProfitOpportunityInput(
    val name: String, val unit: String, val openingQuantity: Double,
    val purchasedQuantity: Double, val openingCost: Double?,
    val purchasedCost: Double?, val weightPerUnitJin: Double?,
    val retailPricePerJin: Double?,
    val retailPriceUnit: String = "斤"
)
data class ProfitOpportunityItem(
    val input: ProfitOpportunityInput,
    val availableGrossProfit: Double?,
    val purchasedGrossProfit: Double?
) {
    val hasAvailable get() = input.openingQuantity + input.purchasedQuantity > 0.000001
    val hasPurchased get() = input.purchasedQuantity > 0.000001
}
data class ProfitOpportunitySummary(val items: List<ProfitOpportunityItem>) {
    val hasAvailableGoods get() = items.any { it.hasAvailable }
    val hasPurchasedGoods get() = items.any { it.hasPurchased }
    val missingAvailable get() = items.count { it.hasAvailable && it.availableGrossProfit == null }
    val missingPurchased get() = items.count { it.hasPurchased && it.purchasedGrossProfit == null }
    val knownAvailableProfit get() = items.sumOf { it.availableGrossProfit ?: 0.0 }
    val knownPurchasedProfit get() = items.sumOf { it.purchasedGrossProfit ?: 0.0 }
}
fun opportunityInput(item: OperatingAnalysisItemRecord): ProfitOpportunityInput =
    ProfitOpportunityInput(item.fruitName, item.unit, item.openingQuantity,
        item.purchasedQuantity, item.openingCost,
        if (item.purchaseCostAvailable) item.purchaseCost else null,
        item.unitWeightJin, item.retailPricePerJin, item.retailPriceUnit)

object ProfitOpportunityCalculator {
    fun calculate(inputs: List<ProfitOpportunityInput>): ProfitOpportunitySummary =
        ProfitOpportunitySummary(inputs.map { row ->
            val opening = row.openingQuantity.coerceAtLeast(0.0)
            val purchased = row.purchasedQuantity.coerceAtLeast(0.0)
            val weight = row.weightPerUnitJin?.takeIf { it.isFinite() && it > 0.0 }
            val retail = row.retailPricePerJin?.takeIf { it.isFinite() && it > 0.0 }
            val retailUnit = row.retailPriceUnit.ifBlank { "斤" }
            val oldCost = if (opening < 0.000001) 0.0 else row.openingCost?.takeIf { it.isFinite() && it >= 0.0 }
            val newCost = if (purchased < 0.000001) 0.0 else row.purchasedCost?.takeIf { it.isFinite() && it >= 0.0 }
            fun gross(qty: Double, cost: Double?): Double? {
                if (qty < 0.000001) return 0.0
                if (cost == null || retail == null) return null
                val sales = when {
                    retailUnit == "斤" -> weight?.let { qty * it * retail }
                    retailUnit == row.unit -> qty * retail
                    else -> null
                } ?: return null
                return sales - cost
            }
            val combined = if (oldCost != null && newCost != null) oldCost + newCost else null
            ProfitOpportunityItem(row, gross(opening + purchased, combined), gross(purchased, newCost))
        })
}
