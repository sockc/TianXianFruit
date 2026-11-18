import Foundation
import CryptoKit

/// Explicit contract: staging server must expose a protected readiness proof
/// at GET /api/v1/ios-test/attestation. A DNS alias, a test ledger *name*
/// or a different URL alone is not evidence of database isolation.
struct StagingProof: Decodable {
    let deploymentScope: String
    let isolatedFromProduction: Bool
    let iosPurchaseWriteEnabled: Bool
    let instanceID: String
    let allowedBookIDs: [String]

    enum CodingKeys: String, CodingKey {
        case deploymentScope = "deployment_scope"
        case isolatedFromProduction = "isolated_from_production"
        case iosPurchaseWriteEnabled = "ios_purchase_write_enabled"
        case instanceID = "instance_id"
        case allowedBookIDs = "allowed_book_ids"
    }
}

enum StagingSafetyError: LocalizedError {
    case unsafeEndpoint
    case missingProof
    case notIsolated
    case inaccessibleBook
    case wrongSourceBook
    case staleReferences
    case invalidDraft
    case unexpectedResponse(String)
    case needsReview(String)

    var errorDescription: String? {
        switch self {
        case .unsafeEndpoint:
            return "测试服务器必须为独立 HTTPS 域名，不能是正式生产域名或本机地址"
        case .missingProof:
            return "测试服务器未提供独立环境证明；已拒绝上传"
        case .notIsolated:
            return "服务器未声明独立测试部署及采购写入白名单，已拒绝上传"
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
    static let forbiddenHost = "sync.830888.xyz"

    static func validateURL(_ raw: String) throws -> URL {
        guard let parts = URLComponents(string: raw.trimmingCharacters(in: .whitespacesAndNewlines)),
              parts.scheme?.lowercased() == "https", let host = parts.host?.lowercased(),
              !host.isEmpty, host != forbiddenHost,
              host != "830888.xyz", host != "localhost",
              !host.hasSuffix(".localhost"), !host.hasSuffix(".local"),
              !host.hasSuffix(".internal"), !host.contains(":"),
              !host.allSatisfy({ $0.isNumber || $0 == "." }),
              parts.port == nil || parts.port == 443,
              parts.user == nil, parts.password == nil,
              (parts.path == "" || parts.path == "/"),
              parts.query == nil, parts.fragment == nil,
              let url = parts.url else { throw StagingSafetyError.unsafeEndpoint }
        return url
    }

    static func requireProof(_ proof: StagingProof, book: CloudBook) throws {
        guard proof.deploymentScope == "isolated_test",
              proof.isolatedFromProduction,
              proof.iosPurchaseWriteEnabled,
              !proof.instanceID.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              proof.allowedBookIDs.contains(book.id) else {
            throw StagingSafetyError.notIsolated
        }
        guard book.isAvailable, book.name.hasPrefix(TestWritePolicy.prefix),
              ["OWNER", "SUPERADMIN"].contains(book.role) else {
            throw StagingSafetyError.inaccessibleBook
        }
    }

    static func requireMatchingDraft(_ draft: TestDraft, sourceBook: CloudBook,
                                     stageBook: CloudBook) throws {
        guard draft.kind == .purchase else { throw StagingSafetyError.invalidDraft }
        try TestWritePolicy.validate(draft, book: sourceBook)
        guard draft.bookID == stageBook.id,
              sourceBook.id == stageBook.id,
              stageBook.name.hasPrefix(TestWritePolicy.prefix) else {
            throw StagingSafetyError.wrongSourceBook
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
                        stageHost: String, proof: StagingProof, username: String,
                        deviceID: String, available: [SyncEvent],
                        now: Date = Date()) throws -> StagingPurchaseJob {
        try StagingPurchasePolicy.requireMatchingDraft(draft, sourceBook: sourceBook,
                                                       stageBook: stageBook)
        try StagingPurchasePolicy.requireProof(proof, book: stageBook)
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
            stageHost: stageHost, stageInstanceID: proof.instanceID,
            stageUsername: username, deviceID: deviceID,
            orderSyncID: orderSync, itemSyncID: itemSync,
            orderRemoteID: orderID, itemRemoteID: itemID,
            orderPayload: order, itemPayload: item, createdAt: now,
            state: .ready, lastMessage: "已验证隔离测试账本和商品关联，等待用户确认上传")
    }

    static func event(_ job: StagingPurchaseJob, item: Bool) -> SyncEvent {
        SyncEvent(tableName: item ? "purchase_item" : "purchase_order",
                  syncId: item ? job.itemSyncID : job.orderSyncID,
                  rowVersion: 1, operation: "UPSERT",
                  payload: item ? job.itemPayload : job.orderPayload)
    }
}
