"""V1.4.7.131: avoid false manual receipt conflicts; preserve true CAS safety."""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BRIDGE = (ROOT / "app/src/main/java/com/tianxian/fruit/sync/CustomerCloudBridge.kt").read_text("utf-8")
POLICY = (ROOT / "app/src/main/java/com/tianxian/fruit/sync/CustomerManualSyncPolicy.kt").read_text("utf-8")


class CustomerManualConflictRecoveryTest(unittest.TestCase):
    def test_transport_store_is_not_morning_business_identity(self):
        self.assertIn("sameOvernightBusinessValues(local, incoming)", BRIDGE)
        self.assertIn("local.partnerId == cloud.partnerId", POLICY)
        self.assertNotIn("local.storeId == cloud.storeId", POLICY)
        self.assertIn("local.wechat == cloud.wechat", POLICY)
        self.assertIn("local.cashRecorded == cloud.cashRecorded", POLICY)

    def test_conflict_recovery_is_read_only_and_bounded(self):
        block = BRIDGE.split("private fun reconcileIdenticalManualConflict(", 1)[1].split(
            "private fun applyRemote(", 1)[0]
        self.assertIn("for (page in 0 until 100)", block)
        self.assertIn("CustomerSyncCursorPolicy.progresses", block)
        self.assertIn('"include_overnight=true"', block)
        self.assertIn("sameManualBusinessPayload", block)
        self.assertIn("ack(database, item.kind, item.key, item.hash, revision)", block)
        self.assertNotIn("customer_cloud_cursor", block)
        self.assertNotIn("saveSnapshot", block)
        self.assertNotIn("saveNatural", block)

    def test_real_changes_still_fail_closed(self):
        self.assertIn("if (local != null && CustomerManualSyncPolicy.sameOvernightBusinessValues", BRIDGE)
        self.assertIn("if(local!=null && local!=incoming &&", BRIDGE)
        self.assertIn("if (recovered > 0) continue", BRIDGE)
        self.assertIn("客流同步上传失败", BRIDGE)


if __name__ == "__main__":
    unittest.main()
