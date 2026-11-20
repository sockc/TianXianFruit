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
            note: "本机备注不上传", expense: "1.00",
            openingStock: "10.00", closingStock: "15.00", updatedAt: Date())
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
        XCTAssertEqual(proposal.profit, "64.25")
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
            totalRevenue: replaced.totalRevenue, expense: replaced.expense,
            openingStockValue: replaced.openingStockValue,
            stockLeftValue: replaced.stockLeftValue,
            profit: replaced.profit, payloadHash: "different")
        XCTAssertThrowsError(try store.reserve(replaced))
        var unknown = original
        unknown.phase = .unknown
        unknown.updatedAt = Date().addingTimeInterval(1)
        try store.update(unknown)
        XCTAssertEqual(try store.load(id: row.id,
            account: user.username, bookID: book.id)?.phase, .unknown)
    }
    func testAndroidSingleRowSplitReadbackIsVerified() throws {
        let row = draft()
        let book = CloudBook(id: UUID().uuidString, name: "测试账本",
                             role: "OWNER", deleted: nil, purged: nil)
        let user = Account(username: "unituser", displayName: "Unit", systemRole: nil)
        let partnerPK = JSONValue.number(42)
        var rows = cloud(row)
        rows[1] = SyncEvent(tableName: "partner",
            syncId: row.collectors[0].partnerSyncID,
            rowVersion: 1, operation: "UPSERT",
            payload: ["name": .string("收款人A"), "id": partnerPK])
        let proposal = try BusinessCommitPolicy.prepare(
            row, book: book, account: user, records: rows)
        let split = """
        [{"partner_id":42,"partner_name":"收款人A","amount":60.25,
          "wechat_income":35.25,"alipay_income":20.0,"cash_income":5.0}]
        """
        let recordID = UUID().uuidString
        rows.append(SyncEvent(tableName: "store_daily_record",
            syncId: recordID, rowVersion: 1, operation: "UPSERT",
            payload: [
                "date": .string(row.date), "store_name": .string(row.storeName),
                "store_sync_id": .string(row.storeID),
                "revenue": .number(60.25), "wechat_income": .number(35.25),
                "alipay_income": .number(20.0), "cash_income": .number(5.0),
                "expense": .number(1.0),
                "opening_stock_value": .number(10.0),
                "stock_left_value": .number(15.0), "profit": .number(64.25),
                "actual_start_time": .string("16:00"),
                "actual_end_time": .string("00:10"),
                "receipt_splits_json": .string(split)
            ]))
        let receipt = BusinessCommitReceipt(status: "committed",
            operationID: proposal.operationID, bookID: proposal.bookID,
            recordSyncID: recordID,
            collectorSyncIDs: proposal.collectors.map(\.partnerSyncID),
            payloadHash: proposal.payloadHash, syncCursor: 1)
        XCTAssertTrue(BusinessCommitPolicy.readback(proposal, receipt: receipt,
                                                    records: rows))
        rows.removeLast()
        XCTAssertFalse(BusinessCommitPolicy.readback(proposal, receipt: receipt,
                                                     records: rows))
    }

}
