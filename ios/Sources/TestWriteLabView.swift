import SwiftUI

@MainActor
private final class TestDraftManager: ObservableObject {
    @Published var drafts: [TestDraft] = []
    @Published var message: String?
    private let storage: TestDraftStore?

    init() {
        do { storage = try TestDraftStore() }
        catch { storage = nil; message = error.localizedDescription }
    }

    func reload(account: String?, book: String?) {
        drafts = []
        guard let account, let book, let storage else { return }
        do { drafts = try storage.load(account: account, book: book) }
        catch { message = error.localizedDescription }
    }

    func save(_ draft: TestDraft, book: CloudBook) {
        guard let storage else { message = "本机草稿数据库不可用"; return }
        do {
            try TestWritePolicy.validate(draft, book: book)
            try storage.save(draft)
            drafts = try storage.load(account: draft.account, book: draft.bookID)
            message = "仅保存到这台 iPhone · 未上传任何云端账目"
        } catch {
            message = error.localizedDescription
        }
    }

    func delete(_ draft: TestDraft) {
        guard let storage else { return }
        do {
            try storage.delete(id: draft.id, account: draft.account, book: draft.bookID)
            drafts = try storage.load(account: draft.account, book: draft.bookID)
            message = "已删除本机测试草稿，不影响云端营业或采购"
        } catch {
            message = error.localizedDescription
        }
    }
}

/// Local-only, isolated form rehearsal. Never sends any network mutation.
struct TestWriteLabView: View {
    @EnvironmentObject private var model: LedgerViewModel
    @StateObject private var draftManager = TestDraftManager()
    @State private var acknowledged = false
    @State private var typedBookID = ""
    @State private var draftKind: TestDraftKind = .business
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
    private var unlocked: Bool {
        guard acknowledged, let book else { return false }
        return (try? TestWritePolicy.authorize(book: book, typedID: typedBookID)) != nil
    }

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 14) {
                Label("V0.2.0 · 本机写入预演", systemImage: "lock.shield")
                    .font(.headline).foregroundColor(.orange)
                Text("此版本仅保存测试草稿到这台 iPhone。云端营业、采购、凌晨收款与资金结算仍然完全只读。")
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
                            TextField("手动输入完整测试账本 ID 才能解锁", text: $typedBookID)
                                .textInputAutocapitalization(.never)
                                .autocorrectionDisabled()
                                .font(.caption)
                            Toggle("我确认这是独立测试账本，不含正式营业记录", isOn: $acknowledged)
                                .font(.caption)
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
                                Text("未上传云端").font(.caption2).foregroundColor(.orange)
                            }
                            Spacer()
                            Button(role: .destructive) {
                                draftManager.delete(draft)
                            } label: {
                                Image(systemName: "trash")
                            }
                            .accessibilityLabel("删除本机草稿")
                        }
                        .padding(11)
                        .background(Color(UIColor.secondarySystemGroupedBackground),
                                    in: RoundedRectangle(cornerRadius: 10))
                    }
                }
                Divider()
                Text("何时才能真正写入？必须先验证 Android 资金结算口径、采购父子 ID 跨设备映射、账号权限及服务端冲突策略，再由单独版本明确开放测试账本 /sync/push。本版没有任何业务提交入口或自动重试上传功能。")
                    .font(.caption2).foregroundColor(.secondary)
            }
            .padding(12)
        }
        .navigationBarTitle("写入测试实验室", displayMode: .inline)
        .onAppear(perform: reload)
        .onChange(of: model.selectedBookID) { _ in
            typedBookID = ""
            acknowledged = false
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
            amount("当日采购金额（仅录入核对）", text: $purchaseCost)
            Text("不自动计算利润、不扣凌晨收款、不修改 Android 当日结算。正式云端营业写入仍锁定。")
                .font(.caption2).foregroundColor(.orange)
        }
        .padding(12)
        .background(Color(UIColor.secondarySystemGroupedBackground),
                    in: RoundedRectangle(cornerRadius: 11))
    }

    private var purchaseForm: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("采购录入草稿（单个商品）").font(.subheadline.bold())
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
