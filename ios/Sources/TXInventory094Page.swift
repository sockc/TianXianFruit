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
        guard Set(values.map(\.key)).count == values.count,
              values.allSatisfy({ valid($0.remaining) && valid($0.loss) && !$0.basis.isEmpty }) else {
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
    private var day: Date { model.workDate }
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
    @State private var cloudInventoryAllowed = false
    @State private var capabilityRevision = ""
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
        (TXTestBookAccess.isCandidate(model.selectedBook, account: model.account,
                                     offline: model.isUsingCachedAuthorization) ||
         (model.v34LocalCandidate && cloudInventoryAllowed && capabilityRevision == revision))
    }

    private var syncStatus: String {
        if model.isLedgerV33Busy { return "正在同步" }
        if model.v33HasPending { return "待核对上次同步结果" }
        if model.isUsingCachedAuthorization { return "离线缓存 · 暂停编辑" }
        if !storageOK { return "库存草稿不可用或尚未加载" }
        if report == nil || loadedRevision != revision { return "等待库存同步" }
        if report?.issues.isEmpty == false { return "历史数据异常 · 暂停编辑" }
        if !model.v34LocalCandidate { return "当前账号无库存编辑权限" }
        if !editable {
            return capabilityRevision != revision ? "正在确认库存授权" : "服务端库存写入未授权"
        }
        let count = drafts.count + prices.count
        if count > 0 { return "\(count) 项待同步" }
        return "已读取云端库存"
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
              TXInventory094DraftStore.valid(draft.loss),
              report?.rows.contains(where: { $0.key == draft.key && $0.basis == draft.basis }) == true else { return }
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
            guard !next.contains(where: { $0.id == value.id && $0.basis != value.basis }) else {
                error = "原价格草稿与云端版本冲突，未覆盖"; return
            }
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
        let purchased = rows.filter { $0.purchased > 0.000001 }.count
        let carried = rows.filter { $0.purchased <= 0.000001 && $0.opening > 0.000001 }.count
        let closing = known.isEmpty ? "—" : known.compactMap(\.closingCost).reduce(0,+).formatted(.currency(code: "CNY").precision(.fractionLength(0)))
        return VStack(alignment: .leading, spacing: 4) {
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 4) {
                    TXCompactMetric(title: "库存商品", value: "\(rows.count)种", tint: .green).frame(minWidth: 65)
                    TXCompactMetric(title: "今日采购", value: "\(purchased)种", tint: .blue).frame(minWidth: 65)
                    TXCompactMetric(title: "仅结转", value: "\(carried)种", tint: .orange).frame(minWidth: 65)
                    TXCompactMetric(title: "剩余价值", value: closing, tint: .purple).frame(minWidth: 90)
                }
                VStack(spacing: 4) {
                    HStack(spacing: 4) {
                        TXCompactMetric(title: "库存商品", value: "\(rows.count)种", tint: .green)
                        TXCompactMetric(title: "今日采购", value: "\(purchased)种", tint: .blue)
                    }
                    HStack(spacing: 4) {
                        TXCompactMetric(title: "仅结转", value: "\(carried)种", tint: .orange)
                        TXCompactMetric(title: "剩余价值", value: closing, tint: .purple)
                    }
                }
            }
            DisclosureGroup("估值明细 · 已盘点已知成本") {
                Text("损耗成本 " + (known.isEmpty ? "—" : known.compactMap(\.lossCost).reduce(0,+).formatted(.currency(code: "CNY"))))
                    .font(.caption)
            }.font(.caption2)
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
            VStack(alignment: .leading, spacing: 6) {
                TXPageDateHeader(title: "库存", date: $model.workDate)
                TextField("查找商品", text: $search)
                    .textFieldStyle(.roundedBorder)
                HStack(spacing: 5) {
                    Image(systemName: model.isLedgerV33Busy ? "arrow.triangle.2.circlepath" :
                          (model.v33HasPending ? "exclamationmark.icloud" : "icloud"))
                    Text(syncStatus)
                    Spacer(minLength: 0)
                    if editable && autoEnabled { Text("自动保存").foregroundColor(TXTheme.primary) }
                }.font(.caption2).foregroundColor(.secondary)
                if !error.isEmpty { Text(error).font(.caption).foregroundColor(.red) }
                TXLedgerV33InventoryActions(date: date, count: drafts.count + prices.count, onReload: reload)
                if autoEnabled && !freshAutosaveSession && (!drafts.isEmpty || !prices.isEmpty) {
                    Text("有上次遗留草稿，请手动核对并同步")
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
                                                onInvalid: { autosaveDelay?.cancel() },
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
            autoEnabled = false; cloudInventoryAllowed = false; capabilityRevision = ""
            reload()
            let original = revision
            let write = await model.v33Capability("inventory")
            let allowed = await model.v33Capability("inventory_auto")
            if original == revision { cloudInventoryAllowed = write; autoEnabled = allowed; capabilityRevision = original }
        }
        .onChange(of: scope) { _ in
            autosaveDelay?.cancel(); autoMessage = ""; autoEnabled = false; cloudInventoryAllowed = false; capabilityRevision = ""
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
    let onInvalid: () -> Void
    let onPrice: () -> Void
    @State private var remaining: String
    @State private var loss: String
    @State private var showValuation = false

    init(row: TXInventory094Policy.Row, draft: TXInventory094Draft?, editable: Bool,
         onSave: @escaping (TXInventory094Draft) -> Void, onInvalid: @escaping () -> Void,
         onPrice: @escaping () -> Void) {
        self.row = row; self.draft = draft; self.editable = editable; self.onSave = onSave
        self.onPrice = onPrice; self.onInvalid = onInvalid
        _remaining = State(initialValue: draft?.remaining ?? Self.text(row.remaining))
        _loss = State(initialValue: draft?.loss ?? Self.text(row.loss))
    }
    private static func text(_ n: Double) -> String {
        TXLedgerV33AutosavePolicy.quantityText(n)
    }
    private var conflict: Bool { draft.map { $0.basis != row.basis } ?? false }
    private var valid: Bool { TXInventory094DraftStore.valid(remaining) && TXInventory094DraftStore.valid(loss) }
    private func save() {
        guard editable, !conflict, valid else { onInvalid(); return }
        onSave(TXInventory094Draft(key: row.key, basis: row.basis,
                                   remaining: remaining, loss: loss))
    }
    private func tile(_ label: String, _ value: Double) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(label).font(.caption2).foregroundColor(.secondary)
            Text(Self.text(value)).font(.caption.bold())
        }.frame(maxWidth: .infinity, alignment: .leading)
    }
    private var priceText: String {
        let cost = row.cost.map { Self.text($0) + "/" + row.key.unit } ?? "—"
        let retail = row.retail.map { Self.text($0) + "/" + row.retailUnit } ?? "—"
        return "成本 " + cost + " · 零售 " + retail
    }

    private var savedOnCloud: Bool {
        row.savedID != nil && draft == nil && !row.usesLocalCost && !row.usesLocalRetail
    }

    private var productStatus: some View {
        HStack(spacing: 3) {
            Text(row.purchased > 0.000001 ? "有采购" : "无")
                .font(.caption2).foregroundColor(row.purchased > 0.000001 ? TXTheme.primary : .primary)
            if savedOnCloud {
                Image(systemName: "checkmark").foregroundColor(TXTheme.primary).font(.caption)
            }
        }.fixedSize()
    }

    private var productPrice: some View {
        Button(action: onPrice) {
            Text(priceText).font(.caption2).foregroundColor(.secondary)
        }
        .buttonStyle(.plain).disabled(!editable)
        .accessibilityLabel(priceText + "，点击编辑")
    }

    private var productHeader: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 3) {
                Text(row.name).font(.subheadline.bold()).fixedSize()
                Spacer(minLength: 0)
                productPrice.fixedSize()
                productStatus
            }
            VStack(alignment: .leading, spacing: 3) {
                HStack {
                    Text(row.name).font(.subheadline.bold()).fixedSize(horizontal: false, vertical: true)
                    Spacer(minLength: 3)
                    productStatus
                }
                productPrice.fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private func numberInput(_ label: String, value: Binding<String>) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(label).font(.caption2).foregroundColor(.secondary)
            TXFastNumericField(text: value, label: label)
                .frame(height: 34)
                .onChange(of: value.wrappedValue) { _ in save() }
        }.frame(maxWidth: .infinity)
    }

    private var quantityRow: some View {
        HStack(spacing: 4) {
            tile("结转", row.opening)
            tile("采购", row.purchased)
            tile("可售", row.available)
            if editable && !conflict {
                numberInput("损耗", value: $loss)
                numberInput("剩余", value: $remaining)
            } else {
                tile("损耗", row.loss)
                tile("剩余", row.remaining)
            }
        }
    }

    private var valuationText: String {
        let value = TXInventory095Valuation.item(row, draft: draft)
        guard value.counted && !value.anomaly else { return "待盘点" }
        return value.closingCost.map { $0.formatted(.currency(code: "CNY")) } ?? "未知"
    }

    private var valuationDetails: some View {
        DisclosureGroup("估值与规格", isExpanded: $showValuation) {
            if let weight = row.weight {
                Text("规格 " + Self.text(weight) + "斤/" + row.key.unit).font(.caption2)
            }
            HStack(spacing: 5) {
                Text("加权成本 " + (row.averageCost.map { Self.text($0) } ?? "未知"))
                Spacer(minLength: 0)
                Text("剩余估值 " + valuationText)
            }.font(.caption2).foregroundColor(.secondary)
        }.font(.caption2)
    }

    @ViewBuilder private var warnings: some View {
        if row.usesLocalCost || row.usesLocalRetail {
            Text("价格本地草稿 · 未同步").font(.caption2).foregroundColor(.orange)
        }
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

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            productHeader
            quantityRow
            valuationDetails
            warnings
        }
        .padding(7)
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
                    Text("有效采购价优先；参考成本仅在没有有效采购价时使用。参考成本不覆盖历史进价。")
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
                    Button("保存价格") {
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
                    Text("先保存本地，再按当前账本权限同步。").font(.caption).foregroundColor(.orange)
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
