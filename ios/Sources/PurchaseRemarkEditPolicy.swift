import Foundation

enum PurchaseRemarkEditState: String, Codable {
    case ready, sending, complete, needsReview

    var title: String {
        switch self {
        case .ready: return "备注更正已准备，尚未发送"
        case .sending: return "更正可能已发送，需核对"
        case .complete: return "云端备注更正已确认"
        case .needsReview: return "发生冲突或网络结果不明，禁止重发"
        }
    }
}

/// The parent and every visible child are frozen before sending any bytes.
/// This edition never mutates purchase_item, costs, inventory or settlement.
struct PurchaseRemarkEditJob: Codable, Identifiable {
    let id: String
    let sourceAccount: String
    let bookID: String
    let stageHost: String
    let stageInstanceID: String
    let stageUsername: String
    let deviceID: String
    let original: SyncEvent
    let proposed: SyncEvent
    let children: [SyncEvent]
    let createdAt: Date
    var state: PurchaseRemarkEditState
    var note: String
}

enum PurchaseRemarkEditPolicy {
    private static let maxExactInt = 9_007_199_254_740_991.0

    static func integer(_ raw: JSONValue?) -> Int64? {
        guard let raw else { return nil }
        let value: Double
        switch raw {
        case .number(let n): value = n
        case .string(let s): value = Double(s) ?? 0
        default: return nil
        }
        guard value.isFinite, value > 0, value <= maxExactInt,
              value.rounded() == value else { return nil }
        return Int64(value)
    }

    private static func cents(_ raw: JSONValue?) -> Int64? {
        guard let raw else { return nil }
        let number: Double
        switch raw {
        case .number(let n): number = n
        case .string(let s): number = Double(s) ?? -1
        default: return nil
        }
        guard number.isFinite, number >= 0, number <= 10_000_000,
              abs(number * 100 - (number * 100).rounded()) < 0.0001 else {
            return nil
        }
        return Int64((number * 100).rounded())
    }

    private static func same(_ first: SyncEvent, _ second: SyncEvent) -> Bool {
        first.tableName == second.tableName &&
            first.syncId == second.syncId &&
            first.rowVersion == second.rowVersion &&
            first.operation == second.operation &&
            first.payload == second.payload
    }

    /// Never select a child by product name, only the canonical numeric parent
    /// id and a unique parent. Tombstones are included in the safety check.
    static func attachedChildren(order: SyncEvent,
                                 events: [SyncEvent]) throws -> [SyncEvent] {
        guard order.tableName == "purchase_order", !order.isDeletion,
              order.rowVersion > 0,
              order.rowVersion < 9_007_199_254_740_990,
              let date = order.payload["date"]?.text, !date.isEmpty,
              let parentID = integer(order.payload["id"]),
              cents(order.payload["total_cost"]) != nil else {
            throw StagingSafetyError.needsReview("采购订单缺少有效云端身份或金额")
        }
        let parents = events.filter {
            $0.tableName == "purchase_order" &&
            integer($0.payload["id"]) == parentID
        }
        guard parents.count == 1, let one = parents.first,
              same(one, order) else {
            throw StagingSafetyError.needsReview(
                "父订单 ID 冲突、已删除或版本变化；本次只读检查已停止")
        }
        let linked = events.filter {
            $0.tableName == "purchase_item" &&
            integer($0.payload["order_id"]) == parentID
        }.sorted { $0.syncId < $1.syncId }
        let active = linked.filter { !$0.isDeletion }
        guard !active.isEmpty,
              active.allSatisfy({
                $0.rowVersion > 0 &&
                !$0.syncId.isEmpty &&
                cents($0.payload["total_cost"]) != nil
              }) else {
            throw StagingSafetyError.needsReview(
                "商品明细尚未完整同步，或金额无效；不能修改订单")
        }
        let sum = active.reduce(Int64(0)) {
            $0 + (cents($1.payload["total_cost"]) ?? 0)
        }
        guard sum == cents(order.payload["total_cost"]) else {
            throw StagingSafetyError.needsReview(
                "商品合计与父订单不一致，先在 Android 修复后再修改备注")
        }
        // Preserve tombstones as evidence against mistaken child remapping.
        return linked
    }

    static func childrenEqual(_ left: [SyncEvent],
                              _ right: [SyncEvent]) -> Bool {
        guard left.count == right.count else { return false }
        return zip(left, right).allSatisfy { same($0, $1) }
    }

    static func prepare(original: SyncEvent, remark: String,
                        events: [SyncEvent], book: CloudBook,
                        account: String, session: StagingSession,
                        now: Date = Date(), jobID: String = UUID().uuidString)
        throws -> PurchaseRemarkEditJob {
        guard book.isAvailable, book.name.hasPrefix(TestWritePolicy.prefix),
              session.book.id == book.id, session.book.name == book.name,
              session.scope.bookID == book.id,
              session.scope.instanceID == "production-scoped-testbook:" + book.id,
              session.host == StagingPurchasePolicy.productionURL,
              !account.isEmpty, !jobID.isEmpty else {
            throw StagingSafetyError.wrongSourceBook
        }
        let corrected = remark.trimmingCharacters(in: .whitespacesAndNewlines)
        guard corrected.count <= 200, !corrected.contains("\u{0000}") else {
            throw StagingSafetyError.invalidDraft
        }
        let currentText = original.payload["remark"]?.text ?? ""
        guard corrected != currentText else {
            throw StagingSafetyError.needsReview("备注没有变化，无需再次提交")
        }
        guard let current = events.first(where: {
            $0.tableName == original.tableName &&
            $0.syncId == original.syncId
        }), same(current, original) else {
            throw StagingSafetyError.needsReview(
                "Android 或其他设备已修改订单，停止本次更正")
        }
        let children = try attachedChildren(order: current, events: events)
        var payload = original.payload
        let next = original.rowVersion + 1
        payload["remark"] = .string(corrected)
        payload["row_version"] = .number(Double(next))
        payload["updated_at"] = .number(now.timeIntervalSince1970 * 1000)
        payload["sync_status"] = .number(2)
        payload["modified_by"] = .string(session.deviceID)
        let proposed = SyncEvent(tableName: "purchase_order",
            syncId: original.syncId, rowVersion: next,
            operation: "UPSERT", payload: payload)
        return PurchaseRemarkEditJob(
            id: jobID, sourceAccount: account, bookID: book.id,
            stageHost: session.host,
            stageInstanceID: session.scope.instanceID,
            stageUsername: session.username, deviceID: session.deviceID,
            original: original, proposed: proposed,
            children: children, createdAt: now, state: .ready,
            note: "仅备注变更，订单金额、商品和收款结算字段全部保留")
    }

    static func unchanged(_ job: PurchaseRemarkEditJob,
                          events: [SyncEvent]) -> Bool {
        guard let current = events.first(where: {
            $0.tableName == "purchase_order" &&
            $0.syncId == job.original.syncId
        }), same(current, job.original),
              let children = try? attachedChildren(order: current, events: events)
        else { return false }
        return childrenEqual(job.children, children)
    }

    static func confirmed(_ job: PurchaseRemarkEditJob,
                          events: [SyncEvent]) -> Bool {
        guard let current = events.first(where: {
            $0.tableName == "purchase_order" &&
            $0.syncId == job.proposed.syncId
        }), !current.isDeletion,
              current.rowVersion == job.proposed.rowVersion,
              current.operation == "UPSERT",
              current.payload["remark"] == job.proposed.payload["remark"],
              let children = try? attachedChildren(order: current, events: events),
              childrenEqual(job.children, children) else { return false }
        // Verify no original business field was silently changed by the push.
        let allowed: Set<String> = [
            "remark", "row_version", "updated_at",
            "modified_by", "sync_status"
        ]
        for (key, wanted) in job.original.payload where !allowed.contains(key) {
            guard current.payload[key] == wanted else { return false }
        }
        return true
    }
}
