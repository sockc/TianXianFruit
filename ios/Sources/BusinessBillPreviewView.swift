import SwiftUI
import UniformTypeIdentifiers

/// No write method, no customer push and no automatic changes to the draft.
struct TXBillPreviewEntry: View {
    @EnvironmentObject private var model: LedgerViewModel
    let scope: TXBusinessV2EditorScope
    @State private var show = false

    var body: some View {
        Button { show = true } label: {
            Label("账单补录预览", systemImage: "doc.text.magnifyingglass").font(.subheadline)
        }.buttonStyle(.bordered)
        .sheet(isPresented: $show) {
            TXBillPreviewView(scope: scope).environmentObject(model)
        }
    }
}

private struct TXBillPreviewView: View {
    @EnvironmentObject private var model: LedgerViewModel
    @Environment(\.dismiss) private var dismiss
    let scope: TXBusinessV2EditorScope
    @State private var partner = ""
    @State private var choose = false
    @State private var reading = false
    @State private var result: TXBillCSVResult?
    @State private var error: String?

    private var permitted: Bool { scope.permits(model.account, model.selectedBook) }
    private var rows: [TXBillPreviewRow] { result?.rows.filter { $0.date == scope.date } ?? [] }
    private var existingKeys: Set<String> { Set(model.customerSnapshot.events.values.filter { $0.kind == "RECEIPT" && !$0.isDeleted }.map(\.key)) }
    private func total(_ values: [TXBillPreviewRow]) -> String { "¥" + BusinessCommitPolicy.money(values.reduce(Int64(0)) { $0 + $1.net }) }
    private var nextDate: String? {
        guard let date = BusinessDates.parse(scope.date) else { return nil }
        return BusinessDates.calendar.date(byAdding: .day, value: 1, to: date).map(BusinessDates.key)
    }

    private func importFile(_ selected: Result<URL, Error>) {
        guard permitted, !reading, !partner.isEmpty else { return }
        result = nil; error = nil
        do {
            let url = try selected.get()
            let fileName = url.lastPathComponent
            reading = true
            Task {
                defer { reading = false }
                do {
                    let parsed = try await Task.detached(priority: .userInitiated) {
                        let accessed = url.startAccessingSecurityScopedResource()
                        defer { if accessed { url.stopAccessingSecurityScopedResource() } }
                        let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
                        guard size <= TXBillCSV.maximumBytes else { throw TXBillCSVError.tooLarge }
                        let data = try Data(contentsOf: url, options: .mappedIfSafe)
                        return try TXBillCSV.parse(fileName: fileName, data: data)
                    }.value
                    guard permitted else { return }
                    result = parsed
                } catch { self.error = error.localizedDescription }
            }
        } catch { self.error = error.localizedDescription }
    }

    private var summary: some View {
        Section(header: Text("所选自然日账单（未扣凌晨）")) {
            if let result {
                HStack { Text(result.platform.label + "净收款"); Spacer(); Text(total(rows)).bold() }
                HStack { Text("付款笔数"); Spacer(); Text("\(rows.filter { $0.cents > 0 && $0.net > 0 }.count)") }
                HStack { Text("与本机已同步交易键重合"); Spacer(); Text("\(rows.filter { existingKeys.contains($0.id) }.count)") }
                Text("整份文件：识别 \(result.rows.count) 条，忽略 \(result.ignored) 条，重复 \(result.duplicates) 条，待核对 \(result.review) 条。")
                    .font(.caption)
                let following = result.rows.filter { $0.date == nextDate && $0.hour == 0 }
                HStack { Text("次日00时交易（仅观察）"); Spacer(); Text(total(following)) }
                Text("凌晨以现有营业规则和来源核对为准；此处未分配营业位置，也不自动覆盖任何金额。")
                    .font(.caption).foregroundColor(.orange)
            } else { Text("请选择官方微信或支付宝 CSV 账单。").foregroundColor(.secondary) }
        }
    }
    var body: some View {
        NavigationView {
            Form {
                Section {
                    Text(scope.date + " · " + scope.storeName)
                    Picker("账单收款人", selection: $partner) {
                        Text("请选择").tag("")
                        ForEach(model.snapshot.testPartners) { Text($0.name).tag($0.id) }
                    }.disabled(reading)
                    Button { choose = true } label: {
                        Label(reading ? "正在解析" : "选择 CSV 账单", systemImage: "folder")
                    }.disabled(!permitted || partner.isEmpty || reading)
                    if let error { Text(error).font(.caption).foregroundColor(.red) }
                }
                summary
                Section(header: Text("注意")) {
                    Text("仅本机预览，不上传账单、不改现金、不改营业或客户数据。暂不支持 ZIP/XLSX 或缺失订单号的自动识别；覆盖完整性尚未验证。")
                        .font(.caption).foregroundColor(.secondary)
                }
            }
            .navigationTitle("账单补录预览")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("关闭") { dismiss() } } }
            .fileImporter(isPresented: $choose, allowedContentTypes: [.commaSeparatedText, .plainText], onCompletion: importFile)
            .onChange(of: partner) { _ in result = nil; error = nil }
            .onChange(of: permitted) { allowed in if !allowed { result = nil; dismiss() } }
        }.navigationViewStyle(.stack)
    }
}
