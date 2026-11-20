import Foundation

enum TestBusinessEditState: String, Codable {
    case ready, sending, complete, needsReview

    var title: String {
        switch self {
        case .ready: return "已保存更正，待提交"
        case .sending: return "请求结果不确定，需核对"
        case .complete: return "云端金额更正已回读确认"
        case .needsReview: return "冲突或不确定，禁止重发"
        }
    }
}

struct TestBusinessEditValues: Equatable {
    let wechat: String
    let alipay: String
    let cash: String
    let expense: String
    let opening: String
    let closing: String

    init(wechat: String, alipay: String, cash: String, expense: String,
         opening: String, closing: String) {
        self.wechat = wechat
        self.alipay = alipay
        self.cash = cash
        self.expense = expense
        self.opening = opening
        self.closing = closing
    }

    init(_ event: SyncEvent) {
        wechat = String(format: "%.2f", event.payload["wechat_income"]?.decimal ?? 0)
        alipay = String(format: "%.2f", event.payload["alipay_income"]?.decimal ?? 0)
        cash = String(format: "%.2f", event.payload["cash_income"]?.decimal ?? 0)
        expense = String(format: "%.2f", event.payload["expense"]?.decimal ?? 0)
        opening = String(format: "%.2f", event.payload["opening_stock_value"]?.decimal ?? 0)
        closing = String(format: "%.2f", event.payload["stock_left_value"]?.decimal ?? 0)
    }
}

/// Immutable once persisted; original and replacement snapshots never change.
struct TestBusinessEditJob: Codable, Identifiable {
    let id: String
    let sourceAccount: String
    let bookID: String
    let stageHost: String
    let stageInstanceID: String
    let stageUsername: String
    let deviceID: String
    let original: SyncEvent
    let proposed: SyncEvent
    let createdAt: Date
    var state: TestBusinessEditState
    var note: String
}

struct TestBusinessReceiptSplit: Codable, Equatable {
    let partnerID: Int64
    let partnerName: String
    var amount: Double
    var wechatIncome: Double
    var alipayIncome: Double
    var cashIncome: Double
    enum CodingKeys: String, CodingKey {
        case partnerID = "partner_id"
        case partnerName = "partner_name"
        case amount
        case wechatIncome = "wechat_income"
        case alipayIncome = "alipay_income"
        case cashIncome = "cash_income"
    }
}

/// The only writable operation in V0.3.0: amendment of an existing, modern,
/// uniquely identified TEST business row with unchanged collector identities.
/// No delete, settlement, overnight receipt or purchase modifications.
enum TestBusinessEditPolicy {
    private static func money(_ raw: String, name: String) throws -> Int64 {
        Int64((try TestWritePolicy.money(raw, label: name) * 100).rounded())
    }

    private static func cents(_ raw: Double) -> Int64? {
        guard raw.isFinite, raw >= 0, raw <= 1_000_000,
              abs(raw * 100 - (raw * 100).rounded()) < 0.0001 else { return nil }
        return Int64((raw * 100).rounded())
    }

    private static func amount(_ n: Int64) -> JSONValue {
        .number(Double(n) / 100)
    }

    private static func activeBusiness(_ record: SyncEvent,
                                       events: [SyncEvent]) throws {
        guard record.tableName == "store_daily_record",
              !record.isDeletion,
              record.rowVersion > 0,
              record.rowVersion < Int64.max - 1,
              let date = record.payload["date"]?.text,
              let storeID = record.payload["store_sync_id"]?.text,
              let storeName = record.payload["store_name"]?.text,
              !date.isEmpty, !storeID.isEmpty, !storeName.isEmpty,
              record.syncId == BusinessUploadPolicy.androidSyncID(
                date: date, storeSyncID: storeID) else {
            throw StagingSafetyError.needsReview(
                "只允许更正拥有 Android 稳定同步 ID 的测试营业")
        }
        // Tombstones and historical aliases also block a possibly ambiguous
        // same-day/store adjustment. No best-guess merging.
        let overlapping = events.filter {
            guard $0.tableName == "store_daily_record" else { return false }
            return $0.syncId == record.syncId ||
                ($0.payload["date"]?.text == date &&
                 ($0.payload["store_sync_id"]?.text == storeID ||
                  $0.payload["store_name"]?.text == storeName))
        }
        guard overlapping.count == 1,
              overlapping.first?.syncId == record.syncId,
              overlapping.first?.rowVersion == record.rowVersion,
              overlapping.first?.payload == record.payload else {
            throw StagingSafetyError.needsReview(
                "云端发现同日位置重复、删除历史或版本变化，禁止更正")
        }
    }

    static func unchanged(_ job: TestBusinessEditJob,
                          events: [SyncEvent]) -> Bool {
        guard let current = events.first(where: {
            $0.tableName == "store_daily_record" &&
            $0.syncId == job.original.syncId
        }), current.rowVersion == job.original.rowVersion,
           current.operation == job.original.operation,
           current.payload == job.original.payload else { return false }
        return (try? activeBusiness(current, events: events)) != nil
    }

    static func prepare(original: SyncEvent, values: TestBusinessEditValues,
                        events: [SyncEvent], book: CloudBook,
                        account: String, session: StagingSession,
                        now: Date = Date(), jobID: String = UUID().uuidString)
        throws -> TestBusinessEditJob {
        guard book.isAvailable, book.name.hasPrefix(TestWritePolicy.prefix),
              session.book.id == book.id, session.book.name == book.name,
              session.scope.bookID == book.id,
              session.scope.instanceID == "production-scoped-testbook:" + book.id,
              session.host == StagingPurchasePolicy.productionURL,
              !account.isEmpty, !jobID.isEmpty else {
            throw StagingSafetyError.wrongSourceBook
        }
        try activeBusiness(original, events: events)
        let wechat = try money(values.wechat, name: "微信")
        let alipay = try money(values.alipay, name: "支付宝")
        let cash = try money(values.cash, name: "现金")
        let expense = try money(values.expense, name: "费用")
        let opening = try money(values.opening, name: "开摊库存")
        let closing = try money(values.closing, name: "收摊库存")

        guard let raw = original.payload["receipt_splits_json"]?.text,
              let data = raw.data(using: .utf8),
              var splits = try? JSONDecoder().decode(
                [TestBusinessReceiptSplit].self, from: data),
              !splits.isEmpty else {
            throw StagingSafetyError.needsReview(
                "原始收款人明细不完整，请先在 Android 修正")
        }
        let oldAmounts: [String: Int64] = try [
            "wechat_income", "alipay_income", "cash_income"
        ].reduce(into: [:]) { result, field in
            guard let num = original.payload[field]?.decimal,
                  let c = cents(num) else {
                throw StagingSafetyError.invalidDraft
            }
            result[field] = c
        }
        func apply(_ key: String, desired: Int64,
                   get: (TestBusinessReceiptSplit) -> Double,
                   set: (inout TestBusinessReceiptSplit, Double) -> Void) throws {
            let indexes = splits.indices.filter {
                (cents(get(splits[$0])) ?? 0) > 0
            }
            let originalTotal = splits.reduce(Int64(0)) {
                $0 + (cents(get($1)) ?? -100_000_000)
            }
            guard originalTotal >= 0,
                  originalTotal == oldAmounts[key] else {
                throw StagingSafetyError.needsReview("现有收款归属与渠道金额不一致")
            }
            let previous = oldAmounts[key] ?? 0
            if desired == previous { return }
            let index: Int?
            if indexes.count == 1 { index = indexes.first }
            else if indexes.isEmpty && splits.count == 1 { index = 0 }
            else { index = nil }
            guard let target = index else {
                throw StagingSafetyError.needsReview(
                    "当前支付渠道由多人收款或收款人不明，禁止自动分摊修改；请用 Android 编辑")
            }
            set(&splits[target], Double(desired) / 100)
        }
        try apply("wechat_income", desired: wechat,
                  get: { $0.wechatIncome }, set: { $0.wechatIncome = $1 })
        try apply("alipay_income", desired: alipay,
                  get: { $0.alipayIncome }, set: { $0.alipayIncome = $1 })
        try apply("cash_income", desired: cash,
                  get: { $0.cashIncome }, set: { $0.cashIncome = $1 })
        for index in splits.indices {
            let item = splits[index]
            guard item.partnerID >= 0, !item.partnerName.isEmpty,
                  let a = cents(item.wechatIncome),
                  let b = cents(item.alipayIncome),
                  let c = cents(item.cashIncome) else {
                throw StagingSafetyError.invalidDraft
            }
            splits[index].amount = Double(a + b + c) / 100
        }
        let total = splits.reduce(0.0) { $0 + $1.amount }
        guard abs(total - Double(wechat + alipay + cash) / 100) < 0.005 else {
            throw StagingSafetyError.needsReview("更新后的收款归属与营业额不一致")
        }
        let changed = [
            "wechat_income": wechat, "alipay_income": alipay,
            "cash_income": cash, "expense": expense,
            "opening_stock_value": opening, "stock_left_value": closing
        ]
        guard changed.contains(where: { key, newValue in
            cents(original.payload[key]?.decimal ?? -1) != newValue
        }) else {
            throw StagingSafetyError.needsReview("金额没有变化，无需提交")
        }
        let receiptData = try JSONEncoder().encode(splits)
        guard let receiptText = String(data: receiptData, encoding: .utf8) else {
            throw StagingSafetyError.invalidDraft
        }
        let next = original.rowVersion + 1
        var payload = original.payload
        for (key, value) in changed { payload[key] = amount(value) }
        payload["revenue"] = amount(wechat + alipay + cash)
        payload["profit"] = amount(wechat + alipay + cash + closing - opening - expense)
        payload["receipt_splits_json"] = .string(receiptText)
        payload["row_version"] = .number(Double(next))
        payload["updated_at"] = .number(now.timeIntervalSince1970 * 1000)
        payload["modified_by"] = .string(session.deviceID)
        payload["sync_status"] = .number(2)
        payload["deleted"] = .number(0)
        let proposed = SyncEvent(
            tableName: original.tableName, syncId: original.syncId,
            rowVersion: next, operation: "UPSERT", payload: payload)
        return TestBusinessEditJob(
            id: jobID, sourceAccount: account, bookID: book.id,
            stageHost: session.host, stageInstanceID: session.scope.instanceID,
            stageUsername: session.username, deviceID: session.deviceID,
            original: original, proposed: proposed, createdAt: now,
            state: .ready, note: "更正意图已冻结，提交前需再次检查云端版本")
    }

    static func confirmed(_ job: TestBusinessEditJob,
                          events: [SyncEvent]) -> Bool {
        let candidates = events.filter {
            $0.tableName == "store_daily_record" &&
            $0.syncId == job.proposed.syncId
        }
        guard candidates.count == 1, let actual = candidates.first,
              !actual.isDeletion,
              actual.rowVersion == job.proposed.rowVersion else { return false }
        // Compare all business/ownership fields that this operation changes.
        for key in [
            "date", "store_sync_id", "store_name", "wechat_income",
            "alipay_income", "cash_income", "revenue", "expense",
            "opening_stock_value", "stock_left_value", "profit",
            "wechat_collector_id", "alipay_collector_id", "cash_collector_id",
            "expense_payer_id", "new_customer", "old_customer"
        ] {
            guard let original = job.proposed.payload[key],
                  let remote = actual.payload[key] else { return false }
            switch original {
            case .number(let value):
                guard abs(remote.decimal - value) < 0.005 else { return false }
            case .string(let value):
                guard remote.text == value else { return false }
            default: return false
            }
        }
        guard let wanted = job.proposed.payload["receipt_splits_json"]?.text,
              let received = actual.payload["receipt_splits_json"]?.text,
              let wantedData = wanted.data(using: .utf8),
              let receivedData = received.data(using: .utf8),
              let a = try? JSONDecoder().decode(
                [TestBusinessReceiptSplit].self, from: wantedData),
              let b = try? JSONDecoder().decode(
                [TestBusinessReceiptSplit].self, from: receivedData) else {
            return false
        }
        return a.sorted(by: { $0.partnerID < $1.partnerID }) ==
               b.sorted(by: { $0.partnerID < $1.partnerID })
    }
}
