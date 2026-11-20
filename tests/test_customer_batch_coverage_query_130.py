"""Exercise the exact optimized batch-coverage SQL against legacy and modern imports."""
import sqlite3
import unittest
from pathlib import Path

SOURCE = (Path(__file__).resolve().parents[1] /
          "app/src/main/java/com/tianxian/fruit/data/AppDatabase.kt").read_text(encoding="utf-8")
SQL = SOURCE.split("fun getPaymentImportBatches(", 1)[1].split('"""', 2)[1].strip()


class CustomerBatchCoverageQueryTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.db.executescript("""
            CREATE TABLE payment_import_batch (
                id INTEGER PRIMARY KEY,
                imported_at INTEGER NOT NULL,
                observed_first_date TEXT NOT NULL DEFAULT '',
                observed_last_date TEXT NOT NULL DEFAULT '',
                observed_receipt_days INTEGER NOT NULL DEFAULT 0,
                observed_receipt_rows INTEGER NOT NULL DEFAULT 0
            );
            CREATE TABLE customer_payment_transaction (
                id INTEGER PRIMARY KEY,
                source_batch_id INTEGER NOT NULL,
                trade_time TEXT NOT NULL,
                amount REAL NOT NULL
            );
            CREATE INDEX idx_customer_payment_source_batch
                ON customer_payment_transaction(source_batch_id,trade_time);
            INSERT INTO payment_import_batch VALUES
                (1, 300, '2026-11-01','2026-11-05',4,200),
                (2, 200, '','',0,0),
                (3, 100, '','',0,0);
            INSERT INTO customer_payment_transaction VALUES
                (1,2,'2026-10-01 18:00:00',10.0),
                (2,2,'2026-10-01 19:00:00',20.0),
                (3,2,'2026-10-03 20:00:00',30.0),
                (4,2,'2026-10-04 21:00:00',0.0),
                (5,3,'2026-09-30 18:00:00',-5.0);
        """)

    def tearDown(self):
        self.db.close()

    def test_observed_batch_is_authoritative_without_invented_fallback(self):
        self.db.row_factory = sqlite3.Row
        batches = {row["id"]: row for row in self.db.execute(SQL, ("30",))}
        row = batches[1]
        self.assertEqual(("2026-11-01", "2026-11-05", 4, 200, "FILE"),
                         (row["first_receipt_date"], row["last_receipt_date"],
                          row["receipt_days"], row["receipt_count"], row["coverage_source"]))

    def test_legacy_batch_uses_only_positive_receipts(self):
        self.db.row_factory = sqlite3.Row
        batches = {row["id"]: row for row in self.db.execute(SQL, ("30",))}
        row = batches[2]
        self.assertEqual(("2026-10-01", "2026-10-03", 2, 3, "INSERTED_ONLY"),
                         (row["first_receipt_date"], row["last_receipt_date"],
                          row["receipt_days"], row["receipt_count"], row["coverage_source"]))
        missing = batches[3]
        self.assertEqual(("", "", 0, 0, "NONE"),
                         (missing["first_receipt_date"], missing["last_receipt_date"],
                          missing["receipt_days"], missing["receipt_count"], missing["coverage_source"]))

    def test_order_and_indexed_fallback_are_preserved(self):
        self.assertEqual([1, 2], [row[0] for row in self.db.execute(SQL, ("2",))])
        explain = " ".join(str(row) for row in self.db.execute(
            "EXPLAIN QUERY PLAN " + SQL, ("2",)))
        self.assertIn("idx_customer_payment_source_batch", explain)
        self.assertNotIn("GROUP BY source_batch_id", SQL)


if __name__ == "__main__":
    unittest.main()
