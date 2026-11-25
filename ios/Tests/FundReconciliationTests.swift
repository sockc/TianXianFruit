import XCTest
@testable import TianXianFruit

final class FundReconciliationTests: XCTestCase {
    private let date = "2026-11-20"
    private let daySource: Int64 = 79152283000000003
    private let transferSource: Int64 = 79152283000000004

    private func event(_ table: String, _ id: String,
                       _ body: [String: JSONValue], version: Int64 = 3) -> SyncEvent {
        SyncEvent(tableName: table, syncId: id, rowVersion: version,
                  operation: "UPSERT", payload: body)
    }

    private func snapshot(dailyCount: Int = 1, amount: Double = 100,
                          settled: Double = 20, status: Int = 2,
                          wrongLink: Bool = false, addCutoff: Bool = false,
                          addInvalidLegacy: Bool = false) -> ReadOnlySnapshot {
        var events: [SyncEvent] = [
            event("settlement_transfer", "transfer1", [
                "id": .exactInteger(transferSource),
                "settlement_id": .exactInteger(wrongLink ? 79152283000009999 : daySource),
                "date": .string(date),
                "settlement_kind": .string("DAILY"),
                "amount": .number(amount),
                "settled_amount": .number(settled)
            ])
        ]
        if dailyCount > 0 {
            events.append(event("daily_cash_settlement", "daily1", [
                "id": .exactInteger(daySource), "date": .string(date),
                "status": .number(Double(status))]))
        }
        if dailyCount > 1 {
            events.append(event("daily_cash_settlement", "daily2", [
                "id": .exactInteger(79152283000000006),
                "date": .string(date), "status": .number(0)]))
        }
        if addCutoff {
            events.append(event("settlement_transfer", "cutoff", [
                "date": .string(date),
                "settlement_kind": .string("CUTOFF_CENTER"),
                "amount": .number(900),
                "settled_amount": .number(900)]))
        }
        if addInvalidLegacy {
            events.append(event("settlement_transfer", "invalid", [
                "date": .string(date),
                "settlement_kind": .string("CUTOFF_LEGACY_INVALID"),
                "amount": .number(99),
                "settled_amount": .number(99)]))
        }
        var result = ReadOnlySnapshot()
        result.apply(events)
        return result
    }
    private func report(_ snap: ReadOnlySnapshot) -> TXFundAuditReport {
        TXFundAuditPolicy.audit(snap, since: "2026-11-01", through: "2026-11-30")
    }

    func testValidPartialRegistrationUsesIntegerCentsNotCumulativeBalance() {
        let result = report(snapshot())
        XCTAssertEqual(result.days.count, 1)
        XCTAssertEqual(result.verifiedTransferCount, 1)
        XCTAssertEqual(result.plannedCents, 10000)
        XCTAssertEqual(result.settledCents, 2000)
        XCTAssertEqual(result.remainingCents, 8000)
        XCTAssertTrue(result.issues.isEmpty)
    }

    func testCutoffTransfersAreNeverDoubleCountedAsDaily() {
        let result = report(snapshot(addCutoff: true))
        XCTAssertEqual(result.plannedCents, 10000)
        XCTAssertEqual(result.settledCents, 2000)
        XCTAssertEqual(result.periodTransferCount, 1)
        XCTAssertTrue(result.issues.isEmpty)
    }

    func testOrphanAndDuplicateDayNeverProduceFalseVerifiedAmounts() {
        let missing = report(snapshot(dailyCount: 0))
        XCTAssertEqual(missing.verifiedTransferCount, 0)
        XCTAssertEqual(missing.plannedCents, 0)
        XCTAssertTrue(missing.issues.contains { $0.title.contains("缺失") })
        let multiple = report(snapshot(dailyCount: 2))
        XCTAssertEqual(multiple.verifiedTransferCount, 0)
        XCTAssertEqual(multiple.plannedCents, 0)
        XCTAssertTrue(multiple.issues.contains { $0.title.contains("多份") })
    }

    func testExactAndroidLongIDLinkageRejectsWrongParent() {
        let mismatched = report(snapshot(wrongLink: true))
        XCTAssertEqual(mismatched.verifiedTransferCount, 0)
        XCTAssertEqual(mismatched.remainingCents, 0)
        XCTAssertTrue(mismatched.issues.contains { $0.title.contains("关联异常") })
        XCTAssertEqual(report(snapshot()).verifiedTransferCount, 1)
    }

    func testOverRegisteredTransferAndInvalidPrecisionExcludedFromTotals() {
        let excessive = report(snapshot(settled: 101))
        XCTAssertEqual(excessive.verifiedTransferCount, 0)
        XCTAssertTrue(excessive.issues.contains { $0.title.contains("金额异常") })
        let rounding = report(snapshot(settled: 12.345))
        XCTAssertEqual(rounding.settledCents, 0)
        XCTAssertTrue(rounding.issues.contains { $0.title.contains("金额异常") })
    }

    func testSavedStatusMismatchFlaggedButNotTreatedAsCutoffEffectiveState() {
        let inconsistent = report(snapshot(status: 1, addCutoff: true))
        XCTAssertTrue(inconsistent.issues.contains {
            $0.title.contains("原始状态与转账登记不一致")
        })
        XCTAssertEqual(inconsistent.remainingCents, 8000)
        XCTAssertEqual(inconsistent.periodTransferCount, 1)
    }

    func testInvalidLegacyIsVisibleButExcludedFromDailyTotals() {
        let result = report(snapshot(addInvalidLegacy: true))
        XCTAssertEqual(result.periodTransferCount, 1)
        XCTAssertEqual(result.plannedCents, 10000)
        XCTAssertTrue(result.issues.contains { $0.title.contains("旧版无效") })
    }

    func testDateRangeDoesNotIncludeOutsideTransfers() {
        let result = TXFundAuditPolicy.audit(snapshot(),
                                            since: "2026-11-21",
                                            through: "2026-11-30")
        XCTAssertTrue(result.days.isEmpty)
        XCTAssertEqual(result.plannedCents, 0)
    }
}
