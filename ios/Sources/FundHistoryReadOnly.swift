import SwiftUI

/// Read-only accounting evidence from the cloud event log.
/// This is deliberately NOT an implementation of Android's
/// getPartnerFundBalances/buildPartnerFundLedger/FundBalanceTimeline.
/// Daily "balance" is the saved settlement_partner.balance, not the
/// cumulative remaining balance after partial or cutoff settlement.
struct TXFundHistoryDay: Identifiable {
    let date: String
    let partnerRows: [FinanceSettlementPartnerRow]
    let dailyTransfers: [FinanceTransferRow]
    var id: String { date }
}

struct TXFundPeriodBatch: Identifiable {
    let id: String
    let recordDate: String
    let transfers: [FinanceTransferRow]

    var first: FinanceTransferRow? { transfers.first }
    var kindLabel: String { first?.kindLabel ?? "未知资金记录" }
    var focusPartnerName: String { first?.focusPartnerName ?? "" }
    var batchRange: String {
        guard let first else { return "未提供期间" }
        let allMatch = transfers.allSatisfy {
            $0.rangeStartDate == first.rangeStartDate &&
            $0.rangeEndDate == first.rangeEndDate
        }
        guard allMatch else { return "批次覆盖日期不一致，需逐笔核对" }
        return first.periodLabel ?? "未提供完整覆盖期间"
    }
    /// Simple sum of persisted transfer rows in this batch. NOT a new
    /// recommendation, nor a cumulative partner balance.
    var plannedTotal: Double { transfers.reduce(0) { $0 + $1.amount } }
    var registeredTotal: Double { transfers.reduce(0) { $0 + $1.recordedSettled } }
    var hasInvalidAmount: Bool {
        transfers.contains {
            TXSettlementRecordPolicy.transferRegistration($0) == .inconsistent
        }
    }
    var isLegacyInvalid: Bool {
        transfers.contains { $0.kind.uppercased() == "CUTOFF_LEGACY_INVALID" }
    }
    var isCancelledCutoff: Bool {
        let cutoff = transfers.allSatisfy {
            let kind = $0.kind.uppercased()
            return kind == "CUTOFF" || kind == "CUTOFF_CENTER"
        }
        return cutoff &&
            transfers.contains { $0.confirmedAt > 0 } &&
            transfers.allSatisfy { $0.recordedSettled <= 0.005 }
    }

    var recordedLabel: String {
        if isLegacyInvalid { return "旧版无效记录" }
        if hasInvalidAmount { return "原始登记金额异常" }
        if isCancelledCutoff { return "已撤销（原登记清零）" }
        let statuses = transfers.map { TXSettlementRecordPolicy.transferRegistration($0) }
        if statuses.allSatisfy({ $0 == .noTransferRequired || $0 == .fullyRegistered }) {
            return "已登记全额"
        }
        if statuses.allSatisfy({ $0 == .notRegistered }) { return "未登记结算" }
        return "已登记部分金额"
    }
}

enum TXFundHistoryPolicy {
    /// Current cumulative fund balance is NOT a field in the cloud-synced
    /// settlement_partner records. Only a server endpoint calling the exact
    /// Android-equivalent calculation can populate that future card.
    static let cumulativeBalanceIsAuthoritative = false

    static func partnerNames(_ snapshot: ReadOnlySnapshot) -> [String] {
        Array(Set(
            snapshot.financeSettlementPartners.map(\.partner) +
            snapshot.financeTransfers.flatMap { [$0.from, $0.to, $0.focusPartnerName] }
        ))
        .filter { !$0.isEmpty && $0 != "未知" && $0 != "未知合伙人" }
        .sorted()
    }

    static func days(_ snapshot: ReadOnlySnapshot, since: String, until: String,
                     partner: String = "") -> [TXFundHistoryDay] {
        let rows = snapshot.financeSettlementPartners.filter {
            $0.date >= since && $0.date <= until &&
            (partner.isEmpty || $0.partner == partner)
        }
        let transfers = snapshot.financeTransfers.filter {
            $0.date >= since && $0.date <= until &&
            $0.kind.uppercased() == "DAILY" &&
            (partner.isEmpty || $0.from == partner || $0.to == partner)
        }
        let dates = Set(rows.map(\.date) + transfers.map(\.date)).sorted(by: >)
        return dates.map { date in
            TXFundHistoryDay(
                date: date,
                partnerRows: rows.filter { $0.date == date }
                    .sorted { $0.partner == $1.partner ? $0.id < $1.id :
                        $0.partner < $1.partner },
                dailyTransfers: transfers.filter { $0.date == date }
                    .sorted { $0.id < $1.id }
            )
        }
    }

    /// Each sync event appears in exactly one historical batch. An absent
    /// batch_key is NOT evidence of a shared transaction: keep rows separate.
    static func batches(_ snapshot: ReadOnlySnapshot, since: String, until: String,
                        partner: String = "") -> [TXFundPeriodBatch] {
        let transfers = snapshot.financeTransfers.filter {
            $0.date >= since && $0.date <= until &&
            $0.kind.uppercased() != "DAILY" &&
            (partner.isEmpty || $0.from == partner || $0.to == partner ||
             $0.focusPartnerName == partner)
        }
        var map: [String: [FinanceTransferRow]] = [:]
        for item in transfers {
            let identity = item.batchKey.isEmpty
                ? "single|" + item.id
                : item.date + "|" + item.kind.uppercased() + "|" + item.batchKey
            map[identity, default: []].append(item)
        }
        return map.map { key, rows in
            TXFundPeriodBatch(
                id: key,
                recordDate: rows[0].date,
                transfers: rows.sorted { $0.id < $1.id }
            )
        }.sorted {
            $0.recordDate == $1.recordDate ? $0.id < $1.id :
                $0.recordDate > $1.recordDate
        }
    }
}

struct TXFundHistoryView: View {
    @EnvironmentObject private var model: LedgerViewModel
    @State private var selectedDate = Date()
    @State private var period = "近7天"
    @State private var partner = ""
    @State private var mode = "逐日资金"
    @State private var visibleCount = 20

    private var endDate: String { BusinessDates.key(selectedDate) }
    private var startDate: String {
        switch period {
        case "近7天": return BusinessDates.daysAgo(6, from: selectedDate)
        case "近30天": return BusinessDates.daysAgo(29, from: selectedDate)
        default: return "0000-00-00"
        }
    }
    private var days: [TXFundHistoryDay] {
        TXFundHistoryPolicy.days(model.snapshot, since: startDate,
                                 until: endDate, partner: partner)
    }
    private var batches: [TXFundPeriodBatch] {
        TXFundHistoryPolicy.batches(model.snapshot, since: startDate,
                                    until: endDate, partner: partner)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                HStack {
                    VStack(alignment: .leading, spacing: 3) {
                        Text("合伙人资金与结清历史").font(.title3.bold())
                        Text("天鲜果业 · 云端已保存记录")
                            .font(.caption).foregroundColor(.secondary)
                    }
                    Spacer()
                    Label("只读", systemImage: "lock.shield.fill")
                        .font(.caption.bold()).foregroundColor(TXTheme.primary)
                }
                .padding(12)
                .background(TXTheme.header,
                            in: RoundedRectangle(cornerRadius: 12))

                VStack(alignment: .leading, spacing: 6) {
                    Label("当前累计余额：待权威接口", systemImage: "info.circle")
                        .font(.subheadline.bold())
                    Text("安卓资金余额包含历史部分结算、跨日结清、资金中心镜像及旧账清理，不能将以下各日资金差额累加作为应收、应付或当前余额。")
                        .font(.caption).foregroundColor(.secondary)
                }
                .padding(12)
                .background(Color(UIColor.tertiarySystemGroupedBackground),
                            in: RoundedRectangle(cornerRadius: 12))

                TXDayBar(date: $selectedDate, label: "资金截止日期", allowFuture: false)
                    .onChange(of: selectedDate) { _ in visibleCount = 20 }
                Picker("历史范围", selection: $period) {
                    Text("近7天").tag("近7天")
                    Text("近30天").tag("近30天")
                    Text("全部").tag("全部")
                }
                .pickerStyle(SegmentedPickerStyle())
                .onChange(of: period) { _ in visibleCount = 20 }

                HStack {
                    Picker("合伙人", selection: $partner) {
                        Text("全部合伙人").tag("")
                        ForEach(TXFundHistoryPolicy.partnerNames(model.snapshot),
                                id: \.self) { name in
                            Text(name).tag(name)
                        }
                    }
                    .pickerStyle(MenuPickerStyle())
                    Spacer()
                    if !partner.isEmpty {
                        Button("清除筛选") { partner = ""; visibleCount = 20 }
                            .font(.caption)
                    }
                }
                Picker("查看模式", selection: $mode) {
                    Text("逐日资金").tag("逐日资金")
                    Text("周期结清记录").tag("周期结清记录")
                }
                .pickerStyle(SegmentedPickerStyle())
                .onChange(of: mode) { _ in visibleCount = 20 }

                if model.lastSyncAt == nil {
                    Label("尚未完成云端同步，请先在账本首页同步。",
                          systemImage: "icloud.slash")
                        .font(.caption).foregroundColor(.orange)
                } else if mode == "逐日资金" {
                    dailyHistory
                } else {
                    periodHistory
                }
                Text("本页所有金额来自已保存原始记录，属于历史核对视图。已登记金额不代表银行实际到账；不会推断当前可提现余额、重新计算最少转账，也不会进行结算写入。")
                    .font(.caption2).foregroundColor(.secondary)
                SyncFooter()
            }.padding(12)
        }
        .navigationTitle("资金余额与历史")
        .navigationBarTitleDisplayMode(.inline)
    }

    @ViewBuilder
    private var dailyHistory: some View {
        if days.isEmpty {
            Text("所选日期没有已同步合伙人日结资金记录。")
                .font(.subheadline).foregroundColor(.secondary).padding(12)
        } else {
            Text("已保存 \(days.count) 个日期的资金记录")
                .font(.caption).foregroundColor(.secondary)
            LazyVStack(spacing: 9) {
                ForEach(Array(days.prefix(visibleCount))) { day in
                    TXFundDayCard(day: day)
                }
            }
            if days.count > visibleCount {
                Button("加载更多日期") { visibleCount += 20 }
                    .frame(maxWidth: .infinity)
            }
        }
    }

    @ViewBuilder
    private var periodHistory: some View {
        if batches.isEmpty {
            Text("所选日期没有已同步的周期或历史结算记录。")
                .font(.subheadline).foregroundColor(.secondary).padding(12)
        } else {
            Text("已保存 \(batches.count) 组周期及历史记录")
                .font(.caption).foregroundColor(.secondary)
            LazyVStack(spacing: 9) {
                ForEach(Array(batches.prefix(visibleCount))) { batch in
                    TXPeriodBatchCard(batch: batch, isFiltered: !partner.isEmpty)
                }
            }
            if batches.count > visibleCount {
                Button("加载更多记录") { visibleCount += 20 }
                    .frame(maxWidth: .infinity)
            }
        }
    }
}

private struct TXFundDayCard: View {
    let day: TXFundHistoryDay
    @State private var expanded = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Button { withAnimation { expanded.toggle() } } label: {
                HStack {
                    Text(day.date).font(.headline).foregroundColor(.primary)
                    Spacer()
                    Text("\(day.partnerRows.count) 位合伙人 · \(day.dailyTransfers.count) 条当日转账")
                        .font(.caption2).foregroundColor(.secondary)
                    Image(systemName: expanded ? "chevron.up" : "chevron.down")
                        .font(.caption).foregroundColor(.secondary)
                }
            }.buttonStyle(.plain)
            if day.partnerRows.isEmpty {
                Text("本日只有转账记录，未同步合伙人资金差额。")
                    .font(.caption).foregroundColor(.secondary)
            } else {
                ForEach(day.partnerRows) { item in
                    HStack {
                        Text(item.partner).font(.subheadline.bold())
                        Spacer()
                        VStack(alignment: .trailing, spacing: 2) {
                            Text(item.balance.formatted(.currency(code: "CNY")))
                                .font(.subheadline.bold())
                                .foregroundColor(item.balance < 0 ? .red : .primary)
                            Text("当日原始资金差额")
                                .font(.caption2).foregroundColor(.secondary)
                        }
                    }
                    if expanded {
                        TXFundOriginalLine(label: "收款", amount: item.revenueReceived)
                        TXFundOriginalLine(label: "采购垫付", amount: item.purchasePaid)
                        TXFundOriginalLine(label: "费用垫付", amount: item.expensePaid)
                        TXFundOriginalLine(label: "已存利润份额", amount: item.profitShare)
                        TXFundOriginalLine(label: "应留存", amount: item.shouldKeep)
                    }
                }
            }
            if expanded && !day.dailyTransfers.isEmpty {
                Divider()
                Text("本日转账登记记录").font(.caption.bold())
                ForEach(day.dailyTransfers) { transfer in
                    VStack(alignment: .leading, spacing: 3) {
                        Text(transfer.from + " → " + transfer.to)
                            .font(.caption.bold())
                        TXFundOriginalLine(label: "方案金额", amount: transfer.amount)
                        TXFundOriginalLine(label: "已登记", amount: transfer.recordedSettled)
                        Text(TXSettlementRecordPolicy.transferRegistration(transfer).label)
                            .font(.caption2).foregroundColor(.secondary)
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

private struct TXPeriodBatchCard: View {
    let batch: TXFundPeriodBatch
    let isFiltered: Bool
    @State private var expanded = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Button { withAnimation { expanded.toggle() } } label: {
                VStack(alignment: .leading, spacing: 5) {
                    HStack {
                        Text(batch.recordDate).font(.headline).foregroundColor(.primary)
                        Spacer()
                        Text(batch.recordedLabel)
                            .font(.caption2.bold())
                            .foregroundColor(batch.isLegacyInvalid ||
                                             batch.hasInvalidAmount ? .orange : .secondary)
                        Image(systemName: expanded ? "chevron.up" : "chevron.down")
                            .font(.caption).foregroundColor(.secondary)
                    }
                    Text(batch.kindLabel)
                        .font(.subheadline.bold()).foregroundColor(TXTheme.primary)
                    Text(batch.batchRange)
                        .font(.caption).foregroundColor(.secondary)
                    if !batch.focusPartnerName.isEmpty {
                        Text("结清对象：" + batch.focusPartnerName)
                            .font(.caption2).foregroundColor(.secondary)
                    }
                    HStack {
                        TXFundAmountTile(title: isFiltered ? "筛选后方案合计" : "方案合计",
                                         amount: batch.plannedTotal)
                        TXFundAmountTile(title: isFiltered ? "筛选后已登记" : "已登记合计",
                                         amount: batch.registeredTotal)
                    }
                }
            }.buttonStyle(.plain)
            if batch.isLegacyInvalid {
                Text("安卓标记为无效的历史结算，不应计入当前资金余额。")
                    .font(.caption2).foregroundColor(.orange)
            }
            if batch.isCancelledCutoff {
                Text("安卓撤销周期结清后可能保留原确认时间；当前已登记金额为零。")
                    .font(.caption2).foregroundColor(.orange)
            }
            if expanded {
                Divider()
                ForEach(batch.transfers) { transfer in
                    VStack(alignment: .leading, spacing: 3) {
                        Text(transfer.from + " → " + transfer.to)
                            .font(.subheadline.bold())
                        Text("登记状态：" +
                             TXSettlementRecordPolicy.transferRegistration(transfer).label)
                            .font(.caption2).foregroundColor(.secondary)
                        TXFundOriginalLine(label: "方案金额", amount: transfer.amount)
                        TXFundOriginalLine(label: "已登记金额", amount: transfer.recordedSettled)
                        if let period = transfer.periodLabel {
                            Text(period).font(.caption2).foregroundColor(.secondary)
                        }
                    }.padding(.vertical, 4)
                }
            }
        }
        .padding(12)
        .background(Color(UIColor.secondarySystemGroupedBackground),
                    in: RoundedRectangle(cornerRadius: 12))
    }
}

private struct TXFundAmountTile: View {
    let title: String
    let amount: Double
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(.caption2).foregroundColor(.secondary)
            Text(amount.formatted(.currency(code: "CNY")))
                .font(.subheadline.bold()).foregroundColor(.primary)
                .lineLimit(1).minimumScaleFactor(0.65)
        }.frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct TXFundOriginalLine: View {
    let label: String
    let amount: Double
    var body: some View {
        HStack {
            Text(label).font(.caption).foregroundColor(.secondary)
            Spacer()
            Text(amount.formatted(.currency(code: "CNY")))
                .font(.caption.bold())
                .foregroundColor(amount < 0 ? .red : .primary)
        }
    }
}
