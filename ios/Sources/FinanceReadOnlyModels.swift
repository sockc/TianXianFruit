import Foundation

/// Read only persisted settlement snapshots. These are NOT recomputed amounts
/// and must never be used to mark a transfer settled on the server.
struct FinanceProfitRow: Identifiable {
    let id: String
    let date: String
    let partner: String
    let ratio: Double
    let role: String
    let sourceProfit: Double
    let allocated: Double
    init?(_ event: SyncEvent) {
        guard event.tableName == "profit_distribution", !event.isDeletion,
              let date = event.payload["date"]?.text, !date.isEmpty else { return nil }
        id = event.syncId
        self.date = date
        partner = event.payload["partner_name"]?.text ?? "未知合伙人"
        ratio = event.payload["ratio"]?.decimal ?? 0
        role = event.payload["role"]?.text ?? ""
        sourceProfit = event.payload["source_profit"]?.decimal ?? 0
        allocated = event.payload["allocated_profit"]?.decimal ?? 0
    }
}

struct FinanceTransferRow: Identifiable {
    let id: String
    let date: String
    let from: String
    let to: String
    let amount: Double
    let recordedSettled: Double
    let kind: String
    let rangeStartDate: String
    let rangeEndDate: String
    let businessDayCount: Int
    var kindLabel: String {
        switch kind.uppercased() {
        case "DAILY": return "当日资金方案"
        case "CUTOFF": return "周期资金结算"
        case "CENTER": return "资金中心结算"
        case "LEGACY": return "历史资金记录"
        default: return "资金记录（\(kind)）"
        }
    }
    /// Date of the record is NOT necessarily its covered business period.
    var periodLabel: String? {
        guard !rangeStartDate.isEmpty, !rangeEndDate.isEmpty else { return nil }
        return rangeStartDate == rangeEndDate
            ? "覆盖营业日 " + rangeStartDate
            : "覆盖营业期间 " + rangeStartDate + " 至 " + rangeEndDate
    }
    init?(_ event: SyncEvent) {
        guard event.tableName == "settlement_transfer", !event.isDeletion,
              let date = event.payload["date"]?.text, !date.isEmpty else { return nil }
        id = event.syncId
        self.date = date
        from = event.payload["from_partner_name"]?.text ?? "未知"
        to = event.payload["to_partner_name"]?.text ?? "未知"
        amount = event.payload["amount"]?.decimal ?? 0
        recordedSettled = event.payload["settled_amount"]?.decimal ?? 0
        kind = event.payload["settlement_kind"]?.text ?? "DAILY"
        rangeStartDate = event.payload["range_start_date"]?.text ?? ""
        rangeEndDate = event.payload["range_end_date"]?.text ?? ""
        businessDayCount = Int(event.payload["business_day_count"]?.decimal ?? 0)
    }
}

/// A single persisted partner-side cash settlement record, not a recomputed
/// share, overall wallet balance or cross-day funds netting.
struct FinanceSettlementPartnerRow: Identifiable {
    let id: String
    let date: String
    let partner: String
    let purchasePaid: Double
    let expensePaid: Double
    let revenueReceived: Double
    let profitShare: Double
    let shouldKeep: Double
    let balance: Double

    init?(_ event: SyncEvent) {
        guard event.tableName == "settlement_partner", !event.isDeletion,
              let date = event.payload["date"]?.text, !date.isEmpty else { return nil }
        id = event.syncId
        self.date = date
        partner = event.payload["partner_name"]?.text ?? "未知合伙人"
        purchasePaid = event.payload["purchase_paid"]?.decimal ?? 0
        expensePaid = event.payload["expense_paid"]?.decimal ?? 0
        revenueReceived = event.payload["revenue_received"]?.decimal ?? 0
        profitShare = event.payload["profit_share"]?.decimal ?? 0
        shouldKeep = event.payload["should_keep"]?.decimal ?? 0
        balance = event.payload["balance"]?.decimal ?? 0
    }
}

struct FinanceDailyRow: Identifiable {
    let id: String
    let date: String
    let revenue: Double
    let purchase: Double
    let expense: Double
    let profit: Double
    let status: Int
    init?(_ event: SyncEvent) {
        guard event.tableName == "daily_cash_settlement", !event.isDeletion,
              let date = event.payload["date"]?.text, !date.isEmpty else { return nil }
        id = event.syncId
        self.date = date
        revenue = event.payload["revenue"]?.decimal ?? 0
        purchase = event.payload["purchase_cost"]?.decimal ?? 0
        expense = event.payload["expense"]?.decimal ?? 0
        profit = event.payload["profit"]?.decimal ?? 0
        status = Int(event.payload["status"]?.decimal ?? 0)
    }
}

extension ReadOnlySnapshot {
    var financeProfitRows: [FinanceProfitRow] {
        records.values.compactMap(FinanceProfitRow.init).sorted { $0.date > $1.date }
    }
    var financeTransfers: [FinanceTransferRow] {
        records.values.compactMap(FinanceTransferRow.init).sorted { $0.date > $1.date }
    }
    var financeSettlementPartners: [FinanceSettlementPartnerRow] {
        records.values.compactMap(FinanceSettlementPartnerRow.init)
            .sorted { $0.date == $1.date ? $0.id < $1.id : $0.date > $1.date }
    }
    var financeDaily: [FinanceDailyRow] {
        records.values.compactMap(FinanceDailyRow.init).sorted { $0.date > $1.date }
    }

    var storeLabels: [String: String] {
        var map: [String: String] = [:]
        for event in records.values where event.tableName == "store" && !event.isDeletion {
            map[event.syncId] = event.payload["name"]?.text ?? "未命名位置"
        }
        return map
    }

    func dailySummary(date: String, storeName: String = "") -> (revenue: Double, profit: Double) {
        let matches = business.filter { $0.date == date &&
            (storeName.isEmpty || $0.storeName == storeName) }
        return (matches.reduce(0) { $0 + $1.revenue },
                matches.reduce(0) { $0 + $1.profit })
    }
}
