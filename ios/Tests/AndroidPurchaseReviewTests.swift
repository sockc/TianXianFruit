import XCTest
@testable import TianXianFruit

final class AndroidPurchaseReviewTests: XCTestCase {
    private let book = CloudBook(id: "ios-review-book-01",
                                 name: "iOS_TEST_验收数据",
                                 role: "OWNER", deleted: false, purged: false)

    private func job(_ key: String = "review-draft-01") throws -> StagingPurchaseJob {
        let draft = TestDraft(
            id: key, account: "test_reader", bookID: book.id,
            kind: .purchase, date: "2026-11-19",
            fields: ["buyer_sync_id": "buyer-01", "fruit_sync_id": "fruit-01",
                     "fruit_name": "无籽红提", "quantity": "2.5", "unit": "箱",
                     "total_cost": "80.5", "unit_weight_jin": "10",
                     "purchase_type": "PLANNED", "remark": "测试"],
            createdAt: Date(timeIntervalSince1970: 1795050000))
        let scope = ScopedAccountEvidence(
            instanceID: "production-scoped-testbook:" + book.id,
            bookID: book.id)
        var prepared = try StagingPayloadBuilder.prepare(
            draft, sourceBook: book, stageBook: book,
            stageHost: StagingPurchasePolicy.productionURL,
            scope: scope, username: "test_editor", deviceID: "device-01",
            available: [
                SyncEvent(tableName: "partner", syncId: "buyer-01",
                          rowVersion: 1, operation: "UPSERT",
                          payload: ["id": .number(11), "name": .string("测试采购员")]),
                SyncEvent(tableName: "fruit", syncId: "fruit-01",
                          rowVersion: 1, operation: "UPSERT",
                          payload: ["id": .number(22), "name": .string("无籽红提")])
            ])
        prepared.state = .complete
        prepared.childParentID = prepared.orderRemoteID
        prepared.itemRequestStarted = true
        return prepared
    }

    private func audit(_ job: StagingPurchaseJob, orderVersion: Int64 = 1,
                       itemVersion: Int64 = 1,
                       orderID: Double? = nil) -> PurchaseReadbackAudit {
        var order = job.orderPayload
        var item = job.itemPayload
        if let orderID {
            order["id"] = .number(orderID)
            item["order_id"] = .number(orderID)
        }
        return PurchaseReadbackPolicy.audit(job, events: [
            SyncEvent(tableName: "purchase_order", syncId: job.orderSyncID,
                      rowVersion: orderVersion, operation: "UPSERT", payload: order),
            SyncEvent(tableName: "purchase_item", syncId: job.itemSyncID,
                      rowVersion: itemVersion, operation: "UPSERT", payload: item)
        ])
    }

    private func observed() -> AndroidPurchaseObservation {
        AndroidPurchaseObservation(date: "2026-11-19", buyerName: "测试采购员",
                                   fruitName: "无籽红提", unit: "箱",
                                   quantity: "2.5", totalCost: "80.50")
    }

    func testSixIndependentAndroidFieldsCanBeConfirmed() throws {
        let job = try job()
        let audit = audit(job)
        let evidence = try AndroidPurchaseReviewPolicy.confirm(
            job: job, audit: audit, observation: observed(),
            now: audit.checkedAt.addingTimeInterval(6))
        XCTAssertEqual(evidence.observation.fruitName, "无籽红提")
        XCTAssertEqual(evidence.cloudFingerprint.count, 64)
        XCTAssertTrue(AndroidPurchaseReviewPolicy.isCurrent(
            evidence, job: job, audit: audit))
    }

    func testDifferentAmountAndQuantityRejectConfirmation() throws {
        let job = try job()
        let result = audit(job)
        var wrong = observed()
        wrong.totalCost = "99"
        XCTAssertThrowsError(try AndroidPurchaseReviewPolicy.confirm(
            job: job, audit: result, observation: wrong,
            now: result.checkedAt.addingTimeInterval(3)))
        wrong = observed()
        wrong.quantity = "3"
        XCTAssertThrowsError(try AndroidPurchaseReviewPolicy.confirm(
            job: job, audit: result, observation: wrong,
            now: result.checkedAt.addingTimeInterval(3)))
    }

    func testBuyerFruitDateAndUnitMustMatchExactly() throws {
        let job = try job()
        let result = audit(job)
        let invalidValues: [(String, String)] = [
            ("date", "2026-11-18"), ("buyer", "其他采购员"),
            ("fruit", "青提"), ("unit", "斤")
        ]
        for (field, value) in invalidValues {
            var input = observed()
            switch field {
            case "date": input.date = value
            case "buyer": input.buyerName = value
            case "fruit": input.fruitName = value
            default: input.unit = value
            }
            XCTAssertThrowsError(try AndroidPurchaseReviewPolicy.confirm(
                job: job, audit: result, observation: input,
                now: result.checkedAt.addingTimeInterval(2)), field)
        }
    }

    func testMissingFieldsAndInvalidDecimalsAreRejected() throws {
        let job = try job()
        let result = audit(job)
        var noBuyer = observed()
        noBuyer.buyerName = " "
        XCTAssertThrowsError(try AndroidPurchaseReviewPolicy.confirm(
            job: job, audit: result, observation: noBuyer,
            now: result.checkedAt.addingTimeInterval(2)))
        var invalidCost = observed()
        invalidCost.totalCost = "80.501"
        XCTAssertThrowsError(try AndroidPurchaseReviewPolicy.confirm(
            job: job, audit: result, observation: invalidCost,
            now: result.checkedAt.addingTimeInterval(2)))
    }

    func testStaleOrFutureCloudAuditRequiresFreshReadback() throws {
        let job = try job()
        let result = audit(job)
        XCTAssertThrowsError(try AndroidPurchaseReviewPolicy.confirm(
            job: job, audit: result, observation: observed(),
            now: result.checkedAt.addingTimeInterval(2701)))
        XCTAssertThrowsError(try AndroidPurchaseReviewPolicy.confirm(
            job: job, audit: result, observation: observed(),
            now: result.checkedAt.addingTimeInterval(-6)))
    }

    func testCloudRevisionOrCanonicalIDChangeInvalidatesStoredReview() throws {
        let job = try job()
        let original = audit(job)
        let evidence = try AndroidPurchaseReviewPolicy.confirm(
            job: job, audit: original, observation: observed(),
            now: original.checkedAt.addingTimeInterval(2))
        XCTAssertTrue(AndroidPurchaseReviewPolicy.isCurrent(
            evidence, job: job, audit: original))
        XCTAssertFalse(AndroidPurchaseReviewPolicy.isCurrent(
            evidence, job: job, audit: audit(job, orderVersion: 2)))
        XCTAssertFalse(AndroidPurchaseReviewPolicy.isCurrent(
            evidence, job: job, audit: audit(job, orderID: 99123)))
    }

    func testLegacySingleTapConfirmationCannotUnlockNextOrder() throws {
        var job = try job()
        job.androidCheckedAt = Date()
        let current = audit(job)
        XCTAssertThrowsError(try StagingPurchasePolicy.authorizeNextOrder(
            existing: [job], audits: [job.id: current],
            bookID: book.id, username: job.stageUsername))
        let evidence = try AndroidPurchaseReviewPolicy.confirm(
            job: job, audit: current, observation: observed(),
            now: current.checkedAt.addingTimeInterval(2))
        job.androidReview = evidence
        XCTAssertNoThrow(try StagingPurchasePolicy.authorizeNextOrder(
            existing: [job], audits: [job.id: current],
            bookID: book.id, username: job.stageUsername))
    }

    func testReviewIsImmutableInSQLiteAndSurvivesReopen() throws {
        let folder = FileManager.default.temporaryDirectory
            .appendingPathComponent("android-review-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: folder,
                                                withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let url = folder.appendingPathComponent("drafts.sqlite")
        let original = try job()
        let server = audit(original)
        let evidence = try AndroidPurchaseReviewPolicy.confirm(
            job: original, audit: server, observation: observed(),
            now: server.checkedAt.addingTimeInterval(2))
        do {
            let store = try TestDraftStore(url: url)
            try store.saveStagingJob(original)
            var saved = original
            saved.androidCheckedAt = evidence.checkedAt
            saved.androidReview = evidence
            try store.saveStagingJob(saved)
            var mutation = saved
            mutation.androidReview = nil
            XCTAssertThrowsError(try store.saveStagingJob(mutation))
            mutation = saved
            mutation.androidReview = AndroidPurchaseReviewEvidence(
                checkedAt: evidence.checkedAt, observation: observed(),
                cloudFingerprint: "forged")
            XCTAssertThrowsError(try store.saveStagingJob(mutation))
        }
        let reopened = try TestDraftStore(url: url)
        let restored = try XCTUnwrap(reopened.stagingJob(
            id: original.id, account: original.sourceAccount, book: book.id))
        XCTAssertEqual(restored.androidReview, evidence)
    }

    func testOldJSONWithoutStructuredReviewStillDecodes() throws {
        let current = try job()
        let json = try JSONEncoder().encode(current)
        var obj = try XCTUnwrap(
            JSONSerialization.jsonObject(with: json) as? [String: Any])
        obj.removeValue(forKey: "androidReview")
        let bytes = try JSONSerialization.data(withJSONObject: obj)
        let restored = try JSONDecoder().decode(StagingPurchaseJob.self, from: bytes)
        XCTAssertNil(restored.androidReview)
        XCTAssertEqual(restored.orderSyncID, current.orderSyncID)
    }
}
