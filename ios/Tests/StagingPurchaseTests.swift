import Foundation
import XCTest
@testable import TianXianFruit

final class StagingPurchaseTests: XCTestCase {
    private let book = CloudBook(id: "ios-test-book-copy-1",
                                 name: "iOS_TEST_家庭测试",
                                 role: "OWNER", deleted: false, purged: false)

    private func proof(isolated: Bool = true, enabled: Bool = true,
                       instance: String = "isolated-vm-001") -> StagingProof {
        StagingProof(deploymentScope: "isolated_test",
                     isolatedFromProduction: isolated,
                     iosPurchaseWriteEnabled: enabled,
                     instanceID: instance,
                     allowedBookIDs: [book.id])
    }

    private func draft() -> TestDraft {
        TestDraft(id: "93058B47-EDAB-45AE-8171-5F613BE1AA38",
                  account: "owner", bookID: book.id, kind: .purchase,
                  date: "2026-11-18", fields: [
                    "buyer_sync_id": "buyer-shared-1",
                    "fruit_sync_id": "fruit-shared-1",
                    "fruit_name": "提子",
                    "quantity": "2.5",
                    "unit": "箱",
                    "total_cost": "80.5",
                    "unit_weight_jin": "5",
                    "purchase_type": "PLANNED",
                    "remark": "独立测试"
                  ], createdAt: Date(timeIntervalSince1970: 1794950000))
    }

    private func references() -> [SyncEvent] {
        [
            SyncEvent(tableName: "partner", syncId: "buyer-shared-1",
                      rowVersion: 2, operation: "UPSERT", payload: [
                        "id": .number(101), "name": .string("采购人测试")
                      ]),
            SyncEvent(tableName: "fruit", syncId: "fruit-shared-1",
                      rowVersion: 1, operation: "UPSERT", payload: [
                        "id": .number(202), "name": .string("提子")
                      ])
        ]
    }

    private func job() throws -> StagingPurchaseJob {
        try StagingPayloadBuilder.prepare(draft(), sourceBook: book, stageBook: book,
                                          stageHost: "https://staging.example.com/",
                                          proof: proof(), username: "stage_user",
                                          deviceID: "staging-device-1",
                                          available: references())
    }

    func testProductionAndUnknownEndpointsNeverPermitted() {
        for candidate in [
            "https://sync.830888.xyz",
            "http://staging.example.com",
            "https://localhost",
            "https://127.0.0.1",
            "https://[::1]",
            "https://staging.example.com:8443",
            "https://user:password@staging.example.com",
            "https://staging.example.com/path",
            "https://staging.example.com?book_id=prod",
            "not-a-url"
        ] {
            XCTAssertThrowsError(try StagingPurchasePolicy.validateURL(candidate),
                                 candidate)
        }
        XCTAssertNoThrow(try StagingPurchasePolicy.validateURL(
            "https://staging.example.com"))
    }

    func testProofMustAttestSeparateDatabaseAndAllowListedTestBook() {
        XCTAssertNoThrow(try StagingPurchasePolicy.requireProof(proof(), book: book))
        XCTAssertThrowsError(try StagingPurchasePolicy.requireProof(
            proof(isolated: false), book: book))
        XCTAssertThrowsError(try StagingPurchasePolicy.requireProof(
            proof(enabled: false), book: book))
        XCTAssertThrowsError(try StagingPurchasePolicy.requireProof(
            proof(instance: ""), book: book))
        let production = CloudBook(id: book.id, name: "天鲜账本正式账本",
                                   role: "OWNER", deleted: false, purged: false)
        XCTAssertThrowsError(try StagingPurchasePolicy.requireProof(
            proof(), book: production))
        let viewer = CloudBook(id: book.id, name: book.name,
                               role: "VIEWER", deleted: false, purged: false)
        XCTAssertThrowsError(try StagingPurchasePolicy.requireProof(
            proof(), book: viewer))
    }

    func testStableNumericParentIDAndPayloadExactCost() throws {
        let saved = try job()
        let repeated = try job()
        XCTAssertEqual(saved.orderRemoteID, repeated.orderRemoteID)
        XCTAssertEqual(saved.itemRemoteID, repeated.itemRemoteID)
        XCTAssertNotEqual(saved.orderRemoteID, saved.itemRemoteID)
        XCTAssertGreaterThan(saved.orderRemoteID, 2_000_000_000_000)
        XCTAssertEqual(saved.orderPayload["id"]?.numberValue,
                       Double(saved.orderRemoteID))
        XCTAssertEqual(saved.itemPayload["order_id"]?.numberValue,
                       Double(saved.orderRemoteID))
        XCTAssertEqual(saved.orderPayload["buyer_id"]?.numberValue, 101)
        XCTAssertEqual(saved.itemPayload["fruit_id"]?.numberValue, 202)
        XCTAssertEqual(saved.orderPayload["total_cost"]?.decimal, 80.5)
        XCTAssertEqual(saved.itemPayload["total_cost"]?.decimal, 80.5)
        XCTAssertEqual(saved.itemPayload["quantity"]?.decimal, 2.5)
        XCTAssertEqual(saved.itemPayload["unit_weight_jin"]?.decimal, 5)
        XCTAssertEqual(saved.itemPayload["unit_price"]?.decimal, 32.2)
        XCTAssertEqual(saved.itemPayload["fruit_name"]?.text, "提子")
        XCTAssertEqual(saved.state, .ready)
    }

    func testMissingRemoteBuyerOrFruitFailsBeforeNetworkPush() throws {
        XCTAssertThrowsError(try StagingPayloadBuilder.prepare(
            draft(), sourceBook: book, stageBook: book,
            stageHost: "https://staging.example.com/", proof: proof(),
            username: "test", deviceID: "device",
            available: [references()[0]]))
        let copy = CloudBook(id: "other-book", name: book.name,
                             role: "OWNER", deleted: false, purged: false)
        XCTAssertThrowsError(try StagingPayloadBuilder.prepare(
            draft(), sourceBook: book, stageBook: copy,
            stageHost: "https://staging.example.com/", proof: proof(),
            username: "test", deviceID: "device", available: references()))
    }

    func testAcceptedRequiresMatchingSingleRowAndNoConflicts() {
        let item = SyncEvent(tableName: "purchase_item", syncId: "child",
                             rowVersion: 1, operation: "UPSERT", payload: [:])
        let yes = StageSyncAck(accepted: [
            .init(tableName: "purchase_item", syncID: "child", rowVersion: 1)
        ], conflicts: [])
        XCTAssertTrue(yes.isAccepted(item))
        let mismatch = StageSyncAck(accepted: [
            .init(tableName: "purchase_order", syncID: "child", rowVersion: 1)
        ], conflicts: [])
        XCTAssertFalse(mismatch.isAccepted(item))
        let conflict = StageSyncAck(accepted: yes.accepted, conflicts: [
            .init(tableName: "purchase_item", syncID: "child", serverVersion: 2)
        ])
        XCTAssertFalse(conflict.isAccepted(item))
    }

    func testDurableOutboxRejectsIdentityRewriteAndInvalidTransitions() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("staging-outbox-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let url = root.appendingPathComponent("drafts.sqlite")
        let prepared = try job()
        do {
            let store = try TestDraftStore(url: url)
            try store.saveStagingJob(prepared)
            XCTAssertEqual(try store.stagingJobs(account: "owner", book: book.id).count, 1)
            XCTAssertTrue(try store.stagingJobs(account: "another", book: book.id).isEmpty)
            var sending = prepared
            sending.state = .sendingOrder
            try store.saveStagingJob(sending)
            var ack = sending
            ack.state = .orderConfirmed
            try store.saveStagingJob(ack)
            var completed = ack
            completed.state = .complete
            XCTAssertThrowsError(try store.saveStagingJob(completed))
            var mutating = ack
            mutating.orderPayload["total_cost"] = .number(9000)
            XCTAssertThrowsError(try store.saveStagingJob(mutating))
        }
        let reopened = try TestDraftStore(url: url)
        let recovered = try XCTUnwrap(reopened.stagingJob(
            id: prepared.id, account: "owner", book: book.id))
        XCTAssertEqual(recovered.state, .orderConfirmed)
        XCTAssertEqual(recovered.orderRemoteID, prepared.orderRemoteID)
        XCTAssertEqual(recovered.orderPayload["total_cost"]?.decimal, 80.5)
    }

    func testTimeoutStateSurvivesRestartForManualReconciliation() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("staging-timeout-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let url = root.appendingPathComponent("outbox.sqlite")
        var sending = try job()
        do {
            let store = try TestDraftStore(url: url)
            try store.saveStagingJob(sending)
            sending.state = .sendingOrder
            try store.saveStagingJob(sending)
            sending.state = .needsReview
            sending.lastMessage = "超时: 不自动重试"
            try store.saveStagingJob(sending)
        }
        let reopened = try TestDraftStore(url: url)
        let recovered = try XCTUnwrap(reopened.stagingJob(
            id: sending.id, account: "owner", book: book.id))
        XCTAssertEqual(recovered.state, .needsReview)
        XCTAssertEqual(recovered.orderSyncID, "ios-stage-order-" + sending.id)
    }

    func testAttestationWireFormatExample() throws {
        let json = """
        {"deployment_scope":"isolated_test","isolated_from_production":true,
         "ios_purchase_write_enabled":true,"instance_id":"isolated-vm-001",
         "allowed_book_ids":["ios-test-book-copy-1"]}
        """
        let proof = try JSONDecoder().decode(StagingProof.self,
                                              from: Data(json.utf8))
        XCTAssertNoThrow(try StagingPurchasePolicy.requireProof(proof, book: book))
    }
}
