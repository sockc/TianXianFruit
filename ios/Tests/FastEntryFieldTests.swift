import XCTest
@testable import TianXianFruit

final class FastEntryFieldTests: XCTestCase {
    func testExistingDefaultsAndAmountsSelectAll() {
        XCTAssertTrue(TXFastEntryPolicy.shouldSelectOnFocus("0"))
        XCTAssertTrue(TXFastEntryPolicy.shouldSelectOnFocus("1"))
        XCTAssertTrue(TXFastEntryPolicy.shouldSelectOnFocus("123.45"))
        XCTAssertFalse(TXFastEntryPolicy.shouldSelectOnFocus(""))
    }

    func testDraftDefaultsRemainUnchangedAndCostsUseExistingPolicies() throws {
        let first = PurchaseDraftLine.blank()
        XCTAssertEqual(first.quantity, "1")
        XCTAssertEqual(first.unitPrice, "0")
        XCTAssertEqual(first.unitWeightJin, "0")
        var item = first
        item.fruitSyncID = "fruit-id"
        item.fruitName = "红提"
        item.quantity = "2.5"
        item.unitPrice = "3.45"
        XCTAssertEqual(try PurchaseDraftPolicy.subtotal(item), Decimal(string: "8.63")!)
        XCTAssertEqual(first.quantity, "1", "Fast-entry must not rewrite saved drafts")
    }

    func testReceiptsStillRequireValidPrecisionAndMultiCollectorIdentity() throws {
        let single = BusinessCollectorDraftLine(partnerSyncID: "a",
                                                 wechat: "10", alipay: "0",
                                                 cash: "0")
        let fields = ["wechat": "10", "alipay": "0", "cash": "0"]
        XCTAssertNoThrow(try BusinessCollectorDraftPolicy.validate([single], fields: fields))
        let duplicate = BusinessCollectorDraftLine(partnerSyncID: "a",
                                                    wechat: "20", alipay: "0",
                                                    cash: "0")
        XCTAssertThrowsError(try BusinessCollectorDraftPolicy.validate([single, duplicate],
            fields: ["wechat": "30", "alipay": "0", "cash": "0"]))
        XCTAssertThrowsError(try BusinessCollectorDraftPolicy.cents("1.234", label: "微信"))
    }
}
