import Foundation

/// Mirrors Android getInventoryDayItems for unambiguous cloud records.
/// No cloud mutation; an unresolved parent or product blocks that projection.
enum TXInventory094Policy {
    struct Key: Hashable, Codable {
        let product: String
        let unit: String
    }
    struct Row: Identifiable {
        let key: Key
        let name: String
        let opening: Double
        let purchased: Double
        let remaining: Double
        let loss: Double
        let savedID: String?
        let savedVersion: Int64?
        let cost: Double?
        let costSource: String
        let retail: Double?
        let retailUnit: String
        let retailDate: String?
        let weight: Double?
        let previousSnapshotDate: String?
        let openingCost: Double?
        let purchaseCost: Double?
        let averageCost: Double?
        let manualCost: Double?
        let manualBasis: String?
        let retailBasis: String?
        let usesLocalCost: Bool
        let usesLocalRetail: Bool
        var id: Key { key }
        var available: Double { opening + purchased }
        var basis: String {
            [savedID ?? "", String(savedVersion ?? 0), String(opening),
             String(purchased), String(remaining), String(loss)].joined(separator: "|")
        }
    }
    struct Report {
        let rows: [Row]
        let issues: [String]
    }
    private struct Stock {
        let name: String
        let date: String
        let quantity: Double
        let loss: Double
        let event: SyncEvent
    }
    private struct Purchase {
        let name: String
        let date: String
        let quantity: Double
        let cost: Double?
        let weight: Double?
        let created: Double
        let id: Int64?
    }

    static func quantity(_ value: JSONValue?) -> Double? {
        guard let amount = TXFundDataAdapter.number(value), amount >= 0 else { return nil }
        return amount
    }

    static func evaluate(_ snapshot: ReadOnlySnapshot, date: String,
                         prices: [TXInventory095PriceDraft] = []) -> Report {
        let events = Array(snapshot.records.values)
        let fruits = events.filter { $0.tableName == "fruit" }
        var issues = Set<String>()
        func key(_ p: [String: JSONValue]) -> Key? {
            guard let id = AndroidNumericIdentity.exact(p["fruit_id"]),
                  let unit = p["unit"]?.text, !unit.trimmingCharacters(in: .whitespaces).isEmpty
            else { return nil }
            let matches = fruits.filter { AndroidNumericIdentity.exact($0.payload["id"]) == id }
            guard matches.count == 1, let product = matches.first,
                  let name = product.payload["name"]?.text,
                  !name.isEmpty else { return nil }
            return Key(product: product.syncId, unit: unit)
        }
        let orders = events.filter { $0.tableName == "purchase_order" }
        let items = events.filter { $0.tableName == "purchase_item" }
        var purchases: [Key: [Purchase]] = [:]
        var seenOrderChildren = Set<String>()
        for item in items {
            let p = item.payload
            let name = p["fruit_name"]?.text ?? ""
            let parents: [SyncEvent]
            if item.syncId.hasPrefix("collab-item-") {
                let expected = "collab-order-" + String(item.syncId.dropFirst("collab-item-".count))
                let linked = orders.filter { $0.syncId == expected }
                if linked.isEmpty {
                    parents = orders.filter { AndroidNumericIdentity.same($0.payload["id"], p["order_id"]) }
                } else { parents = linked }
            } else {
                parents = orders.filter { AndroidNumericIdentity.same($0.payload["id"], p["order_id"]) }
            }
            guard parents.count == 1, let parent = parents.first,
                  let day = parent.payload["date"]?.text, BusinessDates.parse(day) != nil else {
                issues.insert("存在无法唯一关联采购订单的商品明细"); continue
            }
            if day > date { continue }
            seenOrderChildren.insert(parent.syncId)
            if name.trimmingCharacters(in: .whitespacesAndNewlines) == "总价" { continue }
            guard let k = key(p), !name.isEmpty, let qty = quantity(p["quantity"]) else {
                issues.insert("采购商品身份、单位或数量缺失：" + day); continue
            }
            let direct = quantity(p["unit_price"])
            let total = quantity(p["total_cost"])
            let cost = (direct ?? 0) > 0.000001 ? direct :
                (qty > 0.000001 && (total ?? 0) > 0.000001 ? total.map { $0 / qty } : nil)
            let stamp = parent.payload["created_at"]?.decimal ?? 0
            purchases[k, default: []].append(Purchase(name: name, date: day, quantity: qty,
                cost: cost, weight: quantity(p["unit_weight_jin"]).flatMap { $0 > 0.000001 ? $0 : nil },
                created: stamp.isFinite ? stamp : 0,
                id: AndroidNumericIdentity.exact(p["id"])))
        }
        for order in orders where order.payload["date"]?.text == date &&
            !seenOrderChildren.contains(order.syncId) {
            issues.insert("当天有采购单尚未同步商品明细")
        }

        var current: [Key: [Stock]] = [:]
        var prior: [Key: [Stock]] = [:]
        for e in events where e.tableName == "inventory_snapshot" {
            let p = e.payload
            guard let day = p["date"]?.text, BusinessDates.parse(day) != nil else {
                issues.insert("库存快照缺少日期"); continue
            }
            if day > date { continue }
            let name = p["fruit_name"]?.text ?? ""
            if name.trimmingCharacters(in: .whitespacesAndNewlines) == "总价" { continue }
            guard let k = key(p), !name.isEmpty, let qty = quantity(p["remaining_quantity"]),
                  let loss = quantity(p["loss_quantity"]) else {
                issues.insert("库存身份或数量不完整：" + day); continue
            }
            let value = Stock(name: name, date: day, quantity: qty, loss: loss, event: e)
            if day == date { current[k, default: []].append(value) }
            else { prior[k, default: []].append(value) }
        }
        let manual = events.filter { $0.tableName == "product_cost_reference" }
        let retail = events.filter { $0.tableName == "daily_retail_price" }
        let allKeys = Set(purchases.keys).union(current.keys).union(prior.keys)
        var rows: [Row] = []
        for k in allKeys {
            let today = (purchases[k] ?? []).filter { $0.date == date }
            let historical = prior[k] ?? []
            let last = historical.map(\.date).max()
            let latest = historical.filter { $0.date == last }
            let saved = current[k] ?? []
            guard latest.count <= 1, saved.count <= 1 else {
                issues.insert("同商品同单位库存快照重复，未合并：" + k.unit); continue
            }
            let opening = latest.first?.quantity ?? 0
            let purchased = today.reduce(0) { $0 + $1.quantity }
            guard !today.isEmpty || opening > 0.000001 || !saved.isEmpty else { continue }
            guard let name = today.map(\.name).max() ?? saved.first?.name ?? latest.first?.name,
                  let product = fruits.first(where: { $0.syncId == k.product }),
                  let fid = AndroidNumericIdentity.exact(product.payload["id"]) else { continue }
            // Android's latest positive price/weight ordering: date, order
            // created_at, purchase item ID. If a tie cannot be resolved, do
            // not substitute a guessed price.
            let sorted = (purchases[k] ?? []).sorted {
                if $0.date != $1.date { return $0.date > $1.date }
                if $0.created != $1.created { return $0.created > $1.created }
                return ($0.id ?? 0) > ($1.id ?? 0)
            }
            func latestValue(_ extract: (Purchase) -> Double?, through: String) -> Double? {
                let eligible = sorted.filter { $0.date <= through && extract($0) != nil }
                guard let first = eligible.first else { return nil }
                let tied = eligible.filter {
                    $0.date == first.date && $0.created == first.created && $0.id == first.id
                }
                return tied.count == 1 ? extract(first) : nil
            }
            let latestPurchasePrice = latestValue({ $0.cost }, through: date)
            let manualRows = manual.filter { key($0.payload) == k }
            let originalManual = manualRows.count == 1
                ? quantity(manualRows[0].payload["manual_unit_cost"])
                : nil
            let manualBasis = TXInventory095PriceDraft.basis(manualRows)
            let costDraft = prices.first { $0.kind == .cost && $0.product == k.product &&
                $0.unit == k.unit && $0.date == date && $0.basis == manualBasis }
            let manualValue = costDraft.flatMap { Double($0.value) } ?? originalManual
            let manualCost = manualValue.flatMap { $0 > 0.000001 ? $0 : nil }
            func effective(_ day: String) -> Double? {
                latestValue({ $0.cost }, through: day) ?? manualCost
            }
            func aggregate(_ batch: [Purchase], on day: String) -> (qty: Double, cost: Double, complete: Bool) {
                var qty = 0.0, value = 0.0, complete = true
                for item in batch {
                    qty += item.quantity
                    let price = item.cost ?? effective(day)
                    if item.quantity > 0.000001 {
                        if let price, price > 0.000001 { value += item.quantity * price }
                        else { complete = false }
                    }
                }
                return (qty, value, complete)
            }
            var openingCost: Double? = opening <= 0.000001 ? 0 : nil
            if opening > 0.000001, let last {
                let snapshots = Dictionary(grouping: historical, by: \.date)
                if snapshots.values.allSatisfy({ $0.count == 1 }) {
                    let previousPurchases = Dictionary(grouping: sorted.filter { $0.date <= last }, by: \.date)
                    let dates = Set(previousPurchases.keys).union(snapshots.keys).sorted()
                    var tracker = TXInventory095CostTracker()
                    for day in dates {
                        if let batch = previousPurchases[day] {
                            let a = aggregate(batch, on: day)
                            tracker.purchase(quantity: a.qty, cost: a.cost, known: a.complete)
                        }
                        if let stock = snapshots[day]?.first {
                            tracker.snapshot(remaining: stock.quantity)
                        }
                    }
                    if tracker.complete, let basis = tracker.unitCost ?? effective(last) {
                        openingCost = TXFundAccumulator.roundMoney(opening * basis)
                    }
                } else {
                    issues.insert("历史库存成本结转存在重复快照：" + name)
                }
            }
            let todayCost = aggregate(today, on: date)
            let purchaseValue = todayCost.complete ? TXFundAccumulator.roundMoney(todayCost.cost) : nil
            let average: Double? = openingCost.flatMap { old in
                purchaseValue.map { new in
                    opening + purchased > 0.000001 ? (old + new) / (opening + purchased) : 0
                }
            }
            let retailRows = retail.filter {
                AndroidNumericIdentity.exact($0.payload["fruit_id"]) == fid &&
                ($0.payload["date"]?.text ?? "") <= date &&
                BusinessDates.parse($0.payload["date"]?.text ?? "") != nil
            }
            let exact = retailRows.filter { $0.payload["date"]?.text == date }
            let earlier = retailRows.filter { ($0.payload["date"]?.text ?? "") < date &&
                (quantity($0.payload["price_per_jin"]) ?? 0) > 0 }
            let priorRetailDay = earlier.compactMap { $0.payload["date"]?.text }.max()
            let eligibleRetail = exact.isEmpty ? earlier.filter { $0.payload["date"]?.text == priorRetailDay } : exact
            let priceRow = eligibleRetail.count == 1 ? eligibleRetail.first : nil
            // Retail is one dated snapshot per product, not per purchase unit.
            let retailBasis = TXInventory095PriceDraft.basis(exact)
            let retailDraft = prices.first { $0.kind == .retail && $0.product == k.product &&
                $0.date == date && $0.basis == retailBasis }
            let displayRetail = retailDraft.flatMap { Double($0.value) } ?? quantity(priceRow?.payload["price_per_jin"])
            rows.append(Row(key: k, name: name, opening: opening, purchased: purchased,
                remaining: saved.first?.quantity ?? 0, loss: saved.first?.loss ?? 0,
                savedID: saved.first?.event.syncId, savedVersion: saved.first?.event.rowVersion,
                cost: latestPurchasePrice ?? manualCost,
                costSource: latestPurchasePrice != nil ? "采购" : (manualCost != nil ? "手工参考" : "缺失"),
                retail: displayRetail,
                retailUnit: retailDraft?.unit ?? priceRow?.payload["price_unit"]?.text ?? "斤",
                retailDate: retailDraft != nil ? date : priceRow?.payload["date"]?.text,
                weight: latestValue({ $0.weight }, through: date),
                previousSnapshotDate: last, openingCost: openingCost, purchaseCost: purchaseValue, averageCost: average,
                manualCost: manualValue, manualBasis: manualBasis, retailBasis: retailBasis,
                usesLocalCost: costDraft != nil, usesLocalRetail: retailDraft != nil))
        }
        rows.sort {
            if ($0.purchased > 0.000001) != ($1.purchased > 0.000001) { return $0.purchased > 0.000001 }
            if $0.name != $1.name { return $0.name < $1.name }
            if $0.key.unit != $1.key.unit { return $0.key.unit < $1.key.unit }
            return $0.key.product < $1.key.product
        }
        return Report(rows: rows, issues: issues.sorted())
    }
}
