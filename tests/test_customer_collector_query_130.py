"""SQLite regression for collector assignments on overlapping exported bills.

Check the optimized query against the previous left-join semantics. Tests the
actual SQL extracted from AppDatabase.kt; never changes any production data.
"""
from collections import defaultdict
import sqlite3
import unittest
from pathlib import Path

SOURCE = (Path(__file__).resolve().parents[1] /
          "app/src/main/java/com/tianxian/fruit/data/AppDatabase.kt").read_text("utf-8")
METHOD = SOURCE.split("fun getCustomerCollectorByReceiptId()", 1)[1]
QUERY = METHOD.split('rawQuery("""', 1)[1].split('"""', 1)[0].strip()

LEGACY_QUERY = """
SELECT p.id AS receipt_id, COALESCE(a.partner_sync_id,'') AS partner_sync_id
FROM customer_payment_transaction p
LEFT JOIN customer_payment_source_link l
  ON l.platform=p.platform AND l.dedupe_key=p.dedupe_key
LEFT JOIN payment_import_batch b ON b.id=p.source_batch_id
LEFT JOIN customer_bill_collector_assignment a
  ON a.platform=p.platform AND a.file_hash=COALESCE(l.file_hash,b.file_hash)
"""


class CustomerCollectorQueryTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.db.executescript("""
            CREATE TABLE customer_payment_transaction(
                id INTEGER PRIMARY KEY, platform TEXT, dedupe_key TEXT,
                source_batch_id INTEGER
            );
            CREATE UNIQUE INDEX idx_payment_key
                ON customer_payment_transaction(platform,dedupe_key);
            CREATE TABLE customer_payment_source_link(
                platform TEXT, dedupe_key TEXT, file_hash TEXT,
                PRIMARY KEY(platform,dedupe_key,file_hash)
            );
            CREATE INDEX idx_customer_source_file
                ON customer_payment_source_link(platform,file_hash);
            CREATE TABLE payment_import_batch(
                id INTEGER PRIMARY KEY, file_hash TEXT
            );
            CREATE TABLE customer_bill_collector_assignment(
                platform TEXT, file_hash TEXT, partner_sync_id TEXT,
                PRIMARY KEY(platform,file_hash)
            );
            INSERT INTO payment_import_batch VALUES
                (1,'file-A'),(2,'file-B'),(3,'file-C'),(4,'file-D');
            INSERT INTO customer_payment_transaction VALUES
                (101,'ALIPAY','key-overlap',1),
                (102,'ALIPAY','key-single',1),
                (103,'ALIPAY','key-legacy',3),
                (104,'ALIPAY','key-link-no-assignment',1),
                (105,'ALIPAY','key-anonymous',4),
                (106,'WECHAT','key-other-platform',1);
            INSERT INTO customer_payment_source_link VALUES
                ('ALIPAY','key-overlap','file-A'),
                ('ALIPAY','key-overlap','file-B'),
                ('ALIPAY','key-single','file-A'),
                ('ALIPAY','key-link-no-assignment','file-D'),
                ('WECHAT','key-other-platform','file-A');
            INSERT INTO customer_bill_collector_assignment VALUES
                ('ALIPAY','file-A','partner-A'),
                ('ALIPAY','file-B','partner-B'),
                ('ALIPAY','file-C','partner-C'),
                ('WECHAT','file-A','partner-W');
        """)

    def tearDown(self):
        self.db.close()

    def collectors(self, query):
        found = defaultdict(set)
        for row_id, collector in self.db.execute(query):
            if collector:
                found[row_id].add(collector)
        return dict(found)

    def test_new_query_preserves_legacy_attribution_semantics(self):
        actual = self.collectors(QUERY)
        self.assertEqual(self.collectors(LEGACY_QUERY), actual)
        self.assertEqual({"partner-A", "partner-B"}, actual[101])
        self.assertEqual({"partner-A"}, actual[102])
        self.assertEqual({"partner-C"}, actual[103])
        self.assertEqual({"partner-W"}, actual[106])
        # A linked receipt's own source is authoritative; a differently
        # assigned original batch must not be used as a fallback.
        self.assertNotIn(104, actual)
        self.assertNotIn(105, actual)

    def test_unassigned_batch_does_not_expand_query_result(self):
        self.db.execute("INSERT INTO payment_import_batch VALUES (5,'unassigned')")
        for index in range(1000, 1100):
            self.db.execute(
                "INSERT INTO customer_payment_transaction VALUES (?,?,?,?)",
                (index, "ALIPAY", "extra-%s" % index, 5)
            )
        self.assertEqual(self.collectors(LEGACY_QUERY), self.collectors(QUERY))
        # Unlike the original SQL, only actively assigned rows are returned.
        self.assertLess(len(self.db.execute(QUERY).fetchall()),
                        len(self.db.execute(LEGACY_QUERY).fetchall()))


if __name__ == "__main__":
    unittest.main()
