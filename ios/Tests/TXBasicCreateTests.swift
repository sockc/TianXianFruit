import XCTest
@testable import TianXianFruit
final class TXBasicCreateTests: XCTestCase {
    func testCreateCapabilityNeverAllowsFormal() throws {
        let raw = """
        {"version":1,"scope":"iOS_TEST_","atomic":true,"idempotency":true,"operation_lookup":true,"inventory":false,"daily_generate":false,"basic":true,"basic_create":true}
        """
        let caps = try JSONDecoder().decode(TXLedgerV33Capabilities.self, from: Data(raw.utf8))
        let test = CloudBook(id: "a", name: "iOS_TEST_资料", role: "OWNER", deleted: false, purged: false)
        let formal = CloudBook(id: "b", name: "正式", role: "OWNER", deleted: false, purged: false)
        XCTAssertTrue(caps.allows("basic_create", book: test))
        XCTAssertFalse(caps.allows("basic_create", book: formal))
        XCTAssertFalse(caps.allows("basic_create"))
        let formalCap = try JSONDecoder().decode(TXLedgerV33Capabilities.self, from: Data(raw.replacingOccurrences(of: "iOS_TEST_", with: "formal").utf8))
        XCTAssertFalse(formalCap.allows("basic_create", book: formal))
    }
    func testCreationRequiresNewUUIDAndEnabledProduct() {
        var p = TXBasicEdit(table: "fruit", sync_id: UUID().uuidString.lowercased(), expected_row_version: 0, name: "测试商品", default_unit: "件", enabled: 1)
        XCTAssertTrue(p.valid); XCTAssertTrue(p.isCreation)
        p.enabled = 0; XCTAssertFalse(p.valid)
        XCTAssertFalse(TXBasicEdit(table: "fruit", sync_id: "old", expected_row_version: 0, name: "测试商品", default_unit: "件", enabled: 1).valid)
    }
}
