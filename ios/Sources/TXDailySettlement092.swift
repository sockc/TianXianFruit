import SwiftUI

/// Independent, read-only daily settlement preview. Android generateCashSettlement
/// uses saved allocated profits (not effective cumulative-balance profit shares).
/// The plan is constrained to the settlement center; it never mutates a ledger.
enum TXDailySettlement092Policy {
    struct Row: Identifiable {
        let id: Int64
        let name: String
        let purchase: Double
        let expense: Double
        let receipt: Double
        let profit: Double
        let keep: Double
        let balance: Double
        let center: Bool
    }
    struct Transfer: Identifiable {
        let id: Int64
        let from: String
        let to: String
        let amount: Double
    }
    struct Report {
        let date: String
        let revenue: Double
        let purchase: Double
        let expense: Double
        let profit: Double
        let inventoryDelta: Double
        let rows: [Row]
        let transfers: [Transfer]
        let blocker: String?
    }
    private struct Block: Error {
        let message: String
        init(_ message: String) { self.message = message }
    }
    private struct Person {
        let syncID: String
        let name: String
        let enabled: Bool
    }
    private struct Rule {
        let person: Int64
        let order: Int64
        let ratio: Double
        let center: Bool
    }
    private struct Flow {
        var purchase = 0.0
        var expense = 0.0
        var receipt = 0.0
        var profit = 0.0
    }
    private static func number(_ raw: JSONValue?) -> Double? {
        TXFundDataAdapter.number(raw)
    }

    static func evaluate(_ snapshot: ReadOnlySnapshot, date: String) -> Report {
        do { return try calculate(snapshot, date: date) }
        catch {
            return Report(date: date, revenue: 0, purchase: 0, expense: 0, profit: 0,
                          inventoryDelta: 0, rows: [], transfers: [],
                          blocker: (error as? Block)?.message ?? "当日数据不完整")
        }
    }

    /// Match the current Android UI's per-partner daily fund registration.
    /// This is not an independently settled purchase flag.
    struct PurchaseProgress: Identifiable {
        let id: String
        let name: String
        let purchase: Double
        let need: Double
        let registered: Double
        var remaining: Double { max(0, TXFundAccumulator.roundMoney(need - registered)) }
    }

    static func purchaseProgress(_ snapshot: ReadOnlySnapshot, date: String) -> [PurchaseProgress]? {
        let all = Array(snapshot.records.values)
        let parents = all.filter { $0.tableName == "daily_cash_settlement" &&
            $0.payload["date"]?.text == date }
        guard parents.count == 1, let parent = parents.first,
              let parentID = AndroidNumericIdentity.exact(parent.payload["id"]),
              AndroidNumericIdentity.unique(parent, in: all) else { return nil }
        let children = all.filter { $0.tableName == "settlement_partner" &&
            $0.payload["date"]?.text == date }
        let transfers = all.filter { $0.tableName == "settlement_transfer" &&
            $0.payload["date"]?.text == date }
        var paid: [(Int64, Int64, Double)] = []
        for e in transfers {
            let p = e.payload
            if number(p["settlement_id"]) == 0 { continue }
            guard AndroidNumericIdentity.exact(p["settlement_id"]) == parentID,
                  let from = AndroidNumericIdentity.exact(p["from_partner_id"]),
                  let to = AndroidNumericIdentity.exact(p["to_partner_id"]),
                  let amount = number(p["amount"]), let registered = number(p["settled_amount"]),
                  amount >= 0, registered >= 0, registered <= amount + 0.005 else { return nil }
            paid.append((from, to, registered))
        }
        var result: [PurchaseProgress] = []
        var seen = Set<Int64>()
        for e in children {
            let p = e.payload
            guard AndroidNumericIdentity.exact(p["settlement_id"]) == parentID,
                  let id = AndroidNumericIdentity.exact(p["partner_id"]),
                  seen.insert(id).inserted,
                  let purchase = number(p["purchase_paid"]), purchase >= 0,
                  let balance = number(p["balance"]) else { return nil }
            if purchase <= 0.005 { continue }
            let related = paid.filter { balance >= 0 ? $0.1 == id : $0.0 == id }
            result.append(PurchaseProgress(id: e.syncId, name: p["partner_name"]?.text ?? "未知",
                purchase: purchase, need: abs(balance),
                registered: TXFundAccumulator.roundMoney(related.reduce(0) { $0 + $1.2 })))
        }
        return children.isEmpty ? nil : result.sorted { $0.id < $1.id }
    }

    private static func calculate(_ snapshot: ReadOnlySnapshot, date: String) throws -> Report {
        let all = Array(snapshot.records.values)
        let events = all.filter { $0.payload["date"]?.text == date }
        var people: [Int64: Person] = [:]
        func required(_ p: [String: JSONValue], _ k: String) throws -> Double {
            guard let x = number(p[k]) else { throw Block("缺少或无效金额字段：" + k) }
            return x
        }
        func identity(_ p: [String: JSONValue], _ k: String) throws -> Int64 {
            guard let id = AndroidNumericIdentity.exact(p[k]) else {
                throw Block("缺少有效身份字段：" + k)
            }
            return id
        }
        for e in all where e.tableName == "partner" {
            let p = e.payload
            let id = try identity(p, "id")
            let name = p["name"]?.text ?? ""
            guard people[id] == nil, !name.isEmpty, let enabled = number(p["enabled"]),
                  enabled == 0 || enabled == 1 else {
                throw Block("合伙人身份重复或状态缺失")
            }
            people[id] = Person(syncID: e.syncId, name: name, enabled: enabled == 1)
        }
        guard !people.isEmpty else { throw Block("未同步合伙人主数据") }
        func partner(_ p: [String: JSONValue], _ key: String, _ nameKey: String) throws -> Int64 {
            let id = try identity(p, key)
            guard let person = people[id] else { throw Block("合伙人引用无法唯一关联：" + key) }
            if let name = p[nameKey]?.text, !name.isEmpty, name != person.name {
                throw Block("合伙人身份与名称不一致：" + name)
            }
            return id
        }

        var rules: [Rule] = []
        var seenRules = Set<Int64>()
        for e in all where e.tableName == "profit_rule" {
            let p = e.payload
            let id = try identity(p, "partner_id")
            guard let person = people[id] else { throw Block("利润规则合伙人缺失") }
            if !person.enabled { continue }
            let order = try identity(p, "id")
            guard seenRules.insert(id).inserted else { throw Block("同一合伙人存在多份利润规则") }
            let ratio = try required(p, "percent") / 100
            guard ratio >= 0 else { throw Block("利润比例异常") }
            let marker = number(p["is_settlement_center"])
            guard marker == 0 || marker == 1 else { throw Block("资金中心标记不完整") }
            rules.append(Rule(person: id, order: order, ratio: ratio, center: marker == 1))
        }
        guard Set(rules.map(\.order)).count == rules.count else { throw Block("利润规则ID重复") }
        rules.sort { $0.person < $1.person }
        let explicit = rules.filter(\.center).max { $0.order < $1.order }
        let center = explicit?.person ?? rules.min { $0.order < $1.order }?.person ??
            people.keys.sorted().first { people[$0]?.enabled == true }


        guard let center, let centerName = people[center]?.name else {
            throw Block("请先设置资金中心")
        }
        let daily = events.filter { $0.tableName == "daily_cash_settlement" }
        guard daily.count <= 1 else { throw Block("当日存在重复日结快照") }
        // Even a transfer with an uncertain parent must never be ignored.
        for e in events where e.tableName == "settlement_transfer" {
            let p = e.payload
            guard let parent = number(p["settlement_id"]),
                  let paid = number(p["settled_amount"]), paid >= 0 else {
                throw Block("当日转账记录不完整")
            }
            if parent != 0 && paid > 0.005 {
                throw Block("当天已有实际结算登记，请查看原保存方案，不重新生成")
            }
            if parent != 0 {
                guard let d = daily.first,
                      let parentID = AndroidNumericIdentity.exact(p["settlement_id"]),
                      AndroidNumericIdentity.exact(d.payload["id"]) == parentID else {
                    throw Block("当日转账父记录不明确")
                }
            }
        }
            var flows: [Int64: Flow] = [:]
            var revenue = 0.0, opening = 0.0, closing = 0.0, purchase = 0.0, expense = 0.0
            for e in events where e.tableName == "purchase_order" {
                let p = e.payload
                let amount = try required(p, "total_cost")
                let id = try partner(p, "buyer_id", "buyer_name")
                purchase += amount
                flows[id, default: Flow()].purchase += amount
            }
            for e in events where e.tableName == "store_daily_record" {
                let p = e.payload
                let savedRevenue = try required(p, "revenue")
                let cost = try required(p, "expense")
                revenue += savedRevenue
                expense += cost
                opening += try required(p, "opening_stock_value")
                closing += try required(p, "stock_left_value")
                if cost > 0 {
                    let id = try partner(p, "expense_payer_id", "expense_payer_name")
                    flows[id, default: Flow()].expense += cost
                    }
                var attributed = 0.0
                var splits: [[String: JSONValue]] = []
                if let raw = p["receipt_splits_json"]?.text, !raw.isEmpty {
                    guard let data = raw.data(using: .utf8),
                          let decoded = try? JSONDecoder().decode([[String: JSONValue]].self, from: data)
                    else { throw Block("逐人收款明细无法解析：" + date) }
                    splits = decoded
                }
                if !splits.isEmpty {
                    for split in splits {
                        let channels = try ["wechat_income", "alipay_income", "cash_income"]
                            .map { try required(split, $0) }
                        let channelTotal = TXFundAccumulator.roundMoney(channels.map {
                            TXFundAccumulator.roundMoney($0)
                        }.reduce(0, +))
                        let amount = channelTotal > 0.005 ? channelTotal :
                            TXFundAccumulator.roundMoney(try required(split, "amount"))
                        if amount <= 0.005 { continue }
                        let id = try partner(split, "partner_id", "partner_name")
                        flows[id, default: Flow()].receipt += amount
                        attributed += amount
                            }
                } else {
                    for channel in ["wechat", "alipay", "cash"] {
                        let amount = try required(p, channel + "_income")
                        if abs(amount) <= 0.005 { continue }
                        guard p[channel + "_collector_name"]?.text != "多人收款" else {
                            throw Block("旧版多人收款缺少逐人明细：" + date)
                        }
                        let id = try partner(p, channel + "_collector_id", channel + "_collector_name")
                        flows[id, default: Flow()].receipt += amount
                        attributed += amount
                            }
                }
                guard abs(attributed - savedRevenue) < 0.005 else {
                    throw Block("逐人收款合计与营业额不符：" + date)
                }
            }


        let round = TXFundAccumulator.roundMoney
        let actual = revenue + closing - opening - purchase - expense
        let delta = round(closing - opening)
        func report(_ blocker: String?, _ rows: [Row] = [],
                    _ transfers: [Transfer] = []) -> Report {
            Report(date: date, revenue: revenue, purchase: purchase, expense: expense,
                   profit: actual, inventoryDelta: delta, rows: rows,
                   transfers: transfers, blocker: blocker)
        }
        guard revenue > 0 else { return report("当天还没有营业额") }
        let distributions = events.filter { $0.tableName == "profit_distribution" }
        guard !distributions.isEmpty || abs(actual) <= 0.005 else {
            return report(actual < 0 ? "请先保存当天亏损分担" : "请先保存当天利润分配")
        }
        guard abs(delta) <= 0.01 else {
            return report("开摊／收摊库存估值不同，安卓当日现金结清要求库存差为0")
        }
        var seen = Set<Int64>()
        for e in distributions {
            let p = e.payload
            let id = try partner(p, "partner_id", "partner_name")
            guard seen.insert(id).inserted else { return report("当天利润分配重复，需先核对") }
            flows[id, default: Flow()].profit = try required(p, "allocated_profit")
        }
        if flows[center] == nil { flows[center] = Flow() }
        let rows = try flows.keys.sorted().map { id -> Row in
            guard let f = flows[id], let person = people[id] else {
                throw Block("合伙人身份未确认")
            }
            let keep = round(f.purchase + f.expense + f.profit)
            return Row(id: id, name: person.name, purchase: f.purchase,
                       expense: f.expense, receipt: f.receipt, profit: f.profit,
                       keep: keep, balance: round(keep - f.receipt), center: id == center)
        }
        let total = round(rows.reduce(0) { $0 + $1.balance })
        guard abs(total) <= 0.01 else {
            return report("资金差额未轧平：" + total.formatted(.currency(code: "CNY")), rows)
        }
        let transfers = rows.filter { !$0.center && abs($0.balance) > 0.005 }.map { row in
            Transfer(id: row.id, from: row.balance > 0 ? centerName : row.name,
                     to: row.balance > 0 ? row.name : centerName, amount: round(abs(row.balance)))
        }
        return report(nil, rows, transfers)
    }
}

struct TXDailySettlement092Panel: View {
    @EnvironmentObject private var model: LedgerViewModel
    let date: String
    @State private var result: TXDailySettlement092Policy.Report?
    @State private var showRows = false
    @State private var showTransfers = true

    private func amount(_ name: String, _ value: Double) -> some View {
        HStack {
            Text(name).foregroundColor(.secondary)
            Spacer()
            Text(value.formatted(.currency(code: "CNY"))).fontWeight(.semibold)
        }.font(.caption)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("当日实时计算").font(.subheadline.bold())
                Spacer()
                Button("计算预览") {
                    result = TXDailySettlement092Policy.evaluate(model.snapshot, date: date)
                }
                .font(.caption.bold())
                .disabled(model.isBusy || model.lastSyncAt == nil || model.isUsingCachedAuthorization)
            }
            Text("按安卓当日现金轧差规则读取已同步数据，不写入结算记录。")
                .font(.caption2).foregroundColor(.secondary)
            if let result {
                if result.blocker == nil || result.revenue > 0 {
                    HStack(spacing: 5) {
                        tile("营业额", result.revenue)
                        tile("采购金额", result.purchase)
                        tile("当日利润", result.profit)
                    }
                    amount("费用", result.expense)
                    amount("库存估值差", result.inventoryDelta)
                }
                if let blocker = result.blocker {
                    Label(blocker, systemImage: "exclamationmark.triangle")
                        .font(.caption).foregroundColor(.orange)
                }
                if !result.rows.isEmpty {
                    DisclosureGroup(isExpanded: $showRows) {
                        ForEach(result.rows) { row in
                            VStack(alignment: .leading, spacing: 4) {
                                HStack {
                                    Text(row.name + (row.center ? " · 资金中心" : "")).font(.caption.bold())
                                    Spacer()
                                    Text(row.balance.formatted(.currency(code: "CNY")))
                                        .font(.subheadline.bold())
                                        .foregroundColor(row.balance < 0 ? .red : TXTheme.primary)
                                }
                                amount("收款", row.receipt)
                                amount("采购垫付", row.purchase)
                                amount("费用垫付", row.expense)
                                amount("已存利润份额", row.profit)
                                amount("应留存", row.keep)
                                Divider()
                            }
                        }
                    } label: { Text("合伙人资金轧差").font(.subheadline.bold()) }
                }
                if result.blocker == nil {
                    DisclosureGroup(isExpanded: $showTransfers) {
                        if result.transfers.isEmpty {
                            Text("当前预览无资金中心转账需求").font(.caption)
                        }
                        ForEach(result.transfers) { t in
                            HStack {
                                Text(t.from + " → " + t.to)
                                Spacer()
                                Text(t.amount.formatted(.currency(code: "CNY"))).bold()
                            }.font(.caption)
                        }
                    } label: { Text("最少转账方案 · \(result.transfers.count)笔").font(.subheadline.bold()) }
                    Text("这是待跨端核对的计算预览，不代表已结清；不是已保存结算单。")
                        .font(.caption2).foregroundColor(.secondary)
                }
            }
            if let progress = TXDailySettlement092Policy.purchaseProgress(model.snapshot, date: date),
               !progress.isEmpty {
                DisclosureGroup {
                    ForEach(progress) { row in
                        VStack(alignment: .leading, spacing: 4) {
                            Text(row.name).font(.caption.bold())
                            amount("采购垫付", row.purchase)
                            amount("当日资金已登记", row.registered)
                            amount("当日资金未登记", row.remaining)
                            Text(row.remaining <= 0.005 ? "当日资金原始方案无剩余" :
                                (row.registered <= 0.005 ? "当日资金未登记" : "当日资金部分登记"))
                                .font(.caption2).foregroundColor(.secondary)
                        }
                    }
                } label: { Text("采购垫付与当日资金登记").font(.caption.bold()) }
            }
            Text("采购垫付随当日转账逐笔结算；不会单独推断采购款已结清。")
                .font(.caption2).foregroundColor(.secondary)
        }
        .padding(10)
        .background(Color(UIColor.secondarySystemGroupedBackground),
                    in: RoundedRectangle(cornerRadius: 10))
        .onChange(of: date) { _ in result = nil }
        .onChange(of: model.lastSyncAt) { _ in result = nil }
        .onChange(of: model.selectedBookID) { _ in result = nil }
        .onChange(of: model.isUsingCachedAuthorization) { _ in result = nil }
    }

    private func tile(_ label: String, _ value: Double) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(label).font(.caption2).foregroundColor(.secondary)
            Text(value.formatted(.currency(code: "CNY")))
                .font(.subheadline.bold()).lineLimit(1).minimumScaleFactor(0.6)
        }.frame(maxWidth: .infinity, alignment: .leading)
    }
}
