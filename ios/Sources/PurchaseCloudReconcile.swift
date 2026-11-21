import Foundation
import CryptoKit

/// A read-only, production-account purchase reconciliation. This does not
/// authorize cloud writes. The current server repository and transactional
/// multi-table purchase endpoint are not available to this iOS project.
enum PurchaseCloudReconcileStatus: Equatable {
    case matchingCloudOrder
    case potentialDuplicate
    case absentFromLatestCloud
    case unsafeToCompare
}

struct PurchaseCloudReconcileReport: Equatable {
    let status: PurchaseCloudReconcileStatus
    let fingerprint: String
    let matchingOrderID: String?
    let message: String
    let checkedAt: Date

    var isConfirmedCloudOrder: Bool { status == .matchingCloudOrder }
}

enum PurchaseCloudReconcilePolicy {
    private static func cents(_ amount: Double?) -> Int64? {
        guard let amount, amount.isFinite, amount >= 0,
              amount <= 1_000_000, abs(amount * 100 - (amount * 100).rounded()) < 0.0001
        else { return nil }
        return Int64((amount * 100).rounded())
    }

    private static func numericID(_ value: JSONValue?) -> Int64? {
        AndroidNumericIdentity.exact(value)
    }

    static func fingerprint(_ draft: PurchaseWorkspaceDraft) -> String {
        // Stable across repeated checks for identical draft content. The
        // server must later enforce idempotency atomically; this SHA alone
        // cannot guarantee exactly-once semantics on the cloud.
        let lines = draft.lines.map {
            [
                $0.fruitSyncID,
                $0.quantity.trimmingCharacters(in: .whitespacesAndNewlines),
                $0.unit.trimmingCharacters(in: .whitespacesAndNewlines),
                $0.unitPrice.trimmingCharacters(in: .whitespacesAndNewlines),
                $0.unitWeightJin.trimmingCharacters(in: .whitespacesAndNewlines),
                $0.sourcePlanItemSyncID ?? ""
            ].joined(separator: "\u{1f}")
        }.joined(separator: "\u{1e}")
        let parts = [
            draft.bookID, draft.account.lowercased(), draft.id,
            draft.date, draft.buyerSyncID, draft.purchaseType,
            draft.remark, lines
        ].joined(separator: "\u{0}")
        return SHA256.hash(data: Data(parts.utf8))
            .map { String(format: "%02x", $0) }.joined()
    }

    /// Compare only against one complete, freshly fetched account-scoped
    /// ledger. Caller is responsible for fresh /sync/pull confirmation.
    static func inspect(
        _ draft: PurchaseWorkspaceDraft,
        events: [SyncEvent],
        at date: Date = Date()
    ) -> PurchaseCloudReconcileReport {
        let key = fingerprint(draft)
        func report(_ status: PurchaseCloudReconcileStatus,
                    _ text: String, id: String? = nil) -> PurchaseCloudReconcileReport {
            PurchaseCloudReconcileReport(
                status: status, fingerprint: key,
                matchingOrderID: id, message: text, checkedAt: date)
        }

        guard let total = draft.total,
              let totalCents = cents(NSDecimalNumber(decimal: total).doubleValue),
              !draft.lines.isEmpty else {
            return report(.unsafeToCompare,
                "本机金额或商品不完整，无法进行云端核对")
        }

        let buyers = events.filter {
            $0.tableName == "partner" && !$0.isDeletion &&
            $0.syncId == draft.buyerSyncID &&
            $0.payload["name"]?.text == draft.buyerName
        }
        guard buyers.count == 1,
              let buyerID = numericID(buyers[0].payload["id"]) else {
            return report(.unsafeToCompare,
                "采购人云端身份不唯一或已失效，无法安全核对")
        }
        // Resolve every drafted fruit's exact active sync_id, not just its
        // display name: two same-named fruits must never be merged.
        var expected: [String] = []
        for line in draft.lines {
            let fruits = events.filter {
                $0.tableName == "fruit" && !$0.isDeletion &&
                $0.syncId == line.fruitSyncID &&
                $0.payload["name"]?.text == line.fruitName
            }
            guard fruits.count == 1,
                  let fruitID = numericID(fruits[0].payload["id"]),
                  let q = Double(line.quantity), q.isFinite, q > 0,
                  let price = Double(line.unitPrice),
                  let priceCents = cents(price),
                  let subtotal = try? PurchaseDraftPolicy.subtotal(line),
                  let sumCents = cents(
                    NSDecimalNumber(decimal: subtotal).doubleValue) else {
                return report(.unsafeToCompare,
                    "采购商品身份、数量、单价或金额无法核对，拒绝按名称猜测")
            }
            expected.append("\(fruitID)|\(line.fruitName)|\(line.unit)|" +
                            "\(q)|\(priceCents)|\(sumCents)")
        }
        expected.sort()

        let orders = events.filter {
            $0.tableName == "purchase_order" && !$0.isDeletion &&
            $0.payload["date"]?.text == draft.date &&
            (numericID($0.payload["buyer_id"]) == buyerID ||
             $0.payload["buyer_name"]?.text == draft.buyerName)
        }
        if orders.isEmpty {
            return report(.absentFromLatestCloud,
                "最新云端未发现该日该采购人的订单；这不代表已经上传，本机记录仍未提交")
        }
        var suspects: [String] = []

        for order in orders {
            guard let parentID = numericID(order.payload["id"]) else {
                suspects.append(order.syncId)
                continue
            }
            let sameNumericID = events.filter {
                $0.tableName == "purchase_order" && !$0.isDeletion &&
                numericID($0.payload["id"]) == parentID
            }
            guard sameNumericID.count == 1 else {
                suspects.append(order.syncId)
                continue
            }
            let children = events.filter {
                $0.tableName == "purchase_item" && !$0.isDeletion &&
                numericID($0.payload["order_id"]) == parentID
            }
            guard !children.isEmpty else {
                suspects.append(order.syncId)
                continue
            }
            var actual: [String] = []
            var valid = true
            for child in children {
                guard let fid = numericID(child.payload["fruit_id"]),
                      let quantity = child.payload["quantity"]?.decimal,
                      quantity.isFinite, quantity > 0,
                      let priceCents = cents(child.payload["unit_price"]?.decimal),
                      let amountCents = cents(child.payload["total_cost"]?.decimal) else {
                    valid = false
                    break
                }
                actual.append("\(fid)|\(child.payload["fruit_name"]?.text ?? "")|" +
                              "\(child.payload["unit"]?.text ?? "")|" +
                              "\(quantity)|\(priceCents)|\(amountCents)")
            }
            guard valid, actual.count == expected.count else {
                suspects.append(order.syncId)
                continue
            }
            actual.sort()
            if actual == expected &&
               cents(order.payload["total_cost"]?.decimal) == totalCents {
                return report(.matchingCloudOrder,
                    "云端已有逐商品金额完全一致的采购订单；为防重复记账，此草稿不会再次提交",
                    id: order.syncId)
            }
            suspects.append(order.syncId)
        }
        return report(.potentialDuplicate,
            "当天采购人已有 \(suspects.count) 笔订单，但商品或金额不完全一致；需要人工判断是否重复，禁止自动覆盖")
    }
}
