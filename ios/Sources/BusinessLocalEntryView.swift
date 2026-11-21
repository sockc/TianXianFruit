import Foundation
import SwiftUI

/// Formal-book local draft only. This type must never be passed to /sync/push.
struct BusinessLocalDraft: Codable, Identifiable {
    var id: String
    var date: String
    var storeID: String
    var storeName: String
    var collectors: [BusinessCollectorDraftLine]
    var startTime: String
    var endTime: String
    var note: String
    var expense: String? = nil
    var openingStock: String? = nil
    var closingStock: String? = nil
    var updatedAt: Date
}

enum BusinessLocalDraftStore {
    private static func key(account: String, bookID: String) -> String {
        "ios.formal.business.drafts.v1." + account.lowercased() + "." + bookID.lowercased()
    }

    static func load(account: String, bookID: String) -> [BusinessLocalDraft] {
        guard !account.isEmpty, !bookID.isEmpty,
              let data = UserDefaults.standard.data(forKey: key(account: account, bookID: bookID)),
              let drafts = try? JSONDecoder().decode([BusinessLocalDraft].self, from: data)
        else { return [] }
        return drafts
    }

    static func save(_ draft: BusinessLocalDraft, account: String, bookID: String) throws {
        guard !account.isEmpty, !bookID.isEmpty else {
            throw TestWriteError.insufficientSchema("未选择账号或账本")
        }
        var drafts = load(account: account, bookID: bookID)
        // One local draft per calendar date and stall. No network side effects.
        drafts.removeAll { ($0.date == draft.date && $0.storeID == draft.storeID) || $0.id == draft.id }
        drafts.append(draft)
        let data = try JSONEncoder().encode(drafts)
        UserDefaults.standard.set(data, forKey: key(account: account, bookID: bookID))
    }
}

/// An isolated local editor; the existing TestWriteLab and cloud write API are untouched.
struct BusinessLocalEntryView: View {
    @EnvironmentObject private var model: LedgerViewModel
    @Environment(\.dismiss) private var dismiss
    @State private var date: Date
    @State private var storeID = ""
    @State private var lines: [BusinessCollectorDraftLine] = [BusinessCollectorDraftLine()]
    @State private var startTime = ""
    @State private var endTime = ""
    @State private var note = ""
    @State private var expense = ""
    @State private var openingStock = ""
    @State private var closingStock = ""
    @State private var errorText: String?
    @State private var saved = false
    @State private var attempt: BusinessCommitAttempt?
    @State private var canResumeOriginal = false
    @State private var commitMessage: String?
    @State private var submitting = false
    @State private var showHours = false

    init(date: Date, preferredStoreID: String? = nil) {
        _date = State(initialValue: date)
        _storeID = State(initialValue: preferredStoreID ?? "")
    }

    private var account: String { model.account?.username ?? "" }
    private var bookID: String { model.selectedBookID ?? "" }
    private var dateKey: String { BusinessDates.key(date) }
    private var persistedDraft: BusinessLocalDraft? {
        BusinessLocalDraftStore.load(account: account, bookID: bookID)
            .first { $0.date == dateKey && $0.storeID == storeID }
    }

    private var formMatchesSavedDraft: Bool {
        BusinessDraftWorkflowPolicy.matchesSaved(
            draft: persistedDraft, date: dateKey, storeID: storeID,
            lines: lines, startTime: startTime, endTime: endTime,
            expense: expense, opening: openingStock,
            closing: closingStock, note: note)
    }

    private var preview: BusinessDraftWorkflowPolicy.Preview? {
        BusinessDraftWorkflowPolicy.preview(
            lines: lines, expense: expense,
            opening: openingStock, closing: closingStock)
    }

    private var remoteRecord: SyncEvent? {
        guard !storeID.isEmpty else { return nil }
        let selectedName = model.snapshot.testStores.first { $0.id == storeID }?.name
        return model.snapshot.records.values.first { row in
            row.tableName == "store_daily_record" && !row.isDeletion &&
            row.payload["date"]?.text == dateKey &&
            (row.payload["store_sync_id"]?.text == storeID ||
             (selectedName != nil && row.payload["store_name"]?.text == selectedName))
        }
    }

    private var editingAllowed: Bool {
        guard let book = model.selectedBook, book.isAvailable, !account.isEmpty else { return false }
        return ["OWNER", "SUPERADMIN", "EDITOR"].contains(book.role.uppercased())
    }

    private var existingRemote: Bool { remoteRecord != nil }

    private func loadDraft() {
        guard !storeID.isEmpty else { return }
        if let draft = persistedDraft {
            lines = draft.collectors
            startTime = draft.startTime
            endTime = draft.endTime
            note = draft.note
            expense = draft.expense ?? ""
            openingStock = draft.openingStock ?? ""
            closingStock = draft.closingStock ?? ""
        } else {
            lines = [BusinessCollectorDraftLine(
                partnerSyncID: model.snapshot.testPartners.count == 1
                    ? model.snapshot.testPartners[0].id : ""
            )]
            startTime = ""
            endTime = ""
            note = ""
            expense = ""
            openingStock = ""
            closingStock = ""
        }
        saved = persistedDraft != nil
        showHours = !startTime.isEmpty || !endTime.isEmpty
        canResumeOriginal = false
        commitMessage = nil
        errorText = nil
        loadAttempt()
    }

    private func loadAttempt() {
        let draft = persistedDraft
        guard let id = draft?.id else { attempt = nil; return }
        do {
            attempt = try BusinessCommitOutbox().load(
                id: id, account: account, bookID: bookID)
        } catch {
            attempt = nil // Fail closed instead of retaining a stale attempt.
            commitMessage = "上传保护记录不可读取，禁止写入：" + error.localizedDescription
        }
    }

    private func save() {
        guard attempt == nil, !existingRemote,
              commitMessage?.hasPrefix("上传保护记录不可读取") != true else {
            errorText = existingRemote
                ? "云端已有该日该位置营业，请从营业历史查看或更正"
                : "已有不可重复的提交操作，请先核对结果"
            return
        }
        guard editingAllowed,
              let store = model.snapshot.testStores.first(where: { $0.id == storeID }) else {
            errorText = "请选择有效营业位置，并确认账本编辑权限"
            return
        }
        do {
            let totals = try BusinessCollectorDraftPolicy.totals(lines)
            let fields = [
                "wechat": String(format: "%.2f", Double(totals.wechat) / 100),
                "alipay": String(format: "%.2f", Double(totals.alipay) / 100),
                "cash": String(format: "%.2f", Double(totals.cash) / 100),
                "actual_start_time": startTime.trimmingCharacters(in: .whitespaces),
                "actual_end_time": endTime.trimmingCharacters(in: .whitespaces)
            ]
            try BusinessCollectorDraftPolicy.validate(lines, fields: fields)
            try BusinessCollectorDraftPolicy.validateTimes(fields)
            // These three amounts must be explicitly supplied: silently
            // assuming zero could misstate Android's saved daily profit.
            guard !expense.isEmpty, !openingStock.isEmpty, !closingStock.isEmpty
            else { throw BusinessAtomicError.invalidDraft }
            _ = try BusinessCollectorDraftPolicy.cents(expense, label: "支出")
            _ = try BusinessCollectorDraftPolicy.cents(openingStock, label: "开摊库存估值")
            _ = try BusinessCollectorDraftPolicy.cents(closingStock, label: "收摊库存估值")
            let previous = BusinessLocalDraftStore.load(account: account, bookID: bookID)
                .first(where: { $0.date == dateKey && $0.storeID == storeID })
            let draft = BusinessLocalDraft(
                id: previous?.id ?? UUID().uuidString, date: dateKey,
                storeID: store.id, storeName: store.name, collectors: lines,
                startTime: fields["actual_start_time"] ?? "",
                endTime: fields["actual_end_time"] ?? "", note: note,
                expense: expense, openingStock: openingStock,
                closingStock: closingStock, updatedAt: Date())
            try BusinessLocalDraftStore.save(draft, account: account, bookID: bookID)
            saved = true
            errorText = nil
            loadAttempt()
        } catch {
            errorText = error.localizedDescription
            saved = false
        }
    }

    private func commitDraft() async {
        guard !submitting, attempt == nil, editingAllowed,
              model.businessTestBookAllowed, saved, formMatchesSavedDraft,
              !existingRemote,
              commitMessage?.hasPrefix("上传保护记录不可读取") != true,
              let draft = persistedDraft else { return }
        submitting = true
        defer { submitting = false; loadAttempt() }
        do {
            let result = try await BusinessFormalSubmit.send(model, draft: draft)
            attempt = result
            commitMessage = result.label
        } catch {
            commitMessage = error.localizedDescription
        }
    }

    private func checkOriginalOperation() async {
        guard !submitting, let saved = attempt else { return }
        submitting = true
        defer { submitting = false; loadAttempt() }
        do {
            let result = try await BusinessFormalSubmit.recover(model, attempt: saved)
            canResumeOriginal = false
            attempt = result
            commitMessage = result.label
        } catch BusinessAtomicError.missingResult {
            // A 404 is not success and does not authorize a fresh operation.
            // The user may explicitly resume the identical immutable request.
            canResumeOriginal = true
            commitMessage = "服务器没有原操作结果。可以继续同一操作编号；不会创建新提交编号。"
        } catch {
            canResumeOriginal = false
            commitMessage = error.localizedDescription
        }
    }

    private func resumeOriginal() async {
        guard !submitting, canResumeOriginal, let stored = attempt,
              model.businessTestBookAllowed else { return }
        submitting = true
        defer { submitting = false; loadAttempt() }
        do {
            let result = try await BusinessFormalSubmit.resumeOriginal(model, attempt: stored)
            canResumeOriginal = false
            attempt = result
            commitMessage = result.label
        } catch {
            // Keep the immutable outbox even after a timeout or server rejection.
            commitMessage = error.localizedDescription
        }
    }

    var body: some View {
        NavigationView {
            Form {
                Section(header: Text("营业日期与位置")) {
                    TXDayBar(date: $date, label: "营业日期", allowFuture: false)
                        .onChange(of: date) { _ in loadDraft() }
                    Picker("营业位置", selection: $storeID) {
                        Text("请选择位置").tag("")
                        ForEach(model.snapshot.testStores) { store in
                            Text(store.name).tag(store.id)
                        }
                    }
                    .onChange(of: storeID) { _ in loadDraft() }
                    if !startTime.isEmpty && !endTime.isEmpty {
                        Text("实际营业 " + startTime + "—" + endTime)
                            .font(.caption).foregroundColor(.secondary)
                    }
                    if let remoteRecord {
                        Label("云端已有该日该位置营业，不能重复新增。",
                              systemImage: "checkmark.shield")
                            .font(.caption).foregroundColor(.orange)
                        NavigationLink(destination: BusinessCloudDetailView(
                            recordSyncID: remoteRecord.syncId, bookID: bookID)) {
                            Label("查看这笔云端营业", systemImage: "doc.text.magnifyingglass")
                        }
                    }
                }
                Section(header: Text("收款人及金额（元）")) {
                    ForEach($lines) { $line in
                        VStack(alignment: .leading, spacing: 8) {
                            HStack {
                                if model.snapshot.testPartners.count == 1,
                                   let person = model.snapshot.testPartners.first {
                                    Text(person.name).font(.subheadline.bold())
                                } else {
                                    Picker("收款人", selection: $line.partnerSyncID) {
                                        Text("请选择收款人").tag("")
                                        ForEach(model.snapshot.testPartners) { person in
                                            Text(person.name).tag(person.id)
                                        }
                                    }
                                }
                                Spacer(minLength: 4)
                                if lines.count > 1 {
                                    Button(role: .destructive) {
                                        lines.removeAll { $0.id == line.id }
                                    } label: {
                                        Image(systemName: "minus.circle")
                                    }.accessibilityLabel("移除收款人")
                                }
                            }
                            HStack(spacing: 7) {
                                TXReceiptChannelInput(label: "微信", value: $line.wechat)
                                TXReceiptChannelInput(label: "支付宝", value: $line.alipay)
                                TXReceiptChannelInput(label: "现金", value: $line.cash)
                            }
                            if lines.count > 1,
                               let subtotal = TXBusinessReceiptPolicy.individual(line) {
                                HStack {
                                    Text("该收款人小计")
                                        .font(.caption2).foregroundColor(.secondary)
                                    Spacer()
                                    Text((Double(subtotal) / 100.0)
                                         .formatted(.currency(code: "CNY")))
                                        .font(.caption.bold())
                                }
                            }
                        }
                        .padding(.vertical, 4)
                    }
                    if lines.count < 12 {
                        Button {
                            lines.append(BusinessCollectorDraftLine())
                        } label: {
                            Label("增加收款人", systemImage: "person.badge.plus")
                        }
                    }
                    TXBusinessReceiptSummaryView(lines: lines)
                }
                Section(header: Text("营业成本与利润依据（必填）")) {
                    amount("支出", value: $expense)
                    amount("开摊库存估值", value: $openingStock)
                    amount("收摊库存估值", value: $closingStock)
                    if let preview {
                        HStack {
                            Text("本次营业额").foregroundColor(.secondary)
                            Spacer()
                            Text("¥" + preview.revenue).font(.headline.bold())
                        }
                        HStack {
                            Text("按填入数值计算的利润").foregroundColor(.secondary)
                            Spacer()
                            Text("¥" + preview.profit).font(.headline.bold())
                        }
                    } else {
                        Text("确认每位收款人金额，以及支出、开摊和收摊库存估值后，显示利润预览。")
                            .font(.caption).foregroundColor(.secondary)
                    }
                    Text("利润按安卓原规则：收款总额＋收摊库存－开摊库存－支出。必须确认实际数据；不能为了上传直接全部填零。")
                        .font(.caption).foregroundColor(.secondary)
                }
                Section {
                    DisclosureGroup(isExpanded: $showHours) {
                        HStack {
                            Text("开摊")
                            Spacer()
                            TextField("16:00", text: $startTime)
                                .keyboardType(.numbersAndPunctuation)
                                .multilineTextAlignment(.trailing)
                                .frame(maxWidth: 105)
                        }
                        HStack {
                            Text("收摊")
                            Spacer()
                            TextField("00:10", text: $endTime)
                                .keyboardType(.numbersAndPunctuation)
                                .multilineTextAlignment(.trailing)
                                .frame(maxWidth: 105)
                        }
                        Text("格式 HH:mm；可跨午夜。这里只记录实际营业时间，不自动扣减凌晨收款。")
                            .font(.caption2).foregroundColor(.secondary)
                        TextField("营业备注（仅本机，不参与结算）", text: $note)
                    } label: {
                        Label("实际营业时间与备注", systemImage: "clock")
                    }
                }
                Section {
                    Button {
                        save()
                    } label: {
                        Label("保存本机营业草稿", systemImage: "square.and.arrow.down")
                    }
                    .disabled(!editingAllowed || storeID.isEmpty || existingRemote ||
                              attempt != nil || submitting ||
                              (saved && formMatchesSavedDraft))
                    if saved && formMatchesSavedDraft {
                        Label("本机草稿已保存（云端状态见下方）",
                              systemImage: "checkmark.circle")
                            .foregroundColor(.green)
                    } else if saved && !formMatchesSavedDraft {
                        Label("当前有未保存的修改，上传前请先保存",
                              systemImage: "exclamationmark.circle")
                            .font(.caption).foregroundColor(.orange)
                    }
                    if let errorText {
                        Text(errorText).font(.caption).foregroundColor(.orange)
                    }
                    if let attempt {
                        Text(attempt.label).font(.caption)
                            .foregroundColor(attempt.phase == .confirmed ? .green : .orange)
                        Button {
                            Task { await checkOriginalOperation() }
                        } label: {
                            Label("查询原操作（不再次提交）", systemImage: "shield.lefthalf.filled")
                        }
                        .disabled(submitting)
                        if canResumeOriginal &&
                           (attempt.phase == .unknown || attempt.phase == .prepared) {
                            Button {
                                Task { await resumeOriginal() }
                            } label: {
                                Label("继续原操作（同一编号，不另建账）",
                                      systemImage: "arrow.clockwise.circle")
                            }
                            .disabled(submitting || !model.businessTestBookAllowed ||
                                      model.businessCommitCapabilities?.allowsWrite != true)
                            Text("仅 iOS_TEST_ 测试账本可使用。服务器需开启营业写入；相同操作编号及金额由服务端幂等保护。")
                                .font(.caption2).foregroundColor(.secondary)
                        }
                        if model.businessCommitCapabilities?.allowsWrite != true {
                            Button("刷新服务器写入权限") {
                                Task { await model.refreshBusinessCommitCapabilities() }
                            }
                            .disabled(submitting)
                        }
                    } else {
                        Button {
                            Task { await commitDraft() }
                        } label: {
                            Label("提交本次营业至测试云端", systemImage: "arrow.up.circle")
                        }
                        .disabled(!editingAllowed || !saved || !formMatchesSavedDraft ||
                                  existingRemote || submitting ||
                                  (!model.businessTestBookAllowed ||
                                   model.businessCommitCapabilities?.allowsWrite != true))
                    }
                    if submitting { ProgressView("正在核对营业事务…") }
                    if let commitMessage {
                        Text(commitMessage).font(.caption).foregroundColor(.orange)
                    }
                    if let warning = model.businessCommitError {
                        Text(warning).font(.caption).foregroundColor(.secondary)
                    }
                    Text("必须由服务器启用专用原子营业事务才可提交；未开放时只保存本机草稿。发生断网或超时，只能查询原操作，不允许再次发送。")
                        .font(.caption2).foregroundColor(.secondary)
                }
            }
            .navigationTitle("营业录入")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("关闭") { dismiss() }
                }
            }
            .onAppear {
                Task { await model.refreshBusinessCommitCapabilities() }
                if storeID.isEmpty, model.snapshot.testStores.count == 1 {
                    storeID = model.snapshot.testStores[0].id
                }
                loadDraft()
            }
        }
    }

    private func amount(_ title: String, value: Binding<String>) -> some View {
        HStack {
            Text(title)
            Spacer()
            TextField("0.00", text: value)
                .keyboardType(.decimalPad)
                .multilineTextAlignment(.trailing)
        }
    }
}
