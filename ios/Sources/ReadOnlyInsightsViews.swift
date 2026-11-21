import SwiftUI

/// Read-only customer analytics from the separate customer-analytics cloud API.
/// Never labels a payment as a unique person unless a reliable identity exists.
struct CustomerInsightsView: View {
    @EnvironmentObject private var model: LedgerViewModel
    @State private var selectedDate: Date
    @State private var period = "单日"
    @State private var platform = ""
    @State private var storeID: String
    @State private var showFilters = false
    @State private var showHourComparison = false
    let contextNotice: String

    init(initialDate: String? = nil, initialStoreID: String = "",
         contextNotice: String = "") {
        _selectedDate = State(initialValue:
            initialDate.flatMap(BusinessDates.parse) ?? Date())
        _storeID = State(initialValue: initialStoreID)
        self.contextNotice = contextNotice
    }

    private var endDate: String { BusinessDates.key(selectedDate) }
    private var startDate: String {
        switch period {
        case "近7天": return BusinessDates.daysAgo(6, from: selectedDate)
        case "近30天": return BusinessDates.daysAgo(29, from: selectedDate)
        default: return endDate
        }
    }
    private var rows: [CustomerReceipt] {
        model.customerSnapshot.selected(dateStart: startDate, dateEnd: endDate,
                                         platform: platform, store: storeID)
    }
    private var totals: CustomerSummary { model.customerSnapshot.summary(rows) }
    private var showAny: Bool { model.customerSyncedAt != nil }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                TXDayBar(date: $selectedDate, label: "客流日期", allowFuture: false)
                if !contextNotice.isEmpty {
                    Label(contextNotice, systemImage: "info.circle")
                        .font(.caption2).foregroundColor(.secondary)
                }
                HStack(spacing: 7) {
                    Picker("渠道", selection: $platform) {
                        Text("合并").tag("")
                        Text("微信").tag("WECHAT")
                        Text("支付宝").tag("ALIPAY")
                    }
                    .pickerStyle(.segmented)
                    Button {
                        withAnimation(.easeInOut(duration: 0.15)) {
                            showFilters.toggle()
                        }
                    } label: {
                        Image(systemName: showFilters ? "line.3.horizontal.decrease.circle.fill"
                            : "line.3.horizontal.decrease.circle")
                            .font(.title3)
                            .foregroundColor(TXTheme.primary)
                    }
                    .accessibilityLabel("日期范围和位置筛选")
                }
                if showFilters {
                    VStack(spacing: 8) {
                        Picker("范围", selection: $period) {
                            Text("单日").tag("单日")
                            Text("近7天").tag("近7天")
                            Text("近30天").tag("近30天")
                        }
                        .pickerStyle(.segmented)
                        Picker("位置", selection: $storeID) {
                            Text("全部位置").tag("")
                            Text("未匹配").tag("__unmatched__")
                            ForEach(model.customerSnapshot.assignedStores, id: \.self) { id in
                                Text(model.snapshot.storeLabels[id] ??
                                     ("未知位置 " + String(id.prefix(6)))).tag(id)
                            }
                            if !storeID.isEmpty && storeID != "__unmatched__" &&
                               !model.customerSnapshot.assignedStores.contains(storeID) {
                                Text(model.snapshot.storeLabels[storeID] ?? "当前未导入该位置账单")
                                    .tag(storeID)
                            }
                        }
                        .pickerStyle(.menu)
                    }
                    .padding(9)
                    .background(Color(UIColor.secondarySystemGroupedBackground),
                                in: RoundedRectangle(cornerRadius: 9))
                }
                if !storeID.isEmpty && storeID != "__unmatched__" {
                    HStack {
                        Label("位置：" +
                              (model.snapshot.storeLabels[storeID] ?? "已关联位置"),
                              systemImage: "mappin")
                            .font(.caption)
                        Spacer()
                        Button("切换") { showFilters = true }
                            .font(.caption2)
                    }
                    .foregroundColor(.secondary)
                }
                if let error = model.customerError {
                    Text(error).font(.caption).foregroundColor(.orange)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                if !showAny {
                    EmptyInsights(title: "尚无云端客户账单",
                                  detail: "仅在服务器声明支持客流同步时读取。不会根据营业额猜测付款笔数。",
                                  icon: "person.3")
                } else {
                    HStack(spacing: 8) {
                        InfoNumber(title: "付款笔数", text: "\(totals.paymentCount)")
                        InfoNumber(title: "收款净额", text: money(totals.amount))
                        InfoNumber(title: "笔均收款", text: money(totals.average))
                    }
                    if rows.isEmpty {
                        Text("当前日期/位置未观察到有效已导入账单，不能解释成当天没有顾客。")
                            .font(.caption).foregroundColor(.orange)
                    }
                    let unknown = rows.filter { $0.storeSyncID.isEmpty }.count
                    if unknown > 0 && storeID.isEmpty {
                        Label("本范围 \(unknown) 笔缺少可靠位置归属；不会分摊到某个摊位。", systemImage: "exclamationmark.triangle")
                            .font(.caption).foregroundColor(.orange)
                    }
                    if storeID.isEmpty && model.snapshot.business
                        .filter({ $0.date >= startDate && $0.date <= endDate })
                        .map(\.storeName).uniqued().count > 1 {
                        Text("所选期间存在多个营业位置。合计代表所有已导入账单，不能视为单一摊位客流。")
                            .font(.caption2).foregroundColor(.secondary)
                    }

                    HStack {
                        Text("逐小时付款表现").font(.headline)
                        Spacer()
                        Text("笔数 · 按实际交易时间")
                            .font(.caption2).foregroundColor(.secondary)
                    }
                    hourly
                    if period == "单日" && !storeID.isEmpty && storeID != "__unmatched__" {
                        historicalComparison
                        hourlyWeekdayDetails
                    }
                    HStack {
                        Text("微信").font(.caption.bold())
                        Text("\(model.customerSnapshot.summary(rows.filter { $0.platform == "WECHAT" }).paymentCount) 笔")
                        Spacer()
                        Text("支付宝").font(.caption.bold())
                        Text("\(model.customerSnapshot.summary(rows.filter { $0.platform == "ALIPAY" }).paymentCount) 笔")
                    }
                    .font(.caption)
                    .foregroundColor(.secondary)
                    Text("数据依据微信/支付宝二维码账单云端同步，仅统计有效正向交易；排除设置以已同步规则为准。未导入日期未知，不按零客流计算。新老客需要可靠身份或官方数据，此页不伪造身份。")
                        .font(.caption2).foregroundColor(.secondary)
                }
                HStack {
                    Button {
                        Task { await model.refreshCustomer() }
                    } label: {
                        if model.isCustomerBusy { ProgressView() } else {
                            Label("刷新账单", systemImage: "arrow.clockwise")
                        }
                    }.disabled(model.isCustomerBusy)
                    Spacer()
                    if showAny {
                        Text("云端同步于 " + (model.customerSyncedAt?.formatted(date: .abbreviated, time: .shortened) ?? ""))
                            .font(.caption2).foregroundColor(.secondary)
                    }
                }
                if model.customerSyncedAt != nil && !model.customerAPIAvailable {
                    Text("正在显示上次缓存；尚未核验本次服务器客流同步能力。")
                        .font(.caption2).foregroundColor(.orange)
                }
            }
            .padding(12)
        }
        .navigationBarTitle("客户分析", displayMode: .inline)
        .onAppear {
            if model.customerSyncedAt == nil && !model.isCustomerBusy {
                Task { await model.refreshCustomer() }
            }
        }
    }

    private var hourly: some View {
        let hourly = model.customerSnapshot.hourly(rows)
        let top = max(1, hourly.map(\.paymentCount).max() ?? 1)
        return VStack(alignment: .leading, spacing: 9) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(alignment: .bottom, spacing: 11) {
                    ForEach(hourly) { item in
                        VStack(spacing: 5) {
                            Text(item.paymentCount == 0 ? "" : String(item.paymentCount))
                                .font(.caption2).foregroundColor(.secondary)
                                .frame(height: 16)
                            RoundedRectangle(cornerRadius: 3)
                                .fill(item.paymentCount == 0 ? Color.gray.opacity(0.2) : Color.green)
                                .frame(width: 15, height: max(CGFloat(3),
                                    CGFloat(item.paymentCount) / CGFloat(top) * 92))
                            Text(String(format: "%02d", item.hour))
                                .font(.caption2).foregroundColor(.secondary)
                        }
                    }
                }
                .frame(height: 132, alignment: .bottom)
                .padding(.horizontal, 4)
            }
            if rows.contains(where: { $0.hour == nil }) {
                Text("部分交易时间无法识别，未加入逐小时图，但已计入总笔数。")
                    .font(.caption2).foregroundColor(.orange)
            }
        }
        .padding(10)
        .background(Color(UIColor.secondarySystemGroupedBackground),
                    in: RoundedRectangle(cornerRadius: 12))
    }

    private var hourlyWeekdayDetails: some View {
        let compared = model.customerSnapshot.hourlySameWeekday(
            date: endDate, platform: platform, store: storeID)
        return Group {
            if let comparison = compared {
                DisclosureGroup(isExpanded: $showHourComparison) {
                    VStack(alignment: .leading, spacing: 8) {
                        HStack(spacing: 6) {
                            Text("时段").frame(width: 46, alignment: .leading)
                            Text("本次").frame(maxWidth: .infinity, alignment: .trailing)
                            Text("历史日均").frame(maxWidth: .infinity, alignment: .trailing)
                            Text("差额").frame(maxWidth: .infinity, alignment: .trailing)
                        }
                        .font(.caption2.bold()).foregroundColor(.secondary)
                        ForEach(comparison.hours.filter {
                            $0.currentCount > 0 || $0.previousDailyCount > 0
                        }) { hour in
                            HStack(spacing: 6) {
                                Text(String(format: "%02d:00", hour.hour))
                                    .frame(width: 46, alignment: .leading)
                                Text(String(hour.currentCount))
                                    .frame(maxWidth: .infinity, alignment: .trailing)
                                Text(String(format: "%.1f", hour.previousDailyCount))
                                    .frame(maxWidth: .infinity, alignment: .trailing)
                                Text(String(format: "%+.1f", hour.countDifference))
                                    .foregroundColor(hour.countDifference < 0 ? .red : TXTheme.primary)
                                    .frame(maxWidth: .infinity, alignment: .trailing)
                            }
                            .font(.caption)
                        }
                        Text("逐小时对比仅统计已导入交易；历史缺少账单的日期不补零。")
                            .font(.caption2).foregroundColor(.secondary)
                    }
                    .padding(.top, 10)
                } label: {
                    Label("同位置 · 同星期逐小时对比（历史" +
                          String(comparison.historicalDays) + "日）",
                          systemImage: "clock.arrow.circlepath")
                        .font(.subheadline.bold())
                }
                .padding(12)
                .background(Color(UIColor.secondarySystemGroupedBackground),
                            in: RoundedRectangle(cornerRadius: 12))
            }
        }
    }

    private var historicalComparison: some View {
        let comparison = model.customerSnapshot.compareSameWeekday(
            date: endDate, platform: platform, store: storeID)
        return VStack(alignment: .leading, spacing: 8) {
            Text("同位置 · 同星期历史对比").font(.headline)
            if let value = comparison {
                Text("当前 \(value.current.paymentCount) 笔 / 历史 \(value.historicalDays) 个有记录的同星期日平均 " +
                     String(format: "%.1f", value.priorDayAverageCount) + " 笔")
                    .font(.subheadline)
                Text("当前 " + money(value.current.amount) + " / 历史均值 " +
                     money(value.priorDayAverageAmount))
                    .font(.subheadline)
                Text("仅比较有导入交易的日期；没有记录的日子不会补零。营业时段、账单导入覆盖可能不同，比较只用于辅助观察。")
                    .font(.caption2).foregroundColor(.secondary)
            } else {
                Text("至少需要两个有账单数据的历史同星期营业日，目前样本不足。")
                    .font(.caption).foregroundColor(.secondary)
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(UIColor.secondarySystemGroupedBackground),
                    in: RoundedRectangle(cornerRadius: 12))
    }
}

struct FinanceReadOnlyView: View {
    @EnvironmentObject private var model: LedgerViewModel
    @State private var period = "近30天"
    @State private var selectedPartner = ""
    @State private var visibleProfits = 60
    @State private var visibleTransfers = 60

    private var startDate: String {
        period == "全部" ? "0000-00-00" : BusinessDates.daysAgo(period == "近7天" ? 6 : 29)
    }
    private var profits: [FinanceProfitRow] {
        model.snapshot.financeProfitRows.filter {
            $0.date >= startDate && (selectedPartner.isEmpty || $0.partner == selectedPartner)
        }
    }
    private var transfers: [FinanceTransferRow] {
        model.snapshot.financeTransfers.filter {
            $0.date >= startDate &&
            (selectedPartner.isEmpty || $0.from == selectedPartner || $0.to == selectedPartner)
        }
    }
    private var partners: [String] {
        Array(Set(model.snapshot.financeProfitRows.map(\.partner) +
                  model.snapshot.financeTransfers.flatMap { [$0.from, $0.to] })).sorted()
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Label("资金记录 · 只读", systemImage: "lock.shield.fill")
                    .foregroundColor(.green).font(.caption)
                HStack {
                    Picker("日期", selection: $period) {
                        Text("近7天").tag("近7天")
                        Text("近30天").tag("近30天")
                        Text("全部").tag("全部")
                    }.pickerStyle(SegmentedPickerStyle())
                    Picker("合伙人", selection: $selectedPartner) {
                        Text("全部").tag("")
                        ForEach(partners, id: \.self) { Text($0).tag($0) }
                    }.pickerStyle(MenuPickerStyle())
                }
                Text("已保存利润分配").font(.headline)
                if profits.isEmpty {
                    EmptyInsights(title: "没有已同步的利润分配记录",
                                  detail: "不根据营业额自行生成利润分配。", icon: "chart.pie")
                }
                LazyVStack(spacing: 7) {
                    ForEach(Array(profits.prefix(visibleProfits))) { item in
                        HStack {
                            VStack(alignment: .leading, spacing: 3) {
                                Text(item.partner).font(.subheadline.bold())
                                Text(item.date).font(.caption2).foregroundColor(.secondary)
                            }
                            Spacer()
                            Text(money(item.allocated)).font(.subheadline)
                        }
                        .padding(11)
                        .background(Color(UIColor.secondarySystemGroupedBackground),
                                    in: RoundedRectangle(cornerRadius: 10))
                    }
                }
                if profits.count > visibleProfits {
                    Button("加载更多利润分配") { visibleProfits += 60 }
                        .frame(maxWidth: .infinity)
                }
                Text("云端已保存的资金转账方案").font(.headline)
                if transfers.isEmpty {
                    EmptyInsights(title: "没有已同步的转账记录",
                                  detail: "不推断应转账金额或已结清状态。", icon: "arrow.left.arrow.right")
                }
                LazyVStack(spacing: 7) {
                    ForEach(Array(transfers.prefix(visibleTransfers))) { item in
                        VStack(alignment: .leading, spacing: 5) {
                            Text(item.date + "  ·  " + item.from + " → " + item.to)
                                .font(.subheadline.bold()).lineLimit(2)
                            HStack {
                                Text("方案金额：" + money(item.amount))
                                Spacer()
                                Text("已登记结算：" + money(item.recordedSettled))
                            }
                            .font(.caption).foregroundColor(.secondary)
                        }
                        .padding(11)
                        .background(Color(UIColor.secondarySystemGroupedBackground),
                                    in: RoundedRectangle(cornerRadius: 10))
                    }
                }
                if transfers.count > visibleTransfers {
                    Button("加载更多转账记录") { visibleTransfers += 60 }
                        .frame(maxWidth: .infinity)
                }
                Text("只显示正式同步表中的已保存利润分配和转账方案。不得将“已登记结算”视作银行实际到账证明。本页不能修改、删除或确认任何结算。")
                    .font(.caption2).foregroundColor(.secondary)
            }
            .padding(12)
        }
        .navigationBarTitle("资金查看", displayMode: .inline)
    }
}

struct BusinessStatsView: View {
    @EnvironmentObject private var model: LedgerViewModel
    @State private var days = 7
    @State private var location = ""
    @State private var metric = "revenue"

    private var dates: [String] { BusinessDates.recentDays(days) }
    private struct StatDay: Identifiable {
        let date: String
        let value: Double
        let count: Int
        var id: String { date }
    }

    private var values: [StatDay] {
        dates.map { date in
            let sales = model.snapshot.business.filter {
                $0.date == date && (location.isEmpty || $0.storeName == location)
            }
            return StatDay(date: date,
                           value: sales.reduce(0) { $0 + (metric == "revenue" ? $1.revenue : $1.profit) },
                           count: sales.count)
        }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                HStack {
                    Picker("时间", selection: $days) {
                        Text("近7天").tag(7)
                        Text("近30天").tag(30)
                    }.pickerStyle(SegmentedPickerStyle())
                    Picker("位置", selection: $location) {
                        Text("全部位置").tag("")
                        ForEach(model.snapshot.locations, id: \.self) { Text($0).tag($0) }
                    }.pickerStyle(MenuPickerStyle())
                }
                Picker("指标", selection: $metric) {
                    Text("营业额").tag("revenue")
                    Text("利润").tag("profit")
                }.pickerStyle(SegmentedPickerStyle())
                Text(metric == "revenue" ? "逐日已存营业额" : "逐日已存利润")
                    .font(.headline)
                let maximum = max(1, values.map { abs($0.value) }.max() ?? 1)
                LazyVStack(spacing: 12) {
                    ForEach(values) { row in
                        HStack(spacing: 8) {
                            Text(String(row.date.suffix(5))).font(.caption)
                                .frame(width: 42, alignment: .leading)
                            GeometryReader { geometry in
                                HStack(spacing: 0) {
                                    RoundedRectangle(cornerRadius: 3)
                                        .fill(row.value < 0 ? Color.red : Color.green)
                                        .frame(width: max(2, geometry.size.width *
                                            CGFloat(abs(row.value) / maximum)))
                                    Spacer(minLength: 0)
                                }
                            }
                            .frame(height: 12)
                            VStack(alignment: .trailing, spacing: 2) {
                                Text(money(row.value)).font(.caption.bold())
                                Text("\(row.count) 个位置记录")
                                    .font(.caption2).foregroundColor(.secondary)
                            }
                            .frame(width: 100, alignment: .trailing)
                        }
                    }
                }
                .padding(12)
                .background(Color(UIColor.secondarySystemGroupedBackground),
                            in: RoundedRectangle(cornerRadius: 12))
                Text("仅依据正式营业记录，不包括未保存数据。没有记录的日期显示 0 条，不代表该日期没有营业；利润不重新计算。")
                    .font(.caption2).foregroundColor(.secondary)
            }
            .padding(12)
        }
        .navigationBarTitle("经营统计", displayMode: .inline)
    }
}

private struct InfoNumber: View {
    let title: String
    let text: String
    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(title).font(.caption2).foregroundColor(.secondary)
            Text(text).font(.subheadline.bold()).minimumScaleFactor(0.65).lineLimit(1)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(10)
        .background(Color(UIColor.secondarySystemGroupedBackground),
                    in: RoundedRectangle(cornerRadius: 10))
    }
}

private struct EmptyInsights: View {
    let title: String
    let detail: String
    let icon: String
    var body: some View {
        VStack(spacing: 8) {
            Image(systemName: icon).foregroundColor(.secondary).font(.title2)
            Text(title).font(.subheadline.bold())
            Text(detail).font(.caption).foregroundColor(.secondary)
                .multilineTextAlignment(.center)
        }
        .padding(14)
        .frame(maxWidth: .infinity)
    }
}

private func money(_ value: Double) -> String {
    value.formatted(.currency(code: "CNY"))
}

private extension Sequence where Element: Hashable {
    func uniqued() -> [Element] { Array(Set(self)) }
}
