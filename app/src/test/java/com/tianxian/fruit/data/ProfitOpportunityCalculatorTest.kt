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

    @Test fun retailPerPackageWorksWithoutWeight() {
        val p = ProfitOpportunityCalculator.calculate(listOf(
            ProfitOpportunityInput("红提","箱",2.0,3.0,200.0,330.0,null,160.0,"箱")))
        assertEquals(270.0,p.knownAvailableProfit,0.00001)
        assertEquals(150.0,p.knownPurchasedProfit,0.00001)
        assertEquals(0,p.missingAvailable)
        assertEquals(0,p.missingPurchased)
    }

    @Test fun weightMissingButPerJinPriceDoesNotInventWeight() {
        val p = ProfitOpportunityCalculator.calculate(listOf(
            ProfitOpportunityInput("红提","箱",2.0,3.0,200.0,330.0,null,6.0,"斤")))
        assertEquals(1,p.missingAvailable)
        assertEquals(1,p.missingPurchased)
        assertEquals(0.0,p.knownAvailableProfit,0.00001)
    }

    @Test fun explicitJinPricingUsesWeightWhilePackagePricingUsesUnits() {
        val byJin = ProfitOpportunityCalculator.calculate(listOf(
            ProfitOpportunityInput("桃","筐",1.0,2.0,90.0,200.0,20.0,8.0,"斤")))
        val byBasket = ProfitOpportunityCalculator.calculate(listOf(
            ProfitOpportunityInput("桃","筐",1.0,2.0,90.0,200.0,20.0,170.0,"筐")))
        assertEquals(190.0,byJin.knownAvailableProfit,0.00001)
        assertEquals(120.0,byJin.knownPurchasedProfit,0.00001)
        assertEquals(220.0,byBasket.knownAvailableProfit,0.00001)
        assertEquals(140.0,byBasket.knownPurchasedProfit,0.00001)
    }

    @Test fun differentPackageUnitMustNotApplyOtherPrice() {
        val p = ProfitOpportunityCalculator.calculate(listOf(
            ProfitOpportunityInput("苹果","箱",1.0,2.0,100.0,210.0,null,200.0,"袋")))
        assertEquals(1,p.missingAvailable)
        assertEquals(1,p.missingPurchased)
    }

    @Test fun missingCarryCostKeepsKnownNewPurchaseProfitWithoutWeight() {
        val p = ProfitOpportunityCalculator.calculate(listOf(
            ProfitOpportunityInput("梨","件",2.0,3.0,null,150.0,null,80.0,"件")))
        assertEquals(1,p.missingAvailable)
        assertEquals(0,p.missingPurchased)
        assertEquals(90.0,p.knownPurchasedProfit,0.00001)
    }
}
