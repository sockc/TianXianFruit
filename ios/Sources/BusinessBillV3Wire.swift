import Foundation

/// V1.0.39 RC1 exact wire shape. Preparation and validation only; no POST.
struct TXBillV3Input: Codable, Equatable {
    struct File: Codable, Equatable {
        let platform: TXBillPlatform
        let file_hash: String
        let file_name: String
        let observed_date: String
        let accepted_rows: Int
        let ignored_rows: Int
        let duplicate_rows: Int
    }
    struct Receipt: Codable, Equatable {
        let platform: TXBillPlatform
        let trade_time: String
        let transaction_ref: String
        let dedupe_key: String
        let customer_key: String
        let identity_confidence: String
        let customer_name: String
        let transaction_type: String
        let merchant_product: String
        let trade_status: String
        let amount: String
        let refund_amount: String
        let file_hashes: [String]
        var key: String { platform.rawValue + "|" + dedupe_key }
    }
    let partner_sync_id: String
    let expected_customer_cursor: Int64
    let coverage_confirmed: Bool
    let channels: [TXBillPlatform]
    let files: [File]
    let receipts: [Receipt]

    private static func hex(_ value: String) -> Bool {
        value.count == 64 && value.utf8.allSatisfy { (48...57).contains($0) || (97...102).contains($0) }
    }
    private static func kotlin(_ cents: Int64) -> String {
        let money = BusinessCommitPolicy.money(cents)
        return money.hasSuffix("0") ? String(money.dropLast()) : money
    }
    private static func amount(_ text: String) throws -> Int64 {
        let value = try BusinessCollectorDraftPolicy.cents(text, label: "账单交易")
        guard text == BusinessCommitPolicy.money(value) else { throw TXBillPlanError.conflict }
        return value
    }
    static func build(batch: TXBillBatchPreview, date: String, partner: String,
                      channels: [TXBillPlatform], cursor: Int64) throws -> Self {
        let source = batch.files.filter { channels.contains($0.platform) }
        // Whole-file lineage: do not register the hash of a partially
        // imported multi-day file. Such files stay preview-only in RC1.
        guard !source.isEmpty, source.allSatisfy({ !$0.rows.isEmpty && $0.review == 0 &&
            $0.rows.allSatisfy { $0.date == date } }) else { throw TXBillPlanError.incomplete }
        let files = source.map { File(platform: $0.platform, file_hash: $0.fileHash,
            file_name: $0.fileName, observed_date: date, accepted_rows: $0.rows.count,
            ignored_rows: $0.ignored, duplicate_rows: $0.duplicates) }
            .sorted { ($0.platform.rawValue + $0.file_hash) < ($1.platform.rawValue + $1.file_hash) }
        let rows = batch.rows.filter { channels.contains($0.platform) && $0.date == date }
        guard (1...1000).contains(rows.count) else { throw TXBillCSVError.tooLarge }
        let receipts = rows.map { r in Receipt(platform: r.platform, trade_time: r.tradeTime,
            transaction_ref: r.reference, dedupe_key: r.dedupeKey, customer_key: r.customerKey,
            identity_confidence: r.reliableIdentity ? "HIGH" : "LOW", customer_name: r.customerName,
            transaction_type: r.transactionType, merchant_product: r.merchantProduct, trade_status: r.tradeStatus,
            amount: BusinessCommitPolicy.money(r.cents), refund_amount: BusinessCommitPolicy.money(r.refundCents),
            file_hashes: (batch.sourceHashes[r.id] ?? []).sorted()) }.sorted { $0.key < $1.key }
        return Self(partner_sync_id: partner, expected_customer_cursor: cursor, coverage_confirmed: true,
                    channels: channels, files: files, receipts: receipts)
    }

    func validate(date: String, partner: String, channels expected: [TXBillPlatform], cursor: Int64,
                  receiptKeys: [String], fileHashes: [String], target: TXBusinessV2CollectorInput) throws {
        guard partner_sync_id == partner, expected_customer_cursor == cursor, cursor >= 0, coverage_confirmed,
              channels == expected, Set(channels).count == channels.count, (1...2).contains(channels.count),
              (1...12).contains(files.count), (1...1000).contains(receipts.count),
              receipts.map(\.key).sorted() == receiptKeys, Set(receiptKeys).count == receipts.count,
              Set(files.map(\.file_hash)) == Set(fileHashes), target.partner == partner else { throw TXBillPlanError.conflict }
        var byFile: [String: File] = [:], counts: [String: Int] = [:], refs = Set<String>()
        var net: [TXBillPlatform: Int64] = [:], paid = Set<TXBillPlatform>()
        for file in files {
            let key = file.platform.rawValue + "|" + file.file_hash
            guard byFile[key] == nil, Self.hex(file.file_hash), channels.contains(file.platform),
                  file.observed_date == date, (1...160).contains(file.file_name.count),
                  (1...1000).contains(file.accepted_rows), (0...100000).contains(file.ignored_rows),
                  (0...100000).contains(file.duplicate_rows) else { throw TXBillPlanError.conflict }
            byFile[key] = file
        }
        for row in receipts {
            let p = row.platform.rawValue
            let kind = row.transaction_type, product = row.merchant_product, status = row.trade_status
            let merchant = kind.uppercased() == "NATIVE" ||
                ["二维码收款","经营收款","收款码收款","收钱码收款","商家收款","扫码收款","二维码经营收款"].contains { (kind+"|"+product).contains($0) } ||
                (row.platform == .alipay && product == "经营码交易" && kind == "收入")
            let blocked = ["转账","红包","提现","充值","余额宝转入","余额宝转出"].contains { kind.contains($0) }
            let refund = status.contains("退款") || status.uppercased() == "REFUND"
            let rejected = ["失败","关闭","待付款","待支付","未支付","REVOKED","CLOSED","FAIL"].contains { status.uppercased().contains($0) }
            guard merchant, !blocked, refund || !rejected, channels.contains(row.platform),
                  row.trade_time.count == 19, row.trade_time.hasPrefix(date+" "),
                  Self.hex(row.dedupe_key), Self.hex(row.customer_key), ["HIGH","LOW"].contains(row.identity_confidence),
                  (1...160).contains(kind.count), product.count <= 160, status.count <= 80, row.customer_name.count <= 160,
                  (1...160).contains(row.transaction_ref.count), row.transaction_ref == row.transaction_ref.trimmingCharacters(in: .whitespacesAndNewlines),
                  !row.transaction_ref.contains(where: { "*|\r\n".contains($0) }),
                  row.transaction_ref.range(of: "e[+-][0-9]", options: .caseInsensitive.union(.regularExpression)) == nil,
                  refs.insert(p+"|"+row.transaction_ref).inserted,
                  (1...12).contains(row.file_hashes.count), Set(row.file_hashes).count == row.file_hashes.count
            else { throw TXBillPlanError.conflict }
            let a = try Self.amount(row.amount), r = try Self.amount(row.refund_amount)
            guard a > 0 || r > 0, !refund || (a == 0 && r > 0) else { throw TXBillPlanError.conflict }
            let digest = TXBillCSV.digest(Data([p,row.transaction_ref,row.trade_time,Self.kotlin(a),Self.kotlin(r),status].joined(separator: "|").utf8))
            guard digest == row.dedupe_key else { throw TXBillPlanError.conflict }
            if row.identity_confidence == "LOW" {
                guard TXBillCSV.digest(Data((p+"|ANON|"+row.transaction_ref+"|"+row.trade_time).utf8)) == row.customer_key else { throw TXBillPlanError.conflict }
            }
            for hash in row.file_hashes {
                let key = p+"|"+hash
                guard byFile[key] != nil else { throw TXBillPlanError.conflict }
                counts[key,default: 0] += 1
            }
            net[row.platform,default: 0] += a-r
            if a > 0 { paid.insert(row.platform) }
        }
        guard byFile.allSatisfy({ counts[$0.key] == $0.value.accepted_rows }) else { throw TXBillPlanError.conflict }
        for channel in channels {
            let raw = try BusinessCollectorDraftPolicy.cents(channel == .wechat ? target.wechat.natural : target.alipay.natural, label: "自然日收款")
            guard paid.contains(channel), net[channel] == raw, raw >= 0 else { throw TXBillPlanError.conflict }
        }
    }

    var eventKeys: Set<String> {
        var keys = Set<String>()
        for f in files {
            let k = f.platform.rawValue+"|"+f.file_hash
            keys.insert("BATCH|"+k); keys.insert("COLLECTOR|"+k)
        }
        for r in receipts {
            keys.insert("RECEIPT|"+r.key)
            for h in r.file_hashes { keys.insert("SOURCE_LINK|"+r.key+"|"+h) }
        }
        return keys
    }
}

struct TXBillV3Capabilities: Decodable {
    let version: Int
    let feature: String
    let scope: String
    let enabled: Bool
    let atomic: Bool
    let idempotency: Bool
    let new_sale_only: Bool
    let max_receipts: Int
    func allows(book: CloudBook) -> Bool {
        book.isAvailable && book.name.hasPrefix("iOS_TEST_") && version == 3 &&
        feature == "business_bill_atomic_v3" && scope == "test" && enabled && atomic &&
        idempotency && new_sale_only && max_receipts == 1000
    }
}

struct TXBillV3Prepared: Codable, Equatable {
    let base: TXBusinessV2Immutable
    let bill: TXBillV3Input
    let canonical: Data
    let hash: String

    static func prepare(plan: TXBillLocalPlan, scope: TXBusinessV2EditorScope, account: Account,
                        book: CloudBook, records: [SyncEvent], events: [CustomerChangeEvent], cursor: Int64) throws -> Self {
        guard book.name.hasPrefix("iOS_TEST_"), scope.permits(account,book),
              plan.customerCursor == cursor, let bill = plan.wireBill else { throw TXBillPlanError.scope }
        try plan.validate(scope: scope)
        let prepared = try TXBusinessV2DraftPolicy.prepare(plan.proposed, scope: scope, account: account,
            book: book, records: records, events: events)
        let base = TXBusinessV2Immutable(scope: scope, prepared: prepared)
        let canonical = try encode(base, bill: bill)
        return Self(base: base, bill: bill, canonical: canonical, hash: TXBusinessV2Preparation.hash(canonical))
    }
    static func encode(_ base: TXBusinessV2Immutable, bill: TXBillV3Input) throws -> Data {
        _ = try base.validated()
        guard var object = try JSONSerialization.jsonObject(with: base.prepared.canonical) as? [String: Any] else {
            throw BusinessAtomicError.invalidDraft
        }
        object["protocol_version"] = 3
        object["bill"] = try JSONSerialization.jsonObject(with: JSONEncoder().encode(bill))
        return try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys,.withoutEscapingSlashes])
    }
    func validate() throws {
        guard canonical == (try Self.encode(base,bill: bill)),
              hash == TXBusinessV2Preparation.hash(canonical) else { throw BusinessAtomicError.badReceipt }
    }
}

struct TXBillV3Receipt: Decodable {
    struct Event: Decodable {
        let kind: String
        let key: String
        let revision: Int64
        var id: String { kind+"|"+key }
    }
    let base: TXBusinessV2Receipt
    let bill_events: [Event]
    let customer_cursor: Int64
    enum CodingKeys: String,CodingKey { case bill_events,customer_cursor }
    init(from decoder: Decoder) throws {
        base = try TXBusinessV2Receipt(from: decoder)
        let c = try decoder.container(keyedBy: CodingKeys.self)
        bill_events = try c.decode([Event].self, forKey: .bill_events)
        customer_cursor = try c.decode(Int64.self, forKey: .customer_cursor)
    }
    func verified(_ request: TXBillV3Prepared, records: [SyncEvent], events: [CustomerChangeEvent],
                  ledgerCursor: Int64, customerCursor: Int64) -> Bool {
        guard (try? request.validate()) != nil, base.payload_hash == request.hash,
              customer_cursor >= request.bill.expected_customer_cursor, customerCursor >= customer_cursor,
              Set(bill_events.map(\.id)) == request.bill.eventKeys,
              bill_events.count == request.bill.eventKeys.count,
              bill_events.allSatisfy({ $0.revision > 0 }) else { return false }
        // Adapt only after the V3 hash and exact event manifest are checked.
        let adapter = TXBusinessV2Receipt(status: base.status, operation_id: base.operation_id,
            book_id: base.book_id, record_sync_id: base.record_sync_id, collector_sync_ids: base.collector_sync_ids,
            payload_hash: request.base.prepared.hash, sync_cursor: base.sync_cursor,
            overnight_receipts: base.overnight_receipts, expense_payer_sync_id: base.expense_payer_sync_id)
        guard TXBusinessV2Readback.verified(request.base, receipt: adapter, records: records, events: events,
            ledgerCursor: ledgerCursor) else { return false }
        var index: [String: CustomerChangeEvent] = [:]
        for event in events {
            guard index[event.identity] == nil else { return false }
            index[event.identity] = event
        }
        for item in bill_events {
            guard let e = index[item.id], !e.isDeleted, e.revision == item.revision else { return false }
        }
        for file in request.bill.files {
            let key = file.platform.rawValue+"|"+file.file_hash
            guard let b = index["BATCH|"+key], let c = index["COLLECTOR|"+key],
                  b.payload["platform"] == .string(file.platform.rawValue), b.payload["file_hash"] == .string(file.file_hash),
                  c.payload["platform"] == .string(file.platform.rawValue), c.payload["file_hash"] == .string(file.file_hash),
                  c.payload["partner_sync_id"] == .string(request.bill.partner_sync_id) else { return false }
        }
        for row in request.bill.receipts {
            guard let e = index["RECEIPT|"+row.key], e.payload["platform"] == .string(row.platform.rawValue),
                  e.payload["dedupe_key"] == .string(row.dedupe_key), e.payload["transaction_ref"] == .string(row.transaction_ref),
                  e.payload["trade_time"] == .string(row.trade_time), e.payload["business_date"] == .string(request.base.scope.date),
                  e.payload["store_sync_id"] == .string(request.base.scope.storeID), e.payload["customer_key"] == .string(row.customer_key),
                  e.payload["identity_confidence"] == .string(row.identity_confidence),
                  let a = try? BusinessCollectorDraftPolicy.cents(row.amount,label:"账单"),
                  let r = try? BusinessCollectorDraftPolicy.cents(row.refund_amount,label:"退款"),
                  money(e.payload["amount"],a), money(e.payload["refund_amount"],r), money(e.payload["net_amount"],a-r) else { return false }
            for h in row.file_hashes {
                guard let link = index["SOURCE_LINK|"+row.key+"|"+h],
                      link.payload["platform"] == .string(row.platform.rawValue),
                      link.payload["dedupe_key"] == .string(row.dedupe_key),
                      link.payload["file_hash"] == .string(h) else { return false }
            }
        }
        return true
    }
    private func money(_ value: JSONValue?, _ cents: Int64) -> Bool {
        let n: Double
        switch value {
        case .number(let x)?: n = x
        case .exactInteger(let x)?: n = Double(x)
        default: return false
        }
        return n.isFinite && abs(n*100-Double(cents)) < 0.00001
    }
}
