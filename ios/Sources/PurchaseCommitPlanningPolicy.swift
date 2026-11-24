import Foundation
import CryptoKit

/// Canonical *local* purchase commit proposal. No network code uses this
/// request until the production backend implements the matching atomic API.
struct PurchaseCommitItem: Codable, Equatable {
    let fruitSyncID: String
    let fruitName: String
    let unit: String
    let quantity: String
    let unitPrice: String
    let lineTotal: String
    let unitWeightJin: String
    let sourcePlanItemSyncID: String?

    enum CodingKeys: String, CodingKey {
        case fruitSyncID = "fruit_sync_id"
        case fruitName = "fruit_name"
        case unit
        case quantity
        case unitPrice = "unit_price"
        case lineTotal = "line_total"
        case unitWeightJin = "unit_weight_jin"
        case sourcePlanItemSyncID = "source_plan_item_sync_id"
    }
}

struct PurchaseExpectedVersion: Codable, Equatable {
    let tableName: String
    let syncID: String
    let rowVersion: Int64

    enum CodingKeys: String, CodingKey {
        case tableName = "table_name"
        case syncID = "sync_id"
        case rowVersion = "row_version"
    }
}

struct PurchaseCommitProposal: Codable, Equatable {
    let protocolVersion: Int
    let bookID: String
    let account: String
    let draftID: String
    let date: String
    let buyerSyncID: String
    let purchaseType: String
    let remark: String
    let operation: String
    let totalCost: String
    let lines: [PurchaseCommitItem]
    let expectedVersions: [PurchaseExpectedVersion]
    let payloadHash: String

    enum CodingKeys: String, CodingKey {
        case protocolVersion = "protocol_version"
        case bookID = "book_id"
        case account
        case draftID = "draft_id"
        case date
        case buyerSyncID = "buyer_sync_id"
        case purchaseType = "purchase_type"
        case remark
        case operation
        case totalCost = "total_cost"
        case lines
        case expectedVersions = "expected_versions"
        case payloadHash = "payload_hash"
    }
}

enum PurchaseCommitPlanningError: LocalizedError {
    case wrongAccount, staleBuyer, staleFruit, mixedPlans
    case stalePlan, missingCollaboration, duplicateSource

    var errorDescription: String? {
        switch self {
        case .wrongAccount: return "账号或账本已切换，无法生成正式采购提交内容"
        case .staleBuyer: return "采购人身份已变化，需重新选择"
        case .staleFruit: return "商品不存在或身份不唯一，需重新选择"
        case .mixedPlans: return "同一订单不能混合已有计划项目与非计划项目；请分单处理"
        case .stalePlan: return "采购计划已完成、删除或跨日期，必须重新核对"
        case .missingCollaboration: return "待采购计划的协作状态不完整，暂不能安全完成"
        case .duplicateSource: return "计划项目被重复引用，已停止生成订单"
        }
    }
}

enum PurchaseCommitPlanningPolicy {
    private static func visible(_ records: [SyncEvent],
                                table: String, id: String) -> [SyncEvent] {
        records.filter {
            !$0.isDeletion && $0.tableName == table && $0.syncId == id
        }
    }

    private static func cents(_ value: Decimal) -> String {
        PurchaseDraftPolicy.display(value)
    }

    static func prepare(_ draft: PurchaseWorkspaceDraft,
                        account: Account,
                        book: CloudBook,
                        records: [SyncEvent],
                        capabilities: PurchaseCommitCapabilities? = nil) throws -> PurchaseCommitProposal {
        // Formal ledgers cannot be prepared with a legacy test-only capability.
        // This guard is *additional* to the live server recheck before POST.
        let testBook = book.name.hasPrefix("iOS_TEST_")
        guard account.username.lowercased() == draft.account.lowercased(),
              book.id == draft.bookID,
              (testBook || capabilities?.eligible(book: book) == true),
              PurchaseWorkspaceDraftPolicy.authorized(book: book, account: account)
        else { throw PurchaseCommitPlanningError.wrongAccount }

        let buyer = visible(records, table: "partner", id: draft.buyerSyncID)
        guard buyer.count == 1,
              buyer[0].payload["name"]?.text == draft.buyerName,
              let buyerNumericID = AndroidNumericIdentity.exact(
                  buyer[0].payload["id"]),
              AndroidNumericIdentity.unique(buyer[0], in: records) else {
            throw PurchaseCommitPlanningError.staleBuyer
        }
        try PurchaseWorkspaceDraftPolicy.validate(draft, buyers: [
            TestLookup(id: draft.buyerSyncID, name: draft.buyerName,
                       localID: buyer[0].payload["id"]?.text ?? "")
        ])

        let linked = draft.lines.compactMap(\.sourcePlanItemSyncID)
        guard linked.isEmpty || linked.count == draft.lines.count else {
            throw PurchaseCommitPlanningError.mixedPlans
        }
        guard Set(linked).count == linked.count else {
            throw PurchaseCommitPlanningError.duplicateSource
        }

        var lines: [PurchaseCommitItem] = []
        var versions: [PurchaseExpectedVersion] = []
        for line in draft.lines {
            let fruit = visible(records, table: "fruit", id: line.fruitSyncID)
            guard fruit.count == 1,
                  fruit[0].payload["name"]?.text == line.fruitName,
                  let fruitNumericID = AndroidNumericIdentity.exact(
                      fruit[0].payload["id"]),
                  AndroidNumericIdentity.unique(fruit[0], in: records) else {
                throw PurchaseCommitPlanningError.staleFruit
            }
            let quantity = try PurchaseDraftPolicy.quantity(line.quantity)
            let price = try PurchaseDraftPolicy.unitPrice(line.unitPrice)
            let weight = try PurchaseDraftPolicy.unitWeight(line.unitWeightJin)
            let subtotal = try PurchaseDraftPolicy.subtotal(line)

            if let id = line.sourcePlanItemSyncID {
                let items = visible(records, table: "purchase_plan_item", id: id)
                guard items.count == 1,
                      items[0].payload["status"]?.decimal == 0,
                      items[0].payload["fruit_name"]?.text == line.fruitName,
                      AndroidNumericIdentity.exact(
                      items[0].payload["fruit_id"]) == fruitNumericID,
                      items[0].payload["unit"]?.text == line.unit,
                      items[0].rowVersion > 0 else {
                    throw PurchaseCommitPlanningError.stalePlan
                }
                let plans = records.filter {
                    !$0.isDeletion && $0.tableName == "purchase_plan" &&
                    $0.payload["plan_date"]?.text == draft.date &&
                    AndroidNumericIdentity.same(
                        $0.payload["id"], items[0].payload["plan_id"])
                }
                guard plans.count == 1, plans[0].rowVersion > 0,
                      AndroidNumericIdentity.unique(plans[0], in: records) else {
                    throw PurchaseCommitPlanningError.stalePlan
                }
                versions.append(PurchaseExpectedVersion(
                    tableName: "purchase_plan",
                    syncID: plans[0].syncId, rowVersion: plans[0].rowVersion))
                versions.append(PurchaseExpectedVersion(
                    tableName: "purchase_plan_item",
                    syncID: id, rowVersion: items[0].rowVersion))
                let collabs = records.filter {
                    !$0.isDeletion &&
                    $0.tableName == "purchase_collaboration" &&
                    $0.payload["plan_item_sync_id"]?.text == id
                }
                guard collabs.count == 1,
                      collabs[0].rowVersion > 0,
                      ((collabs[0].payload["buyer_id"]?.decimal ?? 0) == 0 ||
                       AndroidNumericIdentity.exact(
                           collabs[0].payload["buyer_id"]) == buyerNumericID) else {
                    throw PurchaseCommitPlanningError.missingCollaboration
                }
                versions.append(PurchaseExpectedVersion(
                    tableName: "purchase_collaboration",
                    syncID: collabs[0].syncId,
                    rowVersion: collabs[0].rowVersion))
            }

            lines.append(PurchaseCommitItem(
                fruitSyncID: line.fruitSyncID,
                fruitName: line.fruitName,
                unit: line.unit,
                quantity: NSDecimalNumber(decimal: quantity).stringValue,
                unitPrice: cents(price),
                lineTotal: cents(subtotal),
                unitWeightJin: NSDecimalNumber(decimal: weight).stringValue,
                sourcePlanItemSyncID: line.sourcePlanItemSyncID))
        }

        let total = try PurchaseDraftPolicy.total(draft.lines)
        let expected = Array(Set(versions.map {
            $0.tableName + "|" + $0.syncID + "|" + String($0.rowVersion)
        })).sorted().compactMap { identity -> PurchaseExpectedVersion? in
            versions.first {
                $0.tableName + "|" + $0.syncID + "|" +
                    String($0.rowVersion) == identity
            }
        }
        let operation = linked.isEmpty ? "create_purchase" : "complete_plan"
        struct Canonical: Encodable {
            let protocolVersion: Int
            let bookID: String
            let account: String
            let draftID: String
            let date: String
            let buyerSyncID: String
            let purchaseType: String
            let remark: String
            let operation: String
            let totalCost: String
            let lines: [PurchaseCommitItem]
            let expectedVersions: [PurchaseExpectedVersion]
        }
        let normalized = Canonical(
            protocolVersion: 1, bookID: book.id,
            account: account.username.lowercased(),
            draftID: draft.id, date: draft.date,
            buyerSyncID: draft.buyerSyncID,
            purchaseType: draft.purchaseType,
            remark: draft.remark, operation: operation,
            totalCost: cents(total), lines: lines,
            expectedVersions: expected)
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        let bytes = try encoder.encode(normalized)
        let digest = SHA256.hash(data: bytes).map {
            String(format: "%02x", $0)
        }.joined()
        return PurchaseCommitProposal(
            protocolVersion: normalized.protocolVersion,
            bookID: normalized.bookID,
            account: normalized.account,
            draftID: normalized.draftID,
            date: normalized.date,
            buyerSyncID: normalized.buyerSyncID,
            purchaseType: normalized.purchaseType,
            remark: normalized.remark,
            operation: normalized.operation,
            totalCost: normalized.totalCost,
            lines: normalized.lines,
            expectedVersions: normalized.expectedVersions,
            payloadHash: digest)
    }
}
