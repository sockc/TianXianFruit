import Foundation
import CryptoKit

struct StagingSession {
    let token: String
    let username: String
    let deviceID: String
    let book: CloudBook
    let proof: StagingProof
    let host: String
}

struct StageSyncAck: Decodable {
    struct Accepted: Decodable {
        let tableName: String
        let syncID: String
        let rowVersion: Int64
        enum CodingKeys: String, CodingKey {
            case tableName = "table_name"
            case syncID = "sync_id"
            case rowVersion = "row_version"
        }
    }
    let accepted: [Accepted]
    let conflicts: [StageConflict]

    struct StageConflict: Decodable {
        let tableName: String
        let syncID: String
        let serverVersion: Int64
        enum CodingKeys: String, CodingKey {
            case tableName = "table_name"
            case syncID = "sync_id"
            case serverVersion = "server_version"
        }
    }

    func isAccepted(_ event: SyncEvent) -> Bool {
        conflicts.isEmpty && accepted.contains {
            $0.tableName == event.tableName &&
            $0.syncID == event.syncId &&
            $0.rowVersion == event.rowVersion
        }
    }
}

/// Separate ephemeral staging credentials. Production CloudAPI credentials
/// and cookies never enter this class. The production URL is forbidden.
final class StagingPurchaseAPI: NSObject, URLSessionTaskDelegate {
    let base: URL
    private lazy var session: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.urlCache = nil
        config.httpCookieStorage = nil
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        config.timeoutIntervalForRequest = 20
        config.timeoutIntervalForResource = 60
        return URLSession(configuration: config, delegate: self, delegateQueue: nil)
    }()

    init(endpoint: String) throws {
        base = try StagingPurchasePolicy.validateURL(endpoint)
        super.init()
    }

    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest,
                    completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }

    private func send(_ method: String, _ path: String, token: String? = nil,
                      query: [URLQueryItem] = [],
                      payload: Data? = nil) async throws -> Data {
        let getAllowed = ["/api/v1/ios-test/attestation", "/api/v1/auth/me",
                          "/api/v1/books", "/api/v1/sync/pull"]
        let postAllowed = ["/api/v1/auth/login", "/api/v1/devices/register",
                           "/api/v1/sync/push"]
        guard (method == "GET" && getAllowed.contains(path)) ||
              (method == "POST" && postAllowed.contains(path)) else {
            throw StagingSafetyError.unexpectedResponse("未允许的测试环境 API")
        }
        guard let source = URL(string: path, relativeTo: base)?.absoluteURL,
              var parts = URLComponents(url: source, resolvingAgainstBaseURL: false) else {
            throw StagingSafetyError.unsafeEndpoint
        }
        parts.queryItems = query.isEmpty ? nil : query
        guard let url = parts.url,
              url.scheme == "https",
              url.host?.lowercased() == base.host?.lowercased(),
              url.port == base.port else { throw StagingSafetyError.unsafeEndpoint }
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("no-store", forHTTPHeaderField: "Cache-Control")
        if let token {
            request.setValue("Bearer " + token, forHTTPHeaderField: "Authorization")
        }
        if let payload {
            request.httpBody = payload
            request.setValue("application/json; charset=utf-8",
                             forHTTPHeaderField: "Content-Type")
        }
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw StagingSafetyError.unexpectedResponse("无 HTTP 返回")
        }
        guard (200...299).contains(http.statusCode) else {
            throw StagingSafetyError.unexpectedResponse(
                "HTTP " + String(http.statusCode) + "，请在隔离测试服务端检查日志")
        }
        return data
    }

    private func get<T: Decodable>(_ type: T.Type, path: String,
                                   token: String? = nil,
                                   query: [URLQueryItem] = []) async throws -> T {
        let data = try await send("GET", path, token: token, query: query)
        return try JSONDecoder().decode(type, from: data)
    }

    /// Public attestation is REQUIRED before stage account credentials
    /// are sent. Endpoint absence -> fail closed (no credentials, no write).
    func attestation() async throws -> StagingProof {
        do {
            return try await get(StagingProof.self, path: "/api/v1/ios-test/attestation")
        } catch {
            throw StagingSafetyError.missingProof
        }
    }

    func login(username: String, password: String,
               sourceBook: CloudBook) async throws -> StagingSession {
        let proof = try await attestation()
        guard proof.deploymentScope == "isolated_test",
              proof.isolatedFromProduction, proof.iosPurchaseWriteEnabled,
              proof.allowedBookIDs.contains(sourceBook.id),
              !proof.instanceID.isEmpty else {
            throw StagingSafetyError.notIsolated
        }
        let normalized = username.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalized.isEmpty, !password.isEmpty else {
            throw StagingSafetyError.unexpectedResponse("请输入测试服务器独立账号密码")
        }
        let local = try SecureStore.deviceIdentifier()
        let identity = Data((local + "|" + base.absoluteString + "|" +
                             normalized.lowercased()).utf8)
        let deviceID = SHA256.hash(data: identity).map {
            String(format: "%02x", $0)
        }.joined()
        struct LoginPayload: Encodable {
            let username: String
            let password: String
            let deviceID: String
            let deviceName: String
            let platform: String
            let appVersion: String
            enum CodingKeys: String, CodingKey {
                case username, password, platform
                case deviceID = "device_id"
                case deviceName = "device_name"
                case appVersion = "app_version"
            }
        }
        let authBytes = try await send(
            "POST", "/api/v1/auth/login",
            payload: JSONEncoder().encode(LoginPayload(
                username: normalized, password: password, deviceID: deviceID,
                deviceName: "天鲜 iOS 隔离测试", platform: "ios", appVersion: "0.2.1")))
        let auth = try JSONDecoder().decode(LoginResponse.self, from: authBytes)
        let account = try await get(Account.self, path: "/api/v1/auth/me",
                                    token: auth.accessToken)
        guard account.username.lowercased() == normalized.lowercased() else {
            throw StagingSafetyError.unexpectedResponse("服务器返回了不同测试账号")
        }
        let books = try await get([CloudBook].self, path: "/api/v1/books",
                                  token: auth.accessToken)
        guard let book = books.first(where: { $0.id == sourceBook.id }) else {
            throw StagingSafetyError.wrongSourceBook
        }
        try StagingPurchasePolicy.requireProof(proof, book: book)
        guard book.name == sourceBook.name else {
            throw StagingSafetyError.wrongSourceBook
        }
        struct DeviceRegistration: Encodable {
            let deviceID: String
            let name: String
            let platform: String
            let appVersion: String
            enum CodingKeys: String, CodingKey {
                case name, platform
                case deviceID = "device_id"
                case appVersion = "app_version"
            }
        }
        _ = try await send("POST", "/api/v1/devices/register",
                           token: auth.accessToken,
                           payload: JSONEncoder().encode(DeviceRegistration(
                             deviceID: deviceID, name: "天鲜 iOS 隔离测试",
                             platform: "ios", appVersion: "0.2.1")))
        return StagingSession(token: auth.accessToken, username: account.username,
                              deviceID: deviceID, book: book,
                              proof: proof, host: base.absoluteString)
    }

    func validateSession(_ session: StagingSession) async throws {
        guard session.host == base.absoluteString else {
            throw StagingSafetyError.unsafeEndpoint
        }
        let newProof = try await attestation()
        try StagingPurchasePolicy.requireProof(newProof, book: session.book)
        guard newProof.instanceID == session.proof.instanceID else {
            throw StagingSafetyError.notIsolated
        }
        let books = try await get([CloudBook].self, path: "/api/v1/books",
                                  token: session.token)
        guard let book = books.first(where: { $0.id == session.book.id }) else {
            throw StagingSafetyError.inaccessibleBook
        }
        try StagingPurchasePolicy.requireProof(newProof, book: book)
    }

    func pullAll(_ auth: StagingSession) async throws -> [SyncEvent] {
        var since: Int64 = 0
        var all: [SyncEvent] = []
        var complete = false
        for _ in 0..<200 {
            let page = try await get(SyncPage.self, path: "/api/v1/sync/pull",
                                     token: auth.token, query: [
                URLQueryItem(name: "book_id", value: auth.book.id),
                URLQueryItem(name: "since", value: String(since)),
                URLQueryItem(name: "limit", value: "200")
            ])
            guard page.nextCursor >= since, !(page.hasMore && page.nextCursor == since) else {
                throw StagingSafetyError.unexpectedResponse("回读游标无效")
            }
            all.append(contentsOf: page.events)
            since = page.nextCursor
            if !page.hasMore { complete = true; break }
        }
        guard complete else {
            throw StagingSafetyError.unexpectedResponse("测试账本记录过多，停止上传")
        }
        // Staging book only. Revisions/tombstones must be applied before
        // we trust numeric IDs or infer that an upload arrived remotely.
        var state = ReadOnlySnapshot()
        state.apply(all)
        return Array(state.records.values)
    }

    func pushSingle(_ event: SyncEvent, auth: StagingSession) async throws -> StageSyncAck {
        try await validateSession(auth)
        struct Envelope: Encodable {
            let bookID: String
            let deviceID: String
            let changes: [SyncEvent]
            enum CodingKeys: String, CodingKey {
                case changes
                case bookID = "book_id"
                case deviceID = "device_id"
            }
        }
        let body = try JSONEncoder().encode(Envelope(
            bookID: auth.book.id, deviceID: auth.deviceID, changes: [event]))
        let data = try await send("POST", "/api/v1/sync/push",
                                  token: auth.token, payload: body)
        return try JSONDecoder().decode(StageSyncAck.self, from: data)
    }

    func verify(_ event: SyncEvent, auth: StagingSession) async throws -> Bool {
        let events = try await pullAll(auth)
        guard let found = events.first(where: {
            $0.tableName == event.tableName && $0.syncId == event.syncId
        }) else { return false }
        guard !found.isDeletion, found.rowVersion >= event.rowVersion else { return false }
        // Receipt from server must contain at least all financial business
        // values and the canonical parent numeric ID. An ACK alone is not enough.
        let fields = event.tableName == "purchase_order"
            ? ["id", "date", "buyer_id", "total_cost", "sync_id"]
            : ["id", "order_id", "fruit_id", "quantity", "total_cost", "sync_id"]
        return fields.allSatisfy { field in
            let left = event.payload[field]
            let right = found.payload[field]
            if let left, let right {
                if let a = left.numberValue, let b = right.numberValue {
                    return abs(a - b) < 0.0001
                }
                return left == right
            }
            return false
        }
    }
}
