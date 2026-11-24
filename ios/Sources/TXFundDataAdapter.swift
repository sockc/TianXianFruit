import Foundation

/// Conditional reconstruction from the materialized cloud feed. Numeric
/// Android IDs are local references, not global identities. Only accept
/// unambiguous references to a single partner sync record; never join by name.
/// This is a comparison candidate, not a server-certified balance.
enum TXFundDataAdapter {
    struct Row: Identifiable {
        let id: String
        let name: String
        let balance: Double
        let isCenter: Bool
    }
    struct Report {
        let rows: [Row]
        let blocker: String?
    }
    struct Block: Error {
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
        var saved: Double?
        var settled = false
        var dailySent = 0.0
        var dailyReceived = 0.0
        var hasDaily = false
        var sent = 0.0
        var received = 0.0
        var cutoff = false
    }

    static func number(_ value: JSONValue?) -> Double? {
        guard let value else { return nil }
        let result: Double?
        switch value {
        case .number(let x): result = x
        case .exactInteger(let x): result = Double(x)
        case .string(let x): result = Double(x)
        default: result = nil
        }
        guard let result, result.isFinite, abs(result) < 1e12 else { return nil }
        return result
    }

    static func evaluate(_ snapshot: ReadOnlySnapshot, until: String) -> Report {
        do {
            return Report(rows: try calculate(snapshot, until: until), blocker: nil)
        } catch let error as Block {
            return Report(rows: [], blocker: error.message)
        } catch {
            return Report(rows: [], blocker: "资金运算输入异常，无法安全核对")
        }
    }

    private static func calculate(_ snapshot: ReadOnlySnapshot, until: String) throws -> [Row] {
        let all = Array(snapshot.records.values)
        let tables = Set(["purchase_order", "store_daily_record", "profit_distribution",
                          "daily_cash_settlement", "settlement_partner", "settlement_transfer"])
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

        var byDate: [String: [SyncEvent]] = [:]
        for event in all where tables.contains(event.tableName) {
            guard let date = event.payload["date"]?.text, date.count == 10,
                  let parsed = BusinessDates.parse(date), BusinessDates.key(parsed) == date else {
                throw Block("资金记录缺少有效营业日期")
            }
            if date <= until { byDate[date, default: []].append(event) }
        }
        guard !byDate.isEmpty else { throw Block("截止日期前没有可核对记录") }

        var active = Set(people.keys.filter { people[$0]?.enabled == true })
        var ledger: [Int64: [TXFundAccumulator.Day]] = [:]
        for date in byDate.keys.sorted() {
            let events = byDate[date] ?? []
            var flows: [Int64: Flow] = [:]
            var revenue = 0.0, opening = 0.0, closing = 0.0, purchase = 0.0, expense = 0.0
            for e in events where e.tableName == "purchase_order" {
                let p = e.payload
                let amount = try required(p, "total_cost")
                let id = try partner(p, "buyer_id", "buyer_name")
                purchase += amount
                flows[id, default: Flow()].purchase += amount
                active.insert(id)
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
                    active.insert(id)
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
                        active.insert(id)
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
                        active.insert(id)
                    }
                }
                guard abs(attributed - savedRevenue) < 0.005 else {
                    throw Block("逐人收款合计与营业额不符：" + date)
                }
            }

            let actualProfit = TXFundAccumulator.roundMoney(revenue + closing - opening - purchase - expense)
            var historical: [(Int64, Double, Double, Int64)] = []
            var profitPeople = Set<Int64>()
            for e in events where e.tableName == "profit_distribution" {
                let p = e.payload
                let id = try partner(p, "partner_id", "partner_name")
                guard profitPeople.insert(id).inserted else { throw Block("历史利润分配重复：" + date) }
                historical.append((id, try required(p, "ratio"), try required(p, "allocated_profit"),
                                   try identity(p, "id")))
            }
            if abs(actualProfit) > 0.005 {
                guard Set(historical.map { $0.3 }).count == historical.count else {
                    throw Block("历史利润分配ID重复：" + date)
                }
                historical.sort { $0.2 == $1.2 ? $0.3 < $1.3 : $0.2 > $1.2 }
                let useHistorical = !historical.isEmpty && abs(historical.map { $0.1 }.reduce(0,+) - 1) < 0.01
                let shares = useHistorical ? historical.map { ($0.0, $0.1) } :
                    rules.map { ($0.person, $0.ratio) }
                let total = shares.map { $0.1 }.reduce(0,+)
                guard total > 0.000001, shares.allSatisfy({ $0.1 >= 0 }) else {
                    throw Block("缺少有效利润分配规则：" + date)
                }
                var allocated = 0.0
                for (index, share) in shares.enumerated() {
                    let amount = TXFundAccumulator.roundMoney(index == shares.count - 1 ?
                        actualProfit - allocated : actualProfit * share.1 / total)
                    allocated = TXFundAccumulator.roundMoney(allocated + amount)
                    flows[share.0, default: Flow()].profit = amount
                    active.insert(share.0)
                }
            }

            let dailyRows = events.filter { $0.tableName == "daily_cash_settlement" }
            guard dailyRows.count <= 1 else { throw Block("重复当日日结：" + date) }
            let dailyID = try dailyRows.first.map { try identity($0.payload, "id") }
            var dailyStatus = 0.0
            if let daily = dailyRows.first {
                dailyStatus = try required(daily.payload, "status")
                guard [0.0, 1.0, 2.0].contains(dailyStatus) else { throw Block("未知日结状态：" + date) }
            }
            for e in events where e.tableName == "settlement_partner" {
                let p = e.payload
                guard let dailyID, try identity(p, "settlement_id") == dailyID else {
                    throw Block("日结子记录父身份无法核对：" + date)
                }
                let id = try partner(p, "partner_id", "partner_name")
                guard flows[id]?.saved == nil else { throw Block("同日合伙人结算重复：" + date) }
                flows[id, default: Flow()].saved = try required(p, "balance")
                flows[id, default: Flow()].settled = dailyStatus == 1
                active.insert(id)
            }
            for e in events where e.tableName == "settlement_transfer" {
                let p = e.payload
                let amount = try required(p, "amount")
                let paid = try required(p, "settled_amount")
                guard amount >= 0, paid >= 0, paid <= amount + 0.005 else {
                    throw Block("转账登记金额异常：" + date)
                }
                if paid <= 0.005 { continue }
                let from = try partner(p, "from_partner_id", "from_partner_name")
                let to = try partner(p, "to_partner_id", "to_partner_name")
                guard from != to, var kind = p["settlement_kind"]?.text, !kind.isEmpty else {
                    throw Block("转账关系或类别不完整：" + date)
                }
                if kind == "CUTOFF_LEGACY_INVALID" { continue }
                let standalone = number(p["settlement_id"]) == 0
                if !standalone {
                    guard let parent = dailyID, try identity(p, "settlement_id") == parent else {
                        throw Block("转账所属日结无法核对：" + date)
                    }
                }
                if standalone, let center {
                    if from != center && to != center { continue }
                    if ["CUTOFF","LEGACY","DAILY"].contains(kind) { kind = "CUTOFF_CENTER" }
                }
                active.insert(from)
                active.insert(to)
                if standalone {
                    flows[from, default: Flow()].sent += paid
                    flows[to, default: Flow()].received += paid
                    if amount - paid <= 0.005 && ["CUTOFF","CUTOFF_CENTER"].contains(kind) {
                        let focus = AndroidNumericIdentity.exact(p["focus_partner_id"])
                        if let focus {
                            guard focus == from || focus == to else { throw Block("结清对象与转账关系不符：" + date) }
                            flows[focus, default: Flow()].cutoff = true
                        } else {
                            guard number(p["focus_partner_id"]) == 0 else {
                                throw Block("结清对象字段缺失：" + date)
                            }
                            flows[from, default: Flow()].cutoff = true
                            flows[to, default: Flow()].cutoff = true
                        }
                    }
                } else {
                    flows[from, default: Flow()].dailySent += paid
                    flows[to, default: Flow()].dailyReceived += paid
                    flows[from, default: Flow()].hasDaily = true
                    flows[to, default: Flow()].hasDaily = true
                }
            }
            let round = TXFundAccumulator.roundMoney
            for (id, f) in flows {
                let raw = round(round(f.purchase) + round(f.expense) + round(f.profit) - round(f.receipt))
                ledger[id, default: []].append(.init(date: date, rawBalance: raw,
                    savedBalance: f.saved, dailyFullySettled: f.settled,
                    dailySent: f.dailySent, dailyReceived: f.dailyReceived, hasDailyTransfers: f.hasDaily,
                    standaloneSent: f.sent, standaloneReceived: f.received, cutoffCleared: f.cutoff))
            }
        }
        var balances: [Int64: Double] = [:]
        for id in active {
            balances[id] = try TXFundAccumulator.ledger(ledger[id] ?? []).last?.endBalance ?? 0
        }
        if let center {
            let otherBalances = balances.keys.sorted().filter { $0 != center }.compactMap { balances[$0] }
            balances[center] = try TXFundAccumulator.centerBalance(otherBalances)
        }
        return balances.keys.sorted().compactMap { id in
            guard let person = people[id], let balance = balances[id] else { return nil }
            return Row(id: person.syncID, name: person.name, balance: balance, isCenter: id == center)
        }
    }
}
