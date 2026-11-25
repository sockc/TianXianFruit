import XCTest
@testable import TianXianFruit

final class TXWorkDayPolicyTests: XCTestCase {
    func testOperatingDateUsesShanghaiCalendarAcrossMonthBoundary() throws {
        let day = try XCTUnwrap(BusinessDates.parse("2026-11-30"))
        let now = try XCTUnwrap(BusinessDates.parse("2026-12-02"))
        XCTAssertEqual(BusinessDates.key(TXWorkDayPolicy.shifted(day, days: 1, allowFuture: false, now: now)), "2026-12-01")
        XCTAssertEqual(BusinessDates.key(TXWorkDayPolicy.shifted(day, days: -1, allowFuture: false, now: now)), "2026-11-29")
    }

    func testFuturePurchaseAllowedButBusinessDateNotAdvanced() throws {
        let today = try XCTUnwrap(BusinessDates.parse("2026-11-25"))
        let next = TXWorkDayPolicy.shifted(today, days: 1, allowFuture: true, now: today)
        XCTAssertEqual(BusinessDates.key(next), "2026-11-26")
        XCTAssertEqual(TXWorkDayPolicy.shifted(today, days: 1, allowFuture: false, now: today), today)
        XCTAssertEqual(TXWorkDayPolicy.clamped(next, allowFuture: false, now: today), today)
    }

    func testHorizontalSwipesIgnoreVerticalScrollAndSmallMovements() {
        XCTAssertEqual(TXWorkDayPolicy.swipeDays(horizontal: -75, vertical: 8), 1)
        XCTAssertEqual(TXWorkDayPolicy.swipeDays(horizontal: 75, vertical: 8), -1)
        XCTAssertEqual(TXWorkDayPolicy.swipeDays(horizontal: 75, vertical: 80), 0)
        XCTAssertEqual(TXWorkDayPolicy.swipeDays(horizontal: 15, vertical: 0), 0)
    }
}
