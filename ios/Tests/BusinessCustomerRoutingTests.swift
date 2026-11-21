import XCTest
@testable import TianXianFruit

final class BusinessCustomerRoutingTests: XCTestCase {
    private let date = "2026-11-18"
    private let storeOriginal: Int64 = 79152283000000001

    private func event(_ table: String, _ key: String,
                       _ payload: [String: JSONValue]) -> SyncEvent {
        SyncEvent(tableName: table, syncId: key, rowVersion: 2,
                  operation: "UPSERT", payload: payload)
    }
    private func snapshot(sale: [String: JSONValue],
                          stores: [SyncEvent] = []) -> ReadOnlySnapshot {
        var ledger = ReadOnlySnapshot()
        var data = sale
        data["date"] = .string(date)
        ledger.apply([
            event("store_daily_record", "sale", data)
        ] + stores)
        return ledger
    }
    private var store: SyncEvent {
        event("store", "store-sync", [
            "id": .exactInteger(storeOriginal),
            "name": .string("龙归南村")
        ])
    }

    func testOriginalAndroid64BitKeyResolvesExactStall() {
        let ledger = snapshot(sale: [
            "store_id": .exactInteger(storeOriginal),
            "store_name": .string("龙归南村")
        ], stores: [store])
        let route = TXBusinessCustomerRouting.route(recordSyncID: "sale",
            ledger: ledger, customer: CustomerReadSnapshot())
        XCTAssertTrue(route.hasVerifiedStore)
        XCTAssertEqual(route.storeSyncID, "store-sync")
        XCTAssertEqual(route.date, date)
    }

    func testExplicitStoreSyncIDWithStrongSourceRelationship() {
        let ledger = snapshot(sale: [
            "store_sync_id": .string("store-sync")
        ], stores: [store])
        let route = TXBusinessCustomerRouting.route(recordSyncID: "sale",
            ledger: ledger, customer: CustomerReadSnapshot())
        XCTAssertEqual(route.storeSyncID, "store-sync")
    }

    func testAmbiguousSameNameWithoutSourceIDFallsBackToDayOnly() {
        let other = event("store", "other-sync", [
            "id": .exactInteger(79152283000000002),
            "name": .string("龙归南村")
        ])
        let ledger = snapshot(sale: [
            "store_name": .string("龙归南村")
        ], stores: [store, other])
        let route = TXBusinessCustomerRouting.route(recordSyncID: "sale",
            ledger: ledger, customer: CustomerReadSnapshot())
        XCTAssertFalse(route.hasVerifiedStore)
        XCTAssertEqual(route.storeSyncID, "")
        XCTAssertEqual(route.date, date)
        XCTAssertTrue(route.notice.contains("无法"))
    }

    func testContradictoryForeignKeysCannotAutoAssignCustomerTraffic() {
        let ledger = snapshot(sale: [
            "store_sync_id": .string("other-sync"),
            "store_id": .exactInteger(storeOriginal)
        ], stores: [store, event("store", "other-sync", [
            "id": .exactInteger(79152283000000002)])])
        let route = TXBusinessCustomerRouting.route(recordSyncID: "sale",
            ledger: ledger, customer: CustomerReadSnapshot())
        XCTAssertFalse(route.hasVerifiedStore)
        XCTAssertTrue(route.notice.contains("冲突"))
    }

    func testImpreciseFloatingAndroidIdNotAccepted() {
        let ledger = snapshot(sale: [
            "store_id": .number(Double(storeOriginal)),
            "store_name": .string("龙归南村")
        ], stores: [store])
        let route = TXBusinessCustomerRouting.route(recordSyncID: "sale",
            ledger: ledger, customer: CustomerReadSnapshot())
        XCTAssertFalse(route.hasVerifiedStore)
    }

    func testMissingBusinessRecordNeverGuessStall() {
        let route = TXBusinessCustomerRouting.route(recordSyncID: "unknown",
            ledger: ReadOnlySnapshot(), customer: CustomerReadSnapshot())
        XCTAssertFalse(route.hasVerifiedStore)
    }

    func testCustomerReceiptsDoNotMakeUndocumentedStoreNameMatchSafe() {
        var customers = CustomerReadSnapshot()
        customers.apply([CustomerChangeEvent(
            kind: "RECEIPT", key: "WECHAT|abc", revision: 1, payload: [
                "platform": .string("WECHAT"), "dedupe_key": .string("abc"),
                "business_date": .string(date),
                "trade_time": .string("2026-11-18 18:00:00"),
                "amount": .number(20), "net_amount": .number(20),
                "store_sync_id": .string("store-sync")])])
        let ledger = snapshot(sale: ["store_name": .string("龙归南村")])
        XCTAssertFalse(TXBusinessCustomerRouting.route(
            recordSyncID: "sale", ledger: ledger, customer: customers).hasVerifiedStore)
    }
}
