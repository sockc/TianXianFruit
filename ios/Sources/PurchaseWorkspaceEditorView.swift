import SwiftUI

/// Native purchase entry for signed-in ledgers. Local drafts are not cloud
/// purchase orders and must never enter Android settlement until saved through
/// a future atomic purchase endpoint.
struct PurchaseWorkspaceEditorView: View {
    @EnvironmentObject private var model: LedgerViewModel
    @Environment(\.dismiss) private var dismiss

    let original: PurchaseWorkspaceDraft?
    let account: String
    let bookID: String
    let onSave: (PurchaseWorkspaceDraft) -> Bool

    @State private var date: Date
    @State private var buyerSyncID: String
    @State private var purchaseType: String
    @State private var remark: String
    @State private var lines: [PurchaseDraftLine]
    @State private var errorText: String?
    @State private var showImportPicker = false
    @State private var importNotice: String?
    @State private var showDiscardConfirmation = false
    private let originalDateKey: String
    private let originalBuyerID: String
    private let originalPurchaseType: String
    private let originalRemark: String
    private let originalLines: [PurchaseDraftLine]

    init(original: PurchaseWorkspaceDraft?, date: Date,
         account: String, bookID: String,
         initialBuyerSyncID: String? = nil,
         initialLines: [PurchaseDraftLine]? = nil,
         onSave: @escaping (PurchaseWorkspaceDraft) -> Bool) {
        self.original = original
        self.account = account
        self.bookID = bookID
        self.onSave = onSave
        let startingDate = original.flatMap { BusinessDates.parse($0.date) } ?? date
        let startingBuyerID = original?.buyerSyncID ?? initialBuyerSyncID ?? ""
        let startingType = original?.purchaseType ?? "PLANNED"
        let startingRemark = original?.remark ?? ""
        let startingLines = original?.lines ?? initialLines ?? [.blank()]
        originalDateKey = BusinessDates.key(startingDate)
        originalBuyerID = startingBuyerID
        originalPurchaseType = startingType
        originalRemark = startingRemark
        originalLines = startingLines
        _date = State(initialValue: startingDate)
        _buyerSyncID = State(initialValue: startingBuyerID)
        _purchaseType = State(initialValue: startingType)
        _remark = State(initialValue: startingRemark)
        _lines = State(initialValue: startingLines)
    }

    private var canEdit: Bool {
        guard model.account?.username.lowercased() == account.lowercased(),
              model.selectedBookID == bookID,
              let book = model.selectedBook else { return false }
        return PurchaseWorkspaceDraftPolicy.authorized(
            book: book, account: model.account)
    }

    private var total: Decimal? { try? PurchaseDraftPolicy.total(lines) }

    private var hasUnsavedInput: Bool {
        let changedFields = BusinessDates.key(date) != originalDateKey ||
            purchaseType != originalPurchaseType ||
            remark != originalRemark || lines != originalLines
        if changedFields { return true }
        // Do not warn merely because the only available buyer was autofilled.
        return buyerSyncID != originalBuyerID &&
            !(originalBuyerID.isEmpty &&
              model.snapshot.testPartners.count == 1 &&
              model.snapshot.testPartners[0].id == buyerSyncID)
    }
    private var hasPlanSource: Bool {
        lines.contains { $0.sourcePlanItemSyncID != nil }
    }

    var body: some View {
        NavigationView {
            ScrollView {
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        Text(original == nil ? "新增采购" : "修改采购")
                            .font(.headline)
                        Spacer()
                        Text("本机待提交")
                            .font(.caption.bold()).foregroundColor(.orange)
                    }
                    if model.selectedBook.map({ book in
                        model.purchaseCommitCapabilities?.eligible(book: book) != true
                    }) ?? true {
                        Label("本机草稿 · 云端未授权，不计入结算",
                              systemImage: "lock.shield")
                            .font(.caption2).foregroundColor(.secondary)
                    }

                    TXDayBar(date: $date, label: "采购日期")
                        .disabled(hasPlanSource)
                    HStack(spacing: 8) {
                        Picker("采购人", selection: $buyerSyncID) {
                            Text("请选择采购人").tag("")
                            ForEach(model.snapshot.testPartners) { buyer in
                                Text(buyer.name).tag(buyer.id)
                            }
                        }
                        .pickerStyle(MenuPickerStyle())
                        Picker("类型", selection: $purchaseType) {
                            Text("计划").tag("PLANNED")
                            Text("临时").tag("TEMPORARY")
                        }
                        .pickerStyle(MenuPickerStyle())
                    }
                    .disabled(hasPlanSource)
                    if hasPlanSource {
                        Label("已关联安卓待采购计划：采购日期与采购人已锁定",
                              systemImage: "link.circle")
                            .font(.caption).foregroundColor(.green)
                        Text("计划数量仅作为初始值，实际数量、单价、重量请按采购结果填写；提交后服务器会同步完成状态。")
                            .font(.caption2).foregroundColor(.secondary)
                    }
                    Button {
                        showImportPicker = true
                    } label: {
                        Label("导入库存 / 待采购计划", systemImage: "square.and.arrow.down.on.square")
                    }
                    .font(.subheadline)
                    .disabled(!canEdit || hasPlanSource)
                    if let importNotice {
                        Text(importNotice)
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                     HStack {
                        Text("商品明细").font(.subheadline.bold())
                        Spacer()
                        Text("共 " + String(lines.count) + " 种")
                            .font(.caption).foregroundColor(.secondary)
                        Text(total.map { "¥" + PurchaseDraftPolicy.display($0) } ?? "金额待核对")
                            .font(.caption.bold())
                            .foregroundColor(total == nil ? .orange : TXTheme.primary)
                        Button {
                            if lines.count < PurchaseDraftPolicy.maxLines {
                                lines.append(.blank())
                            }
                        } label: {
                            Label("添加商品", systemImage: "plus.circle")
                        }
                        .disabled(lines.count >= PurchaseDraftPolicy.maxLines || hasPlanSource)
                    }
                    ForEach($lines) { $line in
                        PurchaseWorkspaceLineCard(
                            line: $line,
                            fruits: model.snapshot.testFruits,
                            purchaseDate: BusinessDates.key(date),
                            canDelete: lines.count > 1,
                            onDelete: {
                                lines.removeAll { $0.id == line.id }
                            })
                    }
                    VStack(spacing: 6) {
                        HStack {
                            Text("本单合计").font(.subheadline.bold())
                            Spacer()
                            Text(total.map {
                                "¥" + PurchaseDraftPolicy.display($0)
                            } ?? "待核对")
                                .font(.headline.bold())
                                .foregroundColor(total == nil ? .orange : .green)
                        }
                        TextField("采购备注（可选）", text: $remark)
                            .textFieldStyle(.roundedBorder)
                        Text("单价优先真实填写；采购总额按商品明细计算，不把“总价”当作商品单价。")
                            .font(.caption2).foregroundColor(.secondary)
                    }
                    .padding(9)
                    .background(Color(UIColor.secondarySystemGroupedBackground),
                                in: RoundedRectangle(cornerRadius: 12))
                    if let errorText {
                        Label(errorText, systemImage: "exclamationmark.triangle")
                            .font(.caption).foregroundColor(.orange)
                    }
                    Button(action: save) {
                        Label(model.selectedBook.map { book in
                                  model.purchaseCommitCapabilities?.eligible(book: book) == true
                              } == true
                              ? "保存并准备同步" : "保存采购录入",
                              systemImage: "square.and.arrow.down")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(!canEdit || model.isBusy)
                    Text(model.selectedBook.map { book in
                            model.purchaseCommitCapabilities?.eligible(book: book) == true
                                ? "保存后提示确认同步；服务器原子提交并回读成功才算已完成。"
                                : "本机录入不改变安卓采购与结算，此账本尚未授权云端写入。"
                         } ?? "请先选择账本")
                        .font(.caption2).foregroundColor(.secondary)
                }
                .padding(12)
            }
            .navigationBarTitle("采购录入", displayMode: .inline)
            .navigationBarItems(leading: Button("取消") {
                if hasUnsavedInput { showDiscardConfirmation = true }
                else { dismiss() }
            })
        }
        .navigationViewStyle(StackNavigationViewStyle())
        .onAppear {
            if original == nil, buyerSyncID.isEmpty,
               model.snapshot.testPartners.count == 1 {
                buyerSyncID = model.snapshot.testPartners[0].id
            }
        }
        .interactiveDismissDisabled(hasUnsavedInput)
        .confirmationDialog("采购内容尚未保存",
                            isPresented: $showDiscardConfirmation,
                            titleVisibility: .visible) {
            Button("放弃未保存的采购输入", role: .destructive) { dismiss() }
            Button("继续录入", role: .cancel) {}
        } message: {
            Text("离开后本次未保存的数量、单价和商品明细会丢失；已保存的本机采购不受影响。")
        }
        .sheet(isPresented: $showImportPicker) {
            PurchaseImportPickerView(
                date: BusinessDates.key(date),
                buyerSyncID: buyerSyncID,
                events: Array(model.snapshot.records.values)) { chosen in
                    let originalLines = lines.count == 1 &&
                        lines[0].fruitSyncID.isEmpty ? [] : lines
                    let imported = PurchaseImportPolicy.append(
                        chosen, to: originalLines)
                    if imported.added > 0 {
                        lines = imported.lines
                    }
                    importNotice = "已导入 " + String(imported.added) +
                        " 种，重复或超过上限跳过 " + String(imported.skipped) +
                        " 种。采购单价需要实际核实。"
                }
        }
    }

    private func save() {
        do {
            guard canEdit else { throw PurchaseWorkspaceStoreError.scope }
            let buyer = try resolvedBuyer()
            let now = Date()
            let proposed = PurchaseWorkspaceDraft(
                id: original?.id ?? UUID().uuidString,
                account: account, bookID: bookID,
                date: BusinessDates.key(date),
                buyerSyncID: buyer.id, buyerName: buyer.name,
                purchaseType: purchaseType,
                remark: remark.trimmingCharacters(in: .whitespacesAndNewlines),
                lines: lines,
                createdAt: original?.createdAt ?? now,
                updatedAt: now)
            try PurchaseWorkspaceDraftPolicy.validate(
                proposed, buyers: model.snapshot.testPartners,
                fruits: model.snapshot.testFruits)
            guard onSave(proposed) else {
                throw PurchaseWorkspaceStoreError.invalid
            }
            dismiss()
        } catch {
            errorText = error.localizedDescription
        }
    }

    private func resolvedBuyer() throws -> TestLookup {
        guard let value = model.snapshot.testPartners.first(where: {
            $0.id == buyerSyncID
        }) else { throw TestWriteError.missingData("采购人") }
        return value
    }
}

private struct PurchaseWorkspaceLineCard: View {
    @EnvironmentObject private var model: LedgerViewModel
    @Binding var line: PurchaseDraftLine
    let fruits: [TestLookup]
    let purchaseDate: String
    let canDelete: Bool
    let onDelete: () -> Void

    private var subtotal: Decimal? {
        try? PurchaseDraftPolicy.subtotal(line)
    }

    private var lastPrice: PurchaseLastPrice? {
        guard !line.fruitSyncID.isEmpty else { return nil }
        return PurchaseLastPricePolicy.latest(
            fruitSyncID: line.fruitSyncID, unit: line.unit,
            records: Array(model.snapshot.records.values),
            onOrBefore: purchaseDate)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            HStack(spacing: 6) {
                Picker("商品", selection: $line.fruitSyncID) {
                    Text("请选择商品").tag("")
                    ForEach(fruits) { fruit in
                        Text(fruit.name).tag(fruit.id)
                    }
                }
                .pickerStyle(MenuPickerStyle())
                .disabled(line.sourcePlanItemSyncID != nil)
                .onChange(of: line.fruitSyncID) { next in
                    // Different goods must not inherit the previous goods'
                    // unit price. A valid historical price is optional and
                    // only copied via the explicit "填入" action below.
                    line.unitPrice = "0"
                    line.fruitName = fruits.first { $0.id == next }?.name ?? ""
                    line.sourcePlanItemSyncID = nil
                }
                .onChange(of: line.unit) { _ in
                    // "件" and "箱" may have completely different unit costs.
                    // Retain quantity and weight, but require the new price
                    // to be explicitly entered or selected from its own unit.
                    line.unitPrice = "0"
                    line.sourcePlanItemSyncID = nil
                }
                Spacer(minLength: 4)
                Button(role: .destructive, action: onDelete) {
                    Image(systemName: "minus.circle")
                }
                .disabled(!canDelete)
                .accessibilityLabel("移除商品")
            }
            if line.sourcePlanItemSyncID != nil {
                Text("原计划商品与单位已锁定；填写实际采购数量、单价和重量")
                    .font(.caption2)
                    .foregroundColor(.secondary)
            }

            HStack(alignment: .bottom, spacing: 7) {
                input("数量", value: $line.quantity)
                input("单价（元）", value: $line.unitPrice)
                VStack(alignment: .trailing, spacing: 4) {
                    Text("总价").font(.caption2).foregroundColor(.secondary)
                    Text(subtotal.map { "¥" + PurchaseDraftPolicy.display($0) } ??
                         "待核对")
                        .font(.subheadline.bold())
                        .foregroundColor(subtotal == nil ? .orange : TXTheme.primary)
                        .lineLimit(1).minimumScaleFactor(0.65)
                        .frame(height: 36, alignment: .center)
                }
                .frame(maxWidth: .infinity, alignment: .trailing)
            }
            if let lastPrice {
                HStack(spacing: 7) {
                    Text("截至采购日的历史有效进价 ¥" +
                         String(format: "%.2f", lastPrice.price) +
                         "/" + lastPrice.unit + " · " + lastPrice.date)
                        .font(.caption2).foregroundColor(.secondary)
                    Spacer(minLength: 0)
                    Button("填入") {
                        // Never auto-overwrite a user's real input.
                        line.unitPrice = String(
                            format: "%.2f", lastPrice.price)
                    }
                    .font(.caption2)
                }
            }
            HStack(spacing: 10) {
                input("单位", value: $line.unit, numeric: false,
                      editable: line.sourcePlanItemSyncID == nil)
                input("每件重量（斤）", value: $line.unitWeightJin)
            }
         }
        .padding(8)
        .background(Color(UIColor.secondarySystemGroupedBackground),
                    in: RoundedRectangle(cornerRadius: 10))
    }

    private func input(_ name: String, value: Binding<String>,
                       numeric: Bool = true,
                       editable: Bool = true) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(name).font(.caption2).foregroundColor(.secondary)
            if numeric {
                TXFastNumericField(text: value, label: name, enabled: editable)
                    .frame(height: 36)
            } else {
                TextField(name, text: value)
                    .disabled(!editable)
                    .textFieldStyle(.roundedBorder)
                    .font(.subheadline)
                    .frame(height: 36)
                    .accessibilityLabel(name)
            }
        }
        .frame(maxWidth: .infinity)
    }
}
