import SwiftUI

/// Audit of cloud-saved settlement evidence. Not an Android fund balance:
/// saved daily transfers, partner balances, and cutoff adjustments have
/// different semantics and MUST NOT be summed as "current outstanding funds".
struct TXFundAuditIssue: Identifiable, Equatable {
    let id: String
    let date: String
    let title: String
    let detail: String
}

struct TXFundAuditDay: Identifiable {
    let date: String
    let dailyCount: Int
    let transferCount: Int
    let verifiedCount: Int
    let periodCount: Int
    let plannedCents: Int64
    let settledCents: Int64
    let issues: [TXFundAuditIssue]

    var id: String { date }
    var remainingCents: Int64 { plannedCents - settledCents }
    var verified: Bool { dailyCount == 1 && issues.isEmpty }
}

struct TXFundAuditReport {
    let days: [TXFundAuditDay]
    var issues: [TXFundAuditIssue] { days.flatMap(\.issues) }
    var plannedCents: Int64 { days.reduce(0) { $0 + $1.plannedCents } }
    var settledCents: Int64 { days.reduce(0) { $0 + $1.settledCents } }
    var remainingCents: Int64 { plannedCents - settledCents }
    var verifiedTransferCount: Int { days.reduce(0) { $0 + $1.verifiedCount } }
    var periodTransferCount: Int { days.reduce(0) { $0 + $1.periodCount } }
}

enum TXFundAuditPolicy {
    /// The dashboard only uses full-book evidence. Source references retain
    /// exact signed 64-bit Android IDs; never match by partner display name.
    static func audit(_ snapshot: ReadOnlySnapshot,
                      since: String, through: String) -> TXFundAuditReport {
        let daily = snapshot.financeDaily.filter {
            $0.date >= since && $0.date <= through
        }
        let transfers = snapshot.financeTransfers.filter {
            $0.date >= since && $0.date <= through
        }
        let dates = Set(daily.map(\.date) + transfers.map(\.date))
        let days = dates.sorted(by: >).map { date -> TXFundAuditDay in
            let dailyRows = daily.filter { $0.date == date }
            let dailyTransfers = transfers.filter {
                $0.date == date && $0.kind.uppercased() == "DAILY"
            }
            let period = transfers.filter {
                $0.date == date && $0.kind.uppercased() != "DAILY"
            }
            var issues: [TXFundAuditIssue] = []
            func note(_ code: String, _ title: String, _ detail: String) {
                issues.append(TXFundAuditIssue(id: date + "|" + code,
                                              date: date, title: title,
                                              detail: detail))
            }
            if dailyRows.count > 1 {
                note("duplicate", "同日有多份日结快照",
                     "存在 \(dailyRows.count) 份有效日结；无法认定哪份是权威来源")
            } else if dailyRows.isEmpty && !dailyTransfers.isEmpty {
                note("orphan-day", "日结原始快照缺失",
                     "本日有 \(dailyTransfers.count) 条当日转账，不能独立确认为有效日结")
            }

            var sourceDayID: Int64?
            if dailyRows.count == 1, let day = dailyRows.first {
                if let event = snapshot.records["daily_cash_settlement|" + day.id],
                   event.rowVersion > 0 {
                    sourceDayID = AndroidNumericIdentity.exact(event.payload["id"])
                }
                if sourceDayID == nil {
                    note("day-id", "日结 Android 原始ID无效",
                         "关联必须使用精确64位数字ID；禁止按日期或名称猜测")
                }
                if !day.statusProvided || !(0...2).contains(day.status) {
                    note("day-status", "原始日结状态缺失或超出范围",
                         "需要安卓端核对状态0/1/2及同步版本")
                }
            }

            var validCount = 0
            var planned: Int64 = 0
            var settled: Int64 = 0
            var amountsValid = true
            for transfer in dailyTransfers {
                let key = transfer.id
                guard let raw = snapshot.records["settlement_transfer|" + key],
                      raw.rowVersion > 0,
                      let sid = AndroidNumericIdentity.exact(raw.payload["settlement_id"]),
                      let tid = AndroidNumericIdentity.exact(raw.payload["id"]),
                      tid > 0,
                      let sourceDayID, sid == sourceDayID else {
                    note("link-" + key, "当日转账关联异常",
                         "转账 \(key) 与唯一日结的64位原始ID或版本不匹配")
                    amountsValid = false
                    continue
                }
                guard let amount = TXSettlementPreparationPolicy.persistedCents(transfer.amount),
                      let already = TXSettlementPreparationPolicy.persistedCents(
                        transfer.recordedSettled),
                      already <= amount else {
                    note("amount-" + key, "当日转账金额异常",
                         "转账 \(key) 的方案/登记金额不符合两位小数或登记超过方案")
                    amountsValid = false
                    continue
                }
                // More than 9e16 cents would overflow; max in current atomic
                // contract is 100,000,000 cents and the test window is bounded.
                guard planned <= Int64.max - amount,
                      settled <= Int64.max - already else {
                    note("overflow-" + key, "汇总金额超出整数范围", "拒绝显示不可靠合计")
                    amountsValid = false
                    continue
                }
                validCount += 1
                planned += amount
                settled += already
            }

            // Compare ONLY Android's *saved* daily status, not its effective
            // status after period cutoff. Empty plans can be saved status 0.
            if let day = dailyRows.first, dailyRows.count == 1,
               day.statusProvided, (0...2).contains(day.status),
               amountsValid, validCount == dailyTransfers.count,
               !dailyTransfers.isEmpty {
                let derived: Int
                if planned == 0 || settled >= planned {
                    derived = 1
                } else if settled == 0 {
                    derived = 0
                } else {
                    derived = 2
                }
                if derived != day.status {
                    note("status-drift", "日结原始状态与转账登记不一致",
                         "保存状态为 \(day.status)，按已保存单笔金额应为 \(derived)；不是周期结清后的最终状态")
                }
            }
            for row in period where row.kind.uppercased() == "CUTOFF_LEGACY_INVALID" {
                note("invalid-legacy-" + row.id, "发现旧版无效资金转账",
                     "历史记录 \(row.id) 已被标为 CUTOFF_LEGACY_INVALID，不计入本页当日方案合计")
            }
            return TXFundAuditDay(
                date: date, dailyCount: dailyRows.count,
                transferCount: dailyTransfers.count,
                verifiedCount: validCount, periodCount: period.count,
                plannedCents: planned, settledCents: settled, issues: issues)
        }
        return TXFundAuditReport(days: days)
    }
}

struct TXFundReconciliationView: View {
    @EnvironmentObject private var model: LedgerViewModel
    @State private var range = "近30天"
    @State private var visibleCount = 20

    private var through: String { BusinessDates.key(Date()) }
    private var since: String {
        switch range {
        case "近7天": return BusinessDates.daysAgo(6, from: Date())
        case "近30天": return BusinessDates.daysAgo(29, from: Date())
        default: return "0000-00-00"
        }
    }
    private var report: TXFundAuditReport {
        TXFundAuditPolicy.audit(model.snapshot, since: since, through: through)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                HStack {
                    Label("资金对账检查", systemImage: "checkmark.shield")
                        .font(.headline)
                    Spacer()
                    Label("只读", systemImage: "lock.shield")
                        .font(.caption).foregroundColor(TXTheme.primary)
                    Button {
                        Task { await model.refresh(forceFull: true) }
                    } label: {
                        Image(systemName: "arrow.clockwise")
                    }
                    .disabled(model.isBusy)
                    .accessibilityLabel("重新核对云端资金记录")
                }
                Text("以云端已保存的日结与转账为准。金额仅表示当日方案登记进度，绝不是合伙人累计余额或银行实际到账。")
                    .font(.caption).foregroundColor(.secondary)
                Picker("日期范围", selection: $range) {
                    Text("近7天").tag("近7天")
                    Text("近30天").tag("近30天")
                    Text("全部").tag("全部")
                }
                .pickerStyle(.segmented)
                .onChange(of: range) { _ in visibleCount = 20 }

                HStack(spacing: 7) {
                    TXFundAuditTile(label: "当日方案", cents: report.plannedCents)
                    TXFundAuditTile(label: "已登记", cents: report.settledCents)
                    TXFundAuditTile(label: "尚未登记", cents: report.remainingCents)
                }
                HStack {
                    Text("\(report.verifiedTransferCount) 笔已核对当日转账")
                    Spacer()
                    Text("\(report.issues.count) 项需注意")
                        .foregroundColor(report.issues.isEmpty ? .secondary : .orange)
                }.font(.caption)
                if report.periodTransferCount > 0 {
                    Label("另有 \(report.periodTransferCount) 笔周期或历史转账未计入当日方案合计",
                          systemImage: "calendar.badge.clock")
                        .font(.caption2).foregroundColor(.secondary)
                }
                if model.lastSyncAt == nil {
                    Label("尚未完成在线同步，现有数据不足以审计；请先点击同步。",
                          systemImage: "exclamationmark.triangle")
                        .font(.caption).foregroundColor(.orange)
                } else if report.days.isEmpty {
                    Text("所选日期没有已同步的日结或资金转账记录。")
                        .font(.subheadline).foregroundColor(.secondary)
                } else {
                    LazyVStack(spacing: 9) {
                        ForEach(Array(report.days.prefix(visibleCount))) { day in
                            TXFundAuditDayCard(day: day)
                        }
                    }
                    if report.days.count > visibleCount {
                        Button("加载更多日期") { visibleCount += 20 }
                            .frame(maxWidth: .infinity)
                    }
                }
                Text("累计资金余额：待服务端权威接口。安卓资金余额还包括采购/费用/收款/实际利润、历史结清断点及资金中心镜像。本页不根据单日金额推算余额，也不能发起周期结清。")
                    .font(.caption2).foregroundColor(.secondary)
                SyncFooter()
            }.padding(12)
        }
        .navigationTitle("资金对账检查")
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct TXFundAuditTile: View {
    let label: String
    let cents: Int64
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label).font(.caption2).foregroundColor(.secondary)
            Text(TXSettlementPreparationPolicy.yuan(cents))
                .font(.subheadline.bold()).lineLimit(1)
                .minimumScaleFactor(0.7)
        }.frame(maxWidth: .infinity, alignment: .leading)
            .padding(9)
            .background(Color(UIColor.secondarySystemGroupedBackground),
                        in: RoundedRectangle(cornerRadius: 9))
    }
}

private struct TXFundAuditDayCard: View {
    let day: TXFundAuditDay
    @State private var expanded = false
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Button {
                withAnimation { expanded.toggle() }
            } label: {
                HStack {
                    Text(day.date).font(.subheadline.bold()).foregroundColor(.primary)
                    Spacer()
                    Text(day.issues.isEmpty ? "无异常" : "\(day.issues.count) 项待核对")
                        .font(.caption2)
                        .foregroundColor(day.issues.isEmpty ? TXTheme.primary : .orange)
                    Image(systemName: expanded ? "chevron.up" : "chevron.down")
                        .font(.caption).foregroundColor(.secondary)
                }
            }.buttonStyle(.plain)
            HStack {
                Text("日结 \(day.dailyCount) 份")
                Text("核对 \(day.verifiedCount)/\(day.transferCount) 笔")
                Spacer()
                Text("未登记 ¥" + TXSettlementPreparationPolicy.yuan(day.remainingCents))
            }.font(.caption2).foregroundColor(.secondary)
            if expanded {
                Divider()
                TXFundAuditAmountLine(label: "方案总额", cents: day.plannedCents)
                TXFundAuditAmountLine(label: "已登记", cents: day.settledCents)
                TXFundAuditAmountLine(label: "未登记", cents: day.remainingCents)
                if day.periodCount > 0 {
                    Text("本日另有 \(day.periodCount) 笔周期/历史转账（不加入上述合计）")
                        .font(.caption2).foregroundColor(.secondary)
                }
                ForEach(day.issues) { issue in
                    VStack(alignment: .leading, spacing: 3) {
                        Label(issue.title, systemImage: "exclamationmark.triangle")
                            .font(.caption.bold()).foregroundColor(.orange)
                        Text(issue.detail).font(.caption2).foregroundColor(.secondary)
                    }.frame(maxWidth: .infinity, alignment: .leading)
                }
            }
        }
        .padding(12)
        .background(Color(UIColor.secondarySystemGroupedBackground),
                    in: RoundedRectangle(cornerRadius: 11))
    }
}

private struct TXFundAuditAmountLine: View {
    let label: String
    let cents: Int64
    var body: some View {
        HStack {
            Text(label).foregroundColor(.secondary)
            Spacer()
            Text("¥" + TXSettlementPreparationPolicy.yuan(cents)).bold()
        }.font(.caption)
    }
}
