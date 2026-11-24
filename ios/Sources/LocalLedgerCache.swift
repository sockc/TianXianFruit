import Foundation
import SQLite3

enum LocalCacheError: LocalizedError {
    case sql(String)
    case invalidEvent

    var errorDescription: String? {
        switch self {
        case .sql(let message): return "本地只读缓存异常：\(message)"
        case .invalidEvent: return "云端记录身份无效，已保留此前缓存"
        }
    }
}

struct LoadedLedgerCache {
    let snapshot: ReadOnlySnapshot
    let cursor: Int64
    let syncedAt: Date
}

/// Durable mirror of server-confirmed events. No SQL tables matching the
/// Android business schema are created, and no cloud mutation is possible.
/// All updates to rows AND cursor are committed within the same transaction.
final class LocalLedgerCache {
    private var database: OpaquePointer?
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()
    private let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

    init(url: URL? = nil) throws {
        let file: URL
        if let url = url {
            file = url
        } else {
            let base = FileManager.default.urls(for: .applicationSupportDirectory,
                                                 in: .userDomainMask)[0]
            let directory = base.appendingPathComponent("LedgerReadOnly", isDirectory: true)
            try FileManager.default.createDirectory(at: directory,
                                                      withIntermediateDirectories: true)
            try? FileManager.default.setAttributes([
                .protectionKey: FileProtectionType.completeUntilFirstUserAuthentication
            ], ofItemAtPath: directory.path)
            file = directory.appendingPathComponent("cloud-events.sqlite")
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            var path = directory
            try? path.setResourceValues(values)
        }
        let result = sqlite3_open_v2(file.path, &database,
                                     SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX,
                                     nil)
        guard result == SQLITE_OK else {
            let message = database.map { String(cString: sqlite3_errmsg($0)) } ?? "打开失败"
            if database != nil { sqlite3_close(database) }
            database = nil
            throw LocalCacheError.sql(message)
        }
        sqlite3_busy_timeout(database, 3000)
        try run("PRAGMA foreign_keys=ON")
        try run("""
        CREATE TABLE IF NOT EXISTS cloud_rows (
          account TEXT NOT NULL,
          book_id TEXT NOT NULL,
          table_name TEXT NOT NULL,
          sync_id TEXT NOT NULL,
          row_version INTEGER NOT NULL,
          event BLOB NOT NULL,
          PRIMARY KEY (account, book_id, table_name, sync_id)
        )
        """)
        try run("""
        CREATE TABLE IF NOT EXISTS cloud_state (
          account TEXT NOT NULL,
          book_id TEXT NOT NULL,
          cursor INTEGER NOT NULL,
          synced_at_ms INTEGER NOT NULL,
          PRIMARY KEY (account, book_id)
        )
        """)
        try run("""
        CREATE TABLE IF NOT EXISTS customer_rows (
          account TEXT NOT NULL,
          book_id TEXT NOT NULL,
          kind TEXT NOT NULL,
          entity_key TEXT NOT NULL,
          revision INTEGER NOT NULL,
          event BLOB NOT NULL,
          PRIMARY KEY (account, book_id, kind, entity_key)
        )
        """)
        try run("""
        CREATE TABLE IF NOT EXISTS customer_state (
          account TEXT NOT NULL,
          book_id TEXT NOT NULL,
          cursor INTEGER NOT NULL,
          synced_at_ms INTEGER NOT NULL,
          PRIMARY KEY (account, book_id)
        )
        """)
    }

    deinit { if database != nil { sqlite3_close(database) } }

    private func run(_ sql: String) throws {
        guard sqlite3_exec(database, sql, nil, nil, nil) == SQLITE_OK else {
            throw LocalCacheError.sql(String(cString: sqlite3_errmsg(database)))
        }
    }

    private func prepare(_ sql: String) throws -> OpaquePointer {
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(database, sql, -1, &statement, nil) == SQLITE_OK,
              let statement = statement else {
            throw LocalCacheError.sql(String(cString: sqlite3_errmsg(database)))
        }
        return statement
    }

    private func bind(_ value: String, _ position: Int32, _ statement: OpaquePointer) {
        value.withCString { sqlite3_bind_text(statement, position, $0, -1, transient) }
    }

    private func bind(_ value: Data, _ position: Int32, _ statement: OpaquePointer) {
        value.withUnsafeBytes { bytes in
            sqlite3_bind_blob(statement, position, bytes.baseAddress,
                              Int32(value.count), transient)
        }
    }

    private func step(_ statement: OpaquePointer) throws {
        guard sqlite3_step(statement) == SQLITE_DONE else {
            throw LocalCacheError.sql(String(cString: sqlite3_errmsg(database)))
        }
    }

    static func scope(_ username: String) -> String {
        username.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    }

    func load(account: String, book: String) throws -> LoadedLedgerCache? {
        let account = Self.scope(account)
        let meta = try prepare("""
            SELECT cursor,synced_at_ms FROM cloud_state
            WHERE account=? AND book_id=?
        """)
        defer { sqlite3_finalize(meta) }
        bind(account, 1, meta)
        bind(book, 2, meta)
        guard sqlite3_step(meta) == SQLITE_ROW else { return nil }
        let cursor = sqlite3_column_int64(meta, 0)
        let at = sqlite3_column_int64(meta, 1)
        let statement = try prepare("""
            SELECT event FROM cloud_rows WHERE account=? AND book_id=?
        """)
        defer { sqlite3_finalize(statement) }
        bind(account, 1, statement)
        bind(book, 2, statement)
        var snapshot = ReadOnlySnapshot()
        var rc = sqlite3_step(statement)
        while rc == SQLITE_ROW {
            let size = Int(sqlite3_column_bytes(statement, 0))
            guard let ptr = sqlite3_column_blob(statement, 0) else {
                throw LocalCacheError.sql("缓存记录损坏")
            }
            let event = try decoder.decode(SyncEvent.self, from: Data(bytes: ptr, count: size))
            snapshot.apply([event])
            rc = sqlite3_step(statement)
        }
        guard rc == SQLITE_DONE else {
            throw LocalCacheError.sql(String(cString: sqlite3_errmsg(database)))
        }
        return LoadedLedgerCache(snapshot: snapshot, cursor: cursor,
                                 syncedAt: Date(timeIntervalSince1970: Double(at) / 1000))
    }

    /// Atomic: either all event changes and their cursor are committed or none.
    /// replace=true is the initial baseline or an explicit full recheck.
    func commit(account: String, book: String, events: [SyncEvent],
                cursor: Int64, replace: Bool, at: Date = Date()) throws {
        guard cursor >= 0, !account.isEmpty, !book.isEmpty else {
            throw LocalCacheError.invalidEvent
        }
        let account = Self.scope(account)
        try run("BEGIN IMMEDIATE TRANSACTION")
        do {
            if replace {
                let clear = try prepare("DELETE FROM cloud_rows WHERE account=? AND book_id=?")
                defer { sqlite3_finalize(clear) }
                bind(account, 1, clear); bind(book, 2, clear)
                try step(clear)
            }
            let insert = try prepare("""
                INSERT INTO cloud_rows(account,book_id,table_name,sync_id,row_version,event)
                VALUES(?,?,?,?,?,?)
                ON CONFLICT(account,book_id,table_name,sync_id) DO UPDATE SET
                    row_version=excluded.row_version, event=excluded.event
                WHERE excluded.row_version>=cloud_rows.row_version
            """)
            defer { sqlite3_finalize(insert) }
            for event in events {
                guard !event.tableName.isEmpty, !event.syncId.isEmpty, event.rowVersion >= 0 else {
                    throw LocalCacheError.invalidEvent
                }
                sqlite3_reset(insert)
                sqlite3_clear_bindings(insert)
                bind(account, 1, insert)
                bind(book, 2, insert)
                bind(event.tableName, 3, insert)
                bind(event.syncId, 4, insert)
                sqlite3_bind_int64(insert, 5, event.rowVersion)
                try bind(encoder.encode(event), 6, insert)
                try step(insert)
            }
            let meta = try prepare("""
                INSERT INTO cloud_state(account,book_id,cursor,synced_at_ms)
                VALUES(?,?,?,?)
                ON CONFLICT(account,book_id) DO UPDATE SET
                   cursor=excluded.cursor, synced_at_ms=excluded.synced_at_ms
            """)
            defer { sqlite3_finalize(meta) }
            bind(account, 1, meta)
            bind(book, 2, meta)
            sqlite3_bind_int64(meta, 3, cursor)
            sqlite3_bind_int64(meta, 4, Int64(at.timeIntervalSince1970 * 1000))
            try step(meta)
            try run("COMMIT")
        } catch {
            try? run("ROLLBACK")
            throw error
        }
    }

    /// Called only on explicit sign-out: do not expose the old user's cache.
    func erase(account: String) throws {
        let account = Self.scope(account)
        try run("BEGIN IMMEDIATE TRANSACTION")
        do {
            for name in ["cloud_rows", "cloud_state", "customer_rows", "customer_state"] {
                let statement = try prepare("DELETE FROM \(name) WHERE account=?")
                defer { sqlite3_finalize(statement) }
                bind(account, 1, statement)
                try step(statement)
            }
            try run("COMMIT")
        } catch {
            try? run("ROLLBACK")
            throw error
        }
    }
}

struct CustomerCacheLoaded {
    let snapshot: CustomerReadSnapshot
    let cursor: Int64
    let syncedAt: Date
}

extension LocalLedgerCache {
    func loadCustomer(account: String, book: String) throws -> CustomerCacheLoaded? {
        let account = Self.scope(account)
        let meta = try prepare("""
            SELECT cursor,synced_at_ms FROM customer_state WHERE account=? AND book_id=?
        """)
        defer { sqlite3_finalize(meta) }
        bind(account, 1, meta)
        bind(book, 2, meta)
        guard sqlite3_step(meta) == SQLITE_ROW else { return nil }
        let cursor = sqlite3_column_int64(meta, 0)
        let at = sqlite3_column_int64(meta, 1)

        let rows = try prepare("""
            SELECT event FROM customer_rows WHERE account=? AND book_id=?
        """)
        defer { sqlite3_finalize(rows) }
        bind(account, 1, rows)
        bind(book, 2, rows)
        var snapshot = CustomerReadSnapshot()
        var status = sqlite3_step(rows)
        while status == SQLITE_ROW {
            let count = Int(sqlite3_column_bytes(rows, 0))
            guard let bytes = sqlite3_column_blob(rows, 0) else {
                throw LocalCacheError.sql("客户事件缓存数据损坏")
            }
            let event = try decoder.decode(CustomerChangeEvent.self,
                                           from: Data(bytes: bytes, count: count))
            snapshot.apply([event])
            status = sqlite3_step(rows)
        }
        guard status == SQLITE_DONE else {
            throw LocalCacheError.sql(String(cString: sqlite3_errmsg(database)))
        }
        return CustomerCacheLoaded(snapshot: snapshot, cursor: cursor,
                                   syncedAt: Date(timeIntervalSince1970: Double(at) / 1000))
    }

    func commitCustomer(account: String, book: String, events: [CustomerChangeEvent],
                        cursor: Int64, replace: Bool, at: Date = Date()) throws {
        guard cursor >= 0, !account.isEmpty, !book.isEmpty else {
            throw LocalCacheError.invalidEvent
        }
        let account = Self.scope(account)
        try run("BEGIN IMMEDIATE TRANSACTION")
        do {
            if replace {
                let clear = try prepare("DELETE FROM customer_rows WHERE account=? AND book_id=?")
                defer { sqlite3_finalize(clear) }
                bind(account, 1, clear)
                bind(book, 2, clear)
                try step(clear)
            }
            let insert = try prepare("""
                INSERT INTO customer_rows(account,book_id,kind,entity_key,revision,event)
                VALUES(?,?,?,?,?,?)
                ON CONFLICT(account,book_id,kind,entity_key) DO UPDATE SET
                  revision=excluded.revision, event=excluded.event
                WHERE excluded.revision>=customer_rows.revision
            """)
            defer { sqlite3_finalize(insert) }
            for change in events {
                guard !change.kind.isEmpty, !change.key.isEmpty, change.revision >= 0 else {
                    throw LocalCacheError.invalidEvent
                }
                sqlite3_reset(insert)
                sqlite3_clear_bindings(insert)
                bind(account, 1, insert)
                bind(book, 2, insert)
                bind(change.kind, 3, insert)
                bind(change.key, 4, insert)
                sqlite3_bind_int64(insert, 5, change.revision)
                try bind(encoder.encode(change), 6, insert)
                try step(insert)
            }
            let state = try prepare("""
                INSERT INTO customer_state(account,book_id,cursor,synced_at_ms)
                VALUES(?,?,?,?)
                ON CONFLICT(account,book_id) DO UPDATE SET
                  cursor=excluded.cursor,synced_at_ms=excluded.synced_at_ms
            """)
            defer { sqlite3_finalize(state) }
            bind(account, 1, state)
            bind(book, 2, state)
            sqlite3_bind_int64(state, 3, cursor)
            sqlite3_bind_int64(state, 4, Int64(at.timeIntervalSince1970 * 1000))
            try step(state)
            try run("COMMIT")
        } catch {
            try? run("ROLLBACK")
            throw error
        }
    }
}
