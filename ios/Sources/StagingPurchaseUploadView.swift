import SwiftUI

/// Real network pushes are exclusively enabled here, not in regular business,
/// purchase, customer, settlement or overnight pages. The test backend must
/// limit business writes to the selected test book using a dedicated account.
struct StagingPurchaseUploadView: View {
    @EnvironmentObject private var model: LedgerViewModel
    @StateObject private var coordinator = StagingPurchaseCoordinator()
    @State private var stageUsername = ""
    @State private var stagePassword = ""
    @State private var typedBookID = ""
    @State private var acknowledged = false
    @State private var confirmUploadID: String?
    @State private var androidReviewTarget: PendingUpload?
    @State private var stageInfoExpanded = false

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 13) {
                Label("V0.2.6 · 采购云端与 Android 数据逐项验收", systemImage: "shield.lefthalf.filled")
                    .font(.headline)
                    .foregroundColor(.orange)
                Text("仅对 sync.830888.xyz 中明确指定的 iOS_TEST_ 测试账本分阶段试写最多三笔单商品采购。必须使用只拥有该账本权限的专用账号；正式账本、营业和资金结算仍然不可写。")
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

                DisclosureGroup("测试前的权限与安全检查", isExpanded: $stageInfoExpanded) {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("① 仅使用正式同步域名 sync.830888.xyz，不需要独立服务器。")
                        Text("② 为测试账本建立专用账号，不能是管理员、超级用户或拥有其他账本的账号。")
                        Text("③ 客户端再次核对完整账本 ID、名称、权限和商品/采购人的同步 ID。")
                        Text("④ 逐笔试写最多三笔，必须在 Android 手动输入六项数据验收，不能只点确认。")
                        Text("⑤ 只靠客户端不能保证服务器的跨账本隔离；须确认服务端按 token 和 book_id 授权。")
                    }
                    .font(.caption).foregroundColor(.secondary)
                    .padding(.top, 5)
                }
                .font(.caption.bold())

                if !coordinator.connected {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("连接正式服务器中的独立测试账本").font(.headline)
                        Text(StagingPurchasePolicy.productionURL)
                            .font(.caption).foregroundColor(.secondary)
                        TextField("仅有该账本权限的测试账号", text: $stageUsername)
                            .textInputAutocapitalization(.never).autocorrectionDisabled()
                        SecureField("测试账号密码", text: $stagePassword)
                        TextField("再次输入完整测试账本 ID", text: $typedBookID)
                            .textInputAutocapitalization(.never).autocorrectionDisabled()
                        Toggle("我确认此账号只能访问上述测试账本，已核对账本 ID", isOn: $acknowledged)
                            .font(.caption)
                        Button {
                            Task {
                                await coordinator.connect(
                                    username: stageUsername,
                                    password: stagePassword, repeatedBookID: typedBookID,
                                    acknowledged: acknowledged)
                                stagePassword = ""
                            }
                        } label: {
                            if coordinator.busy {
                                ProgressView().frame(maxWidth: .infinity)
                            } else {
                                Label("验证专用账号和测试账本", systemImage: "checkmark.shield")
                                    .frame(maxWidth: .infinity)
                            }
                        }
                        .buttonStyle(.borderedProminent)
                        .disabled(coordinator.busy || !acknowledged ||
                                  model.selectedBook?.id != typedBookID ||
                                  stageUsername.isEmpty || stagePassword.isEmpty)
                        Text("登录后会检查账号可见账本是否只有这一个。若权限过高或能看到正式账本，立即拒绝上传。")
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
                        androidReviewTarget = nil
                    }
                    .font(.caption)
                }

                if let message = coordinator.message {
                    Label(message, systemImage: "info.circle")
                        .font(.caption)
                        .foregroundColor(.orange)
                }

                Text("已录入的本机采购草稿").font(.headline)
                Text("多商品草稿仅支持本机录入、编辑和金额核对；单商品旧版草稿仍可走三笔测试同步")
                    .font(.caption).foregroundColor(.secondary)
                Text("本账本已准备 " + String(coordinator.jobs.count) +
                     " / 3 笔；每笔都必须先通过云端和 Android 人工核对，才能准备下一笔")
                    .font(.caption).foregroundColor(.secondary)
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
                            Image(systemName: current?.state == .complete &&
                                  coordinator.audits[draft.id]?.complete != false ?
                                  "checkmark.circle.fill" : "doc.badge.clock")
                                .foregroundColor(
                                    current?.state == .complete &&
                                    coordinator.audits[draft.id]?.complete != false
                                    ? .green : .orange)
                        }
                        Text(current?.state.title ??
                             (draft.purchaseItems == nil ?
                              "单商品待准备" : "多商品本机草稿 · 尚未开放整单云端提交"))
                            .font(.caption2).foregroundColor(.secondary)
                        if let linkedID = current?.childParentID {
                            Text("商品明细使用云端父订单 ID：" + String(linkedID))
                                .font(.caption2).foregroundColor(.secondary)
                        }
                        if current?.itemRequestStarted == true &&
                           current?.state != .complete {
                            Text("商品明细请求可能已发出；必须核对服务器，不允许盲目重传")
                                .font(.caption2).foregroundColor(.orange)
                        }
                        if let current {
                            Text(current.lastMessage).font(.caption2)
                                .foregroundColor(.secondary)
                            Text("同步服务器：" + current.stageHost)
                                .font(.caption2).foregroundColor(.secondary)
                                .lineLimit(2)
                        }
                        if let audit = coordinator.audits[draft.id] {
                            VStack(alignment: .leading, spacing: 6) {
                                Label(audit.headline, systemImage:
                                      audit.complete ? "checkmark.shield" :
                                      "exclamationmark.triangle")
                                    .font(.caption.bold())
                                    .foregroundColor(audit.complete ? .green : .orange)
                                Text("云端父订单：" + (audit.orderVerified ? "核对通过" : "尚未核对") +
                                     " · 商品明细：" + (audit.itemVerified ? "核对通过" : "尚未核对"))
                                    .font(.caption2).foregroundColor(.secondary)
                                if let remoteID = audit.serverOrderID {
                                    Text("云端订单数字 ID：" + String(remoteID))
                                        .font(.caption2).foregroundColor(.secondary)
                                }
                                if audit.remappedOrderID {
                                    Text("检测到服务端数字 ID 重映射；未校验明细关联前不会自动继续上传")
                                        .font(.caption2).foregroundColor(.orange)
                                }
                                ForEach(audit.issues) { issue in
                                    Text("• " + issue.description)
                                        .font(.caption2)
                                        .foregroundColor(.orange)
                                }
                                Text("云端回读时间：" + audit.checkedAt.formatted(
                                    date: .abbreviated, time: .shortened))
                                    .font(.caption2).foregroundColor(.secondary)
                            }
                        }
                        if let current, let evidence = current.androidReview {
                            if let audit = coordinator.audits[draft.id] {
                                if AndroidPurchaseReviewPolicy.isCurrent(
                                    evidence, job: current, audit: audit) {
                                    Label("Android 六项验收已核对 · " +
                                          evidence.checkedAt.formatted(
                                              date: .abbreviated, time: .shortened),
                                          systemImage: "checkmark.seal")
                                        .font(.caption).foregroundColor(.green)
                                } else {
                                    Label("Android 验收对应的云端指纹已变化；禁止解锁下一笔",
                                          systemImage: "exclamationmark.triangle")
                                        .font(.caption).foregroundColor(.orange)
                                }
                            } else {
                                Text("已保存 Android 六项验收，请刷新云端确认仍一致")
                                    .font(.caption).foregroundColor(.secondary)
                            }
                        } else if current?.androidCheckedAt != nil {
                            Text("旧版 Android 确认已保留，但缺少六项数据；需重新核对才能准备下一单")
                                .font(.caption).foregroundColor(.orange)
                        }
                        if coordinator.connected {
                            if current == nil && draft.purchaseItems != nil {
                                Label("多商品采购仅限本机草稿，暂不可上传",
                                      systemImage: "lock.fill")
                                    .font(.caption).foregroundColor(.orange)
                            } else if current == nil {
                                Button("① 核验上一单并准备本笔采购") {
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
                            } else if current?.state == .complete {
                                if coordinator.audits[draft.id]?.complete == false {
                                    Text("此前确认的云端记录现有差异，不能继续视为核对通过")
                                        .font(.caption).foregroundColor(.orange)
                                } else {
                                    Text("上次服务器回读确认；请刷新核对，Android 需人工验收")
                                        .font(.caption).foregroundColor(.green)
                                }
                            } else if current?.state == .needsReview ||
                                      current?.state == .sendingOrder ||
                                      current?.state == .sendingItem {
                                Text("未确认结果不会自动重新发送，请先核对远端")
                                    .font(.caption).foregroundColor(.orange)
                            }
                            if current != nil {
                                Button("刷新云端对账（仅查询，不提交）") {
                                    Task { await coordinator.checkServer(draftID: draft.id) }
                                }
                                .disabled(coordinator.busy)
                            }
                            if current?.state == .complete,
                               coordinator.audits[draft.id]?.complete == true,
                               current?.androidReview == nil {
                                Button("填写 Android 实际六项数据并验收") {
                                    androidReviewTarget = PendingUpload(id: draft.id)
                                }
                                .font(.caption)
                                .disabled(coordinator.busy)
                            }
                        }
                    }
                    .padding(12)
                    .background(Color(UIColor.secondarySystemGroupedBackground),
                                in: RoundedRectangle(cornerRadius: 12))
                }
                Text("仅限测试账本的前三笔单商品采购。上一单须先经过云端核对和 Android 人工验收，之后才允许准备下一单。服务器改变父订单数字 ID 时，会先冻结实际云端 ID，再发送商品明细。")
                    .font(.caption2).foregroundColor(.secondary)
            }
            .padding(12)
        }
        .navigationBarTitle("采购数据逐项验收", displayMode: .inline)
        .alert(item: Binding(
            get: { confirmUploadID.map { PendingUpload(id: $0) } },
            set: { confirmUploadID = $0?.id }
        )) { selection in
            Alert(
                title: Text("向正式服务器的测试账本试写一笔"),
                message: Text("此操作会向 sync.830888.xyz 的独立测试账本写入真实记录。请确认账号只有此账本权限。若响应不确定需人工核对，绝不自动重传。"),
                primaryButton: .destructive(Text("确认试写一笔")) {
                    confirmUploadID = nil
                    Task { await coordinator.upload(draftID: selection.id) }
                },
                secondaryButton: .cancel(Text("取消")) {
                    confirmUploadID = nil
                })
        }
        .sheet(item: $androidReviewTarget) { target in
            if let job = coordinator.jobs.first(where: { $0.id == target.id }),
               let audit = coordinator.audits[target.id], audit.complete {
                AndroidPurchaseReviewSheet(job: job, audit: audit) { observation in
                    coordinator.confirmAndroidReview(
                        draftID: target.id, observation: observation)
                }
            } else {
                Text("请重新进行云端核对后再填写 Android 数据")
                    .padding()
            }
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
            androidReviewTarget = nil
        }
    }
}

private struct PendingUpload: Identifiable {
    let id: String
}
