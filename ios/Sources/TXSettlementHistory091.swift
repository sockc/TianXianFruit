import SwiftUI

/// Settlement history is read-only. Group daily records using their unique
/// saved parent ID, and standalone records using the actual batch key.
/// Do not classify a legacy standalone DAILY label as a daily settlement.
enum TXSettlementHistory091Policy {
    enum State: String, CaseIterable {
        case full = "已登记全额"
        case partial = "部分结算"
        case pending = "未结算"
        case cancelled = "已撤销"
        case invalid = "旧版无效"
        case review = "待核对"
    }

    struct Entry: Identifiable {
        let id: String
        let date: String
        let kind: String
        let period: String
        let focus: String
        let transfers: [FinanceTransferRow]
        let planned: Double?
        let registered: Double?
        let state: State
        let confirmedAt: Double
        let note: String
        let daily: Bool

        var isHistory: Bool {
            if daily { return transfers.contains { $0.recordedSettled > 0.005 } }
            return transfers.contains { $0.recordedSettled > 0.005 } ||
                (["CUTOFF", "CUTOFF_CENTER", "CUTOFF_LEGACY_INVALID"].contains(kind) &&
                 confirmedAt > 0)
        }

        func matches(_ partner: String) -> Bool {
            partner.isEmpty || focus == partner ||
                transfers.contains { $0.from == partner || $0.to == partner ||
                    $0.focusPartnerName == partner }
        }
    }

    private struct Item {
        let event: SyncEvent
        let row: FinanceTransferRow
        let moneyValid: Bool
    }

    private static func numericID(_ p: [String: JSONValue], _ key: String) -> Int64? {
        AndroidNumericIdentity.exact(p[key])
    }

    private static func amount(_ p: [String: JSONValue], _ key: String) -> Double? {
        TXFundDataAdapter.number(p[key])
    }

    private static func centerID(_ all: [SyncEvent]) -> Int64? {
        let people = all.filter { $0.tableName == "partner" }
        var enabled = Set<Int64>()
        var seen = Set<Int64>()
        for p in people {
            guard let id = numericID(p.payload, "id"), seen.insert(id).inserted,
                  let flag = amount(p.payload, "enabled"), flag == 0 || flag == 1
            else { return nil }
            if flag == 1 { enabled.insert(id) }
        }
        var rules: [(id: Int64, person: Int64, center: Bool)] = []
        for r in all where r.tableName == "profit_rule" {
            guard let person = numericID(r.payload, "partner_id"),
                  seen.contains(person) else { return nil }
            guard enabled.contains(person) else { continue }
            guard let id = numericID(r.payload, "id"),
                  let flag = amount(r.payload, "is_settlement_center"),
                  flag == 0 || flag == 1 else { return nil }
            rules.append((id, person, flag == 1))
        }
        guard Set(rules.map { $0.id }).count == rules.count else { return nil }
        return rules.filter { $0.center }.max { $0.id < $1.id }?.person ??
            rules.min { $0.id < $1.id }?.person ?? enabled.min()
    }

    static func entries(_ snapshot: ReadOnlySnapshot, since: String, until: String,
                        partner: String = "") -> [Entry] {
        let all = Array(snapshot.records.values)
        let center = centerID(all)
        let parents = all.filter { $0.tableName == "daily_cash_settlement" }
        let parentsByDate = Dictionary(grouping: parents) { $0.payload["date"]?.text ?? "" }
        var parentIDs: [Int64: [SyncEvent]] = [:]
        for p in parents {
            if let id = numericID(p.payload, "id") {
                parentIDs[id, default: []].append(p)
            }
        }
        var dailyGroups: [String: [Item]] = [:]
        var loose: [String: [Item]] = [:]
        var unresolved: [Item] = []
        for e in all where e.tableName == "settlement_transfer" {
            guard let row = FinanceTransferRow(e), row.date >= since, row.date <= until else { continue }
            let a = amount(e.payload, "amount"), b = amount(e.payload, "settled_amount")
            let valid = a != nil && b != nil &&
                TXSettlementRecordPolicy.transferRegistration(row) != .inconsistent
            let item = Item(event: e, row: row, moneyValid: valid)
            if amount(e.payload, "settlement_id") == 0 {
                let key = row.batchKey.isEmpty ? "single|" + row.id : "batch|" + row.batchKey
                loose[key, default: []].append(item)
            } else if let id = numericID(e.payload, "settlement_id"),
                      let candidates = parentIDs[id], candidates.count == 1,
                      let parent = candidates.first,
                      parent.payload["date"]?.text == row.date,
                      parentsByDate[row.date]?.count == 1 {
                dailyGroups[parent.syncId, default: []].append(item)
            } else {
                unresolved.append(item)
            }
        }
        var result: [Entry] = []
        for p in parents {
            guard let date = p.payload["date"]?.text, date >= since, date <= until else { continue }
            let items = dailyGroups[p.syncId] ?? []
            let unique = parentsByDate[date]?.count == 1 &&
                (numericID(p.payload, "id").map { parentIDs[$0]?.count == 1 } ?? false)
            let missing = unresolved.contains { $0.row.date == date }
            let stamp = max(amount(p.payload, "confirmed_at") ?? 0,
                            amount(p.payload, "updated_at") ?? 0)
            result.append(make(id: "daily|" + p.syncId, date: date, kind: "DAILY",
                period: date, focus: "", items: items, daily: true,
                confirmed: stamp, forced: (!unique || missing || items.isEmpty) ? .review : nil,
                note: (!unique || missing) ? "日结身份或子记录归属存在歧义" :
                    (items.isEmpty ? "未同步转账明细，不据此推断结清" : "")))
        }
        for (key, items) in loose {
            let ordered = items.sorted { $0.row.id < $1.row.id }
            guard let first = ordered.first else { continue }
            let rows = ordered.map { $0.row }
            let same = rows.allSatisfy {
                $0.date == first.row.date && $0.kind == first.row.kind &&
                $0.rangeStartDate == first.row.rangeStartDate &&
                $0.rangeEndDate == first.row.rangeEndDate &&
                $0.focusPartnerName == first.row.focusPartnerName
            }
            var kind = first.row.kind
            var force: State? = same ? nil : .review
            var note = same ? "" : "同一批次的日期、类型或覆盖期间不一致"
            let stamp = rows.map { $0.confirmedAt }.max() ?? 0
            if same, kind == "CUTOFF_LEGACY_INVALID" { force = .invalid }
            else if same, let center {
                let links = ordered.map { item -> Bool? in
                    guard let from = numericID(item.event.payload, "from_partner_id"),
                          let to = numericID(item.event.payload, "to_partner_id") else { return nil }
                    return from == center || to == center
                }
                if links.contains(where: { $0 == nil }) {
                    force = .review; note = "转账身份缺失，不能确认资金中心归属"
                } else if links.allSatisfy({ $0 == false }) {
                    if stamp > 0 || rows.contains(where: { $0.recordedSettled > 0.005 }) {
                        force = .invalid; kind = "CUTOFF_LEGACY_INVALID"
                        note = "按当前资金中心规则，此旧账不进入余额"
                    }
                } else if links.contains(where: { $0 == false }) {
                    force = .review; note = "批次内同时含资金中心与非中心转账"
                } else if ["DAILY", "LEGACY", "CUTOFF"].contains(kind),
                          stamp > 0 || rows.contains(where: { $0.recordedSettled > 0.005 }) {
                    kind = "CUTOFF_CENTER"
                }
            } else if same && center == nil {
                force = .review
                note = "资金中心身份未确认，保留原始历史"
            }
            let period = first.row.periodLabel ?? first.row.date
            result.append(make(id: key, date: first.row.date, kind: kind,
                period: period, focus: first.row.focusPartnerName, items: ordered,
                daily: false, confirmed: stamp, forced: force, note: note))
        }
        for item in unresolved {
            result.append(make(id: "unresolved|" + item.row.id, date: item.row.date,
                kind: item.row.kind, period: item.row.periodLabel ?? item.row.date,
                focus: item.row.focusPartnerName, items: [item], daily: false,
                confirmed: item.row.confirmedAt, forced: .review,
                note: "日结父记录缺失或编号冲突；未合并到其他批次"))
        }
        return result.filter { $0.matches(partner) }.sorted {
            if $0.date != $1.date { return $0.date > $1.date }
            if $0.confirmedAt != $1.confirmedAt { return $0.confirmedAt > $1.confirmedAt }
            return $0.id < $1.id
        }
    }

    private static func make(id: String, date: String, kind: String, period: String,
                             focus: String, items: [Item], daily: Bool, confirmed: Double,
                             forced: State?, note: String) -> Entry {
        let rows = items.map { $0.row }.sorted { $0.id < $1.id }
        let valid = !items.isEmpty && items.allSatisfy { $0.moneyValid }
        let planned = valid ? TXFundAccumulator.roundMoney(rows.reduce(0) { $0 + $1.amount }) : nil
        let paid = valid ? TXFundAccumulator.roundMoney(rows.reduce(0) { $0 + $1.recordedSettled }) : nil
        let state: State
        if !valid { state = .review }
        else if let forced { state = forced }
        else if ["CUTOFF", "CUTOFF_CENTER"].contains(kind),
                confirmed > 0, let paid, paid <= 0.005 { state = .cancelled }
        else if let planned, let paid {
            state = paid + 0.005 >= planned ? .full : (paid > 0.005 ? .partial : .pending)
        } else { state = .review }
        return Entry(id: id, date: date, kind: kind, period: period, focus: focus,
                     transfers: rows, planned: planned, registered: paid, state: state,
                     confirmedAt: max(confirmed, rows.map { $0.confirmedAt }.max() ?? 0),
                     note: valid ? note : (note.isEmpty ? "金额缺失或登记异常" : note),
                     daily: daily)
    }
}

struct TXSettlementHistory091Panel: View {
    @EnvironmentObject private var model: LedgerViewModel
    @Binding var date: Date
    let onDaily: () -> Void
    let onFunds: () -> Void
    @State private var period = "近30天"
    @State private var category = "全部"
    @State private var status = ""
    @State private var partner = ""
    @State private var limit = 20

    private var end: String { BusinessDates.key(date) }
    private var start: String {
        switch period {
        case "近7天": return BusinessDates.daysAgo(6, from: date)
        case "近30天": return BusinessDates.daysAgo(29, from: date)
        default: return "0000-00-00"
        }
    }
    private var entries: [TXSettlementHistory091Policy.Entry] {
        TXSettlementHistory091Policy.entries(model.snapshot, since: start, until: end,
                                             partner: partner)
    }
    private var filtered: [TXSettlementHistory091Policy.Entry] {
        entries.filter { entry in
            let categoryMatch = category == "全部" ||
                (category == "当日" && entry.daily) ||
                (category == "周期/轧差" && !entry.daily)
            return categoryMatch && (status.isEmpty || entry.state.rawValue == status)
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            TXDayBar(date: $date, label: "记录截止日期", allowFuture: false)
            Picker("历史范围", selection: $period) {
                Text("近7天").tag("近7天")
                Text("近30天").tag("近30天")
                Text("全部").tag("全部")
            }.pickerStyle(.segmented)
            Picker("记录类型", selection: $category) {
                Text("全部").tag("全部")
                Text("当日").tag("当日")
                Text("周期/轧差").tag("周期/轧差")
            }.pickerStyle(.segmented)
            HStack {
                Picker("合伙人", selection: $partner) {
                    Text("全部合伙人").tag("")
                    ForEach(TXFundHistoryPolicy.partnerNames(model.snapshot), id: \.self) {
                        Text($0).tag($0)
                    }
                }.pickerStyle(.menu)
                Spacer()
                Picker("状态", selection: $status) {
                    Text("全部状态").tag("")
                    ForEach(TXSettlementHistory091Policy.State.allCases, id: \.rawValue) {
                        Text($0.rawValue).tag($0.rawValue)
                    }
                }.pickerStyle(.menu)
            }
            if model.lastSyncAt == nil {
                Label("尚无云端同步记录", systemImage: "icloud.slash")
                    .font(.caption).foregroundColor(.orange)
            } else {
                let recent = entries.first { !$0.daily && $0.isHistory && $0.state != .invalid }
                VStack(alignment: .leading, spacing: 5) {
                    Text("最近资金轧差记录（所选范围）").font(.subheadline.bold())
                    if let recent {
                        Text(recent.date + " · " + recent.state.rawValue)
                            .font(.caption)
                        if let value = recent.registered {
                            Text("已登记 " + value.formatted(.currency(code: "CNY")))
                                .font(.caption.bold())
                        }
                    } else {
                        Text("未发现符合条件的周期记录")
                            .font(.caption).foregroundColor(.secondary)
                    }
                    Button("查看截至所选日期资金余额", action: onFunds)
                        .font(.caption.bold())
                    Text("转账登记不等于累计结清；以资金余额核对为准。")
                        .font(.caption2).foregroundColor(.secondary)
                }
                .padding(10)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(TXTheme.header, in: RoundedRectangle(cornerRadius: 10))
                HStack {
                    Text("\(filtered.count) 条记录").font(.caption.bold())
                    Spacer()
                    Text(partner.isEmpty ? "按批次展示" : "显示相关完整批次")
                        .font(.caption2).foregroundColor(.secondary)
                }
                if filtered.isEmpty {
                    Text("当前筛选没有结算记录")
                        .font(.subheadline).foregroundColor(.secondary)
                        .padding(.vertical, 12)
                }
                LazyVStack(spacing: 8) {
                    ForEach(Array(filtered.prefix(limit))) { entry in
                        TXSettlementHistory091Card(entry: entry) {
                            if let selected = BusinessDates.parse(entry.date) {
                                date = selected
                                onDaily()
                            }
                        }
                    }
                }
                if filtered.count > limit {
                    Button("加载更多记录") { limit += 20 }
                        .frame(maxWidth: .infinity)
                }
            }
        }
        .onChange(of: end) { _ in limit = 20 }
        .onChange(of: period) { _ in limit = 20 }
        .onChange(of: category) { _ in limit = 20 }
        .onChange(of: status) { _ in limit = 20 }
        .onChange(of: partner) { _ in limit = 20 }
        .onChange(of: model.selectedBookID) { _ in
            partner = ""; status = ""; limit = 20
        }
    }
}

private struct TXSettlementHistory091Card: View {
    let entry: TXSettlementHistory091Policy.Entry
    let openDaily: () -> Void
    @State private var expanded = false

    private var title: String {
        if entry.daily { return "当日结算" }
        switch entry.kind {
        case "CUTOFF_CENTER", "CUTOFF": return "周期资金结清"
        case "CUTOFF_LEGACY_INVALID": return "旧版无效结算"
        default: return "资金轧差 / 历史转账"
        }
    }

    private func money(_ label: String, _ value: Double?) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(label).font(.caption2).foregroundColor(.secondary)
            Text(value.map { $0.formatted(.currency(code: "CNY")) } ?? "待核对")
                .font(.subheadline.bold()).lineLimit(1).minimumScaleFactor(0.65)
        }.frame(maxWidth: .infinity, alignment: .leading)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 7) {
            Button {
                withAnimation { expanded.toggle() }
            } label: {
                VStack(alignment: .leading, spacing: 6) {
                    HStack {
                        Text(entry.date).font(.subheadline.bold()).foregroundColor(.primary)
                        Spacer()
                        Text(entry.state.rawValue).font(.caption.bold())
                            .foregroundColor(entry.state == .full ? TXTheme.primary : .orange)
                        Image(systemName: expanded ? "chevron.up" : "chevron.down")
                            .font(.caption).foregroundColor(.secondary)
                    }
                    Text(title + (entry.focus.isEmpty ? "" : " · " + entry.focus))
                        .font(.caption).foregroundColor(.secondary)
                    Text(entry.period).font(.caption2).foregroundColor(.secondary)
                    HStack(spacing: 4) {
                        money("方案金额", entry.planned)
                        money("已登记", entry.registered)
                        money("未登记差额", entry.planned.flatMap { total in
                            entry.registered.map { max(0, TXFundAccumulator.roundMoney(total - $0)) }
                        })
                    }
                    .foregroundColor(.primary)
                }
            }.buttonStyle(.plain)
            if !entry.note.isEmpty {
                Text(entry.note).font(.caption2).foregroundColor(.orange)
            }
            if expanded {
                Divider()
                ForEach(entry.transfers) { row in
                    VStack(alignment: .leading, spacing: 4) {
                        Text(row.from + " → " + row.to).font(.caption.bold())
                        if entry.planned != nil {
                            HStack {
                                Text("方案 " + row.amount.formatted(.currency(code: "CNY")))
                                Spacer()
                                Text("已登记 " + row.recordedSettled.formatted(.currency(code: "CNY")))
                            }.font(.caption2)
                        } else {
                            Text("原始金额待核对").font(.caption2).foregroundColor(.orange)
                        }
                    }
                    .padding(.vertical, 3)
                }
                if entry.daily {
                    Button("查看当日计算", action: openDaily).font(.caption.bold())
                }
            }
        }
        .padding(10)
        .background(Color(UIColor.secondarySystemGroupedBackground),
                    in: RoundedRectangle(cornerRadius: 10))
    }
}
