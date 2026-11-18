import Foundation
import CryptoKit

/// Locally constructed after the server returns EXACTLY one accessible book.
/// This is NOT a database-isolation attestation; server authorization remains
/// responsible for enforcing book_id and ownership on every pushed event.
struct ScopedAccountEvidence {
    let instanceID: String
    let bookID: String
}

enum StagingSafetyError: LocalizedError {
    case unsafeEndpoint
    case multipleBookAccess
    case privilegedAccount
    case inaccessibleBook
    case wrongSourceBook
    case staleReferences
    case invalidDraft
    case unexpectedResponse(String)
    case needsReview(String)

    var errorDescription: String? {
        switch self {
        case .unsafeEndpoint:
            return "仅允许使用 https://sync.830888.xyz 上的独立测试账本"
        case .multipleBookAccess:
            return "专用测试账号必须只能看到一个 iOS_TEST_ 账本，已拒绝上传"
        case .privilegedAccount:
            return "禁止管理员或超级用户账号进行采购写入测试；请使用测试账本专用账号"
        case .inaccessibleBook:
            return "测试服务端没有当前账本的采购编辑权限"
        case .wrongSourceBook:
            return "测试服务端账本 ID 与本机草稿不一致，禁止强行映射"
        case .staleReferences:
            return "测试服务端缺少相同 sync_id 的采购人或商品，无法安全映射本地 ID"
        case .invalidDraft:
            return "当前仅支持完整且已校验的本机采购草稿"
        case .unexpectedResponse(let message):
            return "测试服务器回应与 Android 同步协议不符：" + message
        case .needsReview(let message):
            return "上传结果不确定，禁止盲目重传：" + message
        }
    }
}

enum StagingPurchasePolicy {
    static let productionURL = "https://sync.830888.xyz"
    static let productionHost = "sync.830888.xyz"

    /// Exactly one canonical host; no arbitrary URL, IP or reverse proxy.
    static func validateURL(_ raw: String) throws -> URL {
        guard let parts = URLComponents(
                  string: raw.trimmingCharacters(in: .whitespacesAndNewlines)),
              parts.scheme?.lowercased() == "https",
              parts.host?.lowercased() == productionHost,
              parts.port == nil || parts.port == 443,
              parts.user == nil, parts.password == nil,
              (parts.path == "" || parts.path == "/"),
              parts.query == nil, parts.fragment == nil,
              let url = URL(string: productionURL) else {
            throw StagingSafetyError.unsafeEndpoint
        }
        return url
    }

    /// Client-side precondition, not proof of server DB isolation.
    /// Only a dedicated non-admin account with one visible test book can send.
    static func requireBookScope(books: [CloudBook], account: Account,
                                 sourceBook: CloudBook) throws -> ScopedAccountEvidence {
        let role = (account.systemRole ?? "").uppercased()
        guard role == "USER" else {
            throw StagingSafetyError.privilegedAccount
        }
        guard books.count == 1, let book = books.first else {
            throw StagingSafetyError.multipleBookAccess
        }
        guard book.id == sourceBook.id, book.name == sourceBook.name,
              book.isAvailable, book.name.hasPrefix(TestWritePolicy.prefix),
              book.role == "OWNER" || book.role == "EDITOR",
              book.id.count >= 8 else {
            throw StagingSafetyError.inaccessibleBook
        }
        return ScopedAccountEvidence(
            instanceID: "production-scoped-testbook:" + book.id,
            bookID: book.id)
    }

    static func requireMatchingDraft(_ draft: TestDraft, sourceBook: CloudBook,
                                     stageBook: CloudBook) throws {
        guard draft.kind == .purchase else { throw StagingSafetyError.invalidDraft }
        try TestWritePolicy.validate(draft, book: sourceBook)
        guard draft.bookID == stageBook.id,
              sourceBook.id == stageBook.id,
              stageBook.name == sourceBook.name,
              stageBook.name.hasPrefix(TestWritePolicy.prefix) else {
            throw StagingSafetyError.wrongSourceBook
        }
    }

    /// Normal test-book operation: a fresh cloud readback must validate
    /// EVERY prior upload. No arbitrary three-order cap or human checklist.
    /// Unsafe/unfinished orders remain blocked until reconciled.
    static func authorizeNextOrder(existing: [StagingPurchaseJob],
                                   audits: [String: PurchaseReadbackAudit],
                                   bookID: String, username: String) throws {
        for job in existing {
            guard job.bookID == bookID,
                  job.stageHost == productionURL,
                  job.stageInstanceID == "production-scoped-testbook:" + bookID,
                  job.stageUsername.lowercased() == username.lowercased(),
                  job.state == .complete,
                  let audit = audits[job.id], audit.complete,
                  audit.bookID == bookID else {
                throw StagingSafetyError.needsReview(
                    "测试账本有未完成或异常采购，请先只读核对该笔订单后再提交新单")
            }
        }
    }

    /// Use deterministic, signed-Int64-safe remote numeric IDs from the
    /// immutable local draft UUID. Both parent and child retain these IDs
    /// across timeout/crash. The parent ID is copied to purchase_item.order_id.
    static func numericID(_ uuid: String, suffix: String) -> Int64 {
        let digest = SHA256.hash(data: Data((uuid + "|" + suffix).utf8))
        var value: UInt64 = 0
        for byte in digest.prefix(7) { value = (value << 8) | UInt64(byte) }
        return Int64(3_000_000_000_000 + value % 1_000_000_000_000)
    }
}

enum StagingUploadState: String, Codable, Hashable {
    case ready
    case sendingOrder
    case orderConfirmed
    case sendingItem
    case complete
    case needsReview

    var title: String {
        switch self {
        case .ready: return "待上传采购订单"
        case .sendingOrder: return "订单结果待核对"
        case .orderConfirmed: return "订单已确认，待上传明细"
        case .sendingItem: return "明细结果待核对"
        case .complete: return "服务器订单和明细均已确认"
        case .needsReview: return "冲突或结果不确定，需人工核对"
        }
    }
}

/// Immutable identity & payload after staging. Never dynamically overwrite
/// remote IDs or rebuild a different financial amount on retry.
struct StagingPurchaseExtraItem: Codable, Equatable {
    let syncID: String
    let localID: Int64
    let payload: [String: JSONValue]
}

struct StagingPurchaseJob: Codable, Identifiable {
    let id: String
    let sourceAccount: String
    let bookID: String
    let stageHost: String
    let stageInstanceID: String
    let stageUsername: String
    let deviceID: String
    let orderSyncID: String
    let itemSyncID: String
    let orderRemoteID: Int64
    let itemRemoteID: Int64
    let orderPayload: [String: JSONValue]
    let itemPayload: [String: JSONValue]
    let createdAt: Date
    var state: StagingUploadState
    var lastMessage: String
    /// Human confirmation only; never treated as an Android API acknowledgement.
    var androidCheckedAt: Date? = nil
    /// Server's canonical parent numeric ID, frozen BEFORE the first child
    /// upload. Nil in V0.2.2/V0.2.3 jobs, which still decode unchanged.
    var childParentID: Int64? = nil
    /// Irreversible marker: a child network request may have reached the
    /// server. Missing readback after timeout is not proof of non-delivery.
    var itemRequestStarted: Bool? = nil
    /// Structured manual observation rather than a blind confirmation tap.
    /// Older V0.2.4 jobs decode with nil and require one-time reinspection.
    var androidReview: AndroidPurchaseReviewEvidence? = nil
    /// Extra children of one multi-line order; nil for all existing jobs.
    var extraItems: [StagingPurchaseExtraItem]? = nil
    /// Once an item ID appears here it can NEVER be removed or reordered.
    /// Nil for legacy single-item jobs.
    var startedItemIDs: [String]? = nil
}

struct StageBookReference {
    let syncID: String
    let numericID: Int64
    let name: String
}

enum StagingPayloadBuilder {
    static func reference(table: String, syncID: String,
                          events: [SyncEvent]) throws -> StageBookReference {
        guard let event = events.first(where: {
            $0.tableName == table && $0.syncId == syncID && !$0.isDeletion
        }), let numeric = event.payload["id"]?.numberValue,
              numeric.isFinite, numeric > 0,
              numeric.rounded() == numeric,
              numeric <= Double(Int64.max),
              let name = event.payload["name"]?.text, !name.isEmpty else {
            throw StagingSafetyError.staleReferences
        }
        return StageBookReference(syncID: syncID, numericID: Int64(numeric), name: name)
    }

    static func prepare(_ draft: TestDraft,
                        sourceBook: CloudBook, stageBook: CloudBook,
                        stageHost: String, scope: ScopedAccountEvidence, username: String,
                        deviceID: String, available: [SyncEvent],
                        now: Date = Date()) throws -> StagingPurchaseJob {
        if draft.purchaseItems != nil {
            return try prepareMulti(draft, sourceBook: sourceBook,
                                    stageBook: stageBook, stageHost: stageHost,
                                    scope: scope, username: username,
                                    deviceID: deviceID, available: available,
                                    now: now)
        }
        try StagingPurchasePolicy.requireMatchingDraft(draft, sourceBook: sourceBook,
                                                       stageBook: stageBook)
        guard scope.bookID == stageBook.id,
              scope.instanceID == "production-scoped-testbook:" + stageBook.id,
              stageHost == StagingPurchasePolicy.productionURL else {
            throw StagingSafetyError.unsafeEndpoint
        }
        guard !deviceID.isEmpty, !username.isEmpty else {
            throw StagingSafetyError.invalidDraft
        }
        let buyer = try reference(table: "partner",
                                  syncID: draft.fields["buyer_sync_id"] ?? "",
                                  events: available)
        let fruit = try reference(table: "fruit",
                                  syncID: draft.fields["fruit_sync_id"] ?? "",
                                  events: available)
        let total = try TestWritePolicy.money(draft.fields["total_cost"] ?? "",
                                              label: "总价")
        let quantity = try TestWritePolicy.count(draft.fields["quantity"] ?? "",
                                                 label: "数量")
        let weight = try TestWritePolicy.money(draft.fields["unit_weight_jin"] ?? "0",
                                               label: "每件重量")
        let orderID = StagingPurchasePolicy.numericID(draft.id, suffix: "order")
        let itemID = StagingPurchasePolicy.numericID(draft.id, suffix: "item")
        let orderSync = "ios-stage-order-" + draft.id
        let itemSync = "ios-stage-item-" + draft.id
        let milliseconds = Int64(now.timeIntervalSince1970 * 1000)
        let common: [String: JSONValue] = [
            "deleted": .number(0), "sync_status": .number(0),
            "created_at": .number(Double(milliseconds)),
            "updated_at": .number(Double(milliseconds)),
            "row_version": .number(1),
            "modified_by": .string(deviceID)
        ]
        var order = common
        order["id"] = .number(Double(orderID))
        order["date"] = .string(draft.date)
        order["buyer_id"] = .number(Double(buyer.numericID))
        order["buyer_name"] = .string(buyer.name)
        order["store_id"] = .number(0)
        order["store_name"] = .string("共用货品")
        order["total_cost"] = .number(total)
        order["remark"] = .string(draft.fields["remark"] ?? "")
        order["status"] = .number(0)
        order["sync_id"] = .string(orderSync)

        var item = common
        item["id"] = .number(Double(itemID))
        item["order_id"] = .number(Double(orderID))
        item["fruit_id"] = .number(Double(fruit.numericID))
        item["fruit_name"] = .string(fruit.name)
        item["unit"] = .string(draft.fields["unit"] ?? "件")
        item["quantity"] = .number(quantity)
        item["total_cost"] = .number(total)
        item["unit_price"] = .number(total / quantity)
        item["unit_weight_jin"] = .number(weight)
        item["sync_id"] = .string(itemSync)

        return StagingPurchaseJob(
            id: draft.id, sourceAccount: draft.account, bookID: stageBook.id,
            stageHost: stageHost, stageInstanceID: scope.instanceID,
            stageUsername: username, deviceID: deviceID,
            orderSyncID: orderSync, itemSyncID: itemSync,
            orderRemoteID: orderID, itemRemoteID: itemID,
            orderPayload: order, itemPayload: item, createdAt: now,
            state: .ready, lastMessage: "已验证专用账号和测试账本商品关联，等待首次小额上传确认")
    }

    /// Each child has its own immutable remote sync identity and original
    /// line amount. Only the canonical order_id is replaced on send.
    static func itemEvents(_ job: StagingPurchaseJob) -> [SyncEvent] {
        var items = [event(job, item: true)]
        for extra in job.extraItems ?? [] {
            var payload = extra.payload
            if let parent = job.childParentID {
                payload["order_id"] = .number(Double(parent))
            }
            items.append(SyncEvent(tableName: "purchase_item",
                                   syncId: extra.syncID, rowVersion: 1,
                                   operation: "UPSERT", payload: payload))
        }
        return items
    }

    private static func prepareMulti(
        _ draft: TestDraft, sourceBook: CloudBook,
        stageBook: CloudBook, stageHost: String,
        scope: ScopedAccountEvidence, username: String,
        deviceID: String, available: [SyncEvent],
        now: Date) throws -> StagingPurchaseJob {
        try StagingPurchasePolicy.requireMatchingDraft(
            draft, sourceBook: sourceBook, stageBook: stageBook)
        guard scope.bookID == stageBook.id,
              scope.instanceID == "production-scoped-testbook:" + stageBook.id,
              stageHost == StagingPurchasePolicy.productionURL,
              !deviceID.isEmpty, !username.isEmpty,
              let lines = draft.purchaseItems,
              !lines.isEmpty else { throw StagingSafetyError.invalidDraft }
        let total = try PurchaseDraftPolicy.total(lines)
        let buyer = try reference(
            table: "partner", syncID: draft.fields["buyer_sync_id"] ?? "",
            events: available)
        let orderID = StagingPurchasePolicy.numericID(
            draft.id, suffix: "order")
        let orderSync = "ios-stage-order-" + draft.id
        let ms = Int64(now.timeIntervalSince1970 * 1000)
        let common: [String: JSONValue] = [
            "deleted": .number(0), "sync_status": .number(0),
            "created_at": .number(Double(ms)),
            "updated_at": .number(Double(ms)),
            "row_version": .number(1), "modified_by": .string(deviceID)
        ]
        var order = common
        order["id"] = .number(Double(orderID))
        order["date"] = .string(draft.date)
        order["buyer_id"] = .number(Double(buyer.numericID))
        order["buyer_name"] = .string(buyer.name)
        order["store_id"] = .number(0)
        order["store_name"] = .string("共用货品")
        order["total_cost"] = .number(
            NSDecimalNumber(decimal: total).doubleValue)
        order["remark"] = .string(draft.fields["remark"] ?? "")
        order["status"] = .number(0)
        order["sync_id"] = .string(orderSync)

        var children: [StagingPurchaseExtraItem] = []
        for line in lines {
            let fruit = try reference(table: "fruit",
                                      syncID: line.fruitSyncID,
                                      events: available)
            let lineID = StagingPurchasePolicy.numericID(
                draft.id, suffix: "item|" + line.id)
            let lineSync = "ios-stage-item-" + draft.id + "-" + line.id
            let qty = try PurchaseDraftPolicy.quantity(line.quantity)
            let price = try PurchaseDraftPolicy.unitPrice(line.unitPrice)
            let weight = try PurchaseDraftPolicy.unitWeight(line.unitWeightJin)
            let subtotal = try PurchaseDraftPolicy.subtotal(line)
            var payload = common
            payload["id"] = .number(Double(lineID))
            payload["order_id"] = .number(Double(orderID))
            payload["fruit_id"] = .number(Double(fruit.numericID))
            payload["fruit_name"] = .string(fruit.name)
            payload["unit"] = .string(line.unit)
            payload["quantity"] = .number(
                NSDecimalNumber(decimal: qty).doubleValue)
            payload["unit_price"] = .number(
                NSDecimalNumber(decimal: price).doubleValue)
            payload["unit_weight_jin"] = .number(
                NSDecimalNumber(decimal: weight).doubleValue)
            payload["total_cost"] = .number(
                NSDecimalNumber(decimal: subtotal).doubleValue)
            payload["sync_id"] = .string(lineSync)
            children.append(StagingPurchaseExtraItem(
                syncID: lineSync, localID: lineID, payload: payload))
        }
        guard let first = children.first else {
            throw StagingSafetyError.invalidDraft
        }
        return StagingPurchaseJob(
            id: draft.id, sourceAccount: draft.account,
            bookID: stageBook.id, stageHost: stageHost,
            stageInstanceID: scope.instanceID,
            stageUsername: username, deviceID: deviceID,
            orderSyncID: orderSync, itemSyncID: first.syncID,
            orderRemoteID: orderID, itemRemoteID: first.localID,
            orderPayload: order, itemPayload: first.payload,
            createdAt: now, state: .ready,
            lastMessage: "已校验测试账本与 " + String(lines.count) +
                         " 条商品，等待提交",
            extraItems: Array(children.dropFirst()), startedItemIDs: [])
    }

    static func event(_ job: StagingPurchaseJob, item: Bool) -> SyncEvent {
        var payload = item ? job.itemPayload : job.orderPayload
        if item, let canonicalID = job.childParentID {
            payload["order_id"] = .number(Double(canonicalID))
        }
        return SyncEvent(tableName: item ? "purchase_item" : "purchase_order",
                         syncId: item ? job.itemSyncID : job.orderSyncID,
                         rowVersion: 1, operation: "UPSERT", payload: payload)
    }
}
