import XCTest
@testable import TianXianFruit

final class FundHistoryReadOnlyTests: XCTestCase {
    private func event(_ table: String, _ id: String, _ date: String,
                       _ fields: [String: JSONValue] = [:],
                       version: Int64 = 1, operation: String = "UPSERT") -> SyncEvent {
        var payload = fields
        payload["date"] = .string(date)
        return SyncEvent(tableName: table, syncId: id, rowVersion: version,
                         operation: operation, payload: payload)
    }

    func testPartnerDailyRawFundsAreNotCumulativeBalance() throws {
        var snapshot = ReadOnlySnapshot()
        snapshot.apply([
            event("settlement_partner", "a", "2026-11-20", [
                "partner_name": .string("张"), "purchase_paid": .number(100),
                "expense_paid": .number(10), "revenue_received": .number(200),
                "profit_share": .number(30), "should_keep": .number(140),
                "balance": .number(-60)]),
            event("settlement_partner", "b", "2026-11-19", [
                "partner_name": .string("张"), "balance": .number(50)]),
            event("settlement_partner", "c", "2026-11-20", [
                "partner_name": .string("李"), "balance": .number(60)]),
            event("settlement_transfer", "t", "2026-11-20", [
                "from_partner_name": .string("张"),
                "to_partner_name": .string("李"),
                "settlement_kind": .string("DAILY"),
                "amount": .number(60), "settled_amount": .number(20)])
        ])
        let days = TXFundHistoryPolicy.days(snapshot, since: "2026-11-18",
                                            until: "2026-11-21", partner: "张")
        XCTAssertEqual(days.count, 2)
        XCTAssertEqual(days.map(\.date), ["2026-11-20", "2026-11-19"])
        XCTAssertEqual(days[0].partnerRows.count, 1)
        XCTAssertEqual(days[0].partnerRows[0].balance, -60)
        XCTAssertEqual(days[0].dailyTransfers[0].recordedSettled, 20)
        XCTAssertEqual(days[1].partnerRows[0].balance, 50)
        XCTAssertFalse(TXFundHistoryPolicy.cumulativeBalanceIsAuthoritative,
                       "Do not sum -60 + 50 to invent a -10 current balance")
    }

    func testPeriodTransfersGroupByActualBatchAndKeepUnknownUnbatched() throws {
        var snapshot = ReadOnlySnapshot()
        let shared: [String: JSONValue] = [
            "settlement_kind": .string("CUTOFF_CENTER"),
            "batch_key": .string("cutoff-123"),
            "range_start_date": .string("2026-11-01"),
            "range_end_date": .string("2026-11-20"),
            "focus_partner_name": .string("张")]
        snapshot.apply([
            event("settlement_transfer", "a", "2026-11-21", shared.merging([
                "from_partner_name": .string("资金中心"),
                "to_partner_name": .string("张"),
                "amount": .number(120), "settled_amount": .number(120)]) { _, new in new }),
            event("settlement_transfer", "b", "2026-11-21", shared.merging([
                "from_partner_name": .string("李"),
                "to_partner_name": .string("资金中心"),
                "amount": .number(35), "settled_amount": .number(25)]) { _, new in new }),
            event("settlement_transfer", "blank-1", "2026-11-21", [
                "settlement_kind": .string("LEGACY"), "amount": .number(10)]),
            event("settlement_transfer", "blank-2", "2026-11-21", [
                "settlement_kind": .string("LEGACY"), "amount": .number(30)]),
            event("settlement_transfer", "daily", "2026-11-21", [
                "settlement_kind": .string("DAILY"), "amount": .number(40)])
        ])
        let batches = TXFundHistoryPolicy.batches(snapshot, since: "2026-11-20",
                                                  until: "2026-11-21")
        XCTAssertEqual(batches.count, 3,
                       "Two cutoff records form one saved batch; blank keys stay separate")
        let main = try XCTUnwrap(batches.first(where: { $0.transfers.count == 2 }))
        XCTAssertEqual(main.plannedTotal, 155)
        XCTAssertEqual(main.registeredTotal, 145)
        XCTAssertEqual(main.recordedLabel, "已登记部分金额")
        XCTAssertEqual(main.batchRange, "覆盖营业期间 2026-11-01 至 2026-11-20")
        XCTAssertEqual(TXFundHistoryPolicy.batches(snapshot,
            since: "2026-11-21", until: "2026-11-21", partner: "王").count, 0)
    }

    func testCancelledCutoffAndInvalidLegacyAreNotCurrentBalanceEvidence() throws {
        var snapshot = ReadOnlySnapshot()
        snapshot.apply([
            event("settlement_transfer", "cancelled", "2026-11-21", [
                "settlement_kind": .string("CUTOFF_CENTER"),
                "batch_key": .string("k1"),
                "from_partner_name": .string("资金中心"),
                "to_partner_name": .string("张"),
                "amount": .number(100), "settled_amount": .number(0),
                "confirmed_at": .number(1795200000000)]),
            event("settlement_transfer", "invalid", "2026-11-21", [
                "settlement_kind": .string("CUTOFF_LEGACY_INVALID"),
                "batch_key": .string("k2"), "amount": .number(300),
                "settled_amount": .number(300)])
        ])
        let batches = TXFundHistoryPolicy.batches(snapshot, since: "2026-11-21",
                                                  until: "2026-11-21")
        XCTAssertEqual(batches.count, 2)
        XCTAssertEqual(batches.first(where: { $0.isCancelledCutoff })?.recordedLabel,
                       "已撤销（原登记清零）")
        XCTAssertEqual(batches.first(where: { $0.isLegacyInvalid })?.recordedLabel,
                       "旧版无效记录")
    }

    func testTombstonesPreventHistoryResurrection() {
        var snapshot = ReadOnlySnapshot()
        let original = event("settlement_transfer", "period", "2026-11-21", [
            "settlement_kind": .string("CUTOFF_CENTER"),
            "amount": .number(200)])
        snapshot.apply([original])
        XCTAssertEqual(TXFundHistoryPolicy.batches(snapshot, since: "2026-11-21",
                                                    until: "2026-11-21").count, 1)
        snapshot.apply([
            event("settlement_transfer", "period", "2026-11-21",
                  version: 2, operation: "DELETE"),
            original
        ])
        XCTAssertTrue(TXFundHistoryPolicy.batches(snapshot, since: "2026-11-21",
                                                   until: "2026-11-21").isEmpty)
    }

    func testFilterUsesRecordDateNotCoveredBusinessDate() {
        var snapshot = ReadOnlySnapshot()
        snapshot.apply([
            event("settlement_transfer", "cutoff", "2026-11-21", [
                "settlement_kind": .string("CUTOFF"),
                "range_start_date": .string("2026-11-01"),
                "range_end_date": .string("2026-11-20"),
                "amount": .number(150)])
        ])
        XCTAssertTrue(TXFundHistoryPolicy.batches(snapshot,
            since: "2026-11-01", until: "2026-11-20").isEmpty)
        XCTAssertEqual(TXFundHistoryPolicy.batches(snapshot,
            since: "2026-11-21", until: "2026-11-21").count, 1)
    }
}
