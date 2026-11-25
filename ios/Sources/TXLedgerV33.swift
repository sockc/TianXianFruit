import Foundation
import SwiftUI

struct TXLedgerV33Capabilities: Decodable {
    let version: Int
    let scope: String
    let atomic: Bool
    let idempotency: Bool
    let operation_lookup: Bool
    let inventory: Bool
    let inventory_autosave: Bool?
    let daily_generate: Bool
    let profit_save: Bool?
    let authoritative_balance: Bool?
    let cutoff: Bool?
    func allows(_ feature: String) -> Bool {
        version == 1 && scope == "iOS_TEST_" && atomic && idempotency && operation_lookup &&
        (["inventory": inventory, "inventory_auto": inventory && inventory_autosave == true, "daily": daily_generate, "profit": profit_save == true, "balance": authoritative_balance == true, "cutoff": cutoff == true, "undo": cutoff == true][feature] == true)
    }
}

struct TXLedgerV33Line: Codable {
    let kind: String
    let fruit_sync_id: String
    let fruit_row_version: Int64
    let unit: String
    let expected_sync_id: String?
    let expected_row_version: Int64
    let amount: String
    let loss: String?
}

struct TXLedgerV33Allocation: Codable {
    let partner_sync_id: String
    let percent: String
}

struct TXLedgerV33Body: Codable {
    let protocol_version: Int
    let book_id: String
    let account: String
    let operation_id: String
    let device_id: String
    let date: String
    let expected_cursor: Int64
    let lines: [TXLedgerV33Line]?
    let allocations: [TXLedgerV33Allocation]?
    var partner_sync_id: String? = nil
    var expected_balance: String? = nil
    var batch_key: String? = nil
}

struct TXLedgerV33Proposal: Codable {
    let feature: String
    let body: TXLedgerV33Body
    let stockDrafts: [TXInventory094Draft]
    let priceDrafts: [TXInventory095PriceDraft]
    var scope: String { [body.account, body.book_id, body.date].joined(separator: "|") }
    var matchesFeature: Bool {
        (feature == "inventory" && body.lines?.isEmpty == false && body.allocations == nil) ||
        ((feature == "daily" || feature == "profit") && body.lines == nil && body.allocations != nil) ||
        (feature == "cutoff" && body.lines == nil && body.allocations == nil && body.partner_sync_id?.isEmpty == false && body.expected_balance != nil) ||
        (feature == "undo" && body.lines == nil && body.allocations == nil && body.batch_key?.isEmpty == false)
    }
}

struct TXLedgerV33Receipt: Codable {
    struct Change: Codable {
        let table_name: String
        let sync_id: String
        let row_version: Int64
        let operation: String
        var key: String { table_name + "|" + sync_id }
    }
    let status: String
    let feature: String
    let book_id: String
    let operation_id: String
    let date: String
    let sync_cursor: Int64
    let changes: [Change]
    let request_hash: String
    func matches(_ p: TXLedgerV33Proposal) -> Bool {
        let tables: Set<String> = feature == "inventory" ?
            ["inventory_snapshot", "product_cost_reference", "daily_retail_price"] :
            ["profit_distribution", "daily_cash_settlement", "settlement_partner", "settlement_transfer"]
        return ["committed", "already_committed"].contains(status) &&
            feature == p.feature && book_id.lowercased() == p.body.book_id.lowercased() &&
            operation_id.lowercased() == p.body.operation_id.lowercased() && date == p.body.date &&
            sync_cursor > p.body.expected_cursor && request_hash.count == 64 &&
            !changes.isEmpty && Set(changes.map(\.key)).count == changes.count &&
            changes.allSatisfy { tables.contains($0.table_name) && !$0.sync_id.isEmpty &&
                $0.row_version > 0 && ["UPSERT", "DELETE"].contains($0.operation) } &&
            (feature != "inventory" || (changes.count == p.body.lines?.count &&
                changes.allSatisfy { $0.operation == "UPSERT" }))
    }
    func readback(_ p: TXLedgerV33Proposal, snapshot: ReadOnlySnapshot, cursor: Int64) -> Bool {
        guard matches(p), cursor >= sync_cursor,
              changes.allSatisfy({ change in
                  snapshot.versions[change.key] == change.row_version &&
                  (change.operation == "DELETE" ? snapshot.records[change.key] == nil :
                   snapshot.records[change.key]?.rowVersion == change.row_version)
              }) else { return false }
        if feature == "cutoff" || feature == "undo" {
            let rows = changes.compactMap { snapshot.records[$0.key] }
            guard rows.count == changes.count, rows.allSatisfy({
                $0.tableName == "settlement_transfer" && $0.payload["date"]?.text == p.body.date &&
                TXFundDataAdapter.number($0.payload["settlement_id"]) == 0 &&
                ["CUTOFF", "CUTOFF_CENTER"].contains($0.payload["settlement_kind"]?.text ?? "")
            }) else { return false }
            if feature == "undo" {
                return rows.allSatisfy { $0.payload["batch_key"]?.text == p.body.batch_key &&
                    TXFundDataAdapter.number($0.payload["settled_amount"]) == 0 }
            }
            guard rows.count == 1, let row = rows.first, let sid = p.body.partner_sync_id,
                  let partner = snapshot.records["partner|" + sid],
                  let pid = AndroidNumericIdentity.exact(partner.payload["id"]),
                  let balance = p.body.expected_balance.flatMap({ Double($0) }),
                  TXFundDataAdapter.number(row.payload["amount"]) == abs(balance),
                  TXFundDataAdapter.number(row.payload["settled_amount"]) == abs(balance),
                  AndroidNumericIdentity.exact(row.payload["focus_partner_id"]) == pid,
                  row.payload["batch_key"]?.text == p.body.operation_id else { return false }
            return true
        }
        guard feature == "inventory" else { return true }
        guard let lines = p.body.lines else { return false }
        return lines.allSatisfy { line in
            guard let product = snapshot.records["fruit|" + line.fruit_sync_id],
                  let fid = AndroidNumericIdentity.exact(product.payload["id"]) else { return false }
            let table = line.kind == "stock" ? "inventory_snapshot" :
                line.kind == "cost" ? "product_cost_reference" : "daily_retail_price"
            let matches = changes.compactMap { snapshot.records[$0.key] }.filter { event in
                let payload = event.payload
                return event.tableName == table &&
                    AndroidNumericIdentity.exact(payload["fruit_id"]) == fid &&
                    (line.kind == "retail" ? payload["price_unit"]?.text == line.unit :
                        payload["unit"]?.text == line.unit) &&
                    (line.kind == "cost" || payload["date"]?.text == p.body.date) &&
                    (line.expected_sync_id == nil || event.syncId == line.expected_sync_id)
            }
            guard matches.count == 1, let payload = matches.first?.payload,
                  let amount = Double(line.amount) else { return false }
            let field = line.kind == "stock" ? "remaining_quantity" :
                line.kind == "cost" ? "manual_unit_cost" : "price_per_jin"
            guard let actual = TXFundDataAdapter.number(payload[field]),
                  abs(actual - amount) < 0.000001 else { return false }
            if line.kind == "stock" {
                guard let loss = line.loss.flatMap { Double($0) },
                      let actualLoss = TXFundDataAdapter.number(payload["loss_quantity"]),
                      abs(loss - actualLoss) < 0.000001 else { return false }
            }
            return true
        }
    }
}

enum TXLedgerV33Policy {
    static func inventory(snapshot: ReadOnlySnapshot, date: String,
                          stocks: [TXInventory094Draft], prices: [TXInventory095PriceDraft]) throws -> [TXLedgerV33Line] {
        let report = TXInventory094Policy.evaluate(snapshot, date: date)
        guard report.issues.isEmpty else { throw TXSettlementAtomicError.stale }
        var lines: [TXLedgerV33Line] = []
        for draft in stocks {
            guard let row = report.rows.first(where: { $0.key == draft.key }),
                  row.basis == draft.basis,
                  TXInventory094DraftStore.valid(draft.remaining),
                  TXInventory094DraftStore.valid(draft.loss),
                  let remaining = Double(draft.remaining), let loss = Double(draft.loss),
                  remaining + loss <= row.available + 0.000001,
                  let product = snapshot.records["fruit|" + row.key.product], product.rowVersion > 0
            else { throw TXSettlementAtomicError.stale }
            lines.append(.init(kind: "stock", fruit_sync_id: row.key.product,
                fruit_row_version: product.rowVersion, unit: row.key.unit,
                expected_sync_id: row.savedID, expected_row_version: row.savedVersion ?? 0,
                amount: draft.remaining, loss: draft.loss))
        }
        for draft in prices {
            guard draft.date == date, TXInventory095PriceDraft.valid(draft.value),
                  let row = report.rows.first(where: { $0.key.product == draft.product &&
                      (draft.kind == .retail || $0.key.unit == draft.unit) }),
                  draft.basis == (draft.kind == .cost ? row.manualBasis : row.retailBasis),
                  let product = snapshot.records["fruit|" + draft.product], product.rowVersion > 0,
                  let fid = AndroidNumericIdentity.exact(product.payload["id"])
            else { throw TXSettlementAtomicError.stale }
            let table = draft.kind == .cost ? "product_cost_reference" : "daily_retail_price"
            let matches = snapshot.records.values.filter { event in
                event.tableName == table &&
                AndroidNumericIdentity.exact(event.payload["fruit_id"]) == fid &&
                (draft.kind == .cost ? event.payload["unit"]?.text == draft.unit :
                    event.payload["date"]?.text == date)
            }
            guard TXInventory095PriceDraft.basis(Array(matches)) == draft.basis else {
                throw TXSettlementAtomicError.stale
            }
            let prior = matches.first
            lines.append(.init(kind: draft.kind == .cost ? "cost" : "retail",
                fruit_sync_id: draft.product, fruit_row_version: product.rowVersion,
                unit: draft.unit, expected_sync_id: prior?.syncId,
                expected_row_version: prior?.rowVersion ?? 0, amount: draft.value, loss: nil))
        }
        guard !lines.isEmpty && lines.count <= 100 else { throw TXSettlementAtomicError.stale }
        return lines
    }
}

enum TXLedgerV33Store {
    private static func url(_ account: String, _ book: String) throws -> URL {
        try TXInventory094DraftStore.url(scope: "ledger-v33|" + account.lowercased() + "|" + book.lowercased())
    }
    static func load(_ account: String, _ book: String) throws -> TXLedgerV33Proposal? {
        let file = try url(account, book)
        guard FileManager.default.fileExists(atPath: file.path) else { return nil }
        let p = try JSONDecoder().decode(TXLedgerV33Proposal.self, from: Data(contentsOf: file))
        guard p.body.account.lowercased() == account.lowercased(),
              p.body.book_id.lowercased() == book.lowercased(),
              UUID(uuidString: p.body.operation_id) != nil, p.matchesFeature else {
            throw TXSettlementAtomicError.storage
        }
        return p
    }
    static func save(_ p: TXLedgerV33Proposal) throws {
        try JSONEncoder().encode(p).write(to: url(p.body.account, p.body.book_id),
            options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }
    static func clear(_ p: TXLedgerV33Proposal) throws {
        let file = try url(p.body.account, p.body.book_id)
        if FileManager.default.fileExists(atPath: file.path) { try FileManager.default.removeItem(at: file) }
    }
}

/// An explicit draft-sync entry in the development candidate. Auto-save to
/// cloud is deferred until Android logical-key compatibility is verified.
struct TXLedgerV33InventoryActions: View {
    @EnvironmentObject private var model: LedgerViewModel
    let date: String
    let count: Int
    let onReload: () -> Void
    @State private var message = ""
    @State private var enabled = false
    @State private var pending = false
    private var scope: String { (model.account?.username ?? "") + "|" + (model.selectedBookID ?? "") }
    private func refresh() async {
        enabled = await model.v33Capability("inventory")
        pending = model.v33HasPending
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            if enabled && count > 0 {
                Button("同步待提交记录（\(count)项）") {
                    Task {
                        message = await model.v33SubmitInventory(date: date)
                        pending = model.v33HasPending
                        onReload()
                    }
                }.buttonStyle(.borderedProminent)
                .disabled(count == 0 || pending || model.v33HasPending || model.isLedgerV33Busy || model.isBusy)
            }
            if pending || model.v33HasPending {
                Button("核对上次库存／结算操作") {
                    Task {
                        message = await model.v33Recover()
                        pending = model.v33HasPending
                        onReload()
                    }
                }.disabled(model.isLedgerV33Busy || model.isBusy)
            }
            if !message.isEmpty { Text(message).font(.caption2).foregroundColor(.secondary) }
        }.task(id: scope) { message = ""; await refresh() }
    }
}

struct TXLedgerV33DailyActions: View {
    @EnvironmentObject private var model: LedgerViewModel
    let date: String
    @State private var enabled = false
    @State private var pending = false
    @State private var message = ""
    @State private var showEditor = false
    @State private var custom = false
    @State private var profitEnabled = false
    @State private var percents: [String: String] = [:]
    @State private var editorKey = ""
    private var scope: String { (model.account?.username ?? "") + "|" + (model.selectedBookID ?? "") + "|" + date }
    private var key: String { scope + "|" + String(model.currentCursor) }
    private var partners: [SyncEvent] {
        model.snapshot.records.values.filter { $0.tableName == "partner" &&
            TXFundDataAdapter.number($0.payload["enabled"]) == 1 &&
            AndroidNumericIdentity.exact($0.payload["id"]) != nil }
            .sorted { (AndroidNumericIdentity.exact($0.payload["id"]) ?? 0) <
                      (AndroidNumericIdentity.exact($1.payload["id"]) ?? 0) }
    }
    private var valid: Bool {
        if !custom { return true }
        let values = partners.compactMap { item -> Double? in
            guard let raw = percents[item.syncId], TXInventory095PriceDraft.valid(raw),
                  let value = Double(raw), value <= 100 else { return nil }
            return value
        }
        return !values.isEmpty && values.count == partners.count &&
            abs(values.reduce(0,+) - 100) < 0.01
    }
    private func refresh() async {
        enabled = await model.v33Capability("daily")
        profitEnabled = await model.v33Capability("profit")
        pending = model.v33HasPending
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            if enabled {
                Button("生成并保存当日结算") {
                    editorKey = key
                    custom = false; percents = [:]
                    for partner in partners {
                        let id = AndroidNumericIdentity.exact(partner.payload["id"])
                        let rules = model.snapshot.records.values.filter {
                            $0.tableName == "profit_rule" && AndroidNumericIdentity.exact($0.payload["partner_id"]) == id
                        }
                        if rules.count == 1, let percent = TXFundDataAdapter.number(rules.first?.payload["percent"]) {
                            percents[partner.syncId] = String(format: "%.3f", percent)
                        }
                    }
                    showEditor = true
                }.buttonStyle(.borderedProminent)
                 .disabled(pending || model.isLedgerV33Busy || model.isBusy)
            }
            if pending || model.v33HasPending {
                Button("核对上次库存／结算操作") {
                    Task { message = await model.v33Recover(); pending = model.v33HasPending }
                }.disabled(model.isLedgerV33Busy || model.isBusy)
            }
            if !message.isEmpty { Text(message).font(.caption2).foregroundColor(.secondary) }
        }
        .task(id: scope) { message = ""; await refresh() }
        .sheet(isPresented: $showEditor) {
            NavigationView {
                Form {
                    Section(header: Text("营业日 " + date)) {
                        Toggle("重新指定利润分配比例", isOn: $custom)
                        if custom {
                            ForEach(partners, id: \.syncId) { partner in
                                HStack {
                                    Text(partner.payload["name"]?.text ?? "未知")
                                    Spacer()
                                    TextField("百分比", text: Binding(
                                        get: { percents[partner.syncId] ?? "" },
                                        set: { percents[partner.syncId] = $0 }))
                                        .keyboardType(.decimalPad).multilineTextAlignment(.trailing)
                                    Text("%")
                                }
                            }
                        } else {
                            Text("沿用当天已保存的利润／亏损分配。")
                        }
                        Text("仅测试账本。服务端按原始营业、采购及分配记录生成；已有实际转账时拒绝重建。")
                            .font(.caption).foregroundColor(.secondary)
                    }
                    Button("确认生成并保存") {
                        let allocations: [TXLedgerV33Allocation] = custom ? partners.map {
                            .init(partner_sync_id: $0.syncId, percent: percents[$0.syncId] ?? "")
                        } : []
                        guard editorKey == key else { message = "账本或源数据已变化，请重新打开"; showEditor = false; return }
                        showEditor = false
                        Task {
                            message = await model.v33SubmitDaily(date: date, allocations: allocations)
                            pending = model.v33HasPending
                        }
                    }.disabled(!valid || editorKey != key)
                    if profitEnabled && custom {
                        Button("只保存利润／亏损分配") {
                            guard editorKey == key else { showEditor = false; return }
                            let values: [TXLedgerV33Allocation] = partners.map {
                                .init(partner_sync_id: $0.syncId, percent: percents[$0.syncId] ?? "")
                            }
                            showEditor = false
                            Task {
                                message = await model.v33SubmitDaily(date: date, allocations: values, profitOnly: true)
                                pending = model.v33HasPending
                            }
                        }.disabled(!valid || editorKey != key)
                    }
                }.navigationTitle("生成日结")
                    .toolbar { ToolbarItem(placement: .cancellationAction) { Button("取消") { showEditor = false } } }
            }.navigationViewStyle(.stack)
        }
    }
}


enum TXLedgerV33AutosavePolicy {
    static func quantityText(_ n: Double) -> String {
        let raw = String(format: "%.3f", locale: Locale(identifier: "en_US_POSIX"), n)
        let result = raw.replacingOccurrences(of: #"\.?0+$"#, with: "", options: .regularExpression)
        return result.isEmpty || result == "-0" ? "0" : result
    }
    static func canSubmit(enabled: Bool, fresh: Bool,
                          originalScope: String, currentScope: String,
                          originalRevision: String, currentRevision: String,
                          editable: Bool, busy: Bool, pending: Bool) -> Bool {
        enabled && fresh && !originalScope.isEmpty && originalScope == currentScope &&
        originalRevision == currentRevision && editable && !busy && !pending
    }
}
