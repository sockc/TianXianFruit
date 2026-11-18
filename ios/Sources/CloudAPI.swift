import Foundation
import CryptoKit

struct CloudAPIError: LocalizedError {
    let status: Int
    let message: String

    var errorDescription: String? {
        if status == 401 { return "账号登录已失效，请重新登录；只读数据未修改" }
        if status == 403 { return "当前账号没有读取此账本的权限" }
        return message.isEmpty ? "服务器返回 HTTP \(status)" : "HTTP \(status)：\(message)"
    }
}

/// All network operations are allow-listed here. This client cannot send
/// sales, purchase, inventory, overnight or settlement mutations.
final class CloudAPI: NSObject, URLSessionTaskDelegate {
    private let root = "https://sync.830888.xyz"
    private lazy var session: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        config.urlCache = nil
        config.httpCookieStorage = nil
        config.timeoutIntervalForRequest = 25
        config.timeoutIntervalForResource = 60
        return URLSession(configuration: config, delegate: self, delegateQueue: nil)
    }()

    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest,
                    completionHandler: @escaping (URLRequest?) -> Void) {
        // Never forward a bearer token to an unexpected redirect destination.
        completionHandler(nil)
    }

    private func request(_ method: String, _ path: String,
                         token: String? = nil, body: [String: String]? = nil,
                         query: [URLQueryItem] = []) async throws -> Data {
        let permittedGET = path == "/api/v1/auth/me"
            || path == "/api/v1/books"
            || path == "/api/v1/sync/pull"
            || path == "/api/v1/weather/overview"
            || (path.hasPrefix("/api/v1/books/") && (
                path.hasSuffix("/customer-analytics/capabilities")
                || path.hasSuffix("/customer-analytics/changes")))
        let permittedPOST = path == "/api/v1/auth/login"
            || path == "/api/v1/devices/register"
        guard (method == "GET" && permittedGET) || (method == "POST" && permittedPOST),
              let base = URL(string: root + path),
              var parts = URLComponents(url: base, resolvingAgainstBaseURL: false) else {
            throw CloudAPIError(status: 0, message: "未授权的客户端请求")
        }
        parts.queryItems = query.isEmpty ? nil : query
        guard let url = parts.url, url.scheme == "https", url.host == "sync.830888.xyz" else {
            throw CloudAPIError(status: 0, message: "服务器地址校验失败")
        }
        var req = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData)
        req.httpMethod = method
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        if let token { req.setValue("Bearer " + token, forHTTPHeaderField: "Authorization") }
        if let body {
            req.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
            req.httpBody = try JSONEncoder().encode(body)
        }
        let (data, response) = try await session.data(for: req)
        guard let http = response as? HTTPURLResponse else {
            throw CloudAPIError(status: 0, message: "服务器响应无效")
        }
        guard (200...299).contains(http.statusCode) else {
            let detail = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
            let message = detail?["detail"] as? String ?? ""
            throw CloudAPIError(status: http.statusCode, message: String(message.prefix(180)))
        }
        return data
    }

    private func decode<T: Decodable>(_ type: T.Type, from data: Data) throws -> T {
        let decoder = JSONDecoder()
        return try decoder.decode(type, from: data)
    }

    func account(token: String) async throws -> Account {
        let data = try await request("GET", "/api/v1/auth/me", token: token)
        return try decode(Account.self, from: data)
    }

    func books(token: String) async throws -> [CloudBook] {
        let data = try await request("GET", "/api/v1/books", token: token)
        let list = try decode([CloudBook].self, from: data)
        return list.filter(\.isAvailable)
    }

    func signIn(username: String, password: String) async throws -> (String, Account) {
        let trimmed = username.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty && !password.isEmpty else {
            throw CloudAPIError(status: 0, message: "请输入账号和密码")
        }
        let localID = try SecureStore.deviceIdentifier()
        let identifier = Data((localID + "|" + trimmed.lowercased()).utf8)
        let scopedID = SHA256.hash(data: identifier)
            .map { String(format: "%02x", $0) }.joined()
        let deviceName = "天鲜账本 iPhone"
        let loginData = try await request(
            "POST", "/api/v1/auth/login", body: [
                "username": trimmed, "password": password,
                "device_id": scopedID, "device_name": deviceName,
                "platform": "ios", "app_version": "0.2.4"
            ])
        let auth: LoginResponse = try decode(LoginResponse.self, from: loginData)
        let me = try await account(token: auth.accessToken)
        // Registration creates only the server-side device entry; it never mutates ledger rows.
        _ = try await request("POST", "/api/v1/devices/register",
                              token: auth.accessToken, body: [
            "device_id": scopedID, "name": deviceName,
            "platform": "ios", "app_version": "0.2.4"
        ])
        return (auth.accessToken, me)
    }

    func pull(token: String, bookID: String, since: Int64) async throws -> SyncPage {
        guard since >= 0 else { throw CloudAPIError(status: 0, message: "同步游标非法") }
        let params = [
            URLQueryItem(name: "book_id", value: bookID),
            URLQueryItem(name: "since", value: String(since)),
            URLQueryItem(name: "limit", value: "200")
        ]
        let data = try await request(
            "GET", "/api/v1/sync/pull", token: token, query: params)
        return try decode(SyncPage.self, from: data)
    }
    private func customerEndpoint(_ bookID: String) throws -> String {
        guard !bookID.isEmpty,
              let safeID = bookID.addingPercentEncoding(withAllowedCharacters: .alphanumerics)
        else { throw CloudAPIError(status: 0, message: "账本 ID 无效") }
        return "/api/v1/books/\(safeID)/customer-analytics"
    }

    func customerCapabilities(token: String, bookID: String) async throws -> CustomerCapabilities {
        let data = try await request("GET", customerEndpoint(bookID) + "/capabilities", token: token)
        let capabilities = try decode(CustomerCapabilities.self, from: data)
        guard capabilities.supported, capabilities.version == 1,
              capabilities.offers("receipt_pull") else {
            throw CloudAPIError(status: 0, message: "服务器不支持客户账单读取；未修改已缓存数据")
        }
        return capabilities
    }

    func customerChanges(token: String, bookID: String, since: Int64,
                         capabilities: CustomerCapabilities) async throws -> CustomerChangePage {
        guard since >= 0 else {
            throw CloudAPIError(status: 0, message: "客户同步游标无效")
        }
        var query = [
            URLQueryItem(name: "cursor", value: String(since)),
            URLQueryItem(name: "limit", value: "200"),
            URLQueryItem(name: "include_assignments", value: "true")
        ]
        for (feature, flag) in [
            ("bill_collector", "include_collectors"),
            ("receipt_analysis_state", "include_analysis"),
            ("analysis_settings", "include_settings"),
            ("bill_source_links", "include_source_links")
        ] where capabilities.offers(feature) {
            query.append(URLQueryItem(name: flag, value: "true"))
        }
        let data = try await request("GET", customerEndpoint(bookID) + "/changes",
                                     token: token, query: query)
        return try decode(CustomerChangePage.self, from: data)
    }

    /// Same existing read-only weather overview endpoint as Android.
    /// We never store forecasts in the cloud or send coordinates to third parties.
    func weatherForecast(token: String, bookID: String,
                         latitude: Double, longitude: Double,
                         date: String) async throws -> WeatherForecastResponse {
        guard !bookID.isEmpty, BusinessDates.parse(date) != nil,
              latitude.isFinite && longitude.isFinite,
              (-90...90).contains(latitude), (-180...180).contains(longitude),
              latitude != 0, longitude != 0 else {
            throw CloudAPIError(status: 0, message: "天气位置坐标或日期无效")
        }
        let data = try await request("GET", "/api/v1/weather/overview",
                                     token: token, query: [
            URLQueryItem(name: "book_id", value: bookID),
            URLQueryItem(name: "lat", value: String(latitude)),
            URLQueryItem(name: "lon", value: String(longitude)),
            URLQueryItem(name: "date", value: date),
            URLQueryItem(name: "hours", value: "120")
        ])
        return try decode(WeatherForecastResponse.self, from: data)
    }

}
