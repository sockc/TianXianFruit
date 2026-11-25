import XCTest
@testable import TianXianFruit

final class TXLedgerV33Tests: XCTestCase {
    func testCapabilitiesFailClosed() throws {
        let json = """
        {"version":1,"scope":"iOS_TEST_","atomic":true,"idempotency":true,"operation_lookup":true,"inventory":true,"daily_generate":false}
        """
        let c = try JSONDecoder().decode(TXLedgerV33Capabilities.self, from: Data(json.utf8))
        XCTAssertTrue(c.allows("inventory"))
        XCTAssertFalse(c.allows("daily"))
        XCTAssertFalse(c.allows("formal"))
    }
    func testAutosaveRequiresExplicitServerGateAndInventoryPermission() throws {
        for (inventory, auto, expected) in [(true, false, false), (false, true, false), (true, true, true)] {
            let json = """
            {"version":1,"scope":"iOS_TEST_","atomic":true,"idempotency":true,"operation_lookup":true,"inventory":\(inventory),"inventory_autosave":\(auto),"daily_generate":false}
            """
            let c = try JSONDecoder().decode(TXLedgerV33Capabilities.self, from: Data(json.utf8))
            XCTAssertEqual(c.allows("inventory_auto"), expected)
        }
        let legacy = """
        {"version":1,"scope":"iOS_TEST_","atomic":true,"idempotency":true,"operation_lookup":true,"inventory":true,"daily_generate":false}
        """
        let c = try JSONDecoder().decode(TXLedgerV33Capabilities.self, from: Data(legacy.utf8))
        XCTAssertFalse(c.allows("inventory_auto"))
    }

    func testAutosaveDoesNotUploadRestoredDraftsOrChangedScope() {
        func maySend(fresh: Bool = true, scope: String = "test|book|day",
                     revision: String = "r1", busy: Bool = false, pending: Bool = false) -> Bool {
            TXLedgerV33AutosavePolicy.canSubmit(enabled: true, fresh: fresh,
                originalScope: "test|book|day", currentScope: scope,
                originalRevision: "r1", currentRevision: revision,
                editable: true, busy: busy, pending: pending)
        }
        XCTAssertTrue(maySend())
        XCTAssertFalse(maySend(fresh: false))
        XCTAssertFalse(maySend(scope: "test|other|day"))
        XCTAssertFalse(maySend(revision: "r2"))
        XCTAssertFalse(maySend(busy: true))
        XCTAssertFalse(maySend(pending: true))
    }

    func testReceiptRequiresExactScopeAndReadback() throws {
        let body = TXLedgerV33Body(protocol_version: 1,
            book_id: "11111111-2222-4333-8444-555555555555", account: "tester",
            operation_id: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            device_id: "device-test-01", date: "2026-11-25", expected_cursor: 4,
            lines: [.init(kind: "stock", fruit_sync_id: "fruit", fruit_row_version: 1,
                unit: "件", expected_sync_id: "stock", expected_row_version: 2,
                amount: "2", loss: "0.5")], allocations: nil)
        let p = TXLedgerV33Proposal(feature: "inventory", body: body, stockDrafts: [], priceDrafts: [])
        let receipt = TXLedgerV33Receipt(status: "committed", feature: "inventory",
            book_id: body.book_id, operation_id: body.operation_id, date: body.date,
            sync_cursor: 5, changes: [.init(table_name: "inventory_snapshot", sync_id: "stock",
                row_version: 3, operation: "UPSERT")], request_hash: String(repeating: "a", count: 64))
        XCTAssertTrue(receipt.matches(p))
        XCTAssertFalse(receipt.readback(p, snapshot: ReadOnlySnapshot(), cursor: 5))
        var snap = ReadOnlySnapshot()
        snap.apply([SyncEvent(tableName: "fruit", syncId: "fruit", rowVersion: 1, operation: "UPSERT",
            payload: ["id": .number(1)]),
            SyncEvent(tableName: "inventory_snapshot", syncId: "stock", rowVersion: 3, operation: "UPSERT",
                payload: ["date": .string(body.date), "fruit_id": .number(1), "unit": .string("件"),
                    "remaining_quantity": .number(2), "loss_quantity": .number(0.5)])])
        XCTAssertTrue(receipt.readback(p, snapshot: snap, cursor: 5))
        XCTAssertFalse(receipt.readback(p, snapshot: snap, cursor: 4))
        snap.apply([SyncEvent(tableName: "inventory_snapshot", syncId: "stock", rowVersion: 4,
            operation: "UPSERT", payload: ["remaining_quantity": .number(9)])])
        XCTAssertFalse(receipt.readback(p, snapshot: snap, cursor: 6))
    }
}
