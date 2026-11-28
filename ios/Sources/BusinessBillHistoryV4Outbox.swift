import Foundation
import SQLite3

/// Immutable insert-only bill operation. Never retry an uncertain POST or delete its reservation.
final class TXHistoryBillV4Outbox {
    private var db: OpaquePointer?
    init(url: URL? = nil) throws {
        let file = url ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("TianXianBusinessCommit/v4-history-bill.sqlite")
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
    private func bind(_ scope: String, _ stmt: OpaquePointer) {
        _ = scope.withCString { sqlite3_bind_text(stmt, 1, $0, -1, unsafeBitCast(-1, to: sqlite3_destructor_type.self)) }
    }
    func load(bookID: String, recordID: String) throws -> TXHistoryBillV4Prepared? {
        let stmt = try statement("SELECT payload FROM reserved WHERE scope=?")
        defer { sqlite3_finalize(stmt) }
        let scope = bookID.lowercased()+"|"+recordID
        bind(scope, stmt)
        let rc = sqlite3_step(stmt)
        if rc == SQLITE_DONE { return nil }
        guard rc == SQLITE_ROW, let ptr = sqlite3_column_blob(stmt, 0) else { throw BusinessAtomicError.persistence }
        let data = Data(bytes: ptr, count: Int(sqlite3_column_bytes(stmt, 0)))
        let value = try JSONDecoder().decode(TXHistoryBillV4Prepared.self, from: data)
        guard value.scopeKey == scope else { throw BusinessAtomicError.persistence }
        try value.validate()
        return value
    }
    func reserve(_ request: TXHistoryBillV4Prepared) throws {
        try request.validate()
        let bytes = try JSONEncoder().encode(request)
        let stmt = try statement("INSERT INTO reserved(scope,payload) VALUES(?,?)")
        defer { sqlite3_finalize(stmt) }
        bind(request.scopeKey, stmt)
        _ = bytes.withUnsafeBytes { sqlite3_bind_blob(stmt, 2, $0.baseAddress, Int32(bytes.count),
            unsafeBitCast(-1, to: sqlite3_destructor_type.self)) }
        guard sqlite3_step(stmt) == SQLITE_DONE else { throw BusinessAtomicError.locked }
        guard try load(bookID:request.request.book_id,recordID:request.request.record_sync_id) == request else { throw BusinessAtomicError.persistence }
    }
}
