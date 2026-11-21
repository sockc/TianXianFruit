import XCTest
@testable import TianXianFruit

final class SettlementCompactPresentationTests: XCTestCase {
    private func event(_ table: String, _ id: String, date: String,
                       _ payload: [String: JSONValue] = [:]) -> SyncEvent {
        var fields = payload
        fields["date"] = .string(date)
        return SyncEvent(tableName: table, syncId: id, rowVersion: 1,
                         operation: "UPSERT", payload: fields)
    }

    func testMissingSnapshotDoesNotDisplayEstimatedProfit() throws {
        var snapshot = ReadOnlySnapshot()
        snapshot.apply([
            event("store_daily_record", "s1", date: "2026-11-20", [
                "store_name": .string("龙归"),
                "revenue": .number(1500),
                "profit": .number(480)]),
            event("purchase_order", "p1", date: "2026-11-20", [
                "total_cost": .number(500)])
        ])
        let day = try XCTUnwrap(SettlementTablePolicy.rows(snapshot,
            since: "2026-11-20", until: "2026-11-20").first)
        XCTAssertEqual(day.snapshotState, .missing)
        XCTAssertNil(day.uniqueSnapshot, "Must not use store profit as official settlement")
        XCTAssertEqual(day.savedSalesTotal, 1500)
        XCTAssertEqual(day.savedPurchaseTotal, 500)
    }

    func testOneAuthoritativeSnapshotShowsItsOriginalNegativeProfit() throws {
        var snapshot = ReadOnlySnapshot()
        snapshot.apply([
            event("daily_cash_settlement", "d1", date: "2026-11-21", [
                "revenue": .number(1000),
                "purchase_cost": .number(690),
                "expense": .number(25),
                "profit": .number(-58.5),
                "status": .number(0)])
        ])
        let day = try XCTUnwrap(SettlementTablePolicy.rows(snapshot,
            since: "2026-11-21", until: "2026-11-21").first)
        XCTAssertEqual(day.snapshotState, .available)
        XCTAssertEqual(day.uniqueSnapshot?.revenue, 1000)
        XCTAssertEqual(day.uniqueSnapshot?.purchase, 690)
        XCTAssertEqual(day.uniqueSnapshot?.profit, -58.5)
        XCTAssertEqual(day.uniqueSnapshot?.status, 0,
                       "Saved status must not be silently treated as fully settled")
    }

    func testDuplicateSnapshotsRequireExplicitAuditWithoutSummation() throws {
        var snapshot = ReadOnlySnapshot()
        snapshot.apply([
            event("daily_cash_settlement", "a", date: "2026-11-20", [
                "profit": .number(100)]),
            event("daily_cash_settlement", "b", date: "2026-11-20", [
                "profit": .number(75)])
        ])
        let day = try XCTUnwrap(SettlementTablePolicy.rows(snapshot,
            since: "2026-11-20", until: "2026-11-20").first)
        XCTAssertEqual(day.snapshotState, .conflicting(2))
        XCTAssertNil(day.uniqueSnapshot, "Never fabricate a 175 summary")
        XCTAssertEqual(day.savedDaily.count, 2)
    }

    func testPeriodTransferDoesNotBecomeSingleDaySettlement() throws {
        var snapshot = ReadOnlySnapshot()
        snapshot.apply([
            event("settlement_transfer", "t1", date: "2026-11-21", [
                "settlement_kind": .string("CUTOFF"),
                "range_start_date": .string("2026-11-15"),
                "range_end_date": .string("2026-11-21"),
                "amount": .number(450),
                "settled_amount": .number(120)])
        ])
        let today = try XCTUnwrap(SettlementTablePolicy.rows(snapshot,
            since: "2026-11-21", until: "2026-11-21").first)
        XCTAssertTrue(today.includesPeriodTransfers)
        XCTAssertEqual(today.snapshotState, .missing)
        XCTAssertEqual(today.transfers[0].recordedSettled, 120)
        XCTAssertTrue(SettlementTablePolicy.rows(snapshot,
            since: "2026-11-20", until: "2026-11-20").isEmpty)
    }

    func testFilteredDaysKeepWholeBookDailySnapshots() throws {
        var snapshot = ReadOnlySnapshot()
        snapshot.apply([
            event("store_daily_record", "s1", date: "2026-11-20", [
                "store_name": .string("龙归"), "revenue": .number(180)]),
            event("store_daily_record", "s2", date: "2026-11-20", [
                "store_name": .string("夏茅"), "revenue": .number(620)]),
            event("daily_cash_settlement", "d1", date: "2026-11-20", [
                "revenue": .number(800), "profit": .number(250)]),
            event("settlement_partner", "f1", date: "2026-11-20", [
                "partner_name": .string("张"), "balance": .number(-90)])
        ])
        let day = try XCTUnwrap(SettlementTablePolicy.rows(snapshot,
            since: "2026-11-20", until: "2026-11-20",
            location: "龙归", partner: "张").first)
        XCTAssertEqual(day.sales.count, 1)
        XCTAssertEqual(day.savedSalesTotal, 180)
        XCTAssertEqual(day.uniqueSnapshot?.revenue, 800,
                       "Daily snapshot remains whole-book, never reallocated to 龙归")
        XCTAssertEqual(day.funds[0].balance, -90)
    }
}
