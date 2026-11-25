import Foundation
import SwiftUI

/// Android-compatible "previous seven actual days": the selected day is
/// displayed separately above the history and never consumes one of the 7.
struct TXRecentHistoryDay<Row> {
    let date: String
    let rows: [Row]
}

enum TXRecentLedgerHistoryPolicy {
    static func previousDays<Row>(_ rows: [Row],
                                  before selectedDate: String,
                                  limit: Int = 7,
                                  date: (Row) -> String) -> [TXRecentHistoryDay<Row>] {
        guard limit > 0, BusinessDates.parse(selectedDate) != nil else { return [] }
        let previous = rows.filter {
            let key = date($0)
            return key < selectedDate && BusinessDates.parse(key) != nil
        }
        let grouped = Dictionary(grouping: previous, by: date)
        return grouped.keys.sorted(by: >).prefix(limit).map { key in
            TXRecentHistoryDay(date: key, rows: grouped[key] ?? [])
        }
    }

    static func weekdayLabel(_ key: String) -> String {
        guard let date = BusinessDates.parse(key) else { return key }
        let day = BusinessDates.calendar.component(.weekday, from: date)
        let names = ["周日", "周一", "周二", "周三", "周四", "周五", "周六"]
        return String(key.suffix(5)) + " · " + names[max(0, min(day - 1, 6))]
    }
}

/// Mirrors Android's recent-seven BUSINESS day list directly below the
/// selected day's business records. Reads only server-backed snapshot rows.
struct TXRecentBusinessDaysView: View {
    @EnvironmentObject private var model: LedgerViewModel
    let selectedDate: String
    let onOpenFullHistory: () -> Void

    private var groups: [TXRecentHistoryDay<StoreDailyRow>] {
        TXRecentLedgerHistoryPolicy.previousDays(
            model.snapshot.business, before: selectedDate, date: { $0.date })
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 9) {
            HStack(spacing: 6) {
                Button(action: onOpenFullHistory) {
                    HStack(spacing: 4) {
                        Text("营业历史").font(.headline)
                        Image(systemName: "chevron.right").font(.caption)
                    }
                }
                Spacer()
                Text("最近7个营业日")
                    .font(.caption2).foregroundColor(.secondary)
            }
            .padding(.top, 4)
            if groups.isEmpty {
                Text("所选日期之前暂无已同步营业记录")
                    .font(.caption).foregroundColor(.secondary)
            } else {
                ForEach(groups, id: \.date) { day in
                    VStack(alignment: .leading, spacing: 7) {
                        HStack(spacing: 6) {
                            Text(TXRecentLedgerHistoryPolicy.weekdayLabel(day.date))
                                .font(.subheadline.bold())
                            Spacer(minLength: 4)
                            Text("\(day.rows.count)个位置 · " +
                                 day.rows.reduce(0.0) { $0 + $1.revenue }
                                    .formatted(.currency(code: "CNY")))
                                .font(.caption.bold()).foregroundColor(TXTheme.primary)
                                .lineLimit(1).minimumScaleFactor(0.75)
                        }
                        ForEach(day.rows) { record in
                            HStack(spacing: 8) {
                                NavigationLink(destination: BusinessCloudDetailView(
                                    recordSyncID: record.id,
                                    bookID: model.selectedBookID ?? "")) {
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(record.storeName)
                                            .font(.subheadline.bold())
                                            .foregroundColor(.primary)
                                        if let channels = channelSummary(record) {
                                            Text(channels)
                                                .font(.caption2).foregroundColor(.secondary)
                                                .lineLimit(2)
                                        }
                                    }
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                }
                                .buttonStyle(.plain)
                                TXBusinessCustomerShortcut(recordSyncID: record.id)
                                NavigationLink(destination: BusinessCloudDetailView(
                                    recordSyncID: record.id,
                                    bookID: model.selectedBookID ?? "")) {
                                    HStack(spacing: 4) {
                                        Text(record.revenue.formatted(.currency(code: "CNY")))
                                            .font(.subheadline.bold()).foregroundColor(.primary)
                                        Image(systemName: "chevron.right")
                                            .font(.caption2).foregroundColor(.secondary)
                                    }
                                }
                                .buttonStyle(.plain)
                            }
                            if record.id != day.rows.last?.id { Divider() }
                        }
                    }
                    .padding(10)
                    .background(Color(UIColor.secondarySystemGroupedBackground),
                                in: RoundedRectangle(cornerRadius: 11))
                }
            }
            Text("按实际营业日分组；当天记录在上方。点位置查看安卓云端营业明细。")
                .font(.caption2).foregroundColor(.secondary)
        }
    }

    private func channelSummary(_ row: StoreDailyRow) -> String? {
        guard let payload = model.snapshot.records[
            "store_daily_record|" + row.id]?.payload,
              let wechat = payload["wechat_income"]?.decimal,
              let alipay = payload["alipay_income"]?.decimal,
              let cash = payload["cash_income"]?.decimal else { return nil }
        let channelText = "微信 " + wechat.formatted(.currency(code: "CNY")) +
            " · 支付宝 " + alipay.formatted(.currency(code: "CNY")) +
            " · 现金 " + cash.formatted(.currency(code: "CNY"))
        guard let event = model.snapshot.records["store_daily_record|" + row.id] else {
            return channelText
        }
        let splits = TestBusinessSplitInput.from(event)
        guard !splits.isEmpty else { return channelText }
        let people = splits.prefix(2).compactMap { split -> String? in
            guard let wx = Double(split.wechat),
                  let ali = Double(split.alipay),
                  let cash = Double(split.cash) else { return nil }
            let total = wx + ali + cash
            return split.partnerName + " " +
                total.formatted(.currency(code: "CNY"))
        }
        if people.isEmpty { return channelText }
        return channelText + " · " + people.joined(separator: " / ") +
            (splits.count > 2 ? " 等收款人" : "")
    }
}

/// Android's selected-day orders are already above this view. The seven
/// following groups are distinct prior PURCHASE days, not calendar days.
struct TXRecentPurchaseDaysView: View {
    @EnvironmentObject private var model: LedgerViewModel
    let selectedDate: String

    private var groups: [TXRecentHistoryDay<PurchaseOrderRow>] {
        TXRecentLedgerHistoryPolicy.previousDays(
            model.snapshot.purchases, before: selectedDate, date: { $0.date })
    }
    private var hasSelectedDayOrders: Bool {
        model.snapshot.purchases.contains { $0.date == selectedDate }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 9) {
            HStack {
                NavigationLink(destination: PurchasesHistoryView()) {
                    HStack(spacing: 4) {
                        Text("采购历史").font(.headline)
                        Image(systemName: "chevron.right").font(.caption)
                    }
                }
                Spacer()
                Text(hasSelectedDayOrders ? "当天 + 前7个采购日" : "最近7个采购日")
                    .font(.caption2).foregroundColor(.secondary)
            }
            .padding(.top, 4)
            if groups.isEmpty {
                Text("所选日期之前暂无已同步采购记录")
                    .font(.caption).foregroundColor(.secondary)
            } else {
                ForEach(groups, id: \.date) { day in
                    VStack(alignment: .leading, spacing: 7) {
                        HStack(spacing: 5) {
                            Text(TXRecentLedgerHistoryPolicy.weekdayLabel(day.date))
                                .font(.subheadline.bold())
                            Spacer(minLength: 4)
                            Text("\(day.rows.count)单 · " +
                                 day.rows.reduce(0.0) { $0 + $1.totalCost }
                                    .formatted(.currency(code: "CNY")))
                                .font(.caption.bold()).foregroundColor(TXTheme.primary)
                                .lineLimit(1).minimumScaleFactor(0.7)
                        }
                        ForEach(day.rows) { order in
                            NavigationLink(destination: PurchaseCloudDetailView(
                                order: order, bookID: model.selectedBookID ?? "")) {
                                VStack(alignment: .leading, spacing: 3) {
                                    HStack(spacing: 6) {
                                        Text(order.buyer)
                                            .font(.subheadline.bold())
                                            .foregroundColor(.primary)
                                        Spacer(minLength: 4)
                                        Text(order.totalCost.formatted(.currency(code: "CNY")))
                                            .font(.subheadline.bold())
                                            .foregroundColor(.primary)
                                        Image(systemName: "chevron.right")
                                            .font(.caption).foregroundColor(.secondary)
                                    }
                                    Text(orderItemPreview(order))
                                        .font(.caption2).foregroundColor(.secondary)
                                        .lineLimit(2)
                                }
                            }
                            .buttonStyle(.plain)
                            if order.id != day.rows.last?.id { Divider() }
                        }
                    }
                    .padding(10)
                    .background(Color(UIColor.secondarySystemGroupedBackground),
                                in: RoundedRectangle(cornerRadius: 11))
                }
            }
            Text("按实际采购日分组；当天订单在上方，点采购人查看完整商品明细。")
                .font(.caption2).foregroundColor(.secondary)
        }
    }

    private func orderItemPreview(_ order: PurchaseOrderRow) -> String {
        let result = CloudPurchaseDetail(order: order, snapshot: model.snapshot)
        guard !result.items.isEmpty else {
            return "商品明细待核对，点击查看云端原始数据"
        }
        let warning = result.status == .amountDiffers
            ? "订单与明细金额不一致 · "
            : (result.status == .matched ? "" : "商品明细未完全核对 · ")
        let parts = result.items.prefix(2).map { item -> String in
            let quantity = item.quantity.map { String(format: "%g", $0) } ?? "?"
            let amount = item.subtotal.map {
                $0.formatted(.currency(code: "CNY"))
            } ?? "金额待核对"
            return item.name + " " + quantity + item.unit + " · " + amount
        }
        return warning + parts.joined(separator: " / ") +
            (result.items.count > 2 ? " 等\(result.items.count)项" : "")
    }
}
