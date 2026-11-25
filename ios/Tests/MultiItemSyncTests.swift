import XCTest
@testable import TianXianFruit

final class MultiItemSyncTests: XCTestCase {
    private let book = CloudBook(
        id: "ios-test-multi-sync-001", name: "iOS_TEST_同步",
        role: "OWNER", deleted: false, purged: false)

    private func draft() throws -> TestDraft {
        let lines = [
            PurchaseDraftLine(id: "red", fruitSyncID: "red-sync",
                              fruitName: "无籽红提", quantity: "20", unit: "斤",
                              unitPrice: "3.50", unitWeightJin: "0"),
            PurchaseDraftLine(id: "apple", fruitSyncID: "apple-sync",
                              fruitName: "苹果", quantity: "30", unit: "斤",
                              unitPrice: "4.00", unitWeightJin: "0"),
            PurchaseDraftLine(id: "banana", fruitSyncID: "banana-sync",
                              fruitName: "香蕉", quantity: "25", unit: "斤",
                              unitPrice: "2.00", unitWeightJin: "0")
        ]
        return try PurchaseDraftPolicy.buildDraft(
            id: "multi-order-001", account: "local_test", bookID: book.id,
            date: "2026-11-19", buyerSyncID: "buyer-sync",
            purchaseType: "PLANNED", remark: "测试三商品",
            lines: lines, createdAt: Date(timeIntervalSince1970: 1795050000))
    }

    private func sourceEvents() -> [SyncEvent] {
        let pairs = [
            ("partner", "buyer-sync", 100.0, "采购人"),
            ("fruit", "red-sync", 101.0, "无籽红提"),
            ("fruit", "apple-sync", 102.0, "苹果"),
            ("fruit", "banana-sync", 103.0, "香蕉")
        ]
        return pairs.map { name, syncID, numeric, title in
            SyncEvent(tableName: name, syncId: syncID,
                      rowVersion: 1, operation: "UPSERT",
                      payload: ["id": .number(numeric),
                                "name": .string(title)])
        }
    }

    private func job() throws -> StagingPurchaseJob {
        try StagingPayloadBuilder.prepare(
            draft(), sourceBook: book, stageBook: book,
            stageHost: StagingPurchasePolicy.productionURL,
            scope: ScopedAccountEvidence(
                instanceID: "production-scoped-testbook:" + book.id,
                bookID: book.id),
            username: "dedicated_test", deviceID: "ios-phone",
            available: sourceEvents())
    }

    private func cloud(_ job: StagingPurchaseJob) -> [SyncEvent] {
        [StagingPayloadBuilder.event(job, item: false)] +
            StagingPayloadBuilder.itemEvents(job)
    }

    func testAllThreeItemsHaveIndependentStableSyncIDsAndExactCost() throws {
        let j = try job()
        let items = StagingPayloadBuilder.itemEvents(j)
        XCTAssertEqual(items.count, 3)
        XCTAssertEqual(Set(items.map(\.syncId)).count, 3)
        XCTAssertEqual(j.orderPayload["total_cost"]?.numberValue, 240)
        XCTAssertEqual(items.map { $0.payload["total_cost"]?.numberValue ?? -1 },
                       [70, 120, 50])
        XCTAssertEqual(items.map { $0.payload["unit_price"]?.numberValue ?? -1 },
                       [3.5, 4, 2])
        XCTAssertEqual(items.map { $0.payload["fruit_id"]?.numberValue ?? -1 },
                       [101, 102, 103])
        XCTAssertEqual(j.extraItems?.count, 2)
        XCTAssertEqual(j.startedItemIDs, [])
        XCTAssertEqual(StagingPayloadBuilder.itemEvents(try job()).map(\.syncId),
                       items.map(\.syncId))
    }

    func testCloudAuditRequiresAllItemsNotJustFirst() throws {
        let j = try job()
        let full = PurchaseReadbackPolicy.audit(j, events: cloud(j))
        XCTAssertTrue(full.complete)
        XCTAssertEqual(full.expectedItemCount, 3)
        XCTAssertEqual(full.verifiedItemSyncIDs.count, 3)

        let onlyFirstTwo = Array(cloud(j).prefix(3))
        let partial = PurchaseReadbackPolicy.audit(j, events: onlyFirstTwo)
        XCTAssertTrue(partial.orderVerified)
        XCTAssertFalse(partial.complete)
        XCTAssertEqual(partial.verifiedItemSyncIDs.count, 2)
        XCTAssertEqual(partial.pendingItemSyncIDs.count, 1)
        XCTAssertTrue(partial.issues.contains { $0.id == "item.3.missing" })
    }

    func testCanonicalParentRemapIsAppliedToEveryChild() throws {
        var j = try job()
        j.childParentID = 809022
        let items = StagingPayloadBuilder.itemEvents(j)
        XCTAssertTrue(items.allSatisfy {
            $0.payload["order_id"]?.numberValue == 809022
        })
        var order = j.orderPayload
        order["id"] = .number(809022)
        let events = [
            SyncEvent(tableName: "purchase_order",
                      syncId: j.orderSyncID, rowVersion: 1,
                      operation: "UPSERT", payload: order)
        ] + items
        let audit = PurchaseReadbackPolicy.audit(j, events: events)
        XCTAssertTrue(audit.complete)
        XCTAssertTrue(audit.remappedOrderID)
        XCTAssertEqual(audit.serverOrderID, 809022)
    }

    func testWrongParentOrExtraChildIsNotSilentlyAccepted() throws {
        let j = try job()
        var events = cloud(j)
        let second = StagingPayloadBuilder.itemEvents(j)[1]
        var misplaced = second.payload
        misplaced["order_id"] = .number(876)
        events[2] = SyncEvent(tableName: "purchase_item",
                              syncId: second.syncId,
                              rowVersion: 1, operation: "UPSERT",
                              payload: misplaced)
        let bad = PurchaseReadbackPolicy.audit(j, events: events)
        XCTAssertFalse(bad.complete)
        XCTAssertTrue(bad.issues.contains { $0.id == "item.2.order_id" })
        var duplicatePayload = StagingPayloadBuilder.itemEvents(j)[0].payload
        duplicatePayload["sync_id"] = .string("foreign-child-sync")
        duplicatePayload["id"] = .number(81238)
        let duplicate = SyncEvent(
            tableName: "purchase_item", syncId: "foreign-child-sync",
            rowVersion: 1, operation: "UPSERT",
            payload: duplicatePayload)
        let duplicateAudit = PurchaseReadbackPolicy.audit(
            j, events: cloud(j) + [duplicate])
        XCTAssertFalse(duplicateAudit.complete)
        XCTAssertTrue(duplicateAudit.issues.contains {
            $0.id == "item.link_count"
        })
    }

    func testMissingServerFruitRefBlocksWholeOrderBeforeAnyPush() throws {
        XCTAssertThrowsError(try StagingPayloadBuilder.prepare(
            draft(), sourceBook: book, stageBook: book,
            stageHost: StagingPurchasePolicy.productionURL,
            scope: ScopedAccountEvidence(
                instanceID: "production-scoped-testbook:" + book.id,
                bookID: book.id),
            username: "dedicated_test", deviceID: "ios-phone",
            available: Array(sourceEvents().prefix(3))))
    }

    func testStartedChildIdsPersistAndCannotBeRemoved() throws {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("multi-sync-" + UUID().uuidString)
        try FileManager.default.createDirectory(
            at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let url = dir.appendingPathComponent("outbox.sqlite")
        let allIDs = StagingPayloadBuilder.itemEvents(try job()).map(\.syncId)
        do {
            let store = try TestDraftStore(url: url)
            var j = try job()
            try store.saveStagingJob(j)
            j.state = .sendingOrder
            try store.saveStagingJob(j)
            j.childParentID = j.orderRemoteID
            j.state = .orderConfirmed
            try store.saveStagingJob(j)
            j.itemRequestStarted = true
            j.startedItemIDs = [allIDs[0]]
            j.state = .sendingItem
            try store.saveStagingJob(j)
            j.startedItemIDs = [allIDs[0], allIDs[1]]
            try store.saveStagingJob(j)
            var reversed = j
            reversed.startedItemIDs = [allIDs[1], allIDs[0]]
            XCTAssertThrowsError(try store.saveStagingJob(reversed))
            var erased = j
            erased.startedItemIDs = [allIDs[0]]
            XCTAssertThrowsError(try store.saveStagingJob(erased))
            var tampered = j
            tampered.extraItems = []
            XCTAssertThrowsError(try store.saveStagingJob(tampered))
        }
        let reopened = try TestDraftStore(url: url)
        let saved = try XCTUnwrap(reopened.stagingJob(
            id: "multi-order-001", account: "local_test", book: book.id))
        XCTAssertEqual(saved.startedItemIDs, Array(allIDs.prefix(2)))
        XCTAssertEqual(saved.extraItems?.count, 2)
    }

    func testOldSingleItemJobStillProducesOneItemEvent() throws {
        let draft = TestDraft(
            id: "legacy-order", account: "local_test", bookID: book.id,
            kind: .purchase, date: "2026-11-19",
            fields: ["buyer_sync_id": "buyer-sync",
                     "fruit_sync_id": "red-sync", "fruit_name": "无籽红提",
                     "quantity": "20", "unit": "斤",
                     "total_cost": "70", "unit_weight_jin": "0",
                     "purchase_type": "PLANNED"],
            createdAt: Date())
        let j = try StagingPayloadBuilder.prepare(
            draft, sourceBook: book, stageBook: book,
            stageHost: StagingPurchasePolicy.productionURL,
            scope: ScopedAccountEvidence(
                instanceID: "production-scoped-testbook:" + book.id,
                bookID: book.id),
            username: "dedicated_test", deviceID: "ios-phone",
            available: sourceEvents())
        XCTAssertNil(j.extraItems)
        XCTAssertNil(j.startedItemIDs)
        XCTAssertEqual(StagingPayloadBuilder.itemEvents(j).count, 1)
        XCTAssertTrue(PurchaseReadbackPolicy.audit(
            j, events: cloud(j)).complete)
    }

    func testKeychainTestSessionKeyIsScopedWithoutExposingRawBookID() {
        let a = TestSyncCredentialStore.key(
            account: "test-one", bookID: "book-a")
        let b = TestSyncCredentialStore.key(
            account: "test-one", bookID: "book-b")
        let c = TestSyncCredentialStore.key(
            account: "test-two", bookID: "book-a")
        XCTAssertNotEqual(a, b)
        XCTAssertNotEqual(a, c)
        XCTAssertFalse(a.contains("book-a"))
        XCTAssertEqual(a, TestSyncCredentialStore.key(
            account: "TEST-ONE", bookID: "book-a"))
    }
}
