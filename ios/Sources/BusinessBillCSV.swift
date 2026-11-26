import Foundation
import CryptoKit
import CoreFoundation

/// Preview-only subset of Android PaymentBillParser. Never use this result
/// as a cloud receipt or proof of complete export-period coverage.
enum TXBillCSVError: LocalizedError {
    case unsupported, malformed, header, ambiguous, conflict, tooLarge
    var errorDescription: String? {
        switch self {
        case .unsupported: return "不支持的账单格式、加密压缩包或旧版 XLS"
        case .malformed: return "CSV 格式或编码异常"
        case .header: return "没有找到可核验的交易表头"
        case .ambiguous: return "无法确认账单平台，请使用官方导出文件"
        case .conflict: return "发现相同交易号的不一致记录，请先核对账单"
        case .tooLarge: return "账单超过本机预览安全上限"
        }
    }
}

enum TXBillPlatform: String, Codable, CaseIterable {
    case wechat = "WECHAT", alipay = "ALIPAY"
    var label: String { self == .wechat ? "微信" : "支付宝" }
}

struct TXBillPreviewRow: Equatable, Identifiable {
    let platform: TXBillPlatform
    let tradeTime: String
    let reference: String
    let cents: Int64
    let refundCents: Int64
    let dedupeKey: String
    let customerKey: String
    let reliableIdentity: Bool
    var id: String { platform.rawValue + "|" + dedupeKey }
    var date: String { String(tradeTime.prefix(10)) }
    var hour: Int { Int(tradeTime.dropFirst(11).prefix(2)) ?? -1 }
    var net: Int64 { cents - refundCents }
}

struct TXBillCSVResult: Equatable {
    let platform: TXBillPlatform
    let fileHash: String
    let rows: [TXBillPreviewRow]
    let ignored: Int
    let review: Int
    let duplicates: Int
}

enum TXBillCSV {
    static let maximumBytes = 15 * 1024 * 1024
    static func digest(_ bytes: Data) -> String {
        SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
    }
    private static func hash(_ value: String) -> String { digest(Data(value.utf8)) }
    private static func clean(_ value: String) -> String {
        value.replacingOccurrences(of: "\u{FEFF}", with: "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .trimmingCharacters(in: CharacterSet(charactersIn: "\"'`"))
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }
    private static func header(_ value: String) -> String {
        clean(value).lowercased().replacingOccurrences(of: "（", with: "(")
            .replacingOccurrences(of: "）", with: ")")
            .replacingOccurrences(of: "[\\s:_：/\\-]", with: "", options: .regularExpression)
    }
    static func decode(_ data: Data) throws -> String {
        guard !data.isEmpty else { throw TXBillCSVError.malformed }
        guard data.count <= maximumBytes else { throw TXBillCSVError.tooLarge }
        if data.starts(with: [0x50,0x4b]) || data.starts(with: [0xd0,0xcf]) { throw TXBillCSVError.unsupported }
        if data.starts(with: [0xff,0xfe]) || data.starts(with: [0xfe,0xff]) {
            guard let value = String(data: data, encoding: .utf16) else { throw TXBillCSVError.malformed }
            return value
        }
        if let value = String(data: data, encoding: .utf8) { return value }
        let encoding = CFStringConvertEncodingToNSStringEncoding(CFStringEncoding(CFStringEncodings.GB_18030_2000.rawValue))
        guard let value = String(data: data, encoding: String.Encoding(rawValue: encoding)) else { throw TXBillCSVError.malformed }
        return value
    }

    /// Strict CSV quoting; newline inside a quoted cell and doubled quotes
    /// are preserved. Reject partial/truncated quoted records.
    static func table(_ text: String, separator: Character = ",") throws -> [[String]] {
        var rows: [[String]] = [], row: [String] = [], field = ""
        var quoted = false, afterQuote = false
        var iterator = text.makeIterator()
        func endCell() throws {
            guard row.count < 256, field.utf8.count <= 131072 else { throw TXBillCSVError.tooLarge }
            row.append(field); field = ""; afterQuote = false
        }
        func endRow() throws {
            try endCell()
            guard rows.count < 100000 else { throw TXBillCSVError.tooLarge }
            rows.append(row); row = []
        }
        while let c = iterator.next() {
            if quoted {
                if c == "\"" { quoted = false; afterQuote = true }
                else { field.append(c) }
            } else if afterQuote {
                if c == "\"" { field.append(c); quoted = true; afterQuote = false }
                else if c == separator { try endCell() }
                else if c.isNewline { try endRow() }
                else if c != " " && c != "\t" { throw TXBillCSVError.malformed }
            } else if c == "\"" && field.isEmpty {
                quoted = true
            } else if c == separator {
                try endCell()
            } else if c.isNewline {
                try endRow()
            } else { field.append(c) }
        }
        guard !quoted else { throw TXBillCSVError.malformed }
        if !field.isEmpty || !row.isEmpty || afterQuote { try endRow() }
        return rows
    }

    private static let time = ["交易时间","创建时间","支付时间","付款时间","时间","入账时间"]
    private static let amount = ["商家实收","实收金额","应结订单金额","订单金额(元)","订单金额","金额(元)","金额","交易金额","总金额"]
    private static let reference = ["微信订单号","微信支付订单号","支付宝交易号","交易订单号","交易单号","订单号","商户订单号","商家订单号","单号"]
    private static let type = ["交易类型","交易分类","业务类型","交易方式","收款类型","资金类型"]
    private static let product = ["商品","商品名称","商品说明","名称","业务名称"]
    private static let direction = ["收/支","收支","资金方向","收入/支出","收支类型","账务类型"]
    private static let strict = ["二维码收款","经营收款","收款码收款","收钱码收款","商家收款","扫码收款","二维码经营收款"]
    private static let nonMerchant = ["转账","红包","提现","充值","余额宝转入","余额宝转出"]
    private static let rejected = ["失败","关闭","待付款","待支付","未支付","REVOKED","CLOSED","FAIL"]

    private static func timeValue(_ raw: String) -> String? {
        let value = clean(raw)
        let formats = ["yyyy-MM-dd HH:mm:ss","yyyy/MM/dd HH:mm:ss","yyyy-MM-dd HH:mm","yyyy/MM/dd HH:mm","yyyy年MM月dd日 HH:mm:ss","yyyy年MM月dd日 HH:mm"]
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.timeZone = TimeZone(secondsFromGMT: 28800)
        formatter.isLenient = false
        // Match Android Excel serial (1899-12-30, China-local natural day).
        if let serial = Double(value), serial.isFinite, serial > 30000, serial < 100000 {
            var calendar = Calendar(identifier: .gregorian)
            calendar.timeZone = TimeZone(secondsFromGMT: 28800)!
            if let epoch = calendar.date(from: DateComponents(year: 1899, month: 12, day: 30)),
               let date = calendar.date(byAdding: .day, value: Int(serial), to: epoch) {
                let seconds = min(86399, max(0, Int((serial - Double(Int(serial))) * 86400)))
                formatter.dateFormat = formats[0]
                return formatter.string(from: date.addingTimeInterval(Double(seconds)))
            }
        }
        for format in formats {
            formatter.dateFormat = format
            guard let date = formatter.date(from: value), formatter.string(from: date) == value else { continue }
            formatter.dateFormat = formats[0]
            return formatter.string(from: date)
        }
        return nil
    }
    private static func money(_ raw: String, optional: Bool = false) -> Int64? {
        var value = clean(raw)
        for token in ["¥","￥",",","元","+"] { value = value.replacingOccurrences(of: token, with: "") }
        value = value.trimmingCharacters(in: .whitespacesAndNewlines)
        if optional && value.isEmpty { return 0 }
        if value.hasPrefix("-") { value.removeFirst() }
        guard value.range(of: "^[0-9]+(\\.[0-9]{1,2})?$", options: .regularExpression) != nil,
              let cents = try? BusinessCollectorDraftPolicy.cents(value, label: "账单金额") else { return nil }
        return cents
    }
    /// Kotlin Double.toString for bounded decimal cents (no scientific form).
    private static func kotlinMoney(_ cents: Int64) -> String {
        let whole = cents / 100, fractional = cents % 100
        if fractional % 10 == 0 { return "\(whole).\(fractional / 10)" }
        return "\(whole)." + String(format: "%02lld", fractional)
    }
    private static func identity(_ value: String) -> String {
        clean(value).lowercased().replacingOccurrences(of: "\\s+", with: "", options: .regularExpression)
    }

    static func parse(fileName: String, data: Data) throws -> TXBillCSVResult {
        let decoded = try TXBillArchive.read(fileName: fileName, data: data)
        let source = decoded.rows.prefix(30).flatMap { $0 }.joined(separator: "|")
        let rows = decoded.rows
        let fileName = decoded.name
        guard let index = rows.firstIndex(where: { row in
            let h = Set(row.map(header))
            return time.contains { h.contains(header($0)) } &&
                amount.contains { h.contains(header($0)) } &&
                reference.contains { h.contains(header($0)) }
        }) else { throw TXBillCSVError.header }
        var columns: [String: Int] = [:]
        let names = rows[index].map(header)
        for (i, h) in names.enumerated() where !h.isEmpty {
            if columns[h] != nil { throw TXBillCSVError.header }
            columns[h] = i
        }
        let w = names.contains(header("微信订单号")) || names.contains(header("微信支付订单号"))
        let a = names.contains(header("支付宝交易号"))
        let officialW = Set(names).isSuperset(of: ["收支类型","明细名称","单号"].map(header)) && source.prefix(16000).contains("经营收款-来自")
        let file = fileName.lowercased()
        let namedW = file.contains("微信") || file.contains("wechat")
        let namedA = file.contains("支付宝") || file.contains("alipay")
        let platform: TXBillPlatform
        if w && a { throw TXBillCSVError.ambiguous }
        else if officialW || w { platform = .wechat }
        else if a { platform = .alipay }
        else if namedW != namedA { platform = namedW ? .wechat : .alipay }
        else { throw TXBillCSVError.ambiguous }
        var accepted: [TXBillPreviewRow] = [], seen = Set<String>(), byRef: [String: String] = [:]
        var ignored = 0, review = 0, duplicates = 0
        for row in rows.dropFirst(index + 1) {
            if row.allSatisfy({ clean($0).isEmpty }) { continue }
            func value(_ aliases: [String]) -> String {
                for alias in aliases {
                    if let i = columns[header(alias)], row.indices.contains(i), !clean(row[i]).isEmpty { return clean(row[i]) }
                }
                return ""
            }
            let flow = value(direction), kind = value(type), goods = value(product)
            let remark = value(["备注","付款备注","商户数据包"])
            if flow.contains("支出") || flow == "支" || nonMerchant.contains(where: { kind.contains($0) }) { ignored += 1; continue }
            let evidence = platform == .alipay ? kind + "|" + goods : kind + "|" + goods + "|" + remark
            let isMerchant = strict.contains(where: { evidence.contains($0) }) ||
                (platform == .alipay && goods == "经营码交易" && flow == "收入") || kind.uppercased() == "NATIVE"
            if !isMerchant { ignored += 1; continue }
            let status = value(["交易状态","当前状态","状态","资金状态"])
            let refunded = status.contains("退款") || status.uppercased() == "REFUND"
            if !refunded && rejected.contains(where: { status.uppercased().contains($0) }) { ignored += 1; continue }
            guard let raw = money(value(amount)), let explicit = money(value(["退款金额","申请退款金额","商家退款金额","退款"]), optional: true),
                  let stamp = timeValue(value(time)) else { review += 1; continue }
            let paid = refunded ? 0 : raw
            let refund = explicit > 0 ? explicit : (refunded ? raw : 0)
            guard paid > 0 || refund > 0 else { ignored += 1; continue }
            let ref = value(reference)
            // No synthetic identifier: Android's fallback hash depends on
            // original raw evidence. Missing order IDs require manual review.
            guard !ref.isEmpty, !ref.hasPrefix("UNSAFE_NUMERIC_ID:"), !ref.contains("*"), !ref.lowercased().contains("e+"), !ref.lowercased().contains("e-"),
                  ref.range(of: "[\\r\\n]", options: .regularExpression) == nil else { review += 1; continue }
            let stable = identity(value(["用户标识","OPENID","买家账号","买家信息","付款方账号","对方账号","对方支付宝账号","支付宝账号","买家支付宝账号"]))
            let reliable = stable.count >= 3 && !["匿名","未知","无","微信用户","支付宝用户","-","--","null","n/a"].contains(stable) &&
                !stable.contains(where: { "*＊•●··".contains($0) })
            let prefix = platform.rawValue
            let customer = hash(reliable ? prefix + "|ID|" + stable : prefix + "|ANON|" + ref + "|" + stamp)
            let key = hash([prefix,ref,stamp,kotlinMoney(paid),kotlinMoney(refund),status].joined(separator: "|"))
            if seen.contains(key) { duplicates += 1; continue }
            if let old = byRef[ref], old != key { throw TXBillCSVError.conflict }
            byRef[ref] = key; seen.insert(key)
            accepted.append(TXBillPreviewRow(platform: platform, tradeTime: stamp, reference: ref, cents: paid,
                refundCents: refund, dedupeKey: key, customerKey: customer, reliableIdentity: reliable))
        }
        return TXBillCSVResult(platform: platform, fileHash: digest(data), rows: accepted, ignored: ignored, review: review, duplicates: duplicates)
    }
}
