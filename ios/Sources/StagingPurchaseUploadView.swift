import SwiftUI

/// Real network pushes are exclusively enabled here, not in regular business,
/// purchase, customer, settlement or overnight pages. The test backend must
/// attest isolated storage BEFORE it receives staging credentials.
struct StagingPurchaseUploadView: View {
    @EnvironmentObject private var model: LedgerViewModel
    @StateObject private var coordinator = StagingPurchaseCoordinator()
    @State private var endpoint = ""
    @State private var stageUsername = ""
    @State private var stagePassword = ""
    @State private var typedBookID = ""
    @State private var acknowledged = false
    @State private var confirmUploadID: String?
    @State private var stageInfoExpanded = false

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 13) {
                Label("V0.2.1 · 采购真实同步测试", systemImage: "shield.lefthalf.filled")
                    .font(.headline)
                    .foregroundColor(.orange)
                Text("只向经过隔离证明的测试服务端提交采购订单和商品明细。正式服务器 sync.830888.xyz 被硬性禁止。营业、利润和结算始终只读。")
                    .font(.caption).foregroundColor(.secondary)

                if let book = model.selectedBook {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("草稿所属测试账本：" + book.name)
                            .font(.subheadline.bold())
                        Text("完整账本 ID：" + book.id)
                            .font(.caption2).foregroundColor(.secondary)
                            .textSelection(.enabled)
                        if !book.name.hasPrefix(TestWritePolicy.prefix) {
                            Text("当前不是 iOS_TEST_ 测试账本，不能建立写入连接。")
                                .font(.caption).foregroundColor(.red)
                        }
                    }
                }

                DisclosureGroup("独立测试环境必须满足的条件", isExpanded: $stageInfoExpanded) {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("① 独立 HTTPS 域名和独立数据库，不是生产域名的代理。")
                        Text("② 公开 GET /api/v1/ios-test/attestation 返回隔离环境证明。")
                        Text("③ 与草稿匹配的测试账本 ID、商品/采购人 sync_id 及可编辑角色。")
                        Text("④ 测试 Android 必须连接相同的独立服务器，不要用正式 Android 账号回读。")
                    }
                    .font(.caption).foregroundColor(.secondary)
                    .padding(.top, 5)
                }
                .font(.caption.bold())

                if !coordinator.connected {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("连接独立测试服务器").font(.headline)
                        TextField("例如 https://staging.example.com", text: $endpoint)
                            .textInputAutocapitalization(.never).autocorrectionDisabled()
                            .keyboardType(.URL)
                            .font(.subheadline)
                        TextField("仅测试服务器账号", text: $stageUsername)
                            .textInputAutocapitalization(.never).autocorrectionDisabled()
                        SecureField("仅测试服务器密码", text: $stagePassword)
                        TextField("再次输入完整测试账本 ID", text: $typedBookID)
                            .textInputAutocapitalization(.never).autocorrectionDisabled()
                        Toggle("我确认服务端、数据库与生产环境完全隔离", isOn: $acknowledged)
                            .font(.caption)
                        Button {
                            Task {
                                await coordinator.connect(
                                    endpoint: endpoint, username: stageUsername,
                                    password: stagePassword, repeatedBookID: typedBookID,
                                    acknowledged: acknowledged)
                                stagePassword = ""
                            }
                        } label: {
                            if coordinator.busy {
                                ProgressView().frame(maxWidth: .infinity)
                            } else {
                                Label("验证测试环境并登录", systemImage: "checkmark.shield")
                                    .frame(maxWidth: .infinity)
                            }
                        }
                        .buttonStyle(.borderedProminent)
                        .disabled(coordinator.busy || !acknowledged ||
                                  model.selectedBook?.id != typedBookID ||
                                  endpoint.isEmpty || stageUsername.isEmpty || stagePassword.isEmpty)
                        Text("先核验服务端隔离证明，再发送测试账号密码。若测试服务端还没有证明接口，将停留在锁定状态，不发送采购数据。")
                            .font(.caption2).foregroundColor(.secondary)
                    }
                    .padding(12)
                    .background(Color(UIColor.secondarySystemGroupedBackground),
                                in: RoundedRectangle(cornerRadius: 12))
                } else {
                    Label("已连接：" + coordinator.stageName,
                          systemImage: "checkmark.shield.fill")
                        .font(.caption)
                        .foregroundColor(.green)
                    Button("断开测试会话") {
                        coordinator.open(account: model.account?.username,
                                         book: model.selectedBook)
                        acknowledged = false
                        typedBookID = ""
                        confirmUploadID = nil
                    }
                    .font(.caption)
                }

                if let message = coordinator.message {
                    Label(message, systemImage: "info.circle")
                        .font(.caption)
                        .foregroundColor(.orange)
                }

                Text("已录入的本机采购草稿").font(.headline)
                if coordinator.drafts.isEmpty {
                    Text("此测试账本暂无采购草稿。请先返回“写入测试实验室”录入并保存。")
                        .font(.caption).foregroundColor(.secondary)
                }
                ForEach(coordinator.drafts) { draft in
                    let current = coordinator.jobs.first(where: { $0.id == draft.id })
                    VStack(alignment: .leading, spacing: 9) {
                        HStack(alignment: .top) {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(draft.date + " · " + (draft.fields["fruit_name"] ?? "商品"))
                                    .font(.subheadline.bold())
                                Text(draft.summary).font(.caption)
                            }
                            Spacer()
                            Image(systemName: current?.state == .complete ?
                                  "checkmark.circle.fill" : "doc.badge.clock")
                                .foregroundColor(current?.state == .complete ? .green : .orange)
                        }
                        Text(current?.state.title ?? "未准备上传")
                            .font(.caption2).foregroundColor(.secondary)
                        if let current {
                            Text(current.lastMessage).font(.caption2)
                                .foregroundColor(.secondary)
                            Text("测试服务端：" + current.stageHost)
                                .font(.caption2).foregroundColor(.secondary)
                                .lineLimit(2)
                        }
                        if coordinator.connected {
                            if current == nil {
                                Button("① 核验采购人/商品并准备订单") {
                                    Task { await coordinator.prepare(draftID: draft.id) }
                                }
                                .disabled(coordinator.busy)
                            } else if current?.state == .ready ||
                                      current?.state == .orderConfirmed {
                                Button(current?.state == .ready ?
                                       "② 确认上传订单和明细" : "③ 继续上传剩余商品明细") {
                                    confirmUploadID = draft.id
                                }
                                .disabled(coordinator.busy)
                                .foregroundColor(.orange)
                            } else if current?.state == .needsReview ||
                                      current?.state == .sendingOrder ||
                                      current?.state == .sendingItem {
                                Button("检查服务器是否已经接收，绝不自动重传") {
                                    Task { await coordinator.checkServer(draftID: draft.id) }
                                }
                                .disabled(coordinator.busy)
                            } else if current?.state == .complete {
                                Text("已由测试服务器回读确认，等待 Android 测试客户端读取核对")
                                    .font(.caption).foregroundColor(.green)
                            }
                        }
                    }
                    .padding(12)
                    .background(Color(UIColor.secondarySystemGroupedBackground),
                                in: RoundedRectangle(cornerRadius: 12))
                }
                Text("本页任何上传都需要先连接独立测试环境，再核验采购人/商品，最后对每一条草稿单独确认。测试服务器没有隔离证明时上传功能不可用。")
                    .font(.caption2).foregroundColor(.secondary)
            }
            .padding(12)
        }
        .navigationBarTitle("隔离测试采购同步", displayMode: .inline)
        .alert(item: Binding(
            get: { confirmUploadID.map { PendingUpload(id: $0) } },
            set: { confirmUploadID = $0?.id }
        )) { selection in
            Alert(
                title: Text("仅上传到独立测试服务器"),
                message: Text("将提交一笔采购订单及其商品明细，不会写入正式服务器。服务器响应不确定时需要人工核对，绝不自动重传。"),
                primaryButton: .destructive(Text("确认测试上传")) {
                    confirmUploadID = nil
                    Task { await coordinator.upload(draftID: selection.id) }
                },
                secondaryButton: .cancel(Text("取消")) {
                    confirmUploadID = nil
                })
        }
        .onAppear {
            coordinator.open(account: model.account?.username,
                             book: model.selectedBook)
        }
        .onChange(of: model.selectedBookID) { _ in
            coordinator.open(account: model.account?.username,
                             book: model.selectedBook)
            typedBookID = ""
            acknowledged = false
            stagePassword = ""
            confirmUploadID = nil
        }
    }
}

private struct PendingUpload: Identifiable {
    let id: String
}
