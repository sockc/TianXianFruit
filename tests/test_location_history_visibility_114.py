"""Regression: location analysis lives in another Kotlin file, so shared enums cannot be file-private."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
BASE = (ROOT / "app/src/main/java/com/tianxian/fruit/ui/CustomerAnalysisUi.kt").read_text(encoding="utf-8")
LOCATION = (ROOT / "app/src/main/java/com/tianxian/fruit/ui/CustomerLocationHistoryUi.kt").read_text(encoding="utf-8")

class LocationHistoryVisibilityTest(unittest.TestCase):
    def test_shared_enums_are_module_visible(self):
        for name in ("CustomerPaymentPlatform", "CustomerDailyMetric"):
            self.assertIn(f"internal enum class {name}(", BASE)
            self.assertNotIn(f"private enum class {name}(", BASE)
            self.assertIn(name, LOCATION)

    def test_location_tab_uses_side_by_side_columns(self):
        self.assertIn('LOCATION("位置")', BASE)
        self.assertIn('CustomerLocationHistoryContent(', BASE)
        self.assertIn('逐小时收款 · 双柱对照', LOCATION)
        self.assertIn('历史同小时均值', LOCATION)

if __name__ == "__main__":
    unittest.main()
