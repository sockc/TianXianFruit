import XCTest
@testable import TianXianFruit

final class BusinessLocalDraftStorageTests: XCTestCase {
    private func draft(_ id: String) -> BusinessLocalDraft {
        BusinessLocalDraft(
            id: id, date: "2026-11-22", storeID: "stall-1", storeName: "龙归",
            collectors: [BusinessCollectorDraftLine(
                partnerSyncID: "owner-1", wechat: "120", alipay: "30", cash: "0")],
            startTime: "", endTime: "", note: "未填成本先保存",
            expense: "", openingStock: "", closingStock: "",
            updatedAt: Date())
    }

    private func storageKey(_ account: String, _ book: String) -> String {
        "ios.formal.business.drafts.v1." +
        account.lowercased() + "." + book.lowercased()
    }

    func testIncompleteBusinessDraftSurvivesRoundTripAndBookIsolation() throws {
        let account = "ios-operator-" + UUID().uuidString
        let book = UUID().uuidString
        let other = UUID().uuidString
        let key = storageKey(account, book)
        defer {
            UserDefaults.standard.removeObject(forKey: key)
            UserDefaults.standard.removeObject(forKey: key + ".backup")
        }
        let item = draft(UUID().uuidString)
        try BusinessLocalDraftStore.save(item, account: account, bookID: book)
        let stored = try BusinessLocalDraftStore.loadChecked(
            account: account, bookID: book)
        XCTAssertEqual(stored.first?.id, item.id)
        XCTAssertEqual(stored.first?.openingStock, "")
        XCTAssertTrue(BusinessLocalDraftStore.load(
            account: account, bookID: other).isEmpty)
    }

    func testCorruptDraftBlocksOverwriteInsteadOfSilentlyDeletingOriginal() throws {
        let account = "ios-operator-" + UUID().uuidString
        let book = UUID().uuidString
        let key = storageKey(account, book)
        defer { UserDefaults.standard.removeObject(forKey: key) }
        let corrupt = Data("not-json".utf8)
        UserDefaults.standard.set(corrupt, forKey: key)
        XCTAssertThrowsError(try BusinessLocalDraftStore.loadChecked(
            account: account, bookID: book))
        XCTAssertThrowsError(try BusinessLocalDraftStore.save(
            draft(UUID().uuidString), account: account, bookID: book))
        XCTAssertEqual(UserDefaults.standard.data(forKey: key), corrupt)
    }
}
