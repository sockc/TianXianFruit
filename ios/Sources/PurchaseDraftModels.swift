import Foundation

/// A line has stable device-local identity across edits. This is a LOCAL
/// drafting format, not an approved /sync/push purchase_item payload.
struct PurchaseDraftLine: Codable, Equatable, Identifiable {
    let id: String
    var fruitSyncID: String
    var fruitName: String
    var quantity: String
    var unit: String
    var unitPrice: String
    var unitWeightJin: String
    /// Optional cloud plan-item provenance, never assumed to authorize completion.
    /// Older V0.4.0-V0.4.1 JSON drafts decode with nil.
    var sourcePlanItemSyncID: String? = nil

    static func blank() -> PurchaseDraftLine {
        PurchaseDraftLine(id: UUID().uuidString, fruitSyncID: "",
                          fruitName: "", quantity: "1", unit: "件",
                          unitPrice: "0", unitWeightJin: "0")
    }

    func duplicated() -> PurchaseDraftLine {
        PurchaseDraftLine(id: UUID().uuidString, fruitSyncID: fruitSyncID,
                          fruitName: fruitName, quantity: quantity, unit: unit,
                          unitPrice: unitPrice, unitWeightJin: unitWeightJin,
                          sourcePlanItemSyncID: sourcePlanItemSyncID)
    }
}

enum PurchaseDraftPolicy {
    static let maxLines = 30
    private static let decimalLocale = Locale(identifier: "en_US_POSIX")
    private static let largestValue = Decimal(1_000_000)

    private static func parsed(_ raw: String, decimals: Int, label: String,
                               strictlyPositive: Bool = false) throws -> Decimal {
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        let regex = "^[0-9]+(\\.[0-9]{1," + String(decimals) + "})?$"
        guard !text.isEmpty,
              text.range(of: regex, options: .regularExpression) != nil,
              let decimal = Decimal(string: text, locale: decimalLocale),
              decimal <= largestValue,
              decimal >= 0,
              !strictlyPositive || decimal > 0 else {
            throw TestWriteError.invalidNumber(label)
        }
        return decimal
    }

    static func quantity(_ raw: String) throws -> Decimal {
        let value = try parsed(raw, decimals: 3, label: "数量",
                               strictlyPositive: true)
        guard value <= Decimal(100_000) else {
            throw TestWriteError.invalidNumber("数量")
        }
        return value
    }

    static func unitPrice(_ raw: String) throws -> Decimal {
        try parsed(raw, decimals: 2, label: "单价")
    }

    static func unitWeight(_ raw: String) throws -> Decimal {
        try parsed(raw, decimals: 3, label: "每件重量")
    }

    static func rounded(_ value: Decimal) -> Decimal {
        var unrounded = value
        var result = Decimal()
        NSDecimalRound(&result, &unrounded, 2, .plain)
        return result
    }

    static func subtotal(_ line: PurchaseDraftLine) throws -> Decimal {
        let count = try quantity(line.quantity)
        let price = try unitPrice(line.unitPrice)
        _ = try unitWeight(line.unitWeightJin)
        guard !line.fruitSyncID.isEmpty,
              !line.fruitName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              !line.unit.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw TestWriteError.missingData("商品及单位")
        }
        let amount = rounded(count * price)
        guard amount <= largestValue else {
            throw TestWriteError.invalidNumber("商品小计超过上限")
        }
        return amount
    }

    /// Convert a single product's explicitly entered line total to the existing
    /// two-decimal unit price. Never use an entire-order "总价" placeholder as
    /// a real product price, nor silently round away an entered amount.
    static func unitPriceForExactLineTotal(_ raw: String, line: PurchaseDraftLine) throws -> String {
        guard !line.fruitName.isEmpty,
              line.fruitName.trimmingCharacters(in: .whitespacesAndNewlines) != "总价" else {
            throw TestWriteError.missingData("请先选择实际商品；整单总价不反算单品")
        }
        let count = try quantity(line.quantity)
        let desired = try unitPrice(raw)
        let price = rounded(desired / count)
        var candidate = line
        candidate.unitPrice = display(price)
        guard try subtotal(candidate) == desired else {
            throw TestWriteError.invalidNumber("两位小数单价无法精确对应此总价，请核对数量与实际单价")
        }
        return candidate.unitPrice
    }

    static func total(_ lines: [PurchaseDraftLine]) throws -> Decimal {
        guard !lines.isEmpty, lines.count <= maxLines else {
            throw TestWriteError.missingData("商品数量应在 1–30 种之间")
        }
        var lineIDs = Set<String>()
        var total = Decimal(0)
        for line in lines {
            guard !line.id.isEmpty, lineIDs.insert(line.id).inserted else {
                throw TestWriteError.missingData("商品明细 ID 重复")
            }
            total += try subtotal(line)
        }
        guard total <= largestValue else {
            throw TestWriteError.invalidNumber("整单采购金额超过上限")
        }
        return rounded(total)
    }

    static func display(_ value: Decimal) -> String {
        let formatter = NumberFormatter()
        formatter.locale = decimalLocale
        formatter.numberStyle = .decimal
        formatter.minimumFractionDigits = 2
        formatter.maximumFractionDigits = 2
        formatter.usesGroupingSeparator = false
        return formatter.string(from: NSDecimalNumber(decimal: value)) ?? "0.00"
    }

    /// Convert a single-item legacy local draft to a LINE EDITOR preview.
    /// Do not mutate the old draft until the user explicitly saves.
    static func previewLegacy(_ draft: TestDraft) -> [PurchaseDraftLine] {
        if let items = draft.purchaseItems { return items }
        let quantity = draft.fields["quantity"] ?? "1"
        let total = draft.fields["total_cost"] ?? "0"
        let price: Decimal
        if let q = try? self.quantity(quantity), q > 0,
           let t = try? unitPrice(total) {
            price = rounded(t / q)
        } else {
            price = 0
        }
        return [
            PurchaseDraftLine(id: UUID().uuidString,
                              fruitSyncID: draft.fields["fruit_sync_id"] ?? "",
                              fruitName: draft.fields["fruit_name"] ?? "",
                              quantity: quantity, unit: draft.fields["unit"] ?? "件",
                              unitPrice: display(price),
                              unitWeightJin: draft.fields["unit_weight_jin"] ?? "0")
        ]
    }

    static func buildDraft(id: String, account: String, bookID: String,
                           date: String, buyerSyncID: String,
                           purchaseType: String, remark: String,
                           lines: [PurchaseDraftLine],
                           createdAt: Date) throws -> TestDraft {
        let total = try self.total(lines)
        guard !buyerSyncID.isEmpty else {
            throw TestWriteError.missingData("采购人")
        }
        guard ["PLANNED", "TEMPORARY"].contains(purchaseType) else {
            throw TestWriteError.missingData("采购类型")
        }
        return TestDraft(
            id: id, account: account, bookID: bookID, kind: .purchase,
            date: date, fields: [
                "buyer_sync_id": buyerSyncID,
                "purchase_type": purchaseType,
                "remark": remark,
                "total_cost": display(total)
            ], createdAt: createdAt, purchaseItems: lines)
    }
}
