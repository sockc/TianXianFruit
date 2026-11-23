"""V1.4.7.132: silent metadata rotation must not create a manual edit."""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BRIDGE = (ROOT / "app/src/main/java/com/tianxian/fruit/sync/CustomerCloudBridge.kt").read_text("utf-8")
POLICY = (ROOT / "app/src/main/java/com/tianxian/fruit/sync/CustomerManualSyncPolicy.kt").read_text("utf-8")


class CollectorManualAckFingerprintTests(unittest.TestCase):
    def test_pending_and_cloud_ack_use_same_business_hash(self):
        self.assertIn("private fun payloadFingerprint(kind: String, payload: JSONObject)", BRIDGE)
        self.assertIn("SyncItem(kind, key, payload, payloadFingerprint(kind, payload)", BRIDGE)
        self.assertIn('payloadFingerprint("OVERNIGHT", overnightPayload(it,', BRIDGE)
        self.assertIn('payloadFingerprint("OVERNIGHT", overnightPayload(', BRIDGE)
        self.assertIn('kind == "OVERNIGHT"', BRIDGE)
        self.assertIn("overnightBusinessFingerprint(", BRIDGE)
        self.assertNotIn("storeId", POLICY.split("fun overnightBusinessFingerprint(", 1)[1])

    def test_conflict_diagnostic_is_read_only(self):
        replay = BRIDGE.split("private fun reconcileIdenticalManualConflict(", 1)[1].split(
            "private fun applyRemote(", 1)[0]
        self.assertIn("CustomerSyncCursorPolicy.progresses", replay)
        self.assertIn("if (!complete)", replay)
        self.assertIn("manualDifference(", replay)
        self.assertIn("targetKeys: Set<String>", replay)
        self.assertIn("ack(database, item.kind, item.key, item.hash, revision)", replay)
        self.assertNotIn("customer_cloud_cursor", replay)
        self.assertNotIn("saveNatural", replay)
        self.assertNotIn("saveSnapshot", replay)

    def test_real_money_or_provenance_differences_still_block_auto_ack(self):
        self.assertIn('local.optString("store_sync_id") != remote.optString("store_sync_id")', BRIDGE)
        self.assertIn('listOf("saved_wechat", "saved_alipay", "saved_cash")', BRIDGE)
        self.assertIn("if (!sameManualBusinessPayload", BRIDGE)
        self.assertIn("if (report.recovered > 0) continue", BRIDGE)


if __name__ == "__main__":
    unittest.main()
