import Foundation
import CryptoKit

enum BusinessUploadState: String, Codable {
    case ready, sending, complete, needsReview

    var title: String {
        switch self {
        case .ready: return "待提交"
        case .sending: return "已尝试发送，需核对"
        case .complete: return "云端营业已确认"
        case .needsReview: return "云端结果不确定，禁止重发"
        }
    }
}

struct BusinessUploadJob: Codable, Identifiable {
    let id: String
    let sourceAccount: String
    let bookID: String
    let stageHost: String
    let stageInstanceID: String
    let stageUsername: String
    let deviceID: String
    let syncID: String
    let payload: [String: JSONValue]
    let createdAt: Date
    var state: BusinessUploadState
    var note: String

    var event: SyncEvent {
        SyncEvent(tableName: "store_daily_record", syncId: syncID,
                  rowVersion: 1, operation: "UPSERT", payload: payload)
    }
}

/// Mirrors the Android day+store identity and source revenue/profit formula.
/// No settlement, deletion or existing-record update is performed in iOS.
enum BusinessUploadPolicy {
    private static func cents(_ string: String, name: String) throws -> Int64 {
        Int64((try TestWritePolicy.money(string, label: name) * 100).rounded())
    }
    private static func cash(_ value: Int64) -> JSONValue {
        .number(Double(value) / 100.0)
    }

    /// Java UUID.nameUUIDFromBytes on UTF-8 bytes.
    static func androidSyncID(date: String, storeSyncID: String) -> String {
        let bytes = Data(("store_daily_record|" + date + "|" + storeSyncID).utf8)
        var md5 = Array(Insecure.MD5.hash(data: bytes))
        md5[6] = (md5[6] & 0x0f) | 0x30
        md5[8] = (md5[8] & 0x3f) | 0x80
        let h = md5.map { String(format: "%02x", $0) }.joined()
        return String(h.prefix(8)) + "-" +
            String(h.dropFirst(8).prefix(4)) + "-" +
            String(h.dropFirst(12).prefix(4)) + "-" +
            String(h.dropFirst(16).prefix(4)) + "-" +
            String(h.dropFirst(20))
    }

    static func conflict(date: String, storeSyncID: String,
                         storeName: String, events: [SyncEvent]) -> Bool {
        let key = androidSyncID(date: date, storeSyncID: storeSyncID)
        return events.contains { event in
            guard event.tableName == "store_daily_record" else { return false }
            return event.syncId == key || (
                event.payload["date"]?.text == date &&
                (event.payload["store_sync_id"]?.text == storeSyncID ||
                 event.payload["store_name"]?.text == storeName))
        }
    }

    static func prepare(_ draft: TestDraft, sourceBook: CloudBook,
                        session: StagingSession, events: [SyncEvent],
                        now: Date = Date()) throws -> BusinessUploadJob {
        guard draft.kind == .business, !draft.account.isEmpty,
              draft.bookID == sourceBook.id, session.book.id == sourceBook.id,
              sourceBook.name == session.book.name,
              sourceBook.name.hasPrefix(TestWritePolicy.prefix),
              sourceBook.isAvailable,
              session.scope.bookID == sourceBook.id,
              session.scope.instanceID == "production-scoped-testbook:" + sourceBook.id,
              session.host == StagingPurchasePolicy.productionURL else {
            throw StagingSafetyError.wrongSourceBook
        }
        try TestWritePolicy.validate(draft, book: sourceBook)
        let storeID = draft.fields["store_sync_id"] ?? ""
        let store = try StagingPayloadBuilder.reference(
            table: "store", syncID: storeID, events: events)
        guard draft.fields["store_name"] == store.name else {
            throw StagingSafetyError.staleReferences
        }
        guard !conflict(date: draft.date, storeSyncID: storeID,
                        storeName: store.name, events: events) else {
            throw StagingSafetyError.needsReview(
                "云端已有该日期/该位置营业记录（或删除历史），禁止重复或覆盖")
        }
        let wechat = try cents(draft.fields["wechat"] ?? "", name: "微信")
        let alipay = try cents(draft.fields["alipay"] ?? "", name: "支付宝")
        let cashIn = try cents(draft.fields["cash"] ?? "", name: "现金")
        let expense = try cents(draft.fields["expense"] ?? "", name: "费用")
        let opening = try cents(draft.fields["opening_stock_value"] ?? "", name: "开摊库存")
        let closing = try cents(draft.fields["stock_left_value"] ?? "", name: "收摊库存")
        let revenue = wechat + alipay + cashIn
        let profit = revenue + closing - opening - expense

        struct Split {
            let id: Int64
            let name: String
            var wechat: Int64 = 0
            var alipay: Int64 = 0
            var cash: Int64 = 0
        }
        var collectors: [String: Split] = [:]
        func add(_ sum: Int64, from field: String, channel: String) throws {
            if sum == 0 { return }
            let syncID = draft.fields[field] ?? ""
            let partner = try StagingPayloadBuilder.reference(
                table: "partner", syncID: syncID, events: events)
            var part = collectors[syncID] ?? Split(id: partner.numericID, name: partner.name)
            if channel == "wechat" { part.wechat += sum }
            else if channel == "alipay" { part.alipay += sum }
            else { part.cash += sum }
            collectors[syncID] = part
        }
        try add(wechat, from: "wechat_collector_sync_id", channel: "wechat")
        try add(alipay, from: "alipay_collector_sync_id", channel: "alipay")
        try add(cashIn, from: "cash_collector_sync_id", channel: "cash")
        let splits: [[String: Any]] = collectors.keys.sorted().compactMap { key in
            guard let part = collectors[key] else { return nil }
            return ["partner_id": part.id, "partner_name": part.name,
                    "amount": Double(part.wechat + part.alipay + part.cash) / 100.0,
                    "wechat_income": Double(part.wechat) / 100.0,
                    "alipay_income": Double(part.alipay) / 100.0,
                    "cash_income": Double(part.cash) / 100.0]
        }
        let totalSplit = splits.reduce(0.0) {
            $0 + ($1["amount"] as? Double ?? 0)
        }
        guard abs(totalSplit - Double(revenue) / 100) < 0.005 else {
            throw StagingSafetyError.invalidDraft
        }
        let splitBytes = try JSONSerialization.data(withJSONObject: splits,
                                                     options: [.sortedKeys])
        guard let splitJSON = String(data: splitBytes, encoding: .utf8) else {
            throw StagingSafetyError.invalidDraft
        }
        let payer: StageBookReference?
        if expense > 0 {
            payer = try StagingPayloadBuilder.reference(
                table: "partner", syncID: draft.fields["expense_payer_sync_id"] ?? "",
                events: events)
        } else { payer = nil }
        let single = collectors.count == 1 ? collectors.values.first : nil
        let collectorID = single?.id ?? 0
        let collectorName = collectors.count > 1 ? "多人收款" : (single?.name ?? "未指定")
        let key = androidSyncID(date: draft.date, storeSyncID: storeID)
        let localID = StagingPurchasePolicy.numericID(draft.id, suffix: "business")
        let milliseconds = Int64(now.timeIntervalSince1970 * 1000)
        let payload: [String: JSONValue] = [
            "id": .number(Double(localID)),
            "date": .string(draft.date),
            "store_id": .number(Double(store.numericID)),
            "store_sync_id": .string(storeID),
            "store_name": .string(store.name),
            "wechat_income": cash(wechat),
            "wechat_collector_id": .number(Double(collectorID)),
            "wechat_collector_name": .string(collectorName),
            "alipay_income": cash(alipay),
            "alipay_collector_id": .number(Double(collectorID)),
            "alipay_collector_name": .string(collectorName),
            "cash_income": cash(cashIn),
            "cash_collector_id": .number(Double(collectorID)),
            "cash_collector_name": .string(collectorName),
            "receipt_splits_json": .string(splitJSON),
            "revenue": cash(revenue), "expense": cash(expense),
            "expense_payer_id": .number(Double(payer?.numericID ?? 0)),
            "expense_payer_name": .string(payer?.name ?? "未指定"),
            "opening_stock_value": cash(opening),
            "stock_left_value": cash(closing),
            "purchase_cost": .number(0),
            "profit": cash(profit),
            "new_customer": .number(0), "old_customer": .number(0),
            "actual_start_time": .string(""), "actual_end_time": .string(""),
            "deleted": .number(0), "sync_id": .string(key),
            "sync_status": .number(0), "row_version": .number(1),
            "created_at": .number(Double(milliseconds)),
            "updated_at": .number(Double(milliseconds)),
            "modified_by": .string(session.deviceID)
        ]
        return BusinessUploadJob(
            id: draft.id, sourceAccount: draft.account, bookID: sourceBook.id,
            stageHost: session.host, stageInstanceID: session.scope.instanceID,
            stageUsername: session.username, deviceID: session.deviceID,
            syncID: key, payload: payload, createdAt: now,
            state: .ready, note: "已验证测试账号、同日同位置唯一性及收款归属")
    }

    /// Compare immutable source fields, not remappable Android local numeric IDs.
    static func verified(_ job: BusinessUploadJob,
                         events: [SyncEvent]) -> Bool {
        let matching = events.filter {
            $0.tableName == "store_daily_record" && $0.syncId == job.syncID
        }
        guard matching.count == 1, let row = matching.first,
              !row.isDeletion,
              let date = job.payload["date"]?.text,
              let store = job.payload["store_sync_id"]?.text,
              let name = job.payload["store_name"]?.text,
              !events.contains(where: {
                $0.tableName == "store_daily_record" &&
                $0.syncId != job.syncID &&
                $0.payload["date"]?.text == date &&
                ($0.payload["store_sync_id"]?.text == store ||
                 $0.payload["store_name"]?.text == name)
              }) else { return false }

        let numberKeys = ["wechat_income", "alipay_income", "cash_income",
                          "revenue", "expense", "profit",
                          "opening_stock_value", "stock_left_value",
                          "purchase_cost", "new_customer", "old_customer"]
        let textKeys = ["date", "store_sync_id", "store_name",
                        "expense_payer_name"]
        for key in numberKeys {
            guard let a = job.payload[key]?.decimal,
                  let b = row.payload[key]?.decimal,
                  b.isFinite, abs(a - b) < 0.005 else { return false }
        }
        for key in textKeys {
            guard let a = job.payload[key]?.text, a == row.payload[key]?.text else {
                return false
            }
        }
        guard let lhsText = job.payload["receipt_splits_json"]?.text,
              let rhsText = row.payload["receipt_splits_json"]?.text,
              let lhsData = lhsText.data(using: .utf8),
              let rhsData = rhsText.data(using: .utf8),
              let a = try? JSONSerialization.jsonObject(with: lhsData) as? [[String: Any]],
              let b = try? JSONSerialization.jsonObject(with: rhsData) as? [[String: Any]] else {
            return false
        }
        func normalized(_ splits: [[String: Any]]) -> [String] {
            splits.map { split in
                split.keys.sorted().map { key in
                    key + ":" + String(describing: split[key] ?? "")
                }.joined(separator: "|")
            }.sorted()
        }
        return normalized(a) == normalized(b)
    }
}
