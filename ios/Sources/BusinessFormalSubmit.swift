import Foundation
import SwiftUI

/// Cloud submission is explicitly initiated, never an automatic sync.
@MainActor
enum BusinessFormalSubmit {
    static func preflight(_ model: LedgerViewModel,
                          draft: BusinessLocalDraft) async throws -> BusinessCommitProposal {
        guard let account = model.account, let book = model.selectedBook,
              !model.isUsingCachedAuthorization else { throw BusinessAtomicError.scope }
        try TXBusinessV2Outbox.requireV1Free(book: book, account: account.username, date: draft.date, storeID: draft.storeID)
        await model.refreshBusinessCommitCapabilities()
        guard model.businessCommitCapabilities?.allows(book: book) == true else {
            throw BusinessAtomicError.unavailable
        }
        try await freshPull(model)
        return try BusinessCommitPolicy.prepare(draft, book: book,
            account: account, records: Array(model.snapshot.records.values))
    }

    private static func freshPull(_ model: LedgerViewModel) async throws {
        let old = model.lastSyncAt
        await model.refresh(forceFull: true)
        guard model.errorMessage == nil, !model.isUsingCachedAuthorization,
              let updated = model.lastSyncAt, old == nil || updated > old!
        else { throw BusinessAtomicError.cloudConflict }
    }

    static func send(_ model: LedgerViewModel,
                     draft: BusinessLocalDraft) async throws -> BusinessCommitAttempt {
        // Two complete reads, including one just before reservation. The backend
        // still MUST enforce its own unique key and serializable/CAS lock.
        let first = try await preflight(model, draft: draft)
        guard let book = model.selectedBook, let account = model.account else {
            throw BusinessAtomicError.scope
        }
        try await freshPull(model)
        let final = try BusinessCommitPolicy.prepare(
            draft, book: book, account: account,
            records: Array(model.snapshot.records.values))
        guard first == final else { throw BusinessAtomicError.cloudConflict }
        let outbox = try BusinessCommitOutbox()
        if try outbox.load(id: draft.id, account: account.username, bookID: book.id) != nil {
            throw BusinessAtomicError.locked
        }
        var attempt = try outbox.reserve(final)
        attempt.phase = .unknown
        attempt.updatedAt = Date()
        // Persist the "may have sent" marker BEFORE network operation.
        try outbox.update(attempt)
        do {
            let receipt = try await model.commitBusiness(final)
            attempt.phase = .accepted
            attempt.receipt = receipt
            attempt.updatedAt = Date()
            try outbox.update(attempt)
            return try await verify(model, attempt: attempt)
        } catch {
            // Unknown result stays locked. No second POST from this client.
            throw error
        }
    }

    static func recover(_ model: LedgerViewModel,
                        attempt: BusinessCommitAttempt) async throws -> BusinessCommitAttempt {
        if attempt.phase == .confirmed { return attempt }
        if attempt.phase == .accepted { return try await verify(model, attempt: attempt) }
        guard let receipt = try await model.findBusinessCommit(attempt.proposal)
        else { throw BusinessAtomicError.missingResult }
        var saved = attempt
        saved.receipt = receipt
        saved.phase = .accepted
        saved.updatedAt = Date()
        try BusinessCommitOutbox().update(saved)
        return try await verify(model, attempt: saved)
    }

    /// A 404 for the original operation is NOT permission to allocate a new
    /// operation ID. On V1.0.27 the exact original ID + payload hash is
    /// idempotent under a book-row lock. Only an explicit user action may
    /// resume that same immutable operation within the live authorized book.
    static func resumeOriginal(_ model: LedgerViewModel,
                               attempt: BusinessCommitAttempt) async throws -> BusinessCommitAttempt {
        guard attempt.phase == .unknown || attempt.phase == .prepared,
              let account = model.account, let book = model.selectedBook,
              book.id.lowercased() == attempt.bookID.lowercased(),
              account.username.lowercased() == attempt.account.lowercased(),
              model.businessCloudBookAllowed, !model.isUsingCachedAuthorization
        else { throw BusinessAtomicError.scope }
        let outbox = try BusinessCommitOutbox()
        guard let persisted = try outbox.load(id: attempt.proposal.operationID,
            account: attempt.account, bookID: attempt.bookID),
              persisted.proposal == attempt.proposal,
              persisted.phase == .unknown || persisted.phase == .prepared else {
            throw BusinessAtomicError.locked
        }
        // Query first: a committed operation must be verified, never resent.
        if let receipt = try await model.findBusinessCommit(persisted.proposal) {
            var accepted = persisted
            accepted.receipt = receipt
            accepted.phase = .accepted
            accepted.updatedAt = Date()
            try outbox.update(accepted)
            return try await verify(model, attempt: accepted)
        }
        // A remote daily row without an operation receipt is ambiguous.
        // Never attempt to manufacture a matching record or resend in that case.
        try await freshPull(model)
        let p = persisted.proposal
        guard !model.snapshot.records.values.contains(where: { row in
            row.tableName == "store_daily_record" && !row.isDeletion &&
            row.payload["date"]?.text == p.date &&
            (row.payload["store_sync_id"]?.text == p.storeSyncID ||
             row.payload["store_name"]?.text == p.storeName)
        }) else { throw BusinessAtomicError.cloudConflict }
        var locked = persisted
        if locked.phase == .prepared {
            locked.phase = .unknown
            locked.updatedAt = Date()
            try outbox.update(locked)
        }
        // This rechecks a *live* V1.0.27 capability and account/device scope.
        // The server deduplicates this same operation ID and canonical hash.
        // Failure/timeout retains the same outbox; never generates a new ID.
        let receipt = try await model.commitBusiness(p)
        locked.receipt = receipt
        locked.phase = .accepted
        locked.updatedAt = Date()
        try outbox.update(locked)
        return try await verify(model, attempt: locked)
    }

    private static func verify(_ model: LedgerViewModel,
                               attempt: BusinessCommitAttempt) async throws -> BusinessCommitAttempt {
        guard let receipt = attempt.receipt else { throw BusinessAtomicError.badReceipt }
        try await freshPull(model)
        guard BusinessCommitPolicy.readback(attempt.proposal, receipt: receipt,
             records: Array(model.snapshot.records.values)) else {
            throw BusinessAtomicError.readback
        }
        var confirmed = attempt
        confirmed.phase = .confirmed
        confirmed.updatedAt = Date()
        try BusinessCommitOutbox().update(confirmed)
        return confirmed
    }
}
