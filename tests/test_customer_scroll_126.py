"""V1.4.7.126 customer-analysis scroll contracts: one bill list, pinned tabs."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = (ROOT / "app/src/main/java/com/tianxian/fruit/ui/CustomerAnalysisUi.kt").read_text()
POLICY = (ROOT / "app/src/main/java/com/tianxian/fruit/ui/CustomerChromeScrollPolicy.kt").read_text()


class CustomerScroll126(unittest.TestCase):
    def test_header_uses_gesture_threshold_not_every_drag_delta(self):
        self.assertIn("CustomerChromeScrollPolicy(", SRC)
        self.assertIn(".nestedScroll(chromeConnection)", SRC)
        self.assertIn("source != NestedScrollSource.UserInput", SRC)
        self.assertIn("hideThresholdPx", POLICY)
        self.assertIn("showThresholdPx", POLICY)
        self.assertIn("nextDirection != direction", POLICY)
        self.assertIn("chromeExpanded.value = true", SRC)

    def test_tabs_are_always_pinned_and_compact_header_retains_context(self):
        header = SRC.split("Column(\n        Modifier\n            .fillMaxSize()\n            .nestedScroll", 1)[1]
        self.assertIn("AnimatedVisibility(\n            visible = !chromeExpanded.value", header)
        self.assertIn("Text(headerDateLabel", header)
        self.assertIn("Text(selectedStoreName", header)
        self.assertIn("TabRow(", header)
        self.assertIn('Text("筛选 ⌄"', header)

    def test_single_bill_lazy_list_contains_tools_and_cards(self):
        records = SRC.split("private fun CustomerPaymentRecordsContent(", 1)[1]
        above_dialog = records.split("    if (showImportHistory) {", 1)[0]
        self.assertEqual(1, above_dialog.count("LazyColumn("))
        self.assertIn("state = listState", above_dialog)
        for key in ("coverage", "records_toolbar", "records_filters",
                    "large_payment_hint", "empty_receipts"):
            self.assertIn(f'item(key = "{key}")', above_dialog)
        self.assertIn("items(\n                    visible.take(pageSize),", above_dialog)
        self.assertIn("if (visible.size > pageSize)", above_dialog)
        self.assertIn('Text("账单归属 ›")', above_dialog)
        self.assertIn("onStateChange(", above_dialog)

    def test_bill_list_state_survives_header_collapse_and_tab_switch(self):
        self.assertIn("val billListState = rememberLazyListState()", SRC)
        self.assertIn("listState = billListState,", SRC)
        self.assertIn("coverageContent = {", SRC)
        self.assertIn("CustomerImportCoverageStrip(", SRC)
        self.assertIn("chromePolicy.reset()", SRC)


if __name__ == "__main__":
    unittest.main()
