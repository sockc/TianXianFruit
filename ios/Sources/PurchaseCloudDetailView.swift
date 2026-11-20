import SwiftUI

/// No mutation endpoints: safe to inspect both live read-only books and
/// isolated iOS_TEST_ accounts. Switching books invalidates this screen.
struct PurchaseCloudDetailView: View {
    @EnvironmentObject private var model: LedgerViewModel
    let order: PurchaseOrderRow
    let bookID: String

    private var correctBook: Bool { model.selectedBookID == bookID }
    private var detail: CloudPurchaseDetail {
        CloudPurchaseDetail(order: order, snapshot: model.snapshot)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 13) {
                if !correctBook {
                    Label("当前账本已切换，请返回采购列表重新进入", systemImage: "exclamationmark.shield")
                        .foregroundColor(.orange)
                } else {
                    VStack(alignment: .leading, spacing: 7) {
                        Text(order.date + " · " + order.buyer).font(.headline)
                        Text("采购订单：" + order.totalCost.formatted(.currency(code: "CNY")))
                            .font(.title3.bold())
                        Text("云端记录只读 · 不会改变 Android 采购、库存或结算")
                            .font(.caption2).foregroundColor(.secondary)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    let result = detail
                    VStack(alignment: .leading, spacing: 8) {
                        Label(statusTitle(result.status),
                              systemImage: result.status == .matched
                                ? "checkmark.circle.fill" : "exclamationmark.triangle")
                            .foregroundColor(result.status == .matched ? .green : .orange)
                            .font(.subheadline.bold())
                        Text(result.note).font(.caption).foregroundColor(.secondary)
                        if let itemTotal = result.itemTotal {
                            HStack {
                                Text("商品明细合计")
                                Spacer()
                                Text(itemTotal.formatted(.currency(code: "CNY")))
                            }.font(.subheadline)
                        }
                        if result.status == .amountDiffers,
                           let difference = result.difference {
                            Text("订单 - 明细：" + difference.formatted(.currency(code: "CNY")))
                                .font(.caption.bold()).foregroundColor(.orange)
                        }
                    }
                    .padding(12)
                    .background(Color(UIColor.secondarySystemGroupedBackground),
                                in: RoundedRectangle(cornerRadius: 12))
                    if model.selectedBook?.name.hasPrefix(TestWritePolicy.prefix) == true {
                        NavigationLink(destination: PurchaseRemarkEditView(
                            order: order, bookID: bookID)) {
                            Label("修改测试账本采购备注（不改金额）",
                                  systemImage: "square.and.pencil")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.bordered)
                    }
                    Text("云端商品明细 · \(result.items.count) 条").font(.headline)
                    if result.items.isEmpty {
                        Text("暂无可安全归属的商品明细")
                            .font(.caption).foregroundColor(.secondary)
                    }
                    ForEach(result.items) { item in
                        VStack(alignment: .leading, spacing: 6) {
                            HStack {
                                Text(item.name).font(.subheadline.bold())
                                Spacer()
                                Text(item.subtotal.map {
                                    $0.formatted(.currency(code: "CNY"))
                                } ?? "金额未提供").font(.subheadline)
                            }
                            HStack {
                                Text("数量：" + (item.quantity.map {
                                    String(format: "%g", $0)
                                } ?? "未知") +
                                (item.unit.isEmpty ? "" : " " + item.unit))
                                Spacer()
                                Text("单价：" + (item.unitPrice.map {
                                    $0.formatted(.currency(code: "CNY"))
                                } ?? "未知"))
                            }.font(.caption).foregroundColor(.secondary)
                        }
                        .padding(12)
                        .background(Color(UIColor.secondarySystemGroupedBackground),
                                    in: RoundedRectangle(cornerRadius: 12))
                    }
                    Button {
                        Task { await model.refresh() }
                    } label: {
                        Label(model.isBusy ? "正在读取云端…" : "刷新云端采购",
                              systemImage: "arrow.clockwise")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                    .disabled(model.isBusy)
                    Text(model.lastSyncAt.map {
                        "最近成功读取：\($0.formatted(date: .abbreviated, time: .shortened))"
                    } ?? "尚未完成云端同步")
                        .font(.caption2).foregroundColor(.secondary)
                    if let error = model.errorMessage {
                        Text("同步未成功：" + error)
                            .font(.caption).foregroundColor(.orange)
                    }
                }
            }
            .padding(12)
        }
        .navigationTitle("采购商品明细")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func statusTitle(_ status: CloudPurchaseDetail.Status) -> String {
        switch status {
        case .matched: return "云端商品金额核对一致"
        case .amountDiffers: return "商品合计与订单金额不同"
        case .incomplete: return "明细尚不完整"
        case .ambiguous: return "商品归属无法确认"
        }
    }
}