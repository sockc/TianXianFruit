"""UI regression guard for compact customer analytics and remembered purchase buyer."""
import unittest
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
UI=(ROOT/"app/src/main/java/com/tianxian/fruit/ui/CustomerAnalysisUi.kt")
PURCHASE=(ROOT/"app/src/main/java/com/tianxian/fruit/ui/AppUi.kt")

class CustomerUiCompact110Test(unittest.TestCase):
    def test_single_hour_chart_and_no_legacy_official_prompt(self):
        src=UI.read_text(encoding="utf-8")
        overview=src.split("private fun CustomerOverview(",1)[1].split("private fun PaymentSourceCoverageCard(",1)[0]
        self.assertEqual(1,overview.count("HourlyCustomerTrafficCard("))
        self.assertNotIn("CustomerHourlyComparisonCard(",overview)
        self.assertIn("CustomerOverviewPolicies.headline(",overview)
        self.assertIn("showShared = !showShared",overview)
        self.assertNotIn("微信官方新老客请到",src)
        self.assertIn("CustomerOverviewPolicies.coverage(",src)

    def test_purchase_buyer_preference_scoped_to_book(self):
        src=PURCHASE.read_text(encoding="utf-8")
        screen=src.split("private fun PurchaseScreen(",1)[1].split("private fun PurchaseDraftRow",1)[0]
        self.assertIn('lastNewBuyerId',screen)
        self.assertIn('db.ledgerId.hashCode()',screen)
        self.assertIn('purchaseBuyerPrefs.edit().putLong("last_buyer_id"',screen)
        self.assertIn('fun defaultBuyerId(): Long? = preferredBuyer()?.id',screen)

if __name__=="__main__":
    unittest.main()
