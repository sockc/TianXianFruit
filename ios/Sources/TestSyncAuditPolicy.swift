import Foundation

/// Pure, read-only interpretation of a complete scoped /sync/pull. No status
/// change in local SQLite, and no claim about another Android device's cursor.
enum TestSyncAuditStatus: String, Equatable, CaseIterable {
    case notSent, confirmed, newerRevision, missing, deleted, mismatch, wrongScope

    var title: String {
        switch self {
        case .notSent: return "本机尚未发送"
        case .confirmed: return "当前云端记录一致"
        case .newerRevision: return "云端已有后续版本"
        case .missing: return "云端尚未找到"
        case .deleted: return "云端记录已删除"
        case .mismatch: return "云端字段不一致"
        case .wrongScope: return "测试账号身份不匹配"
        }
    }

    var requiresAttention: Bool {
        switch self {
        case .confirmed, .newerRevision: return false
        case .notSent, .missing, .deleted, .mismatch, .wrongScope: return true
        }
    }
}

struct TestSyncAuditItem: Identifiable {
    let id: String
    let label: String
    let localState: String
    let cloudStatus: TestSyncAuditStatus
    let details: String
    let createdAt: Date

    var needsAttention: Bool { cloudStatus.requiresAttention }
}

struct TestSyncAuditReport {
    let checkedAt: Date
    let bookID: String
    let items: [TestSyncAuditItem]

    var confirmed: Int { items.filter { $0.cloudStatus == .confirmed }.count }
    var newer: Int { items.filter { $0.cloudStatus == .newerRevision }.count }
    var attention: Int { items.filter(\.needsAttention).count }
}

enum TestSyncAuditPolicy {
    private static func latest(_ event: SyncEvent, from events: [SyncEvent]) -> SyncEvent? {
        events.first {
            $0.tableName == event.tableName && $0.syncId == event.syncId
        }
    }

    private static func status(expected: SyncEvent, events: [SyncEvent],
                               exact: () -> Bool) -> TestSyncAuditStatus {
        guard let actual = latest(expected, from: events) else { return .missing }
        if actual.isDeletion { return .deleted }
        if actual.rowVersion > expected.rowVersion { return .newerRevision }
        if actual.rowVersion < expected.rowVersion { return .missing }
        return exact() ? .confirmed : .mismatch
    }

    private static func scoped(bookID: String, host: String,
                               instanceID: String, username: String,
                               session: StagingSession) -> Bool {
        bookID == session.book.id && host == session.host &&
        instanceID == session.scope.instanceID &&
        username.lowercased() == session.username.lowercased()
    }

    private static func item(id: String, label: String, local: String,
                             status: TestSyncAuditStatus, date: Date,
                             detail: String) -> TestSyncAuditItem {
        TestSyncAuditItem(id: id, label: label, localState: local,
                          cloudStatus: status, details: detail, createdAt: date)
    }

    static func audit(businessUploads: [BusinessUploadJob],
                      businessEdits: [TestBusinessEditJob],
                      purchases: [StagingPurchaseJob],
                      remarks: [PurchaseRemarkEditJob],
                      events: [SyncEvent], session: StagingSession,
                      now: Date = Date()) -> TestSyncAuditReport {
        let current = StagingSyncMaterializer.latest(events)
        var rows: [TestSyncAuditItem] = []

        for job in businessUploads {
            let allowed = scoped(bookID: job.bookID, host: job.stageHost,
                                 instanceID: job.stageInstanceID,
                                 username: job.stageUsername, session: session)
            let state: TestSyncAuditStatus
            if !allowed { state = .wrongScope }
            else if job.state == .ready { state = .notSent }
            else {
                state = status(expected: job.event, events: current) {
                    BusinessUploadPolicy.verified(job, events: current)
                }
            }
            let title = (job.payload["date"]?.text ?? "") + " · " +
                        (job.payload["store_name"]?.text ?? "未知位置")
            rows.append(item(id: "business-upload|" + job.id,
                             label: "营业新增 · " + title, local: job.state.title,
                             status: state, date: job.createdAt,
                             detail: state == .newerRevision
                                ? "云端记录已被后续修改；不能再用首次上传金额作一致性判定"
                                : "仅核对服务器的营业身份、金额与收款人；不代表安卓已下载"))
        }

        for job in businessEdits {
            let allowed = scoped(bookID: job.bookID, host: job.stageHost,
                                 instanceID: job.stageInstanceID,
                                 username: job.stageUsername, session: session)
            let state: TestSyncAuditStatus
            if !allowed { state = .wrongScope }
            else if job.state == .ready { state = .notSent }
            else {
                state = status(expected: job.proposed, events: current) {
                    TestBusinessEditPolicy.confirmed(job, events: current)
                }
            }
            let title = (job.original.payload["date"]?.text ?? "") + " · " +
                        (job.original.payload["store_name"]?.text ?? "未知位置")
            rows.append(item(id: "business-edit|" + job.id,
                             label: "营业更正 · " + title, local: job.state.title,
                             status: state, date: job.createdAt,
                             detail: "核对云端更正版本、利润及收款归属；后续版本并非本次更正失败"))
        }

        for job in purchases {
            let allowed = scoped(bookID: job.bookID, host: job.stageHost,
                                 instanceID: job.stageInstanceID,
                                 username: job.stageUsername, session: session)
            let parent = current.first {
                $0.tableName == "purchase_order" &&
                $0.syncId == job.orderSyncID
            }
            let state: TestSyncAuditStatus
            let detail: String
            if !allowed {
                state = .wrongScope
                detail = "来源账号或账本与当前专用测试会话不符"
            } else if job.state == .ready {
                state = .notSent
                detail = "本机订单尚未开始发送；请通过原采购同步入口操作"
            } else if parent?.isDeletion == true {
                state = .deleted
                detail = "云端父采购订单已删除，不允许从核对中心重传"
            } else if let parent, parent.rowVersion > 1 {
                state = .newerRevision
                detail = "云端采购父单已有后续更正；原始上传快照不再用于判断最新备注"
            } else {
                let audit = PurchaseReadbackPolicy.audit(job, events: current, now: now)
                detail = audit.headline
                if audit.complete { state = .confirmed }
                else if !audit.orderPresent && !audit.itemPresent { state = .missing }
                else { state = .mismatch }
            }
            let title = (job.orderPayload["date"]?.text ?? "") + " · " +
                        (job.orderPayload["buyer_name"]?.text ?? "采购人")
            rows.append(item(id: "purchase-upload|" + job.id,
                             label: "采购新增 · " + title, local: job.state.title,
                             status: state, date: job.createdAt, detail: detail))
        }

        for job in remarks {
            let allowed = scoped(bookID: job.bookID, host: job.stageHost,
                                 instanceID: job.stageInstanceID,
                                 username: job.stageUsername, session: session)
            let state: TestSyncAuditStatus
            if !allowed { state = .wrongScope }
            else if job.state == .ready { state = .notSent }
            else {
                state = status(expected: job.proposed, events: current) {
                    PurchaseRemarkEditPolicy.confirmed(job, events: current)
                }
            }
            let title = (job.original.payload["date"]?.text ?? "") + " · " +
                        (job.original.payload["buyer_name"]?.text ?? "采购人")
            rows.append(item(id: "purchase-remark|" + job.id,
                             label: "采购备注更正 · " + title,
                             local: job.state.title, status: state,
                             date: job.createdAt,
                             detail: "只读核对采购父单版本、备注和原商品明细；不影响采购金额"))
        }
        rows.sort {
            $0.createdAt == $1.createdAt ?
                $0.id < $1.id : $0.createdAt > $1.createdAt
        }
        return TestSyncAuditReport(checkedAt: now, bookID: session.book.id,
                                   items: rows)
    }
}
