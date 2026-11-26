import Foundation
import SwiftUI

/// An isolated V2 draft; account/book/date/store identity is unchanged from Build 79.
struct TXBusinessV2EditorScope: Identifiable, Equatable, Codable {
    let account: String
    let book: String
    let date: String
    let storeID: String
    let storeName: String
    var id: String { [account.lowercased(), book.lowercased(), date, storeID].joined(separator: "|") }

    // Local editor identity only. All network writes require fresh server capabilities.
    func permits(_ account: Account?, _ book: CloudBook?) -> Bool {
        guard let account, let book, book.isAvailable,
              ["OWNER", "EDITOR", "SUPERADMIN"].contains(book.role.uppercased()) else { return false }
        return account.username.lowercased() == self.account.lowercased() && book.id == self.book
    }
}

struct TXBusinessV2ChannelDraft: Codable, Equatable {
    var natural = "0"
    var deduct = true
    // Blank = not confirmed. Explicit zero is a confirmed amount.
    var morning = ""
}

struct TXBusinessV2CollectorInput: Codable, Equatable, Identifiable {
    var id = UUID().uuidString
    var partner = ""
    var wechat = TXBusinessV2ChannelDraft()
    var alipay = TXBusinessV2ChannelDraft()
    var cash = TXBusinessV2ChannelDraft()
    var replaceFollowing = false
    // Persist the last explicitly acknowledged source revisions with the draft.
    var boundPreviousRevision: Int64?
    var boundFollowingRevision: Int64?

    mutating func acknowledge(previous: Int64, following: Int64) {
        boundPreviousRevision = previous
        boundFollowingRevision = following
    }

    func matchesSources(previous: Int64, following: Int64) -> Bool {
        boundPreviousRevision == previous && boundFollowingRevision == following
    }

    var channels: [TXBusinessV2ChannelDraft] { [wechat, alipay, cash] }

    func request(previousRevision: Int64, followingRevision: Int64) throws -> TXBusinessV2Natural {
        guard matchesSources(previous: previousRevision, following: followingRevision) else {
            throw BusinessAtomicError.cloudConflict
        }
        let values = channels
        let replacement: TXBusinessV2Morning?
        if replaceFollowing {
            let confirmed = values.map { !$0.morning.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
            let money = try values.enumerated().map { index, item in
                BusinessCommitPolicy.money(try BusinessCollectorDraftPolicy.cents(
                    confirmed[index] ? item.morning : "0", label: "本次凌晨"))
            }
            replacement = TXBusinessV2Morning(wechat: money[0], alipay: money[1], cash: money[2],
                wechatRecorded: confirmed[0], alipayRecorded: confirmed[1], cashRecorded: confirmed[2])
        } else { replacement = nil }
        return TXBusinessV2Natural(partnerSyncID: partner, wechat: wechat.natural,
            alipay: alipay.natural, cash: cash.natural, deductWechat: wechat.deduct,
            deductAlipay: alipay.deduct, deductCash: cash.deduct,
            previousRevision: previousRevision, followingRevision: followingRevision, following: replacement)
    }
}

struct TXBusinessV2LocalDraft: Codable, Equatable {
    var operationID = UUID().uuidString.lowercased()
    var collectors = [TXBusinessV2CollectorInput()]
    var start = "16:00"
    var end = ""
    var expense = ""
    var payer = ""
    var opening = ""
    var closing = ""
}

/// Local-only and separate from BusinessLocalDraftStore. Never overwrite
/// malformed data or share account/book scope keys. Keep Build 79 key for recovery.
enum TXBusinessV2DraftStore {
    static func key(_ scope: TXBusinessV2EditorScope) -> String {
        "ios.test.business.v2.draft." + scope.id
    }
    static func load(_ scope: TXBusinessV2EditorScope, defaults: UserDefaults = .standard) throws -> TXBusinessV2LocalDraft? {
        guard let raw = defaults.object(forKey: key(scope)) else { return nil }
        guard let data = raw as? Data,
              let draft = try? JSONDecoder().decode(TXBusinessV2LocalDraft.self, from: data),
              UUID(uuidString: draft.operationID) != nil,
              (1...12).contains(draft.collectors.count),
              Set(draft.collectors.map(\.id)).count == draft.collectors.count else {
            throw BusinessLocalDraftStoreError.unreadable
        }
        return draft
    }
    static func save(_ draft: TXBusinessV2LocalDraft, scope: TXBusinessV2EditorScope,
                     defaults: UserDefaults = .standard) throws {
        _ = try load(scope, defaults: defaults)
        let storageKey = key(scope)
        if let old = defaults.data(forKey: storageKey) {
            defaults.set(old, forKey: storageKey + ".backup")
        }
        defaults.set(try JSONEncoder().encode(draft), forKey: storageKey)
        guard try load(scope, defaults: defaults) == draft else {
            throw BusinessLocalDraftStoreError.verificationFailed
        }
    }
}

enum TXBusinessV2DraftPolicy {
    typealias Morning = TXBusinessOvernightReferencePolicy.Morning
    struct Source {
        let morning: Morning
        let revision: Int64
    }
    struct Prepared: Codable, Equatable {
        let proposal: BusinessCommitProposal
        let canonical: Data
        let hash: String
    }

    static func source(_ events: [CustomerChangeEvent], date: String, partner: String) throws -> Source {
        let morning = TXBusinessOvernightReferencePolicy.morning(events, date: date, partner: partner)
        let key = date + "|" + partner
        let candidates = events.filter {
            $0.kind == "OVERNIGHT" && ($0.key == key ||
                ($0.payload["date"] == .string(date) && $0.payload["partner_sync_id"] == .string(partner)))
        }
        guard morning != .invalid, candidates.count <= 1 else { throw BusinessAtomicError.cloudConflict }
        if let row = candidates.first {
            guard !row.isDeleted, row.key == key, row.revision > 0,
                  row.payload["date"] == .string(date),
                  row.payload["partner_sync_id"] == .string(partner) else {
                throw BusinessAtomicError.cloudConflict
            }
            return Source(morning: morning, revision: row.revision)
        }
        return Source(morning: morning, revision: 0)
    }

    static func sources(_ events: [CustomerChangeEvent], date: String, partner: String) throws -> (Source, Source) {
        guard let reference = TXBusinessOvernightReferencePolicy.reference(events, businessDate: date, partner: partner) else {
            throw BusinessAtomicError.invalidDraft
        }
        return (try source(events, date: date, partner: partner),
                try source(events, date: reference.followingDate, partner: partner))
    }

    static func adjustedCollectors(_ draft: TXBusinessV2LocalDraft, date: String,
                                   events: [CustomerChangeEvent]) throws -> (natural: [TXBusinessV2Natural], lines: [BusinessCollectorDraftLine]) {
        guard (1...12).contains(draft.collectors.count),
              Set(draft.collectors.map(\.partner)).count == draft.collectors.count else {
            throw BusinessAtomicError.invalidDraft
        }
        var natural: [TXBusinessV2Natural] = []
        var lines: [BusinessCollectorDraftLine] = []
        for input in draft.collectors {
            let key = date + "|" + input.partner
            guard !events.contains(where: { $0.kind == "NATURAL" &&
                ($0.key == key || ($0.payload["date"] == .string(date) &&
                 $0.payload["partner_sync_id"] == .string(input.partner))) }) else {
                throw BusinessAtomicError.cloudConflict
            }
            let pair = try sources(events, date: date, partner: input.partner)
            let request = try input.request(previousRevision: pair.0.revision, followingRevision: pair.1.revision)
            let adjusted = try TXBusinessV2Preparation.adjusted(request,
                previous: pair.0.morning, following: pair.1.morning)
            natural.append(request)
            lines.append(BusinessCollectorDraftLine(id: input.id, partnerSyncID: input.partner,
                wechat: BusinessCommitPolicy.money(adjusted[0]), alipay: BusinessCommitPolicy.money(adjusted[1]),
                cash: BusinessCommitPolicy.money(adjusted[2])))
        }
        return (natural, lines)
    }

    static func preview(_ draft: TXBusinessV2LocalDraft, date: String,
                        events: [CustomerChangeEvent]) -> (revenue: String, profit: String?)? {
        guard let adjusted = try? adjustedCollectors(draft, date: date, events: events),
              let totals = try? BusinessCollectorDraftPolicy.totals(adjusted.lines) else { return nil }
        let revenue = BusinessCommitPolicy.money(totals.wechat + totals.alipay + totals.cash)
        let profit = BusinessDraftWorkflowPolicy.preview(lines: adjusted.lines, expense: draft.expense,
            opening: draft.opening, closing: draft.closing)?.profit
        return (revenue, profit)
    }

    static func prepare(_ draft: TXBusinessV2LocalDraft, scope: TXBusinessV2EditorScope,
                        account: Account, book: CloudBook, records: [SyncEvent],
                        events: [CustomerChangeEvent]) throws -> Prepared {
        guard scope.permits(account, book), (1...12).contains(draft.collectors.count),
              Set(draft.collectors.map(\.partner)).count == draft.collectors.count else {
            throw BusinessAtomicError.scope
        }
        let expense = try BusinessCollectorDraftPolicy.cents(draft.expense, label: "日常开销")
        guard expense == 0 || !draft.payer.isEmpty else { throw BusinessAtomicError.invalidDraft }
        if !draft.payer.isEmpty {
            guard records.filter({ $0.tableName == "partner" && !$0.isDeletion && $0.syncId == draft.payer }).count == 1 else {
                throw BusinessAtomicError.invalidDraft
            }
        }
        let (natural, lines) = try adjustedCollectors(draft, date: scope.date, events: events)
        // Reuse the stable arithmetic and validation, not V1 transport or storage.
        let base = BusinessLocalDraft(id: draft.operationID, date: scope.date, storeID: scope.storeID,
            storeName: scope.storeName, collectors: lines, startTime: draft.start, endTime: draft.end,
            note: "", expense: draft.expense, openingStock: draft.opening, closingStock: draft.closing, updatedAt: Date())
        let proposal = try BusinessCommitPolicy.prepare(base, book: book, account: account, records: records)
        let canonical = try TXBusinessV2Preparation.canonical(proposal,
            payer: draft.payer.isEmpty ? nil : draft.payer, natural: natural)
        return Prepared(proposal: proposal, canonical: canonical, hash: TXBusinessV2Preparation.hash(canonical))
    }
}

struct TXBusinessV2DraftEditor: View {
    @EnvironmentObject private var model: LedgerViewModel
    let scope: TXBusinessV2EditorScope
    let onClose: () -> Void
    @State private var draft = TXBusinessV2LocalDraft()
    @State private var saved: TXBusinessV2LocalDraft?
    @State private var loaded = false
    @State private var storageError: String?
    @State private var message = ""
    @State private var prepared: TXBusinessV2DraftPolicy.Prepared?
    @State private var showClose = false
    @State private var reserved: TXBusinessV2Immutable?
    @State private var serverAllowed = false
    @State private var working = false
    @State private var showSend = false
    @State private var billPlan: TXBillLocalPlan?
    @State private var discardBill = false
    @State private var billAllowed = false
    @State private var billReserved: TXBillV3Prepared?
    @State private var billPrepared: TXBillV3Prepared?
    @State private var billVerified = false
    @State private var showBillSend = false

    private var permitted: Bool { scope.permits(model.account, model.selectedBook) }
    private var sourcesReady: Bool {
        permitted && model.customerOvernightCovered && model.customerAPIAvailable &&
        model.customerError == nil && !model.isCustomerBusy &&
        !model.isUsingCachedAuthorization && model.errorMessage == nil &&
        model.lastSyncAt != nil && model.customerSyncedAt != nil
    }
    private var events: [CustomerChangeEvent] { Array(model.customerSnapshot.events.values) }

    private func load() {
        guard !loaded, permitted else { return }
        loaded = true
        do {
            if let stored = try TXBusinessV2DraftStore.load(scope) {
                draft = stored
            } else if model.snapshot.testPartners.count == 1, let p = model.snapshot.testPartners.first {
                draft.collectors[0].partner = p.id
            }
            saved = draft
            reserved = try TXBusinessV2Outbox().load(scope)
            billPlan = try TXBillPlanStore.load(scope)
            billReserved = try TXBillV3Outbox().load(scope)
        } catch { storageError = error.localizedDescription }
    }
    private func save() {
        guard permitted, loaded, storageError == nil, reserved == nil, billReserved == nil, !working else { return }
        do {
            try TXBusinessV2DraftStore.save(draft, scope: scope)
            saved = draft
            message = "营业草稿已保存本机，未上传"
        } catch { message = error.localizedDescription }
    }
    private func prepare() {
        prepared = nil
        guard billPlan == nil, billReserved == nil else { message = TXBillPlanError.pending.localizedDescription; return }
        guard sourcesReady, reserved == nil, !working, let account = model.account, let book = model.selectedBook else {
            message = "请先完成在线营业及凌晨同步"; return
        }
        do {
            guard !((try BusinessLocalDraftStore.loadChecked(account: scope.account, bookID: scope.book))
                .contains { $0.date == scope.date && $0.storeID == scope.storeID }) else {
                message = "此日期位置已有 V1 草稿，禁止混用两套录入。请选择其他日期或位置。"; return
            }
            let result = try TXBusinessV2DraftPolicy.prepare(draft, scope: scope, account: account,
                book: book, records: Array(model.snapshot.records.values), events: events)
            prepared = result
            message = "请求预览已生成，未发送"
        } catch { message = error.localizedDescription }
    }

    private func refreshSources() async {
        guard !working, permitted else { return }
        serverAllowed = false
        billAllowed = false
        await model.refresh()
        guard permitted else { return }
        await model.refreshCustomer()
        guard permitted else { return }
        do {
            serverAllowed = try await model.checkBusinessV2(scope)
            if !serverAllowed { message = "当前账本未获得 V2 写入授权；草稿仍可保存" }
        } catch { message = "V2 接口未开放：" + error.localizedDescription }
        guard permitted, isTest else { return }
        billAllowed = (try? await model.checkBillV3(scope)) == true
    }

    private func stageBillPlan(_ plan: TXBillLocalPlan) throws {
        guard permitted, loaded, sourcesReady, !editorLocked, billPlan == nil,
              draft == plan.original, plan.customerCursor == model.customerCursor else { throw TXBillPlanError.scope }
        try plan.validate(scope: scope)
        let pair = try TXBusinessV2DraftPolicy.sources(events, date: scope.date, partner: plan.partner)
        guard pair.0.revision == plan.previousRevision, pair.1.revision == plan.followingRevision else { throw TXBillPlanError.source }
        // Save only the ORIGINAL normal draft. The proposed amounts are stored
        // under an independent key so even an older IPA cannot submit them.
        try TXBusinessV2DraftStore.save(draft, scope: scope)
        saved = draft
        try TXBillPlanStore.save(plan, scope: scope)
        billPlan = plan; prepared = nil
    }
    private func discardStagedBill() {
        guard permitted, !editorLocked, billReserved == nil, let billPlan else { return }
        do {
            try TXBillPlanStore.discard(billPlan, scope: scope)
            self.billPlan = nil
            message = "已放弃独立补录草稿，原营业输入未改动"
        } catch { message = error.localizedDescription }
    }
    private func prepareBillSend() {
        billPrepared = nil
        guard isTest, billAllowed, sourcesReady, !editorLocked, let plan = billPlan,
              draft == plan.original, let user = model.account, let book = model.selectedBook else { return }
        do {
            billPrepared = try TXBillV3Prepared.prepare(plan: plan, scope: scope, account: user, book: book,
                records: Array(model.snapshot.records.values), events: events, cursor: model.customerCursor)
            showBillSend = true
        } catch { message = error.localizedDescription }
    }
    private func sendBill() async {
        guard isTest, billAllowed, sourcesReady, !editorLocked, let plan = billPlan,
              draft == plan.original, let request = billPrepared else { return }
        working = true
        defer { working = false; billPrepared = nil }
        do {
            try await model.submitBillV3(request, plan: plan)
            billVerified = true
            message = "营业、凌晨及客户交易均已云端回读一致"
        } catch { message = error.localizedDescription }
        do { billReserved = try TXBillV3Outbox().load(scope) }
        catch { storageError = error.localizedDescription }
    }
    private func recoverBill() async {
        guard !working, permitted, isTest, let request = billReserved else { return }
        working = true
        defer { working = false }
        do {
            try await model.recoverBillV3(request)
            billVerified = true
            message = "已核对原操作：营业及客户数据一致"
        } catch { message = error.localizedDescription }
    }
    private var billActions: some View {
        VStack(alignment: .leading, spacing: 5) {
            if billReserved != nil {
                Text(billVerified ? "联合提交已确认" : "原联合请求已锁定，仅可查询核对")
                    .font(.caption).foregroundColor(billVerified ? .green : .orange)
                Button("查询原联合操作") { Task { await recoverBill() } }.disabled(working || !permitted || !isTest)
            } else {
                if isTest && billAllowed, billPlan?.wireBill != nil {
                    Button("联合保存营业与客户交易") { prepareBillSend() }
                        .buttonStyle(.borderedProminent)
                        .disabled(editorLocked || !sourcesReady || billPlan?.customerCursor != model.customerCursor || billPlan?.original != draft)
                } else {
                    Text("V3 暂未获得测试账本授权，不能联合上传。").font(.caption).foregroundColor(.orange)
                }
                Button("放弃补录草稿", role: .destructive) { discardBill = true }.disabled(editorLocked)
            }
        }
    }
    private var billCard: some View {
        card {
            if let billPlan, let original = billPlan.originalCollector, let proposed = billPlan.proposedCollector {
                Text(billVerified ? "账单补录 · 已回读确认" : (billReserved == nil ? "账单补录草稿 · 未提交" : "账单补录 · 原请求已锁定")).font(.subheadline.bold())
                HStack { Text("微信自然日"); Spacer(); Text(original.wechat.natural + " → " + proposed.wechat.natural) }
                    .font(.caption)
                HStack { Text("支付宝自然日"); Spacer(); Text(original.alipay.natural + " → " + proposed.alipay.natural) }
                    .font(.caption)
                HStack { Text("现金（原样保留）"); Spacer(); Text(original.cash.natural) }.font(.caption)
                let amount = billPlan.adjustedWechat + billPlan.adjustedAlipay + billPlan.adjustedCash
                HStack { Text("该收款人调整后预览"); Spacer(); Text("¥" + BusinessCommitPolicy.money(amount)).bold() }
                    .font(.caption)
                if billReserved == nil && (billPlan.customerCursor != model.customerCursor || billPlan.original != draft) {
                    Text("原输入或客户游标已变化，补录方案需要重新核对。").font(.caption).foregroundColor(.orange)
                }
                if let wire = billPlan.wireBill {
                    Text("已保留 \(wire.receipts.count) 笔完整交易证据，可用于 V3 请求核对。")
                        .font(.caption2).foregroundColor(.secondary)
                } else {
                    Text("仅预览：V3 首版要求完整单日文件、最多1000笔及明确经营凭据。")
                        .font(.caption2).foregroundColor(.orange)
                }
                billActions
            } else if billReserved != nil {
                billActions
            } else {
                TXBillPreviewEntry(scope: scope, draft: draft, onStage: stageBillPlan).disabled(editorLocked || !loaded)
            }
        }
        .alert("放弃独立补录草稿？", isPresented: $discardBill) {
            Button("放弃", role: .destructive, action: discardStagedBill)
            Button("取消", role: .cancel) {}
        } message: { Text("原营业草稿、现金和云端记录均不会被修改。") }
    }
    private func requestClose() {
        guard !working else { return }
        if reserved != nil || billReserved != nil { onClose(); return }
        if saved != draft && storageError == nil { showClose = true }
        else { onClose() }
    }

    private func send(_ request: TXBusinessV2Immutable) async {
        guard !working, permitted, serverAllowed, sourcesReady, billPlan == nil, billReserved == nil, reserved == nil, prepared == request.prepared else { return }
        working = true
        defer { working = false }
        do {
            try await model.submitBusinessV2(request, draft: draft)
            message = "营业、凌晨来源及费用付款人均已云端回读核对"
        } catch { message = error.localizedDescription }
        do { reserved = try TXBusinessV2Outbox().load(scope) }
        catch { storageError = error.localizedDescription }
        prepared = nil
    }
    private func recover() async {
        guard !working, let reserved, permitted else { return }
        working = true
        defer { working = false }
        do {
            try await model.recoverBusinessV2(reserved)
            message = "原操作已确认：营业与凌晨来源均回读一致"
        } catch { message = error.localizedDescription }
    }

    private var collectorFields: some View {
        ForEach($draft.collectors) { $line in
            TXBusinessV2CollectorEditor(line: $line, date: scope.date, events: events,
                sourcesReady: sourcesReady, onRemove: draft.collectors.count > 1 ? {
                    draft.collectors.removeAll { $0.id == line.id }
                } : nil)
        }
    }

    private var costFields: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                TXReceiptChannelInput(label: "日常开销", value: $draft.expense)
                Picker("费用付款人", selection: $draft.payer) {
                    Text("请选择付款人").tag("")
                    ForEach(model.snapshot.testPartners) { Text($0.name).tag($0.id) }
                }.pickerStyle(.menu).lineLimit(1)
            }
            HStack {
                TXReceiptChannelInput(label: "开摊库存估值", value: $draft.opening)
                TXReceiptChannelInput(label: "收摊库存估值", value: $draft.closing)
            }
        }
    }

    private var editorLocked: Bool { !permitted || storageError != nil || reserved != nil || billReserved != nil || working }
    private var isTest: Bool { model.selectedBook?.name.hasPrefix("iOS_TEST_") == true }

    private func card<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 7, content: content)
            .padding(9).frame(maxWidth: .infinity, alignment: .leading)
            .background(TXTheme.header, in: RoundedRectangle(cornerRadius: 10))
    }

    private var header: some View {
        card {
            HStack {
                Text("营业 · " + scope.date).font(.subheadline.bold())
                Spacer()
                Button("关闭", action: requestClose).disabled(working)
            }
            HStack(spacing: 5) {
                Text(scope.storeName).font(.subheadline.bold()).lineLimit(1)
                Spacer(minLength: 3)
                TextField("开摊", text: $draft.start).textFieldStyle(.roundedBorder).frame(width: 70)
                Text("—").foregroundColor(.secondary)
                TextField("收摊", text: $draft.end).textFieldStyle(.roundedBorder).frame(width: 70)
            }.disabled(editorLocked)
            if isTest { Text("测试账本").font(.caption2).foregroundColor(.orange) }
            HStack {
                Text(serverAllowed ? "营业 V2 已授权" : "V2 未授权或待核对")
                    .font(.caption2).foregroundColor(serverAllowed ? .green : .orange)
                Spacer()
                Button { Task { await refreshSources() } } label: {
                    Label("核对来源", systemImage: "arrow.clockwise").font(.caption)
                }.disabled(model.isBusy || model.isCustomerBusy || !permitted || working)
            }
            if !sourcesReady { Text("来源未在线核验，暂不计算调整金额。").font(.caption2).foregroundColor(.orange) }
        }
    }

    private var submission: some View {
        card {
            if let reserved {
                HStack {
                    TXCompactMetric(title: "原提交营业额", value: "¥" + reserved.prepared.proposal.totalRevenue, tint: .green)
                    TXCompactMetric(title: "原提交利润", value: "¥" + reserved.prepared.proposal.profit, tint: .orange)
                }
            } else {
                let current = sourcesReady ? TXBusinessV2DraftPolicy.preview(draft, date: scope.date, events: events) : nil
                HStack {
                    TXCompactMetric(title: "本摊营业额", value: current.map { "¥" + $0.revenue } ?? "待核对", tint: .green)
                    TXCompactMetric(title: "利润预览", value: current?.profit.map { "¥" + $0 } ?? "待补齐", tint: .orange)
                }
            }
            if let prepared {
                DisclosureGroup("请求指纹") {
                    Text(prepared.hash).font(.caption2).textSelection(.enabled)
                }.font(.caption2)
                Text("尚未发送，不能当作云端营业或结算结果。").font(.caption2).foregroundColor(.orange)
            }
            if reserved == nil {
                HStack {
                    Button("保存本机草稿", action: save).buttonStyle(.bordered)
                        .disabled(editorLocked || !loaded)
                    Spacer()
                    Button("保存营业") {
                        prepare()
                        if prepared != nil && serverAllowed { showSend = true }
                    }.buttonStyle(.borderedProminent)
                        .disabled(editorLocked || !sourcesReady || !serverAllowed || billPlan != nil)
                }
            } else {
                Text("原操作已锁定，只能查询及回读；不会自动重发。").font(.caption).foregroundColor(.orange)
                Button("查询原操作并核对") { Task { await recover() } }.disabled(working || !permitted)
            }
            if let storageError { Text(storageError).foregroundColor(.red).font(.caption) }
            if !message.isEmpty { Text(message).font(.caption) }
        }
    }

    private var receiptCard: some View {
        card {
            Text("营业收款").font(.subheadline.bold())
            collectorFields
            Button {
                draft.collectors.append(TXBusinessV2CollectorInput())
            } label: {
                Label("增加收款人", systemImage: "person.badge.plus").font(.caption)
            }.disabled(draft.collectors.count >= 12)
        }.disabled(editorLocked || billPlan != nil)
    }

    private var editorContent: some View {
        VStack(alignment: .leading, spacing: 7) {
            header
            billCard
            receiptCard
            card { costFields }.disabled(editorLocked)
            submission
        }
    }

    private var observedContent: some View {
        editorContent
            .onAppear(perform: load)
            .task { await refreshSources() }
            .onChange(of: draft) { _ in prepared = nil; billPrepared = nil; message = "" }
            .onChange(of: model.customerCursor) { _ in prepared = nil; billPrepared = nil }
            .onChange(of: model.currentCursor) { _ in prepared = nil; billPrepared = nil }
    }

    private var guardedContent: some View {
        observedContent
            .onChange(of: model.customerSyncedAt) { _ in prepared = nil }
            .onChange(of: model.lastSyncAt) { _ in prepared = nil }
            .onChange(of: sourcesReady) { ready in if !ready { prepared = nil } }
            .onChange(of: permitted) { allowed in if !allowed { prepared = nil; onClose() } }
    }

    private var confirmationTitle: String {
        isTest ? "确认保存到测试账本？" : "确认保存营业到正式账本？"
    }
    private var confirmationMessage: String {
        let amount = prepared?.proposal.totalRevenue ?? "待核对"
        return scope.storeName + " · " + scope.date + "\n营业额 ¥" + amount
    }
    private func confirmSend() {
        guard let prepared else { return }
        let value = TXBusinessV2Immutable(scope: scope, prepared: prepared)
        Task { await send(value) }
    }

    private var confirmedContent: some View {
        guardedContent.alert(confirmationTitle, isPresented: $showSend) {
            Button("确认提交", action: confirmSend)
            Button("取消", role: .cancel) {}
        } message: {
            Text(confirmationMessage)
        }
    }

    private var billConfirmedContent: some View {
        confirmedContent.alert("确认联合保存到测试账本？", isPresented: $showBillSend) {
            Button("确认提交") { Task { await sendBill() } }
            Button("取消", role: .cancel) { billPrepared = nil }
        } message: {
            Text("营业额 ¥" + (billPrepared?.base.prepared.proposal.totalRevenue ?? "待核对") + "，账单 " + String(billPrepared?.bill.receipts.count ?? 0) + " 笔。一次联合保存，失败后查询原操作。")
        }
    }
    var body: some View {
        billConfirmedContent.confirmationDialog("有未保存的营业草稿", isPresented: $showClose, titleVisibility: .visible) {
            Button("保存草稿并关闭") { save(); if saved == draft { onClose() } }
            Button("放弃未保存修改", role: .destructive) { onClose() }
            Button("继续编辑", role: .cancel) {}
        }
    }
}

private struct TXBusinessV2CollectorEditor: View {
    @EnvironmentObject private var model: LedgerViewModel
    @Binding var line: TXBusinessV2CollectorInput
    let date: String
    let events: [CustomerChangeEvent]
    let sourcesReady: Bool
    let onRemove: (() -> Void)?

    private var reference: TXBusinessOvernightReferencePolicy.Reference? {
        guard sourcesReady, !line.partner.isEmpty else { return nil }
        return TXBusinessOvernightReferencePolicy.reference(events, businessDate: date, partner: line.partner)
    }
    private func amounts(_ morning: TXBusinessOvernightReferencePolicy.Morning?) -> [Int64?] {
        if case .recorded(let x)? = morning { return [x.wechat, x.alipay, x.cash] }
        return [nil, nil, nil]
    }
    private var sourcePair: (TXBusinessV2DraftPolicy.Source, TXBusinessV2DraftPolicy.Source)? {
        guard sourcesReady, !line.partner.isEmpty else { return nil }
        return try? TXBusinessV2DraftPolicy.sources(events, date: date, partner: line.partner)
    }

    private var sourceMatches: Bool {
        guard let pair = sourcePair else { return false }
        return line.matchesSources(previous: pair.0.revision, following: pair.1.revision)
    }

    private func changeFollowing(_ enabled: Bool) {
        guard sourceMatches else { return }
        if enabled {
            let values = amounts(reference?.following)
            line.wechat.morning = values[0].map(BusinessCommitPolicy.money) ?? ""
            line.alipay.morning = values[1].map(BusinessCommitPolicy.money) ?? ""
            line.cash.morning = values[2].map(BusinessCommitPolicy.money) ?? ""
        }
        line.replaceFollowing = enabled
    }

    private var adjustedPreview: String {
        guard let reference,
              let pair = try? TXBusinessV2DraftPolicy.sources(events, date: date, partner: line.partner),
              let request = try? line.request(previousRevision: pair.0.revision, followingRevision: pair.1.revision),
              let result = try? TXBusinessV2Preparation.adjusted(request, previous: reference.previous,
                following: reference.following) else { return "待核对" }
        return "¥" + BusinessCommitPolicy.money(result.reduce(0,+))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Picker("收款人", selection: $line.partner) {
                    Text("请选择收款人").tag("")
                    ForEach(model.snapshot.testPartners) { Text($0.name).tag($0.id) }
                }
                if let onRemove { Button(role: .destructive, action: onRemove) { Image(systemName: "minus.circle") } }
            }
            HStack(spacing: 5) {
                TXReceiptChannelInput(label: "微信", value: $line.wechat.natural)
                TXReceiptChannelInput(label: "支付宝", value: $line.alipay.natural)
                TXReceiptChannelInput(label: "现金", value: $line.cash.natural)
            }
            if let reference {
                let previous = amounts(reference.previous)
                if previous.contains(where: { $0 != nil && $0 != 0 }) {
                    HStack(spacing: 4) {
                        Text("昨日凌晨").font(.caption2)
                        deduction(previous[0], flag: $line.wechat.deduct)
                        deduction(previous[1], flag: $line.alipay.deduct)
                        deduction(previous[2], flag: $line.cash.deduct)
                    }
                    Text("绿色扣减 · 红色不扣减").font(.caption2).foregroundColor(.secondary)
                }
                if reference.previous == .invalid || reference.following == .invalid {
                    Text("凌晨来源异常，请在安卓核对").font(.caption).foregroundColor(.orange)
                } else {
                    let next = amounts(reference.following)
                    if next.contains(where: { $0 != nil }) {
                        Text("云端本次凌晨： " + next.map { $0.map(BusinessCommitPolicy.money) ?? "未确认" }.joined(separator: " / "))
                            .font(.caption2).foregroundColor(.secondary)
                    }
                    if let pair = sourcePair, !sourceMatches {
                        Text("首次录入或来源版本已变化，请核对以上云端金额；不会自动覆盖已输入数据。")
                            .font(.caption2).foregroundColor(.orange)
                        Button("确认采用当前来源版本") {
                            line.acknowledge(previous: pair.0.revision, following: pair.1.revision)
                        }.font(.caption)
                    }
                    Toggle("登记／修改本次凌晨", isOn: Binding(get: { line.replaceFollowing }, set: changeFollowing))
                        .font(.caption).disabled(!sourceMatches)
                    if line.replaceFollowing {
                        HStack(spacing: 5) {
                            TXReceiptChannelInput(label: "微信", value: $line.wechat.morning)
                            TXReceiptChannelInput(label: "支付宝", value: $line.alipay.morning)
                            TXReceiptChannelInput(label: "现金", value: $line.cash.morning)
                        }
                        Text("留空=未确认，输入0=确认零；仅修改营业草稿。").font(.caption2).foregroundColor(.secondary)
                    }
                }
            }
            HStack { Text("调整后收款").font(.caption); Spacer(); Text(adjustedPreview).font(.subheadline.bold()) }
        }
        .onChange(of: line.partner) { _ in
            // Never copy one collector's morning replacement to another.
            line.replaceFollowing = false
            line.boundPreviousRevision = nil; line.boundFollowingRevision = nil
            line.wechat.morning = ""; line.alipay.morning = ""; line.cash.morning = ""
            line.wechat.deduct = true; line.alipay.deduct = true; line.cash.deduct = true
        }
    }

    private func deduction(_ amount: Int64?, flag: Binding<Bool>) -> some View {
        Button {
            if let amount, amount > 0 { flag.wrappedValue.toggle() }
        } label: {
            Text(amount.map(BusinessCommitPolicy.money) ?? "—")
                .font(.caption.bold()).foregroundColor(flag.wrappedValue ? .green : .red)
                .frame(maxWidth: .infinity)
        }.buttonStyle(.plain).disabled(amount == nil || amount == 0)
    }
}
