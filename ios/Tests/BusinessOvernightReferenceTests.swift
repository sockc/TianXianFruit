import XCTest
@testable import TianXianFruit

final class BusinessOvernightReferenceTests: XCTestCase {
    private func event(_ date: String, partner: String = "p", store: String = "a",
                       revision: Int64 = 3, recorded: Bool = true, amount: Double = 12.34) -> CustomerChangeEvent {
        CustomerChangeEvent(kind: "OVERNIGHT", key: date + "|" + partner, revision: revision,
            payload: ["date": .string(date), "partner_sync_id": .string(partner), "store_sync_id": .string(store),
                      "wechat": .number(amount), "alipay": .number(0), "cash": .number(0),
                      "wechat_recorded": .bool(recorded), "alipay_recorded": .bool(false), "cash_recorded": .bool(true)])
    }
    func testNaturalDateAndCollectorNotPosition() throws {
        let events = [event("2026-11-24", amount: 99), event("2026-11-25", store: "different"),
                      event("2026-11-26", amount: 5), event("2026-11-25", partner: "other", amount: 100)]
        let ref = try XCTUnwrap(TXBusinessOvernightReferencePolicy.reference(events, businessDate: "2026-11-25", partner: "p"))
        XCTAssertEqual(ref.previous, .recorded(.init(wechat: 1234, alipay: nil, cash: 0)))
        XCTAssertEqual(ref.following, .recorded(.init(wechat: 500, alipay: nil, cash: 0)))
        XCTAssertEqual(ref.followingDate, "2026-11-26")
    }
    func testMissingAndUnconfirmedAreNotZero() {
        XCTAssertEqual(TXBusinessOvernightReferencePolicy.morning([], date: "2026-11-25", partner: "p"), .missing)
        XCTAssertEqual(TXBusinessOvernightReferencePolicy.morning([event("2026-11-25", recorded: false, amount: 0)], date: "2026-11-25", partner: "p"), .recorded(.init(wechat: nil, alipay: nil, cash: 0)))
        XCTAssertEqual(TXBusinessOvernightReferencePolicy.morning([event("2026-11-25", recorded: false)], date: "2026-11-25", partner: "p"), .invalid)
    }
    func testDuplicateMalformedOrExcessPrecisionRejected() {
        let a = event("2026-11-25")
        XCTAssertEqual(TXBusinessOvernightReferencePolicy.morning([a,a], date: "2026-11-25", partner: "p"), .invalid)
        XCTAssertEqual(TXBusinessOvernightReferencePolicy.morning([event("2026-11-25", amount: 0.001)], date: "2026-11-25", partner: "p"), .invalid)
        let malformed = CustomerChangeEvent(kind: a.kind, key: a.key, revision: a.revision, payload: ["date": .string("2026-11-25"), "partner_sync_id": .string("p")])
        XCTAssertEqual(TXBusinessOvernightReferencePolicy.morning([malformed], date: "2026-11-25", partner: "p"), .invalid)
    }
}
