import XCTest
@testable import TianXianFruit

final class SettlementRecordedStatusTests: XCTestCase {
    private func row(_ table: String, id: String, date: String,
                     fields: [String: JSONValue] = [:],
                     version: Int64 = 1, operation: String = "UPSERT") -> SyncEvent {
        var payload = fields
        payload["date"] = .string(date)
        return SyncEvent(tableName: table, syncId: id, rowVersion: version,
                         operation: operation, payload: payload)
    }

    func testAndroidOriginalDailyStatusesReadWithoutEffectiveRecalculation() throws {
        var snapshot = ReadOnlySnapshot()
        snapshot.apply([
            row("daily_cash_settlement", id: "zero", date: "2026-11-18",
                fields: ["status": .number(0)]),
            row("daily_cash_settlement", id: "half", date: "2026-11-19",
                fields: ["status": .number(2)]),
            row("daily_cash_settlement", id: "all", date: "2026-11-20",
                fields: ["status": .number(1)]),
            row("daily_cash_settlement", id: "missing", date: "2026-11-21")
        ])
        func daily(_ date: String) -> FinanceDailyRow? {
            SettlementTablePolicy.rows(snapshot, since: date, until: date)
                .first?.uniqueSnapshot
        }
        XCTAssertEqual(TXRecordedSettlementState.of(daily("2026-11-18")), .notSettled)
        XCTAssertEqual(TXRecordedSettlementState.of(daily("2026-11-19")), .partiallySettled)
        XCTAssertEqual(TXRecordedSettlementState.of(daily("2026-11-20")), .savedAsSettled)
        XCTAssertEqual(TXRecordedSettlementState.of(daily("2026-11-21")), .statusMissing)
        XCTAssertEqual(TXRecordedSettlementState.of(nil), .noDailySnapshot)
    }

    func testStoredTransferRegistrationProgressAndInvalidAmounts() throws {
        func progress(_ amount: Double, _ registered: Double) throws -> TXTransferRegistration {
            let event = row("settlement_transfer", id: UUID().uuidString,
                            date: "2026-11-20",
                            fields: ["amount": .number(amount),
                                     "settled_amount": .number(registered)])
            return TXSettlementRecordPolicy.transferRegistration(
                try XCTUnwrap(FinanceTransferRow(event)))
        }
        XCTAssertEqual(try progress(100, 0), .notRegistered)
        XCTAssertEqual(try progress(100, 25), .partiallyRegistered)
        XCTAssertEqual(try progress(100, 100), .fullyRegistered)
        XCTAssertEqual(try progress(0, 0), .noTransferRequired)
        XCTAssertEqual(try progress(100, 101), .inconsistent)
        XCTAssertEqual(try progress(-5, 0), .inconsistent)
        XCTAssertEqual(try progress(100, -1), .inconsistent)
    }

    func testCompactDailyTransferStatusNeverCountsPeriodSettlement() throws {
        let day = "2026-11-20"
        let events = [
            row("settlement_transfer", id: "full", date: day,
                fields: ["amount": .number(100), "settled_amount": .number(100),
                         "settlement_kind": .string("DAILY")]),
            row("settlement_transfer", id: "partial", date: day,
                fields: ["amount": .number(120), "settled_amount": .number(20),
                         "settlement_kind": .string("DAILY")]),
            row("settlement_transfer", id: "pending", date: day,
                fields: ["amount": .number(30), "settled_amount": .number(0),
                         "settlement_kind": .string("DAILY")]),
            row("settlement_transfer", id: "invalid", date: day,
                fields: ["amount": .number(50), "settled_amount": .number(90),
                         "settlement_kind": .string("DAILY")]),
            row("settlement_transfer", id: "period", date: day,
                fields: ["amount": .number(1000), "settled_amount": .number(1000),
                         "settlement_kind": .string("CUTOFF")])
        ]
        let readout = TXDailyTransferReadout(
            transfers: events.compactMap(FinanceTransferRow.init))
        XCTAssertEqual(readout.total, 4)
        XCTAssertEqual(readout.full, 1)
        XCTAssertEqual(readout.partial, 1)
        XCTAssertEqual(readout.pending, 1)
        XCTAssertEqual(readout.invalid, 1)
        XCTAssertTrue(readout.label.contains("异常 1"))
        XCTAssertEqual(TXDailyTransferReadout(transfers: []).total, 0)
    }

    func testConfirmedLaterCutoffShowsAuditWarningNotEffectiveSettlement() throws {
        var snapshot = ReadOnlySnapshot()
        snapshot.apply([
            row("daily_cash_settlement", id: "day", date: "2026-11-18",
                fields: ["status": .number(0)]),
            row("settlement_transfer", id: "cutoff", date: "2026-11-21",
                fields: ["settlement_kind": .string("CUTOFF_CENTER"),
                         "range_start_date": .string("2026-11-15"),
                         "range_end_date": .string("2026-11-21"),
                         "focus_partner_name": .string("张"),
                         "from_partner_name": .string("资金中心"),
                         "to_partner_name": .string("张"),
                         "confirmed_at": .number(1795224000000),
                         "settled_amount": .number(120),
                         "amount": .number(120)]),
            row("settlement_transfer", id: "old", date: "2026-11-22",
                fields: ["settlement_kind": .string("CUTOFF_LEGACY_INVALID"),
                         "range_start_date": .string("2026-11-15"),
                         "range_end_date": .string("2026-11-21"),
                         "confirmed_at": .number(1795224000000),
                         "settled_amount": .number(500)])
        ])
        let day = try XCTUnwrap(SettlementTablePolicy.rows(snapshot,
            since: "2026-11-18", until: "2026-11-18").first)
        XCTAssertEqual(day.coveringCutoffs.count, 1)
        XCTAssertTrue(day.hasPotentialPeriodCoverage)
        XCTAssertEqual(day.coveringCutoffs.first?.focusPartnerName, "张")
        XCTAssertEqual(TXRecordedSettlementState.of(day.uniqueSnapshot), .notSettled,
                       "Do not turn raw status into 1 using a guessed cutoff relationship")
        XCTAssertEqual(SettlementTablePolicy.rows(snapshot, since: "2026-11-18",
            until: "2026-11-18", partner: "李").count, 0)
    }

    func testUnconfirmedAndWrongPeriodCutoffsCannotCreateCoverageEvidence() {
        let candidates: [[String: JSONValue]] = [
            ["settlement_kind": .string("CUTOFF"),
             "range_start_date": .string("2026-11-15"),
             "range_end_date": .string("2026-11-21"),
             "settled_amount": .number(100), "confirmed_at": .number(0)],
            ["settlement_kind": .string("DAILY"),
             "range_start_date": .string("2026-11-15"),
             "range_end_date": .string("2026-11-21"),
             "settled_amount": .number(100), "confirmed_at": .number(33)],
            ["settlement_kind": .string("CUTOFF"),
             "range_start_date": .string("2026-11-22"),
             "range_end_date": .string("2026-11-25"),
             "settled_amount": .number(100), "confirmed_at": .number(33)],
            ["settlement_kind": .string("CUTOFF_LEGACY_INVALID"),
             "range_start_date": .string("2026-11-15"),
             "range_end_date": .string("2026-11-21"),
             "settled_amount": .number(100), "confirmed_at": .number(33)]
        ]
        for (index, fields) in candidates.enumerated() {
            let event = row("settlement_transfer", id: "t\(index)",
                            date: "2026-11-22", fields: fields)
            guard let transfer = FinanceTransferRow(event) else {
                XCTFail("Expected a transfer"); continue
            }
            XCTAssertFalse(TXSettlementRecordPolicy.isPotentialCutoff(
                transfer, on: "2026-11-18"))
        }
    }

    func testTombstoneRemovesRegisteredTransferFromReadOnlyProgress() {
        var snapshot = ReadOnlySnapshot()
        let transfer = row("settlement_transfer", id: "t", date: "2026-11-20",
                           fields: ["amount": .number(50),
                                    "settled_amount": .number(50)])
        snapshot.apply([transfer])
        XCTAssertEqual(snapshot.financeTransfers.count, 1)
        snapshot.apply([row("settlement_transfer", id: "t", date: "2026-11-20",
                            version: 2, operation: "DELETE"), transfer])
        XCTAssertTrue(snapshot.financeTransfers.isEmpty)
    }
}
