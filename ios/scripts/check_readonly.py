#!/usr/bin/env python3
"""V0.3.2: one-pull TEST audit must never submit or mutate financial records."""
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
assert "let password:" not in keychain
assert '"password":' not in keychain
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
business = swift["BusinessUploadCoordinator.swift"]
policy_business = swift["TestBusinessUploadModels.swift"]
view_business = swift["BusinessUploadView.swift"]
assert "api.pushSingle(job.event" in business
assert "api.validateSession(auth)" in business
assert "BusinessUploadPolicy.conflict" in business
assert "job.state = .sending" in business
assert "try storage.saveBusinessJob(job)" in business
assert "BusinessUploadPolicy.verified" in business
assert "book.name.hasPrefix(TestWritePolicy.prefix)" in business
assert "androidSyncID" in policy_business
assert "store_daily_record|" in policy_business
assert "receipt_splits_json" in policy_business
assert "production-scoped-testbook:" in policy_business
assert "coordinator.inspect" in view_business
assert "isolated_business_jobs" in outbox
assert "businessJob(id: draft.id" in outbox
assert (root.parent / "Tests" / "BusinessUploadTests.swift").exists()
editor = swift["TestBusinessEditCoordinator.swift"]
edit_policy = swift["TestBusinessEditPolicy.swift"]
edit_ui = swift["TestBusinessEditView.swift"]
assert "api.pushSingle(sending.proposed" in editor
assert "api.validateSession(auth)" in editor
assert "TestBusinessEditPolicy.unchanged" in editor
assert "TestBusinessEditPolicy.confirmed" in editor
assert "try store.saveBusinessEditJob(sending)" in editor
assert "sending.state = .sending" in editor
assert "TestSyncCredentialStore.load" in editor
assert "book.name.hasPrefix(TestWritePolicy.prefix)" in editor
assert "rowVersion + 1" in edit_policy
assert "receipt_splits_json" in edit_policy
assert "BusinessUploadPolicy.androidSyncID" in edit_policy
assert "isolated_business_edit_jobs" in outbox
assert "coordinator.submit" in edit_ui
assert "coordinator.inspect" in edit_ui
assert (root.parent / "Tests" / "TestBusinessEditTests.swift").exists()
remark = swift["PurchaseRemarkEditPolicy.swift"]
remark_upload = swift["PurchaseRemarkEditCoordinator.swift"]
remark_ui = swift["PurchaseRemarkEditView.swift"]
purchase_details = swift["PurchaseCloudDetailView.swift"]
business_review = swift["BusinessEditCloudReview.swift"]
assert "PurchaseRemarkEditPolicy.prepare" in remark_upload
assert "PurchaseRemarkEditPolicy.unchanged" in remark_upload
assert "PurchaseRemarkEditPolicy.confirmed" in remark_upload
assert "api.pushSingle(sending.proposed" in remark_upload
assert "try store.savePurchaseRemarkJob(sending)" in remark_upload
assert "api.validateSession(auth)" in remark_upload
assert "TestSyncCredentialStore.load" in remark_upload
assert "book.name.hasPrefix(TestWritePolicy.prefix)" in remark_upload
assert "receipt_splits_json" not in remark
assert "purchase_order" in remark and "purchase_item" in remark
assert "attachedChildren" in remark and "childrenEqual" in remark
assert "original.rowVersion + 1" in remark
assert "isolated_purchase_remark_jobs" in outbox
assert "cancelUnsentRemark" in outbox
assert "coordinator.submit" in remark_ui
assert "coordinator.inspect" in remark_ui
assert "PurchaseRemarkEditView" in purchase_details
assert "BusinessEditCloudReview" in swift["TestBusinessEditView.swift"]
assert "not Android" in business_review or "not proof" in business_review
assert (root.parent / "Tests" / "PurchaseRemarkEditTests.swift").exists()
assert (root.parent / "Tests" / "BusinessEditCloudReviewTests.swift").exists()
audit_policy = swift["TestSyncAuditPolicy.swift"]
audit_coordinator = swift["TestSyncAuditCoordinator.swift"]
audit_view = swift["TestSyncAuditView.swift"]
assert "TestSyncAuditPolicy.audit" in audit_coordinator
assert "transport.pullAll(session)" in audit_coordinator
assert "transport.validateSession(session)" in audit_coordinator
assert "TestSyncCredentialStore.load" in audit_coordinator
assert "book.name.hasPrefix(TestWritePolicy.prefix)" in audit_coordinator
assert "businessJobs" in audit_coordinator
assert "businessEditJobs" in audit_coordinator
assert "stagingJobs" in audit_coordinator
assert "purchaseRemarkJobs" in audit_coordinator
assert "BusinessUploadPolicy.verified" in audit_policy
assert "TestBusinessEditPolicy.confirmed" in audit_policy
assert "PurchaseReadbackPolicy.audit" in audit_policy
assert "PurchaseRemarkEditPolicy.confirmed" in audit_policy
assert "StagingSyncMaterializer.latest" in audit_policy
assert "TestSyncAuditView()" in swift["Views.swift"]
assert "coordinator.refresh()" in audit_view
assert "api.pushSingle" not in audit_coordinator
assert "pushSingle" not in audit_policy
assert "saveBusinessJob" not in audit_coordinator
assert "saveBusinessEditJob" not in audit_coordinator
assert "savePurchaseRemarkJob" not in audit_coordinator
assert "saveStagingJob" not in audit_coordinator
assert (root.parent / "Tests" / "TestSyncAuditTests.swift").exists()
print("V0.3.2 PASS: official read-only, scoped test audit without financial writes or retry.")
