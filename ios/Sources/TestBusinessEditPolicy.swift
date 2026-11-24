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
    /// Explicit per-person channel edits. Nil keeps the old single-person
    /// correction path and refuses to guess multi-person allocation.
    let splits: [TestBusinessSplitInput]?

    init(wechat: String, alipay: String, cash: String, expense: String,
         opening: String, closing: String,
         splits: [TestBusinessSplitInput]? = nil) {
        self.wechat = wechat
        self.alipay = alipay
        self.cash = cash
        self.expense = expense
        self.opening = opening
        self.closing = closing
        self.splits = splits
    }

    init(_ event: SyncEvent) {
        wechat = String(format: "%.2f", event.payload["wechat_income"]?.decimal ?? 0)
        alipay = String(format: "%.2f", event.payload["alipay_income"]?.decimal ?? 0)
        cash = String(format: "%.2f", event.payload["cash_income"]?.decimal ?? 0)
        expense = String(format: "%.2f", event.payload["expense"]?.decimal ?? 0)
        opening = String(format: "%.2f", event.payload["opening_stock_value"]?.decimal ?? 0)
        closing = String(format: "%.2f", event.payload["stock_left_value"]?.decimal ?? 0)
        splits = nil
    }
}

/// Exact existing collector identity with editable per-channel amounts. We
/// never introduce, remove, rename or remap a collector on iOS.
struct TestBusinessSplitInput: Equatable, Identifiable {
    let partnerID: Int64
    let partnerName: String
    var wechat: String
    var alipay: String
    var cash: String

    var id: String { String(partnerID) + "|" + partnerName }

    static func from(_ event: SyncEvent) -> [TestBusinessSplitInput] {
        guard let raw = event.payload["receipt_splits_json"]?.text,
              let data = raw.data(using: .utf8),
              let splits = try? JSONDecoder().decode(
                [TestBusinessReceiptSplit].self, from: data) else { return [] }
        return splits.map { value in
            TestBusinessSplitInput(
                partnerID: value.partnerID,
                partnerName: value.partnerName,
                wechat: String(format: "%.2f", value.wechatIncome),
                alipay: String(format: "%.2f", value.alipayIncome),
                cash: String(format: "%.2f", value.cashIncome))
        }
    }

    static func total(_ entries: [TestBusinessSplitInput],
                      key: KeyPath<TestBusinessSplitInput, String>) -> String {
        var total: Int64 = 0
        for row in entries {
            guard let value = Double(row[keyPath: key]),
                  value.isFinite, value >= 0, value <= 1_000_000,
                  abs(value * 100 - (value * 100).rounded()) < 0.0001,
                  total <= Int64.max - Int64((value * 100).rounded()) else {
                return "无效金额"
            }
            total += Int64((value * 100).rounded())
        }
        return String(format: "%.2f", Double(total) / 100.0)
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

/// TEST-only correction of modern stable business rows. V0.3.3 accepts
/// explicit per-person amounts without guessing allocation. Date, location,
/// collector identities, midnight receipts and settlement remain immutable.
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

    /// Android's business edit/delete invalidates derived settlement rows in
    /// the same SQLite transaction. iOS does not have that server capability.
    /// Fail closed if there is even historical evidence of derived data for
    /// the date; never leave a stale transfer or distributed profit behind.
    static func hasDerivedSettlement(date: String,
                                     events: [SyncEvent]) -> Bool {
        let dateTables: Set<String> = [
            "profit_distribution", "daily_cash_settlement",
            "settlement_partner", "settlement_transfer",
            "profit_settlement_item"
        ]
        return events.contains { event in
            if dateTables.contains(event.tableName) {
                let dateKey = event.tableName == "profit_settlement_item"
                    ? "profit_date" : "date"
                if event.payload[dateKey]?.text == date { return true }
                // Period transfers can cover more dates than their save date.
                if event.tableName == "settlement_transfer",
                   let first = event.payload["range_start_date"]?.text,
                   let last = event.payload["range_end_date"]?.text,
                   !first.isEmpty, !last.isEmpty,
                   first <= date, date <= last { return true }
            }
            if dateTables.contains(event.tableName) &&
               event.isDeletion &&
               event.payload["date"] == nil &&
               event.payload["profit_date"] == nil {
                // A tombstone without its date cannot be safely assigned
                // to another day. Block instead of silently ignoring it.
                return true
            }
            if event.tableName == "profit_settlement_batch",
               event.isDeletion &&
               (event.payload["period_start"] == nil ||
                event.payload["period_end"] == nil) {
                return true
            }
            if event.tableName == "profit_settlement_batch",
               let first = event.payload["period_start"]?.text,
               let last = event.payload["period_end"]?.text,
               !first.isEmpty, !last.isEmpty,
               first <= date, date <= last { return true }
            return false
        }
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
        guard let date = current.payload["date"]?.text,
              !hasDerivedSettlement(date: date, events: events) else {
            return false
        }
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
        guard let sourceDate = original.payload["date"]?.text,
              !hasDerivedSettlement(date: sourceDate, events: events) else {
            throw StagingSafetyError.needsReview(
                "该日已有利润分配、结算或转账历史；安卓需同步作废派生数据，iOS 禁止直接更正")
        }
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
        guard Set(splits.map { String($0.partnerID) + "|" + $0.partnerName }).count
                == splits.count,
              splits.allSatisfy({ $0.partnerID > 0 && !$0.partnerName.isEmpty })
        else {
            throw StagingSafetyError.needsReview(
                "原始收款人身份不完整或有重复项，请在 Android 核对")
        }
        guard Set(splits.map(\.partnerID)).count == splits.count else {
            throw StagingSafetyError.needsReview(
                "原始收款人 ID 存在重复，禁止跨设备更正")
        }
        let beforeSplits = splits
        let oldAmounts: [String: Int64] = try [
            "wechat_income", "alipay_income", "cash_income"
        ].reduce(into: [:]) { result, field in
            guard let num = original.payload[field]?.decimal,
                  let c = cents(num) else {
                throw StagingSafetyError.invalidDraft
            }
            result[field] = c
        }
        // Before any edit, prove the cloud's original collector detail
        // balances every channel and each row's declared sum. Never
        // "repair" corrupt or partially synced collector data by guessing.
        var sourceWechat: Int64 = 0
        var sourceAlipay: Int64 = 0
        var sourceCash: Int64 = 0
        for item in beforeSplits {
            guard let w = cents(item.wechatIncome),
                  let a = cents(item.alipayIncome),
                  let c = cents(item.cashIncome),
                  let stated = cents(item.amount),
                  stated == w + a + c, stated > 0 else {
                throw StagingSafetyError.needsReview(
                    "原有收款人明细金额不完整或合计错误，拒绝覆盖")
            }
            sourceWechat += w
            sourceAlipay += a
            sourceCash += c
        }
        guard sourceWechat == oldAmounts["wechat_income"],
              sourceAlipay == oldAmounts["alipay_income"],
              sourceCash == oldAmounts["cash_income"] else {
            throw StagingSafetyError.needsReview(
                "原有各渠道金额与收款人明细不一致，请先在安卓核对")
        }
        guard let oldRevenue = cents(original.payload["revenue"]?.decimal ?? -1),
              let oldExpense = cents(original.payload["expense"]?.decimal ?? -1),
              let oldOpening = cents(original.payload["opening_stock_value"]?.decimal ?? -1),
              let oldClosing = cents(original.payload["stock_left_value"]?.decimal ?? -1),
              let oldProfit = original.payload["profit"]?.decimal,
              oldProfit.isFinite,
              abs(oldProfit - Double(oldRevenue + oldClosing - oldOpening - oldExpense) / 100.0) < 0.005,
              oldRevenue == sourceWechat + sourceAlipay + sourceCash else {
            throw StagingSafetyError.needsReview(
                "原营业额或利润与原明细不一致，禁止 iOS 重新计算覆盖")
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
        if let explicit = values.splits {
            guard explicit.count == splits.count,
                  Set(explicit.map(\.id)).count == explicit.count,
                  Set(explicit.map(\.id)) == Set(splits.map {
                    String($0.partnerID) + "|" + $0.partnerName
                  }) else {
                throw StagingSafetyError.needsReview(
                    "收款人集合已变化；不能增加、移除或重新归属到其他收款人")
            }
            let byID = Dictionary(uniqueKeysWithValues: explicit.map {
                ($0.id, $0)
            })
            for index in splits.indices {
                let identity = String(splits[index].partnerID) + "|" +
                               splits[index].partnerName
                guard let entry = byID[identity] else {
                    throw StagingSafetyError.invalidDraft
                }
                let w = try money(entry.wechat, name: "微信收款")
                let a = try money(entry.alipay, name: "支付宝收款")
                let c = try money(entry.cash, name: "现金收款")
                guard w + a + c > 0 else {
                    throw StagingSafetyError.needsReview(
                        "不允许删除原有收款人或将其所有渠道金额清零")
                }
                splits[index].wechatIncome = Double(w) / 100
                splits[index].alipayIncome = Double(a) / 100
                splits[index].cashIncome = Double(c) / 100
            }
        } else {
            try apply("wechat_income", desired: wechat,
                      get: { $0.wechatIncome },
                      set: { $0.wechatIncome = $1 })
            try apply("alipay_income", desired: alipay,
                      get: { $0.alipayIncome },
                      set: { $0.alipayIncome = $1 })
            try apply("cash_income", desired: cash,
                      get: { $0.cashIncome },
                      set: { $0.cashIncome = $1 })
        }
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
        func sumCents(_ field: KeyPath<TestBusinessReceiptSplit, Double>) -> Int64 {
            splits.reduce(Int64(0)) {
                $0 + (cents($1[keyPath: field]) ?? 0)
            }
        }
        guard sumCents(\.wechatIncome) == wechat,
              sumCents(\.alipayIncome) == alipay,
              sumCents(\.cashIncome) == cash,
              abs(total - Double(wechat + alipay + cash) / 100) < 0.005 else {
            throw StagingSafetyError.needsReview("更新后的收款归属与营业额不一致")
        }
        let changed = [
            "wechat_income": wechat, "alipay_income": alipay,
            "cash_income": cash, "expense": expense,
            "opening_stock_value": opening, "stock_left_value": closing
        ]
        let splitsChanged = beforeSplits != splits
        guard splitsChanged || changed.contains(where: { entry in
            cents(original.payload[entry.key]?.decimal ?? -1) != entry.value
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
              actual.rowVersion == job.proposed.rowVersion,
              (try? activeBusiness(actual, events: events)) != nil else {
            return false
        }
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
            case .exactInteger(let value):
                // Never validate an Android 17-digit collector/expense
                // identity via floating-point comparison.
                guard remote == .exactInteger(value) else { return false }
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
