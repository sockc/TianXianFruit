import Foundation
import XCTest
@testable import TianXianFruit

final class LocalLedgerCacheTests: XCTestCase {
    private func withCache(_ run: (LocalLedgerCache) throws -> Void) throws {
        let folder = FileManager.default.temporaryDirectory
            .appendingPathComponent("tianxian-ios-test-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let cache = try LocalLedgerCache(url: folder.appendingPathComponent("cache.sqlite"))
        try run(cache)
    }

    private func event(_ table: String, _ id: String, _ version: Int64,
                       _ operation: String = "UPSERT",
                       _ fields: [String: JSONValue] = [:]) -> SyncEvent {
        SyncEvent(tableName: table, syncId: id, rowVersion: version,
                  operation: operation, payload: fields)
    }

    func testOfflineCacheReopensAndKeepsIncrementalCursor() throws {
        try withCache { cache in
            let sale = event("store_daily_record", "sale-1", 1, "UPSERT", [
                "date": .string("2026-11-18"),
                "store_name": .string("龙归"),
                "revenue": .number(900),
                "profit": .number(400)
            ])
            try cache.commit(account: "Owner", book: "b1", events: [sale],
                             cursor: 10, replace: true)
            let first = try XCTUnwrap(cache.load(account: "owner", book: "b1"))
            XCTAssertEqual(first.cursor, 10)
            XCTAssertEqual(first.snapshot.totalRevenue, 900)
            XCTAssertEqual(first.snapshot.totalProfit, 400)
            let purchase = event("purchase_order", "po-1", 2, "UPSERT", [
                "date": .string("2026-11-18"),
                "buyer_name": .string("采购A"),
                "total_cost": .number(520)
            ])
            try cache.commit(account: "OWNER", book: "b1",
                             events: [purchase], cursor: 16, replace: false)
            let second = try XCTUnwrap(cache.load(account: "owner", book: "b1"))
            XCTAssertEqual(second.cursor, 16)
            XCTAssertEqual(second.snapshot.totalRevenue, 900)
            XCTAssertEqual(second.snapshot.totalPurchases, 520)
        }
    }

    func testDeletionTombstoneAndOldReplayAfterRestart() throws {
        try withCache { cache in
            let original = event("store_daily_record", "s1", 3, "UPSERT", [
                "date": .string("2026-11-18"), "revenue": .number(300)
            ])
            let delete = event("store_daily_record", "s1", 4, "DELETE")
            try cache.commit(account: "a", book: "b", events: [original],
                             cursor: 3, replace: true)
            try cache.commit(account: "a", book: "b", events: [delete],
                             cursor: 4, replace: false)
            try cache.commit(account: "a", book: "b", events: [original],
                             cursor: 5, replace: false)
            let loaded = try XCTUnwrap(cache.load(account: "a", book: "b"))
            XCTAssertEqual(loaded.cursor, 5)
            XCTAssertEqual(loaded.snapshot.versions["store_daily_record|s1"], 4)
            XCTAssertEqual(loaded.snapshot.totalRevenue, 0)
        }
    }

    func testDifferentBooksAndAccountsNeverMix() throws {
        try withCache { cache in
            try cache.commit(account: "owner", book: "one", events: [
                event("store_daily_record", "s", 1, "UPSERT", [
                    "date": .string("2026-11-18"), "revenue": .number(111)
                ])
            ], cursor: 1, replace: true)
            try cache.commit(account: "owner", book: "two", events: [
                event("store_daily_record", "s", 1, "UPSERT", [
                    "date": .string("2026-11-18"), "revenue": .number(222)
                ])
            ], cursor: 2, replace: true)
            try cache.commit(account: "family", book: "one", events: [
                event("store_daily_record", "s", 1, "UPSERT", [
                    "date": .string("2026-11-18"), "revenue": .number(333)
                ])
            ], cursor: 3, replace: true)
            XCTAssertEqual(try cache.load(account: "owner", book: "one")?.snapshot.totalRevenue, 111)
            XCTAssertEqual(try cache.load(account: "owner", book: "two")?.snapshot.totalRevenue, 222)
            XCTAssertEqual(try cache.load(account: "family", book: "one")?.snapshot.totalRevenue, 333)
            try cache.erase(account: "owner")
            XCTAssertNil(try cache.load(account: "owner", book: "one"))
            XCTAssertNotNil(try cache.load(account: "family", book: "one"))
        }
    }

    func testInvalidEventsCannotPartiallyOverwriteRowsOrCursor() throws {
        try withCache { cache in
            try cache.commit(account: "a", book: "b", events: [
                event("store_daily_record", "s1", 1, "UPSERT", [
                    "date": .string("2026-11-18"), "revenue": .number(12)
                ])
            ], cursor: 5, replace: true)
            XCTAssertThrowsError(try cache.commit(account: "a", book: "b", events: [
                event("store_daily_record", "s1", 2, "UPSERT", [
                    "date": .string("2026-11-18"), "revenue": .number(9999)
                ]),
                event("store_daily_record", "", -1)
            ], cursor: 6, replace: false))
            let saved = try XCTUnwrap(cache.load(account: "a", book: "b"))
            XCTAssertEqual(saved.cursor, 5)
            XCTAssertEqual(saved.snapshot.totalRevenue, 12)
        }
    }

    func testEmptyCloudBaselineIsStillMarkedSynced() throws {
        try withCache { cache in
            try cache.commit(account: "owner", book: "empty", events: [],
                             cursor: 0, replace: true)
            let cache = try XCTUnwrap(cache.load(account: "owner", book: "empty"))
            XCTAssertEqual(cache.snapshot.business.count, 0)
            XCTAssertEqual(cache.cursor, 0)
        }
    }

    func testAndroidWireFieldsAndSameDateTwoLocations() throws {
        let json = """
        {"events":[
          {"table_name":"store_daily_record","sync_id":"s1","row_version":1,"operation":"UPSERT",
           "payload":{"date":"2026-11-18","store_name":"龙归","revenue":120,"profit":40}},
          {"table_name":"store_daily_record","sync_id":"s2","row_version":1,"operation":"UPSERT",
           "payload":{"date":"2026-11-18","store_name":"夏茅","revenue":190,"profit":70}},
          {"table_name":"inventory_snapshot","sync_id":"i1","row_version":1,"operation":"UPSERT",
           "payload":{"date":"2026-11-17","fruit_name":"提子","remaining_quantity":2.5,
                      "loss_quantity":0.5,"unit":"箱"}}
        ],"next_cursor":8,"has_more":false}
        """
        let page = try JSONDecoder().decode(SyncPage.self, from: Data(json.utf8))
        XCTAssertEqual(page.nextCursor, 8)
        XCTAssertFalse(page.hasMore)
        var data = ReadOnlySnapshot()
        data.apply(page.events)
        XCTAssertEqual(data.business.count, 2)
        XCTAssertEqual(data.sales(since: "2026-11-18", until: "2026-11-18").count, 2)
        XCTAssertEqual(data.totalRevenue, 310)
        XCTAssertEqual(data.totalProfit, 110)
        XCTAssertEqual(data.latestInventory.first?.remaining, 2.5)
        XCTAssertEqual(data.latestInventory.first?.loss, 0.5)
    }
}
