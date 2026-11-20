import XCTest
@testable import TianXianFruit

final class PurchaseCloudDetailTests: XCTestCase {
    private func event(_ table: String, _ id: String,
                       _ payload: [String: JSONValue]) -> SyncEvent {
        SyncEvent(tableName: table, syncId: id, rowVersion: 1,
                  operation: "UPSERT", payload: payload)
    }
    private func makeSnapshot(_ children: [SyncEvent],
                              otherOrders: [SyncEvent] = []) -> (PurchaseOrderRow, ReadOnlySnapshot) {
        let order = event("purchase_order", "order-A", [
            "id": .number(800), "date": .string("2026-11-20"),
            "buyer_name": .string("测试采购人"), "total_cost": .number(240)
        ])
        var snapshot = ReadOnlySnapshot()
        snapshot.apply([order] + otherOrders + children)
        return (PurchaseOrderRow(order)!, snapshot)
    }
    func testThreeItemsFromAndroidReconcileByServerParentID() {
        let children = [
            event("purchase_item", "line-1", [
                "order_id": .number(800), "fruit_name": .string("红提"),
                "quantity": .number(20), "unit": .string("斤"),
                "unit_price": .number(3.5), "total_cost": .number(70)
            ]),
            event("purchase_item", "line-2", [
                "order_id": .number(800), "fruit_name": .string("苹果"),
                "quantity": .number(30), "unit": .string("斤"),
                "unit_price": .number(4), "total_cost": .number(120)
            ]),
            event("purchase_item", "line-3", [
                "order_id": .number(800), "fruit_name": .string("香蕉"),
                "quantity": .number(25), "unit": .string("斤"),
                "unit_price": .number(2), "total_cost": .number(50)
            ])
        ]
        let (order, snapshot) = makeSnapshot(children)
        let result = CloudPurchaseDetail(order: order, snapshot: snapshot)
        XCTAssertEqual(result.status, .matched)
        XCTAssertEqual(result.items.count, 3)
        XCTAssertEqual(result.itemTotal, 240)
        XCTAssertEqual(result.difference, 0)
    }
    func testPartialChildSyncCannotClaimSuccess() {
        let child = event("purchase_item", "line-1", [
            "order_id": .number(800), "total_cost": .number(70)
        ])
        let (order, snapshot) = makeSnapshot([child])
        let result = CloudPurchaseDetail(order: order, snapshot: snapshot)
        XCTAssertEqual(result.status, .amountDiffers)
        XCTAssertEqual(result.difference, 170)
    }
    func testWrongParentDoesNotAttachItem() {
        let child = event("purchase_item", "foreign", [
            "order_id": .number(801), "total_cost": .number(240)
        ])
        let (order, snapshot) = makeSnapshot([child])
        XCTAssertEqual(CloudPurchaseDetail(order: order, snapshot: snapshot).status, .incomplete)
    }
    func testMissingPriceOrTombstoneStaysIncomplete() {
        let child = event("purchase_item", "line-1", [
            "order_id": .number(800), "fruit_name": .string("红提")
        ])
        let (order, original) = makeSnapshot([child])
        XCTAssertEqual(CloudPurchaseDetail(order: order, snapshot: original).status, .incomplete)
        var snapshot = original
        snapshot.apply([SyncEvent(tableName: "purchase_item",
            syncId: "line-1", rowVersion: 2, operation: "DELETE", payload: [:])])
        XCTAssertEqual(CloudPurchaseDetail(order: order, snapshot: snapshot).status, .incomplete)
    }
    func testDuplicateParentIDMustNotAttributeItems() {
        let duplicate = event("purchase_order", "order-B", [
            "id": .number(800), "date": .string("2026-11-20"),
            "buyer_name": .string("其他采购人"), "total_cost": .number(240)
        ])
        let child = event("purchase_item", "line-1", [
            "order_id": .number(800), "total_cost": .number(240)
        ])
        let (order, snapshot) = makeSnapshot([child], otherOrders: [duplicate])
        let result = CloudPurchaseDetail(order: order, snapshot: snapshot)
        XCTAssertEqual(result.status, .ambiguous)
        XCTAssertTrue(result.items.isEmpty)
    }
}