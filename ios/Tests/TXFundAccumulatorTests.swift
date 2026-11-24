import XCTest
@testable import TianXianFruit

final class TXFundAccumulatorTests: XCTestCase {
    private func day(_ date: String, raw: Double = 0, saved: Double? = nil,
                     settled: Bool = false, sent: Double = 0, received: Double = 0,
                     dailyTransfer: Bool = false, standaloneSent: Double = 0,
                     standaloneReceived: Double = 0, cutoff: Bool = false) -> TXFundAccumulator.Day {
        .init(date: date, rawBalance: raw, savedBalance: saved,
              dailyFullySettled: settled, dailySent: sent, dailyReceived: received,
              hasDailyTransfers: dailyTransfer, standaloneSent: standaloneSent,
              standaloneReceived: standaloneReceived, cutoffCleared: cutoff)
    }

    func testPartialSettlementAndOldOutstandingCarryForward() throws {
        let rows = try TXFundAccumulator.ledger([
            day("2026-11-20", saved: 100, received: 40, dailyTransfer: true),
            day("2026-11-01", raw: -20)
        ])
        XCTAssertEqual(rows.map(\.endBalance), [-20, 40])
        XCTAssertEqual(rows.last?.residual, 60)
        XCTAssertFalse(try XCTUnwrap(rows.last).checkpoint)
    }

    func testFullDailySettlementIsCheckpointNotJustZeroResidual() throws {
        let rows = try TXFundAccumulator.ledger([
            day("2026-11-01", raw: 50),
            day("2026-11-02", saved: -100, sent: 100, dailyTransfer: true),
            day("2026-11-03", raw: -12)
        ])
        XCTAssertEqual(rows.map(\.endBalance), [50, 0, -12])
        XCTAssertTrue(rows[1].checkpoint)
    }

    func testExplicitSettledAndZeroSavedBalanceRules() throws {
        let rows = try TXFundAccumulator.ledger([
            day("2026-11-01", raw: 50),
            day("2026-11-02", saved: 0, settled: true),
            day("2026-11-03", saved: 12, settled: true)
        ])
        XCTAssertEqual(rows.map(\.endBalance), [50, 50, 0])
        XCTAssertFalse(rows[1].checkpoint)
        XCTAssertTrue(rows[2].checkpoint)
    }

    func testStandaloneTransferAndCutoffReset() throws {
        let rows = try TXFundAccumulator.ledger([
            day("2026-11-01", raw: 90),
            day("2026-11-02", standaloneReceived: 30),
            day("2026-11-03", standaloneReceived: 60, cutoff: true),
            day("2026-11-04", raw: 8)
        ])
        XCTAssertEqual(rows.map(\.endBalance), [90, 60, 0, 8])
        XCTAssertEqual(rows[1].standaloneAdjustment, -30)
        XCTAssertTrue(rows[2].checkpoint)
        XCTAssertEqual(try TXFundAccumulator.centerBalance([8, -15]), 7)
    }

    func testDuplicateAndInvalidInputsFailClosed() {
        XCTAssertThrowsError(try TXFundAccumulator.ledger([day("2026-11-01"),
                                                           day("2026-11-01")]))
        XCTAssertThrowsError(try TXFundAccumulator.ledger([day("2026-11-01", raw: .nan)]))
        XCTAssertThrowsError(try TXFundAccumulator.ledger([day("2026-11-01", settled: true)]))
        XCTAssertEqual(TXFundAccumulator.roundMoney(0.125), 0.12)
    }

    private func event(_ table: String, _ id: String, _ date: String,
                       _ fields: [String: JSONValue]) -> SyncEvent {
        var payload = fields
        payload["date"] = .string(date)
        return SyncEvent(tableName: table, syncId: id, rowVersion: 1,
                         operation: "UPSERT", payload: payload)
    }

    func testAuditUsesFullCutoffAndDoesNotClaimVerifiedBalance() {
        var snapshot = ReadOnlySnapshot()
        snapshot.apply([
            event("daily_cash_settlement", "d1", "2026-10-01", [:]),
            event("daily_cash_settlement", "d2", "2026-10-01", ["status": .number(1)]),
            event("settlement_transfer", "t1", "2026-10-02",
                  ["settlement_kind": .string("CUTOFF"), "amount": .number(100),
                   "settled_amount": .number(20)]),
            event("settlement_transfer", "future", "2026-11-26",
                  ["settlement_kind": .string("DAILY"), "amount": .number(30)])
        ])
        let audit = TXFundAuditPolicy.inspect(snapshot, cutoff: "2026-11-25")
        XCTAssertEqual(audit.dailyRecordCount, 2)
        XCTAssertEqual(audit.transferCount, 1)
        XCTAssertTrue(audit.hasBlocker)
        XCTAssertTrue(audit.hasReviewableEvidence)
        let ids = Set(audit.findings.map(\.id))
        XCTAssertTrue(ids.isSuperset(of: ["daily-duplicates", "partial", "cutoffs",
                                         "status-missing", "authoritative"]))
    }
}
