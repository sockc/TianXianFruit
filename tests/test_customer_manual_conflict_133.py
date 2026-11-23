"""V1.4.7.133: replay recent cloud tail, never assert stale ACK; avoid duplicate natural saves."""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BRIDGE = (ROOT / "app/src/main/java/com/tianxian/fruit/sync/CustomerCloudBridge.kt").read_text("utf-8")
POLICY = (ROOT / "app/src/main/java/com/tianxian/fruit/sync/CustomerManualReplayWindowPolicy.kt").read_text("utf-8")
STORE = (ROOT / "app/src/main/java/com/tianxian/fruit/ui/BusinessOvernightReceiptUi.kt").read_text("utf-8")


class ManualConflict133Test(unittest.TestCase):
    def test_bounded_tail_replay_from_current_incremental_cursor(self):
        replay = BRIDGE.split("private fun reconcileIdenticalManualConflict(", 1)[1].split(
            "private fun applyRemote(", 1
        )[0]
        self.assertIn("val headCursor = serverCursor(database)", replay)
        self.assertIn("CustomerManualReplayWindowPolicy.startCursors(headCursor)", replay)
        self.assertNotIn("var cursor = 0L", replay)
        self.assertIn("for (page in 0 until 100)", replay)
        self.assertIn("CustomerSyncCursorPolicy.progresses", replay)
        self.assertIn('"include_overnight=true"', replay)

    def test_no_unsafe_ack_if_latest_event_not_verified(self):
        replay = BRIDGE.split("private fun reconcileIdenticalManualConflict(", 1)[1].split(
            "private fun applyRemote(", 1
        )[0]
        self.assertIn("if (!complete) return", replay)
        self.assertIn("if (latest.isEmpty()) return", replay)
        self.assertIn("if (!sameManualBusinessPayload", replay)
        self.assertIn("ack(database, item.kind, item.key, item.hash, revision)", replay)
        self.assertNotIn("saveNatural", replay)
        self.assertNotIn("saveSnapshot", replay)
        self.assertNotIn("customer_cloud_cursor", replay)

    def test_window_is_bounded_and_begins_near_recent_head(self):
        self.assertIn("2_048L, 8_192L, 16_000L", POLICY)
        self.assertIn("(currentCursor - it).coerceAtLeast(0L)", POLICY)
        self.assertIn(".distinct()", POLICY)

    def test_identical_natural_draft_skips_storage_write(self):
        block = STORE.split("fun saveNatural(s: NaturalDayReceiptDraft): Boolean {", 1)[1].split(
            "fun clearForDeletedBusiness(", 1
        )[0]
        self.assertIn("if (matching.size == 1 && matching.first() == s) return true", block)
        self.assertLess(block.index("matching.first() == s"), block.index('putString("natural"'))


if __name__ == "__main__":
    unittest.main()
