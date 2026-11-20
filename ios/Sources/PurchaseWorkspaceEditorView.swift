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

    init(original: PurchaseWorkspaceDraft?, date: Date,
         account: String, bookID: String,
         onSave: @escaping (PurchaseWorkspaceDraft) -> Bool) {
        self.original = original
        self.account = account
        self.bookID = bookID
        self.onSave = onSave
        _date = State(initialValue: original.flatMap {
            BusinessDates.parse($0.date)
        } ?? date)
        _buyerSyncID = State(initialValue: original?.buyerSyncID ?? "")
        _purchaseType = State(initialValue: original?.purchaseType ?? "PLANNED")
        _remark = State(initialValue: original?.remark ?? "")
        _lines = State(initialValue: original?.lines ?? [.blank()])
    }

    private var canEdit: Bool {
        guard model.account?.username.lowercased() == account.lowercased(),
              model.selectedBookID == bookID,
              let book = model.selectedBook else { return false }
        return PurchaseWorkspaceDraftPolicy.authorized(
            book: book, account: model.account)
    }

    private var total: Decimal? { try? PurchaseDraftPolicy.total(lines) }

    var body: some View {
        NavigationView {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    HStack {
                        Text(original == nil ? "新增采购" : "修改采购")
                            .font(.headline)
                        Spacer()
                        Text("本机待提交")
                            .font(.caption.bold()).foregroundColor(.orange)
                    }
                    Text("按照安卓采购的日期、采购人、商品、数量和单价填写。当前先保存在本机，不生成云端采购额或资金结算。")
                        .font(.caption).foregroundColor(.secondary)

                    DatePicker("采购日期", selection: $date,
                               displayedComponents: .date)
                        .font(.subheadline)
                    Picker("采购人", selection: $buyerSyncID) {
                        Text("请选择采购人").tag("")
                        ForEach(model.snapshot.testPartners) { buyer in
                            Text(buyer.name).tag(buyer.id)
                        }
                    }
                    .pickerStyle(MenuPickerStyle())
                    Picker("采购类型", selection: $purchaseType) {
                        Text("计划采购").tag("PLANNED")
                        Text("临时采购").tag("TEMPORARY")
                    }.pickerStyle(SegmentedPickerStyle())

                    HStack {
                        Text("商品明细").font(.subheadline.bold())
                        Spacer()
                        Text(String(lines.count) + "/" +
                             String(PurchaseDraftPolicy.maxLines))
                            .font(.caption).foregroundColor(.secondary)
                        Button {
                            if lines.count < PurchaseDraftPolicy.maxLines {
                                lines.append(.blank())
                            }
                        } label: {
                            Label("新增商品", systemImage: "plus.circle")
                        }
                        .disabled(lines.count >= PurchaseDraftPolicy.maxLines)
                    }
                    ForEach($lines) { $line in
                        PurchaseWorkspaceLineCard(
                            line: $line,
                            fruits: model.snapshot.testFruits,
                            canDelete: lines.count > 1,
                            onDelete: {
                                lines.removeAll { $0.id == line.id }
                            })
                    }
                    VStack(spacing: 10) {
                        HStack {
                            Text("已录入金额").font(.subheadline.bold())
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
                    .padding(12)
                    .background(Color(UIColor.secondarySystemGroupedBackground),
                                in: RoundedRectangle(cornerRadius: 12))
                    if let errorText {
                        Label(errorText, systemImage: "exclamationmark.triangle")
                            .font(.caption).foregroundColor(.orange)
                    }
                    Button(action: save) {
                        Label("保存采购录入", systemImage: "square.and.arrow.down")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(!canEdit || model.isBusy)
                    Text("保留当前账号与账本的本机记录；未接通正式服务器的事务级采购写入前，不会把该草稿计入安卓的已完成采购或结算。")
                        .font(.caption2).foregroundColor(.secondary)
                }
                .padding(12)
            }
            .navigationBarTitle("采购录入", displayMode: .inline)
            .navigationBarItems(leading: Button("取消") { dismiss() })
        }
        .navigationViewStyle(StackNavigationViewStyle())
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
                proposed, buyers: model.snapshot.testPartners)
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
    let canDelete: Bool
    let onDelete: () -> Void

    private var subtotal: Decimal? {
        try? PurchaseDraftPolicy.subtotal(line)
    }

    private var lastPrice: PurchaseLastPrice? {
        guard !line.fruitSyncID.isEmpty else { return nil }
        return PurchaseLastPricePolicy.latest(
            fruitSyncID: line.fruitSyncID, unit: line.unit,
            records: Array(model.snapshot.records.values))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(line.fruitName.isEmpty ? "商品" : line.fruitName)
                    .font(.subheadline.bold())
                Spacer()
                Button(role: .destructive, action: onDelete) {
                    Image(systemName: "minus.circle")
                }
                .disabled(!canDelete)
                .accessibilityLabel("移除商品")
            }
            Picker("选择商品", selection: $line.fruitSyncID) {
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
            if let lastPrice {
                HStack(spacing: 7) {
                    Text("历史有效进价 ¥" +
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
                input("单位", value: $line.unit, numeric: false)
                input("每件重量（斤）", value: $line.unitWeightJin)
            }
            HStack {
                Text("小计").font(.caption).foregroundColor(.secondary)
                Spacer()
                Text(subtotal.map { "¥" + PurchaseDraftPolicy.display($0) } ??
                     "未完成录入")
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
        }
        .frame(maxWidth: .infinity)
    }
}
