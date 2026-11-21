import SwiftUI

/// Presentation-only grouping of rows already persisted by Android/cloud sync.
/// Never recomputes settlement, profit allocation, transfer recommendations,
/// settled flags or partner balances.
struct SettlementTableDay: Identifiable {
    let date: String
    let sales: [StoreDailyRow]
    let purchases: [PurchaseOrderRow]
    let savedDaily: [FinanceDailyRow]
    let shares: [FinanceProfitRow]
    let funds: [FinanceSettlementPartnerRow]
    let transfers: [FinanceTransferRow]

    var id: String { date }
    var savedSalesTotal: Double? {
        sales.isEmpty ? nil : sales.reduce(0) { $0 + $1.revenue }
    }
    var savedStoreProfitTotal: Double? {
        sales.isEmpty ? nil : sales.reduce(0) { $0 + $1.profit }
    }
    var savedPurchaseTotal: Double? {
        purchases.isEmpty ? nil : purchases.reduce(0) { $0 + $1.totalCost }
    }
}

enum SettlementTablePolicy {
    /// Store and partner filters control which *dates* appear. Full-book daily
    /// settlement and purchase figures stay full-book: they must not be
    /// attributed to one store or partner without an authoritative mapping.
    static func rows(_ snapshot: ReadOnlySnapshot, since: String, until: String,
                     location: String = "", partner: String = "") -> [SettlementTableDay] {
        func inRange(_ date: String) -> Bool { date >= since && date <= until }
        let sales = snapshot.business.filter {
            inRange($0.date) && (location.isEmpty || $0.storeName == location)
        }
        let purchases = snapshot.purchases.filter { inRange($0.date) }
        let daily = snapshot.financeDaily.filter { inRange($0.date) }
        let shares = snapshot.financeProfitRows.filter {
            inRange($0.date) && (partner.isEmpty || $0.partner == partner)
        }
        let funds = snapshot.financeSettlementPartners.filter {
            inRange($0.date) && (partner.isEmpty || $0.partner == partner)
        }
        let transfers = snapshot.financeTransfers.filter {
            inRange($0.date) && (partner.isEmpty || $0.from == partner || $0.to == partner)
        }

        var dates = Set(sales.map(\.date) + purchases.map(\.date) +
                        daily.map(\.date) + shares.map(\.date) +
                        funds.map(\.date) + transfers.map(\.date))
        if !location.isEmpty {
            dates.formIntersection(Set(sales.map(\.date)))
        }
        if !partner.isEmpty {
            dates.formIntersection(Set(shares.map(\.date) +
                                       funds.map(\.date) + transfers.map(\.date)))
        }
        return dates.sorted(by: >).map { date in
            SettlementTableDay(
                date: date,
                sales: sales.filter { $0.date == date }
                    .sorted { $0.storeName == $1.storeName ? $0.id < $1.id : $0.storeName < $1.storeName },
                purchases: purchases.filter { $0.date == date }.sorted { $0.id < $1.id },
                savedDaily: daily.filter { $0.date == date }.sorted { $0.id < $1.id },
                shares: shares.filter { $0.date == date }.sorted { $0.id < $1.id },
                funds: funds.filter { $0.date == date }.sorted { $0.id < $1.id },
                transfers: transfers.filter { $0.date == date }.sorted { $0.id < $1.id }
            )
        }
    }
}

enum SettlementSnapshotState: Equatable {
    case missing
    case available
    case conflicting(Int)

    var label: String {
        switch self {
        case .missing: return "未同步正式日结"
        case .available: return "已同步日结记录"
        case .conflicting(let count): return "\(count) 份日结记录待核对"
        }
    }
}

extension SettlementTableDay {
    /// A daily amount is safe to show on the compact card only when there is
    /// precisely one authoritative cloud snapshot for that business date.
    /// Do not sum duplicate snapshots or substitute store-level profit.
    var snapshotState: SettlementSnapshotState {
        if savedDaily.isEmpty { return .missing }
        if savedDaily.count > 1 { return .conflicting(savedDaily.count) }
        return .available
    }

    var uniqueSnapshot: FinanceDailyRow? {
        savedDaily.count == 1 ? savedDaily[0] : nil
    }

    var includesPeriodTransfers: Bool {
        transfers.contains { $0.kind.uppercased() != "DAILY" }
    }
}

struct SettlementTableView: View {
    @EnvironmentObject private var model: LedgerViewModel
    @State private var selectedDate = Date()
    @State private var range = "近7天"
    @State private var location = ""
    @State private var partner = ""
    @State private var visibleDays = 14

    private var endDate: String { BusinessDates.key(selectedDate) }
    private var startDate: String {
        switch range {
        case "单日": return endDate
        case "近7天": return BusinessDates.daysAgo(6, from: selectedDate)
        case "近30天": return BusinessDates.daysAgo(29, from: selectedDate)
        default: return "0000-00-00"
        }
    }

    private var partnerNames: [String] {
        Array(Set(model.snapshot.financeProfitRows.map(\.partner) +
                  model.snapshot.financeSettlementPartners.map(\.partner) +
                  model.snapshot.financeTransfers.flatMap { [$0.from, $0.to] }))
            .filter { !$0.isEmpty && $0 != "未知" && $0 != "未知合伙人" }
            .sorted()
    }

    private var rows: [SettlementTableDay] {
        SettlementTablePolicy.rows(model.snapshot, since: startDate, until: endDate,
                                   location: location, partner: partner)
    }

    private var activeFilters: Bool { !location.isEmpty || !partner.isEmpty }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                HStack(spacing: 8) {
                    VStack(alignment: .leading, spacing: 3) {
                        Text("资金结算表").font(.title3.bold())
                        Text("天鲜果业 · 已保存云端数据")
                            .font(.caption2).foregroundColor(.secondary)
                    }
                    Spacer()
                    Label("只读", systemImage: "lock.shield")
                        .font(.caption.bold()).foregroundColor(TXTheme.primary)
                    Button {
                        Task { await model.refresh() }
                    } label: {
                        if model.isBusy { ProgressView() }
                        else { Image(systemName: "arrow.clockwise") }
                    }
                    .disabled(model.isBusy)
                    .accessibilityLabel("同步资金数据")
                }
                .padding(12)
                .background(TXTheme.header,
                            in: RoundedRectangle(cornerRadius: 12))

                TXDayBar(date: $selectedDate, label: "结算日期", allowFuture: false)
                    .onChange(of: selectedDate) { _ in visibleDays = 14 }
                Picker("日期范围", selection: $range) {
                    Text("单日").tag("单日")
                    Text("近7天").tag("近7天")
                    Text("近30天").tag("近30天")
                    Text("全部").tag("全部")
                }
                .pickerStyle(SegmentedPickerStyle())
                .onChange(of: range) { _ in visibleDays = 14 }

                HStack(spacing: 8) {
                    Picker("营业位置", selection: $location) {
                        Text("全部位置").tag("")
                        ForEach(model.snapshot.locations, id: \.self) { name in
                            Text(name).tag(name)
                        }
                    }
                    .pickerStyle(MenuPickerStyle())
                    .onChange(of: location) { _ in visibleDays = 14 }

                    Picker("合伙人", selection: $partner) {
                        Text("全部合伙人").tag("")
                        ForEach(partnerNames, id: \.self) { name in
                            Text(name).tag(name)
                        }
                    }
                    .pickerStyle(MenuPickerStyle())
                    .onChange(of: partner) { _ in visibleDays = 14 }

                    Spacer(minLength: 0)
                    if activeFilters {
                        Button("清除筛选") {
                            location = ""
                            partner = ""
                            visibleDays = 14
                        }
                        .font(.caption)
                    }
                }

                if activeFilters {
                    Label("筛选仅定位相关营业日。日结快照、采购成本仍为全账本金额，不分摊到单个位置或合伙人。",
                          systemImage: "info.circle")
                        .font(.caption2)
                        .foregroundColor(.orange)
                }

                if model.lastSyncAt == nil {
                    SettlementEmpty(title: "尚无同步结算数据",
                                    detail: "请点击上方同步。没有云端日结记录时不生成估算结算。")
                } else if rows.isEmpty {
                    SettlementEmpty(title: "当前条件下没有记录",
                                    detail: "未发现记录不代表当日利润、资金或转账金额为零。")
                } else {
                    HStack {
                        Text("\(rows.count) 个有记录日期")
                            .font(.caption.bold())
                        Spacer()
                        Text("\(rows.filter { $0.uniqueSnapshot != nil }.count) 日有唯一日结记录")
                            .font(.caption2).foregroundColor(.secondary)
                    }
                    LazyVStack(spacing: 10) {
                        ForEach(Array(rows.prefix(visibleDays))) { day in
                            SettlementDateCard(day: day, initiallyExpanded: range == "单日")
                                .id(day.date + "|" + range)
                        }
                    }
                    if rows.count > visibleDays {
                        Button {
                            visibleDays += 14
                        } label: {
                            Label("加载更多日期", systemImage: "chevron.down")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.bordered)
                    }
                }

                Text("只读展示已同步的营业、日结、合伙人利润分配和转账原始记录。日结状态指记录存在，不代表资金已结清；跨日转账不会按覆盖日期重复计入当天。")
                    .font(.caption2).foregroundColor(.secondary)
                SyncFooter()
            }.padding(12)
        }
        .navigationBarTitle("资金结算表", displayMode: .inline)
    }
}

private struct SettlementDateCard: View {
    let day: SettlementTableDay
    @State private var expanded: Bool

    init(day: SettlementTableDay, initiallyExpanded: Bool = false) {
        self.day = day
        _expanded = State(initialValue: initiallyExpanded)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Button {
                withAnimation(.easeInOut(duration: 0.18)) { expanded.toggle() }
            } label: {
                VStack(alignment: .leading, spacing: 9) {
                    HStack(spacing: 6) {
                        Text(day.date).font(.headline).foregroundColor(.primary)
                        Spacer(minLength: 4)
                        Text(day.snapshotState.label)
                            .font(.caption2)
                            .foregroundColor(day.uniqueSnapshot == nil ? .orange : TXTheme.primary)
                        Image(systemName: expanded ? "chevron.up" : "chevron.down")
                            .font(.caption).foregroundColor(.secondary)
                    }
                    if let snapshot = day.uniqueSnapshot {
                        HStack(spacing: 5) {
                            SettlementSummaryTile(label: "营业额", amount: snapshot.revenue)
                            SettlementSummaryTile(label: "采购额", amount: snapshot.purchase)
                            SettlementSummaryTile(label: "利润", amount: snapshot.profit,
                                                  emphasize: true)
                        }
                    } else {
                        HStack(spacing: 8) {
                            Image(systemName: "exclamationmark.circle")
                                .foregroundColor(.orange)
                            Text(day.savedDaily.isEmpty
                                 ? "缺少正式日结快照，展开可查看已有营业及资金记录"
                                 : "同一日期存在多份日结快照，须核对，不能自动求和")
                                .font(.caption).foregroundColor(.secondary)
                        }
                    }
                    HStack(spacing: 9) {
                        Text("\(day.sales.count) 条位置营业")
                        Text("\(day.funds.count) 条资金明细")
                        Text("\(day.transfers.count) 条转账记录")
                        Spacer(minLength: 0)
                    }
                    .font(.caption2).foregroundColor(.secondary)
                    if day.includesPeriodTransfers {
                        Label("含周期或资金中心转账，展开核对覆盖日期",
                              systemImage: "calendar.badge.clock")
                            .font(.caption2).foregroundColor(.orange)
                    }
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(day.date + "，" + day.snapshotState.label +
                                (expanded ? "，收起明细" : "，展开明细"))

            if expanded {
                Divider()
                SettlementDateDetail(day: day)
            }
        }
        .padding(12)
        .background(Color(UIColor.secondarySystemGroupedBackground),
                    in: RoundedRectangle(cornerRadius: 12))
    }
}

private struct SettlementSummaryTile: View {
    let label: String
    let amount: Double
    var emphasize = false

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(label).font(.caption2).foregroundColor(.secondary)
            Text(settleYuan(amount))
                .font(.subheadline.bold())
                .foregroundColor(emphasize
                                 ? (amount < 0 ? .red : TXTheme.primary) : .primary)
                .lineLimit(1)
                .minimumScaleFactor(0.62)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(8)
        .background(Color(UIColor.tertiarySystemGroupedBackground),
                    in: RoundedRectangle(cornerRadius: 9))
    }
}

private struct SettlementDateDetail: View {
    let day: SettlementTableDay

    var body: some View {
        VStack(alignment: .leading, spacing: 9) {
            Text("位置营业记录").font(.subheadline.bold())
            if day.sales.isEmpty {
                Text("没有已同步的位置营业明细")
                    .font(.caption).foregroundColor(.secondary)
            } else {
                ForEach(day.sales) { sale in
                    HStack {
                        Text(sale.storeName).font(.subheadline)
                        Spacer(minLength: 4)
                        VStack(alignment: .trailing, spacing: 2) {
                            Text("营业 " + settleYuan(sale.revenue))
                                .font(.caption)
                            Text("位置已存利润 " + settleYuan(sale.profit))
                                .font(.caption2).foregroundColor(.secondary)
                        }
                    }
                }
                if let amount = day.savedSalesTotal {
                    SettlementValueLine(label: "位置营业记录合计", amount: amount)
                }
                if let profit = day.savedStoreProfitTotal {
                    SettlementValueLine(label: "位置已存利润合计", amount: profit)
                }
            }

            Divider()
            Text("采购记录 · 按采购自然日").font(.subheadline.bold())
            if let amount = day.savedPurchaseTotal {
                SettlementValueLine(label: "已同步采购订单合计（全账本）", amount: amount)
                Text("\(day.purchases.count) 笔采购订单，不按位置或合伙人分摊")
                    .font(.caption2).foregroundColor(.secondary)
            } else {
                Text("没有已同步的采购订单；不能认定采购额为零")
                    .font(.caption).foregroundColor(.secondary)
            }

            Divider()
            Text("正式日结原始快照 · 全账本").font(.subheadline.bold())
            if day.savedDaily.isEmpty {
                Text("未同步正式日结快照；以上营业与采购金额不作为资金结算。")
                    .font(.caption).foregroundColor(.orange)
            } else {
                if day.savedDaily.count > 1 {
                    Label("存在多份同日日结快照，逐条展示、不合并。",
                          systemImage: "exclamationmark.triangle")
                        .font(.caption).foregroundColor(.orange)
                }
                ForEach(day.savedDaily) { row in
                    VStack(alignment: .leading, spacing: 5) {
                        SettlementValueLine(label: "营业额", amount: row.revenue)
                        SettlementValueLine(label: "采购金额", amount: row.purchase)
                        SettlementValueLine(label: "其他费用", amount: row.expense)
                        SettlementValueLine(label: "保存的利润", amount: row.profit)
                    }
                    .padding(9)
                    .background(Color(UIColor.tertiarySystemGroupedBackground),
                                in: RoundedRectangle(cornerRadius: 9))
                }
            }

            Divider()
            Text("合伙人资金轧差 · 原始记录").font(.subheadline.bold())
            if day.funds.isEmpty {
                Text("没有已同步的合伙人资金日结记录")
                    .font(.caption).foregroundColor(.secondary)
            } else {
                ForEach(day.funds) { item in
                    DisclosureGroup {
                        VStack(alignment: .leading, spacing: 6) {
                            SettlementValueLine(label: "实际收款", amount: item.revenueReceived)
                            SettlementValueLine(label: "采购垫付", amount: item.purchasePaid)
                            SettlementValueLine(label: "费用垫付", amount: item.expensePaid)
                            SettlementValueLine(label: "已保存利润份额", amount: item.profitShare)
                            SettlementValueLine(label: "应留存款", amount: item.shouldKeep)
                            SettlementValueLine(label: "本日资金差额", amount: item.balance)
                        }
                        .padding(.top, 6)
                    } label: {
                        HStack {
                            Text(item.partner).font(.subheadline.bold())
                            Spacer(minLength: 3)
                            Text(settleYuan(item.balance))
                                .font(.caption.bold())
                                .foregroundColor(item.balance < 0 ? .red : .primary)
                        }
                    }
                    .padding(9)
                    .background(Color(UIColor.tertiarySystemGroupedBackground),
                                in: RoundedRectangle(cornerRadius: 9))
                }
                Text("本日资金差额不是跨日累计余额，也不表示已结清。")
                    .font(.caption2).foregroundColor(.secondary)
            }

            Divider()
            Text("实际利润分配 · 原始记录").font(.subheadline.bold())
            if day.shares.isEmpty {
                Text("没有已同步的分配记录，不自动推算")
                    .font(.caption).foregroundColor(.secondary)
            } else {
                ForEach(day.shares) { item in
                    VStack(alignment: .leading, spacing: 3) {
                        SettlementValueLine(label: item.partner, amount: item.allocated)
                        Text("云端比例值：" + item.ratio.formatted() +
                             (item.role.isEmpty ? "" : "  ·  角色：" + item.role))
                            .font(.caption2).foregroundColor(.secondary)
                    }
                    .padding(.vertical, 2)
                }
                Text("ratio 原样显示，不擅自按百分比转换。")
                    .font(.caption2).foregroundColor(.secondary)
            }

            Divider()
            Text("转账方案及登记记录 · 原始记录").font(.subheadline.bold())
            if day.transfers.isEmpty {
                Text("没有已同步转账记录，不自行计算最少转账或已结清。")
                    .font(.caption).foregroundColor(.secondary)
            } else {
                ForEach(day.transfers) { item in
                    VStack(alignment: .leading, spacing: 5) {
                        Text(item.from + " → " + item.to)
                            .font(.subheadline.bold())
                        Text(item.kindLabel + " · 记录日 " + item.date)
                            .font(.caption2).foregroundColor(.secondary)
                        if let period = item.periodLabel {
                            Text(period + (item.businessDayCount > 0
                                 ? " · \(item.businessDayCount) 个营业日" : ""))
                                .font(.caption2).foregroundColor(.secondary)
                        } else if item.kind.uppercased() != "DAILY" {
                            Text("没有完整覆盖日期，不应视为单日转账。")
                                .font(.caption2).foregroundColor(.orange)
                        }
                        SettlementValueLine(label: "方案金额", amount: item.amount)
                        SettlementValueLine(label: "已登记结算（不代表银行到账）",
                                            amount: item.recordedSettled)
                    }
                    .padding(9)
                    .background(Color(UIColor.tertiarySystemGroupedBackground),
                                in: RoundedRectangle(cornerRadius: 9))
                }
            }
        }
    }
}

private struct SettlementValueLine: View {
    let label: String
    let amount: Double

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Text(label).font(.caption).foregroundColor(.secondary)
            Spacer(minLength: 3)
            Text(settleYuan(amount))
                .font(.subheadline.bold())
                .foregroundColor(amount < 0 ? .red : .primary)
                .lineLimit(1).minimumScaleFactor(0.7)
        }
    }
}

private struct SettlementEmpty: View {
    let title: String
    let detail: String

    var body: some View {
        VStack(spacing: 8) {
            Image(systemName: "doc.text.magnifyingglass")
                .font(.title2).foregroundColor(.secondary)
            Text(title).font(.subheadline.bold())
            Text(detail).font(.caption).foregroundColor(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity).padding(24)
        .background(Color(UIColor.secondarySystemGroupedBackground),
                    in: RoundedRectangle(cornerRadius: 12))
    }
}

private func settleYuan(_ amount: Double) -> String {
    amount.formatted(.currency(code: "CNY"))
}
