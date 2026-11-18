#!/usr/bin/env python3
"""V0.2.0 hard stop: no remotely transmitted business writes, including drafts."""
from pathlib import Path

sources = Path(__file__).resolve().parents[1] / "Sources"
code = "\n".join(p.read_text(encoding="utf-8") for p in sources.glob("*.swift"))
for path in (
    "/api/v1/sync/push",
    "/customer-analytics/push",
    "/api/v1/books/restore",
    "/api/v1/books/purge",
    "/api/v1/admin/",
):
    assert path not in code, f"Forbidden cloud mutation route: {path}"
assert '"/api/v1/sync/pull"' in code
assert '"/api/v1/books"' in code
assert '"/api/v1/auth/login"' in code
assert '"/api/v1/devices/register"' in code
assert 'path == "/api/v1/sync/push"' not in code
assert "cloudSubmissionIsAllowed" in code
assert "return false" in (sources / "TestWriteModels.swift").read_text(encoding="utf-8")
assert "TestDraftStore" in (sources / "TestWriteLabView.swift").read_text(encoding="utf-8")
assert "class TestDraftStore" in (sources / "TestDraftStore.swift").read_text(encoding="utf-8")
print("V0.2.0 safe: all business mutations disabled; drafts are device-local only.")
