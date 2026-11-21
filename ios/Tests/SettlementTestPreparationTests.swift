import XCTest
@testable import TianXianFruit

final class SettlementTestPreparationTests: XCTestCase {
    private let owner = Account(username: "ios-tester", displayName: "测试", systemRole: "USER")
    private let testBook = CloudBook(
        id: "11111111-2222-4333-8444-555555555555",
        name: "iOS_TEST_SETTLEMENT", role: "EDITOR",
        deleted: false, purged: false
    )

    private func event(_ name: String, id: String, date: String,
                       version: Int64 = 4,
                       values: [String: JSONValue] = [:]) -> SyncEvent {
        var payload = values
        payload["date"] = .string(date)
        return SyncEvent(tableName: name, syncId: id,
                         rowVersion: version,
                         operation: "UPSERT", payload: payload)
    }

    private func snapshot(amount: Double = 300, settled: Double = 100,
                          dailyCount: Int = 1,
                          transferDate: String = "2026-11-20") -> ReadOnlySnapshot {
        var events: [SyncEvent] = [
            event("settlement_transfer", id: "transfer-a", date: transferDate,
                  version: 6, values: [
                    "settlement_kind": .string("DAILY"),
                    "amount": .number(amount),
                    "settled_amount": .number(settled),
                    "from_partner_name": .string("甲"),
                    "to_partner_name": .string("资金中心")])
        ]
        if dailyCount > 0 {
            events.append(event("daily_cash_settlement",
                                id: "daily-a", date: "2026-11-20",
                                version: 5, values: ["status": .number(2)]))
        }
        if dailyCount > 1 {
            events.append(event("daily_cash_settlement",
                                id: "daily-b", date: "2026-11-20",
                                version: 7, values: ["status": .number(0)]))
        }
        var result = ReadOnlySnapshot()
        result.apply(events)
        return result
    }

    private func prepare(_ snapshot: ReadOnlySnapshot, book: CloudBook? = nil,
                         account: Account? = nil, offline: Bool = false,
                         date: String = "2026-11-20",
                         action: TXSettlementTestAction = .partial,
                         amount: String = "125.50") throws -> TXSettlementLocalPreparation {
        try TXSettlementPreparationPolicy.prepare(
            book: book ?? testBook, account: account ?? owner,
            offline: offline, snapshot: snapshot,
            date: date, transferID: "transfer-a",
            action: action, typedAmount: amount,
            operationID: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        )
    }

    func testPartialPreviewIsImmutableAndIncludesOptimisticVersions() throws {
        let snapshot = snapshot()
        let previous = try XCTUnwrap(snapshot.financeTransfers.first)
        let prepared = try prepare(snapshot)
        XCTAssertEqual(prepared.bookID, testBook.id)
        XCTAssertEqual(prepared.date, "2026-11-20")
        XCTAssertEqual(prepared.dailySyncID, "daily-a")
        XCTAssertEqual(prepared.dailyRowVersion, 5)
        XCTAssertEqual(prepared.transferSyncID, "transfer-a")
        XCTAssertEqual(prepared.transferRowVersion, 6)
        XCTAssertEqual(prepared.expectedSettledCents, 10000)
        XCTAssertEqual(prepared.targetSettledCents, 12550)
        XCTAssertTrue(prepared.verificationSummary.contains("未提交"))
        XCTAssertEqual(snapshot.financeTransfers.first?.recordedSettled,
                       previous.recordedSettled, "Preparation cannot mutate cloud snapshot")
        XCTAssertFalse(TXSettlementPreparationPolicy.serverSettlementCommitAvailable)
    }

    func testFullResetAndNoopProtectRegisteredAmount() throws {
        let current = snapshot(amount: 300, settled: 100)
        XCTAssertEqual(try prepare(current, action: .full).targetSettledCents, 30000)
        XCTAssertEqual(try prepare(current, action: .reset).targetSettledCents, 0)
        XCTAssertThrowsError(try prepare(current, action: .partial, amount: "100.00"))
        XCTAssertThrowsError(try prepare(snapshot(amount: 300, settled: 300),
                                         action: .full))
        XCTAssertThrowsError(try prepare(snapshot(amount: 300, settled: 0),
                                         action: .reset))
    }

    func testInvalidMoneyNeverRoundedOrClamped() {
        for bad in ["-1", "100.999", "300.01", "1,000.00", "NaN", "inf", "",
                    "1e2", "1000000.01"] {
            XCTAssertThrowsError(try prepare(snapshot(), amount: bad),
                                 "Should reject \(bad)")
        }
        XCTAssertEqual(TXSettlementPreparationPolicy.cents("0"), 0)
        XCTAssertEqual(TXSettlementPreparationPolicy.cents("20.05"), 2005)
        XCTAssertEqual(TXSettlementPreparationPolicy.cents("20.005"), nil)
        XCTAssertEqual(TXSettlementPreparationPolicy.cents("1e2"), nil)
    }

    func testMissingAndDuplicateDailyOrWrongDateFailClosed() {
        XCTAssertThrowsError(try prepare(snapshot(dailyCount: 0)))
        XCTAssertThrowsError(try prepare(snapshot(dailyCount: 2)))
        XCTAssertThrowsError(try prepare(snapshot(transferDate: "2026-11-19")))
        XCTAssertThrowsError(try prepare(snapshot(), date: "2026-11-21"))
    }

    func testProductionViewerOfflineAndDeletedBookNeverPrepare() {
        let formal = CloudBook(id: testBook.id, name: "天鲜果业", role: "OWNER",
                               deleted: false, purged: false)
        let viewer = CloudBook(id: testBook.id, name: testBook.name, role: "VIEWER",
                               deleted: false, purged: false)
        let deleted = CloudBook(id: testBook.id, name: testBook.name, role: "OWNER",
                                deleted: true, purged: false)
        for blocked in [formal, viewer, deleted] {
            XCTAssertThrowsError(try prepare(snapshot(), book: blocked))
        }
        XCTAssertThrowsError(try prepare(snapshot(), offline: true))
        XCTAssertThrowsError(try TXSettlementPreparationPolicy.prepare(
            book: testBook, account: nil, offline: false,
            snapshot: snapshot(), date: "2026-11-20",
            transferID: "transfer-a", action: .full, typedAmount: "300"))
    }

    func testServerOnlyOperationsCannotBeGuessedFromAmounts() {
        for action in [TXSettlementTestAction.generate, .cutoff, .profitShare] {
            XCTAssertThrowsError(try prepare(snapshot(), action: action))
            XCTAssertFalse(action.canPreviewSavedTransfer)
        }
    }

    func testSourceRowVersionRefreshNeverReusesStalePreview() throws {
        var snapshot = snapshot()
        let old = try prepare(snapshot)
        snapshot.apply([event("settlement_transfer",
                              id: "transfer-a", date: "2026-11-20",
                              version: 7, values: [
                                "settlement_kind": .string("DAILY"),
                                "amount": .number(300),
                                "settled_amount": .number(120)])])
        let fresh = try prepare(snapshot)
        XCTAssertNotEqual(old.transferRowVersion, fresh.transferRowVersion)
        XCTAssertNotEqual(old.expectedSettledCents, fresh.expectedSettledCents)
        XCTAssertEqual(old.transferRowVersion, 6,
                       "Local preflight must remain immutable, not update old identity")
    }
}
