import Foundation
import SwiftUI

/// Explicit tap only. Uses the same separated TEST Keychain session as purchase
/// uploads; does not use the ordinary read-only CloudAPI login for mutations.
@MainActor
final class BusinessUploadCoordinator: ObservableObject {
    @Published private(set) var connected = false
    @Published private(set) var busy = false
    @Published private(set) var message: String?
    @Published private(set) var drafts: [TestDraft] = []
    @Published private(set) var jobs: [BusinessUploadJob] = []
    @Published private(set) var bookName = ""

    private let storage: TestDraftStore?
    private var api: StagingPurchaseAPI?
    private var auth: StagingSession?
    private var account = ""
    private var book: CloudBook?
    private var generation = 0

    init() {
        do { storage = try TestDraftStore() }
        catch {
            storage = nil
            message = error.localizedDescription
        }
    }

    func open(account: String?, book: CloudBook?) {
        generation += 1
        api = nil; auth = nil; connected = false
        bookName = ""
        self.account = account ?? ""
        self.book = book
        reload()
    }

    private func requireCurrent(_ value: Int) throws {
        guard value == generation else {
            throw StagingSafetyError.needsReview("当前测试账号或账本已切换，请只读核对")
        }
    }

    private func reload() {
        guard let storage, !account.isEmpty, let book else {
            drafts = []; jobs = []; return
        }
        do {
            drafts = try storage.load(account: account, book: book.id)
                .filter { $0.kind == .business }
            jobs = try storage.businessJobs(account: account, book: book.id)
        } catch {
            message = error.localizedDescription
        }
    }

    func restore() async {
        guard !busy, let book, !account.isEmpty,
              book.isAvailable,
              book.name.hasPrefix(TestWritePolicy.prefix),
              let remembered = TestSyncCredentialStore.load(
                  account: account, bookID: book.id) else { return }
        let g = generation
        busy = true
        defer { busy = false }
        do {
            let client = try StagingPurchaseAPI(
                endpoint: StagingPurchasePolicy.productionURL)
            let session = try await client.resume(remembered, sourceBook: book)
            try requireCurrent(g)
            api = client; auth = session; connected = true
            bookName = session.book.name
            message = nil
            reload()
        } catch {
            guard g == generation else { return }
            if (error as? CloudAPIError)?.status == 401 ||
                (error as? CloudAPIError)?.status == 403 ||
                error is StagingSafetyError {
                TestSyncCredentialStore.clear(account: account, bookID: book.id)
            }
            message = "测试账号自动验证失败：" + error.localizedDescription
        }
    }

    func login(username: String, password: String) async {
        guard !busy, !username.isEmpty, !password.isEmpty, let book,
              !account.isEmpty else { return }
        let g = generation
        busy = true
        defer { busy = false }
        do {
            let client = try StagingPurchaseAPI(
                endpoint: StagingPurchasePolicy.productionURL)
            let session = try await client.login(
                username: username, password: password, sourceBook: book)
            try requireCurrent(g)
            try TestSyncCredentialStore.save(session, sourceAccount: account)
            api = client; auth = session; connected = true
            bookName = session.book.name
            message = "专用测试账号权限已核实"
            reload()
        } catch {
            guard g == generation else { return }
            message = error.localizedDescription
        }
    }

    func forget() {
        if let book, !account.isEmpty {
            TestSyncCredentialStore.clear(account: account, bookID: book.id)
        }
        open(account: account, book: book)
        message = "已清除该测试账本的本机登录"
    }

    private func active() throws -> (StagingPurchaseAPI, StagingSession, CloudBook, TestDraftStore) {
        guard let api, let auth, let book, let storage,
              connected, book.id == auth.book.id,
              auth.host == api.base.absoluteString,
              book.name.hasPrefix(TestWritePolicy.prefix) else {
            throw StagingSafetyError.wrongSourceBook
        }
        return (api, auth, book, storage)
    }

    func send(draftID: String) async {
        guard !busy else { return }
        let g = generation
        busy = true
        defer { busy = false; reload() }
        do {
            let (api, auth, book, storage) = try active()
            try await api.validateSession(auth)
            try requireCurrent(g)
            let available = try await api.pullAll(auth)
            try requireCurrent(g)
            var job: BusinessUploadJob
            if let saved = try storage.businessJob(
                id: draftID, account: account, book: book.id) {
                guard saved.state == .ready,
                      saved.stageHost == auth.host,
                      saved.stageInstanceID == auth.scope.instanceID,
                      saved.stageUsername == auth.username,
                      saved.bookID == book.id else {
                    throw StagingSafetyError.needsReview(
                        "该笔营业已发送或待核对，不允许重复上传")
                }
                job = saved
            } else {
                guard let draft = try storage.load(
                    account: account, book: book.id).first(where: {
                        $0.id == draftID && $0.kind == .business
                    }), draft.account.lowercased() == account.lowercased() else {
                    throw StagingSafetyError.invalidDraft
                }
                // No other unresolved business intent may be in flight.
                let existing = try storage.businessJobs(account: account, book: book.id)
                guard existing.allSatisfy({ $0.state == .complete }) else {
                    throw StagingSafetyError.needsReview(
                        "本机另有待核对的营业提交，请先检查其云端结果")
                }
                job = try BusinessUploadPolicy.prepare(
                    draft, sourceBook: book, session: auth, events: available)
                try storage.saveBusinessJob(job) // durable before any network call
            }

            guard !BusinessUploadPolicy.conflict(
                date: job.payload["date"]?.text ?? "",
                storeSyncID: job.payload["store_sync_id"]?.text ?? "",
                storeName: job.payload["store_name"]?.text ?? "",
                events: available) else {
                job.state = .needsReview
                job.note = "上传前发现云端已有该日该位置记录，未再次提交"
                try storage.saveBusinessJob(job)
                throw StagingSafetyError.needsReview(job.note)
            }
            try await api.validateSession(auth)
            try requireCurrent(g)
            // Re-read just before sending: avoid accepting an Android record
            // created after the first inspection. Still not server-side CAS.
            let latest = try await api.pullAll(auth)
            try requireCurrent(g)
            guard !BusinessUploadPolicy.conflict(
                date: job.payload["date"]?.text ?? "",
                storeSyncID: job.payload["store_sync_id"]?.text ?? "",
                storeName: job.payload["store_name"]?.text ?? "",
                events: latest) else {
                job.state = .needsReview
                job.note = "最终提交前发现安卓/云端已有同日同位置营业，未出网"
                try storage.saveBusinessJob(job)
                throw StagingSafetyError.needsReview(job.note)
            }
            job.state = .sending
            job.note = "网络请求之前已落盘；任何未知结果都不能自动重试"
            try storage.saveBusinessJob(job)
            do {
                try requireCurrent(g)
                let receipt = try await api.pushSingle(job.event, auth: auth)
                try requireCurrent(g)
                guard receipt.isAccepted(job.event) else {
                    throw StagingSafetyError.needsReview("服务器没有确认该条营业")
                }
                let readback = try await api.pullAll(auth)
                try requireCurrent(g)
                guard BusinessUploadPolicy.verified(job, events: readback) else {
                    throw StagingSafetyError.needsReview(
                        "回读金额、收款归属或同日位置存在冲突")
                }
                job.state = .complete
                job.note = "营业云端回读完成；Android 本机是否同步请在安卓端查看"
                try storage.saveBusinessJob(job)
                message = job.note
            } catch {
                if g == generation {
                    job.state = .needsReview
                    job.note = "结果不确定： " + error.localizedDescription
                    try storage.saveBusinessJob(job)
                }
                throw error
            }
        } catch {
            if g == generation { message = error.localizedDescription }
        }
    }

    func inspect(draftID: String) async {
        guard !busy else { return }
        let g = generation
        busy = true
        defer { busy = false; reload() }
        do {
            let (api, auth, book, storage) = try active()
            guard var job = try storage.businessJob(
                id: draftID, account: account, book: book.id),
                  job.stageUsername == auth.username,
                  job.stageInstanceID == auth.scope.instanceID else {
                throw StagingSafetyError.wrongSourceBook
            }
            try await api.validateSession(auth)
            try requireCurrent(g)
            let rows = try await api.pullAll(auth)
            try requireCurrent(g)
            if BusinessUploadPolicy.verified(job, events: rows) {
                if job.state == .ready {
                    job.state = .needsReview
                    job.note = "提交前发现已有云端记录，需核实来源"
                } else {
                    job.state = .complete
                    job.note = "云端金额和收款归属均已确认；没有再次发送"
                }
                try storage.saveBusinessJob(job)
            } else if job.state == .sending {
                job.state = .needsReview
                job.note = "尚未完整确认云端结果，不允许重新发送"
                try storage.saveBusinessJob(job)
            }
            message = job.note
        } catch {
            if g == generation { message = error.localizedDescription }
        }
    }
}
