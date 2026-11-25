import SwiftUI

/// Android-style "导入库存 / 计划" selector; import only to a local draft.
/// No cloud records or settlement rows are mutated by this screen.
struct PurchaseImportPickerView: View {
    @Environment(\.dismiss) private var dismiss
    let date: String
    let buyerSyncID: String
    let events: [SyncEvent]
    let onImport: ([PurchaseImportCandidate]) -> Void

    @State private var source = 0
    @State private var selected = Set<String>()

    private var candidates: [PurchaseImportCandidate] {
        if source == 0 {
            return PurchaseImportPolicy.inventory(events, on: date)
        }
        return PurchaseImportPolicy.pendingPlan(
            events, on: date, buyerSyncID: buyerSyncID)
    }

    var body: some View {
        NavigationView {
            VStack(spacing: 10) {
                Picker("导入来源", selection: $source) {
                    Text("上次库存").tag(0)
                    Text("待采购计划").tag(1)
                }
                .pickerStyle(SegmentedPickerStyle())
                .padding(.horizontal, 12)
                .onChange(of: source) { _ in selected.removeAll() }

                if source == 0 {
                    Text("读取所选日期之前最近一次库存；库存剩余量仅作参考，导入后数量默认 1，请填写今天实际采购数量。")
                        .font(.caption)
                        .foregroundColor(.secondary)
                        .padding(.horizontal, 12)
                } else {
                    Text("仅显示所选采购人或未分配采购人的待采购计划。导入保留计划数量、规格和来源，但单价默认为 0；完成采购须由正式云端事务确认。")
                        .font(.caption)
                        .foregroundColor(.secondary)
                        .padding(.horizontal, 12)
                }

                HStack {
                    Text("可导入 " + String(candidates.count) + " 种")
                        .font(.subheadline.bold())
                    Spacer()
                    Button(selected.count == candidates.count ? "清空" : "全选") {
                        if selected.count == candidates.count {
                            selected.removeAll()
                        } else {
                            selected = Set(candidates.map(\.id))
                        }
                    }
                    .disabled(candidates.isEmpty)
                }
                .padding(.horizontal, 12)

                if candidates.isEmpty {
                    Spacer()
                    Text(source == 0 ?
                         "当前日期之前没有可匹配的库存记录" :
                         "当前日期该采购人没有可匹配的待采购计划")
                        .font(.callout)
                        .foregroundColor(.secondary)
                    Spacer()
                } else {
                    List {
                        ForEach(candidates) { item in
                            Button {
                                if selected.contains(item.id) {
                                    selected.remove(item.id)
                                } else {
                                    selected.insert(item.id)
                                }
                            } label: {
                                HStack(spacing: 10) {
                                    Image(systemName: selected.contains(item.id) ?
                                          "checkmark.circle.fill" : "circle")
                                        .foregroundColor(selected.contains(item.id) ?
                                                         .green : .secondary)
                                    VStack(alignment: .leading, spacing: 3) {
                                        Text(item.fruitName).foregroundColor(.primary)
                                            .font(.subheadline.bold())
                                        Text((source == 0 ? "上次剩余 " : "计划数量 ") +
                                             item.referenceQuantity + item.unit +
                                             " · " + item.source)
                                            .foregroundColor(.secondary)
                                            .font(.caption)
                                    }
                                    Spacer()
                                }
                            }
                        }
                    }
                    .listStyle(PlainListStyle())
                }

                Button {
                    let chosen = candidates.filter { selected.contains($0.id) }
                    onImport(chosen)
                    dismiss()
                } label: {
                    Text("导入选中 " + String(selected.count) + " 种商品")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .disabled(selected.isEmpty)
                .padding(.horizontal, 12)
                .padding(.bottom, 10)
            }
            .navigationTitle("导入采购商品")
            .navigationBarTitleDisplayMode(.inline)
            .navigationBarItems(leading: Button("取消") { dismiss() })
        }
        .navigationViewStyle(StackNavigationViewStyle())
    }
}
