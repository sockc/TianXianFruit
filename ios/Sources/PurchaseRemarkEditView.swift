import SwiftUI

/// Enter from the *read-only* cloud purchase order detail. Only an explicitly
/// verified iOS_TEST_ session may issue a remark-only change.
struct PurchaseRemarkEditView: View {
    @EnvironmentObject private var model: LedgerViewModel
    @StateObject private var coordinator = PurchaseRemarkEditCoordinator()
    @State private var username = ""
    @State private var password = ""
    @State private var remark = ""
    @State private var confirm = false

    let order: PurchaseOrderRow
    let bookID: String

    private var eligible: Bool {
        model.selectedBookID == bookID &&
        model.selectedBook?.isAvailable == true &&
        model.selectedBook?.name.hasPrefix(TestWritePolicy.prefix) == true
    }

    private var current: SyncEvent? {
        guard eligible else { return nil }
        return model.snapshot.records["purchase_order|" + order.id]
    }

    private var pending: [PurchaseRemarkEditJob] {
        coordinator.jobs.filter { $0.original.syncId == order.id }
    }

    private var canSubmit: Bool {
        eligible && coordinator.connected && !coordinator.busy &&
        current != nil &&
        remark.trimmingCharacters(in: .whitespacesAndNewlines) !=
            (current?.payload["remark"]?.text ?? "")
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Label("V0.3.3 · 测试采购备注更正",
                      systemImage: "pencil.and.list.clipboard")
                    .font(.headline)
                Text("只修改备注，不改变采购金额、商品、采购人、库存或结算。任何冲突、断网或结果不明都不会自动重发。")
                    .font(.caption).foregroundColor(.secondary)
                if !eligible {
                    Label("仅 iOS_TEST_ 测试账本允许修改，正式账本只读",
                          systemImage: "lock.shield")
                        .foregroundColor(.orange)
                } else {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(order.date + " · " + order.buyer)
                            .font(.subheadline.bold())
                        Text("采购合计 " +
                             order.totalCost.formatted(.currency(code: "CNY")))
                            .font(.subheadline)
                        Text("原云端版本：" + String(current?.rowVersion ?? 0))
                            .font(.caption).foregroundColor(.secondary)
                        Text("商品明细与金额需要完全核对后才能提交备注更正")
                            .font(.caption2).foregroundColor(.secondary)
                    }
                    .padding(12)
                    .background(Color(UIColor.secondarySystemGroupedBackground),
                                in: RoundedRectangle(cornerRadius: 12))

                    if !coordinator.connected {
                        VStack(alignment: .leading, spacing: 8) {
                            Text("首次验证专用测试账号").font(.subheadline.bold())
                            TextField("测试账号", text: $username)
                                .autocorrectionDisabled()
                                .textInputAutocapitalization(.never)
                            SecureField("密码", text: $password)
                            Button {
                                Task {
                                    await coordinator.login(
                                        username: username, password: password)
                                    password = ""
                                }
                            } label: {
                                Label("验证测试账本", systemImage: "checkmark.shield")
                            }
                            .disabled(coordinator.busy || username.isEmpty ||
                                      password.isEmpty)
                            .buttonStyle(.borderedProminent)
                            Text("可复用之前已登录的测试账号令牌，不保存密码。")
                                .font(.caption2).foregroundColor(.secondary)
                        }
                        .padding(12)
                        .background(Color(UIColor.secondarySystemGroupedBackground),
                                    in: RoundedRectangle(cornerRadius: 12))
                    } else {
                        Label("测试账号已验证", systemImage: "checkmark.shield.fill")
                            .font(.caption).foregroundColor(.green)
                    }

                    VStack(alignment: .leading, spacing: 8) {
                        Text("采购备注").font(.subheadline.bold())
                        TextField("填写备注，最多 200 字", text: $remark)
                            .textFieldStyle(.roundedBorder)
                            .onChange(of: remark) { next in
                                if next.count > 200 { remark = String(next.prefix(200)) }
                            }
                        HStack {
                            Text("只更改文字；不修改成本或商品明细")
                                .font(.caption2).foregroundColor(.secondary)
                            Spacer()
                            Text("\(remark.count)/200")
                                .font(.caption2).foregroundColor(.secondary)
                        }
                        Button {
                            confirm = true
                        } label: {
                            Label("保存并提交测试备注更正",
                                  systemImage: "checkmark.shield")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.borderedProminent)
                        .disabled(!canSubmit)
                    }
                    .padding(12)
                    .background(Color(UIColor.secondarySystemGroupedBackground),
                                in: RoundedRectangle(cornerRadius: 12))
                    if coordinator.busy {
                        HStack {
                            ProgressView()
                            Text("正在检查云端订单、商品与版本…")
                                .font(.caption)
                        }
                    }
                    if let message = coordinator.message {
                        Text(message).font(.caption).foregroundColor(.orange)
                    }
                    if !pending.isEmpty {
                        Text("本机更正记录").font(.headline)
                        ForEach(pending) { job in
                            VStack(alignment: .leading, spacing: 8) {
                                Text(job.state.title)
                                    .font(.caption.bold())
                                    .foregroundColor(job.state == .complete ? .green : .orange)
                                Text(job.note).font(.caption)
                                if job.state == .ready {
                                    Button("取消未发送的本机更正") {
                                        coordinator.cancelUnsent(id: job.id)
                                    }
                                    .disabled(coordinator.busy)
                                } else {
                                    Button {
                                        Task { await coordinator.inspect(id: job.id) }
                                    } label: {
                                        Label("只读核对云端（不重发）",
                                              systemImage: "checkmark.shield")
                                    }
                                    .disabled(!coordinator.connected || coordinator.busy)
                                }
                            }
                            .padding(12)
                            .background(Color(UIColor.secondarySystemGroupedBackground),
                                        in: RoundedRectangle(cornerRadius: 12))
                        }
                    }
                    Button {
                        Task { await model.refresh() }
                    } label: {
                        Label("刷新只读采购记录", systemImage: "arrow.clockwise")
                    }
                    .disabled(model.isBusy)
                }
            }
            .padding(12)
        }
        .navigationBarTitle("测试采购备注更正", displayMode: .inline)
        .alert(isPresented: $confirm) {
            Alert(
                title: Text("提交测试备注更正？"),
                message: Text("这会更新 iOS_TEST_ 测试账本的一张云端采购订单；商品和金额不变，出网后不允许重复发送。"),
                primaryButton: .cancel(Text("取消")),
                secondaryButton: .default(Text("确认提交"), action: {
                    guard canSubmit, let source = current else { return }
                    let target = remark
                    Task {
                        await coordinator.submit(original: source, remark: target)
                        await model.refresh()
                    }
                })
            )
        }
        .onAppear(perform: open)
        .onChange(of: model.selectedBookID) { _ in open() }
        .onChange(of: model.account?.username) { _ in open() }
    }

    private func open() {
        coordinator.open(account: model.account?.username,
                         book: model.selectedBook)
        username = model.account?.username ?? ""
        password = ""
        remark = current?.payload["remark"]?.text ?? ""
        Task { await coordinator.restore() }
    }
}
