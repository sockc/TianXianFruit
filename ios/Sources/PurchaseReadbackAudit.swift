import Foundation

/// Read-only cloud observation. Neither the server ACK nor this audit proves
/// the Android client has already downloaded the same change.
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
    let verifiedItemSyncIDs: [String]
    let pendingItemSyncIDs: [String]
    let expectedItemCount: Int
    let issues: [Issue]

    var complete: Bool { orderVerified && itemVerified && issues.isEmpty }
    var hasRemoteHistory: Bool {
        orderPresent || itemPresent ||
        issues.contains { $0.id.hasSuffix(".deleted") }
    }
    var remappedOrderID: Bool {
        guard let serverOrderID else { return false }
        return orderVerified && serverOrderID != submittedOrderID
    }
    var headline: String {
        if complete {
            return "云端订单及 " + String(expectedItemCount) +
                   " 条商品一致 · Android 设备状态未验证"
        }
        if issues.contains(where: { $0.id.hasSuffix(".deleted") }) {
            return "云端记录已删除，禁止重传并请核对"
        }
        if !orderPresent && !itemPresent {
            return "尚未回读到采购订单（不代表上传失败）"
        }
        if orderVerified && !pendingItemSyncIDs.isEmpty {
            return "云端已确认 " + String(verifiedItemSyncIDs.count) + " / " +
                   String(expectedItemCount) + " 条商品"
        }
        return "云端采购存在差异，已阻止自动重试"
    }
}

enum PurchaseReadbackPolicy {
    static func audit(_ job: StagingPurchaseJob, events: [SyncEvent],
                      now: Date = Date()) -> PurchaseReadbackAudit {
        let latest = StagingSyncMaterializer.latest(events)
        var snapshot = ReadOnlySnapshot()
        snapshot.apply(latest)
        let records = Array(snapshot.records.values)
        let expected = StagingPayloadBuilder.itemEvents(job)
        let ids = Set(expected.map(\.syncId))
        let parent = records.first {
            $0.tableName == "purchase_order" && $0.syncId == job.orderSyncID
        }
        var issues: [PurchaseReadbackAudit.Issue] = []
        func fail(_ field: String, _ detail: String) {
            issues.append(.init(field, detail))
        }
        func numeric(_ event: SyncEvent, _ key: String) -> Double? {
            event.payload[key]?.numberValue
        }
        func identity(_ event: SyncEvent, _ key: String) -> Int64? {
            guard let n = numeric(event, key), n.isFinite,
                  n > 0, n <= 9_007_199_254_740_991,
                  n.rounded() == n else { return nil }
            return Int64(n)
        }
        func equal(_ left: JSONValue?, _ right: JSONValue?,
                   tolerance: Double = 0.000001) -> Bool {
            guard let left, let right else { return false }
            if let a = left.numberValue, let b = right.numberValue {
                return a.isFinite && b.isFinite && abs(a - b) <= tolerance
            }
            return left == right
        }
        func compare(_ expected: [String: JSONValue],
                     _ actual: SyncEvent, fields: [String],
                     prefix: String) {
            for field in fields {
                let epsilon = field == "total_cost" ? 0.005 : 0.000001
                if !equal(expected[field], actual.payload[field],
                          tolerance: epsilon) {
                    fail(prefix + "." + field,
                         prefix + " 的 " + field + " 与草稿不符")
                }
            }
        }
        let deletedOrder = latest.contains {
            $0.tableName == "purchase_order" &&
            $0.syncId == job.orderSyncID && $0.isDeletion
        }
        var parentID: Int64?
        var parentOK = false
        if let parent {
            parentID = identity(parent, "id")
            if parentID == nil { fail("order.id", "订单数字 ID 无效") }
            if parent.rowVersion < 1 { fail("order.version", "订单版本无效") }
            if parent.payload["sync_id"]?.text != job.orderSyncID {
                fail("order.sync_id", "订单同步 ID 不匹配")
            }
            compare(job.orderPayload, parent,
                    fields: ["date", "buyer_id", "buyer_name", "store_id",
                             "total_cost", "remark", "status"], prefix: "order")
            parentOK = issues.isEmpty
        } else if deletedOrder {
            fail("order.deleted", "采购订单已被服务端删除")
        } else {
            fail("order.missing", "未找到采购订单 sync_id")
        }
        var verified: [String] = []
        var pending: [String] = []
        var primaryItemID: Int64?
        var primaryItemVersion: Int64?
        var anyItemPresent = false
        var sum = 0.0

        for (index, item) in expected.enumerated() {
            let prefix = expected.count == 1 ? "item" :
                         "item." + String(index + 1)
            let found = records.first {
                $0.tableName == "purchase_item" && $0.syncId == item.syncId
            }
            let deleted = latest.contains {
                $0.tableName == "purchase_item" &&
                $0.syncId == item.syncId && $0.isDeletion
            }
            guard let found else {
                if deleted {
                    fail(prefix + ".deleted",
                         "第 " + String(index + 1) + " 条商品已被服务器删除")
                } else {
                    pending.append(item.syncId)
                    fail(prefix + ".missing",
                         "第 " + String(index + 1) + " 条商品尚未回读")
                }
                continue
            }
            anyItemPresent = true
            if index == 0 {
                primaryItemID = identity(found, "id")
                primaryItemVersion = found.rowVersion
            }
            let issueCount = issues.count
            if identity(found, "id") == nil {
                fail(prefix + ".id", "商品明细数字 ID 无效")
            }
            if found.rowVersion < 1 {
                fail(prefix + ".version", "商品明细版本无效")
            }
            if found.payload["sync_id"]?.text != item.syncId {
                fail(prefix + ".sync_id", "商品明细同步 ID 不一致")
            }
            compare(item.payload, found,
                    fields: ["fruit_id", "fruit_name", "unit", "quantity",
                             "total_cost", "unit_price", "unit_weight_jin"],
                    prefix: prefix)
            if parentID == nil {
                fail(prefix + ".parent_missing", "父订单 ID 未确认")
            } else if identity(found, "order_id") != parentID {
                fail(prefix + ".order_id", "商品明细未指向云端实际父订单")
            }
            if issues.count == issueCount {
                verified.append(item.syncId)
                sum += found.payload["total_cost"]?.numberValue ?? 0
            }
        }

        if let parentID {
            let linked = records.filter {
                $0.tableName == "purchase_item" &&
                identity($0, "order_id") == parentID
            }
            if linked.contains(where: { !ids.contains($0.syncId) }) ||
                (pending.isEmpty && linked.count != expected.count) {
                fail("item.link_count",
                     "该父订单存在多余或重复的商品明细关联")
            }
            if pending.isEmpty, verified.count == expected.count,
               let target = job.orderPayload["total_cost"]?.numberValue,
               abs(sum - target) > 0.005 {
                fail("item.total_cost", "商品小计之和与订单总价不一致")
            }
        }
        return PurchaseReadbackAudit(
            jobID: job.id, bookID: job.bookID, checkedAt: now,
            orderPresent: parent != nil,
            itemPresent: anyItemPresent,
            orderVerified: parentOK,
            itemVerified: verified.count == expected.count &&
                          pending.isEmpty && parentOK &&
                          issues.isEmpty,
            serverOrderID: parentID, serverItemID: primaryItemID,
            serverOrderVersion: parent?.rowVersion,
            serverItemVersion: primaryItemVersion,
            submittedOrderID: job.orderRemoteID,
            verifiedItemSyncIDs: verified,
            pendingItemSyncIDs: pending,
            expectedItemCount: expected.count, issues: issues)
    }
}
