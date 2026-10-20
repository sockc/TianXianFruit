"""Regression for deduplicated payment receipts and overlapping bill sources."""
import sqlite3
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DB_SOURCE = ROOT / "app/src/main/java/com/tianxian/fruit/data/AppDatabase.kt"
CLOUD_SOURCE = ROOT / "app/src/main/java/com/tianxian/fruit/sync/CustomerCloudBridge.kt"

class CustomerPaymentSourceSqlTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.db.executescript("""
        CREATE TABLE payment_import_batch(id INTEGER PRIMARY KEY,platform TEXT,file_hash TEXT);
        CREATE TABLE customer_payment_transaction(
            id INTEGER PRIMARY KEY,platform TEXT,dedupe_key TEXT UNIQUE,source_batch_id INTEGER);
        CREATE TABLE customer_payment_source_link(
            platform TEXT NOT NULL,dedupe_key TEXT NOT NULL,file_hash TEXT NOT NULL,
            PRIMARY KEY(platform,dedupe_key,file_hash));
        CREATE TABLE customer_bill_collector_assignment(
            platform TEXT,file_hash TEXT,partner_sync_id TEXT);
        """)
        self.db.execute("INSERT INTO payment_import_batch VALUES (1,'WECHAT','file-one')")
        self.db.execute("INSERT INTO payment_import_batch VALUES (2,'WECHAT','file-two')")
        self.db.execute("INSERT INTO customer_payment_transaction VALUES (1,'WECHAT','tx-123',1)")

    def tearDown(self):
        self.db.close()

    def test_duplicate_receipt_can_link_to_two_exports_without_duplicate_customer(self):
        self.db.execute("""INSERT OR IGNORE INTO customer_payment_source_link
            SELECT p.platform,p.dedupe_key,b.file_hash
            FROM customer_payment_transaction p JOIN payment_import_batch b
            ON b.id=p.source_batch_id""")
        self.db.execute("INSERT OR IGNORE INTO customer_payment_source_link VALUES ('WECHAT','tx-123','file-two')")
        self.db.execute("INSERT OR IGNORE INTO customer_payment_source_link VALUES ('WECHAT','tx-123','file-two')")
        self.assertEqual(1,self.db.execute("SELECT COUNT(*) FROM customer_payment_transaction").fetchone()[0])
        self.assertEqual(2,self.db.execute("SELECT COUNT(*) FROM customer_payment_source_link").fetchone()[0])

    def test_collector_conflicts_are_detectable_from_all_associated_files(self):
        self.db.executemany("INSERT INTO customer_payment_source_link VALUES (?,?,?)",[
            ("WECHAT","tx-123","file-one"),("WECHAT","tx-123","file-two")])
        self.db.executemany("INSERT INTO customer_bill_collector_assignment VALUES (?,?,?)",[
            ("WECHAT","file-one","collector-A"),("WECHAT","file-two","collector-B")])
        people=[r[0] for r in self.db.execute("""
            SELECT DISTINCT a.partner_sync_id
            FROM customer_payment_transaction p
            JOIN customer_payment_source_link l
              ON l.platform=p.platform AND l.dedupe_key=p.dedupe_key
            JOIN customer_bill_collector_assignment a
              ON a.platform=l.platform AND a.file_hash=l.file_hash
            WHERE p.id=1
        """)]
        self.assertEqual({"collector-A","collector-B"},set(people))

    def test_android_migration_and_capability_guards_exist(self):
        db=DB_SOURCE.read_text(encoding="utf-8")
        bridge=CLOUD_SOURCE.read_text(encoding="utf-8")
        self.assertIn("createV45CustomerPaymentSourceLinks(db)",db)
        self.assertIn("const val DB_VERSION = 45",db)
        self.assertIn('"customer_payment_source_link"',db)
        for feature in ("receipt_analysis_state","analysis_settings","bill_source_links"):
            self.assertIn(feature,bridge)
        self.assertIn("unsyncedAnalysis",bridge)

if __name__ == "__main__":
    unittest.main()
