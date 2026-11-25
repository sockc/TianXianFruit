import Foundation

/// Business edit identity must come from a fresh authenticated /sync/pull.
/// The generic LedgerReadOnly SQLite cache can retain pre-Int64 decoding
/// snapshots from earlier iOS builds; it is never a write precondition.
enum TestBusinessEditSourcePolicy {
    static func editableRecords(in remoteEvents: [SyncEvent],
                                focus: String? = nil) -> [SyncEvent] {
        remoteEvents.filter {
            $0.tableName == "store_daily_record" && !$0.isDeletion &&
            (focus == nil || $0.syncId == focus)
        }.sorted {
            let a = $0.payload["date"]?.text ?? ""
            let b = $1.payload["date"]?.text ?? ""
            return a == b ? $0.syncId < $1.syncId : a > b
        }
    }

    /// The operation must be refused if any remote version, deletion or
    /// payload actually changed after this *live* row was opened.
    static func isUnchanged(_ source: SyncEvent, in remoteEvents: [SyncEvent]) -> Bool {
        let matched = remoteEvents.filter {
            $0.tableName == source.tableName && $0.syncId == source.syncId
        }
        guard matched.count == 1, let latest = matched.first,
              !latest.isDeletion else { return false }
        return latest.rowVersion == source.rowVersion &&
               latest.operation == source.operation &&
               latest.payload == source.payload
    }

    static func version(_ source: SyncEvent, in events: [SyncEvent]) -> String {
        guard let found = events.first(where: {
            $0.tableName == source.tableName && $0.syncId == source.syncId
        }), !found.isDeletion else { return "已删除" }
        return String(found.rowVersion)
    }
}
