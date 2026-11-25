import XCTest
@testable import TianXianFruit

final class UnifiedIOSAccessTests: XCTestCase {
    private let testBook = CloudBook(id: "test-id", name: "iOS_TEST_A",
                                     role: "EDITOR", deleted: false, purged: false)
    private let realBook = CloudBook(id: "real-id", name: "天鲜果业",
                                     role: "OWNER", deleted: false, purged: false)
    private let account = Account(username: "tester", displayName: "测试", systemRole: "USER")

    func testTestBookCapabilitiesAreGatedByServer() {
        XCTAssertTrue(TXTestBookAccess.isCandidate(testBook, account: account, offline: false))
        XCTAssertEqual(TXTestBookAccess.state(.businessCreate,
            book: testBook, account: account, offline: false,
            businessEnabled: true, purchaseEnabled: true), .live)
        XCTAssertEqual(TXTestBookAccess.state(.purchaseCreate,
            book: testBook, account: account, offline: false,
            businessEnabled: true, purchaseEnabled: false), .serverUnavailable)
        XCTAssertEqual(TXTestBookAccess.state(.businessEdit,
            book: testBook, account: account, offline: false,
            businessEnabled: false, purchaseEnabled: false), .separateVerification)
    }

    func testFormalBookAndOfflineRemainReadOnly() {
        for module in TXTestModule.allCases {
            XCTAssertEqual(TXTestBookAccess.state(module,
                book: realBook, account: account, offline: false,
                businessEnabled: true, purchaseEnabled: true), .productionReadOnly)
            XCTAssertEqual(TXTestBookAccess.state(module,
                book: testBook, account: account, offline: true,
                businessEnabled: true, purchaseEnabled: true), .restricted)
        }
    }

    func testSettlementPreviewOnlyForOnlineTestBooks() {
        XCTAssertEqual(TXTestBookAccess.state(.profitWrite, book: testBook,
            account: account, offline: false, businessEnabled: true,
            purchaseEnabled: true), .preparationOnly)
        XCTAssertEqual(TXTestBookAccess.state(.profitWrite, book: realBook,
            account: account, offline: false, businessEnabled: true,
            purchaseEnabled: true), .productionReadOnly)
        XCTAssertEqual(TXTestBookAccess.state(.profitWrite, book: testBook,
            account: account, offline: true, businessEnabled: true,
            purchaseEnabled: true), .restricted)
    }

    func testViewerDeletedBookAndAnonymousRejected() {
        let viewer = CloudBook(id: "a", name: "iOS_TEST_A", role: "VIEWER",
                               deleted: false, purged: false)
        let deleted = CloudBook(id: "a", name: "iOS_TEST_A", role: "OWNER",
                                deleted: true, purged: false)
        XCTAssertFalse(TXTestBookAccess.isCandidate(viewer, account: account, offline: false))
        XCTAssertFalse(TXTestBookAccess.isCandidate(deleted, account: account, offline: false))
        XCTAssertFalse(TXTestBookAccess.isCandidate(testBook, account: nil, offline: false))
    }

    func testAllUnimplementedFeaturesRemainReservedEvenWithCapabilities() {
        for module in TXTestModule.allCases
            where !module.isImplemented && module != .profitWrite {
            XCTAssertEqual(TXTestBookAccess.state(module,
                book: testBook, account: account, offline: false,
                businessEnabled: true, purchaseEnabled: true), .reserved)
        }
    }
}
