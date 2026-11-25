import XCTest
@testable import TianXianFruit

final class PurchaseAtomicWriteTests: XCTestCase {
    private func proposal(id: String = UUID().uuidString,
                          book: String = UUID().uuidString)
        -> PurchaseCommitProposal {
        PurchaseCommitProposal(
            protocolVersion: 1, bookID: book, account: "seller",
            draftID: id, date: "2026-11-20",
            buyerSyncID: "buyer-1", purchaseType: "PLANNED",
            remark: "", operation: "create_purchase",
            totalCost: "30.00", lines: [
                PurchaseCommitItem(
                    fruitSyncID: "fruit-1", fruitName: "苹果",
                    unit: "件", quantity: "2",
                    unitPrice: "15.00", lineTotal: "30.00",
                    unitWeightJin: "20", sourcePlanItemSyncID: nil)
            ], expectedVersions: [],
            payloadHash: String(repeating: "a", count: 64))
    }

    private func store() throws -> (PurchaseWorkspaceStore, URL) {
        let folder = FileManager.default.temporaryDirectory
            .appendingPathComponent("purchase-atomic-" + UUID().uuidString)
        try FileManager.default.createDirectory(
            at: folder, withIntermediateDirectories: true)
        return (try PurchaseWorkspaceStore(
            url: folder.appendingPathComponent("test.sqlite")), folder)
    }

    private func receipt(_ proposed: PurchaseCommitProposal) -> PurchaseCommitReceipt {
        PurchaseCommitReceipt(
            status: "committed", operationID: proposed.draftID.lowercased(),
            bookID: proposed.bookID.lowercased(),
            orderSyncID: "order-cloud-1", itemSyncIDs: ["item-cloud-1"],
            syncCursor: 100)
    }

    private func event(_ table: String, _ id: String,
                       _ payload: [String: JSONValue]) -> SyncEvent {
        SyncEvent(tableName: table, syncId: id,
                  rowVersion: 1, operation: "UPSERT", payload: payload)
    }

    func testServerCapabilityMustExplicitlyAdvertiseAllGuarantees() {
        let proposed = proposal()
        let supported = PurchaseCommitCapabilities(
            version: 1, enabled: true, supportsCompletePlan: false,
            atomic: true, idempotency: true,
            feature: "purchase_atomic_commit_v1", scope: nil)
        XCTAssertTrue(supported.allows(proposed))
        let disabled = PurchaseCommitCapabilities(
            version: 1, enabled: false, supportsCompletePlan: false,
            atomic: true, idempotency: true,
            feature: "purchase_atomic_commit_v1", scope: nil)
        XCTAssertFalse(disabled.allows(proposed))
        var plan = proposed
        plan = PurchaseCommitProposal(
            protocolVersion: 1, bookID: proposed.bookID,
            account: proposed.account, draftID: proposed.draftID,
            date: proposed.date, buyerSyncID: proposed.buyerSyncID,
            purchaseType: proposed.purchaseType, remark: proposed.remark,
            operation: "complete_plan", totalCost: proposed.totalCost,
            lines: proposed.lines, expectedVersions: [],
            payloadHash: proposed.payloadHash)
        XCTAssertFalse(supported.allows(plan))
    }

    func testFormalPurchasePreparationRequiresMatchingExplicitScope() throws {
        let formal = CloudBook(id: "book-formal-1", name: "天鲜正式账本",
                               role: "OWNER", deleted: false, purged: false)
        let user = Account(username: "seller", displayName: "采购人", systemRole: nil)
        let draft = PurchaseWorkspaceDraft(
            id: UUID().uuidString, account: "seller", bookID: formal.id,
            date: "2026-11-20", buyerSyncID: "buyer-1", buyerName: "张",
            purchaseType: "PLANNED", remark: "",
            lines: [PurchaseDraftLine(
                id: UUID().uuidString, fruitSyncID: "fruit-1",
                fruitName: "苹果", quantity: "2", unit: "件",
                unitPrice: "15", unitWeightJin: "20")],
            createdAt: Date(), updatedAt: Date())
        let records = [
            event("partner", "buyer-1", [
                "name": .string("张"), "id": .number(101)]),
            event("fruit", "fruit-1", [
                "name": .string("苹果"), "id": .number(202)])
        ]
        let allow = PurchaseCommitCapabilities(
            version: 1, enabled: true, supportsCompletePlan: true,
            atomic: true, idempotency: true,
            feature: "purchase_atomic_commit_v1", scope: "formal")
        let testOnly = PurchaseCommitCapabilities(
            version: 1, enabled: true, supportsCompletePlan: true,
            atomic: true, idempotency: true,
            feature: "purchase_atomic_commit_v1", scope: "test")
        XCTAssertThrowsError(try PurchaseCommitPlanningPolicy.prepare(
            draft, account: user, book: formal, records: records))
        XCTAssertThrowsError(try PurchaseCommitPlanningPolicy.prepare(
            draft, account: user, book: formal, records: records,
            capabilities: testOnly))
        let proposal = try PurchaseCommitPlanningPolicy.prepare(
            draft, account: user, book: formal, records: records,
            capabilities: allow)
        XCTAssertEqual(proposal.operation, "create_purchase")
        XCTAssertEqual(proposal.bookID, formal.id)
        XCTAssertEqual(proposal.totalCost, "30.00")
        XCTAssertTrue(allow.allows(book: formal, proposal: proposal))
        XCTAssertFalse(testOnly.allows(book: formal, proposal: proposal))
    }

    func testDurableReservationAndUnknownStateAfterRestart() throws {
        let (db, folder) = try store()
        defer { try? FileManager.default.removeItem(at: folder) }
        let proposed = proposal()
        let initial = try db.reserve(
            proposed, account: "seller", bookID: proposed.bookID)
        XCTAssertEqual(initial.phase, .prepared)
        XCTAssertThrowsError(try db.assertEditable(
            id: proposed.draftID, account: "seller", bookID: proposed.bookID))
        var unknown = initial
        unknown.phase = .unknown
        unknown.updatedAt = Date().addingTimeInterval(1)
        try db.updateAttempt(
            unknown, account: "seller", bookID: proposed.bookID)
        let reopened = try PurchaseWorkspaceStore(
            url: folder.appendingPathComponent("test.sqlite"))
        let loaded = try XCTUnwrap(reopened.attempt(
            id: proposed.draftID, account: "seller",
            bookID: proposed.bookID))
        XCTAssertEqual(loaded.phase, .unknown)
        XCTAssertEqual(loaded.proposal, proposed)
        let duplicate = try reopened.reserve(
            proposed, account: "seller", bookID: proposed.bookID)
        XCTAssertEqual(duplicate.phase, .unknown)
        XCTAssertThrowsError(try reopened.reserve(
            proposal(id: proposed.draftID, book: "wrong-book"),
            account: "seller", bookID: proposed.bookID))
    }

    func testNoSecondProposalWithSameOperationUUID() throws {
        let (db, folder) = try store()
        defer { try? FileManager.default.removeItem(at: folder) }
        let original = proposal()
        _ = try db.reserve(
            original, account: "seller", bookID: original.bookID)
        let changed = PurchaseCommitProposal(
            protocolVersion: original.protocolVersion,
            bookID: original.bookID, account: original.account,
            draftID: original.draftID, date: original.date,
            buyerSyncID: original.buyerSyncID,
            purchaseType: original.purchaseType, remark: "修改了金额",
            operation: original.operation, totalCost: original.totalCost,
            lines: original.lines, expectedVersions: [],
            payloadHash: String(repeating: "b", count: 64))
        XCTAssertThrowsError(try db.reserve(
            changed, account: "seller", bookID: original.bookID))
        XCTAssertNil(try db.attempt(
            id: original.draftID, account: "another",
            bookID: original.bookID))
    }

    func testAcceptedReceiptAndFinalReadback() throws {
        let (db, folder) = try store()
        defer { try? FileManager.default.removeItem(at: folder) }
        let p = proposal()
        var attempt = try db.reserve(p, account: "seller", bookID: p.bookID)
        let committed = receipt(p)
        attempt.receipt = committed
        attempt.phase = .accepted
        attempt.updatedAt = Date().addingTimeInterval(1)
        try db.updateAttempt(attempt, account: "seller", bookID: p.bookID)
        let history = [
            event("purchase_order", "order-cloud-1", [
                "id": .number(8101), "date": .string("2026-11-20"),
                "total_cost": .number(30)]),
            event("purchase_item", "item-cloud-1", [
                "id": .number(8102), "order_id": .number(8101),
                "fruit_name": .string("苹果"), "unit": .string("件"),
                "quantity": .number(2), "unit_price": .number(15),
                "total_cost": .number(30)])
        ]
        XCTAssertTrue(PurchaseAtomicReadbackPolicy.confirmed(
            p, receipt: committed, records: history))
        XCTAssertFalse(PurchaseAtomicReadbackPolicy.confirmed(
            p, receipt: committed, records: Array(history.dropLast())))
        var confirmed = attempt
        confirmed.phase = .confirmed
        confirmed.updatedAt = Date().addingTimeInterval(2)
        try db.updateAttempt(confirmed, account: "seller", bookID: p.bookID)
        XCTAssertThrowsError(try db.updateAttempt(
            attempt, account: "seller", bookID: p.bookID))
    }

    func testWrongReceiptAndMismatchedItemNeverConfirm() {
        let proposed = proposal()
        let wrong = PurchaseCommitReceipt(
            status: "committed", operationID: UUID().uuidString,
            bookID: proposed.bookID, orderSyncID: "order-cloud-1",
            itemSyncIDs: ["item-cloud-1"], syncCursor: 110)
        XCTAssertFalse(wrong.isFor(proposed))
        let history = [
            event("purchase_order", "order-cloud-1", [
                "id": .number(8101), "date": .string("2026-11-20"),
                "total_cost": .number(30)]),
            event("purchase_item", "item-cloud-1", [
                "id": .number(8102), "order_id": .number(8101),
                "fruit_name": .string("苹果"), "unit": .string("件"),
                "quantity": .number(3), "unit_price": .number(15),
                "total_cost": .number(30)])
        ]
        XCTAssertFalse(PurchaseAtomicReadbackPolicy.confirmed(
            proposed, receipt: receipt(proposed), records: history))
    }
    func testPlanCompletionMustReadBackOrderItemsAndAtomicStatus() {
        let initial = proposal()
        let linkedLine = PurchaseCommitItem(
            fruitSyncID: "fruit-1", fruitName: "苹果",
            unit: "件", quantity: "2", unitPrice: "15.00",
            lineTotal: "30.00", unitWeightJin: "20",
            sourcePlanItemSyncID: "plan-item-a")
        let plan = PurchaseCommitProposal(
            protocolVersion: initial.protocolVersion,
            bookID: initial.bookID, account: initial.account,
            draftID: initial.draftID, date: initial.date,
            buyerSyncID: initial.buyerSyncID,
            purchaseType: initial.purchaseType,
            remark: initial.remark,
            operation: "complete_plan",
            totalCost: initial.totalCost,
            lines: [linkedLine],
            expectedVersions: [], payloadHash: initial.payloadHash)
        let remoteID: Int64 = 79152283000000002
        let orderID: Int64 = 79152283000000004
        let cloud = [
            event("partner", "buyer-1", [
                "id": .exactInteger(remoteID), "name": .string("头脑")]),
            event("purchase_order", "order-cloud-1", [
                "id": .exactInteger(orderID), "date": .string("2026-11-20"),
                "buyer_id": .exactInteger(remoteID),
                "total_cost": .number(30)]),
            event("purchase_item", "item-cloud-1", [
                "id": .exactInteger(79152283000000005),
                "order_id": .exactInteger(orderID),
                "fruit_name": .string("苹果"),
                "unit": .string("件"), "quantity": .number(2),
                "unit_price": .number(15), "total_cost": .number(30)]),
            event("purchase_plan_item", "plan-item-a", [
                "status": .number(1), "fruit_name": .string("苹果"),
                "unit": .string("件")]),
            event("purchase_collaboration", "collab-a", [
                "plan_item_sync_id": .string("plan-item-a"),
                "buyer_id": .exactInteger(remoteID),
                "actual_quantity": .number(2),
                "actual_amount": .number(30),
                "purchase_order_sync_id": .string("order-cloud-1")])
        ]
        let receipt = receipt(plan)
        XCTAssertTrue(PurchaseAtomicReadbackPolicy.confirmed(
            plan, receipt: receipt, records: cloud))
        XCTAssertFalse(PurchaseAtomicReadbackPolicy.confirmed(
            plan, receipt: receipt,
            records: cloud.filter { $0.tableName != "purchase_collaboration" }))
        let notCompleted = cloud.map { item -> SyncEvent in
            if item.syncId != "plan-item-a" { return item }
            var p = item.payload
            p["status"] = .number(0)
            return event(item.tableName, item.syncId, p)
        }
        XCTAssertFalse(PurchaseAtomicReadbackPolicy.confirmed(
            plan, receipt: receipt, records: notCompleted))
        let wrongOrder = cloud.map { item -> SyncEvent in
            if item.syncId != "collab-a" { return item }
            var p = item.payload
            p["purchase_order_sync_id"] = .string("another-order")
            return event(item.tableName, item.syncId, p)
        }
        XCTAssertFalse(PurchaseAtomicReadbackPolicy.confirmed(
            plan, receipt: receipt, records: wrongOrder))
    }

}
