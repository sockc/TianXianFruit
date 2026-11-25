import Foundation
import LocalAuthentication

enum TXFormalLedgerAccess {
    static func localCandidate(_ book: CloudBook?, account: Account?, offline: Bool) -> Bool {
        guard let book, account != nil, !offline, book.isAvailable else { return false }
        return ["OWNER", "EDITOR", "SUPERADMIN"].contains(book.role)
    }

    /// No book supplied is the original test-only decoding contract.
    /// A formal response must match an actual non-test selected book.
    static func scopeMatches(_ scope: String, book: CloudBook?) -> Bool {
        guard let book else { return scope == "iOS_TEST_" }
        guard book.isAvailable else { return false }
        return book.name.hasPrefix("iOS_TEST_") ? scope == "iOS_TEST_" : scope == "formal"
    }

    @MainActor
    static func confirmFinance(reason: String = "验证身份后才能修改正式账本资金记录") async throws {
        let context = LAContext()
        context.localizedCancelTitle = "取消"
        var error: NSError?
        guard context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &error) else {
            throw TXFormalLedgerError.authenticationUnavailable
        }
        do {
            guard try await context.evaluatePolicy(.deviceOwnerAuthentication,
                localizedReason: reason) else {
                throw TXFormalLedgerError.authenticationCancelled
            }
        } catch { throw TXFormalLedgerError.authenticationCancelled }
    }
}

enum TXFormalLedgerError: LocalizedError {
    case authenticationUnavailable, authenticationCancelled
    var errorDescription: String? {
        switch self {
        case .authenticationUnavailable: return "请先设置设备密码或面容／指纹识别，未修改正式账本记录"
        case .authenticationCancelled: return "身份验证未完成，未提交操作"
        }
    }
}
