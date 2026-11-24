import XCTest
@testable import TianXianFruit

final class TXInventory096AnalysisTests: XCTestCase {
    private func event(_ table: String, _ id: String, _ p: [String: JSONValue]) -> SyncEvent {
        SyncEvent(tableName: table, syncId: id, rowVersion: 1, operation: "UPSERT", payload: p)
    }
    private func source() -> ReadOnlySnapshot {
        var s = ReadOnlySnapshot()
        s.apply([
            event("fruit", "f", ["id": .number(1), "name": .string("红提")]),
            event("purchase_order", "prior", ["id": .number(9), "date": .string("2026-11-24"),
                "total_cost": .number(100), "created_at": .number(1)]),
            event("purchase_item", "prior-item", ["id": .number(11), "order_id": .number(9),
                "fruit_id": .number(1), "fruit_name": .string("红提"), "unit": .string("件"),
                "quantity": .number(5), "unit_price": .number(20), "total_cost": .number(100)]),
            event("inventory_snapshot", "prior-stock", ["date": .string("2026-11-24"),
                "fruit_id": .number(1), "fruit_name": .string("红提"), "unit": .string("件"),
                "remaining_quantity": .number(2), "loss_quantity": .number(0)]),
            event("purchase_order", "now", ["id": .number(10), "date": .string("2026-11-25"),
                "total_cost": .number(120), "created_at": .number(2)]),
            event("purchase_item", "now-item", ["id": .number(12), "order_id": .number(10),
                "fruit_id": .number(1), "fruit_name": .string("红提"), "unit": .string("件"),
                "quantity": .number(4), "unit_price": .number(30), "total_cost": .number(120),
                "unit_weight_jin": .number(5)]),
            event("daily_retail_price", "retail", ["date": .string("2026-11-25"),
                "fruit_id": .number(1), "price_per_jin": .number(10), "price_unit": .string("斤")]),
            event("inventory_snapshot", "stock", ["date": .string("2026-11-25"),
                "fruit_id": .number(1), "fruit_name": .string("红提"), "unit": .string("件"),
                "remaining_quantity": .number(2), "loss_quantity": .number(1)]),
            event("store_daily_record", "sale", ["date": .string("2026-11-25"), "revenue": .number(150),
                "expense": .number(10), "opening_stock_value": .number(0), "stock_left_value": .number(0)])
        ])
        return s
    }
    func testThreeProfitDefinitionsStaySeparate() throws {
        let r = TXInventory096Analysis.evaluate(source(), date: "2026-11-25")
        XCTAssertTrue(r.complete)
        XCTAssertEqual(r.knownAvailable, 140)
        XCTAssertEqual(r.knownPurchased, 80)
        XCTAssertEqual(r.recordedProfit, 20)
        XCTAssertEqual(r.soldCost, 80)
        XCTAssertEqual(r.lossCost, 26.67)
        XCTAssertEqual(r.operatingProfit, 33.33)
        let item = try XCTUnwrap(r.items.first)
        XCTAssertEqual(item.valuation.sold, 3)
        XCTAssertEqual(item.productGross, 70)
    }
    func testOpportunityNeedsWeightForJinAndCompatibleUnits() {
        let p = TXInventory096Analysis.opportunity
        XCTAssertNil(p(4, 120, 10, nil, "斤", "件"))
        XCTAssertNil(p(4, 120, 10, 5, "箱", "件"))
        XCTAssertEqual(p(4, 120, 50, nil, "件", "件"), 80)
        XCTAssertEqual(p(0, nil, nil, nil, "斤", "件"), 0)
    }
    func testLocalDraftAffectsAnalysisButNotRecordedProfit() throws {
        let s = source()
        let row = try XCTUnwrap(TXInventory094Policy.evaluate(s, date: "2026-11-25").rows.first)
        let local = TXInventory094Draft(key: row.key, basis: row.basis, remaining: "3", loss: "1")
        let r = TXInventory096Analysis.evaluate(s, date: "2026-11-25", drafts: [local])
        XCTAssertTrue(r.hasLocal)
        XCTAssertEqual(r.recordedProfit, 20)
        XCTAssertEqual(r.knownAvailable, 140)
        XCTAssertEqual(r.items.first?.valuation.sold, 2)
        XCTAssertNotEqual(r.operatingProfit, 33.33)
        let bad = TXInventory094Draft(key: row.key, basis: row.basis, remaining: "99", loss: "0")
        let blocked = TXInventory096Analysis.evaluate(s, date: "2026-11-25", drafts: [bad])
        XCTAssertFalse(blocked.complete)
        XCTAssertNil(blocked.operatingProfit)
    }
}
