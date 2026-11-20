package com.tianxian.fruit.sync

/**
 * Opening ledger management is a navigation capability, not an administrator
 * privilege. The API /books response is already account-scoped; only the
 * returned, non-revoked ledgers may be listed for an ordinary member.
 *
 * Destructive cloud actions are intentionally distinct from viewing and
 * switching ledgers. Server-side authorization remains authoritative.
 */
internal object LedgerManagementAccessPolicy {
    fun visibleCloudBooks(books: List<CloudBookInfo>): List<CloudBookInfo> =
        books.filterNot { it.deleted || it.purged || it.role == "REVOKED" }

    fun visibleLocalBooks(
        books: List<LedgerBook>,
        systemRole: String
    ): List<LedgerBook> =
        if (systemRole == "SUPERADMIN") books
        else books.filter { it.permission != "REVOKED" }

    fun canManageCloudBook(book: CloudBookInfo, systemRole: String): Boolean =
        systemRole == "SUPERADMIN" ||
            book.role == "OWNER" || book.role == "SUPERADMIN"

    fun canOpenCloudTrash(
        books: List<CloudBookInfo>,
        systemRole: String
    ): Boolean =
        systemRole == "SUPERADMIN" ||
            visibleCloudBooks(books).any { it.role == "OWNER" }
}
