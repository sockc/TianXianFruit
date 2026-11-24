import XCTest
@testable import TianXianFruit

final class PurchaseWorkspaceTests: XCTestCase {
    private func event(_ table: String, _ syncID: String,
                       _ fields: [String: JSONValue],
                       operation: String = "UPSERT") -> SyncEvent {
        SyncEvent(tableName: table, syncId: syncID, rowVersion: 2,
                  operation: operation, payload: fields)
    }

    private func catalog() -> [SyncEvent] {
        [
            event("purchase_plan", "plan-1", [
                "id": .number(31), "plan_date": .string("2026-11-20")]),
            event("purchase_plan_item", "item-1", [
                "id": .number(71), "plan_id": .number(31),
                "fruit_name": .string("苹果"), "quantity": .number(4),
                "unit": .string("件"), "status": .number(0),
                "remark": .string("新鲜")]),
            event("purchase_plan_item", "item-2", [
                "id": .number(72), "plan_id": .number(31),
                "fruit_name": .string("香蕉"), "quantity": .number(2),
                "unit": .string("箱"), "status": .number(1)]),
            event("purchase_collaboration", "collab-1", [
                "plan_item_sync_id": .string("item-1"),
                "estimated_amount": .number(90),
                "actual_amount": .number(0),
                "buyer_name": .string("甲")]),
            event("purchase_collaboration", "collab-2", [
                "plan_item_sync_id": .string("item-2"),
                "estimated_amount": .number(80),
                "actual_amount": .number(75),
                "actual_quantity": .number(2),
                "buyer_name": .string("乙"),
                "purchase_order_sync_id": .string("order-1")]),
            event("purchase_order", "order-1", [
                "id": .number(500), "date": .string("2026-11-20"),
                "buyer_name": .string("乙"), "total_cost": .number(75)])
        ]
    }

    func testAndroidPurchasePlanPendingCompletedAndMetrics() throws {
        let day = PurchaseWorkspaceReadPolicy.materialize(
            catalog(), date: "2026-11-20")
        XCTAssertTrue(day.hasPlan)
        XCTAssertEqual(day.pending.count, 1)
        XCTAssertEqual(day.completed.count, 1)
        XCTAssertEqual(day.pending[0].name, "苹果")
        XCTAssertEqual(day.pending[0].buyerName, "甲")
        XCTAssertEqual(day.pending[0].estimatedAmount, 90)
        XCTAssertEqual(day.completed[0].name, "香蕉")
        XCTAssertEqual(day.completed[0].actualAmount, 75)
        XCTAssertEqual(day.completed[0].buyerName, "乙")
        XCTAssertTrue(day.completed[0].hasVerifiedOrder)
        XCTAssertEqual(day.plannedAmount, 170)
        XCTAssertEqual(day.completedAmount, 75)
        XCTAssertEqual(day.pendingFruits, 1)
        XCTAssertEqual(day.completedFruits, 1)
    }

    func testMissingCollaborationIsNotMisreadAsZeroAmount() throws {
        let missing = catalog().filter {
            $0.syncId != "collab-1" && $0.syncId != "collab-2"
        }
        let day = PurchaseWorkspaceReadPolicy.materialize(
            missing, date: "2026-11-20")
        XCTAssertNil(day.plannedAmount)
        XCTAssertNil(day.completedAmount)
        XCTAssertNotNil(day.completed[0].warning)
        XCTAssertEqual(day.pending.count, 1)
        XCTAssertEqual(day.completed.count, 1)
    }

    func testSameNumericParentIDFromDifferentPlansBlocked() throws {
        let alias = event("purchase_plan", "plan-2", [
            "id": .number(31),
            "plan_date": .string("2026-11-19")])
        let day = PurchaseWorkspaceReadPolicy.materialize(
            catalog() + [alias], date: "2026-11-20")
        XCTAssertTrue(day.hasPlan)
        XCTAssertTrue(day.pending.isEmpty)
        XCTAssertTrue(day.completed.isEmpty)
        XCTAssertFalse(day.warnings.isEmpty)
    }

    func testDeletedAndLegacyCancelledPlanItemsAreHidden() throws {
        var catalog = catalog()
        catalog.append(event("purchase_plan_item", "legacy", [
            "plan_id": .number(31), "fruit_name": .string("西瓜"),
            "status": .number(2)]))
        catalog.append(event("purchase_plan_item", "deleted", [
            "plan_id": .number(31), "fruit_name": .string("葡萄"),
            "status": .number(0), "deleted": .number(1)],
            operation: "DELETE"))
        let day = PurchaseWorkspaceReadPolicy.materialize(
            catalog, date: "2026-11-20")
        XCTAssertEqual(day.pending.count, 1)
        XCTAssertEqual(day.completed.count, 1)
    }

    private func draft(account: String, book: String,
                       id: String = "draft-1") -> PurchaseWorkspaceDraft {
        var line = PurchaseDraftLine.blank()
        line.fruitSyncID = "fruit-sync-id"
        line.fruitName = "苹果"
        line.quantity = "2"
        line.unitPrice = "15"
        line.unit = "件"
        line.unitWeightJin = "20"
        return PurchaseWorkspaceDraft(
            id: id, account: account, bookID: book,
            date: "2026-11-20", buyerSyncID: "buyer-1",
            buyerName: "采购人甲", purchaseType: "PLANNED",
            remark: "测试本机范围", lines: [line],
            createdAt: Date(timeIntervalSince1970: 10),
            updatedAt: Date(timeIntervalSince1970: 20))
    }

    func testRealAccountDraftsAreIsolatedByAccountAndBook() throws {
        let folder = FileManager.default.temporaryDirectory
            .appendingPathComponent("purchase-workspace-" + UUID().uuidString)
        try FileManager.default.createDirectory(
            at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let path = folder.appendingPathComponent("workspace.sqlite")
        let store = try PurchaseWorkspaceStore(url: path)
        let a = draft(account: "SellerA", book: "formal-1")
        let b = draft(account: "sellerB", book: "formal-2", id: "draft-2")
        try store.save(a, account: "SellerA", bookID: "formal-1")
        try store.save(b, account: "sellerB", bookID: "formal-2")
        XCTAssertEqual(try store.load(
            account: "sellera", bookID: "formal-1").count, 1)
        XCTAssertEqual(try store.load(
            account: "sellerB", bookID: "formal-1").count, 0)
        XCTAssertEqual(try store.load(
            account: "SellerA", bookID: "formal-2").count, 0)
        XCTAssertThrowsError(try store.delete(
            a.id, account: "sellerB", bookID: "formal-1"))
        XCTAssertThrowsError(try store.save(
            a, account: "sellerB", bookID: "formal-2"))
        XCTAssertEqual(try PurchaseWorkspaceStore(url: path).load(
            account: "sellera", bookID: "formal-1").first?.id, a.id)
        try store.delete(a.id, account: "sellera", bookID: "formal-1")
        XCTAssertTrue(try store.load(
            account: "SellerA", bookID: "formal-1").isEmpty)
    }

    func testPurchaseDraftMoneyAndBuyerMustStayCorrect() throws {
        let item = draft(account: "seller", book: "formal-1")
        let buyer = TestLookup(id: "buyer-1",
                               name: "采购人甲", localID: "9")
        XCTAssertNoThrow(try PurchaseWorkspaceDraftPolicy.validate(
            item, buyers: [buyer]))
        XCTAssertEqual(item.total, 30)
        XCTAssertThrowsError(try PurchaseWorkspaceDraftPolicy.validate(
            item, buyers: []))
        let viewer = CloudBook(id: "formal-1", name: "正式账本",
                               role: "VIEWER", deleted: false, purged: false)
        let owner = CloudBook(id: "formal-1", name: "正式账本",
                              role: "OWNER", deleted: false, purged: false)
        let account = Account(
            username: "seller", displayName: "采购人", systemRole: "USER")
        XCTAssertFalse(PurchaseWorkspaceDraftPolicy.authorized(
            book: viewer, account: account))
        XCTAssertTrue(PurchaseWorkspaceDraftPolicy.authorized(
            book: owner, account: account))
    }
}
