import SwiftUI

/// Build 44 immutable preparation is accepted only by a dedicated, test-only
/// V1.0.31 atomic endpoint. The Android settlement algorithms stay authoritative.
enum TXSettlementTestAction: String, CaseIterable, Identifiable, Codable {
    case partial, full, reset, generate, cutoff, profitShare
    var id: String { rawValue }
    var title: String {
        switch self {
        case .partial: return "部分结算"
        case .full: return "全额结算"
        case .reset: return "恢复未结"
        case .generate: return "生成日结方案"
        case .cutoff: return "结清截至今日"
        case .profitShare: return "利润分配"
        }
    }
    var canPreviewSavedTransfer: Bool {
        self == .partial || self == .full || self == .reset
    }
}

/// Future server implementation must accept an immutable operation identity
/// and the expected cloud versions; a network timeout may be recovered only
/// with an operation GET, never by resubmitting under a different UUID.
struct TXSettlementLocalPreparation: Equatable, Identifiable, Codable {
    let id: String
    let account: String
    let bookID: String
    let date: String
    let action: TXSettlementTestAction
    let dailySyncID: String
    let dailyRowVersion: Int64
    let transferSyncID: String
    let transferRowVersion: Int64
    let expectedSettledCents: Int64
    let targetSettledCents: Int64

    var verificationSummary: String {
        "仅本机核对 / 未提交\n" +
        "测试账号: \(account)\n账本: \(bookID)\n营业日: \(date)\n" +
        "操作: \(action.title)\n操作ID: \(id)\n" +
        "日结原始ID: \(dailySyncID) v\(dailyRowVersion)\n" +
        "转账原始ID: \(transferSyncID) v\(transferRowVersion)\n" +
        "当前登记: \(TXSettlementPreparationPolicy.yuan(expectedSettledCents)) 元\n" +
        "目标登记: \(TXSettlementPreparationPolicy.yuan(targetSettledCents)) 元"
    }
}

enum TXSettlementPreparationError: LocalizedError {
    case forbiddenScope
    case noUniqueDaily
    case invalidTransfer
    case unsupportedAction
    case badAmount
    case unchanged
    case unverifiedSnapshot

    var errorDescription: String? {
        switch self {
        case .forbiddenScope: return "仅在线、有编辑权限的 iOS_TEST_ 测试账本可以准备结算操作"
        case .noUniqueDaily: return "必须存在唯一已同步的正式日结快照"
        case .invalidTransfer: return "请选择同日有效的已保存当日转账方案"
        case .unsupportedAction: return "生成日结、周期结清及利润分配需服务端事务计算，不能在 iOS 预演生成"
        case .badAmount: return "目标累计结算金额非法，必须在 0 至方案总额之间并保留两位小数"
        case .unchanged: return "目标金额与已登记金额相同，无需修改"
        case .unverifiedSnapshot: return "同步记录缺少有效的版本或无法确认原始状态，请重新同步核对"
        }
    }
}

enum TXSettlementPreparationPolicy {
    static func yuan(_ cents: Int64) -> String {
        String(format: "%.2f", Double(cents) / 100)
    }

    /// Exactly two decimal places and nonnegative; avoids floating point
    /// source-amount drift for the proposed settlement target.
    static func cents(_ raw: String) -> Int64? {
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, text.count <= 15,
              text.range(of: #"^\d{1,10}(\.\d{1,2})?$"#,
                         options: .regularExpression) != nil,
              let value = Decimal(string: text, locale: Locale(identifier: "en_US_POSIX")),
              value <= Decimal(1_000_000) else { return nil }
        return NSDecimalNumber(decimal: value * 100).int64Value
    }

    static func persistedCents(_ value: Double) -> Int64? {
        guard value.isFinite, value >= 0, value <= 1_000_000,
              abs(value * 100 - (value * 100).rounded()) < 0.0001 else { return nil }
        return Int64((value * 100).rounded())
    }

    static func prepare(book: CloudBook?, account: Account?, offline: Bool,
                        snapshot: ReadOnlySnapshot, date: String,
                        transferID: String, action: TXSettlementTestAction,
                        typedAmount: String, operationID: String = UUID().uuidString)
        throws -> TXSettlementLocalPreparation {
        guard TXTestBookAccess.isCandidate(book, account: account, offline: offline),
              let book, let account, !account.username.isEmpty,
              UUID(uuidString: book.id) != nil else {
            throw TXSettlementPreparationError.forbiddenScope
        }
        guard action.canPreviewSavedTransfer else {
            throw TXSettlementPreparationError.unsupportedAction
        }
        guard UUID(uuidString: operationID) != nil,
              BusinessDates.parse(date) != nil else {
            throw TXSettlementPreparationError.unverifiedSnapshot
        }
        let daily = snapshot.financeDaily.filter { $0.date == date }
        guard daily.count == 1, let only = daily.first else {
            throw TXSettlementPreparationError.noUniqueDaily
        }
        guard only.statusProvided,
              let dailyEvent = snapshot.records["daily_cash_settlement|" + only.id],
              dailyEvent.rowVersion > 0 else {
            throw TXSettlementPreparationError.unverifiedSnapshot
        }
        guard let transfer = snapshot.financeTransfers.first(where: {
            $0.id == transferID && $0.date == date &&
            $0.kind.uppercased() == "DAILY"
        }), TXSettlementRecordPolicy.transferRegistration(transfer) != .inconsistent else {
            throw TXSettlementPreparationError.invalidTransfer
        }
        guard let event = snapshot.records["settlement_transfer|" + transferID],
              event.rowVersion > 0,
              let dailyID = AndroidNumericIdentity.exact(dailyEvent.payload["id"]),
              AndroidNumericIdentity.exact(event.payload["settlement_id"]) == dailyID,
              AndroidNumericIdentity.exact(event.payload["id"]) != nil else {
            throw TXSettlementPreparationError.unverifiedSnapshot
        }
        guard let original = persistedCents(transfer.recordedSettled),
              let maximum = persistedCents(transfer.amount) else {
            throw TXSettlementPreparationError.invalidTransfer
        }
        let target: Int64
        switch action {
        case .partial:
            guard let parsed = cents(typedAmount),
                  parsed > 0, parsed < maximum else {
                throw TXSettlementPreparationError.badAmount
            }
            target = parsed
        case .full:
            target = maximum
        case .reset:
            target = 0
        default:
            throw TXSettlementPreparationError.unsupportedAction
        }
        guard target != original else { throw TXSettlementPreparationError.unchanged }
        return TXSettlementLocalPreparation(
            id: operationID.uppercased(),
            account: account.username,
            bookID: book.id,
            date: date,
            action: action,
            dailySyncID: only.id,
            dailyRowVersion: dailyEvent.rowVersion,
            transferSyncID: transfer.id,
            transferRowVersion: event.rowVersion,
            expectedSettledCents: original,
            targetSettledCents: target
        )
    }

}

struct TXSettlementTestWorkbenchView: View {
    @EnvironmentObject private var model: LedgerViewModel
    @State private var date = Date()
    @State private var transferID = ""
    @State private var action: TXSettlementTestAction = .full
    @State private var amount = ""
    @State private var prepared: TXSettlementLocalPreparation?
    @State private var errorText = ""
    @State private var copied = false
    @State private var activeOperation: TXSettlementCommitAttempt?
    @State private var operationLockError = ""

    /// Restore the saved immutable intent without generating a new operation UUID.
    private func restoreOperation() {
        guard let book = model.selectedBook, let account = model.account else {
            activeOperation = nil
            operationLockError = ""
            return
        }
        do {
            activeOperation = try TXSettlementCommitStore().load(
                account: account.username, book: book.id)
            operationLockError = ""
        } catch {
            activeOperation = nil
            operationLockError = "本机结算操作锁读取失败；禁止新结算，请保留数据核对原操作"
        }
    }

    private var operationLocked: Bool {
        !operationLockError.isEmpty ||
        (activeOperation != nil && activeOperation?.phase != .confirmed)
    }

    private var candidate: Bool {
        TXTestBookAccess.isCandidate(model.selectedBook, account: model.account,
                                     offline: model.isUsingCachedAuthorization)
    }
    private var dateKey: String { BusinessDates.key(date) }
    private var validDay: Bool {
        model.snapshot.financeDaily.filter { $0.date == dateKey }.count == 1
    }
    private var transfers: [FinanceTransferRow] {
        model.snapshot.financeTransfers.filter {
            $0.date == dateKey && $0.kind.uppercased() == "DAILY"
        }.sorted { $0.id < $1.id }
    }
    private var chosen: FinanceTransferRow? {
        transfers.first(where: { $0.id == transferID })
    }
    private func clearPreview() { prepared = nil; errorText = ""; copied = false }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Label("当日结算", systemImage: "checkmark.shield")
                    .font(.headline)

                if let previous = activeOperation {
                    VStack(alignment: .leading, spacing: 8) {
                        Label(previous.phase == .confirmed ? "上次结算已核对" : "存在待核对结算",
                              systemImage: previous.phase == .confirmed ?
                                "checkmark.circle" : "lock.shield")
                            .font(.subheadline.bold())
                            .foregroundColor(previous.phase == .confirmed ?
                                             TXTheme.primary : .orange)
                        Text(previous.label).font(.caption)
                        Text("营业日：" + previous.proposal.date +
                             " · 目标：" +
                             TXSettlementPreparationPolicy.yuan(
                                previous.proposal.targetSettledCents) + " 元")
                            .font(.caption)
                        NavigationLink(
                            destination: TXSettlementAtomicSubmitView(
                                proposal: previous.proposal)) {
                            Label(previous.phase == .confirmed ?
                                  "查看上次结算记录" : "恢复原操作 / 查询同步结果",
                                  systemImage: "arrow.clockwise.circle")
                        }.font(.caption)
                    }
                    .padding(12)
                    .background(Color(UIColor.secondarySystemGroupedBackground),
                                in: RoundedRectangle(cornerRadius: 11))
                }
                if !operationLockError.isEmpty {
                    Label(operationLockError, systemImage: "exclamationmark.shield")
                        .font(.caption).foregroundColor(.red)
                }
                if !candidate {
                    Label("当前账本仅可查看结算记录，不能修改。",
                          systemImage: "lock.shield.fill")
                        .foregroundColor(.orange)
                } else {
                    TXDayBar(date: $date, label: "结算日期", allowFuture: false)
                        .onChange(of: date) { _ in
                            transferID = transfers.count == 1 ? transfers[0].id : ""
                            amount = ""
                            clearPreview()
                        }
                    Picker("结算操作", selection: $action) {
                        ForEach(TXSettlementTestAction.allCases.filter { $0.canPreviewSavedTransfer }) { item in
                            Text(item.title).tag(item)
                        }
                    }
                    .pickerStyle(.menu)
                    .onChange(of: action) { _ in clearPreview() }

                    if !action.canPreviewSavedTransfer {
                        Label("该操作需要服务端资金中心、利润及历史资金规则的权威计算。目前只保留入口，不生成推测结算方案。",
                              systemImage: "exclamationmark.shield")
                            .font(.caption).foregroundColor(.orange)
                    } else if !validDay {
                        Label("当前日期没有唯一正式日结记录，不能准备转账更改。请先在安卓核对。",
                              systemImage: "exclamationmark.triangle")
                            .font(.caption).foregroundColor(.orange)
                    } else if transfers.isEmpty {
                        Text("这一天没有已保存的当日转账方案，不能凭合伙人资金差额自行生成。")
                            .font(.caption).foregroundColor(.secondary)
                    } else {
                        Picker("选择原始转账", selection: $transferID) {
                            Text("请选择一笔转账").tag("")
                            ForEach(transfers) { item in
                                Text(item.from + " → " + item.to +
                                     " · " + item.amount.formatted(.currency(code: "CNY")))
                                    .tag(item.id)
                            }
                        }
                        .pickerStyle(.menu)
                        .onChange(of: transferID) { _ in
                            if let chosen {
                                amount = TXSettlementPreparationPolicy.yuan(
                                    TXSettlementPreparationPolicy.persistedCents(
                                        chosen.recordedSettled) ?? 0)
                            }
                            clearPreview()
                        }
                        if let chosen {
                            VStack(alignment: .leading, spacing: 7) {
                                row("云端方案金额", chosen.amount)
                                row("已登记累计金额", chosen.recordedSettled)
                                Text("登记进度：" +
                                     TXSettlementRecordPolicy.transferRegistration(chosen).label)
                                    .font(.caption).foregroundColor(.secondary)
                                if action == .partial {
                                    HStack {
                                        Text("目标累计已结算（元）").font(.caption)
                                        Spacer(minLength: 5)
                                        TXFastNumericField(text: $amount,
                                            label: "目标累计已结算金额")
                                            .frame(width: 128, height: 36)
                                            .onChange(of: amount) { _ in clearPreview() }
                                    }
                                } else {
                                    Text(action == .full
                                        ? "将准备把累计登记金额改为方案总额"
                                        : "将准备把累计登记金额改为 0 元")
                                        .font(.caption).foregroundColor(.secondary)
                                }
                            }
                            .padding(12)
                            .background(Color(UIColor.secondarySystemGroupedBackground),
                                        in: RoundedRectangle(cornerRadius: 11))
                        }
                        Button("核对本次结算") {
                            clearPreview()
                            do {
                                prepared = try TXSettlementPreparationPolicy.prepare(
                                    book: model.selectedBook,
                                    account: model.account,
                                    offline: model.isUsingCachedAuthorization,
                                    snapshot: model.snapshot,
                                    date: dateKey,
                                    transferID: transferID,
                                    action: action,
                                    typedAmount: amount)
                            } catch { errorText = error.localizedDescription }
                        }
                        .buttonStyle(.borderedProminent)
                        .disabled(chosen == nil || operationLocked)
                    }

                    if !errorText.isEmpty {
                        Text(errorText).font(.caption).foregroundColor(.red)
                    }
                    if let prepared {
                        VStack(alignment: .leading, spacing: 9) {
                            Label("待确认 · 尚未提交", systemImage: "doc.text")
                                .font(.subheadline.bold())
                            Text("登记金额：" +
                                 TXSettlementPreparationPolicy.yuan(
                                    prepared.expectedSettledCents) +
                                 " → " +
                                 TXSettlementPreparationPolicy.yuan(
                                    prepared.targetSettledCents) + " 元")
                                .font(.subheadline.bold())
                            DisclosureGroup("查看详细核对信息") {
                                Text(prepared.verificationSummary)
                                    .font(.caption2)
                                    .textSelection(.enabled)
                                Button(copied ? "已复制" : "复制详情") {
                                    UIPasteboard.general.string = prepared.verificationSummary
                                    copied = true
                                }.font(.caption)
                            }
                            .font(.caption).foregroundColor(.secondary)
                        }
                        .padding(12)
                        .background(TXTheme.header,
                                    in: RoundedRectangle(cornerRadius: 11))
                    }
                }

                if let prepared, !operationLocked {
                    NavigationLink(destination: TXSettlementAtomicSubmitView(proposal: prepared)) {
                        Label("确认结算", systemImage: "checkmark.shield")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                }
                SyncFooter()
            }.padding(12)
        }
        .navigationTitle("当日结算")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            restoreOperation()
            if transfers.count == 1 { transferID = transfers[0].id }
        }
        .onChange(of: model.selectedBookID) { _ in
            transferID = ""; amount = ""; clearPreview()
            restoreOperation()
        }
        .onChange(of: model.currentCursor) { _ in
            clearPreview()
            restoreOperation()
        }
    }

    private func row(_ title: String, _ value: Double) -> some View {
        HStack {
            Text(title).font(.caption).foregroundColor(.secondary)
            Spacer()
            Text(value.formatted(.currency(code: "CNY")))
                .font(.subheadline.bold())
        }
    }
}
