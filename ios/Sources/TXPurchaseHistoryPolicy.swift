import Foundation

/// Presentation-only filter over already synchronized Android purchase orders.
/// The selected date is the inclusive upper bound for every range, avoiding
/// accidental mixing of historical/future purchase dates.
enum TXPurchaseHistoryPolicy {
    static let ranges = ["单日", "近7天", "近30天", "近90天", "全部"]

    static func lowerBound(range: String, selectedDate: Date) -> String {
        switch range {
        case "单日": return BusinessDates.key(selectedDate)
        case "近7天": return BusinessDates.daysAgo(6, from: selectedDate)
        case "近30天": return BusinessDates.daysAgo(29, from: selectedDate)
        case "近90天": return BusinessDates.daysAgo(89, from: selectedDate)
        case "全部": return "0000-00-00"
        default: return BusinessDates.key(selectedDate)
        }
    }

    static func filter(_ orders: [PurchaseOrderRow],
                       range: String, selectedDate: Date,
                       buyer: String, search: String) -> [PurchaseOrderRow] {
        let until = BusinessDates.key(selectedDate)
        let since = lowerBound(range: range, selectedDate: selectedDate)
        let needle = search.trimmingCharacters(in: .whitespacesAndNewlines)
        return orders.filter { order in
            (order.date >= since && order.date <= until) &&
            (buyer.isEmpty || order.buyer == buyer) &&
            (needle.isEmpty ||
             order.buyer.localizedCaseInsensitiveContains(needle) ||
             order.date.contains(needle))
        }
    }
}
