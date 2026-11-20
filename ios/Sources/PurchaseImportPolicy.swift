import Foundation

/// Import offers source references, NOT money, cloud writes or finished orders.
struct PurchaseImportCandidate: Identifiable, Equatable {
    let id: String
    let source: String
    let fruitSyncID: String
    let fruitName: String
    let unit: String
    let suggestedQuantity: String
    let referenceQuantity: String
    let unitWeightJin: String
    let sourcePlanItemSyncID: String?

    var label: String {
        fruitName + " · " + referenceQuantity + unit
    }

    func asDraftLine() -> PurchaseDraftLine {
        PurchaseDraftLine(id: UUID().uuidString,
                          fruitSyncID: fruitSyncID,
                          fruitName: fruitName,
                          quantity: suggestedQuantity,
                          unit: unit,
                          unitPrice: "0",
                          unitWeightJin: unitWeightJin,
                          sourcePlanItemSyncID: sourcePlanItemSyncID)
    }
}

struct PurchaseImportResult {
    let lines: [PurchaseDraftLine]
    let added: Int
    let skipped: Int
}

enum PurchaseImportPolicy {
    private static func numericID(_ raw: JSONValue?) -> Int64? {
        guard let raw else { return nil }
        let value: Double
        switch raw {
        case .number(let n): value = n
        case .string(let s): guard let v = Double(s) else { return nil }; value = v
        default: return nil
        }
        guard value.isFinite, value > 0,
              value < 9_007_199_254_740_992,
              value.rounded() == value else { return nil }
        return Int64(value)
    }

    private static func quantity(_ raw: JSONValue?) -> String? {
        guard let raw else { return nil }
        let value: Double
        switch raw {
        case .number(let n): value = n
        case .string(let s): guard let v = Double(s) else { return nil }; value = v
        default: return nil
        }
        guard value.isFinite && value > 0 && value <= 100_000,
              abs(value * 1000 - (value * 1000).rounded()) < 0.00001 else {
            return nil
        }
        let formatted = String(format: "%.3f", value)
        return formatted.replacingOccurrences(
            of: #"\.?0+$"#, with: "", options: .regularExpression)
    }

    private static func positiveWeight(_ raw: JSONValue?) -> String {
        guard let raw, let value = Double(raw.text), value.isFinite,
              value > 0 && value <= 1_000_000,
              abs(value * 1000 - (value * 1000).rounded()) < 0.00001 else {
            return "0"
        }
        return String(format: "%.3f", value).replacingOccurrences(
            of: #"\.?0+$"#, with: "", options: .regularExpression)
    }

    private static func fruit(_ id: Int64, name: String,
                              active: [SyncEvent]) -> SyncEvent? {
        let matches = active.filter {
            $0.tableName == "fruit" &&
            numericID($0.payload["id"]) == id &&
            $0.payload["name"]?.text == name
        }
        guard matches.count == 1 else { return nil }
        // Reject aliased numeric IDs even if only one name matches.
        guard active.filter({
            $0.tableName == "fruit" && numericID($0.payload["id"]) == id
        }).count == 1 else { return nil }
        return matches.first
    }

    private static func active(_ events: [SyncEvent]) -> [SyncEvent] {
        events.filter { !$0.isDeletion }
    }

    /// Most recent completed daily inventory snapshot on or before the
    /// selected purchase date. Remaining stock is a reference label only:
    /// it is NOT the quantity to purchase.
    static func inventory(_ events: [SyncEvent],
                          on date: String) -> [PurchaseImportCandidate] {
        let source = active(events)
        let possible = source.filter {
            $0.tableName == "inventory_snapshot" &&
            ($0.payload["date"]?.text ?? "") <= date &&
            BusinessDates.parse($0.payload["date"]?.text ?? "") != nil
        }
        guard let last = possible.compactMap({
            $0.payload["date"]?.text
        }).max() else { return [] }

        let rows = possible.filter { $0.payload["date"]?.text == last }
        var candidates: [PurchaseImportCandidate] = []
        var seen: Set<String> = []
        for row in rows.sorted(by: { $0.syncId < $1.syncId }) {
            guard let fid = numericID(row.payload["fruit_id"]),
                  let name = row.payload["fruit_name"]?.text,
                  let product = fruit(fid, name: name, active: source),
                  let remaining = quantity(row.payload["remaining_quantity"]),
                  let unit = row.payload["unit"]?.text,
                  !unit.isEmpty else { continue }
            let unique = product.syncId + "|" + unit
            guard seen.insert(unique).inserted else { continue }
            candidates.append(PurchaseImportCandidate(
                id: "inventory|" + row.syncId, source: "上次库存 " + last,
                fruitSyncID: product.syncId,
                fruitName: name, unit: unit,
                suggestedQuantity: "1",
                referenceQuantity: remaining,
                unitWeightJin: "0",
                sourcePlanItemSyncID: nil))
        }
        return candidates
    }

    /// Plan quantities may be suggested as a starting entry, but the actual
    /// price starts at zero and is never derived from estimated order amount.
    /// Only the selected buyer's (or unassigned) pending items are offered.
    static func pendingPlan(_ events: [SyncEvent], on date: String,
                            buyerSyncID: String) -> [PurchaseImportCandidate] {
        let source = active(events)
        let buyers = source.filter {
            $0.tableName == "partner" && $0.syncId == buyerSyncID
        }
        guard buyers.count == 1,
              let buyerID = numericID(buyers[0].payload["id"]),
              source.filter({
                  $0.tableName == "partner" &&
                  numericID($0.payload["id"]) == buyerID
              }).count == 1 else {
            return []
        }
        let plans = source.filter {
            $0.tableName == "purchase_plan" &&
            $0.payload["plan_date"]?.text == date
        }
        guard plans.count == 1,
              let planID = numericID(plans[0].payload["id"]),
              source.filter({
                $0.tableName == "purchase_plan" &&
                numericID($0.payload["id"]) == planID
              }).count == 1 else { return [] }

        var result: [PurchaseImportCandidate] = []
        for item in source.filter({
            $0.tableName == "purchase_plan_item" &&
            numericID($0.payload["plan_id"]) == planID &&
            $0.payload["status"]?.decimal == 0
        }).sorted(by: { $0.syncId < $1.syncId }) {
            let links = source.filter {
                $0.tableName == "purchase_collaboration" &&
                $0.payload["plan_item_sync_id"]?.text == item.syncId
            }
            guard links.count <= 1 else { continue }
            if let owner = links.first,
               let boundBuyer = numericID(owner.payload["buyer_id"]),
               boundBuyer != buyerID { continue }
            guard let fid = numericID(item.payload["fruit_id"]),
                  let name = item.payload["fruit_name"]?.text,
                  let product = fruit(fid, name: name, active: source),
                  let qty = quantity(item.payload["quantity"]),
                  let unit = item.payload["unit"]?.text,
                  !unit.isEmpty else { continue }
            result.append(PurchaseImportCandidate(
                id: "plan|" + item.syncId, source: "待采购计划",
                fruitSyncID: product.syncId, fruitName: name,
                unit: unit, suggestedQuantity: qty,
                referenceQuantity: qty,
                unitWeightJin: positiveWeight(item.payload["unit_weight_jin"]),
                sourcePlanItemSyncID: item.syncId))
        }
        return result
    }

    /// Nothing is removed or replaced. A duplicate fruit+unit is intentionally
    /// skipped to avoid accidentally doubling a purchase quantity.
    static func append(_ chosen: [PurchaseImportCandidate],
                       to existing: [PurchaseDraftLine]) -> PurchaseImportResult {
        var result = existing
        var skipped = 0
        for item in chosen {
            let already = result.contains {
                ($0.fruitSyncID == item.fruitSyncID && $0.unit == item.unit) ||
                (item.sourcePlanItemSyncID != nil &&
                 $0.sourcePlanItemSyncID == item.sourcePlanItemSyncID)
            }
            if already || result.count >= PurchaseDraftPolicy.maxLines {
                skipped += 1
                continue
            }
            result.append(item.asDraftLine())
        }
        return PurchaseImportResult(lines: result,
                                    added: result.count - existing.count,
                                    skipped: skipped)
    }
}
