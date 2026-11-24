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
    /// Later period settlements that may cover this date. Not effective status.
    let coveringCutoffs: [FinanceTransferRow]

    var hasPotentialPeriodCoverage: Bool { !coveringCutoffs.isEmpty }
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
                transfers: transfers.filter { $0.date == date }.sorted { $0.id < $1.id },
                coveringCutoffs: snapshot.financeTransfers.filter {
                    TXSettlementRecordPolicy.isPotentialCutoff(
                        $0, on: date, partner: partner)
                }.sorted { $0.date == $1.date ? $0.id < $1.id : $0.date > $1.date }
            )
        }
    }
}

/// Counts only saved DAILY transfer registration, not cross-day effective
/// settlement, bank payment, or newly calculated transfer recommendations.
struct TXDailyTransferReadout: Equatable {
    let total: Int
    let full: Int
    let partial: Int
    let pending: Int
    let invalid: Int

    init(transfers: [FinanceTransferRow]) {
        let daily = transfers.filter { $0.kind.uppercased() == "DAILY" }
        total = daily.count
        let states = daily.map(TXSettlementRecordPolicy.transferRegistration)
        full = states.filter { $0 == .fullyRegistered }.count
        partial = states.filter { $0 == .partiallyRegistered }.count
        pending = states.filter { $0 == .notRegistered }.count
        invalid = states.filter { $0 == .inconsistent }.count
    }

    var label: String {
        "当日转账原始登记：\(total) 笔 · 全额 \(full) · 部分 \(partial) · 未登记 \(pending)" +
            (invalid > 0 ? " · 异常 \(invalid)" : "")
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

    var dailyTransferReadout: TXDailyTransferReadout {
        TXDailyTransferReadout(transfers: transfers)
    }
}

struct SettlementTableView: View {
    @EnvironmentObject private var model: LedgerViewModel
    @State private var selectedDate = Date()
    @State private var range = "单日"
    @State private var showFilters = false
    @State private var location = ""
    @State private var partner = ""
    @State private var visibleDays = 14
    @State private var settlementSection = "当日计算"

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
                        Text("结算表").font(.title3.bold())
                        Text("当日计算优先 · 云端已保存数据")
                            .font(.caption2).foregroundColor(.secondary)
                    }
                    Spacer()
                    if model.selectedBook?.name.hasPrefix("iOS_TEST_") != true {
                        Label("只读", systemImage: "lock.shield")
                            .font(.caption2).foregroundColor(.secondary)
                    }
                    Button {
                        Task { await model.refresh() }
                    } label: {
                        if model.isBusy { ProgressView() }
                        else { Image(systemName: "arrow.clockwise") }
                    }
                    .disabled(model.isBusy)
                    .accessibilityLabel("同步资金数据")
                }
                .padding(9)
                .background(TXTheme.header,
                            in: RoundedRectangle(cornerRadius: 12))

                NavigationLink(destination: TXFundHistoryView()) {
                    HStack {
                        Label("资金余额与结清历史", systemImage: "clock.arrow.circlepath")
                            .font(.subheadline.bold())
                        Spacer()
                        Text("逐日明细 · 周期记录").font(.caption2)
                            .foregroundColor(.secondary)
                        Image(systemName: "chevron.right").font(.caption)
                    }
                    .padding(11)
                    .background(Color(UIColor.secondarySystemGroupedBackground),
                                in: RoundedRectangle(cornerRadius: 11))
                }
                if TXTestBookAccess.isCandidate(model.selectedBook,
                                                 account: model.account,
                                                 offline: model.isUsingCachedAuthorization) {
                    NavigationLink(destination: TXSettlementTestWorkbenchView(initialDate: selectedDate)) {
                        HStack(spacing: 8) {
                            Label("办理已保存转账", systemImage: "checkmark.circle")
                                .font(.subheadline.bold())
                            Spacer()
                            Text("已保存方案")
                                .font(.caption2).foregroundColor(.secondary)
                        }
                        .padding(10)
                        .background(TXTheme.header,
                                    in: RoundedRectangle(cornerRadius: 10))
                    }
                }
                Picker("结算模块", selection: $settlementSection) {
                    Text("当日计算").tag("当日计算")
                    Text("资金余额").tag("资金余额")
                    Text("结算记录").tag("结算记录")
                }
                .pickerStyle(.segmented)

                if settlementSection == "当日计算" {
                Text("本页直接展示安卓同步的日结快照、利润分配及转账登记；不重新推算未保存的资金方案。")
                    .font(.caption2).foregroundColor(.secondary)
                TXDayBar(date: $selectedDate, label: "结算日期", allowFuture: false)
                    .onChange(of: selectedDate) { _ in visibleDays = 14 }
                DisclosureGroup(isExpanded: $showFilters) {
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

                } label: {
                    Label("日期范围与位置筛选", systemImage: "line.3.horizontal.decrease")
                        .font(.caption)
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

                } else if settlementSection == "资金余额" {
                    TXFundOverviewPanel()
                } else {
                    VStack(alignment: .leading, spacing: 10) {
                        Label("结算记录（逐步移植）", systemImage: "clock.arrow.circlepath")
                            .font(.headline)
                        Text("可查看已同步的当日转账、周期结清和历史登记；已登记不等于最终结清。")
                            .font(.subheadline).foregroundColor(.secondary)
                        NavigationLink(destination: TXFundHistoryView()) {
                            Label("查看已有结算历史", systemImage: "arrow.right.circle")
                        }
                        .buttonStyle(.bordered)
                    }
                    .padding(12)
                    .background(Color(UIColor.secondarySystemGroupedBackground),
                                in: RoundedRectangle(cornerRadius: 12))
                }
                // Recorded transfers are not effective fund balances.
                SyncFooter()
            }.padding(12)
        }
        .navigationBarTitle("结算", displayMode: .inline)
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
                        HStack(spacing: 5) {
                            Image(systemName: "doc.text")
                            Text("云端" + TXRecordedSettlementState.of(snapshot).label)
                            Spacer()
                            Text("非最终有效状态")
                        }
                        .font(.caption2)
                        .foregroundColor(.secondary)
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
                    if !day.shares.isEmpty || !day.funds.isEmpty {
                        VStack(alignment: .leading, spacing: 5) {
                            Text("合伙人已保存明细（不是跨日余额）")
                                .font(.caption2.bold()).foregroundColor(.secondary)
                            ForEach(Array(day.shares.prefix(2))) { row in
                                HStack {
                                    Text(row.partner + " · 实际利润分配")
                                        .lineLimit(1).minimumScaleFactor(0.8)
                                    Spacer(minLength: 4)
                                    Text(settleYuan(row.allocated))
                                        .fontWeight(.medium)
                                }
                                .font(.caption2).foregroundColor(.primary)
                            }
                            ForEach(Array(day.funds.prefix(2))) { row in
                                HStack {
                                    Text(row.partner + " · 本日资金差额")
                                        .lineLimit(1).minimumScaleFactor(0.8)
                                    Spacer(minLength: 4)
                                    Text(settleYuan(row.balance))
                                        .fontWeight(.medium)
                                        .foregroundColor(row.balance < 0 ? .red : .primary)
                                }
                                .font(.caption2)
                            }
                            if day.shares.count > 2 || day.funds.count > 2 {
                                Text("其余合伙人明细请展开查看")
                                    .font(.caption2).foregroundColor(.secondary)
                            }
                        }
                        .padding(8)
                        .background(Color(UIColor.tertiarySystemGroupedBackground),
                                    in: RoundedRectangle(cornerRadius: 9))
                    }
                    HStack(spacing: 9) {
                        Text("\(day.sales.count) 条位置营业")
                        Text("\(day.funds.count) 条资金明细")
                        Text("\(day.transfers.count) 条转账记录")
                        Spacer(minLength: 0)
                    }
                    .font(.caption2).foregroundColor(.secondary)
                    if day.dailyTransferReadout.total > 0 {
                        Text(day.dailyTransferReadout.label)
                            .font(.caption2)
                            .foregroundColor(day.dailyTransferReadout.invalid > 0
                                             ? .orange : .secondary)
                        Text("仅原始登记进度，不代表已实际到账或跨日结清。")
                            .font(.caption2).foregroundColor(.secondary)
                    }
                    if day.includesPeriodTransfers {
                        Label("本日有周期或资金中心转账记录，需核对覆盖日期",
                              systemImage: "calendar.badge.clock")
                            .font(.caption2).foregroundColor(.orange)
                    }
                    if day.hasPotentialPeriodCoverage {
                        Label("另有 " + String(day.coveringCutoffs.count) +
                              " 笔已登记周期结清可能覆盖本日，最终状态以安卓有效状态为准",
                              systemImage: "exclamationmark.circle")
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
                        HStack {
                            Text("原始结算状态")
                                .font(.caption).foregroundColor(.secondary)
                            Spacer()
                            Text(TXRecordedSettlementState.of(row).label)
                                .font(.caption.bold())
                                .foregroundColor(.secondary)
                        }
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
            Text("当日转账方案 · 逐笔登记进度").font(.subheadline.bold())
            let dailyTransfers = day.transfers.filter {
                $0.kind.uppercased() == "DAILY"
            }
            if dailyTransfers.isEmpty {
                Text("没有已同步的当日转账方案，不能据此判断已结清。")
                    .font(.caption).foregroundColor(.secondary)
            } else {
                ForEach(dailyTransfers) { item in
                    SettlementTransferDetailRow(item: item)
                }
            }

            Divider()
            Text("周期 / 历史转账记录").font(.subheadline.bold())
            let standalone = day.transfers.filter {
                $0.kind.uppercased() != "DAILY"
            }
            if standalone.isEmpty {
                Text("本日没有已同步的周期或历史转账。")
                    .font(.caption).foregroundColor(.secondary)
            } else {
                ForEach(standalone) { item in
                    SettlementTransferDetailRow(item: item)
                }
            }
            if day.hasPotentialPeriodCoverage {
                Divider()
                Label("可能覆盖本日的已登记跨日结清（仅作核对提醒）",
                      systemImage: "calendar.badge.exclamationmark")
                    .font(.subheadline.bold()).foregroundColor(.orange)
                ForEach(day.coveringCutoffs) { cutoff in
                    HStack(alignment: .top) {
                        Text(cutoff.date + " · " + cutoff.from + " → " + cutoff.to)
                            .font(.caption)
                        Spacer(minLength: 4)
                        Text("登记 " + settleYuan(cutoff.recordedSettled))
                            .font(.caption.bold())
                    }
                }
                Text("这里只显示符合日期与登记证据的可能关联，不表示该日所有合伙人已结清。最终有效状态仍由安卓资金中心与周期结清算法决定。")
                    .font(.caption2).foregroundColor(.secondary)
            }
        }
    }
}

private struct SettlementTransferDetailRow: View {
    let item: FinanceTransferRow

    private var registration: TXTransferRegistration {
        TXSettlementRecordPolicy.transferRegistration(item)
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                Text(item.from + " → " + item.to)
                    .font(.subheadline.bold()).lineLimit(2)
                Spacer(minLength: 3)
                Text(registration.label)
                    .font(.caption2.bold())
                    .foregroundColor(registration == .inconsistent ? .red : .secondary)
            }
            Text(item.kindLabel + " · 记录日期 " + item.date)
                .font(.caption2).foregroundColor(.secondary)
            if let period = item.periodLabel {
                Text(period + (item.businessDayCount > 0
                     ? " · \(item.businessDayCount) 个营业日" : ""))
                    .font(.caption2).foregroundColor(.secondary)
            } else if item.kind.uppercased() != "DAILY" {
                Text("此笔记录未提供完整覆盖日期，不能当作单日结清。")
                    .font(.caption2).foregroundColor(.orange)
            }
            SettlementValueLine(label: "方案金额", amount: item.amount)
            SettlementValueLine(label: "云端已登记结算", amount: item.recordedSettled)
            if item.recordedSettled > 0.005 && item.confirmedAt <= 0 {
                Text("存在登记金额但未附确认时间；可能为旧数据，请在安卓核对。")
                    .font(.caption2).foregroundColor(.orange)
            }
            if registration == .inconsistent {
                Text("原始登记金额超出方案或格式异常，不自行调整金额。")
                    .font(.caption2).foregroundColor(.red)
            }
            if item.kind.uppercased() == "CUTOFF_LEGACY_INVALID" {
                Text("旧版无效结清记录：安卓不会将其计入当前资金余额。")
                    .font(.caption2).foregroundColor(.orange)
            }
        }
        .padding(9)
        .background(Color(UIColor.tertiarySystemGroupedBackground),
                    in: RoundedRectangle(cornerRadius: 9))
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
