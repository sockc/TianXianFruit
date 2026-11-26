import SwiftUI
import UniformTypeIdentifiers

/// Preview only: no draft mutation, no customer push.
struct TXBillPreviewEntry: View {
    @EnvironmentObject private var model: LedgerViewModel
    let scope: TXBusinessV2EditorScope
    @State private var show = false
    var body: some View {
        Button { show = true } label: {
            Label("账单补录预览", systemImage: "doc.text.magnifyingglass").font(.subheadline)
        }.buttonStyle(.bordered)
        .sheet(isPresented: $show) { TXBillPreviewView(scope: scope).environmentObject(model) }
    }
}

private struct TXBillPreviewView: View {
    @EnvironmentObject private var model: LedgerViewModel
    @Environment(\.dismiss) private var dismiss
    let scope: TXBusinessV2EditorScope
    @State private var partner = ""
    @State private var choose = false
    @State private var reading = false
    @State private var files: [TXBillCSVResult] = []
    @State private var batch: TXBillBatchPreview?
    @State private var error: String?
    @State private var selectedDayOffset = 0

    private var permitted: Bool { scope.permits(model.account, model.selectedBook) }
    private var selectedDate: String {
        guard let date = BusinessDates.parse(scope.date),
              let shifted = BusinessDates.calendar.date(byAdding: .day, value: selectedDayOffset, to: date) else { return scope.date }
        return BusinessDates.key(shifted)
    }
    private var rows: [TXBillPreviewRow] {
        (batch?.rows ?? []).filter { $0.date == selectedDate && (selectedDayOffset == 0 || $0.hour == 0) }
    }
    private func total(_ values: [TXBillPreviewRow]) -> String {
        "¥" + BusinessCommitPolicy.money(values.reduce(Int64(0)) { $0 + $1.net })
    }
    private var auditCounts: [TXBillCloudMatch: Int] {
        guard let batch else { return [:] }
        let audit = TXBillCloudAudit(events: Array(model.customerSnapshot.events.values))
        return rows.reduce(into: [:]) { counts, row in
            let state = audit.match(row, selectedPartner: partner, fileHashes: batch.sourceHashes[row.id] ?? [])
            counts[state, default: 0] += 1
        }
    }
    private var snapshotReady: Bool {
        model.customerAPIAvailable && model.customerError == nil && model.customerSyncedAt != nil &&
        !model.isCustomerBusy && !model.isUsingCachedAuthorization
    }
    private func importFile(_ selected: Result<[URL], Error>) {
        guard permitted, !reading, !partner.isEmpty else { return }
        error = nil
        do {
            let urls = try selected.get()
            guard !urls.isEmpty, urls.count + files.count <= 12 else { throw TXBillCSVError.tooLarge }
            let chosenPartner = partner
            reading = true
            Task {
                defer { reading = false }
                do {
                    let parsed = try await Task.detached(priority: .userInitiated) {
                        var result: [TXBillCSVResult] = []
                        for url in urls {
                            let accessed = url.startAccessingSecurityScopedResource()
                            defer { if accessed { url.stopAccessingSecurityScopedResource() } }
                            let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
                            guard size <= TXBillCSV.maximumBytes else { throw TXBillCSVError.tooLarge }
                            let data = try Data(contentsOf: url, options: .mappedIfSafe)
                            result.append(try TXBillCSV.parse(fileName: url.lastPathComponent, data: data))
                        }
                        return result
                    }.value
                    guard permitted, chosenPartner == partner else { return }
                    let combined = try TXBillBatchPreview(files + parsed)
                    files = combined.files
                    batch = combined
                } catch { self.error = error.localizedDescription }
            }
        } catch { self.error = error.localizedDescription }
    }

    private var summary: some View {
        Section(header: Text(selectedDate + (selectedDayOffset == 0 ? " 自然日（未扣凌晨）" : " 00:00–00:59（仅观察）"))) {
            Picker("范围", selection: $selectedDayOffset) {
                Text("本自然日").tag(0); Text("次日凌晨").tag(1)
            }.pickerStyle(.segmented)
            ForEach(TXBillPlatform.allCases, id: \.self) { platform in
                let items = rows.filter { $0.platform == platform }
                HStack {
                    Text(platform.label + "净收款")
                    Spacer()
                    Text(items.isEmpty ? "无观察记录" : total(items)).bold()
                }
            }
            HStack { Text("付款笔数"); Spacer(); Text("\(rows.filter { $0.cents > 0 && $0.net > 0 }.count)") }
            if let batch {
                Text("\(batch.files.count) 份有效文件，跨文件重复 \(batch.duplicateRows) 笔，重复文件 \(batch.duplicateFiles) 份。")
                    .font(.caption)
                Text("文件内重复 \(batch.files.reduce(0) { $0 + $1.duplicates }) 笔，异常待核对 \(batch.files.reduce(0) { $0 + $1.review }) 笔。")
                    .font(.caption)
            }
        }
    }
    private var reconciliation: some View {
        Section(header: Text("云端交易与收款人归属")) {
            if !snapshotReady {
                Text("云端客户记录尚未在线核验，以下仅为本机缓存观察。").font(.caption).foregroundColor(.orange)
            }
            let counts = auditCounts
            ForEach(TXBillCloudMatch.allCases, id: \.self) { state in
                HStack { Text(state.label); Spacer(); Text("\(counts[state, default: 0]) 笔") }
            }
            Button("刷新客户记录") { Task { await model.refreshCustomer(forceFull: true) } }
                .disabled(model.isCustomerBusy || !permitted || reading)
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
                    }.disabled(reading || !files.isEmpty)
                    Button { choose = true } label: {
                        Label(reading ? "正在解析" : "添加账单文件", systemImage: "folder")
                    }.disabled(!permitted || partner.isEmpty || reading || files.count >= 12)
                    if !files.isEmpty {
                        Button("清空本次预览", role: .destructive) { files = []; batch = nil; error = nil }.disabled(reading)
                    }
                    if let error { Text(error).font(.caption).foregroundColor(.red) }
                }
                summary
                reconciliation
                Section(header: Text("注意")) {
                    Text("仅本机预览，不上传、不覆盖电子收款、不改变现金或客户交易。支持 CSV、单账单 ZIP、单工作表 XLSX；加密 ZIP、多个候选文件、公式及不可靠数字订单号需先在官方渠道重新导出。没有交易不代表收入为零或账单覆盖完整。")
                        .font(.caption).foregroundColor(.secondary)
                }
            }
            .navigationTitle("账单补录预览")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("关闭") { dismiss() }.disabled(reading) } }
            .fileImporter(isPresented: $choose, allowedContentTypes: [.commaSeparatedText,.plainText,.zip,UTType(filenameExtension: "xlsx") ?? .data], allowsMultipleSelection: true, onCompletion: importFile)
            .onChange(of: partner) { _ in files = []; batch = nil; error = nil }
            .onChange(of: permitted) { allowed in if !allowed { files = []; batch = nil; dismiss() } }
        }.navigationViewStyle(.stack)
    }
}
