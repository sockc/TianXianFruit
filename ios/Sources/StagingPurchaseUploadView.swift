import SwiftUI

/// Same server as Android, but limited to a dedicated USER account which
/// can see exactly one iOS_TEST_ book. Login is remembered in a separate
/// ThisDeviceOnly Keychain slot; business mutations always require a tap.
struct StagingPurchaseUploadView: View {
    @EnvironmentObject private var model: LedgerViewModel
    @StateObject private var coordinator = StagingPurchaseCoordinator()
    @State private var username = ""
    @State private var password = ""
    @State private var showDetails = false

    private var testBook: CloudBook? {
        guard let book = model.selectedBook,
              book.isAvailable, book.name.hasPrefix(TestWritePolicy.prefix) else {
            return nil
        }
        return book
    }

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 12) {
                Label("V0.3.3 · 测试账本采购同步",
                      systemImage: "arrow.triangle.2.circlepath")
                    .font(.headline)

                Text("iOS 与 Android 使用同一测试账本。首次授权后自动恢复登录；正常采购直接点“上传测试账本”，不再手输账本 ID 或逐笔填写验收表。")
                    .font(.caption).foregroundColor(.secondary)

                if let book = testBook {
                    VStack(alignment: .leading, spacing: 5) {
                        Label(book.name, systemImage: "checkmark.shield")
                            .font(.subheadline.bold())
                            .foregroundColor(.green)
                        Text("固定服务器：" + StagingPurchasePolicy.productionURL)
                            .font(.caption2).foregroundColor(.secondary)
                        Text("仅该测试账本允许采购写入；正式账本始终只读")
                            .font(.caption2).foregroundColor(.secondary)
                    }
                    .padding(12)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Color(UIColor.secondarySystemGroupedBackground),
                                in: RoundedRectangle(cornerRadius: 12))
                } else {
                    Label("先在首页切换到独立 iOS_TEST_ 测试账本",
                          systemImage: "lock.shield")
                        .foregroundColor(.orange)
                }

                if testBook != nil && !coordinator.connected {
                    VStack(alignment: .leading, spacing: 9) {
                        Text("首次验证测试账号").font(.subheadline.bold())
                        TextField("测试账号", text: $username)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .textFieldStyle(.roundedBorder)
                        SecureField("测试密码", text: $password)
                            .textFieldStyle(.roundedBorder)
                        Button {
                            let name = username
                            let secret = password
                            password = ""
                            Task {
                                await coordinator.connect(
                                    username: name, password: secret)
                            }
                        } label: {
                            Label("登录测试账本并记住会话",
                                  systemImage: "checkmark.shield")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.borderedProminent)
                        .disabled(coordinator.busy || username.isEmpty ||
                                  password.isEmpty)
                        Text("只需首次登录；iPhone 钥匙串只保存此测试账号的会话令牌，不保存密码。服务端必须确认该普通账号只能看到这一个测试账本。令牌失效才需重新登录。")
                            .font(.caption2).foregroundColor(.secondary)
                    }
                    .padding(12)
                    .background(Color(UIColor.secondarySystemGroupedBackground),
                                in: RoundedRectangle(cornerRadius: 12))
                } else if coordinator.connected {
                    HStack(alignment: .center, spacing: 8) {
                        Label("测试账号已授权 · " + coordinator.stageName,
                              systemImage: "checkmark.circle.fill")
                            .font(.caption).foregroundColor(.green)
                        Spacer()
                        Button("更换账号") {
                            coordinator.forgetTestLogin()
                            password = ""
                        }
                        .font(.caption)
                    }
                }

                if coordinator.busy {
                    HStack(spacing: 8) {
                        ProgressView()
                        Text("正在核对测试账本或同步商品…")
                            .font(.caption).foregroundColor(.secondary)
                    }
                }
                if let message = coordinator.message {
                    Label(message, systemImage: "info.circle")
                        .font(.caption)
                        .foregroundColor(.orange)
                }

                Text("采购草稿 · " + String(coordinator.drafts.count))
                    .font(.headline)
                if coordinator.drafts.isEmpty {
                    Text("尚无本机采购草稿。请到“写入测试实验室”新增采购单。")
                        .font(.caption).foregroundColor(.secondary)
                }
                ForEach(coordinator.drafts) { draft in
                    purchaseCard(draft)
                }
                DisclosureGroup("同步异常时才需要的核对说明",
                                isExpanded: $showDetails) {
                    Text("每张采购单均有固定 sync_id。订单先回读确认，再逐条上传商品。如果网络断开或结果不确定，iOS 不会盲目重发可能已接收的商品；可点“只读核对云端”定位缺失或关联异常。测试账号权限仅能证明当前账户的可见账本，不能代替服务器实际的跨账本写入权限校验。")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
                .font(.caption.bold())
                .padding(.top, 6)
            }
            .padding(12)
        }
        .navigationBarTitle("采购同步", displayMode: .inline)
        .onAppear(perform: openSession)
        .onDisappear {
            coordinator.open(account: nil, book: nil)
        }
        .onChange(of: model.selectedBookID) { _ in
            openSession()
        }
    }

    private func openSession() {
        coordinator.open(account: model.account?.username,
                         book: model.selectedBook)
        password = ""
        if username.isEmpty { username = model.account?.username ?? "" }
        Task { await coordinator.restoreRemembered() }
    }

    @ViewBuilder
    private func purchaseCard(_ draft: TestDraft) -> some View {
        let current = coordinator.jobs.first { $0.id == draft.id }
        let audit = coordinator.audits[draft.id]
        VStack(alignment: .leading, spacing: 9) {
            HStack {
                VStack(alignment: .leading, spacing: 3) {
                    Text(draft.date + " · " +
                         (draft.purchaseItems.map {
                             String($0.count) + " 种商品"
                         } ?? (draft.fields["fruit_name"] ?? "采购")))
                        .font(.subheadline.bold())
                    Text(draft.summary).font(.caption)
                }
                Spacer()
                Image(systemName: audit?.complete == true ||
                      (audit == nil && current?.state == .complete)
                      ? "checkmark.circle.fill" : "doc.badge.clock")
                    .foregroundColor(audit?.complete == true ? .green : .orange)
            }

            if let current {
                Text(current.state.title)
                    .font(.caption2).foregroundColor(.secondary)
                if current.state == .complete {
                    Text("云端订单及全部商品已确认，无需重复上传")
                        .font(.caption).foregroundColor(.green)
                }
            } else {
                Text("本机草稿 · 可上传同一测试账本")
                    .font(.caption2).foregroundColor(.secondary)
            }

            if coordinator.connected {
                if current == nil {
                    Button {
                        Task { await coordinator.prepareAndUpload(draftID: draft.id) }
                    } label: {
                        Label("上传测试账本", systemImage: "arrow.up.doc")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(coordinator.busy)
                } else if current?.state == .ready ||
                          current?.state == .orderConfirmed {
                    Button {
                        Task { await coordinator.upload(draftID: draft.id) }
                    } label: {
                        Label("继续同步未发送的商品",
                              systemImage: "arrow.up.doc")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                    .disabled(coordinator.busy)
                }
                if current != nil {
                    Button {
                        Task { await coordinator.checkServer(draftID: draft.id) }
                    } label: {
                        Label("只读核对云端",
                              systemImage: "arrow.clockwise")
                            .font(.caption)
                    }
                    .disabled(coordinator.busy)
                }
            }

            if let audit {
                Text(audit.headline).font(.caption)
                    .foregroundColor(audit.complete ? .green : .orange)
                if !audit.complete {
                    ForEach(audit.issues) { issue in
                        Text("• " + issue.description)
                            .font(.caption2).foregroundColor(.orange)
                    }
                }
            }
            if let current, current.state == .needsReview {
                Text(current.lastMessage).font(.caption)
                    .foregroundColor(.orange)
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(UIColor.secondarySystemGroupedBackground),
                    in: RoundedRectangle(cornerRadius: 12))
    }
}
