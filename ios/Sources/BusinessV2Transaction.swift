import Foundation
import SQLite3

struct TXBusinessV2Capabilities: Decodable {
    let version: Int
    let feature: String
    let enabled: Bool
    let scope: String
    let atomic: Bool
    let idempotency: Bool
    let supportsOvernight: Bool
    let supportsExpensePayer: Bool

    enum CodingKeys: String, CodingKey {
        case version, feature, enabled, scope, atomic, idempotency
        case supportsOvernight = "supports_overnight", supportsExpensePayer = "supports_expense_payer"
    }
    var allowsTest: Bool {
        version == 2 && feature == "business_atomic_commit_v2" && enabled &&
        scope == "test" && atomic && idempotency && supportsOvernight && supportsExpensePayer
    }
}

struct TXBusinessV2Immutable: Codable, Equatable {
    let scope: TXBusinessV2EditorScope
    let prepared: TXBusinessV2DraftPolicy.Prepared

    struct Wire: Decodable {
        let protocol_version: Int
        let book_id: String
        let account: String
        let operation_id: String
        let date: String
        let store_sync_id: String
        let expense_payer_sync_id: String?
        let natural: [TXBusinessV2Natural]
    }

    func validated() throws -> Wire {
        let p = prepared.proposal
        let w = try JSONDecoder().decode(Wire.self, from: prepared.canonical)
        guard w.protocol_version == 2, w.account == scope.account.lowercased(),
              w.book_id.lowercased() == scope.book.lowercased(),
              w.operation_id.lowercased() == p.operationID.lowercased(),
              w.date == scope.date, w.store_sync_id == scope.storeID,
              p.bookID == scope.book, p.account == scope.account.lowercased(),
              p.date == scope.date, p.storeSyncID == scope.storeID,
              p.storeName == scope.storeName,
              prepared.hash == TXBusinessV2Preparation.hash(prepared.canonical),
              prepared.canonical == (try TXBusinessV2Preparation.canonical(p,
                  payer: w.expense_payer_sync_id, natural: w.natural)) else {
            throw BusinessAtomicError.badReceipt
        }
        return w
    }

    func body(deviceID: String) throws -> Data {
        _ = try validated()
        guard var value = try JSONSerialization.jsonObject(with: prepared.canonical) as? [String: Any] else {
            throw BusinessAtomicError.invalidDraft
        }
        value["device_id"] = deviceID
        value["payload_hash"] = prepared.hash
        return try JSONSerialization.data(withJSONObject: value, options: [.sortedKeys, .withoutEscapingSlashes])
    }
}

struct TXBusinessV2Receipt: Codable, Equatable {
    struct Revisions: Codable, Equatable {
        let partner_sync_id: String
        let previous_revision: Int64
        let following_revision: Int64
        let natural_revision: Int64
    }
    let status: String
    let operation_id: String
    let book_id: String
    let record_sync_id: String
    let collector_sync_ids: [String]
    let payload_hash: String
    let sync_cursor: Int64
    let overnight_receipts: [Revisions]
    let expense_payer_sync_id: String?

    func matches(_ request: TXBusinessV2Immutable) -> Bool {
        guard let w = try? request.validated(),
              ["committed","already_committed"].contains(status),
              operation_id.lowercased() == w.operation_id.lowercased(),
              book_id.lowercased() == w.book_id.lowercased(),
              record_sync_id == BusinessUploadPolicy.androidSyncID(date: w.date, storeSyncID: w.store_sync_id),
              payload_hash == request.prepared.hash, sync_cursor > 0,
              expense_payer_sync_id == w.expense_payer_sync_id,
              collector_sync_ids == request.prepared.proposal.collectors.map(\.partnerSyncID),
              overnight_receipts.count == w.natural.count,
              Set(overnight_receipts.map(\.partner_sync_id)).count == overnight_receipts.count else { return false }
        return w.natural.allSatisfy { row in
            guard let rev = overnight_receipts.first(where: { $0.partner_sync_id == row.partnerSyncID }),
                  rev.previous_revision == row.previousRevision, rev.natural_revision == 1 else { return false }
            if row.following == nil { return rev.following_revision == row.followingRevision }
            return rev.following_revision == row.followingRevision ||
                (row.followingRevision < Int64.max && rev.following_revision == row.followingRevision + 1)
        }
    }
}

enum TXBusinessV2Readback {
    private static func money(_ value: JSONValue?, _ expected: String) -> Bool {
        guard let value, let cents = try? BusinessCollectorDraftPolicy.cents(expected, label: "核对") else { return false }
        let actual: Double
        switch value {
        case .number(let v): actual = v
        case .exactInteger(let v): actual = Double(v)
        default: return false
        }
        return actual.isFinite && abs(actual * 100 - Double(cents)) < 0.00001
    }
    private static func identity(_ value: JSONValue?) -> Int64? {
        switch value {
        case .exactInteger(let n)?: return n
        case .number(let n)?: return Int64(exactly: n)
        default: return nil
        }
    }
    private static func partnerID(_ id: String, records: [SyncEvent]) -> Int64? {
        let matches = records.filter { $0.tableName == "partner" && !$0.isDeletion && $0.syncId == id }
        guard matches.count == 1, let number = identity(matches[0].payload["id"]), number > 0 else { return nil }
        return number
    }

    static func verified(_ request: TXBusinessV2Immutable, receipt: TXBusinessV2Receipt,
                         records: [SyncEvent], events: [CustomerChangeEvent], ledgerCursor: Int64) -> Bool {
        guard receipt.matches(request), ledgerCursor >= receipt.sync_cursor,
              let w = try? request.validated() else { return false }
        let p = request.prepared.proposal
        // V1 readback verifies sale amounts and splits. Its synthetic receipt is
        // only an adapter AFTER the real V2 receipt/hash has been verified.
        let base = BusinessCommitReceipt(status: receipt.status, operationID: receipt.operation_id,
            bookID: receipt.book_id, recordSyncID: receipt.record_sync_id, collectorSyncIDs: receipt.collector_sync_ids,
            payloadHash: p.payloadHash, syncCursor: receipt.sync_cursor)
        guard BusinessCommitPolicy.readback(p, receipt: base, records: records),
              let sale = records.first(where: { $0.tableName == "store_daily_record" && !$0.isDeletion && $0.syncId == receipt.record_sync_id }),
              let raw = sale.payload["receipt_splits_json"]?.text.data(using: .utf8),
              let splits = try? JSONDecoder().decode([[String: JSONValue]].self, from: raw),
              splits.count == p.collectors.count else { return false }
        for (index, row) in p.collectors.enumerated() {
            guard let number = partnerID(row.partnerSyncID, records: records),
                  identity(splits[index]["partner_id"]) == number else { return false }
        }
        if let payer = w.expense_payer_sync_id {
            guard let number = partnerID(payer, records: records),
                  identity(sale.payload["expense_payer_id"]) == number else { return false }
        } else if identity(sale.payload["expense_payer_id"]) != 0 { return false }

        for row in w.natural {
            guard let rev = receipt.overnight_receipts.first(where: { $0.partner_sync_id == row.partnerSyncID }),
                  let collector = p.collectors.first(where: { $0.partnerSyncID == row.partnerSyncID }),
                  let pair = try? TXBusinessV2DraftPolicy.sources(events, date: w.date, partner: row.partnerSyncID),
                  pair.0.revision == rev.previous_revision, pair.1.revision == rev.following_revision else { return false }
            let key = w.date + "|" + row.partnerSyncID
            let natural = events.filter { $0.kind == "NATURAL" && $0.key == key && !$0.isDeleted }
            guard natural.count == 1, let e = natural.first, e.revision == rev.natural_revision,
                  e.payload["date"] == .string(w.date), e.payload["partner_sync_id"] == .string(row.partnerSyncID),
                  e.payload["store_sync_id"] == .string(w.store_sync_id) else { return false }
            for (name, amount, deduct, saved) in [
                ("wechat", row.wechat, row.deductWechat, collector.wechat),
                ("alipay", row.alipay, row.deductAlipay, collector.alipay),
                ("cash", row.cash, row.deductCash, collector.cash)] {
                guard money(e.payload[name], amount), money(e.payload["saved_" + name], saved),
                      e.payload["deduct_" + name] == .bool(deduct) else { return false }
            }
            if let following = row.following {
                guard case .recorded(let actual) = pair.1.morning,
                      let values = try? following.values(),
                      actual.wechat == (following.wechatRecorded ? values[0] : nil),
                      actual.alipay == (following.alipayRecorded ? values[1] : nil),
                      actual.cash == (following.cashRecorded ? values[2] : nil) else { return false }
            }
        }
        return true
    }
}

/// One durable immutable reservation per account/book/date/store, never
/// delete, overwrite or automatically retry. A crash after reserve is unknown.
final class TXBusinessV2Outbox {
    static func requireV1Free(book: CloudBook, account: String, date: String, storeID: String) throws {
        guard book.name.hasPrefix("iOS_TEST_") else { return }
        let scope = TXBusinessV2EditorScope(account: account, book: book.id, date: date, storeID: storeID, storeName: "")
        guard try TXBusinessV2Outbox().load(scope) == nil else { throw BusinessAtomicError.locked }
    }

    private var db: OpaquePointer?
    init(url: URL? = nil) throws {
        let file = url ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("TianXianBusinessCommit/v2.sqlite")
        try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
        guard sqlite3_open_v2(file.path, &db, SQLITE_OPEN_CREATE | SQLITE_OPEN_READWRITE | SQLITE_OPEN_FULLMUTEX, nil) == SQLITE_OK else {
            if db != nil { sqlite3_close(db); db = nil }
            throw BusinessAtomicError.persistence
        }
        sqlite3_busy_timeout(db, 3000)
        guard sqlite3_exec(db, "PRAGMA synchronous=FULL; CREATE TABLE IF NOT EXISTS reserved(scope TEXT PRIMARY KEY, payload BLOB NOT NULL)", nil, nil, nil) == SQLITE_OK else {
            sqlite3_close(db); db = nil
            throw BusinessAtomicError.persistence
        }
    }
    deinit { if db != nil { sqlite3_close(db) } }
    private func statement(_ sql: String) throws -> OpaquePointer {
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK, let stmt else {
            throw BusinessAtomicError.persistence
        }
        return stmt
    }
    private func bind(_ scope: TXBusinessV2EditorScope, _ stmt: OpaquePointer) {
        _ = scope.id.withCString { sqlite3_bind_text(stmt, 1, $0, -1, unsafeBitCast(-1, to: sqlite3_destructor_type.self)) }
    }
    func load(_ scope: TXBusinessV2EditorScope) throws -> TXBusinessV2Immutable? {
        let stmt = try statement("SELECT payload FROM reserved WHERE scope=?")
        defer { sqlite3_finalize(stmt) }
        bind(scope, stmt)
        let rc = sqlite3_step(stmt)
        if rc == SQLITE_DONE { return nil }
        guard rc == SQLITE_ROW, let ptr = sqlite3_column_blob(stmt, 0) else { throw BusinessAtomicError.persistence }
        let data = Data(bytes: ptr, count: Int(sqlite3_column_bytes(stmt, 0)))
        let value = try JSONDecoder().decode(TXBusinessV2Immutable.self, from: data)
        guard value.scope.id == scope.id else { throw BusinessAtomicError.persistence }
        _ = try value.validated()
        return value
    }
    func reserve(_ request: TXBusinessV2Immutable) throws {
        _ = try request.validated()
        let bytes = try JSONEncoder().encode(request)
        let stmt = try statement("INSERT INTO reserved(scope,payload) VALUES(?,?)")
        defer { sqlite3_finalize(stmt) }
        bind(request.scope, stmt)
        _ = bytes.withUnsafeBytes { sqlite3_bind_blob(stmt, 2, $0.baseAddress, Int32(bytes.count),
            unsafeBitCast(-1, to: sqlite3_destructor_type.self)) }
        guard sqlite3_step(stmt) == SQLITE_DONE else { throw BusinessAtomicError.locked }
        guard try load(request.scope) == request else { throw BusinessAtomicError.persistence }
    }
}
