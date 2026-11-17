import Foundation

/// Deliberately in-memory in V0.1: no local write queue, no dirty rows and
/// no business-table SQL mutations. Always rebuild from cloud on book switch.
struct ReadOnlySnapshot {
    private(set) var records: [String: SyncEvent] = [:]
    private(set) var versions: [String: Int64] = [:]

    mutating func apply(_ changes: [SyncEvent]) {
        for event in changes {
            let key = event.logicalKey
            if let previous = versions[key], event.rowVersion < previous { continue }
            versions[key] = event.rowVersion
            if event.isDeletion {
                records.removeValue(forKey: key)
            } else {
                records[key] = event
            }
        }
    }

    var business: [StoreDailyRow] {
        records.values.compactMap(StoreDailyRow.init)
            .sorted { $0.date == $1.date ? $0.id < $1.id : $0.date > $1.date }
    }

    var purchases: [PurchaseOrderRow] {
        records.values.compactMap(PurchaseOrderRow.init)
            .sorted { $0.date == $1.date ? $0.id < $1.id : $0.date > $1.date }
    }

    var totalRevenue: Double { business.reduce(0) { $0 + $1.revenue } }
    var totalProfit: Double { business.reduce(0) { $0 + $1.profit } }
    var totalPurchases: Double { purchases.reduce(0) { $0 + $1.totalCost } }
}
