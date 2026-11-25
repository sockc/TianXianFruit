"""Master identity compatibility pilot: existing rows stay unchanged."""
import sqlite3
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MIN = 1 << 48
SERVER = 1 << 50

class MasterIdentityPilotTest(unittest.TestCase):
    def make_db(self):
        db = sqlite3.connect(":memory:")
        db.execute("CREATE TABLE fruit(id INTEGER PRIMARY KEY AUTOINCREMENT, sync_id TEXT UNIQUE, name TEXT UNIQUE)")
        db.execute("INSERT INTO fruit(id,sync_id,name) VALUES (1,'legacy','总价')")
        db.execute("INSERT INTO fruit(id,sync_id,name) VALUES (?, 'ios', '红提')", (SERVER + 19,))
        db.execute("CREATE TABLE purchase_item(fruit_id INTEGER NOT NULL)")
        db.execute("INSERT INTO purchase_item VALUES (1)")
        return db

    def test_explicit_ids_do_not_follow_sqlite_sequence(self):
        a, b = self.make_db(), self.make_db()
        try:
            a.execute("INSERT INTO fruit VALUES (?, 'a', '苹果')", (MIN+11,))
            b.execute("INSERT INTO fruit VALUES (?, 'b', '香蕉')", (MIN+12,))
            b.execute("INSERT INTO fruit VALUES (?, 'a', '苹果')", (MIN+11,))
            a.execute("INSERT INTO fruit VALUES (?, 'b', '香蕉')", (MIN+12,))
            self.assertEqual(a.execute("SELECT * FROM fruit ORDER BY id").fetchall(),
                             b.execute("SELECT * FROM fruit ORDER BY id").fetchall())
            self.assertEqual([(1,)], a.execute("SELECT fruit_id FROM purchase_item").fetchall())
            self.assertEqual(SERVER+19, a.execute("SELECT seq FROM sqlite_sequence WHERE name='fruit'").fetchone()[0])
            with self.assertRaises(sqlite3.IntegrityError):
                a.execute("INSERT INTO fruit VALUES (?, 'different', '橙子')", (MIN+11,))
        finally:
            a.close(); b.close()

    def test_legacy_devices_still_need_upgrade(self):
        a, b = self.make_db(), self.make_db()
        try:
            first = a.execute("INSERT INTO fruit(sync_id,name) VALUES ('a','苹果')").lastrowid
            second = b.execute("INSERT INTO fruit(sync_id,name) VALUES ('b','香蕉')").lastrowid
            self.assertEqual(first, second)
        finally:
            a.close(); b.close()

    def test_database_source_preserves_formal_path_and_existing_identity(self):
        s = (ROOT / "app/src/main/java/com/tianxian/fruit/data/AppDatabase.kt").read_text()
        self.assertIn("if (!MasterRecordIdentity.pilotEnabled(ledgerName)) return insert()", s)
        self.assertIn('SELECT 1 FROM $table WHERE id=? OR sync_id=? LIMIT 1', s)
        self.assertIn('return insertNewMasterRow(writableDatabase, "fruit"', s)
        self.assertIn('return insertNewMasterRow(writableDatabase, "partner"', s)
        self.assertIn('return insertNewMasterRow(\n            writableDatabase,\n            "store"', s)
        self.assertIn('if (existing != null)', s)

if __name__ == "__main__":
    unittest.main()
