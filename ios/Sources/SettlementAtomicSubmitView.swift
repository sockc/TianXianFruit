import SwiftUI

@MainActor
final class TXSettlementAtomicCoordinator: ObservableObject {
    @Published private(set) var attempt: TXSettlementCommitAttempt?
    @Published private(set) var busy = false
    @Published private(set) var message = ""
    private let store: TXSettlementCommitStore?
    init() { store = try? TXSettlementCommitStore() }

    func restore(_ proposal: TXSettlementLocalPreparation) {
        guard !busy else { return }
        do {
            guard let store else { throw TXSettlementAtomicError.storage }
            attempt = try store.load(account: proposal.account, book: proposal.bookID)
            message = attempt?.label ?? "尚未实际提交；服务端确认前操作将持久锁定"
        } catch { message = error.localizedDescription }
    }

    func submit(_ proposal: TXSettlementLocalPreparation,
                model: LedgerViewModel) async {
        guard !busy else { return }
        busy = true
        defer { busy = false }
        do {
            guard let store else { throw TXSettlementAtomicError.storage }
            let prior = try store.load(account: proposal.account, book: proposal.bookID)
            guard TXSettlementSubmissionPolicy.canBegin(proposal, prior: prior)
            else { throw TXSettlementAtomicError.locked }
            guard TXSettlementCommitReadback.original(proposal, in: model.snapshot)
            else { throw TXSettlementAtomicError.stale }
            // Save immutable account/book/UUID/expected versions BEFORE any POST.
            let locked = TXSettlementCommitAttempt(
                proposal: proposal, phase: .unknown, receipt: nil, updatedAt: Date())
            try store.save(locked)
            attempt = locked
            message = locked.label
            let receipt = try await model.commitSettlement(proposal)
            guard receipt.matches(proposal) else {
                throw TXSettlementAtomicError.invalidReceipt
            }
            var accepted = locked
            accepted.phase = .accepted
            accepted.receipt = receipt
            accepted.updatedAt = Date()
            try store.save(accepted)
            attempt = accepted
            await model.refresh()
            try confirmReadback(model)
        } catch {
            message = error.localizedDescription + "；不得使用新操作ID重发"
        }
    }

    func recover(model: LedgerViewModel) async {
        guard !busy, var previous = attempt,
              previous.phase != .confirmed else { return }
        busy = true
        defer { busy = false }
        do {
            guard let store else { throw TXSettlementAtomicError.storage }
            let receipt: TXSettlementCommitReceipt
            if let saved = previous.receipt { receipt = saved }
            else {
                guard let fetched = try await model.findSettlementOperation(previous.proposal)
                else { throw TXSettlementAtomicError.unknown }
                receipt = fetched
            }
            guard receipt.matches(previous.proposal) else {
                throw TXSettlementAtomicError.invalidReceipt
            }
            previous.receipt = receipt
            previous.phase = .accepted
            previous.updatedAt = Date()
            try store.save(previous)
            attempt = previous
            await model.refresh(forceFull: true)
            try confirmReadback(model)
        } catch {
            message = error.localizedDescription + "；只允许查询同一操作ID"
        }
    }

    private func confirmReadback(_ model: LedgerViewModel) throws {
        guard let store, var saved = attempt, let receipt = saved.receipt else {
            throw TXSettlementAtomicError.storage
        }
        let assessment = TXSettlementCommitReadback.assessment(
            saved.proposal, receipt: receipt,
            snap: model.snapshot, cursor: model.currentCursor)
        guard assessment == .verified else {
            message = assessment.explanation
            return
        }
        saved.phase = .confirmed
        saved.updatedAt = Date()
        try store.save(saved)
        attempt = saved
        message = saved.label
    }
}

struct TXSettlementAtomicSubmitView: View {
    @EnvironmentObject private var model: LedgerViewModel
    @StateObject private var coordinator = TXSettlementAtomicCoordinator()
    let proposal: TXSettlementLocalPreparation

    private var matchingTestBook: Bool {
        TXTestBookAccess.isCandidate(model.selectedBook, account: model.account,
                                     offline: model.isUsingCachedAuthorization) &&
        model.selectedBookID?.lowercased() == proposal.bookID.lowercased() &&
        model.account?.username.lowercased() == proposal.account.lowercased()
    }
    private var canSubmit: Bool {
        matchingTestBook && model.settlementCommitCapabilities?.allowsWrite == true
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Label("测试账本 · 原子结算", systemImage: "checkmark.shield.fill")
                    .font(.headline).foregroundColor(TXTheme.primary)
                Text(proposal.verificationSummary)
                    .font(.caption).textSelection(.enabled)
                if !matchingTestBook {
                    Label("账号或测试账本已变化，资金操作禁止执行。",
                          systemImage: "lock.shield.fill")
                        .font(.caption).foregroundColor(.orange)
                } else if !canSubmit {
                    Label("服务器 V1.0.31 原子结算尚未启用或权限不匹配。",
                          systemImage: "lock.shield.fill")
                        .font(.caption).foregroundColor(.orange)
                } else {
                    Label("只修改已保存的单笔当日转账金额及其日结状态，保留安卓计算规则。",
                          systemImage: "info.circle")
                        .font(.caption).foregroundColor(.secondary)
                }
                if let attempt = coordinator.attempt {
                    Text(attempt.label).font(.subheadline.bold())
                        .foregroundColor(attempt.phase == .confirmed ?
                                         TXTheme.primary : .orange)
                    Text("操作ID：" + attempt.proposal.id)
                        .font(.caption2).textSelection(.enabled)
                }
                Button {
                    Task { await coordinator.submit(proposal, model: model) }
                } label: {
                    Label("确认登记测试账本结算", systemImage: "checkmark.shield")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .disabled(!canSubmit || coordinator.busy ||
                          !TXSettlementSubmissionPolicy.canBegin(
                            proposal, prior: coordinator.attempt))
                Button {
                    Task { await coordinator.recover(model: model) }
                } label: {
                    Label("查询原操作 / 同步回读", systemImage: "arrow.clockwise")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .disabled(!matchingTestBook || coordinator.busy ||
                          coordinator.attempt == nil ||
                          coordinator.attempt?.phase == .confirmed)
                if coordinator.busy { ProgressView() }
                if !coordinator.message.isEmpty {
                    Text(coordinator.message)
                        .font(.caption).foregroundColor(.secondary)
                }
                Text("已完成操作ID禁止再次提交；超时只查原ID。若其他设备随后改写转账金额，会提示版本已变更并保持锁定。周期结清与利润分配仍未开放，正式账本只读。")
                    .font(.caption2).foregroundColor(.secondary)
                SyncFooter()
            }.padding(12)
        }
        .navigationTitle("结算操作核对")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            coordinator.restore(proposal)
            if matchingTestBook {
                await model.refreshSettlementCapabilities()
            }
        }
    }
}
