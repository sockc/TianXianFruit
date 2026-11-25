import XCTest
@testable import TianXianFruit

final class TXBasicEditTests: XCTestCase {
    func testProductEditAndReadback() {
        let p = TXBasicEdit(table: "fruit", sync_id: "a", expected_row_version: 2, name: "红提", default_unit: "件", enabled: 0)
        XCTAssertTrue(p.valid)
        XCTAssertTrue(p.matches(SyncEvent(tableName: "fruit", syncId: "a", rowVersion: 3, operation: "UPSERT", payload: ["name": .string("红提"), "default_unit": .string("件"), "enabled": .number(0)])))
        XCTAssertFalse(TXBasicEdit(table: "fruit", sync_id: "a", expected_row_version: 2, name: "总价", default_unit: "件", enabled: 0).valid)
    }
    func testStoreCoordinatesAndTime() {
        var p = TXBasicEdit(table: "store", sync_id: "s", expected_row_version: 2, name: "南村", address: "", latitude: "23.1", longitude: "113.2", default_start_time: "16:00", default_end_time: "24:00")
        XCTAssertTrue(p.valid)
        p.default_end_time = "02:00"; XCTAssertFalse(p.valid)
        p.default_end_time = "24:00"; p.latitude = ""; XCTAssertFalse(p.valid)
        p.longitude = ""; XCTAssertTrue(p.valid)
    }
}
