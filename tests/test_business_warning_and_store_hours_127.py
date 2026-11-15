"""V1.4.7.127 UI guard: no premature warning; strict save; cross-midnight store defaults."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
UI = (ROOT / "app/src/main/java/com/tianxian/fruit/ui/AppUi.kt").read_text(encoding="utf-8")
HOURS = (ROOT / "app/src/main/java/com/tianxian/fruit/data/StoreBusinessHoursPolicy.kt").read_text(encoding="utf-8")

class BusinessWarningStoreHours127Test(unittest.TestCase):
    def test_unstarted_form_hides_only_visual_overdraw(self):
        self.assertIn("val visibleComputedError = BusinessWarningDisplayPolicy.visibleError(", UI)
        self.assertIn("editingRecordId != null, hasEnteredReceiptAmount", UI)
        self.assertIn("if (visibleComputedError != null)", UI)
        self.assertIn("BusinessWarningDisplayPolicy.OVERNIGHT_OVERDRAW", UI)
        self.assertNotIn("if (computedError!=null)", UI)

    def test_saving_still_validates_overnight_adjustment(self):
        save = UI.split("fun saveBusinessEdits(): Boolean", 1)[1]
        before_database_save = save.split("val result = db.saveStoreDailyRecord(", 1)[0]
        self.assertIn("val (effectiveRows, overnightError) = effectiveReceiptRows()", before_database_save)
        self.assertIn("if (overnightError != null)", before_database_save)
        self.assertIn("return false", before_database_save)

    def test_store_edit_accepts_next_day_and_saves_existing_fields(self):
        self.assertIn("StoreBusinessHoursPolicy.valid(startTime, endTime)", UI)
        self.assertIn("StoreBusinessHoursPolicy.endsNextDay(startTime, endTime)", UI)
        self.assertIn("次日收摊", UI)
        self.assertIn("onSave(name.trim(), address.trim(), lat, lon, startTime, endTime)", UI)
        self.assertIn('endMinute + 1440 - startMinute', HOURS)
        self.assertIn('endMinute - startMinute', HOURS)
        self.assertIn('end == "24:00"', HOURS)

if __name__ == "__main__":
    unittest.main()
