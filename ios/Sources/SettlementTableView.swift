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
        let transfers = snapshot.financeTransfers.filter {
            inRange($0.date) && (partner.isEmpty || $0.from == partner || $0.to == partner)
        }

        var dates = Set(sales.map(\.date) + purchases.map(\.date) +
                        daily.map(\.date) + shares.map(\.date) + transfers.map(\.date))
        if !location.isEmpty {
            dates.formIntersection(Set(sales.map(\.date)))
        }
        if !partner.isEmpty {
            dates.formIntersection(Set(shares.map(\.date) + transfers.map(\.date)))
        }
        return dates.sorted(by: >).map { date in
            SettlementTableDay(
                date: date,
                sales: sales.filter { $0.date == date }
                    .sorted { $0.storeName == $1.storeName ? $0.id < $1.id : $0.storeName < $1.storeName },
                purchases: purchases.filter { $0.date == date }.sorted { $0.id < $1.id },
                savedDaily: daily.filter { $0.date == date }.sorted { $0.id < $1.id },
                shares: shares.filter { $0.date == date }.sorted { $0.id < $1.id },
                transfers: transfers.filter { $0.date == date }.sorted { $0.id < $1.id }
            )
        }
    }
}

struct SettlementTableView: View {
    @EnvironmentObject private var model: LedgerViewModel
    @State private var selectedDate = Date()
    @State private var range = "近7天"
    @State private var location = ""
    @State private var partner = ""
    @State private var visibleDays = 30

    private var lastDate: String { BusinessDates.key(selectedDate) }
    private var firstDate: String {
        switch range {
        case "单日": return lastDate
        case "近7天": return BusinessDates.daysAgo(6, from: selectedDate)
        case "近30天": return BusinessDates.daysAgo(29, from: selectedDate)
        default: return "0000-00-00"
        }
    }
    private var partnerNames: [String] {
        Array(Set(model.snapshot.financeProfitRows.map(\.partner) +
                  model.snapshot.financeTransfers.flatMap { [$0.from, $0.to] }))
            .filter { !$0.isEmpty && $0 != "未知" && $0 != "未知合伙人" }
            .sorted()
    }
    private var rows: [SettlementTableDay] {
        SettlementTablePolicy.rows(model.snapshot, since: firstDate, until: lastDate,
                                   location: location, partner: partner)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Label("资金结算表 · 云端只读", systemImage: "lock.shield.fill")
                    .font(.caption.bold()).foregroundColor(.green)
                HStack(spacing: 8) {
                    Text("截至").font(.caption).foregroundColor(.secondary)
                    DatePicker("日期", selection: $selectedDate, displayedComponents: .date)
                        .datePickerStyle(CompactDatePickerStyle()).labelsHidden()
                    Spacer(minLength: 2)
                    Picker("位置", selection: $location) {
                        Text("全部位置").tag("")
                        ForEach(model.snapshot.locations, id: \.self) {
                            Text($0).tag($0)
                        }
                    }.pickerStyle(MenuPickerStyle())
                    Picker("合伙人", selection: $partner) {
                        Text("全部合伙人").tag("")
                        ForEach(partnerNames, id: \.self) {
                            Text($0).tag($0)
                        }
                    }.pickerStyle(MenuPickerStyle())
                }
                Picker("日期范围", selection: $range) {
                    Text("单日").tag("单日")
                    Text("近7天").tag("近7天")
                    Text("近30天").tag("近30天")
                    Text("全部").tag("全部")
                }
                .pickerStyle(SegmentedPickerStyle())

                if !location.isEmpty || !partner.isEmpty {
                    Text("筛选仅选择对应营业位置或合伙人涉及的日期。下方日结快照、采购金额仍为全账本原始数据，不会被错误分配到该位置或合伙人。")
                        .font(.caption2).foregroundColor(.orange)
                }
                if model.lastSyncAt == nil {
                    SettlementEmpty(title: "尚未读取资金结算数据",
                                    detail: "点击右上角同步后查看；此页不会生成估算结算。")
                } else if rows.isEmpty {
                    SettlementEmpty(title: "所选日期暂无已同步记录",
                                    detail: "缺失数据不代表利润或结算金额为零。")
                } else {
                    Text("\(rows.count) 个有记录的日期")
                        .font(.caption).foregroundColor(.secondary)
                    LazyVStack(spacing: 12) {
                        ForEach(Array(rows.prefix(visibleDays))) { day in
                            SettlementDateCard(day: day)
                        }
                    }
                    if rows.count > visibleDays {
                        Button("加载更多日期") { visibleDays += 30 }
                            .frame(maxWidth: .infinity)
                    }
                }
                Text("营业和采购合计仅汇总已保存的原始记录；正式结算金额、利润分配与资金转账只显示同步的原始值。不会重新计算利润、轧差、最少转账或改变已结算资金。")
                    .font(.caption2).foregroundColor(.secondary)
                SyncFooter()
            }
            .padding(12)
        }
        .navigationBarTitle("资金结算表", displayMode: .inline)
    }
}

private struct SettlementDateCard: View {
    let day: SettlementTableDay

    var body: some View {
        VStack(alignment: .leading, spacing: 9) {
            HStack {
                Text(day.date).font(.headline)
                Spacer()
                Text("\(day.sales.count) 条位置营业记录")
                    .font(.caption2).foregroundColor(.secondary)
            }
            Divider()
            Text("每日经营记录").font(.subheadline.bold())
            if day.sales.isEmpty {
                Text("未同步位置营业明细").font(.caption).foregroundColor(.secondary)
            } else {
                ForEach(day.sales) { item in
                    HStack(alignment: .firstTextBaseline) {
                        Text(item.storeName).font(.subheadline)
                        Spacer(minLength: 3)
                        VStack(alignment: .trailing, spacing: 2) {
                            Text("营业 " + settleYuan(item.revenue)).font(.caption)
                            Text("已存利润 " + settleYuan(item.profit))
                                .font(.caption2).foregroundColor(.secondary)
                        }
                    }
                }
                Divider()
                SettlementValueLine(label: "已保存营业额合计", amount: day.savedSalesTotal!)
                SettlementValueLine(label: "已保存位置利润合计", amount: day.savedStoreProfitTotal!)
            }
            if let purchase = day.savedPurchaseTotal {
                SettlementValueLine(label: "当日采购订单合计（全账本）", amount: purchase)
                Text("按采购日期统计 \(day.purchases.count) 笔已保存订单，不向位置分摊。")
                    .font(.caption2).foregroundColor(.secondary)
            } else {
                Text("当日采购金额：无已同步的采购订单")
                    .font(.caption).foregroundColor(.secondary)
            }
            Divider()
            Text("已保存日结快照 · 全账本").font(.subheadline.bold())
            if day.savedDaily.isEmpty {
                Text("未同步正式日结快照；以上经营数据不能代替资金结算。")
                    .font(.caption).foregroundColor(.orange)
            } else {
                ForEach(day.savedDaily) { row in
                    VStack(alignment: .leading, spacing: 3) {
                        SettlementValueLine(label: "营业额", amount: row.revenue)
                        SettlementValueLine(label: "采购金额", amount: row.purchase)
                        SettlementValueLine(label: "正式记录利润", amount: row.profit)
                    }
                    .padding(9)
                    .background(Color(UIColor.tertiarySystemGroupedBackground),
                                in: RoundedRectangle(cornerRadius: 9))
                }
            }
            Divider()
            Text("已保存实际利润分配").font(.subheadline.bold())
            if day.shares.isEmpty {
                Text("暂无已同步分配记录，不自行推算分配。")
                    .font(.caption).foregroundColor(.secondary)
            } else {
                ForEach(day.shares) { item in
                    SettlementValueLine(label: item.partner, amount: item.allocated)
                }
            }
            Divider()
            Text("云端已保存转账方案及登记记录").font(.subheadline.bold())
            if day.transfers.isEmpty {
                Text("暂无已同步转账方案，不自行生成最少转账方案或已结清判断。")
                    .font(.caption).foregroundColor(.secondary)
            } else {
                ForEach(day.transfers) { item in
                    VStack(alignment: .leading, spacing: 3) {
                        Text(item.from + " → " + item.to)
                            .font(.subheadline.bold()).lineLimit(2)
                        SettlementValueLine(label: "方案金额", amount: item.amount)
                        SettlementValueLine(label: "已登记结算（非银行到账证明）",
                                            amount: item.recordedSettled)
                    }
                    .padding(.vertical, 3)
                }
            }
        }
        .padding(12)
        .background(Color(UIColor.secondarySystemGroupedBackground),
                    in: RoundedRectangle(cornerRadius: 12))
    }
}

private struct SettlementValueLine: View {
    let label: String
    let amount: Double

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Text(label).font(.caption).foregroundColor(.secondary)
            Spacer(minLength: 2)
            Text(settleYuan(amount)).font(.subheadline.bold())
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
