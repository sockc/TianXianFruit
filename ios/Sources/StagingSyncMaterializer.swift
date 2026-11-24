import Foundation

/// Keep the newest revision of each cloud sync row, INCLUDING its tombstone.
/// Removing DELETE events too early allows stale upserts to look like active
/// buyer/product references and hides server deletions during reconciliation.
enum StagingSyncMaterializer {
    static func latest(_ events: [SyncEvent]) -> [SyncEvent] {
        var result: [String: SyncEvent] = [:]
        for event in events {
            let key = event.logicalKey
            if let existing = result[key], existing.rowVersion > event.rowVersion {
                continue
            }
            // On equal revisions, later pull event is authoritative, matching
            // ReadOnlySnapshot.apply's semantics.
            result[key] = event
        }
        return result.keys.sorted().compactMap { result[$0] }
    }
}
