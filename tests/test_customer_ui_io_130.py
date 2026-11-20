"""Keep expensive customer import coverage queries off Compose's UI thread."""
import unittest
from pathlib import Path

UI = (Path(__file__).resolve().parents[1] /
      "app/src/main/java/com/tianxian/fruit/ui/CustomerAnalysisUi.kt").read_text(encoding="utf-8")


class CustomerUiIORegression(unittest.TestCase):
    def test_batch_aggregation_runs_in_io_coroutine(self):
        self.assertIn("val importBatches by produceState<List<PaymentImportBatchRecord>>", UI)
        self.assertIn("withContext(Dispatchers.IO) { db.getPaymentImportBatches(200) }", UI)
        self.assertNotIn("val importBatches = remember(dataVersion, showSettings, tab)", UI)

    def test_single_day_reconciliation_reuses_io_loaded_business_history(self):
        self.assertIn("dailyHistory.filter { it.date == dayAnchor.toString() }", UI)
        self.assertNotIn(
            "db.getDailyRecordsBetween(dayAnchor.toString(), dayAnchor.toString())",
            UI
        )

    def test_same_weekday_card_is_collapsible_and_conservative(self):
        self.assertIn("SameWeekdayCustomerCard(sameWeekdayComparisons)", UI)
        self.assertIn("row.sampleDays < 3", UI)
        self.assertIn("资料不足", UI)
        self.assertIn("showSameWeekday", UI)

    def test_customer_snapshot_stays_on_background_dispatcher(self):
        self.assertIn("value = withContext(Dispatchers.IO)", UI)
        self.assertIn("db.getCustomerPayments(), sessions", UI)


if __name__ == "__main__":
    unittest.main()
