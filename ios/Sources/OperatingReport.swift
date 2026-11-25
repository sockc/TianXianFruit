import Foundation
import UIKit

struct OperatingReportLine {
    enum Style { case title, section, normal, note, total }
    let title: String
    let value: String
    let style: Style

    init(_ title: String, _ value: String = "", style: Style = .normal) {
        self.title = title
        self.value = value
        self.style = style
    }
}

enum OperatingReportError: LocalizedError {
    case noData
    case imageTooLong
    var errorDescription: String? {
        switch self {
        case .noData: return "所选日期内没有已同步的营业/采购记录，未导出空报表"
        case .imageTooLong: return "图片报表内容过长，请选择 PDF 多页导出"
        }
    }
}

/// A presentation-only export. Neither SQL data nor remote ledger rows are
/// modified. All financial values come from already-synced Android records.
enum OperatingReport {
    static func lines(snapshot: ReadOnlySnapshot, book: String,
                      start: String, end: String, storeName: String = "") -> [OperatingReportLine] {
        let business = snapshot.sales(since: start, until: end, location: storeName)
        let purchases = snapshot.purchases(since: start, until: end)
        guard !business.isEmpty || !purchases.isEmpty else { return [] }

        let amount: (Double) -> String = { $0.formatted(.currency(code: "CNY")) }
        let label = storeName.isEmpty ? "全部营业位置" : "营业位置：" + storeName
        var lines = [
            OperatingReportLine("天鲜果业 · 经营汇总", style: .title),
            OperatingReportLine(book + " · " + label, style: .note),
            OperatingReportLine(start + " 至 " + end, style: .note),
            OperatingReportLine("营业额合计", amount(business.reduce(0) { $0 + $1.revenue }), style: .total),
            OperatingReportLine("已存利润合计", amount(business.reduce(0) { $0 + $1.profit }), style: .total),
            OperatingReportLine(storeName.isEmpty ? "采购金额合计" : "采购金额（全账本，不分摊到位置）",
                                amount(purchases.reduce(0) { $0 + $1.totalCost }), style: .total),
            OperatingReportLine("经营记录来自云端正式营业/采购表，不重新结算利润", style: .note)
        ]
        let dates = Set(business.map(\.date)).union(Set(purchases.map(\.date))).sorted(by: >)
        for date in dates {
            let daySales = business.filter { $0.date == date }
            let dayPurchases = purchases.filter { $0.date == date }
            let dayRevenue = daySales.reduce(0) { $0 + $1.revenue }
            let dayProfit = daySales.reduce(0) { $0 + $1.profit }
            let dayCost = dayPurchases.reduce(0) { $0 + $1.totalCost }
            lines.append(OperatingReportLine(date + " · 每日经营", style: .section))
            for row in daySales {
                lines.append(OperatingReportLine(row.storeName + "  营业",
                                                 amount(row.revenue)))
                lines.append(OperatingReportLine("  已存利润", amount(row.profit), style: .note))
            }
            lines.append(OperatingReportLine("当天营业额合计", amount(dayRevenue), style: .total))
            lines.append(OperatingReportLine("当天已存利润", amount(dayProfit), style: .normal))
            lines.append(OperatingReportLine("当天采购金额（全账本）", amount(dayCost), style: .normal))
        }
        lines.append(OperatingReportLine("说明：采购金额按采购日汇总；同日多位置营业逐条展示，采购不按摊位拆分。", style: .note))
        return lines
    }

    private static let bannerGreen = UIColor(red: 19/255, green: 168/255, blue: 104/255, alpha: 1)
    private static let muted = UIColor.darkGray
    private static let dark = UIColor.black

    private static func outputURL(ext: String) throws -> URL {
        let root = FileManager.default.urls(for: .cachesDirectory,
                                             in: .userDomainMask)[0]
            .appendingPathComponent("ReadOnlyReports", isDirectory: true)
        try FileManager.default.createDirectory(at: root,
                                                withIntermediateDirectories: true)
        let old = (try? FileManager.default.contentsOfDirectory(at: root,
                            includingPropertiesForKeys: [.contentModificationDateKey])) ?? []
        for file in old {
            let modified = try? file.resourceValues(forKeys: [.contentModificationDateKey])
                .contentModificationDate
            if let modified, Date().timeIntervalSince(modified) > 86400 {
                try? FileManager.default.removeItem(at: file)
            }
        }
        let name = "TianXian-ReadOnly-" + UUID().uuidString + "." + ext
        return root.appendingPathComponent(name)
    }

    private static func heading(_ ctx: CGContext, width: CGFloat) {
        ctx.setFillColor(bannerGreen.cgColor)
        ctx.fill(CGRect(x: 0, y: 0, width: width, height: 68))
        let font: [NSAttributedString.Key: Any] = [
            .font: UIFont.boldSystemFont(ofSize: 22), .foregroundColor: UIColor.white
        ]
        ("天鲜果业" as NSString).draw(in: CGRect(x: 32, y: 17, width: width - 64, height: 32),
                                     withAttributes: font)
    }

    private static func footer(_ ctx: CGContext, width: CGFloat, height: CGFloat,
                               page: Int, stamp: String) {
        ctx.setStrokeColor(UIColor.lightGray.cgColor)
        ctx.setLineWidth(0.5)
        ctx.move(to: CGPoint(x: 32, y: height - 43))
        ctx.addLine(to: CGPoint(x: width - 32, y: height - 43))
        ctx.strokePath()
        let attrs: [NSAttributedString.Key: Any] = [
            .font: UIFont.systemFont(ofSize: 9), .foregroundColor: muted
        ]
        ("只读报表 · 第 \(page) 页" as NSString).draw(
            in: CGRect(x: 32, y: height - 33, width: width / 2, height: 16),
            withAttributes: attrs)
        (("生成时间：" + stamp) as NSString).draw(
            in: CGRect(x: width / 2 - 70, y: height - 33,
                       width: width / 2 + 38, height: 16),
            withAttributes: attrs)
    }

    private static func line(_ data: OperatingReportLine, width: CGFloat,
                             top: CGFloat, scale: CGFloat = 1) {
        let titleSize: CGFloat = data.style == .title ? 15 :
                                  (data.style == .section ? 13 : (data.style == .note ? 9 : 11))
        let bold = data.style == .title || data.style == .section ||
                   data.style == .total
        let color: UIColor = data.style == .note ? muted : dark
        let left: [NSAttributedString.Key: Any] = [
            .font: bold ? UIFont.boldSystemFont(ofSize: titleSize * scale) :
                          UIFont.systemFont(ofSize: titleSize * scale),
            .foregroundColor: color
        ]
        let right: [NSAttributedString.Key: Any] = [
            .font: UIFont.systemFont(ofSize: 11 * scale,
                                    weight: data.style == .total ? .semibold : .regular),
            .foregroundColor: dark
        ]
        let margin = 32 * scale
        (data.title as NSString).draw(in: CGRect(x: margin, y: top,
             width: data.value.isEmpty ? width - 2 * margin : (width - 2 * margin) * 0.66,
             height: 24 * scale), withAttributes: left)
        if !data.value.isEmpty {
            (data.value as NSString).draw(in: CGRect(
                x: width * 0.66, y: top, width: width * 0.34 - margin,
                height: 24 * scale), withAttributes: right)
        }
    }

    static func pdf(_ lines: [OperatingReportLine]) throws -> URL {
        guard !lines.isEmpty else { throw OperatingReportError.noData }
        let width: CGFloat = 595
        let height: CGFloat = 842
        let format = UIGraphicsPDFRendererFormat()
        let renderer = UIGraphicsPDFRenderer(
            bounds: CGRect(x: 0, y: 0, width: width, height: height),
            format: format)
        let timestamp = Date().formatted(date: .abbreviated, time: .shortened)
        let data = renderer.pdfData { ctx in
            var page = 1
            var y: CGFloat = 90
            ctx.beginPage()
            heading(ctx.cgContext, width: width)
            for item in lines {
                let step: CGFloat = item.style == .section ? 30 : 25
                if y + step > height - 60 {
                    footer(ctx.cgContext, width: width, height: height,
                           page: page, stamp: timestamp)
                    page += 1
                    ctx.beginPage()
                    heading(ctx.cgContext, width: width)
                    y = 90
                }
                line(item, width: width, top: y)
                y += step
            }
            footer(ctx.cgContext, width: width, height: height,
                   page: page, stamp: timestamp)
        }
        let url = try outputURL(ext: "pdf")
        try data.write(to: url, options: .atomic)
        return url
    }

    static func png(_ lines: [OperatingReportLine]) throws -> URL {
        guard !lines.isEmpty else { throw OperatingReportError.noData }
        guard lines.count <= 120 else { throw OperatingReportError.imageTooLong }
        // A bounded single-page export suitable for an iPhone X.
        let width: CGFloat = 720
        let scale: CGFloat = 1
        let height = CGFloat(120 + lines.count * 30 + 65)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let renderer = UIGraphicsImageRenderer(
            size: CGSize(width: width, height: height), format: format)
        let timestamp = Date().formatted(date: .abbreviated, time: .shortened)
        let image = renderer.image { ctx in
            UIColor.white.setFill()
            ctx.cgContext.fill(CGRect(x: 0, y: 0, width: width, height: height))
            heading(ctx.cgContext, width: width)
            var y: CGFloat = 90
            for item in lines {
                line(item, width: width, top: y, scale: scale)
                y += 30
            }
            footer(ctx.cgContext, width: width, height: height,
                   page: 1, stamp: timestamp)
        }
        guard let bytes = image.pngData() else {
            throw OperatingReportError.noData
        }
        let url = try outputURL(ext: "png")
        try bytes.write(to: url, options: .atomic)
        return url
    }
}
