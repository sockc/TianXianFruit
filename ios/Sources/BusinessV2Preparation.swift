import Foundation
import CryptoKit

/// V1.0.37 RC1 wire preparation ONLY. No network write, no v1 fallback.
/// The existing v1 proposal and outbox are not modified.
struct TXBusinessV2Morning: Equatable, Codable {
    let wechat: String
    let alipay: String
    let cash: String
    let wechatRecorded: Bool
    let alipayRecorded: Bool
    let cashRecorded: Bool

    enum CodingKeys: String, CodingKey {
        case wechat, alipay, cash
        case wechatRecorded = "wechat_recorded", alipayRecorded = "alipay_recorded", cashRecorded = "cash_recorded"
    }

    func values() throws -> [Int64] {
        let amounts = try [wechat, alipay, cash].map {
            try BusinessCollectorDraftPolicy.cents($0, label: "凌晨收款")
        }
        guard zip(amounts, [wechatRecorded, alipayRecorded, cashRecorded]).allSatisfy({
            $0.1 || $0.0 == 0
        }) else { throw BusinessAtomicError.invalidDraft }
        return amounts
    }

    var wire: [String: Any] {
        ["wechat": wechat, "alipay": alipay, "cash": cash,
         "wechat_recorded": wechatRecorded, "alipay_recorded": alipayRecorded,
         "cash_recorded": cashRecorded]
    }
}

struct TXBusinessV2Natural: Equatable, Codable {
    let partnerSyncID: String
    let wechat: String
    let alipay: String
    let cash: String
    let deductWechat: Bool
    let deductAlipay: Bool
    let deductCash: Bool
    let previousRevision: Int64
    let followingRevision: Int64
    let following: TXBusinessV2Morning?
    enum CodingKeys: String, CodingKey {
        case partnerSyncID = "partner_sync_id", wechat, alipay, cash
        case deductWechat = "deduct_wechat", deductAlipay = "deduct_alipay", deductCash = "deduct_cash"
        case previousRevision = "previous_revision", followingRevision = "following_revision", following
    }

    var wire: [String: Any] {
        var value: [String: Any] = [
            "partner_sync_id": partnerSyncID, "wechat": wechat, "alipay": alipay, "cash": cash,
            "deduct_wechat": deductWechat, "deduct_alipay": deductAlipay, "deduct_cash": deductCash,
            "previous_revision": previousRevision, "following_revision": followingRevision
        ]
        if let following { value["following"] = following.wire }
        else { value["following"] = NSNull() }
        return value
    }
}

enum TXBusinessV2Preparation {
    typealias Morning = TXBusinessOvernightReferencePolicy.Morning

    static func adjusted(_ draft: TXBusinessV2Natural, previous: Morning,
                         following: Morning) throws -> [Int64] {
        guard draft.previousRevision >= 0, draft.followingRevision >= 0,
              UUID(uuidString: draft.partnerSyncID) != nil else {
            throw BusinessAtomicError.invalidDraft
        }
        let raw = try [draft.wechat, draft.alipay, draft.cash].map {
            try BusinessCollectorDraftPolicy.cents($0, label: "自然日收款")
        }
        let prev = try values(previous)
        // Never permit replacement of an invalid existing morning.
        let priorNext = try values(following)
        let next = try draft.following?.values() ?? priorNext
        let flags = [draft.deductWechat, draft.deductAlipay, draft.deductCash]
        let result = (0..<3).map { raw[$0] - (flags[$0] ? prev[$0] : 0) + next[$0] }
        guard result.allSatisfy({ $0 >= 0 && $0 <= 100_000_000 }) else {
            throw BusinessAtomicError.invalidDraft
        }
        return result
    }

    private static func values(_ morning: Morning) throws -> [Int64] {
        switch morning {
        case .missing: return [0, 0, 0]
        case .invalid: throw BusinessAtomicError.cloudConflict
        case .recorded(let amounts):
            return [amounts.wechat ?? 0, amounts.alipay ?? 0, amounts.cash ?? 0]
        }
    }

    /// Serialize explicit nulls like Pydantic model_dump(mode="json").
    /// This is not a submit method. Caller must eventually bind a v2-specific
    /// immutable outbox, server capabilities and both-stream readback.
    static func canonical(_ proposal: BusinessCommitProposal, payer: String?,
                          natural: [TXBusinessV2Natural]) throws -> Data {
        guard let book = UUID(uuidString: proposal.bookID),
              let operation = UUID(uuidString: proposal.operationID),
              UUID(uuidString: proposal.storeSyncID) != nil,
              !natural.isEmpty, natural.count == proposal.collectors.count,
              Set(natural.map(\.partnerSyncID)).count == natural.count,
              Set(natural.map(\.partnerSyncID)) == Set(proposal.collectors.map(\.partnerSyncID)),
              natural.allSatisfy({ UUID(uuidString: $0.partnerSyncID) != nil &&
                  $0.previousRevision >= 0 && $0.followingRevision >= 0 }),
              (payer == nil || UUID(uuidString: payer ?? "") != nil) else {
            throw BusinessAtomicError.invalidDraft
        }
        if try BusinessCollectorDraftPolicy.cents(proposal.expense, label: "费用") > 0,
           payer == nil { throw BusinessAtomicError.invalidDraft }
        guard var body = try JSONSerialization.jsonObject(with: JSONEncoder().encode(proposal))
                as? [String: Any] else { throw BusinessAtomicError.invalidDraft }
        body.removeValue(forKey: "payload_hash")
        body["protocol_version"] = 2
        body["book_id"] = book.uuidString.lowercased()
        body["operation_id"] = operation.uuidString.lowercased()
        if let payer { body["expense_payer_sync_id"] = payer }
        else { body["expense_payer_sync_id"] = NSNull() }
        body["natural"] = natural.map(\.wire)
        return try JSONSerialization.data(withJSONObject: body,
                                          options: [.sortedKeys, .withoutEscapingSlashes])
    }

    static func hash(_ canonical: Data) -> String {
        SHA256.hash(data: canonical).map { String(format: "%02x", $0) }.joined()
    }
}
