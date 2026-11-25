import Foundation

/// UI orchestration only. The server remains the sole authority that can
/// transition plan item + collaboration + order in a single transaction.
enum PurchasePlanCompletionPolicy {
    static func availableBuyers(for row: PurchasePlanningRow,
                                partners: [TestLookup]) -> [TestLookup] {
        let candidates = partners.filter {
            row.buyerName.isEmpty || $0.name == row.buyerName
        }
        return candidates.sorted { a, b in
            a.name == b.name ? a.id < b.id : a.name < b.name
        }
    }

    /// An existing local draft wins over new entry. One plan item must never
    /// be duplicated into multiple local drafts, even if buyer selection differs.
    static func existingDraft(for planItemSyncID: String,
                              drafts: [PurchaseWorkspaceDraft]) -> PurchaseWorkspaceDraft? {
        drafts.first { draft in
            draft.lines.contains {
                $0.sourcePlanItemSyncID == planItemSyncID
            }
        }
    }

    static func preparedItem(_ planItemSyncID: String,
                             buyerSyncID: String, date: String,
                             events: [SyncEvent]) -> PurchaseDraftLine? {
        PurchaseImportPolicy.pendingPlan(
            events, on: date, buyerSyncID: buyerSyncID
        ).first { $0.sourcePlanItemSyncID == planItemSyncID }?.asDraftLine()
    }

    /// Drafts containing plan links are only eligible to create a new purchase
    /// if EVERY chosen item references a distinct unfinished plan source.
    static func planSourceIDs(_ draft: PurchaseWorkspaceDraft) -> [String]? {
        let links = draft.lines.compactMap(\.sourcePlanItemSyncID)
        guard !links.isEmpty, links.count == draft.lines.count,
              Set(links).count == links.count else { return nil }
        return links
    }

    /// A fresh /sync/pull may contain another order by the same buyer. That
    /// order does not, by itself, mean the current plan item was completed.
    /// All linked items and collaborations must still be untouched and pending.
    static func pendingSources(
        _ draft: PurchaseWorkspaceDraft, events: [SyncEvent]
    ) -> Bool {
        guard let links = planSourceIDs(draft), !links.isEmpty else { return false }
        for sid in links {
            let items = events.filter {
                $0.tableName == "purchase_plan_item" && !$0.isDeletion &&
                $0.syncId == sid && $0.payload["status"]?.decimal == 0
            }
            let collabs = events.filter {
                $0.tableName == "purchase_collaboration" && !$0.isDeletion &&
                $0.payload["plan_item_sync_id"]?.text == sid &&
                ($0.payload["purchase_order_sync_id"]?.text ?? "").isEmpty
            }
            guard items.count == 1, collabs.count == 1,
                  items[0].rowVersion > 0, collabs[0].rowVersion > 0
            else { return false }
        }
        return true
    }
}
