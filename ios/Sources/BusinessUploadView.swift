import SwiftUI

/// This screen is never reachable as a writer from a non-iOS_TEST_ ledger.
/// Sending one test sale is an explicit action; no auto-sync of sale drafts.
struct BusinessUploadView: View {
    @EnvironmentObject private var model: LedgerViewModel
    @StateObject private var coordinator = BusinessUploadCoordinator()
    @State private var username = ""
    @State private var password = ""
    @State private var showSafety = false

    private var allowedBook: CloudBook? {
        guard let book = model.selectedBook, book.isAvailable,
              book.name.hasPrefix(TestWritePolicy.prefix) else { return nil }
        return book
    }

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 12) {
                Label("V0.3.1 · 测试营业上传", systemImage: "lock.shield")
                    .font(.headline)
                Text("只向独立 iOS_TEST_ 账本提交一笔新的营业记录。与 Android 同日同位置重复、已删除历史或本机提交状态不确定时，拒绝再次发送。")
                    .font(.caption).foregroundColor(.secondary)

                if let book = allowedBook {
                    VStack(alignment: .leading, spacing: 6) {
                        Label(book.name, systemImage: "checkmark.shield.fill")
                            .foregroundColor(.green)
                        Text("固定服务端：" + StagingPurchasePolicy.productionURL)
                            .font(.caption2).foregroundColor(.secondary)
                        Text("正式账本营业、利润分配和资金结算始终禁止 iOS 写入")
                            .font(.caption2).foregroundColor(.secondary)
                    }
                    .padding(12)
                    .background(Color(UIColor.secondarySystemGroupedBackground),
                                in: RoundedRectangle(cornerRadius: 11))
                } else {
                    Label("请先选择名称以 iOS_TEST_ 开头的测试账本",
                          systemImage: "lock.circle")
                        .foregroundColor(.orange)
                }

                if allowedBook != nil && !coordinator.connected {
                    VStack(alignment: .leading, spacing: 9) {
                        Text("首次授权测试账号").font(.subheadline.bold())
                        TextField("专用测试账号", text: $username)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                        SecureField("测试账号密码", text: $password)
                        Button {
                            Task { await coordinator.login(
                                username: username, password: password) }
                        } label: {
                            Label("登录并验证唯一测试账本",
                                  systemImage: "checkmark.shield")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.borderedProminent)
                        .disabled(coordinator.busy || username.isEmpty || password.isEmpty)
                        Text("仅第一次验证；密码不会保存，后续由独立钥匙串恢复测试会话。")
                            .font(.caption2).foregroundColor(.secondary)
                    }
                    .padding(12)
                    .background(Color(UIColor.secondarySystemGroupedBackground),
                                in: RoundedRectangle(cornerRadius: 11))
                } else if coordinator.connected {
                    HStack {
                        Label("测试账号已验证", systemImage: "checkmark.circle.fill")
                            .foregroundColor(.green).font(.caption)
                        Spacer()
                        Button("更换账号") {
                            coordinator.forget()
                            password = ""
                        }.font(.caption)
                    }
                }

                if coordinator.busy {
                    HStack {
                        ProgressView()
                        Text("正在核对测试账本…").font(.caption)
                    }
                }
                if let message = coordinator.message {
                    Label(message, systemImage: "info.circle")
                        .font(.caption).foregroundColor(.orange)
                }
                Text("本机营业草稿 · \(coordinator.drafts.count)")
                    .font(.headline)
                if coordinator.drafts.isEmpty {
                    Text("请先进入“写入测试实验室”，选择营业草稿，填写后保存到本机。")
                        .font(.caption).foregroundColor(.secondary)
                }
                ForEach(coordinator.drafts) { draft in
                    let job = coordinator.jobs.first(where: { $0.id == draft.id })
                    VStack(alignment: .leading, spacing: 8) {
                        Text(draft.date + " · " +
                             (draft.fields["store_name"] ?? "未指定位置"))
                            .font(.subheadline.bold())
                        Text(draft.summary).font(.caption)
                        if let job {
                            Text(job.state.title)
                                .font(.caption.bold())
                                .foregroundColor(job.state == .complete ? .green : .orange)
                            Text(job.note).font(.caption2).foregroundColor(.secondary)
                        }
                        if job == nil || job?.state == .ready {
                            Button {
                                Task { await coordinator.send(draftID: draft.id) }
                            } label: {
                                Label("上传测试账本", systemImage: "arrow.up.circle")
                                    .frame(maxWidth: .infinity)
                            }
                            .buttonStyle(.borderedProminent)
                            .disabled(!coordinator.connected || coordinator.busy ||
                                      allowedBook == nil)
                        } else {
                            Button {
                                Task { await coordinator.inspect(draftID: draft.id) }
                            } label: {
                                Label("只读核对云端（不会重发）",
                                      systemImage: "checkmark.shield")
                            }
                            .disabled(!coordinator.connected || coordinator.busy)
                        }
                    }
                    .padding(12)
                    .background(Color(UIColor.secondarySystemGroupedBackground),
                                in: RoundedRectangle(cornerRadius: 12))
                }
                DisclosureGroup("本版限制与金额说明", isExpanded: $showSafety) {
                    Text("沿用 Android 原有营业利润公式：微信＋支付宝＋现金＋收摊库存估值－开摊库存估值－费用。营业源数据中的采购金额固定为 0，不把采购草稿额重复扣减。新老客户计数暂为 0；营业更正需进入独立测试更正页面；不提供删除、凌晨收款、资金结算或利润分配。只允许一笔新营业上云，网络中断后必须先只读核对，不能重复点击发送。")
                        .font(.caption).foregroundColor(.secondary)
                }
            }
            .padding(12)
        }
        .navigationBarTitle("测试营业同步", displayMode: .inline)
        .onAppear(perform: open)
        .onDisappear { coordinator.open(account: nil, book: nil) }
        .onChange(of: model.selectedBookID) { _ in open() }
    }

    private func open() {
        coordinator.open(account: model.account?.username, book: model.selectedBook)
        if username.isEmpty { username = model.account?.username ?? "" }
        password = ""
        Task { await coordinator.restore() }
    }
}
