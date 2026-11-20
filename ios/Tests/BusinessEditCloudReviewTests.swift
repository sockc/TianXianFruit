import XCTest
@testable import TianXianFruit

final class BusinessEditCloudReviewTests: XCTestCase {
    private func record(version: Int64, cash: Double) -> SyncEvent {
        let syncID = BusinessUploadPolicy.androidSyncID(
            date: "2026-11-20", storeSyncID: "store-1")
        return SyncEvent(tableName: "store_daily_record", syncId: syncID,
            rowVersion: version, operation: "UPSERT", payload: [
                "date": .string("2026-11-20"),
                "store_sync_id": .string("store-1"),
                "store_name": .string("测试摊"),
                "wechat_income": .number(0),
                "alipay_income": .number(0),
                "cash_income": .number(cash),
                "revenue": .number(cash),
                "expense": .number(0),
                "profit": .number(cash),
                "opening_stock_value": .number(0),
                "stock_left_value": .number(0),
                "purchase_cost": .number(0),
                "new_customer": .number(0),
                "old_customer": .number(0),
                "wechat_collector_id": .number(5),
                "alipay_collector_id": .number(5),
                "cash_collector_id": .number(5),
                "expense_payer_id": .number(0),
                "expense_payer_name": .string("未指定"),
                "receipt_splits_json": .string("[]")
            ])
    }

    private func job() -> TestBusinessEditJob {
        TestBusinessEditJob(
            id: "test-edit-1", sourceAccount: "test", bookID: "test-book",
            stageHost: StagingPurchasePolicy.productionURL,
            stageInstanceID: "production-scoped-testbook:test-book",
            stageUsername: "test", deviceID: "device",
            original: record(version: 3, cash: 40),
            proposed: record(version: 4, cash: 45),
            createdAt: Date(timeIntervalSince1970: 1795161600),
            state: .complete, note: "已回读")
    }

    func testMissingAndOlderRevisionAreNotPresentedAsDownloaded() {
        let task = job()
        var snapshot = ReadOnlySnapshot()
        XCTAssertEqual(BusinessEditCloudReview(job: task, snapshot: snapshot).status,
                       .notVisible)
        snapshot.apply([record(version: 3, cash: 40)])
        XCTAssertEqual(BusinessEditCloudReview(job: task, snapshot: snapshot).status,
                       .waitingForRefresh)
    }

    func testMatchingCloudRevisionIsExplicitlyNotAndroidAcknowledgement() {
        let task = job()
        var snapshot = ReadOnlySnapshot()
        snapshot.apply([task.proposed])
        let result = BusinessEditCloudReview(job: task, snapshot: snapshot)
        XCTAssertEqual(result.status, .current)
        XCTAssertTrue(result.title.contains("不代表 Android 已下载"))
    }

    func testNewerOrMismatchedRevisionIsFlagged() {
        let task = job()
        var snapshot = ReadOnlySnapshot()
        snapshot.apply([record(version: 5, cash: 50)])
        XCTAssertEqual(BusinessEditCloudReview(job: task, snapshot: snapshot).status,
                       .newerRevision)
        var wrong = ReadOnlySnapshot()
        wrong.apply([record(version: 4, cash: 80)])
        XCTAssertEqual(BusinessEditCloudReview(job: task, snapshot: wrong).status,
                       .inconsistent)
    }
}
