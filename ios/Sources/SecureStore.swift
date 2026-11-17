import Foundation
import Security

enum SecureStoreError: LocalizedError {
    case failure(OSStatus)

    var errorDescription: String? {
        switch self {
        case .failure(let status): return "本机安全存储不可用（\(status)），登录凭据未保存"
        }
    }
}

enum SecureStore {
    private static let service = "com.tianxian.fruit.ios"

    static func read(_ name: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: name,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let bytes = result as? Data else { return nil }
        return String(data: bytes, encoding: .utf8)
    }

    static func write(_ name: String, value: String) throws {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: name
        ]
        let data = Data(value.utf8)
        let add = query.merging([
            kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
            kSecValueData as String: data
        ]) { _, replacement in replacement }

        let status = SecItemAdd(add as CFDictionary, nil)
        if status == errSecDuplicateItem {
            let updated = SecItemUpdate(query as CFDictionary,
                                        [kSecValueData as String: data] as CFDictionary)
            guard updated == errSecSuccess else { throw SecureStoreError.failure(updated) }
        } else if status != errSecSuccess {
            throw SecureStoreError.failure(status)
        }
    }

    static func erase(_ name: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: name
        ]
        SecItemDelete(query as CFDictionary)
    }

    static func deviceIdentifier() throws -> String {
        if let existing = read("device-id"), !existing.isEmpty { return existing }
        let created = UUID().uuidString
        try write("device-id", value: created)
        return created
    }
}
