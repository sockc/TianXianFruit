import SwiftUI

/// Test-book-only correction of a saved Android-compatible business record.
/// A general CloudAPI account never gains write privileges from this view.
struct TestBusinessEditView: View {
    @EnvironmentObject private var model: LedgerViewModel
    @StateObject private var coordinator = TestBusinessEditCoordinator()
    @State private var username = ""
    @State private var password = ""
    @State private var showSafety = false

    /// When opened from a business-history row, show only that cloud record.
    let focusRecordSyncID: String?

    init(focusRecordSyncID: String? = nil) {
        self.focusRecordSyncID = focusRecordSyncID
    }

    private var testBook: CloudBook? {
        guard let book = model.selectedBook, book.isAvailable,
              book.name.hasPrefix(TestWritePolicy.prefix) else { return nil }
        return book
    }

    private var records: [SyncEvent] {
        coordinator.liveEvents.filter {
            $0.tableName == "store_daily_record" && !$0.isDeletion &&
            (focusRecordSyncID == nil || $0.syncId == focusRecordSyncID)
        }.sorted {
            let first = $0.payload["date"]?.text ?? ""
            let second = $1.payload["date"]?.text ?? ""
            return first == second ? $0.syncId < $1.syncId : first > second
        }
    }

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 13) {
                Label("测试账本 · 营业更正", systemImage: "pencil.circle")
                    .font(.headline)
                Text("仅测试账本：可按原收款人分别更正微信、支付宝、现金金额。已发生结算的日期禁止更正；日期、位置和收款人身份不变。")
                    .font(.caption).foregroundColor(.secondary)

                if let book = testBook {
                    Label(book.name, systemImage: "checkmark.shield.fill")
                        .font(.subheadline.bold()).foregroundColor(.green)
                } else {
                    Label("正式账本保持只读；请先切换到 iOS_TEST_ 测试账本",
                          systemImage: "lock.shield")
                        .foregroundColor(.orange)
                }
                if testBook != nil && !coordinator.connected {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("测试账号授权").font(.subheadline.bold())
                        TextField("测试账号", text: $username)
                            .autocorrectionDisabled()
                            .textInputAutocapitalization(.never)
                        SecureField("密码", text: $password)
                        Button {
                            Task { await coordinator.login(
                                username: username, password: password) }
                        } label: {
                            Label("验证独立测试账本", systemImage: "checkmark.shield")
                        }
                        .buttonStyle(.borderedProminent)
                        .disabled(coordinator.busy || username.isEmpty ||
                                  password.isEmpty)
                        Text("可自动复用此前采购/营业测试账号的安全登录状态；不保存密码。")
                            .font(.caption2).foregroundColor(.secondary)
                    }
                    .padding(12)
                    .background(Color(UIColor.secondarySystemGroupedBackground),
                                in: RoundedRectangle(cornerRadius: 12))
                } else if coordinator.connected {
                    Label("专用测试账号已验证", systemImage: "checkmark.circle.fill")
                        .font(.caption).foregroundColor(.green)
                }
                if coordinator.busy {
                    HStack {
                        ProgressView()
                        Text("核验云端版本及修改状态…").font(.caption)
                    }
                }
                if let message = coordinator.message {
                    Text(message).font(.caption).foregroundColor(.orange)
                }
                if testBook != nil && !coordinator.liveRecordsReady {
                    Text("更正操作必须先从服务器取得最新完整记录，不使用旧版本机缓存。")
                        .font(.caption2).foregroundColor(.secondary)
                }

                HStack {
                    Text(focusRecordSyncID == nil ? "已同步营业记录" : "本次选择的营业记录").font(.headline)
                    Spacer()
                    Button {
                        Task { await coordinator.refreshCurrent() }
                    } label: {
                        Image(systemName: "arrow.clockwise")
                    }
                    .disabled(coordinator.busy || !coordinator.connected)
                    .accessibilityLabel("重新获取服务器最新营业记录")
                }
                if testBook != nil && coordinator.connected &&
                   coordinator.liveRecordsReady {
                    if records.isEmpty {
                        Text("所选记录不存在或已变更，请回到营业历史刷新")
                            .font(.caption).foregroundColor(.secondary)
                    }
                    ForEach(records, id: \.syncId) { record in
                        NavigationLink(destination: TestBusinessEditForm(
                            original: record, bookID: model.selectedBookID ?? "",
                            coordinator: coordinator)) {
                            HStack {
                                VStack(alignment: .leading, spacing: 4) {
                                    Text((record.payload["date"]?.text ?? "") + " · " +
                                         (record.payload["store_name"]?.text ?? "未知位置"))
                                        .font(.subheadline.bold())
                                    Text("营业 " + (record.payload["revenue"]?.decimal ?? 0)
                                        .formatted(.currency(code: "CNY")) +
                                         " · 云端版本 " + String(record.rowVersion))
                                        .font(.caption).foregroundColor(.secondary)
                                }
                                Spacer()
                                Image(systemName: "chevron.right")
                                    .font(.caption).foregroundColor(.secondary)
                            }
                        }
                        .buttonStyle(.plain)
                        .padding(12)
                        .background(Color(UIColor.secondarySystemGroupedBackground),
                                    in: RoundedRectangle(cornerRadius: 12))
                    }
                }
                if !coordinator.jobs.isEmpty {
                    Text("本机更正任务").font(.headline)
                    ForEach(coordinator.jobs) { job in
                        VStack(alignment: .leading, spacing: 7) {
                            Text((job.original.payload["date"]?.text ?? "") + " · " +
                                 (job.original.payload["store_name"]?.text ?? "位置"))
                                .font(.subheadline.bold())
                            Text(job.state.title)
                                .font(.caption.bold())
                                .foregroundColor(job.state == .complete ? .green : .orange)
                            Text(job.note).font(.caption).foregroundColor(.secondary)
                            let review = BusinessEditCloudReview(
                                job: job, snapshot: model.snapshot)
                            Text("云端复核：" + review.title)
                                .font(.caption2)
                                .foregroundColor(review.status == .current ? .green : .secondary)
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
                if !coordinator.jobs.isEmpty {
                    Button {
                        Task {
                            await coordinator.refreshCurrent()
                            await model.refresh(forceFull: true)
                        }
                    } label: {
                        Label("重新读取云端记录并核对（只读）",
                              systemImage: "arrow.clockwise")
                    }
                    .disabled(model.isBusy || coordinator.busy)
                    Text("更正列表以服务器刚读取的记录为准；安卓本机需另行同步。")
                        .font(.caption2).foregroundColor(.secondary)
                }
                DisclosureGroup("本版安全范围", isExpanded: $showSafety) {
                    Text("多人收款必须逐个输入原有收款人的实际金额，不能增加、删除或更换收款人。只有没有利润分配、结算与转账历史的营业日才允许更正；检测到历史就阻止写入，避免与安卓自动作废结算的规则不一致。营业删除、日期及位置调整、凌晨收款、结算仍未开放。")
                        .font(.caption).foregroundColor(.secondary)
                }
            }
            .padding(12)
        }
        .navigationBarTitle("测试营业更正", displayMode: .inline)
        .onAppear(perform: open)
        // NavigationLink pushes a child screen and may trigger onDisappear
        // on its parent. Do not invalidate the in-scope test session here.
        .onChange(of: model.selectedBookID) { _ in open() }
        .onChange(of: model.account?.username) { _ in open() }
    }

    private func open() {
        coordinator.open(account: model.account?.username,
                         book: model.selectedBook)
        if username.isEmpty { username = model.account?.username ?? "" }
        password = ""
        Task { await coordinator.restore() }
    }
}

private struct TestBusinessEditForm: View {
    @EnvironmentObject private var model: LedgerViewModel
    @ObservedObject var coordinator: TestBusinessEditCoordinator

    let original: SyncEvent
    let bookID: String

    @State private var wechat: String
    @State private var alipay: String
    @State private var cash: String
    @State private var expense: String
    @State private var opening: String
    @State private var closing: String
    @State private var splitRows: [TestBusinessSplitInput]
    @State private var confirm = false

    init(original: SyncEvent, bookID: String,
         coordinator: TestBusinessEditCoordinator) {
        self.original = original
        self.bookID = bookID
        self.coordinator = coordinator
        let current = TestBusinessEditValues(original)
        _wechat = State(initialValue: current.wechat)
        _alipay = State(initialValue: current.alipay)
        _cash = State(initialValue: current.cash)
        _expense = State(initialValue: current.expense)
        _opening = State(initialValue: current.opening)
        _closing = State(initialValue: current.closing)
        _splitRows = State(initialValue: TestBusinessSplitInput.from(original))
    }

    private var multiCollector: Bool { splitRows.count > 1 }

    private var amounts: TestBusinessEditValues {
        let w = multiCollector ?
            TestBusinessSplitInput.total(splitRows, key: \.wechat) : wechat
        let a = multiCollector ?
            TestBusinessSplitInput.total(splitRows, key: \.alipay) : alipay
        let c = multiCollector ?
            TestBusinessSplitInput.total(splitRows, key: \.cash) : cash
        return TestBusinessEditValues(
            wechat: w, alipay: a, cash: c,
            expense: expense, opening: opening, closing: closing,
            splits: multiCollector ? splitRows : nil)
    }

    private var previouslySettled: Bool {
        guard let date = original.payload["date"]?.text else { return true }
        return TestBusinessEditPolicy.hasDerivedSettlement(
            date: date, events: coordinator.liveEvents)
    }

    private var correctBook: Bool {
        model.selectedBookID == bookID &&
        model.selectedBook?.name.hasPrefix(TestWritePolicy.prefix) == true
    }

    private var hasChanges: Bool {
        let before = TestBusinessEditValues(original)
        let after = amounts
        return before.wechat != after.wechat ||
               before.alipay != after.alipay ||
               before.cash != after.cash ||
               before.expense != after.expense ||
               before.opening != after.opening ||
               before.closing != after.closing ||
               (multiCollector &&
                splitRows != TestBusinessSplitInput.from(original))
    }

    private var canSubmit: Bool {
        correctBook && coordinator.connected &&
        coordinator.liveRecordsReady && !coordinator.busy &&
        !previouslySettled && hasChanges
    }

    var body: some View {
        Form {
            Section(header: Text("原云端记录")) {
                Text((original.payload["date"]?.text ?? "") + " · " +
                     (original.payload["store_name"]?.text ?? ""))
                Text("营业额：" + (original.payload["revenue"]?.decimal ?? 0)
                    .formatted(.currency(code: "CNY")))
                Text("版本：" + String(original.rowVersion) +
                     "（提交前再次核对，版本变化不会覆盖）")
                    .font(.caption).foregroundColor(.secondary)
            }
            if correctBook {
                if previouslySettled {
                    Section {
                        Label("该营业日已有分配或结算记录，请先在安卓处理。iOS 禁止更正，以免留下一笔未同步作废的资金结算。",
                              systemImage: "lock.shield")
                            .font(.caption).foregroundColor(.orange)
                    }
                }
                if multiCollector {
                    Section(header: Text("按收款人更正金额（元）")) {
                        Text("每位原收款人独立输入，不自动按比例分摊。收款人身份不可变。")
                            .font(.caption).foregroundColor(.secondary)
                        ForEach($splitRows) { $split in
                            VStack(alignment: .leading, spacing: 8) {
                                Text(split.partnerName)
                                    .font(.subheadline.bold())
                                amount("微信", text: $split.wechat)
                                amount("支付宝", text: $split.alipay)
                                amount("现金", text: $split.cash)
                            }
                        }
                        Text("合计：微信 ¥" + amounts.wechat +
                             " · 支付宝 ¥" + amounts.alipay +
                             " · 现金 ¥" + amounts.cash)
                            .font(.caption).foregroundColor(.secondary)
                    }
                }
                Section(header: Text("修改金额（元）")) {
                    if !multiCollector {
                        amount("微信", text: $wechat)
                        amount("支付宝", text: $alipay)
                        amount("现金", text: $cash)
                    }
                    amount("费用", text: $expense)
                    amount("开摊库存估值", text: $opening)
                    amount("收摊库存估值", text: $closing)
                }
                Section {
                    Button {
                        confirm = true
                    } label: {
                        Label("保存并提交测试更正", systemImage: "checkmark.shield")
                            .frame(maxWidth: .infinity)
                    }
                    .disabled(!canSubmit)
                    if let message = coordinator.message {
                        Text(message).font(.caption).foregroundColor(.orange)
                    }
                    Text("不得更改日期、位置、收款人身份、顾客、凌晨收款或已结算日。若另一设备已修改，提交前会拒绝覆盖。")
                        .font(.caption2).foregroundColor(.secondary)
                }
            } else {
                Section {
                    Text("测试账本已切换或未授权，禁止写入")
                        .foregroundColor(.orange)
                }
            }
        }
        .navigationBarTitle("更正营业金额", displayMode: .inline)
        .alert(isPresented: $confirm) {
            Alert(
                title: Text("提交测试更正？"),
                message: Text("仅写入 iOS_TEST_ 测试账本；提交可能无法撤回。发生冲突或断网时只能只读核对，不会自动重发。"),
                primaryButton: .cancel(Text("取消")),
                secondaryButton: .default(Text("确认提交"), action: {
                    guard canSubmit else { return }
                    let requested = amounts
                    Task {
                        await coordinator.submit(original: original, values: requested)
                        await model.refresh()
                    }
                })
            )
        }
    }

    private func amount(_ title: String, text: Binding<String>) -> some View {
        HStack {
            Text(title)
            Spacer()
            TextField("0.00", text: text)
                .keyboardType(.decimalPad)
                .multilineTextAlignment(.trailing)
                .frame(width: 130)
        }
    }
}
