import XCTest
@testable import TianXianFruit

final class BusinessUploadTests: XCTestCase {
    private let book = CloudBook(
        id: "ios-test-business-001", name: "iOS_TEST_营业",
        role: "OWNER", deleted: false, purged: false)

    private func remote(_ table: String, _ syncID: String,
                        _ payload: [String: JSONValue],
                        operation: String = "UPSERT") -> SyncEvent {
        SyncEvent(tableName: table, syncId: syncID, rowVersion: 1,
                  operation: operation, payload: payload)
    }

    private func sources() -> [SyncEvent] {
        [
            remote("store", "store-sync-1",
                   ["id": .number(100), "name": .string("测试位置")]),
            remote("partner", "partner-sync-1",
                   ["id": .number(201), "name": .string("甲")]),
            remote("partner", "partner-sync-2",
                   ["id": .number(202), "name": .string("乙")])
        ]
    }

    private func session() -> StagingSession {
        StagingSession(token: "TEST", username: "test-user",
                       deviceID: "ios-business-test",
                       book: book, scope: ScopedAccountEvidence(
                        instanceID: "production-scoped-testbook:" + book.id,
                        bookID: book.id),
                       host: StagingPurchasePolicy.productionURL)
    }

    private func draft() -> TestDraft {
        TestDraft(id: "ios-business-001", account: "local-test",
                  bookID: book.id, kind: .business,
                  date: "2026-11-20",
                  fields: [
                    "store_sync_id": "store-sync-1", "store_name": "测试位置",
                    "wechat": "100.00", "alipay": "60.00", "cash": "40.00",
                    "wechat_collector_sync_id": "partner-sync-1",
                    "alipay_collector_sync_id": "partner-sync-2",
                    "cash_collector_sync_id": "partner-sync-1",
                    "expense": "20", "expense_payer_sync_id": "partner-sync-2",
                    "opening_stock_value": "80", "stock_left_value": "30",
                    "purchase_cost": "400"
                  ], createdAt: Date(timeIntervalSince1970: 1795161600))
    }

    private func prepared() throws -> BusinessUploadJob {
        try BusinessUploadPolicy.prepare(draft(), sourceBook: book,
                                         session: session(), events: sources(),
                                         now: Date(timeIntervalSince1970: 1795161600))
    }

    func testUsesAndroidDeterministicDayStoreIdentity() {
        XCTAssertEqual(BusinessUploadPolicy.androidSyncID(
            date: "2026-11-20", storeSyncID: "store-sync-1"),
                       "4d8eaa46-d0d1-3cb4-aa1b-85638993211d")
    }

    func testMultiCollectorSplitRevenueAndProfitAreExact() throws {
        let job = try prepared()
        XCTAssertEqual(job.payload["revenue"]?.decimal, 200)
        XCTAssertEqual(job.payload["profit"]?.decimal, 130
        // Android business source saves purchase_cost=0; purchase cost
        // belongs to the separate purchase table and must not be deducted.
        XCTAssertEqual(job.payload["purchase_cost"]?.decimal, 0)
        XCTAssertEqual(job.payload["store_sync_id"]?.text, "store-sync-1")
        let raw = try XCTUnwrap(job.payload["receipt_splits_json"]?.text)
        let json = try XCTUnwrap(raw.data(using: .utf8))
        let splits = try XCTUnwrap(JSONSerialization.jsonObject(with: json) as? [[String: Any]])
        XCTAssertEqual(splits.count, 2)
        XCTAssertEqual(splits.reduce(0.0) {
            $0 + ($1["amount"] as? Double ?? 0)
        }, 200)
        XCTAssertTrue(BusinessUploadPolicy.verified(
            job, events: sources() + [job.event]))
    }

    func testExistingSameDayStoreAndDeletedHistoryBlockNewBusiness() throws {
        let job = try prepared()
        XCTAssertTrue(BusinessUploadPolicy.conflict(
            date: "2026-11-20", storeSyncID: "store-sync-1",
            storeName: "测试位置", events: sources() + [job.event]))
        let legacy = remote("store_daily_record", "old-id", [
            "date": .string("2026-11-20"),
            "store_name": .string("测试位置"),
            "store_sync_id": .string(""),
            "deleted": .number(1)
        ], operation: "DELETE")
        XCTAssertThrowsError(try BusinessUploadPolicy.prepare(
            draft(), sourceBook: book, session: session(),
            events: sources() + [legacy]))
    }

    func testReadbackMismatchAndOtherPositionCannotConfirm() throws {
        let job = try prepared()
        var changed = job.payload
        changed["cash_income"] = .number(999)
        let wrong = remote("store_daily_record", job.syncID, changed)
        XCTAssertFalse(BusinessUploadPolicy.verified(job, events: [wrong]))
        let foreign = remote("store_daily_record", "foreign", [
            "date": .string("2026-11-20"),
            "store_sync_id": .string("store-sync-1"),
            "store_name": .string("测试位置")
        ])
        XCTAssertFalse(BusinessUploadPolicy.verified(
            job, events: [job.event, foreign]))
        XCTAssertFalse(BusinessUploadPolicy.verified(
            job, events: [remote("store_daily_record", job.syncID, job.payload,
                                 operation: "DELETE")]))
    }

    func testDurableIntentBlocksEditingDeletingAndBlindRetry() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("business-test-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: root,
                                                 withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let path = root.appendingPathComponent("drafts.sqlite")
        let store = try TestDraftStore(url: path)
        let original = draft()
        try store.save(original)
        var job = try prepared()
        try store.saveBusinessJob(job)
        XCTAssertThrowsError(try store.save(original))
        XCTAssertThrowsError(try store.delete(id: original.id,
            account: original.account, book: original.bookID))
        job.state = .sending
        try store.saveBusinessJob(job)
        var retry = job
        retry.state = .ready
        XCTAssertThrowsError(try store.saveBusinessJob(retry))
        job.state = .needsReview
        try store.saveBusinessJob(job)
        retry = job
        retry.payload["cash_income"] = .number(1234)
        XCTAssertThrowsError(try store.saveBusinessJob(retry))
        let restored = try TestDraftStore(url: path).businessJob(
            id: job.id, account: job.sourceAccount, book: job.bookID)
        XCTAssertEqual(restored?.state, .needsReview)
    }

    func testRejectsNonTestBookAndStaleCollector() throws {
        let live = CloudBook(id: book.id, name: "正式账本",
                             role: "OWNER", deleted: false, purged: false)
        XCTAssertThrowsError(try BusinessUploadPolicy.prepare(
            draft(), sourceBook: live, session: session(), events: sources()))
        XCTAssertThrowsError(try BusinessUploadPolicy.prepare(
            draft(), sourceBook: book, session: session(),
            events: Array(sources().prefix(2))))
    }
}
