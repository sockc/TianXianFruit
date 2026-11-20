"""Fail CI when the Android build version and published documentation diverge.

The branch under development must keep the last *released* version in
RELEASE_NOTES.md until the actual signed release is ready.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


class VersionDocsConsistencyTest(unittest.TestCase):
    def test_readme_matches_android_build(self):
        gradle = (ROOT / "app/build.gradle").read_text(encoding="utf-8")
        readme = (ROOT / "README.md").read_text(encoding="utf-8")
        version = re.search(r'versionName\s+"([^"]+)"', gradle)
        code = re.search(r'versionCode\s+(\d+)', gradle)
        self.assertIsNotNone(version)
        self.assertIsNotNone(code)
        self.assertIn("V" + version.group(1), readme)
        self.assertIn("versionCode **" + code.group(1) + "**", readme)

    def test_release_notes_describe_one_version_only(self):
        notes = (ROOT / "RELEASE_NOTES.md").read_text(encoding="utf-8")
        headings = re.findall(r"(?m)^# 天鲜账本 V(\d+\.\d+\.\d+\.\d+)", notes)
        self.assertEqual(1, len(headings), "发布说明只能含当前版本")
        gradle = (ROOT / "app/build.gradle").read_text(encoding="utf-8")
        version = re.search(r'versionName\s+"([^"]+)"', gradle)
        self.assertIsNotNone(version)
        self.assertEqual(version.group(1), headings[0])


if __name__ == "__main__":
    unittest.main()
