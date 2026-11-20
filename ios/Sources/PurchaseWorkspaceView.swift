import SwiftUI

@MainActor
final class PurchaseWorkspaceModel: ObservableObject {
    @Published private(set) var drafts: [PurchaseWorkspaceDraft] = []
    @Published var message: String?
    private let store: PurchaseWorkspaceStore?

    init() {
        do { store = try PurchaseWorkspaceStore() }
        catch { store = nil; message = error.localizedDescription }
    }

    func reload(account: String?, bookID: String?) {
        drafts = []
        guard let account, let bookID, let store else { return }
        do {
            drafts = try store.load(account: account, bookID: bookID)
        } catch { message = error.localizedDescription }
    }

    func save(_ item: PurchaseWorkspaceDraft,
              account: String, bookID: String) -> Bool {
        guard let store else { message = "本机采购数据库不可用"; return false }
        do {
            try store.save(item, account: account, bookID: bookID)
            reload(account: account, bookID: bookID)
            message = "已保存在本机采购录入，不影响云端采购额或结算"
            return true
        } catch {
            message = error.localizedDescription
            return false
        }
    }

    func delete(_ item: PurchaseWorkspaceDraft,
                account: String, bookID: String) {
        guard let store else { return }
        do {
            try store.delete(item.id, account: account, bookID: bookID)
            reload(account: account, bookID: bookID)
            message = "已删除本机草稿，没有删除云端采购"
        } catch { message = error.localizedDescription }
    }
}

private struct PurchaseWorkspaceEditorRequest: Identifiable {
    let id = UUID().uuidString
    let original: PurchaseWorkspaceDraft?
}

struct PurchaseWorkspaceView: View {
    @EnvironmentObject private var model: LedgerViewModel
    @StateObject private var workspace = PurchaseWorkspaceModel()
    @State private var date = Date()
    @State private var request: PurchaseWorkspaceEditorRequest?
    @State private var showDrafts = true
    @State private var reconcilingID: String?
    @State private var reviewedID: String?
    @State private var review: PurchaseCloudReconcileReport?

    private var day: String { BusinessDates.key(date) }
    private var planning: PurchasePlanningDay {
        PurchaseWorkspaceReadPolicy.materialize(
            Array(model.snapshot.records.values), date: day)
    }
    private var dailyOrders: [PurchaseOrderRow] {
        model.snapshot.purchases.filter { $0.date == day }
    }
    private var localDrafts: [PurchaseWorkspaceDraft] {
        workspace.drafts.filter { $0.date == day }
    }
    private var canEdit: Bool {
        guard let book = model.selectedBook else { return false }
        return PurchaseWorkspaceDraftPolicy.authorized(
            book: book, account: model.account)
    }

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 10) {
                dateHeader
                metrics
                pendingSection
                completedSection
                localDraftSection
                dailyOrderSection
                if let message = workspace.message {
                    Label(message, systemImage: "info.circle")
                        .font(.caption).foregroundColor(.secondary)
                }
                Text("采购计划、已完成及历史订单来自安卓云同步；本机新增采购暂不发送，正式采购/结算不会被草稿重复计入。")
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
        .sheet(item: $request) { edit in
            if let bookID = model.selectedBookID,
               let account = model.account?.username {
                PurchaseWorkspaceEditorView(
                    original: edit.original, date: date,
                    account: account, bookID: bookID) { item in
                        guard model.selectedBookID == bookID,
                              model.account?.username.lowercased()
                                  == account.lowercased(), canEdit else {
                            workspace.message = "账号或账本已切换，草稿未保存"
                            return false
                        }
                        return workspace.save(item, account: account, bookID: bookID)
                    }
                    .environmentObject(model)
            } else {
                Text("请先登录并选择账本")
            }
        }
        .onAppear(perform: reload)
        .onChange(of: model.selectedBookID) { _ in
            request = nil
            clearReview()
            reload()
        }
        .onChange(of: model.account?.username) { _ in
            request = nil
            clearReview()
            reload()
        }
    }

    private var dateHeader: some View {
        HStack(spacing: 10) {
            Button {
                shiftDate(-1)
            } label: {
                Image(systemName: "chevron.left")
                    .frame(width: 28, height: 34)
            }
            .accessibilityLabel("上一日")
            DatePicker("采购日期", selection: $date,
                       displayedComponents: .date)
                .labelsHidden()
                .datePickerStyle(.compact)
                .frame(maxWidth: .infinity)
            Button {
                shiftDate(1)
            } label: {
                Image(systemName: "chevron.right")
                    .frame(width: 28, height: 34)
            }
            .accessibilityLabel("下一日")
            Button {
                Task { await model.refresh() }
            } label: {
                if model.isBusy { ProgressView() }
                else { Text("刷新") }
            }
            .disabled(model.isBusy)
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
            }
            HStack(spacing: 7) {
                PurchaseWorkspaceMetric(
                    title: "已采购",
                    value: String(planning.completedFruits) + " 种")
                PurchaseWorkspaceMetric(
                    title: "剩余",
                    value: String(planning.pendingFruits) + " 种")
            }
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
                ForEach(planning.pending) { row in
                    PurchaseWorkspacePlanRow(item: row)
                }
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
                    Text("本机采购录入 · " + String(localDrafts.count))
                        .font(.headline)
                    Spacer()
                    Image(systemName: showDrafts ? "chevron.up" : "chevron.down")
                }
            }
            .buttonStyle(.plain)
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
                            Text("未提交")
                                .font(.caption.bold()).foregroundColor(.orange)
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
                            .disabled(!canEdit)
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
                            .disabled(!canEdit)
                        }
                        .font(.caption)
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

    private func shiftDate(_ by: Int) {
        date = BusinessDates.calendar.date(
            byAdding: .day, value: by, to: date) ?? date
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
            Text(title).font(.caption).foregroundColor(.secondary)
            Text(value).font(.subheadline.bold()).minimumScaleFactor(0.8)
                .lineLimit(1)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(11)
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
