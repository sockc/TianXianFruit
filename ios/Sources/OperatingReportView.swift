import SwiftUI
import UIKit

private struct SharedOperatingReport: Identifiable {
    let id = UUID()
    let url: URL
}

private struct ReportActivitySheet: UIViewControllerRepresentable {
    let url: URL

    func makeUIViewController(context: Context) -> UIActivityViewController {
        let activity = UIActivityViewController(activityItems: [url], applicationActivities: nil)
        // Present as a sheet on iPhone X, without enabling files in the
        // public application Documents directory.
        return activity
    }

    func updateUIViewController(_ view: UIActivityViewController, context: Context) {}
}

/// Business-date grouped, position-aware export: stores are never assigned
/// purchases by guessing, and no settlement or accounting rows are written.
struct OperatingReportView: View {
    @EnvironmentObject private var model: LedgerViewModel
    @State private var days = 30
    @State private var location = ""
    @State private var errorMessage: String?
    @State private var exporting = false
    @State private var shared: SharedOperatingReport?

    private var end: String { BusinessDates.key(Date()) }
    private var start: String { BusinessDates.daysAgo(days - 1) }
    private var lines: [OperatingReportLine] {
        OperatingReport.lines(snapshot: model.snapshot,
                              book: model.selectedBook?.name ?? "天鲜账本",
                              start: start, end: end, storeName: location)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 13) {
                Label("天鲜果业 · 只读经营报表", systemImage: "doc.text")
                    .font(.headline)
                HStack(spacing: 8) {
                    Picker("周期", selection: $days) {
                        Text("近7天").tag(7)
                        Text("近30天").tag(30)
                        Text("近90天").tag(90)
                    }
                    .pickerStyle(SegmentedPickerStyle())
                    Picker("营业位置", selection: $location) {
                        Text("全部位置").tag("")
                        ForEach(model.snapshot.locations, id: \.self) { name in
                            Text(name).tag(name)
                        }
                    }
                    .pickerStyle(MenuPickerStyle())
                }
                Text("\(start) 至 \(end)")
                    .font(.caption).foregroundColor(.secondary)
                let sales = model.snapshot.sales(since: start, until: end, location: location)
                let purchases = model.snapshot.purchases(since: start, until: end)
                VStack(alignment: .leading, spacing: 10) {
                    reportMetric("营业额", sales.reduce(0) { $0 + $1.revenue })
                    reportMetric("已存利润", sales.reduce(0) { $0 + $1.profit })
                    reportMetric(location.isEmpty ? "采购金额" : "采购金额（全账本）",
                                 purchases.reduce(0) { $0 + $1.totalCost })
                    Text("营业记录：\(sales.count) 条 · 采购订单：\(purchases.count) 条")
                        .font(.caption).foregroundColor(.secondary)
                }
                .padding(12)
                .background(Color(UIColor.secondarySystemGroupedBackground),
                            in: RoundedRectangle(cornerRadius: 12))
                if let errorMessage {
                    Label(errorMessage, systemImage: "exclamationmark.triangle")
                        .font(.caption).foregroundColor(.red)
                }
                HStack(spacing: 12) {
                    Button {
                        create("pdf")
                    } label: {
                        Label("导出 PDF", systemImage: "doc.richtext")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    Button {
                        create("png")
                    } label: {
                        Label("导出图片", systemImage: "photo")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                }
                .disabled(exporting || lines.isEmpty)
                Text("导出文件通过 iOS 系统分享菜单自行保存，App 不上传报表，也不公开家庭经营数据。生成时间显示在每页右下角；PDF 支持多页，图片过长时提示改用 PDF。")
                    .font(.caption2).foregroundColor(.secondary)
                Text("当前筛选单位置时，采购金额仍属于全部位置的采购订单，不会错误分摊为此位置采购成本。")
                    .font(.caption2).foregroundColor(.secondary)
            }
            .padding(12)
        }
        .navigationBarTitle("导出经营报表", displayMode: .inline)
        .sheet(item: $shared) { item in
            ReportActivitySheet(url: item.url)
        }
    }

    private func reportMetric(_ title: String, _ amount: Double) -> some View {
        HStack {
            Text(title).font(.subheadline)
            Spacer()
            Text(amount.formatted(.currency(code: "CNY")))
                .font(.subheadline.bold()).minimumScaleFactor(0.7)
        }
    }

    private func create(_ kind: String) {
        guard !exporting else { return }
        exporting = true
        errorMessage = nil
        defer { exporting = false }
        do {
            let content = lines
            let url = try kind == "pdf"
                ? OperatingReport.pdf(content)
                : OperatingReport.png(content)
            shared = SharedOperatingReport(url: url)
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}
