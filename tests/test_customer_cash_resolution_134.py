"""V134 guard: no automatic resolution of real money/other-switch conflicts."""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BRIDGE = (ROOT / "app/src/main/java/com/tianxian/fruit/sync/CustomerCloudBridge.kt").read_text("utf-8")
UI = (ROOT / "app/src/main/java/com/tianxian/fruit/ui/AppUi.kt").read_text("utf-8")
STORE = (ROOT / "app/src/main/java/com/tianxian/fruit/sync/CustomerCashConflictStore.kt").read_text("utf-8")


class CashOnlyChoiceSafetyTest(unittest.TestCase):
    def test_only_cash_choice_diff_offers_resolution(self):
        cash_only = BRIDGE.split("private fun naturalCashOnlyDifference(", 1)[1].split(
            "fun adoptCloudCashChoice(", 1)[0]
        self.assertIn('local.optBoolean("deduct_cash") == cloud.optBoolean("deduct_cash")', cash_only)
        self.assertIn('if (!local.has("deduct_cash") || !cloud.has("deduct_cash")', cash_only)
        self.assertIn('sameManualBusinessPayload("NATURAL", asCloud, cloud)', cash_only)

    def test_choice_must_be_explicit_and_current_local_snapshot_identical(self):
        adopt = BRIDGE.split("fun adoptCloudCashChoice(", 1)[1].split(
            "private data class ManualReplayResult", 1
        )[0]
        self.assertIn("pending != choice", adopt)
        self.assertIn("pending.localHash", adopt)
        self.assertIn('payloadFingerprint("NATURAL", actual)', adopt)
        self.assertIn("local.copy(deductCash = pending.cloudCash)", adopt)
        self.assertNotIn("saveStoreDailyRecord", adopt)
        self.assertNotIn("daily_cash_settlement", adopt)
        self.assertNotIn("POST", adopt)
        self.assertNotIn("ack(", adopt)

    def test_ui_only_exposes_use_cloud_and_keep_local_without_override(self):
        sync_ui = UI.split("private fun PageSyncStatus(", 1)[1].split(
            "fun resolveConflicts(", 1
        )[0]
        self.assertIn("核对并选择现金扣减设置", sync_ui)
        self.assertIn("采用云端设置", sync_ui)
        self.assertIn("保留本机（不覆盖云端）", sync_ui)
        self.assertIn("CustomerCloudBridge.adoptCloudCashChoice(", sync_ui)
        self.assertIn("syncContext.requestSync()", sync_ui)

    def test_book_scope_and_no_remote_data_in_conflict_cache(self):
        self.assertIn('customer_cash_conflict_${bookId}', STORE)
        self.assertIn('"cloud_revision"', STORE)
        self.assertNotIn("saveStoreDailyRecord", STORE)


if __name__ == "__main__":
    unittest.main()
