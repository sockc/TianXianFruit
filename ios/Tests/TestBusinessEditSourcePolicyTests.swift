import XCTest
@testable import TianXianFruit

final class TestBusinessEditSourcePolicyTests: XCTestCase {
    private func row(version: Int64 = 3, id: JSONValue,
                     operation: String = "UPSERT") -> SyncEvent {
        let day = "2026-11-21"
        let storeID = "a71e88c0-02e1-45a3-8487-ed48095ad3d2"
        return SyncEvent(tableName: "store_daily_record",
            syncId: BusinessUploadPolicy.androidSyncID(
                date: day, storeSyncID: storeID),
            rowVersion: version,
            operation: operation,
            payload: [
                "id": id,
                "store_id": .exactInteger(79152283000000001),
                "date": .string(day),
                "store_sync_id": .string(storeID),
                "store_name": .string("宁德"),
                "wechat_income": .number(110),
                "alipay_income": .number(210),
                "cash_income": .number(0),
                "revenue": .number(320)])
    }

    func testEditorUsesLatestServerRowNotOldRoundedReadOnlyCache() {
        let remote = row(id: .exactInteger(79152283000000002))
        let cached = row(id: .number(Double(79152283000000002)))
        XCTAssertNotEqual(cached.payload, remote.payload,
                          "Legacy read-only cache must not be used as editing baseline")
        let rows = TestBusinessEditSourcePolicy.editableRecords(
            in: [remote], focus: cached.syncId)
        XCTAssertEqual(rows.count, 1)
        XCTAssertEqual(rows.first?.payload["id"],
                       .exactInteger(79152283000000002))
        XCTAssertTrue(TestBusinessEditSourcePolicy.isUnchanged(
            remote, in: [remote]))
        XCTAssertFalse(TestBusinessEditSourcePolicy.isUnchanged(
            cached, in: [remote]))
    }

    func testOnlyRealConcurrentChangeBlocksAFreshForm() {
        let original = row(id: .exactInteger(79152283000000002))
        let newer = row(version: 4, id: .exactInteger(79152283000000002))
        XCTAssertFalse(TestBusinessEditSourcePolicy.isUnchanged(
            original, in: [newer]))
        XCTAssertEqual(TestBusinessEditSourcePolicy.version(
            original, in: [newer]), "4")
        XCTAssertFalse(TestBusinessEditSourcePolicy.isUnchanged(
            original, in: []))
        let deleted = row(version: 4, id: .exactInteger(79152283000000002),
                          operation: "DELETE")
        XCTAssertFalse(TestBusinessEditSourcePolicy.isUnchanged(
            original, in: [deleted]))
        XCTAssertEqual(TestBusinessEditSourcePolicy.version(
            original, in: [deleted]), "已删除")
    }

    func testLiveSourceListCannotIncludeDeleteOrUnrelatedTable() {
        let original = row(id: .exactInteger(79152283000000002))
        let deleted = row(version: 4, id: .exactInteger(79152283000000002),
                          operation: "DELETE")
        let unrelated = SyncEvent(tableName: "partner", syncId: "partner-1",
            rowVersion: 1, operation: "UPSERT",
            payload: ["name": .string("头脑")])
        XCTAssertTrue(TestBusinessEditSourcePolicy.editableRecords(
            in: [deleted, unrelated]).isEmpty)
        XCTAssertEqual(TestBusinessEditSourcePolicy.editableRecords(
            in: [original, unrelated], focus: original.syncId).count, 1)
    }
}
