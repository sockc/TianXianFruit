import XCTest
@testable import TianXianFruit

final class PurchaseReadbackAuditTests: XCTestCase {
    private let book = CloudBook(id: "ios-test-book-copy-1",
                                 name: "iOS_TEST_采购核对",
                                 role: "OWNER", deleted: false, purged: false)

    private func job() throws -> StagingPurchaseJob {
        let draft = TestDraft(id: "02B4229E-67CC-4213-8071-E7C749D8B012",
                              account: "test_user", bookID: book.id,
                              kind: .purchase, date: "2026-11-18",
                              fields: [
                                "buyer_sync_id": "test-buyer",
                                "fruit_sync_id": "test-fruit",
                                "fruit_name": "红提", "quantity": "2.5",
                                "unit": "箱", "total_cost": "87.5",
                                "unit_weight_jin": "5",
                                "purchase_type": "PLANNED",
                                "remark": "首单核对"
                              ], createdAt: Date(timeIntervalSince1970: 1794950000))
        let scope = ScopedAccountEvidence(
            instanceID: "production-scoped-testbook:" + book.id,
            bookID: book.id)
        return try StagingPayloadBuilder.prepare(
            draft, sourceBook: book, stageBook: book,
            stageHost: StagingPurchasePolicy.productionURL,
            scope: scope, username: "test_user", deviceID: "test-ios",
            available: [
                SyncEvent(tableName: "partner", syncId: "test-buyer",
                          rowVersion: 1, operation: "UPSERT", payload: [
                            "id": .number(19), "name": .string("测试采购")
                          ]),
                SyncEvent(tableName: "fruit", syncId: "test-fruit",
                          rowVersion: 1, operation: "UPSERT", payload: [
                            "id": .number(33), "name": .string("红提")
                          ])
            ])
    }

    private func event(_ table: String, _ id: String,
                       payload: [String: JSONValue],
                       operation: String = "UPSERT",
                       version: Int64 = 1) -> SyncEvent {
        SyncEvent(tableName: table, syncId: id, rowVersion: version,
                  operation: operation, payload: payload)
    }

    private func pair(_ job: StagingPurchaseJob,
                      order: [String: JSONValue]? = nil,
                      item: [String: JSONValue]? = nil) -> [SyncEvent] {
        [
            event("purchase_order", job.orderSyncID,
                  payload: order ?? job.orderPayload),
            event("purchase_item", job.itemSyncID,
                  payload: item ?? job.itemPayload)
        ]
    }

    func testFullCloudReadbackPassesButDoesNotClaimAndroidHasReadIt() throws {
        let job = try job()
        let result = PurchaseReadbackPolicy.audit(job, events: pair(job))
        XCTAssertTrue(result.complete)
        XCTAssertTrue(result.orderVerified)
        XCTAssertTrue(result.itemVerified)
        XCTAssertFalse(result.remappedOrderID)
        XCTAssertTrue(result.headline.contains("待 Android"))
        XCTAssertTrue(result.issues.isEmpty)
    }

    func testServerRemappingBothParentAndChildIDsMaintainsValidAssociation() throws {
        let job = try job()
        var order = job.orderPayload
        var item = job.itemPayload
        order["id"] = .number(8080)
        item["id"] = .number(8081)
        item["order_id"] = .number(8080)
        let result = PurchaseReadbackPolicy.audit(
            job, events: pair(job, order: order, item: item))
        XCTAssertTrue(result.complete)
        XCTAssertTrue(result.remappedOrderID)
        XCTAssertEqual(result.serverOrderID, 8080)
        XCTAssertEqual(result.serverItemID, 8081)
    }

    func testCrossBookParentIDMismatchIsRejected() throws {
        let job = try job()
        var item = job.itemPayload
        item["order_id"] = .number(9000)
        let result = PurchaseReadbackPolicy.audit(job,
                                                   events: pair(job, item: item))
        XCTAssertTrue(result.orderVerified)
        XCTAssertFalse(result.itemVerified)
        XCTAssertFalse(result.complete)
        XCTAssertTrue(result.issues.contains { $0.id == "item.order_id" })
    }

    func testASecondChildWithSameParentIsNotSilentlyCountedAsOneOrder() throws {
        let job = try job()
        var sibling = job.itemPayload
        sibling["id"] = .number(999999)
        sibling["sync_id"] = .string("other-purchase-item")
        let rows = pair(job) + [
            event("purchase_item", "other-purchase-item", payload: sibling)
        ]
        let result = PurchaseReadbackPolicy.audit(job, events: rows)
        XCTAssertFalse(result.complete)
        XCTAssertTrue(result.issues.contains { $0.id == "item.link_count" })
    }

    func testChangedAmountQuantityUnitAndBuyerAreReported() throws {
        let job = try job()
        var order = job.orderPayload
        var item = job.itemPayload
        order["total_cost"] = .number(999.00)
        order["buyer_id"] = .number(777)
        item["quantity"] = .number(4)
        item["unit"] = .string("袋")
        let result = PurchaseReadbackPolicy.audit(
            job, events: pair(job, order: order, item: item))
        let keys = Set(result.issues.map(\.id))
        XCTAssertFalse(result.complete)
        XCTAssertTrue(keys.contains("order.total_cost"))
        XCTAssertTrue(keys.contains("order.buyer_id"))
        XCTAssertTrue(keys.contains("item.quantity"))
        XCTAssertTrue(keys.contains("item.unit"))
    }

    func testOrderOnlyMeansNotCompleteAndCanBeReviewedWithoutRetry() throws {
        let job = try job()
        let result = PurchaseReadbackPolicy.audit(job, events: [
            event("purchase_order", job.orderSyncID, payload: job.orderPayload)
        ])
        XCTAssertTrue(result.orderPresent)
        XCTAssertFalse(result.itemPresent)
        XCTAssertTrue(result.orderVerified)
        XCTAssertFalse(result.complete)
        XCTAssertTrue(result.issues.contains { $0.id == "item.missing" })
    }

    func testMissingAndDeletedRowsAreNotMisrepresentedAsSuccess() throws {
        let job = try job()
        let empty = PurchaseReadbackPolicy.audit(job, events: [])
        XCTAssertFalse(empty.complete)
        XCTAssertTrue(empty.headline.contains("不代表上传失败"))

        let deleted = PurchaseReadbackPolicy.audit(job, events: [
            event("purchase_order", job.orderSyncID,
                  payload: ["deleted": .bool(true)], operation: "DELETE",
                  version: 2),
            event("purchase_item", job.itemSyncID,
                  payload: job.itemPayload)
        ])
        XCTAssertFalse(deleted.orderVerified)
        XCTAssertFalse(deleted.complete)
        XCTAssertTrue(deleted.issues.contains { $0.id == "order.deleted" })
    }

    func testManualAndroidConfirmationIsLocalOnlyAndCannotPrecedeServerSuccess() throws {
        let job = try job()
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(
            "purchase-audit-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let file = root.appendingPathComponent("cache.sqlite")
        do {
            let storage = try TestDraftStore(url: file)
            try storage.saveStagingJob(job)
            var invalid = job
            invalid.androidCheckedAt = Date()
            XCTAssertThrowsError(try storage.saveStagingJob(invalid))
            var pending = job
            pending.state = .sendingOrder
            try storage.saveStagingJob(pending)
            pending.state = .orderConfirmed
            try storage.saveStagingJob(pending)
            pending.state = .sendingItem
            try storage.saveStagingJob(pending)
            pending.state = .complete
            try storage.saveStagingJob(pending)
            pending.androidCheckedAt = Date()
            try storage.saveStagingJob(pending)
        }
        let reopened = try TestDraftStore(url: file)
        let restored = try XCTUnwrap(reopened.stagingJob(
            id: job.id, account: job.sourceAccount, book: job.bookID))
        XCTAssertEqual(restored.state, .complete)
        XCTAssertNotNil(restored.androidCheckedAt)
        var tampered = restored
        tampered.androidCheckedAt = nil
        XCTAssertThrowsError(try reopened.saveStagingJob(tampered))
    }

    func testOlderSavedJobWithoutOptionalAndroidFieldStillDecodes() throws {
        let job = try job()
        let data = try JSONEncoder().encode(job)
        var dictionary = try XCTUnwrap(
            JSONSerialization.jsonObject(with: data) as? [String: Any])
        dictionary.removeValue(forKey: "androidCheckedAt")
        let oldJSON = try JSONSerialization.data(withJSONObject: dictionary)
        let restored = try JSONDecoder().decode(StagingPurchaseJob.self,
                                                from: oldJSON)
        XCTAssertNil(restored.androidCheckedAt)
        XCTAssertEqual(restored.orderSyncID, job.orderSyncID)
    }
}
