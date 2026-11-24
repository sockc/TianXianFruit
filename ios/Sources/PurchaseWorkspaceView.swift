import SwiftUI

@MainActor
final class PurchaseWorkspaceModel: ObservableObject {
    @Published private(set) var drafts: [PurchaseWorkspaceDraft] = []
    @Published private(set) var attempts: [String: PurchaseCommitAttempt] = [:]
    @Published private(set) var storageReady = false
    @Published var message: String?
    private let store: PurchaseWorkspaceStore?

    init() {
        do {
            store = try PurchaseWorkspaceStore()
            storageReady = true
        }
        catch {
            store = nil
            storageReady = false
            message = error.localizedDescription
        }
    }

    func reload(account: String?, bookID: String?) {
        drafts = []
        attempts = [:]
        guard let account, let bookID, let store else { return }
        do {
            let existing = try store.load(account: account, bookID: bookID)
            var locked: [String: PurchaseCommitAttempt] = [:]
            for draft in existing {
                if let result = try store.attempt(
                    id: draft.id, account: account, bookID: bookID) {
                    locked[draft.id] = result
                }
            }
            drafts = existing
            attempts = locked
            storageReady = true
        } catch {
            storageReady = false
            message = "采购提交保护记录读取失败，已禁止进一步操作：" +
                error.localizedDescription
        }
    }

    func save(_ item: PurchaseWorkspaceDraft,
              account: String, bookID: String) -> Bool {
        guard let store else { message = "本机采购数据库不可用"; return false }
        do {
            try store.assertEditable(
                id: item.id, account: account, bookID: bookID)
            try store.save(item, account: account, bookID: bookID)
            reload(account: account, bookID: bookID)
            message = "已保存在本机采购录入，不影响云端采购额或结算"
            return true
        } catch {
            message = error.localizedDescription
            return false
        }
    }

    func reserve(_ proposal: PurchaseCommitProposal,
                 account: String, bookID: String) throws
        -> PurchaseCommitAttempt {
        guard storageReady, let store else {
            throw PurchaseAtomicError.persistentStore
        }
        let attempt = try store.reserve(
            proposal, account: account, bookID: bookID)
        reload(account: account, bookID: bookID)
        return attempt
    }

    func transition(_ attempt: PurchaseCommitAttempt,
                    to phase: PurchaseCommitAttempt.Phase,
                    receipt: PurchaseCommitReceipt? = nil,
                    account: String, bookID: String) throws {
        guard storageReady, let store else {
            throw PurchaseAtomicError.persistentStore
        }
        var changed = attempt
        changed.phase = phase
        changed.updatedAt = Date()
        if let receipt { changed.receipt = receipt }
        try store.updateAttempt(
            changed, account: account, bookID: bookID)
        reload(account: account, bookID: bookID)
    }

    func attempt(for draft: PurchaseWorkspaceDraft) -> PurchaseCommitAttempt? {
        attempts[draft.id]
    }

    func delete(_ item: PurchaseWorkspaceDraft,
                account: String, bookID: String) {
        guard let store else { return }
        do {
            try store.assertEditable(
                id: item.id, account: account, bookID: bookID)
            try store.delete(item.id, account: account, bookID: bookID)
            reload(account: account, bookID: bookID)
            message = "已删除本机草稿，没有删除云端采购"
        } catch { message = error.localizedDescription }
    }
}

private struct PurchaseWorkspaceEditorRequest: Identifiable {
    let id = UUID().uuidString
    let original: PurchaseWorkspaceDraft?
    let initialBuyerSyncID: String?
    let initialLines: [PurchaseDraftLine]?

    init(original: PurchaseWorkspaceDraft?,
         buyerSyncID: String? = nil,
         lines: [PurchaseDraftLine]? = nil) {
        self.original = original
        self.initialBuyerSyncID = buyerSyncID
        self.initialLines = lines
    }
}

struct PurchaseWorkspaceView: View {
    @EnvironmentObject private var model: LedgerViewModel
    @StateObject private var workspace = PurchaseWorkspaceModel()
    @State private var date = Date()
    @State private var request: PurchaseWorkspaceEditorRequest?
    @State private var showDrafts = true
    @State private var showPlanDetails = false
    @State private var reconcilingID: String?
    @State private var reviewedID: String?
    @State private var review: PurchaseCloudReconcileReport?
    @State private var pendingCommitID: String?
    @State private var showConfirm = false
    @State private var submittingID: String?
    @State private var savedPurchaseToConfirmID: String?
    @State private var selectedPendingPlan: PurchasePlanningRow?
    @State private var showPlanBuyerChooser = false

    private var day: String { BusinessDates.key(date) }
    private var planning: PurchasePlanningDay {
        PurchaseWorkspaceReadPolicy.materialize(
            Array(model.snapshot.records.values), date: day)
    }
    private var dailyOrders: [PurchaseOrderRow] {
        model.snapshot.purchases.filter { $0.date == day }
    }
    // Only synced purchase_order amounts: plan estimates never enter this total.
    private var syncedPurchaseAmount: String {
        guard model.lastSyncAt != nil else { return "未同步" }
        guard !dailyOrders.isEmpty else { return "无订单" }
        return money(dailyOrders.reduce(0) { $0 + $1.totalCost })
    }
    private var localDrafts: [PurchaseWorkspaceDraft] {
        workspace.drafts.filter { $0.date == day }
    }
    private var canEdit: Bool {
        guard workspace.storageReady,
              let book = model.selectedBook else { return false }
        return PurchaseWorkspaceDraftPolicy.authorized(
            book: book, account: model.account)
    }

    private var canCommitCloud: Bool {
        guard canEdit, !model.isUsingCachedAuthorization,
              let book = model.selectedBook,
              let caps = model.purchaseCommitCapabilities else { return false }
        return caps.eligible(book: book)
    }

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 10) {
                dateHeader
                metrics
                Button {
                    request = PurchaseWorkspaceEditorRequest(original: nil)
                } label: {
                    Label("新增采购", systemImage: "plus.circle.fill")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .disabled(!canEdit)
                localDraftSection
                DisclosureGroup(isExpanded: $showPlanDetails) {
                    VStack(alignment: .leading, spacing: 10) {
                        pendingSection
                        completedSection
                    }
                    .padding(.top, 8)
                } label: {
                    HStack {
                        Label("采购计划与完成情况",
                              systemImage: "list.bullet.clipboard")
                        Spacer()
                        Text(String(planning.pendingFruits) + " 种待采购")
                            .font(.caption).foregroundColor(.secondary)
                    }
                    .font(.subheadline)
                }
                dailyOrderSection
                if let message = workspace.message {
                    Label(message, systemImage: "info.circle")
                        .font(.caption).foregroundColor(.secondary)
                }
                Text(canCommitCloud
                     ? "本机采购保存后可同步，云端回读确认后才计入安卓采购。"
                     : "当前账本尚未授权采购云端写入；本机录入不计入结算。")
                    .font(.caption2).foregroundColor(.secondary)
                HStack {
                    Spacer()
                    NavigationLink(destination: PurchasesHistoryView()) {
                        Label("查看更多采购历史", systemImage: "clock.arrow.circlepath")
                    }
                    Spacer()
                }
                SyncFooter()
            }
            .padding(12)
        }
        .sheet(item: $request, onDismiss: {
            // Offer the cloud confirmation only AFTER the editor has closed.
            // Cancel means saved locally only; no HTTP write occurs.
            if let id = savedPurchaseToConfirmID,
               let draft = workspace.drafts.first(where: { $0.id == id }),
               canCommitCloud {
                date = BusinessDates.parse(draft.date) ?? date
                pendingCommitID = id
                showConfirm = true
            }
            savedPurchaseToConfirmID = nil
        }) { edit in
            if let bookID = model.selectedBookID,
               let account = model.account?.username {
                PurchaseWorkspaceEditorView(
                    original: edit.original, date: date,
                    account: account, bookID: bookID,
                    initialBuyerSyncID: edit.initialBuyerSyncID,
                    initialLines: edit.initialLines) { item in
                        guard model.selectedBookID == bookID,
                              model.account?.username.lowercased()
                                  == account.lowercased(), canEdit else {
                            workspace.message = "账号或账本已切换，草稿未保存"
                            return false
                        }
                        let persisted = workspace.save(
                            item, account: account, bookID: bookID)
                        if persisted && canCommitCloud,
                           let caps = model.purchaseCommitCapabilities,
                           caps.atomic && caps.idempotency &&
                           (!item.lines.contains(where: {
                               $0.sourcePlanItemSyncID != nil
                           }) || caps.supportsCompletePlan) {
                            savedPurchaseToConfirmID = item.id
                        }
                        return persisted
                    }
                    .environmentObject(model)
            } else {
                Text("请先登录并选择账本")
            }
        }
        .confirmationDialog("选择本次采购人",
                            isPresented: $showPlanBuyerChooser,
                            titleVisibility: .visible) {
            if let item = selectedPendingPlan {
                ForEach(PurchasePlanCompletionPolicy.availableBuyers(
                    for: item, partners: model.snapshot.testPartners)) { buyer in
                    Button(buyer.name) {
                        openPlanEntry(item, buyer: buyer)
                    }
                }
            }
        } message: {
            Text("只带入所选采购人的待采购计划；实际数量和单价需要确认。")
        }
        .alert("确认提交采购计划／订单？", isPresented: $showConfirm) {
            Button("取消", role: .cancel) { pendingCommitID = nil }
            Button("确认提交") {
                guard let id = pendingCommitID,
                      let draft = workspace.drafts.first(where: { $0.id == id })
                else { return }
                Task { await submitPurchase(draft) }
            }
        } message: {
            Text("提交成功后服务器一次性保存采购订单和商品明细，关联的待采购计划同步变为已完成。网络异常仅查询原操作，不重复新增。")
        }
        .onAppear {
            reload()
            Task { await model.refreshPurchaseCommitCapabilities() }
        }
        .onChange(of: model.selectedBookID) { _ in
            request = nil
            savedPurchaseToConfirmID = nil
            clearReview()
            reload()
            Task { await model.refreshPurchaseCommitCapabilities() }
        }
        .onChange(of: model.account?.username) { _ in
            request = nil
            savedPurchaseToConfirmID = nil
            clearReview()
            reload()
            Task { await model.refreshPurchaseCommitCapabilities() }
        }
    }

    private var dateHeader: some View {
        HStack(spacing: 6) {
            TXDayBar(date: $date, label: "采购日期")
            Button {
                Task { await model.refresh() }
            } label: {
                Image(systemName: "arrow.clockwise")
            }
            .disabled(model.isBusy)
            .accessibilityLabel("同步采购")
        }
    }

    private var metrics: some View {
        VStack(spacing: 7) {
            HStack(spacing: 7) {
                PurchaseWorkspaceMetric(
                    title: "计划金额",
                    value: money(planning.plannedAmount))
                PurchaseWorkspaceMetric(
                    title: "已完成金额",
                    value: money(planning.completedAmount))
                PurchaseWorkspaceMetric(
                    title: "云端采购额",
                    value: syncedPurchaseAmount)
            }
            Text("计划金额为原计划数据，云端采购额只统计当日已同步订单，不将本机草稿计入结算。")
                .font(.caption2).foregroundColor(.secondary)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private var pendingSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("待采购").font(.headline)
                Spacer()
                Text(String(planning.pending.count) + " 种")
                    .font(.caption.bold()).foregroundColor(.green)
            }
            if !planning.hasPlan {
                notice("这一天还没有同步到采购计划。")
            } else if planning.pending.isEmpty {
                notice(planning.completed.isEmpty ?
                    "这一天暂无待采购商品。" : "✓ 当天采购已全部完成")
            } else {
                let names = Array(Set(planning.pending.map {
                    $0.buyerName.isEmpty ? "未分配采购人" : $0.buyerName
                })).sorted()
                ForEach(names, id: \.self) { name in
                    VStack(alignment: .leading, spacing: 7) {
                        Text(name).font(.subheadline.bold())
                        ForEach(planning.pending.filter {
                            ($0.buyerName.isEmpty ? "未分配采购人" : $0.buyerName) == name
                        }) { row in
                            PurchaseWorkspacePlanRow(item: row)
                            if canEdit {
                                HStack {
                                    Spacer()
                                    Button {
                                        beginPlanEntry(row)
                                    } label: {
                                        Label(PurchasePlanCompletionPolicy.existingDraft(
                                            for: row.id, drafts: workspace.drafts) == nil
                                            ? "填写实际采购" : "继续采购草稿",
                                            systemImage: "square.and.pencil")
                                    }
                                    .buttonStyle(.bordered)
                                    .font(.caption)
                                }
                            }
                        }
                    }
                    .padding(8)
                    .background(Color(UIColor.secondarySystemGroupedBackground),
                                in: RoundedRectangle(cornerRadius: 12))
                }
                Text("从待采购商品直接带入原计划，填写实际数量、单价和重量后再提交完成；不同采购人分别录入。")
                    .font(.caption2).foregroundColor(.secondary)
            }
            if !planning.warnings.isEmpty {
                ForEach(planning.warnings, id: \.self) { warning in
                    Label(warning, systemImage: "exclamationmark.triangle")
                        .font(.caption).foregroundColor(.orange)
                }
            }
            if canEdit {
                Button {
                    request = PurchaseWorkspaceEditorRequest(original: nil)
                } label: {
                    Label("＋ 添加商品／采购录入", systemImage: "plus.circle")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                Text("新增将先保存在本机采购录入；云端待采购清单由 Android 已同步计划决定。")
                    .font(.caption2).foregroundColor(.secondary)
            }
        }
    }

    private var completedSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("已完成采购").font(.headline)
                Spacer()
                Text(String(planning.completed.count) + " 种 · " +
                     money(planning.completedAmount))
                    .font(.caption.bold()).foregroundColor(.green)
            }
            if planning.completed.isEmpty {
                notice("该日期没有已完成采购计划。")
            } else {
                let names = Array(Set(planning.completed.map {
                    $0.buyerName.isEmpty ? "未指定采购人" : $0.buyerName
                })).sorted()
                ForEach(names, id: \.self) { name in
                    VStack(alignment: .leading, spacing: 8) {
                        Text(name).font(.subheadline.bold())
                        ForEach(planning.completed.filter {
                            ($0.buyerName.isEmpty ? "未指定采购人" : $0.buyerName) == name
                        }) { item in
                            PurchaseWorkspacePlanRow(item: item)
                            if item.hasVerifiedOrder,
                               let order = dailyOrders.first(where: {
                                   $0.id == item.linkedOrderID
                               }) {
                                NavigationLink(destination: PurchaseCloudDetailView(
                                    order: order, bookID: model.selectedBookID ?? "")) {
                                    Label("查看关联采购单",
                                          systemImage: "doc.text.magnifyingglass")
                                        .font(.caption)
                                }
                            } else {
                                Text("采购计划已标记完成，订单关联待核对")
                                    .font(.caption2).foregroundColor(.orange)
                            }
                        }
                    }
                    .padding(10)
                    .background(Color(UIColor.secondarySystemGroupedBackground),
                                in: RoundedRectangle(cornerRadius: 12))
                }
            }
        }
    }

    private var localDraftSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Button {
                showDrafts.toggle()
            } label: {
                HStack {
                    Text("采购录入 · " + String(localDrafts.count))
                        .font(.headline)
                    Spacer()
                    Image(systemName: showDrafts ? "chevron.up" : "chevron.down")
                }
            }
            .buttonStyle(.plain)
            if canEdit && !canCommitCloud {
                HStack {
                    Text("当前账本尚未获得云端采购写入授权")
                        .font(.caption).foregroundColor(.secondary)
                    Spacer()
                    Button("刷新权限") {
                        Task { await model.refreshPurchaseCommitCapabilities() }
                    }
                    .font(.caption)
                }
            }
            if showDrafts {
                if localDrafts.isEmpty {
                    notice("当前日期没有本机待提交采购")
                }
                ForEach(localDrafts) { draft in
                    VStack(alignment: .leading, spacing: 8) {
                        HStack {
                            Text(draft.buyerName + " · " +
                                 (draft.purchaseType == "PLANNED" ?
                                  "计划采购" : "临时采购"))
                                .font(.subheadline.bold())
                            Spacer()
                            if let attempt = workspace.attempt(for: draft) {
                                Text(attempt.phase == .confirmed ?
                                    "云端已确认" : "提交待核对")
                                    .font(.caption.bold())
                                    .foregroundColor(
                                        attempt.phase == .confirmed ?
                                        .green : .orange)
                            } else {
                                Text("待同步")
                                    .font(.caption.bold()).foregroundColor(.orange)
                            }
                        }
                        Text(String(draft.lines.count) + " 种商品 · " +
                             (draft.total.map {
                                "¥" + PurchaseDraftPolicy.display($0)
                             } ?? "待核对金额"))
                            .font(.caption)
                        HStack {
                            Button("修改") {
                                clearReview()
                                request = PurchaseWorkspaceEditorRequest(
                                    original: draft)
                            }
                            .disabled(!canEdit || workspace.attempt(for: draft) != nil)
                            Spacer()
                            Button("核对云端") {
                                Task { await reconcile(draft) }
                            }
                            .disabled(!canEdit || model.isBusy ||
                                      reconcilingID != nil)
                            Spacer()
                            Button("删除本机录入", role: .destructive) {
                                if let bookID = model.selectedBookID,
                                   let account = model.account?.username,
                                   canEdit {
                                    clearReview()
                                    workspace.delete(draft,
                                        account: account, bookID: bookID)
                                }
                            }
                            .disabled(!canEdit || workspace.attempt(for: draft) != nil)
                        }
                        .font(.caption)

                        if let attempt = workspace.attempt(for: draft) {
                            Text(attempt.label)
                                .font(.caption2)
                                .foregroundColor(.orange)
                            Button(attempt.phase == .accepted ?
                                   "核对订单同步" : "查询原提交结果") {
                                Task { await recoverPurchase(attempt) }
                            }
                            .disabled(model.isPurchaseCommitBusy || submittingID != nil ||
                                      model.isBusy)
                            .font(.caption)
                        } else if canCommitCloud, let caps = model.purchaseCommitCapabilities,
                                  caps.enabled && caps.atomic && caps.idempotency {
                            if draft.lines.contains(where: { $0.sourcePlanItemSyncID != nil }) &&
                               !caps.supportsCompletePlan {
                                Label("当前账本没有采购计划完成权限；请检查服务端计划编辑授权",
                                      systemImage: "lock.shield")
                                    .font(.caption).foregroundColor(.orange)
                            } else {
                            Button {
                                pendingCommitID = draft.id
                                showConfirm = true
                            } label: {
                                Label("同步采购到云端", systemImage: "arrow.up.doc.fill")
                            }
                            .buttonStyle(.borderedProminent)
                            .disabled(model.isPurchaseCommitBusy ||
                                      model.isBusy || submittingID != nil)
                            }
                        } else {
                            Text("此账本尚未授权采购云端提交，草稿保留本机，不影响结算")
                                .font(.caption2)
                                .foregroundColor(.secondary)
                        }
                        if submittingID == draft.id {
                            ProgressView("正在安全提交采购")
                                .font(.caption)
                        }
                        if reconcilingID == draft.id {
                            HStack(spacing: 8) {
                                ProgressView()
                                Text("正在刷新云端并核对订单")
                                    .font(.caption)
                            }
                        } else if reviewedID == draft.id, let review {
                            Label(review.message, systemImage:
                                review.isConfirmedCloudOrder ?
                                "checkmark.shield.fill" :
                                "exclamationmark.triangle")
                                .font(.caption)
                                .foregroundColor(
                                    review.isConfirmedCloudOrder ? .green : .orange)
                        }
                    }
                    .padding(10)
                    .background(Color(UIColor.secondarySystemGroupedBackground),
                                in: RoundedRectangle(cornerRadius: 11))
                }
            }
        }
    }

    private var dailyOrderSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("正式采购订单").font(.headline)
                Spacer()
                Text(String(dailyOrders.count) + " 单")
                    .font(.caption).foregroundColor(.secondary)
            }
            ForEach(dailyOrders) { order in
                NavigationLink(destination: PurchaseCloudDetailView(
                    order: order, bookID: model.selectedBookID ?? "")) {
                    HStack {
                        Text(order.buyer).font(.subheadline)
                        Spacer()
                        Text(order.totalCost.formatted(.currency(code: "CNY")))
                            .font(.subheadline.bold())
                        Image(systemName: "chevron.right")
                            .font(.caption).foregroundColor(.secondary)
                    }
                    .padding(10)
                    .background(Color(UIColor.secondarySystemGroupedBackground),
                                in: RoundedRectangle(cornerRadius: 11))
                }
                .buttonStyle(.plain)
            }
            if dailyOrders.isEmpty {
                notice("当天尚未同步到正式采购订单")
            }
        }
    }

    private func notice(_ text: String) -> some View {
        Text(text)
            .font(.caption)
            .foregroundColor(.secondary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(11)
            .background(Color(UIColor.secondarySystemGroupedBackground),
                        in: RoundedRectangle(cornerRadius: 11))
    }

    private func money(_ value: Double?) -> String {
        guard let value else { return "未核对" }
        return value.formatted(.currency(code: "CNY"))
    }

    private func beginPlanEntry(_ item: PurchasePlanningRow) {
        guard canEdit else {
            workspace.message = "当前账号没有录入采购权限"
            return
        }
        if let existing = PurchasePlanCompletionPolicy.existingDraft(
            for: item.id, drafts: workspace.drafts) {
            if workspace.attempt(for: existing) != nil {
                workspace.message = "此计划已有锁定的采购提交操作，请在本机采购录入中查询原操作结果，禁止生成第二笔"
                return
            }
            clearReview()
            request = PurchaseWorkspaceEditorRequest(original: existing)
            return
        }
        let buyers = PurchasePlanCompletionPolicy.availableBuyers(
            for: item, partners: model.snapshot.testPartners)
        if buyers.count == 1, let buyer = buyers.first {
            openPlanEntry(item, buyer: buyer)
        } else if buyers.isEmpty {
            workspace.message = "该计划的采购人已变更或删除，无法安全带入，请先在安卓核对"
        } else {
            selectedPendingPlan = item
            showPlanBuyerChooser = true
        }
    }

    private func openPlanEntry(_ item: PurchasePlanningRow, buyer: TestLookup) {
        guard canEdit, model.account != nil, model.selectedBookID != nil else {
            workspace.message = "账号或账本已切换，请重新进入采购页面"
            return
        }
        let events = Array(model.snapshot.records.values)
        guard let line = PurchasePlanCompletionPolicy.preparedItem(
            item.id, buyerSyncID: buyer.id, date: day, events: events)
        else {
            workspace.message = "计划来源未能安全关联（可能已完成、缺少协作记录或采购人不匹配），请同步最新安卓计划"
            return
        }
        clearReview()
        request = PurchaseWorkspaceEditorRequest(
            original: nil, buyerSyncID: buyer.id, lines: [line])
    }

    private func shiftDate(_ by: Int) {
        date = BusinessDates.calendar.date(
            byAdding: .day, value: by, to: date) ?? date
    }

    /// All cloud writes are explicit, preflight-checked and preceded by a
    /// durable reservation. Never retry a timed-out request automatically.
    private func submitPurchase(_ draft: PurchaseWorkspaceDraft) async {
        guard submittingID == nil,
              workspace.attempt(for: draft) == nil,
              let account = model.account,
              let book = model.selectedBook,
              account.username.lowercased() == draft.account.lowercased(),
              book.id == draft.bookID,
              canCommitCloud, !model.isBusy else { return }
        submittingID = draft.id
        defer { submittingID = nil }
        do {
            let oldSyncTime = model.lastSyncAt
            await model.refresh(forceFull: true)
            guard model.errorMessage == nil,
                  let newSyncTime = model.lastSyncAt,
                  oldSyncTime == nil || newSyncTime > oldSyncTime!,
                  model.selectedBookID == book.id,
                  model.account?.username == account.username,
                  !model.isUsingCachedAuthorization else {
                throw PurchaseAtomicError.scopeChanged
            }
            let events = Array(model.snapshot.records.values)
            let review = PurchaseCloudReconcilePolicy.inspect(
                draft, events: events)
            // Multiple valid orders by one buyer are normal. When completing
            // DISTINCT pending plan sources, an unrelated existing order must
            // not block their atomic CAS completion. The server still prevents
            // same-amount duplicates and all actual plan version conflicts.
            let anotherOrderIsUnrelated =
                review.status == .potentialDuplicate &&
                PurchasePlanCompletionPolicy.pendingSources(
                    draft, events: events)
            guard review.status == .absentFromLatestCloud ||
                  anotherOrderIsUnrelated else {
                workspace.message = review.message
                return
            }
            // Never reserve an immutable outbox using stale capability or
            // a capability from a previous selected book.
            await model.refreshPurchaseCommitCapabilities()
            guard let caps = model.purchaseCommitCapabilities,
                  model.selectedBookID == book.id,
                  model.account?.username == account.username,
                  !model.isUsingCachedAuthorization,
                  caps.eligible(book: book) else {
                throw PurchaseAtomicError.unsupported
            }
            let proposal = try PurchaseCommitPlanningPolicy.prepare(
                draft, account: account, book: book, records: events,
                capabilities: caps)
            guard caps.allows(book: book, proposal: proposal) else {
                throw PurchaseAtomicError.unsupported
            }
            let pending = try workspace.reserve(
                proposal, account: account.username, bookID: book.id)
            guard pending.phase == .prepared else {
                throw PurchaseAtomicError.blockedByAttempt
            }
            do {
                let receipt = try await model.commitPurchase(proposal)
                try workspace.transition(pending, to: .accepted,
                    receipt: receipt, account: account.username, bookID: book.id)
                await verifyCommitted(proposal, receipt: receipt,
                                      account: account.username, bookID: book.id)
            } catch {
                // If a write was attempted and the response is uncertain, do
                // not retry POST, do not change operation id, do not unlock.
                if let existing = workspace.attempt(for: draft),
                   existing.phase == .prepared {
                    try? workspace.transition(existing, to: .unknown,
                        account: account.username, bookID: book.id)
                }
                throw error
            }
        } catch {
            workspace.message = error.localizedDescription
        }
    }

    private func recoverPurchase(_ attempt: PurchaseCommitAttempt) async {
        guard submittingID == nil,
              model.account?.username.lowercased() == attempt.account.lowercased(),
              model.selectedBookID == attempt.bookID else { return }
        submittingID = attempt.id
        defer { submittingID = nil }
        do {
            if let receipt = attempt.receipt {
                await verifyCommitted(attempt.proposal, receipt: receipt,
                                      account: attempt.account, bookID: attempt.bookID)
                return
            }
            guard let receipt = try await model.findPurchaseCommit(
                attempt.proposal) else {
                workspace.message = "服务器暂未确认这笔采购，请勿重复提交；原本机记录已保留"
                return
            }
            try workspace.transition(attempt, to: .accepted,
                receipt: receipt, account: attempt.account, bookID: attempt.bookID)
            await verifyCommitted(attempt.proposal, receipt: receipt,
                                  account: attempt.account, bookID: attempt.bookID)
        } catch {
            workspace.message = error.localizedDescription
        }
    }

    private func verifyCommitted(_ proposal: PurchaseCommitProposal,
                                 receipt: PurchaseCommitReceipt,
                                 account: String, bookID: String) async {
        await model.refresh(forceFull: true)
        guard model.errorMessage == nil,
              model.account?.username.lowercased() == account.lowercased(),
              model.selectedBookID == bookID,
              PurchaseAtomicReadbackPolicy.confirmed(
                proposal, receipt: receipt,
                records: Array(model.snapshot.records.values)),
              let last = workspace.attempts[proposal.draftID] else {
            workspace.message = "服务器提交回执已保存，但尚未核对到全部订单明细；请点击“核对订单同步”"
            return
        }
        do {
            try workspace.transition(last, to: .confirmed,
                receipt: receipt, account: account, bookID: bookID)
            workspace.message = proposal.operation == "complete_plan"
                ? "采购单、商品明细及待采购计划完成状态已全部核对；安卓刷新后可查看"
                : "采购已完整同步至云端，安卓刷新后可查看"
        } catch {
            workspace.message = error.localizedDescription
        }
    }

    private func clearReview() {
        reviewedID = nil
        review = nil
    }

    private func reconcile(_ draft: PurchaseWorkspaceDraft) async {
        guard reconcilingID == nil, !model.isBusy,
              model.account?.username.lowercased() == draft.account.lowercased(),
              model.selectedBookID == draft.bookID,
              !model.isUsingCachedAuthorization else {
            workspace.message = "离线或正在同步时不能核对云端订单"
            return
        }
        let oldSynced = model.lastSyncAt
        reconcilingID = draft.id
        clearReview()
        defer { reconcilingID = nil }
        await model.refresh(forceFull: true)
        guard model.account?.username.lowercased() == draft.account.lowercased(),
              model.selectedBookID == draft.bookID,
              !model.isUsingCachedAuthorization,
              model.errorMessage == nil,
              let current = model.lastSyncAt,
              oldSynced == nil || current > oldSynced! else {
            workspace.message = "此次未成功完整同步，不能据旧缓存判断是否已上传"
            return
        }
        // A full refresh can make a referenced fruit/partner invalid. Never
        // reuse earlier cached lookup status or claim successful cloud write.
        let report = PurchaseCloudReconcilePolicy.inspect(
            draft, events: Array(model.snapshot.records.values))
        review = report
        reviewedID = draft.id
    }

    private func reload() {
        workspace.reload(account: model.account?.username,
                         bookID: model.selectedBookID)
    }
}

private struct PurchaseWorkspaceMetric: View {
    let title: String
    let value: String

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(title).font(.caption2).foregroundColor(.secondary)
                .lineLimit(1).minimumScaleFactor(0.8)
            Text(value).font(.subheadline.bold()).minimumScaleFactor(0.65)
                .lineLimit(1)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(8)
        .background(Color(UIColor.secondarySystemGroupedBackground),
                    in: RoundedRectangle(cornerRadius: 11))
    }
}

private struct PurchaseWorkspacePlanRow: View {
    let item: PurchasePlanningRow

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: item.isCompleted ?
                  "checkmark.circle.fill" : "circle")
                .foregroundColor(item.isCompleted ? .green : .secondary)
                .font(.title3)
            VStack(alignment: .leading, spacing: 4) {
                Text(item.name).font(.subheadline.bold())
                HStack(spacing: 4) {
                    Text(item.isCompleted ? "实际" : "计划")
                    Text(item.displayQuantity.formatted() + item.unit)
                    Text("·")
                    if let amount = item.displayAmount {
                        Text(amount.formatted(.currency(code: "CNY")))
                    } else {
                        Text("金额未核对")
                    }
                    if !item.buyerName.isEmpty {
                        Text("· " + item.buyerName)
                    }
                }
                .font(.caption).foregroundColor(.secondary)
                if !item.remark.isEmpty {
                    Text(item.remark)
                        .font(.caption2).foregroundColor(.secondary)
                }
                if let warning = item.warning {
                    Text(warning).font(.caption2).foregroundColor(.orange)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(10)
        .background(Color(UIColor.secondarySystemGroupedBackground),
                    in: RoundedRectangle(cornerRadius: 11))
    }
}
