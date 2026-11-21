import Foundation
import CryptoKit
import SwiftUI

/// Server V1.0.31 is the only authority for settlement writes. Not /sync/push.
struct TXSettlementCommitCapabilities: Decodable {
    let version: Int
    let feature: String
    let enabled: Bool
    let atomic: Bool
    let idempotency: Bool
    let operationLookup: Bool
    let scope: String
    let actions: [String]
    enum CodingKeys: String, CodingKey {
        case version, feature, enabled, atomic, idempotency, scope, actions
        case operationLookup = "operation_lookup"
    }
    var allowsWrite: Bool {
        version == 1 && feature == "settlement_transfer_atomic_v1" &&
        enabled && atomic && idempotency && operationLookup && scope == "iOS_TEST_" &&
        Set(["PARTIAL", "FULL", "RESET"]).isSubset(of: Set(actions))
    }
}

enum TXSettlementAtomicError: LocalizedError {
    case scope, unavailable, stale, locked, invalidReceipt, storage, unknown
    var errorDescription: String? {
        switch self {
        case .scope: return "账号或测试账本已变化，禁止结算"
        case .unavailable: return "测试账本资金事务未启用或服务器版本不足"
        case .stale: return "云端原始版本已变化，请同步后重新核对"
        case .locked: return "存在未完成结算，须先查询原操作结果，不能换ID重发"
        case .invalidReceipt: return "服务器回执不符合原操作，已锁定等待核查"
        case .storage: return "本机不可变操作记录保存失败，已禁止网络写入"
        case .unknown: return "原操作未确认，禁止生成新ID再次提交"
        }
    }
}

struct TXSettlementCommitBody: Encodable {
    let protocolVersion = 1
    let bookID: String
    let account: String
    let operationID: String
    let deviceID: String
    let date: String
    let action: String
    let dailySyncID: String
    let dailyRowVersion: Int64
    let transferSyncID: String
    let transferRowVersion: Int64
    let expectedSettledAmount: String
    let targetSettledAmount: String
    enum CodingKeys: String, CodingKey {
        case protocolVersion = "protocol_version"
        case bookID = "book_id"
        case account, date, action
        case operationID = "operation_id"
        case deviceID = "device_id"
        case dailySyncID = "daily_sync_id"
        case dailyRowVersion = "daily_row_version"
        case transferSyncID = "transfer_sync_id"
        case transferRowVersion = "transfer_row_version"
        case expectedSettledAmount = "expected_settled_amount"
        case targetSettledAmount = "target_settled_amount"
    }
    init(_ prep: TXSettlementLocalPreparation, deviceID: String) throws {
        guard prep.action.canPreviewSavedTransfer,
              UUID(uuidString: prep.id) != nil, UUID(uuidString: prep.bookID) != nil
        else { throw TXSettlementAtomicError.scope }
        self.bookID = prep.bookID
        self.account = prep.account
        self.operationID = prep.id
        self.deviceID = deviceID
        self.date = prep.date
        switch prep.action {
        case .partial: self.action = "PARTIAL"
        case .full: self.action = "FULL"
        case .reset: self.action = "RESET"
        default: throw TXSettlementAtomicError.scope
        }
        self.dailySyncID = prep.dailySyncID
        self.dailyRowVersion = prep.dailyRowVersion
        self.transferSyncID = prep.transferSyncID
        self.transferRowVersion = prep.transferRowVersion
        self.expectedSettledAmount =
            TXSettlementPreparationPolicy.yuan(prep.expectedSettledCents)
        self.targetSettledAmount =
            TXSettlementPreparationPolicy.yuan(prep.targetSettledCents)
    }
}

struct TXSettlementCommitReceipt: Codable, Equatable {
    let status: String
    let operationID: String
    let bookID: String
    let date: String
    let dailySyncID: String
    let dailyRowVersion: Int64
    let transferSyncID: String
    let transferRowVersion: Int64
    let settledAmount: String
    let dailyStatus: Int
    let syncCursor: Int64
    let requestHash: String
    enum CodingKeys: String, CodingKey {
        case status, date
        case operationID = "operation_id"
        case bookID = "book_id"
        case dailySyncID = "daily_sync_id"
        case dailyRowVersion = "daily_row_version"
        case transferSyncID = "transfer_sync_id"
        case transferRowVersion = "transfer_row_version"
        case settledAmount = "settled_amount"
        case dailyStatus = "daily_status"
        case syncCursor = "sync_cursor"
        case requestHash = "request_hash"
    }
    func matches(_ prep: TXSettlementLocalPreparation) -> Bool {
        (status == "committed" || status == "already_committed") &&
        operationID.lowercased() == prep.id.lowercased() &&
        bookID.lowercased() == prep.bookID.lowercased() &&
        date == prep.date && dailySyncID == prep.dailySyncID &&
        transferSyncID == prep.transferSyncID &&
        dailyRowVersion == prep.dailyRowVersion + 1 &&
        transferRowVersion == prep.transferRowVersion + 1 &&
        TXSettlementPreparationPolicy.cents(settledAmount) == prep.targetSettledCents &&
        (0...2).contains(dailyStatus) && syncCursor > 0 && requestHash.count == 64
    }
}

struct TXSettlementCommitAttempt: Codable {
    enum Phase: String, Codable { case unknown, accepted, confirmed }
    let proposal: TXSettlementLocalPreparation
    var phase: Phase
    var receipt: TXSettlementCommitReceipt?
    var updatedAt: Date
    var label: String {
        switch phase {
        case .unknown: return "已锁定原操作：结果未知，只能查询"
        case .accepted: return "服务器已登记，等待同步核对"
        case .confirmed: return "服务器已提交，双记录同步核对成功"
        }
    }
}

/// One durable, account+book-scoped lock. No bearer token or password persisted.
struct TXSettlementCommitStore {
    let directory: URL
    init(directory: URL? = nil) throws {
        if let directory { self.directory = directory }
        else {
            self.directory = FileManager.default.urls(
                for: .applicationSupportDirectory, in: .userDomainMask)[0]
                .appendingPathComponent("SettlementCommitLocks", isDirectory: true)
        }
        do {
            try FileManager.default.createDirectory(at: self.directory,
                                                     withIntermediateDirectories: true)
            try? FileManager.default.setAttributes([
                .protectionKey: FileProtectionType.completeUntilFirstUserAuthentication
            ], ofItemAtPath: self.directory.path)
            var folder = self.directory
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            try? folder.setResourceValues(values)
        } catch { throw TXSettlementAtomicError.storage }
    }
    private func file(_ account: String, _ book: String) -> URL {
        let digest = SHA256.hash(data: Data(
            (account.lowercased() + "|" + book.lowercased()).utf8))
            .map { String(format: "%02x", $0) }.joined()
        return directory.appendingPathComponent(digest + ".json")
    }
    func load(account: String, book: String) throws -> TXSettlementCommitAttempt? {
        let url = file(account, book)
        guard FileManager.default.fileExists(atPath: url.path) else { return nil }
        do {
            let saved = try JSONDecoder().decode(TXSettlementCommitAttempt.self,
                                                   from: Data(contentsOf: url))
            guard saved.proposal.account.lowercased() == account.lowercased() &&
                  saved.proposal.bookID.lowercased() == book.lowercased()
            else { throw TXSettlementAtomicError.storage }
            return saved
        } catch { throw TXSettlementAtomicError.storage }
    }
    func save(_ attempt: TXSettlementCommitAttempt) throws {
        do {
            try JSONEncoder().encode(attempt).write(
                to: file(attempt.proposal.account, attempt.proposal.bookID),
                options: .atomic)
        } catch { throw TXSettlementAtomicError.storage }
    }
}

enum TXSettlementCommitReadback {
    static func original(_ prep: TXSettlementLocalPreparation,
                         in snap: ReadOnlySnapshot) -> Bool {
        guard let daily = snap.records["daily_cash_settlement|" + prep.dailySyncID],
              let row = snap.records["settlement_transfer|" + prep.transferSyncID],
              daily.rowVersion == prep.dailyRowVersion,
              row.rowVersion == prep.transferRowVersion,
              daily.payload["date"]?.text == prep.date,
              row.payload["date"]?.text == prep.date,
              row.payload["settlement_kind"]?.text == "DAILY",
              let cents = TXSettlementPreparationPolicy.persistedCents(
                row.payload["settled_amount"]?.decimal ?? -1)
        else { return false }
        return cents == prep.expectedSettledCents
    }
    static func confirmed(_ prep: TXSettlementLocalPreparation,
                          receipt: TXSettlementCommitReceipt,
                          snap: ReadOnlySnapshot, cursor: Int64) -> Bool {
        guard receipt.matches(prep), cursor >= receipt.syncCursor,
              let daily = snap.records["daily_cash_settlement|" + prep.dailySyncID],
              let row = snap.records["settlement_transfer|" + prep.transferSyncID],
              daily.rowVersion == receipt.dailyRowVersion,
              row.rowVersion == receipt.transferRowVersion,
              daily.payload["date"]?.text == prep.date,
              row.payload["date"]?.text == prep.date,
              row.payload["settlement_kind"]?.text == "DAILY",
              let cents = TXSettlementPreparationPolicy.persistedCents(
                row.payload["settled_amount"]?.decimal ?? -1),
              cents == prep.targetSettledCents,
              Int(daily.payload["status"]?.decimal ?? -1) == receipt.dailyStatus
        else { return false }
        return true
    }
}
