import Foundation

/// Optional new-business per-person receipt input. Kept separate from the
/// existing one-person-per-channel draft fields for backwards compatibility.
struct BusinessCollectorDraftLine: Codable, Equatable, Identifiable {
    let id: String
    var partnerSyncID: String
    var wechat: String
    var alipay: String
    var cash: String

    init(id: String = UUID().uuidString, partnerSyncID: String = "",
         wechat: String = "0", alipay: String = "0", cash: String = "0") {
        self.id = id
        self.partnerSyncID = partnerSyncID
        self.wechat = wechat
        self.alipay = alipay
        self.cash = cash
    }
}

enum BusinessCollectorDraftPolicy {
    static func cents(_ raw: String, label: String) throws -> Int64 {
        let money = try TestWritePolicy.money(raw, label: label)
        return Int64((money * 100).rounded())
    }

    static func totals(_ lines: [BusinessCollectorDraftLine])
        throws -> (wechat: Int64, alipay: Int64, cash: Int64) {
        var w: Int64 = 0
        var a: Int64 = 0
        var c: Int64 = 0
        for line in lines {
            w += try cents(line.wechat, label: "微信逐人金额")
            a += try cents(line.alipay, label: "支付宝逐人金额")
            c += try cents(line.cash, label: "现金逐人金额")
        }
        return (w, a, c)
    }

    static func validate(_ lines: [BusinessCollectorDraftLine],
                         fields: [String: String]) throws {
        guard !lines.isEmpty, lines.count <= 12,
              Set(lines.map(\.id)).count == lines.count,
              lines.allSatisfy({ !$0.id.isEmpty &&
                                 !$0.partnerSyncID.isEmpty }),
              Set(lines.map(\.partnerSyncID)).count == lines.count else {
            throw TestWriteError.insufficientSchema(
                "收款人至少一位、最多十二位，不得重复或留空")
        }
        let totals = try totals(lines)
        for line in lines {
            let w = try cents(line.wechat, label: "微信逐人金额")
            let a = try cents(line.alipay, label: "支付宝逐人金额")
            let c = try cents(line.cash, label: "现金逐人金额")
            guard w + a + c > 0 else {
                throw TestWriteError.insufficientSchema(
                    "每位收款人必须至少有一笔非零收入")
            }
        }
        for (key, expected) in [
            ("wechat", totals.wechat),
            ("alipay", totals.alipay),
            ("cash", totals.cash)
        ] {
            guard try cents(fields[key] ?? "", label: key) == expected else {
                throw TestWriteError.insufficientSchema(
                    "逐人收款之和与" + key + "合计不一致")
            }
        }
    }

    /// Consistent with Android's actual_start_time/actual_end_time text
    /// columns. End earlier than start represents an overnight session.
    /// This is time metadata only; never create overnight receipt deductions.
    static func validClock(_ text: String) -> Bool {
        let parts = text.split(separator: ":", omittingEmptySubsequences: false)
        guard parts.count == 2, parts[0].count == 2, parts[1].count == 2,
              parts.allSatisfy({ $0.allSatisfy(\.isNumber) }),
              let hours = Int(parts[0]), let minutes = Int(parts[1]) else {
            return false
        }
        return (0..<24).contains(hours) && (0..<60).contains(minutes)
    }

    static func validateTimes(_ fields: [String: String]) throws {
        let start = fields["actual_start_time"] ?? ""
        let end = fields["actual_end_time"] ?? ""
        guard (start.isEmpty && end.isEmpty) ||
              (validClock(start) && validClock(end) && start != end) else {
            throw TestWriteError.insufficientSchema(
                "实际营业时间需同时填写 HH:mm；可跨午夜，不能相同")
        }
    }
}
