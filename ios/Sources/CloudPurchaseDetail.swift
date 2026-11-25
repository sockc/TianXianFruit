import Foundation

/// Cloud-only readback. Numeric Android IDs are not cross-device identities
/// unless the server has exactly one active parent with that ID.
struct CloudPurchaseDetail {
    enum Status: Equatable { case matched, amountDiffers, incomplete, ambiguous }
    struct Item: Identifiable {
        let id: String
        let name: String
        let quantity: Double?
        let unit: String
        let unitPrice: Double?
        let subtotal: Double?
    }

    let status: Status
    let items: [Item]
    let orderTotal: Double
    let itemTotal: Double?
    let difference: Double?
    let note: String

    init(order: PurchaseOrderRow, snapshot: ReadOnlySnapshot) {
        orderTotal = order.totalCost
        guard let source = snapshot.records["purchase_order|" + order.id],
              let parentID = Self.numericID(source.payload["id"]) else {
            status = .ambiguous; items = []; itemTotal = nil; difference = nil
            note = "云端父订单已消失或缺少有效数字 ID，无法安全归属商品"
            return
        }
        let parents = snapshot.records.values.filter {
            $0.tableName == "purchase_order" &&
            Self.numericID($0.payload["id"]) == parentID
        }
        guard parents.count == 1, parents[0].syncId == order.id else {
            status = .ambiguous; items = []; itemTotal = nil; difference = nil
            note = "云端父订单数字 ID 不唯一，不展示可能错挂的商品"
            return
        }
        let children = snapshot.records.values.filter {
            $0.tableName == "purchase_item" &&
            Self.numericID($0.payload["order_id"]) == parentID
        }.sorted { $0.syncId < $1.syncId }
        items = children.map { event in
            let name = event.payload["fruit_name"]?.text ?? ""
            return Item(
                id: event.syncId, name: name.isEmpty ? "未命名商品" : name,
                quantity: Self.nonnegative(event.payload["quantity"]),
                unit: event.payload["unit"]?.text ?? "",
                unitPrice: Self.nonnegative(event.payload["unit_price"]),
                subtotal: Self.nonnegative(event.payload["total_cost"]))
        }
        guard !items.isEmpty else {
            status = .incomplete; itemTotal = nil; difference = nil
            note = "云端只有采购订单，暂未读取到商品明细；不能把空明细判断为零采购"
            return
        }
        guard let expected = Self.cents(Self.nonnegative(source.payload["total_cost"])),
              items.allSatisfy({ Self.cents($0.subtotal) != nil }) else {
            status = .incomplete; itemTotal = nil; difference = nil
            note = "采购订单或商品金额字段缺失，无法进行金额核对"
            return
        }
        let actual = items.reduce(Int64(0)) { $0 + (Self.cents($1.subtotal) ?? 0) }
        itemTotal = Double(actual) / 100
        difference = Double(expected - actual) / 100
        if expected == actual {
            status = .matched
            note = "云端订单金额与已读取商品明细合计一致；不代表 Android 已完成本机同步"
        } else {
            status = .amountDiffers
            note = "云端订单金额与当前商品明细有差额，请在 Android 完成同步后刷新核对"
        }
    }

    private static func numericID(_ raw: JSONValue?) -> Int64? {
        guard let raw else { return nil }
        let value: Double
        switch raw {
        case .exactInteger(let identity): return identity > 0 ? identity : nil
        case .number(let number): value = number
        case .string(let text): value = Double(text) ?? 0
        default: return nil
        }
        guard value.isFinite, value > 0,
              value <= 9_007_199_254_740_991,
              value.rounded() == value else { return nil }
        return Int64(value)
    }

    private static func nonnegative(_ raw: JSONValue?) -> Double? {
        guard let raw else { return nil }
        let value: Double
        switch raw {
        case .exactInteger(let identity): value = Double(identity)
        case .number(let number): value = number
        case .string(let text):
            guard let parsed = Double(text) else { return nil }
            value = parsed
        default: return nil
        }
        return value.isFinite && value >= 0 ? value : nil
    }

    private static func cents(_ amount: Double?) -> Int64? {
        guard let amount, amount >= 0, amount < 100_000_000_000 else { return nil }
        return Int64((amount * 100).rounded())
    }
}