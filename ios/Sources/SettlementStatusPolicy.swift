import Foundation

/// This policy never calculates Android's *effective* daily settlement
/// status. Android may treat a later CUTOFF/CUTOFF_CENTER transfer as covering
/// an older daily transfer, depending on settlement center and focused partner.
/// The iOS sync snapshot alone is not an authoritative effective-state API.
enum TXRecordedSettlementState: Equatable {
    case noDailySnapshot
    case statusMissing
    case notSettled
    case partiallySettled
    case savedAsSettled
    case unknown(Int)

    static func of(_ snapshot: FinanceDailyRow?) -> TXRecordedSettlementState {
        guard let snapshot else { return .noDailySnapshot }
        guard snapshot.statusProvided else { return .statusMissing }
        switch snapshot.status {
        case 0: return .notSettled
        case 1: return .savedAsSettled
        case 2: return .partiallySettled
        default: return .unknown(snapshot.status)
        }
    }

    var label: String {
        switch self {
        case .noDailySnapshot: return "无日结记录"
        case .statusMissing: return "原始状态字段缺失"
        case .notSettled: return "原始未结算"
        case .partiallySettled: return "原始部分结算"
        case .savedAsSettled: return "原始已结算"
        case .unknown(let value): return "未知原始状态 \(value)"
        }
    }
}

/// The following names describe saved transfer registration, not receipt of
/// money at a bank and not the state of a partner's cumulative fund balance.
enum TXTransferRegistration: Equatable {
    case noTransferRequired
    case notRegistered
    case partiallyRegistered
    case fullyRegistered
    case inconsistent

    var label: String {
        switch self {
        case .noTransferRequired: return "零金额方案"
        case .notRegistered: return "未登记结算"
        case .partiallyRegistered: return "已登记部分金额"
        case .fullyRegistered: return "已登记全额"
        case .inconsistent: return "原始金额异常"
        }
    }
}

enum TXSettlementRecordPolicy {
    static let moneyTolerance = 0.005

    static func transferRegistration(_ transfer: FinanceTransferRow) -> TXTransferRegistration {
        let amount = transfer.amount
        let recorded = transfer.recordedSettled
        guard amount.isFinite, recorded.isFinite, amount >= 0,
              recorded >= 0, recorded <= amount + moneyTolerance else {
            return .inconsistent
        }
        if amount <= moneyTolerance {
            return recorded <= moneyTolerance ? .noTransferRequired : .inconsistent
        }
        if recorded <= moneyTolerance { return .notRegistered }
        if recorded + moneyTolerance >= amount { return .fullyRegistered }
        return .partiallyRegistered
    }

    /// A *possible* later cutoff affecting the given business day, not proof
    /// that this business day's settlement or every partner was cleared.
    /// Android also checks center IDs, focus ID and transfer relationships.
    static func isPotentialCutoff(_ transfer: FinanceTransferRow,
                                  on businessDate: String,
                                  partner: String = "") -> Bool {
        let kind = transfer.kind.uppercased()
        guard kind == "CUTOFF" || kind == "CUTOFF_CENTER",
              transfer.confirmedAt > 0,
              transfer.recordedSettled > moneyTolerance,
              transfer.rangeStartDate.count == 10,
              transfer.rangeEndDate.count == 10,
              transfer.rangeStartDate <= transfer.rangeEndDate,
              transfer.rangeStartDate <= businessDate,
              transfer.rangeEndDate >= businessDate else { return false }
        if partner.isEmpty { return true }
        return transfer.focusPartnerName == partner ||
               transfer.from == partner || transfer.to == partner
    }
}
