import XCTest
@testable import TianXianFruit

final class ReadOnlySnapshotTests: XCTestCase {
    private func event(_ table: String, _ id: String, _ version: Int64,
                       _ operation: String, _ fields: [String: JSONValue]) -> SyncEvent {
        SyncEvent(tableName: table, syncId: id, rowVersion: version,
                  operation: operation, payload: fields)
    }

    func testSameEventDoesNotDuplicateSales() {
        var snapshot = ReadOnlySnapshot()
        let sale = event("store_daily_record", "sale-1", 1, "UPSERT", [
            "date": .string("2026-11-18"), "store_name": .string("龙归"),
            "revenue": .number(100), "profit": .number(25)
        ])
        snapshot.apply([sale, sale])
        XCTAssertEqual(snapshot.business.count, 1)
        XCTAssertEqual(snapshot.totalRevenue, 100)
        XCTAssertEqual(snapshot.totalProfit, 25)
    }

    func testDeleteTombstonePreventsOldSalesFromReturning() {
        var snapshot = ReadOnlySnapshot()
        let sale = event("store_daily_record", "sale-1", 2, "UPSERT", [
            "date": .string("2026-11-18"), "revenue": .number(120)
        ])
        let removed = event("store_daily_record", "sale-1", 3, "DELETE", [:])
        snapshot.apply([sale, removed, sale])
        XCTAssertTrue(snapshot.business.isEmpty)
        XCTAssertEqual(snapshot.versions["store_daily_record|sale-1"], 3)
    }

    func testStoreDateSameButDistinctStableIDsAreNotMerged() {
        var snapshot = ReadOnlySnapshot()
        snapshot.apply([
            event("store_daily_record", "a", 1, "UPSERT", [
                "date": .string("2026-11-18"), "store_name": .string("龙归"), "revenue": .number(80)
            ]),
            event("store_daily_record", "b", 1, "UPSERT", [
                "date": .string("2026-11-18"), "store_name": .string("夏茅"), "revenue": .number(90)
            ])
        ])
        XCTAssertEqual(snapshot.business.count, 2)
        XCTAssertEqual(snapshot.totalRevenue, 170)
    }

    func testDeletedNumericFlagIsRespected() {
        var snapshot = ReadOnlySnapshot()
        snapshot.apply([event("purchase_order", "p1", 1, "UPSERT", [
            "date": .string("2026-11-18"), "total_cost": .number(500),
            "deleted": .number(1)
        ])])
        XCTAssertTrue(snapshot.purchases.isEmpty)
    }

    func testUnknownTablesDoNotInflateBusinessTotals() {
        var snapshot = ReadOnlySnapshot()
        snapshot.apply([event("cash_settlement", "s1", 1, "UPSERT", [
            "date": .string("2026-11-18"), "revenue": .number(2000)
        ])])
        XCTAssertEqual(snapshot.totalRevenue, 0)
        XCTAssertEqual(snapshot.totalProfit, 0)
    }
}
