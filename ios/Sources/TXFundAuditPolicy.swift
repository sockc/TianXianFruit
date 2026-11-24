import Foundation

/// Cross-device accounting evidence, not a replacement for Android's
/// AppDatabase.buildPartnerFundLedger / getPartnerFundBalances.
/// This audit never calculates a withdrawable or cumulative fund balance.
struct TXFundAuditFinding: Identifiable, Equatable {
    enum Severity: String {
        case information
        case blocked
    }
    let id: String
    let title: String
    let detail: String
    let severity: Severity
}

struct TXFundAuditReport {
    let cutoff: String
    let dailyRecordCount: Int
    let partnerRecordCount: Int
    let transferCount: Int
    let findings: [TXFundAuditFinding]

    /// A true value means the saved source records are worth manually
    /// comparing, never that the current fund balance is verified.
    var hasReviewableEvidence: Bool {
        dailyRecordCount > 0 || partnerRecordCount > 0 || transferCount > 0
    }
    var hasBlocker: Bool { findings.contains { $0.severity == .blocked } }
}

enum TXFundAuditPolicy {
    static func inspect(_ snapshot: ReadOnlySnapshot, cutoff: String,
                        partner: String = "") -> TXFundAuditReport {
        // Inspect the full cloud snapshot through the cutoff. The history
        // visible range (7/30 days) must never restrict accounting evidence.
        let daily = snapshot.financeDaily.filter { $0.date <= cutoff }
        let partnerRows = snapshot.financeSettlementPartners.filter {
            $0.date <= cutoff && (partner.isEmpty || $0.partner == partner)
        }
        let transfers = snapshot.financeTransfers.filter {
            $0.date <= cutoff &&
            (partner.isEmpty || $0.from == partner || $0.to == partner ||
             $0.focusPartnerName == partner)
        }
        var issues: [TXFundAuditFinding] = []
        func add(_ id: String, _ title: String, _ detail: String,
                 _ severity: TXFundAuditFinding.Severity = .blocked) {
            issues.append(TXFundAuditFinding(id: id, title: title,
                                             detail: detail, severity: severity))
        }

        if daily.isEmpty && partnerRows.isEmpty && transfers.isEmpty {
            add("empty", "未发现资金原始记录",
                "当前同步快照在截止日期前没有相关记录；不能将缺失当作余额为零。")
        }

        let duplicateDays = Dictionary(grouping: daily, by: \.date)
            .filter { $0.value.count > 1 }.keys.sorted()
        if !duplicateDays.isEmpty {
            add("daily-duplicates", "日结快照重复",
                "这些日期存在多份日结快照：" + duplicateDays.joined(separator: "、"))
        }

        let duplicatePartners = Dictionary(grouping: partnerRows) {
            $0.date + "|" + $0.partner
        }.filter { $0.value.count > 1 }.keys.sorted()
        if !duplicatePartners.isEmpty {
            add("partner-duplicates", "合伙人当日记录重复",
                "以下日期及人员存在多条快照：" + duplicatePartners.joined(separator: "、"))
        }

        let invalid = transfers.filter {
            $0.kind.uppercased() == "CUTOFF_LEGACY_INVALID" ||
            TXSettlementRecordPolicy.transferRegistration($0) == .inconsistent
        }
        if !invalid.isEmpty {
            add("invalid", "含历史无效或登记异常转账",
                "共 \(invalid.count) 条；安卓会按有效性规则排除或调整，不能按转账总额抵扣。")
        }

        let partial = transfers.filter {
            TXSettlementRecordPolicy.transferRegistration($0) == .partiallyRegistered
        }
        if !partial.isEmpty {
            add("partial", "含部分结算记录",
                "共 \(partial.count) 条；必须按实际已登记金额、剩余资金及结清断点计算。")
        }

        let cutoffs = transfers.filter {
            $0.kind.uppercased() == "CUTOFF" ||
            $0.kind.uppercased() == "CUTOFF_CENTER"
        }
        if !cutoffs.isEmpty {
            add("cutoffs", "含跨日／资金中心结清",
                "共 \(cutoffs.count) 条；必须使用安卓结清断点与资金中心规则。")
        }

        let statusMissing = daily.filter { !$0.statusProvided }
        if !statusMissing.isEmpty {
            add("status-missing", "日结状态字段不完整",
                "共 \(statusMissing.count) 条没有明确的日结状态；不能推断已结清。")
        }

        // Currently the sync feed has no authoritative current balance,
        // authoritative partner IDs, effective Android transfer predicate,
        // or checkpoint/legacy-normalization markers.
        add("authoritative", "缺少安卓版累计资金余额权威结果",
            "现有云端事件没有 getPartnerFundBalances 截止日结果、完整结清断点与资金中心镜像；即使无其他异常也不能证明余额一致。")

        return TXFundAuditReport(
            cutoff: cutoff, dailyRecordCount: daily.count,
            partnerRecordCount: partnerRows.count, transferCount: transfers.count,
            findings: issues
        )
    }
}
