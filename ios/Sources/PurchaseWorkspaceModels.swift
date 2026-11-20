import Foundation

/// A faithful cloud read model of Android's collaborative purchase page:
/// purchase_plan -> purchase_plan_item -> purchase_collaboration.
/// Never infer a cross-device parent relation from coincidental numeric IDs
/// without proving that the numeric ID belongs to exactly one plan.
struct PurchasePlanningRow: Identifiable, Equatable {
    let id: String
    let name: String
    let quantity: Double
    let unit: String
    let unitWeightJin: Double
    let remark: String
    let buyerName: String
    let estimatedAmount: Double?
    let actualQuantity: Double?
    let actualAmount: Double?
    let completedAt: Double?
    let linkedOrderID: String
    let hasVerifiedOrder: Bool
    let isCompleted: Bool
    let warning: String?

    var displayQuantity: Double { isCompleted ? (actualQuantity ?? quantity) : quantity }
    var displayAmount: Double? { isCompleted ? actualAmount : estimatedAmount }
}

struct PurchasePlanningDay {
    let date: String
    let pending: [PurchasePlanningRow]
    let completed: [PurchasePlanningRow]
    let warnings: [String]
    let hasPlan: Bool

    var plannedAmount: Double? {
        let all = pending + completed
        guard all.allSatisfy({ $0.estimatedAmount != nil }) else { return nil }
        return all.reduce(0) { $0 + ($1.estimatedAmount ?? 0) }
    }

    var completedAmount: Double? {
        guard completed.allSatisfy({ $0.actualAmount != nil }) else { return nil }
        return completed.reduce(0) { $0 + ($1.actualAmount ?? 0) }
    }

    var completedFruits: Int { Set(completed.map(\.name)).count }
    var pendingFruits: Int { Set(pending.map(\.name)).count }
}

enum PurchaseWorkspaceReadPolicy {
    private static func numericID(_ value: JSONValue?) -> Int64? {
        guard let value else { return nil }
        let number: Double
        switch value {
        case .number(let amount): number = amount
        case .string(let raw): number = Double(raw) ?? 0
        default: return nil
        }
        guard number.isFinite, number > 0,
              number < 9_007_199_254_740_992,
              number.rounded() == number else { return nil }
        return Int64(number)
    }

    private static func money(_ value: JSONValue?) -> Double? {
        guard let value else { return nil }
        let amount: Double
        switch value {
        case .number(let n): amount = n
        case .string(let s): guard let n = Double(s) else { return nil }; amount = n
        default: return nil
        }
        guard amount.isFinite, amount >= 0, amount <= 1_000_000_000 else { return nil }
        return amount
    }

    static func materialize(_ records: [SyncEvent], date: String) -> PurchasePlanningDay {
        let plans = records.filter {
            $0.tableName == "purchase_plan" && !$0.isDeletion &&
            $0.payload["plan_date"]?.text == date
        }
        guard plans.count == 1, let plan = plans.first,
              let planID = numericID(plan.payload["id"]) else {
            return PurchasePlanningDay(date: date, pending: [], completed: [],
                warnings: plans.count > 1 ? ["当天存在重复计划，已隐藏无法确认归属的明细"] :
                    plans.count == 1 ? ["采购计划缺少唯一有效的计划 ID"] : [],
                hasPlan: !plans.isEmpty)
        }

        let allPlanIDs = records.filter {
            $0.tableName == "purchase_plan" && !$0.isDeletion &&
            numericID($0.payload["id"]) == planID
        }
        guard allPlanIDs.count == 1 else {
            return PurchasePlanningDay(date: date, pending: [], completed: [],
                warnings: ["不同计划在云端具有同一个本地数字 ID，已停止关联采购商品"],
                hasPlan: true)
        }
        let items = records.filter {
            $0.tableName == "purchase_plan_item" && !$0.isDeletion &&
            numericID($0.payload["plan_id"]) == planID &&
            ($0.payload["status"]?.decimal ?? 0) != 2
        }.sorted { $0.syncId < $1.syncId }

        var pending: [PurchasePlanningRow] = []
        var completed: [PurchasePlanningRow] = []
        var warnings: [String] = []

        for event in items {
            let matching = records.filter {
                $0.tableName == "purchase_collaboration" && !$0.isDeletion &&
                $0.payload["plan_item_sync_id"]?.text == event.syncId
            }
            let linked = matching.count == 1 ? matching.first : nil
            if matching.count > 1 {
                warnings.append("商品 " + (event.payload["fruit_name"]?.text ?? "") +
                                " 的采购归属记录重复，暂不计算其金额")
            }
            let finished = event.payload["status"]?.decimal == 1
            let orderID = linked?.payload["purchase_order_sync_id"]?.text ?? ""
            let verifiedOrder = !orderID.isEmpty && records.contains {
                $0.tableName == "purchase_order" && !$0.isDeletion &&
                $0.syncId == orderID
            }
            let amount = matching.count == 1
                ? money(linked?.payload["actual_amount"]) : nil
            let estimate = matching.count == 1
                ? money(linked?.payload["estimated_amount"]) : nil
            var note: String?
            if finished && (linked == nil || !verifiedOrder) {
                note = "已标记完成，但正式采购订单尚未核对到云端"
            }
            if matching.count > 1 { note = "采购协作记录重复，归属未核实" }
            let row = PurchasePlanningRow(
                id: event.syncId,
                name: event.payload["fruit_name"]?.text ?? "未命名商品",
                quantity: event.payload["quantity"]?.decimal ?? 0,
                unit: event.payload["unit"]?.text ?? "件",
                unitWeightJin: event.payload["unit_weight_jin"]?.decimal ?? 0,
                remark: event.payload["remark"]?.text ?? "",
                buyerName: linked?.payload["buyer_name"]?.text ?? "",
                estimatedAmount: estimate,
                actualQuantity: matching.count == 1
                    ? linked?.payload["actual_quantity"]?.decimal : nil,
                actualAmount: amount,
                completedAt: matching.count == 1
                    ? linked?.payload["completed_at"]?.decimal : nil,
                linkedOrderID: orderID,
                hasVerifiedOrder: verifiedOrder,
                isCompleted: finished,
                warning: note)
            if finished { completed.append(row) } else { pending.append(row) }
        }

        return PurchasePlanningDay(date: date, pending: pending,
            completed: completed, warnings: warnings, hasPlan: true)
    }
}

/// Native iOS purchase drafts belong to the real signed-in account and ledger,
/// not the old test lab. They are LOCAL until a transaction-safe production
/// purchase write endpoint is separately implemented.
struct PurchaseWorkspaceDraft: Codable, Equatable, Identifiable {
    let id: String
    let account: String
    let bookID: String
    var date: String
    var buyerSyncID: String
    var buyerName: String
    var purchaseType: String
    var remark: String
    var lines: [PurchaseDraftLine]
    let createdAt: Date
    var updatedAt: Date

    var total: Decimal? { try? PurchaseDraftPolicy.total(lines) }
}

enum PurchaseWorkspaceDraftPolicy {
    static func authorized(book: CloudBook, account: Account?) -> Bool {
        book.isAvailable && account != nil &&
        ["OWNER", "EDITOR", "SUPERADMIN"].contains(book.role)
    }

    static func validate(_ draft: PurchaseWorkspaceDraft,
                         buyers: [TestLookup]) throws {
        guard !draft.id.isEmpty, !draft.account.isEmpty, !draft.bookID.isEmpty,
              BusinessDates.parse(draft.date) != nil else {
            throw TestWriteError.missingData("采购日期与账本")
        }
        guard buyers.contains(where: {
            $0.id == draft.buyerSyncID && $0.name == draft.buyerName
        }) else {
            throw TestWriteError.missingData("有效采购人，请先同步合伙人")
        }
        guard ["PLANNED", "TEMPORARY"].contains(draft.purchaseType) else {
            throw TestWriteError.missingData("采购类型")
        }
        _ = try PurchaseDraftPolicy.total(draft.lines)
    }
}
