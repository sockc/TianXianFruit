import XCTest
@testable import TianXianFruit

final class AndroidEntryParityTests: XCTestCase {
    func testSingleCollectorDisplaysOriginalChannelsWithoutProfitCalculation() throws {
        let line = BusinessCollectorDraftLine(partnerSyncID: "a", wechat: "15.50",
                                              alipay: "30", cash: "4.20")
        let summary = try XCTUnwrap(TXBusinessReceiptPolicy.summary([line]))
        XCTAssertEqual(summary.wechat, 1550)
        XCTAssertEqual(summary.alipay, 3000)
        XCTAssertEqual(summary.cash, 420)
        XCTAssertEqual(summary.combined, 4970)
        XCTAssertEqual(TXBusinessReceiptPolicy.individual(line), 4970)
    }

    func testMultiCollectorsDoNotMergeOrModifySourceLines() throws {
        let rows = [
            BusinessCollectorDraftLine(partnerSyncID: "a", wechat: "100",
                                       alipay: "0", cash: "20"),
            BusinessCollectorDraftLine(partnerSyncID: "b", wechat: "10.25",
                                       alipay: "21.35", cash: "0")
        ]
        let original = rows
        let total = try XCTUnwrap(TXBusinessReceiptPolicy.summary(rows))
        XCTAssertEqual(total.wechat, 11025)
        XCTAssertEqual(total.alipay, 2135)
        XCTAssertEqual(total.cash, 2000)
        XCTAssertEqual(total.combined, 15160)
        XCTAssertEqual(TXBusinessReceiptPolicy.individual(rows[0]), 12000)
        XCTAssertEqual(TXBusinessReceiptPolicy.individual(rows[1]), 3160)
        XCTAssertEqual(rows, original)
    }

    func testInvalidMoneyDoesNotSilentlyBecomeZero() {
        let bad = BusinessCollectorDraftLine(partnerSyncID: "a", wechat: "1.555")
        XCTAssertNil(TXBusinessReceiptPolicy.summary([bad]))
        XCTAssertNil(TXBusinessReceiptPolicy.individual(bad))
        XCTAssertNil(TXBusinessReceiptPolicy.summary([]))
    }

    func testOnlyExistingOnlineTestBooksMayOpenRemarkSelection() {
        let user = Account(username: "tester", displayName: "测试", systemRole: "USER")
        let test = CloudBook(id: "t", name: "iOS_TEST_B", role: "EDITOR",
                             deleted: false, purged: false)
        let live = CloudBook(id: "p", name: "天鲜果业", role: "OWNER",
                             deleted: false, purged: false)
        XCTAssertTrue(TXTestBookAccess.isCandidate(test, account: user, offline: false))
        XCTAssertFalse(TXTestBookAccess.isCandidate(live, account: user, offline: false))
        XCTAssertFalse(TXTestBookAccess.isCandidate(test, account: user, offline: true))
        XCTAssertEqual(TXTestWriteState.separateVerification.label, "可进入独立验证")
    }
}
