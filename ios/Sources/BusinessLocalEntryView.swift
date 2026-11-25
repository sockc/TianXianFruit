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

enum BusinessLocalDraftStoreError: LocalizedError {
    case unreadable, verificationFailed
    var errorDescription: String? {
        switch self {
        case .unreadable:
            return "本机营业草稿无法读取，已禁止覆盖原数据。请保留 App 数据进行恢复。"
        case .verificationFailed:
            return "本机营业草稿保存后未能校验，请勿删除或重新安装 App。"
        }
    }
}

enum BusinessLocalDraftStore {
    private static func key(account: String, bookID: String) -> String {
        "ios.formal.business.drafts.v1." + account.lowercased() + "." + bookID.lowercased()
    }

    /// Never silently overwrite a malformed or unreadable draft collection.
    static func loadChecked(account: String, bookID: String) throws -> [BusinessLocalDraft] {
        guard !account.isEmpty, !bookID.isEmpty else {
            throw TestWriteError.insufficientSchema("未选择账号或账本")
        }
        let storageKey = key(account: account, bookID: bookID)
        guard let value = UserDefaults.standard.object(forKey: storageKey) else { return [] }
        guard let data = value as? Data,
              let drafts = try? JSONDecoder().decode([BusinessLocalDraft].self, from: data),
              Set(drafts.map(\.id)).count == drafts.count else {
            throw BusinessLocalDraftStoreError.unreadable
        }
        return drafts
    }

    static func load(account: String, bookID: String) -> [BusinessLocalDraft] {
        (try? loadChecked(account: account, bookID: bookID)) ?? []
    }

    static func save(_ draft: BusinessLocalDraft, account: String, bookID: String) throws {
        guard !account.isEmpty, !bookID.isEmpty else {
            throw TestWriteError.insufficientSchema("未选择账号或账本")
        }
        var drafts = try loadChecked(account: account, bookID: bookID)
        let storageKey = key(account: account, bookID: bookID)
        // Keep one known-readable backup of preexisting drafts for recovery.
        if let old = UserDefaults.standard.data(forKey: storageKey) {
            UserDefaults.standard.set(old, forKey: storageKey + ".backup")
        }
        drafts.removeAll { ($0.date == draft.date && $0.storeID == draft.storeID) || $0.id == draft.id }
        drafts.append(draft)
        UserDefaults.standard.set(try JSONEncoder().encode(drafts), forKey: storageKey)
        let verified = try loadChecked(account: account, bookID: bookID)
        guard verified.count == drafts.count,
              verified.contains(where: {
                  $0.id == draft.id && $0.date == draft.date &&
                  $0.storeID == draft.storeID && $0.collectors == draft.collectors &&
                  $0.expense == draft.expense &&
                  $0.openingStock == draft.openingStock &&
                  $0.closingStock == draft.closingStock
              }) else {
            throw BusinessLocalDraftStoreError.verificationFailed
        }
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
    @State private var storageError: String?
    @State private var pendingDate: Date?
    @State private var pendingStoreID: String?
    @State private var showSwitchConfirmation = false
    @State private var showCloseConfirmation = false

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

    private var cloudReady: Bool {
        BusinessDraftWorkflowPolicy.readyForCloud(
            lines: lines, expense: expense, opening: openingStock,
            closing: closingStock, startTime: startTime, endTime: endTime)
    }
    private var missingCloudFields: [String] {
        BusinessDraftWorkflowPolicy.missingCloudFields(
            lines: lines, expense: expense, opening: openingStock,
            closing: closingStock, startTime: startTime, endTime: endTime)
    }

    // Preserve unsaved inputs when switching date or business location.
    private var hasUnsavedInput: Bool {
        guard !formMatchesSavedDraft else { return false }
        if persistedDraft != nil { return true }
        return lines.count > 1 ||
            lines.contains { $0.wechat != "0" || $0.alipay != "0" ||
                             $0.cash != "0" } ||
            !expense.isEmpty || !openingStock.isEmpty ||
            !closingStock.isEmpty || !note.isEmpty ||
            !startTime.isEmpty || !endTime.isEmpty
    }

    private var guardedDate: Binding<Date> {
        Binding(get: { date }, set: { next in
            guard BusinessDates.key(next) != dateKey else { return }
            if hasUnsavedInput {
                pendingDate = next
                pendingStoreID = nil
                showSwitchConfirmation = true
            } else {
                date = next
                loadDraft()
            }
        })
    }

    private var guardedStore: Binding<String> {
        Binding(get: { storeID }, set: { next in
            guard next != storeID else { return }
            if hasUnsavedInput {
                pendingStoreID = next
                pendingDate = nil
                showSwitchConfirmation = true
            } else {
                storeID = next
                loadDraft()
            }
        })
    }

    private func applySwitch() {
        if let pendingDate { date = pendingDate }
        if let pendingStoreID { storeID = pendingStoreID }
        pendingDate = nil
        pendingStoreID = nil
        loadDraft()
    }

    private func saveBeforeSwitch() {
        save()
        guard saved, formMatchesSavedDraft else { return }
        applySwitch()
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

    private var currentCloudState: BusinessDraftWorkflowPolicy.CloudState? {
        guard let draft = persistedDraft, let attempt else { return nil }
        return BusinessDraftWorkflowPolicy.status(
            draft: draft, events: Array(model.snapshot.records.values),
            attempt: attempt,
            cloudSnapshotReady: model.lastSyncAt != nil &&
                !model.isUsingCachedAuthorization)
    }

    private func loadDraft() {
        guard !storeID.isEmpty else { return }
        let existing: BusinessLocalDraft?
        do {
            existing = try BusinessLocalDraftStore.loadChecked(
                account: account, bookID: bookID)
                .first { $0.date == dateKey && $0.storeID == storeID }
            storageError = nil
        } catch {
            storageError = error.localizedDescription
            saved = false
            commitMessage = storageError
            return
        }
        if let draft = existing {
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
        // Keep the required hours visible; collapsing hid why sync was blocked.
        showHours = true
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
        guard storageError == nil, attempt == nil, !existingRemote,
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
            // Local drafts are allowed before all amounts are complete.
            // Server upload still requires readyForCloud and the atomic policy.
            guard !lines.isEmpty, lines.count <= 12 else {
                throw BusinessAtomicError.invalidDraft
            }
            let previous = try BusinessLocalDraftStore.loadChecked(
                account: account, bookID: bookID)
                .first(where: { $0.date == dateKey && $0.storeID == storeID })
            let draft = BusinessLocalDraft(
                id: previous?.id ?? UUID().uuidString, date: dateKey,
                storeID: store.id, storeName: store.name, collectors: lines,
                startTime: startTime.trimmingCharacters(in: .whitespaces),
                endTime: endTime.trimmingCharacters(in: .whitespaces), note: note,
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

    /// One primary action. Cloud commit is never attempted unless the local
    /// draft was successfully persisted and the server explicitly allows it.
    private var cloudWriteAvailable: Bool {
        editingAllowed && model.businessCloudBookAllowed
    }

    private func saveThenSync() async {
        guard cloudReady else {
            showHours = true
            errorText = "请补齐：" + missingCloudFields.joined(separator: "、") +
                        "；缺失金额不会被当作零。"
            return
        }
        if !saved || !formMatchesSavedDraft { save() }
        guard saved && formMatchesSavedDraft else { return }
        await commitDraft()
    }

    private func commitDraft() async {
        guard !submitting, attempt == nil, editingAllowed, cloudReady,
              model.businessCloudBookAllowed, saved, formMatchesSavedDraft,
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
              model.businessCloudBookAllowed else { return }
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
                Section(header: Text("营业日期、位置与时间")) {
                    TXDayBar(date: guardedDate, label: "营业日期", allowFuture: false)
                    HStack(spacing: 8) {
                        Picker("营业位置", selection: guardedStore) {
                            Text("请选择位置").tag("")
                            ForEach(model.snapshot.testStores) { store in
                                Text(store.name).tag(store.id)
                            }
                        }
                        .pickerStyle(MenuPickerStyle())
                        .accessibilityLabel("营业位置")
                    }
                    HStack(spacing: 5) {
                        Text("营业时间").font(.caption)
                        Spacer(minLength: 6)
                        TextField("16:00", text: $startTime)
                            .keyboardType(.numbersAndPunctuation)
                            .multilineTextAlignment(.center)
                            .frame(width: 61)
                            .textFieldStyle(.roundedBorder)
                            .accessibilityLabel("实际开摊时间")
                        Text("—").foregroundColor(.secondary)
                        TextField("00:10", text: $endTime)
                            .keyboardType(.numbersAndPunctuation)
                            .multilineTextAlignment(.center)
                            .frame(width: 61)
                            .textFieldStyle(.roundedBorder)
                            .accessibilityLabel("实际收摊时间")
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
                    // One collector already sees all three channel amounts.
                    // A second totals card only repeats those same figures.
                    if lines.count > 1 {
                        TXBusinessReceiptSummaryView(lines: lines)
                    }
                }
                Section(header: Text("营业成本与利润依据（同步前必填）")) {
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
                    Text("可以先保存未填完的营业草稿。利润沿用安卓规则；云端提交前必须补齐真实成本，不能把缺失值当作零。")
                        .font(.caption).foregroundColor(.secondary)
                }
                Section {
                    TextField("营业备注（仅本机，不参与结算）", text: $note)
                    Text("营业时间可跨午夜，格式 HH:mm；不自动扣减凌晨收款。")
                        .font(.caption2).foregroundColor(.secondary)
                }
                Section {
                    Button {
                        if cloudWriteAvailable && cloudReady {
                            Task { await saveThenSync() }
                        } else {
                            save()
                        }
                    } label: {
                        Label(cloudWriteAvailable && cloudReady ?
                                  "保存并同步营业" : "保存营业草稿",
                              systemImage: cloudWriteAvailable && cloudReady ?
                                  "icloud.and.arrow.up" : "square.and.arrow.down")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(!editingAllowed || storeID.isEmpty || existingRemote ||
                              storageError != nil || attempt != nil || submitting ||
                              (saved && formMatchesSavedDraft &&
                               !(cloudWriteAvailable && cloudReady)))
                    if saved && formMatchesSavedDraft {
                        Label("本机草稿已保存（云端状态见下方）",
                              systemImage: "checkmark.circle")
                            .foregroundColor(.green)
                    } else if saved && !formMatchesSavedDraft {
                        Label("当前有未保存的修改，上传前请先保存",
                              systemImage: "exclamationmark.circle")
                            .font(.caption).foregroundColor(.orange)
                    }
                    if let storageError {
                        Label(storageError, systemImage: "externaldrive.badge.exclamationmark")
                            .font(.caption).foregroundColor(.red)
                    }
                    if cloudWriteAvailable && !cloudReady {
                        Label("待补齐：" + missingCloudFields.joined(separator: "、") +
                              "；可以先保存本机。",
                              systemImage: "info.circle")
                            .font(.caption).foregroundColor(.orange)
                    }
                    if let errorText {
                        Text(errorText).font(.caption).foregroundColor(.orange)
                    }
                    if let attempt {
                        if let state = currentCloudState,
                           state == .confirmedRemoteMissing ||
                           state == .confirmedRemoteChanged {
                            Label(state.label, systemImage: "exclamationmark.triangle")
                                .font(.caption).foregroundColor(.orange)
                            Button("刷新云端并复核原营业") {
                                Task { await model.refresh(forceFull: true) }
                            }
                            .disabled(submitting || model.isBusy)
                        } else {
                            Text(attempt.label).font(.caption)
                                .foregroundColor(attempt.phase == .confirmed
                                                 ? .green : .orange)
                        }
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
                            .disabled(submitting || !model.businessCloudBookAllowed)
                            Text("服务器需明确授权当前账本；只允许使用原操作编号恢复，不能另建重复记录。")
                                .font(.caption2).foregroundColor(.secondary)
                        }
                        if !model.businessCloudBookAllowed {
                            Button("刷新服务器写入权限") {
                                Task { await model.refreshBusinessCommitCapabilities() }
                            }
                            .disabled(submitting)
                        }
                    } else if saved && formMatchesSavedDraft &&
                              !cloudWriteAvailable {
                        Button("检查同步权限") {
                            Task { await model.refreshBusinessCommitCapabilities() }
                        }
                        .disabled(submitting)
                    }
                    if submitting { ProgressView("正在核对营业事务…") }
                    if let commitMessage {
                        Text(commitMessage).font(.caption).foregroundColor(.orange)
                    }
                    if let warning = model.businessCommitError {
                        Text(warning).font(.caption).foregroundColor(.secondary)
                    }
                    if !cloudWriteAvailable {
                        Text("营业云端写入未授权，本机草稿不计入结算。")
                            .font(.caption2).foregroundColor(.orange)
                    }
                }
            }
            .navigationTitle("营业录入")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("关闭") {
                        if hasUnsavedInput { showCloseConfirmation = true }
                        else { dismiss() }
                    }
                }
            }
            .onAppear {
                Task { await model.refreshBusinessCommitCapabilities() }
                if storeID.isEmpty, model.snapshot.testStores.count == 1 {
                    storeID = model.snapshot.testStores[0].id
                }
                loadDraft()
            }
            .interactiveDismissDisabled(hasUnsavedInput)
            .alert("退出前保存营业草稿？",
                   isPresented: $showCloseConfirmation) {
                Button("先保存本机，再关闭") {
                    save()
                    if saved && formMatchesSavedDraft { dismiss() }
                }
                .disabled(!editingAllowed || storageError != nil)
                Button("放弃未保存内容并关闭", role: .destructive) { dismiss() }
                Button("继续编辑", role: .cancel) {}
            } message: {
                Text("未保存的收款和成本不会保留。")
            }
            .confirmationDialog("当前营业录入尚未保存",
                                isPresented: $showSwitchConfirmation,
                                titleVisibility: .visible) {
                Button("先保存在本机，再切换") { saveBeforeSwitch() }
                    .disabled(!editingAllowed || storageError != nil)
                Button("放弃本次未保存的修改", role: .destructive) {
                    applySwitch()
                }
                Button("继续编辑", role: .cancel) {
                    pendingDate = nil
                    pendingStoreID = nil
                }
            } message: {
                Text("切换日期或位置会替换当前输入。保存只写入本机，不会自动创建云端营业。")
            }
        }
    }

    private func amount(_ title: String, value: Binding<String>) -> some View {
        HStack {
            Text(title)
            Spacer()
            TXFastNumericField(text: value, label: title)
                .frame(width: 138, height: 36)
        }
    }
}
