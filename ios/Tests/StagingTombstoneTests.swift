import XCTest
@testable import TianXianFruit

final class StagingTombstoneTests: XCTestCase {
    private func event(_ table: String, _ id: String, _ version: Int64,
                       _ op: String, _ fields: [String: JSONValue]) -> SyncEvent {
        SyncEvent(tableName: table, syncId: id, rowVersion: version,
                  operation: op, payload: fields)
    }

    func testNewestTombstoneWinsOverStaleUpsertAndIsRetained() {
        let changes = [
            event("fruit", "fruit-one", 1, "UPSERT",
                  ["id": .number(9), "name": .string("红提")]),
            event("fruit", "fruit-one", 3, "DELETE",
                  ["deleted": .bool(true)]),
            event("fruit", "fruit-one", 2, "UPSERT",
                  ["id": .number(9), "name": .string("过期提子")])
        ]
        let latest = StagingSyncMaterializer.latest(changes)
        XCTAssertEqual(latest.count, 1)
        XCTAssertTrue(latest[0].isDeletion)
        XCTAssertEqual(latest[0].rowVersion, 3)
        XCTAssertThrowsError(try StagingPayloadBuilder.reference(
            table: "fruit", syncID: "fruit-one", events: latest))
    }

    func testEqualRevisionLastEventWinsWithoutInventingActiveRecord() {
        let result = StagingSyncMaterializer.latest([
            event("partner", "buyer", 5, "UPSERT",
                  ["id": .number(13), "name": .string("采购人")]),
            event("partner", "buyer", 5, "DELETE",
                  ["deleted": .bool(true)])
        ])
        XCTAssertEqual(result.count, 1)
        XCTAssertTrue(result[0].isDeletion)
    }

    private func previewJob() throws -> StagingPurchaseJob {
        let book = CloudBook(id: "ios-test-tombstone-1", name: "iOS_TEST_墓碑",
                             role: "OWNER", deleted: false, purged: false)
        let draft = TestDraft(id: "test-tombstone-draft",
                              account: "test_account", bookID: book.id,
                              kind: .purchase, date: "2026-11-19",
                              fields: [
                                "buyer_sync_id": "buyer", "fruit_sync_id": "fruit",
                                "fruit_name": "提子", "quantity": "2",
                                "unit": "箱", "total_cost": "80",
                                "unit_weight_jin": "5",
                                "purchase_type": "PLANNED"
                              ], createdAt: Date())
        return try StagingPayloadBuilder.prepare(
            draft, sourceBook: book, stageBook: book,
            stageHost: StagingPurchasePolicy.productionURL,
            scope: ScopedAccountEvidence(
                instanceID: "production-scoped-testbook:" + book.id,
                bookID: book.id),
            username: "test_account", deviceID: "test_phone",
            available: [
                event("partner", "buyer", 1, "UPSERT",
                      ["id": .number(11), "name": .string("测试采购")]),
                event("fruit", "fruit", 1, "UPSERT",
                      ["id": .number(22), "name": .string("提子")])
            ])
    }

    func testCloudAuditRecognizesDeletedParentEvenIfPreviouslyPresent() throws {
        let job = try previewJob()
        let audit = PurchaseReadbackPolicy.audit(job, events: [
            event("purchase_order", job.orderSyncID, 1, "UPSERT",
                  job.orderPayload),
            event("purchase_item", job.itemSyncID, 1, "UPSERT",
                  job.itemPayload),
            event("purchase_order", job.orderSyncID, 2, "DELETE",
                  ["deleted": .bool(true)])
        ])
        XCTAssertFalse(audit.complete)
        XCTAssertFalse(audit.orderVerified)
        XCTAssertTrue(audit.issues.contains { $0.id == "order.deleted" })
        XCTAssertTrue(audit.headline.contains("已删除"))
    }

    func testCloudAuditRecognizesDeletedChildAndRejectsResume() throws {
        let job = try previewJob()
        let audit = PurchaseReadbackPolicy.audit(job, events: [
            event("purchase_order", job.orderSyncID, 1, "UPSERT",
                  job.orderPayload),
            event("purchase_item", job.itemSyncID, 1, "UPSERT",
                  job.itemPayload),
            event("purchase_item", job.itemSyncID, 2, "DELETE",
                  ["deleted": .bool(true)])
        ])
        XCTAssertTrue(audit.orderVerified)
        XCTAssertFalse(audit.itemVerified)
        XCTAssertTrue(audit.issues.contains { $0.id == "item.deleted" })
        XCTAssertFalse(audit.complete)
    }
}
