import XCTest
@testable import TianXianFruit

final class PurchaseRemarkEditTests: XCTestCase {
    private let book = CloudBook(
        id: "ios-test-remark-01", name: "iOS_TEST_采购备注",
        role: "OWNER", deleted: false, purged: false)

    private func session() -> StagingSession {
        StagingSession(token: "TEST", username: "only-test",
                       deviceID: "ios-remark-device", book: book,
                       scope: ScopedAccountEvidence(
                        instanceID: "production-scoped-testbook:" + book.id,
                        bookID: book.id),
                       host: StagingPurchasePolicy.productionURL)
    }

    private func event(_ table: String, id: String, _ payload: [String: JSONValue],
                       version: Int64 = 2, operation: String = "UPSERT") -> SyncEvent {
        SyncEvent(tableName: table, syncId: id,
                  rowVersion: version, operation: operation, payload: payload)
    }

    private func parent(version: Int64 = 3) -> SyncEvent {
        event("purchase_order", id: "order-test-1", [
            "id": .number(901), "date": .string("2026-11-20"),
            "buyer_id": .number(21), "buyer_name": .string("测试人"),
            "store_id": .number(0), "store_name": .string("共用货品"),
            "total_cost": .number(100),
            "remark": .string("旧备注"), "status": .number(1),
            "deleted": .number(0),
            "created_at": .number(90000),
            "updated_at": .number(99000),
            "row_version": .number(Double(version))
        ], version: version)
    }

    private func children() -> [SyncEvent] {
        [
            event("purchase_item", id: "i-1", [
                "id": .number(777),
                "order_id": .number(901),
                "fruit_name": .string("苹果"), "fruit_id": .number(30),
                "quantity": .number(10), "unit": .string("斤"),
                "total_cost": .number(30), "unit_price": .number(3),
                "deleted": .number(0)
            ]),
            event("purchase_item", id: "i-2", [
                "id": .number(778),
                "order_id": .number(901),
                "fruit_name": .string("橙子"), "fruit_id": .number(31),
                "quantity": .number(10), "unit": .string("斤"),
                "total_cost": .number(70), "unit_price": .number(7),
                "deleted": .number(0)
            ])
        ]
    }

    private func prepared() throws -> PurchaseRemarkEditJob {
        let first = parent()
        return try PurchaseRemarkEditPolicy.prepare(
            original: first, remark: "  已核对  ", events: [first] + children(),
            book: book, account: "local-account", session: session(),
            now: Date(timeIntervalSince1970: 1795161600),
            jobID: "remark-edit-1")
    }

    func testRemarkOnlyChangesVersionAndMetadataNotMoneyOrChildren() throws {
        let job = try prepared()
        XCTAssertEqual(job.proposed.syncId, job.original.syncId)
        XCTAssertEqual(job.proposed.rowVersion, job.original.rowVersion + 1)
        XCTAssertEqual(job.proposed.payload["remark"]?.text, "已核对")
        XCTAssertEqual(job.proposed.payload["total_cost"]?.decimal, 100)
        XCTAssertEqual(job.proposed.payload["buyer_name"]?.text, "测试人")
        XCTAssertEqual(job.children.count, 2)
        let otherFields = job.original.payload.filter {
            !["remark", "row_version", "updated_at", "modified_by",
              "sync_status"].contains($0.key)
        }
        for (key, value) in otherFields {
            XCTAssertEqual(job.proposed.payload[key], value)
        }
        XCTAssertTrue(PurchaseRemarkEditPolicy.unchanged(
            job, events: [job.original] + job.children))
        XCTAssertTrue(PurchaseRemarkEditPolicy.confirmed(
            job, events: [job.proposed] + job.children))
    }

    func testParentVersionChangeAndDeletionBlockStaleOverwrite() throws {
        let first = parent()
        let changed = parent(version: 4)
        XCTAssertThrowsError(try PurchaseRemarkEditPolicy.prepare(
            original: first, remark: "new", events: [changed] + children(),
            book: book, account: "local", session: session()))
        let deleted = event("purchase_order", id: first.syncId,
            first.payload, version: 4, operation: "DELETE")
        XCTAssertThrowsError(try PurchaseRemarkEditPolicy.prepare(
            original: first, remark: "new", events: [deleted] + children(),
            book: book, account: "local", session: session()))
        let job = try prepared()
        XCTAssertFalse(PurchaseRemarkEditPolicy.unchanged(
            job, events: [changed] + children()))
    }

    func testPartialItemsWrongParentAndDuplicateNumericIDAreBlocked() throws {
        let first = parent()
        XCTAssertThrowsError(try PurchaseRemarkEditPolicy.prepare(
            original: first, remark: "new",
            events: [first] + Array(children().prefix(1)),
            book: book, account: "local", session: session()))
        var changed = children()[0].payload
        changed["order_id"] = .number(902)
        let alien = event("purchase_item", id: "i-1", changed)
        XCTAssertThrowsError(try PurchaseRemarkEditPolicy.prepare(
            original: first, remark: "new",
            events: [first, alien, children()[1]],
            book: book, account: "local", session: session()))
        let duplicate = event("purchase_order", id: "different-sync", first.payload)
        XCTAssertThrowsError(try PurchaseRemarkEditPolicy.prepare(
            original: first, remark: "new",
            events: [first, duplicate] + children(),
            book: book, account: "local", session: session()))
    }

    func testMutatedChildOrOrderMoneyRejectsReadback() throws {
        let job = try prepared()
        var brokenChild = children()[1].payload
        brokenChild["total_cost"] = .number(75)
        let changed = event("purchase_item", id: "i-2", brokenChild,
                            version: 3)
        XCTAssertFalse(PurchaseRemarkEditPolicy.confirmed(
            job, events: [job.proposed, children()[0], changed]))
        var corrupt = job.proposed.payload
        corrupt["total_cost"] = .number(101)
        XCTAssertFalse(PurchaseRemarkEditPolicy.confirmed(
            job, events: [event("purchase_order", id: job.proposed.syncId,
                corrupt, version: job.proposed.rowVersion)] + job.children))
    }

    func testNoChangeAndFormalBookRejected() throws {
        let first = parent()
        XCTAssertThrowsError(try PurchaseRemarkEditPolicy.prepare(
            original: first, remark: "旧备注", events: [first] + children(),
            book: book, account: "local", session: session()))
        let formal = CloudBook(
            id: book.id, name: "正式账本", role: "OWNER",
            deleted: false, purged: false)
        XCTAssertThrowsError(try PurchaseRemarkEditPolicy.prepare(
            original: first, remark: "new", events: [first] + children(),
            book: formal, account: "local", session: session()))
    }

    func testDurableOutboxGuardsImmutablePayloadAndUnknownRetry() throws {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("remark-test-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: dir,
                                                 withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let path = dir.appendingPathComponent("test.sqlite")
        let store = try TestDraftStore(url: path)
        var job = try prepared()
        try store.savePurchaseRemarkJob(job)
        XCTAssertEqual(try store.purchaseRemarkJob(
            id: job.id, account: job.sourceAccount, book: job.bookID)?.state, .ready)
        job.state = .sending
        try store.savePurchaseRemarkJob(job)
        var retry = job
        retry.state = .ready
        XCTAssertThrowsError(try store.savePurchaseRemarkJob(retry))
        job.state = .needsReview
        try store.savePurchaseRemarkJob(job)
        XCTAssertThrowsError(try store.cancelUnsentRemark(
            id: job.id, account: job.sourceAccount, book: job.bookID))
        let reopened = try TestDraftStore(url: path)
        XCTAssertEqual(try reopened.purchaseRemarkJob(
            id: job.id, account: job.sourceAccount, book: job.bookID)?.state,
            .needsReview)
        job.state = .complete
        try store.savePurchaseRemarkJob(job)
        XCTAssertThrowsError(try store.savePurchaseRemarkJob(retry))
    }

    func testReadyJobCanCancelBeforeAnyNetworkSubmission() throws {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("remark-cancel-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: dir,
                                                 withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let store = try TestDraftStore(url: dir.appendingPathComponent("test.sqlite"))
        let job = try prepared()
        try store.savePurchaseRemarkJob(job)
        try store.cancelUnsentRemark(id: job.id, account: job.sourceAccount,
                                     book: job.bookID)
        XCTAssertNil(try store.purchaseRemarkJob(
            id: job.id, account: job.sourceAccount, book: job.bookID))
    }
}
