import Foundation
import SwiftUI

/// All mutating operations are foreground user actions, scoped to a verified
/// ordinary USER account that can see exactly one iOS_TEST_ ledger.
@MainActor
final class PurchaseRemarkEditCoordinator: ObservableObject {
    @Published private(set) var connected = false
    @Published private(set) var busy = false
    @Published private(set) var message: String?
    @Published private(set) var jobs: [PurchaseRemarkEditJob] = []

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

    private func requireCurrent(_ g: Int) throws {
        guard generation == g else {
            throw StagingSafetyError.needsReview(
                "测试账号或账本已经切换，停止更正；请只读核对云端")
        }
    }

    private func reload() {
        guard let book, !account.isEmpty, let store else {
            jobs = []; return
        }
        do { jobs = try store.purchaseRemarkJobs(account: account, book: book.id) }
        catch { message = error.localizedDescription }
    }

    func restore() async {
        guard !busy, let book, book.isAvailable,
              book.name.hasPrefix(TestWritePolicy.prefix),
              let saved = TestSyncCredentialStore.load(
                account: account, bookID: book.id) else { return }
        let g = generation
        busy = true
        defer { busy = false }
        do {
            let transport = try StagingPurchaseAPI(
                endpoint: StagingPurchasePolicy.productionURL)
            let session = try await transport.resume(saved, sourceBook: book)
            try requireCurrent(g)
            api = transport; auth = session; connected = true
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
        guard !busy, !username.isEmpty, !password.isEmpty,
              !account.isEmpty, let book else { return }
        let g = generation
        busy = true
        defer { busy = false }
        do {
            let transport = try StagingPurchaseAPI(
                endpoint: StagingPurchasePolicy.productionURL)
            let session = try await transport.login(
                username: username, password: password, sourceBook: book)
            try requireCurrent(g)
            try TestSyncCredentialStore.save(session, sourceAccount: account)
            api = transport; auth = session; connected = true
            message = "专用测试账号已验证"
            reload()
        } catch {
            if g == generation { message = error.localizedDescription }
        }
    }

    private func active() throws -> (StagingPurchaseAPI, StagingSession, CloudBook, TestDraftStore) {
        guard connected, let api, let auth, let book, let store,
              !account.isEmpty, book.isAvailable,
              book.name.hasPrefix(TestWritePolicy.prefix),
              auth.book.id == book.id, auth.book.name == book.name,
              auth.host == api.base.absoluteString,
              auth.scope.bookID == book.id else {
            throw StagingSafetyError.wrongSourceBook
        }
        return (api, auth, book, store)
    }

    func submit(original: SyncEvent, remark: String) async {
        guard !busy else { return }
        let g = generation
        busy = true
        defer { busy = false; reload() }
        var pending: PurchaseRemarkEditJob?
        do {
            let (api, auth, book, store) = try active()
            try await api.validateSession(auth)
            try requireCurrent(g)
            let rows = try await api.pullAll(auth)
            try requireCurrent(g)
            // Full original payload equality, not only row_version, prevents
            // overwriting an already edited Android snapshot.
            let prepared = try PurchaseRemarkEditPolicy.prepare(
                original: original, remark: remark,
                events: rows, book: book, account: account, session: auth)

            let previous = try store.purchaseRemarkJobs(
                account: account, book: book.id)
            guard previous.allSatisfy({
                $0.state == .complete || $0.original.syncId != original.syncId
            }) else {
                throw StagingSafetyError.needsReview(
                    "这张采购订单还有未完成的更正；请先只读核对")
            }
            // A partly uploaded purchase order is not a safe edit target.
            let purchaseWrites = try store.stagingJobs(
                account: account, book: book.id)
            guard purchaseWrites.allSatisfy({ $0.state == .complete }) else {
                throw StagingSafetyError.needsReview(
                    "有未完成的采购上传，请先核对订单及商品明细")
            }
            try store.savePurchaseRemarkJob(prepared)
            pending = prepared
            try await api.validateSession(auth)
            try requireCurrent(g)
            let preflight = try await api.pullAll(auth)
            try requireCurrent(g)
            guard PurchaseRemarkEditPolicy.unchanged(
                prepared, events: preflight) else {
                var blocked = prepared
                blocked.state = .needsReview
                blocked.note = "提交前检测到订单/商品被其他设备修改，未发送更正"
                try store.savePurchaseRemarkJob(blocked)
                pending = blocked
                throw StagingSafetyError.needsReview(blocked.note)
            }

            var sending = prepared
            sending.state = .sending
            sending.note = "已保存不可变提交意图；结果不明时不会重发"
            try store.savePurchaseRemarkJob(sending)
            pending = sending
            try requireCurrent(g)
            let ack = try await api.pushSingle(sending.proposed, auth: auth)
            try requireCurrent(g)
            guard ack.isAccepted(sending.proposed) else {
                throw StagingSafetyError.needsReview(
                    "服务器未确认新版本，可能有并发冲突")
            }
            let after = try await api.pullAll(auth)
            try requireCurrent(g)
            guard PurchaseRemarkEditPolicy.confirmed(
                sending, events: after) else {
                throw StagingSafetyError.needsReview(
                    "云端订单版本、备注或商品明细核对不一致")
            }
            sending.state = .complete
            sending.note = "云端备注更正成功；请在 Android 同步后查看"
            try store.savePurchaseRemarkJob(sending)
            pending = sending
            message = sending.note
        } catch {
            if g == generation {
                if var unknown = pending, unknown.state == .sending {
                    unknown.state = .needsReview
                    unknown.note = "网络或回读结果不明确：" + error.localizedDescription
                    try? store?.savePurchaseRemarkJob(unknown)
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
            guard var job = try store.purchaseRemarkJob(
                id: id, account: account, book: book.id),
                job.stageInstanceID == auth.scope.instanceID,
                job.stageUsername == auth.username else {
                throw StagingSafetyError.wrongSourceBook
            }
            try await api.validateSession(auth)
            try requireCurrent(g)
            let events = try await api.pullAll(auth)
            try requireCurrent(g)
            if PurchaseRemarkEditPolicy.confirmed(job, events: events) {
                if job.state == .ready {
                    job.state = .needsReview
                    job.note = "发现相同云端结果，但本机没有发送记录；需人工核实来源"
                } else {
                    job.state = .complete
                    job.note = "只读回读核对成功，没有重复发送"
                }
            } else if job.state != .complete {
                job.state = .needsReview
                job.note = "无法确认本次更正，禁止再次发送"
            }
            try store.savePurchaseRemarkJob(job)
            message = job.note
        } catch {
            if g == generation { message = error.localizedDescription }
        }
    }

    func cancelUnsent(id: String) {
        guard !busy, let book, let store else { return }
        do {
            try store.cancelUnsentRemark(id: id,
                account: account, book: book.id)
            message = "尚未发出的本机更正已取消，服务器未修改"
            reload()
        } catch { message = error.localizedDescription }
    }
}
