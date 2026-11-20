import SwiftUI

@MainActor
private final class TestDraftManager: ObservableObject {
    @Published var drafts: [TestDraft] = []
    @Published var lockedDraftIDs: Set<String> = []
    @Published var message: String?
    private let storage: TestDraftStore?

    init() {
        do { storage = try TestDraftStore() }
        catch { storage = nil; message = error.localizedDescription }
    }

    func reload(account: String?, book: String?) {
        drafts = []
        lockedDraftIDs = []
        guard let account, let book, let storage else { return }
        do {
            drafts = try storage.load(account: account, book: book)
            let purchases = try storage.stagingJobs(
                account: account, book: book).map(\.id)
            let sales = try storage.businessJobs(
                account: account, book: book).map(\.id)
            lockedDraftIDs = Set(purchases + sales)
        }
        catch { message = error.localizedDescription }
    }

    @discardableResult
    func save(_ draft: TestDraft, book: CloudBook) -> Bool {
        guard let storage else {
            message = "本机草稿数据库不可用"
            return false
        }
        do {
            try TestWritePolicy.validate(draft, book: book)
            try storage.save(draft)
            reload(account: draft.account, book: draft.bookID)
            message = "草稿仅保存在这台 iPhone，未发送到云端"
            return true
        } catch {
            message = error.localizedDescription
            return false
        }
    }

    func duplicate(_ draft: TestDraft, book: CloudBook) {
        guard draft.kind == .purchase else { return }
        let lines = draft.purchaseItems?.map { $0.duplicated() }
        let newDraft = TestDraft(
            id: UUID().uuidString, account: draft.account, bookID: draft.bookID,
            kind: .purchase, date: draft.date,
            fields: draft.fields, createdAt: Date(),
            purchaseItems: lines)
        if save(newDraft, book: book) {
            message = "已复制为新的本机草稿，原订单与云端上传状态不受影响"
        }
    }

    func delete(_ draft: TestDraft) {
        guard let storage else { return }
        do {
            try storage.delete(id: draft.id, account: draft.account, book: draft.bookID)
            reload(account: draft.account, book: draft.bookID)
            message = "已删除本机测试草稿，不影响云端营业或采购"
        } catch {
            message = error.localizedDescription
        }
    }
}

private struct PurchaseEditRequest: Identifiable {
    let id = UUID().uuidString
    let draft: TestDraft?
}

/// Local-only, isolated form rehearsal. Never sends any network mutation.
struct TestWriteLabView: View {
    @EnvironmentObject private var model: LedgerViewModel
    @StateObject private var draftManager = TestDraftManager()
    @State private var draftKind: TestDraftKind = .business
    @State private var purchaseEditRequest: PurchaseEditRequest?
    @State private var entryDate = Date()

    @State private var storeSyncID = ""
    @State private var wechat = "0"
    @State private var alipay = "0"
    @State private var cash = "0"
    @State private var expense = "0"
    @State private var openingStock = "0"
    @State private var closingStock = "0"
    @State private var purchaseCost = "0"
    @State private var wechatCollectorID = ""
    @State private var alipayCollectorID = ""
    @State private var cashCollectorID = ""
    @State private var expensePayerID = ""

    @State private var buyerSyncID = ""
    @State private var fruitSyncID = ""
    @State private var quantity = "1"
    @State private var unit = "件"
    @State private var unitWeightJin = "0"
    @State private var totalCost = "0"
    @State private var remark = ""
    @State private var draftStatus = "PLANNED"

    private var book: CloudBook? { model.selectedBook }
    private var accessibleTestBook: Bool {
        guard let book else { return false }
        return book.isAvailable &&
            book.name.hasPrefix(TestWritePolicy.prefix) &&
            ["OWNER", "SUPERADMIN", "EDITOR"].contains(book.role)
    }
    private var unlocked: Bool { accessibleTestBook }

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 14) {
                Label("V0.3.3 · 测试账本营业与采购", systemImage: "lock.shield")
                    .font(.headline).foregroundColor(.orange)
                Text("测试账本可录入营业及多商品采购草稿，之后从对应同步页面提交；正式账本始终只读。")
                    .font(.caption).foregroundColor(.secondary)

                if let book {
                    VStack(alignment: .leading, spacing: 9) {
                        Text("当前账本：" + book.name).font(.subheadline.bold())
                        Text("账本 ID：" + book.id)
                            .font(.caption2).foregroundColor(.secondary)
                            .textSelection(.enabled)
                        if !accessibleTestBook {
                            Text("已锁定：请先在 Android/管理端创建独立账本，名称以 iOS_TEST_ 开头，并使用该账本的可编辑账号登录。当前正式账本绝不启用录入预演。")
                                .font(.caption).foregroundColor(.orange)
                        } else {
                            Label("已自动识别测试账本，无需再次输入 ID",
                                  systemImage: "checkmark.shield")
                                .font(.caption).foregroundColor(.green)
                        }
                    }
                    .padding(12)
                    .background(Color(UIColor.secondarySystemGroupedBackground),
                                in: RoundedRectangle(cornerRadius: 12))
                } else {
                    Text("当前没有可读取的账本")
                        .foregroundColor(.orange).font(.caption)
                }

                if unlocked {
                    Picker("草稿类型", selection: $draftKind) {
                        Text("营业草稿").tag(TestDraftKind.business)
                        Text("采购草稿").tag(TestDraftKind.purchase)
                    }
                    .pickerStyle(SegmentedPickerStyle())

                    DatePicker("录入日期", selection: $entryDate,
                               displayedComponents: .date)
                        .font(.subheadline)
                    if draftKind == .business {
                        businessForm
                    } else {
                        Button {
                            purchaseEditRequest = PurchaseEditRequest(draft: nil)
                        } label: {
                            Label("新建多商品采购单（推荐）",
                                  systemImage: "cart.badge.plus")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.borderedProminent)
                        purchaseForm
                    }
                    Button {
                        save()
                    } label: {
                        Label("只保存本机草稿（不发送服务器）",
                              systemImage: "square.and.arrow.down")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(model.isBusy)
                }

                if let message = draftManager.message {
                    Label(message, systemImage: "info.circle")
                        .font(.caption).foregroundColor(.orange)
                }

                if unlocked {
                    Text("本机草稿 · " + String(draftManager.drafts.count) + " 条")
                        .font(.subheadline.bold())
                    if draftManager.drafts.isEmpty {
                        Text("尚未保存草稿").font(.caption).foregroundColor(.secondary)
                    }
                    ForEach(draftManager.drafts) { draft in
                        HStack(alignment: .top, spacing: 10) {
                            VStack(alignment: .leading, spacing: 5) {
                                Text(draft.kind.title + " · " + draft.date)
                                    .font(.subheadline.bold())
                                Text(draft.summary).font(.caption)
                                if draftManager.lockedDraftIDs.contains(draft.id) {
                                    Text("已绑定云端上传任务 · 不可修改或删除")
                                        .font(.caption2).foregroundColor(.orange)
                                } else if draft.purchaseItems != nil {
                                    Text("多商品采购草稿 · 可进入采购同步上传")
                                        .font(.caption2).foregroundColor(.secondary)
                                } else {
                                    Text("本机草稿 · 尚无云端任务")
                                        .font(.caption2).foregroundColor(.secondary)
                                }
                            }
                            Spacer()
                            if draft.kind == .purchase {
                                VStack(spacing: 9) {
                                    Button {
                                        purchaseEditRequest = PurchaseEditRequest(
                                            draft: draft)
                                    } label: {
                                        Image(systemName: "pencil")
                                    }
                                    .disabled(draftManager.lockedDraftIDs.contains(draft.id))
                                    .accessibilityLabel("编辑采购草稿")
                                    Button {
                                        if let book {
                                            draftManager.duplicate(draft, book: book)
                                        }
                                    } label: {
                                        Image(systemName: "doc.on.doc")
                                    }
                                    .accessibilityLabel("复制为新草稿")
                                }
                            }
                            Button(role: .destructive) {
                                draftManager.delete(draft)
                            } label: {
                                Image(systemName: "trash")
                            }
                            .disabled(draftManager.lockedDraftIDs.contains(draft.id))
                            .accessibilityLabel("删除本机草稿")
                        }
                        .padding(11)
                        .background(Color(UIColor.secondarySystemGroupedBackground),
                                    in: RoundedRectangle(cornerRadius: 10))
                    }
                }
                Divider()
                Text("本页只保存本机草稿；经测试账号权限验证后，可在“测试营业同步”或“采购同步”提交，正式账本与结算写入始终锁定。")
                    .font(.caption2).foregroundColor(.secondary)
            }
            .padding(12)
        }
        .navigationBarTitle("写入测试实验室", displayMode: .inline)
        .sheet(item: $purchaseEditRequest) { request in
            MultiPurchaseEditorView(original: request.draft) { draft in
                guard let book = model.selectedBook, unlocked else {
                    draftManager.message = "测试账本尚未解锁"
                    return false
                }
                return draftManager.save(draft, book: book)
            }
            .environmentObject(model)
        }
        .onAppear(perform: reload)
        .onChange(of: model.selectedBookID) { _ in
            purchaseEditRequest = nil
            reload()
        }
    }

    private var businessForm: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("营业录入草稿").font(.subheadline.bold())
            Picker("营业位置", selection: $storeSyncID) {
                Text("请选择已同步的位置").tag("")
                ForEach(model.snapshot.testStores) { item in
                    Text(item.name).tag(item.id)
                }
            }.pickerStyle(MenuPickerStyle())
            amount("微信收款", text: $wechat)
            collector("微信收款人", selection: $wechatCollectorID)
            amount("支付宝收款", text: $alipay)
            collector("支付宝收款人", selection: $alipayCollectorID)
            amount("现金收款", text: $cash)
            collector("现金收款人", selection: $cashCollectorID)
            amount("支出", text: $expense)
            collector("支出付款人", selection: $expensePayerID)
            amount("开摊库存估值", text: $openingStock)
            amount("收摊库存估值", text: $closingStock)
            amount("当日采购金额（仅本机备注，不参与营业上传）", text: $purchaseCost)
            Text("测试营业上传遵守 Android 原营业利润公式，不扣凌晨收款，不生成资金结算；正式账本写入仍锁定。")
                .font(.caption2).foregroundColor(.orange)
        }
        .padding(12)
        .background(Color(UIColor.secondarySystemGroupedBackground),
                    in: RoundedRectangle(cornerRadius: 11))
    }

    private var purchaseForm: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("旧版单商品快捷录入").font(.subheadline.bold())
            Picker("采购人", selection: $buyerSyncID) {
                Text("请选择已同步采购人").tag("")
                ForEach(model.snapshot.testPartners) { item in
                    Text(item.name).tag(item.id)
                }
            }.pickerStyle(MenuPickerStyle())
            Picker("商品", selection: $fruitSyncID) {
                Text("请选择已同步商品").tag("")
                ForEach(model.snapshot.testFruits) { item in
                    Text(item.name).tag(item.id)
                }
            }.pickerStyle(MenuPickerStyle())
            Picker("采购类型", selection: $draftStatus) {
                Text("计划采购").tag("PLANNED")
                Text("临时采购").tag("TEMPORARY")
            }.pickerStyle(SegmentedPickerStyle())
            field("数量", text: $quantity, numeric: true)
            field("单位（件/箱/袋/斤）", text: $unit)
            amount("总价（元）", text: $totalCost)
            amount("每件重量（斤，可为 0）", text: $unitWeightJin)
            field("备注", text: $remark)
            Text("保留采购订单与商品明细的关系信息；目前不生成实际采购订单，也不会改变库存成本或经营利润。")
                .font(.caption2).foregroundColor(.orange)
        }
        .padding(12)
        .background(Color(UIColor.secondarySystemGroupedBackground),
                    in: RoundedRectangle(cornerRadius: 11))
    }

    private func collector(_ title: String, selection: Binding<String>) -> some View {
        Picker(title, selection: selection) {
            Text("未指定").tag("")
            ForEach(model.snapshot.testPartners) { item in
                Text(item.name).tag(item.id)
            }
        }
        .pickerStyle(MenuPickerStyle())
    }

    private func amount(_ title: String, text: Binding<String>) -> some View {
        field(title, text: text, numeric: true)
    }

    private func field(_ title: String, text: Binding<String>,
                       numeric: Bool = false) -> some View {
        HStack {
            Text(title).font(.caption).frame(maxWidth: .infinity, alignment: .leading)
            TextField("请输入", text: text)
                .keyboardType(numeric ? .decimalPad : .default)
                .multilineTextAlignment(.trailing)
                .font(.subheadline)
                .frame(maxWidth: 130)
        }
    }

    private func save() {
        guard let account = model.account, let book, unlocked else {
            draftManager.message = "测试账本尚未解锁"
            return
        }
        let fields: [String: String]
        if draftKind == .business {
            let store = model.snapshot.testStores.first { $0.id == storeSyncID }
            fields = [
                "store_sync_id": storeSyncID,
                "store_name": store?.name ?? "",
                "wechat": wechat,
                "wechat_collector_sync_id": wechatCollectorID,
                "alipay": alipay,
                "alipay_collector_sync_id": alipayCollectorID,
                "cash": cash,
                "cash_collector_sync_id": cashCollectorID,
                "expense": expense,
                "expense_payer_sync_id": expensePayerID,
                "opening_stock_value": openingStock,
                "stock_left_value": closingStock,
                "purchase_cost": purchaseCost
            ]
        } else {
            let fruit = model.snapshot.testFruits.first { $0.id == fruitSyncID }
            fields = [
                "buyer_sync_id": buyerSyncID,
                "fruit_sync_id": fruitSyncID,
                "fruit_name": fruit?.name ?? "",
                "quantity": quantity,
                "unit": unit.trimmingCharacters(in: .whitespacesAndNewlines),
                "total_cost": totalCost,
                "unit_weight_jin": unitWeightJin,
                "purchase_type": draftStatus,
                "remark": remark
            ]
        }
        let draft = TestDraft(
            id: UUID().uuidString, account: account.username, bookID: book.id,
            kind: draftKind, date: BusinessDates.key(entryDate),
            fields: fields, createdAt: Date())
        draftManager.save(draft, book: book)
    }

    private func reload() {
        draftManager.reload(account: model.account?.username,
                            book: model.selectedBookID)
    }
}
