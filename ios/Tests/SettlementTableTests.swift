import XCTest
@testable import TianXianFruit

final class SettlementTableTests: XCTestCase {
    private func row(_ table: String, _ id: String, _ date: String,
                     _ fields: [String: JSONValue] = [:],
                     version: Int64 = 1, operation: String = "UPSERT") -> SyncEvent {
        var p = fields
        p["date"] = .string(date)
        return SyncEvent(tableName: table, syncId: id, rowVersion: version,
                         operation: operation, payload: p)
    }

    func testTwoLocationsSameDayRemainSeparateAndNoDerivedSettlement() {
        var snapshot = ReadOnlySnapshot()
        snapshot.apply([
            row("store_daily_record", "s1", "2026-11-20", [
                "store_name": .string("龙归"), "revenue": .number(1200),
                "profit": .number(420)]),
            row("store_daily_record", "s2", "2026-11-20", [
                "store_name": .string("夏茅"), "revenue": .number(800),
                "profit": .number(270)]),
            row("purchase_order", "p1", "2026-11-20", [
                "buyer_name": .string("采购员"), "total_cost": .number(620)])
        ])
        let days = SettlementTablePolicy.rows(snapshot, since: "2026-11-20",
                                              until: "2026-11-20")
        XCTAssertEqual(days.count, 1)
        XCTAssertEqual(days[0].sales.count, 2)
        XCTAssertEqual(days[0].savedSalesTotal, 2000)
        XCTAssertEqual(days[0].savedStoreProfitTotal, 690)
        XCTAssertEqual(days[0].savedPurchaseTotal, 620)
        XCTAssertTrue(days[0].savedDaily.isEmpty,
                      "Do not fabricate a financial settlement from store profits")
        XCTAssertTrue(days[0].shares.isEmpty)
        XCTAssertTrue(days[0].transfers.isEmpty)
    }

    func testFinanceSnapshotsAreCopiedNotRecalculated() {
        var snapshot = ReadOnlySnapshot()
        snapshot.apply([
            row("daily_cash_settlement", "f1", "2026-11-19", [
                "revenue": .number(950), "purchase_cost": .number(410),
                "profit": .number(-28)]),
            row("profit_distribution", "a1", "2026-11-19", [
                "partner_name": .string("张"), "allocated_profit": .number(-15)]),
            row("settlement_transfer", "t1", "2026-11-19", [
                "from_partner_name": .string("李"),
                "to_partner_name": .string("张"),
                "amount": .number(50), "settled_amount": .number(15)])
        ])
        let day = SettlementTablePolicy.rows(snapshot, since: "2026-11-19",
                                             until: "2026-11-19")[0]
        XCTAssertNil(day.savedSalesTotal)
        XCTAssertNil(day.savedPurchaseTotal)
        XCTAssertEqual(day.savedDaily[0].profit, -28)
        XCTAssertEqual(day.shares[0].allocated, -15)
        XCTAssertEqual(day.transfers[0].recordedSettled, 15)
    }

    func testFiltersDoNotMisattributeWholeBookFinancialData() {
        var snapshot = ReadOnlySnapshot()
        snapshot.apply([
            row("store_daily_record", "s1", "2026-11-20", [
                "store_name": .string("龙归"), "revenue": .number(100)]),
            row("store_daily_record", "s2", "2026-11-20", [
                "store_name": .string("夏茅"), "revenue": .number(900)]),
            row("store_daily_record", "s3", "2026-11-19", [
                "store_name": .string("夏茅"), "revenue": .number(500)]),
            row("purchase_order", "p1", "2026-11-20", [
                "total_cost": .number(430)]),
            row("daily_cash_settlement", "f1", "2026-11-20", [
                "profit": .number(70)]),
            row("profit_distribution", "a1", "2026-11-20", [
                "partner_name": .string("张"), "allocated_profit": .number(30)]),
            row("profit_distribution", "a2", "2026-11-20", [
                "partner_name": .string("李"), "allocated_profit": .number(40)])
        ])
        let days = SettlementTablePolicy.rows(snapshot, since: "2026-11-18",
                                              until: "2026-11-21",
                                              location: "龙归", partner: "张")
        XCTAssertEqual(days.count, 1)
        XCTAssertEqual(days[0].sales.count, 1)
        XCTAssertEqual(days[0].savedSalesTotal, 100)
        XCTAssertEqual(days[0].savedPurchaseTotal, 430,
                       "Purchase remains whole-book, not attributed to a store")
        XCTAssertEqual(days[0].savedDaily[0].profit, 70)
        XCTAssertEqual(days[0].shares.count, 1)
        XCTAssertEqual(days[0].shares[0].partner, "张")
    }

    func testReplayTombstonesAndDateBoundary() {
        var snapshot = ReadOnlySnapshot()
        let old = row("settlement_transfer", "t1", "2026-11-20", [
            "amount": .number(88)])
        snapshot.apply([old, old])
        XCTAssertEqual(SettlementTablePolicy.rows(snapshot, since: "2026-11-20",
                                                  until: "2026-11-20").count, 1)
        snapshot.apply([
            row("settlement_transfer", "t1", "2026-11-20", version: 2,
                operation: "DELETE"),
            old
        ])
        XCTAssertTrue(SettlementTablePolicy.rows(snapshot, since: "2026-11-20",
                                                 until: "2026-11-20").isEmpty)
    }
}
