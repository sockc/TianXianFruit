"""Read-only overnight review and collector-level sync contracts for V1.4.7.129."""
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[1]
DB=(ROOT/"app/src/main/java/com/tianxian/fruit/data/AppDatabase.kt").read_text()
UI=(ROOT/"app/src/main/java/com/tianxian/fruit/ui/AppUi.kt").read_text()
EDITOR=(ROOT/"app/src/main/java/com/tianxian/fruit/ui/BusinessOvernightReceiptUi.kt").read_text()
SYNC=(ROOT/"app/src/main/java/com/tianxian/fruit/sync/CustomerCloudBridge.kt").read_text()
AUDIT=(ROOT/"app/src/main/java/com/tianxian/fruit/data/OvernightReceiptAuditPolicy.kt").read_text()
POLICY=(ROOT/"app/src/main/java/com/tianxian/fruit/data/OvernightReceiptPolicy.kt").read_text()


class OvernightReconciliation129Test(unittest.TestCase):
    def test_ledger_save_and_settlement_calculations_untouched(self):
        self.assertIn("fun saveStoreDailyRecord(",DB)
        self.assertIn("invalidateProfitDistributionForDate(",DB)
        self.assertIn("invalidateCashSettlementForDate(",DB)
        self.assertIn("fun deleteStoreDailyRecord(",DB)

    def test_only_sidecar_review_flags_change_when_midnight_is_edited(self):
        store=EDITOR.split("internal class OvernightReceiptDraftStore(",1)[1]
        save=store.split("fun saveSnapshot(",1)[1].split("fun saveNatural(",1)[0]
        self.assertIn('putStringSet("needs_review", flags)',save)
        self.assertIn("morning.minusDays(1)",save)
        self.assertIn("morning.toString()",save)
        self.assertNotIn("saveStoreDailyRecord(",save)
        self.assertNotIn("invalidateCashSettlement",save)

    def test_sale_deletion_does_not_erase_shared_morning(self):
        section=EDITOR.split("fun clearForDeletedBusiness(",1)[1].split("fun findSnapshot(",1)[0]
        self.assertIn('putString("natural"',section)
        self.assertIn('putStringSet("needs_review"',section)
        self.assertNotIn('putString("snapshots"',section)
        self.assertIn("OvernightReceiptDeletionPolicy.clearedNatural(",section)

    def test_business_screen_displays_review_without_background_rewrite(self):
        self.assertIn("OvernightReceiptAuditPolicy.inspect(",UI)
        self.assertIn("凌晨收款待核对",UI)
        self.assertIn("查看受影响的营业记录",UI)
        self.assertIn("不会自动变更",UI)
        self.assertIn("if (!collectorOwnsAdjustment(pid))",UI)
        self.assertIn("collectorOwnsAdjustment(pid)) {",UI)
        self.assertIn("acknowledgeReview(date, partnerId)",UI)

    def test_offline_dual_store_conflict_audited_not_silently_modified(self):
        self.assertIn("OvernightReviewReason.SHARED_COLLECTOR",AUDIT)
        self.assertIn("OvernightReviewReason.DELETED_OWNER",AUDIT)
        self.assertIn("OvernightReviewReason.SAVED_TOTAL_DIFFERS",AUDIT)
        self.assertIn("OvernightReviewReason.MORNING_CHANGED",AUDIT)
        self.assertNotIn("db.saveStoreDailyRecord(",AUDIT)
        self.assertNotIn("db.update(",AUDIT)

    def test_collector_record_can_be_registered_without_selected_store(self):
        self.assertIn("snapshot.storeId >= 0 && snapshot.partnerId > 0",POLICY)
        self.assertIn("if(s.storeId>=0 && s.partnerId>0",EDITOR)
        self.assertIn("enabled=partnerId!=null",EDITOR)
        self.assertIn("val transportStore = sql.rawQuery(",SYNC)
        self.assertIn('(kind == "NATURAL" && resolvedStore<=0L)',SYNC)

if __name__=="__main__":
    unittest.main()
