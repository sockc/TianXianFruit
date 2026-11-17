import Foundation

/// The server sends SQLite-derived JSON payloads. Preserve primitive types;
/// do not reinterpret local Android numeric IDs as cross-device identities.
indirect enum JSONValue: Codable, Equatable {
    case string(String)
    case number(Double)
    case bool(Bool)
    case object([String: JSONValue])
    case array([JSONValue])
    case null

    init(from decoder: Decoder) throws {
        let value = try decoder.singleValueContainer()
        if value.decodeNil() { self = .null }
        else if let flag = try? value.decode(Bool.self) { self = .bool(flag) }
        else if let amount = try? value.decode(Double.self) { self = .number(amount) }
        else if let text = try? value.decode(String.self) { self = .string(text) }
        else if let map = try? value.decode([String: JSONValue].self) { self = .object(map) }
        else if let list = try? value.decode([JSONValue].self) { self = .array(list) }
        else { throw DecodingError.dataCorruptedError(in: value, debugDescription: "Invalid JSON") }
    }

    func encode(to encoder: Encoder) throws {
        var value = encoder.singleValueContainer()
        switch self {
        case .string(let text): try value.encode(text)
        case .number(let amount): try value.encode(amount)
        case .bool(let flag): try value.encode(flag)
        case .object(let map): try value.encode(map)
        case .array(let list): try value.encode(list)
        case .null: try value.encodeNil()
        }
    }

    var text: String {
        switch self {
        case .string(let value): return value
        case .number(let value): return String(value)
        default: return ""
        }
    }

    var decimal: Double {
        switch self {
        case .number(let value): return value
        case .string(let value): return Double(value) ?? 0
        default: return 0
        }
    }

    var isTrue: Bool {
        switch self {
        case .bool(let value): return value
        case .number(let value): return value != 0
        case .string(let value): return value == "1" || value.lowercased() == "true"
        default: return false
        }
    }
}

struct LoginResponse: Decodable {
    let accessToken: String
    enum CodingKeys: String, CodingKey { case accessToken = "access_token" }
}

struct Account: Codable {
    let username: String
    let displayName: String
    let systemRole: String?
    enum CodingKeys: String, CodingKey {
        case username
        case displayName = "display_name"
        case systemRole = "system_role"
    }
}

struct CloudBook: Codable, Identifiable, Hashable {
    let id: String
    let name: String
    let role: String
    let deleted: Bool?
    let purged: Bool?

    var isAvailable: Bool { deleted != true && purged != true }
}

struct SyncEvent: Codable {
    let tableName: String
    let syncId: String
    let rowVersion: Int64
    let operation: String
    let payload: [String: JSONValue]

    enum CodingKeys: String, CodingKey {
        case tableName = "table_name"
        case syncId = "sync_id"
        case rowVersion = "row_version"
        case operation, payload
    }

    var logicalKey: String { tableName + "|" + syncId }
    var isDeletion: Bool {
        operation.uppercased() == "DELETE" || payload["deleted"]?.isTrue == true
    }
}

struct SyncPage: Decodable {
    let events: [SyncEvent]
    let nextCursor: Int64
    let hasMore: Bool
    enum CodingKeys: String, CodingKey {
        case events
        case nextCursor = "next_cursor"
        case hasMore = "has_more"
    }
}

struct StoreDailyRow: Identifiable {
    let id: String
    let date: String
    let storeName: String
    let revenue: Double
    let profit: Double

    init?(_ event: SyncEvent) {
        guard event.tableName == "store_daily_record",
              !event.isDeletion,
              let date = event.payload["date"]?.text, !date.isEmpty else { return nil }
        id = event.syncId
        self.date = date
        storeName = event.payload["store_name"]?.text ?? "未命名位置"
        revenue = event.payload["revenue"]?.decimal ?? 0
        profit = event.payload["profit"]?.decimal ?? 0
    }
}

struct PurchaseOrderRow: Identifiable {
    let id: String
    let date: String
    let buyer: String
    let totalCost: Double

    init?(_ event: SyncEvent) {
        guard event.tableName == "purchase_order",
              !event.isDeletion,
              let date = event.payload["date"]?.text, !date.isEmpty else { return nil }
        id = event.syncId
        self.date = date
        buyer = event.payload["buyer_name"]?.text ?? "未指定"
        totalCost = event.payload["total_cost"]?.decimal ?? 0
    }
}

struct InventoryRow: Identifiable {
    let id: String
    let date: String
    let name: String
    let unit: String
    let remaining: Double
    let loss: Double

    init?(_ event: SyncEvent) {
        guard event.tableName == "inventory_snapshot", !event.isDeletion,
              let date = event.payload["date"]?.text, !date.isEmpty else { return nil }
        id = event.syncId
        self.date = date
        name = event.payload["fruit_name"]?.text ?? "未命名商品"
        unit = event.payload["unit"]?.text ?? "件"
        remaining = event.payload["remaining_quantity"]?.decimal ?? 0
        loss = event.payload["loss_quantity"]?.decimal ?? 0
    }
}
