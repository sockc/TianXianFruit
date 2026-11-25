import XCTest
@testable import TianXianFruit

final class TXBasicDirectoryTests: XCTestCase {
    func testDirectoryUsesActualCloudRecordsNotHistoricalSales() {
        var snap = ReadOnlySnapshot()
        snap.apply([
            SyncEvent(tableName: "fruit", syncId: "a", rowVersion: 1, operation: "UPSERT", payload: ["name": .string("红提")]),
            SyncEvent(tableName: "fruit", syncId: "b", rowVersion: 1, operation: "UPSERT", payload: ["name": .string("苹果")]),
            SyncEvent(tableName: "fruit", syncId: "b", rowVersion: 2, operation: "DELETE", payload: [:]),
            SyncEvent(tableName: "store", syncId: "s", rowVersion: 1, operation: "UPSERT", payload: ["name": .string("南村")])
        ])
        XCTAssertEqual(TXBasicDirectoryPolicy.entries(snap, kind: .fruit).map(\.name), ["红提"])
        XCTAssertTrue(TXBasicDirectoryPolicy.entries(snap, kind: .fruit, query: "苹果").isEmpty)
        XCTAssertEqual(TXBasicDirectoryPolicy.entries(snap, kind: .store).map(\.name), ["南村"])
        XCTAssertEqual(TXBasicDirectoryPolicy.roleLabel("EDITOR"), "编辑成员")
    }
}
