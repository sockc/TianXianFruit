import XCTest
@testable import TianXianFruit

final class TestSyncAuditTests: XCTestCase {
    private var book: CloudBook {
        CloudBook(id: "isolated-qa-book",
                  name: "iOS_TEST_同步核对", role: "OWNER",
                  deleted: false, purged: false)
    }

    private func session() -> StagingSession {
        StagingSession(token: "T", username: "test-only",
                       deviceID: "test-device", book: book,
                       scope: ScopedAccountEvidence(
                        instanceID: "production-scoped-testbook:" + book.id,
                        bookID: book.id),
                       host: StagingPurchasePolicy.productionURL)
    }

    private var payload: [String: JSONValue] {
        let receipt = "[{\"partner_id\":11,\"partner_name\":\"测试人\",\"amount\":45,\"wechat_income\":0,\"alipay_income\":0,\"cash_income\":45}]"
        return [
            "date": .string("2026-11-20"),
            "store_sync_id": .string("store-sync-01"),
            "store_name": .string("测试摊位"),
            "cash_income": .number(45),
            "wechat_income": .number(0),
            "alipay_income": .number(0),
            "revenue": .number(45),
            "profit": .number(45),
            "expense": .number(0),
            "opening_stock_value": .number(0),
            "stock_left_value": .number(0),
            "purchase_cost": .number(0),
            "new_customer": .number(0),
            "old_customer": .number(0),
            "wechat_collector_id": .number(11),
            "alipay_collector_id": .number(11),
            "cash_collector_id": .number(11),
            "expense_payer_id": .number(11),
            "wechat_collector_name": .string("测试人"),
            "alipay_collector_name": .string("测试人"),
            "cash_collector_name": .string("测试人"),
            "expense_payer_name": .string("测试人"),
            "receipt_splits_json": .string(receipt)
        ]
    }

    private func sale(version: Int64 = 1, deleted: Bool = false,
                      cash: Double = 45) -> SyncEvent {
        let uuid = BusinessUploadPolicy.androidSyncID(
            date: "2026-11-20", storeSyncID: "store-sync-01")
        var content = payload
        content["cash_income"] = .number(cash)
        content["revenue"] = .number(cash)
        content["profit"] = .number(cash)
        return SyncEvent(tableName: "store_daily_record",
                         syncId: uuid, rowVersion: version,
                         operation: deleted ? "DELETE" : "UPSERT",
                         payload: content)
    }

    private func salesJob(state: BusinessUploadState,
                          username: String = "test-only") -> BusinessUploadJob {
        BusinessUploadJob(id: "sales-1",
            sourceAccount: "primary",
            bookID: book.id,
            stageHost: StagingPurchasePolicy.productionURL,
            stageInstanceID: "production-scoped-testbook:" + book.id,
            stageUsername: username,
            deviceID: "test-device",
            syncID: sale().syncId, payload: payload,
            createdAt: Date(timeIntervalSince1970: 1),
            state: state, note: "")
    }

    private func editJob() -> TestBusinessEditJob {
        TestBusinessEditJob(id: "edit-1",
            sourceAccount: "primary", bookID: book.id,
            stageHost: StagingPurchasePolicy.productionURL,
            stageInstanceID: "production-scoped-testbook:" + book.id,
            stageUsername: "test-only", deviceID: "test-device",
            original: sale(),
            proposed: sale(version: 2),
            createdAt: Date(timeIntervalSince1970: 2),
            state: .complete, note: "")
    }

    private func purchaseJob(state: StagingUploadState = .ready) -> StagingPurchaseJob {
        StagingPurchaseJob(id: "purchase-1",
            sourceAccount: "primary", bookID: book.id,
            stageHost: StagingPurchasePolicy.productionURL,
            stageInstanceID: "production-scoped-testbook:" + book.id,
            stageUsername: "test-only", deviceID: "test-device",
            orderSyncID: "purchase-parent-1",
            itemSyncID: "purchase-item-1",
            orderRemoteID: 300, itemRemoteID: 301,
            orderPayload: ["date": .string("2026-11-20"),
                           "buyer_name": .string("采购人")],
            itemPayload: [:],
            createdAt: Date(timeIntervalSince1970: 3),
            state: state, lastMessage: "")
    }

    private func remarkJob() -> PurchaseRemarkEditJob {
        let original = SyncEvent(tableName: "purchase_order",
            syncId: "purchase-parent-1", rowVersion: 1,
            operation: "UPSERT", payload: [
                "id": .number(300), "date": .string("2026-11-20"),
                "buyer_name": .string("采购人"),
                "total_cost": .number(50), "remark": .string("旧备注")
            ])
        var after = original.payload
        after["remark"] = .string("新备注")
        let proposed = SyncEvent(tableName: original.tableName,
            syncId: original.syncId, rowVersion: 2,
            operation: "UPSERT", payload: after)
        let child = SyncEvent(tableName: "purchase_item",
            syncId: "purchase-item-1", rowVersion: 1,
            operation: "UPSERT", payload: [
                "order_id": .number(300),
                "total_cost": .number(50)
            ])
        return PurchaseRemarkEditJob(id: "remark-1",
            sourceAccount: "primary", bookID: book.id,
            stageHost: StagingPurchasePolicy.productionURL,
            stageInstanceID: "production-scoped-testbook:" + book.id,
            stageUsername: "test-only", deviceID: "test-device",
            original: original, proposed: proposed,
            children: [child], createdAt: Date(timeIntervalSince1970: 4),
            state: .complete, note: "")
    }

    private func audit(sales: [BusinessUploadJob] = [],
                       edits: [TestBusinessEditJob] = [],
                       purchases: [StagingPurchaseJob] = [],
                       remarks: [PurchaseRemarkEditJob] = [],
                       events: [SyncEvent] = []) -> TestSyncAuditReport {
        TestSyncAuditPolicy.audit(
            businessUploads: sales, businessEdits: edits,
            purchases: purchases, remarks: remarks,
            events: events, session: session())
    }

    func testReadyJobNeverClaimsCloudMatchEvenIfRemoteLooksIdentical() {
        let job = salesJob(state: .ready)
        let report = audit(sales: [job], events: [sale()])
        XCTAssertEqual(report.items.count, 1)
        XCTAssertEqual(report.items[0].cloudStatus, .notSent)
        XCTAssertEqual(report.attention, 1)
    }

    func testCloudConfirmedThenNewerAndDeletedHaveDistinctMeaning() {
        let job = salesJob(state: .complete)
        let matching = audit(sales: [job], events: [sale()])
        XCTAssertEqual(matching.items[0].cloudStatus, .confirmed)
        XCTAssertEqual(matching.confirmed, 1)

        let next = audit(sales: [job], events: [sale(version: 3, cash: 50)])
        XCTAssertEqual(next.items[0].cloudStatus, .newerRevision)
        XCTAssertEqual(next.attention, 0)
        XCTAssertEqual(next.newer, 1)

        let deleted = audit(sales: [job], events: [sale(version: 4, deleted: true)])
        XCTAssertEqual(deleted.items[0].cloudStatus, .deleted)
        XCTAssertEqual(deleted.attention, 1)
    }

    func testSameRevisionButDifferentCashIsNotAccepted() {
        let job = salesJob(state: .complete)
        let report = audit(sales: [job], events: [sale(cash: 46)])
        XCTAssertEqual(report.items[0].cloudStatus, .mismatch)
    }

    func testDifferentTestUsernameBlocksCrossSessionObservation() {
        let job = salesJob(state: .complete, username: "another-test-account")
        XCTAssertEqual(audit(sales: [job], events: [sale()])
            .items[0].cloudStatus, .wrongScope)
    }

    func testBusinessEditCloudExactAndLaterRevision() {
        let job = editJob()
        XCTAssertEqual(audit(edits: [job], events: [sale(version: 2)])
            .items[0].cloudStatus, .confirmed)
        XCTAssertEqual(audit(edits: [job], events: [sale(version: 3, cash: 46)])
            .items[0].cloudStatus, .newerRevision)
    }

    func testPurchaseNeverSubmittedAndLaterParentRevision() {
        let pending = purchaseJob()
        XCTAssertEqual(audit(purchases: [pending], events: [])
            .items[0].cloudStatus, .notSent)
        let done = purchaseJob(state: .complete)
        let changed = SyncEvent(tableName: "purchase_order",
            syncId: done.orderSyncID, rowVersion: 3,
            operation: "UPSERT", payload: [
                "id": .number(300), "total_cost": .number(50),
                "remark": .string("新版")
            ])
        XCTAssertEqual(audit(purchases: [done], events: [changed])
            .items[0].cloudStatus, .newerRevision)
        XCTAssertEqual(done.state, .complete)
    }

    func testPurchaseRemarkRequiresUnchangedChildrenAndOriginalMoney() {
        let job = remarkJob()
        XCTAssertEqual(audit(remarks: [job],
                             events: [job.proposed] + job.children)
            .items[0].cloudStatus, .confirmed)
        let wrongChild = SyncEvent(tableName: "purchase_item",
            syncId: "purchase-item-1", rowVersion: 2,
            operation: "UPSERT", payload: [
                "order_id": .number(300), "total_cost": .number(55)
            ])
        XCTAssertEqual(audit(remarks: [job],
                             events: [job.proposed, wrongChild])
            .items[0].cloudStatus, .mismatch)
    }

    func testFourQueueSummarySortedAndNoAndroidAckClaim() {
        let report = audit(
            sales: [salesJob(state: .ready)],
            edits: [editJob()],
            purchases: [purchaseJob()],
            remarks: [remarkJob()],
            events: [sale(version: 2), remarkJob().proposed] +
                    remarkJob().children)
        XCTAssertEqual(report.items.count, 4)
        XCTAssertEqual(report.items[0].id, "purchase-remark|remark-1")
        XCTAssertEqual(report.confirmed, 2)
        XCTAssertEqual(report.attention, 2)
        XCTAssertTrue(report.items[0].details.contains("只读"))
    }
}
