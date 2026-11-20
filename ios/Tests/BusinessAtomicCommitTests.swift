import XCTest
@testable import TianXianFruit

final class BusinessAtomicCommitTests: XCTestCase {
    private func draft() -> BusinessLocalDraft {
        BusinessLocalDraft(
            id: UUID().uuidString, date: "2026-11-21",
            storeID: UUID().uuidString, storeName: "营业位置A",
            collectors: [
                BusinessCollectorDraftLine(partnerSyncID: UUID().uuidString,
                                           wechat: "35.25", alipay: "20", cash: "5")
            ],
            startTime: "16:00", endTime: "00:10",
            note: "本机备注不上传", updatedAt: Date())
    }

    private func cloud(_ draft: BusinessLocalDraft) -> [SyncEvent] {
        [
            SyncEvent(tableName: "store", syncId: draft.storeID,
                rowVersion: 1, operation: "UPSERT",
                payload: ["name": .string(draft.storeName)]),
            SyncEvent(tableName: "partner",
                syncId: draft.collectors[0].partnerSyncID,
                rowVersion: 1, operation: "UPSERT",
                payload: ["name": .string("收款人A")])
        ]
    }

    func testCapabilityRequiresAllExplicitAtomicFlags() {
        let disabled = BusinessCommitCapabilities(
            version: 1, enabled: false, atomic: true, idempotency: true,
            feature: "business_atomic_commit_v1", supportsCollectorSplits: true)
        XCTAssertFalse(disabled.allowsWrite)
        let unexpected = BusinessCommitCapabilities(
            version: 1, enabled: true, atomic: true, idempotency: true,
            feature: "generic_sync_push", supportsCollectorSplits: true)
        XCTAssertFalse(unexpected.allowsWrite)
    }

    func testProposalRejectsCloudDuplicateAtSameStoreDate() throws {
        let row = draft()
        let book = CloudBook(id: UUID().uuidString, name: "测试账本", role: "OWNER",
                             deleted: nil, purged: nil)
        let user = Account(username: "unituser", displayName: "Unit", systemRole: nil)
        let proposal = try BusinessCommitPolicy.prepare(
            row, book: book, account: user, records: cloud(row))
        XCTAssertEqual(proposal.totalRevenue, "60.25")
        XCTAssertEqual(proposal.collectors.count, 1)
        XCTAssertEqual(proposal.actualEndTime, "00:10")
        XCTAssertEqual(proposal.payloadHash.count, 64)
        var rows = cloud(row)
        rows.append(SyncEvent(tableName: "store_daily_record",
            syncId: UUID().uuidString, rowVersion: 1, operation: "UPSERT",
            payload: ["date": .string(row.date),
                      "store_name": .string(row.storeName)]))
        XCTAssertThrowsError(try BusinessCommitPolicy.prepare(
            row, book: book, account: user, records: rows))
    }

    func testOutboxNeverOverwritesOneOperationWithAnotherPayload() throws {
        let temp = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString + ".sqlite")
        defer { try? FileManager.default.removeItem(at: temp) }
        let store = try BusinessCommitOutbox(url: temp)
        let row = draft()
        let book = CloudBook(id: UUID().uuidString, name: "测试账本", role: "OWNER",
                             deleted: nil, purged: nil)
        let user = Account(username: "unituser", displayName: "Unit", systemRole: nil)
        let proposal = try BusinessCommitPolicy.prepare(
            row, book: book, account: user, records: cloud(row))
        let original = try store.reserve(proposal)
        XCTAssertEqual(original.phase, .prepared)
        XCTAssertEqual(try store.reserve(proposal).proposal, proposal)
        var replaced = proposal
        replaced = BusinessCommitProposal(
            protocolVersion: replaced.protocolVersion,
            bookID: replaced.bookID, account: replaced.account,
            operationID: replaced.operationID, date: replaced.date,
            storeSyncID: replaced.storeSyncID, storeName: replaced.storeName,
            collectors: replaced.collectors, actualStartTime: replaced.actualStartTime,
            actualEndTime: replaced.actualEndTime, wechat: replaced.wechat,
            alipay: replaced.alipay, cash: replaced.cash,
            totalRevenue: replaced.totalRevenue, payloadHash: "different")
        XCTAssertThrowsError(try store.reserve(replaced))
        var unknown = original
        unknown.phase = .unknown
        unknown.updatedAt = Date().addingTimeInterval(1)
        try store.update(unknown)
        XCTAssertEqual(try store.load(id: row.id,
            account: user.username, bookID: book.id)?.phase, .unknown)
    }
}
