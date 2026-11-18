import Foundation
import XCTest
@testable import TianXianFruit

final class StagingPurchaseTests: XCTestCase {
    private let book = CloudBook(id: "ios-test-book-copy-1",
                                 name: "iOS_TEST_家庭测试",
                                 role: "OWNER", deleted: false, purged: false)

    private func user(_ role: String = "USER") -> Account {
        Account(username: "test_editor", displayName: "测试用户", systemRole: role)
    }

    private func scope() throws -> ScopedAccountEvidence {
        try StagingPurchasePolicy.requireBookScope(
            books: [book], account: user(), sourceBook: book)
    }

    private func draft() -> TestDraft {
        TestDraft(id: "93058B47-EDAB-45AE-8171-5F613BE1AA38",
                  account: "owner", bookID: book.id, kind: .purchase,
                  date: "2026-11-18", fields: [
                    "buyer_sync_id": "buyer-shared-1", "fruit_sync_id": "fruit-shared-1",
                    "fruit_name": "提子", "quantity": "2.5", "unit": "箱",
                    "total_cost": "80.5", "unit_weight_jin": "5",
                    "purchase_type": "PLANNED", "remark": "测试采购"
                  ], createdAt: Date(timeIntervalSince1970: 1794950000))
    }

    private func refs() -> [SyncEvent] {
        [
            SyncEvent(tableName: "partner", syncId: "buyer-shared-1",
                      rowVersion: 2, operation: "UPSERT", payload: [
                        "id": .number(101), "name": .string("测试采购人")
                      ]),
            SyncEvent(tableName: "fruit", syncId: "fruit-shared-1",
                      rowVersion: 1, operation: "UPSERT", payload: [
                        "id": .number(202), "name": .string("提子")
                      ])
        ]
    }

    private func job() throws -> StagingPurchaseJob {
        try StagingPayloadBuilder.prepare(
            draft(), sourceBook: book, stageBook: book,
            stageHost: StagingPurchasePolicy.productionURL,
            scope: scope(), username: "test_editor",
            deviceID: "test-device", available: refs())
    }

    func testOnlyCanonicalOfficialHTTPSHostIsAccepted() {
        for candidate in [
            "http://sync.830888.xyz", "https://other.example.com",
            "https://sync.830888.xyz.evil.com",
            "https://localhost", "https://127.0.0.1",
            "https://sync.830888.xyz:8443",
            "https://user:password@sync.830888.xyz",
            "https://sync.830888.xyz/other",
            "https://sync.830888.xyz?book_id=production",
            "not-a-url"
        ] {
            XCTAssertThrowsError(try StagingPurchasePolicy.validateURL(candidate),
                                 candidate)
        }
        XCTAssertEqual(try? StagingPurchasePolicy.validateURL(
            "https://sync.830888.xyz").absoluteString,
                       StagingPurchasePolicy.productionURL)
    }

    func testDedicatedAccountMustHaveExactlyOneTestBookAndNormalRole() throws {
        let matched = try scope()
        XCTAssertEqual(matched.bookID, book.id)
        XCTAssertEqual(matched.instanceID,
                       "production-scoped-testbook:" + book.id)

        let other = CloudBook(id: "production-book-id",
                              name: "天鲜果业正式账本", role: "OWNER",
                              deleted: false, purged: false)
        XCTAssertThrowsError(try StagingPurchasePolicy.requireBookScope(
            books: [book, other], account: user(), sourceBook: book))
        XCTAssertThrowsError(try StagingPurchasePolicy.requireBookScope(
            books: [], account: user(), sourceBook: book))
        XCTAssertThrowsError(try StagingPurchasePolicy.requireBookScope(
            books: [book], account: user("SUPERADMIN"), sourceBook: book))
        XCTAssertThrowsError(try StagingPurchasePolicy.requireBookScope(
            books: [book], account: user("ADMIN"), sourceBook: book))
        XCTAssertThrowsError(try StagingPurchasePolicy.requireBookScope(
            books: [book], account: user("UNKNOWN"), sourceBook: book))
        XCTAssertThrowsError(try StagingPurchasePolicy.requireBookScope(
            books: [other], account: user(), sourceBook: book))
        let viewer = CloudBook(id: book.id, name: book.name, role: "VIEWER",
                               deleted: false, purged: false)
        XCTAssertThrowsError(try StagingPurchasePolicy.requireBookScope(
            books: [viewer], account: user(), sourceBook: book))
        let masquerade = CloudBook(id: book.id, name: "天鲜果业正式账本",
                                   role: "OWNER", deleted: false, purged: false)
        XCTAssertThrowsError(try StagingPurchasePolicy.requireBookScope(
            books: [masquerade], account: user(), sourceBook: book))
    }

    func testStableParentChildIdentityAndExactAmount() throws {
        let a = try job()
        let b = try job()
        XCTAssertEqual(a.orderRemoteID, b.orderRemoteID)
        XCTAssertEqual(a.itemRemoteID, b.itemRemoteID)
        XCTAssertNotEqual(a.orderRemoteID, a.itemRemoteID)
        XCTAssertEqual(a.orderPayload["id"]?.numberValue, Double(a.orderRemoteID))
        XCTAssertEqual(a.itemPayload["order_id"]?.numberValue, Double(a.orderRemoteID))
        XCTAssertEqual(a.orderPayload["buyer_id"]?.numberValue, 101)
        XCTAssertEqual(a.itemPayload["fruit_id"]?.numberValue, 202)
        XCTAssertEqual(a.orderPayload["total_cost"]?.decimal, 80.5)
        XCTAssertEqual(a.itemPayload["total_cost"]?.decimal, 80.5)
        XCTAssertEqual(a.itemPayload["quantity"]?.decimal, 2.5)
        XCTAssertEqual(a.itemPayload["unit_price"]?.decimal, 32.2)
        XCTAssertEqual(a.stageHost, StagingPurchasePolicy.productionURL)
        XCTAssertEqual(a.stageInstanceID, "production-scoped-testbook:" + book.id)
    }

    func testWrongBookAndMissingReferencesFailBeforeUpload() throws {
        XCTAssertThrowsError(try StagingPayloadBuilder.prepare(
            draft(), sourceBook: book, stageBook: book,
            stageHost: StagingPurchasePolicy.productionURL,
            scope: scope(), username: "test_editor", deviceID: "test-device",
            available: [refs()[0]]))
        let different = CloudBook(id: "another-test-book",
                                  name: "iOS_TEST_其他", role: "OWNER",
                                  deleted: false, purged: false)
        XCTAssertThrowsError(try StagingPayloadBuilder.prepare(
            draft(), sourceBook: book, stageBook: different,
            stageHost: StagingPurchasePolicy.productionURL,
            scope: scope(), username: "test_editor", deviceID: "test-device",
            available: refs()))
        XCTAssertThrowsError(try StagingPayloadBuilder.prepare(
            draft(), sourceBook: book, stageBook: book,
            stageHost: "https://untrusted.example.com",
            scope: scope(), username: "test_editor", deviceID: "test-device",
            available: refs()))
    }

    func testAcknowledgementMustMatchExactEventWithoutConflict() {
        let event = SyncEvent(tableName: "purchase_item", syncId: "child",
                              rowVersion: 1, operation: "UPSERT", payload: [:])
        let accepted = StageSyncAck(accepted: [
            .init(tableName: "purchase_item", syncID: "child", rowVersion: 1)
        ], conflicts: [])
        XCTAssertTrue(accepted.isAccepted(event))
        XCTAssertFalse(StageSyncAck(accepted: [
            .init(tableName: "purchase_order", syncID: "child", rowVersion: 1)
        ], conflicts: []).isAccepted(event))
        XCTAssertFalse(StageSyncAck(accepted: accepted.accepted, conflicts: [
            .init(tableName: "purchase_item", syncID: "child", serverVersion: 2)
        ]).isAccepted(event))
    }

    func testImmutableOutboxScopeAndNoBlindRetry() throws {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("onebook-outbox-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let url = dir.appendingPathComponent("draft.sqlite")
        let original = try job()
        do {
            let store = try TestDraftStore(url: url)
            try store.saveStagingJob(original)
            var sending = original
            sending.state = .sendingOrder
            try store.saveStagingJob(sending)
            var needs = sending
            needs.state = .needsReview
            needs.lastMessage = "超时，不自动重发"
            try store.saveStagingJob(needs)
            XCTAssertEqual(try store.stagingJobs(account: "owner",
                                                  book: book.id).count, 1)
            XCTAssertTrue(try store.stagingJobs(account: "other",
                                                 book: book.id).isEmpty)
            var wrongHost = needs
            // Immutable let fields cannot be changed even in an in-memory copy.
            // Reconstruct an attempted foreign target for the same draft ID.
            let malicious = StagingPurchaseJob(
                id: needs.id, sourceAccount: needs.sourceAccount,
                bookID: needs.bookID, stageHost: "https://untrusted.example.com",
                stageInstanceID: needs.stageInstanceID, stageUsername: needs.stageUsername,
                deviceID: needs.deviceID, orderSyncID: needs.orderSyncID,
                itemSyncID: needs.itemSyncID, orderRemoteID: needs.orderRemoteID,
                itemRemoteID: needs.itemRemoteID, orderPayload: needs.orderPayload,
                itemPayload: needs.itemPayload, createdAt: needs.createdAt,
                state: needs.state, lastMessage: needs.lastMessage)
            XCTAssertThrowsError(try store.saveStagingJob(malicious))
            wrongHost.lastMessage = "state remains unchanged"
        }
        let reopened = try TestDraftStore(url: url)
        let restored = try XCTUnwrap(reopened.stagingJob(
            id: original.id, account: original.sourceAccount, book: book.id))
        XCTAssertEqual(restored.state, .needsReview)
        XCTAssertEqual(restored.orderSyncID, original.orderSyncID)
        XCTAssertEqual(restored.stageHost, StagingPurchasePolicy.productionURL)
    }
}
