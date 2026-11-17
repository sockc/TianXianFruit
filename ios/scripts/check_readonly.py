#!/usr/bin/env python3
"""Static guard: V0.1 iOS must not contain ledger mutation routes."""
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
    assert path not in code, f"Forbidden business/admin route in iOS source: {path}"
assert '"/api/v1/sync/pull"' in code
assert '"/api/v1/books"' in code
print("Read-only route guard passed (login and device registration permitted).")
