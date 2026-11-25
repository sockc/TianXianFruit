import XCTest
@testable import TianXianFruit

final class AndroidMainNavigationTests: XCTestCase {
    func testMainTabsMatchAndroidOrderAndNeverOverflowIntoSystemMore() {
        XCTAssertEqual(TXMainTab.allCases.map(\.title),
                       ["首页", "库存", "采购", "营业", "结算", "更多"])
        XCTAssertEqual(TXMainTab.allCases.count, 6)
        XCTAssertEqual(Set(TXMainTab.allCases.map(\.id)).count, 6)
    }

    func testPrimaryTabsHaveAccessibleSymbols() {
        for tab in TXMainTab.allCases {
            XCTAssertFalse(tab.title.isEmpty)
            XCTAssertFalse(tab.symbol.isEmpty)
        }
        XCTAssertEqual(TXMainTab.settlement.title, "结算")
    }

    func testTestLedgerScopeIsNotRelaxedByVisualSimplification() {
        let account = Account(username: "test", displayName: "test",
                              systemRole: "USER")
        let test = CloudBook(id: "11111111-2222-4333-8444-555555555555",
                             name: "iOS_TEST_A", role: "EDITOR",
                             deleted: false, purged: false)
        let real = CloudBook(id: "11111111-2222-4333-8444-555555555555",
                             name: "天鲜果业", role: "OWNER",
                             deleted: false, purged: false)
        XCTAssertTrue(TXTestBookAccess.isCandidate(test, account: account,
                                                   offline: false))
        XCTAssertFalse(TXTestBookAccess.isCandidate(real, account: account,
                                                    offline: false))
        XCTAssertFalse(TXTestBookAccess.isCandidate(test, account: account,
                                                    offline: true))
    }

    func testUnsupportedSettlementOptionsRemainNonWritable() {
        XCTAssertTrue(TXSettlementTestAction.partial.canPreviewSavedTransfer)
        XCTAssertTrue(TXSettlementTestAction.full.canPreviewSavedTransfer)
        XCTAssertTrue(TXSettlementTestAction.reset.canPreviewSavedTransfer)
        XCTAssertFalse(TXSettlementTestAction.generate.canPreviewSavedTransfer)
        XCTAssertFalse(TXSettlementTestAction.cutoff.canPreviewSavedTransfer)
        XCTAssertFalse(TXSettlementTestAction.profitShare.canPreviewSavedTransfer)
    }
}
