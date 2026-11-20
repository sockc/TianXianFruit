import Foundation
import CryptoKit
import SQLite3

enum BusinessAtomicError: LocalizedError {
    case unavailable, scope, duplicate, invalidDraft, cloudConflict
    case locked, missingResult, badReceipt, readback, persistence

    var errorDescription: String? {
        switch self {
        case .unavailable: return "服务端尚未启用正式营业原子提交，草稿仍保存在本机"
        case .scope: return "账号、账本或权限不匹配，停止营业提交"
        case .duplicate: return "云端已有此日此位置记录，不能新增第二笔"
        case .invalidDraft: return "营业日期、位置、收款人或金额无效"
        case .cloudConflict: return "服务器记录已改变，请刷新后核对"
        case .locked: return "此草稿已有提交尝试，不能重新 POST；只能查询原操作"
        case .missingResult: return "未查到服务器操作结果；为避免重复记账，仍保持锁定"
        case .badReceipt: return "服务器回执身份、金额或幂等操作不一致"
        case .readback: return "云端回读尚未完全核对，禁止再次提交"
        case .persistence: return "无法持久化上传保护记录，已停止网络写入"
        }
    }
}

struct BusinessCommitCapabilities: Decodable {
    let version: Int
    let enabled: Bool
    let atomic: Bool
    let idempotency: Bool
    let feature: String
    let supportsCollectorSplits: Bool

    enum CodingKeys: String, CodingKey {
        case version, enabled, atomic, idempotency, feature
        case supportsCollectorSplits = "supports_collector_splits"
    }

    var allowsWrite: Bool {
        version == 1 && enabled && atomic && idempotency &&
        supportsCollectorSplits && feature == "business_atomic_commit_v1"
    }
}

struct BusinessCommitCollector: Codable, Equatable {
    let partnerSyncID: String
    let wechat: String
    let alipay: String
    let cash: String

    enum CodingKeys: String, CodingKey {
        case partnerSyncID = "partner_sync_id"
        case wechat, alipay, cash
    }
}

struct BusinessCommitProposal: Codable, Equatable {
    let protocolVersion: Int
    let bookID: String
    let account: String
    let operationID: String
    let date: String
    let storeSyncID: String
    let storeName: String
    let collectors: [BusinessCommitCollector]
    let actualStartTime: String
    let actualEndTime: String
    let wechat: String
    let alipay: String
    let cash: String
    let totalRevenue: String
    let expense: String
    let openingStockValue: String
    let stockLeftValue: String
    let profit: String
    let payloadHash: String

    enum CodingKeys: String, CodingKey {
        case protocolVersion = "protocol_version"
        case bookID = "book_id"
        case account
        case operationID = "operation_id"
        case date
        case storeSyncID = "store_sync_id"
        case storeName = "store_name"
        case collectors
        case actualStartTime = "actual_start_time"
        case actualEndTime = "actual_end_time"
        case wechat, alipay, cash
        case totalRevenue = "total_revenue"
        case expense, profit
        case openingStockValue = "opening_stock_value"
        case stockLeftValue = "stock_left_value"
        case payloadHash = "payload_hash"
    }
}

struct BusinessCommitReceipt: Codable, Equatable {
    let status: String
    let operationID: String
    let bookID: String
    let recordSyncID: String
    let collectorSyncIDs: [String]
    let payloadHash: String
    let syncCursor: Int64

    enum CodingKeys: String, CodingKey {
        case status
        case operationID = "operation_id"
        case bookID = "book_id"
        case recordSyncID = "record_sync_id"
        case collectorSyncIDs = "collector_sync_ids"
        case payloadHash = "payload_hash"
        case syncCursor = "sync_cursor"
    }

    func matches(_ proposal: BusinessCommitProposal) -> Bool {
        (status == "committed" || status == "already_committed") &&
        operationID.lowercased() == proposal.operationID.lowercased() &&
        bookID.lowercased() == proposal.bookID.lowercased() &&
        payloadHash == proposal.payloadHash && !recordSyncID.isEmpty &&
        collectorSyncIDs.count == proposal.collectors.count &&
        Set(collectorSyncIDs).count == collectorSyncIDs.count &&
        syncCursor > 0
    }
}

struct BusinessCommitAttempt: Codable, Equatable {
    enum Phase: String, Codable {
        case prepared, unknown, accepted, confirmed
    }
    let proposal: BusinessCommitProposal
    let account: String
    let bookID: String
    var phase: Phase
    var receipt: BusinessCommitReceipt?
    var updatedAt: Date

    var label: String {
        switch phase {
        case .prepared: return "已锁定提交内容，请查询原操作结果"
        case .unknown: return "结果不确定，禁止再次上传"
        case .accepted: return "服务端已确认，等待完整回读"
        case .confirmed: return "已核对云端营业及所有收款人"
        }
    }
}

enum BusinessCommitPolicy {
    static func money(_ cents: Int64) -> String {
        String(format: "%.2f", Double(cents) / 100)
    }

    static func prepare(_ draft: BusinessLocalDraft, book: CloudBook,
                        account: Account, records: [SyncEvent]) throws -> BusinessCommitProposal {
        guard book.isAvailable, ["OWNER", "EDITOR", "SUPERADMIN"].contains(book.role.uppercased()),
              UUID(uuidString: book.id) != nil, UUID(uuidString: draft.id) != nil,
              BusinessDates.parse(draft.date) != nil else {
            throw BusinessAtomicError.scope
        }
        let stores = records.filter {
            $0.tableName == "store" && !$0.isDeletion && $0.syncId == draft.storeID &&
            $0.payload["name"]?.text == draft.storeName
        }
        guard stores.count == 1 else { throw BusinessAtomicError.invalidDraft }
        let conflict = records.contains {
            $0.tableName == "store_daily_record" && !$0.isDeletion &&
            $0.payload["date"]?.text == draft.date &&
            ($0.payload["store_sync_id"]?.text == draft.storeID ||
             $0.payload["store_name"]?.text == draft.storeName)
        }
        guard !conflict else { throw BusinessAtomicError.duplicate }
        let totals = try BusinessCollectorDraftPolicy.totals(draft.collectors)
        let fields = [
            "wechat": money(totals.wechat), "alipay": money(totals.alipay),
            "cash": money(totals.cash), "actual_start_time": draft.startTime,
            "actual_end_time": draft.endTime
        ]
        try BusinessCollectorDraftPolicy.validate(draft.collectors, fields: fields)
        try BusinessCollectorDraftPolicy.validateTimes(fields)
        for line in draft.collectors {
            guard records.filter({
                $0.tableName == "partner" && !$0.isDeletion &&
                $0.syncId == line.partnerSyncID
            }).count == 1 else { throw BusinessAtomicError.invalidDraft }
        }
        let collectors = try draft.collectors.map { line -> BusinessCommitCollector in
            BusinessCommitCollector(
                partnerSyncID: line.partnerSyncID,
                wechat: money(try BusinessCollectorDraftPolicy.cents(line.wechat, label: "微信")),
                alipay: money(try BusinessCollectorDraftPolicy.cents(line.alipay, label: "支付宝")),
                cash: money(try BusinessCollectorDraftPolicy.cents(line.cash, label: "现金")))
        }
        guard let expenseText = draft.expense, !expenseText.isEmpty,
              let openingText = draft.openingStock, !openingText.isEmpty,
              let closingText = draft.closingStock, !closingText.isEmpty else {
            throw BusinessAtomicError.invalidDraft
        }
        let expenseCents = try BusinessCollectorDraftPolicy.cents(expenseText, label: "支出")
        let openingCents = try BusinessCollectorDraftPolicy.cents(openingText, label: "开摊库存")
        let closingCents = try BusinessCollectorDraftPolicy.cents(closingText, label: "收摊库存")
        let total = totals.wechat + totals.alipay + totals.cash
        let profitCents = total + closingCents - openingCents - expenseCents
        guard total > 0 else { throw BusinessAtomicError.invalidDraft }
        struct Canonical: Encodable {
            let protocolVersion: Int
            let bookID: String
            let account: String
            let operationID: String
            let date: String
            let storeSyncID: String
            let storeName: String
            let collectors: [BusinessCommitCollector]
            let actualStartTime: String
            let actualEndTime: String
            let wechat: String
            let alipay: String
            let cash: String
            let totalRevenue: String
            let expense: String
            let openingStockValue: String
            let stockLeftValue: String
            let profit: String
            enum CodingKeys: String, CodingKey {
                case protocolVersion = "protocol_version"
                case bookID = "book_id"
                case account
                case operationID = "operation_id"
                case date
                case storeSyncID = "store_sync_id"
                case storeName = "store_name"
                case collectors
                case actualStartTime = "actual_start_time"
                case actualEndTime = "actual_end_time"
                case wechat, alipay, cash
                case totalRevenue = "total_revenue"
                case expense, profit
                case openingStockValue = "opening_stock_value"
                case stockLeftValue = "stock_left_value"
            }
        }
        let normalized = Canonical(protocolVersion: 1, bookID: book.id,
            account: account.username.lowercased(),
            operationID: draft.id.lowercased(), date: draft.date,
            storeSyncID: draft.storeID, storeName: draft.storeName,
            collectors: collectors, actualStartTime: draft.startTime,
            actualEndTime: draft.endTime, wechat: money(totals.wechat),
            alipay: money(totals.alipay), cash: money(totals.cash),
            totalRevenue: money(total), expense: money(expenseCents),
            openingStockValue: money(openingCents),
            stockLeftValue: money(closingCents), profit: money(profitCents))
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        let digest = SHA256.hash(data: try encoder.encode(normalized))
            .map { String(format: "%02x", $0) }.joined()
        return BusinessCommitProposal(protocolVersion: 1, bookID: book.id,
            account: normalized.account, operationID: normalized.operationID,
            date: normalized.date, storeSyncID: normalized.storeSyncID,
            storeName: normalized.storeName, collectors: normalized.collectors,
            actualStartTime: normalized.actualStartTime,
            actualEndTime: normalized.actualEndTime, wechat: normalized.wechat,
            alipay: normalized.alipay, cash: normalized.cash,
            totalRevenue: normalized.totalRevenue,
            expense: normalized.expense,
            openingStockValue: normalized.openingStockValue,
            stockLeftValue: normalized.stockLeftValue,
            profit: normalized.profit, payloadHash: digest)
    }

    /// Android stores collector allocations inside the single
    /// store_daily_record.receipt_splits_json field (not a separate table).
    /// Verify each split against its unique, active partner numeric ID.
    static func readback(_ proposal: BusinessCommitProposal,
                         receipt: BusinessCommitReceipt,
                         records: [SyncEvent]) -> Bool {
        guard receipt.matches(proposal),
              receipt.collectorSyncIDs == proposal.collectors.map(\.partnerSyncID)
        else { return false }
        let parent = records.filter {
            $0.tableName == "store_daily_record" && !$0.isDeletion &&
            $0.syncId == receipt.recordSyncID &&
            $0.payload["date"]?.text == proposal.date &&
            $0.payload["store_sync_id"]?.text == proposal.storeSyncID &&
            $0.payload["store_name"]?.text == proposal.storeName
        }
        guard parent.count == 1 else { return false }
        let payload = parent[0].payload
        for (key, expected) in [
            ("revenue", proposal.totalRevenue),
            ("wechat_income", proposal.wechat),
            ("alipay_income", proposal.alipay),
            ("cash_income", proposal.cash),
            ("expense", proposal.expense),
            ("opening_stock_value", proposal.openingStockValue),
            ("stock_left_value", proposal.stockLeftValue),
            ("profit", proposal.profit)
        ] {
            guard moneyMatches(payload[key]?.decimal, expected) else { return false }
        }
        guard payload["actual_start_time"]?.text == proposal.actualStartTime,
              payload["actual_end_time"]?.text == proposal.actualEndTime,
              let raw = payload["receipt_splits_json"]?.text,
              let data = raw.data(using: .utf8),
              let splits = (try? JSONSerialization.jsonObject(with: data))
                    as? [[String: Any]],
              splits.count == proposal.collectors.count
        else { return false }
        let partnerRows = records.filter {
            $0.tableName == "partner" && !$0.isDeletion
        }
        for (index, requested) in proposal.collectors.enumerated() {
            let partners = partnerRows.filter {
                $0.syncId == requested.partnerSyncID
            }
            guard partners.count == 1,
                  let numeric = partners[0].payload["id"]?.decimal,
                  numeric.isFinite, numeric > 0,
                  numeric.rounded() == numeric else { return false }
            let split = splits[index]
            guard let partnerID = split["partner_id"] as? NSNumber,
                  partnerID.doubleValue == numeric,
                  moneyMatches(split["wechat_income"] as? Double,
                               requested.wechat),
                  moneyMatches(split["alipay_income"] as? Double,
                               requested.alipay),
                  moneyMatches(split["cash_income"] as? Double,
                               requested.cash),
                  let a = Double(requested.wechat),
                  let b = Double(requested.alipay),
                  let c = Double(requested.cash),
                  moneyMatches(split["amount"] as? Double,
                               String(format: "%.2f", a+b+c))
            else { return false }
        }
        return true
    }

    private static func moneyMatches(_ actual: Double?, _ expected: String) -> Bool {
        guard let actual, let wanted = Double(expected),
              actual.isFinite, wanted.isFinite else { return false }
        return abs(actual - wanted) < 0.005
    }
}

/// SQLite insert-only reservation; never overwrite a different proposal for
/// the same operation UUID. No attempt is deleted after a timeout or 404.
final class BusinessCommitOutbox {
    private var db: OpaquePointer?
    init(url: URL? = nil) throws {
        let file = url ?? FileManager.default.urls(for: .applicationSupportDirectory,
            in: .userDomainMask)[0].appendingPathComponent("TianXianBusinessCommit/v1.sqlite")
        try FileManager.default.createDirectory(at: file.deletingLastPathComponent(),
                                                 withIntermediateDirectories: true)
        guard sqlite3_open_v2(file.path, &db,
            SQLITE_OPEN_CREATE | SQLITE_OPEN_READWRITE | SQLITE_OPEN_FULLMUTEX,
            nil) == SQLITE_OK else { throw BusinessAtomicError.persistence }
        sqlite3_busy_timeout(db, 3000)
        guard sqlite3_exec(db, """
            CREATE TABLE IF NOT EXISTS attempts (
              operation_id TEXT NOT NULL,
              account TEXT NOT NULL,
              book_id TEXT NOT NULL,
              payload BLOB NOT NULL,
              PRIMARY KEY(operation_id,account,book_id)
            )
            """, nil, nil, nil) == SQLITE_OK else {
            throw BusinessAtomicError.persistence
        }
    }
    deinit { if db != nil { sqlite3_close(db) } }

    private func statement(_ sql: String) throws -> OpaquePointer {
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK,
              let stmt else { throw BusinessAtomicError.persistence }
        return stmt
    }
    private func bind(_ text: String, at position: Int32, to stmt: OpaquePointer) {
        _ = text.withCString {
            sqlite3_bind_text(stmt, position, $0, -1,
                unsafeBitCast(-1, to: sqlite3_destructor_type.self))
        }
    }
    private func bind(_ data: Data, at position: Int32, to stmt: OpaquePointer) {
        _ = data.withUnsafeBytes {
            sqlite3_bind_blob(stmt, position, $0.baseAddress, Int32(data.count),
                unsafeBitCast(-1, to: sqlite3_destructor_type.self))
        }
    }

    func load(id: String, account: String, bookID: String) throws -> BusinessCommitAttempt? {
        let stmt = try statement("""
            SELECT payload FROM attempts
            WHERE operation_id=? AND account=? AND book_id=?
            """)
        defer { sqlite3_finalize(stmt) }
        bind(id.lowercased(), at: 1, to: stmt)
        bind(account.lowercased(), at: 2, to: stmt)
        bind(bookID.lowercased(), at: 3, to: stmt)
        let code = sqlite3_step(stmt)
        if code == SQLITE_DONE { return nil }
        guard code == SQLITE_ROW,
              let ptr = sqlite3_column_blob(stmt, 0) else {
            throw BusinessAtomicError.persistence
        }
        let raw = Data(bytes: ptr, count: Int(sqlite3_column_bytes(stmt, 0)))
        let result = try JSONDecoder().decode(BusinessCommitAttempt.self, from: raw)
        guard result.proposal.operationID.lowercased() == id.lowercased(),
              result.account == account.lowercased(),
              result.bookID.lowercased() == bookID.lowercased()
        else { throw BusinessAtomicError.persistence }
        return result
    }

    func reserve(_ proposal: BusinessCommitProposal) throws -> BusinessCommitAttempt {
        if let existing = try load(id: proposal.operationID,
                                   account: proposal.account, bookID: proposal.bookID) {
            guard existing.proposal == proposal else { throw BusinessAtomicError.locked }
            return existing
        }
        let attempt = BusinessCommitAttempt(proposal: proposal,
            account: proposal.account, bookID: proposal.bookID,
            phase: .prepared, receipt: nil, updatedAt: Date())
        let stmt = try statement("""
            INSERT INTO attempts(operation_id,account,book_id,payload)
            VALUES (?,?,?,?)
            """)
        defer { sqlite3_finalize(stmt) }
        bind(proposal.operationID.lowercased(), at: 1, to: stmt)
        bind(proposal.account.lowercased(), at: 2, to: stmt)
        bind(proposal.bookID.lowercased(), at: 3, to: stmt)
        bind(try JSONEncoder().encode(attempt), at: 4, to: stmt)
        guard sqlite3_step(stmt) == SQLITE_DONE else {
            // Another thread/device process may have reserved first.
            throw BusinessAtomicError.locked
        }
        return attempt
    }

    func update(_ attempt: BusinessCommitAttempt) throws {
        guard let previous = try load(id: attempt.proposal.operationID,
            account: attempt.account, bookID: attempt.bookID),
              previous.proposal == attempt.proposal else {
            throw BusinessAtomicError.locked
        }
        let rank: [BusinessCommitAttempt.Phase: Int] = [
            .prepared: 0, .unknown: 1, .accepted: 2, .confirmed: 3]
        guard rank[attempt.phase, default: 0] >= rank[previous.phase, default: 0]
        else { throw BusinessAtomicError.locked }
        let stmt = try statement("""
            UPDATE attempts SET payload=?
            WHERE operation_id=? AND account=? AND book_id=? AND payload=?
            """)
        defer { sqlite3_finalize(stmt) }
        bind(try JSONEncoder().encode(attempt), at: 1, to: stmt)
        bind(attempt.proposal.operationID.lowercased(), at: 2, to: stmt)
        bind(attempt.account.lowercased(), at: 3, to: stmt)
        bind(attempt.bookID.lowercased(), at: 4, to: stmt)
        bind(try JSONEncoder().encode(previous), at: 5, to: stmt)
        guard sqlite3_step(stmt) == SQLITE_DONE,
              sqlite3_changes(db) == 1 else { throw BusinessAtomicError.persistence }
    }
}
