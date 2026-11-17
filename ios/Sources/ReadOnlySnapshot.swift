import Foundation

/// Materialized *cloud* rows only. The local SQL cache has no business-table
/// write queue or settlement calculations. Tombstones retain server versions.
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

    var inventory: [InventoryRow] {
        records.values.compactMap(InventoryRow.init)
            .sorted { $0.date == $1.date ? $0.name < $1.name : $0.date > $1.date }
    }

    var latestInventoryDate: String? { inventory.first?.date }
    var latestInventory: [InventoryRow] {
        guard let date = latestInventoryDate else { return [] }
        return inventory.filter { $0.date == date }
    }

    var locations: [String] {
        Array(Set(business.map(\.storeName))).sorted()
    }

    var buyers: [String] {
        Array(Set(purchases.map(\.buyer))).sorted()
    }

    func sales(since: String, until: String, location: String = "") -> [StoreDailyRow] {
        business.filter { ($0.date >= since && $0.date <= until) &&
            (location.isEmpty || $0.storeName == location) }
    }

    func purchases(since: String, until: String, buyer: String = "") -> [PurchaseOrderRow] {
        purchases.filter { ($0.date >= since && $0.date <= until) &&
            (buyer.isEmpty || $0.buyer == buyer) }
    }

    var totalRevenue: Double { business.reduce(0) { $0 + $1.revenue } }
    var totalProfit: Double { business.reduce(0) { $0 + $1.profit } }
    var totalPurchases: Double { purchases.reduce(0) { $0 + $1.totalCost } }
}

enum BusinessDates {
    static var calendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Asia/Shanghai")!
        return calendar
    }
    static func key(_ date: Date) -> String {
        let format = DateFormatter()
        format.calendar = calendar
        format.timeZone = calendar.timeZone
        format.locale = Locale(identifier: "en_US_POSIX")
        format.dateFormat = "yyyy-MM-dd"
        return format.string(from: date)
    }
    static func recentDays(_ count: Int, from now: Date = Date()) -> [String] {
        guard count > 0 else { return [] }
        return (0..<count).reversed().map { daysAgo($0, from: now) }
    }

    static func parse(_ date: String) -> Date? {
        let format = DateFormatter()
        format.calendar = calendar
        format.timeZone = calendar.timeZone
        format.locale = Locale(identifier: "en_US_POSIX")
        format.dateFormat = "yyyy-MM-dd"
        return format.date(from: date)
    }

    static func daysAgo(_ count: Int, from now: Date = Date()) -> String {
        let day = calendar.date(byAdding: .day, value: -count, to: now) ?? now
        return key(day)
    }
}
