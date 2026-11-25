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
        XCTAssertTrue(scope.permits(account, formal)) // Local candidate only; server capability governs POST.
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
        XCTAssertEqual(try TXBusinessV2DraftPolicy.prepare(draft, scope: scope, account: account, book: formal,
            records: records, events: [source]), prepared)
        let testCaps = TXBusinessV2Capabilities(version: 2, feature: "business_atomic_commit_v2", enabled: true,
            scope: "test", atomic: true, idempotency: true, supportsOvernight: true, supportsExpensePayer: true)
        let formalCaps = TXBusinessV2Capabilities(version: 2, feature: "business_atomic_commit_v2", enabled: true,
            scope: "formal", atomic: true, idempotency: true, supportsOvernight: true, supportsExpensePayer: true)
        XCTAssertTrue(testCaps.allows(book: testBook))
        XCTAssertFalse(testCaps.allows(book: formal))
        XCTAssertTrue(formalCaps.allows(book: formal))
        XCTAssertFalse(formalCaps.allows(book: testBook))
        let disabled = TXBusinessV2Capabilities(version: 2, feature: "business_atomic_commit_v2", enabled: false,
            scope: "formal", atomic: true, idempotency: true, supportsOvernight: true, supportsExpensePayer: true)
        XCTAssertFalse(disabled.allows(book: formal))
        let immutable = TXBusinessV2Immutable(scope: scope, prepared: prepared)
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let outbox = try TXBusinessV2Outbox(url: folder.appendingPathComponent("v2.sqlite"))
        XCTAssertNil(try outbox.load(scope))
        try outbox.reserve(immutable)
        XCTAssertEqual(try outbox.load(scope), immutable)
        XCTAssertThrowsError(try outbox.reserve(immutable))
        let reopened = try TXBusinessV2Outbox(url: folder.appendingPathComponent("v2.sqlite"))
        XCTAssertEqual(try reopened.load(scope), immutable)
        let bodyWithDevice = try XCTUnwrap(JSONSerialization.jsonObject(with: immutable.body(deviceID: "device")) as? [String: Any])
        XCTAssertEqual(bodyWithDevice["payload_hash"] as? String, prepared.hash)
        XCTAssertEqual(bodyWithDevice["device_id"] as? String, "device")

        let saleID = BusinessUploadPolicy.androidSyncID(date: scope.date, storeSyncID: scope.storeID)
        let receipt = TXBusinessV2Receipt(status: "committed", operation_id: prepared.proposal.operationID,
            book_id: scope.book, record_sync_id: saleID, collector_sync_ids: [partner],
            payload_hash: prepared.hash, sync_cursor: 22,
            overnight_receipts: [.init(partner_sync_id: partner, previous_revision: 4, following_revision: 1, natural_revision: 1)],
            expense_payer_sync_id: payer)
        XCTAssertTrue(receipt.matches(immutable))
        var readbackRecords = records
        readbackRecords[1] = SyncEvent(tableName: "partner", syncId: partner, rowVersion: 1, operation: "UPSERT",
            payload: ["id": .number(42), "name": .string("收款人")])
        readbackRecords[2] = SyncEvent(tableName: "partner", syncId: payer, rowVersion: 1, operation: "UPSERT",
            payload: ["id": .exactInteger(9007199254740993), "name": .string("付款人")])
        let split = """
        [{"partner_id":42,"amount":9,"wechat_income":9,"alipay_income":0,"cash_income":0}]
        """
        let sale: [String: JSONValue] = ["date": .string(scope.date), "store_sync_id": .string(scope.storeID),
            "store_name": .string(scope.storeName), "revenue": .number(9), "wechat_income": .number(9),
            "alipay_income": .number(0), "cash_income": .number(0), "expense": .number(1), "profit": .number(8),
            "opening_stock_value": .number(0), "stock_left_value": .number(0), "actual_start_time": .string("16:00"),
            "actual_end_time": .string("00:30"), "expense_payer_id": .exactInteger(9007199254740993),
            "receipt_splits_json": .string(split)]
        readbackRecords.append(SyncEvent(tableName: "store_daily_record", syncId: saleID,
            rowVersion: 1, operation: "UPSERT", payload: sale))
        let following = CustomerChangeEvent(kind: "OVERNIGHT", key: "2026-11-26|" + partner, revision: 1,
            payload: ["date": .string("2026-11-26"), "partner_sync_id": .string(partner), "wechat": .number(2),
                "alipay": .number(0), "cash": .number(0), "wechat_recorded": .bool(true),
                "alipay_recorded": .bool(false), "cash_recorded": .bool(false)])
        let provenance = CustomerChangeEvent(kind: "NATURAL", key: scope.date + "|" + partner, revision: 1,
            payload: ["date": .string(scope.date), "partner_sync_id": .string(partner),
                "store_sync_id": .string(scope.storeID), "wechat": .number(10), "alipay": .number(0), "cash": .number(0),
                "saved_wechat": .number(9), "saved_alipay": .number(0), "saved_cash": .number(0),
                "deduct_wechat": .bool(true), "deduct_alipay": .bool(true), "deduct_cash": .bool(true)])
        XCTAssertTrue(TXBusinessV2Readback.verified(immutable, receipt: receipt, records: readbackRecords,
            events: [source, following, provenance], ledgerCursor: 22))
        XCTAssertFalse(TXBusinessV2Readback.verified(immutable, receipt: receipt, records: readbackRecords,
            events: [source, following], ledgerCursor: 22))
        XCTAssertFalse(TXBusinessV2Readback.verified(immutable, receipt: receipt, records: readbackRecords,
            events: [source, following, provenance], ledgerCursor: 21))
        var badSale = sale
        badSale["expense_payer_id"] = .exactInteger(9007199254740992)
        readbackRecords[3] = SyncEvent(tableName: "store_daily_record", syncId: saleID,
            rowVersion: 2, operation: "UPSERT", payload: badSale)
        XCTAssertFalse(TXBusinessV2Readback.verified(immutable, receipt: receipt, records: readbackRecords,
            events: [source, following, provenance], ledgerCursor: 22))
    }
}
