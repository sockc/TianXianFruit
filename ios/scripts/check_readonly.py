#!/usr/bin/env python3
"""V0.2.6: only the dedicated one-book purchase client may write businesses."""
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
assert "api.readback(" in coordinator
assert "needsReview" in coordinator
assert "TestDraftStore" in swift["TestWriteLabView.swift"]
assert 'return false' in swift["TestWriteModels.swift"] # drafts still cannot push themselves
assert "确认试写一笔" in ui
assert "confirmAndroidReview" in ui
assert "PurchaseReadbackAudit.swift" in swift
assert "PurchaseReadbackPolicy.audit" in swift["StagingPurchaseAPI.swift"]
assert "readback" in coordinator
assert "pilotOrderLimit = 3" in policy
assert "authorizeNextOrder(" in coordinator
assert "itemRequestStarted = true" in coordinator
assert "childParentID = canonical" in coordinator
assert "job.childParentID == canonical" in coordinator
assert "previous.childParentID" in swift["TestDraftStore.swift"]
assert "itemRequestStarted" in swift["TestDraftStore.swift"]
assert "androidCheckedAt" in swift["TestDraftStore.swift"]
assert "AndroidPurchaseReview.swift" in swift
assert "AndroidPurchaseReviewSheet.swift" in swift
assert "androidReview" in policy
assert "AndroidPurchaseReviewPolicy.isCurrent" in policy
assert "AndroidPurchaseReviewPolicy.confirm" in coordinator
assert "cloudFingerprint" in swift["AndroidPurchaseReview.swift"]
assert "androidReview" in swift["TestDraftStore.swift"]
assert "PurchaseDraftModels.swift" in swift
assert "MultiPurchaseEditorView.swift" in swift
assert "guard draft.purchaseItems == nil" in policy
assert "guard draft.purchaseItems == nil" in coordinator
assert "draft.purchaseItems != nil" in swift["StagingPurchaseUploadView.swift"]
assert "lockedForUpload" in swift["TestDraftStore.swift"]
assert "date=excluded.date" in swift["TestDraftStore.swift"]
assert "StagingSyncMaterializer.latest(all)" in stage
assert "orderDeleted" in swift["PurchaseReadbackAudit.swift"]
assert "itemDeleted" in swift["PurchaseReadbackAudit.swift"]
print("V0.2.6 PASS: fixed HTTPS test-book scope, durable child mapping, tombstones and readback gates.")
