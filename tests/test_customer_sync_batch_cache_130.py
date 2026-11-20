"""Guard initial large bill imports against repeated batch metadata reconstruction.

The upload loop may reuse one read-only file coverage snapshot; the final
pending check must rebuild it so concurrently imported bills are not marked
as synced prematurely.
"""
import unittest
from pathlib import Path

SRC = (Path(__file__).resolve().parents[1] /
       "app/src/main/java/com/tianxian/fruit/sync/CustomerCloudBridge.kt").read_text("utf-8")


class CustomerSyncBatchCachingTest(unittest.TestCase):
    def test_pending_accepts_optional_snapshot_and_keeps_uncached_fallback(self):
        self.assertIn("importBatchSnapshot: List<PaymentImportBatchRecord>? = null", SRC)
        self.assertIn(
            "importBatchSnapshot ?: db.getPaymentImportBatches(1_000_000)",
            SRC
        )

    def test_upload_reuses_single_snapshot_and_final_check_refreshes(self):
        upload = SRC.split("if (canWrite) {", 1)[1].split("val status =", 1)[0]
        self.assertIn("val importBatchSnapshot = db.getPaymentImportBatches(1_000_000)",
                      upload)
        self.assertIn("supportsOvernight, supportsCashDeduction, importBatchSnapshot)", upload)
        # End-of-run check deliberately omits the cached argument.
        self.assertIn("supportsOvernight,supportsCashDeduction).isNotEmpty()", upload)
        self.assertEqual(
            1, upload.count("db.getPaymentImportBatches(1_000_000)"),
            "One file-coverage query per upload cycle, not per 180-row page"
        )


if __name__ == "__main__":
    unittest.main()
