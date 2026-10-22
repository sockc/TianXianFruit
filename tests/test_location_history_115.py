"""Store history regression guard: aligned hours, 4-vs-4 and compact bars."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
DATA = (ROOT / "app/src/main/java/com/tianxian/fruit/data/LocationHistoryComparison.kt").read_text(encoding="utf-8")
UI = (ROOT / "app/src/main/java/com/tianxian/fruit/ui/CustomerLocationHistoryUi.kt").read_text(encoding="utf-8")
APP = (ROOT / "app/src/main/java/com/tianxian/fruit/ui/CustomerAnalysisUi.kt").read_text(encoding="utf-8")
RELEASE = (ROOT / "RELEASE_NOTES.md").read_text(encoding="utf-8")

class LocationHistory115Test(unittest.TestCase):
    def test_shared_engine_prevents_full_day_vs_hourly_mismatch(self):
        self.assertIn('val targetSlots = completeSlots(active, now)', DATA)
        self.assertIn('currentAmount = current.sumOf { it.netAmount }', DATA)
        self.assertIn('currentRecords = current', DATA)
        self.assertIn('historicalAmount = if (sufficient)', DATA)
        self.assertIn('val recent = comparable.take(4)', DATA)
        self.assertIn('val earlier = comparable.drop(4).take(4)', DATA)
        self.assertIn('recent.size == 4 && earlier.size == 4', DATA)

    def test_compact_bars_and_coverage(self):
        self.assertIn('LocationHistoryComparisonEngine.inspect(', UI)
        self.assertIn('近期4次 vs 此前4次', UI)
        self.assertIn('小时变化较明显', UI)
        self.assertIn('sharedThisDate.isNotEmpty()', UI)
        self.assertIn('Box(Modifier.weight(1f).height(9.dp)', UI)
        self.assertIn('if(day.payments == 0) "待核验" else label', UI)
        self.assertIn('sharedGroups = collectorAttribution.shared', APP)

    def test_release_notes_only_current_version(self):
        self.assertTrue(RELEASE.startswith('# 天鲜账本 V1.4.7.115'))
        self.assertEqual(1, RELEASE.count('# 天鲜账本 V'))
        self.assertNotIn('# 天鲜账本 V1.4.7.114', RELEASE)

if __name__ == "__main__":
    unittest.main()
