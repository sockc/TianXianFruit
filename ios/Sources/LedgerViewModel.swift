import Foundation
import SwiftUI

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

    private let api = CloudAPI()
    private var accessToken: String?
    private var restoring = false

    var selectedBook: CloudBook? { books.first(where: { $0.id == selectedBookID }) }

    func restoreSession() async {
        guard !restoring, account == nil, let token = SecureStore.read("access-token") else { return }
        restoring = true
        isBusy = true
        defer { restoring = false; isBusy = false }
        do {
            let me = try await api.account(token: token)
            let cloudBooks = try await api.books(token: token)
            accessToken = token
            account = me
            books = cloudBooks
            if let book = cloudBooks.first { selectedBookID = book.id }
        } catch {
            // Never use old credentials to show another account's cached data.
            SecureStore.erase("access-token")
            clearSession()
            errorMessage = error.localizedDescription
        }
    }

    func signIn(username: String, password: String) async {
        guard !isBusy else { return }
        isBusy = true
        errorMessage = nil
        defer { isBusy = false }
        do {
            let (token, me) = try await api.signIn(username: username, password: password)
            let fetchedBooks = try await api.books(token: token)
            try SecureStore.write("access-token", value: token)
            clearSession()
            accessToken = token
            account = me
            books = fetchedBooks
            selectedBookID = fetchedBooks.first?.id
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func selectBook(_ bookID: String) {
        guard books.contains(where: { $0.id == bookID }) else { return }
        selectedBookID = bookID
        snapshot = ReadOnlySnapshot()
        currentCursor = 0
        lastSyncAt = nil
        errorMessage = nil
    }

    func refresh() async {
        guard !isBusy, let accessToken, let selectedBookID else { return }
        isBusy = true
        errorMessage = nil
        defer { isBusy = false }
        do {
            // Build an isolated snapshot first. On network error, keep the
            // last fully downloaded snapshot and its cursor unchanged.
            var fresh = ReadOnlySnapshot()
            var cursor: Int64 = 0
            var completed = false
            for _ in 0..<2000 {
                let page = try await api.pull(token: accessToken,
                                              bookID: selectedBookID, since: cursor)
                guard page.nextCursor >= cursor,
                      !(page.hasMore && page.nextCursor == cursor) else {
                    throw CloudAPIError(status: 0, message: "云端游标异常，未覆盖已显示数据")
                }
                fresh.apply(page.events)
                cursor = page.nextCursor
                if !page.hasMore { completed = true; break }
            }
            guard completed else {
                throw CloudAPIError(status: 0, message: "账本数据页数超过安全上限，未更新只读视图")
            }
            // A logout or book switch during download must not reveal the old book.
            guard self.accessToken == accessToken,
                  self.selectedBookID == selectedBookID else { return }
            self.snapshot = fresh
            self.currentCursor = cursor
            self.lastSyncAt = Date()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func signOut() {
        SecureStore.erase("access-token")
        clearSession()
    }

    private func clearSession() {
        account = nil
        books = []
        selectedBookID = nil
        accessToken = nil
        snapshot = ReadOnlySnapshot()
        currentCursor = 0
        lastSyncAt = nil
        errorMessage = nil
    }
}
