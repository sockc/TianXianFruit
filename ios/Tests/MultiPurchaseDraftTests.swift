import XCTest
@testable import TianXianFruit

final class MultiPurchaseDraftTests: XCTestCase {
    private let book = CloudBook(
        id: "ios-test-multi-01", name: "iOS_TEST_多商品",
        role: "OWNER", deleted: false, purged: false)

    private func line(_ id: String, _ fruit: String, _ quantity: String,
                      _ price: String) -> PurchaseDraftLine {
        PurchaseDraftLine(id: id, fruitSyncID: "fruit-" + id,
                          fruitName: fruit, quantity: quantity, unit: "斤",
                          unitPrice: price, unitWeightJin: "0")
    }

    private func draft(_ lines: [PurchaseDraftLine]) throws -> TestDraft {
        try PurchaseDraftPolicy.buildDraft(
            id: "draft-one", account: "family", bookID: book.id,
            date: "2026-11-19", buyerSyncID: "buyer-sync",
            purchaseType: "PLANNED", remark: "三种商品",
            lines: lines, createdAt: Date(timeIntervalSince1970: 1795050000))
    }

    private func three() -> [PurchaseDraftLine] {
        [
            line("red", "无籽红提", "20", "3.50"),
            line("apple", "苹果", "30", "4.00"),
            line("banana", "香蕉", "25", "2.00")
        ]
    }

    func testThreeItemPurchaseSumsAllItemsCorrectly() throws {
        let result = try draft(three())
        XCTAssertEqual(result.purchaseItems?.count, 3)
        XCTAssertEqual(result.fields["total_cost"], "240.00")
        XCTAssertEqual(result.summary, "3 种商品 · 合计 ¥240.00")
        XCTAssertNoThrow(try TestWritePolicy.validate(result, book: book))
    }

    func testRoundEachLineToCentsBeforeAddingAndAllowZeroPrice() throws {
        let lines = [
            line("one", "商品 A", "3", "0.335"),
            line("two", "商品 B", "1", "0.03")
        ]
        XCTAssertThrowsError(try PurchaseDraftPolicy.total(lines))
        let valid = [
            line("one", "商品 A", "0.335", "3.00"),
            line("two", "商品 B", "1", "0.03")
        ]
        let actual = try PurchaseDraftPolicy.total(valid)
        XCTAssertEqual(PurchaseDraftPolicy.display(actual), "1.04")
        let free = try PurchaseDraftPolicy.total([
            line("free", "赠品", "1", "0")
        ])
        XCTAssertEqual(PurchaseDraftPolicy.display(free), "0.00")
    }

    func testDifferentUnitsDecimalsAndWeight() throws {
        let a = PurchaseDraftLine(
            id: "box", fruitSyncID: "fruit-box", fruitName: "红提",
            quantity: "2.5", unit: "箱", unitPrice: "30.00",
            unitWeightJin: "11.25")
        let b = PurchaseDraftLine(
            id: "jin", fruitSyncID: "fruit-jin", fruitName: "橙子",
            quantity: "5", unit: "斤", unitPrice: "6.00", unitWeightJin: "0")
        XCTAssertEqual(PurchaseDraftPolicy.display(
            try PurchaseDraftPolicy.total([a,b])), "105.00")
    }

    func testRejectInvalidPriceQuantityWeightDuplicateLineAndMissingFruit() throws {
        let badQuantity = line("invalid", "水果", "0", "3")
        XCTAssertThrowsError(try PurchaseDraftPolicy.total([badQuantity]))
        XCTAssertThrowsError(try PurchaseDraftPolicy.total([
            line("invalid", "水果", "-2", "3")
        ]))
        XCTAssertThrowsError(try PurchaseDraftPolicy.total([
            line("invalid", "水果", "1", "3.999")
        ]))
        var missing = line("missing", "水果", "1", "3")
        missing.fruitSyncID = ""
        XCTAssertThrowsError(try PurchaseDraftPolicy.total([missing]))
        var weight = line("weight", "水果", "1", "3")
        weight.unitWeightJin = "-1"
        XCTAssertThrowsError(try PurchaseDraftPolicy.total([weight]))
        let repeated = line("same-id", "水果", "1", "3")
        XCTAssertThrowsError(try PurchaseDraftPolicy.total([repeated,repeated]))
        XCTAssertThrowsError(try PurchaseDraftPolicy.total([]))
        XCTAssertThrowsError(try PurchaseDraftPolicy.total(
            (0..<31).map { line(String($0), "商品", "1", "1") }))
    }

    func testOrderTotalTamperingCannotPassPolicy() throws {
        var edited = try draft(three())
        edited.fields["total_cost"] = "900.00"
        XCTAssertThrowsError(try TestWritePolicy.validate(edited, book: book))
        let production = CloudBook(
            id: book.id, name: "天鲜果业正式账本",
            role: "OWNER", deleted: false, purged: false)
        XCTAssertThrowsError(try TestWritePolicy.validate(
            try draft(three()), book: production))
    }

    func testLegacySingleItemDraftDecodesAndConvertsOnlyInEditorPreview() throws {
        let legacy = TestDraft(
            id: "old-id", account: "family", bookID: book.id,
            kind: .purchase, date: "2026-11-19", fields: [
                "buyer_sync_id": "buyer-sync", "fruit_sync_id": "fruit-old",
                "fruit_name": "苹果", "quantity": "2", "unit": "箱",
                "total_cost": "80", "unit_weight_jin": "12",
                "purchase_type": "PLANNED", "remark": "旧版"
            ], createdAt: Date())
        let data = try JSONEncoder().encode(legacy)
        var dictionary = try XCTUnwrap(
            JSONSerialization.jsonObject(with: data) as? [String: Any])
        dictionary.removeValue(forKey: "purchaseItems")
        let oldBytes = try JSONSerialization.data(withJSONObject: dictionary)
        let loaded = try JSONDecoder().decode(TestDraft.self, from: oldBytes)
        XCTAssertNil(loaded.purchaseItems)
        XCTAssertEqual(loaded.fields["total_cost"], "80")
        let preview = PurchaseDraftPolicy.previewLegacy(loaded)
        XCTAssertEqual(preview.count, 1)
        XCTAssertEqual(preview[0].unitPrice, "40.00")
        XCTAssertEqual(preview[0].fruitSyncID, "fruit-old")
        XCTAssertFalse(TestWritePolicy.cloudSubmissionIsAllowed(loaded))
    }

    func testMultiItemUploadNeedsCompleteServerFruitAndBuyerReferences() throws {
        let purchase = try draft(three())
        let scope = ScopedAccountEvidence(
            instanceID: "production-scoped-testbook:" + book.id,
            bookID: book.id)
        XCTAssertThrowsError(try StagingPayloadBuilder.prepare(
            purchase, sourceBook: book, stageBook: book,
            stageHost: StagingPurchasePolicy.productionURL,
            scope: scope, username: "test_user", deviceID: "test_phone",
            available: []))
    }

    func testLocalSQLiteEditDuplicateAndRebootWithThreeItems() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("tx-multi-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let path = root.appendingPathComponent("local.sqlite")
        let initial = try draft(three())
        do {
            let store = try TestDraftStore(url: path)
            try store.save(initial)
            var changed = initial
            var newLines = try XCTUnwrap(changed.purchaseItems)
            newLines[0].unitPrice = "4.00"
            changed.purchaseItems = newLines
            changed.fields["total_cost"] = "250.00"
            try TestWritePolicy.validate(changed, book: book)
            try store.save(changed)
            let duplicated = TestDraft(
                id: UUID().uuidString, account: changed.account,
                bookID: changed.bookID, kind: changed.kind,
                date: changed.date, fields: changed.fields, createdAt: Date(),
                purchaseItems: changed.purchaseItems?.map { $0.duplicated() })
            try store.save(duplicated)
            XCTAssertEqual(try store.load(account: "family", book: book.id).count, 2)
            XCTAssertTrue(try store.load(account: "other", book: book.id).isEmpty)
        }
        let reopened = try TestDraftStore(url: path)
        let records = try reopened.load(account: "family", book: book.id)
        XCTAssertEqual(records.count, 2)
        XCTAssertEqual(records.first(where: { $0.id == initial.id })?
                       .purchaseItems?.count, 3)
        XCTAssertEqual(records.first(where: { $0.id == initial.id })?
                       .fields["total_cost"], "250.00")
    }

    func testStagedPurchaseDraftCannotBeEditedOrDeletedButCanBeCopied() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("tx-multi-staged-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let db = try TestDraftStore(url: root.appendingPathComponent("local.sqlite"))
        let draft = try self.draft(three())
        try db.save(draft)
        let job = StagingPurchaseJob(
            id: draft.id, sourceAccount: draft.account, bookID: draft.bookID,
            stageHost: StagingPurchasePolicy.productionURL,
            stageInstanceID: "production-scoped-testbook:" + book.id,
            stageUsername: "test_user", deviceID: "test_device",
            orderSyncID: "ios-stage-order-" + draft.id,
            itemSyncID: "ios-stage-item-" + draft.id,
            orderRemoteID: 3_000_000_000_001,
            itemRemoteID: 3_000_000_000_002,
            orderPayload: [:], itemPayload: [:],
            createdAt: Date(), state: .ready, lastMessage: "测试")
        try db.saveStagingJob(job)
        XCTAssertThrowsError(try db.save(draft))
        XCTAssertThrowsError(try db.delete(id: draft.id,
                                           account: draft.account, book: draft.bookID))
        let copy = TestDraft(
            id: UUID().uuidString, account: draft.account, bookID: draft.bookID,
            kind: .purchase, date: draft.date,
            fields: draft.fields, createdAt: Date(),
            purchaseItems: draft.purchaseItems?.map { $0.duplicated() })
        XCTAssertNoThrow(try db.save(copy))
        XCTAssertEqual(try db.load(account: "family", book: book.id).count, 2)
    }
}
