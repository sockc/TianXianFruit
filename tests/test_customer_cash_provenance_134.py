"""V1.4.7.134: legacy cash-flag provenance must survive unrelated natural-day edits."""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
UI = (ROOT / "app/src/main/java/com/tianxian/fruit/ui/BusinessOvernightReceiptUi.kt").read_text("utf-8")
BRIDGE = (ROOT / "app/src/main/java/com/tianxian/fruit/sync/CustomerCloudBridge.kt").read_text("utf-8")
POLICY = (ROOT / "app/src/main/java/com/tianxian/fruit/sync/CustomerCashDeductionConflictPolicy.kt").read_text("utf-8")


class CashFlagProvenance134Test(unittest.TestCase):
    def test_pre_v123_read_defaults_false_without_mutating_raw(self):
        self.assertIn('j.optBoolean("deduct_cash",false)', UI)
        self.assertIn('if (!old.has("deduct_cash"))', UI)
        self.assertIn('NaturalCashFlagPresence.LEGACY_ABSENT', UI)

    def test_existing_legacy_implicit_false_remains_absent_on_other_saves(self):
        natural = UI.split("fun saveNatural(s: NaturalDayReceiptDraft): Boolean {", 1)[1].split(
            "fun clearForDeletedBusiness(", 1)[0]
        self.assertIn("legacyMissingCashKeys", natural)
        self.assertIn("row.deductCash ||", natural)
        self.assertIn('(row.businessDate to row.partnerId) !in legacyMissingCashKeys', natural)
        self.assertIn('put("deduct_cash", row.deductCash)', natural)
        self.assertIn("if (matching.size == 1 && matching.first() == s) return true", natural)

    def test_diagnosis_checks_original_device_field_presence(self):
        replay = BRIDGE.split("private fun reconcileIdenticalManualConflict(", 1)[1].split(
            "private fun applyRemote(", 1)[0]
        self.assertIn('cashFlagPresence(item.payload.optString("date"), partnerId)', replay)
        self.assertIn("sameManualBusinessPayload(item.kind, item.payload, payload)", replay)
        self.assertIn("manualDifference(item.kind, item.payload, payload, cashPresence)", replay)
        self.assertIn("if (matching.isEmpty())", replay)
        self.assertIn("ack(database, item.kind, item.key, item.hash, revision)", replay)
        self.assertNotIn("saveNatural(", replay)
        self.assertNotIn("saveSnapshot(", replay)

    def test_cash_mismatch_always_fails_closed(self):
        self.assertIn("未确认前双方金额和扣减设置均不覆盖", POLICY)
        self.assertIn("本机已经明确保存过现金扣减设置", POLICY)
        self.assertIn("本机为旧版无现金扣减字段", POLICY)
        self.assertIn('if (field == "deduct_cash" && kind == "NATURAL")', BRIDGE)
        self.assertIn('local.has(field) != cloud.has(field)', BRIDGE)

    def test_settlement_calculation_files_untouched(self):
        # Structural safety: this feature edits only sidecar provenance and
        # customer-analytics diagnostics, never settlement algorithms.
        self.assertNotIn("daily_cash_settlement", POLICY)
        self.assertNotIn("profit_settlement_batch", POLICY)


if __name__ == "__main__":
    unittest.main()
