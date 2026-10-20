"""New event pull must be capability-gated to avoid breaking legacy servers."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
BRIDGE = ROOT / "app/src/main/java/com/tianxian/fruit/sync/CustomerCloudBridge.kt"
BUILD = ROOT / "app/build.gradle"

class CustomerV22CapabilityContractTest(unittest.TestCase):
    def test_all_new_event_kinds_are_opted_in_only_if_server_supports(self):
        source = BRIDGE.read_text(encoding="utf-8")
        expected = {
            "receipt_analysis_state": ("supportsAnalysis", "include_analysis"),
            "analysis_settings": ("supportsSettings", "include_settings"),
            "bill_source_links": ("supportsSourceLinks", "include_source_links"),
        }
        for feature, (flag, param) in expected.items():
            self.assertIn(f'"{feature}" in supported', source)
            self.assertIn(f'if ({flag}) "&{param}=true" else ""', source)
        self.assertIn('else -> throw IllegalStateException("未知客流同步数据类型：$kind")', source)

    def test_bump_version_without_resetting_db(self):
        build = BUILD.read_text(encoding="utf-8")
        self.assertIn("versionCode 147", build)
        self.assertIn('versionName "1.4.7.109"', build)

if __name__=="__main__":
    unittest.main()
