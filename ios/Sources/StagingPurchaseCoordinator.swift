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

    /// Silent login restoration; token is scoped to this LOCAL account and
    /// its one visible TEST book, never to the normal CloudAPI session.
    func restoreRemembered() async {
        guard !busy, let book = sourceBook, !sourceAccount.isEmpty,
              book.name.hasPrefix(TestWritePolicy.prefix),
              let saved = TestSyncCredentialStore.load(
                account: sourceAccount, bookID: book.id) else { return }
        let generation = sessionGeneration
        busy = true
        defer { busy = false }
        do {
            let client = try StagingPurchaseAPI(
                endpoint: StagingPurchasePolicy.productionURL)
            let session = try await client.resume(saved, sourceBook: book)
            try requireCurrent(generation)
            api = client
            auth = session
            connected = true
            stageName = session.book.name
            message = nil
            refreshLocal()
        } catch {
            // Only explicit invalid credentials/permissions warrant deleting
            // a previously verified token. Network outages retain it.
            if generation == sessionGeneration {
                if (error as? CloudAPIError)?.status == 401 ||
                    (error as? CloudAPIError)?.status == 403 ||
                    error is StagingSafetyError {
                    TestSyncCredentialStore.clear(
                        account: sourceAccount, bookID: book.id)
                }
                message = "测试账本自动登录暂不可用：" + error.localizedDescription
            }
        }
    }

    func connect(username: String, password: String) async {
        guard !busy else { return }
        let generation = sessionGeneration
        busy = true
        defer { busy = false }
        do {
            guard let book = sourceBook, !sourceAccount.isEmpty,
                  book.name.hasPrefix(TestWritePolicy.prefix),
                  ["OWNER", "SUPERADMIN", "EDITOR"].contains(book.role) else {
                throw StagingSafetyError.inaccessibleBook
            }
            let client = try StagingPurchaseAPI(
                endpoint: StagingPurchasePolicy.productionURL)
            let session = try await client.login(
                username: username, password: password, sourceBook: book)
            try requireCurrent(generation)
            // Credentials are remembered as a distinct test-only token.
            try TestSyncCredentialStore.save(
                session, sourceAccount: sourceAccount)
            api = client
            auth = session
            connected = true
            stageName = session.book.name
            message = "测试账本权限已核实，此设备会自动恢复本次登录"
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

    func forgetTestLogin() {
        if let book = sourceBook, !sourceAccount.isEmpty {
            TestSyncCredentialStore.clear(
                account: sourceAccount, bookID: book.id)
        }
        open(account: sourceAccount, book: sourceBook)
        message = "已清除这台 iPhone 保存的测试账本会话"
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

            guard let draft = try storage.load(account: sourceAccount,
                                                book: book.id).first(where: { $0.id == draftID }) else {
                throw StagingSafetyError.invalidDraft
            }
            try await api.validateSession(auth)
            try requireCurrent(generation)
            let stageRows = try await api.pullAll(auth)
            try requireCurrent(generation)

            // An already completed Android/iOS order can subsequently be
            // edited on Android. Do NOT compare every old amount on each
            // new order: only unresolved local uploads require attention.
            try StagingPurchasePolicy.authorizeNextOrder(
                existing: previousJobs, audits: [:], bookID: book.id,
                username: auth.username)
            let job = try StagingPayloadBuilder.prepare(
                draft, sourceBook: book, stageBook: auth.book,
                stageHost: auth.host, scope: auth.scope,
                username: auth.username, deviceID: auth.deviceID,
                available: stageRows)
            try storage.saveStagingJob(job)
            message = "商品关联检查通过，已准备完整订单"
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

    /// One explicit user action prepares (if needed) and uploads ALL lines.
    /// All retries after an uncertain network result remain read-only until
    /// the server has definitively confirmed that specific child.
    func prepareAndUpload(draftID: String) async {
        guard !busy else { return }
        if !jobs.contains(where: { $0.id == draftID }) {
            await prepare(draftID: draftID)
        }
        guard jobs.contains(where: { $0.id == draftID }) else { return }
        await upload(draftID: draftID)
    }

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
                if audit.hasRemoteHistory {
                    _ = try transition(job, to: .needsReview,
                        "云端已有此同步 ID 的历史记录，不重复提交", in: storage)
                    throw StagingSafetyError.needsReview(
                        "该订单已在服务端出现，请先只读核对")
                }
                let order = StagingPayloadBuilder.event(job, item: false)
                job = try transition(job, to: .sendingOrder,
                                     "父订单请求前已落盘", in: storage)
                do {
                    try requireCurrent(generation)
                    let receipt = try await api.pushSingle(order, auth: auth)
                    try requireCurrent(generation)
                    guard receipt.isAccepted(order) else {
                        throw StagingSafetyError.needsReview("父订单服务端确认异常")
                    }
                    audit = try await api.readback(job, auth: auth)
                    try requireCurrent(generation)
                    audits[draftID] = audit
                    guard audit.orderVerified, !audit.itemPresent,
                          let canonical = audit.serverOrderID,
                          canonical > 0 else {
                        throw StagingSafetyError.needsReview(audit.headline)
                    }
                    job.childParentID = canonical
                    job = try transition(job, to: .orderConfirmed,
                                         "父订单已回读，关联 ID 已固定", in: storage)
                } catch {
                    try requireCurrent(generation)
                    _ = try transition(job, to: .needsReview,
                        "订单可能已经接收：" + error.localizedDescription,
                        in: storage)
                    throw error
                }
            } else if audit.complete {
                _ = try transition(job, to: .complete,
                                   "服务器全部商品已确认，无需重复提交",
                                   in: storage)
                message = "已回读全部采购商品，没有再次上传"
                return
            }

            // A product that may have been sent but is not in readback
            // MUST NOT be retried. Never infer safe retry from a timeout.
            let expected = StagingPayloadBuilder.itemEvents(job)
            var started = job.startedItemIDs ??
                (job.itemRequestStarted == true ? [job.itemSyncID] : [])
            for expectedItem in expected {
                try requireCurrent(generation)
                audit = try await api.readback(job, auth: auth)
                try requireCurrent(generation)
                audits[draftID] = audit

                guard audit.orderVerified,
                      let canonical = audit.serverOrderID, canonical > 0,
                      job.childParentID == canonical else {
                    _ = try transition(job, to: .needsReview,
                        "云端父订单或数字关联 ID 已变化", in: storage)
                    throw StagingSafetyError.needsReview(audit.headline)
                }
                let unexpected = audit.issues.filter {
                    !$0.id.hasSuffix(".missing")
                }
                guard unexpected.isEmpty else {
                    _ = try transition(job, to: .needsReview,
                        "商品金额、归属或重复关联异常", in: storage)
                    throw StagingSafetyError.needsReview(audit.headline)
                }
                if audit.verifiedItemSyncIDs.contains(expectedItem.syncId) {
                    continue
                }
                guard audit.pendingItemSyncIDs.contains(expectedItem.syncId),
                      !started.contains(expectedItem.syncId) else {
                    _ = try transition(job, to: .needsReview,
                        "该商品请求曾发出或存在删除/冲突，禁止盲目重发",
                        in: storage)
                    throw StagingSafetyError.needsReview(audit.headline)
                }

                started.append(expectedItem.syncId)
                job.startedItemIDs = started
                if expectedItem.syncId == job.itemSyncID {
                    job.itemRequestStarted = true
                }
                job = try transition(job, to: .sendingItem,
                    "商品 " + String(started.count) + "/" +
                    String(expected.count) + " 出网前已落盘",
                    in: storage)
                do {
                    try requireCurrent(generation)
                    let receipt = try await api.pushSingle(
                        expectedItem, auth: auth)
                    try requireCurrent(generation)
                    guard receipt.isAccepted(expectedItem) else {
                        throw StagingSafetyError.needsReview(
                            "服务器未确认当前商品的同步 ID")
                    }
                    audit = try await api.readback(job, auth: auth)
                    try requireCurrent(generation)
                    audits[draftID] = audit
                    guard audit.orderVerified,
                          audit.verifiedItemSyncIDs.contains(
                              expectedItem.syncId) else {
                        throw StagingSafetyError.needsReview(audit.headline)
                    }
                } catch {
                    try requireCurrent(generation)
                    _ = try transition(job, to: .needsReview,
                        "商品请求结果不确定，需只读核对：" +
                        error.localizedDescription, in: storage)
                    throw error
                }
            }

            audit = try await api.readback(job, auth: auth)
            try requireCurrent(generation)
            audits[draftID] = audit
            guard audit.complete else {
                _ = try transition(job, to: .needsReview,
                    "父订单或部分商品未能完整回读", in: storage)
                throw StagingSafetyError.needsReview(audit.headline)
            }
            _ = try transition(job, to: .complete,
                "已完整回读 " + String(audit.expectedItemCount) +
                " 条商品及整单金额", in: storage)
            message = "测试账本采购同步成功：" +
                      String(audit.expectedItemCount) + " 条商品已云端核对"
        } catch {
            if generation == sessionGeneration {
                message = error.localizedDescription
                refreshLocal()
            }
        }
    }

    /// Read-only reconciliation. An unknown network outcome never causes
    /// an automatic retry of an already attempted item.
    func checkServer(draftID: String) async {
        guard !busy else { return }
        let generation = sessionGeneration
        busy = true
        defer { busy = false }
        do {
            let (api, auth, book, storage) = try verified()
            guard var job = try storage.stagingJob(
                id: draftID, account: sourceAccount, book: book.id),
                  job.stageHost == auth.host,
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
                        "上传前已存在同一订单同步 ID", in: storage)
                } else if job.state != .complete {
                    job = try transition(job, to: .complete,
                        "完整云端回读确认所有商品", in: storage)
                }
            } else if job.state == .ready && !audit.hasRemoteHistory {
                message = "订单尚未发送"
                return
            } else if audit.orderVerified,
                      let canonical = audit.serverOrderID, canonical > 0,
                      job.childParentID == nil ||
                      job.childParentID == canonical {
                let started = job.startedItemIDs ??
                    (job.itemRequestStarted == true ? [job.itemSyncID] : [])
                let pendingStarted = audit.pendingItemSyncIDs.contains {
                    started.contains($0)
                }
                let unexpected = audit.issues.contains {
                    !$0.id.hasSuffix(".missing")
                }
                if !pendingStarted && !unexpected &&
                   job.state != .ready && job.state != .complete {
                    if job.childParentID == nil {
                        job.childParentID = canonical
                    }
                    job = try transition(job, to: .orderConfirmed,
                        "剩余商品尚未发送，可安全继续提交", in: storage)
                } else if job.state != .needsReview && job.state != .complete {
                    job = try transition(job, to: .needsReview,
                        "存在曾发出却未回读的商品或关联异常，禁止重传",
                        in: storage)
                }
            } else if job.state != .needsReview &&
                      job.state != .complete {
                job = try transition(job, to: .needsReview,
                    "云端父子订单异常，需要人工检查", in: storage)
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
