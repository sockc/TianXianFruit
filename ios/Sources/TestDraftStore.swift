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
        try exec("""
        CREATE TABLE IF NOT EXISTS isolated_staging_jobs (
          id TEXT PRIMARY KEY,
          source_account TEXT NOT NULL,
          book_id TEXT NOT NULL,
          stage_host TEXT NOT NULL,
          state TEXT NOT NULL,
          payload BLOB NOT NULL,
          created_at_ms INTEGER NOT NULL
        )
        """)
        try exec("CREATE INDEX IF NOT EXISTS idx_staging_jobs_scope ON isolated_staging_jobs(source_account,book_id)")
        try exec("""
            CREATE TABLE IF NOT EXISTS isolated_business_jobs (
                id TEXT PRIMARY KEY,
                source_account TEXT NOT NULL,
                book_id TEXT NOT NULL,
                stage_host TEXT NOT NULL,
                state TEXT NOT NULL,
                payload BLOB NOT NULL,
                created_at_ms INTEGER NOT NULL
            )
        """)
        try exec("CREATE INDEX IF NOT EXISTS idx_business_jobs_scope ON isolated_business_jobs(source_account,book_id)")
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
        // Once a draft has an upload intent, its financial fields and
        // identity are frozen. Edits/duplicates must use a NEW draft ID.
        guard try stagingJob(id: draft.id, account: draft.account,
                             book: draft.bookID) == nil,
              try businessJob(id: draft.id, account: draft.account,
                              book: draft.bookID) == nil else {
            throw TestDraftStoreError.lockedForUpload
        }
        let data = try JSONEncoder().encode(draft)
        let statement = try prepare("""
            INSERT INTO test_preview_drafts(id,account,book_id,kind,date,json,created_at_ms)
            VALUES (?,?,?,?,?,?,?)
            ON CONFLICT(id) DO UPDATE SET
              json=excluded.json,
              kind=excluded.kind,
              date=excluded.date
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
        guard try stagingJob(id: id, account: account, book: book) == nil,
              try businessJob(id: id, account: account, book: book) == nil else {
            throw TestDraftStoreError.lockedForUpload
        }
        let statement = try prepare("""
            DELETE FROM test_preview_drafts WHERE id=? AND account=? AND book_id=?
        """)
        defer { sqlite3_finalize(statement) }
        bind(id, at: 1, to: statement)
        bind(normalized(account), at: 2, to: statement)
        bind(book, at: 3, to: statement)
        try step(statement)
    }

    /// Exactly one immutable staging upload intent per local draft.
    /// The payload/host cannot be changed after preparation, even on retry.
    func saveStagingJob(_ job: StagingPurchaseJob) throws {
        guard !job.id.isEmpty, !job.bookID.isEmpty, !job.sourceAccount.isEmpty,
              job.stageHost.hasPrefix("https://"),
              !job.stageInstanceID.isEmpty else { throw TestDraftStoreError.invalid }
        if let previous = try stagingJob(id: job.id, account: job.sourceAccount,
                                         book: job.bookID) {
            guard previous.id == job.id,
                  previous.stageHost == job.stageHost,
                  previous.stageInstanceID == job.stageInstanceID,
                  previous.stageUsername == job.stageUsername,
                  previous.bookID == job.bookID,
                  previous.deviceID == job.deviceID,
                  previous.orderSyncID == job.orderSyncID,
                  previous.itemSyncID == job.itemSyncID,
                  previous.orderRemoteID == job.orderRemoteID,
                  previous.itemRemoteID == job.itemRemoteID,
                  previous.orderPayload == job.orderPayload,
                  previous.itemPayload == job.itemPayload,
                  (previous.extraItems ?? []) == (job.extraItems ?? []),
                  previous.createdAt == job.createdAt else {
                throw TestDraftStoreError.invalid
            }
            let allowed: [StagingUploadState: Set<StagingUploadState>] = [
                .ready: [.ready, .sendingOrder, .needsReview],
                .sendingOrder: [.sendingOrder, .orderConfirmed, .complete, .needsReview],
                .orderConfirmed: [.orderConfirmed, .sendingItem, .complete, .needsReview],
                .sendingItem: [.sendingItem, .complete, .needsReview, .orderConfirmed],
                .needsReview: [.needsReview, .orderConfirmed, .complete],
                .complete: [.complete]
            ]
            guard allowed[previous.state]?.contains(job.state) == true else {
                throw TestDraftStoreError.invalid
            }
            let originalStarted = previous.startedItemIDs ?? []
            let updatedStarted = job.startedItemIDs ?? []
            guard updatedStarted.starts(with: originalStarted),
                  Set(updatedStarted).count == updatedStarted.count,
                  Set(updatedStarted).isSubset(of: Set(
                      [job.itemSyncID] + (job.extraItems ?? []).map(\.syncID))) else {
                throw TestDraftStoreError.invalid
            }
            if previous.androidCheckedAt != nil &&
               previous.androidCheckedAt != job.androidCheckedAt {
                throw TestDraftStoreError.invalid
            }
            // Once recorded, the Android observations and the cloud
            // fingerprint are immutable, including across logout/restart.
            if let earlier = previous.androidReview,
               earlier != job.androidReview {
                throw TestDraftStoreError.invalid
            }
            if previous.androidReview == nil, job.androidReview != nil,
               previous.state != .complete {
                throw TestDraftStoreError.invalid
            }
            // The canonical parent link may be set ONCE after verified
            // server readback, before the child request is sent. No rewriting
            // a frozen association on retries or after a conflict.
            if let previousID = previous.childParentID,
               previousID != job.childParentID {
                throw TestDraftStoreError.invalid
            }
            if previous.itemRequestStarted == true &&
               job.itemRequestStarted != true {
                throw TestDraftStoreError.invalid
            }
            if previous.childParentID == nil, job.childParentID != nil,
               ![.sendingOrder, .orderConfirmed, .needsReview].contains(previous.state) {
                throw TestDraftStoreError.invalid
            }
        }
        guard job.androidCheckedAt == nil || job.state == .complete else {
            throw TestDraftStoreError.invalid
        }
        guard job.androidReview == nil ||
              (job.state == .complete && job.androidCheckedAt != nil) else {
            throw TestDraftStoreError.invalid
        }
        if let canonical = job.childParentID,
           canonical <= 0 || canonical > 9_007_199_254_740_991 {
            throw TestDraftStoreError.invalid
        }
        if job.state == .sendingItem &&
           (job.childParentID == nil || job.itemRequestStarted != true) {
            throw TestDraftStoreError.invalid
        }
        if let started = job.startedItemIDs {
            let valid = [job.itemSyncID] + (job.extraItems ?? []).map(\.syncID)
            guard Set(started).count == started.count,
                  valid.starts(with: started),
                  (job.state != .sendingItem || !started.isEmpty),
                  started.isEmpty || job.childParentID != nil else {
                throw TestDraftStoreError.invalid
            }
        }
        let stmt = try prepare("""
            INSERT INTO isolated_staging_jobs
            (id,source_account,book_id,stage_host,state,payload,created_at_ms)
            VALUES (?,?,?,?,?,?,?)
            ON CONFLICT(id) DO UPDATE SET
                payload=excluded.payload,
                state=excluded.state
            WHERE source_account=excluded.source_account AND
                  book_id=excluded.book_id AND stage_host=excluded.stage_host
        """)
        defer { sqlite3_finalize(stmt) }
        bind(job.id, at: 1, to: stmt)
        bind(normalized(job.sourceAccount), at: 2, to: stmt)
        bind(job.bookID, at: 3, to: stmt)
        bind(job.stageHost, at: 4, to: stmt)
        bind(job.state.rawValue, at: 5, to: stmt)
        bind(try JSONEncoder().encode(job), at: 6, to: stmt)
        sqlite3_bind_int64(stmt, 7,
                           Int64(job.createdAt.timeIntervalSince1970 * 1000))
        try step(stmt)
    }

    /// Each submitted business draft has one immutable local intent.
    /// A sending/unknown state cannot return to ready or send again.
    func saveBusinessJob(_ job: BusinessUploadJob) throws {
        guard !job.id.isEmpty, !job.sourceAccount.isEmpty,
              !job.bookID.isEmpty,
              job.stageHost == StagingPurchasePolicy.productionURL,
              job.stageInstanceID == "production-scoped-testbook:" + job.bookID else {
            throw TestDraftStoreError.invalid
        }
        if let old = try businessJob(id: job.id, account: job.sourceAccount,
                                      book: job.bookID) {
            guard old.sourceAccount == job.sourceAccount,
                  old.bookID == job.bookID, old.stageHost == job.stageHost,
                  old.stageInstanceID == job.stageInstanceID,
                  old.stageUsername == job.stageUsername,
                  old.deviceID == job.deviceID, old.syncID == job.syncID,
                  old.payload == job.payload, old.createdAt == job.createdAt else {
                throw TestDraftStoreError.invalid
            }
            let allowed: [BusinessUploadState: Set<BusinessUploadState>] = [
                .ready: [.ready, .sending, .needsReview],
                .sending: [.sending, .complete, .needsReview],
                .needsReview: [.needsReview, .complete],
                .complete: [.complete]
            ]
            guard allowed[old.state]?.contains(job.state) == true else {
                throw TestDraftStoreError.invalid
            }
        }
        let stmt = try prepare("""
            INSERT INTO isolated_business_jobs
              (id,source_account,book_id,stage_host,state,payload,created_at_ms)
            VALUES (?,?,?,?,?,?,?)
            ON CONFLICT(id) DO UPDATE SET payload=excluded.payload,
              state=excluded.state
            WHERE source_account=excluded.source_account AND
                  book_id=excluded.book_id AND stage_host=excluded.stage_host
        """)
        defer { sqlite3_finalize(stmt) }
        bind(job.id, at: 1, to: stmt)
        bind(normalized(job.sourceAccount), at: 2, to: stmt)
        bind(job.bookID, at: 3, to: stmt)
        bind(job.stageHost, at: 4, to: stmt)
        bind(job.state.rawValue, at: 5, to: stmt)
        bind(try JSONEncoder().encode(job), at: 6, to: stmt)
        sqlite3_bind_int64(stmt, 7, Int64(job.createdAt.timeIntervalSince1970 * 1000))
        try step(stmt)
        // SQLite ON CONFLICT ... WHERE may do nothing for an ID belonging
        // to another account/book. Never send before checking disk readback.
        guard let stored = try businessJob(
            id: job.id, account: job.sourceAccount, book: job.bookID),
              stored.syncID == job.syncID,
              stored.payload == job.payload,
              stored.state == job.state,
              stored.stageInstanceID == job.stageInstanceID else {
            throw TestDraftStoreError.invalid
        }
    }

    func businessJob(id: String, account: String,
                     book: String) throws -> BusinessUploadJob? {
        let stmt = try prepare("""
            SELECT payload FROM isolated_business_jobs
            WHERE id=? AND source_account=? AND book_id=?
        """)
        defer { sqlite3_finalize(stmt) }
        bind(id, at: 1, to: stmt)
        bind(normalized(account), at: 2, to: stmt)
        bind(book, at: 3, to: stmt)
        guard sqlite3_step(stmt) == SQLITE_ROW,
              let ptr = sqlite3_column_blob(stmt, 0) else { return nil }
        let count = Int(sqlite3_column_bytes(stmt, 0))
        let job = try JSONDecoder().decode(BusinessUploadJob.self,
                                           from: Data(bytes: ptr, count: count))
        guard normalized(job.sourceAccount) == normalized(account),
              job.bookID == book, job.id == id else {
            throw TestDraftStoreError.invalid
        }
        return job
    }

    func businessJobs(account: String, book: String) throws -> [BusinessUploadJob] {
        let stmt = try prepare("""
            SELECT id FROM isolated_business_jobs
            WHERE source_account=? AND book_id=?
            ORDER BY created_at_ms DESC
        """)
        defer { sqlite3_finalize(stmt) }
        bind(normalized(account), at: 1, to: stmt)
        bind(book, at: 2, to: stmt)
        var ids: [String] = []
        var rc = sqlite3_step(stmt)
        while rc == SQLITE_ROW {
            if let ptr = sqlite3_column_text(stmt, 0) {
                ids.append(String(cString: ptr))
            }
            rc = sqlite3_step(stmt)
        }
        guard rc == SQLITE_DONE else {
            throw TestDraftStoreError.sql(String(cString: sqlite3_errmsg(database)))
        }
        return try ids.compactMap { try businessJob(id: $0, account: account,
                                                     book: book) }
    }

    func stagingJob(id: String, account: String,
                    book: String) throws -> StagingPurchaseJob? {
        let stmt = try prepare("""
            SELECT payload FROM isolated_staging_jobs
            WHERE id=? AND source_account=? AND book_id=?
        """)
        defer { sqlite3_finalize(stmt) }
        bind(id, at: 1, to: stmt)
        bind(normalized(account), at: 2, to: stmt)
        bind(book, at: 3, to: stmt)
        guard sqlite3_step(stmt) == SQLITE_ROW,
              let bytes = sqlite3_column_blob(stmt, 0) else { return nil }
        let count = Int(sqlite3_column_bytes(stmt, 0))
        let value = try JSONDecoder().decode(
            StagingPurchaseJob.self, from: Data(bytes: bytes, count: count))
        guard normalized(value.sourceAccount) == normalized(account),
              value.bookID == book, value.id == id else {
            throw TestDraftStoreError.invalid
        }
        return value
    }

    func stagingJobs(account: String, book: String) throws -> [StagingPurchaseJob] {
        let stmt = try prepare("""
            SELECT id FROM isolated_staging_jobs
            WHERE source_account=? AND book_id=? ORDER BY created_at_ms DESC
        """)
        defer { sqlite3_finalize(stmt) }
        bind(normalized(account), at: 1, to: stmt)
        bind(book, at: 2, to: stmt)
        var ids: [String] = []
        var status = sqlite3_step(stmt)
        while status == SQLITE_ROW {
            if let name = sqlite3_column_text(stmt, 0) {
                ids.append(String(cString: name))
            }
            status = sqlite3_step(stmt)
        }
        guard status == SQLITE_DONE else {
            throw TestDraftStoreError.sql(String(cString: sqlite3_errmsg(database)))
        }
        return try ids.compactMap { try stagingJob(id: $0, account: account, book: book) }
    }
}

enum TestDraftStoreError: LocalizedError {
    case invalid
    case lockedForUpload
    case sql(String)

    var errorDescription: String? {
        switch self {
        case .invalid: return "测试草稿数据无效，没有写入或读取其他账本的数据"
        case .lockedForUpload:
            return "该草稿已有关联的云端测试任务，禁止修改或删除；如需修改请新建草稿"
        case .sql(let value): return "测试草稿本地存储失败：" + value
        }
    }
}
