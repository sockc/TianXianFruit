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
    @State private var loadedScope = ""
    @State private var error = ""
    @State private var storageOK = false

    private var date: String { BusinessDates.key(day) }
    private var scope: String {
        [model.account?.username ?? "", model.selectedBookID ?? "", date].joined(separator: "|")
    }
    private var revision: String {
        scope + "|" + String(model.currentCursor) + "|" + String(model.lastSyncAt?.timeIntervalSince1970 ?? 0)
    }
    private var editable: Bool {
        loadedScope == scope && storageOK && report?.issues.isEmpty == true &&
        TXTestBookAccess.isCandidate(model.selectedBook, account: model.account,
                                     offline: model.isUsingCachedAuthorization)
    }

    private func reload() {
        report = model.lastSyncAt == nil ? nil : TXInventory094Policy.evaluate(model.snapshot, date: date)
        loadedScope = ""
        drafts = []; error = ""; storageOK = false
        guard model.account != nil, model.selectedBookID != nil else { return }
        do {
            drafts = try TXInventory094DraftStore.load(scope: scope)
            loadedScope = scope; storageOK = true
        } catch { self.error = "本地库存草稿读取失败，已禁用编辑；原文件未覆盖" }
    }

    private func save(_ draft: TXInventory094Draft) {
        guard editable, TXInventory094DraftStore.valid(draft.remaining),
              TXInventory094DraftStore.valid(draft.loss) else { return }
        var next = drafts.filter { $0.key != draft.key }
        next.append(draft)
        do {
            try TXInventory094DraftStore.save(next, scope: scope)
            drafts = next; error = ""
        } catch { self.error = "本地草稿保存失败，请保留输入并重试"; storageOK = false }
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
                if let report {
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
                    LazyVStack(spacing: 8) {
                        ForEach(rows) { row in
                            TXInventory094Card(row: row, draft: drafts.first { $0.key == row.key },
                                                editable: editable, onSave: save)
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
        .task(id: revision) { reload() }
    }
}

private struct TXInventory094Card: View {
    let row: TXInventory094Policy.Row
    let draft: TXInventory094Draft?
    let editable: Bool
    let onSave: (TXInventory094Draft) -> Void
    @State private var remaining: String
    @State private var loss: String

    init(row: TXInventory094Policy.Row, draft: TXInventory094Draft?, editable: Bool,
         onSave: @escaping (TXInventory094Draft) -> Void) {
        self.row = row; self.draft = draft; self.editable = editable; self.onSave = onSave
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
            }.font(.caption2).foregroundColor(.secondary)
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
