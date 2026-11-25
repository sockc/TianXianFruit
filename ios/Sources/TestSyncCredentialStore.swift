import Foundation
import CryptoKit

/// The only persisted credential is the scoped test access token, never
/// the test password. This namespace is entirely separate from CloudAPI's
/// normal app access-token. Keychain protection is ThisDeviceOnly.
struct RememberedTestSession: Codable {
    let sourceAccount: String
    let bookID: String
    let username: String
    let token: String
}

enum TestSyncCredentialStore {
    static func key(account: String, bookID: String) -> String {
        let scope = account.trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased() + "|" + bookID
        let digest = SHA256.hash(data: Data(scope.utf8)).map {
            String(format: "%02x", $0)
        }.joined()
        return "testbook-sync-" + digest
    }

    static func load(account: String, bookID: String) -> RememberedTestSession? {
        guard let json = SecureStore.read(key(account: account, bookID: bookID)),
              let data = json.data(using: .utf8),
              let record = try? JSONDecoder().decode(RememberedTestSession.self, from: data),
              record.bookID == bookID,
              record.sourceAccount.lowercased() == account.lowercased(),
              !record.username.isEmpty, !record.token.isEmpty else {
            return nil
        }
        return record
    }

    static func save(_ session: StagingSession, sourceAccount: String) throws {
        let saved = RememberedTestSession(
            sourceAccount: sourceAccount, bookID: session.book.id,
            username: session.username, token: session.token)
        let bytes = try JSONEncoder().encode(saved)
        guard let json = String(data: bytes, encoding: .utf8) else {
            throw StagingSafetyError.unexpectedResponse("本机测试登录数据编码失败")
        }
        try SecureStore.write(key(account: sourceAccount, bookID: session.book.id),
                              value: json)
    }

    static func clear(account: String, bookID: String) {
        SecureStore.erase(key(account: account, bookID: bookID))
    }
}
