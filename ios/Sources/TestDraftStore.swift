import Foundation
import SQLite3

/// Separate, device-local SQLite file: deliberately not in CloudSyncManager's
/// dirty queue and never included in /sync/push.
final class TestDraftStore {
    private var database: OpaquePointer?
    private let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

    init(url: URL? = nil) throws {
        let file: URL
        if let url {
            file = url
        } else {
            let root = FileManager.default.urls(for: .applicationSupportDirectory,
                                                 in: .userDomainMask)[0]
                .appendingPathComponent("TestWriteLab", isDirectory: true)
            try FileManager.default.createDirectory(at: root,
                                                     withIntermediateDirectories: true)
            try? FileManager.default.setAttributes([
                .protectionKey: FileProtectionType.completeUntilFirstUserAuthentication
            ], ofItemAtPath: root.path)
            var local = root
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            try? local.setResourceValues(values)
            file = root.appendingPathComponent("local-only-drafts.sqlite")
        }
        guard sqlite3_open_v2(file.path, &database,
                              SQLITE_OPEN_CREATE | SQLITE_OPEN_READWRITE | SQLITE_OPEN_FULLMUTEX,
                              nil) == SQLITE_OK else {
            let message = database.map { String(cString: sqlite3_errmsg($0)) } ?? "无法打开"
            if database != nil { sqlite3_close(database) }
            database = nil
            throw TestDraftStoreError.sql(message)
        }
        sqlite3_busy_timeout(database, 3000)
        try exec("""
        CREATE TABLE IF NOT EXISTS test_preview_drafts (
          id TEXT PRIMARY KEY,
          account TEXT NOT NULL,
          book_id TEXT NOT NULL,
          kind TEXT NOT NULL,
          date TEXT NOT NULL,
          json BLOB NOT NULL,
          created_at_ms INTEGER NOT NULL
        )
        """)
        try exec("CREATE INDEX IF NOT EXISTS idx_test_drafts_scope ON test_preview_drafts(account,book_id,created_at_ms)")
    }

    deinit { if database != nil { sqlite3_close(database) } }

    private func exec(_ sql: String) throws {
        guard sqlite3_exec(database, sql, nil, nil, nil) == SQLITE_OK else {
            throw TestDraftStoreError.sql(String(cString: sqlite3_errmsg(database)))
        }
    }

    private func prepare(_ sql: String) throws -> OpaquePointer {
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(database, sql, -1, &statement, nil) == SQLITE_OK,
              let statement else {
            throw TestDraftStoreError.sql(String(cString: sqlite3_errmsg(database)))
        }
        return statement
    }

    private func bind(_ value: String, at index: Int32, to statement: OpaquePointer) {
        value.withCString { c in
            sqlite3_bind_text(statement, index, c, -1, transient)
        }
    }

    private func bind(_ value: Data, at index: Int32, to statement: OpaquePointer) {
        value.withUnsafeBytes { bytes in
            sqlite3_bind_blob(statement, index, bytes.baseAddress,
                              Int32(value.count), transient)
        }
    }

    private func step(_ statement: OpaquePointer) throws {
        guard sqlite3_step(statement) == SQLITE_DONE else {
            throw TestDraftStoreError.sql(String(cString: sqlite3_errmsg(database)))
        }
    }

    private func normalized(_ username: String) -> String {
        username.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    }

    func save(_ draft: TestDraft) throws {
        guard !draft.id.isEmpty, !draft.bookID.isEmpty, !draft.account.isEmpty else {
            throw TestDraftStoreError.invalid
        }
        let data = try JSONEncoder().encode(draft)
        let statement = try prepare("""
            INSERT INTO test_preview_drafts(id,account,book_id,kind,date,json,created_at_ms)
            VALUES (?,?,?,?,?,?,?)
            ON CONFLICT(id) DO UPDATE SET
              json=excluded.json
            WHERE account=excluded.account AND book_id=excluded.book_id
        """)
        defer { sqlite3_finalize(statement) }
        bind(draft.id, at: 1, to: statement)
        bind(normalized(draft.account), at: 2, to: statement)
        bind(draft.bookID, at: 3, to: statement)
        bind(draft.kind.rawValue, at: 4, to: statement)
        bind(draft.date, at: 5, to: statement)
        bind(data, at: 6, to: statement)
        sqlite3_bind_int64(statement, 7,
                           Int64(draft.createdAt.timeIntervalSince1970 * 1000))
        try step(statement)
    }

    func load(account: String, book: String) throws -> [TestDraft] {
        let statement = try prepare("""
            SELECT json FROM test_preview_drafts
            WHERE account=? AND book_id=? ORDER BY created_at_ms DESC
        """)
        defer { sqlite3_finalize(statement) }
        bind(normalized(account), at: 1, to: statement)
        bind(book, at: 2, to: statement)
        var result: [TestDraft] = []
        var status = sqlite3_step(statement)
        while status == SQLITE_ROW {
            let len = Int(sqlite3_column_bytes(statement, 0))
            guard let pointer = sqlite3_column_blob(statement, 0) else {
                throw TestDraftStoreError.invalid
            }
            let draft = try JSONDecoder().decode(TestDraft.self,
                                                  from: Data(bytes: pointer, count: len))
            // A corrupt/misplaced row must never leak into another book.
            guard normalized(draft.account) == normalized(account),
                  draft.bookID == book else { throw TestDraftStoreError.invalid }
            result.append(draft)
            status = sqlite3_step(statement)
        }
        guard status == SQLITE_DONE else {
            throw TestDraftStoreError.sql(String(cString: sqlite3_errmsg(database)))
        }
        return result
    }

    func delete(id: String, account: String, book: String) throws {
        let statement = try prepare("""
            DELETE FROM test_preview_drafts WHERE id=? AND account=? AND book_id=?
        """)
        defer { sqlite3_finalize(statement) }
        bind(id, at: 1, to: statement)
        bind(normalized(account), at: 2, to: statement)
        bind(book, at: 3, to: statement)
        try step(statement)
    }
}

enum TestDraftStoreError: LocalizedError {
    case invalid
    case sql(String)

    var errorDescription: String? {
        switch self {
        case .invalid: return "测试草稿数据无效，没有写入或读取其他账本的数据"
        case .sql(let value): return "测试草稿本地存储失败：" + value
        }
    }
}
