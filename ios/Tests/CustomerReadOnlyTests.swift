import XCTest
@testable import TianXianFruit

final class CustomerReadOnlyTests: XCTestCase {
    private func receipt(_ id: String, date: String, platform: String = "WECHAT",
                         amount: Double = 16, time: String = "2026-11-18 18:30:00",
                         location: String = "", ref: String = "") -> CustomerChangeEvent {
        let dedupe = id
        return CustomerChangeEvent(kind: "RECEIPT", key: platform + "|" + dedupe,
            revision: 1, payload: [
                "platform": .string(platform), "dedupe_key": .string(dedupe),
                "business_date": .string(date), "trade_time": .string(time),
                "amount": .number(amount), "net_amount": .number(amount),
                "store_sync_id": .string(location), "transaction_ref": .string(ref)
            ])
    }

    private func event(_ kind: String, _ key: String, _ body: [String: JSONValue],
                       _ revision: Int64 = 1) -> CustomerChangeEvent {
        CustomerChangeEvent(kind: kind, key: key, revision: revision, payload: body)
    }

    func testCustomerWireJSONAndIdempotentRepeatedImports() throws {
        let json = """
        {"changes":[
          {"kind":"RECEIPT","key":"ALIPAY|tx-1","revision":4,"payload":{
            "platform":"ALIPAY","dedupe_key":"tx-1",
            "business_date":"2026-11-18","trade_time":"2026-11-18 18:30:00",
            "amount":20.5,"net_amount":20.5,"store_sync_id":""}}
        ],"next_cursor":123,"has_more":false}
        """
        let page = try JSONDecoder().decode(CustomerChangePage.self, from: Data(json.utf8))
        XCTAssertEqual(page.nextCursor, 123)
        var snapshot = CustomerReadSnapshot()
        snapshot.apply(page.changes + page.changes)
        XCTAssertEqual(snapshot.receipts.count, 1)
        XCTAssertEqual(snapshot.summary(snapshot.receipts).amount, 20.5)
        XCTAssertEqual(snapshot.hourly(snapshot.receipts)[18].paymentCount, 1)
    }

    func testLargePaymentSettingsManualExclusionAndIncludes() {
        var snapshot = CustomerReadSnapshot()
        snapshot.apply([
            receipt("small", date: "2026-11-18", amount: 23),
            receipt("large", date: "2026-11-18", amount: 2000),
            receipt("refund", date: "2026-11-18", amount: -15),
            event("SETTINGS", "global", ["exclude_large_payments": .bool(true),
                                           "large_payment_threshold": .number(2000)])
        ])
        XCTAssertEqual(snapshot.receipts.map(\.id), ["WECHAT|small"])
        snapshot.apply([
            event("ANALYSIS", "WECHAT|large", ["platform": .string("WECHAT"),
                "dedupe_key": .string("large"), "state": .string("INCLUDED")]),
            event("ANALYSIS", "WECHAT|small", ["platform": .string("WECHAT"),
                "dedupe_key": .string("small"), "state": .string("EXCLUDED")])
        ])
        XCTAssertEqual(snapshot.receipts.map(\.id), ["WECHAT|large"])
    }

    func testSameTransactionRefExclusionAvoidsDoubleCounting() {
        var snapshot = CustomerReadSnapshot()
        snapshot.apply([
            receipt("a", date: "2026-11-18", amount: 2000, ref: "shared-ref"),
            receipt("b", date: "2026-11-18", amount: 25, ref: "shared-ref"),
            receipt("c", date: "2026-11-18", amount: 12, ref: "different")
        ])
        XCTAssertEqual(snapshot.receipts.map(\.id), ["WECHAT|c"])
    }

    func testDifferentStoresAndUnmatchedNotAttributedTwice() {
        var snapshot = CustomerReadSnapshot()
        snapshot.apply([
            receipt("one", date: "2026-11-18", location: "store-one"),
            receipt("two", date: "2026-11-18", location: "store-two"),
            receipt("unmatched", date: "2026-11-18", location: "")
        ])
        XCTAssertEqual(snapshot.selected(dateStart: "2026-11-18", dateEnd: "2026-11-18", store: "store-one").count, 1)
        XCTAssertEqual(snapshot.selected(dateStart: "2026-11-18", dateEnd: "2026-11-18", store: "store-two").count, 1)
        XCTAssertEqual(snapshot.selected(dateStart: "2026-11-18", dateEnd: "2026-11-18", store: "__unmatched__").count, 1)
        XCTAssertEqual(snapshot.unmatchedCount, 1)
    }

    func testSameWeekdayOnlyObservedDaysNoZeroImputation() {
        var snapshot = CustomerReadSnapshot()
        snapshot.apply([
            receipt("old-1", date: "2026-11-04", location: "store-a"),
            receipt("old-2", date: "2026-11-11", location: "store-a"),
            receipt("current", date: "2026-11-18", location: "store-a")
        ])
        let v = snapshot.compareSameWeekday(date: "2026-11-18",
                                            platform: "", store: "store-a")
        XCTAssertEqual(v?.historicalDays, 2)
        XCTAssertEqual(v?.current.paymentCount, 1)
        XCTAssertEqual(v?.priorDayAverageCount, 1)
        XCTAssertNil(snapshot.compareSameWeekday(date: "2026-11-18",
                                                  platform: "ALIPAY", store: "store-a"))
    }

    func testHourlySameWeekdayUsesObservedHistoricDatesOnly() throws {
        var snapshot = CustomerReadSnapshot()
        snapshot.apply([
            receipt("old1", date: "2026-11-04",
                    time: "2026-11-04 18:10:00", location: "stall-a"),
            receipt("old2", date: "2026-11-11",
                    time: "2026-11-11 18:11:00", location: "stall-a"),
            receipt("old3", date: "2026-11-11",
                    time: "2026-11-11 18:20:00", location: "stall-a"),
            receipt("today", date: "2026-11-18",
                    time: "2026-11-18 18:22:00", location: "stall-a"),
            receipt("other-stall", date: "2026-11-18",
                    time: "2026-11-18 18:23:00", location: "stall-b")
        ])
        let comparison = try XCTUnwrap(snapshot.hourlySameWeekday(
            date: "2026-11-18", platform: "", store: "stall-a"))
        XCTAssertEqual(comparison.historicalDays, 2)
        XCTAssertEqual(comparison.hours.count, 24)
        XCTAssertEqual(comparison.hours[18].currentCount, 1)
        XCTAssertEqual(comparison.hours[18].previousDailyCount, 1.5)
        XCTAssertEqual(comparison.hours[18].countDifference, -0.5)
        XCTAssertEqual(comparison.hours[17].previousDailyCount, 0)
    }

    func testHourlyComparisonRejectsUnobservedCurrentAndInsufficientHistory() {
        var snapshot = CustomerReadSnapshot()
        snapshot.apply([
            receipt("old1", date: "2026-11-04", location: "stall-a"),
            receipt("old2", date: "2026-11-11", location: "stall-a")
        ])
        XCTAssertNil(snapshot.hourlySameWeekday(
            date: "2026-11-18", platform: "", store: "stall-a"))
        XCTAssertNil(snapshot.hourlySameWeekday(
            date: "2026-11-18", platform: "", store: ""))
        snapshot.apply([receipt("today", date: "2026-11-18",
                                location: "stall-a")])
        XCTAssertNotNil(snapshot.hourlySameWeekday(
            date: "2026-11-18", platform: "", store: "stall-a"))
        XCTAssertNil(snapshot.hourlySameWeekday(
            date: "2026-11-18", platform: "ALIPAY", store: "stall-a"))
    }

    func testAccountBookScopedCustomerCacheAndRevisionTombstones() throws {
        let folder = FileManager.default.temporaryDirectory
            .appendingPathComponent("tianxian-customer-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let cache = try LocalLedgerCache(url: folder.appendingPathComponent("cache.sqlite"))
        let original = receipt("r1", date: "2026-11-18")
        try cache.commitCustomer(account: "owner", book: "one", events: [original],
                                 cursor: 42, replace: true)
        XCTAssertNil(try cache.loadCustomer(account: "owner", book: "other"))
        XCTAssertNil(try cache.loadCustomer(account: "other", book: "one"))
        XCTAssertEqual(try cache.loadCustomer(account: "owner", book: "one")?.cursor, 42)
        let deleted = event("RECEIPT", "WECHAT|r1", ["deleted": .bool(true)], 2)
        try cache.commitCustomer(account: "owner", book: "one", events: [deleted],
                                 cursor: 45, replace: false)
        try cache.commitCustomer(account: "owner", book: "one", events: [original],
                                 cursor: 46, replace: false)
        let final = try XCTUnwrap(cache.loadCustomer(account: "owner", book: "one"))
        XCTAssertEqual(final.cursor, 46)
        XCTAssertTrue(final.snapshot.receipts.isEmpty)
    }

    func testCustomerTransactionFailurePreservesCommittedCursor() throws {
        let folder = FileManager.default.temporaryDirectory
            .appendingPathComponent("tianxian-customer-rollback-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let cache = try LocalLedgerCache(url: folder.appendingPathComponent("cache.sqlite"))
        try cache.commitCustomer(account: "a", book: "b", events: [
            receipt("old", date: "2026-11-18")], cursor: 3, replace: true)
        let bad = event("RECEIPT", "", [:], 1)
        XCTAssertThrowsError(try cache.commitCustomer(account: "a", book: "b",
            events: [receipt("new", date: "2026-11-18"), bad],
            cursor: 5, replace: false))
        let current = try XCTUnwrap(cache.loadCustomer(account: "a", book: "b"))
        XCTAssertEqual(current.cursor, 3)
        XCTAssertEqual(current.snapshot.receipts.count, 1)
    }

    func testFinancesArePersistedCloudValuesNotDerivedFromRevenue() {
        var business = ReadOnlySnapshot()
        business.apply([
            SyncEvent(tableName: "store_daily_record", syncId: "sale",
                rowVersion: 1, operation: "UPSERT", payload: [
                    "date": .string("2026-11-18"), "revenue": .number(20000),
                    "profit": .number(10000)]),
            SyncEvent(tableName: "profit_distribution", syncId: "share",
                rowVersion: 1, operation: "UPSERT", payload: [
                    "date": .string("2026-11-18"), "partner_name": .string("合伙人A"),
                    "allocated_profit": .number(1234)]),
            SyncEvent(tableName: "settlement_transfer", syncId: "transfer",
                rowVersion: 1, operation: "UPSERT", payload: [
                    "date": .string("2026-11-18"), "from_partner_name": .string("合伙人A"),
                    "to_partner_name": .string("合伙人B"), "amount": .number(500),
                    "settled_amount": .number(200)])
        ])
        XCTAssertEqual(business.financeProfitRows.first?.allocated, 1234)
        XCTAssertEqual(business.financeTransfers.first?.amount, 500)
        XCTAssertEqual(business.financeTransfers.first?.recordedSettled, 200)
    }
    func testOvernightCoverageIsDurableScopedAndClearedWithOldProtocol() throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let url = folder.appendingPathComponent("coverage.sqlite")
        let cache = try LocalLedgerCache(url: url)
        try cache.commitCustomer(account: "owner", book: "one", events: [], cursor: 20, replace: true)
        XCTAssertFalse(try XCTUnwrap(cache.loadCustomer(account: "owner", book: "one")).overnightCovered)
        try cache.commitCustomer(account: "owner", book: "one", events: [], cursor: 30, replace: true, overnightCovered: true)
        let reopened = try LocalLedgerCache(url: url)
        XCTAssertTrue(try XCTUnwrap(reopened.loadCustomer(account: "owner", book: "one")).overnightCovered)
        XCTAssertNil(try reopened.loadCustomer(account: "other", book: "one"))
        try reopened.commitCustomer(account: "owner", book: "two", events: [], cursor: 30, replace: true)
        XCTAssertFalse(try XCTUnwrap(reopened.loadCustomer(account: "owner", book: "two")).overnightCovered)
        try reopened.commitCustomer(account: "owner", book: "one", events: [], cursor: 31, replace: false)
        XCTAssertFalse(try XCTUnwrap(reopened.loadCustomer(account: "owner", book: "one")).overnightCovered)
    }

}
