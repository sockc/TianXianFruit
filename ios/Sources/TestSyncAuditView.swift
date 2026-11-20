import SwiftUI

/// Read-only dashboard of isolated test-book upload and correction history.
/// One authenticated pull is shared across all categories.
struct TestSyncAuditView: View {
    @EnvironmentObject private var model: LedgerViewModel
    @StateObject private var coordinator = TestSyncAuditCoordinator()
    @State private var username = ""
    @State private var password = ""
    @State private var selection: Filter = .attention

    private enum Filter: String, CaseIterable, Identifiable {
        case attention = "需核对"
        case all = "全部"
        case current = "已匹配"
        case changed = "后续变化"
        var id: String { rawValue }
    }

    private var testBook: CloudBook? {
        guard let book = model.selectedBook, book.isAvailable,
              book.name.hasPrefix(TestWritePolicy.prefix) else { return nil }
        return book
    }

    private var visible: [TestSyncAuditItem] {
        guard let report = coordinator.report else { return [] }
        switch selection {
        case .all: return report.items
        case .attention: return report.items.filter(\.needsAttention)
        case .current: return report.items.filter {
            $0.cloudStatus == .confirmed
        }
        case .changed: return report.items.filter {
            $0.cloudStatus == .newerRevision
        }
        }
    }

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 12) {
                Label("V0.3.4 · 测试同步核对中心",
                      systemImage: "checklist")
                    .font(.headline)
                Text("用一次云端完整回读检查本机四类测试记录：采购新增、营业新增、营业金额更正及采购备注更正。只读，不会发送或补发任何财务数据。")
                    .font(.caption).foregroundColor(.secondary)

                if let testBook {
                    Label(testBook.name, systemImage: "checkmark.shield.fill")
                        .foregroundColor(.green)
                        .font(.subheadline.bold())
                } else {
                    Label("仅 iOS_TEST_ 独立测试账本可使用此核对中心",
                          systemImage: "lock.shield")
                        .foregroundColor(.orange)
                }

                if testBook != nil && !coordinator.connected {
                    VStack(alignment: .leading, spacing: 9) {
                        Text("测试账号验证").font(.subheadline.bold())
                        TextField("专用测试账号", text: $username)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                        SecureField("测试账号密码", text: $password)
                        Button {
                            Task {
                                await coordinator.login(
                                    username: username, password: password)
                                password = ""
                            }
                        } label: {
                            Label("验证并只读核对", systemImage: "checkmark.shield")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.borderedProminent)
                        .disabled(coordinator.busy || username.isEmpty ||
                                  password.isEmpty)
                        Text("已有测试账号凭据会自动恢复；不用重复填写账号、账本 ID 或密码。")
                            .font(.caption2).foregroundColor(.secondary)
                    }
                    .padding(12)
                    .background(Color(UIColor.secondarySystemGroupedBackground),
                                in: RoundedRectangle(cornerRadius: 12))
                } else if coordinator.connected {
                    HStack {
                        Label("专用测试账号已验证",
                              systemImage: "checkmark.shield.fill")
                            .foregroundColor(.green).font(.caption)
                        Spacer()
                        Button {
                            Task { await coordinator.refresh() }
                        } label: {
                            Label("一键复核", systemImage: "arrow.clockwise")
                        }
                        .font(.caption.bold())
                        .disabled(coordinator.busy)
                    }
                }

                if coordinator.busy {
                    HStack {
                        ProgressView()
                        Text("正在一次性读取并核对全部测试记录…")
                            .font(.caption).foregroundColor(.secondary)
                    }
                }
                if let message = coordinator.message {
                    Text(message).font(.caption)
                        .foregroundColor(.secondary)
                }

                if let report = coordinator.report, testBook != nil,
                   report.bookID == testBook?.id {
                    HStack(spacing: 7) {
                        counter("总任务", value: report.items.count)
                        counter("已匹配", value: report.confirmed)
                        counter("后续变化", value: report.newer)
                        counter("需核对", value: report.attention)
                    }
                    Text("最近云端核对：" +
                         report.checkedAt.formatted(date: .abbreviated,
                                                    time: .shortened))
                        .font(.caption2).foregroundColor(.secondary)
                    Picker("核对结果", selection: $selection) {
                        ForEach(Filter.allCases) { option in
                            Text(option.rawValue).tag(option)
                        }
                    }
                    .pickerStyle(.segmented)
                    if visible.isEmpty {
                        Text(report.items.isEmpty
                             ? "本机没有此测试账号和账本的历史上传任务"
                             : "当前筛选下暂无任务")
                            .font(.caption).foregroundColor(.secondary)
                            .padding(.vertical, 10)
                    }
                    ForEach(visible) { item in
                        VStack(alignment: .leading, spacing: 7) {
                            HStack(alignment: .top) {
                                Text(item.label).font(.subheadline.bold())
                                Spacer(minLength: 6)
                                Image(systemName: item.cloudStatus.requiresAttention
                                    ? "exclamationmark.circle" : "checkmark.shield")
                                    .foregroundColor(item.cloudStatus.requiresAttention
                                        ? .orange : .green)
                            }
                            Text("本机： " + item.localState)
                                .font(.caption).foregroundColor(.secondary)
                            Text("云端： " + item.cloudStatus.title)
                                .font(.caption.bold())
                                .foregroundColor(item.cloudStatus.requiresAttention
                                                 ? .orange : .green)
                            Text(item.details).font(.caption2)
                                .foregroundColor(.secondary)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(12)
                        .background(Color(UIColor.secondarySystemGroupedBackground),
                                    in: RoundedRectangle(cornerRadius: 12))
                    }
                    VStack(alignment: .leading, spacing: 6) {
                        Label("核对范围与说明", systemImage: "info.circle")
                            .font(.caption.bold())
                        Text("“云端已有后续版本”不等于原先上传失败，也不保证新内容符合预期。发现删除、缺失或金额不符时，回原功能核对，切勿重复提交。")
                        Text("这里显示的是服务端状态，不能判断安卓设备是否已经同步。所有正式经营账本仍然只读。")
                    }
                    .font(.caption2).foregroundColor(.secondary)
                }
            }
            .padding(12)
        }
        .navigationBarTitle("测试同步核对", displayMode: .inline)
        .onAppear(perform: open)
        .onChange(of: model.selectedBookID) { _ in open() }
        .onChange(of: model.account?.username) { _ in open() }
    }

    private func counter(_ label: String, value: Int) -> some View {
        VStack(spacing: 5) {
            Text(String(value)).font(.title3.bold())
                .monospacedDigit()
            Text(label).font(.caption2).foregroundColor(.secondary)
                .lineLimit(1)
                .minimumScaleFactor(0.75)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 10)
        .background(Color(UIColor.secondarySystemGroupedBackground),
                    in: RoundedRectangle(cornerRadius: 10))
    }

    private func open() {
        coordinator.open(account: model.account?.username,
                         book: model.selectedBook)
        if username.isEmpty { username = model.account?.username ?? "" }
        password = ""
        Task { await coordinator.restore() }
    }
}
