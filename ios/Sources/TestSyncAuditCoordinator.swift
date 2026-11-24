import Foundation
import SwiftUI

/// Consolidated cloud readback. No POST, no mutation of financial data, no
/// retries, and no changing durable outbox states.
@MainActor
final class TestSyncAuditCoordinator: ObservableObject {
    @Published private(set) var busy = false
    @Published private(set) var connected = false
    @Published private(set) var message: String?
    @Published private(set) var report: TestSyncAuditReport?

    private let storage: TestDraftStore?
    private var api: StagingPurchaseAPI?
    private var auth: StagingSession?
    private var book: CloudBook?
    private var account = ""
    private var generation = 0

    init() {
        do { storage = try TestDraftStore() }
        catch { storage = nil; message = error.localizedDescription }
    }

    func open(account: String?, book: CloudBook?) {
        generation += 1
        self.account = account ?? ""
        self.book = book
        api = nil
        auth = nil
        connected = false
        report = nil
        message = nil
    }

    private func assertCurrent(_ g: Int) throws {
        guard generation == g else {
            throw StagingSafetyError.needsReview(
                "测试账号或账本已切换，停止使用上次的核对结果")
        }
    }

    private func validateActive() throws -> (StagingPurchaseAPI, StagingSession, CloudBook, TestDraftStore) {
        guard let book, let api, let auth, let storage, connected,
              book.isAvailable,
              book.name.hasPrefix(TestWritePolicy.prefix),
              auth.book.id == book.id,
              auth.scope.bookID == book.id,
              auth.scope.instanceID == "production-scoped-testbook:" + book.id,
              auth.host == StagingPurchasePolicy.productionURL,
              auth.host == api.base.absoluteString,
              !account.isEmpty else {
            throw StagingSafetyError.wrongSourceBook
        }
        return (api, auth, book, storage)
    }

    func restore() async {
        guard !busy, let book, book.isAvailable,
              book.name.hasPrefix(TestWritePolicy.prefix),
              !account.isEmpty,
              let saved = TestSyncCredentialStore.load(
                account: account, bookID: book.id) else { return }
        let g = generation
        busy = true
        defer { busy = false }
        do {
            let transport = try StagingPurchaseAPI(
                endpoint: StagingPurchasePolicy.productionURL)
            let session = try await transport.resume(saved, sourceBook: book)
            try assertCurrent(g)
            api = transport
            auth = session
            connected = true
            message = "专用测试账号已自动验证，无需重新填写密码"
            await readbackInSession(transport: transport, session: session,
                                    book: book, generation: g)
        } catch {
            guard g == generation else { return }
            if (error as? CloudAPIError)?.status == 401 ||
               (error as? CloudAPIError)?.status == 403 ||
               error is StagingSafetyError {
                TestSyncCredentialStore.clear(account: account, bookID: book.id)
            }
            message = "自动验证失败：" + error.localizedDescription
            report = nil
        }
    }

    func login(username: String, password: String) async {
        guard !busy, !username.isEmpty, !password.isEmpty,
              let book, !account.isEmpty else { return }
        let g = generation
        busy = true
        defer { busy = false }
        do {
            let transport = try StagingPurchaseAPI(
                endpoint: StagingPurchasePolicy.productionURL)
            let session = try await transport.login(
                username: username, password: password, sourceBook: book)
            try assertCurrent(g)
            try TestSyncCredentialStore.save(session, sourceAccount: account)
            api = transport
            auth = session
            connected = true
            await readbackInSession(transport: transport, session: session,
                                    book: book, generation: g)
        } catch {
            if g == generation {
                report = nil
                message = "测试登录失败：" + error.localizedDescription
            }
        }
    }

    func refresh() async {
        guard !busy else { return }
        let g = generation
        busy = true
        defer { busy = false }
        do {
            let (transport, session, book, _) = try validateActive()
            await readbackInSession(transport: transport, session: session,
                                    book: book, generation: g)
        } catch {
            if g == generation {
                report = nil
                message = error.localizedDescription
            }
        }
    }

    private func readbackInSession(transport: StagingPurchaseAPI,
                                   session: StagingSession, book: CloudBook,
                                   generation g: Int) async {
        do {
            // Auth and permissions are revalidated every time. All four
            // categories share exactly one complete materialized pull.
            try await transport.validateSession(session)
            try assertCurrent(g)
            let events = try await transport.pullAll(session)
            try assertCurrent(g)
            guard let storage, book.id == session.book.id else {
                throw StagingSafetyError.wrongSourceBook
            }
            let sales = try storage.businessJobs(
                account: account, book: book.id)
            let edits = try storage.businessEditJobs(
                account: account, book: book.id)
            let purchases = try storage.stagingJobs(
                account: account, book: book.id)
            let remarks = try storage.purchaseRemarkJobs(
                account: account, book: book.id)
            let result = TestSyncAuditPolicy.audit(
                businessUploads: sales, businessEdits: edits,
                purchases: purchases, remarks: remarks,
                events: events, session: session)
            try assertCurrent(g)
            report = result
            if result.items.isEmpty {
                message = "测试账本尚无本机提交任务；没有向云端写入任何记录"
            } else {
                message = "只读核对完成；共 " + String(result.items.count) +
                    " 项，无任何自动重发或状态修改"
            }
        } catch {
            if g == generation {
                report = nil
                message = "云端只读核对失败：" + error.localizedDescription
            }
        }
    }
}
