package com.tianxian.fruit.sync

import android.content.Context
import android.os.Build
import com.tianxian.fruit.data.AppDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class LedgerBook(
    val id: String,
    val name: String,
    val databaseName: String,
    val ownerDeviceId: String,
    val ownerUsername: String = "",
    val permission: String = "OWNER",
    val permissionTemplate: String = "OWNER",
    val permissions: Set<String> =
        BookPermissions.allMemberPermissions,
    val cloudBookId: String = "",
    val cloudEnabled: Boolean = false,
    val lastSyncAt: Long = 0L,
    val createdAt: Long,
    val updatedAt: Long,
    val isDefault: Boolean = false
)

class LedgerManager(
    private val context: Context
) {
    private val prefs =
        context.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

    val deviceId: String =
        prefs.getString(KEY_DEVICE_ID, null)
            ?: UUID.randomUUID()
                .toString()
                .also {
                    prefs.edit()
                        .putString(
                            KEY_DEVICE_ID,
                            it
                        )
                        .apply()
                }

    val deviceName: String =
        buildString {
            val manufacturer =
                Build.MANUFACTURER
                    .orEmpty()
                    .trim()
            val model =
                Build.MODEL
                    .orEmpty()
                    .trim()

            if (manufacturer.isNotBlank()) {
                append(manufacturer)
            }

            if (
                model.isNotBlank() &&
                !model.equals(
                    manufacturer,
                    ignoreCase = true
                )
            ) {
                if (isNotEmpty()) append(" ")
                append(model)
            }

            if (isEmpty()) {
                append("Android设备")
            }
        }

    init {
        ensureDefaultBook()
    }

    fun books(): List<LedgerBook> =
        readBooks()
            .sortedWith(
                compareByDescending<LedgerBook> {
                    it.isDefault
                }.thenBy {
                    it.createdAt
                }
            )

    fun currentBook(): LedgerBook {
        val books = books()
        val currentId =
            prefs.getString(
                KEY_CURRENT_BOOK_ID,
                null
            )

        return books.firstOrNull {
            it.id == currentId
        } ?: books.first().also {
            setCurrentBook(it.id)
        }
    }

    fun getBook(
        bookId: String
    ): LedgerBook? =
        books().firstOrNull {
            it.id == bookId
        }

    fun createBook(
        name: String
    ): LedgerBook? {
        val clean = name.trim()
        if (clean.isBlank()) return null

        val now =
            System.currentTimeMillis()
        val id =
            UUID.randomUUID()
                .toString()

        val book =
            LedgerBook(
                id = id,
                name = clean,
                databaseName =
                    "tianxian_book_" +
                        id.replace(
                            "-",
                            ""
                        ) +
                        ".db",
                ownerDeviceId =
                    deviceId,
                createdAt = now,
                updatedAt = now
            )

        val list =
            books().toMutableList()
        list += book
        saveBooks(list)
        return book
    }

    fun renameBook(
        bookId: String,
        newName: String
    ): Boolean {
        val clean =
            newName.trim()
        if (clean.isBlank()) {
            return false
        }

        var changed = false
        val now =
            System.currentTimeMillis()

        val updated =
            books().map {
                if (it.id == bookId) {
                    changed = true
                    it.copy(
                        name = clean,
                        updatedAt = now
                    )
                } else {
                    it
                }
            }

        if (changed) {
            saveBooks(updated)
        }

        return changed
    }

    fun setCurrentBook(
        bookId: String
    ): Boolean {
        if (
            books().none {
                it.id == bookId
            }
        ) {
            return false
        }

        prefs.edit()
            .putString(
                KEY_CURRENT_BOOK_ID,
                bookId
            )
            .apply()

        return true
    }

    fun deleteBook(
        bookId: String
    ): Boolean {
        val target =
            getBook(bookId)
                ?: return false

        if (
            target.isDefault ||
            currentBook().id == bookId
        ) {
            return false
        }

        val updated =
            books().filterNot {
                it.id == bookId
            }

        saveBooks(updated)

        context.deleteDatabase(
            target.databaseName
        )

        return true
    }

    fun upsertCloudBook(
        bookId: String,
        name: String,
        ownerUsername: String,
        permission: String,
        permissionTemplate: String = "LEGACY",
        permissions: Set<String> =
            BookPermissions.fallbackForRole(permission)
    ): LedgerBook {
        val cleanName =
            name.trim()
                .ifBlank {
                    "云端账本"
                }

        val existing =
            getBook(bookId)

        val now =
            System.currentTimeMillis()

        val updatedBook =
            if (existing != null) {
                existing.copy(
                    name = cleanName,
                    ownerUsername =
                        ownerUsername,
                    permission = permission,
                    permissionTemplate = permissionTemplate,
                    permissions = permissions,
                    cloudBookId = bookId,
                    cloudEnabled = true,
                    updatedAt = now
                )
            } else {
                LedgerBook(
                    id = bookId,
                    name = cleanName,
                    databaseName =
                        "tianxian_cloud_" +
                            bookId.replace(
                                "-",
                                ""
                            ) +
                            ".db",
                    ownerDeviceId = "",
                    ownerUsername =
                        ownerUsername,
                    permission =
                        permission,
                    permissionTemplate =
                        permissionTemplate,
                    permissions =
                        permissions,
                    cloudBookId =
                        bookId,
                    cloudEnabled = true,
                    createdAt = now,
                    updatedAt = now,
                    isDefault = false
                )
            }

        val list =
            books().toMutableList()

        val index =
            list.indexOfFirst {
                it.id == bookId
            }

        if (index >= 0) {
            list[index] =
                updatedBook
        } else {
            list +=
                updatedBook
        }

        saveBooks(list)

        return updatedBook
    }

    fun revokeCloudBooksForAccountSwitch() {
        val now =
            System.currentTimeMillis()

        val updated =
            books().map {
                book ->
                if (
                    book.cloudBookId
                        .isNotBlank()
                ) {
                    book.copy(
                        permission =
                            "REVOKED",
                        permissionTemplate =
                            "REVOKED",
                        permissions =
                            emptySet(),
                        cloudEnabled =
                            false,
                        updatedAt = now
                    )
                } else {
                    book
                }
            }

        saveBooks(updated)
    }

    fun reconcileCloudAccess(
        accessibleBookIds: Set<String>
    ) {
        val now =
            System.currentTimeMillis()

        var changed = false

        val updated =
            books().map {
                book ->
                if (
                    book.cloudBookId
                        .isNotBlank() &&
                    book.id !in
                        accessibleBookIds &&
                    book.permission !=
                        "REVOKED"
                ) {
                    changed = true
                    book.copy(
                        permission =
                            "REVOKED",
                        permissionTemplate =
                            "REVOKED",
                        permissions =
                            emptySet(),
                        cloudEnabled =
                            false,
                        updatedAt = now
                    )
                } else {
                    book
                }
            }

        if (changed) {
            saveBooks(updated)
        }
    }

    fun markCloudAccessRevoked(
        bookId: String
    ): Boolean {
        var changed = false
        val now =
            System.currentTimeMillis()

        val updated =
            books().map {
                if (it.id == bookId) {
                    changed = true
                    it.copy(
                        permission =
                            "REVOKED",
                        permissionTemplate =
                            "REVOKED",
                        permissions =
                            emptySet(),
                        cloudEnabled =
                            false,
                        updatedAt = now
                    )
                } else {
                    it
                }
            }

        if (changed) {
            saveBooks(updated)
        }

        return changed
    }

    fun updateCloudMetadata(
        bookId: String,
        name: String,
        ownerUsername: String,
        permission: String,
        permissionTemplate: String = "LEGACY",
        permissions: Set<String> =
            BookPermissions.fallbackForRole(permission)
    ): Boolean {
        val existing =
            getBook(bookId)
                ?: return false

        val now =
            System.currentTimeMillis()

        val updated =
            books().map {
                if (it.id == bookId) {
                    it.copy(
                        name =
                            name.trim()
                                .ifBlank {
                                    it.name
                                },
                        ownerUsername =
                            ownerUsername,
                        permission =
                            permission,
                        permissionTemplate =
                            permissionTemplate,
                        permissions =
                            permissions,
                        cloudBookId =
                            bookId,
                        cloudEnabled =
                            true,
                        updatedAt = now
                    )
                } else {
                    it
                }
            }

        saveBooks(updated)
        return existing.id == bookId
    }

    fun findBookByCloudId(
        cloudBookId: String
    ): LedgerBook? =
        books().firstOrNull {
            it.cloudBookId ==
                cloudBookId ||
                it.id ==
                cloudBookId
        }

    fun updateCloudState(
        bookId: String,
        cloudBookId: String,
        enabled: Boolean,
        lastSyncAt: Long
    ): Boolean {
        var changed = false
        val now =
            System.currentTimeMillis()

        val updated =
            books().map {
                if (it.id == bookId) {
                    changed = true
                    it.copy(
                        cloudBookId =
                            cloudBookId,
                        cloudEnabled =
                            enabled,
                        lastSyncAt =
                            lastSyncAt,
                        updatedAt = now
                    )
                } else {
                    it
                }
            }

        if (changed) {
            saveBooks(updated)
        }

        return changed
    }

    fun displayName(
        book: LedgerBook
    ): String {
        val owner =
            book.ownerUsername
                .trim()

        if (owner.isBlank()) {
            return book.name
        }

        return if (
            book.name ==
                "我的账本"
        ) {
            "${owner}的账本"
        } else {
            "${book.name}（$owner）"
        }
    }

    fun permissionLabel(
        permission: String
    ): String =
        when (permission) {
            "OWNER" ->
                "所有者"
            "SUPERADMIN",
            "EDITOR",
            "VIEWER" ->
                "成员访问"
            "REVOKED" ->
                "已失去云端权限"
            else ->
                "成员访问"
        }

    private fun ensureDefaultBook() {
        val existing =
            readBooksRaw()

        if (existing.isNotEmpty()) {
            if (
                prefs.getString(
                    KEY_CURRENT_BOOK_ID,
                    null
                ) == null
            ) {
                prefs.edit()
                    .putString(
                        KEY_CURRENT_BOOK_ID,
                        existing.first().id
                    )
                    .apply()
            }
            return
        }

        val now =
            System.currentTimeMillis()

        val defaultBook =
            LedgerBook(
                id =
                    UUID.randomUUID()
                        .toString(),
                name = "我的账本",
                databaseName =
                    AppDatabase.DB_NAME,
                ownerDeviceId =
                    deviceId,
                createdAt = now,
                updatedAt = now,
                isDefault = true
            )

        saveBooks(
            listOf(defaultBook)
        )

        prefs.edit()
            .putString(
                KEY_CURRENT_BOOK_ID,
                defaultBook.id
            )
            .apply()
    }

    private fun readBooks(): List<LedgerBook> {
        val list =
            readBooksRaw()

        return if (list.isEmpty()) {
            ensureDefaultBook()
            readBooksRaw()
        } else {
            list
        }
    }

    private fun readBooksRaw(): List<LedgerBook> {
        val raw =
            prefs.getString(
                KEY_BOOKS_JSON,
                null
            )
                ?: return emptyList()

        return runCatching {
            val array =
                JSONArray(raw)

            buildList {
                for (
                    i in 0 until
                        array.length()
                ) {
                    val obj =
                        array.getJSONObject(i)

                    add(
                        LedgerBook(
                            id =
                                obj.getString("id"),
                            name =
                                obj.getString("name"),
                            databaseName =
                                obj.getString(
                                    "databaseName"
                                ),
                            ownerDeviceId =
                                obj.optString(
                                    "ownerDeviceId",
                                    deviceId
                                ),
                            ownerUsername =
                                obj.optString(
                                    "ownerUsername",
                                    ""
                                ),
                            permission =
                                obj.optString(
                                    "permission",
                                    "OWNER"
                                ),
                            permissionTemplate =
                                obj.optString(
                                    "permissionTemplate",
                                    if (
                                        obj.optString(
                                            "permission",
                                            "OWNER"
                                        ) == "OWNER"
                                    ) {
                                        "OWNER"
                                    } else {
                                        "LEGACY"
                                    }
                                ),
                            permissions =
                                obj.optJSONArray(
                                    "permissions"
                                )?.let {
                                    array ->
                                    buildSet {
                                        for (
                                            index in 0 until
                                                array.length()
                                        ) {
                                            add(
                                                array.getString(
                                                    index
                                                )
                                            )
                                        }
                                    }
                                } ?: BookPermissions
                                    .fallbackForRole(
                                        obj.optString(
                                            "permission",
                                            "OWNER"
                                        )
                                    ),
                            cloudBookId =
                                obj.optString(
                                    "cloudBookId",
                                    ""
                                ),
                            cloudEnabled =
                                obj.optBoolean(
                                    "cloudEnabled",
                                    false
                                ),
                            lastSyncAt =
                                obj.optLong(
                                    "lastSyncAt",
                                    0L
                                ),
                            createdAt =
                                obj.optLong(
                                    "createdAt",
                                    0L
                                ),
                            updatedAt =
                                obj.optLong(
                                    "updatedAt",
                                    0L
                                ),
                            isDefault =
                                obj.optBoolean(
                                    "isDefault",
                                    false
                                )
                        )
                    )
                }
            }
        }.getOrElse {
            emptyList()
        }
    }

    private fun saveBooks(
        books: List<LedgerBook>
    ) {
        val array = JSONArray()

        books.forEach {
            book ->
            array.put(
                JSONObject().apply {
                    put("id", book.id)
                    put("name", book.name)
                    put(
                        "databaseName",
                        book.databaseName
                    )
                    put(
                        "ownerDeviceId",
                        book.ownerDeviceId
                    )
                    put(
                        "ownerUsername",
                        book.ownerUsername
                    )
                    put(
                        "permission",
                        book.permission
                    )
                    put(
                        "permissionTemplate",
                        book.permissionTemplate
                    )
                    put(
                        "permissions",
                        JSONArray().apply {
                            book.permissions
                                .sorted()
                                .forEach {
                                    put(it)
                                }
                        }
                    )
                    put(
                        "cloudBookId",
                        book.cloudBookId
                    )
                    put(
                        "cloudEnabled",
                        book.cloudEnabled
                    )
                    put(
                        "lastSyncAt",
                        book.lastSyncAt
                    )
                    put(
                        "createdAt",
                        book.createdAt
                    )
                    put(
                        "updatedAt",
                        book.updatedAt
                    )
                    put(
                        "isDefault",
                        book.isDefault
                    )
                }
            )
        }

        prefs.edit()
            .putString(
                KEY_BOOKS_JSON,
                array.toString()
            )
            .apply()
    }

    companion object {
        private const val PREFS_NAME =
            "tianxian_ledger_registry"
        private const val KEY_DEVICE_ID =
            "device_id"
        private const val KEY_BOOKS_JSON =
            "books_json"
        private const val KEY_CURRENT_BOOK_ID =
            "current_book_id"
    }
}
