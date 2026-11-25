import XCTest
@testable import TianXianFruit

final class BusinessV2DraftTests: XCTestCase {
    private let partner = "00000000-0000-0000-0000-000000000004"
    private let payer = "00000000-0000-0000-0000-000000000005"
    private let scope = TXBusinessV2EditorScope(account: "seller", book: "00000000-0000-0000-0000-000000000001",
        date: "2026-11-25", storeID: "00000000-0000-0000-0000-000000000003", storeName: "A")
    func testBlankAndZeroAreDifferent() throws {
        var input = TXBusinessV2CollectorInput()
        input.partner = partner
        input.replaceFollowing = true
        input.acknowledge(previous: 0, following: 0)
        input.wechat.morning = "0"
        let request = try input.request(previousRevision: 0, followingRevision: 0)
        XCTAssertEqual(request.following?.wechatRecorded, true)
        XCTAssertEqual(request.following?.alipayRecorded, false)
        XCTAssertEqual(request.following?.alipay, "0.00")
    }
    func testSourceRevisionChangeRequiresExplicitReview() throws {
        var input = TXBusinessV2CollectorInput()
        input.partner = partner
        XCTAssertThrowsError(try input.request(previousRevision: 4, followingRevision: 1))
        input.acknowledge(previous: 4, following: 1)
        XCTAssertNoThrow(try input.request(previousRevision: 4, followingRevision: 1))
        XCTAssertThrowsError(try input.request(previousRevision: 4, followingRevision: 2))
    }
    func testSeparateStorageAndMalformedDraftProtection() throws {
        let name = "v2draft-tests-" + UUID().uuidString
        let defaults = try XCTUnwrap(UserDefaults(suiteName: name))
        defer { defaults.removePersistentDomain(forName: name) }
        let draft = TXBusinessV2LocalDraft()
        try TXBusinessV2DraftStore.save(draft, scope: scope, defaults: defaults)
        XCTAssertEqual(try TXBusinessV2DraftStore.load(scope, defaults: defaults), draft)
        let other = TXBusinessV2EditorScope(account: "seller", book: scope.book, date: scope.date,
            storeID: "other", storeName: "B")
        XCTAssertNil(try TXBusinessV2DraftStore.load(other, defaults: defaults))
        defaults.set("bad", forKey: TXBusinessV2DraftStore.key(scope))
        XCTAssertThrowsError(try TXBusinessV2DraftStore.save(draft, scope: scope, defaults: defaults))
    }
    func testTestScopeAndPreparedTotals() throws {
        let account = Account(username: "seller", displayName: "seller", systemRole: nil)
        let testBook = CloudBook(id: scope.book, name: "iOS_TEST_V37", role: "OWNER", deleted: nil, purged: nil)
        let formal = CloudBook(id: scope.book, name: "正式账本", role: "OWNER", deleted: nil, purged: nil)
        XCTAssertTrue(scope.permits(account, testBook))
        XCTAssertFalse(scope.permits(account, formal))
        var draft = TXBusinessV2LocalDraft()
        draft.end = "00:30"; draft.expense = "1"; draft.payer = payer; draft.opening = "0"; draft.closing = "0"
        draft.collectors[0].partner = partner; draft.collectors[0].wechat.natural = "10"
        draft.collectors[0].replaceFollowing = true; draft.collectors[0].wechat.morning = "2"
        draft.collectors[0].acknowledge(previous: 4, following: 0)
        let records = [SyncEvent(tableName: "store", syncId: scope.storeID, rowVersion: 1, operation: "UPSERT", payload: ["name": .string(scope.storeName)]),
            SyncEvent(tableName: "partner", syncId: partner, rowVersion: 1, operation: "UPSERT", payload: ["name": .string("收款人")]),
            SyncEvent(tableName: "partner", syncId: payer, rowVersion: 1, operation: "UPSERT", payload: ["name": .string("付款人")])]
        let source = CustomerChangeEvent(kind: "OVERNIGHT", key: scope.date + "|" + partner, revision: 4,
            payload: ["date": .string(scope.date), "partner_sync_id": .string(partner), "wechat": .number(3),
            "alipay": .number(0), "cash": .number(0), "wechat_recorded": .bool(true),
            "alipay_recorded": .bool(false), "cash_recorded": .bool(false)])
        let prepared = try TXBusinessV2DraftPolicy.prepare(draft, scope: scope, account: account, book: testBook,
            records: records, events: [source])
        XCTAssertEqual(prepared.proposal.totalRevenue, "9.00")
        XCTAssertEqual(prepared.proposal.profit, "8.00")
        let body = try XCTUnwrap(JSONSerialization.jsonObject(with: prepared.canonical) as? [String: Any])
        XCTAssertEqual(body["expense_payer_sync_id"] as? String, payer)
        let natural = try XCTUnwrap(body["natural"] as? [[String: Any]])
        XCTAssertEqual(natural.first?["previous_revision"] as? Int, 4)
        XCTAssertThrowsError(try TXBusinessV2DraftPolicy.prepare(draft, scope: scope, account: account, book: formal,
            records: records, events: [source]))
    }
}
