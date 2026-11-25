import Foundation

/// Reads historical *positive* item prices from verified cloud orders.
/// No source purchase is ever modified. A displayed hint is not an automatic
/// replacement for a buyer's real input.
struct PurchaseLastPrice: Equatable {
    let price: Double
    let unit: String
    let date: String
}

enum PurchaseLastPricePolicy {
    private static func numericID(_ value: JSONValue?) -> Int64? {
        AndroidNumericIdentity.exact(value)
    }

    /// When changing goods, a previous fruit's price must never leak
    /// into the new line. Existing manually entered prices are preserved
    /// whenever the fruit identity remains the same.
    static func priceAfterFruitChange(previousFruitID: String,
                                      nextFruitID: String,
                                      currentPrice: String) -> String {
        previousFruitID == nextFruitID ? currentPrice : "0"
    }

    static func latest(
        fruitSyncID: String,
        unit: String,
        records: [SyncEvent],
        onOrBefore dateLimit: String? = nil
    ) -> PurchaseLastPrice? {
        let matches = records.filter {
            $0.tableName == "fruit" && !$0.isDeletion &&
            $0.syncId == fruitSyncID
        }
        guard matches.count == 1,
              let fruitID = numericID(matches[0].payload["id"]),
              records.filter({
                $0.tableName == "fruit" && !$0.isDeletion &&
                numericID($0.payload["id"]) == fruitID
              }).count == 1 else {
            return nil
        }
        let orders = records.filter {
            $0.tableName == "purchase_order" && !$0.isDeletion &&
            numericID($0.payload["id"]) != nil
        }
        var parentDates: [Int64: String] = [:]
        for order in orders {
            guard let id = numericID(order.payload["id"]),
                  let date = order.payload["date"]?.text,
                  BusinessDates.parse(date) != nil else { continue }
            if parentDates[id] != nil {
                // Ambiguous numeric IDs are never used for historical prices.
                parentDates[id] = ""
            } else {
                parentDates[id] = date
            }
        }
        let suggestions: [PurchaseLastPrice] = records.compactMap { event in
            guard event.tableName == "purchase_item", !event.isDeletion,
                  numericID(event.payload["fruit_id"]) == fruitID,
                  let orderID = numericID(event.payload["order_id"]),
                  let date = parentDates[orderID], !date.isEmpty,
                  (dateLimit == nil || date <= dateLimit!),
                  let price = event.payload["unit_price"]?.decimal,
                  price.isFinite, price > 0, price <= 1_000_000,
                  let childUnit = event.payload["unit"]?.text,
                  childUnit == unit,
                  abs(price * 100 - (price * 100).rounded()) < 0.0001
            else { return nil }
            return PurchaseLastPrice(price: price, unit: childUnit, date: date)
        }
        return suggestions.sorted {
            $0.date == $1.date ? $0.price > $1.price : $0.date > $1.date
        }.first
    }
}
