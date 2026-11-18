import Foundation
import SwiftUI

/// Explicit foreground action only; never starts a business upload on app
/// launch, logout, account switch, background refresh or a retry timer.
@MainActor
final class StagingPurchaseCoordinator: ObservableObject {
    @Published private(set) var connected = false
    @Published private(set) var busy = false
    @Published private(set) var message: String?
    @Published private(set) var stageName = ""
    @Published private(set) var jobs: [StagingPurchaseJob] = []
    @Published private(set) var drafts: [TestDraft] = []

    private let storage: TestDraftStore?
    private var api: StagingPurchaseAPI?
    private var auth: StagingSession?
    private var sourceAccount = ""
    private var sourceBook: CloudBook?

    init() {
        do { storage = try TestDraftStore() }
        catch {
            storage = nil
            message = error.localizedDescription
        }
    }

    func open(account: String?, book: CloudBook?) {
        connected = false
        api = nil
        auth = nil
        stageName = ""
        sourceAccount = account ?? ""
        sourceBook = book
        refreshLocal()
    }

    private func refreshLocal() {
        guard let storage, let book = sourceBook, !sourceAccount.isEmpty else {
            drafts = []; jobs = []
            return
        }
        do {
            drafts = try storage.load(account: sourceAccount, book: book.id)
                .filter { $0.kind == .purchase }
            jobs = try storage.stagingJobs(account: sourceAccount, book: book.id)
        } catch {
            message = error.localizedDescription
        }
    }

    func connect(endpoint: String, username: String, password: String,
                 repeatedBookID: String, acknowledged: Bool) async {
        guard !busy else { return }
        busy = true
        defer { busy = false }
        do {
            guard acknowledged, let book = sourceBook,
                  !sourceAccount.isEmpty,
                  repeatedBookID == book.id else {
                throw StagingSafetyError.wrongSourceBook
            }
            guard book.name.hasPrefix(TestWritePolicy.prefix),
                  ["OWNER", "SUPERADMIN", "EDITOR"].contains(book.role) else {
                throw StagingSafetyError.inaccessibleBook
            }
            let client = try StagingPurchaseAPI(endpoint: endpoint)
            let session = try await client.login(username: username, password: password,
                                                  sourceBook: book)
            api = client
            auth = session
            connected = true
            stageName = session.host + " · 独立实例 " + session.proof.instanceID
            message = "已验证独立测试服务端、账本 ID 和角色。上传仍需要单独点击确认。"
            refreshLocal()
        } catch {
            connected = false
            api = nil
            auth = nil
            message = error.localizedDescription
        }
    }

    private func verified() throws -> (StagingPurchaseAPI, StagingSession, CloudBook, TestDraftStore) {
        guard let api, let auth, let book = sourceBook,
              let storage, connected,
              book.id == auth.book.id,
              auth.host == api.base.absoluteString else {
            throw StagingSafetyError.wrongSourceBook
        }
        return (api, auth, book, storage)
    }

    /// Freeze all server-specific local numeric ID references before first
    /// upload. If the staging ledger is not a verified copy of the test book,
    /// preparation fails without changing any cloud data.
    func prepare(draftID: String) async {
        guard !busy else { return }
        busy = true
        defer { busy = false }
        do {
            let (api, auth, book, storage) = try verified()
            if try storage.stagingJob(id: draftID, account: sourceAccount,
                                      book: book.id) != nil {
                message = "此草稿已有固定上传意图；不能生成第二组订单 ID"
                return
            }
            guard let draft = try storage.load(account: sourceAccount,
                                                book: book.id).first(where: { $0.id == draftID }) else {
                throw StagingSafetyError.invalidDraft
            }
            try await api.validateSession(auth)
            let stageRows = try await api.pullAll(auth)
            let job = try StagingPayloadBuilder.prepare(
                draft, sourceBook: book, stageBook: auth.book,
                stageHost: auth.host, proof: auth.proof,
                username: auth.username, deviceID: auth.deviceID,
                available: stageRows)
            try storage.saveStagingJob(job)
            message = "关联检查通过。订单和明细已冻结为唯一同步 ID；尚未上传。"
            refreshLocal()
        } catch {
            message = error.localizedDescription
        }
    }

    private func verifyBinding(_ job: StagingPurchaseJob,
                               auth: StagingSession) throws {
        guard job.bookID == auth.book.id,
              job.stageHost == auth.host,
              job.stageInstanceID == auth.proof.instanceID,
              job.stageUsername.lowercased() == auth.username.lowercased(),
              job.deviceID == auth.deviceID,
              job.sourceAccount.lowercased() == sourceAccount.lowercased(),
              job.state != .complete else {
            throw StagingSafetyError.unsafeEndpoint
        }
    }

    private func transition(_ current: StagingPurchaseJob,
                            to next: StagingUploadState, _ detail: String,
                            in storage: TestDraftStore) throws -> StagingPurchaseJob {
        var updated = current
        updated.state = next
        updated.lastMessage = detail
        try storage.saveStagingJob(updated)
        refreshLocal()
        return updated
    }

    /// Two sequential pushes with a durable state transition before EACH
    /// request. A timeout never causes an automatic retry; only explicit
    /// remote readback can resolve an uncertain result.
    func upload(draftID: String) async {
        guard !busy else { return }
        busy = true
        defer { busy = false }
        do {
            let (api, auth, book, storage) = try verified()
            guard var job = try storage.stagingJob(id: draftID,
                account: sourceAccount, book: book.id) else {
                throw StagingSafetyError.invalidDraft
            }
            try verifyBinding(job, auth: auth)
            guard job.state == .ready || job.state == .orderConfirmed else {
                throw StagingSafetyError.needsReview(job.state.title)
            }
            try await api.validateSession(auth)

            if job.state == .ready {
                let event = StagingPayloadBuilder.event(job, item: false)
                job = try transition(job, to: .sendingOrder,
                                     "订单网络请求发出前已落盘", in: storage)
                do {
                    let response = try await api.pushSingle(event, auth: auth)
                    guard response.isAccepted(event),
                          try await api.verify(event, auth: auth) else {
                        throw StagingSafetyError.needsReview("采购订单确认或回读失败")
                    }
                    job = try transition(job, to: .orderConfirmed,
                                         "服务端已接受订单并回读匹配", in: storage)
                } catch {
                    _ = try transition(job, to: .needsReview,
                                       "订单可能已写入：" + error.localizedDescription,
                                       in: storage)
                    throw error
                }
            }

            let itemEvent = StagingPayloadBuilder.event(job, item: true)
            // Staging server must already have the canonical parent row
            // before the purchase_item refers to its remote numeric ID.
            guard try await api.verify(StagingPayloadBuilder.event(job, item: false),
                                       auth: auth) else {
                throw StagingSafetyError.needsReview("采购订单未能回读，请先人工核对")
            }
            job = try transition(job, to: .sendingItem,
                                 "明细请求发出前已落盘", in: storage)
            do {
                let response = try await api.pushSingle(itemEvent, auth: auth)
                guard response.isAccepted(itemEvent),
                      try await api.verify(itemEvent, auth: auth) else {
                    throw StagingSafetyError.needsReview("商品明细确认或回读失败")
                }
                _ = try transition(job, to: .complete,
                                   "订单和商品明细已由隔离服务端回读确认",
                                   in: storage)
                message = "隔离测试服务端已确认订单与明细；请在连接同一测试服务端的 Android 上核对。"
            } catch {
                _ = try transition(job, to: .needsReview,
                                   "明细可能已写入：" + error.localizedDescription,
                                   in: storage)
                throw error
            }
        } catch {
            message = error.localizedDescription
            refreshLocal()
        }
    }

    func checkServer(draftID: String) async {
        guard !busy else { return }
        busy = true
        defer { busy = false }
        do {
            let (api, auth, book, storage) = try verified()
            guard var job = try storage.stagingJob(id: draftID,
                account: sourceAccount, book: book.id) else {
                throw StagingSafetyError.invalidDraft
            }
            guard job.stageHost == auth.host,
                  job.stageInstanceID == auth.proof.instanceID,
                  job.bookID == book.id else {
                throw StagingSafetyError.unsafeEndpoint
            }
            try await api.validateSession(auth)
            let order = try await api.verify(StagingPayloadBuilder.event(job, item: false),
                                             auth: auth)
            let item = try await api.verify(StagingPayloadBuilder.event(job, item: true),
                                            auth: auth)
            if order && item {
                job = try transition(job, to: .complete,
                                     "重新回读确认订单和明细", in: storage)
            } else if order && !item && job.state != .complete && job.state != .ready {
                job = try transition(job, to: .orderConfirmed,
                                     "仅订单已回读；明细未确认可继续手动提交",
                                     in: storage)
            } else {
                if job.state != .needsReview {
                    job = try transition(job, to: .needsReview,
                                         "测试服务器未确认完整的父子记录",
                                         in: storage)
                }
            }
            message = job.lastMessage
        } catch {
            message = error.localizedDescription
        }
    }
}
