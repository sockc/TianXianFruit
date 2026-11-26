import Foundation

struct TXBillBatchPreview {
    let files: [TXBillCSVResult]
    let rows: [TXBillPreviewRow]
    let duplicateFiles: Int
    let duplicateRows: Int
    let sourceHashes: [String: Set<String>]

    init(_ input: [TXBillCSVResult]) throws {
        guard input.count <= 12 else { throw TXBillCSVError.tooLarge }
        var files: [TXBillCSVResult] = [], seen = Set<String>(), rows: [TXBillPreviewRow] = []
        var byRef: [String: TXBillPreviewRow] = [:], hashes: [String: Set<String>] = [:]
        var fileDuplicates = 0, rowDuplicates = 0
        for file in input {
            if !seen.insert(file.fileHash).inserted { fileDuplicates += 1; continue }
            files.append(file)
            for row in file.rows {
                let key = row.platform.rawValue + "|" + row.reference
                hashes[row.id, default: []].insert(file.fileHash)
                if let old = byRef[key] {
                    guard old == row else { throw TXBillCSVError.conflict }
                    rowDuplicates += 1
                } else {
                    guard rows.count < 100000 else { throw TXBillCSVError.tooLarge }
                    rows.append(row); byRef[key] = row
                }
            }
        }
        self.files = files; self.rows = rows; duplicateFiles = fileDuplicates
        duplicateRows = rowDuplicates; sourceHashes = hashes
    }
}

enum TXBillCloudMatch: String, CaseIterable {
    case new, sameCollector, unknownCollector, otherCollector, conflict, deleted
    var label: String {
        switch self {
        case .new: return "云端未见"
        case .sameCollector: return "同收款人已同步"
        case .unknownCollector: return "归属待核对"
        case .otherCollector: return "其他收款人"
        case .conflict: return "交易内容冲突"
        case .deleted: return "已删除交易"
        }
    }
}

/// Local snapshot evidence only: never interpret no match as permission to
/// write. Preserve every file link; exact receipt identity plus attribution.
struct TXBillCloudAudit {
    private let receipts: [String: CustomerChangeEvent]
    private let referenceKeys: [String: Set<String>]
    private let sourceLinks: [String: Set<String>]
    private let collectors: [String: Set<String>]
    private let conflictedCollectors: Set<String>

    init(events: [CustomerChangeEvent]) {
        var receipts: [String: CustomerChangeEvent] = [:]
        var refs: [String: Set<String>] = [:], links: [String: Set<String>] = [:], owners: [String: Set<String>] = [:]
        var conflicts = Set<String>()
        for e in events {
            let p = e.payload
            let platform = p["platform"]?.text ?? ""
            guard ["WECHAT","ALIPAY"].contains(platform) else { continue }
            if e.kind == "RECEIPT", let key = p["dedupe_key"]?.text, e.key == platform+"|"+key {
                receipts[e.key] = e
                if let ref = p["transaction_ref"]?.text, !ref.isEmpty { refs[platform+"|"+ref, default: []].insert(e.key) }
                if let file = p["source_file_hash"]?.text, !file.isEmpty { links[e.key,default: []].insert(platform+"|"+file) }
            } else if e.kind == "SOURCE_LINK", !e.isDeleted,
                      let key = p["dedupe_key"]?.text, let file = p["file_hash"]?.text,
                      !file.isEmpty, e.key == platform+"|"+key+"|"+file {
                links[platform+"|"+key,default: []].insert(platform+"|"+file)
            } else if e.kind == "COLLECTOR", let file = p["file_hash"]?.text, e.key == platform+"|"+file {
                if e.isDeleted { conflicts.insert(e.key) }
                else if let owner = p["partner_sync_id"]?.text, !owner.isEmpty { owners[e.key,default: []].insert(owner) }
                else { conflicts.insert(e.key) }
            }
        }
        self.receipts = receipts; referenceKeys = refs; sourceLinks = links; collectors = owners; conflictedCollectors = conflicts
    }
    private func moneyMatches(_ value: JSONValue?, _ cents: Int64) -> Bool {
        guard let n = value?.decimal, n.isFinite else { return false }
        return abs(n*100-Double(cents)) < 0.00001
    }
    /// Only a known same-collector cloud row can establish missing coverage.
    /// Unknown attribution does not establish zero or completeness.
    func hasOmittedKnownReceipts(date: String, platform: TXBillPlatform, partner: String,
                                 included: Set<String>) -> Bool {
        receipts.values.contains { receipt in
            let p = receipt.payload
            guard !receipt.isDeleted, p["platform"]?.text == platform.rawValue,
                  p["trade_time"]?.text.hasPrefix(date + " ") == true,
                  !included.contains(receipt.key) else { return false }
            return (sourceLinks[receipt.key] ?? []).contains { source in
                collectors[source]?.contains(partner) == true
            }
        }
    }

    func match(_ row: TXBillPreviewRow, selectedPartner: String, fileHashes: Set<String>) -> TXBillCloudMatch {
        let prefix = row.platform.rawValue+"|"
        if let keys = referenceKeys[prefix+row.reference], keys.contains(where: { $0 != row.id }) { return .conflict }
        let existing = receipts[row.id]
        if existing?.isDeleted == true { return .deleted }
        if let existing {
            let p = existing.payload
            guard p["transaction_ref"]?.text == row.reference, p["trade_time"]?.text == row.tradeTime,
                  moneyMatches(p["amount"],row.cents), moneyMatches(p["refund_amount"],row.refundCents),
                  moneyMatches(p["net_amount"],row.net) else { return .conflict }
        }
        let sources = (sourceLinks[row.id] ?? []).union(fileHashes.map { prefix+$0 })
        var found = Set<String>()
        var unknown = sources.isEmpty
        for source in sources {
            if conflictedCollectors.contains(source) { unknown = true }
            if let ids = collectors[source], !ids.isEmpty { found.formUnion(ids) }
            else if (sourceLinks[row.id] ?? []).contains(source) { unknown = true }
        }
        if found.contains(where: { $0 != selectedPartner }) { return .otherCollector }
        if existing == nil { return unknown ? .unknownCollector : .new }
        if found == [selectedPartner] && !unknown { return .sameCollector }
        return .unknownCollector
    }
}
