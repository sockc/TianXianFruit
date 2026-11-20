import Foundation
import SQLite3

enum PurchaseWorkspaceStoreError: LocalizedError {
    case invalid, scope, missing, sql(String)
    var errorDescription: String? {
        switch self {
        case .invalid: return "采购草稿数据无效"
        case .scope: return "账号或账本已切换，禁止修改其他账本草稿"
        case .missing: return "草稿已被删除或修改，请刷新后操作"
        case .sql(let message): return "采购本机存储错误：" + message
        }
    }
}

/// A standalone local purchase editor store. It never queues /sync/push:
/// real cloud purchasing needs multi-table Android parity, atomic CAS and
/// settlement invalidation. Do not repurpose the old TEST outbox.
final class PurchaseWorkspaceStore {
    private var db: OpaquePointer?
    private let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

    init(url: URL? = nil) throws {
        let path: URL
        if let url { path = url }
        else {
            let root = FileManager.default.urls(
                for: .applicationSupportDirectory, in: .userDomainMask)[0]
            path = root.appendingPathComponent(
                "TianXianPurchaseWorkspace/v0.4.0.sqlite")
        }
        try FileManager.default.createDirectory(
            at: path.deletingLastPathComponent(), withIntermediateDirectories: true)
        guard sqlite3_open_v2(path.path, &db,
                SQLITE_OPEN_CREATE | SQLITE_OPEN_READWRITE | SQLITE_OPEN_FULLMUTEX,
                nil) == SQLITE_OK else {
            throw PurchaseWorkspaceStoreError.sql("无法打开本机数据库")
        }
        sqlite3_busy_timeout(db, 3000)
        try execute("""
            CREATE TABLE IF NOT EXISTS purchase_workspace_draft (
                id TEXT PRIMARY KEY,
                account TEXT NOT NULL,
                book_id TEXT NOT NULL,
                date TEXT NOT NULL,
                payload BLOB NOT NULL,
                updated_at_ms INTEGER NOT NULL
            )
            """)
        try execute("""
            CREATE INDEX IF NOT EXISTS idx_purchase_workspace_scope
            ON purchase_workspace_draft(account,book_id,date)
            """)
        try execute("""
            CREATE TABLE IF NOT EXISTS purchase_workspace_attempt (
                draft_id TEXT NOT NULL,
                account TEXT NOT NULL,
                book_id TEXT NOT NULL,
                payload BLOB NOT NULL,
                updated_at_ms INTEGER NOT NULL,
                PRIMARY KEY(draft_id,account,book_id)
            )
            """)
    }

    deinit { if db != nil { sqlite3_close(db) } }

    private func accountKey(_ account: String) -> String {
        account.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    }

    private func execute(_ sql: String) throws {
        guard sqlite3_exec(db, sql, nil, nil, nil) == SQLITE_OK else {
            throw PurchaseWorkspaceStoreError.sql(
                db.map { String(cString: sqlite3_errmsg($0)) } ?? "SQL")
        }
    }

    private func statement(_ sql: String) throws -> OpaquePointer {
        var query: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &query, nil) == SQLITE_OK,
              let query else {
            throw PurchaseWorkspaceStoreError.sql(
                db.map { String(cString: sqlite3_errmsg($0)) } ?? "SQL")
        }
        return query
    }

    private func bind(_ text: String, at index: Int32,
                      query: OpaquePointer) {
        text.withCString {
            sqlite3_bind_text(query, index, $0, -1, transient)
        }
    }

    private func bind(_ data: Data, at index: Int32,
                      query: OpaquePointer) {
        data.withUnsafeBytes {
            sqlite3_bind_blob(query, index, $0.baseAddress,
                              Int32(data.count), transient)
        }
    }

    private func decode(_ query: OpaquePointer,
                        account: String, bookID: String) throws
        -> PurchaseWorkspaceDraft {
        let count = Int(sqlite3_column_bytes(query, 0))
        guard let bytes = sqlite3_column_blob(query, 0),
              count > 0 else { throw PurchaseWorkspaceStoreError.invalid }
        let item = try JSONDecoder().decode(
            PurchaseWorkspaceDraft.self, from: Data(bytes: bytes, count: count))
        guard accountKey(item.account) == accountKey(account),
              item.bookID == bookID else {
            throw PurchaseWorkspaceStoreError.scope
        }
        return item
    }

    func load(account: String, bookID: String) throws
        -> [PurchaseWorkspaceDraft] {
        let query = try statement("""
            SELECT payload FROM purchase_workspace_draft
            WHERE account=? AND book_id=?
            ORDER BY date DESC,updated_at_ms DESC
            """)
        defer { sqlite3_finalize(query) }
        bind(accountKey(account), at: 1, query: query)
        bind(bookID, at: 2, query: query)
        var rows: [PurchaseWorkspaceDraft] = []
        var status = sqlite3_step(query)
        while status == SQLITE_ROW {
            rows.append(try decode(query, account: account, bookID: bookID))
            status = sqlite3_step(query)
        }
        guard status == SQLITE_DONE else {
            throw PurchaseWorkspaceStoreError.sql("读取草稿失败")
        }
        return rows
    }

    func save(_ draft: PurchaseWorkspaceDraft,
              account: String, bookID: String) throws {
        guard !draft.id.isEmpty, !draft.bookID.isEmpty,
              !draft.account.isEmpty, accountKey(draft.account) == accountKey(account),
              draft.bookID == bookID else {
            throw PurchaseWorkspaceStoreError.scope
        }
        let blob = try JSONEncoder().encode(draft)
        let query = try statement("""
            INSERT INTO purchase_workspace_draft
                (id,account,book_id,date,payload,updated_at_ms)
            VALUES (?,?,?,?,?,?)
            ON CONFLICT(id) DO UPDATE SET
                date=excluded.date,
                payload=excluded.payload,
                updated_at_ms=excluded.updated_at_ms
            WHERE account=excluded.account AND book_id=excluded.book_id
            """)
        defer { sqlite3_finalize(query) }
        bind(draft.id, at: 1, query: query)
        bind(accountKey(account), at: 2, query: query)
        bind(bookID, at: 3, query: query)
        bind(draft.date, at: 4, query: query)
        bind(blob, at: 5, query: query)
        sqlite3_bind_int64(query, 6,
            Int64(draft.updatedAt.timeIntervalSince1970 * 1000))
        guard sqlite3_step(query) == SQLITE_DONE,
              sqlite3_changes(db) == 1 else {
            throw PurchaseWorkspaceStoreError.scope
        }
    }

    func delete(_ id: String, account: String, bookID: String) throws {
        let query = try statement("""
            DELETE FROM purchase_workspace_draft
            WHERE id=? AND account=? AND book_id=?
            """)
        defer { sqlite3_finalize(query) }
        bind(id, at: 1, query: query)
        bind(accountKey(account), at: 2, query: query)
        bind(bookID, at: 3, query: query)
        guard sqlite3_step(query) == SQLITE_DONE else {
            throw PurchaseWorkspaceStoreError.sql("删除本机草稿失败")
        }
        guard sqlite3_changes(db) == 1 else {
            throw PurchaseWorkspaceStoreError.missing
        }
    }

    /// Immutable outbox is written BEFORE making the HTTP request.
    func attempt(id: String, account: String,
                 bookID: String) throws -> PurchaseCommitAttempt? {
        let query = try statement("""
            SELECT payload FROM purchase_workspace_attempt
            WHERE draft_id=? AND account=? AND book_id=?
            """)
        defer { sqlite3_finalize(query) }
        bind(id, at: 1, query: query)
        bind(accountKey(account), at: 2, query: query)
        bind(bookID, at: 3, query: query)
        let state = sqlite3_step(query)
        if state == SQLITE_DONE { return nil }
        guard state == SQLITE_ROW,
              let raw = sqlite3_column_blob(query, 0),
              sqlite3_column_bytes(query, 0) > 0 else {
            throw PurchaseWorkspaceStoreError.invalid
        }
        let item = try JSONDecoder().decode(
            PurchaseCommitAttempt.self,
            from: Data(bytes: raw, count: Int(sqlite3_column_bytes(query, 0))))
        guard accountKey(item.account) == accountKey(account),
              item.bookID == bookID, item.id == id else {
            throw PurchaseWorkspaceStoreError.scope
        }
        return item
    }

    /// Never replace the payload of an in-flight purchase. The same operation
    /// UUID and exact proposal survive app crash, network loss or account swap.
    func reserve(_ proposal: PurchaseCommitProposal,
                 account: String, bookID: String) throws
        -> PurchaseCommitAttempt {
        guard proposal.account.lowercased() == accountKey(account),
              proposal.bookID == bookID,
              !proposal.draftID.isEmpty else { throw PurchaseWorkspaceStoreError.scope }
        if let old = try attempt(
            id: proposal.draftID, account: account, bookID: bookID) {
            guard old.proposal == proposal else {
                throw PurchaseAtomicError.staleDraft
            }
            return old
        }
        let now = Date()
        let item = PurchaseCommitAttempt(
            account: accountKey(account), bookID: bookID,
            proposal: proposal, createdAt: now, updatedAt: now,
            phase: .prepared, receipt: nil)
        let blob = try JSONEncoder().encode(item)
        let query = try statement("""
            INSERT INTO purchase_workspace_attempt
                (draft_id,account,book_id,payload,updated_at_ms)
            VALUES (?,?,?,?,?)
            """)
        defer { sqlite3_finalize(query) }
        bind(proposal.draftID, at: 1, query: query)
        bind(accountKey(account), at: 2, query: query)
        bind(bookID, at: 3, query: query)
        bind(blob, at: 4, query: query)
        sqlite3_bind_int64(query, 5, Int64(now.timeIntervalSince1970 * 1000))
        guard sqlite3_step(query) == SQLITE_DONE,
              sqlite3_changes(db) == 1 else {
            throw PurchaseAtomicError.persistentStore
        }
        return item
    }

    func updateAttempt(_ item: PurchaseCommitAttempt,
                       account: String, bookID: String) throws {
        guard accountKey(item.account) == accountKey(account),
              item.bookID == bookID,
              let previous = try attempt(id: item.id, account: account,
                                         bookID: bookID),
              item.proposal == previous.proposal else {
            throw PurchaseWorkspaceStoreError.scope
        }
        let allowed: Bool
        switch (previous.phase, item.phase) {
        case (.prepared, .unknown), (.prepared, .accepted),
             (.unknown, .accepted), (.accepted, .confirmed),
             (.prepared, .prepared), (.unknown, .unknown),
             (.accepted, .accepted), (.confirmed, .confirmed):
            allowed = true
        default: allowed = false
        }
        guard allowed,
              item.phase == .prepared || item.updatedAt >= previous.updatedAt else {
            throw PurchaseWorkspaceStoreError.invalid
        }
        let blob = try JSONEncoder().encode(item)
        let query = try statement("""
            UPDATE purchase_workspace_attempt SET payload=?,updated_at_ms=?
            WHERE draft_id=? AND account=? AND book_id=?
              AND updated_at_ms=?
            """)
        defer { sqlite3_finalize(query) }
        bind(blob, at: 1, query: query)
        sqlite3_bind_int64(query, 2,
            Int64(item.updatedAt.timeIntervalSince1970 * 1000))
        bind(item.id, at: 3, query: query)
        bind(accountKey(account), at: 4, query: query)
        bind(bookID, at: 5, query: query)
        sqlite3_bind_int64(query, 6,
            Int64(previous.updatedAt.timeIntervalSince1970 * 1000))
        guard sqlite3_step(query) == SQLITE_DONE,
              sqlite3_changes(db) == 1 else {
            throw PurchaseWorkspaceStoreError.missing
        }
    }

    func assertEditable(id: String, account: String, bookID: String) throws {
        if try attempt(id: id, account: account, bookID: bookID) != nil {
            throw PurchaseAtomicError.blockedByAttempt
        }
    }

}
