import SwiftUI

/// Read-only presentation of persisted, per-business-day partner cash movements.
/// It deliberately never derives a cumulative balance by summing daily balances:
/// Android accounts for partial transfers, cutoffs and invalid legacy records.
enum TXFundOverviewPolicy {
    static func latestRows(_ rows: [FinanceSettlementPartnerRow],
                           until: String) -> [FinanceSettlementPartnerRow] {
        let filtered = rows.filter { $0.date <= until }
        let dates = Dictionary(grouping: filtered, by: \.partner)
        return dates.keys.sorted().compactMap { name in
            guard let latest = dates[name]?.map(\.date).max() else { return nil }
            let matches = (dates[name] ?? []).filter { $0.date == latest }
            // Refuse to select an arbitrary duplicate snapshot for the same
            // partner/date. The history list remains available for inspection.
            return matches.count == 1 ? matches[0] : nil
        }
    }

    static func conflictingPartners(_ rows: [FinanceSettlementPartnerRow],
                                    until: String) -> [String] {
        let filtered = rows.filter { $0.date <= until }
        return Dictionary(grouping: filtered, by: \.partner).compactMap { name, items in
            guard let latest = items.map(\.date).max() else { return nil }
            return items.filter { $0.date == latest }.count > 1 ? name : nil
        }.sorted()
    }
}

/// This screen is embedded in the settlement table's "资金余额" tab.
/// The amounts shown here are source records, NOT the Android current wallet.
struct TXFundOverviewPanel: View {
    @EnvironmentObject private var model: LedgerViewModel
    @State private var cutoff = Date()
    @State private var period = "近7天"
    @State private var selectedPartner = ""
    @State private var expandedDay = ""

    private var endDate: String { BusinessDates.key(cutoff) }
    private var startDate: String {
        switch period {
        case "近7天": return BusinessDates.daysAgo(6, from: cutoff)
        case "近30天": return BusinessDates.daysAgo(29, from: cutoff)
        default: return "0000-00-00"
        }
    }
    private var names: [String] {
        TXFundHistoryPolicy.partnerNames(model.snapshot)
    }
    private var daily: [TXFundHistoryDay] {
        TXFundHistoryPolicy.days(model.snapshot, since: startDate,
                                 until: endDate, partner: selectedPartner)
    }
    private var snapshotsInRange: [FinanceSettlementPartnerRow] {
        model.snapshot.financeSettlementPartners.filter {
            $0.date >= startDate && $0.date <= endDate &&
            (selectedPartner.isEmpty || $0.partner == selectedPartner)
        }
    }
    private var latest: [FinanceSettlementPartnerRow] {
        TXFundOverviewPolicy.latestRows(snapshotsInRange, until: endDate)
    }
    private var conflicts: [String] {
        TXFundOverviewPolicy.conflictingPartners(snapshotsInRange, until: endDate)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            VStack(alignment: .leading, spacing: 6) {
                Label("资金余额 · 合伙人明细", systemImage: "wallet.pass")
                    .font(.headline)
                Text("当前累计资金余额：待接入安卓权威计算。以下为每日已保存资金差额，不能累加作为应收、应付或可结算余额。")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color(UIColor.tertiarySystemGroupedBackground),
                        in: RoundedRectangle(cornerRadius: 12))

            TXDayBar(date: $cutoff, label: "资金截止日期", allowFuture: false)
            if model.lastSyncAt != nil {
                let audit = TXFundAuditPolicy.inspect(model.snapshot, cutoff: endDate,
                                                      partner: selectedPartner)
                DisclosureGroup {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("核对范围：全部历史至 " + audit.cutoff)
                            .font(.caption).foregroundColor(.secondary)
                        Text("日结快照 \(audit.dailyRecordCount) · 合伙人记录 \(audit.partnerRecordCount) · 转账记录 \(audit.transferCount)")
                            .font(.caption2).foregroundColor(.secondary)
                        ForEach(audit.findings) { finding in
                            VStack(alignment: .leading, spacing: 3) {
                                Text(finding.title).font(.caption.bold())
                                    .foregroundColor(finding.severity == .blocked ? .orange : .secondary)
                                Text(finding.detail).font(.caption2).foregroundColor(.secondary)
                            }
                        }
                    }
                    .padding(.vertical, 5)
                } label: {
                    Label("累计余额核对 · 尚未验证", systemImage: "checklist")
                        .font(.subheadline.bold())
                }
                .padding(10)
                .background(Color(UIColor.secondarySystemGroupedBackground),
                            in: RoundedRectangle(cornerRadius: 10))
            }

            Picker("历史范围", selection: $period) {
                Text("近7天").tag("近7天")
                Text("近30天").tag("近30天")
                Text("全部").tag("全部")
            }
            .pickerStyle(.segmented)
            HStack {
                Picker("合伙人", selection: $selectedPartner) {
                    Text("全部合伙人").tag("")
                    ForEach(names, id: \.self) { name in
                        Text(name).tag(name)
                    }
                }
                .pickerStyle(.menu)
                Spacer()
                Button {
                    Task { await model.refresh() }
                } label: {
                    Label("同步", systemImage: "arrow.clockwise")
                }
                .disabled(model.isBusy)
            }

            if model.lastSyncAt == nil {
                Label("尚未同步云端资金记录。请先同步，不能把缺少记录当作金额为零。",
                      systemImage: "icloud.slash")
                    .font(.caption).foregroundColor(.orange)
            } else if daily.isEmpty {
                Label("所选范围内没有资金记录；这不代表资金余额为零。",
                      systemImage: "info.circle")
                    .font(.caption).foregroundColor(.secondary)
            } else {
                Text("所选范围内最近一次已保存日差额")
                    .font(.subheadline.bold())
                if !conflicts.isEmpty {
                    Label("部分合伙人最新日期存在重复快照，已隐藏歧义金额：" +
                          conflicts.joined(separator: "、"), systemImage: "exclamationmark.triangle")
                        .font(.caption).foregroundColor(.orange)
                }
                ForEach(latest) { item in
                    HStack(alignment: .top) {
                        VStack(alignment: .leading, spacing: 3) {
                            Text(item.partner).font(.subheadline.bold())
                            Text(item.date + " · 当日原始差额")
                                .font(.caption2).foregroundColor(.secondary)
                        }
                        Spacer()
                        Text(item.balance.formatted(.currency(code: "CNY")))
                            .font(.subheadline.bold())
                            .foregroundColor(item.balance < 0 ? .red : .primary)
                    }
                    .padding(10)
                    .background(Color(UIColor.secondarySystemGroupedBackground),
                                in: RoundedRectangle(cornerRadius: 10))
                }
                Text("逐日明细 · \(daily.count) 个有记录日期")
                    .font(.subheadline.bold())
                ForEach(daily) { day in
                    VStack(alignment: .leading, spacing: 7) {
                        Button {
                            withAnimation { expandedDay = expandedDay == day.date ? "" : day.date }
                        } label: {
                            HStack {
                                Text(day.date).font(.subheadline.bold())
                                Spacer()
                                Text("\(day.partnerRows.count) 条合伙人记录")
                                    .font(.caption).foregroundColor(.secondary)
                                Image(systemName: expandedDay == day.date ? "chevron.up" : "chevron.down")
                            }
                        }
                        .buttonStyle(.plain)
                        if expandedDay == day.date {
                            ForEach(day.partnerRows) { row in
                                VStack(alignment: .leading, spacing: 4) {
                                    HStack {
                                        Text(row.partner).font(.subheadline.bold())
                                        Spacer()
                                        Text(row.balance.formatted(.currency(code: "CNY")))
                                            .font(.subheadline.bold())
                                    }
                                    metric("收款", row.revenueReceived)
                                    metric("采购垫付", row.purchasePaid)
                                    metric("费用垫付", row.expensePaid)
                                    metric("实际利润份额", row.profitShare)
                                    metric("应留存", row.shouldKeep)
                                }
                                .padding(.vertical, 5)
                                Divider()
                            }
                            if !day.dailyTransfers.isEmpty {
                                Text("本日另有 \(day.dailyTransfers.count) 条已保存转账登记；不以此推定最终结清。")
                                    .font(.caption2).foregroundColor(.secondary)
                            }
                        }
                    }
                    .padding(11)
                    .background(Color(UIColor.secondarySystemGroupedBackground),
                                in: RoundedRectangle(cornerRadius: 11))
                }
            }
            NavigationLink(destination: TXFundHistoryView()) {
                Label("查看完整逐日与周期结清历史", systemImage: "clock.arrow.circlepath")
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .buttonStyle(.bordered)
        }
    }

    private func metric(_ title: String, _ value: Double) -> some View {
        HStack {
            Text(title).foregroundColor(.secondary)
            Spacer()
            Text(value.formatted(.currency(code: "CNY")))
        }
        .font(.caption)
    }
}
