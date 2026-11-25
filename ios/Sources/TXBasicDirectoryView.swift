import Foundation
import SwiftUI

enum TXBasicDirectoryKind: String {
    case fruit, store, partner
    var title: String {
        switch self {
        case .fruit: return "商品管理"
        case .store: return "位置管理"
        case .partner: return "合伙人管理"
        }
    }
}

struct TXBasicDirectoryEntry: Identifiable, Equatable {
    let id: String
    let name: String
}

enum TXBasicDirectoryPolicy {
    static func entries(_ snapshot: ReadOnlySnapshot, kind: TXBasicDirectoryKind,
                        query: String = "") -> [TXBasicDirectoryEntry] {
        snapshot.records.values.compactMap { event -> TXBasicDirectoryEntry? in
            guard event.tableName == kind.rawValue, !event.isDeletion else { return nil }
            let name = (event.payload["name"]?.text ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            let display = name.isEmpty ? "未命名记录" : name
            guard query.isEmpty || display.localizedCaseInsensitiveContains(query) else { return nil }
            return TXBasicDirectoryEntry(id: event.syncId, name: display)
        }.sorted { a, b in
            a.name == b.name ? a.id < b.id : a.name.localizedStandardCompare(b.name) == .orderedAscending
        }
    }

    // A cached or failed sync must not leave a previously authorized edit button active.
    static func canEdit(scope: String, authorizedScope: String, localCandidate: Bool,
                        synced: Bool, cached: Bool, syncFailed: Bool,
                        busy: Bool, pending: Bool) -> Bool {
        !scope.isEmpty && scope != "|" && scope == authorizedScope && localCandidate &&
        synced && !cached && !syncFailed && !busy && !pending
    }

    static func roleLabel(_ role: String) -> String {
        switch role {
        case "OWNER": return "所有者"
        case "EDITOR": return "编辑成员"
        case "VIEWER": return "只读成员"
        case "SUPERADMIN": return "系统管理员"
        default: return "未识别权限"
        }
    }
}

struct TXBookDirectoryView: View {
    @EnvironmentObject private var model: LedgerViewModel
    private var switchingBlocked: Bool {
        model.isBusy || model.isBusinessCommitBusy || model.isPurchaseCommitBusy ||
        model.isSettlementCommitBusy || model.isLedgerV33Busy
    }

    var body: some View {
        List {
            Section("可用账本") {
                ForEach(model.books.filter(\.isAvailable)) { book in
                    Button {
                        guard !switchingBlocked else { return }
                        model.selectBook(book.id)
                    } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 3) {
                                Text(book.name).foregroundColor(.primary)
                                Text(TXBasicDirectoryPolicy.roleLabel(book.role))
                                    .font(.caption).foregroundColor(.secondary)
                            }
                            Spacer()
                            if model.selectedBookID == book.id {
                                Image(systemName: "checkmark.circle.fill").foregroundColor(TXTheme.primary)
                            }
                        }
                    }.disabled(switchingBlocked || model.selectedBookID == book.id)
                }
            }
            Section("当前账本") {
                if let book = model.selectedBook {
                    Text(book.name)
                    HStack {
                        Text("营业云端录入")
                        Spacer()
                        Text(model.businessCloudBookAllowed ? "已授权" : "未授权").foregroundColor(.secondary)
                    }
                    HStack {
                        Text("采购云端录入")
                        Spacer()
                        Text(model.purchaseCommitCapabilities?.eligible(book: book) == true && model.v34LocalCandidate ? "已授权" : "未授权").foregroundColor(.secondary)
                    }
                }
                if model.isUsingCachedAuthorization {
                    Text("离线缓存：无法确认实时授权").font(.caption).foregroundColor(.orange)
                }
                if model.selectedBook != nil {
                    NavigationLink(destination: TXBookMemberDirectoryView()) {
                        Label("账本成员与权限", systemImage: "person.2")
                    }
                }
                Button("刷新当前账本及权限") {
                    Task { await model.refresh(); await model.refreshAllTestWriteCapabilities() }
                }.disabled(switchingBlocked)
                Text("本页支持查看与切换已有账本，不创建、删除账本或修改成员权限。")
                    .font(.caption).foregroundColor(.secondary)
            }
        }
        .navigationTitle("账本管理")
        .navigationBarTitleDisplayMode(.inline)
    }
}

struct TXBasicDirectoryView: View {
    @EnvironmentObject private var model: LedgerViewModel
    let kind: TXBasicDirectoryKind
    @State private var query = ""
    @State private var selected: TXBasicEditorSelection?
    @State private var authorizedScope = ""
    @State private var createScope = ""
    @State private var message = ""
    private var scope: String { (model.account?.username ?? "") + "|" + (model.selectedBookID ?? "") }
    private var editable: Bool {
        TXBasicDirectoryPolicy.canEdit(scope: scope, authorizedScope: authorizedScope,
            localCandidate: model.v34LocalCandidate, synced: model.lastSyncAt != nil,
            cached: model.isUsingCachedAuthorization, syncFailed: model.errorMessage != nil,
            busy: model.isBusy || model.isLedgerV33Busy, pending: model.v33HasPending)
    }
    private func verifyAccess() async {
        let original = scope
        authorizedScope = ""; createScope = ""
        if kind == .partner {
            let allowed = await model.v33Capability("basic_partner")
            if scope == original && !Task.isCancelled { authorizedScope = allowed ? original : "" }
            return
        }
        let allowed = await model.v33Capability("basic")
        let create = await model.v33Capability("basic_create")
        if scope == original && !Task.isCancelled { authorizedScope = allowed ? original : ""; createScope = create ? original : "" }
    }
    private var rows: [TXBasicDirectoryEntry] {
        TXBasicDirectoryPolicy.entries(model.snapshot, kind: kind, query: query)
    }

    private var accessKey: String {
        [scope, String(model.currentCursor), String(model.isUsingCachedAuthorization), String(model.errorMessage != nil)].joined(separator: "|")
    }

    private var statusText: String {
        if model.isUsingCachedAuthorization || model.errorMessage != nil { return "同步恢复后可重新核验编辑权限。" }
        if kind == .partner { return editable ? "空白测试账本可改名；利润规则仅查看。" : "合伙人历史姓名受保护，利润规则仅查看。" }
        if createScope == scope && !scope.isEmpty { return "测试账本：新增资料为兼容性实验，请勿用于正式经营。" }
        if editable { return "支持修改现有资料；新增和删除请在安卓端操作。" }
        return "当前仅查看，服务端基础资料写入未授权。"
    }

    @ViewBuilder private var statusSection: some View {
        Section {
            if let book = model.selectedBook {
                Text(book.name).font(.caption).foregroundColor(.secondary)
            }
            if model.lastSyncAt == nil {
                Text("尚未同步资料").font(.caption).foregroundColor(.orange)
            } else if model.isUsingCachedAuthorization || model.errorMessage != nil {
                Text("当前显示缓存资料").font(.caption).foregroundColor(.orange)
            }
            Text(statusText).font(.caption).foregroundColor(.secondary)
            if kind == .partner {
                NavigationLink(destination: TXProfitRuleDirectoryView()) {
                    Label("利润分配规则", systemImage: "chart.pie")
                }
            }
            if kind != .partner && editable && createScope == scope && TXTestBookAccess.isCandidate(model.selectedBook, account: model.account, offline: model.isUsingCachedAuthorization) {
                Button(kind == .fruit ? "新增测试商品" : "新增测试位置") {
                    let event = SyncEvent(tableName: kind.rawValue, syncId: UUID().uuidString.lowercased(), rowVersion: 0, operation: "UPSERT", payload: ["default_unit": .string("件"), "enabled": .number(1)])
                    selected = TXBasicEditorSelection(event: event, scope: scope)
                }
            }
            if model.v33HasPending {
                Button("核对上次操作") {
                    Task { message = await model.v33Recover(); await verifyAccess() }
                }.disabled(model.isBusy || model.isLedgerV33Busy)
            }
            if !message.isEmpty {
                Text(message).font(.caption).foregroundColor(.secondary)
            }
        }
    }

    @ViewBuilder private func directoryRow(_ row: TXBasicDirectoryEntry) -> some View {
        if let event = model.snapshot.records[kind.rawValue + "|" + row.id] {
            TXBasicDirectoryRowView(kind: kind, entry: row, event: event,
                editable: editable && event.rowVersion > 0 && row.name != "总价") {
                selected = TXBasicEditorSelection(event: event, scope: scope)
            }
        } else {
            Text(row.name)
        }
    }

    private var directoryList: some View {
        List {
            statusSection
            Section("共 \(rows.count) 条") {
                if rows.isEmpty { Text("没有匹配资料").foregroundColor(.secondary) }
                ForEach(rows) { row in directoryRow(row) }
            }
        }
    }

    private func save(_ edit: TXBasicEdit, selection: TXBasicEditorSelection) {
        selected = nil
        Task {
            let original = selection.scope
            let result = await model.v36SubmitBasic(edit, expectedScope: original)
            guard original == scope else { return }
            message = result
            await verifyAccess()
        }
    }

    var body: some View {
        directoryList
            .searchable(text: $query, prompt: "搜索名称")
            .navigationTitle(kind.title)
            .navigationBarTitleDisplayMode(.inline)
            .task(id: accessKey) { await verifyAccess() }
            .onChange(of: scope) { _ in selected = nil; message = ""; authorizedScope = ""; createScope = "" }
            .sheet(item: $selected) { item in
                TXBasicRecordEditor(selection: item) { edit in save(edit, selection: item) }
            }
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        Task { await model.refresh(); await verifyAccess() }
                    } label: { Image(systemName: "arrow.clockwise") }
                    .disabled(model.isBusy)
                }
            }
    }
}

private struct TXBasicDirectoryRowView: View {
    let kind: TXBasicDirectoryKind
    let entry: TXBasicDirectoryEntry
    let event: SyncEvent
    let editable: Bool
    let onEdit: () -> Void

    private var subtitle: String {
        switch kind {
        case .fruit: return event.payload["default_unit"]?.text ?? ""
        case .store:
            let address = event.payload["address"]?.text ?? ""
            let start = event.payload["default_start_time"]?.text ?? ""
            let end = event.payload["default_end_time"]?.text ?? ""
            let hours = start.isEmpty || end.isEmpty ? "" : start + "–" + end
            return [address, hours].filter { !$0.isEmpty }.joined(separator: " · ")
        case .partner: return ""
        }
    }

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 3) {
                Text(entry.name)
                if !subtitle.isEmpty { Text(subtitle).font(.caption).foregroundColor(.secondary) }
            }
            Spacer()
            if TXFundDataAdapter.number(event.payload["enabled"]) == 0 {
                Text("已停用").font(.caption).foregroundColor(.orange)
            }
            if editable { Button("编辑", action: onEdit).font(.caption).buttonStyle(.borderless) }
        }
    }
}
