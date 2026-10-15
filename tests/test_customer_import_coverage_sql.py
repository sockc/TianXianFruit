"""SQLite regression: original file coverage survives overlapping import deduplication."""
import sqlite3
import unittest
from pathlib import Path

APP_DB = (Path(__file__).resolve().parents[1] /
          "app/src/main/java/com/tianxian/fruit/data/AppDatabase.kt")


QUERY = """
SELECT b.id,
       COALESCE(NULLIF(b.observed_first_date,''),t.first_receipt_date,'') AS first_receipt_date,
       COALESCE(NULLIF(b.observed_last_date,''),t.last_receipt_date,'') AS last_receipt_date,
       CASE WHEN b.observed_first_date<>'' THEN b.observed_receipt_days
            ELSE COALESCE(t.receipt_days,0) END AS receipt_days,
       CASE WHEN b.observed_first_date<>'' THEN b.observed_receipt_rows
            ELSE COALESCE(t.receipt_count,0) END AS receipt_count,
       CASE WHEN b.observed_first_date<>'' THEN 'FILE'
            WHEN t.first_receipt_date IS NOT NULL THEN 'INSERTED_ONLY'
            ELSE 'NONE' END AS coverage_source
FROM payment_import_batch b
LEFT JOIN (
    SELECT source_batch_id,
           MIN(SUBSTR(trade_time,1,10)) AS first_receipt_date,
           MAX(SUBSTR(trade_time,1,10)) AS last_receipt_date,
           COUNT(DISTINCT SUBSTR(trade_time,1,10)) AS receipt_days,
           COUNT(*) AS receipt_count
    FROM customer_payment_transaction
    WHERE amount>0.005
    GROUP BY source_batch_id
) t ON t.source_batch_id=b.id
ORDER BY b.id
"""


class CustomerImportCoverageSqlTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.db.execute("""
            CREATE TABLE payment_import_batch(
                id INTEGER PRIMARY KEY, observed_first_date TEXT DEFAULT '',
                observed_last_date TEXT DEFAULT '',
                observed_receipt_days INTEGER DEFAULT 0,
                observed_receipt_rows INTEGER DEFAULT 0
            )
        """)
        self.db.execute("""
            CREATE TABLE customer_payment_transaction(
                id INTEGER PRIMARY KEY, source_batch_id INTEGER,
                trade_time TEXT NOT NULL, amount REAL NOT NULL
            )
        """)

    def tearDown(self):
        self.db.close()

    def test_legacy_file_only_reports_saved_subset_as_uncertain(self):
        self.db.execute("INSERT INTO payment_import_batch(id) VALUES (1)")
        self.db.execute("""
            INSERT INTO customer_payment_transaction(source_batch_id,trade_time,amount)
            VALUES (1,'2026-10-11 18:00:00',15)
        """)
        value = self.db.execute(QUERY).fetchone()
        self.assertEqual((1, '2026-10-11', '2026-10-11', 1, 1, 'INSERTED_ONLY'), value)

    def test_new_file_keeps_its_full_observed_span_when_all_first_days_duplicate(self):
        # Only new records were saved; previous duplicate receipts were ignored.
        self.db.execute("""
            INSERT INTO payment_import_batch(
                id,observed_first_date,observed_last_date,
                observed_receipt_days,observed_receipt_rows
            ) VALUES (2,'2026-10-08','2026-10-14',5,9)
        """)
        self.db.execute("""
            INSERT INTO customer_payment_transaction(source_batch_id,trade_time,amount)
            VALUES (2,'2026-10-14 18:00:00',15)
        """)
        value = self.db.execute(QUERY).fetchone()
        self.assertEqual((2, '2026-10-08', '2026-10-14', 5, 9, 'FILE'), value)

    def test_refund_is_not_a_sale_coverage_date(self):
        self.db.execute("INSERT INTO payment_import_batch(id) VALUES (3)")
        self.db.execute("""
            INSERT INTO customer_payment_transaction(source_batch_id,trade_time,amount)
            VALUES (3,'2026-10-13 19:00:00',-15)
        """)
        value = self.db.execute(QUERY).fetchone()
        self.assertEqual((3, '', '', 0, 0, 'NONE'), value)

    def test_kotlin_db_query_prioritizes_parsed_file_metadata(self):
        source = APP_DB.read_text(encoding="utf-8")
        self.assertIn("COALESCE(NULLIF(b.observed_first_date,''),t.first_receipt_date,'')", source)
        self.assertIn('put("observed_first_date", receiptStart)', source)
        self.assertIn("const val DB_VERSION = 43", source)


if __name__ == "__main__":
    unittest.main()
