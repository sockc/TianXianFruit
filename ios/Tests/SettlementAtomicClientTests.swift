import XCTest
@testable import TianXianFruit

final class SettlementAtomicClientTests: XCTestCase {
    private let book = CloudBook(
        id: "11111111-2222-4333-8444-555555555555", name: "iOS_TEST_A",
        role: "OWNER", deleted: false, purged: false)
    private let account = Account(username: "ios", displayName: "IOS", systemRole: "USER")

    private func event(_ table: String, _ id: String, version: Int64,
                       _ payload: [String: JSONValue]) -> SyncEvent {
        SyncEvent(tableName: table, syncId: id, rowVersion: version,
                  operation: "UPSERT", payload: payload)
    }
    private func snapshot(settled: Double = 20, version: Int64 = 6,
                          status: Int = 2) -> ReadOnlySnapshot {
        var snapshot = ReadOnlySnapshot()
        snapshot.apply([
            event("daily_cash_settlement", "daily", version: 5, [
                "id": .exactInteger(79152283000000003),
                "date": .string("2026-11-20"), "status": .number(Double(status))]),
            event("settlement_transfer", "transfer", version: version, [
                "id": .exactInteger(79152283000000004),
                "settlement_id": .exactInteger(79152283000000003),
                "date": .string("2026-11-20"), "settlement_kind": .string("DAILY"),
                "settled_amount": .number(settled), "amount": .number(100)])
        ])
        return snapshot
    }
    private func prep() throws -> TXSettlementLocalPreparation {
        try TXSettlementPreparationPolicy.prepare(
            book: book, account: account, offline: false,
            snapshot: snapshot(), date: "2026-11-20",
            transferID: "transfer", action: .partial, typedAmount: "35.50",
            operationID: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
    }

    func testServerCapabilitiesStrictlyRequireAtomicIdempotentOperationLookup() throws {
        let json = """
        {"version":1,"enabled":true,"atomic":true,"idempotency":true,
         "operation_lookup":true,"feature":"settlement_transfer_atomic_v1",
         "scope":"iOS_TEST_","actions":["PARTIAL","FULL","RESET"]}
        """.data(using: .utf8)!
        let caps = try JSONDecoder().decode(TXSettlementCommitCapabilities.self,
                                             from: json)
        XCTAssertTrue(caps.allowsWrite)
        let disabled = try JSONDecoder().decode(TXSettlementCommitCapabilities.self,
                            from: String(data: json, encoding: .utf8)!
                                .replacingOccurrences(of: "\"enabled\":true",
                                                      with: "\"enabled\":false")
                                .data(using: .utf8)!)
        XCTAssertFalse(disabled.allowsWrite)
        let wrongFeature = try JSONDecoder().decode(TXSettlementCommitCapabilities.self,
                            from: String(data: json, encoding: .utf8)!
                                .replacingOccurrences(of: "settlement_transfer_atomic_v1",
                                                      with: "generic_sync_push")
                                .data(using: .utf8)!)
        XCTAssertFalse(wrongFeature.allowsWrite)
    }

    func testImmutableHttpBodyDoesNotGenerateNewOperationID() throws {
        let draft = try prep()
        let body = try TXSettlementCommitBody(draft, deviceID: "registered-device-001")
        let json = try JSONSerialization.jsonObject(with: JSONEncoder().encode(body)) as! [String: Any]
        XCTAssertEqual(json["operation_id"] as? String, draft.id)
        XCTAssertEqual(json["protocol_version"] as? Int, 1)
        XCTAssertEqual(json["action"] as? String, "PARTIAL")
        XCTAssertEqual(json["target_settled_amount"] as? String, "35.50")
        XCTAssertEqual(json["expected_settled_amount"] as? String, "20.00")
        XCTAssertEqual(json["transfer_row_version"] as? Int, 6)
        XCTAssertEqual(json["daily_row_version"] as? Int, 5)
        XCTAssertEqual(json["book_id"] as? String, book.id)
    }

    func testReceiptMustIncludeBothUpdatedVersionsAndCorrectMoney() throws {
        let draft = try prep()
        let ok = TXSettlementCommitReceipt(
            status: "committed", operationID: draft.id.lowercased(), bookID: draft.bookID,
            date: draft.date, dailySyncID: draft.dailySyncID,
            dailyRowVersion: 6, transferSyncID: draft.transferSyncID,
            transferRowVersion: 7, settledAmount: "35.50", dailyStatus: 2,
            syncCursor: 400, requestHash: String(repeating: "a", count: 64))
        XCTAssertTrue(ok.matches(draft))
        let incorrect = TXSettlementCommitReceipt(
            status: "committed", operationID: draft.id.lowercased(), bookID: draft.bookID,
            date: draft.date, dailySyncID: draft.dailySyncID,
            dailyRowVersion: 5, transferSyncID: draft.transferSyncID,
            transferRowVersion: 7, settledAmount: "35.50", dailyStatus: 2,
            syncCursor: 400, requestHash: String(repeating: "a", count: 64))
        XCTAssertFalse(incorrect.matches(draft))
        XCTAssertFalse(TXSettlementCommitReadback.original(draft, in: snapshot(version: 7)))
        XCTAssertTrue(TXSettlementCommitReadback.original(draft, in: snapshot()))
    }

    func testDurableLockRoundTripAndCorruptionFailClosed() throws {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let store = try TXSettlementCommitStore(directory: dir)
        let prepared = try prep()
        let attempt = TXSettlementCommitAttempt(
            proposal: prepared, phase: .unknown, receipt: nil, updatedAt: Date())
        try store.save(attempt)
        let found = try XCTUnwrap(store.load(account: prepared.account, book: prepared.bookID))
        XCTAssertEqual(found.proposal.id, prepared.id)
        XCTAssertEqual(found.phase, .unknown)
        XCTAssertNil(try store.load(account: "different-user", book: prepared.bookID))
        let files = try FileManager.default.contentsOfDirectory(at: dir,
                                                                 includingPropertiesForKeys: nil)
        XCTAssertEqual(files.count, 1)
        try Data("garbled".utf8).write(to: files[0])
        XCTAssertThrowsError(try store.load(account: prepared.account, book: prepared.bookID))
    }

    func testReadbackRequiresExactSyncedDailyAndTransferState() throws {
        let draft = try prep()
        let receipt = TXSettlementCommitReceipt(
            status: "committed", operationID: draft.id, bookID: draft.bookID,
            date: draft.date, dailySyncID: draft.dailySyncID,
            dailyRowVersion: 6, transferSyncID: draft.transferSyncID,
            transferRowVersion: 7, settledAmount: "35.50", dailyStatus: 2,
            syncCursor: 9, requestHash: String(repeating: "b", count: 64))
        XCTAssertFalse(TXSettlementCommitReadback.confirmed(draft, receipt: receipt,
            snap: snapshot(), cursor: 10))
        var updated = snapshot()
        updated.apply([
            event("daily_cash_settlement", "daily", version: 6, [
                "date": .string("2026-11-20"), "status": .number(2)]),
            event("settlement_transfer", "transfer", version: 7, [
                "date": .string("2026-11-20"), "settlement_kind": .string("DAILY"),
                "settled_amount": .number(35.50)])
        ])
        XCTAssertFalse(TXSettlementCommitReadback.confirmed(draft, receipt: receipt,
            snap: updated, cursor: 8))
        XCTAssertTrue(TXSettlementCommitReadback.confirmed(draft, receipt: receipt,
            snap: updated, cursor: 10))
    }
    func testOneShotOperationIdentityNeverReplaysAfterConfirmation() throws {
        let prepared = try prep()
        XCTAssertTrue(TXSettlementSubmissionPolicy.canBegin(prepared, prior: nil))
        for phase: TXSettlementCommitAttempt.Phase in [.unknown, .accepted, .confirmed] {
            let attempt = TXSettlementCommitAttempt(
                proposal: prepared, phase: phase, receipt: nil, updatedAt: Date())
            XCTAssertFalse(TXSettlementSubmissionPolicy.canBegin(prepared, prior: attempt))
        }
        let another = try TXSettlementPreparationPolicy.prepare(
            book: book, account: account, offline: false,
            snapshot: snapshot(), date: "2026-11-20",
            transferID: "transfer", action: .partial, typedAmount: "36.00",
            operationID: "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
        let confirmed = TXSettlementCommitAttempt(
            proposal: prepared, phase: .confirmed, receipt: nil, updatedAt: Date())
        XCTAssertTrue(TXSettlementSubmissionPolicy.canBegin(another, prior: confirmed))
        let pending = TXSettlementCommitAttempt(
            proposal: prepared, phase: .accepted, receipt: nil, updatedAt: Date())
        XCTAssertFalse(TXSettlementSubmissionPolicy.canBegin(another, prior: pending))
    }

    func testCrossDeviceReadbackClassifiesPendingSupersessionAndConflict() throws {
        let prepared = try prep()
        let receipt = TXSettlementCommitReceipt(
            status: "committed", operationID: prepared.id, bookID: prepared.bookID,
            date: prepared.date, dailySyncID: prepared.dailySyncID,
            dailyRowVersion: 6, transferSyncID: prepared.transferSyncID,
            transferRowVersion: 7, settledAmount: "35.50",
            dailyStatus: 2, syncCursor: 100,
            requestHash: String(repeating: "c", count: 64))
        XCTAssertEqual(TXSettlementCommitReadback.assessment(
            prepared, receipt: receipt, snap: snapshot(), cursor: 99), .waitingForSync)
        XCTAssertEqual(TXSettlementCommitReadback.assessment(
            prepared, receipt: receipt, snap: snapshot(), cursor: 100), .waitingForSync)

        var verified = snapshot()
        verified.apply([
            event("daily_cash_settlement", "daily", version: 6, [
                "date": .string("2026-11-20"), "status": .number(2)]),
            event("settlement_transfer", "transfer", version: 7, [
                "date": .string("2026-11-20"),
                "settlement_kind": .string("DAILY"),
                "settled_amount": .number(35.50)])
        ])
        XCTAssertEqual(TXSettlementCommitReadback.assessment(
            prepared, receipt: receipt, snap: verified, cursor: 100), .verified)
        var superseded = verified
        superseded.apply([event("settlement_transfer", "transfer", version: 8, [
            "date": .string("2026-11-20"),
            "settlement_kind": .string("DAILY"),
            "settled_amount": .number(41.00)])])
        XCTAssertEqual(TXSettlementCommitReadback.assessment(
            prepared, receipt: receipt, snap: superseded, cursor: 105), .superseded)
        XCTAssertFalse(TXSettlementCommitReadback.confirmed(
            prepared, receipt: receipt, snap: superseded, cursor: 105))

        var mismatch = snapshot()
        mismatch.apply([
            event("daily_cash_settlement", "daily", version: 6, [
                "date": .string("2026-11-20"), "status": .number(2)]),
            event("settlement_transfer", "transfer", version: 7, [
                "date": .string("2026-11-20"),
                "settlement_kind": .string("DAILY"),
                "settled_amount": .number(34.99)])
        ])
        XCTAssertEqual(TXSettlementCommitReadback.assessment(
            prepared, receipt: receipt, snap: mismatch, cursor: 100), .inconsistent)

        var deleted = verified
        deleted.apply([SyncEvent(tableName: "settlement_transfer",
            syncId: "transfer", rowVersion: 8, operation: "DELETE", payload: [:])])
        XCTAssertEqual(TXSettlementCommitReadback.assessment(
            prepared, receipt: receipt, snap: deleted, cursor: 101), .superseded)
    }

}
