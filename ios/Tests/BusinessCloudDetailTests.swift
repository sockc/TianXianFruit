import XCTest
@testable import TianXianFruit

final class BusinessCloudDetailTests: XCTestCase {
    private let storeID = "23b4f929-ea32-4e75-9b78-9c463fa84331"

    private func record() -> SyncEvent {
        let day = "2026-11-21"
        let stable = BusinessUploadPolicy.androidSyncID(
            date: day, storeSyncID: storeID)
        let split = """
        [{"partner_id":79152283000000002,"partner_name":"头脑","amount":60.25,
        "wechat_income":35.25,"alipay_income":20.0,"cash_income":5.0}]
        """
        return SyncEvent(tableName: "store_daily_record",
                         syncId: stable, rowVersion: 1, operation: "UPSERT",
                         payload: [
                             "date": .string(day),
                             "store_sync_id": .string(storeID),
                             "store_name": .string("困难"),
                             "revenue": .number(60.25),
                             "receipt_splits_json": .string(split)])
    }

    private func book(_ name: String, role: String = "OWNER") -> CloudBook {
        CloudBook(id: UUID().uuidString, name: name,
                  role: role, deleted: nil, purged: nil)
    }

    func testTestBookHasExactInt64CollectorAndCorrectionEntry() {
        let row = record()
        let parts = TestBusinessSplitInput.from(row)
        XCTAssertEqual(parts.count, 1)
        XCTAssertEqual(parts.first?.partnerID, 79152283000000002)
        XCTAssertTrue(BusinessCorrectionEntryPolicy.allowed(
            book: book("iOS_TEST_88"), record: row,
            events: [row], cachedAuthorization: false))
    }

    func testProductionViewerAndCachedAuthenticationAreReadOnly() {
        let row = record()
        for badBook in [book("正式账本"), book("iOS_TEST_88", role: "VIEWER")] {
            XCTAssertFalse(BusinessCorrectionEntryPolicy.allowed(
                book: badBook, record: row,
                events: [row], cachedAuthorization: false))
        }
        XCTAssertFalse(BusinessCorrectionEntryPolicy.allowed(
            book: book("iOS_TEST_88"), record: row,
            events: [row], cachedAuthorization: true))
    }

    func testSettledDayAndUnstableIdentityNeverOpenCorrection() {
        let row = record()
        let settlement = SyncEvent(tableName: "profit_distribution",
            syncId: UUID().uuidString, rowVersion: 1, operation: "UPSERT",
            payload: ["date": .string("2026-11-21")])
        XCTAssertFalse(BusinessCorrectionEntryPolicy.allowed(
            book: book("iOS_TEST_88"), record: row,
            events: [row, settlement], cachedAuthorization: false))
        let alias = SyncEvent(tableName: "store_daily_record",
            syncId: UUID().uuidString, rowVersion: 1, operation: "UPSERT",
            payload: row.payload)
        XCTAssertFalse(BusinessCorrectionEntryPolicy.allowed(
            book: book("iOS_TEST_88"), record: alias,
            events: [alias], cachedAuthorization: false))
    }

    func testRecordWithNoCollectorDetailsStaysReadOnly() {
        let row = record()
        var payload = row.payload
        payload["receipt_splits_json"] = .string("[]")
        let noSplits = SyncEvent(tableName: row.tableName, syncId: row.syncId,
                                rowVersion: row.rowVersion, operation: row.operation,
                                payload: payload)
        XCTAssertFalse(BusinessCorrectionEntryPolicy.allowed(
            book: book("iOS_TEST_88"), record: noSplits,
            events: [noSplits], cachedAuthorization: false))
    }
    func testCloudInt64PayloadRoundTripIsLossless() throws {
        // Without this, copying an Android row into an iOS edit request
        // could change 79152283000000002 into an adjacent numeric ID.
        let raw = """
        {"id":79152283000000002,"store_id":79152283000000001,
         "revenue":60.25,"deleted":0}
        """.data(using: .utf8)!
        let value = try JSONDecoder().decode([String: JSONValue].self, from: raw)
        guard case .exactInteger(let id) = value["id"],
              case .exactInteger(let store) = value["store_id"] else {
            XCTFail("Large Android IDs were decoded as imprecise Double")
            return
        }
        XCTAssertEqual(id, 79152283000000002)
        XCTAssertEqual(store, 79152283000000001)
        let exported = try JSONEncoder().encode(value)
        let reloaded = try JSONDecoder().decode([String: JSONValue].self,
                                                from: exported)
        XCTAssertEqual(reloaded["id"], .exactInteger(79152283000000002))
        XCTAssertEqual(reloaded["store_id"], .exactInteger(79152283000000001))
    }

}
