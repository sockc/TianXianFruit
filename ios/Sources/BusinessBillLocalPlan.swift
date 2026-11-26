import Foundation

enum TXBillPlanError: LocalizedError {
    case incomplete, scope, conflict, excluded, source, pending
    var errorDescription: String? {
        switch self {
        case .incomplete: return "请核对所选渠道完整自然日账单，异常记录不能自动补录"
        case .scope: return "收款人、位置或原营业草稿已变化"
        case .conflict: return "账单存在归属、交易或覆盖冲突，请先核对"
        case .excluded: return "含大额或手动排除交易，请先核对分析设置"
        case .source: return "凌晨来源未确认或已改变，请返回营业表核对"
        case .pending: return "存在独立补录草稿，尚未联动客户交易；请先处理，不能直接保存营业"
        }
    }
}

/// A distinct local proposal, not a V2 sale or customer transaction.
/// Original and proposed drafts are retained for an exact comparison;
/// only selected electronic natural-day amounts may differ.
struct TXBillLocalPlan: Codable, Equatable {
    let scopeID: String
    let partner: String
    let date: String
    let original: TXBusinessV2LocalDraft
    let proposed: TXBusinessV2LocalDraft
    let platforms: [TXBillPlatform]
    let receiptKeys: [String]
    let fileHashes: [String]
    let previousRevision: Int64
    let followingRevision: Int64
    let customerCursor: Int64
    let adjustedWechat: Int64
    let adjustedAlipay: Int64
    let adjustedCash: Int64

    static func build(scope: TXBusinessV2EditorScope, draft: TXBusinessV2LocalDraft,
                      partner: String, batch: TXBillBatchPreview, confirmed: Set<TXBillPlatform>,
                      events: [CustomerChangeEvent], customerCursor: Int64) throws -> Self {
        guard UUID(uuidString: partner) != nil, BusinessDates.parse(scope.date) != nil,
              customerCursor >= 0, draft.collectors.filter({ $0.partner == partner }).count == 1,
              let index = draft.collectors.firstIndex(where: { $0.partner == partner }) else {
            throw TXBillPlanError.scope
        }
        guard !confirmed.isEmpty else { throw TXBillPlanError.incomplete }
        let pair = try TXBusinessV2DraftPolicy.sources(events, date: scope.date, partner: partner)
        let base = draft.collectors[index]
        guard base.matchesSources(previous: pair.0.revision, following: pair.1.revision) else {
            throw TXBillPlanError.source
        }
        guard !events.contains(where: { $0.kind == "NATURAL" &&
            ($0.key == scope.date+"|"+partner ||
             ($0.payload["date"] == .string(scope.date) && $0.payload["partner_sync_id"] == .string(partner))) }) else {
            throw TXBillPlanError.conflict
        }
        let audit = TXBillCloudAudit(events: events)
        let settings = events.first { $0.kind == "SETTINGS" && $0.key == "global" && !$0.isDeleted }
        let threshold = settings?.payload["large_payment_threshold"]?.decimal ?? 2000
        let excludeLarge = settings?.payload["exclude_large_payments"]?.isTrue ?? true
        guard threshold.isFinite, threshold >= 1 else { throw TXBillPlanError.conflict }
        var analysis: [String: String] = [:]
        for e in events where e.kind == "ANALYSIS" && !e.isDeleted {
            if let state = e.payload["state"]?.text { analysis[e.key] = state }
        }
        var proposed = draft
        var keys = Set<String>(), hashes = Set<String>()
        let platforms = confirmed.sorted { $0.rawValue < $1.rawValue }
        for platform in platforms {
            let fileList = batch.files.filter { $0.platform == platform }
            let rows = batch.rows.filter { $0.platform == platform && $0.date == scope.date }
            // An absent channel/day is NOT a confirmed zero. Reject unresolved
            // rows even outside the target date because their dates may be lost.
            guard !fileList.isEmpty, fileList.allSatisfy({ $0.review == 0 }),
                  !rows.isEmpty, rows.contains(where: { $0.cents > 0 }) else { throw TXBillPlanError.incomplete }
            let included = Set(rows.map(\.id))
            guard !audit.hasOmittedKnownReceipts(date: scope.date, platform: platform, partner: partner, included: included) else {
                throw TXBillPlanError.conflict
            }
            for row in rows {
                let source = batch.sourceHashes[row.id] ?? []
                let match = audit.match(row, selectedPartner: partner, fileHashes: source)
                guard match == .new || match == .sameCollector, !source.isEmpty else { throw TXBillPlanError.conflict }
                let state = analysis[row.id]
                if state == "EXCLUDED" || (state != "INCLUDED" && excludeLarge && Double(row.cents)/100 >= threshold) {
                    throw TXBillPlanError.excluded
                }
                keys.insert(row.id); hashes.formUnion(source)
            }
            let net = rows.reduce(Int64(0)) { $0 + $1.net }
            guard net >= 0, (try? BusinessCollectorDraftPolicy.cents(BusinessCommitPolicy.money(net), label: "账单净收款")) == net else {
                throw TXBillPlanError.incomplete
            }
            // RAW natural-day amount; do not subtract yesterday's morning or
            // add following morning here. Existing V2 adjustment does it once.
            if platform == .wechat { proposed.collectors[index].wechat.natural = BusinessCommitPolicy.money(net) }
            else { proposed.collectors[index].alipay.natural = BusinessCommitPolicy.money(net) }
        }
        let input = proposed.collectors[index]
        let request = try input.request(previousRevision: pair.0.revision, followingRevision: pair.1.revision)
        let amounts = try TXBusinessV2Preparation.adjusted(request, previous: pair.0.morning, following: pair.1.morning)
        return Self(scopeID: scope.id, partner: partner, date: scope.date, original: draft, proposed: proposed,
                    platforms: platforms, receiptKeys: keys.sorted(), fileHashes: hashes.sorted(),
                    previousRevision: pair.0.revision, followingRevision: pair.1.revision,
                    customerCursor: customerCursor, adjustedWechat: amounts[0], adjustedAlipay: amounts[1], adjustedCash: amounts[2])
    }

    var originalCollector: TXBusinessV2CollectorInput? { original.collectors.first { $0.partner == partner } }
    var proposedCollector: TXBusinessV2CollectorInput? { proposed.collectors.first { $0.partner == partner } }

    func validate(scope: TXBusinessV2EditorScope) throws {
        guard scopeID == scope.id, date == scope.date, !platforms.isEmpty,
              Set(platforms).count == platforms.count, !receiptKeys.isEmpty, !fileHashes.isEmpty,
              receiptKeys.count <= 100000, fileHashes.count <= 12,
              let i = original.collectors.firstIndex(where: { $0.partner == partner }),
              original.collectors.filter({ $0.partner == partner }).count == 1,
              let target = proposedCollector else { throw TXBillPlanError.scope }
        var expected = original
        if platforms.contains(.wechat) { expected.collectors[i].wechat.natural = target.wechat.natural }
        if platforms.contains(.alipay) { expected.collectors[i].alipay.natural = target.alipay.natural }
        guard expected == proposed, original.collectors[i].matchesSources(previous: previousRevision, following: followingRevision)
        else { throw TXBillPlanError.scope }
    }
}

/// Deliberately independent of the accepted Build 79/80 draft keys and
/// outbox. Never place proposed amounts into the legacy V2 draft store.
enum TXBillPlanStore {
    static func key(_ scope: TXBusinessV2EditorScope) -> String { "ios.business.bill.plan.v1." + scope.id }
    static func load(_ scope: TXBusinessV2EditorScope, defaults: UserDefaults = .standard) throws -> TXBillLocalPlan? {
        guard let raw = defaults.object(forKey: key(scope)) else { return nil }
        guard let data = raw as? Data, let plan = try? JSONDecoder().decode(TXBillLocalPlan.self, from: data) else {
            throw BusinessLocalDraftStoreError.unreadable
        }
        try plan.validate(scope: scope)
        return plan
    }
    static func save(_ plan: TXBillLocalPlan, scope: TXBusinessV2EditorScope,
                     defaults: UserDefaults = .standard) throws {
        try plan.validate(scope: scope)
        guard try load(scope, defaults: defaults) == nil else { throw TXBillPlanError.pending }
        defaults.set(try JSONEncoder().encode(plan), forKey: key(scope))
        guard try load(scope, defaults: defaults) == plan else { throw BusinessLocalDraftStoreError.verificationFailed }
    }
    static func discard(_ plan: TXBillLocalPlan, scope: TXBusinessV2EditorScope,
                        defaults: UserDefaults = .standard) throws {
        guard try load(scope, defaults: defaults) == plan else { throw TXBillPlanError.conflict }
        defaults.removeObject(forKey: key(scope))
        guard try load(scope, defaults: defaults) == nil else { throw BusinessLocalDraftStoreError.verificationFailed }
    }
    static func requireNoPlan(_ scope: TXBusinessV2EditorScope) throws {
        guard try load(scope) == nil else { throw TXBillPlanError.pending }
    }
}
