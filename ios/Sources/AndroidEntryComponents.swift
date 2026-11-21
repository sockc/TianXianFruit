import Foundation
import SwiftUI

/// Display-only receipt totals. Uses the same strict cents parser as business
/// upload. No settlement or cross-midnight deduction is performed here.
struct TXBusinessReceiptTotals: Equatable {
    let wechat: Int64
    let alipay: Int64
    let cash: Int64
    var combined: Int64 { wechat + alipay + cash }
}

enum TXBusinessReceiptPolicy {
    static func summary(_ lines: [BusinessCollectorDraftLine]) -> TXBusinessReceiptTotals? {
        guard !lines.isEmpty,
              let amounts = try? BusinessCollectorDraftPolicy.totals(lines) else { return nil }
        return TXBusinessReceiptTotals(
            wechat: amounts.wechat, alipay: amounts.alipay, cash: amounts.cash
        )
    }

    static func individual(_ line: BusinessCollectorDraftLine) -> Int64? {
        summary([line])?.combined
    }
}

/// Input proportions match the Android business form: channel amounts on
/// one row, numeric pad, right-aligned values, multi-collector breakdowns.
struct TXReceiptChannelInput: View {
    let label: String
    @Binding var value: String

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(label).font(.caption2).foregroundColor(.secondary)
            TXFastNumericField(text: $value, label: label + "收款金额")
                .frame(height: 36)
        }.frame(maxWidth: .infinity)
    }
}

struct TXBusinessReceiptSummaryView: View {
    let lines: [BusinessCollectorDraftLine]
    private var summary: TXBusinessReceiptTotals? {
        TXBusinessReceiptPolicy.summary(lines)
    }
    private func yuan(_ cents: Int64) -> String {
        (Double(cents) / 100.0).formatted(.currency(code: "CNY"))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("收款合计").font(.subheadline.bold())
                Spacer()
                if let summary {
                    Text(yuan(summary.combined)).font(.subheadline.bold())
                        .foregroundColor(TXTheme.primary)
                } else {
                    Text("输入待核对").font(.caption).foregroundColor(.orange)
                }
            }
            if let summary {
                HStack(spacing: 8) {
                    value("微信", cents: summary.wechat)
                    value("支付宝", cents: summary.alipay)
                    value("现金", cents: summary.cash)
                }
            } else {
                Text("请输入有效收款金额。无效或未完成的输入不会被当作零收入。")
                    .font(.caption2).foregroundColor(.secondary)
            }
        }
        .padding(10)
        .background(TXTheme.header, in: RoundedRectangle(cornerRadius: 10))
    }

    private func value(_ label: String, cents: Int64) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label).font(.caption2).foregroundColor(.secondary)
            Text(yuan(cents)).font(.caption.bold())
                .lineLimit(1).minimumScaleFactor(0.65)
        }.frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// Opens the real purchase remark editor from the test workbench. This is
/// navigation only: authentication, live row version and conflict protection
/// remain with PurchaseRemarkEditView and its existing coordinator.
struct TXPurchaseRemarkPickerView: View {
    @EnvironmentObject private var model: LedgerViewModel
    @State private var search = ""

    private var authorized: Bool {
        TXTestBookAccess.isCandidate(model.selectedBook, account: model.account,
                                     offline: model.isUsingCachedAuthorization)
    }
    private var purchases: [PurchaseOrderRow] {
        model.snapshot.purchases.filter { row in
            search.isEmpty || row.date.contains(search) ||
            row.buyer.localizedCaseInsensitiveContains(search)
        }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Label("采购备注更正 · 选择订单", systemImage: "note.text")
                    .font(.headline)
                Text("先从安卓已同步的采购订单选择一笔，再沿用原有独立测试账号验证。只允许修改备注，不改变订单商品和成本。")
                    .font(.caption).foregroundColor(.secondary)
                if !authorized {
                    Label("当前不是在线授权的 iOS_TEST_ 测试账本，禁止更正。",
                          systemImage: "lock.shield")
                        .foregroundColor(.orange)
                } else {
                    TextField("搜索日期或采购人", text: $search)
                        .textFieldStyle(.roundedBorder)
                    if purchases.isEmpty {
                        Text("没有已同步的采购订单，请先同步。")
                            .font(.caption).foregroundColor(.secondary)
                    }
                    LazyVStack(spacing: 8) {
                        ForEach(Array(purchases.prefix(80))) { order in
                            NavigationLink(destination: PurchaseRemarkEditView(
                                order: order, bookID: model.selectedBookID ?? "")) {
                                HStack {
                                    VStack(alignment: .leading, spacing: 3) {
                                        Text(order.date + " · " + order.buyer)
                                            .font(.subheadline.bold())
                                            .foregroundColor(.primary)
                                        Text("只能修改备注").font(.caption2)
                                            .foregroundColor(.secondary)
                                    }
                                    Spacer()
                                    Text(order.totalCost.formatted(.currency(code: "CNY")))
                                        .font(.subheadline).foregroundColor(.primary)
                                    Image(systemName: "chevron.right")
                                        .font(.caption).foregroundColor(.secondary)
                                }
                                .padding(11)
                                .background(Color(UIColor.secondarySystemGroupedBackground),
                                            in: RoundedRectangle(cornerRadius: 10))
                            }
                        }
                    }
                }
                SyncFooter()
            }.padding(12)
        }
        .navigationTitle("采购备注更正")
        .navigationBarTitleDisplayMode(.inline)
    }
}
