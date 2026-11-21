import Foundation

/// Pure presentation and safety rules. Never writes or deletes a ledger row.
/// The persisted operation outbox remains the sole source of truth for whether
/// a local draft can be submitted again.
enum BusinessDraftWorkflowPolicy {
    enum CloudState: Equatable {
        case localOnly
        case waitingForResult
        case acceptedPendingReadback
        case confirmed
        case cloudRecordExists
        case unsafeToRead

        var label: String {
            switch self {
            case .localOnly: return "本机已保存，尚未上传"
            case .waitingForResult: return "原操作待核对，请勿重新提交"
            case .acceptedPendingReadback: return "服务端已接收，等待云端核对"
            case .confirmed: return "云端已确认"
            case .cloudRecordExists: return "云端已有该日该位置营业，请勿重复录入"
            case .unsafeToRead: return "无法核对上传保护记录，禁止提交"
            }
        }

        var needsAttention: Bool {
            switch self {
            case .localOnly, .waitingForResult,
                 .acceptedPendingReadback, .cloudRecordExists, .unsafeToRead:
                return true
            case .confirmed: return false
            }
        }
    }

    static func cloudRecord(for draft: BusinessLocalDraft,
                            events: [SyncEvent]) -> SyncEvent? {
        events.first { row in
            row.tableName == "store_daily_record" && !row.isDeletion &&
            row.payload["date"]?.text == draft.date &&
            (row.payload["store_sync_id"]?.text == draft.storeID ||
             (!draft.storeName.isEmpty &&
              row.payload["store_name"]?.text == draft.storeName))
        }
    }

    static func status(draft: BusinessLocalDraft, events: [SyncEvent],
                       attempt: BusinessCommitAttempt?,
                       outboxReadable: Bool = true) -> CloudState {
        guard outboxReadable else { return .unsafeToRead }
        if let attempt {
            guard attempt.proposal.operationID.lowercased() == draft.id.lowercased(),
                  attempt.proposal.bookID.lowercased() == attempt.bookID.lowercased(),
                  attempt.account.lowercased() == attempt.proposal.account.lowercased(),
                  attempt.proposal.date == draft.date,
                  attempt.proposal.storeSyncID == draft.storeID else {
                return .unsafeToRead
            }
            switch attempt.phase {
            case .confirmed: return .confirmed
            case .accepted: return .acceptedPendingReadback
            case .prepared, .unknown: return .waitingForResult
            }
        }
        return cloudRecord(for: draft, events: events) == nil
            ? .localOnly : .cloudRecordExists
    }

    static func matchesSaved(draft: BusinessLocalDraft?,
                             date: String, storeID: String,
                             lines: [BusinessCollectorDraftLine],
                             startTime: String, endTime: String,
                             expense: String, opening: String, closing: String,
                             note: String) -> Bool {
        guard let draft else { return false }
        return draft.date == date && draft.storeID == storeID &&
               draft.collectors == lines &&
               draft.startTime == startTime.trimmingCharacters(in: .whitespaces) &&
               draft.endTime == endTime.trimmingCharacters(in: .whitespaces) &&
               (draft.expense ?? "") == expense &&
               (draft.openingStock ?? "") == opening &&
               (draft.closingStock ?? "") == closing &&
               draft.note == note
    }

    struct Preview: Equatable {
        let wechatCents: Int64
        let alipayCents: Int64
        let cashCents: Int64
        let revenueCents: Int64
        let profitCents: Int64

        var revenue: String { BusinessCommitPolicy.money(revenueCents) }
        var profit: String { BusinessCommitPolicy.money(profitCents) }
    }

    /// Exactly the same arithmetic as the existing atomic business proposal;
    /// never guesses empty stock values or recalculates Android settlements.
    static func preview(lines: [BusinessCollectorDraftLine],
                        expense: String, opening: String, closing: String) -> Preview? {
        guard !lines.isEmpty, !expense.isEmpty,
              !opening.isEmpty, !closing.isEmpty,
              (try? BusinessCollectorDraftPolicy.validate(lines,
                  fields: totalsFields(lines))) != nil,
              let receipts = try? BusinessCollectorDraftPolicy.totals(lines),
              let e = try? BusinessCollectorDraftPolicy.cents(expense, label: "支出"),
              let o = try? BusinessCollectorDraftPolicy.cents(opening, label: "开摊库存"),
              let c = try? BusinessCollectorDraftPolicy.cents(closing, label: "收摊库存")
        else { return nil }
        let revenue = receipts.wechat + receipts.alipay + receipts.cash
        guard revenue > 0 else { return nil }
        return Preview(wechatCents: receipts.wechat,
                       alipayCents: receipts.alipay,
                       cashCents: receipts.cash,
                       revenueCents: revenue, profitCents: revenue + c - o - e)
    }

    /// Incomplete local drafts must never become zero-cost cloud records.
    static func readyForCloud(lines: [BusinessCollectorDraftLine],
                              expense: String, opening: String, closing: String,
                              startTime: String, endTime: String) -> Bool {
        guard preview(lines: lines, expense: expense,
                      opening: opening, closing: closing) != nil else { return false }
        let fields = [
            "actual_start_time": startTime.trimmingCharacters(in: .whitespaces),
            "actual_end_time": endTime.trimmingCharacters(in: .whitespaces)
        ]
        return (try? BusinessCollectorDraftPolicy.validateTimes(fields)) != nil
    }

    private static func totalsFields(_ lines: [BusinessCollectorDraftLine]) -> [String: String] {
        guard let t = try? BusinessCollectorDraftPolicy.totals(lines) else { return [:] }
        return ["wechat": BusinessCommitPolicy.money(t.wechat),
                "alipay": BusinessCommitPolicy.money(t.alipay),
                "cash": BusinessCommitPolicy.money(t.cash)]
    }
}
