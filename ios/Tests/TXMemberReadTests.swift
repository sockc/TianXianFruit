import XCTest
@testable import TianXianFruit
final class TXMemberReadTests: XCTestCase {
    func testDecodeActualMemberPermissions() throws {
        let json = """
        {"user_id":"a","username":"member","display_name":"采购员","role":"EDITOR","permission_template":"PURCHASER","permissions":["purchase.create","purchase.view"],"visible_user_ids":[]}
        """
        let member = try JSONDecoder().decode(TXBookMember.self, from: Data(json.utf8))
        XCTAssertEqual(member.id, "a")
        XCTAssertEqual(member.permissions.count, 2)
        XCTAssertFalse(member.permissions.contains("settlement.edit"))
        XCTAssertEqual(TXMemberReadPolicy.label("purchase.create"), "新增采购")
        XCTAssertEqual(TXMemberReadPolicy.label("unknown"), "unknown")
    }
}
