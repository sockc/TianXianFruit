import SwiftUI

struct TXLedgerV33FundRow: Decodable, Identifiable {
    let partner_sync_id: String
    let partner_name: String
    let is_center: Bool
    let current_balance: String
    let range_start_date: String
    let business_day_count: Int
    var id: String { partner_sync_id }
}
struct TXLedgerV33FundReport: Decodable {
    let book_id: String
    let date: String
    let sync_cursor: Int64
    let scope: String
    let rows: [TXLedgerV33FundRow]
    func agrees(with snapshot: ReadOnlySnapshot) -> Bool {
        let local = TXFundDataAdapter.evaluate(snapshot, until: date)
        guard local.blocker == nil, local.rows.count == rows.count, !rows.isEmpty,
              Set(rows.map(\.id)).count == rows.count, rows.filter(\.is_center).count == 1 else { return false }
        return rows.allSatisfy { row in
            guard let amount = Double(row.current_balance), amount.isFinite,
                  let other = local.rows.first(where: { $0.id == row.id }),
                  other.isCenter == row.is_center else { return false }
            return abs(other.balance - amount) < 0.005
        }
    }
}
struct TXLedgerV33FundPanel: View {
    @EnvironmentObject private var model: LedgerViewModel
    let date: String
    @State private var report: TXLedgerV33FundReport?
    @State private var reportKey = ""
    @State private var enabled = false
    @State private var message = ""
    @State private var confirm: TXLedgerV33FundRow?
    @State private var undoBatch = ""
    @State private var undoKey = ""
    @State private var showUndo = false
    private var key: String {
        [model.account?.username ?? "", model.selectedBookID ?? "", date,
         String(model.currentCursor), String(model.isUsingCachedAuthorization)].joined(separator: "|")
    }
    private var batches: [String] {
        let rows = model.snapshot.records.values.filter { $0.tableName == "settlement_transfer" &&
            $0.payload["date"]?.text == date &&
            TXFundDataAdapter.number($0.payload["settlement_id"]) == 0 &&
            ["CUTOFF", "CUTOFF_CENTER"].contains($0.payload["settlement_kind"]?.text ?? "") &&
            (TXFundDataAdapter.number($0.payload["settled_amount"]) ?? 0) > 0.005 }
        return Set(rows.compactMap { $0.payload["batch_key"]?.text }.filter { !$0.isEmpty }).sorted()
    }
    private func reload() async {
        let scope = key
        report = nil; reportKey = ""; enabled = false
        do {
            let value = try await model.v33FundReport(date: date)
            let allow = await model.v33Capability("cutoff")
            guard scope == key else { return }
            report = value; reportKey = scope; enabled = allow
        } catch { if scope == key { message = error.localizedDescription } }
    }
    var body: some View {
        if TXTestBookAccess.isCandidate(model.selectedBook, account: model.account, offline: model.isUsingCachedAuthorization) {
            VStack(alignment: .leading, spacing: 7) {
                HStack {
                    Text("服务端累计余额 · 联合测试").font(.subheadline.bold())
                    Spacer()
                    Button("核对") { Task { message = ""; await reload() } }.font(.caption)
                        .disabled(model.isLedgerV33Busy || model.isBusy)
                }
                if let r = report, reportKey == key {
                    let agrees = r.agrees(with: model.snapshot)
                    Text(agrees ? "服务端与本机重算一致，仍需安卓实机核验。" : "服务端与本机重算不一致，禁止周期结清。")
                        .font(.caption2).foregroundColor(agrees ? .secondary : .orange)
                    ForEach(r.rows) { row in
                        HStack {
                            Text(row.partner_name + (row.is_center ? " · 资金中心" : "")).font(.caption)
                            Spacer()
                            Text(row.current_balance + " 元").font(.subheadline.bold())
                            if enabled && agrees && !row.is_center && abs(Double(row.current_balance) ?? 0) > 0.005 {
                                Button("结清") { confirm = row }.font(.caption)
                                    .disabled(model.v33HasPending || model.isLedgerV33Busy || model.isBusy)
                            }
                        }
                    }
                    if enabled && agrees && !batches.isEmpty {
                        Menu("撤销本日周期结清") {
                            ForEach(batches, id: \.self) { batch in
                                Button(batch) { undoBatch = batch; undoKey = key; showUndo = true }
                            }
                        }.font(.caption).disabled(model.v33HasPending || model.isLedgerV33Busy || model.isBusy)
                    }
                }
                if model.v33HasPending {
                    Button("核对原操作") { Task { message = await model.v33Recover(); await reload() } }
                        .font(.caption).disabled(model.isLedgerV33Busy || model.isBusy)
                }
                if !message.isEmpty { Text(message).font(.caption2).foregroundColor(.secondary) }
            }
            .padding(10).background(Color(UIColor.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 10))
            .task(id: key) { confirm = nil; showUndo = false; await reload() }
            .sheet(item: $confirm) { row in
                VStack(alignment: .leading, spacing: 12) {
                    Text("确认实际转账登记").font(.headline)
                    Text(row.partner_name + " · 截至 " + date + " · " + row.current_balance + " 元")
                    Text("仅测试账本。确认表示实际款项已转账，不是计划转账。").font(.caption)
                    Button("确认已实际转账") {
                        guard let r = report, reportKey == key else { confirm = nil; return }
                        confirm = nil
                        Task { message = await model.v33SubmitCutoff(report: r, row: row); await reload() }
                    }.buttonStyle(.borderedProminent)
                    Button("取消") { confirm = nil }
                }.padding()
            }
            .alert("撤销周期结清登记？", isPresented: $showUndo) {
                Button("取消", role: .cancel) {}
                Button("确认撤销", role: .destructive) {
                    guard undoKey == key else { return }
                    let batch = undoBatch, cursor = model.currentCursor
                    Task { message = await model.v33SubmitUndo(date: date, batch: batch, expectedCursor: cursor); await reload() }
                }
            } message: { Text("恢复账本未结金额，不会自动退回真实资金；保留原结清历史。") }
        }
    }
}
