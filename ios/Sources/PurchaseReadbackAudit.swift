import Foundation

/// Evidence from the account-scoped, complete cloud sync/pull result.
/// A successful cloud pull is NOT evidence that another Android device has
/// downloaded or applied the same purchase.
struct PurchaseReadbackAudit {
    struct Issue: Identifiable {
        let id: String
        let description: String
        init(_ id: String, _ description: String) {
            self.id = id
            self.description = description
        }
    }

    let jobID: String
    let bookID: String
    let checkedAt: Date
    let orderPresent: Bool
    let itemPresent: Bool
    let orderVerified: Bool
    let itemVerified: Bool
    let serverOrderID: Int64?
    let serverItemID: Int64?
    let serverOrderVersion: Int64?
    let serverItemVersion: Int64?
    let submittedOrderID: Int64
    let issues: [Issue]

    var complete: Bool { orderVerified && itemVerified && issues.isEmpty }
    /// A tombstone still reserves the remote sync identity, even when the
    /// materialized record is no longer active. Never blindly push over it.
    var hasRemoteHistory: Bool {
        orderPresent || itemPresent ||
        issues.contains { $0.id == "order.deleted" || $0.id == "item.deleted" }
    }
    var remappedOrderID: Bool {
        guard let serverOrderID else { return false }
        return orderVerified && serverOrderID != submittedOrderID
    }
    var headline: String {
        if complete { return "云端订单及明细一致 · 待 Android 设备核对" }
        if issues.contains(where: { $0.id.hasSuffix(".deleted") }) {
            return "云端记录已删除，禁止继续上传并请人工检查"
        }
        if !orderPresent && !itemPresent { return "云端尚未回读到两条记录（不代表上传失败）" }
        if orderVerified && !itemPresent { return "云端已确认订单，商品明细尚未回读" }
        return "发现未确认项目，请检查详情，不要盲目重复上传"
    }
}

/// Server IDs may be different from the sender's device-local numeric IDs.
/// Verify the canonical parent ID from the SERVER against the child's
/// order_id, rather than trusting the original sender-side order_id.
enum PurchaseReadbackPolicy {
    static func audit(_ job: StagingPurchaseJob, events: [SyncEvent],
                      now: Date = Date()) -> PurchaseReadbackAudit {
        let latestEvents = StagingSyncMaterializer.latest(events)
        var snapshot = ReadOnlySnapshot()
        snapshot.apply(latestEvents)
        let records = Array(snapshot.records.values)
        let parent = records.first {
            $0.tableName == "purchase_order" && $0.syncId == job.orderSyncID
        }
        let child = records.first {
            $0.tableName == "purchase_item" && $0.syncId == job.itemSyncID
        }
        let orderDeleted = latestEvents.contains {
            $0.tableName == "purchase_order" &&
            $0.syncId == job.orderSyncID && $0.isDeletion
        }
        let itemDeleted = latestEvents.contains {
            $0.tableName == "purchase_item" &&
            $0.syncId == job.itemSyncID && $0.isDeletion
        }
        var issues: [PurchaseReadbackAudit.Issue] = []
        func fail(_ field: String, _ detail: String) {
            issues.append(.init(field, detail))
        }
        func scalar(_ event: SyncEvent, _ key: String) -> Double? {
            event.payload[key]?.numberValue
        }
        func identity(_ event: SyncEvent, _ key: String) -> Int64? {
            guard let number = scalar(event, key), number > 0,
                  number.isFinite, number.rounded() == number,
                  number <= 9_007_199_254_740_991 else { return nil }
            return Int64(number)
        }
        func equal(_ left: JSONValue?, _ right: JSONValue?,
                   tolerance: Double = 0.000001) -> Bool {
            guard let left, let right else { return false }
            if let a = left.numberValue, let b = right.numberValue {
                return a.isFinite && b.isFinite && abs(a - b) <= tolerance
            }
            return left == right
        }
        func compare(_ expected: [String: JSONValue], _ actual: SyncEvent,
                     fields: [String], prefix: String) {
            for field in fields {
                let tolerance = (field == "total_cost") ? 0.005 : 0.000001
                if !equal(expected[field], actual.payload[field],
                          tolerance: tolerance) {
                    fail(prefix + "." + field, prefix + " 的 " + field + " 与本机草稿不一致")
                }
            }
        }
        var serverOrderID: Int64?
        var serverItemID: Int64?
        var parentOK = false
        var childOK = false

        if let parent {
            if parent.isDeletion {
                fail("order.deleted", "采购订单已被服务端删除")
            } else if parent.rowVersion < 1 {
                fail("order.version", "采购订单服务端版本异常")
            } else {
                serverOrderID = identity(parent, "id")
                if serverOrderID == nil {
                    fail("order.id", "采购订单没有有效的服务端数字 ID")
                }
                if parent.payload["sync_id"]?.text != job.orderSyncID {
                    fail("order.sync_id", "订单同步 ID 与请求不一致")
                }
                compare(job.orderPayload, parent, fields: [
                    "date", "buyer_id", "buyer_name", "store_id",
                    "total_cost", "remark", "status"
                ], prefix: "order")
                parentOK = issues.isEmpty
            }
        } else if orderDeleted {
            fail("order.deleted", "采购订单已被服务端删除")
        } else {
            fail("order.missing", "未找到订单 sync_id，不能据此断定服务器未接收")
        }
        let beforeChild = issues.count
        if let child {
            if child.isDeletion {
                fail("item.deleted", "采购商品明细已被服务端删除")
            } else if child.rowVersion < 1 {
                fail("item.version", "商品明细服务端版本异常")
            } else {
                serverItemID = identity(child, "id")
                if serverItemID == nil {
                    fail("item.id", "明细没有有效的服务端数字 ID")
                }
                if child.payload["sync_id"]?.text != job.itemSyncID {
                    fail("item.sync_id", "商品明细同步 ID 与请求不一致")
                }
                compare(job.itemPayload, child, fields: [
                    "fruit_id", "fruit_name", "unit", "quantity",
                    "total_cost", "unit_price", "unit_weight_jin"
                ], prefix: "item")
                guard let serverOrderID else {
                    fail("item.parent_missing", "无法验证商品明细的父订单 ID")
                    return PurchaseReadbackAudit(
                        jobID: job.id, bookID: job.bookID, checkedAt: now,
                        orderPresent: parent != nil, itemPresent: true,
                        orderVerified: false, itemVerified: false,
                        serverOrderID: nil, serverItemID: serverItemID,
                        serverOrderVersion: parent?.rowVersion,
                        serverItemVersion: child.rowVersion,
                        submittedOrderID: job.orderRemoteID,
                        issues: issues)
                }
                if identity(child, "order_id") != serverOrderID {
                    fail("item.order_id", "商品明细指向的订单 ID 与云端父订单不一致")
                }
                let linked = records.filter { event in
                    event.tableName == "purchase_item" && !event.isDeletion &&
                    identity(event, "order_id") == serverOrderID
                }
                if linked.count != 1 || linked.first?.syncId != job.itemSyncID {
                    fail("item.link_count",
                         "此订单关联的有效商品明细不是唯一一条，请检查跨设备映射和重复记录")
                }
                childOK = parentOK && issues.count == beforeChild
            }
        } else if itemDeleted {
            fail("item.deleted", "采购商品明细已被服务端删除")
        } else {
            fail("item.missing", "未找到商品明细 sync_id，不能据此断定上传失败")
        }
        return PurchaseReadbackAudit(
            jobID: job.id, bookID: job.bookID, checkedAt: now,
            orderPresent: parent != nil, itemPresent: child != nil,
            orderVerified: parentOK, itemVerified: childOK,
            serverOrderID: serverOrderID, serverItemID: serverItemID,
            serverOrderVersion: parent?.rowVersion,
            serverItemVersion: child?.rowVersion,
            submittedOrderID: job.orderRemoteID, issues: issues)
    }
}
