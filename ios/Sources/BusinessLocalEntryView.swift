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
    @State private var commitMessage: String?
    @State private var submitting = false

    init(date: Date) {
        _date = State(initialValue: date)
    }

    private var account: String { model.account?.username ?? "" }
    private var bookID: String { model.selectedBookID ?? "" }
    private var dateKey: String { BusinessDates.key(date) }
    private var editingAllowed: Bool {
        guard let book = model.selectedBook, book.isAvailable, !account.isEmpty else { return false }
        return ["OWNER", "SUPERADMIN", "EDITOR"].contains(book.role.uppercased())
    }

    private var existingRemote: Bool {
        model.snapshot.business.contains {
            $0.date == dateKey && model.snapshot.testStores.first(where: { $0.id == storeID })?.name == $0.storeName
        }
    }

    private func loadDraft() {
        guard !storeID.isEmpty else { return }
        if let draft = BusinessLocalDraftStore.load(account: account, bookID: bookID)
            .first(where: { $0.date == dateKey && $0.storeID == storeID }) {
            lines = draft.collectors
            startTime = draft.startTime
            endTime = draft.endTime
            note = draft.note
            expense = draft.expense ?? ""
            openingStock = draft.openingStock ?? ""
            closingStock = draft.closingStock ?? ""
        } else {
            lines = [BusinessCollectorDraftLine()]
            startTime = ""
            endTime = ""
            note = ""
            expense = ""
            openingStock = ""
            closingStock = ""
        }
        saved = false
        errorText = nil
        loadAttempt()
    }

    private func loadAttempt() {
        let draft = BusinessLocalDraftStore.load(account: account, bookID: bookID)
            .first { $0.date == dateKey && $0.storeID == storeID }
        guard let id = draft?.id else { attempt = nil; return }
        do {
            attempt = try BusinessCommitOutbox().load(
                id: id, account: account, bookID: bookID)
        } catch {
            commitMessage = "上传保护记录不可读取，禁止写入：" + error.localizedDescription
        }
    }

    private func save() {
        guard attempt == nil, commitMessage?.hasPrefix("上传保护记录不可读取") != true else { return }
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
              let draft = BusinessLocalDraftStore.load(account: account, bookID: bookID)
                .first(where: { $0.date == dateKey && $0.storeID == storeID }) else { return }
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
            attempt = result
            commitMessage = result.label
        } catch {
            commitMessage = error.localizedDescription
        }
    }

    var body: some View {
        NavigationView {
            Form {
                Section(header: Text("营业日期与位置")) {
                    DatePicker("营业日期", selection: $date, displayedComponents: .date)
                        .onChange(of: date) { _ in loadDraft() }
                    Picker("营业位置", selection: $storeID) {
                        Text("请选择位置").tag("")
                        ForEach(model.snapshot.testStores) { store in
                            Text(store.name).tag(store.id)
                        }
                    }
                    .onChange(of: storeID) { _ in loadDraft() }
                    if existingRemote {
                        Label("云端已有此日此位置营业记录。本机草稿不会覆盖已有记录。",
                              systemImage: "exclamationmark.triangle")
                            .font(.caption).foregroundColor(.orange)
                    }
                }
                Section(header: Text("收款人及金额（元）")) {
                    ForEach($lines) { $line in
                        VStack(alignment: .leading, spacing: 8) {
                            HStack {
                                Picker("收款人", selection: $line.partnerSyncID) {
                                    Text("请选择收款人").tag("")
                                    ForEach(model.snapshot.testPartners) { person in
                                        Text(person.name).tag(person.id)
                                    }
                                }
                                if lines.count > 1 {
                                    Button(role: .destructive) {
                                        lines.removeAll { $0.id == line.id }
                                    } label: {
                                        Image(systemName: "minus.circle")
                                    }
                                }
                            }
                            amount("微信", value: $line.wechat)
                            amount("支付宝", value: $line.alipay)
                            amount("现金", value: $line.cash)
                        }
                    }
                    if lines.count < 12 {
                        Button {
                            lines.append(BusinessCollectorDraftLine())
                        } label: {
                            Label("增加收款人", systemImage: "person.badge.plus")
                        }
                    }
                    if let totals = try? BusinessCollectorDraftPolicy.totals(lines) {
                        Text(String(format: "微信 ¥%.2f · 支付宝 ¥%.2f · 现金 ¥%.2f",
                                    Double(totals.wechat)/100,
                                    Double(totals.alipay)/100,
                                    Double(totals.cash)/100))
                            .font(.caption.bold())
                    }
                }
                Section(header: Text("营业成本与利润依据（必填）")) {
                    amount("支出", value: $expense)
                    amount("开摊库存估值", value: $openingStock)
                    amount("收摊库存估值", value: $closingStock)
                    Text("利润按安卓原规则：收款总额＋收摊库存－开摊库存－支出。必须确认实际数据；不能为了上传直接全部填零。")
                        .font(.caption).foregroundColor(.secondary)
                }
                Section(header: Text("实际营业时间")) {
                    TextField("开摊 HH:mm，例如 16:00", text: $startTime)
                    TextField("收摊 HH:mm，例如 00:10", text: $endTime)
                    Text("两项同时填写或同时留空；跨午夜仅表示营业时段，不自动生成凌晨收款扣减。")
                        .font(.caption).foregroundColor(.secondary)
                    TextField("本机备注（不参与结算）", text: $note)
                }
                Section {
                    Button {
                        save()
                    } label: {
                        Label("保存本机营业草稿", systemImage: "square.and.arrow.down")
                    }
                    .disabled(!editingAllowed || storeID.isEmpty || attempt != nil || submitting)
                    if saved {
                        Label("本机草稿已保存，未提交云端", systemImage: "checkmark.circle")
                            .foregroundColor(.green)
                    }
                    if let errorText {
                        Text(errorText).font(.caption).foregroundColor(.orange)
                    }
                    if let attempt {
                        Text(attempt.label).font(.caption).foregroundColor(.orange)
                        Button {
                            Task { await checkOriginalOperation() }
                        } label: {
                            Label("查询原操作（不再次提交）", systemImage: "shield.lefthalf.filled")
                        }
                        .disabled(submitting)
                    } else {
                        Button {
                            Task { await commitDraft() }
                        } label: {
                            Label("提交正式营业", systemImage: "arrow.up.circle")
                        }
                        .disabled(!editingAllowed || !saved || submitting ||
                                  (!model.businessTestBookAllowed || model.businessCommitCapabilities?.allowsWrite != true))
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
