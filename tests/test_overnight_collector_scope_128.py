"""V1.4.7.128: collector/date owns midnight money, position owns sales."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
POLICY = (ROOT/"app/src/main/java/com/tianxian/fruit/data/OvernightReceiptPolicy.kt").read_text()
UI = (ROOT/"app/src/main/java/com/tianxian/fruit/ui/AppUi.kt").read_text()
EDITOR = (ROOT/"app/src/main/java/com/tianxian/fruit/ui/BusinessOvernightReceiptUi.kt").read_text()
CLOUD = (ROOT/"app/src/main/java/com/tianxian/fruit/sync/CustomerCloudBridge.kt").read_text()

class CollectorScopedMorning128Test(unittest.TestCase):
    def test_snapshot_identity_never_includes_store(self):
        replacement = POLICY.split("fun replace(",1)[1].split("fun adjustment(",1)[0]
        self.assertIn("it.calendarDate == snapshot.calendarDate",replacement)
        self.assertIn("it.partnerId == snapshot.partnerId",replacement)
        self.assertNotIn("it.storeId == snapshot.storeId",replacement)
        self.assertNotIn("it.storeId != snapshot.storeId",replacement)
        self.assertIn("it.partnerId == draft.partnerId", POLICY)
        following = POLICY.split("val following = saved.filter",1)[1].split(".singleOrNull()",1)[0]
        self.assertNotIn("storeId",following)

    def test_editor_reads_cross_stall_and_deletion_keeps_morning(self):
        self.assertIn("val amount=snapshot?.amount(channel)",EDITOR)
        self.assertIn("remember(tick,date,partnerId,refreshVersion)",EDITOR)
        deletion=EDITOR.split("fun clearForDeletedBusiness(",1)[1].split("fun findSnapshot(",1)[0]
        self.assertIn('putString("natural"',deletion)
        self.assertNotIn('putString("snapshots"',deletion)
        self.assertNotIn("clearedSnapshot(",deletion)

    def test_shared_collector_is_not_blocked_or_double_adjusted(self):
        self.assertIn("collectorOwnsAdjustment(partner)",UI)
        self.assertIn("if (!collectorOwnsAdjustment(partner)) return@map row",UI)
        self.assertIn("if (!collectorOwnsAdjustment(partnerId)) return@forEach",UI)
        self.assertNotIn("val naturalCollision =",UI)
        self.assertNotIn("该收款人这天的自然日总额已归属另一个位置",UI)
        self.assertIn("凌晨只扣减一次",UI)

    def test_cloud_remote_sale_delete_does_not_wipe_collector_morning(self):
        self.assertIn('kind == "NATURAL" &&',CLOUD)
        overnight=CLOUD.split('if(kind=="OVERNIGHT") {',1)[1].split("} else {",1)[0]
        self.assertNotIn("clearedSnapshot(",overnight)
        self.assertIn("storage.findSnapshot(date,resolvedPartner)",overnight)
        reconcile=CLOUD.split("fun reconcileDeletedBusinessOvernight(",1)[1].split("private fun ack(",1)[0]
        self.assertIn("store.naturalDrafts()",reconcile)
        self.assertNotIn("store.snapshots()",reconcile)

if __name__=="__main__":
    unittest.main()
