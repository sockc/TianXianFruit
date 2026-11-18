import XCTest
@testable import TianXianFruit

final class PurchasePilotExpansionTests: XCTestCase {
    private let testBook = CloudBook(
        id: "ios-test-pilot-001", name: "iOS_TEST_批量验证",
        role: "OWNER", deleted: false, purged: false)

    private func job(_ id: String) throws -> StagingPurchaseJob {
        let draft = TestDraft(
            id: id, account: "local_account", bookID: testBook.id,
            kind: .purchase, date: "2026-11-19",
            fields: [
                "buyer_sync_id": "buyer", "fruit_sync_id": "fruit",
                "fruit_name": "红提", "quantity": "2.5", "unit": "箱",
                "total_cost": "80", "unit_weight_jin": "10",
                "purchase_type": "PLANNED", "remark": "测试"
            ], createdAt: Date(timeIntervalSince1970: 1795050000))
        let refs: [SyncEvent] = [
            SyncEvent(tableName: "partner", syncId: "buyer",
                      rowVersion: 1, operation: "UPSERT",
                      payload: ["id": .number(23), "name": .string("测试采购人")]),
            SyncEvent(tableName: "fruit", syncId: "fruit",
                      rowVersion: 1, operation: "UPSERT",
                      payload: ["id": .number(17), "name": .string("红提")])
        ]
        return try StagingPayloadBuilder.prepare(
            draft, sourceBook: testBook, stageBook: testBook,
            stageHost: StagingPurchasePolicy.productionURL,
            scope: ScopedAccountEvidence(
                instanceID: "production-scoped-testbook:" + testBook.id,
                bookID: testBook.id),
            username: "one_book_user", deviceID: "iPhone",
            available: refs)
    }

    private func completed(_ job: StagingPurchaseJob) throws -> StagingPurchaseJob {
        var copy = job
        copy.state = .complete
        copy.childParentID = job.orderRemoteID
        copy.itemRequestStarted = true
        let audit = matchingAudit(copy)
        let observed = AndroidPurchaseObservation(
            date: "2026-11-19", buyerName: "测试采购人",
            fruitName: "红提", unit: "箱", quantity: "2.5", totalCost: "80")
        let evidence = try AndroidPurchaseReviewPolicy.confirm(
            job: copy, audit: audit, observation: observed,
            now: audit.checkedAt.addingTimeInterval(3))
        copy.androidReview = evidence
        copy.androidCheckedAt = evidence.checkedAt
        return copy
    }

    private func matchingAudit(_ job: StagingPurchaseJob) -> PurchaseReadbackAudit {
        PurchaseReadbackPolicy.audit(job, events: [
            StagingPayloadBuilder.event(job, item: false),
            StagingPayloadBuilder.event(job, item: true)
        ])
    }

    func testParentIDRemapIsSafelyReboundOnChildPayload() throws {
        let original = try job("pilot-remap-1")
        var prepared = original
        prepared.childParentID = 67890
        let event = StagingPayloadBuilder.event(prepared, item: true)
        XCTAssertEqual(event.syncId, prepared.itemSyncID)
        XCTAssertEqual(event.rowVersion, 1)
        XCTAssertEqual(event.payload["order_id"]?.numberValue, 67890)
        XCTAssertEqual(original.itemPayload["order_id"]?.numberValue,
                       Double(original.orderRemoteID))
        var serverOrder = original.orderPayload
        serverOrder["id"] = .number(67890)
        let cloud: [SyncEvent] = [
            SyncEvent(tableName: "purchase_order", syncId: original.orderSyncID,
                      rowVersion: 1, operation: "UPSERT", payload: serverOrder),
            event
        ]
        let audit = PurchaseReadbackPolicy.audit(original, events: cloud)
        XCTAssertTrue(audit.complete)
        XCTAssertTrue(audit.remappedOrderID)
        XCTAssertEqual(audit.serverOrderID, 67890)
    }

    func testParentLinkCanOnlyBeFrozenOnce() throws {
        let folder = FileManager.default.temporaryDirectory
            .appendingPathComponent("pilot-link-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let db = try TestDraftStore(url: folder.appendingPathComponent("drafts.sqlite"))
        var current = try job("freeze-one")
        try db.saveStagingJob(current)
        current.state = .sendingOrder
        try db.saveStagingJob(current)
        current.childParentID = 8991
        current.state = .orderConfirmed
        try db.saveStagingJob(current)
        var mutated = current
        mutated.childParentID = 8992
        XCTAssertThrowsError(try db.saveStagingJob(mutated))
        current.itemRequestStarted = true
        current.state = .sendingItem
        try db.saveStagingJob(current)
        current.state = .needsReview
        try db.saveStagingJob(current)
        var forgotRequest = current
        forgotRequest.itemRequestStarted = false
        XCTAssertThrowsError(try db.saveStagingJob(forgotRequest))
        var erasedParent = current
        erasedParent.childParentID = nil
        XCTAssertThrowsError(try db.saveStagingJob(erasedParent))
    }

    func testChildCannotBeginWithoutFrozenParentAndIrreversibleAttemptMarker() throws {
        let folder = FileManager.default.temporaryDirectory
            .appendingPathComponent("pilot-unsafe-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let db = try TestDraftStore(url: folder.appendingPathComponent("drafts.sqlite"))
        var current = try job("no-parent")
        try db.saveStagingJob(current)
        current.state = .sendingOrder
        try db.saveStagingJob(current)
        current.state = .orderConfirmed
        try db.saveStagingJob(current)
        current.state = .sendingItem
        XCTAssertThrowsError(try db.saveStagingJob(current))
        current.childParentID = current.orderRemoteID
        XCTAssertThrowsError(try db.saveStagingJob(current))
        current.itemRequestStarted = true
        XCTAssertNoThrow(try db.saveStagingJob(current))
    }

    func testUnlimitedCompletedTestOrdersNeedFreshCloudChecksOnly() throws {
        let first = try completed(try job("pilot-first"))
        let second = try completed(try job("pilot-second"))
        let third = try completed(try job("pilot-third"))
        let one = [first.id: matchingAudit(first)]
        XCTAssertNoThrow(try StagingPurchasePolicy.authorizeNextOrder(
            existing: [first], audits: one, bookID: testBook.id,
            username: "one_book_user"))
        let two = [
            first.id: matchingAudit(first),
            second.id: matchingAudit(second)
        ]
        XCTAssertNoThrow(try StagingPurchasePolicy.authorizeNextOrder(
            existing: [first, second], audits: two, bookID: testBook.id,
            username: "one_book_user"))
        let three = [
            first.id: matchingAudit(first),
            second.id: matchingAudit(second),
            third.id: matchingAudit(third)
        ]
        XCTAssertNoThrow(try StagingPurchasePolicy.authorizeNextOrder(
            existing: [first, second, third], audits: three,
            bookID: testBook.id, username: "one_book_user"))
    }

    func testAndroidManualChecklistIsNoLongerMandatory() throws {
        var first = try completed(try job("not-confirmed"))
        first.androidCheckedAt = nil
        first.androidReview = nil
        XCTAssertNoThrow(try StagingPurchasePolicy.authorizeNextOrder(
            existing: [first], audits: [first.id: matchingAudit(first)],
            bookID: testBook.id, username: "one_book_user"))
    }

    func testCannotOpenNextIfRemoteRecordHasChangedOrMissing() throws {
        let first = try completed(try job("wrong-total"))
        var order = first.orderPayload
        order["total_cost"] = .number(999)
        let badAudit = PurchaseReadbackPolicy.audit(first, events: [
            SyncEvent(tableName: "purchase_order", syncId: first.orderSyncID,
                      rowVersion: 1, operation: "UPSERT", payload: order),
            StagingPayloadBuilder.event(first, item: true)
        ])
        XCTAssertFalse(badAudit.complete)
        XCTAssertThrowsError(try StagingPurchasePolicy.authorizeNextOrder(
            existing: [first], audits: [first.id: badAudit],
            bookID: testBook.id, username: "one_book_user"))
        XCTAssertThrowsError(try StagingPurchasePolicy.authorizeNextOrder(
            existing: [first], audits: [:],
            bookID: testBook.id, username: "one_book_user"))
    }

    func testCannotMixAccountsOrAnotherBookForNextOrder() throws {
        let first = try completed(try job("wrong-account"))
        let audits = [first.id: matchingAudit(first)]
        XCTAssertThrowsError(try StagingPurchasePolicy.authorizeNextOrder(
            existing: [first], audits: audits, bookID: testBook.id,
            username: "different_user"))
        XCTAssertThrowsError(try StagingPurchasePolicy.authorizeNextOrder(
            existing: [first], audits: audits, bookID: "another-book",
            username: "one_book_user"))
    }

    func testOldV023JobsWithoutNewFieldsRemainReadable() throws {
        let old = try job("old-version")
        let data = try JSONEncoder().encode(old)
        var dictionary = try XCTUnwrap(
            JSONSerialization.jsonObject(with: data) as? [String: Any])
        dictionary.removeValue(forKey: "childParentID")
        dictionary.removeValue(forKey: "itemRequestStarted")
        dictionary.removeValue(forKey: "androidCheckedAt")
        dictionary.removeValue(forKey: "androidReview")
        let decoded = try JSONDecoder().decode(StagingPurchaseJob.self,
            from: JSONSerialization.data(withJSONObject: dictionary))
        XCTAssertNil(decoded.childParentID)
        XCTAssertNil(decoded.itemRequestStarted)
        XCTAssertNil(decoded.androidCheckedAt)
        XCTAssertNil(decoded.androidReview)
        XCTAssertEqual(decoded.orderSyncID, old.orderSyncID)
    }
}
