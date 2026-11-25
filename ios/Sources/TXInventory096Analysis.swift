import SwiftUI

/// Read-only Android ProfitOpportunityCalculator and getOperatingAnalysis
/// projections. Neither local drafts nor estimates feed accounting writes.
enum TXInventory096Analysis {
    struct Item: Identifiable {
        let row: TXInventory094Policy.Row
        let valuation: TXInventory095Valuation.Item
        let availableProfit: Double?
        let purchasedProfit: Double?
        var id: TXInventory094Policy.Key { row.key }
        var hasAvailable: Bool { row.available > 0.000001 }
        var hasPurchased: Bool { row.purchased > 0.000001 }
        var knownSale: Bool { valuation.counted && !valuation.anomaly && valuation.consumedCost != nil }
        var productGross: Double? {
            guard knownSale, let revenue = valuation.estimatedRevenue,
                  let cost = valuation.consumedCost else { return nil }
            return TXFundAccumulator.roundMoney(revenue - cost)
        }
        var margin: Double? {
            guard let revenue = valuation.estimatedRevenue, revenue > 0,
                  let profit = productGross else { return nil }
            return profit / revenue
        }
    }
    struct Report {
        let date: String
        let items: [Item]
        let issues: [String]
        let revenue: Double?
        let expense: Double?
        let recordedProfit: Double?
        let previousDayAligned: Bool
        var missingAvailable: Int { items.filter { $0.hasAvailable && $0.availableProfit == nil }.count }
        var missingPurchased: Int { items.filter { $0.hasPurchased && $0.purchasedProfit == nil }.count }
        var knownAvailable: Double { items.compactMap(\.availableProfit).reduce(0,+) }
        var knownPurchased: Double { items.compactMap(\.purchasedProfit).reduce(0,+) }
        var hasAvailable: Bool { items.contains { $0.hasAvailable } }
        var hasPurchased: Bool { items.contains { $0.hasPurchased } }
        var missingCounts: Int { items.filter { !$0.valuation.counted || $0.valuation.anomaly }.count }
        var missingCosts: Int { items.filter { $0.hasAvailable && $0.row.averageCost == nil }.count }
        var hasLocal: Bool { items.contains { $0.valuation.local } }
        var complete: Bool {
            issues.isEmpty && !items.isEmpty && missingCounts == 0 && missingCosts == 0 &&
                previousDayAligned && revenue != nil && expense != nil
        }
        var soldCost: Double { TXFundAccumulator.roundMoney(items.filter(\.knownSale).compactMap { $0.valuation.consumedCost }.reduce(0,+)) }
        var lossCost: Double { TXFundAccumulator.roundMoney(items.filter(\.knownSale).compactMap { $0.valuation.lossCost }.reduce(0,+)) }
        var gross: Double? {
            guard complete, let revenue else { return nil }
            return TXFundAccumulator.roundMoney(revenue - soldCost)
        }
        var operatingProfit: Double? {
            guard let gross, let expense else { return nil }
            return TXFundAccumulator.roundMoney(gross - lossCost - expense)
        }
    }

    static func opportunity(qty: Double, cost: Double?, retail: Double?,
                            weight: Double?, retailUnit: String, unit: String) -> Double? {
        if qty < 0.000001 { return 0 }
        guard let cost, cost.isFinite, cost >= 0,
              let retail, retail.isFinite, retail > 0 else { return nil }
        let sales: Double
        if retailUnit == "斤" {
            guard let weight, weight.isFinite, weight > 0 else { return nil }
            sales = qty * weight * retail
        } else if retailUnit == unit { sales = qty * retail }
        else { return nil }
        let result = sales - cost
        return result.isFinite ? result : nil
    }

    static func evaluate(_ snapshot: ReadOnlySnapshot, date: String,
                         drafts: [TXInventory094Draft] = [],
                         prices: [TXInventory095PriceDraft] = [],
                         extraIssues: [String] = []) -> Report {
        let inventory = TXInventory094Policy.evaluate(snapshot, date: date, prices: prices)
        let items = inventory.rows.map { row -> Item in
            let old: Double? = row.opening < 0.000001 ? 0 : row.openingCost
            let new: Double? = row.purchased < 0.000001 ? 0 : row.purchaseCost
            let combined = old.flatMap { a in new.map { a + $0 } }
            let unit = row.retailUnit.isEmpty ? "斤" : row.retailUnit
            func gross(_ qty: Double, _ cost: Double?) -> Double? {
                opportunity(qty: qty, cost: cost, retail: row.retail,
                            weight: row.weight, retailUnit: unit, unit: row.key.unit)
            }
            return Item(row: row, valuation: TXInventory095Valuation.item(row,
                draft: drafts.first { $0.key == row.key }),
                availableProfit: gross(row.available, combined),
                purchasedProfit: gross(row.purchased, new))
        }
        let events = snapshot.records.values.filter {
            $0.tableName == "store_daily_record" && $0.payload["date"]?.text == date
        }
        func sum(_ key: String) -> Double? {
            guard !events.isEmpty else { return nil }
            let values = events.compactMap { TXFundDataAdapter.number($0.payload[key]) }
            return values.count == events.count ? TXFundAccumulator.roundMoney(values.reduce(0,+)) : nil
        }
        let yesterday = BusinessDates.parse(date).map { BusinessDates.daysAgo(1, from: $0) }
        let aligned = items.filter { $0.row.opening > 0.000001 }.allSatisfy {
            $0.row.previousSnapshotDate == yesterday
        }
        let orders = snapshot.records.values.filter {
            $0.tableName == "purchase_order" && $0.payload["date"]?.text == date
        }
        let orderAmounts = orders.compactMap { TXFundDataAdapter.number($0.payload["total_cost"]) }
        // Formal daily summary uses purchase orders, not product-level cost
        // estimates or the editable inventory drafts.
        let actual: Double?
        if let revenue = sum("revenue"), let expense = sum("expense"),
           let opening = sum("opening_stock_value"), let closing = sum("stock_left_value"),
           orderAmounts.count == orders.count {
            actual = TXFundAccumulator.roundMoney(revenue + closing - opening -
                orderAmounts.reduce(0,+) - expense)
        } else { actual = nil }
        return Report(date: date, items: items, issues: inventory.issues + extraIssues,
                      revenue: sum("revenue"), expense: sum("expense"),
                      recordedProfit: actual, previousDayAligned: aligned)
    }
}

/// Load local preview drafts only in the same editable test book, never
/// across accounts or into the formal accounting model.
@MainActor
enum TXInventory096Readout {
    static func report(_ model: LedgerViewModel, date: String) -> TXInventory096Analysis.Report {
        var drafts: [TXInventory094Draft] = []
        var prices: [TXInventory095PriceDraft] = []
        var issues: [String] = []
        if model.v34LocalCandidate,
           let username = model.account?.username, let book = model.selectedBookID {
            let scope = [username, book, date].joined(separator: "|")
            do {
                drafts = try TXInventory094DraftStore.load(scope: scope)
                prices = try TXInventory095PriceStore.load(scope: scope)
            } catch {
                drafts = []; prices = []
                issues.append("本地库存或价格草稿读取失败，未纳入预估")
            }
        }
        let result = TXInventory096Analysis.evaluate(model.snapshot, date: date,
            drafts: drafts, prices: prices, extraIssues: issues)
        let staleStock = drafts.contains { d in !result.items.contains { $0.row.key == d.key && $0.row.basis == d.basis } }
        let stalePrice = prices.contains { d in !result.items.contains {
            $0.row.key.product == d.product &&
            (d.kind == .retail || $0.row.key.unit == d.unit) &&
            d.basis == (d.kind == .cost ? $0.row.manualBasis : $0.row.retailBasis)
        } }
        if staleStock || stalePrice {
            issues.append("存在云端版本已变化的草稿，未用于估值")
            return TXInventory096Analysis.evaluate(model.snapshot, date: date,
                drafts: drafts, prices: prices, extraIssues: issues)
        }
        return result
    }
}

struct TXInventory096Overview: View {
    @EnvironmentObject private var model: LedgerViewModel
    let date: String
    var compact = false
    @State private var report: TXInventory096Analysis.Report?
    @State private var reportKey = ""

    private var key: String {
        [model.account?.username ?? "", model.selectedBookID ?? "", date,
         String(model.currentCursor), String(model.lastSyncAt?.timeIntervalSince1970 ?? 0),
         String(model.isUsingCachedAuthorization)].joined(separator: "|")
    }
    private func money(_ value: Double?) -> String {
        value.map { $0.formatted(.currency(code: "CNY").precision(.fractionLength(0))) } ?? "—"
    }
    private func potential(_ value: Double, missing: Int, hasGoods: Bool, issues: [String]) -> String {
        if !issues.isEmpty { return "待核对" }
        if !hasGoods { return "—" }
        if missing > 0 { return "缺\(missing)项" }
        return money(value)
    }
    private func tile(_ title: String, _ amount: String, _ subtitle: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(.caption).foregroundColor(.secondary)
            Text(amount).font(.headline.bold()).lineLimit(1).minimumScaleFactor(0.65)
            Text(subtitle).font(.caption2).foregroundColor(.secondary)
        }.frame(maxWidth: .infinity, alignment: .leading)
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let r = report, reportKey == key {
                HStack(spacing: 5) {
                    tile("预估利润", potential(r.knownAvailable, missing: r.missingAvailable,
                         hasGoods: r.hasAvailable, issues: r.issues), "结转＋采购卖光")
                    tile("估算利润", potential(r.knownPurchased, missing: r.missingPurchased,
                         hasGoods: r.hasPurchased, issues: r.issues), "今日采购卖光")
                    if !compact {
                        tile("实际利润", money(r.recordedProfit), "原账本日汇总")
                    }
                }
                if r.hasLocal {
                    Text("含本地草稿 · 未同步").font(.caption2).foregroundColor(.orange)
                }
                if !compact {
                    HStack(spacing: 5) {
                        tile("销售耗用成本", r.complete ? money(r.soldCost) : "待核对", "已盘点")
                        tile("损耗成本", r.complete ? money(r.lossCost) : "待核对", "已盘点")
                        tile("经营利润", money(r.operatingProfit), "营业额−耗用−损耗−费用")
                    }
                    if r.missingCounts > 0 || r.missingCosts > 0 || !r.previousDayAligned || !r.issues.isEmpty {
                        Text("未盘点/异常 \(r.missingCounts)项 · 缺成本 \(r.missingCosts)项" +
                             (r.previousDayAligned ? "" : " · 结转非昨日"))
                            .font(.caption2).foregroundColor(.orange)
                    }
                }
                NavigationLink(destination: TXInventory096Detail(initialDate: BusinessDates.parse(date) ?? Date())) {
                    Label("商品利润与库存经营分析", systemImage: "chart.bar.xaxis")
                        .font(.caption.bold())
                }
            } else {
                Text("尚未取得库存分析数据").font(.caption).foregroundColor(.secondary)
            }
        }
        .padding(10)
        .background(Color(UIColor.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 10))
        .task(id: key) {
            report = nil; reportKey = ""
            guard model.lastSyncAt != nil else { return }
            report = TXInventory096Readout.report(model, date: date)
            reportKey = key
        }
    }
}

struct TXInventory096Detail: View {
    @EnvironmentObject private var model: LedgerViewModel
    @State private var date: Date
    @State private var mode = "预估利润"
    @State private var report: TXInventory096Analysis.Report?
    @State private var reportKey = ""
    init(initialDate: Date = Date()) { _date = State(initialValue: initialDate) }
    private var day: String { BusinessDates.key(date) }
    private var key: String {
        [model.account?.username ?? "", model.selectedBookID ?? "", day,
         String(model.currentCursor), String(model.lastSyncAt?.timeIntervalSince1970 ?? 0),
         String(model.isUsingCachedAuthorization)].joined(separator: "|")
    }
    private func money(_ n: Double?) -> String { n.map { $0.formatted(.currency(code: "CNY")) } ?? "缺资料" }
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 9) {
                TXDayBar(date: $date, label: "分析日期", allowFuture: false)
                if let r = report, reportKey == key {
                    VStack(alignment: .leading, spacing: 5) {
                        HStack {
                            Text("实际利润").font(.caption)
                            Spacer()
                            Text(money(r.recordedProfit)).font(.headline)
                        }
                        HStack {
                            Text("销售耗用成本").font(.caption)
                            Spacer()
                            Text(r.complete ? money(r.soldCost) : "待核对").font(.subheadline.bold())
                        }
                        HStack {
                            Text("损耗成本").font(.caption)
                            Spacer()
                            Text(r.complete ? money(r.lossCost) : "待核对").font(.subheadline.bold())
                        }
                        HStack {
                            Text("盘点经营利润").font(.caption)
                            Spacer()
                            Text(money(r.operatingProfit)).font(.subheadline.bold())
                        }
                        if !r.complete {
                            Text("盘点或成本未完整，或结转非昨日；不展示完整经营利润。")
                                .font(.caption2).foregroundColor(.orange)
                        }
                        if r.hasLocal {
                            Text("含本地草稿 · 未同步").font(.caption2).foregroundColor(.orange)
                        }
                    }.padding(10).background(TXTheme.header, in: RoundedRectangle(cornerRadius: 10))
                }
                Picker("分析口径", selection: $mode) {
                    Text("预估利润").tag("预估利润")
                    Text("估算利润").tag("估算利润")
                    Text("盘点分析").tag("盘点分析")
                }.pickerStyle(.segmented)
                if let r = report, reportKey == key {
                    Text(mode == "预估利润" ? "结转＋采购全部卖光的潜在毛利，无需当日盘点。" :
                         mode == "估算利润" ? "仅今日采购全部卖光的潜在毛利。" :
                         "盘点后按加权成本估算销售耗用、损耗及单品销售毛利。")
                        .font(.caption).foregroundColor(.secondary)
                    ForEach(r.issues, id: \.self) { issue in
                        Text(issue).font(.caption2).foregroundColor(.orange)
                    }
                    if mode != "盘点分析" {
                        Text("已知商品潜在毛利 " + money(mode == "预估利润" ? r.knownAvailable : r.knownPurchased))
                            .font(.subheadline.bold())
                    }
                    ForEach(r.items.filter { mode == "盘点分析" || (mode == "预估利润" ? $0.hasAvailable : $0.hasPurchased) }) { item in
                        VStack(alignment: .leading, spacing: 5) {
                            HStack {
                                Text(item.row.name).font(.subheadline.bold())
                                Spacer()
                                Text(money(mode == "预估利润" ? item.availableProfit :
                                           mode == "估算利润" ? item.purchasedProfit : item.productGross))
                                    .font(.subheadline.bold())
                            }
                            Text("结转 \(item.row.opening.formatted()) · 采购 \(item.row.purchased.formatted()) \(item.row.key.unit)")
                                .font(.caption).foregroundColor(.secondary)
                            if mode == "盘点分析" {
                                if !item.valuation.counted || item.valuation.anomaly {
                                    Text("未盘点或数量异常，销售数量与毛利待核对").font(.caption).foregroundColor(.orange)
                                } else {
                                    Text("销售 \(item.valuation.sold.formatted()) \(item.row.key.unit) · 耗用 " + money(item.valuation.consumedCost))
                                        .font(.caption)
                                    Text("损耗 " + money(item.valuation.lossCost) + " · 单品毛利率 " +
                                         (item.margin.map { $0.formatted(.percent.precision(.fractionLength(1))) } ?? "—"))
                                        .font(.caption)
                                }
                            } else {
                                Text("成本 " + money(item.row.cost) + " · 零售 " + money(item.row.retail) + "/" + item.row.retailUnit)
                                    .font(.caption).foregroundColor(.secondary)
                            }
                        }.padding(10).background(Color(UIColor.secondarySystemGroupedBackground),
                                                  in: RoundedRectangle(cornerRadius: 9))
                    }
                }
                Text("所有库存推算及本地草稿仅用于经营分析，不参与正式利润分配、资金轧差或结算。")
                    .font(.caption2).foregroundColor(.secondary)
                SyncFooter()
            }.padding(12)
        }
        .navigationTitle("库存经营分析").navigationBarTitleDisplayMode(.inline)
        .task(id: key) {
            report = nil; reportKey = ""
            guard model.lastSyncAt != nil else { return }
            report = TXInventory096Readout.report(model, date: day); reportKey = key
        }
    }
}
