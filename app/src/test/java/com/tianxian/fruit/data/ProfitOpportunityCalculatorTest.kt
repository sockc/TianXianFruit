package com.tianxian.fruit.data
import org.junit.Assert.*
import org.junit.Test
class ProfitOpportunityCalculatorTest {
    @Test fun carryPlusPurchaseVsNewPurchase() {
        val p = ProfitOpportunityCalculator.calculate(listOf(
            ProfitOpportunityInput("红提","箱",2.0,3.0,200.0,330.0,30.0,6.0)))
        assertEquals(370.0,p.knownAvailableProfit,0.00001)
        assertEquals(210.0,p.knownPurchasedProfit,0.00001)
        assertEquals(0,p.missingAvailable)
    }
    @Test fun missingCarryCostDoesNotEraseKnownNewBatch() {
        val p = ProfitOpportunityCalculator.calculate(listOf(
            ProfitOpportunityInput("红提","箱",1.0,2.0,null,220.0,20.0,8.0)))
        assertEquals(1,p.missingAvailable)
        assertEquals(0,p.missingPurchased)
        assertEquals(100.0,p.knownPurchasedProfit,0.00001)
    }
    @Test fun missingPriceOrWeightRemainsUnknown() {
        val p = ProfitOpportunityCalculator.calculate(listOf(
            ProfitOpportunityInput("梨","件",1.0,2.0,50.0,90.0,null,6.0),
            ProfitOpportunityInput("桃","件",1.0,1.0,50.0,90.0,15.0,null)))
        assertEquals(2,p.missingAvailable)
        assertEquals(2,p.missingPurchased)
    }
}
