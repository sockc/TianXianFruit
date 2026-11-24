import SwiftUI

/// Device-local multi-line purchase editing. This screen intentionally has
/// NO network client and cannot create a staging sync job.
struct MultiPurchaseEditorView: View {
    @EnvironmentObject private var model: LedgerViewModel
    @Environment(\.dismiss) private var dismiss

    let original: TestDraft?
    let onSave: (TestDraft) -> Bool

    @State private var date: Date
    @State private var buyerSyncID: String
    @State private var purchaseType: String
    @State private var remark: String
    @State private var lines: [PurchaseDraftLine]
    @State private var errorText: String?

    init(original: TestDraft?, onSave: @escaping (TestDraft) -> Bool) {
        self.original = original
        self.onSave = onSave
        _date = State(initialValue: original.flatMap {
            BusinessDates.parse($0.date)
        } ?? Date())
        _buyerSyncID = State(initialValue:
            original?.fields["buyer_sync_id"] ?? "")
        _purchaseType = State(initialValue:
            original?.fields["purchase_type"] ?? "PLANNED")
        _remark = State(initialValue:
            original?.fields["remark"] ?? "")
        _lines = State(initialValue:
            original.map(PurchaseDraftPolicy.previewLegacy) ??
                [PurchaseDraftLine.blank()])
    }

    private var total: Decimal? {
        try? PurchaseDraftPolicy.total(lines)
    }

    private var canEdit: Bool {
        guard let book = model.selectedBook,
              let account = model.account else { return false }
        return (try? TestWritePolicy.authorize(
            book: book, typedID: book.id)) != nil &&
            (original == nil ||
             (original?.bookID == book.id &&
              original?.account.lowercased() == account.username.lowercased()))
    }

    var body: some View {
        NavigationView {
            ScrollView {
                VStack(alignment: .leading, spacing: 13) {
                    Label(original == nil ? "新建多商品采购草稿" : "修改采购草稿",
                          systemImage: "cart.badge.plus")
                        .font(.headline)
                    Text("本机草稿，不提交服务器。金额由商品数量 × 单价计算，逐行按分四舍五入后合计。")
                        .font(.caption).foregroundColor(.secondary)
                    DatePicker("采购日期", selection: $date,
                               displayedComponents: .date)
                    Picker("采购人", selection: $buyerSyncID) {
                        Text("请选择已同步采购人").tag("")
                        ForEach(model.snapshot.testPartners) { partner in
                            Text(partner.name).tag(partner.id)
                        }
                    }.pickerStyle(MenuPickerStyle())
                    Picker("采购类型", selection: $purchaseType) {
                        Text("计划采购").tag("PLANNED")
                        Text("临时采购").tag("TEMPORARY")
                    }.pickerStyle(SegmentedPickerStyle())

                    HStack {
                        Text("商品明细（" + String(lines.count) + " / 30）")
                            .font(.subheadline.bold())
                        Spacer()
                        Button {
                            if lines.count < PurchaseDraftPolicy.maxLines {
                                lines.append(.blank())
                            }
                        } label: {
                            Label("添加商品", systemImage: "plus.circle")
                        }
                        .disabled(lines.count >= PurchaseDraftPolicy.maxLines)
                    }
                    ForEach($lines) { $line in
                        PurchaseDraftLineEditor(
                            line: $line, fruits: model.snapshot.testFruits,
                            canRemove: lines.count > 1,
                            onRemove: {
                                lines.removeAll { $0.id == line.id }
                            })
                    }
                    VStack(alignment: .leading, spacing: 8) {
                        HStack {
                            Text("商品金额合计").font(.headline)
                            Spacer()
                            Text(total.map { "¥" + PurchaseDraftPolicy.display($0) } ??
                                 "待补齐商品信息")
                                .font(.headline)
                                .foregroundColor(total == nil ? .orange : .primary)
                        }
                        TextField("采购备注", text: $remark)
                            .textFieldStyle(.roundedBorder)
                    }
                    .padding(12)
                    .background(Color(UIColor.secondarySystemGroupedBackground),
                                in: RoundedRectangle(cornerRadius: 12))
                    if original != nil && original?.purchaseItems == nil {
                        Text("旧单导入单价时可能产生分级舍入差异。请核对总价并按需修改单价；原记录在保存前不变。")
                            .font(.caption2).foregroundColor(.orange)
                    }
                    if let errorText {
                        Label(errorText, systemImage: "exclamationmark.triangle")
                            .font(.caption).foregroundColor(.orange)
                    }
                    Button {
                        save()
                    } label: {
                        Label(original == nil ? "保存整单本机草稿" :
                              "保存本机草稿修改", systemImage: "square.and.arrow.down")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(!canEdit || model.isBusy)
                    Text("提示：多商品订单仅在本机保存。此版本现有采购测试上传流程仍限旧版单商品草稿，不允许自动拆分或只上传第一种商品。")
                        .font(.caption2).foregroundColor(.secondary)
                }.padding(12)
            }
            .navigationBarTitle("多商品采购", displayMode: .inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("取消") { dismiss() }
                }
            }
        }
    }

    private func save() {
        do {
            guard let book = model.selectedBook, let account = model.account else {
                throw TestWriteError.notAuthorized
            }
            try TestWritePolicy.authorize(book: book, typedID: book.id)
            guard canEdit else { throw TestWriteError.mismatch }
            let draft = try PurchaseDraftPolicy.buildDraft(
                id: original?.id ?? UUID().uuidString,
                account: original?.account ?? account.username,
                bookID: book.id,
                date: BusinessDates.key(date),
                buyerSyncID: buyerSyncID,
                purchaseType: purchaseType,
                remark: remark,
                lines: lines, createdAt: original?.createdAt ?? Date())
            try TestWritePolicy.validate(draft, book: book)
            if onSave(draft) {
                dismiss()
            } else {
                errorText = "草稿保存失败或订单已锁定，请查看草稿列表提示"
            }
        } catch {
            errorText = error.localizedDescription
        }
    }
}

private struct PurchaseDraftLineEditor: View {
    @Binding var line: PurchaseDraftLine
    let fruits: [TestLookup]
    let canRemove: Bool
    let onRemove: () -> Void

    private var subtotal: Decimal? {
        try? PurchaseDraftPolicy.subtotal(line)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 9) {
            HStack {
                Text(line.fruitName.isEmpty ? "选择商品" : line.fruitName)
                    .font(.subheadline.bold())
                Spacer()
                Button(role: .destructive, action: onRemove) {
                    Image(systemName: "minus.circle")
                }
                .disabled(!canRemove)
                .accessibilityLabel("删除此商品明细")
            }
            Picker("商品", selection: $line.fruitSyncID) {
                Text("请选择商品").tag("")
                ForEach(fruits) { fruit in
                    Text(fruit.name).tag(fruit.id)
                }
            }
            .pickerStyle(MenuPickerStyle())
            .onChange(of: line.fruitSyncID) { next in
                line.fruitName = fruits.first { $0.id == next }?.name ?? ""
            }
            HStack(spacing: 10) {
                input("数量", value: $line.quantity)
                input("单价（元）", value: $line.unitPrice)
            }
            HStack(spacing: 10) {
                input("单位", value: $line.unit, numeric: false)
                input("每件重量（斤）", value: $line.unitWeightJin)
            }
            HStack {
                Text("小计").font(.caption).foregroundColor(.secondary)
                Spacer()
                Text(subtotal.map { "¥" + PurchaseDraftPolicy.display($0) } ??
                     "数据未完整")
                    .font(.subheadline.bold())
            }
        }
        .padding(12)
        .background(Color(UIColor.secondarySystemGroupedBackground),
                    in: RoundedRectangle(cornerRadius: 12))
    }

    private func input(_ name: String, value: Binding<String>,
                       numeric: Bool = true) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(name).font(.caption2).foregroundColor(.secondary)
            TextField(name, text: value)
                .keyboardType(numeric ? .decimalPad : .default)
                .textFieldStyle(.roundedBorder)
                .font(.subheadline)
        }.frame(maxWidth: .infinity)
    }
}
