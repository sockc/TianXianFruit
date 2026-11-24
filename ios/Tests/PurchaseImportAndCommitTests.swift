import XCTest
@testable import TianXianFruit

final class PurchaseImportAndCommitTests: XCTestCase {
    private func event(_ table: String, _ id: String,
                       _ fields: [String: JSONValue],
                       version: Int64 = 2,
                       operation: String = "UPSERT") -> SyncEvent {
        SyncEvent(tableName: table, syncId: id, rowVersion: version,
                  operation: operation, payload: fields)
    }

    private func records() -> [SyncEvent] {
        [
            event("partner", "buyer-1", [
                "id": .number(11), "name": .string("甲")]),
            event("partner", "buyer-2", [
                "id": .number(12), "name": .string("乙")]),
            event("fruit", "fruit-1", [
                "id": .number(100), "name": .string("苹果")]),
            event("fruit", "fruit-2", [
                "id": .number(101), "name": .string("香蕉")]),
            event("inventory_snapshot", "last-stock", [
                "date": .string("2026-11-19"),
                "fruit_id": .number(100),
                "fruit_name": .string("苹果"),
                "unit": .string("件"),
                "remaining_quantity": .number(8)]),
            event("inventory_snapshot", "future-stock", [
                "date": .string("2026-11-21"),
                "fruit_id": .number(101),
                "fruit_name": .string("香蕉"),
                "unit": .string("箱"),
                "remaining_quantity": .number(3)]),
            event("purchase_plan", "plan-1", [
                "id": .number(31), "plan_date": .string("2026-11-20")]),
            event("purchase_plan_item", "plan-item-1", [
                "id": .number(78), "plan_id": .number(31),
                "fruit_id": .number(100), "fruit_name": .string("苹果"),
                "unit": .string("件"), "quantity": .number(4),
                "unit_weight_jin": .number(17),
                "status": .number(0)]),
            event("purchase_plan_item", "plan-item-2", [
                "id": .number(79), "plan_id": .number(31),
                "fruit_id": .number(101), "fruit_name": .string("香蕉"),
                "unit": .string("箱"), "quantity": .number(2),
                "status": .number(0)]),
            event("purchase_collaboration", "collab-1", [
                "plan_item_sync_id": .string("plan-item-1"),
                "buyer_id": .number(11), "buyer_name": .string("甲"),
                "estimated_amount": .number(200)]),
            event("purchase_collaboration", "collab-2", [
                "plan_item_sync_id": .string("plan-item-2"),
                "buyer_id": .number(12), "buyer_name": .string("乙"),
                "estimated_amount": .number(150)])
        ]
    }

    private func makeDraft(_ lines: [PurchaseDraftLine]) -> PurchaseWorkspaceDraft {
        PurchaseWorkspaceDraft(id: "draft-1", account: "seller",
            bookID: "book-1", date: "2026-11-20",
            buyerSyncID: "buyer-1", buyerName: "甲",
            purchaseType: "PLANNED", remark: "真实价另填",
            lines: lines, createdAt: Date(timeIntervalSince1970: 1),
            updatedAt: Date(timeIntervalSince1970: 2))
    }

    private let account = Account(username: "seller",
        displayName: "店主", systemRole: "USER")
    private let book = CloudBook(id: "book-1", name: "iOS_TEST_采购",
        role: "OWNER", deleted: false, purged: false)

    func testImportLastInventoryNeverTreatsRemainingAsPurchasedQuantity() {
        let offer = PurchaseImportPolicy.inventory(
            records(), on: "2026-11-20")
        XCTAssertEqual(offer.count, 1)
        XCTAssertEqual(offer[0].fruitName, "苹果")
        XCTAssertEqual(offer[0].referenceQuantity, "8")
        let line = offer[0].asDraftLine()
        XCTAssertEqual(line.quantity, "1")
        XCTAssertEqual(line.unitPrice, "0")
        XCTAssertNil(line.sourcePlanItemSyncID)
    }

    func testPlanImportRespectsBuyerAndDoesNotInferPrice() {
        let candidates = PurchaseImportPolicy.pendingPlan(
            records(), on: "2026-11-20", buyerSyncID: "buyer-1")
        XCTAssertEqual(candidates.count, 1)
        XCTAssertEqual(candidates[0].fruitName, "苹果")
        XCTAssertEqual(candidates[0].referenceQuantity, "4")
        let line = candidates[0].asDraftLine()
        XCTAssertEqual(line.unitPrice, "0")
        XCTAssertEqual(line.unitWeightJin, "17")
        XCTAssertEqual(line.sourcePlanItemSyncID, "plan-item-1")
        let other = PurchaseImportPolicy.pendingPlan(
            records(), on: "2026-11-20", buyerSyncID: "buyer-2")
        XCTAssertEqual(other.map(\.fruitName), ["香蕉"])
    }

    func testNoDuplicateFruitAndUnitDuringImport() {
        let offer = PurchaseImportPolicy.inventory(
            records(), on: "2026-11-20")
        let first = PurchaseImportPolicy.append(offer, to: [])
        let second = PurchaseImportPolicy.append(offer, to: first.lines)
        XCTAssertEqual(first.added, 1)
        XCTAssertEqual(second.added, 0)
        XCTAssertEqual(second.skipped, 1)
        XCTAssertEqual(second.lines.count, 1)
    }

    func testPlanSourceIsPreservedAcrossCodableRoundtrip() throws {
        let line = try XCTUnwrap(PurchaseImportPolicy.pendingPlan(
            records(), on: "2026-11-20",
            buyerSyncID: "buyer-1").first?.asDraftLine())
        let decoded = try JSONDecoder().decode(
            PurchaseDraftLine.self, from: JSONEncoder().encode(line))
        XCTAssertEqual(decoded.sourcePlanItemSyncID, "plan-item-1")
        // Older V0.4.0 drafts contain no sourcePlanItemSyncID.
        var object = try XCTUnwrap(
            JSONSerialization.jsonObject(
                with: JSONEncoder().encode(line)) as? [String: Any])
        object.removeValue(forKey: "sourcePlanItemSyncID")
        let bytes = try JSONSerialization.data(withJSONObject: object)
        let legacy = try JSONDecoder().decode(PurchaseDraftLine.self, from: bytes)
        XCTAssertNil(legacy.sourcePlanItemSyncID)
    }

    func testNoCrossDeviceCollisionFruitImport() {
        let alias = event("fruit", "other-fruit-same-numeric-id", [
            "id": .number(100), "name": .string("其他苹果")])
        XCTAssertTrue(PurchaseImportPolicy.inventory(
            records() + [alias], on: "2026-11-20").isEmpty)
    }

    func testCanonicalCreateProposalIsStableAndOnlyLocal() throws {
        var line = PurchaseDraftLine.blank()
        line.fruitSyncID = "fruit-1"
        line.fruitName = "苹果"
        line.quantity = "2"
        line.unit = "件"
        line.unitPrice = "12.50"
        let d = makeDraft([line])
        let prepared = try PurchaseCommitPlanningPolicy.prepare(
            d, account: account, book: book, records: records())
        XCTAssertEqual(prepared.operation, "create_purchase")
        XCTAssertEqual(prepared.totalCost, "25.00")
        XCTAssertEqual(prepared.lines[0].lineTotal, "25.00")
        XCTAssertTrue(prepared.expectedVersions.isEmpty)
        XCTAssertEqual(prepared.payloadHash.count, 64)
        let repeated = try PurchaseCommitPlanningPolicy.prepare(
            d, account: account, book: book, records: records())
        XCTAssertEqual(prepared, repeated)
    }

    func testCompletedPlanRequestContainsCASForPlanAndCollaboration() throws {
        var imported = try XCTUnwrap(PurchaseImportPolicy.pendingPlan(
            records(), on: "2026-11-20",
            buyerSyncID: "buyer-1").first?.asDraftLine())
        imported.unitPrice = "12.00"
        let d = makeDraft([imported])
        let prepared = try PurchaseCommitPlanningPolicy.prepare(
            d, account: account, book: book, records: records())
        XCTAssertEqual(prepared.operation, "complete_plan")
        XCTAssertEqual(prepared.totalCost, "48.00")
        XCTAssertEqual(Set(prepared.expectedVersions.map(\.tableName)),
                       ["purchase_plan", "purchase_plan_item",
                        "purchase_collaboration"])
        XCTAssertTrue(prepared.expectedVersions.allSatisfy {
            $0.rowVersion == 2
        })
        XCTAssertEqual(prepared.lines[0].sourcePlanItemSyncID, "plan-item-1")
    }

    func testPlanAlreadyCompletedAndBuyerMismatchRejectsProposal() throws {
        var imported = try XCTUnwrap(PurchaseImportPolicy.pendingPlan(
            records(), on: "2026-11-20",
            buyerSyncID: "buyer-1").first?.asDraftLine())
        imported.unitPrice = "12"
        let draft = makeDraft([imported])
        let completed = records().map { entry -> SyncEvent in
            guard entry.syncId == "plan-item-1" else { return entry }
            var modified = entry.payload
            modified["status"] = .number(1)
            return event(entry.tableName, entry.syncId, modified)
        }
        XCTAssertThrowsError(try PurchaseCommitPlanningPolicy.prepare(
            draft, account: account, book: book, records: completed))
        let wrong = records().map { entry -> SyncEvent in
            guard entry.syncId == "collab-1" else { return entry }
            var modified = entry.payload
            modified["buyer_id"] = .number(12)
            modified["buyer_name"] = .string("乙")
            return event(entry.tableName, entry.syncId, modified)
        }
        XCTAssertThrowsError(try PurchaseCommitPlanningPolicy.prepare(
            draft, account: account, book: book, records: wrong))
    }

    func testTwoPlanItemsAndUnlinkedItemCannotMixedCommit() throws {
        let imported = try XCTUnwrap(PurchaseImportPolicy.pendingPlan(
            records(), on: "2026-11-20",
            buyerSyncID: "buyer-1").first?.asDraftLine())
        var extra = PurchaseDraftLine.blank()
        extra.fruitSyncID = "fruit-2"
        extra.fruitName = "香蕉"
        extra.unitPrice = "6"
        XCTAssertThrowsError(try PurchaseCommitPlanningPolicy.prepare(
            makeDraft([imported, extra]),
            account: account, book: book, records: records()))
    }

    func testAccountSwitchDisablesProposal() throws {
        var line = PurchaseDraftLine.blank()
        line.fruitSyncID = "fruit-1"
        line.fruitName = "苹果"
        XCTAssertThrowsError(try PurchaseCommitPlanningPolicy.prepare(
            makeDraft([line]), account: Account(username: "other",
                displayName: "外部账号", systemRole: "USER"),
            book: book, records: records()))
    }
    func testFormalBookCannotBuildPurchaseWriteProposal() {
        var item = PurchaseDraftLine.blank()
        item.fruitSyncID = "fruit-1"
        item.fruitName = "苹果"
        item.quantity = "2"
        item.unit = "件"
        item.unitPrice = "15"
        let prod = CloudBook(id: "book-1", name: "正式账本",
                             role: "OWNER", deleted: false, purged: false)
        XCTAssertThrowsError(try PurchaseCommitPlanningPolicy.prepare(
            makeDraft([item]), account: account, book: prod, records: records()))
    }

    func testSeventeenDigitBuyerAndFruitAreLinkedWithoutDoubleRounding() throws {
        let bigBuyer: Int64 = 79152283000000002
        let bigFruit: Int64 = 79152283000000001
        let modified = records().map { row -> SyncEvent in
            guard row.syncId == "buyer-1" || row.syncId == "fruit-1" else {
                return row
            }
            var payload = row.payload
            payload["id"] = .exactInteger(row.syncId == "buyer-1" ? bigBuyer : bigFruit)
            return event(row.tableName, row.syncId, payload, version: row.rowVersion)
        }
        var item = PurchaseDraftLine.blank()
        item.fruitSyncID = "fruit-1"
        item.fruitName = "苹果"
        item.quantity = "2"
        item.unit = "件"
        item.unitPrice = "15"
        let proposal = try PurchaseCommitPlanningPolicy.prepare(
            makeDraft([item]), account: account, book: book, records: modified)
        XCTAssertEqual(proposal.totalCost, "30.00")
        XCTAssertEqual(proposal.buyerSyncID, "buyer-1")
        XCTAssertEqual(proposal.lines.first?.fruitSyncID, "fruit-1")
        XCTAssertEqual(AndroidNumericIdentity.exact(.exactInteger(bigBuyer)), bigBuyer)
        XCTAssertEqual(AndroidNumericIdentity.exact(.exactInteger(bigFruit)), bigFruit)
        XCTAssertNil(AndroidNumericIdentity.exact(.number(Double(bigBuyer))))
    }

}
