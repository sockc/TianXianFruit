import XCTest
@testable import TianXianFruit

final class TXFundDataAdapterTests: XCTestCase {
    private func event(_ table: String, _ key: String,
                       _ payload: [String: JSONValue]) -> SyncEvent {
        SyncEvent(tableName: table, syncId: key, rowVersion: 1,
                  operation: "UPSERT", payload: payload)
    }

    private func source() -> ReadOnlySnapshot {
        var s = ReadOnlySnapshot()
        s.apply([
            event("partner", "a", ["id": .number(1), "name": .string("甲"), "enabled": .number(1)]),
            event("partner", "b", ["id": .number(2), "name": .string("乙"), "enabled": .number(1)]),
            event("profit_rule", "r1", ["id": .number(1), "partner_id": .number(1),
                                       "percent": .number(50), "is_settlement_center": .number(1)]),
            event("profit_rule", "r2", ["id": .number(2), "partner_id": .number(2),
                                       "percent": .number(50), "is_settlement_center": .number(0)]),
            event("purchase_order", "p", ["date": .string("2026-11-01"),
                "buyer_id": .number(1), "buyer_name": .string("甲"), "total_cost": .number(40)]),
            event("store_daily_record", "s", ["date": .string("2026-11-01"),
                "revenue": .number(100), "expense": .number(0),
                "opening_stock_value": .number(0), "stock_left_value": .number(0),
                "wechat_income": .number(100), "wechat_collector_id": .number(2),
                "wechat_collector_name": .string("乙"),
                "alipay_income": .number(0), "cash_income": .number(0),
                "receipt_splits_json": .string("[]")])
        ])
        return s
    }

    func testUnsettledBusinessDayAndFullHistory() {
        let r = TXFundDataAdapter.evaluate(source(), until: "2026-11-25")
        XCTAssertNil(r.blocker)
        XCTAssertEqual(r.rows.first(where: { $0.id == "a" })?.balance, 70)
        XCTAssertEqual(r.rows.first(where: { $0.id == "b" })?.balance, -70)
        XCTAssertEqual(r.rows.first(where: { $0.id == "a" })?.isCenter, true)
    }

    func testPartialAndFullyRegisteredCutoff() {
        var s = source()
        s.apply([
            event("settlement_transfer", "t", [
                "date": .string("2026-11-02"), "settlement_id": .number(0),
                "settlement_kind": .string("CUTOFF_CENTER"), "amount": .number(70),
                "settled_amount": .number(20), "from_partner_id": .number(2),
                "to_partner_id": .number(1), "from_partner_name": .string("乙"),
                "to_partner_name": .string("甲"), "focus_partner_id": .number(2)
            ])
        ])
        let r = TXFundDataAdapter.evaluate(s, until: "2026-11-25")
        XCTAssertNil(r.blocker)
        XCTAssertEqual(r.rows.first(where: { $0.id == "b" })?.balance, -50)
        s.apply([event("settlement_transfer", "t2", [
            "date": .string("2026-11-03"), "settlement_id": .number(0),
            "settlement_kind": .string("CUTOFF_CENTER"), "amount": .number(50),
            "settled_amount": .number(50), "from_partner_id": .number(2),
            "to_partner_id": .number(1), "from_partner_name": .string("乙"),
            "to_partner_name": .string("甲"), "focus_partner_id": .number(2)
        ])])
        let cleared = TXFundDataAdapter.evaluate(s, until: "2026-11-25")
        XCTAssertNil(cleared.blocker)
        XCTAssertTrue(cleared.rows.allSatisfy { $0.balance == 0 })
        let before = TXFundDataAdapter.evaluate(s, until: "2026-11-02")
        XCTAssertEqual(before.rows.first(where: { $0.id == "b" })?.balance, -50)
    }

    func testNumericIdentityCollisionAndMissingMoneyBlock() {
        var s = source()
        s.apply([event("partner", "collision", ["id": .number(2), "name": .string("丙"),
                                                 "enabled": .number(1)])])
        let r = TXFundDataAdapter.evaluate(s, until: "2026-11-25")
        XCTAssertNotNil(r.blocker)
        XCTAssertTrue(r.rows.isEmpty)
        XCTAssertNil(TXFundDataAdapter.number(.null))
        XCTAssertNil(TXFundDataAdapter.number(.string("invalid")))
    }

    func testMissingPurchaseIdentityBlocksRatherThanGuesses() {
        var s = source()
        s.apply([event("purchase_order", "bad", ["date": .string("2026-11-02"),
            "buyer_name": .string("甲"), "total_cost": .number(10)])])
        XCTAssertNotNil(TXFundDataAdapter.evaluate(s, until: "2026-11-25").blocker)
    }
}
