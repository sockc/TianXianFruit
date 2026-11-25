import XCTest
@testable import TianXianFruit

final class TXRecentLedgerHistoryTests: XCTestCase {
    private func business(_ date: String, _ id: String,
                          store: String = "龙归") -> StoreDailyRow {
        let event = SyncEvent(
            tableName: "store_daily_record", syncId: id,
            rowVersion: 1, operation: "UPSERT",
            payload: [
                "date": .string(date),
                "store_name": .string(store),
                "revenue": .number(100),
                "profit": .number(30)
            ])
        return StoreDailyRow(event)!
    }

    private func purchase(_ date: String, _ id: String,
                          buyer: String = "采购人A") -> PurchaseOrderRow {
        let event = SyncEvent(
            tableName: "purchase_order", syncId: id,
            rowVersion: 1, operation: "UPSERT",
            payload: [
                "date": .string(date),
                "buyer_name": .string(buyer),
                "total_cost": .number(80)
            ])
        return PurchaseOrderRow(event)!
    }

    func testBusinessRecentSevenAreActualDaysExcludingSelectedAndFuture() {
        let rows = [
            business("2026-11-25", "today-a"),
            business("2026-11-25", "today-b"),
            business("2026-11-26", "future"),
            business("2026-11-24", "d24-a"),
            business("2026-11-24", "d24-b", store: "夏茅"),
            business("2026-11-22", "d22"),
            business("2026-11-19", "d19"),
            business("2026-11-18", "d18"),
            business("2026-11-15", "d15"),
            business("2026-11-11", "d11"),
            business("2026-11-10", "d10"),
            business("2026-11-02", "too-old")
        ]
        let groups = TXRecentLedgerHistoryPolicy.previousDays(
            rows, before: "2026-11-25", date: { $0.date })
        XCTAssertEqual(groups.map(\.date),
            ["2026-11-24", "2026-11-22", "2026-11-19",
             "2026-11-18", "2026-11-15", "2026-11-11", "2026-11-10"])
        XCTAssertEqual(groups.first?.rows.map(\.id).sorted(),
                       ["d24-a", "d24-b"])
        XCTAssertEqual(groups.first?.rows.reduce(0) { $0 + $1.revenue }, 200)
        XCTAssertFalse(groups.contains { $0.date == "2026-11-25" })
    }

    func testPurchaseDayPlusSevenPreviousPurchaseDaysNotCalendarDays() {
        let rows = [
            purchase("2026-11-25", "selected"),
            purchase("2026-11-23", "a"),
            purchase("2026-11-23", "b", buyer: "采购人B"),
            purchase("2026-11-21", "c"),
            purchase("2026-11-18", "d"),
            purchase("2026-11-14", "e"),
            purchase("2026-11-09", "f"),
            purchase("2026-11-03", "g"),
            purchase("2026-10-29", "h"),
            purchase("2026-09-01", "too-old"),
            purchase("2026-11-26", "future")
        ]
        let groups = TXRecentLedgerHistoryPolicy.previousDays(
            rows, before: "2026-11-25", date: { $0.date })
        XCTAssertEqual(groups.map(\.date),
            ["2026-11-23", "2026-11-21", "2026-11-18",
             "2026-11-14", "2026-11-09", "2026-11-03", "2026-10-29"])
        XCTAssertEqual(groups.first?.rows.count, 2)
        XCTAssertEqual(groups.first?.rows.reduce(0) { $0 + $1.totalCost }, 160)
        XCTAssertEqual(rows.filter { $0.date == "2026-11-25" }.count, 1,
                       "The selected day's orders stay in the existing top section")
    }

    func testEmptyAndEarlyHistoryNeverInventsDays() {
        let rows = [business("2026-11-23", "one")]
        XCTAssertEqual(TXRecentLedgerHistoryPolicy.previousDays(
            rows, before: "2026-11-25", date: { $0.date }).map(\.date),
            ["2026-11-23"])
        XCTAssertTrue(TXRecentLedgerHistoryPolicy.previousDays(
            rows, before: "2026-11-20", date: { $0.date }).isEmpty)
        XCTAssertTrue(TXRecentLedgerHistoryPolicy.previousDays(
            rows, before: "2026-11-25", limit: 0,
            date: { $0.date }).isEmpty)
        XCTAssertTrue(TXRecentLedgerHistoryPolicy.previousDays(
            rows, before: "invalid", date: { $0.date }).isEmpty)
    }

    func testChineseWeekdayUsesBusinessTimeZone() {
        XCTAssertEqual(TXRecentLedgerHistoryPolicy.weekdayLabel("2026-11-25"),
                       "11-25 · 周三")
    }
}
