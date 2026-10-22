"""Location history V1.4.7.115 contracts: same-hour evidence, safe period and compact bar UI."""
import unittest
from pathlib import Path
R=Path(__file__).resolve().parents[1]
MODEL=(R/"app/src/main/java/com/tianxian/fruit/data/CustomerLocationHistoryAnalysis.kt").read_text()
DEEP=(R/"app/src/main/java/com/tianxian/fruit/data/CustomerTrafficDeepDive.kt").read_text()
UI=(R/"app/src/main/java/com/tianxian/fruit/ui/CustomerLocationHistoryUi.kt").read_text()
NOTES=(R/"RELEASE_NOTES.md").read_text()
class LocationHistory115Test(unittest.TestCase):
    def test_common_finished_hour_window(self):
        self.assertIn("maxHistoricDays = 7",MODEL)
        self.assertIn("record.tradeTime.drop(11).take(2).toIntOrNull() in hours",MODEL)
        self.assertIn("it.netAmount > 0.005",MODEL)
        self.assertIn("it.storeId == storeId",MODEL)
        self.assertIn("internal fun finishedHours(",DEEP)
        self.assertIn("maxHistoricDays.coerceIn(1, 30)",DEEP)
    def test_groups_and_completeness(self):
        self.assertIn("recent.size == 4 && earlier.size == 4",MODEL)
        self.assertIn("recentDailyAmount",MODEL)
        self.assertIn("biggestChanges",MODEL)
        self.assertIn("共享收款不分摊",MODEL)
        self.assertIn("另一渠道需核验",MODEL)
        self.assertIn("不代表账单完整",MODEL)
    def test_no_area_or_mountain_chart(self):
        self.assertIn("近期4次 vs 此前4次",UI)
        self.assertIn("小时变化排行",UI)
        self.assertIn("最近8个营业日 · 横向短柱",UI)
        self.assertIn("逐小时付款 · 并排双柱图",UI)
        self.assertIn("CustomerLocationHistoryAnalyzer.analyze(",UI)
    def test_release_notes_no_history(self):
        self.assertIn("V1.4.7.115",NOTES)
        self.assertNotIn("V1.4.7.114",NOTES)
        self.assertLess(len(NOTES),3000)
if __name__=="__main__":
    unittest.main()
