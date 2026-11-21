import Foundation

/// Only supported by TianXian-Server V1.0.25+ when the administrator has
/// explicitly enabled the atomic purchase endpoint. The server owns final
/// permission decisions and is the only authority for a committed purchase.
struct PurchaseCommitCapabilities: Decodable {
    let version: Int
    let enabled: Bool
    let supportsCompletePlan: Bool
    let atomic: Bool
    let idempotency: Bool
    let feature: String

    enum CodingKeys: String, CodingKey {
        case version, enabled, atomic, idempotency, feature
        case supportsCompletePlan = "supports_complete_plan"
    }

    func allows(_ proposal: PurchaseCommitProposal) -> Bool {
        version == 1 && enabled && atomic && idempotency &&
        feature == "purchase_atomic_commit_v1" &&
        (proposal.operation != "complete_plan" || supportsCompletePlan)
    }
}

struct PurchaseCommitReceipt: Codable, Equatable {
    let status: String
    let operationID: String
    let bookID: String
    let orderSyncID: String
    let itemSyncIDs: [String]
    let syncCursor: Int64

    enum CodingKeys: String, CodingKey {
        case status
        case operationID = "operation_id"
        case bookID = "book_id"
        case orderSyncID = "order_sync_id"
        case itemSyncIDs = "item_sync_ids"
        case syncCursor = "sync_cursor"
    }

    func isFor(_ proposal: PurchaseCommitProposal) -> Bool {
        (status == "committed" || status == "already_committed") &&
        operationID.lowercased() == proposal.draftID.lowercased() &&
        bookID.lowercased() == proposal.bookID.lowercased() &&
        !orderSyncID.isEmpty &&
        !itemSyncIDs.isEmpty &&
        itemSyncIDs.count == proposal.lines.count &&
        syncCursor > 0
    }
}

/// The attempt remains durable before any POST starts. A timeout or lost
/// response never triggers a new operation UUID, and no automatic retry is
/// allowed. First query the operation by the same immutable ID.
struct PurchaseCommitAttempt: Codable, Equatable, Identifiable {
    enum Phase: String, Codable {
        case prepared
        case unknown
        case accepted
        case confirmed
    }
    var id: String { proposal.draftID }
    let account: String
    let bookID: String
    let proposal: PurchaseCommitProposal
    let createdAt: Date
    var updatedAt: Date
    var phase: Phase
    var receipt: PurchaseCommitReceipt?

    var isLocked: Bool { true }
    var label: String {
        switch phase {
        case .prepared: return "已准备提交，请先查询服务器结果"
        case .unknown: return "提交状态待查询，请勿重复记账"
        case .accepted: return "服务器已提交，正在核对完整同步记录"
        case .confirmed: return "已完成云端提交与订单核对"
        }
    }
}

enum PurchaseAtomicError: LocalizedError {
    case unsupported, scopeChanged, blockedByAttempt, invalidReceipt
    case emptyCloudReadback, staleDraft, persistentStore

    var errorDescription: String? {
        switch self {
        case .unsupported: return "服务器尚未启用采购原子事务接口，保留本机草稿"
        case .scopeChanged: return "账号或账本已切换，禁止提交其他账本采购"
        case .blockedByAttempt: return "此采购已有提交记录，必须先查询原操作结果，不能创建第二笔"
        case .invalidReceipt: return "服务器提交回执不完整或账本不一致，保留待核对状态"
        case .emptyCloudReadback: return "服务器已确认提交，但尚未核对到订单及所有商品明细"
        case .staleDraft: return "本机草稿已修改，先核对或处理已有提交记录"
        case .persistentStore: return "采购提交保护记录无法保存，已禁止网络写入"
        }
    }
}

enum PurchaseAtomicReadbackPolicy {
    static func confirmed(_ proposal: PurchaseCommitProposal,
                          receipt: PurchaseCommitReceipt,
                          records: [SyncEvent]) -> Bool {
        guard receipt.isFor(proposal) else { return false }
        let orders = records.filter {
            $0.tableName == "purchase_order" && !$0.isDeletion &&
            $0.syncId == receipt.orderSyncID &&
            $0.payload["date"]?.text == proposal.date &&
            $0.payload["total_cost"]?.decimal.isFinite == true
        }
        guard orders.count == 1 else { return false }
        guard let orderID = AndroidNumericIdentity.exact(
            orders[0].payload["id"]),
              AndroidNumericIdentity.unique(orders[0], in: records)
        else { return false }
        let items = records.filter {
            $0.tableName == "purchase_item" && !$0.isDeletion &&
            receipt.itemSyncIDs.contains($0.syncId) &&
            AndroidNumericIdentity.exact($0.payload["order_id"]) == orderID
        }
        guard items.count == proposal.lines.count,
              Set(items.map(\.syncId)).count == items.count else {
            return false
        }
        for (index, itemSync) in receipt.itemSyncIDs.enumerated() {
            let matches = items.filter { $0.syncId == itemSync }
            guard matches.count == 1 else { return false }
            let line = proposal.lines[index], row = matches[0].payload
            guard row["fruit_name"]?.text == line.fruitName,
                  row["unit"]?.text == line.unit,
                  Self.matches(row["quantity"]?.decimal, line.quantity, digits: 3),
                  Self.matches(row["unit_price"]?.decimal, line.unitPrice, digits: 2),
                  Self.matches(row["total_cost"]?.decimal, line.lineTotal, digits: 2)
            else { return false }
        }
        guard Self.matches(orders[0].payload["total_cost"]?.decimal,
                           proposal.totalCost, digits: 2) else { return false }
        if proposal.operation == "complete_plan" {
            // Completing a plan is not confirmed by an order alone: Android
            // must receive the plan-item and collaboration transitions too.
            let buyers = records.filter {
                $0.tableName == "partner" && !$0.isDeletion &&
                $0.syncId == proposal.buyerSyncID
            }
            guard buyers.count == 1,
                  let buyerID = AndroidNumericIdentity.exact(
                      buyers[0].payload["id"]),
                  AndroidNumericIdentity.unique(buyers[0], in: records)
            else { return false }
            let sourceIDs = proposal.lines.compactMap(\.sourcePlanItemSyncID)
            guard sourceIDs.count == proposal.lines.count,
                  Set(sourceIDs).count == sourceIDs.count
            else { return false }
            for (index, sid) in sourceIDs.enumerated() {
                let planItems = records.filter {
                    $0.tableName == "purchase_plan_item" && !$0.isDeletion &&
                    $0.syncId == sid && $0.payload["status"]?.decimal == 1
                }
                let collabs = records.filter {
                    $0.tableName == "purchase_collaboration" && !$0.isDeletion &&
                    $0.payload["plan_item_sync_id"]?.text == sid
                }
                guard planItems.count == 1, collabs.count == 1 else { return false }
                let line = proposal.lines[index]
                let plan = planItems[0].payload
                let collab = collabs[0].payload
                guard plan["fruit_name"]?.text == line.fruitName,
                      plan["unit"]?.text == line.unit,
                      collab["purchase_order_sync_id"]?.text == receipt.orderSyncID,
                      AndroidNumericIdentity.exact(collab["buyer_id"]) == buyerID,
                      Self.matches(collab["actual_quantity"]?.decimal,
                                   line.quantity, digits: 3),
                      Self.matches(collab["actual_amount"]?.decimal,
                                   line.lineTotal, digits: 2)
                else { return false }
            }
        }
        return true
    }

    private static func matches(_ source: Double?, _ value: String,
                                digits: Int) -> Bool {
        guard let source, source.isFinite,
              let expected = Decimal(string: value),
              expected >= 0 else { return false }
        let actual = NSDecimalNumber(value: source).decimalValue
        var a = actual, b = expected
        var roundedA = Decimal(), roundedB = Decimal()
        NSDecimalRound(&roundedA, &a, digits, .plain)
        NSDecimalRound(&roundedB, &b, digits, .plain)
        return roundedA == roundedB
    }
}
