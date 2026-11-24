import XCTest
@testable import TianXianFruit

final class TXSettlementHistory091Tests: XCTestCase {
    private func event(_ table: String, _ key: String,
                       _ payload: [String: JSONValue]) -> SyncEvent {
        SyncEvent(tableName: table, syncId: key, rowVersion: 1,
                  operation: "UPSERT", payload: payload)
    }
    private func snapshot() -> ReadOnlySnapshot {
        var s = ReadOnlySnapshot()
        s.apply([
            event("partner", "a", ["id": .number(1), "name": .string("甲"), "enabled": .number(1)]),
            event("partner", "b", ["id": .number(2), "name": .string("乙"), "enabled": .number(1)]),
            event("partner", "c", ["id": .number(3), "name": .string("丙"), "enabled": .number(1)]),
            event("profit_rule", "r", ["id": .number(1), "partner_id": .number(1),
                                       "is_settlement_center": .number(1)]),
            event("daily_cash_settlement", "d", ["id": .number(8), "date": .string("2026-11-10")])
        ])
        return s
    }
    private func transfer(_ key: String, date: String = "2026-11-10",
                          parent: Int = 8, kind: String = "DAILY", batch: String = "",
                          amount: Double = 100, paid: Double = 40,
                          from: Int = 2, to: Int = 1, stamp: Double = 0) -> SyncEvent {
        let names = [1: "甲", 2: "乙", 3: "丙"]
        return event("settlement_transfer", key, [
            "date": .string(date), "settlement_id": .number(Double(parent)),
            "settlement_kind": .string(kind), "batch_key": .string(batch),
            "amount": .number(amount), "settled_amount": .number(paid),
            "from_partner_id": .number(Double(from)), "to_partner_id": .number(Double(to)),
            "from_partner_name": .string(names[from] ?? ""),
            "to_partner_name": .string(names[to] ?? ""),
            "confirmed_at": .number(stamp)
        ])
    }
    private func entries(_ s: ReadOnlySnapshot, partner: String = "") -> [TXSettlementHistory091Policy.Entry] {
        TXSettlementHistory091Policy.entries(s, since: "2026-11-01", until: "2026-11-25",
                                              partner: partner)
    }
    func testDailyParentIncludesPendingTransfersAndFullBatchPartnerFilter() throws {
        var s = snapshot()
        s.apply([transfer("a"), transfer("b", amount: 50, paid: 0, from: 3)])
        let daily = try XCTUnwrap(entries(s, partner: "乙").first(where: { $0.daily }))
        XCTAssertEqual(daily.transfers.count, 2)
        XCTAssertEqual(daily.planned, 150)
        XCTAssertEqual(daily.registered, 40)
        XCTAssertEqual(daily.state, .partial)
        XCTAssertTrue(daily.isHistory)
    }
    func testStandaloneDailyLabelUsesActualParentAndCenterRule() throws {
        var s = snapshot()
        s.apply([transfer("loose", parent: 0, amount: 40, paid: 40, stamp: 10),
                 transfer("old", parent: 0, kind: "LEGACY", amount: 40, paid: 40,
                          from: 2, to: 3, stamp: 10)])
        let list = entries(s)
        let loose = try XCTUnwrap(list.first(where: { $0.id == "single|loose" }))
        XCTAssertFalse(loose.daily)
        XCTAssertEqual(loose.kind, "CUTOFF_CENTER")
        XCTAssertEqual(loose.state, .full)
        XCTAssertEqual(list.first(where: { $0.id == "single|old" })?.state, .invalid)
    }
    func testCancelledBatchAndMissingParent() throws {
        var s = snapshot()
        s.apply([transfer("a", parent: 0, kind: "CUTOFF_CENTER", batch: "k", paid: 0, stamp: 5),
                 transfer("b", parent: 0, kind: "CUTOFF_CENTER", batch: "k", paid: 0, stamp: 5),
                 transfer("orphan", parent: 999)])
        let list = entries(s)
        let batch = try XCTUnwrap(list.first(where: { $0.id == "batch|k" }))
        XCTAssertEqual(batch.state, .cancelled)
        XCTAssertTrue(batch.isHistory)
        XCTAssertEqual(batch.transfers.count, 2)
        XCTAssertEqual(list.first(where: { $0.id == "unresolved|orphan" })?.state, .review)
        XCTAssertEqual(list.first(where: { $0.daily })?.state, .review)
    }
    func testMissingAmountAndDuplicateParentFailClosed() throws {
        var s = snapshot()
        s.apply([event("daily_cash_settlement", "other", ["id": .number(8), "date": .string("2026-11-11")]),
                 transfer("ambiguous"),
                 event("settlement_transfer", "missing", ["date": .string("2026-11-10"),
                    "settlement_id": .number(0), "settlement_kind": .string("CUTOFF"),
                    "settled_amount": .number(30)])])
        let list = entries(s)
        XCTAssertEqual(list.first(where: { $0.id == "unresolved|ambiguous" })?.state, .review)
        let missing = try XCTUnwrap(list.first(where: { $0.id == "single|missing" }))
        XCTAssertEqual(missing.state, .review)
        XCTAssertNil(missing.planned)
        XCTAssertNil(missing.registered)
    }
}
