import XCTest
@testable import TianXianFruit

final class PurchasePlanCompletionTests: XCTestCase {
    private let buyerID: Int64 = 79152283000000002
    private let fruitID: Int64 = 79152283000000001
    private let planID: Int64 = 79152283000000003

    private func row(_ table: String, _ sid: String,
                     _ data: [String: JSONValue],
                     version: Int64 = 2) -> SyncEvent {
        SyncEvent(tableName: table, syncId: sid, rowVersion: version,
                  operation: "UPSERT", payload: data)
    }

    private var catalog: [SyncEvent] {
        [
            row("partner", "buyer-a", ["id": .exactInteger(buyerID),
                                      "name": .string("头脑")]),
            row("partner", "buyer-b", ["id": .exactInteger(79152283000000004),
                                      "name": .string("另一采购人")]),
            row("fruit", "fruit-a", ["id": .exactInteger(fruitID),
                                    "name": .string("葡萄")]),
            row("purchase_plan", "plan-a", [
                "id": .exactInteger(planID),
                "plan_date": .string("2026-11-21"),
                "status": .number(0)]),
            row("purchase_plan_item", "plan-item-a", [
                "id": .exactInteger(79152283000000005),
                "plan_id": .exactInteger(planID),
                "fruit_id": .exactInteger(fruitID),
                "fruit_name": .string("葡萄"), "quantity": .number(2),
                "unit": .string("筐"), "unit_weight_jin": .number(18),
                "status": .number(0)]),
            row("purchase_collaboration", "collab-a", [
                "id": .exactInteger(79152283000000006),
                "plan_item_sync_id": .string("plan-item-a"),
                "buyer_id": .exactInteger(buyerID),
                "buyer_name": .string("头脑"),
                "purchase_order_sync_id": .string("")])
        ]
    }

    private func draft(_ lines: [PurchaseDraftLine]) -> PurchaseWorkspaceDraft {
        PurchaseWorkspaceDraft(
            id: UUID().uuidString, account: "seller",
            bookID: "test-book", date: "2026-11-21",
            buyerSyncID: "buyer-a", buyerName: "头脑",
            purchaseType: "PLANNED", remark: "",
            lines: lines, createdAt: Date(), updatedAt: Date())
    }

    private var planningRow: PurchasePlanningRow {
        PurchasePlanningRow(
            id: "plan-item-a", name: "葡萄", quantity: 2,
            unit: "筐", unitWeightJin: 18, remark: "",
            buyerName: "头脑", estimatedAmount: 100,
            actualQuantity: nil, actualAmount: nil, completedAt: nil,
            linkedOrderID: "", hasVerifiedOrder: false,
            isCompleted: false, warning: nil)
    }

    func testDirectPlanEntryHandlesInt64AndCorrectBuyer() throws {
        let partners = [
            TestLookup(id: "buyer-a", name: "头脑", localID: String(buyerID)),
            TestLookup(id: "buyer-b", name: "另一采购人",
                       localID: "79152283000000004")
        ]
        XCTAssertEqual(PurchasePlanCompletionPolicy.availableBuyers(
            for: planningRow, partners: partners).map(\.id), ["buyer-a"])
        let line = try XCTUnwrap(PurchasePlanCompletionPolicy.preparedItem(
            "plan-item-a", buyerSyncID: "buyer-a", date: "2026-11-21",
            events: catalog))
        XCTAssertEqual(line.fruitSyncID, "fruit-a")
        XCTAssertEqual(line.sourcePlanItemSyncID, "plan-item-a")
        XCTAssertEqual(line.quantity, "2")
        XCTAssertEqual(line.unitWeightJin, "18")
        XCTAssertEqual(line.unitPrice, "0")
        XCTAssertNil(PurchasePlanCompletionPolicy.preparedItem(
            "plan-item-a", buyerSyncID: "buyer-b", date: "2026-11-21",
            events: catalog))
        XCTAssertEqual(PurchasePlanCompletionPolicy.planSourceIDs(
            draft([line])), ["plan-item-a"])
        XCTAssertTrue(PurchasePlanCompletionPolicy.pendingSources(
            draft([line]), events: catalog))
    }

    func testLinkedPlanPreventsDuplicateLocalDraftAndCompletedReimport() throws {
        let line = try XCTUnwrap(PurchasePlanCompletionPolicy.preparedItem(
            "plan-item-a", buyerSyncID: "buyer-a", date: "2026-11-21",
            events: catalog))
        let first = draft([line])
        XCTAssertEqual(PurchasePlanCompletionPolicy.existingDraft(
            for: "plan-item-a", drafts: [first])?.id, first.id)
        XCTAssertNil(PurchasePlanCompletionPolicy.existingDraft(
            for: "another-item", drafts: [first]))
        var changed = catalog
        changed.removeAll { $0.syncId == "collab-a" }
        XCTAssertNil(PurchasePlanCompletionPolicy.preparedItem(
            "plan-item-a", buyerSyncID: "buyer-a", date: "2026-11-21",
            events: changed))
        XCTAssertFalse(PurchasePlanCompletionPolicy.pendingSources(
            first, events: changed))
        changed = catalog.map { source in
            if source.syncId != "plan-item-a" { return source }
            var p = source.payload
            p["status"] = .number(1)
            return row(source.tableName, source.syncId, p)
        }
        XCTAssertNil(PurchasePlanCompletionPolicy.preparedItem(
            "plan-item-a", buyerSyncID: "buyer-a", date: "2026-11-21",
            events: changed))
        XCTAssertFalse(PurchasePlanCompletionPolicy.pendingSources(
            first, events: changed))
    }

    func testDifferentOrdersSameBuyerDoNotInvalidateDistinctPendingPlan() throws {
        let line = try XCTUnwrap(PurchasePlanCompletionPolicy.preparedItem(
            "plan-item-a", buyerSyncID: "buyer-a", date: "2026-11-21",
            events: catalog))
        let previousOrder = row("purchase_order", "unrelated-order", [
            "id": .exactInteger(79152283000000007),
            "date": .string("2026-11-21"),
            "buyer_id": .exactInteger(buyerID),
            "buyer_name": .string("头脑"),
            "total_cost": .number(35)])
        XCTAssertTrue(PurchasePlanCompletionPolicy.pendingSources(
            draft([line]), events: catalog + [previousOrder]))
        var extra = PurchaseDraftLine.blank()
        extra.fruitSyncID = "fruit-other"
        XCTAssertNil(PurchasePlanCompletionPolicy.planSourceIDs(
            draft([line, extra])))
    }
}
