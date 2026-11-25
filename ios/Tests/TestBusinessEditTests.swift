import XCTest
@testable import TianXianFruit

final class TestBusinessEditTests: XCTestCase {
    private let book = CloudBook(id: "ios-test-edit-001",
        name: "iOS_TEST_更正", role: "OWNER", deleted: false, purged: false)

    private func session() -> StagingSession {
        StagingSession(token: "TEST", username: "test-user",
            deviceID: "ios-qa", book: book,
            scope: ScopedAccountEvidence(
                instanceID: "production-scoped-testbook:" + book.id,
                bookID: book.id),
            host: StagingPurchasePolicy.productionURL)
    }

    private func record(splits: [TestBusinessReceiptSplit]? = nil,
                        version: Int64 = 4) throws -> SyncEvent {
        let items = splits ?? [
            TestBusinessReceiptSplit(partnerID: 11, partnerName: "甲",
                amount: 140, wechatIncome: 100, alipayIncome: 0,
                cashIncome: 40),
            TestBusinessReceiptSplit(partnerID: 12, partnerName: "乙",
                amount: 60, wechatIncome: 0, alipayIncome: 60,
                cashIncome: 0)
        ]
        let data = try JSONEncoder().encode(items)
        let json = try XCTUnwrap(String(data: data, encoding: .utf8))
        let key = BusinessUploadPolicy.androidSyncID(
            date: "2026-11-20", storeSyncID: "store-id-1")
        return SyncEvent(tableName: "store_daily_record",
            syncId: key, rowVersion: version, operation: "UPSERT",
            payload: [
                "id": .number(900), "date": .string("2026-11-20"),
                "store_id": .number(91),
                "store_name": .string("测试摊位"),
                "store_sync_id": .string("store-id-1"),
                "wechat_income": .number(100),
                "alipay_income": .number(60),
                "cash_income": .number(40),
                "revenue": .number(200),
                "expense": .number(20),
                "opening_stock_value": .number(80),
                "stock_left_value": .number(30),
                "profit": .number(130),
                "purchase_cost": .number(0),
                "new_customer": .number(1),
                "old_customer": .number(2),
                "receipt_splits_json": .string(json),
                "wechat_collector_id": .number(0),
                "alipay_collector_id": .number(0),
                "cash_collector_id": .number(0),
                "expense_payer_id": .number(12),
                "expense_payer_name": .string("乙"),
                "deleted": .number(0),
                "row_version": .number(Double(version)),
                "created_at": .number(10000), "updated_at": .number(20000)
            ])
    }

    private func amounts(wechat: String = "120", cash: String = "40") -> TestBusinessEditValues {
        TestBusinessEditValues(wechat: wechat, alipay: "60", cash: cash,
            expense: "20", opening: "80", closing: "30")
    }

    private func prepare(_ base: SyncEvent,
                         values: TestBusinessEditValues? = nil,
                         events: [SyncEvent]? = nil) throws -> TestBusinessEditJob {
        try TestBusinessEditPolicy.prepare(original: base,
            values: values ?? amounts(),
            events: events ?? [base], book: book,
            account: "local-account", session: session(),
            now: Date(timeIntervalSince1970: 1795161600),
            jobID: "edit-1")
    }

    func testUpdatesRevenueProfitAndPreservesCollectorsAndStableID() throws {
        let base = try record()
        let job = try prepare(base)
        XCTAssertEqual(job.original.syncId, job.proposed.syncId)
        XCTAssertEqual(job.original.rowVersion + 1, job.proposed.rowVersion)
        XCTAssertEqual(job.proposed.payload["row_version"]?.decimal, 5)
        XCTAssertEqual(job.proposed.payload["revenue"]?.decimal, 220)
        XCTAssertEqual(job.proposed.payload["profit"]?.decimal, 150)
        XCTAssertEqual(job.proposed.payload["purchase_cost"]?.decimal, 0)
        XCTAssertEqual(job.proposed.payload["new_customer"]?.decimal, 1)
        XCTAssertEqual(job.proposed.payload["old_customer"]?.decimal, 2)
        XCTAssertEqual(job.proposed.payload["expense_payer_id"],
                       base.payload["expense_payer_id"])
        let raw = try XCTUnwrap(job.proposed.payload["receipt_splits_json"]?.text)
        let data = try XCTUnwrap(raw.data(using: .utf8))
        let splits = try JSONDecoder().decode([TestBusinessReceiptSplit].self, from: data)
        XCTAssertEqual(splits.count, 2)
        XCTAssertEqual(splits.reduce(0) { $0 + $1.amount }, 220)
        XCTAssertEqual(splits.first?.wechatIncome, 120)
        XCTAssertEqual(splits.first?.cashIncome, 40)
        XCTAssertEqual(splits.last?.alipayIncome, 60)
        XCTAssertTrue(TestBusinessEditPolicy.confirmed(job, events: [job.proposed]))
    }

    func testVersionChangeOrRemoteDeleteRefusesUpdate() throws {
        let base = try record()
        let job = try prepare(base)
        XCTAssertTrue(TestBusinessEditPolicy.unchanged(job, events: [base]))
        let changed = try record(version: 5)
        XCTAssertFalse(TestBusinessEditPolicy.unchanged(job, events: [changed]))
        XCTAssertThrowsError(try prepare(base, events: [changed]))
        let deleted = SyncEvent(tableName: base.tableName,
            syncId: base.syncId, rowVersion: 5,
            operation: "DELETE", payload: base.payload)
        XCTAssertFalse(TestBusinessEditPolicy.unchanged(job, events: [deleted]))
        XCTAssertThrowsError(try prepare(base, events: [deleted]))
    }

    func testSameDayPositionAliasBlocksUpdate() throws {
        let base = try record()
        let duplicate = SyncEvent(tableName: "store_daily_record",
            syncId: "legacy-alias", rowVersion: 1, operation: "UPSERT",
            payload: base.payload)
        XCTAssertThrowsError(try prepare(base, events: [base, duplicate]))
    }

    func testMultiCollectorOneChannelIsNotDividedAutomatically() throws {
        let split = [
            TestBusinessReceiptSplit(partnerID: 11, partnerName: "甲",
                amount: 90, wechatIncome: 50, alipayIncome: 0,
                cashIncome: 40),
            TestBusinessReceiptSplit(partnerID: 12, partnerName: "乙",
                amount: 110, wechatIncome: 50, alipayIncome: 60,
                cashIncome: 0)
        ]
        let base = try record(splits: split)
        XCTAssertThrowsError(try prepare(base))
        // An unchanged multi-collector channel remains untouched when
        // only a channel with an unambiguous collector is modified.
        let job = try prepare(base, values: amounts(wechat: "100", cash: "45"))
        XCTAssertEqual(job.proposed.payload["cash_income"]?.decimal, 45)
        XCTAssertEqual(job.proposed.payload["revenue"]?.decimal, 205)
    }

    func testTamperedSplitAndNoChangeAreRejected() throws {
        let base = try record()
        let values = TestBusinessEditValues(base)
        XCTAssertThrowsError(try prepare(base, values: values))
        var altered = base.payload
        altered["wechat_income"] = .number(99)
        let corrupt = SyncEvent(tableName: base.tableName,
            syncId: base.syncId, rowVersion: base.rowVersion,
            operation: "UPSERT", payload: altered)
        XCTAssertThrowsError(try prepare(corrupt))
    }

    func testOfficialBookNotWritable() throws {
        let base = try record()
        let formal = CloudBook(id: book.id, name: "正式账本",
            role: "OWNER", deleted: false, purged: false)
        XCTAssertThrowsError(try TestBusinessEditPolicy.prepare(
            original: base, values: amounts(), events: [base],
            book: formal, account: "local", session: session()))
    }

    func testOutboxImmutableAndUnknownNeverReturnsToReady() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("test-edit-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: root,
            withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let path = root.appendingPathComponent("edits.sqlite")
        let store = try TestDraftStore(url: path)
        var job = try prepare(record())
        try store.saveBusinessEditJob(job)
        job.state = .sending
        try store.saveBusinessEditJob(job)
        var retry = job
        retry.state = .ready
        XCTAssertThrowsError(try store.saveBusinessEditJob(retry))
        job.state = .needsReview
        try store.saveBusinessEditJob(job)
        // Proposed payload is immutable by design; no mutation exists.
        XCTAssertEqual(try store.businessEditJob(id: job.id,
            account: job.sourceAccount, book: job.bookID)?.state, .needsReview)
        let reopened = try TestDraftStore(url: path)
        XCTAssertEqual(try reopened.businessEditJob(id: job.id,
            account: job.sourceAccount, book: job.bookID)?.state, .needsReview)
        job.state = .complete
        try store.saveBusinessEditJob(job)
        XCTAssertThrowsError(try store.saveBusinessEditJob(retry))
    }
    private func splitValues(_ rows: [TestBusinessSplitInput],
                             wechat: String = "100",
                             alipay: String = "60",
                             cash: String = "40") -> TestBusinessEditValues {
        TestBusinessEditValues(wechat: wechat, alipay: alipay,
                               cash: cash, expense: "20",
                               opening: "80", closing: "30", splits: rows)
    }

    func testPerPersonMultiCollectorEditsBothWechatReceiversWithoutGuessing() throws {
        let originalSplits = [
            TestBusinessReceiptSplit(partnerID: 11, partnerName: "甲",
                amount: 90, wechatIncome: 50, alipayIncome: 0,
                cashIncome: 40),
            TestBusinessReceiptSplit(partnerID: 12, partnerName: "乙",
                amount: 110, wechatIncome: 50, alipayIncome: 60,
                cashIncome: 0)
        ]
        let base = try record(splits: originalSplits)
        let rows = [
            TestBusinessSplitInput(partnerID: 11, partnerName: "甲",
                wechat: "70", alipay: "0", cash: "40"),
            TestBusinessSplitInput(partnerID: 12, partnerName: "乙",
                wechat: "50", alipay: "60", cash: "0")
        ]
        let job = try prepare(base, values: splitValues(
            rows, wechat: "120"))
        XCTAssertEqual(job.proposed.payload["revenue"]?.decimal, 220)
        XCTAssertEqual(job.proposed.payload["profit"]?.decimal, 150)
        XCTAssertEqual(job.proposed.payload["cash_collector_id"],
                       base.payload["cash_collector_id"])
        let json = try XCTUnwrap(job.proposed.payload["receipt_splits_json"]?.text)
        let data = try XCTUnwrap(json.data(using: .utf8))
        let parts = try JSONDecoder().decode(
            [TestBusinessReceiptSplit].self, from: data)
        XCTAssertEqual(parts.map(\.partnerID), [11, 12])
        XCTAssertEqual(parts[0].wechatIncome, 70)
        XCTAssertEqual(parts[1].wechatIncome, 50)
        XCTAssertTrue(TestBusinessEditPolicy.confirmed(
            job, events: [job.proposed]))
    }

    func testPerPersonReallocationWithSameTotalsIsARealEdit() throws {
        let base = try record(splits: [
            TestBusinessReceiptSplit(partnerID: 11, partnerName: "甲",
                amount: 90, wechatIncome: 50, alipayIncome: 0,
                cashIncome: 40),
            TestBusinessReceiptSplit(partnerID: 12, partnerName: "乙",
                amount: 110, wechatIncome: 50, alipayIncome: 60,
                cashIncome: 0)
        ])
        let rows = [
            TestBusinessSplitInput(partnerID: 11, partnerName: "甲",
                wechat: "60", alipay: "0", cash: "40"),
            TestBusinessSplitInput(partnerID: 12, partnerName: "乙",
                wechat: "40", alipay: "60", cash: "0")
        ]
        let job = try prepare(base, values: splitValues(rows))
        XCTAssertEqual(job.proposed.payload["revenue"]?.decimal, 200)
        XCTAssertEqual(job.proposed.payload["profit"]?.decimal, 130)
        XCTAssertNotEqual(job.original.payload["receipt_splits_json"],
                          job.proposed.payload["receipt_splits_json"])
    }

    func testUnknownOrRemovedCollectorAndZeroedCollectorAreRejected() throws {
        let base = try record()
        let originalRows = TestBusinessSplitInput.from(base)
        XCTAssertEqual(originalRows.count, 2)
        XCTAssertThrowsError(try prepare(base, values: splitValues(
            Array(originalRows.prefix(1)), wechat: "100")))
        var renamed = originalRows
        renamed[0] = TestBusinessSplitInput(
            partnerID: 11, partnerName: "冒名收款",
            wechat: "100", alipay: "0", cash: "40")
        XCTAssertThrowsError(try prepare(base, values: splitValues(renamed)))
        var zeroed = originalRows
        zeroed[0] = TestBusinessSplitInput(
            partnerID: 11, partnerName: "甲",
            wechat: "0", alipay: "0", cash: "0")
        XCTAssertThrowsError(try prepare(base, values: splitValues(
            zeroed, wechat: "0", cash: "0")))
    }

    func testCorruptedOriginalCollectorTotalsAreRejectedBeforeMutation() throws {
        let base = try record()
        var fields = base.payload
        fields["wechat_income"] = .number(99)
        let corrupted = SyncEvent(
            tableName: base.tableName, syncId: base.syncId,
            rowVersion: base.rowVersion,
            operation: base.operation, payload: fields)
        XCTAssertThrowsError(try prepare(corrupted))
        let explicit = splitValues(TestBusinessSplitInput.from(corrupted))
        XCTAssertThrowsError(try prepare(corrupted, values: explicit))
    }

    func testExistingProfitMismatchAndDuplicatePartnerIDCannotBeOverwritten() throws {
        let base = try record()
        var wrongProfit = base.payload
        wrongProfit["profit"] = .number(5)
        let corrupt = SyncEvent(tableName: base.tableName,
            syncId: base.syncId, rowVersion: base.rowVersion,
            operation: "UPSERT", payload: wrongProfit)
        XCTAssertThrowsError(try prepare(corrupt))

        let duplicated = try record(splits: [
            TestBusinessReceiptSplit(partnerID: 11, partnerName: "甲",
                amount: 140, wechatIncome: 100, alipayIncome: 0,
                cashIncome: 40),
            TestBusinessReceiptSplit(partnerID: 11, partnerName: "重复身份",
                amount: 60, wechatIncome: 0, alipayIncome: 60,
                cashIncome: 0)
        ])
        XCTAssertThrowsError(try prepare(duplicated))
    }

    func testUnknownSettlementTombstoneDateFailsClosed() throws {
        let base = try record()
        let tombstone = SyncEvent(
            tableName: "daily_cash_settlement",
            syncId: "redacted-tombstone-1",
            rowVersion: 3, operation: "DELETE",
            payload: ["deleted": .number(1)])
        XCTAssertThrowsError(try prepare(base, events: [base, tombstone]))
    }

    func testSettledDateBlockedForProfitAndActualTransfers() throws {
        let base = try record()
        let distribution = SyncEvent(
            tableName: "profit_distribution", syncId: "dist-1",
            rowVersion: 1, operation: "UPSERT",
            payload: ["date": .string("2026-11-20")])
        XCTAssertTrue(TestBusinessEditPolicy.hasDerivedSettlement(
            date: "2026-11-20", events: [base, distribution]))
        XCTAssertThrowsError(try prepare(base, events: [base, distribution]))

        let transfer = SyncEvent(
            tableName: "settlement_transfer", syncId: "transfer-1",
            rowVersion: 2, operation: "UPSERT",
            payload: [
                "date": .string("2026-11-22"),
                "range_start_date": .string("2026-11-18"),
                "range_end_date": .string("2026-11-21"),
                "settled_amount": .number(20)
            ])
        XCTAssertThrowsError(try prepare(base, events: [base, transfer]))
        let job = try prepare(base)
        XCTAssertFalse(TestBusinessEditPolicy.unchanged(
            job, events: [base, transfer]))

        // A historic settlement tombstone still requires manual review.
        let deleted = SyncEvent(tableName: "daily_cash_settlement",
            syncId: "old-cash-1", rowVersion: 2, operation: "DELETE",
            payload: ["date": .string("2026-11-20"), "deleted": .number(1)])
        XCTAssertThrowsError(try prepare(base, events: [base, deleted]))
    }

    func testExactAndroidInt64CollectorAndExpenseIDsSurviveEditReadback() throws {
        let base = try record()
        var payload = base.payload
        payload["id"] = .exactInteger(79152283000000002)
        payload["store_id"] = .exactInteger(79152283000000001)
        payload["wechat_collector_id"] = .exactInteger(79152283000000002)
        payload["expense_payer_id"] = .exactInteger(79152283000000002)
        let exact = SyncEvent(tableName: base.tableName, syncId: base.syncId,
            rowVersion: base.rowVersion, operation: base.operation, payload: payload)
        let job = try prepare(exact)
        XCTAssertEqual(job.proposed.payload["wechat_collector_id"],
                       .exactInteger(79152283000000002))
        XCTAssertEqual(job.proposed.payload["expense_payer_id"],
                       .exactInteger(79152283000000002))
        XCTAssertTrue(TestBusinessEditPolicy.confirmed(
            job, events: [job.proposed]),
            "A correct Int64 cloud readback must not be rejected as a non-Double")
        var tampered = job.proposed.payload
        tampered["expense_payer_id"] = .exactInteger(79152283000000003)
        let wrong = SyncEvent(tableName: job.proposed.tableName,
            syncId: job.proposed.syncId, rowVersion: job.proposed.rowVersion,
            operation: job.proposed.operation, payload: tampered)
        XCTAssertFalse(TestBusinessEditPolicy.confirmed(
            job, events: [wrong]),
            "Adjacent 17-digit numeric identities must not be conflated")
    }

}
