import XCTest
@testable import TianXianFruit

final class TXDailySettlement092Tests: XCTestCase {
    private func event(_ table: String, _ id: String, _ p: [String: JSONValue]) -> SyncEvent {
        SyncEvent(tableName: table, syncId: id, rowVersion: 1, operation: "UPSERT", payload: p)
    }
    private let date = "2026-11-25"
    private func source(savedProfit: Bool = true, stock: Double = 0) -> ReadOnlySnapshot {
        var s = ReadOnlySnapshot()
        s.apply([
            event("partner", "a", ["id": .number(1), "name": .string("甲"), "enabled": .number(1)]),
            event("partner", "b", ["id": .number(2), "name": .string("乙"), "enabled": .number(1)]),
            event("profit_rule", "r", ["id": .number(1), "partner_id": .number(1),
                "percent": .number(100), "is_settlement_center": .number(1)]),
            event("purchase_order", "p", ["date": .string(date), "total_cost": .number(40),
                "buyer_id": .number(1), "buyer_name": .string("甲")]),
            event("store_daily_record", "b1", ["date": .string(date), "revenue": .number(100),
                "expense": .number(0), "opening_stock_value": .number(0), "stock_left_value": .number(stock),
                "wechat_income": .number(100), "alipay_income": .number(0), "cash_income": .number(0),
                "wechat_collector_id": .number(2), "wechat_collector_name": .string("乙"),
                "receipt_splits_json": .string("[]")])
        ])
        if savedProfit {
            s.apply([
                event("profit_distribution", "pa", ["date": .string(date), "partner_id": .number(1),
                    "partner_name": .string("甲"), "allocated_profit": .number(30)]),
                event("profit_distribution", "pb", ["date": .string(date), "partner_id": .number(2),
                    "partner_name": .string("乙"), "allocated_profit": .number(30)])
            ])
        }
        return s
    }
    func testSavedProfitAndCenterTransfer() throws {
        let r = TXDailySettlement092Policy.evaluate(source(), date: date)
        XCTAssertNil(r.blocker)
        XCTAssertEqual(r.profit, 60)
        XCTAssertEqual(r.rows.first(where: { $0.id == 1 })?.balance, 70)
        XCTAssertEqual(r.rows.first(where: { $0.id == 2 })?.balance, -70)
        XCTAssertEqual(r.transfers.count, 1)
        let t = try XCTUnwrap(r.transfers.first)
        XCTAssertEqual(t.from, "乙")
        XCTAssertEqual(t.to, "甲")
        XCTAssertEqual(t.amount, 70)
    }
    func testMissingDistributionAndStockDifferenceBlock() {
        let r = TXDailySettlement092Policy.evaluate(source(savedProfit: false), date: date)
        XCTAssertNotNil(r.blocker)
        XCTAssertTrue(r.transfers.isEmpty)
        let stock = TXDailySettlement092Policy.evaluate(source(stock: 10), date: date)
        XCTAssertEqual(stock.inventoryDelta, 10)
        XCTAssertNotNil(stock.blocker)
        XCTAssertTrue(stock.transfers.isEmpty)
    }
    func testExistingPaidTransferBlocksRegenerationAndShowsPurchaseRegistration() throws {
        var s = source()
        s.apply([
            event("daily_cash_settlement", "d", ["id": .number(8), "date": .string(date)]),
            event("settlement_partner", "sp", ["date": .string(date), "settlement_id": .number(8),
                "partner_id": .number(1), "partner_name": .string("甲"), "purchase_paid": .number(40),
                "balance": .number(70)]),
            event("settlement_transfer", "t", ["date": .string(date), "settlement_id": .number(8),
                "amount": .number(70), "settled_amount": .number(20),
                "from_partner_id": .number(2), "to_partner_id": .number(1)])
        ])
        XCTAssertNotNil(TXDailySettlement092Policy.evaluate(s, date: date).blocker)
        let progress = try XCTUnwrap(TXDailySettlement092Policy.purchaseProgress(s, date: date)?.first)
        XCTAssertEqual(progress.purchase, 40)
        XCTAssertEqual(progress.registered, 20)
        XCTAssertEqual(progress.remaining, 50)
    }
}
