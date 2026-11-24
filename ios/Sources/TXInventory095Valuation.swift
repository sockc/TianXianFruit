import Foundation

/// Android InventoryCostTracker: weighted inventory cost survives a nonzero
/// stock snapshot; an explicit zero snapshot resets missing-cost history.
struct TXInventory095CostTracker {
    private(set) var quantity = 0.0
    private(set) var cost = 0.0
    private(set) var complete = true
    private var lastUnitCost: Double?
    var unitCost: Double? {
        quantity > 0.000001 && cost > 0.000001 ? cost / quantity : lastUnitCost
    }
    mutating func purchase(quantity: Double, cost: Double, known: Bool) {
        self.quantity += quantity
        self.cost += cost
        if quantity > 0.000001 && !known { complete = false }
        if self.quantity > 0.000001 && self.cost > 0.000001 {
            lastUnitCost = self.cost / self.quantity
        }
    }
    mutating func snapshot(remaining: Double) {
        if remaining <= 0.000001 {
            quantity = 0; cost = 0; lastUnitCost = nil; complete = true
        } else {
            let basis = unitCost
            if basis == nil { complete = false }
            quantity = remaining
            cost = remaining * (basis ?? 0)
            if let basis { lastUnitCost = basis }
        }
    }
}

struct TXInventory095PriceDraft: Codable, Equatable, Identifiable {
    enum Kind: String, Codable { case cost, retail }
    let kind: Kind
    let product: String
    let unit: String
    let date: String
    let value: String
    let basis: String
    // Cost is per product/unit; retail is shared across units for this date.
    var id: String {
        kind.rawValue + "|" + date + "|" + product + (kind == .cost ? "|" + unit : "")
    }
    static func basis(_ records: [SyncEvent]) -> String? {
        guard records.count <= 1 else { return nil }
        guard let record = records.first else { return "absent" }
        guard record.rowVersion > 0 else { return nil }
        return record.syncId + "|" + String(record.rowVersion)
    }
    static func valid(_ raw: String) -> Bool {
        guard raw.range(of: #"^\d{1,7}(\.\d{1,3})?$"#,
                        options: .regularExpression) != nil,
              let v = Double(raw), v.isFinite, v >= 0, v <= 1000000 else { return false }
        return true
    }
}

enum TXInventory095PriceStore {
    static func load(scope: String) throws -> [TXInventory095PriceDraft] {
        let url = try TXInventory094DraftStore.url(scope: "price095|" + scope)
        guard FileManager.default.fileExists(atPath: url.path) else { return [] }
        let rows = try JSONDecoder().decode([TXInventory095PriceDraft].self, from: Data(contentsOf: url))
        guard Set(rows.map(\.id)).count == rows.count,
              rows.allSatisfy({ TXInventory095PriceDraft.valid($0.value) &&
                  !$0.product.isEmpty && !$0.unit.isEmpty && !$0.basis.isEmpty })
        else { throw CocoaError(.fileReadCorruptFile) }
        return rows
    }
    static func save(_ rows: [TXInventory095PriceDraft], scope: String) throws {
        try JSONEncoder().encode(rows).write(
            to: TXInventory094DraftStore.url(scope: "price095|" + scope),
            options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }
}

enum TXInventory095Valuation {
    struct Item {
        let remaining: Double
        let loss: Double
        let sold: Double
        let closingCost: Double?
        let lossCost: Double?
        let consumedCost: Double?
        let estimatedRevenue: Double?
        let anomaly: Bool
        let counted: Bool
        let local: Bool
    }
    static func item(_ row: TXInventory094Policy.Row,
                     draft: TXInventory094Draft?) -> Item {
        let matching = draft.flatMap { $0.basis == row.basis ? $0 : nil }
        let remaining = matching.flatMap { Double($0.remaining) } ?? row.remaining
        let loss = matching.flatMap { Double($0.loss) } ?? row.loss
        let sold = max(0, row.available - remaining - loss)
        let anomaly = remaining + loss > row.available + 0.000001
        let counted = row.savedID != nil || matching != nil
        let round = TXFundAccumulator.roundMoney
        let avg = row.averageCost
        var estimatedRevenue: Double?
        if let retail = row.retail, retail > 0 {
            if row.retailUnit == "斤", let weight = row.weight {
                estimatedRevenue = round(round(sold * weight) * retail)
            } else if row.retailUnit == row.key.unit {
                estimatedRevenue = round(sold * retail)
            }
        }
        return Item(remaining: remaining, loss: loss, sold: sold,
                    closingCost: avg.map { round(remaining * $0) },
                    lossCost: avg.map { round(loss * $0) },
                    consumedCost: avg.map { round(sold * $0) },
                    estimatedRevenue: estimatedRevenue, anomaly: anomaly, counted: counted,
                    local: matching != nil || row.usesLocalCost || row.usesLocalRetail)
    }
}
