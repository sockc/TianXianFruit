import Foundation
import SwiftUI

/// A separate, test-ledger-only draft. V1 storage and submission never read it.
struct TXBusinessV2EditorScope: Identifiable, Equatable {
    let account: String
    let book: String
    let date: String
    let storeID: String
    let storeName: String
    var id: String { [account.lowercased(), book.lowercased(), date, storeID].joined(separator: "|") }

    func permits(_ account: Account?, _ book: CloudBook?) -> Bool {
        guard let account, let book, book.isAvailable,
              book.name.hasPrefix("iOS_TEST_"),
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
/// malformed data or share test and formal scope keys.
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
    struct Prepared {
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
        var natural: [TXBusinessV2Natural] = []
        var lines: [BusinessCollectorDraftLine] = []
        for input in draft.collectors {
            let key = scope.date + "|" + input.partner
            guard !events.contains(where: { $0.kind == "NATURAL" &&
                ($0.key == key || ($0.payload["date"] == .string(scope.date) &&
                 $0.payload["partner_sync_id"] == .string(input.partner))) }) else {
                throw BusinessAtomicError.cloudConflict
            }
            let pair = try sources(events, date: scope.date, partner: input.partner)
            let request = try input.request(previousRevision: pair.0.revision, followingRevision: pair.1.revision)
            let adjusted = try TXBusinessV2Preparation.adjusted(request,
                previous: pair.0.morning, following: pair.1.morning)
            natural.append(request)
            lines.append(BusinessCollectorDraftLine(id: input.id, partnerSyncID: input.partner,
                wechat: BusinessCommitPolicy.money(adjusted[0]), alipay: BusinessCommitPolicy.money(adjusted[1]),
                cash: BusinessCommitPolicy.money(adjusted[2])))
        }
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
    @Environment(\.dismiss) private var dismiss
    let scope: TXBusinessV2EditorScope
    @State private var draft = TXBusinessV2LocalDraft()
    @State private var saved: TXBusinessV2LocalDraft?
    @State private var loaded = false
    @State private var storageError: String?
    @State private var message = ""
    @State private var prepared: TXBusinessV2DraftPolicy.Prepared?
    @State private var showClose = false

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
        } catch { storageError = error.localizedDescription }
    }
    private func save() {
        guard permitted, loaded, storageError == nil else { return }
        do {
            try TXBusinessV2DraftStore.save(draft, scope: scope)
            saved = draft
            message = "测试草稿已保存本机，未上传"
        } catch { message = error.localizedDescription }
    }
    private func prepare() {
        prepared = nil
        guard sourcesReady, let account = model.account, let book = model.selectedBook else {
            message = "请先完成在线营业及凌晨同步"; return
        }
        do {
            guard !((try BusinessLocalDraftStore.loadChecked(account: scope.account, bookID: scope.book))
                .contains { $0.date == scope.date && $0.storeID == scope.storeID }) else {
                message = "此日期位置已有 V1 草稿，禁止混用两套录入。请选择其他测试日期或位置。"; return
            }
            let result = try TXBusinessV2DraftPolicy.prepare(draft, scope: scope, account: account,
                book: book, records: Array(model.snapshot.records.values), events: events)
            prepared = result
            message = "请求预览已生成，未发送"
        } catch { message = error.localizedDescription }
    }

    private func requestClose() {
        if saved != draft && storageError == nil { showClose = true }
        else { dismiss() }
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
            TXReceiptChannelInput(label: "日常开销", value: $draft.expense)
            Picker("费用付款人", selection: $draft.payer) {
                Text("请选择付款人").tag("")
                ForEach(model.snapshot.testPartners) { Text($0.name).tag($0.id) }
            }
            HStack {
                TXReceiptChannelInput(label: "开摊库存估值", value: $draft.opening)
                TXReceiptChannelInput(label: "收摊库存估值", value: $draft.closing)
            }
            HStack {
                TextField("开摊 HH:mm", text: $draft.start).textFieldStyle(.roundedBorder)
                TextField("收摊 HH:mm", text: $draft.end).textFieldStyle(.roundedBorder)
            }
        }
    }

    var body: some View {
        NavigationView {
            Form {
                Section {
                    Text(scope.date + " · " + scope.storeName).font(.subheadline.bold())
                    Text("仅隔离测试账本，V2 本地录入及预览。未开放网络提交，不影响正式账本。")
                        .font(.caption).foregroundColor(.orange)
                    Button {
                        Task {
                            await model.refresh()
                            guard permitted else { return }
                            await model.refreshCustomer()
                        }
                    } label: {
                        Label(sourcesReady ? "刷新凌晨及营业来源" : "核对云端来源", systemImage: "arrow.clockwise")
                    }.disabled(model.isBusy || model.isCustomerBusy || !permitted)
                    if !sourcesReady { Text("来源未在线核验，暂不计算调整金额。").font(.caption).foregroundColor(.orange) }
                }
                Section(header: Text("收款人及自然日收款")) {
                    collectorFields
                    Button { draft.collectors.append(TXBusinessV2CollectorInput()) } label: {
                        Label("增加收款人", systemImage: "person.badge.plus")
                    }.disabled(draft.collectors.count >= 12)
                }.disabled(!permitted || storageError != nil)
                Section(header: Text("费用及库存")) { costFields }
                    .disabled(!permitted || storageError != nil)
                Section {
                    Button("保存测试草稿到本机", action: save)
                        .disabled(!permitted || !loaded || storageError != nil)
                    Button("生成 V2 请求预览", action: prepare)
                        .disabled(!sourcesReady || storageError != nil)
                    if let prepared {
                        HStack {
                            TXCompactMetric(title: "本摊营业额", value: "¥" + prepared.proposal.totalRevenue, tint: .green)
                            TXCompactMetric(title: "利润预览", value: "¥" + prepared.proposal.profit, tint: .orange)
                        }
                        Text("请求指纹：" + prepared.hash).font(.caption2).textSelection(.enabled)
                        Text("尚未发送，不能当作云端营业或结算结果。").font(.caption).foregroundColor(.orange)
                    }
                    if let storageError { Text(storageError).foregroundColor(.red).font(.caption) }
                    if !message.isEmpty { Text(message).font(.caption) }
                }
            }
            .navigationTitle("凌晨与费用录入")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("关闭", action: requestClose) } }
            .interactiveDismissDisabled(true)
            .onAppear(perform: load)
            .onChange(of: draft) { _ in prepared = nil; message = "" }
            .onChange(of: model.customerCursor) { _ in prepared = nil }
            .onChange(of: model.currentCursor) { _ in prepared = nil }
            .onChange(of: model.customerSyncedAt) { _ in prepared = nil }
            .onChange(of: model.lastSyncAt) { _ in prepared = nil }
            .onChange(of: sourcesReady) { ready in if !ready { prepared = nil } }
            .onChange(of: permitted) { allowed in if !allowed { prepared = nil; dismiss() } }
            .confirmationDialog("有未保存的测试草稿", isPresented: $showClose, titleVisibility: .visible) {
                Button("保存草稿并关闭") { save(); if saved == draft { dismiss() } }
                Button("放弃未保存修改", role: .destructive) { dismiss() }
                Button("继续编辑", role: .cancel) {}
            }
        }.navigationViewStyle(.stack)
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
                        Text("留空=未确认，输入0=确认零；仅修改测试草稿。").font(.caption2).foregroundColor(.secondary)
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
