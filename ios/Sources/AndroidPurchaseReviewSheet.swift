import SwiftUI

/// User-entered comparison, not an automated Android device connection.
/// Blank defaults are intentional so the reviewer must inspect Android.
struct AndroidPurchaseReviewSheet: View {
    @Environment(\.dismiss) private var dismiss

    let job: StagingPurchaseJob
    let audit: PurchaseReadbackAudit
    let onConfirm: (AndroidPurchaseObservation) -> Bool

    @State private var date = ""
    @State private var buyerName = ""
    @State private var fruitName = ""
    @State private var unit = ""
    @State private var quantity = ""
    @State private var totalCost = ""
    @State private var didCheckAndroid = false
    @State private var errorText: String?

    private var observation: AndroidPurchaseObservation {
        AndroidPurchaseObservation(
            date: date, buyerName: buyerName, fruitName: fruitName,
            unit: unit, quantity: quantity, totalCost: totalCost)
    }

    var body: some View {
        NavigationView {
            ScrollView {
                VStack(alignment: .leading, spacing: 13) {
                    Label("Android 实际数据核对", systemImage: "checkmark.shield")
                        .font(.headline)
                    Text("先在 Android 打开同一 iOS_TEST_ 测试账本中的这笔采购，再将 Android 屏幕显示的内容逐项手动输入。下面不会自动填写 iOS 的预期数据。")
                        .font(.caption)
                        .foregroundColor(.secondary)

                    VStack(alignment: .leading, spacing: 6) {
                        Text("测试账本 ID").font(.caption.bold())
                        Text(job.bookID).font(.caption2)
                            .foregroundColor(.secondary).textSelection(.enabled)
                        Text("采购同步 ID").font(.caption.bold())
                        Text(job.orderSyncID).font(.caption2)
                            .foregroundColor(.secondary).textSelection(.enabled)
                        Text("云端对账：" + audit.checkedAt.formatted(
                            date: .abbreviated, time: .shortened))
                            .font(.caption2).foregroundColor(.secondary)
                    }
                    .padding(10)
                    .background(Color(UIColor.secondarySystemGroupedBackground),
                                in: RoundedRectangle(cornerRadius: 10))

                    entry("Android 显示的采购日期（YYYY-MM-DD）", value: $date)
                    entry("Android 显示的采购人", value: $buyerName)
                    entry("Android 显示的商品", value: $fruitName)
                    entry("Android 显示的单位", value: $unit)
                    entry("Android 显示的数量", value: $quantity, numeric: true)
                    entry("Android 显示的总金额（元）", value: $totalCost, numeric: true)

                    Toggle("我已在 Android 的同一个测试账本亲自检查上述六项",
                           isOn: $didCheckAndroid)
                        .font(.caption)
                    if let errorText {
                        Label(errorText, systemImage: "exclamationmark.triangle")
                            .font(.caption)
                            .foregroundColor(.orange)
                    }
                    Button {
                        do {
                            _ = try AndroidPurchaseReviewPolicy.confirm(
                                job: job, audit: audit, observation: observation)
                            if onConfirm(observation) {
                                dismiss()
                            } else {
                                errorText = "保存失败，请检查页面提示或刷新云端核对"
                            }
                        } catch {
                            errorText = error.localizedDescription
                        }
                    } label: {
                        Label("核对六项并保存本机验收记录",
                              systemImage: "checkmark.circle")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(!didCheckAndroid)
                    Text("此确认仅写入本机 SQLite 草稿，不会操作 Android 或服务器。云端记录后来发生变化时，验收记录会标为需要复核，并阻止后续采购解锁。")
                        .font(.caption2)
                        .foregroundColor(.secondary)
                }
                .padding(14)
            }
            .navigationBarTitle("采购人工验收", displayMode: .inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("取消") { dismiss() }
                }
            }
        }
    }

    private func entry(_ title: String, value: Binding<String>,
                       numeric: Bool = false) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(title).font(.caption)
            TextField("从 Android 手动填写", text: value)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .keyboardType(numeric ? .decimalPad : .default)
                .font(.subheadline)
                .padding(9)
                .background(Color(UIColor.secondarySystemGroupedBackground),
                            in: RoundedRectangle(cornerRadius: 8))
        }
    }
}
