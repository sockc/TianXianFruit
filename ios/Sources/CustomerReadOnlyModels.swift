import Foundation

/// Customer data is a separate versioned API; never infer receipts from the
/// ledger's sale totals or the Android device-local payment database.
struct CustomerCapabilities: Decodable {
    let version: Int
    let supported: Bool
    let features: [String]

    func offers(_ name: String) -> Bool { features.contains(name) }
}

struct CustomerChangePage: Decodable {
    let changes: [CustomerChangeEvent]
    let nextCursor: Int64
    let hasMore: Bool

    enum CodingKeys: String, CodingKey {
        case changes
        case nextCursor = "next_cursor"
        case hasMore = "has_more"
    }
}

struct CustomerChangeEvent: Codable {
    let kind: String
    let key: String
    let revision: Int64
    let payload: [String: JSONValue]

    var identity: String { kind + "|" + key }
    var isDeleted: Bool { payload["deleted"]?.isTrue == true }
}

struct CustomerReceipt: Identifiable {
    let id: String
    let platform: String
    let date: String
    let hour: Int?
    let amount: Double
    let netAmount: Double
    let transactionReference: String
    let storeSyncID: String
    let sourceFile: String

    init?(_ event: CustomerChangeEvent) {
        guard event.kind == "RECEIPT", !event.isDeleted else { return nil }
        let source = event.payload
        let platform = source["platform"]?.text ?? ""
        let dedupeKey = source["dedupe_key"]?.text ?? ""
        let date = source["business_date"]?.text ?? ""
        guard ["WECHAT", "ALIPAY"].contains(platform),
              !dedupeKey.isEmpty, !date.isEmpty,
              event.key == platform + "|" + dedupeKey else { return nil }
        id = event.key
        self.platform = platform
        self.date = date
        let time = source["trade_time"]?.text ?? ""
        // The imported Chinese merchant export uses 'yyyy-MM-dd HH:mm:ss'.
        // Unparseable time remains unknown; it never goes into hour 00.
        let part = time.count >= 13 ? String(time.dropFirst(11).prefix(2)) : ""
        hour = part.count == 2 ? Int(part).flatMap { (0..<24).contains($0) ? $0 : nil } : nil
        amount = source["amount"]?.decimal ?? 0
        netAmount = source["net_amount"]?.decimal ?? 0
        transactionReference = source["transaction_ref"]?.text ?? ""
        storeSyncID = source["store_sync_id"]?.text ?? ""
        sourceFile = source["source_file_hash"]?.text ?? ""
    }
}

struct CustomerSummary {
    let paymentCount: Int
    let amount: Double
    var average: Double { paymentCount == 0 ? 0 : amount / Double(paymentCount) }
}

struct CustomerHour: Identifiable {
    let hour: Int
    let paymentCount: Int
    let amount: Double
    var id: Int { hour }
}

struct CustomerWeekdayComparison {
    let historicalDays: Int
    let current: CustomerSummary
    let priorDayAverageCount: Double
    let priorDayAverageAmount: Double
}

struct CustomerReadSnapshot: Codable {
    private(set) var events: [String: CustomerChangeEvent] = [:]

    mutating func apply(_ changes: [CustomerChangeEvent]) {
        for item in changes {
            guard !item.kind.isEmpty, !item.key.isEmpty, item.revision >= 0 else { continue }
            let key = item.identity
            if let old = events[key], item.revision < old.revision { continue }
            events[key] = item
        }
    }

    private var settings: (excludeLarge: Bool, threshold: Double) {
        guard let setting = events["SETTINGS|global"], !setting.isDeleted else {
            return (true, 2000)
        }
        let amount = setting.payload["large_payment_threshold"]?.decimal ?? 2000
        return (setting.payload["exclude_large_payments"]?.isTrue ?? true,
                amount >= 1 && amount.isFinite ? amount : 2000)
    }

    /// Only strictly positive, non-excluded QR payments are counted.
    /// Root transaction_ref exclusions follow Android's filtering policy.
    var receipts: [CustomerReceipt] {
        let raw = events.values.compactMap(CustomerReceipt.init)
        let limits = settings
        var exclusions = Set<String>()
        for row in raw {
            let state = events["ANALYSIS|" + row.id]
            let included = state?.payload["state"]?.text == "INCLUDED"
            let excluded = state?.payload["state"]?.text == "EXCLUDED"
            if !included && (excluded || (limits.excludeLarge && row.amount > 0.005 &&
                row.amount >= limits.threshold)) && !row.transactionReference.isEmpty {
                exclusions.insert(row.platform + "|" + row.transactionReference)
            }
        }
        return raw.filter { row in
            guard row.netAmount > 0.005 else { return false }
            let state = events["ANALYSIS|" + row.id]
            if state?.payload["state"]?.text == "INCLUDED" { return true }
            if state?.payload["state"]?.text == "EXCLUDED" { return false }
            if limits.excludeLarge && row.amount > 0.005 &&
                row.amount >= limits.threshold { return false }
            if !row.transactionReference.isEmpty &&
                exclusions.contains(row.platform + "|" + row.transactionReference) { return false }
            return true
        }.sorted { $0.date == $1.date ? $0.id < $1.id : $0.date > $1.date }
    }

    func selected(dateStart: String, dateEnd: String,
                  platform: String = "", store: String = "") -> [CustomerReceipt] {
        receipts.filter { row in
            row.date >= dateStart && row.date <= dateEnd &&
            (platform.isEmpty || row.platform == platform) &&
            (store.isEmpty ? true : (store == "__unmatched__"
                ? row.storeSyncID.isEmpty : row.storeSyncID == store))
        }
    }

    func summary(_ rows: [CustomerReceipt]) -> CustomerSummary {
        CustomerSummary(paymentCount: rows.count,
                        amount: rows.reduce(0) { $0 + $1.netAmount })
    }

    func hourly(_ rows: [CustomerReceipt]) -> [CustomerHour] {
        (0..<24).map { hour in
            let matches = rows.filter { $0.hour == hour }
            return CustomerHour(hour: hour, paymentCount: matches.count,
                                amount: matches.reduce(0) { $0 + $1.netAmount })
        }
    }

    /// Never insert a zero for a day without observed imports.
    /// Historic same-weekday averages need >=2 observed historical dates.
    func compareSameWeekday(date: String, platform: String,
                            store: String, maxPriorDays: Int = 70) -> CustomerWeekdayComparison? {
        guard let target = BusinessDates.parse(date) else { return nil }
        let weekday = BusinessDates.calendar.component(.weekday, from: target)
        let start = BusinessDates.key(BusinessDates.calendar.date(
            byAdding: .day, value: -maxPriorDays, to: target) ?? target)
        let prior = selected(dateStart: start, dateEnd: date,
                             platform: platform, store: store)
            .filter { row in
                guard row.date < date, let day = BusinessDates.parse(row.date) else { return false }
                return BusinessDates.calendar.component(.weekday, from: day) == weekday
            }
        let groups = Dictionary(grouping: prior, by: \.date)
        guard groups.count >= 2 else { return nil }
        let current = selected(dateStart: date, dateEnd: date,
                               platform: platform, store: store)
        let groupSummaries = groups.values.map(summary)
        return CustomerWeekdayComparison(
            historicalDays: groups.count,
            current: summary(current),
            priorDayAverageCount: Double(groupSummaries.reduce(0) { $0 + $1.paymentCount }) /
                Double(groups.count),
            priorDayAverageAmount: groupSummaries.reduce(0) { $0 + $1.amount } /
                Double(groups.count))
    }

    var observedDates: [String] { Array(Set(receipts.map(\.date))).sorted(by: >) }

    var assignedStores: [String] {
        Array(Set(receipts.map(\.storeSyncID).filter { !$0.isEmpty })).sorted()
    }

    var unmatchedCount: Int { receipts.filter { $0.storeSyncID.isEmpty }.count }
}
