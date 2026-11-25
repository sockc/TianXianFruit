import Foundation
import SwiftUI

struct TXBookMember: Decodable, Identifiable {
    let user_id: String
    let username: String
    let display_name: String
    let role: String
    let permission_template: String
    let permissions: [String]
    var id: String { user_id }
}

enum TXMemberReadPolicy {
    static func label(_ permission: String) -> String {
        let labels: [String: String] = [
            "home.business.view": "首页经营查看", "purchase.view": "采购查看",
            "purchase.create": "新增采购", "purchase.edit": "采购修改", "purchase.delete": "采购删除",
            "purchase_activity.view": "采购活动查看", "purchase_plan.edit": "采购计划编辑",
            "business.view": "营业查看", "business.edit": "营业编辑",
            "history.view": "历史查看", "stats.view": "经营统计",
            "profit.view": "利润查看", "profit.edit": "利润编辑",
            "settlement.view": "结算查看", "settlement.edit": "结算编辑",
            "report.view": "报表查看", "basic.edit": "基础资料编辑",
            "member.manage": "成员管理", "book.manage": "账本管理"
        ]
        return labels[permission] ?? permission
    }
}

struct TXBookMemberDirectoryView: View {
    @EnvironmentObject private var model: LedgerViewModel
    @State private var members: [TXBookMember] = []
    @State private var loadedScope = ""
    @State private var loading = false
    @State private var message = ""
    @State private var requestID = UUID()
    private var scope: String { (model.account?.username ?? "") + "|" + (model.selectedBookID ?? "") }
    private var accessKey: String { scope + "|" + String(model.isUsingCachedAuthorization) }

    private func reload() async {
        let original = scope
        let id = UUID()
        requestID = id
        loading = true; members = []; loadedScope = ""; message = ""
        defer { if requestID == id { loading = false } }
        do {
            let rows = try await model.readBookMembers(expectedScope: original)
            guard original == scope, requestID == id, !Task.isCancelled else { return }
            members = rows; loadedScope = original
        } catch {
            guard original == scope, requestID == id, !Task.isCancelled else { return }
            message = error.localizedDescription
        }
    }

    var body: some View {
        List {
            Section {
                Text(model.selectedBook?.name ?? "未选择账本")
                Text("仅展示服务器允许当前账号查看的成员和权限；不代表全部成员。")
                    .font(.caption).foregroundColor(.secondary)
                if loading { ProgressView("读取成员") }
                if !message.isEmpty { Text(message).font(.caption).foregroundColor(.orange) }
            }
            if loadedScope == scope && !model.isUsingCachedAuthorization {
                Section("可见成员 \(members.count) 人") {
                    ForEach(members) { member in
                        NavigationLink(destination: TXBookMemberPermissionView(member: member, expectedScope: scope)) {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(member.display_name.isEmpty ? member.username : member.display_name).font(.subheadline.bold())
                                Text(member.username + " · " + TXBasicDirectoryPolicy.roleLabel(member.role))
                                    .font(.caption).foregroundColor(.secondary)
                            }
                        }
                    }
                }
            }
        }
        .navigationTitle("账本成员")
        .navigationBarTitleDisplayMode(.inline)
        .task(id: accessKey) { await reload() }
        .refreshable { await reload() }
    }
}

private struct TXBookMemberPermissionView: View {
    @EnvironmentObject private var model: LedgerViewModel
    let member: TXBookMember
    let expectedScope: String
    private var scoped: Bool {
        expectedScope == (model.account?.username ?? "") + "|" + (model.selectedBookID ?? "") &&
        !model.isUsingCachedAuthorization
    }
    var body: some View {
        List {
            if scoped {
                Section("账本角色") {
                    Text(TXBasicDirectoryPolicy.roleLabel(member.role))
                    Text("权限模板：" + member.permission_template).font(.caption)
                }
                Section("当前服务器授权") {
                    ForEach(Array(Set(member.permissions)).sorted(), id: \.self) { key in
                        Label(TXMemberReadPolicy.label(key), systemImage: "checkmark.circle")
                    }
                }
                Text("以上为读取时的权限快照，iOS 云端写入仍须通过独立准入校验。")
                    .font(.caption).foregroundColor(.secondary)
            } else {
                Text("账本或账号已变化，请重新读取成员。").foregroundColor(.orange)
            }
        }
        .navigationTitle("成员权限")
        .navigationBarTitleDisplayMode(.inline)
    }
}
