import XCTest
@testable import TianXianFruit

final class DailyPurchaseHistoryAndPriceTests: XCTestCase {
    private func event(_ table: String, _ id: String,
                       _ payload: [String: JSONValue]) -> SyncEvent {
        SyncEvent(tableName: table, syncId: id,
                  rowVersion: 1, operation: "UPSERT", payload: payload)
    }

    private func order(_ id: String, _ date: String,
                       buyer: String = "张") -> PurchaseOrderRow {
        PurchaseOrderRow(event("purchase_order", id, [
            "date": .string(date), "buyer_name": .string(buyer),
            "total_cost": .number(120)
        ]))!
    }

    func testPurchaseHistorySingleDayAndCustom90Days() throws {
        let date = try XCTUnwrap(BusinessDates.parse("2026-11-25"))
        let orders = [
            order("a", "2026-11-25", buyer: "张"),
            order("b", "2026-11-24", buyer: "李"),
            order("c", "2026-10-02", buyer: "张"),
            order("d", "2026-08-01", buyer: "张"),
            order("e", "2026-11-26", buyer: "张")
        ]
        XCTAssertEqual(TXPurchaseHistoryPolicy.filter(orders,
            range: "单日", selectedDate: date, buyer: "",
            search: "").map(\.id), ["a"])
        XCTAssertEqual(TXPurchaseHistoryPolicy.filter(orders,
            range: "近7天", selectedDate: date, buyer: "",
            search: "").map(\.id), ["a", "b"])
        XCTAssertEqual(TXPurchaseHistoryPolicy.filter(orders,
            range: "近90天", selectedDate: date, buyer: "张",
            search: "").map(\.id), ["a", "c"])
        XCTAssertEqual(TXPurchaseHistoryPolicy.filter(orders,
            range: "全部", selectedDate: date, buyer: "",
            search: "2026-08").map(\.id), ["d"])
        XCTAssertFalse(TXPurchaseHistoryPolicy.filter(orders,
            range: "全部", selectedDate: date, buyer: "",
            search: "").contains(where: { $0.id == "e" }))
    }

    func testHistoricalPriceIgnoresZeroFutureAndWrongUnits() {
        let records = [
            event("fruit", "apple", ["id": .number(12), "name": .string("苹果")]),
            event("purchase_order", "old", [
                "id": .number(101), "date": .string("2026-11-10")]),
            event("purchase_order", "new", [
                "id": .number(102), "date": .string("2026-11-30")]),
            event("purchase_order", "middle", [
                "id": .number(103), "date": .string("2026-11-20")]),
            event("purchase_item", "old-item", [
                "order_id": .number(101), "fruit_id": .number(12),
                "unit": .string("件"), "unit_price": .number(5)]),
            event("purchase_item", "future-item", [
                "order_id": .number(102), "fruit_id": .number(12),
                "unit": .string("件"), "unit_price": .number(99)]),
            event("purchase_item", "zero-item", [
                "order_id": .number(103), "fruit_id": .number(12),
                "unit": .string("件"), "unit_price": .number(0)]),
            event("purchase_item", "wrong-unit", [
                "order_id": .number(103), "fruit_id": .number(12),
                "unit": .string("箱"), "unit_price": .number(9)])
        ]
        XCTAssertEqual(PurchaseLastPricePolicy.latest(
            fruitSyncID: "apple", unit: "件", records: records,
            onOrBefore: "2026-11-25")?.price, 5)
        XCTAssertEqual(PurchaseLastPricePolicy.latest(
            fruitSyncID: "apple", unit: "件", records: records,
            onOrBefore: "2026-11-30")?.price, 99)
        XCTAssertNil(PurchaseLastPricePolicy.latest(
            fruitSyncID: "apple", unit: "袋", records: records,
            onOrBefore: "2026-11-25"))
    }

    func testDuplicateOrderNumberDoesNotProduceStalePriceSuggestion() {
        let records = [
            event("fruit", "apple", ["id": .number(12), "name": .string("苹果")]),
            event("purchase_order", "one", [
                "id": .number(101), "date": .string("2026-11-10")]),
            event("purchase_order", "duplicate", [
                "id": .number(101), "date": .string("2026-11-11")]),
            event("purchase_item", "item", [
                "order_id": .number(101), "fruit_id": .number(12),
                "unit": .string("件"), "unit_price": .number(5)])
        ]
        XCTAssertNil(PurchaseLastPricePolicy.latest(
            fruitSyncID: "apple", unit: "件", records: records))
        XCTAssertEqual(PurchaseLastPricePolicy.priceAfterFruitChange(
            previousFruitID: "apple", nextFruitID: "pear",
            currentPrice: "7.50"), "0")
        XCTAssertEqual(PurchaseLastPricePolicy.priceAfterFruitChange(
            previousFruitID: "apple", nextFruitID: "apple",
            currentPrice: "7.50"), "7.50")
    }
}
