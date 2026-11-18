#!/usr/bin/env python3
"""V0.2.7: source account read-only; business push only in scoped test client."""
from pathlib import Path

root = Path(__file__).resolve().parents[1] / "Sources"
swift = {p.name: p.read_text(encoding="utf-8") for p in root.glob("*.swift")}
regular = swift["CloudAPI.swift"]
stage = swift["StagingPurchaseAPI.swift"]
policy = swift["StagingPurchaseModels.swift"]
coordinator = swift["StagingPurchaseCoordinator.swift"]
outbox = swift["TestDraftStore.swift"]
ui = swift["StagingPurchaseUploadView.swift"]
keychain = swift["TestSyncCredentialStore.swift"]

for name, code in swift.items():
    if name != "StagingPurchaseAPI.swift":
        assert '"/api/v1/sync/push"' not in code, f"Business push outside test transport: {name}"
    for forbidden in ("/customer-analytics/push", "/api/v1/books/restore",
                      "/api/v1/books/purge", "/api/v1/admin/"):
        assert '"' + forbidden + '"' not in code, f"Forbidden path: {name}"

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
assert "func resume(" in stage
assert '"/api/v1/auth/me"' in stage and '"/api/v1/books"' in stage
assert "SecureStore.write" in keychain
assert "session.token" in keychain
assert "password" not in keychain
assert "TestSyncCredentialStore.load" in coordinator
assert "TestSyncCredentialStore.save" in coordinator
assert "forgetTestLogin" in coordinator
assert "prepareAndUpload" in coordinator
assert "StagingPayloadBuilder.itemEvents" in coordinator
assert "job.startedItemIDs = started" in coordinator
assert "to: .sendingOrder" in coordinator and "to: .sendingItem" in coordinator
assert "api.readback(" in coordinator
assert "needsReview" in coordinator
assert "itemRequestStarted" in coordinator
assert "childParentID = canonical" in coordinator
assert "previous.startedItemIDs" in outbox
assert "previous.extraItems" in outbox
assert "previous.childParentID" in outbox
assert "androidReview" in outbox
assert "lockedForUpload" in outbox
assert "MultiPurchaseEditorView.swift" in swift
assert "StagingPurchaseExtraItem" in policy
assert "prepareMulti(" in policy
assert "static func itemEvents(" in policy
assert "verifiedItemSyncIDs" in swift["PurchaseReadbackAudit.swift"]
assert "pendingItemSyncIDs" in swift["PurchaseReadbackAudit.swift"]
assert "item.link_count" in swift["PurchaseReadbackAudit.swift"]
assert "StagingSyncMaterializer.latest(all)" in stage
assert "TestWriteLabView.swift" in swift
assert "typedBookID" not in swift["TestWriteLabView.swift"]
assert "typedBookID" not in ui
assert "stagePassword" not in ui
assert 'Label("上传测试账本"' in ui
assert "confirmAndroidReview" not in ui
assert "return false" in swift["TestWriteModels.swift"]  # raw drafts cannot push directly
print("V0.2.7 PASS: test-only scoped token, one-tap full purchase, immutable per-item outbox.")
