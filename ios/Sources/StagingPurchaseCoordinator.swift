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
    @Published private(set) var audits: [String: PurchaseReadbackAudit] = [:]

    private let storage: TestDraftStore?
    private var api: StagingPurchaseAPI?
    private var auth: StagingSession?
    private var sourceAccount = ""
    private var sourceBook: CloudBook?
    private var sessionGeneration = 0

    private func requireCurrent(_ generation: Int) throws {
        guard generation == sessionGeneration else {
            throw StagingSafetyError.needsReview(
                "账本或账号已切换，停止继续提交，请人工核对远端记录")
        }
    }

    init() {
        do { storage = try TestDraftStore() }
        catch {
            storage = nil
            message = error.localizedDescription
        }
    }

    func open(account: String?, book: CloudBook?) {
        sessionGeneration += 1
        connected = false
        api = nil
        auth = nil
        stageName = ""
        audits = [:]
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

    func connect(username: String, password: String,
                 repeatedBookID: String, acknowledged: Bool) async {
        guard !busy else { return }
        let generation = sessionGeneration
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
            let client = try StagingPurchaseAPI(
                endpoint: StagingPurchasePolicy.productionURL)
            let session = try await client.login(username: username, password: password,
                                                  sourceBook: book)
            try requireCurrent(generation)
            api = client
            auth = session
            connected = true
            stageName = "正式服务器 / 专用测试账本：" + session.book.name
            message = "已验证专用账号仅能看到当前测试账本，上传仍需逐笔确认。此项客户端检查不能替代服务器账本权限校验。"
            refreshLocal()
        } catch {
            if generation == sessionGeneration {
                connected = false
                api = nil
                auth = nil
                message = error.localizedDescription
            }
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

    /// Resolve buyer/fruit IDs from the same account-scoped test book before
    /// the first upload. The production book is never selected by inference.
    func prepare(draftID: String) async {
        guard !busy else { return }
        let generation = sessionGeneration
        busy = true
        defer { busy = false }
        do {
            let (api, auth, book, storage) = try verified()
            if try storage.stagingJob(id: draftID, account: sourceAccount,
                                      book: book.id) != nil {
                message = "此草稿已有固定上传意图；不能生成第二组订单 ID"
                return
            }
            let previousJobs = try storage.stagingJobs(
                account: sourceAccount, book: book.id)
            guard previousJobs.count < StagingPurchasePolicy.pilotOrderLimit else {
                throw StagingSafetyError.needsReview(
                    "此测试账本最多允许准备三笔采购，继续扩容需另行验收")
            }
            guard let draft = try storage.load(account: sourceAccount,
                                                book: book.id).first(where: { $0.id == draftID }) else {
                throw StagingSafetyError.invalidDraft
            }
            guard draft.purchaseItems == nil else {
                throw TestWriteError.insufficientSchema(
                    "多商品采购本版仅保存本机草稿，尚未开放测试账本整单提交")
            }
            try await api.validateSession(auth)
            try requireCurrent(generation)
            let stageRows = try await api.pullAll(auth)
            try requireCurrent(generation)

            // Prior orders must remain correct on the SAME server. A local
            // Android checkbox alone never authorizes the next purchase.
            var priorAudits: [String: PurchaseReadbackAudit] = [:]
            for previous in previousJobs {
                guard previous.stageHost == auth.host,
                      previous.stageInstanceID == auth.scope.instanceID,
                      previous.stageUsername.lowercased() == auth.username.lowercased(),
                      previous.bookID == book.id else {
                    throw StagingSafetyError.wrongSourceBook
                }
                let audit = PurchaseReadbackPolicy.audit(previous, events: stageRows)
                priorAudits[previous.id] = audit
                audits[previous.id] = audit
            }
            try StagingPurchasePolicy.authorizeNextOrder(
                existing: previousJobs, audits: priorAudits, bookID: book.id,
                username: auth.username)
            // Another phone's pilot sync IDs in this book cannot be counted
            // as one of this device's already-validated orders.
            let known = Set(previousJobs.map(\.orderSyncID))
            let foreign = stageRows.contains { event in
                event.tableName == "purchase_order" && !event.isDeletion &&
                event.syncId.hasPrefix("ios-stage-order-") &&
                !known.contains(event.syncId)
            }
            guard !foreign else {
                throw StagingSafetyError.needsReview(
                    "测试账本存在另一设备的 iOS 采购单；需先核对，禁止并行试写")
            }
            let job = try StagingPayloadBuilder.prepare(
                draft, sourceBook: book, stageBook: auth.book,
                stageHost: auth.host, scope: auth.scope,
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
              job.stageInstanceID == auth.scope.instanceID,
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

    /// All actual network mutations require a foreground button click.
    /// Before the FIRST request, read the current remote state to avoid
    /// blindly replaying an order already accepted by the server.
    func upload(draftID: String) async {
        guard !busy else { return }
        let generation = sessionGeneration
        busy = true
        defer { busy = false }
        do {
            let (api, auth, book, storage) = try verified()
            guard var job = try storage.stagingJob(
                id: draftID, account: sourceAccount, book: book.id) else {
                throw StagingSafetyError.invalidDraft
            }
            try verifyBinding(job, auth: auth)
            guard job.state == .ready || job.state == .orderConfirmed else {
                throw StagingSafetyError.needsReview(job.state.title)
            }
            try await api.validateSession(auth)
            try requireCurrent(generation)
            var audit = try await api.readback(job, auth: auth)
            try requireCurrent(generation)
            audits[draftID] = audit

            if job.state == .ready {
                // A duplicate server record is never an invitation to push
                // another version. Preserve a reviewable local state.
                if audit.hasRemoteHistory {
                    job = try transition(job, to: .needsReview,
                        "已有同一 sync_id 的远端记录，已停止重复写入", in: storage)
                    throw StagingSafetyError.needsReview(job.lastMessage)
                }
                let orderEvent = StagingPayloadBuilder.event(job, item: false)
                job = try transition(job, to: .sendingOrder,
                                     "父订单发送前持久化状态", in: storage)
                do {
                    try requireCurrent(generation)
                    let response = try await api.pushSingle(orderEvent, auth: auth)
                    try requireCurrent(generation)
                    guard response.isAccepted(orderEvent) else {
                        throw StagingSafetyError.needsReview(
                            "服务端未按订单 sync_id 确认当前版本")
                    }
                    audit = try await api.readback(job, auth: auth)
                    try requireCurrent(generation)
                    audits[draftID] = audit
                    guard audit.orderVerified, !audit.itemPresent else {
                        throw StagingSafetyError.needsReview(audit.headline)
                    }
                    guard let canonical = audit.serverOrderID, canonical > 0 else {
                        throw StagingSafetyError.needsReview("云端订单缺少有效数字 ID")
                    }
                    // Freeze the server-authoritative parent ID in SQLite
                    // BEFORE any child request is allowed to begin.
                    job.childParentID = canonical
                    job = try transition(job, to: .orderConfirmed,
                                         "服务端订单已匹配；等待商品明细确认",
                                         in: storage)
                } catch {
                    try requireCurrent(generation)
                    _ = try transition(job, to: .needsReview,
                                       "订单结果待核对：" + error.localizedDescription,
                                       in: storage)
                    throw error
                }
            } else {
                if audit.complete {
                    _ = try transition(job, to: .complete,
                                       "云端已回读确认，无需重复上传",
                                       in: storage)
                    message = "订单及明细已在测试账本中，未发送重复请求"
                    return
                }
                guard audit.orderVerified, !audit.itemPresent,
                      let canonical = audit.serverOrderID, canonical > 0,
                      job.itemRequestStarted != true,
                      job.childParentID == nil || job.childParentID == canonical else {
                    _ = try transition(job, to: .needsReview,
                                       "父订单或商品明细回读异常，禁止再次提交",
                                       in: storage)
                    throw StagingSafetyError.needsReview(audit.headline)
                }
                if job.childParentID == nil {
                    job.childParentID = canonical
                    // Legacy V0.2.3 job without a saved canonical parent.
                    try storage.saveStagingJob(job)
                    refreshLocal()
                }
            }

            // One last read after the order was confirmed, before sending
            // the child. Refuse to use a stale parent numeric ID.
            audit = try await api.readback(job, auth: auth)
            try requireCurrent(generation)
            audits[draftID] = audit
            guard audit.orderVerified, !audit.itemPresent,
                  let canonical = audit.serverOrderID, canonical > 0,
                  job.childParentID == canonical, job.itemRequestStarted != true else {
                _ = try transition(job, to: .needsReview,
                                   "商品明细尚未发送：父订单或远端关联有变化",
                                   in: storage)
                throw StagingSafetyError.needsReview(audit.headline)
            }

            let itemEvent = StagingPayloadBuilder.event(job, item: true)
            // This irreversible marker prevents an uncertain timed-out
            // child upload from silently being retried after reconnect.
            job.itemRequestStarted = true
            job = try transition(job, to: .sendingItem,
                                 "子商品明细发送前持久化状态", in: storage)
            do {
                try requireCurrent(generation)
                let response = try await api.pushSingle(itemEvent, auth: auth)
                try requireCurrent(generation)
                guard response.isAccepted(itemEvent) else {
                    throw StagingSafetyError.needsReview(
                        "服务端未按商品明细 sync_id 确认当前版本")
                }
                audit = try await api.readback(job, auth: auth)
                try requireCurrent(generation)
                audits[draftID] = audit
                guard audit.complete else {
                    throw StagingSafetyError.needsReview(audit.headline)
                }
                _ = try transition(job, to: .complete,
                                   "云端订单、商品、金额及父子 ID 已核对",
                                   in: storage)
                message = "服务器测试账本已确认。请在 Android 同账本核对后手动记录验收。"
            } catch {
                try requireCurrent(generation)
                _ = try transition(job, to: .needsReview,
                                   "商品明细结果待核对：" + error.localizedDescription,
                                   in: storage)
                throw error
            }
        } catch {
            if generation == sessionGeneration {
                message = error.localizedDescription
                refreshLocal()
            }
        }
    }

    /// Read-only audit: safe to repeat. Does NOT retry any pending push.
    func checkServer(draftID: String) async {
        guard !busy else { return }
        let generation = sessionGeneration
        busy = true
        defer { busy = false }
        do {
            let (api, auth, book, storage) = try verified()
            guard var job = try storage.stagingJob(
                id: draftID, account: sourceAccount, book: book.id) else {
                throw StagingSafetyError.invalidDraft
            }
            guard job.stageHost == auth.host,
                  job.stageInstanceID == auth.scope.instanceID,
                  job.bookID == book.id else {
                throw StagingSafetyError.unsafeEndpoint
            }
            try await api.validateSession(auth)
            try requireCurrent(generation)
            let audit = try await api.readback(job, auth: auth)
            try requireCurrent(generation)
            audits[draftID] = audit

            if audit.complete {
                if job.state == .ready {
                    job = try transition(job, to: .needsReview,
                        "请求前已存在完整云端记录，请先确认来源", in: storage)
                    message = "远端存在同一同步 ID 的记录，已停止写入"
                    return
                }
                if job.state != .complete {
                    job = try transition(job, to: .complete,
                                         "订单和明细已从服务器完整回读确认",
                                         in: storage)
                }
            } else if audit.orderVerified && !audit.itemPresent &&
                      job.itemRequestStarted != true &&
                      job.state != .sendingItem &&
                      job.state != .complete && job.state != .ready,
                      let canonical = audit.serverOrderID,
                      job.childParentID == nil || job.childParentID == canonical {
                if job.childParentID == nil {
                    job.childParentID = canonical
                }
                job = try transition(job, to: .orderConfirmed,
                                     "父订单已回读并冻结真实数字 ID，可人工确认上传明细",
                                     in: storage)
            } else if job.state == .ready && !audit.hasRemoteHistory {
                message = "草稿尚未上传；云端回读未找到记录"
                return
            } else if job.state != .complete && job.state != .needsReview {
                job = try transition(job, to: .needsReview,
                                     "云端核对不一致，需人工处理",
                                     in: storage)
            }
            message = audit.headline
        } catch {
            if generation == sessionGeneration {
                message = error.localizedDescription
            }
        }
    }

    /// Requires six independently typed Android values, a fresh verified
    /// cloud readback and an immutable local observation/fingerprint.
    /// This is still MANUAL evidence, not an Android device API response.
    @discardableResult
    func confirmAndroidReview(draftID: String,
                              observation: AndroidPurchaseObservation) -> Bool {
        guard !busy else {
            message = "正在核对云端数据，请完成后再确认 Android"
            return false
        }
        do {
            let (_, auth, book, storage) = try verified()
            guard let audit = audits[draftID], audit.complete,
                  audit.bookID == book.id,
                  var job = try storage.stagingJob(
                      id: draftID, account: sourceAccount, book: book.id),
                  job.state == .complete,
                  job.stageHost == auth.host,
                  job.stageInstanceID == auth.scope.instanceID,
                  job.stageUsername.lowercased() == auth.username.lowercased() else {
                throw StagingSafetyError.needsReview(
                    "请先刷新同一测试账本云端核对，再填写 Android 实际读到的数据")
            }
            guard job.androidReview == nil else {
                throw AndroidPurchaseReviewError.alreadyConfirmed
            }
            let evidence = try AndroidPurchaseReviewPolicy.confirm(
                job: job, audit: audit, observation: observation)
            job.androidReview = evidence
            if job.androidCheckedAt == nil {
                job.androidCheckedAt = evidence.checkedAt
            }
            job.lastMessage = "用户已录入 Android 六项数据且与当前云端核对一致（本机人工记录）"
            try storage.saveStagingJob(job)
            refreshLocal()
            message = "Android 六项人工核对已保存；不代表系统自动连接 Android"
            return true
        } catch {
            message = error.localizedDescription
            return false
        }
    }
}
