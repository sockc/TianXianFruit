import XCTest
@testable import TianXianFruit

final class BusinessV2PreparationTests: XCTestCase {
    private let partner = "00000000-0000-0000-0000-000000000004"
    private let payer = "00000000-0000-0000-0000-000000000005"

    private func natural(deductCash: Bool = true, cash: String = "4.00") -> TXBusinessV2Natural {
        TXBusinessV2Natural(partnerSyncID: partner, wechat: "18.00", alipay: "4.00", cash: cash,
            deductWechat: true, deductAlipay: true, deductCash: deductCash,
            previousRevision: 3, followingRevision: 0,
            following: TXBusinessV2Morning(wechat: "2.00", alipay: "0.00", cash: "0.00",
                wechatRecorded: true, alipayRecorded: true, cashRecorded: true))
    }

    func testAdjustmentMatchesServerFixture() throws {
        let previous = TXBusinessOvernightReferencePolicy.Morning.recorded(
            .init(wechat: 500, alipay: 200, cash: 100))
        XCTAssertEqual(try TXBusinessV2Preparation.adjusted(natural(), previous: previous, following: .missing),
                       [1500, 200, 300])
        XCTAssertEqual(try TXBusinessV2Preparation.adjusted(natural(deductCash: false),
                        previous: previous, following: .missing), [1500, 200, 400])
        XCTAssertThrowsError(try TXBusinessV2Preparation.adjusted(natural(), previous: .invalid, following: .missing))
        XCTAssertThrowsError(try TXBusinessV2Preparation.adjusted(natural(cash: "0"), previous: previous, following: .missing))
    }

    func testCanonicalHashMatchesPythonV37Fixture() throws {
        let proposal = BusinessCommitProposal(protocolVersion: 1,
            bookID: "00000000-0000-0000-0000-000000000001", account: "seller",
            operationID: "00000000-0000-0000-0000-000000000002", date: "2026-11-25",
            storeSyncID: "00000000-0000-0000-0000-000000000003", storeName: "龙归南村",
            collectors: [BusinessCommitCollector(partnerSyncID: partner,
                wechat: "15.00", alipay: "2.00", cash: "3.00")],
            actualStartTime: "16:00", actualEndTime: "00:30",
            wechat: "15.00", alipay: "2.00", cash: "3.00", totalRevenue: "20.00",
            expense: "1.00", openingStockValue: "0.00", stockLeftValue: "0.00",
            profit: "19.00", payloadHash: "legacy")
        let data = try TXBusinessV2Preparation.canonical(proposal, payer: payer, natural: [natural()])
        XCTAssertEqual(TXBusinessV2Preparation.hash(data), "b2a6712b3e07b6f283fa6cf01f0504e57f88fe13f55e16446e1adbd996e66a3f")
        XCTAssertThrowsError(try TXBusinessV2Preparation.canonical(proposal, payer: nil, natural: [natural()]))
        XCTAssertThrowsError(try TXBusinessV2Preparation.canonical(proposal, payer: payer, natural: [natural(),natural()]))
    }
}
