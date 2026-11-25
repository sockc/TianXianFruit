import XCTest
@testable import TianXianFruit

final class TXInventory095ValuationTests: XCTestCase {
    private func e(_ table: String, _ id: String, _ payload: [String: JSONValue],
                   version: Int64 = 1) -> SyncEvent {
        SyncEvent(tableName: table, syncId: id, rowVersion: version,
                  operation: "UPSERT", payload: payload)
    }
    private func fixture() -> ReadOnlySnapshot {
        var s = ReadOnlySnapshot()
        s.apply([
            e("fruit", "f", ["id": .number(1), "name": .string("红提")]),
            e("purchase_order", "old", ["id": .number(10), "date": .string("2026-11-24"),
                "created_at": .number(10)]),
            e("purchase_item", "old-item", ["id": .number(11), "order_id": .number(10),
                "fruit_id": .number(1), "fruit_name": .string("红提"), "unit": .string("件"),
                "quantity": .number(10), "unit_price": .number(20), "total_cost": .number(200)]),
            e("inventory_snapshot", "prev", ["date": .string("2026-11-24"), "fruit_id": .number(1),
                "fruit_name": .string("红提"), "unit": .string("件"),
                "remaining_quantity": .number(4), "loss_quantity": .number(0)]),
            e("purchase_order", "today", ["id": .number(20), "date": .string("2026-11-25"),
                "created_at": .number(20)]),
            e("purchase_item", "new-item", ["id": .number(21), "order_id": .number(20),
                "fruit_id": .number(1), "fruit_name": .string("红提"), "unit": .string("件"),
                "quantity": .number(4), "unit_price": .number(40), "total_cost": .number(160),
                "unit_weight_jin": .number(5)]),
            e("inventory_snapshot", "current", ["date": .string("2026-11-25"), "fruit_id": .number(1),
                "fruit_name": .string("红提"), "unit": .string("件"),
                "remaining_quantity": .number(2), "loss_quantity": .number(1)]),
            e("daily_retail_price", "retail", ["date": .string("2026-11-25"), "fruit_id": .number(1),
                "price_per_jin": .number(10), "price_unit": .string("斤")])
        ])
        return s
    }

    func testWeightedCostDoesNotReplaceOldStockWithNewPurchasePrice() throws {
        let r = TXInventory094Policy.evaluate(fixture(), date: "2026-11-25")
        let row = try XCTUnwrap(r.rows.first)
        XCTAssertTrue(r.issues.isEmpty)
        XCTAssertEqual(row.cost, 40)
        XCTAssertEqual(row.openingCost, 80)
        XCTAssertEqual(row.purchaseCost, 160)
        XCTAssertEqual(row.averageCost, 30)
        let value = TXInventory095Valuation.item(row, draft: nil)
        XCTAssertEqual(value.closingCost, 60)
        XCTAssertEqual(value.lossCost, 30)
        XCTAssertEqual(value.consumedCost, 150)
        XCTAssertEqual(value.estimatedRevenue, 250)
        XCTAssertTrue(value.counted)
    }

    func testLocalPriceOverridesAreDatedAndRequireMatchingSourceVersion() throws {
        let s = fixture()
        let date = "2026-11-25"
        let manual = TXInventory095PriceDraft(kind: .cost, product: "f", unit: "件",
            date: date, value: "99", basis: "absent")
        let retail = TXInventory095PriceDraft(kind: .retail, product: "f", unit: "斤",
            date: date, value: "8", basis: "retail|1")
        let row = try XCTUnwrap(TXInventory094Policy.evaluate(s, date: date,
            prices: [manual, retail]).rows.first)
        XCTAssertEqual(row.cost, 40, "Real purchase price takes priority")
        XCTAssertEqual(row.averageCost, 30)
        XCTAssertEqual(row.retail, 8)
        XCTAssertTrue(row.usesLocalRetail)
        let earlier = try XCTUnwrap(TXInventory094Policy.evaluate(s, date: "2026-11-24",
            prices: [manual, retail]).rows.first)
        XCTAssertFalse(earlier.usesLocalRetail)
        XCTAssertFalse(earlier.usesLocalCost)
        var changed = s
        changed.apply([e("daily_retail_price", "retail", ["date": .string(date), "fruit_id": .number(1),
            "price_per_jin": .number(12), "price_unit": .string("斤")], version: 2)])
        let after = try XCTUnwrap(TXInventory094Policy.evaluate(changed, date: date,
            prices: [retail]).rows.first)
        XCTAssertEqual(after.retail, 12)
        XCTAssertFalse(after.usesLocalRetail)
    }

    func testMissingCostStaysUnknownUntilExplicitZeroSnapshot() {
        var tracker = TXInventory095CostTracker()
        tracker.purchase(quantity: 5, cost: 0, known: false)
        tracker.snapshot(remaining: 2)
        XCTAssertFalse(tracker.complete)
        XCTAssertNil(tracker.unitCost)
        tracker.snapshot(remaining: 0)
        XCTAssertTrue(tracker.complete)
        tracker.purchase(quantity: 4, cost: 120, known: true)
        XCTAssertEqual(tracker.unitCost, 30)
        XCTAssertTrue(tracker.complete)
    }

    func testDraftValuationNeverUsesConflictingStock() throws {
        let row = try XCTUnwrap(TXInventory094Policy.evaluate(fixture(),
            date: "2026-11-25").rows.first)
        let draft = TXInventory094Draft(key: row.key, basis: row.basis, remaining: "3", loss: "1")
        let local = TXInventory095Valuation.item(row, draft: draft)
        XCTAssertEqual(local.closingCost, 90)
        XCTAssertTrue(local.local)
        let stale = TXInventory094Draft(key: row.key, basis: "old", remaining: "99", loss: "0")
        XCTAssertEqual(TXInventory095Valuation.item(row, draft: stale).closingCost, 60)
        XCTAssertFalse(TXInventory095Valuation.item(row, draft: stale).local)
    }
}
