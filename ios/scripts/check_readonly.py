#!/usr/bin/env python3
"""V0.4.3: atomic purchase endpoint is explicitly gated; raw sync push remains test-only."""
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
assert "TestSyncAuditView()" not in swift["Views.swift"]
assert "struct TestSyncAuditView" in swift["TestSyncAuditView.swift"]
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
import_policy = swift["PurchaseImportPolicy.swift"]
import_picker = swift["PurchaseImportPickerView.swift"]
commit_planning = swift["PurchaseCommitPlanningPolicy.swift"]
draft_line = swift["PurchaseDraftModels.swift"]
assert "PurchaseImportPolicy.inventory" in import_picker
assert "PurchaseImportPickerView(" in editor
assert "PurchaseImportPolicy.append" in editor
assert "sourcePlanItemSyncID" in draft_line
assert "sourcePlanItemSyncID" in reconcile
assert "purchase_plan_item" in import_policy
assert "inventory_snapshot" in import_policy
assert "purchase_collaboration" in import_policy
assert "remaining_quantity" in import_policy
assert "suggestedQuantity: \"1\"" in import_policy
assert 'unitPrice: "0"' in import_policy
assert "PurchaseCommitPlanningPolicy" in commit_planning
assert "expectedVersions" in commit_planning
assert "rowVersion" in commit_planning
assert "payloadHash" in commit_planning
assert "SHA256.hash" in commit_planning
assert "/sync/push" not in import_policy
assert "URLSession" not in import_policy
assert "URLSession" not in commit_planning
assert "pushSingle" not in commit_planning
atomic = swift["PurchaseAtomicModels.swift"]
atomic_store = swift["PurchaseWorkspaceStore.swift"]
main_ui = swift["PurchaseWorkspaceView.swift"]
vm = swift["LedgerViewModel.swift"]
assert '"/api/v1/purchases/commit"' in regular
assert '"/api/v1/purchases/capabilities"' in regular
assert '"/api/v1/purchases/operations/"' in regular
assert "func purchaseCommit(" in regular
assert "func purchaseOperation(" in regular
assert "PurchaseCommitCapabilities" in regular
assert "api.purchaseCapabilities" in vm
assert "capabilities.allows(book: book, proposal: proposal)" in vm
assert "api.purchaseCommit(" in vm
assert "model.commitPurchase(proposal)" in main_ui
assert "workspace.reserve(" in main_ui
assert "workspace.transition(" in main_ui
assert "model.findPurchaseCommit(" in main_ui
assert "PurchaseAtomicReadbackPolicy.confirmed" in main_ui
assert "purchase_workspace_attempt" in atomic_store
assert "PRIMARY KEY(draft_id,account,book_id)" in atomic_store
assert "assertEditable(" in atomic_store
assert "status == \"committed\" || status == \"already_committed\"" in atomic
assert "PurchaseCommitCapabilities" in atomic
assert "PurchaseAtomicReadbackPolicy" in atomic
assert "purchaseOrder" not in atomic or "purchase_order" in atomic
assert '"POST", "/api/v1/sync/push"' not in regular
assert (root.parent / "Tests" / "PurchaseAtomicWriteTests.swift").exists()
assert (root.parent / "Tests" / "PurchaseImportAndCommitTests.swift").exists()
print("V0.4.3 PASS: guarded atomic purchase commits, persistent operation lock and verified readback; legacy raw push remains test-only.")

# V0.6.0: the unified test-book workbench must not bypass server-side gates.
shell = swift["AndroidStyleShell.swift"]
assert "book.name.hasPrefix(\"iOS_TEST_\")" in shell
assert "account != nil, !offline" in shell
assert ".productionReadOnly" in shell and ".reserved" in shell
assert "refreshAllTestWriteCapabilities" in swift["LedgerViewModel.swift"]
assert '"/api/v1/purchases/commit"' in regular
assert '"/api/v1/business/commit"' in regular
assert (root.parent / "Tests" / "UnifiedIOSAccessTests.swift").exists()

# V0.6.1: compact UI is display-only; original write safety contracts remain.
assert "TXBusinessReceiptSummaryView(lines: lines)" in swift["BusinessLocalEntryView.swift"]
assert "TXBusinessReceiptPolicy.individual(line)" in swift["BusinessLocalEntryView.swift"]
assert "TXDayBar(date: $date" in swift["PurchaseWorkspaceEditorView.swift"]
assert "PurchaseRemarkEditView(" in swift["AndroidEntryComponents.swift"]
assert "TXTestBookAccess.isCandidate" in swift["AndroidEntryComponents.swift"]
assert (root.parent / "Tests" / "AndroidEntryParityTests.swift").exists()

# V0.6.2: fast input must not introduce new mutation APIs.
assert "TXFastNumericField(text: value, label: title)" in swift["BusinessLocalEntryView.swift"]
assert "TXFastNumericField(text: value, label: name" in swift["PurchaseWorkspaceEditorView.swift"]
assert "TXFastNumericField(text: $value" in swift["AndroidEntryComponents.swift"]
assert "PurchaseDraftPolicy.subtotal(line)" in swift["PurchaseWorkspaceEditorView.swift"]
assert "BusinessFormalSubmit.send" in swift["BusinessLocalEntryView.swift"]
assert "PurchaseWorkspaceDraftPolicy.validate" in swift["PurchaseWorkspaceEditorView.swift"]
assert (root.parent / "Tests" / "FastEntryFieldTests.swift").exists()

# V0.6.3 settlement visual presentation: zero new financial write routes.
settlement_ui = swift["SettlementTableView.swift"]
assert 'TXPageDateHeader(title: "当日结算", date: $model.workDate)' in settlement_ui
assert "snapshotState" in settlement_ui
assert "day.uniqueSnapshot" in settlement_ui
assert "day.savedDaily.count > 1" in settlement_ui
assert "SettlementDateDetail(day: day)" in settlement_ui
assert "TXCloudSyncStatusBar()" in settlement_ui
assert (root.parent / "Tests" / "SettlementCompactPresentationTests.swift").exists()


# V0.6.4: registered transfer progress is read-only; never execute settlement.
stage1 = swift["SettlementStatusPolicy.swift"]
settlement_ui = swift["SettlementTableView.swift"]
finance = swift["FinanceReadOnlyModels.swift"]
assert "TXRecordedSettlementState" in stage1
assert "TXTransferRegistration" in stage1
assert "isPotentialCutoff" in stage1
assert "settlement_kind" in finance
assert "CUTOFF_LEGACY_INVALID" in finance
assert "confirmedAt" in finance
assert "coveringCutoffs" in settlement_ui
assert "SettlementTransferDetailRow" in settlement_ui
assert "云端已登记结算" in settlement_ui
assert '"POST"' not in stage1 and '"PATCH"' not in stage1
assert (root.parent / "Tests" / "SettlementRecordedStatusTests.swift").exists()


# V0.6.5: fund history uses only cloud evidence, not a guessed current balance.
fund_history = swift["FundHistoryReadOnly.swift"]
assert "cumulativeBalanceIsAuthoritative = false" in fund_history
assert "settlement_partner.balance" in fund_history
assert "TXFundHistoryPolicy.days" in fund_history
assert "TXFundHistoryPolicy.batches" in fund_history
assert "已撤销（原登记清零）" in fund_history
assert '"POST"' not in fund_history and '"PATCH"' not in fund_history
assert '"PUT"' not in fund_history and '"DELETE"' not in fund_history
assert "batchKey" in swift["FinanceReadOnlyModels.swift"]
assert "NavigationLink(destination: TXFundHistoryView())" in swift["SettlementTableView.swift"]
assert (root.parent / "Tests" / "FundHistoryReadOnlyTests.swift").exists()


# V0.6.6: settlement actions are immutable local-only preparations.
settlement_preview = swift["SettlementTestPreparation.swift"]
assert "TXSettlementAtomicSubmitView(proposal: prepared)" in settlement_preview
assert "TXSettlementPreparationPolicy.prepare" in settlement_preview
assert "dailyRowVersion" in settlement_preview
assert "transferRowVersion" in settlement_preview
assert "TXTestBookAccess.isCandidate" in settlement_preview
assert "UIPasteboard.general.string" in settlement_preview
assert '"/api/v1/sync/push"' not in settlement_preview
assert '"/api/v1/settlements/commit"' in swift["CloudAPI.swift"]
assert '"/api/v1/settlements/capabilities"' in swift["CloudAPI.swift"]
assert '"/api/v1/settlements/operations/"' in swift["CloudAPI.swift"]
assert "TXSettlementTestWorkbenchView()" in swift["AndroidStyleShell.swift"]
assert (root.parent / "Tests" / "SettlementTestPreparationTests.swift").exists()


# Build44 adds only dedicated TEST settlement endpoint and durable idempotency.
atomic_settlement = swift["SettlementAtomicClient.swift"]
atomic_submit = swift["SettlementAtomicSubmitView.swift"]
assert "operationLookup" in atomic_settlement
assert "scope == \"iOS_TEST_\"" in atomic_settlement
assert "TXSettlementCommitReadback.assessment" in atomic_submit
assert "try store.save(locked)" in atomic_submit
assert "findSettlementOperation" in swift["LedgerViewModel.swift"]
assert "refreshSettlementCapabilities" in swift["LedgerViewModel.swift"]
assert '"/api/v1/sync/push"' not in atomic_settlement
assert '"/api/v1/sync/push"' not in atomic_submit
assert (root.parent / "Tests" / "SettlementAtomicClientTests.swift").exists()

# Build45: restart-safe operation recovery, old-ID replay guard, cross-device states.
settlement_client = swift["SettlementAtomicClient.swift"]
settlement_workbench = swift["SettlementTestPreparation.swift"]
settlement_confirmation = swift["SettlementAtomicSubmitView.swift"]
assert "TXSettlementSubmissionPolicy.canBegin" in settlement_confirmation
assert "TXSettlementCommitReadback.assessment" in settlement_confirmation
assert "restoreOperation()" in settlement_workbench
assert "恢复原操作 / 查询同步结果" in settlement_workbench
assert "operationLocked" in settlement_workbench
assert "case verified, waitingForSync, superseded, inconsistent" in settlement_client
assert '"POST", "/api/v1/sync/push"' not in settlement_confirmation
assert (root.parent / "Tests" / "SettlementAtomicClientTests.swift").exists()

# Build46: the source audit checks original Android Long associations only.
audit = swift["FundReconciliationView.swift"]
assert "TXFundAuditPolicy.audit" in audit
assert "AndroidNumericIdentity.exact" in audit
assert "CUTOFF_LEGACY_INVALID" in audit
assert "累计资金余额：待服务端权威接口" in audit
assert "TXFundReconciliationView()" not in swift["SettlementTableView.swift"]  # diagnostic no longer in routine UI
assert '"POST"' not in audit and '"PATCH"' not in audit
assert '"/api/v1/sync/push"' not in audit
assert (root.parent / "Tests" / "FundReconciliationTests.swift").exists()

# Build47: Android-parity primary navigation, streamlined business screens.
main = swift["Views.swift"]
nav = swift["TXMainNavigation.swift"]
shortcuts = swift["AndroidStyleShell.swift"]
assert "TXMainNavigationBar(selected: $selectedTab)" in main
assert "case .settlement: SettlementTableView()" in main
assert "case home, inventory, purchase, business, settlement, more" in nav
assert "frame(maxWidth: .infinity, minHeight: 52)" in nav
assert "Array(repeating: GridItem(.flexible(), spacing: 7), count: 4)" in shortcuts
assert "TXTestWorkbenchView()" not in main
assert "TXTestWorkbenchView()" not in shortcuts.split("struct TXQuickOperations: View {")[1].split("struct TXInventoryShortcutView: View {")[0]
assert "功能预留" not in main
assert "兼容旧版测试工具" not in main
assert "仅测试账本可提交" not in swift["SettlementTableView.swift"]
assert "办理已保存转账" in swift["SettlementTableView.swift"]
assert "ForEach(TXSettlementTestAction.allCases.filter { $0.canPreviewSavedTransfer })" in swift["SettlementTestPreparation.swift"]
assert (root.parent / "Tests" / "AndroidMainNavigationTests.swift").exists()

# Build48: Android-parity per-business traffic shortcut with safe store scope.
customer_ui = swift["ReadOnlyInsightsViews.swift"]
business_ui = swift["Views.swift"]
routing = swift["BusinessCustomerRouting.swift"]
customer = swift["CustomerReadOnlyModels.swift"]
assert "TXBusinessCustomerShortcut(recordSyncID: row.id)" in business_ui
assert "TXBusinessCustomerRouting.route" in routing
assert "AndroidNumericIdentity.exact" in routing
assert "CustomerInsightsView(" in routing
assert "TXDayBar(date: $selectedDate" in customer_ui
assert "hourlyWeekdayDetails" in customer_ui
assert "hourlySameWeekday" in customer
assert '"POST"' not in routing and '"/api/v1/sync/push"' not in routing
assert (root.parent / "Tests" / "BusinessCustomerRoutingTests.swift").exists()

# Build49: day-to-day actions are visible; cloud write gates and local lock stay intact.
assert "HomePage(selectedTab: $selectedTab)" in main
assert 'coreAction("营业"' in main and 'coreAction("采购"' in main
assert 'coreAction("结算"' in main
assert "saveThenSync()" in swift["BusinessLocalEntryView.swift"]
assert "cloudWriteAvailable" in swift["BusinessLocalEntryView.swift"]
assert "BusinessFormalSubmit.send(model, draft: draft)" in swift["BusinessLocalEntryView.swift"]
assert "workspace.reserve(" in swift["PurchaseWorkspaceView.swift"]
assert "showPlanDetails" in swift["PurchaseWorkspaceView.swift"]
assert 'Label("新增采购"' in swift["PurchaseWorkspaceView.swift"]
assert 'private var range = "单日"' in swift["SettlementTableView.swift"]
assert "showFilters" in swift["SettlementTableView.swift"]
assert '@State private var action: TXSettlementTestAction = .full' in settlement_workbench
assert "TXSettlementPreparationPolicy.prepare(" in settlement_workbench
print("V0.8.0 PASS: primary iOS entry flow and safe cloud write restrictions.")
assert "savedPurchaseToConfirmID" in swift["PurchaseWorkspaceView.swift"]
assert "workspace.drafts.first(where: { $0.id == id })" in swift["PurchaseWorkspaceView.swift"]
assert "onDismiss:" in swift["PurchaseWorkspaceView.swift"]

# Build50: cloud completeness, storage safety, unsaved-field switching and day scope.
business_v081 = swift["BusinessLocalEntryView.swift"]
assert "BusinessLocalDraftStore.loadChecked" in business_v081
assert "BusinessDraftWorkflowPolicy.readyForCloud(" in business_v081
assert "cloudReady" in business_v081
assert "showSwitchConfirmation" in business_v081
assert "loadChecked(account: String, bookID: String) throws" in business_v081
assert "TXSettlementTestWorkbenchView(initialDate: selectedDate)" in swift["SettlementTableView.swift"]
assert "init(initialDate: Date = Date())" in swift["SettlementTestPreparation.swift"]
assert (root.parent / "Tests" / "BusinessLocalDraftStorageTests.swift").exists()
print("V0.8.1 PASS: protect partial business drafts and selected settlement day")

# V0.8.2: server must explicitly authorize production via scope=formal.
assert 'scope == "formal"' in swift["BusinessAtomicCommit.swift"]
assert 'scope == "formal"' in swift["PurchaseAtomicModels.swift"]
assert "caps.allows(book: book)" in swift["LedgerViewModel.swift"]
assert "capabilities.allows(book: book, proposal: proposal)" in swift["LedgerViewModel.swift"]
assert "model.businessCloudBookAllowed" in swift["BusinessLocalEntryView.swift"]
assert "canCommitCloud" in swift["PurchaseWorkspaceView.swift"]
assert (root.parent / "Tests" / "FormalWriteScopeTests.swift").exists()
print("V0.8.2 PASS: formal business/purchase capability scopes; new settlement scope is independently checked")

# V0.8.3: server authorization cannot be bypassed by formal purchase builder.
assert 'capabilities?.eligible(book: book) == true' in swift["PurchaseCommitPlanningPolicy.swift"]
assert 'capabilities: caps' in swift["PurchaseWorkspaceView.swift"]
assert 'caps.allows(book: book, proposal: proposal)' in swift["PurchaseWorkspaceView.swift"]
assert 'oldSyncTime == nil || newSyncTime > oldSyncTime!' in swift["PurchaseWorkspaceView.swift"]
assert 'model.businessCloudBookAllowed else { return }' in swift["BusinessLocalEntryView.swift"]
assert 'missingCloudFields' in swift["BusinessDraftWorkflowPolicy.swift"]
assert 'showDiscardConfirmation' in swift["PurchaseWorkspaceEditorView.swift"]
assert 'dailyTransferReadout' in swift["SettlementTableView.swift"]
assert 'TXTestBookAccess.isCandidate' in swift["SettlementTableView.swift"]
assert 'scope == "formal"' in swift["BusinessAtomicCommit.swift"]
print("V0.8.3 PASS: formal purchase, business recovery and settlement readout")

# V0.8.4: visible pull status and integrity checks never grant write access.
assert "confirmedRemoteMissing" in swift["BusinessDraftWorkflowPolicy.swift"]
assert "confirmedRemoteChanged" in swift["BusinessDraftWorkflowPolicy.swift"]
assert "BusinessCommitPolicy.readback" in swift["BusinessDraftWorkflowPolicy.swift"]
assert "confirmedPurchaseChanged" in swift["PurchaseWorkspaceView.swift"]
assert "PurchaseAtomicReadbackPolicy.confirmed" in swift["PurchaseWorkspaceView.swift"]
assert 'onOrBefore: purchaseDate' in swift["PurchaseWorkspaceEditorView.swift"]
assert 'line.unitPrice = "0"' in swift["PurchaseWorkspaceEditorView.swift"]
assert "TXCloudSyncStatusBar" in swift["Views.swift"]
assert '"近90天"' in swift["TXPurchaseHistoryPolicy.swift"]
assert "TXPurchaseHistoryPolicy.filter" in swift["Views.swift"]
assert "day.shares.prefix(2)" in swift["SettlementTableView.swift"]
assert "day.funds.prefix(2)" in swift["SettlementTableView.swift"]
assert "TXTestBookAccess.isCandidate" in swift["SettlementTableView.swift"]
assert (root.parent / "Tests" / "DailyPurchaseHistoryAndPriceTests.swift").exists()
print("V0.8.4 PASS: Android cloud drift, cost-safe prices, history dates and saved settlement values")

# V0.8.5: dashboard safe-area is adaptive, AppIcon is actually wired to CI.
assert '.navigationBarHidden(true)' in swift["Views.swift"]
assert 'Color(UIColor.systemGroupedBackground).ignoresSafeArea()' in swift["Views.swift"]
assert 'frame(height: 36)' in swift["Views.swift"]
assert 'static let header = Color(UIColor.secondarySystemGroupedBackground)' in swift["AndroidStyleShell.swift"]
assert 'TXBusinessReceiptSummaryView(lines: lines)' in swift["BusinessLocalEntryView.swift"]
assert 'Section(header: Text("营业日期、位置与时间"))' in swift["BusinessLocalEntryView.swift"]
assert 'Picker("类型", selection: $purchaseType)' in swift["PurchaseWorkspaceEditorView.swift"]
assert (root.parent / "scripts" / "generate_app_icon.swift").exists()
assert (root.parent / "Assets.xcassets" / "AppIcon.appiconset" / "Contents.json").exists()
import json
icon_manifest = json.loads((root.parent / "Assets.xcassets" / "AppIcon.appiconset" / "Contents.json").read_text())
assert any(x.get("size") == "1024x1024" for x in icon_manifest["images"])
project = (root.parent / "project.yml").read_text()
assert "ASSETCATALOG_COMPILER_APPICON_NAME: AppIcon" in project
print("V0.8.5 PASS: adaptive dashboard and complete CI-generated AppIcon asset registration")

# V0.8.6: Android-style seven *actual* history days, not calendar days.
recent = swift["TXRecentLedgerHistory.swift"]
assert "previousDays(" in recent
assert "before: selectedDate" in recent
assert 'Text("营业历史")' in recent
assert 'Text("采购历史")' in recent
assert 'Text("最近7个营业日")' in recent
assert '"当天 + 前7个采购日"' in recent
assert '"最近7个采购日"' in recent
assert 'hasSelectedDayOrders' in recent
assert "TXRecentBusinessDaysView(selectedDate: selectedKey)" in swift["Views.swift"]
assert 'if period == "单日"' in swift["Views.swift"]
assert "TXRecentPurchaseDaysView(selectedDate: day)" in swift["PurchaseWorkspaceView.swift"]
assert "PurchaseCloudDetailView" in recent and "BusinessCloudDetailView" in recent
assert (root.parent / "Tests" / "TXRecentLedgerHistoryTests.swift").exists()
print("V0.8.6 PASS: 7 actual previous business/purchase days, detail links, same-day exclusion")


# V0.9.9: never trust a formal response for a test book or a test response
# for a formal book; formal cash operations require device identity.
assert 'TXFormalLedgerAccess.scopeMatches(scope, book: book)' in swift['TXLedgerV33.swift']
assert 'capabilities.allows(book: book)' in swift['LedgerViewModel.swift']
assert 'try await TXFormalLedgerAccess.confirmFinance()' in swift['LedgerViewModel.swift']
assert 'try await TXFormalLedgerAccess.confirmFinance()' in swift['SettlementAtomicSubmitView.swift']
assert '.deviceOwnerAuthentication' in swift['TXFormalLedgerAccess.swift']
assert 'NSFaceIDUsageDescription' in (root.parent / 'Config' / 'AppInfo.plist').read_text()
assert (root.parent / 'Tests' / 'TXFormalLedgerAccessTests.swift').exists()
print("V0.9.9 PASS: formal book capability checks and device authentication path")


# V1.0.0 RC1: normal pages preserve drafts and distinguish failed sync.
assert 'TXAppPresentation.versionLabel' in swift['Views.swift']
assert 'coreAction("库存"' in swift['Views.swift']
assert '"同步异常"' in swift['Views.swift']
assert 'showConfirmedDrafts || attempt.phase != .confirmed || confirmedPurchaseChanged(attempt)' in swift['PurchaseWorkspaceView.swift']
assert 'DisclosureGroup(isExpanded: $showAudit)' in swift['TXFundOverviewPanel.swift']
assert '服务端库存写入未授权' in swift['TXInventory094Page.swift']
assert 'MARKETING_VERSION: "1.0.2"' in project
assert 'CURRENT_PROJECT_VERSION: "80"' in project
print("V1.0.0 RC1 PASS: routine UI, retained history, honest sync states")

# RC2: directory is read-only except existing book selection.
assert 'TXBookDirectoryView()' in swift['Views.swift']
assert 'TXBasicDirectoryView(kind: .fruit)' in swift['Views.swift']
assert 'model.selectBook(book.id)' in swift['TXBasicDirectoryView.swift']
assert 'snapshot.records.values' in swift['TXBasicDirectoryView.swift']
assert '"/api/v1/sync/push"' not in swift['TXBasicDirectoryView.swift']
assert (root.parent / 'Tests' / 'TXBasicDirectoryTests.swift').exists()
print("V1.0.0 RC2 PASS: scoped read-only directory and existing book selection")

# RC3: existing master-data edits only, never generic push.
assert 'basic/commit' in swift['CloudAPI.swift']
assert 'v36SubmitBasic' in swift['LedgerViewModel.swift']
assert 'edit.expected_row_version' in swift['TXLedgerV33.swift']
assert 'kind != .partner && editable' in swift['TXBasicDirectoryView.swift']
assert '"/api/v1/sync/push"' not in swift['TXBasicRecordEditor.swift']
assert (root.parent / 'Tests' / 'TXBasicEditTests.swift').exists()
print("V1.0.0 RC3 PASS: existing master-data editing only")

assert 'private var directoryList: some View' in swift['TXBasicDirectoryView.swift']
assert 'func bookMembers(token:' in swift['CloudAPI.swift']
assert '"/members"' in swift['CloudAPI.swift']
assert 'readBookMembers(expectedScope:' in swift['LedgerViewModel.swift']
assert '"/api/v1/sync/push"' not in swift['TXBookMemberDirectoryView.swift']
assert (root.parent / 'Tests' / 'TXMemberReadTests.swift').exists()
print("V1.0.0 RC4 PASS: split directory view and read-only book membership")

assert 'basic/create-test' in swift['CloudAPI.swift']
assert 'scope == "iOS_TEST_" && book?.name.hasPrefix("iOS_TEST_") == true' in swift['TXLedgerV33.swift']
assert 'snapshot.versions[key] == nil' in swift['LedgerViewModel.swift']
assert '新增测试商品' in swift['TXBasicDirectoryView.swift']
assert (root.parent / 'Tests' / 'TXBasicCreateTests.swift').exists()
print("V1.0.0 RC5 PASS: dedicated test-only master creation")

assert 'basic/partner-test' in swift['CloudAPI.swift']
assert 'basic_partner == true && scope == "iOS_TEST_"' in swift['TXLedgerV33.swift']
assert 'partnerReadbackOnly' in swift['LedgerViewModel.swift']
assert 'TXProfitRuleDirectoryView()' in swift['TXBasicDirectoryView.swift']
assert '"/api/v1/sync/push"' not in swift['TXProfitRuleDirectoryView.swift']
assert (root.parent / 'Tests' / 'TXProfitRuleDirectoryTests.swift').exists()
print("V1.0.0 RC7 PASS: test-only partner rename and read-only rule directory")

assert '@Published var workDate = Date()' in swift["LedgerViewModel.swift"]
assert 'TXWorkDayPolicy.swipeDays' in swift["AndroidStyleShell.swift"]
assert 'TXPageDateHeader(title: "营业", date: $model.workDate)' in swift["Views.swift"]
assert 'TXPageDateHeader(title: "采购", date: $model.workDate' in swift["PurchaseWorkspaceView.swift"]
assert 'TXPageDateHeader(title: "库存", date: $model.workDate)' in swift["TXInventory094Page.swift"]
assert 'TXDayBar(date: $model.workDate, label: "经营日期"' in swift["Views.swift"]
assert 'MARKETING_VERSION: "1.0.2"' in project
print("V1.0.1 UI parity shared operating date guard PASS")

assert "Button(action: openInlineEditor)" in swift["PurchaseWorkspaceView.swift"]
assert "onClose: closeInlineEditor" in swift["PurchaseWorkspaceView.swift"]
assert "guard !inlineLedgerEditing else { return }" in swift["LedgerViewModel.swift"]
assert ".disabled(model.inlineLedgerEditing)" in swift["Views.swift"]
assert "showDiscardConfirmation" in swift["PurchaseWorkspaceEditorView.swift"]
assert "private var quantityRow: some View" in swift["TXInventory094Page.swift"]
print("V1.0.1 inline purchase and inventory type-check split guard PASS")

assert "openBusinessEditor(storeID: filteredStoreID)" in swift["Views.swift"]
assert "onClose: closeBusinessEditor" in swift["Views.swift"]
assert ".sheet(isPresented: $showLocalEntry)" not in swift["Views.swift"]
assert "expectedBookID == bookID" in swift["BusinessLocalEntryView.swift"]
assert "guard scopeMatches, !submitting" in swift["BusinessLocalEntryView.swift"]
assert "inlinePurchaseEditing || inlineBusinessEditing" in swift["LedgerViewModel.swift"]
assert "showCloseConfirmation" in swift["BusinessLocalEntryView.swift"]
print("V1.0.1 inline business entry and account/book isolation guard PASS")

assert "unitPriceForExactLineTotal" in swift["PurchaseDraftModels.swift"]
assert "guard try subtotal(candidate) == desired" in swift["PurchaseDraftModels.swift"]
assert "TXPurchaseLineTotalEditor" in swift["PurchaseWorkspaceEditorView.swift"]
assert "receiptList" in swift["ReadOnlyInsightsViews.swift"]
assert "hourlyAmounts" in swift["ReadOnlyInsightsViews.swift"]
print("V1.0.1 exact line-total reverse and compact customer analysis guard PASS")

assert "resultKey == previewKey" in swift["TXDailySettlement092.swift"]
assert "model.errorMessage == nil" in swift["TXDailySettlement092.swift"]
assert swift["SettlementTableView.swift"].index('Text("实际利润分配 · 原始记录")') < swift["SettlementTableView.swift"].index('Text("合伙人资金轧差 · 原始记录")')
assert 'menuSection("账本与数据")' in swift["Views.swift"]
print("V1.0.1 settlement presentation and grouped settings guard PASS")

assert "keyboardWillShowNotification" in swift["Views.swift"]
assert "keyboardWillHideNotification" in swift["Views.swift"]
assert "if !keyboardVisible" in swift["Views.swift"]
assert swift["TXInventory094Page.swift"].count("TXAdaptiveHorizontal {") == 2
adaptive_ui = swift["AndroidStyleShell.swift"].split("struct TXAdaptiveHorizontal<", 1)[1].split("struct TXCompactMetric", 1)[0]
assert "if #available(iOS 16.0, *)" in adaptive_ui
assert "ViewThatFits(in: .horizontal)" in adaptive_ui
assert "else {\n            stacked" in adaptive_ui
assert 'IPHONEOS_DEPLOYMENT_TARGET: "15.0"' in project
assert "if persisted { date = BusinessDates.parse(item.date) ?? date }" in swift["PurchaseWorkspaceView.swift"]
print("V1.0.1 compact layout and keyboard guard PASS")

reference_ui = swift["BusinessOvernightReference.swift"]
assert '$0.kind == "OVERNIGHT"' in reference_ui
assert "TXBusinessOvernightReferenceView(date: dateKey, partner: line.partnerSyncID)" in swift["BusinessLocalEntryView.swift"]
assert "未自动扣减或计入营业额" in reference_ui
assert "commitBusiness(" not in reference_ui and "sync/push" not in reference_ui
assert (root.parent / "Tests" / "BusinessOvernightReferenceTests.swift").exists()
print("V1.0.2 read-only collector/date overnight reference guard PASS")

assert '("overnight_rollover", "include_overnight")' in swift["CloudAPI.swift"]
assert "(supportsOvernight && !customerOvernightCovered)" in swift["LedgerViewModel.swift"]
assert "customer_overnight_coverage" in swift["LocalLedgerCache.swift"]
assert "model.customerOvernightCovered" in swift["BusinessOvernightReference.swift"]
print("V1.0.2 overnight source opt-in and transactional cache coverage guard PASS")

v2prep = swift["BusinessV2Preparation.swift"]
assert "TXBusinessV2Preparation" in v2prep and "body[\"protocol_version\"] = 2" in v2prep
assert "commitBusiness(" not in v2prep and "URLSession" not in v2prep and "/v2/commit" not in v2prep
assert (root.parent / "Tests" / "BusinessV2PreparationTests.swift").exists()
print("V1.0.2 v2 preparation only, v1 transport retained PASS")

v2editor = swift["BusinessV2DraftEditor.swift"]
assert 'book.name.hasPrefix("iOS_TEST_") ? "test" : "formal"' in swift["BusinessV2Transaction.swift"]
assert "ios.test.business.v2.draft." in v2editor
assert "TXBusinessV2Preparation.adjusted" in v2editor
assert "TXBusinessV2Preparation.canonical" in v2editor
assert "previousRevision: pair.0.revision" in v2editor
assert "未发送" in v2editor and "commitBusiness(" not in v2editor and "/v2/commit" not in v2editor
assert 'Menu {' in swift["Views.swift"] and "TXBusinessV2DraftEditor(scope: scope, onClose: closeV2)" in swift["Views.swift"]
assert (root.parent / "Tests" / "BusinessV2DraftTests.swift").exists()
print("V1.0.2 isolated V2 input draft and preview guard PASS")

assert "matchesSources(previous: previousRevision, following: followingRevision)" in v2editor
assert "确认采用当前来源版本" in v2editor
assert '$0.kind == "NATURAL"' in v2editor
print("V1.0.2 explicit overnight source revision acknowledgement guard PASS")

v2tx = swift["BusinessV2Transaction.swift"]
assert "INSERT INTO reserved(scope,payload)" in v2tx and "DELETE FROM reserved" not in v2tx
assert "TXBusinessV2Readback.verified" in swift["LedgerViewModel.swift"]
assert swift["LedgerViewModel.swift"].index("try outbox.reserve(immutable)") < swift["LedgerViewModel.swift"].index("api.businessV2Commit(token:")
assert "capabilities.allows(book: book)" in regular
assert "查询原操作并核对" in v2editor
print("V1.0.2 V2 test capability, immutable reserve and two-stream readback guard PASS")

assert "requireV1Free" in swift["BusinessFormalSubmit.swift"] and "requireV1Free" in swift["BusinessLocalEntryView.swift"]
assert "try TXBusinessV2DraftStore.load(scope) == nil" in v2tx
assert "TXBusinessV2Outbox.requireV1Free(book: book" in swift["LedgerViewModel.swift"]
print("V1.0.2 V2 reservation protects legacy test-book entry PASS")

assert "caps.allows(book: book)" in swift["LedgerViewModel.swift"]
assert "else if v2Ready" in swift["Views.swift"]
assert "营业草稿及原操作核对" in swift["Views.swift"]
assert "确认保存营业到正式账本" in v2editor
print("Build 80 scope-matched capabilities, inline entry and both-scope draft isolation PASS")
