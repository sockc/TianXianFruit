import Foundation
import XCTest
@testable import TianXianFruit

final class BusinessCollectorDraftTests: XCTestCase {
    private let book = CloudBook(
        id: "ios-test-business-collectors",
        name: "iOS_TEST_营业多人录入", role: "OWNER",
        deleted: false, purged: false)

    private func session() -> StagingSession {
        StagingSession(token: "test", username: "test",
                       deviceID: "ios-qa-device", book: book,
                       scope: ScopedAccountEvidence(
                        instanceID: "production-scoped-testbook:" + book.id,
                        bookID: book.id),
                       host: StagingPurchasePolicy.productionURL)
    }

    private func catalog() -> [SyncEvent] {
        [
            SyncEvent(tableName: "store", syncId: "store-1",
                      rowVersion: 1, operation: "UPSERT",
                      payload: ["id": .number(11),
                                "name": .string("测试位置")]),
            SyncEvent(tableName: "partner", syncId: "partner-1",
                      rowVersion: 1, operation: "UPSERT",
                      payload: ["id": .number(101),
                                "name": .string("甲")]),
            SyncEvent(tableName: "partner", syncId: "partner-2",
                      rowVersion: 1, operation: "UPSERT",
                      payload: ["id": .number(102),
                                "name": .string("乙")])
        ]
    }

    private func lines() -> [BusinessCollectorDraftLine] {
        [
            BusinessCollectorDraftLine(id: "line-a",
                partnerSyncID: "partner-1", wechat: "60",
                alipay: "0", cash: "40"),
            BusinessCollectorDraftLine(id: "line-b",
                partnerSyncID: "partner-2", wechat: "40",
                alipay: "20", cash: "0")
        ]
    }

    private func draft(lines: [BusinessCollectorDraftLine]? = nil,
                       start: String = "16:00",
                       end: String = "00:10") -> TestDraft {
        TestDraft(id: "collectors-draft", account: "ios-test",
                  bookID: book.id, kind: .business,
                  date: "2026-11-20",
                  fields: [
                    "store_sync_id": "store-1",
                    "store_name": "测试位置",
                    "wechat": "100.00", "alipay": "20.00",
                    "cash": "40.00",
                    "expense": "0",
                    "opening_stock_value": "100",
                    "stock_left_value": "30",
                    "purchase_cost": "0",
                    "wechat_collector_sync_id": "",
                    "alipay_collector_sync_id": "",
                    "cash_collector_sync_id": "",
                    "expense_payer_sync_id": "",
                    "actual_start_time": start,
                    "actual_end_time": end
                  ], createdAt: Date(timeIntervalSince1970: 1795161600),
                  businessCollectors: lines)
    }

    func testExplicitSameChannelMultiCollectorCreatesExactAndroidSplits() throws {
        let input = draft(lines: lines())
        try TestWritePolicy.validate(input, book: book)
        let job = try BusinessUploadPolicy.prepare(
            input, sourceBook: book, session: session(),
            events: catalog(), now: Date(timeIntervalSince1970: 1795161600))
        XCTAssertEqual(job.payload["wechat_income"]?.decimal, 100)
        XCTAssertEqual(job.payload["alipay_income"]?.decimal, 20)
        XCTAssertEqual(job.payload["cash_income"]?.decimal, 40)
        XCTAssertEqual(job.payload["revenue"]?.decimal, 160)
        XCTAssertEqual(job.payload["profit"]?.decimal, 90)
        XCTAssertEqual(job.payload["actual_start_time"]?.text, "16:00")
        XCTAssertEqual(job.payload["actual_end_time"]?.text, "00:10")
        XCTAssertEqual(job.payload["wechat_collector_name"]?.text, "多人收款")
        let raw = try XCTUnwrap(job.payload["receipt_splits_json"]?.text)
        let data = try XCTUnwrap(raw.data(using: .utf8))
        let parts = try JSONDecoder().decode(
            [TestBusinessReceiptSplit].self, from: data)
        XCTAssertEqual(parts.count, 2)
        XCTAssertEqual(Set(parts.map(\.partnerID)), [101, 102])
        let first = try XCTUnwrap(parts.first(where: { $0.partnerID == 101 }))
        let second = try XCTUnwrap(parts.first(where: { $0.partnerID == 102 }))
        XCTAssertEqual(first.wechatIncome, 60)
        XCTAssertEqual(first.cashIncome, 40)
        XCTAssertEqual(second.wechatIncome, 40)
        XCTAssertEqual(second.alipayIncome, 20)
        XCTAssertTrue(BusinessUploadPolicy.verified(
            job, events: catalog() + [job.event]))
    }

    func testUnderAllocatedAndDuplicateCollectorsRejectBeforeNetwork() throws {
        var wrong = lines()
        wrong[1].wechat = "30"
        XCTAssertThrowsError(try TestWritePolicy.validate(
            draft(lines: wrong), book: book))
        var dup = lines()
        dup[1].partnerSyncID = "partner-1"
        XCTAssertThrowsError(try TestWritePolicy.validate(
            draft(lines: dup), book: book))
        var zero = lines()
        zero[1].wechat = "0"
        zero[1].alipay = "0"
        XCTAssertThrowsError(try TestWritePolicy.validate(
            draft(lines: zero), book: book))
    }

    func testUnknownPartnerRejectedWithOnlyTestBookCatalog() throws {
        var unrecognized = lines()
        unrecognized[1].partnerSyncID = "no-such-partner"
        let input = draft(lines: unrecognized)
        try TestWritePolicy.validate(input, book: book)
        XCTAssertThrowsError(try BusinessUploadPolicy.prepare(
            input, sourceBook: book, session: session(),
            events: catalog()))
    }

    func testClockValidationAndOvernightTimeDoNotDeductAnyReceipts() throws {
        XCTAssertTrue(BusinessCollectorDraftPolicy.validClock("16:00"))
        XCTAssertTrue(BusinessCollectorDraftPolicy.validClock("00:10"))
        XCTAssertFalse(BusinessCollectorDraftPolicy.validClock("24:00"))
        XCTAssertFalse(BusinessCollectorDraftPolicy.validClock("16:99"))
        XCTAssertFalse(BusinessCollectorDraftPolicy.validClock("6:00"))
        try BusinessCollectorDraftPolicy.validateTimes(
            ["actual_start_time": "16:00", "actual_end_time": "00:10"])
        XCTAssertThrowsError(try TestWritePolicy.validate(
            draft(lines: lines(), end: "16:00"), book: book))
        XCTAssertThrowsError(try TestWritePolicy.validate(
            draft(lines: lines(), end: ""), book: book))
        let job = try BusinessUploadPolicy.prepare(
            draft(lines: lines()), sourceBook: book,
            session: session(), events: catalog())
        XCTAssertEqual(job.payload["revenue"]?.decimal, 160)
        XCTAssertEqual(job.payload["profit"]?.decimal, 90)
    }

    func testOldSavedDraftJSONStillDecodesWithoutCollectorRows() throws {
        let old = draft(lines: nil, start: "", end: "")
        let bytes = try JSONEncoder().encode(old)
        var object = try XCTUnwrap(
            JSONSerialization.jsonObject(with: bytes) as? [String: Any])
        object.removeValue(forKey: "businessCollectors")
        object.removeValue(forKey: "purchaseItems")
        let legacy = try JSONSerialization.data(withJSONObject: object)
        let restored = try JSONDecoder().decode(TestDraft.self, from: legacy)
        XCTAssertNil(restored.businessCollectors)
        XCTAssertEqual(restored.fields["store_sync_id"], "store-1")
        try BusinessCollectorDraftPolicy.validateTimes(restored.fields)
    }

    func testDraftRemainsIsolatedAndImmutableWhenUploadIntentExists() throws {
        let folder = FileManager.default.temporaryDirectory
            .appendingPathComponent("business-collectors-" + UUID().uuidString)
        try FileManager.default.createDirectory(
            at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let store = try TestDraftStore(
            url: folder.appendingPathComponent("draft.sqlite"))
        let input = draft(lines: lines())
        try store.save(input)
        let restored = try XCTUnwrap(store.load(
            account: input.account, book: input.bookID).first)
        XCTAssertEqual(restored.businessCollectors, lines())
        let job = try BusinessUploadPolicy.prepare(
            restored, sourceBook: book, session: session(),
            events: catalog())
        try store.saveBusinessJob(job)
        XCTAssertThrowsError(try store.save(restored))
        XCTAssertThrowsError(try store.delete(
            id: restored.id, account: restored.account,
            book: restored.bookID))
    }
}
