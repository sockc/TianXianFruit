import SwiftUI

/// Read-only customer analytics from the separate customer-analytics cloud API.
/// Never labels a payment as a unique person unless a reliable identity exists.
struct CustomerInsightsView: View {
    @EnvironmentObject private var model: LedgerViewModel
    @State private var selectedDate: Date
    @State private var period = "单日"
    @State private var platform = ""
    @State private var storeID: String
    @State private var showHourComparison = false
    @State private var showDetails = false
    @State private var hourlyAmounts = false
    @State private var visibleReceipts = 40
    @State private var customStart: Date?
    @State private var rangeDraftStart = Date()
    @State private var showRangePicker = false
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
        case "自定义": return min(customStart.map(BusinessDates.key) ?? endDate, endDate)
        default: return endDate
        }
    }
    private var rows: [CustomerReceipt] {
        model.customerSnapshot.selected(dateStart: startDate, dateEnd: endDate,
                                         platform: platform, store: storeID)
    }
    private var totals: CustomerSummary { model.customerSnapshot.summary(rows) }
    private var showAny: Bool { model.customerSyncedAt != nil }

    private var storeTitle: String {
        if storeID.isEmpty { return "全部位置" }
        if storeID == "__unmatched__" { return "未匹配位置" }
        return model.snapshot.storeLabels[storeID] ?? "未知位置 " + String(storeID.prefix(6))
    }

    private var filterHeader: some View {
        VStack(alignment: .leading, spacing: 5) {
            TXDayBar(date: $selectedDate, label: "客流日期", allowFuture: false)
            HStack(spacing: 5) {
                Menu {
                    Button("全部位置") { storeID = "" }
                    Button("未匹配位置") { storeID = "__unmatched__" }
                    ForEach(model.customerSnapshot.assignedStores, id: \.self) { id in
                        Button(model.snapshot.storeLabels[id] ?? "未知位置 " + String(id.prefix(6))) { storeID = id }
                    }
                } label: {
                    Label(storeTitle, systemImage: "mappin").lineLimit(1)
                }
                Spacer(minLength: 3)
                Button {
                    rangeDraftStart = min(customStart ?? selectedDate, selectedDate)
                    showRangePicker = true
                } label: {
                    Text(period == "单日" ? "单日" : startDate + " 至 " + endDate)
                        .lineLimit(1).minimumScaleFactor(0.7)
                }
                Button {
                    Task { await model.refreshCustomer() }
                } label: {
                    if model.isCustomerBusy { ProgressView().controlSize(.small) }
                    else { Image(systemName: "arrow.clockwise") }
                }.disabled(model.isCustomerBusy).accessibilityLabel("刷新账单")
            }.font(.caption)
            Picker("渠道", selection: $platform) {
                Text("合并").tag("")
                Text("微信").tag("WECHAT")
                Text("支付宝").tag("ALIPAY")
            }.pickerStyle(.segmented)
        }
    }

    private var receiptList: some View {
        VStack(alignment: .leading, spacing: 5) {
            HStack {
                Text("有效付款账单").font(.subheadline.bold())
                Spacer()
                Text("\(rows.count) 笔").font(.caption).foregroundColor(.secondary)
            }
            if rows.isEmpty {
                Text("暂无已导入的有效付款").font(.caption).foregroundColor(.secondary)
            }
            LazyVStack(spacing: 4) {
                ForEach(Array(rows.prefix(visibleReceipts))) { receipt in
                    HStack(spacing: 6) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(receipt.platform == "WECHAT" ? "微信" : "支付宝").font(.caption.bold())
                            Text(receipt.date + " · " + (receipt.hour.map { String(format: "%02d时", $0) } ?? "时段未知"))
                                .font(.caption2).foregroundColor(.secondary)
                        }
                        Spacer(minLength: 3)
                        Text(receipt.storeSyncID.isEmpty ? "未匹配" : (model.snapshot.storeLabels[receipt.storeSyncID] ?? "未知位置"))
                            .font(.caption2).foregroundColor(.secondary).lineLimit(1)
                        Text(money(receipt.netAmount)).font(.subheadline.bold()).monospacedDigit()
                    }
                    .padding(7)
                    .background(TXTheme.header, in: RoundedRectangle(cornerRadius: 7))
                }
            }
            if visibleReceipts < rows.count {
                Button("加载更多账单") { visibleReceipts += 40 }.frame(maxWidth: .infinity)
            }
        }
    }

    private var rangePicker: some View {
        NavigationView {
            Form {
                Section {
                    Button("单日") { period = "单日"; showRangePicker = false }
                    Button("近7天") { period = "近7天"; showRangePicker = false }
                    Button("近30天") { period = "近30天"; showRangePicker = false }
                }
                Section(header: Text("自定义日期范围")) {
                    DatePicker("开始", selection: $rangeDraftStart,
                               in: ...selectedDate, displayedComponents: .date)
                    Button("确定范围") {
                        customStart = rangeDraftStart
                        visibleReceipts = 40
                        period = "自定义"
                        showRangePicker = false
                    }
                }
            }
            .environment(\.calendar, BusinessDates.calendar)
            .environment(\.timeZone, BusinessDates.calendar.timeZone)
            .environment(\.locale, Locale(identifier: "zh_CN"))
            .navigationTitle("日期范围")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("关闭") { showRangePicker = false } } }
        }.navigationViewStyle(StackNavigationViewStyle())
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 6) {
                filterHeader
                if !contextNotice.isEmpty {
                    Label(contextNotice, systemImage: "info.circle").font(.caption2).foregroundColor(.secondary)
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
                        TXCompactMetric(title: "电子收款", value: money(totals.amount), tint: .green)
                        TXCompactMetric(title: "付款笔数", value: "\(totals.paymentCount)", tint: .blue)
                        TXCompactMetric(title: "笔均收款", value: money(totals.average), tint: .orange)
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
                        Picker("统计", selection: $hourlyAmounts) {
                            Text("笔数").tag(false)
                            Text("金额").tag(true)
                        }.pickerStyle(.segmented).frame(width: 120)
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
                    receiptList
                    DisclosureGroup("统计口径与数据说明", isExpanded: $showDetails) {
                        Text("数据依据微信/支付宝二维码账单云端同步，仅统计有效正向交易；排除设置以已同步规则为准。未导入日期未知，不按零客流计算。新老客需要可靠身份或官方数据，此页不伪造身份。")
                            .font(.caption2).foregroundColor(.secondary)
                    }.font(.caption)
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
            .padding(8)
        }
        .navigationBarTitle("客户分析", displayMode: .inline)
        .sheet(isPresented: $showRangePicker) { rangePicker }
        .onChange(of: selectedDate) { _ in
            if let start = customStart, start > selectedDate { customStart = selectedDate }
            visibleReceipts = 40
        }
        .onChange(of: period) { _ in visibleReceipts = 40 }
        .onChange(of: platform) { _ in visibleReceipts = 40 }
        .onChange(of: storeID) { _ in visibleReceipts = 40 }
        .onAppear {
            if model.customerSyncedAt == nil && !model.isCustomerBusy {
                Task { await model.refreshCustomer() }
            }
        }
    }

    private var hourly: some View {
        let hourly = model.customerSnapshot.hourly(rows)
        let top = max(1.0, hourly.map { hourlyAmounts ? $0.amount : Double($0.paymentCount) }.max() ?? 1)
        return VStack(alignment: .leading, spacing: 9) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(alignment: .bottom, spacing: 11) {
                    ForEach(hourly) { item in
                        VStack(spacing: 5) {
                            Text(hourlyAmounts ? (item.amount > 0 ? String(format: "%.0f", item.amount) : "") : (item.paymentCount == 0 ? "" : String(item.paymentCount)))
                                .font(.caption2).foregroundColor(.secondary)
                                .frame(height: 16)
                            RoundedRectangle(cornerRadius: 3)
                                .fill(item.paymentCount == 0 ? Color.gray.opacity(0.2) : Color.green)
                                .frame(width: 15, height: max(CGFloat(3),
                                    CGFloat(hourlyAmounts ? item.amount : Double(item.paymentCount)) / CGFloat(top) * 75))
                            Text(String(format: "%02d", item.hour))
                                .font(.caption2).foregroundColor(.secondary)
                        }
                    }
                }
                .frame(height: 112, alignment: .bottom)
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
                                Text(hourlyAmounts ? money(hour.currentAmount) : String(hour.currentCount))
                                    .frame(maxWidth: .infinity, alignment: .trailing)
                                Text(hourlyAmounts ? money(hour.previousDailyAmount) : String(format: "%.1f", hour.previousDailyCount))
                                    .frame(maxWidth: .infinity, alignment: .trailing)
                                Text(hourlyAmounts ? String(format: "%+.2f", hour.amountDifference) : String(format: "%+.1f", hour.countDifference))
                                    .foregroundColor((hourlyAmounts ? hour.amountDifference : hour.countDifference) < 0 ? .red : TXTheme.primary)
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
