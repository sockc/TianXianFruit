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
    @State private var message = ""
    private var scope: String { (model.account?.username ?? "") + "|" + (model.selectedBookID ?? "") }
    private var editable: Bool { kind != .partner && authorizedScope == scope && model.v34LocalCandidate && model.lastSyncAt != nil && !model.isBusy && !model.isLedgerV33Busy && !model.v33HasPending }
    private func verifyAccess() async {
        let original = scope
        authorizedScope = ""
        guard kind != .partner else { return }
        let allowed = await model.v33Capability("basic")
        if scope == original && !Task.isCancelled { authorizedScope = allowed ? original : "" }
    }
    private var rows: [TXBasicDirectoryEntry] {
        TXBasicDirectoryPolicy.entries(model.snapshot, kind: kind, query: query)
    }

    var body: some View {
        List {
            Section {
                if let book = model.selectedBook {
                    Text(book.name).font(.caption).foregroundColor(.secondary)
                }
                if model.lastSyncAt == nil {
                    Text("尚未同步资料").font(.caption).foregroundColor(.orange)
                } else if model.isUsingCachedAuthorization || model.errorMessage != nil {
                    Text("当前显示缓存资料").font(.caption).foregroundColor(.orange)
                }
                Text(kind == .partner ? "合伙人资料仅查看；权限及利润规则请在安卓端管理。" :
                     authorizedScope == scope ? "支持修改现有资料，新增和删除请在安卓端操作。" : "当前仅查看，服务端基础资料写入未授权。")
                    .font(.caption).foregroundColor(.secondary)
                if model.v33HasPending {
                    Button("核对上次操作") {
                        Task { message = await model.v33Recover(); await verifyAccess() }
                    }.disabled(model.isBusy || model.isLedgerV33Busy)
                }
                if !message.isEmpty { Text(message).font(.caption).foregroundColor(.secondary) }
            }
            Section("共 \(rows.count) 条") {
                if rows.isEmpty {
                    Text("没有匹配资料").foregroundColor(.secondary)
                }
                ForEach(rows) { row in
                    let event = model.snapshot.records[kind.rawValue + "|" + row.id]
                    HStack {
                        Text(row.name)
                        Spacer()
                        if kind == .fruit, let event {
                            Text(event.payload["default_unit"]?.text ?? "").font(.caption).foregroundColor(.secondary)
                            if TXFundDataAdapter.number(event.payload["enabled"]) == 0 {
                                Text("已停用").font(.caption).foregroundColor(.orange)
                            }
                        }
                        if editable, let event, event.rowVersion > 0, row.name != "总价" {
                            Button("编辑") {
                                selected = TXBasicEditorSelection(event: event, scope: scope)
                            }.font(.caption)
                        }
                    }
                }
            }
        }
        .task(id: scope + "|" + String(model.currentCursor) + "|" + String(model.isUsingCachedAuthorization)) {
            await verifyAccess()
        }
        .onChange(of: scope) { _ in selected = nil; message = ""; authorizedScope = "" }
        .sheet(item: $selected) { item in
            TXBasicRecordEditor(selection: item) { edit in
                selected = nil
                Task { message = await model.v36SubmitBasic(edit, expectedScope: item.scope); await verifyAccess() }
            }
        }
        .searchable(text: $query, prompt: "搜索名称")
        .navigationTitle(kind.title)
        .navigationBarTitleDisplayMode(.inline)
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
