"""Guard same-file metadata conflicts from hiding genuine receipt conflicts."""
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[1]
BRIDGE=(ROOT/"app/src/main/java/com/tianxian/fruit/sync/CustomerCloudBridge.kt").read_text(encoding="utf8")
POLICY=(ROOT/"app/src/main/java/com/tianxian/fruit/sync/CustomerBatchConflictPolicy.kt").read_text(encoding="utf8")
MANAGER=(ROOT/"app/src/main/java/com/tianxian/fruit/sync/CloudSyncManager.kt").read_text(encoding="utf8")

class ExistingBatchPolicy113(unittest.TestCase):
    def test_policy_is_limited_to_immutable_file_batch_conflict(self):
        self.assertIn('kind != "BATCH"',POLICY)
        self.assertIn('status != "conflict"',POLICY)
        self.assertIn('revision <= 0L',POLICY)
        self.assertIn('fileHash.length != 64',POLICY)
        self.assertIn('return key == "$platform|$fileHash"',POLICY)
        self.assertIn('setOf("WECHAT", "ALIPAY")',POLICY)

    def test_batch_metadata_alone_is_marked_remote(self):
        self.assertIn('CustomerBatchConflictPolicy.existingFileMetadata(',BRIDGE)
        self.assertIn('ack(database, item.kind, item.key, "REMOTE", revision)',BRIDGE)
        self.assertIn('existingBatchMetadata++',BRIDGE)
        self.assertIn('else -> throw IllegalStateException(',BRIDGE)
        self.assertIn('existingBatchMetadata=${count.existingBatchMetadata}',MANAGER)
        self.assertIn('prev?.hash != "REMOTE"',BRIDGE)

if __name__=="__main__":
    unittest.main()
