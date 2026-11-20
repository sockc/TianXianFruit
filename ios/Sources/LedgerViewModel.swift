import Foundation
import SwiftUI

/// Saved only after /auth/me and /books succeed for this exact account.
/// No password and no business write permission is stored here.
private struct VerifiedProfile: Codable {
    let account: Account
    let books: [CloudBook]
}

@MainActor
final class LedgerViewModel: ObservableObject {
    @Published private(set) var account: Account?
    @Published private(set) var books: [CloudBook] = []
    @Published private(set) var selectedBookID: String?
    @Published private(set) var snapshot = ReadOnlySnapshot()
    @Published private(set) var isBusy = false
    @Published private(set) var errorMessage: String?
    @Published private(set) var lastSyncAt: Date?
    @Published private(set) var currentCursor: Int64 = 0
    @Published private(set) var isUsingCachedAuthorization = false
    @Published private(set) var cacheAvailable = true
    @Published private(set) var businessCommitCapabilities: BusinessCommitCapabilities?
    @Published private(set) var businessCommitError: String?
    @Published private(set) var isBusinessCommitBusy = false
    @Published private(set) var purchaseCommitCapabilities: PurchaseCommitCapabilities?
    @Published private(set) var purchaseCommitError: String?
    @Published private(set) var isPurchaseCommitBusy = false
    @Published private(set) var customerSnapshot = CustomerReadSnapshot()
    @Published private(set) var customerCursor: Int64 = 0
    @Published private(set) var customerSyncedAt: Date?
    @Published private(set) var customerError: String?
    @Published private(set) var isCustomerBusy = false
    @Published private(set) var customerAPIAvailable = false
    @Published private(set) var weatherOverview: WeatherForecastResponse?
    @Published private(set) var selectedWeatherStoreID = ""
    @Published private(set) var weatherCheckedAt: Date?
    @Published private(set) var weatherError: String?
    @Published private(set) var weatherBusy = false

    private let api = CloudAPI()
    private var cache: LocalLedgerCache?
    private var accessToken: String?
    private var restoring = false

    init() {
        do { cache = try LocalLedgerCache() }
        catch {
            cacheAvailable = false
            errorMessage = error.localizedDescription
        }
    }

    var selectedBook: CloudBook? { books.first(where: { $0.id == selectedBookID }) }

    var selectedWeatherLocation: WeatherLocation? {
        snapshot.weatherLocations.first { $0.id == selectedWeatherStoreID }
    }

    var weatherRisks: [WeatherBusinessRisk] {
        guard let overview = weatherOverview else { return [] }
        return WeatherWeekPolicy.risks(overview.daily, today: BusinessDates.key(Date()))
    }

    var weatherHeadline: String? { WeatherWeekPolicy.headline(weatherRisks) }

    func chooseWeatherLocation(_ storeID: String) {
        guard snapshot.weatherLocations.contains(where: { $0.id == storeID }) else { return }
        if selectedWeatherStoreID == storeID { return }
        selectedWeatherStoreID = storeID
        weatherOverview = nil
        weatherCheckedAt = nil
        weatherError = nil
        Task { await refreshWeather() }
    }

    /// Forecasts are intentionally ephemeral: NEVER persisted in SQLite,
    /// and NEVER pushed to the production server.
    func refreshWeather(force: Bool = false) async {
        guard !weatherBusy, let token = accessToken,
              let book = selectedBookID,
              let location = selectedWeatherLocation else { return }
        if !force, let at = weatherCheckedAt,
           weatherOverview != nil, Date().timeIntervalSince(at) < 30 * 60 { return }
        weatherBusy = true
        weatherError = nil
        defer { weatherBusy = false }
        do {
            let response = try await api.weatherForecast(
                token: token, bookID: book,
                latitude: location.latitude, longitude: location.longitude,
                date: BusinessDates.key(Date()))
            guard self.accessToken == token, self.selectedBookID == book,
                  self.selectedWeatherStoreID == location.id else { return }
            guard !response.daily.isEmpty else {
                throw CloudAPIError(status: 0, message: "天气服务暂未提供未来7日预报")
            }
            weatherOverview = response
            weatherCheckedAt = Date()
        } catch {
            weatherError = error.localizedDescription
        }
    }


    /// V1.0.27 server only accepts business commits to iOS_TEST_ books.
    /// The server is authoritative; this client guard prevents misleading UI.
    var businessTestBookAllowed: Bool {
        selectedBook?.name.hasPrefix("iOS_TEST_") == true
    }

    func refreshBusinessCommitCapabilities() async {
        businessCommitCapabilities = nil
        businessCommitError = nil
        guard let user = account, let token = accessToken,
              let book = selectedBookID, !isUsingCachedAuthorization else {
            businessCommitError = "账号尚未通过在线授权，正式营业上传不可用"
            return
        }
        guard businessTestBookAllowed else {
            businessCommitError = "当前版本只允许 iOS_TEST_ 测试账本上传营业；正式账本保持只读"
            return
        }
        do {
            let caps = try await api.businessCapabilities(token: token, bookID: book)
            guard self.account?.username == user.username,
                  self.accessToken == token, self.selectedBookID == book else { return }
            if caps.allowsWrite { businessCommitCapabilities = caps }
            else { businessCommitError = "正式营业事务接口未启用；只允许保存本机草稿" }
        } catch {
            businessCommitError = "服务器未提供正式营业事务能力；本机草稿不受影响"
        }
    }

    func commitBusiness(_ proposal: BusinessCommitProposal)
        async throws -> BusinessCommitReceipt {
        guard !isBusinessCommitBusy, !isBusy,
              let user = account, let book = selectedBook, let token = accessToken,
              !isUsingCachedAuthorization,
              user.username.lowercased() == proposal.account.lowercased(),
              book.id == proposal.bookID, book.name.hasPrefix("iOS_TEST_"),
              PurchaseWorkspaceDraftPolicy.authorized(book: book, account: user)
        else { throw BusinessAtomicError.scope }
        isBusinessCommitBusy = true
        defer { isBusinessCommitBusy = false }
        // Never use cached capability to authorize a transaction.
        let caps = try await api.businessCapabilities(token: token, bookID: book.id)
        guard caps.allowsWrite, self.accessToken == token,
              self.selectedBookID == book.id,
              self.account?.username == user.username else {
            throw BusinessAtomicError.unavailable
        }
        let receipt = try await api.businessCommit(token: token, proposal: proposal)
        guard self.accessToken == token, self.selectedBookID == book.id,
              self.account?.username == user.username else {
            throw BusinessAtomicError.scope
        }
        return receipt
    }

    func findBusinessCommit(_ proposal: BusinessCommitProposal)
        async throws -> BusinessCommitReceipt? {
        guard !isBusinessCommitBusy, let user = account,
              let book = selectedBook, let token = accessToken,
              !isUsingCachedAuthorization,
              user.username.lowercased() == proposal.account.lowercased(),
              book.id == proposal.bookID else { throw BusinessAtomicError.scope }
        isBusinessCommitBusy = true
        defer { isBusinessCommitBusy = false }
        let result = try await api.businessOperation(token: token, proposal: proposal)
        guard self.accessToken == token, self.selectedBookID == book.id,
              self.account?.username == user.username else {
            throw BusinessAtomicError.scope
        }
        return result
    }

    /// Never infer production-write capability from an Android/older server.
    /// Missing routes, offline access, or cached authorization all mean off.
    func refreshPurchaseCommitCapabilities() async {
        purchaseCommitCapabilities = nil
        purchaseCommitError = nil
        guard let account, let token = accessToken,
              let book = selectedBookID,
              !isUsingCachedAuthorization else { return }
        do {
            let caps = try await api.purchaseCapabilities(token: token, bookID: book)
            guard self.account?.username == account.username,
                  self.accessToken == token,
                  self.selectedBookID == book else { return }
            if caps.version == 1 && caps.atomic && caps.idempotency &&
               caps.feature == "purchase_atomic_commit_v1" {
                purchaseCommitCapabilities = caps
            }
        } catch {
            purchaseCommitError = "采购云端写入未启用；本机录入仍可使用"
        }
    }

    /// Call only after the SQLite immutable outbox is durably reserved.
    /// The HTTP request contains the existing account-scoped device ID.
    func commitPurchase(_ proposal: PurchaseCommitProposal)
        async throws -> PurchaseCommitReceipt {
        guard !isPurchaseCommitBusy, !isBusy,
              let account, let book = selectedBook,
              let token = accessToken,
              !isUsingCachedAuthorization,
              account.username.lowercased() == proposal.account.lowercased(),
              book.id == proposal.bookID,
              PurchaseWorkspaceDraftPolicy.authorized(book: book, account: account)
        else { throw PurchaseAtomicError.scopeChanged }
        isPurchaseCommitBusy = true
        defer { isPurchaseCommitBusy = false }
        // The capability is re-fetched before each actual write, so a stale
        // UI checkbox or a previous server version cannot enable mutation.
        let capabilities = try await api.purchaseCapabilities(
            token: token, bookID: book.id)
        guard capabilities.allows(proposal),
              self.accessToken == token,
              self.selectedBookID == book.id,
              self.account?.username == account.username else {
            throw PurchaseAtomicError.unsupported
        }
        let result = try await api.purchaseCommit(
            token: token, proposal: proposal)
        guard self.accessToken == token,
              self.selectedBookID == book.id,
              self.account?.username == account.username else {
            throw PurchaseAtomicError.scopeChanged
        }
        return result
    }

    /// Network timeout/unknown state: only this GET may be used for recovery.
    /// No second POST or fresh operation id is automatically generated.
    func findPurchaseCommit(_ proposal: PurchaseCommitProposal)
        async throws -> PurchaseCommitReceipt? {
        guard !isPurchaseCommitBusy,
              let account, let book = selectedBook,
              let token = accessToken, !isUsingCachedAuthorization,
              account.username.lowercased() == proposal.account.lowercased(),
              book.id == proposal.bookID else {
            throw PurchaseAtomicError.scopeChanged
        }
        isPurchaseCommitBusy = true
        defer { isPurchaseCommitBusy = false }
        let result = try await api.purchaseOperation(
            token: token, proposal: proposal)
        guard self.accessToken == token,
              self.selectedBookID == book.id,
              self.account?.username == account.username else {
            throw PurchaseAtomicError.scopeChanged
        }
        return result
    }

    private func saveProfile(_ profile: VerifiedProfile) throws {
        let bytes = try JSONEncoder().encode(profile)
        guard let json = String(data: bytes, encoding: .utf8) else {
            throw LocalCacheError.sql("授权资料编码失败")
        }
        try SecureStore.write("verified-profile", value: json)
    }

    private func cachedProfile() -> VerifiedProfile? {
        guard let json = SecureStore.read("verified-profile"),
              let bytes = json.data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode(VerifiedProfile.self, from: bytes)
    }

    private func activateBook(_ bookID: String) {
        guard books.contains(where: { $0.id == bookID }), let account else { return }
        selectedBookID = bookID
        snapshot = ReadOnlySnapshot()
        currentCursor = 0
        lastSyncAt = nil
        customerSnapshot = CustomerReadSnapshot()
        customerCursor = 0
        customerSyncedAt = nil
        customerAPIAvailable = false
        customerError = nil
        purchaseCommitCapabilities = nil
        purchaseCommitError = nil
        businessCommitCapabilities = nil
        businessCommitError = nil
        weatherOverview = nil
        weatherCheckedAt = nil
        weatherError = nil
        selectedWeatherStoreID = ""
        do {
            if let value = try cache?.load(account: account.username, book: bookID) {
                snapshot = value.snapshot
                currentCursor = value.cursor
                lastSyncAt = value.syncedAt
            }
            if let customer = try cache?.loadCustomer(account: account.username, book: bookID) {
                customerSnapshot = customer.snapshot
                customerCursor = customer.cursor
                customerSyncedAt = customer.syncedAt
            }
            selectedWeatherStoreID = snapshot.weatherLocations.first?.id ?? ""
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func restoreSession() async {
        guard !restoring, account == nil,
              let token = SecureStore.read("access-token") else { return }
        restoring = true
        isBusy = true
        defer { restoring = false; isBusy = false }

        do {
            let verifiedAccount = try await api.account(token: token)
            let cloudBooks = try await api.books(token: token)
            let verified = VerifiedProfile(account: verifiedAccount, books: cloudBooks)
            // Credential is already stored; profile update failure must not
            // result in an incorrectly authorized cross-user cached view.
            try saveProfile(verified)
            accessToken = token
            account = verifiedAccount
            books = cloudBooks
            isUsingCachedAuthorization = false
            if let first = cloudBooks.first { activateBook(first.id) }
            Task { await self.refresh(); await self.refreshCustomer() }
        } catch let error as CloudAPIError where error.status == 401 || error.status == 403 {
            if let cached = cachedProfile() {
                try? cache?.erase(account: cached.account.username)
            }
            SecureStore.erase("access-token")
            SecureStore.erase("verified-profile")
            reset()
            errorMessage = error.localizedDescription
        } catch {
            // Network errors keep the last *previously verified* account and
            // book list accessible offline, but never grant new permissions.
            guard let profile = cachedProfile() else {
                reset()
                errorMessage = "云端核验失败：\(error.localizedDescription)"
                return
            }
            accessToken = token
            account = profile.account
            books = profile.books.filter(\.isAvailable)
            isUsingCachedAuthorization = true
            if let first = books.first { activateBook(first.id) }
            errorMessage = "无法连接服务器，显示上次授权的离线缓存：\(error.localizedDescription)"
        }
    }

    func signIn(username: String, password: String) async {
        guard !isBusy else { return }
        isBusy = true
        errorMessage = nil
        defer { isBusy = false }
        do {
            let (token, verifiedAccount) = try await api.signIn(username: username, password: password)
            let cloudBooks = try await api.books(token: token)
            let profile = VerifiedProfile(account: verifiedAccount, books: cloudBooks)
            try saveProfile(profile)
            try SecureStore.write("access-token", value: token)
            reset()
            account = verifiedAccount
            books = cloudBooks
            accessToken = token
            if let first = cloudBooks.first { activateBook(first.id) }
            Task { await self.refresh(); await self.refreshCustomer() }
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func selectBook(_ bookID: String) {
        guard books.contains(where: { $0.id == bookID }) else { return }
        errorMessage = nil
        activateBook(bookID)
        // If an earlier refresh is in progress the user may press refresh
        // again after it finishes; stale-book requests never update the cache.
        Task { await self.refresh(); await self.refreshCustomer() }
    }

    func refresh(forceFull: Bool = false) async {
        guard !isBusy, let token = accessToken,
              let book = selectedBookID, let username = account?.username else { return }
        isBusy = true
        errorMessage = nil
        defer { isBusy = false }
        do {
            // Use last successfully COMMITTED cursor. The initial download
            // or explicit recheck starts at zero, but never clears existing
            // cache until every page has passed validation.
            let replace = forceFull || lastSyncAt == nil
            var candidate = replace ? ReadOnlySnapshot() : snapshot
            var nextCursor: Int64 = replace ? 0 : currentCursor
            var changes: [SyncEvent] = []
            var complete = false
            for _ in 0..<2000 {
                let page = try await api.pull(token: token, bookID: book, since: nextCursor)
                guard page.nextCursor >= nextCursor,
                      !(page.hasMore && page.nextCursor == nextCursor),
                      page.events.allSatisfy({ !$0.tableName.isEmpty && !$0.syncId.isEmpty && $0.rowVersion >= 0 }) else {
                    throw LocalCacheError.invalidEvent
                }
                candidate.apply(page.events)
                changes.append(contentsOf: page.events)
                nextCursor = page.nextCursor
                if !page.hasMore { complete = true; break }
            }
            guard complete else {
                throw CloudAPIError(status: 0, message: "记录页数超过安全上限，未修改本地缓存")
            }
            // An async logout, account switch or book switch invalidates this
            // response: never commit a former user's or book's records.
            guard self.accessToken == token,
                  self.account?.username == username,
                  self.selectedBookID == book else { return }
            let updated = Date()
            if let cache {
                try cache.commit(account: username, book: book, events: changes,
                                 cursor: nextCursor, replace: replace, at: updated)
            } else {
                errorMessage = "本地存储不可用，本次数据仅显示在内存中"
            }
            snapshot = candidate
            if !snapshot.weatherLocations.contains(where: { $0.id == selectedWeatherStoreID }) {
                selectedWeatherStoreID = snapshot.weatherLocations.first?.id ?? ""
                weatherOverview = nil
                weatherCheckedAt = nil
            }
            currentCursor = nextCursor
            lastSyncAt = updated
            isUsingCachedAuthorization = false
            Task { await self.refreshWeather() }
        } catch {
            // Never advance in-memory cursor or replace the prior snapshot on
            // partial download, invalid event, failed SQL transaction or 401.
            errorMessage = error.localizedDescription
        }
    }

    func signOut() {
        WeatherNotificationScheduler.clear()
        let username = account?.username
        SecureStore.erase("access-token")
        SecureStore.erase("verified-profile")
        reset()
        if let username {
            do { try cache?.erase(account: username) }
            catch { errorMessage = error.localizedDescription }
        }
    }

    private func reset() {
        account = nil
        books = []
        selectedBookID = nil
        accessToken = nil
        snapshot = ReadOnlySnapshot()
        currentCursor = 0
        lastSyncAt = nil
        isUsingCachedAuthorization = false
        errorMessage = nil
        customerSnapshot = CustomerReadSnapshot()
        customerCursor = 0
        customerSyncedAt = nil
        customerError = nil
        customerAPIAvailable = false
        isCustomerBusy = false
        businessCommitCapabilities = nil
        businessCommitError = nil
        isBusinessCommitBusy = false
        weatherOverview = nil
        selectedWeatherStoreID = ""
        weatherCheckedAt = nil
        weatherError = nil
        weatherBusy = false
    }

    /// Independent customer cursor and database transaction. A 404 or
    /// missing feature never pretends that the source is synchronized.
    func refreshCustomer(forceFull: Bool = false) async {
        guard !isCustomerBusy, let token = accessToken,
              let book = selectedBookID, let username = account?.username else { return }
        isCustomerBusy = true
        customerError = nil
        defer { isCustomerBusy = false }
        do {
            let caps = try await api.customerCapabilities(token: token, bookID: book)
            let replace = forceFull || customerSyncedAt == nil
            var candidate = replace ? CustomerReadSnapshot() : customerSnapshot
            var cursor = replace ? Int64(0) : customerCursor
            var updates: [CustomerChangeEvent] = []
            var completed = false

            for _ in 0..<1500 {
                let page = try await api.customerChanges(token: token, bookID: book,
                                                          since: cursor, capabilities: caps)
                guard page.nextCursor >= cursor,
                      !(page.hasMore && page.nextCursor == cursor),
                      page.changes.allSatisfy({
                          !$0.kind.isEmpty && !$0.key.isEmpty && $0.revision >= 0
                      }) else {
                    throw CloudAPIError(status: 0, message: "客户事件游标或记录无效；原缓存已保留")
                }
                candidate.apply(page.changes)
                updates.append(contentsOf: page.changes)
                cursor = page.nextCursor
                if !page.hasMore { completed = true; break }
            }
            guard completed else {
                throw CloudAPIError(status: 0, message: "客户数据超过安全分页上限；原缓存已保留")
            }
            guard self.accessToken == token,
                  self.selectedBookID == book,
                  self.account?.username == username else { return }

            let finishedAt = Date()
            guard let cache else {
                throw CloudAPIError(status: 0, message: "本机存储不可用，客户游标没有更新")
            }
            try cache.commitCustomer(account: username, book: book,
                                     events: updates, cursor: cursor,
                                     replace: replace, at: finishedAt)
            customerSnapshot = candidate
            customerCursor = cursor
            customerSyncedAt = finishedAt
            customerAPIAvailable = true
        } catch {
            customerError = error.localizedDescription
        }
    }
}
