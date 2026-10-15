"""Regression guard: traffic links must live in business history, not today's cards."""
import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
APP_UI = ROOT / "app/src/main/java/com/tianxian/fruit/ui/AppUi.kt"


class BusinessHistoryTrafficShortcutsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = APP_UI.read_text(encoding="utf-8")
        today = cls.source.index('Text("当天营业记录"')
        history = cls.source.index('Text(\n                        "营业历史  ›"', today)
        # "营业历史" heading starts after the selected day's saved records.
        cls.today_section = cls.source[today:history]
        cls.history_section = cls.source[history:cls.source.index('if (showCustomerCountHelp)', history)]

    def test_no_duplicate_shortcuts_in_current_day_record(self):
        self.assertNotIn('"客流分析 ›"', self.today_section)
        self.assertNotIn('"客流 ›"', self.today_section)

    def test_history_date_link_opens_its_own_day_all_stores(self):
        self.assertIn('"客流分析 ›"', self.history_section)
        self.assertIn('onOpenCustomerAnalysis(dayDate, null)', self.history_section)
        self.assertIn('chineseWeekday(LocalDate.parse(dayDate))', self.history_section)

    def test_history_store_link_opens_selected_record_preview(self):
        self.assertIn('"客流 ›"', self.history_section)
        self.assertIn('trafficPreviewRecord = record', self.history_section)
        self.assertIn('if (canViewCustomerAnalysis)', self.history_section)
        self.assertIn('"营业额 ${money(record.revenue)}"', self.history_section)


if __name__ == "__main__":
    unittest.main()
