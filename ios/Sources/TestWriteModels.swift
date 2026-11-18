import Foundation

/// iOS V0.2.0 local-only write simulation. These drafts are NEVER synced or
/// interpreted as formal business transactions.
enum TestDraftKind: String, Codable, CaseIterable {
    case business = "BUSINESS_PREVIEW"
    case purchase = "PURCHASE_PREVIEW"

    var title: String {
        switch self {
        case .business: return "营业录入草稿"
        case .purchase: return "采购录入草稿"
        }
    }
}

struct TestDraft: Codable, Identifiable, Equatable {
    let id: String
    let account: String
    let bookID: String
    let kind: TestDraftKind
    let date: String
    let fields: [String: String]
    let createdAt: Date

    var summary: String {
        switch kind {
        case .business:
            let name = fields["store_name"] ?? "未知位置"
            return "\(name) · 微信 \(fields["wechat"] ?? "0") · 支付宝 \(fields["alipay"] ?? "0") · 现金 \(fields["cash"] ?? "0")"
        case .purchase:
            let name = fields["fruit_name"] ?? "商品"
            return "\(name) · \(fields["quantity"] ?? "0") \(fields["unit"] ?? "") · \(fields["total_cost"] ?? "0") 元"
        }
    }
}

struct TestLookup: Identifiable, Hashable {
    let id: String
    let name: String
    let localID: String
}

enum TestWriteError: LocalizedError {
    case notTestBook
    case notAuthorized
    case mismatch
    case missingData(String)
    case invalidNumber(String)
    case insufficientSchema(String)

    var errorDescription: String? {
        switch self {
        case .notTestBook: return "只允许名称以 iOS_TEST_ 开头的独立测试账本"
        case .notAuthorized: return "当前账号不是此测试账本的可编辑成员"
        case .mismatch: return "输入的账本 ID 与当前测试账本不一致"
        case .missingData(let key): return "请选择或填写：" + key
        case .invalidNumber(let key): return "金额、数量不能为负数或非法数字：" + key
        case .insufficientSchema(let key): return key
        }
    }
}

enum TestWritePolicy {
    static let prefix = "iOS_TEST_"

    static func authorize(book: CloudBook, typedID: String) throws {
        guard book.isAvailable, book.name.hasPrefix(prefix) else {
            throw TestWriteError.notTestBook
        }
        guard ["OWNER", "SUPERADMIN", "EDITOR"].contains(book.role) else {
            throw TestWriteError.notAuthorized
        }
        guard typedID.trimmingCharacters(in: .whitespacesAndNewlines) == book.id,
              !book.id.isEmpty else { throw TestWriteError.mismatch }
    }

    static func money(_ raw: String, label: String) throws -> Double {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let value = Double(trimmed), value.isFinite,
              value >= 0, value <= 1_000_000,
              abs(value * 100 - (value * 100).rounded()) < 0.0001 else {
            throw TestWriteError.invalidNumber(label)
        }
        return value
    }

    static func count(_ raw: String, label: String) throws -> Double {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let value = Double(trimmed), value.isFinite,
              value > 0, value <= 100000 else {
            throw TestWriteError.invalidNumber(label)
        }
        return value
    }

    static func validate(_ draft: TestDraft, book: CloudBook) throws {
        try authorize(book: book, typedID: draft.bookID)
        guard BusinessDates.parse(draft.date) != nil else {
            throw TestWriteError.missingData("营业/采购日期")
        }
        switch draft.kind {
        case .business:
            guard !(draft.fields["store_sync_id"] ?? "").isEmpty else {
                throw TestWriteError.missingData("已同步营业位置的稳定 ID")
            }
            for key in ["wechat", "alipay", "cash", "expense",
                        "opening_stock_value", "stock_left_value", "purchase_cost"] {
                _ = try money(draft.fields[key] ?? "", label: key)
            }
            for (channel, collector) in [
                ("wechat", "wechat_collector_sync_id"),
                ("alipay", "alipay_collector_sync_id"),
                ("cash", "cash_collector_sync_id"),
                ("expense", "expense_payer_sync_id")
            ] {
                let amount = try money(draft.fields[channel] ?? "", label: channel)
                if amount > 0 && (draft.fields[collector] ?? "").isEmpty {
                    throw TestWriteError.missingData(channel + " 对应的合伙人/收款人")
                }
            }
            // Only a draft: collector/split, costs and authoritative profit
            // are still required before a real business mutation could exist.
        case .purchase:
            guard !(draft.fields["buyer_sync_id"] ?? "").isEmpty,
                  !(draft.fields["fruit_sync_id"] ?? "").isEmpty,
                  !(draft.fields["fruit_name"] ?? "").isEmpty else {
                throw TestWriteError.missingData("采购人、商品及其稳定 ID")
            }
            _ = try count(draft.fields["quantity"] ?? "", label: "数量")
            _ = try money(draft.fields["total_cost"] ?? "", label: "总价")
            _ = try money(draft.fields["unit_weight_jin"] ?? "", label: "每件重量")
            guard ["PLANNED", "TEMPORARY"].contains(draft.fields["purchase_type"] ?? "") else {
                throw TestWriteError.missingData("采购类型")
            }
            guard !(draft.fields["unit"] ?? "").isEmpty else {
                throw TestWriteError.missingData("采购单位")
            }
            // The parent/child numeric ID mapping and purchase completion
            // state must be verified against a disposable test server first.
        }
    }

    static func cloudSubmissionIsAllowed(_ draft: TestDraft) -> Bool {
        // V0.2.0 explicit hard stop: NOT a server writing client.
        return false
    }
}

extension ReadOnlySnapshot {
    var testStores: [TestLookup] { testLookups(table: "store") }
    var testPartners: [TestLookup] { testLookups(table: "partner") }
    var testFruits: [TestLookup] { testLookups(table: "fruit") }

    private func testLookups(table: String) -> [TestLookup] {
        records.values.compactMap { event in
            guard event.tableName == table, !event.isDeletion,
                  !event.syncId.isEmpty,
                  let name = event.payload["name"]?.text, !name.isEmpty else { return nil as TestLookup? }
            return TestLookup(id: event.syncId, name: name,
                              localID: event.payload["id"]?.text ?? "")
        }
        .sorted { $0.name < $1.name }
    }
}
