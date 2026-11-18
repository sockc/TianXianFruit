import Foundation
import CryptoKit

/// Values the tester independently reads in Android. No fields are auto-filled
/// from iOS cloud records; user must inspect the same test book in Android.
struct AndroidPurchaseObservation: Codable, Equatable {
    var date: String
    var buyerName: String
    var fruitName: String
    var unit: String
    var quantity: String
    var totalCost: String
}

struct AndroidPurchaseReviewEvidence: Codable, Equatable {
    let checkedAt: Date
    let observation: AndroidPurchaseObservation
    let cloudFingerprint: String
}

enum AndroidPurchaseReviewError: LocalizedError {
    case cloudNotVerified
    case staleCloudAudit
    case mismatchedField(String)
    case missingField(String)
    case alreadyConfirmed

    var errorDescription: String? {
        switch self {
        case .cloudNotVerified:
            return "云端订单、商品明细尚未完整核对，不能确认 Android 数据"
        case .staleCloudAudit:
            return "云端核对结果已过期，请先点击“刷新云端对账”"
        case .mismatchedField(let field):
            return "Android 实际数据与云端不一致：" + field
        case .missingField(let field):
            return "请在 Android 查看后手动填写：" + field
        case .alreadyConfirmed:
            return "该笔 Android 验收已保存；不能覆盖既有核对证据"
        }
    }
}

enum AndroidPurchaseReviewPolicy {
    private struct CloudEvidence: Encodable {
        let bookID: String
        let sourceAccount: String
        let stageUsername: String
        let stageHost: String
        let orderSyncID: String
        let itemSyncID: String
        let serverOrderID: Int64
        let serverItemID: Int64
        let serverOrderVersion: Int64
        let serverItemVersion: Int64
        let orderPayload: [String: JSONValue]
        let itemPayload: [String: JSONValue]
    }

    /// Hash is a local change detector, NOT a cryptographic server signature
    /// or independent evidence that Android's database has synchronized.
    static func cloudFingerprint(job: StagingPurchaseJob,
                                 audit: PurchaseReadbackAudit) -> String? {
        guard audit.complete, audit.jobID == job.id,
              audit.bookID == job.bookID,
              let orderID = audit.serverOrderID, orderID > 0,
              let itemID = audit.serverItemID, itemID > 0,
              let orderVersion = audit.serverOrderVersion, orderVersion > 0,
              let itemVersion = audit.serverItemVersion, itemVersion > 0 else {
            return nil
        }
        let source = CloudEvidence(
            bookID: job.bookID,
            sourceAccount: job.sourceAccount.lowercased(),
            stageUsername: job.stageUsername.lowercased(),
            stageHost: job.stageHost,
            orderSyncID: job.orderSyncID,
            itemSyncID: job.itemSyncID,
            serverOrderID: orderID, serverItemID: itemID,
            serverOrderVersion: orderVersion,
            serverItemVersion: itemVersion,
            orderPayload: job.orderPayload, itemPayload: job.itemPayload)
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        guard let bytes = try? encoder.encode(source) else { return nil }
        return SHA256.hash(data: bytes).map {
            String(format: "%02x", $0)
        }.joined()
    }

    private static func tidy(_ value: String) -> String {
        value.precomposedStringWithCanonicalMapping
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func sameText(_ observed: String, expected: String?,
                                 label: String) throws {
        let input = tidy(observed)
        guard !input.isEmpty else { throw AndroidPurchaseReviewError.missingField(label) }
        guard let expected, input == tidy(expected) else {
            throw AndroidPurchaseReviewError.mismatchedField(label)
        }
    }

    static func confirm(job: StagingPurchaseJob, audit: PurchaseReadbackAudit,
                        observation: AndroidPurchaseObservation,
                        now: Date = Date()) throws -> AndroidPurchaseReviewEvidence {
        guard job.state == .complete,
              let fingerprint = cloudFingerprint(job: job, audit: audit) else {
            throw AndroidPurchaseReviewError.cloudNotVerified
        }
        guard now.timeIntervalSince(audit.checkedAt) >= -5,
              now.timeIntervalSince(audit.checkedAt) <= 2700 else {
            throw AndroidPurchaseReviewError.staleCloudAudit
        }
        try sameText(observation.date, expected: job.orderPayload["date"]?.text,
                     label: "采购日期")
        try sameText(observation.buyerName,
                     expected: job.orderPayload["buyer_name"]?.text, label: "采购人")
        try sameText(observation.fruitName,
                     expected: job.itemPayload["fruit_name"]?.text, label: "商品")
        try sameText(observation.unit,
                     expected: job.itemPayload["unit"]?.text, label: "单位")
        let rawCount = tidy(observation.quantity)
        guard !rawCount.isEmpty else {
            throw AndroidPurchaseReviewError.missingField("数量")
        }
        let quantity = try TestWritePolicy.count(rawCount, label: "数量")
        guard let savedQuantity = job.itemPayload["quantity"]?.numberValue,
              savedQuantity.isFinite,
              abs(quantity - savedQuantity) < 0.000001 else {
            throw AndroidPurchaseReviewError.mismatchedField("数量")
        }
        let rawCost = tidy(observation.totalCost)
        guard !rawCost.isEmpty else {
            throw AndroidPurchaseReviewError.missingField("总金额")
        }
        let cost = try TestWritePolicy.money(rawCost, label: "总金额")
        guard let storedCost = job.orderPayload["total_cost"]?.numberValue,
              storedCost.isFinite,
              abs(cost - storedCost) < 0.005 else {
            throw AndroidPurchaseReviewError.mismatchedField("总金额")
        }
        return AndroidPurchaseReviewEvidence(
            checkedAt: now, observation: observation,
            cloudFingerprint: fingerprint)
    }

    static func isCurrent(_ evidence: AndroidPurchaseReviewEvidence?,
                          job: StagingPurchaseJob,
                          audit: PurchaseReadbackAudit) -> Bool {
        guard let evidence,
              let fingerprint = cloudFingerprint(job: job, audit: audit) else {
            return false
        }
        return evidence.cloudFingerprint == fingerprint
    }
}
