import XCTest
@testable import TianXianFruit

final class TXFormalLedgerAccessTests: XCTestCase {
    private let formal = CloudBook(id: "11111111-2222-4333-8444-555555555555",
        name: "正式账本", role: "EDITOR", deleted: false, purged: false)
    private let test = CloudBook(id: "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
        name: "iOS_TEST_账本", role: "OWNER", deleted: false, purged: false)
    private let account = Account(username: "tester", displayName: "测试", systemRole: "USER")
    func testScopeMustMatchSelectedBook() {
        XCTAssertTrue(TXFormalLedgerAccess.scopeMatches("formal", book: formal))
        XCTAssertFalse(TXFormalLedgerAccess.scopeMatches("iOS_TEST_", book: formal))
        XCTAssertTrue(TXFormalLedgerAccess.scopeMatches("iOS_TEST_", book: test))
        XCTAssertFalse(TXFormalLedgerAccess.scopeMatches("formal", book: test))
        XCTAssertFalse(TXFormalLedgerAccess.scopeMatches("formal", book: nil))
        XCTAssertFalse(TXFormalLedgerAccess.scopeMatches("none", book: formal))
    }
    func testLocalCandidateDoesNotGrantCloudWrites() throws {
        XCTAssertTrue(TXFormalLedgerAccess.localCandidate(formal, account: account, offline: false))
        XCTAssertFalse(TXFormalLedgerAccess.localCandidate(formal, account: account, offline: true))
        let viewer = CloudBook(id: formal.id, name: formal.name, role: "VIEWER", deleted: false, purged: false)
        XCTAssertFalse(TXFormalLedgerAccess.localCandidate(viewer, account: account, offline: false))
        let raw = """
        {"version":1,"scope":"formal","atomic":true,"idempotency":true,"operation_lookup":true,"inventory":true,"inventory_autosave":false,"daily_generate":false,"profit_save":false,"cutoff":false,"authoritative_balance":false}
        """
        let cap = try JSONDecoder().decode(TXLedgerV33Capabilities.self, from: Data(raw.utf8))
        XCTAssertTrue(cap.allows("inventory", book: formal))
        XCTAssertFalse(cap.allows("inventory", book: test))
        XCTAssertFalse(cap.allows("inventory", book: nil))
        XCTAssertFalse(cap.allows("inventory_auto", book: formal))
        XCTAssertFalse(cap.allows("daily", book: formal))
    }
    func testFormalTransferRequiresMatchingExplicitCapability() throws {
        let raw = """
        {"version":1,"scope":"formal","feature":"settlement_transfer_atomic_v1","enabled":true,"atomic":true,"idempotency":true,"operation_lookup":true,"actions":["PARTIAL","FULL","RESET"]}
        """
        let cap = try JSONDecoder().decode(TXSettlementCommitCapabilities.self, from: Data(raw.utf8))
        XCTAssertTrue(cap.allows(book: formal))
        XCTAssertFalse(cap.allows(book: test))
        XCTAssertFalse(cap.allows(book: nil))
    }
}
