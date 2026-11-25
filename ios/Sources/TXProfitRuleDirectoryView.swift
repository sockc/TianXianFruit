import Foundation
import SwiftUI

struct TXProfitRuleReadout {
    struct Row: Identifiable, Equatable {
        let id: String
        let partner: String
        let percent: Double
        let centerFlag: Bool
    }
    let rows: [Row]
    let warnings: [String]
    var total: Double { rows.reduce(0) { $0 + $1.percent } }

    static func inspect(_ snapshot: ReadOnlySnapshot) -> Self {
        let people = snapshot.records.values.filter { $0.tableName == "partner" }
        var rows: [Row] = []
        var warnings: [String] = []
        var seen = Set<Int64>()
        for rule in snapshot.records.values.filter({ $0.tableName == "profit_rule" }).sorted(by: { $0.syncId < $1.syncId }) {
            let p = rule.payload
            guard let pid = AndroidNumericIdentity.exact(p["partner_id"]),
                  let percent = TXFundDataAdapter.number(p["percent"]), percent.isFinite, percent >= 0, percent <= 100 else {
                warnings.append("存在无法识别的利润规则"); continue
            }
            let matches = people.filter { AndroidNumericIdentity.exact($0.payload["id"]) == pid }
            guard matches.count == 1, let partner = matches.first, let name = partner.payload["name"]?.text,
                  !name.isEmpty else { warnings.append("利润规则对应合伙人身份不明确"); continue }
            if TXFundDataAdapter.number(partner.payload["enabled"]) != 1 {
                warnings.append("存在停用或状态未知合伙人的规则：" + name); continue
            }
            guard seen.insert(pid).inserted else { warnings.append("合伙人存在多条规则：" + name); continue }
            let flag = TXFundDataAdapter.number(p["is_settlement_center"]) ?? 0
            guard flag == 0 || flag == 1 else { warnings.append("资金中心标记不明确：" + name); continue }
            rows.append(.init(id: rule.syncId, partner: name, percent: percent, centerFlag: flag == 1))
        }
        let ordered = rows.sorted { $0.partner == $1.partner ? $0.id < $1.id : $0.partner < $1.partner }
        if rows.filter(\.centerFlag).count > 1 { warnings.append("存在多个资金中心标记，请在安卓核对") }
        if !rows.isEmpty && abs(rows.reduce(0) { $0 + $1.percent } - 100) > 0.000001 {
            warnings.append("可识别比例合计不是100%，请在安卓核对")
        }
        return .init(rows: ordered, warnings: Array(Set(warnings)).sorted())
    }
}

struct TXProfitRuleDirectoryView: View {
    @EnvironmentObject private var model: LedgerViewModel
    private var report: TXProfitRuleReadout { TXProfitRuleReadout.inspect(model.snapshot) }
    var body: some View {
        List {
            Section {
                Text(model.selectedBook?.name ?? "未选择账本")
                Text("仅展示已同步的当前利润规则，不推算历史利润，也不修改资金中心或分配比例。")
                    .font(.caption).foregroundColor(.secondary)
                if model.isUsingCachedAuthorization || model.errorMessage != nil {
                    Text("当前显示缓存资料").font(.caption).foregroundColor(.orange)
                }
            }
            if model.lastSyncAt == nil || report.rows.isEmpty {
                Text("暂无可识别规则；可能尚未同步或账号缺少查看权限。").font(.caption)
            } else {
                Section("当前已识别比例") {
                    ForEach(report.rows) { row in
                        HStack {
                            VStack(alignment: .leading) {
                                Text(row.partner)
                                if row.centerFlag { Text("资金中心标记").font(.caption).foregroundColor(.secondary) }
                            }
                            Spacer()
                            Text(row.percent.formatted(.number.precision(.fractionLength(0...4))) + "%")
                        }
                    }
                    HStack {
                        Text("可识别合计")
                        Spacer()
                        Text(report.total.formatted(.number.precision(.fractionLength(0...4))) + "%").bold()
                    }
                }
            }
            if !report.warnings.isEmpty {
                Section("需要核对") {
                    ForEach(report.warnings, id: \.self) { Text($0).font(.caption).foregroundColor(.orange) }
                }
            }
        }
        .navigationTitle("利润分配规则")
        .navigationBarTitleDisplayMode(.inline)
        .refreshable { await model.refresh() }
    }
}
