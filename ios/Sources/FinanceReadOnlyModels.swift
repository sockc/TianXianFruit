import Foundation

/// Read only persisted settlement snapshots. These are NOT recomputed amounts
/// and must never be used to mark a transfer settled on the server.
struct FinanceProfitRow: Identifiable {
    let id: String
    let date: String
    let partner: String
    let ratio: Double
    let allocated: Double
    init?(_ event: SyncEvent) {
        guard event.tableName == "profit_distribution", !event.isDeletion,
              let date = event.payload["date"]?.text, !date.isEmpty else { return nil }
        id = event.syncId
        self.date = date
        partner = event.payload["partner_name"]?.text ?? "未知合伙人"
        ratio = event.payload["ratio"]?.decimal ?? 0
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
    }
}

struct FinanceDailyRow: Identifiable {
    let id: String
    let date: String
    let revenue: Double
    let purchase: Double
    let profit: Double
    let status: Int
    init?(_ event: SyncEvent) {
        guard event.tableName == "daily_cash_settlement", !event.isDeletion,
              let date = event.payload["date"]?.text, !date.isEmpty else { return nil }
        id = event.syncId
        self.date = date
        revenue = event.payload["revenue"]?.decimal ?? 0
        purchase = event.payload["purchase_cost"]?.decimal ?? 0
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
