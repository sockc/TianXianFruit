import XCTest
@testable import TianXianFruit

final class PurchaseCloudReconcileTests: XCTestCase {
    private func event(_ table: String, _ id: String,
                       _ values: [String: JSONValue],
                       operation: String = "UPSERT") -> SyncEvent {
        SyncEvent(tableName: table, syncId: id, rowVersion: 2,
                  operation: operation, payload: values)
    }

    private func catalog() -> [SyncEvent] {
        [
            event("partner", "buyer-sync", [
                "id": .number(55), "name": .string("甲")]),
            event("fruit", "fruit-a", [
                "id": .number(101), "name": .string("苹果")]),
            event("fruit", "fruit-b", [
                "id": .number(102), "name": .string("葡萄")]),
            event("purchase_order", "order-1", [
                "id": .number(808), "date": .string("2026-11-20"),
                "buyer_id": .number(55), "buyer_name": .string("甲"),
                "total_cost": .number(50)]),
            event("purchase_item", "item-1", [
                "id": .number(900), "order_id": .number(808),
                "fruit_id": .number(101), "fruit_name": .string("苹果"),
                "unit": .string("件"), "quantity": .number(2),
                "unit_price": .number(15), "total_cost": .number(30)]),
            event("purchase_item", "item-2", [
                "id": .number(901), "order_id": .number(808),
                "fruit_id": .number(102), "fruit_name": .string("葡萄"),
                "unit": .string("箱"), "quantity": .number(1),
                "unit_price": .number(20), "total_cost": .number(20)])
        ]
    }

    private func draft() -> PurchaseWorkspaceDraft {
        var a = PurchaseDraftLine.blank()
        a.fruitSyncID = "fruit-a"
        a.fruitName = "苹果"
        a.quantity = "2"
        a.unit = "件"
        a.unitPrice = "15"
        var b = PurchaseDraftLine.blank()
        b.fruitSyncID = "fruit-b"
        b.fruitName = "葡萄"
        b.quantity = "1"
        b.unit = "箱"
        b.unitPrice = "20"
        return PurchaseWorkspaceDraft(
            id: "draft-1", account: "seller", bookID: "formal-book",
            date: "2026-11-20", buyerSyncID: "buyer-sync",
            buyerName: "甲", purchaseType: "PLANNED", remark: "",
            lines: [a, b], createdAt: Date(timeIntervalSince1970: 10),
            updatedAt: Date(timeIntervalSince1970: 20))
    }

    func testAnExactCloudOrderIsRecognizedWithoutPushing() {
        let report = PurchaseCloudReconcilePolicy.inspect(
            draft(), events: catalog())
        XCTAssertEqual(report.status, .matchingCloudOrder)
        XCTAssertEqual(report.matchingOrderID, "order-1")
        XCTAssertTrue(report.isConfirmedCloudOrder)
        XCTAssertEqual(report.fingerprint.count, 64)
    }

    func testSameBuyerDifferentAmountsAndMissingItemRequireReview() {
        let altered = catalog().map { entry -> SyncEvent in
            guard entry.syncId == "item-2" else { return entry }
            var fields = entry.payload
            fields["unit_price"] = .number(21)
            return event(entry.tableName, entry.syncId, fields)
        }
        let mismatch = PurchaseCloudReconcilePolicy.inspect(
            draft(), events: altered)
        XCTAssertEqual(mismatch.status, .potentialDuplicate)
        XCTAssertNil(mismatch.matchingOrderID)

        let missing = PurchaseCloudReconcilePolicy.inspect(
            draft(), events: catalog().filter { $0.syncId != "item-2" })
        XCTAssertEqual(missing.status, .potentialDuplicate)
    }

    func testNoOtherPurchaseByBuyerIsAbsentNotAutomaticallySubmitted() {
        let noOrder = catalog().filter { $0.tableName != "purchase_order" }
        let report = PurchaseCloudReconcilePolicy.inspect(
            draft(), events: noOrder)
        XCTAssertEqual(report.status, .absentFromLatestCloud)
        XCTAssertFalse(report.isConfirmedCloudOrder)
    }

    func testMissingFruitIdentityAndDeletedBuyerRejectsComparison() {
        let noFruit = catalog().filter { $0.syncId != "fruit-a" }
        XCTAssertEqual(PurchaseCloudReconcilePolicy.inspect(
            draft(), events: noFruit).status, .unsafeToCompare)
        let noBuyer = catalog().filter { $0.syncId != "buyer-sync" }
        XCTAssertEqual(PurchaseCloudReconcilePolicy.inspect(
            draft(), events: noBuyer).status, .unsafeToCompare)
    }

    func testNumericParentIDCollisionIsNotTreatedAsExact() {
        let alias = event("purchase_order", "unrelated-order", [
            "id": .number(808), "date": .string("2026-11-19"),
            "buyer_id": .number(50), "buyer_name": .string("他人"),
            "total_cost": .number(1)])
        let report = PurchaseCloudReconcilePolicy.inspect(
            draft(), events: catalog() + [alias])
        XCTAssertEqual(report.status, .potentialDuplicate)
    }

    func testFingerprintStableUnderMetadataChangesButNotFinancialEdits() {
        let initial = draft()
        var same = initial
        same.updatedAt = Date()
        XCTAssertEqual(PurchaseCloudReconcilePolicy.fingerprint(initial),
                       PurchaseCloudReconcilePolicy.fingerprint(same))
        same.lines[0].unitPrice = "16"
        XCTAssertNotEqual(PurchaseCloudReconcilePolicy.fingerprint(initial),
                          PurchaseCloudReconcilePolicy.fingerprint(same))
        same = initial
        same.bookID = "different-book"
        XCTAssertNotEqual(PurchaseCloudReconcilePolicy.fingerprint(initial),
                          PurchaseCloudReconcilePolicy.fingerprint(same))
    }

    func testLatestPriceIgnoresZeroAndUsesOnlyMatchingUnit() {
        let all = catalog() + [
            event("purchase_order", "newer-order", [
                "id": .number(9001), "date": .string("2026-11-21")]),
            event("purchase_item", "newer-item", [
                "order_id": .number(9001), "fruit_id": .number(101),
                "fruit_name": .string("苹果"), "unit": .string("件"),
                "unit_price": .number(0), "quantity": .number(1),
                "total_cost": .number(0)]),
            event("purchase_item", "other-unit", [
                "order_id": .number(9001), "fruit_id": .number(101),
                "fruit_name": .string("苹果"), "unit": .string("箱"),
                "unit_price": .number(22), "quantity": .number(1),
                "total_cost": .number(22)])
        ]
        let latest = PurchaseLastPricePolicy.latest(
            fruitSyncID: "fruit-a", unit: "件", records: all)
        XCTAssertEqual(latest?.price, 15)
        XCTAssertEqual(latest?.date, "2026-11-20")
        let boxes = PurchaseLastPricePolicy.latest(
            fruitSyncID: "fruit-a", unit: "箱", records: all)
        XCTAssertEqual(boxes?.price, 22)
    }

    func testLatestPriceFailsClosedOnNumericIDCollision() {
        let alias = event("purchase_order", "fake-order", [
            "id": .number(808), "date": .string("2026-11-21")])
        let last = PurchaseLastPricePolicy.latest(
            fruitSyncID: "fruit-a", unit: "件",
            records: catalog() + [alias])
        XCTAssertNil(last)
    }
}
