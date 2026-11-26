import XCTest
@testable import TianXianFruit

final class BusinessBillLocalPlanTests: XCTestCase {
    private let partner = "00000000-0000-0000-0000-000000000004"
    private let scope = TXBusinessV2EditorScope(account: "seller", book: "00000000-0000-0000-0000-000000000001",
        date: "2026-11-26", storeID: "00000000-0000-0000-0000-000000000003", storeName: "A")
    private func original() -> TXBusinessV2LocalDraft {
        var draft = TXBusinessV2LocalDraft()
        draft.collectors[0].partner = partner
        draft.collectors[0].wechat.natural = "10"
        draft.collectors[0].alipay.natural = "8"
        draft.collectors[0].cash.natural = "20"
        draft.collectors[0].cash.deduct = false
        draft.collectors[0].replaceFollowing = true
        draft.collectors[0].wechat.morning = "2"
        draft.collectors[0].cash.morning = "1"
        draft.collectors[0].acknowledge(previous: 4, following: 0)
        return draft
    }
    private var previous: CustomerChangeEvent {
        CustomerChangeEvent(kind: "OVERNIGHT", key: scope.date + "|" + partner, revision: 4,
            payload: ["date": .string(scope.date), "partner_sync_id": .string(partner),
                "wechat": .number(3), "alipay": .number(0), "cash": .number(5),
                "wechat_recorded": .bool(true), "alipay_recorded": .bool(false), "cash_recorded": .bool(true)])
    }
    private func batch(amount: String = "100", date: String = "2026-11-26") throws -> TXBillBatchPreview {
        let csv = """
        微信订单号,交易时间,交易类型,收/支,金额,交易状态
        0001,\(date) 16:00:00,经营收款,收入,\(amount),成功
        """
        return try TXBillBatchPreview([TXBillCSV.parse(fileName: "微信.csv", data: Data(csv.utf8))])
    }
    func testOnlySelectedRawElectronicChannelChangesAndMorningAppliedOnce() throws {
        let draft = original()
        let plan = try TXBillLocalPlan.build(scope: scope, draft: draft, partner: partner,
            batch: batch(), confirmed: [.wechat], events: [previous], customerCursor: 9)
        XCTAssertEqual(plan.original, draft)
        XCTAssertEqual(plan.proposed.collectors[0].wechat.natural, "100.00")
        XCTAssertEqual(plan.proposed.collectors[0].alipay, draft.collectors[0].alipay)
        XCTAssertEqual(plan.proposed.collectors[0].cash, draft.collectors[0].cash)
        XCTAssertEqual(plan.proposed.collectors[0].wechat.morning, "2")
        XCTAssertEqual(plan.adjustedWechat, 9900)
        XCTAssertEqual(plan.adjustedAlipay, 800)
        XCTAssertEqual(plan.adjustedCash, 2100)
        XCTAssertNoThrow(try plan.validate(scope: scope))
        let name = "bill-plan-test-" + UUID().uuidString
        let defaults = try XCTUnwrap(UserDefaults(suiteName: name))
        defer { defaults.removePersistentDomain(forName: name) }
        try TXBusinessV2DraftStore.save(draft, scope: scope, defaults: defaults)
        try TXBillPlanStore.save(plan, scope: scope, defaults: defaults)
        XCTAssertEqual(try TXBillPlanStore.load(scope, defaults: defaults), plan)
        XCTAssertEqual(try TXBusinessV2DraftStore.load(scope, defaults: defaults), draft)
        XCTAssertThrowsError(try TXBillPlanStore.save(plan, scope: scope, defaults: defaults))
        let other = TXBusinessV2EditorScope(account: "other", book: scope.book, date: scope.date, storeID: scope.storeID, storeName: "A")
        XCTAssertNil(try TXBillPlanStore.load(other, defaults: defaults))
        try TXBillPlanStore.discard(plan, scope: scope, defaults: defaults)
        XCTAssertNil(try TXBillPlanStore.load(scope, defaults: defaults))
        XCTAssertEqual(try TXBusinessV2DraftStore.load(scope, defaults: defaults), draft)
    }

    func testMissingChannelDateLargePaymentAndSourceMismatchFailClosed() throws {
        let draft = original()
        let input = try batch()
        for confirmed: Set<TXBillPlatform> in [[], [.alipay], [.wechat,.alipay]] {
            XCTAssertThrowsError(try TXBillLocalPlan.build(scope: scope, draft: draft, partner: partner,
                batch: input, confirmed: confirmed, events: [previous], customerCursor: 9))
        }
        XCTAssertThrowsError(try TXBillLocalPlan.build(scope: scope, draft: draft, partner: partner,
            batch: batch(date: "2026-11-27"), confirmed: [.wechat], events: [previous], customerCursor: 9))
        XCTAssertThrowsError(try TXBillLocalPlan.build(scope: scope, draft: draft, partner: partner,
            batch: batch(amount: "2000"), confirmed: [.wechat], events: [previous], customerCursor: 9))
        XCTAssertThrowsError(try TXBillLocalPlan.build(scope: scope, draft: draft, partner: partner,
            batch: input, confirmed: [.wechat], events: [], customerCursor: 9))
    }

    func testKnownMissingCloudReceiptBlocksProposal() throws {
        let input = try batch()
        let hash = String(repeating: "a", count: 64)
        let key = String(repeating: "b", count: 64)
        let existing = CustomerChangeEvent(kind: "RECEIPT", key: "WECHAT|" + key, revision: 1,
            payload: ["platform": .string("WECHAT"), "dedupe_key": .string(key),
                "transaction_ref": .string("0002"), "trade_time": .string("2026-11-26 16:01:00"),
                "source_file_hash": .string(hash), "amount": .number(5), "refund_amount": .number(0), "net_amount": .number(5)])
        let owner = CustomerChangeEvent(kind: "COLLECTOR", key: "WECHAT|" + hash, revision: 1,
            payload: ["platform": .string("WECHAT"), "file_hash": .string(hash), "partner_sync_id": .string(partner)])
        XCTAssertThrowsError(try TXBillLocalPlan.build(scope: scope, draft: original(), partner: partner,
            batch: input, confirmed: [.wechat], events: [previous, existing, owner], customerCursor: 9))
        let excluded = CustomerChangeEvent(kind: "ANALYSIS", key: input.rows[0].id, revision: 1,
            payload: ["state": .string("EXCLUDED")])
        XCTAssertThrowsError(try TXBillLocalPlan.build(scope: scope, draft: original(), partner: partner,
            batch: input, confirmed: [.wechat], events: [previous, excluded], customerCursor: 9))
    }
}
