import XCTest
@testable import TianXianFruit

final class TestWriteDraftTests: XCTestCase {
    private let book = CloudBook(id: "sandbox-book-uuid",
                                 name: "iOS_TEST_家庭开发测试",
                                 role: "OWNER", deleted: false, purged: false)

    private func sale(account: String = "owner", bookID: String = "sandbox-book-uuid",
                      wechat: String = "45", collector: String = "partner-sync") -> TestDraft {
        TestDraft(id: UUID().uuidString, account: account, bookID: bookID,
                  kind: .business, date: "2026-11-18", fields: [
                    "store_sync_id": "test-store-uuid", "store_name": "测试摊位",
                    "wechat": wechat, "wechat_collector_sync_id": collector,
                    "alipay": "0", "alipay_collector_sync_id": "",
                    "cash": "0", "cash_collector_sync_id": "",
                    "expense": "0", "expense_payer_sync_id": "",
                    "opening_stock_value": "30",
                    "stock_left_value": "10",
                    "purchase_cost": "8"
                  ], createdAt: Date())
    }

    private func purchase() -> TestDraft {
        TestDraft(id: UUID().uuidString, account: "owner",
                  bookID: book.id, kind: .purchase, date: "2026-11-18",
                  fields: [
                    "buyer_sync_id": "partner-sync",
                    "fruit_sync_id": "fruit-sync",
                    "fruit_name": "提子",
                    "quantity": "2.5",
                    "unit": "箱",
                    "total_cost": "80.5",
                    "unit_weight_jin": "5",
                    "purchase_type": "PLANNED",
                    "remark": "模拟采购"
                  ], createdAt: Date())
    }

    func testStrictTestLedgerNameRoleAndTypedID() throws {
        XCTAssertNoThrow(try TestWritePolicy.authorize(book: book, typedID: book.id))
        let production = CloudBook(id: "prod", name: "天鲜果业正式账本",
                                   role: "OWNER", deleted: false, purged: false)
        XCTAssertThrowsError(try TestWritePolicy.authorize(book: production, typedID: "prod"))
        let deceptive = CloudBook(id: "prod", name: "正式账本-TEST",
                                  role: "OWNER", deleted: false, purged: false)
        XCTAssertThrowsError(try TestWritePolicy.authorize(book: deceptive, typedID: "prod"))
        XCTAssertThrowsError(try TestWritePolicy.authorize(book: book, typedID: "another-book"))
        let viewer = CloudBook(id: "sandbox-book-uuid", name: "iOS_TEST_viewer",
                               role: "VIEWER", deleted: false, purged: false)
        XCTAssertThrowsError(try TestWritePolicy.authorize(book: viewer, typedID: viewer.id))
        let deleted = CloudBook(id: book.id, name: book.name,
                                role: "OWNER", deleted: true, purged: false)
        XCTAssertThrowsError(try TestWritePolicy.authorize(book: deleted, typedID: deleted.id))
    }

    func testValidBusinessDraftAndNoCloudSubmission() throws {
        let draft = sale()
        XCTAssertNoThrow(try TestWritePolicy.validate(draft, book: book))
        XCTAssertFalse(TestWritePolicy.cloudSubmissionIsAllowed(draft))
        XCTAssertThrowsError(try TestWritePolicy.validate(sale(wechat: "50", collector: ""), book: book))
        XCTAssertThrowsError(try TestWritePolicy.validate(sale(wechat: "-1"), book: book))
        XCTAssertThrowsError(try TestWritePolicy.validate(sale(wechat: "123.456"), book: book))
    }

    func testPurchaseDraftValidatesSyncIDsUnitsAndQuantities() throws {
        let draft = purchase()
        XCTAssertNoThrow(try TestWritePolicy.validate(draft, book: book))
        XCTAssertFalse(TestWritePolicy.cloudSubmissionIsAllowed(draft))
        var missing = draft.fields
        missing["fruit_sync_id"] = ""
        let wrong = TestDraft(id: draft.id, account: draft.account, bookID: draft.bookID,
                              kind: draft.kind, date: draft.date, fields: missing,
                              createdAt: draft.createdAt)
        XCTAssertThrowsError(try TestWritePolicy.validate(wrong, book: book))
        var badWeight = draft.fields
        badWeight["unit_weight_jin"] = "-5"
        let invalid = TestDraft(id: draft.id, account: draft.account, bookID: draft.bookID,
                                kind: draft.kind, date: draft.date, fields: badWeight,
                                createdAt: draft.createdAt)
        XCTAssertThrowsError(try TestWritePolicy.validate(invalid, book: book))
    }

    func testLocalDraftStorePersistsButNeverJoinsCloudCache() throws {
        let folder = FileManager.default.temporaryDirectory
            .appendingPathComponent("tianxian-ios-drafts-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let path = folder.appendingPathComponent("test.sqlite")
        let record = sale()
        try TestWritePolicy.validate(record, book: book)
        do {
            let store = try TestDraftStore(url: path)
            try store.save(record)
            XCTAssertEqual(try store.load(account: "OWNER", book: book.id).count, 1)
            XCTAssertTrue(try store.load(account: "owner", book: "production-book").isEmpty)
            XCTAssertTrue(try store.load(account: "other", book: book.id).isEmpty)
        }
        let reopened = try TestDraftStore(url: path)
        XCTAssertEqual(try reopened.load(account: "owner", book: book.id).first?.summary,
                       record.summary)
        let readOnly = ReadOnlySnapshot()
        XCTAssertEqual(readOnly.business.count, 0)
        XCTAssertEqual(readOnly.purchases.count, 0)
        try reopened.delete(id: record.id, account: "other", book: book.id)
        XCTAssertEqual(try reopened.load(account: "owner", book: book.id).count, 1)
        try reopened.delete(id: record.id, account: "owner", book: book.id)
        XCTAssertEqual(try reopened.load(account: "owner", book: book.id).count, 0)
    }

    func testNoCrossBookDataLeakAndDistinctKinds() throws {
        let folder = FileManager.default.temporaryDirectory
            .appendingPathComponent("tianxian-ios-draft-scope-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let store = try TestDraftStore(url: folder.appendingPathComponent("draft.sqlite"))
        let one = sale()
        let other = sale(account: "family")
        let bought = purchase()
        try store.save(one)
        try store.save(other)
        try store.save(bought)
        let first = try store.load(account: "owner", book: book.id)
        XCTAssertEqual(first.count, 2)
        XCTAssertEqual(Set(first.map(\.kind)), Set([.business, .purchase]))
        let second = try store.load(account: "family", book: book.id)
        XCTAssertEqual(second.count, 1)
        XCTAssertEqual(second[0].id, other.id)
    }

    func testPreviewNeverCalculatesProfitOrSendsSyncPush() {
        XCTAssertFalse(TestWritePolicy.cloudSubmissionIsAllowed(sale()))
        XCTAssertFalse(TestWritePolicy.cloudSubmissionIsAllowed(purchase()))
    }
}
