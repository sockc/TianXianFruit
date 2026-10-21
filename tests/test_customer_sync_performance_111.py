"""Cloud customer sync should never reserialize all previously ACKed receipts per batch."""
from pathlib import Path
import re
import sqlite3
import unittest

S = (Path(__file__).resolve().parents[1] /
     "app/src/main/java/com/tianxian/fruit/sync/CustomerCloudBridge.kt").read_text(encoding="utf8")

def sql_block(from_token, ending):
    index = S.index(from_token)
    start = S.rfind('"""', 0, index)
    end = S.find('"""', index)
    if start < 0 or end < 0:
        raise AssertionError("Missing SQL block: "+from_token)
    sql = S[start + 3:end]
    if ending not in sql:
        raise AssertionError("Incorrect SQL block: "+ending)
    return sql.strip()

class CloudSyncPerformanceTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.db.executescript("""
            CREATE TABLE customer_cloud_ack (
                kind TEXT NOT NULL, entity_key TEXT NOT NULL,
                payload_hash TEXT NOT NULL, remote_revision INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(kind,entity_key));
            CREATE TABLE customer_payment_transaction(
                id INTEGER PRIMARY KEY, platform TEXT, dedupe_key TEXT,
                store_id INTEGER, source_batch_id INTEGER);
            CREATE TABLE store(id INTEGER PRIMARY KEY,sync_id TEXT);
            CREATE TABLE payment_import_batch(id INTEGER PRIMARY KEY,file_hash TEXT);
            CREATE TABLE customer_payment_source_link(
                platform TEXT,dedupe_key TEXT,file_hash TEXT,
                PRIMARY KEY(platform,dedupe_key,file_hash));
        """)

    def test_acked_receipts_and_links_not_returned_on_every_upload_page(self):
        rows=[(i, "WECHAT",f"tx{i:06d}",0,0) for i in range(1,501)]
        self.db.executemany("INSERT INTO customer_payment_transaction VALUES(?,?,?,?,?)",rows)
        self.db.executemany("INSERT INTO customer_cloud_ack VALUES(?,?,?,1)",[
            ("RECEIPT","WECHAT|"+p[2],"remote") for p in rows])
        self.db.executemany("INSERT INTO customer_payment_source_link VALUES(?,?,?)",
            [("WECHAT",p[2],"a"*64) for p in rows])
        self.db.executemany("INSERT INTO customer_cloud_ack VALUES(?,?,?,1)",[
            ("SOURCE_LINK",f"WECHAT|{p[2]}|{'a'*64}","remote") for p in rows])
        receipts=sql_block("SELECT p.*,COALESCE", "ORDER BY p.id")
        links=sql_block("SELECT platform,dedupe_key,file_hash", "ORDER BY l.platform")
        self.assertEqual(0,len(self.db.execute(receipts).fetchall()))
        self.assertEqual(0,len(self.db.execute(links).fetchall()))
        self.db.execute("INSERT INTO customer_payment_transaction VALUES(501,'ALIPAY','new',0,0)")
        self.db.execute("INSERT INTO customer_payment_source_link VALUES('ALIPAY','new',?)",("b"*64,))
        self.assertEqual(["new"],[r[2] for r in self.db.execute(receipts)])
        self.assertEqual([("ALIPAY","new","b"*64)],self.db.execute(links).fetchall())

    def test_acked_immutable_records_omitted_from_map(self):
        self.assertIn("WHERE kind NOT IN ('RECEIPT','SOURCE_LINK')",S)
        self.assertIn("WHERE a.kind='RECEIPT'",S)
        self.assertIn("WHERE a.kind='SOURCE_LINK'",S)
        self.assertIn("customerPushBatches", (Path(__file__).resolve().parents[1]/
             "app/src/main/java/com/tianxian/fruit/sync/CloudSyncManager.kt").read_text(encoding="utf8"))

if __name__=="__main__":
    unittest.main()
