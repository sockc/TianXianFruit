import XCTest
@testable import TianXianFruit

final class TXInventory094Tests: XCTestCase {
    private func e(_ table: String, _ id: String, _ p: [String: JSONValue]) -> SyncEvent {
        SyncEvent(tableName: table, syncId: id, rowVersion: 1, operation: "UPSERT", payload: p)
    }
    private func stock(_ id: String, _ day: String, _ unit: String,
                       _ qty: Double, _ loss: Double = 0) -> SyncEvent {
        e("inventory_snapshot", id, ["date": .string(day), "fruit_id": .number(1),
            "fruit_name": .string("红提"), "unit": .string(unit),
            "remaining_quantity": .number(qty), "loss_quantity": .number(loss)])
    }
    private func purchase(_ id: String, parent: Double, unit: String,
                          qty: Double, cost: Double, total: Double,
                          name: String = "红提") -> SyncEvent {
        e("purchase_item", id, ["id": .number(id == "a" ? 11 : 12),
            "order_id": .number(parent), "fruit_id": .number(1), "fruit_name": .string(name),
            "unit": .string(unit), "quantity": .number(qty), "unit_price": .number(cost),
            "total_cost": .number(total)])
    }
    private func fixture() -> ReadOnlySnapshot {
        var s = ReadOnlySnapshot()
        s.apply([e("fruit", "grape", ["id": .number(1), "name": .string("红提")]),
            stock("old", "2026-11-20", "件", 4),
            stock("other-unit", "2026-11-21", "斤", 5),
            stock("current", "2026-11-25", "件", 2, 1),
            e("purchase_order", "p", ["id": .number(9), "date": .string("2026-11-25"),
                                     "created_at": .number(100)]),
            purchase("a", parent: 9, unit: "件", qty: 3, cost: 0, total: 60),
            purchase("b", parent: 9, unit: "件", qty: 1, cost: 0, total: 0),
            purchase("sum", parent: 9, unit: "件", qty: 99, cost: 0, total: 999, name: "总价"),
            e("daily_retail_price", "old-price", ["date": .string("2026-11-21"),
                "fruit_id": .number(1), "price_per_jin": .number(7), "price_unit": .string("斤")])])
        return s
    }
    func testPerUnitCarryForwardActualPurchaseAndSavedInventory() throws {
        let r = TXInventory094Policy.evaluate(fixture(), date: "2026-11-25")
        XCTAssertTrue(r.issues.isEmpty)
        XCTAssertEqual(r.rows.count, 2)
        let row = try XCTUnwrap(r.rows.first(where: { $0.key.unit == "件" }))
        XCTAssertEqual(row.opening, 4)
        XCTAssertEqual(row.purchased, 4)
        XCTAssertEqual(row.available, 8)
        XCTAssertEqual(row.remaining, 2)
        XCTAssertEqual(row.loss, 1)
        XCTAssertEqual(row.cost, 20)
        XCTAssertEqual(row.retail, 7)
        XCTAssertEqual(row.retailDate, "2026-11-21")
        XCTAssertEqual(row.savedID, "current")
        let other = try XCTUnwrap(r.rows.first(where: { $0.key.unit == "斤" }))
        XCTAssertEqual(other.opening, 5)
        XCTAssertEqual(other.remaining, 0)
        XCTAssertNil(other.savedID)
    }
    func testLatestZeroStopsCarryAndFuturePriceExcluded() {
        var s = fixture()
        s.apply([stock("zero", "2026-11-24", "斤", 0),
                 e("daily_retail_price", "future", ["date": .string("2026-11-26"),
                    "fruit_id": .number(1), "price_per_jin": .number(99)])])
        let r = TXInventory094Policy.evaluate(s, date: "2026-11-25")
        XCTAssertFalse(r.rows.contains { $0.key.unit == "斤" })
        XCTAssertEqual(r.rows.first?.retail, 7)
    }
    func testDuplicateInventoryAndMissingParentDoNotGuess() {
        var s = fixture()
        s.apply([stock("duplicate", "2026-11-25", "件", 20),
                 purchase("orphan", parent: 88, unit: "件", qty: 20, cost: 5, total: 100)])
        let r = TXInventory094Policy.evaluate(s, date: "2026-11-25")
        XCTAssertFalse(r.issues.isEmpty)
        XCTAssertFalse(r.rows.contains { $0.key.unit == "件" })
    }
    func testDraftValidation() {
        XCTAssertTrue(TXInventory094DraftStore.valid("1.25"))
        XCTAssertTrue(TXInventory094DraftStore.valid("0"))
        for value in ["", "-1", "1.0001", "NaN", "1e2"] {
            XCTAssertFalse(TXInventory094DraftStore.valid(value))
        }
    }
}
