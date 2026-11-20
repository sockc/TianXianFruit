#!/usr/bin/env python3
"""V0.4.1: cloud reconciliation is read-only; formal purchase writes require server transactions."""
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
assert "TestBusinessSplitInput" in edit_policy
assert "hasDerivedSettlement" in edit_policy
assert "profit_distribution" in edit_policy
assert "daily_cash_settlement" in edit_policy
assert "settlement_transfer" in edit_policy
assert "profit_settlement_batch" in edit_policy
assert "values.splits" in edit_policy
assert "splitsChanged" in edit_policy
assert "sourceWechat == oldAmounts" in edit_policy
assert "rowVersion + 1" in edit_policy
assert "multiCollector" in edit_ui
assert "splitRows" in edit_ui
assert "previouslySettled" in edit_ui
assert "pendingUploads" in editor
assert "api.pushSingle(sending.proposed" in editor
assert "api.validateSession(auth)" in editor
business_drafts = swift["BusinessCollectorDraftPolicy.swift"]
business_form = swift["TestWriteLabView.swift"]
business_payload = swift["TestBusinessUploadModels.swift"]
write_models = swift["TestWriteModels.swift"]
assert "struct BusinessCollectorDraftLine" in business_drafts
assert "BusinessCollectorDraftPolicy.validateTimes" in write_models
assert "BusinessCollectorDraftPolicy.validate(" in write_models
assert "businessCollectors" in write_models
assert "draft.businessCollectors" in business_payload
assert "StagingPayloadBuilder.reference" in business_payload
assert "actual_start_time" in business_payload
assert "actual_end_time" in business_payload
assert "usePerPersonCollectors" in business_form
assert "BusinessCollectorDraftPolicy.totals" in business_form
assert "TestBusinessUploadModels.swift" in str(root / "TestBusinessUploadModels.swift")
assert (root.parent / "Tests" / "BusinessCollectorDraftTests.swift").exists()
assert "api.pushSingle" not in business_form
assert "pushSingle" not in business_drafts
workspace = swift["PurchaseWorkspaceView.swift"]
editor = swift["PurchaseWorkspaceEditorView.swift"]
workspace_models = swift["PurchaseWorkspaceModels.swift"]
workspace_store = swift["PurchaseWorkspaceStore.swift"]
views = swift["Views.swift"]
assert "PurchaseWorkspaceView()" in views
assert "PurchasesHistoryView" in views
assert "purchase_plan_item" in workspace_models
assert "purchase_collaboration" in workspace_models
assert "purchase_plan" in workspace_models
assert "purchase_order" in workspace_models
assert "Set(planning.completed.map" not in workspace_models or "completedFruits" in workspace_models
assert "PurchaseWorkspaceDraft" in workspace_models
assert "PurchaseWorkspaceDraftPolicy.authorized" in editor
assert "selectedBookID == bookID" in editor
assert "model.account?.username.lowercased()" in editor
assert "purchase_workspace_draft" in workspace_store
assert "account=? AND book_id=?" in workspace_store
assert "SET book_id=excluded" not in workspace_store
assert "AND book_id=excluded.book_id" in workspace_store
assert "SELECT payload FROM purchase_workspace_draft" in workspace_store
assert "INSERT INTO purchase_workspace_draft" in workspace_store
assert "purchase_plan_item" not in workspace_store
assert "api.pushSingle" not in workspace_store
assert "pushSingle" not in workspace
assert "/sync/push" not in workspace
assert "pushSingle" not in editor
assert "StagingPurchaseAPI" not in workspace
assert "purchase_workspace_draft" not in regular
assert (root.parent / "Tests" / "PurchaseWorkspaceTests.swift").exists()
reconcile = swift["PurchaseCloudReconcile.swift"]
last_price = swift["PurchaseLastPricePolicy.swift"]
assert "PurchaseCloudReconcilePolicy.inspect" in workspace
assert "model.refresh(forceFull: true)" in workspace
assert "model.isUsingCachedAuthorization" in workspace
assert "PurchaseLastPricePolicy.latest" in editor
assert "price > 0" in last_price
assert "purchase_order" in last_price and "purchase_item" in last_price
assert "fruitSyncID" in last_price
assert "SHA256.hash" in reconcile
assert "matchingCloudOrder" in reconcile
assert "potentialDuplicate" in reconcile
assert "absentFromLatestCloud" in reconcile
assert "unsafeToCompare" in reconcile
assert "/sync/push" not in reconcile
assert "pushSingle" not in reconcile
assert "URLSession" not in reconcile
assert "api.push" not in last_price
assert (root.parent / "Tests" / "PurchaseCloudReconcileTests.swift").exists()
assert (root.parent / "PURCHASE_COMMIT_CONTRACT.md").exists()
print("V0.4.1 PASS: formal-account readback, unique cloud identity checks and last valid price; no production purchase writes.")
