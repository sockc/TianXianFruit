#!/usr/bin/env python3
"""V0.2.2: only the dedicated one-book purchase client may write businesses."""
from pathlib import Path
root = Path(__file__).resolve().parents[1] / "Sources"
swift = {path.name: path.read_text(encoding="utf-8") for path in root.glob("*.swift")}
regular = swift["CloudAPI.swift"]
stage = swift["StagingPurchaseAPI.swift"]
policy = swift["StagingPurchaseModels.swift"]
coordinator = swift["StagingPurchaseCoordinator.swift"]
ui = swift["StagingPurchaseUploadView.swift"]

for name, code in swift.items():
    if name != "StagingPurchaseAPI.swift":
        assert '"/api/v1/sync/push"' not in code, f"Unauthorized business write route in {name}"
    for route in ("/customer-analytics/push", "/api/v1/books/restore",
                  "/api/v1/books/purge", "/api/v1/admin/"):
        assert '"' + route + '"' not in code, f"Forbidden mutation route {route} in {name}"

assert '"/api/v1/sync/push"' not in regular
assert '"/api/v1/sync/pull"' in regular
assert '"POST", "/api/v1/sync/push"' in stage
assert 'productionURL = "https://sync.830888.xyz"' in policy
assert 'parts.host?.lowercased() == productionHost' in policy
assert 'parts.scheme?.lowercased() == "https"' in policy
assert 'role == "USER"' in policy
assert 'books.count == 1' in policy
assert 'book.name.hasPrefix(TestWritePolicy.prefix)' in policy
assert 'book.id == sourceBook.id' in policy
assert 'book.role == "OWNER" || book.role == "EDITOR"' in policy
assert "requireBookScope" in stage
assert "validateSession" in stage
assert 'func attestation(' not in stage
assert '"GET", "/api/v1/ios-test/attestation"' not in stage
assert 'async' in coordinator
assert "stagingJobs(account: sourceAccount" in coordinator
assert "to: .sendingOrder" in coordinator and "to: .sendingItem" in coordinator
assert "api.verify(" in coordinator
assert "needsReview" in coordinator
assert "TestDraftStore" in swift["TestWriteLabView.swift"]
assert 'return false' in swift["TestWriteModels.swift"] # drafts still cannot push themselves
assert "确认试写一笔" in ui
print("V0.2.2 PASS: fixed official host, dedicated one-book test writer, no ordinary app writes.")
