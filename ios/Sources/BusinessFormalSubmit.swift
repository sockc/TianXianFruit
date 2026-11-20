import Foundation
import SwiftUI

/// Cloud submission is explicitly initiated, never an automatic sync.
@MainActor
enum BusinessFormalSubmit {
    static func preflight(_ model: LedgerViewModel,
                          draft: BusinessLocalDraft) async throws -> BusinessCommitProposal {
        guard let account = model.account, let book = model.selectedBook,
              !model.isUsingCachedAuthorization else { throw BusinessAtomicError.scope }
        await model.refreshBusinessCommitCapabilities()
        guard model.businessCommitCapabilities?.allowsWrite == true else {
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
