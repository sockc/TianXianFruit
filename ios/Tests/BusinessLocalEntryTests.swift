import XCTest
@testable import TianXianFruit

final class BusinessLocalEntryTests: XCTestCase {
    func testDraftIsScopedByAccountAndBookAndReplacesSameDayStore() throws {
        let account = "ios-local-" + UUID().uuidString
        let firstBook = UUID().uuidString
        let secondBook = UUID().uuidString
        let line = BusinessCollectorDraftLine(
            partnerSyncID: "partner-1", wechat: "123.45", alipay: "0", cash: "10")
        let row = BusinessLocalDraft(
            id: UUID().uuidString, date: "2026-11-21",
            storeID: "store-1", storeName: "位置A", collectors: [line],
            startTime: "16:00", endTime: "00:10", note: "only local", updatedAt: Date())
        try BusinessLocalDraftStore.save(row, account: account, bookID: firstBook)
        XCTAssertEqual(BusinessLocalDraftStore.load(account: account, bookID: firstBook).count, 1)
        XCTAssertTrue(BusinessLocalDraftStore.load(account: account, bookID: secondBook).isEmpty)
        XCTAssertTrue(BusinessLocalDraftStore.load(account: "other", bookID: firstBook).isEmpty)
        var updated = row
        updated.note = "updated"
        try BusinessLocalDraftStore.save(updated, account: account, bookID: firstBook)
        let rows = BusinessLocalDraftStore.load(account: account, bookID: firstBook)
        XCTAssertEqual(rows.count, 1)
        XCTAssertEqual(rows.first?.note, "updated")
        XCTAssertEqual(rows.first?.collectors.first?.wechat, "123.45")
    }

    func testCollectorTotalsAndOvernightMetadataRemainIndependent() throws {
        let lines = [
            BusinessCollectorDraftLine(partnerSyncID: "a", wechat: "12.50", cash: "3"),
            BusinessCollectorDraftLine(partnerSyncID: "b", alipay: "20.25")
        ]
        let totals = try BusinessCollectorDraftPolicy.totals(lines)
        XCTAssertEqual(totals.wechat, 1250)
        XCTAssertEqual(totals.alipay, 2025)
        XCTAssertEqual(totals.cash, 300)
        try BusinessCollectorDraftPolicy.validateTimes([
            "actual_start_time": "16:00", "actual_end_time": "00:10"])
        XCTAssertThrowsError(try BusinessCollectorDraftPolicy.validateTimes([
            "actual_start_time": "16:00", "actual_end_time": "16:00"]))
    }
}
