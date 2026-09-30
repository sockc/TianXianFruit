"""Exercise the application's actual trigger and pending-query SQL with SQLite."""
import json
import re
import sqlite3
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = (ROOT / "app/src/main/java/com/tianxian/fruit/data/AppDatabase.kt").read_text()
POLICIES = (ROOT / "app/src/main/java/com/tianxian/fruit/data/StabilityPolicies.kt").read_text()


def sql_constant(name):
    return re.search(rf'const val {name} = """(.*?)"""', POLICIES, re.S).group(1)


class SyncSqlTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.db.executescript("""
            PRAGMA recursive_triggers=OFF;
            CREATE TABLE sync_context(id INTEGER PRIMARY KEY, remote_apply INTEGER, device_id TEXT);
            INSERT INTO sync_context VALUES(1,0,'device-A');
            CREATE TABLE sync_change_log(id INTEGER PRIMARY KEY, table_name TEXT, record_sync_id TEXT,
                operation TEXT, row_version INTEGER, device_id TEXT, changed_at INTEGER, uploaded INTEGER);
            CREATE TABLE sample(id INTEGER PRIMARY KEY, sync_id TEXT, row_version INTEGER,
                modified_by TEXT, sync_status INTEGER, updated_at INTEGER, deleted INTEGER, value TEXT);
        """)
        triggers = re.findall(r'(CREATE TRIGGER sync_\$\{table\}_(?:ai|au).*?)"""\.trimIndent\(\)', SOURCE, re.S)
        self.assertEqual(2, len(triggers))
        for trigger in triggers:
            self.db.executescript(trigger.replace("${table}", "sample").replace("$table", "sample"))

    def tearDown(self):
        self.db.close()

    def pending(self, table=None):
        where = "q.uploaded=0 AND " + sql_constant("LATEST_PENDING")
        args = []
        if table is not None:
            where += " AND (q.table_name=? AND q.operation=?)"
            args += [table, "UPSERT"]
        return self.db.execute("SELECT q.id,q.table_name,q.record_sync_id,q.row_version "
            "FROM sync_change_log q WHERE " + where + " ORDER BY " +
            sql_constant("ORIGINAL_ORDER") + " LIMIT 100", args).fetchall()

    def insert(self, id_, sync_id):
        self.db.execute("INSERT INTO sample VALUES(?,?,1,'',0,0,0,'initial')", (id_, sync_id))

    def test_repeated_edits_send_one_matching_latest_version(self):
        self.insert(1, "record-A")
        self.db.execute("UPDATE sample SET value='edit-1' WHERE id=1")
        self.db.execute("UPDATE sample SET value='edit-2' WHERE id=1")
        pending = self.pending()
        version, value = self.db.execute("SELECT row_version,value FROM sample WHERE id=1").fetchone()
        self.assertEqual(1, len(pending))
        self.assertEqual(version, pending[0][3])
        self.assertEqual((3, "edit-2"), (version, value))

    def test_acknowledgment_does_not_clear_an_edit_made_during_upload(self):
        self.insert(1, "record-A")
        self.db.execute("UPDATE sample SET value='before-upload' WHERE id=1")
        version, value = self.db.execute("SELECT row_version,value FROM sample WHERE id=1").fetchone()
        sent_ids = [row[0] for row in self.db.execute("SELECT id FROM sync_change_log "
            "WHERE table_name=? AND record_sync_id=? AND uploaded=0 AND row_version<=?",
            ("sample", "record-A", version))]
        self.db.execute("UPDATE sample SET value='during-upload' WHERE id=1")
        self.db.executemany("UPDATE sync_change_log SET uploaded=1 WHERE id=?", [(id_,) for id_ in sent_ids])
        self.assertEqual("before-upload", value)
        self.assertEqual(3, self.pending()[0][3])
        self.assertEqual("during-upload", self.db.execute("SELECT value FROM sample").fetchone()[0])

    def test_conflict_ack_rebases_concurrent_edit_above_server_version(self):
        self.insert(1, "record-A")
        sent_ids = [row[0] for row in self.pending()]
        self.db.execute("UPDATE sample SET value='during-conflict-upload' WHERE id=1")
        self.db.execute("UPDATE sync_context SET remote_apply=1 WHERE id=1")
        self.db.execute("UPDATE sample SET row_version=12 WHERE id=1")
        self.db.executemany("UPDATE sync_change_log SET uploaded=1 WHERE id=?", [(id_,) for id_ in sent_ids])
        rebase_sql = re.search(r'"(UPDATE sync_change_log SET row_version=\?[^"\n]+)"', SOURCE).group(1)
        self.db.execute(rebase_sql, (12, "sample", "record-A"))
        self.assertEqual(12, self.pending()[0][3])
        self.assertEqual("during-conflict-upload", self.db.execute("SELECT value FROM sample").fetchone()[0])

    def test_permission_filter_before_limit_prevents_head_blocking(self):
        self.db.executemany("INSERT INTO sync_change_log VALUES(?,?,?,?,?,?,?,?)", [
            (i, "profit_rule", str(i), "UPSERT", 1, "device-A", 0, 0) for i in range(1, 121)])
        self.db.execute("INSERT INTO sync_change_log VALUES(121,'purchase_order','order-A','UPSERT',1,'device-A',0,0)")
        self.assertEqual("order-A", self.pending("purchase_order")[0][2])

    def test_parent_keeps_original_order_after_later_edits(self):
        self.insert(1, "parent")
        self.insert(2, "child")
        self.db.execute("UPDATE sample SET value='edited-parent' WHERE id=1")
        self.assertEqual(["parent", "child"], [row[2] for row in self.pending()])

    def test_same_second_edits_have_strictly_increasing_times(self):
        self.insert(1, "record-A")
        times = [self.db.execute("SELECT updated_at FROM sample").fetchone()[0]]
        for i in range(3):
            self.db.execute("UPDATE sample SET value=? WHERE id=1", (str(i),))
            times.append(self.db.execute("SELECT updated_at FROM sample").fetchone()[0])
        self.assertTrue(all(a < b for a, b in zip(times, times[1:])))

    def test_remote_apply_does_not_create_an_upload(self):
        self.db.execute("UPDATE sync_context SET remote_apply=1 WHERE id=1")
        self.insert(1, "cloud-record")
        self.assertEqual([], self.pending())

    def test_latest_delete_and_restore_operations_are_selected(self):
        self.insert(1, "record-A")
        self.db.execute("UPDATE sample SET deleted=1 WHERE id=1")
        self.assertEqual("DELETE", self.db.execute(
            "SELECT operation FROM sync_change_log WHERE id=?", (self.pending()[0][0],)).fetchone()[0])
        self.db.execute("UPDATE sample SET deleted=0 WHERE id=1")
        self.assertEqual("UPSERT", self.db.execute(
            "SELECT operation FROM sync_change_log WHERE id=?", (self.pending()[0][0],)).fetchone()[0])


if __name__ == "__main__":
    unittest.main()
