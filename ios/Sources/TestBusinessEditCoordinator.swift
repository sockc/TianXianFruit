import Foundation
import SwiftUI

/// Foreground only. A correction is a single conditional test-book UPSERT,
/// never a generic business-write queue or a background retry.
@MainActor
final class TestBusinessEditCoordinator: ObservableObject {
    @Published private(set) var connected = false
    @Published private(set) var busy = false
    @Published private(set) var message: String?
    @Published private(set) var jobs: [TestBusinessEditJob] = []

    private let store: TestDraftStore?
    private var api: StagingPurchaseAPI?
    private var auth: StagingSession?
    private var account = ""
    private var book: CloudBook?
    private var generation = 0

    init() {
        do { store = try TestDraftStore() }
        catch { store = nil; message = error.localizedDescription }
    }

    func open(account: String?, book: CloudBook?) {
        generation += 1
        api = nil; auth = nil; connected = false
        self.account = account ?? ""
        self.book = book
        reload()
    }

    private func current(_ value: Int) throws {
        guard value == generation else {
            throw StagingSafetyError.needsReview(
                "账号或账本已切换，更正请求停止；请只读核对云端")
        }
    }

    private func reload() {
        guard let store, let book, !account.isEmpty else {
            jobs = []; return
        }
        do { jobs = try store.businessEditJobs(account: account, book: book.id) }
        catch { message = error.localizedDescription }
    }

    func restore() async {
        guard !busy, let book,
              book.name.hasPrefix(TestWritePolicy.prefix),
              let saved = TestSyncCredentialStore.load(
                account: account, bookID: book.id) else { return }
        let g = generation
        busy = true
        defer { busy = false }
        do {
            let client = try StagingPurchaseAPI(
                endpoint: StagingPurchasePolicy.productionURL)
            let session = try await client.resume(saved, sourceBook: book)
            try current(g)
            api = client; auth = session; connected = true
            message = nil
            reload()
        } catch {
            guard g == generation else { return }
            if (error as? CloudAPIError)?.status == 401 ||
               (error as? CloudAPIError)?.status == 403 ||
               error is StagingSafetyError {
                TestSyncCredentialStore.clear(account: account, bookID: book.id)
            }
            message = "专用测试登录需要重新验证：" + error.localizedDescription
        }
    }

    func login(username: String, password: String) async {
        guard !busy, let book, !username.isEmpty,
              !password.isEmpty, !account.isEmpty else { return }
        let g = generation
        busy = true
        defer { busy = false }
        do {
            let client = try StagingPurchaseAPI(
                endpoint: StagingPurchasePolicy.productionURL)
            let session = try await client.login(
                username: username, password: password, sourceBook: book)
            try current(g)
            try TestSyncCredentialStore.save(session, sourceAccount: account)
            api = client; auth = session; connected = true
            message = "测试账本登录已验证"
            reload()
        } catch {
            if g == generation { message = error.localizedDescription }
        }
    }

    private func active() throws -> (StagingPurchaseAPI, StagingSession, CloudBook, TestDraftStore) {
        guard connected, let api, let auth, let book, let store,
              !account.isEmpty, auth.book.id == book.id,
              book.name.hasPrefix(TestWritePolicy.prefix),
              auth.host == api.base.absoluteString,
              auth.scope.bookID == book.id else {
            throw StagingSafetyError.wrongSourceBook
        }
        return (api, auth, book, store)
    }

    /// One-tap preflight + immutable intent + single POST + authoritative
    /// readback. Not an unconditional replacement of Android records.
    func submit(original: SyncEvent, values: TestBusinessEditValues) async {
        guard !busy else { return }
        let g = generation
        busy = true
        defer { busy = false; reload() }
        var job: TestBusinessEditJob?
        do {
            let (api, auth, book, store) = try active()
            try await api.validateSession(auth)
            try current(g)
            let available = try await api.pullAll(auth)
            try current(g)
            guard let latest = available.first(where: {
                $0.tableName == original.tableName &&
                $0.syncId == original.syncId
            }), latest.rowVersion == original.rowVersion,
               latest.operation == original.operation,
               latest.payload == original.payload else {
                throw StagingSafetyError.needsReview(
                    "安卓或其他设备已修改此记录；请刷新页面后重选，原数据未覆盖")
            }
            // Never issue two amendments for one record while any previous
            // request is missing a verified cloud outcome.
            let previous = try store.businessEditJobs(
                account: account, book: book.id)
            guard !previous.contains(where: {
                $0.original.syncId == original.syncId &&
                $0.state != .complete
            }) else {
                throw StagingSafetyError.needsReview(
                    "该记录有未完成更正任务，请先只读核对；不会重复写入")
            }
            let prepared = try TestBusinessEditPolicy.prepare(
                original: latest, values: values, events: available,
                book: book, account: account, session: auth)
            try current(g)
            try store.saveBusinessEditJob(prepared)
            job = prepared

            // Second online check immediately before freezing the send.
            try await api.validateSession(auth)
            try current(g)
            let check = try await api.pullAll(auth)
            try current(g)
            guard TestBusinessEditPolicy.unchanged(prepared, events: check) else {
                var conflict = prepared
                conflict.state = .needsReview
                conflict.note = "提交前云端版本变化，未执行更正"
                try store.saveBusinessEditJob(conflict)
                job = conflict
                throw StagingSafetyError.needsReview(conflict.note)
            }
            var sending = prepared
            sending.state = .sending
            sending.note = "请求出网前已落盘；结果不明时不能重发"
            try store.saveBusinessEditJob(sending)
            job = sending

            try current(g)
            let ack = try await api.pushSingle(sending.proposed, auth: auth)
            try current(g)
            guard ack.isAccepted(sending.proposed) else {
                throw StagingSafetyError.needsReview(
                    "服务端拒绝本次版本号或存在并发冲突")
            }
            let remote = try await api.pullAll(auth)
            try current(g)
            guard TestBusinessEditPolicy.confirmed(sending, events: remote) else {
                throw StagingSafetyError.needsReview(
                    "云端财务字段或收款归属不一致，请查看 Android")
            }
            sending.state = .complete
            sending.note = "云端已确认营业更正；安卓端仍需完成同步查看"
            try store.saveBusinessEditJob(sending)
            job = sending
            message = sending.note
        } catch {
            if g == generation {
                if var pending = job, pending.state == .sending {
                    pending.state = .needsReview
                    pending.note = "请求结果不确定：" + error.localizedDescription
                    try? store?.saveBusinessEditJob(pending)
                }
                message = error.localizedDescription
            }
        }
    }

    func inspect(id: String) async {
        guard !busy else { return }
        let g = generation
        busy = true
        defer { busy = false; reload() }
        do {
            let (api, auth, book, store) = try active()
            guard var job = try store.businessEditJob(
                id: id, account: account, book: book.id),
                job.stageInstanceID == auth.scope.instanceID,
                job.stageUsername == auth.username else {
                throw StagingSafetyError.wrongSourceBook
            }
            try await api.validateSession(auth)
            try current(g)
            let events = try await api.pullAll(auth)
            try current(g)
            if TestBusinessEditPolicy.confirmed(job, events: events) {
                // A job that has never sent cannot claim ownership of a
                // pre-existing matching remote revision.
                if job.state == .ready {
                    job.state = .needsReview
                    job.note = "发现同版本云端记录但该任务未发送，请手动确认来源"
                } else {
                    job.state = .complete
                    job.note = "只读核对确认成功，未重复发送"
                }
                try store.saveBusinessEditJob(job)
            } else if job.state != .complete {
                job.state = .needsReview
                job.note = "云端尚不能证实本次更正，禁止再次发送；请检查 Android"
                try store.saveBusinessEditJob(job)
            }
            message = job.note
        } catch {
            if g == generation { message = error.localizedDescription }
        }
    }
}
