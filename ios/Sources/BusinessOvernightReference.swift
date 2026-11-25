import Foundation
import SwiftUI

/// Read-only Android customer-analytics protocol. A morning is identified by
/// natural date and collector, never by transport store. Unknown is not zero.
enum TXBusinessOvernightReferencePolicy {
    enum Morning: Equatable {
        case missing, invalid, recorded(Amounts)
    }
    struct Amounts: Equatable {
        let wechat: Int64?
        let alipay: Int64?
        let cash: Int64?
        var hasRecordedAmount: Bool { wechat != nil || alipay != nil || cash != nil }
    }
    struct Reference: Equatable {
        let previous: Morning
        let following: Morning
        let followingDate: String
    }
    private static func cents(_ value: JSONValue?) -> Int64? {
        guard let value else { return nil }
        let n: Double
        switch value {
        case .number(let x): n = x
        case .exactInteger(let x): n = Double(x)
        default: return nil
        }
        guard n.isFinite, n >= 0, n <= 100_000_000 else { return nil }
        let scaled = n * 100
        guard abs(scaled - scaled.rounded()) < 0.00001 else { return nil }
        return Int64(scaled.rounded())
    }

    static func morning(_ events: [CustomerChangeEvent], date: String, partner: String) -> Morning {
        guard !partner.isEmpty, let parsed = BusinessDates.parse(date),
              BusinessDates.key(parsed) == date else { return .invalid }
        let key = date + "|" + partner
        let relevant = events.filter {
            $0.kind == "OVERNIGHT" && !$0.isDeleted &&
            ($0.key == key || ($0.payload["date"] == .string(date) &&
             $0.payload["partner_sync_id"] == .string(partner)))
        }
        guard !relevant.isEmpty else { return .missing }
        guard relevant.count == 1, let e = relevant.first, e.key == key,
              e.revision > 0,
              e.payload["date"] == .string(date),
              e.payload["partner_sync_id"] == .string(partner) else { return .invalid }
        var result: [Int64?] = []
        for name in ["wechat","alipay","cash"] {
            guard case .bool(let recorded)? = e.payload[name + "_recorded"],
                  let amount = cents(e.payload[name]) else { return .invalid }
            // An unconfirmed non-zero amount is not usable as a reference.
            guard recorded || amount == 0 else { return .invalid }
            result.append(recorded ? amount : nil)
        }
        return .recorded(Amounts(wechat: result[0], alipay: result[1], cash: result[2]))
    }

    static func reference(_ events: [CustomerChangeEvent], businessDate: String, partner: String) -> Reference? {
        guard let date = BusinessDates.parse(businessDate), BusinessDates.key(date) == businessDate,
              let following = BusinessDates.calendar.date(byAdding: .day, value: 1, to: date) else { return nil }
        let next = BusinessDates.key(following)
        return Reference(previous: morning(events, date: businessDate, partner: partner),
                         following: morning(events, date: next, partner: partner),
                         followingDate: next)
    }
}

struct TXBusinessOvernightReferenceView: View {
    @EnvironmentObject private var model: LedgerViewModel
    let date: String
    let partner: String
    private func money(_ value: Int64?) -> String {
        value.map { (Double($0)/100).formatted(.currency(code: "CNY")) } ?? "未确认"
    }
    @ViewBuilder private func row(_ title: String, morning: TXBusinessOvernightReferencePolicy.Morning) -> some View {
        switch morning {
        case .missing:
            EmptyView()
        case .invalid:
            Text(title + "：云端来源异常，请在安卓核对").font(.caption2).foregroundColor(.orange)
        case .recorded(let amounts):
            if amounts.hasRecordedAmount {
                HStack(spacing: 4) {
                    Text(title).font(.caption2).foregroundColor(.secondary)
                    ForEach(Array([amounts.wechat, amounts.alipay, amounts.cash].enumerated()), id: \.offset) { item in
                        Text(money(item.element)).font(.caption2).lineLimit(1).minimumScaleFactor(0.75)
                            .frame(maxWidth: .infinity)
                    }
                }
                .accessibilityLabel(title + "，微信 " + money(amounts.wechat) + "，支付宝 " + money(amounts.alipay) + "，现金 " + money(amounts.cash))
            }
        }
    }

    var body: some View {
        if !partner.isEmpty {
            VStack(alignment: .leading, spacing: 3) {
                if let reference = TXBusinessOvernightReferencePolicy.reference(
                    Array(model.customerSnapshot.events.values), businessDate: date, partner: partner),
                   model.customerSyncedAt != nil, model.customerOvernightCovered {
                    row("昨日凌晨", morning: reference.previous)
                    row("本次凌晨", morning: reference.following)
                    if reference.previous != .missing || reference.following != .missing {
                        Text("仅供核对，未自动扣减或计入营业额；请输入已经核对的实际营业收款。")
                            .font(.caption2).foregroundColor(.secondary)
                    }
                }
                if model.customerError != nil || model.isUsingCachedAuthorization || !model.customerAPIAvailable || !model.customerOvernightCovered {
                    Text("凌晨云端来源未完成在线核验；未显示不等于零。")
                        .font(.caption2).foregroundColor(.orange)
                }
            }
        }
    }
}
