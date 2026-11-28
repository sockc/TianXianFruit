import Foundation

struct TXHistoryBillV4Capabilities: Decodable {
    let version: Int
    let feature: String
    let scope: String
    let enabled: Bool
    let atomic: Bool
    let idempotency: Bool
    let existing_sale_only: Bool
    let max_receipts: Int
    func allows(book: CloudBook) -> Bool {
        book.isAvailable && book.name.hasPrefix("iOS_TEST_") &&
        ["OWNER","EDITOR","SUPERADMIN"].contains(book.role.uppercased()) &&
        version == 4 && feature == "business_bill_edit_v4" && scope == "test" &&
        enabled && atomic && idempotency && existing_sale_only && max_receipts == 1000
    }
}

/// Exact EditRequest V40 RC1, excluding device_id and payload_hash.
struct TXHistoryBillV4Request: Codable, Equatable {
    let protocol_version: Int
    let book_id: String
    let operation_id: String
    let account: String
    let date: String
    let store_sync_id: String
    let record_sync_id: String
    let expected_row_version: Int64
    let expected_natural_revision: Int64
    let previous_revision: Int64
    let following_revision: Int64
    let bill: TXBillV3Input

    func canonical() throws -> Data {
        guard protocol_version == 4,
              let book = UUID(uuidString:book_id), book.uuidString.lowercased() == book_id,
              let op = UUID(uuidString:operation_id), op.uuidString.lowercased() == operation_id,
              !account.isEmpty, account.count <= 64, account.lowercased() == account,
              BusinessDates.parse(date) != nil, UUID(uuidString:store_sync_id) != nil,
              !record_sync_id.isEmpty, record_sync_id.count <= 80,
              expected_row_version > 0, expected_row_version < Int64.max,
              expected_natural_revision > 0, expected_natural_revision < Int64.max,
              previous_revision >= 0, following_revision >= 0,
              UUID(uuidString:bill.partner_sync_id) != nil
        else { throw TXBillPlanError.scope }
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys,.withoutEscapingSlashes]
        return try encoder.encode(self)
    }
}

/// Freeze request plus comparison evidence, not a V1/V2 new-sale proposal.
struct TXHistoryBillV4Prepared: Codable, Equatable {
    let request: TXHistoryBillV4Request
    let canonical: Data
    let hash: String
    let originalSale: [String:JSONValue]
    let originalNatural: [String:JSONValue]
    let expectedSale: [String:JSONValue]
    let expectedNatural: [String:JSONValue]
    let expectedSplits: [[String:JSONValue]]
    let originalMorning: [MorningEvidence]
    struct MorningEvidence: Codable, Equatable {
        let key: String
        let revision: Int64
        let payload: [String:JSONValue]
    }

    static func prepare(plan:TXHistoryBillPlan,account:Account,book:CloudBook,
                        operationID:UUID = UUID()) throws -> Self {
        guard book.isAvailable,book.name.hasPrefix("iOS_TEST_"),
              ["OWNER","EDITOR","SUPERADMIN"].contains(book.role.uppercased()),
              let bookID = UUID(uuidString:book.id)
        else { throw TXBillPlanError.scope }
        let r = TXHistoryBillV4Request(protocol_version:4,book_id:bookID.uuidString.lowercased(),
            operation_id:operationID.uuidString.lowercased(),account:account.username.lowercased(),
            date:plan.date,store_sync_id:plan.storeID,record_sync_id:plan.recordID,
            expected_row_version:plan.rowVersion,expected_natural_revision:plan.naturalRevision,
            previous_revision:plan.previousRevision,following_revision:plan.followingRevision,bill:plan.bill)
        let data = try r.canonical()
        let result = Self(request:r,canonical:data,hash:TXBusinessV2Preparation.hash(data),
            originalSale:plan.originalSale,originalNatural:plan.originalNatural,
            expectedSale:plan.expectedSale,expectedNatural:plan.expectedNatural,expectedSplits:plan.expectedSplits,
            originalMorning:plan.originalMorning.map { MorningEvidence(key:$0.key,revision:$0.revision,payload:$0.payload) }.sorted { $0.key < $1.key })
        try result.validate()
        return result
    }

    func validate() throws {
        guard canonical == (try request.canonical()), hash == TXBusinessV2Preparation.hash(canonical),
              originalSale["date"] == .string(request.date),
              originalSale["store_sync_id"] == .string(request.store_sync_id),
              originalNatural["date"] == .string(request.date),
              originalNatural["store_sync_id"] == .string(request.store_sync_id),
              originalNatural["partner_sync_id"] == .string(request.bill.partner_sync_id)
        else { throw BusinessAtomicError.badReceipt }
        let channels = request.bill.channels
        var target = TXBusinessV2CollectorInput()
        target.partner = request.bill.partner_sync_id
        target.wechat.natural = BusinessCommitPolicy.money(try TXHistoryBillPlan.money(expectedNatural["wechat"]))
        target.alipay.natural = BusinessCommitPolicy.money(try TXHistoryBillPlan.money(expectedNatural["alipay"]))
        try request.bill.validate(date:request.date,partner:target.partner,channels:channels,
            cursor:request.bill.expected_customer_cursor,receiptKeys:request.bill.receipts.map(\.key).sorted(),
            fileHashes:Array(Set(request.bill.files.map(\.file_hash))).sorted(),target:target)
        let saleFields = Set(["receipt_splits_json","revenue","profit"]+channels.map{$0.rawValue.lowercased()+"_income"})
        let naturalFields = Set(channels.flatMap{ [$0.rawValue.lowercased(),"saved_"+$0.rawValue.lowercased()] })
        guard originalSale.filter({ !saleFields.contains($0.key) }) == expectedSale.filter({ !saleFields.contains($0.key) }),
              originalNatural.filter({ !naturalFields.contains($0.key) }) == expectedNatural.filter({ !naturalFields.contains($0.key) })
        else { throw TXBillPlanError.conflict }
    }
    func body(deviceID:String) throws -> Data {
        try validate()
        guard (8...64).contains(deviceID.count),
              var object = try JSONSerialization.jsonObject(with:canonical) as? [String:Any]
        else { throw TXBillPlanError.scope }
        object["device_id"] = deviceID; object["payload_hash"] = hash
        return try JSONSerialization.data(withJSONObject:object,options:[.sortedKeys,.withoutEscapingSlashes])
    }
}

struct TXHistoryBillV4Receipt: Decodable {
    let status: String
    let operation_id: String
    let book_id: String
    let record_sync_id: String
    let payload_hash: String
    let sync_cursor: Int64
    let row_version: Int64
    let natural_revision: Int64
    let previous_revision: Int64
    let following_revision: Int64
    let bill_events: [TXBillV3Receipt.Event]
    let customer_cursor: Int64
    func matches(_ prepared: TXHistoryBillV4Prepared) -> Bool {
        let r = prepared.request
        return (try? prepared.validate()) != nil &&
            ["committed","already_committed"].contains(status) &&
            operation_id.lowercased() == r.operation_id && book_id.lowercased() == r.book_id &&
            record_sync_id == r.record_sync_id && payload_hash == prepared.hash &&
            sync_cursor > 0 && customer_cursor > r.bill.expected_customer_cursor &&
            row_version == r.expected_row_version+1 && natural_revision == r.expected_natural_revision+1 &&
            previous_revision == r.previous_revision && following_revision == r.following_revision &&
            Set(bill_events.map(\.id)) == r.bill.eventKeys && bill_events.count == r.bill.eventKeys.count &&
            bill_events.allSatisfy { $0.revision > 0 }
    }

    func verified(_ prepared: TXHistoryBillV4Prepared,records:[SyncEvent],events:[CustomerChangeEvent],
                  ledgerCursor:Int64,customerCursor:Int64) -> Bool {
        guard matches(prepared),ledgerCursor >= sync_cursor,customerCursor >= customer_cursor else { return false }
        let r = prepared.request
        let sales = records.filter { $0.tableName == "store_daily_record" && $0.syncId == r.record_sync_id }
        guard sales.count == 1,let sale = sales.first,!sale.isDeletion,sale.rowVersion == row_version,
              let data = sale.payload["receipt_splits_json"]?.text.data(using:.utf8),
              let splits = try? JSONDecoder().decode([[String:JSONValue]].self,from:data),
              splits == prepared.expectedSplits else { return false }
        // Only transport metadata may differ from the planned payload.
        let transport = Set(["receipt_splits_json","row_version","updated_at","modified_by","sync_status","deleted","sync_id"])
        guard sale.payload.filter({ !transport.contains($0.key) }) ==
            prepared.expectedSale.filter({ !transport.contains($0.key) }) else { return false }
        var index:[String:CustomerChangeEvent] = [:]
        for e in events {
            guard index[e.identity] == nil else { return false }
            index[e.identity] = e
        }
        let naturalKey = r.date+"|"+r.bill.partner_sync_id
        guard let n = index["NATURAL|"+naturalKey],!n.isDeleted,n.revision == natural_revision,
              n.payload == prepared.expectedNatural,
              let pair = try? TXBusinessV2DraftPolicy.sources(events,date:r.date,partner:r.bill.partner_sync_id),
              pair.0.revision == previous_revision,pair.1.revision == following_revision else { return false }
        for m in prepared.originalMorning {
            guard let e = index["OVERNIGHT|"+m.key],!e.isDeleted,e.revision == m.revision,e.payload == m.payload else { return false }
        }
        for item in bill_events {
            guard let e = index[item.id],!e.isDeleted,e.revision == item.revision else { return false }
        }
        for file in r.bill.files {
            let k = file.platform.rawValue+"|"+file.file_hash
            guard let b = index["BATCH|"+k],let c = index["COLLECTOR|"+k],
                b.payload["platform"] == .string(file.platform.rawValue),b.payload["file_hash"] == .string(file.file_hash),
                c.payload["platform"] == .string(file.platform.rawValue),c.payload["file_hash"] == .string(file.file_hash),
                c.payload["partner_sync_id"] == .string(r.bill.partner_sync_id) else { return false }
        }
        for row in r.bill.receipts {
            guard let e = index["RECEIPT|"+row.key],e.payload["platform"] == .string(row.platform.rawValue),
                e.payload["dedupe_key"] == .string(row.dedupe_key),e.payload["transaction_ref"] == .string(row.transaction_ref),
                e.payload["trade_time"] == .string(row.trade_time),e.payload["business_date"] == .string(r.date),
                e.payload["store_sync_id"] == .string(r.store_sync_id),e.payload["customer_key"] == .string(row.customer_key),
                e.payload["identity_confidence"] == .string(row.identity_confidence),
                let a = try? BusinessCollectorDraftPolicy.cents(row.amount,label:"账单"),
                let refund = try? BusinessCollectorDraftPolicy.cents(row.refund_amount,label:"退款"),
                (try? TXHistoryBillPlan.money(e.payload["amount"])) == a,
                (try? TXHistoryBillPlan.money(e.payload["refund_amount"])) == refund,
                (try? TXHistoryBillPlan.money(e.payload["net_amount"],signed:true)) == a-refund else { return false }
            for h in row.file_hashes {
                guard let link = index["SOURCE_LINK|"+row.key+"|"+h],
                    link.payload["platform"] == .string(row.platform.rawValue),
                    link.payload["dedupe_key"] == .string(row.dedupe_key),
                    link.payload["file_hash"] == .string(h) else { return false }
            }
        }
        return true
    }
}
