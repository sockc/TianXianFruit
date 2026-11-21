import Foundation
import XCTest
@testable import TianXianFruit

final class FormalWriteScopeTests: XCTestCase {
    private let testBook = CloudBook(
        id: "11111111-2222-4333-8444-555555555555",
        name: "iOS_TEST_日常", role: "OWNER", deleted: false, purged: false)
    private let formalBook = CloudBook(
        id: "22222222-3333-4444-8555-666666666666",
        name: "天鲜正式账本", role: "OWNER", deleted: false, purged: false)

    private func business(_ scope: String?, enabled: Bool = true) throws -> BusinessCommitCapabilities {
        var value: [String: Any] = [
            "version": 1, "enabled": enabled, "atomic": true,
            "idempotency": true, "feature": "business_atomic_commit_v1",
            "supports_collector_splits": true
        ]
        if let scope { value["scope"] = scope }
        return try JSONDecoder().decode(
            BusinessCommitCapabilities.self, from: JSONSerialization.data(withJSONObject: value))
    }

    private func purchase(_ scope: String?, enabled: Bool = true) throws -> PurchaseCommitCapabilities {
        var value: [String: Any] = [
            "version": 1, "enabled": enabled, "atomic": true,
            "idempotency": true, "feature": "purchase_atomic_commit_v1",
            "supports_complete_plan": true
        ]
        if let scope { value["scope"] = scope }
        return try JSONDecoder().decode(
            PurchaseCommitCapabilities.self, from: JSONSerialization.data(withJSONObject: value))
    }

    func testLegacyCapabilitiesOnlyAuthorizeTestLedger() throws {
        XCTAssertTrue(try business(nil).allows(book: testBook))
        XCTAssertFalse(try business(nil).allows(book: formalBook))
        XCTAssertTrue(try purchase(nil).eligible(book: testBook))
        XCTAssertFalse(try purchase(nil).eligible(book: formalBook))
    }

    func testFormalScopeCannotBeAppliedToTestLedger() throws {
        XCTAssertTrue(try business("formal").allows(book: formalBook))
        XCTAssertFalse(try business("formal").allows(book: testBook))
        XCTAssertTrue(try purchase("formal").eligible(book: formalBook))
        XCTAssertFalse(try purchase("formal").eligible(book: testBook))
    }

    func testDisabledOrWrongScopeNeverUnlocksWrite() throws {
        XCTAssertFalse(try business("none").allows(book: formalBook))
        XCTAssertFalse(try business("formal", enabled: false).allows(book: formalBook))
        XCTAssertFalse(try purchase("test").eligible(book: formalBook))
        XCTAssertFalse(try purchase("formal", enabled: false).eligible(book: formalBook))
    }
}
