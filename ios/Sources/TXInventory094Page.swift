import SwiftUI
import CryptoKit

/// Local-only draft store, scoped to account/book/day/product/unit.
/// Never enqueue a sync event or present drafts as saved inventory.
struct TXInventory094Draft: Codable, Equatable {
    let key: TXInventory094Policy.Key
    let basis: String
    let remaining: String
    let loss: String
}

enum TXInventory094DraftStore {
    static func url(scope: String) throws -> URL {
        let root = FileManager.default.urls(for: .applicationSupportDirectory,
                                            in: .userDomainMask)[0]
            .appendingPathComponent("InventoryLocalDrafts094", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let hash = SHA256.hash(data: Data(scope.utf8)).map { String(format: "%02x", $0) }.joined()
        return root.appendingPathComponent(hash + ".json")
    }
    static func load(scope: String) throws -> [TXInventory094Draft] {
        let file = try url(scope: scope)
        guard FileManager.default.fileExists(atPath: file.path) else { return [] }
        let values = try JSONDecoder().decode([TXInventory094Draft].self, from: Data(contentsOf: file))
        guard Set(values.map(\.key)).count == values.count else {
            throw CocoaError(.fileReadCorruptFile)
        }
        return values
    }
    static func save(_ rows: [TXInventory094Draft], scope: String) throws {
        try JSONEncoder().encode(rows).write(to: url(scope: scope),
                                              options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }
    static func valid(_ raw: String) -> Bool {
        guard let n = Double(raw), n.isFinite, n >= 0, n <= 1000000 else { return false }
        return raw.range(of: #"^\d+(\.\d{1,3})?$"#, options: .regularExpression) != nil
    }
}

struct TXInventory094Page: View {
    @EnvironmentObject private var model: LedgerViewModel
    @State private var day = Date()
    @State private var search = ""
    @State private var report: TXInventory094Policy.Report?
    @State private var drafts: [TXInventory094Draft] = []
    @State private var prices: [TXInventory095PriceDraft] = []
    @State private var priceEditor: TXInventory094Policy.Row?
    @State private var loadedRevision = ""
    @State private var loadedScope = ""
    @State private var error = ""
    @State private var storageOK = false
    @State private var autoEnabled = false
    @State private var freshAutosaveSession = false
    @State private var autosaveDelay: Task<Void, Never>?
    @State private var autoMessage = ""

    private var date: String { BusinessDates.key(day) }
    private var scope: String {
        [model.account?.username ?? "", model.selectedBookID ?? "", date].joined(separator: "|")
    }
    private var revision: String {
        scope + "|" + String(model.currentCursor) + "|" + String(model.lastSyncAt?.timeIntervalSince1970 ?? 0)
    }
    private var editable: Bool {
        loadedScope == scope && loadedRevision == revision && storageOK && !model.isLedgerV33Busy && !model.v33HasPending && report?.issues.isEmpty == true &&
        TXTestBookAccess.isCandidate(model.selectedBook, account: model.account,
                                     offline: model.isUsingCachedAuthorization)
    }

    private func reload() {
        autosaveDelay?.cancel(); autosaveDelay = nil
        loadedScope = ""; loadedRevision = ""; freshAutosaveSession = false
        report = nil; priceEditor = nil
        drafts = []; prices = []; error = ""; storageOK = false
        guard model.account != nil, model.selectedBookID != nil else { return }
        do {
            drafts = try TXInventory094DraftStore.load(scope: scope)
            prices = try TXInventory095PriceStore.load(scope: scope)
            loadedScope = scope; storageOK = true
            freshAutosaveSession = drafts.isEmpty && prices.isEmpty
        } catch { self.error = "本地库存或价格草稿读取失败，已禁用编辑；原文件未覆盖" }
        loadedRevision = revision
        report = model.lastSyncAt == nil ? nil :
            TXInventory094Policy.evaluate(model.snapshot, date: date, prices: prices)
    }

    /// Only fresh edits schedule auto-submit. Existing drafts restored from disk
    /// are never silently uploaded. Delay is cancellable; the durable operation
    /// itself is not attached to a view task, so navigation cannot cancel it.
    private func queueAutosave() {
        autosaveDelay?.cancel()
        guard autoEnabled, freshAutosaveSession, editable else { return }
        let originalScope = scope, originalRevision = revision
        let originalDate = date, cursor = model.currentCursor
        autosaveDelay = Task { @MainActor in
            do { try await Task.sleep(nanoseconds: 1_500_000_000) }
            catch { return }
            guard !Task.isCancelled, TXLedgerV33AutosavePolicy.canSubmit(enabled: autoEnabled,
                  fresh: freshAutosaveSession, originalScope: originalScope, currentScope: scope,
                  originalRevision: originalRevision, currentRevision: revision,
                  editable: editable, busy: model.isBusy || model.isLedgerV33Busy,
                  pending: model.v33HasPending),
                  !model.isLedgerV33Busy, !model.v33HasPending else { return }
            // Do not cancel an already submitted operation when the view
            // disappears or the cursor advances during readback.
            Task { @MainActor in
                let result = await model.v33SubmitInventory(date: originalDate, automatic: true,
                    expectedScope: originalScope, expectedCursor: cursor)
                guard scope == originalScope else { return }
                reload()
                autoMessage = result
            }
        }
    }

    private func save(_ draft: TXInventory094Draft) {
        guard editable, TXInventory094DraftStore.valid(draft.remaining),
              TXInventory094DraftStore.valid(draft.loss) else { return }
        var next = drafts.filter { $0.key != draft.key }
        next.append(draft)
        do {
            try TXInventory094DraftStore.save(next, scope: scope)
            drafts = next; error = ""; queueAutosave()
        } catch { self.error = "本地草稿保存失败，请保留输入并重试"; storageOK = false }
    }

    private func savePrice(_ values: [TXInventory095PriceDraft], expectedRevision: String) {
        guard editable, expectedRevision == revision else {
            error = "账本或云端记录已变化，请重新打开价格编辑"; return
        }
        var next = prices
        for value in values {
            guard value.date == date, TXInventory095PriceDraft.valid(value.value),
                  let row = report?.rows.first(where: { $0.key.product == value.product &&
                      (value.kind == .retail || $0.key.unit == value.unit) }),
                  value.basis == (value.kind == .cost ? row.manualBasis : row.retailBasis)
            else { error = "原始价格记录存在冲突，未保存"; return }
            next.removeAll { $0.id == value.id }
            next.append(value)
        }
        do {
            try TXInventory095PriceStore.save(next, scope: scope)
            prices = next
            report = TXInventory094Policy.evaluate(model.snapshot, date: date, prices: prices)
            priceEditor = nil; error = ""; queueAutosave()
        } catch { self.error = "价格草稿保存失败，未覆盖原记录" }
    }

    private func summary(_ rows: [TXInventory094Policy.Row]) -> some View {
        let values = rows.map { row in
            TXInventory095Valuation.item(row, draft: drafts.first { $0.key == row.key })
        }
        let eligible = values.filter { $0.counted && !$0.anomaly }
        let known = eligible.filter { $0.closingCost != nil }
        let missing = values.count - known.count
        return VStack(alignment: .leading, spacing: 5) {
            Text("库存估值 · 已盘点已知成本").font(.subheadline.bold())
            HStack {
                VStack(alignment: .leading) {
                    Text("剩余库存").font(.caption2).foregroundColor(.secondary)
                    Text(known.isEmpty ? "—" : known.compactMap(\.closingCost).reduce(0,+)
                        .formatted(.currency(code: "CNY"))).font(.headline)
                }
                Spacer()
                VStack(alignment: .trailing) {
                    Text("损耗成本").font(.caption2).foregroundColor(.secondary)
                    Text(known.isEmpty ? "—" : known.compactMap(\.lossCost).reduce(0,+)
                        .formatted(.currency(code: "CNY"))).font(.headline)
                }
            }
            if missing > 0 {
                Text("\(missing) 项未盘点、缺成本或数量异常；仅显示已知金额")
                    .font(.caption2).foregroundColor(.orange)
            }
            if values.contains(where: { $0.local }) {
                Text("含本地草稿预估，未同步，不计入正式利润")
                    .font(.caption2).foregroundColor(.orange)
            }
        }.padding(10).background(TXTheme.header, in: RoundedRectangle(cornerRadius: 10))
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    Text("库存表").font(.headline)
                    Spacer()
                    Button { Task { await model.refresh() } } label: {
                        Label("同步", systemImage: "arrow.clockwise").font(.caption)
                    }.disabled(model.isBusy)
                }
                TXDayBar(date: $day, label: "盘点日期", allowFuture: false)
                TextField("查找商品", text: $search)
                    .textFieldStyle(.roundedBorder)
                Text(editable ? "测试账本 · 本地草稿自动保存，尚未同步到安卓" :
                     "云端库存只读；库存写入将在联合服务端升级后开放")
                    .font(.caption2).foregroundColor(.secondary)
                if !error.isEmpty { Text(error).font(.caption).foregroundColor(.red) }
                TXLedgerV33InventoryActions(date: date, count: drafts.count + prices.count, onReload: reload)
                if autoEnabled {
                    Text(freshAutosaveSession ? "测试账本云端自动保存已启用 · 输入停止后提交" : "有上次遗留草稿，请先手动核对并同步；不会静默上传旧草稿")
                        .font(.caption2).foregroundColor(.secondary)
                }
                if !autoMessage.isEmpty { Text(autoMessage).font(.caption2).foregroundColor(.secondary) }
                if let report, loadedRevision == revision {
                    summary(report.rows)
                    NavigationLink(destination: TXInventory096Detail(initialDate: day)) {
                        Label("商品利润与库存经营分析", systemImage: "chart.bar.xaxis")
                            .font(.caption.bold())
                    }
                    if !report.issues.isEmpty {
                        DisclosureGroup("存在库存数据缺口，已暂停草稿编辑") {
                            ForEach(report.issues, id: \.self) { Text($0).font(.caption) }
                        }.foregroundColor(.orange).font(.caption.bold())
                    }
                    let rows = report.rows.filter { search.isEmpty || $0.name.localizedCaseInsensitiveContains(search) }
                    HStack {
                        Text("\(rows.count) 项商品").font(.caption.bold())
                        Spacer()
                        Text("已同步 \(rows.filter { $0.savedID != nil }.count) · 本地草稿 \(drafts.count)")
                            .font(.caption2).foregroundColor(.secondary)
                    }
                    if rows.isEmpty {
                        Text("所选日期暂无可展示库存，未保存不等于实际库存为零")
                            .font(.caption).foregroundColor(.secondary).padding(.vertical, 10)
                    }
                    if prices.contains(where: { value in
                        !report.rows.contains { row in
                            row.key.product == value.product &&
                            (value.kind == .retail || row.key.unit == value.unit) &&
                            value.basis == (value.kind == .cost ? row.manualBasis : row.retailBasis)
                        }
                    }) {
                        Text("存在与云端版本不一致的价格草稿，未用于估值；原草稿保留。")
                            .font(.caption2).foregroundColor(.orange)
                    }
                    LazyVStack(spacing: 8) {
                        ForEach(rows) { row in
                            TXInventory094Card(row: row, draft: drafts.first { $0.key == row.key },
                                                editable: editable, onSave: save,
                                                onPrice: { priceEditor = row })
                                .id(revision + "|" + row.key.product + "|" + row.key.unit)
                        }
                    }
                    if drafts.contains(where: { draft in !report.rows.contains { $0.key == draft.key } }) {
                        Text("部分本地草稿的商品已不在当前云端库存中；草稿保留，需核对后再处理。")
                            .font(.caption2).foregroundColor(.orange)
                    }
                } else {
                    Text("尚未同步库存与采购数据").font(.caption).foregroundColor(.secondary)
                }
                SyncFooter()
            }.padding(12)
        }
        .task(id: revision) {
            autoEnabled = false
            reload()
            let original = revision
            let allowed = await model.v33Capability("inventory_auto")
            if original == revision { autoEnabled = allowed }
        }
        .onChange(of: scope) { _ in
            autosaveDelay?.cancel(); autoMessage = ""; autoEnabled = false
        }
        .onDisappear { autosaveDelay?.cancel() }
        .sheet(item: $priceEditor) { row in
            TXInventory095PriceEditor(row: row, date: date, revision: revision,
                onSave: savePrice)
        }
    }
}

private struct TXInventory094Card: View {
    let row: TXInventory094Policy.Row
    let draft: TXInventory094Draft?
    let editable: Bool
    let onSave: (TXInventory094Draft) -> Void
    let onPrice: () -> Void
    @State private var remaining: String
    @State private var loss: String

    init(row: TXInventory094Policy.Row, draft: TXInventory094Draft?, editable: Bool,
         onSave: @escaping (TXInventory094Draft) -> Void, onPrice: @escaping () -> Void) {
        self.row = row; self.draft = draft; self.editable = editable; self.onSave = onSave
        self.onPrice = onPrice
        _remaining = State(initialValue: draft?.remaining ?? Self.text(row.remaining))
        _loss = State(initialValue: draft?.loss ?? Self.text(row.loss))
    }
    private static func text(_ n: Double) -> String {
        String(format: "%.3f", n).replacingOccurrences(of: #"\.?0+$"#, with: "", options: .regularExpression)
    }
    private var conflict: Bool { draft.map { $0.basis != row.basis } ?? false }
    private var valid: Bool { TXInventory094DraftStore.valid(remaining) && TXInventory094DraftStore.valid(loss) }
    private func save() {
        guard editable, !conflict, valid else { return }
        onSave(TXInventory094Draft(key: row.key, basis: row.basis,
                                   remaining: remaining, loss: loss))
    }
    private func tile(_ label: String, _ value: Double) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(label).font(.caption2).foregroundColor(.secondary)
            Text(Self.text(value)).font(.caption.bold())
        }.frame(maxWidth: .infinity, alignment: .leading)
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 7) {
            HStack(spacing: 6) {
                Text(row.name).font(.subheadline.bold())
                Text(row.key.unit).font(.caption).foregroundColor(.secondary)
                Spacer(minLength: 2)
                Text(row.purchased > 0.000001 ? "今日有采购" : "无采购")
                    .font(.caption2).foregroundColor(row.purchased > 0.000001 ? TXTheme.primary : .primary)
                if row.savedID != nil { Image(systemName: "checkmark").foregroundColor(TXTheme.primary).font(.caption) }
            }
            HStack(spacing: 5) {
                Text("成本 " + (row.cost.map { Self.text($0) + "元/" + row.key.unit } ?? "缺失"))
                Text("零售 " + (row.retail.map { Self.text($0) + "元/" + row.retailUnit } ?? "未设"))
                Spacer(minLength: 0)
                if editable { Button("价格", action: onPrice).font(.caption.bold()) }
            }.font(.caption2).foregroundColor(.secondary)
            if row.usesLocalCost || row.usesLocalRetail {
                Text("价格本地草稿 · 未同步").font(.caption2).foregroundColor(.orange)
            }
            if let weight = row.weight {
                Text("规格 " + Self.text(weight) + "斤/" + row.key.unit)
                    .font(.caption2).foregroundColor(.secondary)
            }
            HStack(spacing: 4) {
                tile("结转", row.opening)
                tile("今日采购", row.purchased)
                tile("可售合计", row.available)
                if editable && !conflict {
                    VStack(alignment: .leading, spacing: 3) {
                        Text("剩余").font(.caption2).foregroundColor(.secondary)
                        TextField("0", text: $remaining).keyboardType(.decimalPad)
                            .textFieldStyle(.roundedBorder).font(.caption)
                            .onChange(of: remaining) { _ in save() }
                    }.frame(maxWidth: .infinity)
                    VStack(alignment: .leading, spacing: 3) {
                        Text("损耗").font(.caption2).foregroundColor(.secondary)
                        TextField("0", text: $loss).keyboardType(.decimalPad)
                            .textFieldStyle(.roundedBorder).font(.caption)
                            .onChange(of: loss) { _ in save() }
                    }.frame(maxWidth: .infinity)
                } else {
                    tile("剩余", row.remaining)
                    tile("损耗", row.loss)
                }
            }
            let value = TXInventory095Valuation.item(row, draft: draft)
            HStack(spacing: 5) {
                Text("加权成本 " + (row.averageCost.map { Self.text($0) } ?? "未知"))
                Spacer(minLength: 0)
                Text("剩余估值 " + (value.counted && !value.anomaly
                    ? (value.closingCost.map { $0.formatted(.currency(code: "CNY")) } ?? "未知")
                    : "待盘点"))
            }.font(.caption2).foregroundColor(.secondary)
            if conflict {
                Text("云端库存或采购已变化，原草稿剩余 " + (draft?.remaining ?? "") +
                     " / 损耗 " + (draft?.loss ?? "") + " 保留待核对，未覆盖云端。")
                    .font(.caption2).foregroundColor(.orange)
            } else if draft != nil {
                Text("本地草稿 · 未同步").font(.caption2).foregroundColor(.orange)
            } else if row.savedID == nil {
                Text("未盘点").font(.caption2).foregroundColor(.secondary)
            }
            if editable && !valid {
                Text("请输入非负数量，最多三位小数").font(.caption2).foregroundColor(.red)
            }
            if editable && valid, let r = Double(remaining), let l = Double(loss), r + l > row.available + 0.000001 {
                Text("剩余加损耗超过可售数量，请核对").font(.caption2).foregroundColor(.orange)
            }
        }
        .padding(10)
        .background(Color(UIColor.secondarySystemGroupedBackground),
                    in: RoundedRectangle(cornerRadius: 10))
    }
}

private struct TXInventory095PriceEditor: View {
    @Environment(\.dismiss) private var dismiss
    let row: TXInventory094Policy.Row
    let date: String
    let revision: String
    let onSave: ([TXInventory095PriceDraft], String) -> Void
    @State private var cost = ""
    @State private var retail = ""
    @State private var retailUnit = "斤"
    @State private var editCost = false
    @State private var editRetail = false

    private var valid: Bool {
        (editCost || editRetail) &&
        (!editCost || (row.manualBasis != nil && TXInventory095PriceDraft.valid(cost))) &&
        (!editRetail || (row.retailBasis != nil && TXInventory095PriceDraft.valid(retail) &&
            !retailUnit.trimmingCharacters(in: .whitespaces).isEmpty && retailUnit.count <= 16))
    }

    var body: some View {
        NavigationView {
            Form {
                Section(header: Text(row.name + " · " + date)) {
                    Toggle("修改手工参考成本", isOn: $editCost)
                        .disabled(row.manualBasis == nil)
                    if editCost {
                        TextField("元/" + row.key.unit, text: $cost).keyboardType(.decimalPad)
                    }
                    Text("有效采购价优先；参考成本仅在没有有效采购价时使用。本地预览限所选日期，不修改历史进价。")
                        .font(.caption).foregroundColor(.secondary)
                }
                Section(header: Text("每日零售价")) {
                    Toggle("修改当天零售价", isOn: $editRetail)
                        .disabled(row.retailBasis == nil)
                    if editRetail {
                        TextField("零售价", text: $retail).keyboardType(.decimalPad)
                        TextField("零售单位（如斤、件）", text: $retailUnit)
                    }
                    Text("按商品与日期保存，跨采购单位共用同一零售价；不回写其他日期。")
                        .font(.caption).foregroundColor(.secondary)
                }
                Section {
                    Button("保存本地价格草稿") {
                        var values: [TXInventory095PriceDraft] = []
                        if editCost, let basis = row.manualBasis {
                            values.append(.init(kind: .cost, product: row.key.product, unit: row.key.unit,
                                date: date, value: cost, basis: basis))
                        }
                        if editRetail, let basis = row.retailBasis {
                            values.append(.init(kind: .retail, product: row.key.product,
                                unit: retailUnit.trimmingCharacters(in: .whitespaces),
                                date: date, value: retail, basis: basis))
                        }
                        onSave(values, revision)
                    }.disabled(!valid)
                    Text("尚未同步到安卓，不会修改正式账本。").font(.caption).foregroundColor(.orange)
                }
            }
            .navigationTitle("库存价格")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } } }
        }
        .navigationViewStyle(.stack)
        .onAppear {
            cost = row.manualCost.map { String(format: "%.3f", $0) } ?? ""
            retail = row.retail.map { String(format: "%.3f", $0) } ?? ""
            retailUnit = row.retailUnit
        }
    }
}
