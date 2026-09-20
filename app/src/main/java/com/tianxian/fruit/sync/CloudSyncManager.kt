package com.tianxian.fruit.sync

import android.content.Context
import com.tianxian.fruit.data.AppDatabase
import com.tianxian.fruit.data.SyncChangeRecord
import com.tianxian.fruit.data.SyncConflictRecord
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class CloudSession(
    val baseUrl: String,
    val username: String,
    val displayName: String,
    val systemRole: String,
    val token: String
)

data class CloudBookInfo(
    val id: String,
    val name: String,
    val role: String,
    val ownerUserId: String,
    val ownerUsername: String,
    val deleted: Boolean,
    val purged: Boolean = false,
    val createdAt: String = "",
    val updatedAt: String = "",
    val deletedAt: String = "",
    val recordCount: Int = 0,
    val memberCount: Int = 0,
    val permissionTemplate: String = "LEGACY",
    val permissions: Set<String> = emptySet()
)

data class CloudAdminUserInfo(
    val id: String,
    val username: String,
    val displayName: String,
    val systemRole: String,
    val active: Boolean,
    val ownedBookCount: Int,
    val memberBookCount: Int,
    val deviceCount: Int,
    val createdAt: String
)

data class CloudAdminDeviceInfo(
    val deviceId: String,
    val username: String,
    val displayName: String,
    val name: String,
    val platform: String,
    val appVersion: String,
    val revoked: Boolean,
    val createdAt: String,
    val lastSeenAt: String
)

data class CloudSystemAuditInfo(
    val id: Long,
    val actorUsername: String,
    val action: String,
    val targetType: String,
    val targetId: String,
    val createdAt: String
)

data class CloudMemberInfo(
    val userId: String,
    val username: String,
    val displayName: String,
    val role: String,
    val systemRole: String = "USER",
    val permissionTemplate: String = "LEGACY",
    val permissions: Set<String> = emptySet(),
    val visibleUserIds: Set<String> = emptySet()
)

data class CloudAuditInfo(
    val id: Long,
    val username: String,
    val displayName: String,
    val deviceName: String,
    val action: String,
    val tableName: String,
    val syncId: String,
    val beforePayload: JSONObject?,
    val afterPayload: JSONObject?,
    val createdAt: String
)

data class CloudSyncResult(
    val uploaded: Int,
    val downloaded: Int,
    val conflicts: Int,
    val serverCursor: Long,
    val message: String
)

class CloudApiException(
    val statusCode: Int,
    message: String
) : Exception(message)

class CloudSyncManager(
    context: Context,
    private val ledgerManager: LedgerManager
) {
    private val appContext =
        context.applicationContext

    private val prefs =
        appContext.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

    private val autoExecutor =
        Executors.newSingleThreadExecutor()

    private val autoRunning =
        AtomicBoolean(false)

    @Volatile
    private var autoPending = false

    @Volatile
    private var closed = false

    @Volatile
    private var autoSyncListener:
        (() -> Unit)? =
        null

    fun setAutoSyncListener(
        listener:
            (() -> Unit)?
    ) {
        autoSyncListener = listener
    }

    fun defaultBaseUrl(): String =
        prefs.getString(
            KEY_BASE_URL,
            DEFAULT_BASE_URL
        )
            ?.trim()
            ?.trimEnd('/')
            .orEmpty()
            .ifBlank {
                DEFAULT_BASE_URL
            }

    fun session(): CloudSession? {
        val token =
            prefs.getString(
                KEY_TOKEN,
                null
            )
                ?: return null

        val username =
            prefs.getString(
                KEY_USERNAME,
                ""
            ).orEmpty()

        val displayName =
            prefs.getString(
                KEY_DISPLAY_NAME,
                username
            ).orEmpty()

        return CloudSession(
            baseUrl =
                defaultBaseUrl(),
            username = username,
            displayName = displayName,
            systemRole =
                prefs.getString(
                    KEY_SYSTEM_ROLE,
                    "USER"
                ).orEmpty(),
            token = token
        )
    }

    fun login(
        baseUrl: String,
        username: String,
        password: String
    ): CloudSession {
        val cleanBase =
            normalizeBaseUrl(baseUrl)

        val loginJson =
            requestJson(
                baseUrl = cleanBase,
                method = "POST",
                path =
                    "/api/v1/auth/login",
                body =
                    JSONObject().apply {
                        put(
                            "username",
                            username.trim()
                        )
                        put(
                            "password",
                            password
                        )
                        put(
                            "device_id",
                            serverDeviceId(
                                username
                            )
                        )
                        put(
                            "device_name",
                            ledgerManager.deviceName
                        )
                        put(
                            "platform",
                            "android"
                        )
                        put(
                            "app_version",
                            APP_VERSION
                        )
                    },
                token = null
            ) as JSONObject

        val token =
            loginJson.getString(
                "access_token"
            )

        val me =
            requestJson(
                baseUrl = cleanBase,
                method = "GET",
                path =
                    "/api/v1/auth/me",
                body = null,
                token = token
            ) as JSONObject

        val result =
            CloudSession(
                baseUrl =
                    cleanBase,
                username =
                    me.getString(
                        "username"
                    ),
                displayName =
                    me.getString(
                        "display_name"
                    ),
                systemRole =
                    me.optString(
                        "system_role",
                        "USER"
                    ),
                token = token
            )

        // First register the account-scoped device ID.
        // If registration fails, do not overwrite the old local login.
        registerDevice(result)

        // Never let a newly logged-in account inherit cached
        // OWNER/EDITOR permissions from the previously used account.
        // listCloudBooks() below restores only books this account can access.
        ledgerManager
            .revokeCloudBooksForAccountSwitch()

        prefs.edit()
            .putString(
                KEY_BASE_URL,
                cleanBase
            )
            .putString(
                KEY_TOKEN,
                result.token
            )
            .putString(
                KEY_USERNAME,
                result.username
            )
            .putString(
                KEY_DISPLAY_NAME,
                result.displayName
            )
            .putString(
                KEY_SYSTEM_ROLE,
                result.systemRole
            )
            .apply()

        // Refresh local permissions immediately. If this fails,
        // a switched account keeps previous cloud books read-only.
        runCatching {
            listCloudBooks()
        }

        return result
    }

    fun verifyCurrentAccountPassword(
        password: String
    ): Boolean {
        if (password.isBlank()) return false

        val current = requireSession()
        val loginJson =
            requestJson(
                baseUrl = current.baseUrl,
                method = "POST",
                path = "/api/v1/auth/login",
                body =
                    JSONObject().apply {
                        put("username", current.username)
                        put("password", password)
                        put("device_id", serverDeviceId(current.username))
                        put("device_name", ledgerManager.deviceName)
                        put("platform", "android")
                        put("app_version", APP_VERSION)
                    },
                token = null
            ) as JSONObject

        val token = loginJson.optString("access_token")
        if (token.isBlank()) return false

        val me =
            requestJson(
                baseUrl = current.baseUrl,
                method = "GET",
                path = "/api/v1/auth/me",
                body = null,
                token = token
            ) as JSONObject

        return me.optString("username") == current.username
    }

    fun logout() {
        ledgerManager
            .revokeCloudBooksForAccountSwitch()

        prefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_USERNAME)
            .remove(KEY_DISPLAY_NAME)
            .remove(KEY_SYSTEM_ROLE)
            .apply()
    }

    fun verifyLogin(): CloudSession {
        val current =
            requireSession()

        val me =
            requestJson(
                baseUrl =
                    current.baseUrl,
                method = "GET",
                path =
                    "/api/v1/auth/me",
                body = null,
                token = current.token
            ) as JSONObject

        val refreshed =
            current.copy(
                username =
                    me.getString(
                        "username"
                    ),
                displayName =
                    me.getString(
                        "display_name"
                    ),
                systemRole =
                    me.optString(
                        "system_role",
                        "USER"
                    )
            )

        prefs.edit()
            .putString(
                KEY_USERNAME,
                refreshed.username
            )
            .putString(
                KEY_DISPLAY_NAME,
                refreshed.displayName
            )
            .putString(
                KEY_SYSTEM_ROLE,
                refreshed.systemRole
            )
            .apply()

        return refreshed
    }

    private fun parseStringSet(
        array: JSONArray?
    ): Set<String> =
        if (array == null) {
            emptySet()
        } else {
            buildSet {
                for (
                    index in 0 until
                        array.length()
                ) {
                    add(array.getString(index))
                }
            }
        }

    private fun parseCloudBook(
        item: JSONObject
    ): CloudBookInfo =
        CloudBookInfo(
            id = item.getString("id"),
            name = item.getString("name"),
            role = item.getString("role"),
            ownerUserId =
                item.getString("owner_user_id"),
            ownerUsername =
                item.optString("owner_username", ""),
            deleted =
                item.optBoolean("deleted", false),
            purged =
                item.optBoolean("purged", false),
            createdAt =
                item.optString("created_at", ""),
            updatedAt =
                item.optString("updated_at", ""),
            deletedAt =
                item.optString("deleted_at", ""),
            recordCount =
                item.optInt("record_count", 0),
            memberCount =
                item.optInt("member_count", 0),
            permissionTemplate =
                item.optString(
                    "permission_template",
                    "LEGACY"
                ),
            permissions =
                parseStringSet(
                    item.optJSONArray("permissions")
                ).ifEmpty {
                    BookPermissions
                        .fallbackForRole(
                            item.getString("role")
                        )
                }
        )

    fun listCloudBooks(): List<CloudBookInfo> {
        val current = requireSession()
        val array = requestJson(
            baseUrl = current.baseUrl,
            method = "GET",
            path = "/api/v1/books",
            body = null,
            token = current.token
        ) as JSONArray

        val result = buildList {
            for (i in 0 until array.length()) {
                add(parseCloudBook(array.getJSONObject(i)))
            }
        }.filterNot { it.deleted || it.purged }

        result.forEach { info ->
            if (ledgerManager.getBook(info.id) != null) {
                ledgerManager.updateCloudMetadata(
                    bookId = info.id,
                    name = info.name,
                    ownerUsername = info.ownerUsername,
                    permission = info.role,
                    permissionTemplate =
                        info.permissionTemplate,
                    permissions =
                        info.permissions
                )
            }
        }
        ledgerManager.reconcileCloudAccess(result.map { it.id }.toSet())
        return result
    }

    fun listDeletedCloudBooks(): List<CloudBookInfo> {
        val current = requireSession()
        val array = requestJson(
            baseUrl = current.baseUrl,
            method = "GET",
            path = "/api/v1/books/trash",
            body = null,
            token = current.token
        ) as JSONArray
        return buildList {
            for (i in 0 until array.length()) {
                add(parseCloudBook(array.getJSONObject(i)))
            }
        }
    }

    private fun parseMember(
        item: JSONObject
    ): CloudMemberInfo =
        CloudMemberInfo(
            userId =
                item.getString("user_id"),
            username =
                item.getString("username"),
            displayName =
                item.getString("display_name"),
            role =
                item.getString("role"),
            systemRole =
                item.optString(
                    "system_role",
                    "USER"
                ),
            permissionTemplate =
                item.optString(
                    "permission_template",
                    "LEGACY"
                ),
            permissions =
                parseStringSet(
                    item.optJSONArray("permissions")
                ).ifEmpty {
                    BookPermissions
                        .fallbackForRole(
                            item.getString("role")
                        )
                },
            visibleUserIds =
                parseStringSet(
                    item.optJSONArray("visible_user_ids")
                )
        )

    fun listMembers(
        bookId: String
    ): List<CloudMemberInfo> {
        val current =
            requireSession()

        val encoded =
            URLEncoder.encode(
                bookId,
                "UTF-8"
            )

        val array =
            requestJson(
                baseUrl =
                    current.baseUrl,
                method = "GET",
                path =
                    "/api/v1/books/" +
                        "$encoded/members",
                body = null,
                token = current.token
            ) as JSONArray

        return buildList {
            for (
                index in 0 until
                    array.length()
            ) {
                add(
                    parseMember(
                        array.getJSONObject(index)
                    )
                )
            }
        }
    }

    fun listAudit(
        bookId: String,
        limit: Int = 100
    ): List<CloudAuditInfo> {
        val current =
            requireSession()

        val encoded =
            URLEncoder.encode(
                bookId,
                "UTF-8"
            )

        val array =
            requestJson(
                baseUrl =
                    current.baseUrl,
                method = "GET",
                path =
                    "/api/v1/books/" +
                        "$encoded/audit" +
                        "?limit=$limit",
                body = null,
                token = current.token
            ) as JSONArray

        return buildList {
            for (
                i in 0 until
                    array.length()
            ) {
                val item =
                    array.getJSONObject(i)

                add(
                    CloudAuditInfo(
                        id =
                            item.getLong(
                                "id"
                            ),
                        username =
                            item.optString(
                                "username",
                                ""
                            ),
                        displayName =
                            item.optString(
                                "display_name",
                                ""
                            ),
                        deviceName =
                            item.optString(
                                "device_name",
                                ""
                            ),
                        action =
                            item.optString(
                                "action",
                                ""
                            ),
                        tableName =
                            item.optString(
                                "table_name",
                                ""
                            ),
                        syncId =
                            item.optString(
                                "sync_id",
                                ""
                            ),
                        beforePayload =
                            item.optJSONObject(
                                "before_payload"
                            ),
                        afterPayload =
                            item.optJSONObject(
                                "after_payload"
                            ),
                        createdAt =
                            item.optString(
                                "created_at",
                                ""
                            )
                    )
                )
            }
        }
    }

    fun addOrUpdateMember(
        bookId: String,
        username: String,
        permissionTemplate: String,
        permissions: Set<String>
    ): CloudMemberInfo {
        val current =
            requireSession()

        val encoded =
            URLEncoder.encode(
                bookId,
                "UTF-8"
            )

        val item =
            requestJson(
                baseUrl =
                    current.baseUrl,
                method = "POST",
                path =
                    "/api/v1/books/" +
                        "$encoded/members",
                body =
                    JSONObject().apply {
                        put(
                            "username",
                            username.trim()
                        )
                        put(
                            "permission_template",
                            permissionTemplate
                        )
                        put(
                            "permissions",
                            JSONArray().apply {
                                permissions.sorted()
                                    .forEach {
                                        put(it)
                                    }
                            }
                        )
                    },
                token = current.token
            ) as JSONObject

        return parseMember(item)
    }

    fun updateMemberPermissions(
        bookId: String,
        userId: String,
        permissionTemplate: String,
        permissions: Set<String>
    ): CloudMemberInfo {
        val current =
            requireSession()

        val encodedBook =
            URLEncoder.encode(
                bookId,
                "UTF-8"
            )
        val encodedUser =
            URLEncoder.encode(
                userId,
                "UTF-8"
            )

        val item =
            requestJson(
                baseUrl =
                    current.baseUrl,
                method = "PATCH",
                path =
                    "/api/v1/books/" +
                        "$encodedBook/members/" +
                        "$encodedUser/permissions",
                body =
                    JSONObject().apply {
                        put(
                            "permission_template",
                            permissionTemplate
                        )
                        put(
                            "permissions",
                            JSONArray().apply {
                                permissions.sorted()
                                    .forEach {
                                        put(it)
                                    }
                            }
                        )
                    },
                token = current.token
            ) as JSONObject

        return parseMember(item)
    }

    fun updateMemberVisibility(
        bookId: String,
        userId: String,
        visibleUserIds: Set<String>
    ): CloudMemberInfo {
        val current = requireSession()
        val encodedBook =
            URLEncoder.encode(bookId, "UTF-8")
        val encodedUser =
            URLEncoder.encode(userId, "UTF-8")

        val item =
            requestJson(
                baseUrl = current.baseUrl,
                method = "PATCH",
                path =
                    "/api/v1/books/" +
                        "$encodedBook/members/" +
                        "$encodedUser/visibility",
                body =
                    JSONObject().apply {
                        put(
                            "visible_user_ids",
                            JSONArray().apply {
                                visibleUserIds
                                    .sorted()
                                    .forEach { put(it) }
                            }
                        )
                    },
                token = current.token
            ) as JSONObject

        return parseMember(item)
    }

    fun removeMember(
        bookId: String,
        userId: String
    ) {
        val current =
            requireSession()

        val encodedBook =
            URLEncoder.encode(
                bookId,
                "UTF-8"
            )
        val encodedUser =
            URLEncoder.encode(
                userId,
                "UTF-8"
            )

        requestJson(
            baseUrl =
                current.baseUrl,
            method = "DELETE",
            path =
                "/api/v1/books/" +
                    "$encodedBook/members/" +
                    encodedUser,
            body = null,
            token = current.token
        )
    }

    fun renameCloudBook(bookId: String, name: String): CloudBookInfo {
        val current = requireSession()
        val encoded = URLEncoder.encode(bookId, "UTF-8")
        val item = requestJson(
            baseUrl = current.baseUrl, method = "PATCH",
            path = "/api/v1/books/$encoded",
            body = JSONObject().apply { put("name", name.trim()) },
            token = current.token
        ) as JSONObject
        val result = parseCloudBook(item)
        ledgerManager.updateCloudMetadata(
            result.id,
            result.name,
            result.ownerUsername,
            result.role,
            result.permissionTemplate,
            result.permissions
        )
        return result
    }

    fun deleteCloudBook(bookId: String) {
        val current = requireSession()
        val encoded = URLEncoder.encode(bookId, "UTF-8")
        requestJson(current.baseUrl, "DELETE", "/api/v1/books/$encoded", null, current.token)
        ledgerManager.markCloudAccessRevoked(bookId)
    }

    fun restoreCloudBook(bookId: String): CloudBookInfo {
        val current = requireSession()
        val encoded = URLEncoder.encode(bookId, "UTF-8")
        val item = requestJson(
            current.baseUrl, "POST", "/api/v1/books/$encoded/restore",
            JSONObject(), current.token
        ) as JSONObject
        return parseCloudBook(item)
    }

    fun purgeCloudBook(bookId: String) {
        val current = requireSession()
        val encoded = URLEncoder.encode(bookId, "UTF-8")
        requestJson(current.baseUrl, "DELETE", "/api/v1/books/$encoded/purge", null, current.token)
    }

    fun transferCloudBookOwner(bookId: String, username: String): CloudBookInfo {
        val current = requireSession()
        val encoded = URLEncoder.encode(bookId, "UTF-8")
        val item = requestJson(
            current.baseUrl, "POST", "/api/v1/books/$encoded/transfer-owner",
            JSONObject().apply { put("username", username.trim()) }, current.token
        ) as JSONObject
        return parseCloudBook(item)
    }

    fun createAdminUser(
        username: String,
        displayName: String,
        password: String
    ) {
        val current =
            requireSession()

        requestJson(
            baseUrl =
                current.baseUrl,
            method = "POST",
            path =
                "/api/v1/admin/users",
            body =
                JSONObject().apply {
                    put("username", username.trim())
                    put("display_name", displayName.trim())
                    put("password", password)
                },
            token = current.token
        )
    }

    fun listAdminUsers(): List<CloudAdminUserInfo> {
        val current = requireSession()
        val array = requestJson(current.baseUrl, "GET", "/api/v1/admin/users", null, current.token) as JSONArray
        return buildList {
            for (i in 0 until array.length()) {
                val x = array.getJSONObject(i)
                add(CloudAdminUserInfo(
                    id=x.getString("id"), username=x.getString("username"),
                    displayName=x.optString("display_name", ""), systemRole=x.optString("system_role", "USER"),
                    active=x.optBoolean("is_active", true), ownedBookCount=x.optInt("owned_book_count",0),
                    memberBookCount=x.optInt("member_book_count",0), deviceCount=x.optInt("device_count",0),
                    createdAt=x.optString("created_at","")
                ))
            }
        }
    }

    fun setAdminUserActive(userId: String, active: Boolean) {
        val current=requireSession()
        requestJson(current.baseUrl,"PATCH","/api/v1/admin/users/${URLEncoder.encode(userId,"UTF-8")}",
            JSONObject().apply{put("is_active",active)},current.token)
    }

    fun updateAdminUserDisplayName(
        userId: String,
        displayName: String
    ) {
        val current = requireSession()
        requestJson(
            current.baseUrl,
            "PATCH",
            "/api/v1/admin/users/${URLEncoder.encode(userId, "UTF-8")}",
            JSONObject().apply { put("display_name", displayName.trim()) },
            current.token
        )
    }

    fun deleteAdminUser(userId: String) {
        val current = requireSession()
        requestJson(
            current.baseUrl,
            "DELETE",
            "/api/v1/admin/users/${URLEncoder.encode(userId, "UTF-8")}",
            null,
            current.token
        )
    }

    fun resetAdminUserPassword(
        userId: String,
        newPassword: String
    ) {
        val current = requireSession()
        requestJson(
            current.baseUrl,
            "POST",
            "/api/v1/admin/users/${URLEncoder.encode(userId, "UTF-8")}/reset-password",
            JSONObject().apply { put("new_password", newPassword) },
            current.token
        )
    }

    fun forceLogoutAllDevices(userId: String) {
        val current=requireSession()
        requestJson(current.baseUrl,"POST","/api/v1/admin/users/${URLEncoder.encode(userId,"UTF-8")}/logout-all",JSONObject(),current.token)
    }

    fun listAdminDevices(): List<CloudAdminDeviceInfo> {
        val current=requireSession()
        val array=requestJson(current.baseUrl,"GET","/api/v1/admin/devices",null,current.token) as JSONArray
        return buildList {
            for(i in 0 until array.length()){
                val x=array.getJSONObject(i)
                add(CloudAdminDeviceInfo(
                    deviceId=x.getString("device_id"), username=x.getString("username"), displayName=x.optString("display_name",""),
                    name=x.optString("name",""), platform=x.optString("platform",""), appVersion=x.optString("app_version",""),
                    revoked=x.optBoolean("revoked",false), createdAt=x.optString("created_at",""), lastSeenAt=x.optString("last_seen_at","")
                ))
            }
        }
    }

    fun revokeAdminDevice(deviceId: String) {
        val current=requireSession()
        requestJson(current.baseUrl,"DELETE","/api/v1/admin/devices/${URLEncoder.encode(deviceId,"UTF-8")}",null,current.token)
    }

    fun listSystemAudit(limit: Int = 100): List<CloudSystemAuditInfo> {
        val current=requireSession()
        val array=requestJson(current.baseUrl,"GET","/api/v1/admin/audit?limit=$limit",null,current.token) as JSONArray
        return buildList {
            for(i in 0 until array.length()){
                val x=array.getJSONObject(i)
                add(CloudSystemAuditInfo(
                    id=x.getLong("id"), actorUsername=x.optString("actor_username",""), action=x.optString("action",""),
                    targetType=x.optString("target_type",""), targetId=x.optString("target_id",""), createdAt=x.optString("created_at","")
                ))
            }
        }
    }

    private fun hasCloudPermission(
        book: CloudBookInfo,
        permission: String
    ): Boolean {
        if (
            book.role == "SUPERADMIN" ||
            book.role == "OWNER"
        ) {
            return true
        }

        val effective =
            if (book.permissions.isNotEmpty()) {
                book.permissions
            } else {
                BookPermissions
                    .fallbackForRole(
                        book.role
                    )
            }

        return permission in effective
    }

    private fun canPushChange(
        book: CloudBookInfo,
        change: SyncChangeRecord
    ): Boolean {
        val permissions =
            when (change.tableName) {
                "purchase_order",
                "purchase_item",
                "purchase_activity" ->
                    if (
                        change.operation ==
                        "DELETE"
                    ) {
                        setOf(
                            BookPermissions
                                .PURCHASE_DELETE
                        )
                    } else {
                        setOf(
                            BookPermissions
                                .PURCHASE_CREATE,
                            BookPermissions
                                .PURCHASE_EDIT
                        )
                    }

                "purchase_plan",
                "purchase_plan_item",
                "purchase_collaboration" ->
                    setOf(
                        BookPermissions
                            .PURCHASE_PLAN_EDIT
                    )

                "store_daily_record" ->
                    setOf(
                        BookPermissions
                            .BUSINESS_EDIT
                    )

                "inventory_snapshot" ->
                    setOf(
                        BookPermissions.PURCHASE_EDIT,
                        BookPermissions.BUSINESS_EDIT
                    )

                "profit_rule",
                "profit_distribution" ->
                    setOf(
                        BookPermissions
                            .PROFIT_EDIT
                    )

                "daily_cash_settlement",
                "settlement_partner",
                "settlement_transfer",
                "profit_settlement_batch",
                "profit_settlement_item" ->
                    setOf(
                        BookPermissions
                            .SETTLEMENT_EDIT
                    )

                "fruit",
                "store",
                "partner" ->
                    setOf(
                        BookPermissions
                            .BASIC_EDIT
                    )

                else ->
                    emptySet()
            }

        return permissions.any {
            hasCloudPermission(
                book,
                it
            )
        }
    }

    private fun canWriteCloudBook(
        role: String
    ): Boolean =
        role == "SUPERADMIN" ||
            role == "OWNER" ||
            role == "EDITOR"

    private fun hasOwnerLevelCloudAccess(
        role: String
    ): Boolean =
        role == "SUPERADMIN" ||
            role == "OWNER"

    fun scheduleAutoSync(
        book: LedgerBook
    ) {
        if (
            closed ||
            session() == null
        ) {
            return
        }

        autoPending = true

        if (
            !autoRunning.compareAndSet(
                false,
                true
            )
        ) {
            return
        }

        autoExecutor.execute {
            try {
                while (
                    !closed &&
                    autoPending
                ) {
                    autoPending = false

                    try {
                        Thread.sleep(
                            AUTO_SYNC_DEBOUNCE_MS
                        )
                    } catch (
                        _: InterruptedException
                    ) {
                        return@execute
                    }

                    val liveBook =
                        ledgerManager
                            .getBook(
                                book.id
                            )
                            ?: book

                    val localDb =
                        AppDatabase(
                            context =
                                appContext,
                            dbFileName =
                                liveBook.databaseName,
                            ledgerId =
                                liveBook.id,
                            ledgerName =
                                liveBook.name,
                            deviceId =
                                ledgerManager.deviceId,
                            deviceName =
                                ledgerManager.deviceName,
                            seedDefaults = false
                        )

                    var syncSucceeded =
                        false

                    try {
                        syncCurrentBook(
                            db = localDb,
                            book = liveBook
                        )
                        syncSucceeded = true
                    } catch (
                        error: Throwable
                    ) {
                        localDb.setLastCloudSync(
                            localDb
                                .getLastCloudSyncAt(),
                            error.message
                                ?: error.javaClass
                                    .simpleName
                        )
                    } finally {
                        localDb.close()
                    }

                    if (syncSucceeded) {
                        runCatching {
                            autoSyncListener
                                ?.invoke()
                        }
                    }
                }
            } finally {
                autoRunning.set(false)

                if (
                    autoPending &&
                    !closed
                ) {
                    scheduleAutoSync(book)
                }
            }
        }
    }

    fun shutdown() {
        closed = true
        autoPending = false
        autoExecutor.shutdownNow()
    }

    fun resolveConflictUseCloud(
        db: AppDatabase,
        book: LedgerBook,
        conflict: SyncConflictRecord
    ): CloudSyncResult {
        db.resolveConflictUseCloud(
            conflict
        )

        return syncCurrentBook(
            db,
            book
        )
    }

    fun resolveConflictUseLocal(
        db: AppDatabase,
        book: LedgerBook,
        conflict: SyncConflictRecord
    ): CloudSyncResult {
        val current =
            requireSession()

        verifyLogin()
        registerDevice(current)

        val cloudBook =
            resolveCloudBook(
                current,
                book
            )

        val conflictChange =
            SyncChangeRecord(
                id = -1L,
                tableName =
                    conflict.tableName,
                recordSyncId =
                    conflict.recordSyncId,
                operation =
                    "UPSERT",
                rowVersion =
                    conflict.localVersion,
                deviceId = "",
                changedAt = 0L
            )

        if (
            !canPushChange(
                cloudBook,
                conflictChange
            )
        ) {
            throw CloudApiException(
                403,
                "当前账号没有修改该业务数据的权限"
            )
        }

        val localPayload =
            db.getSyncPayload(
                conflict.tableName,
                conflict.recordSyncId
            )
                ?: throw IllegalStateException(
                    "本机记录已经不存在"
                )

        val nextVersion =
            conflict.serverVersion + 1

        val operation =
            if (
                localPayload.optInt(
                    "deleted",
                    0
                ) == 1
            ) {
                "DELETE"
            } else {
                "UPSERT"
            }

        val change =
            JSONObject().apply {
                put(
                    "table_name",
                    conflict.tableName
                )
                put(
                    "sync_id",
                    conflict.recordSyncId
                )
                put(
                    "row_version",
                    nextVersion
                )
                put(
                    "operation",
                    operation
                )
                put(
                    "payload",
                    localPayload
                )
            }

        val response =
            requestJson(
                baseUrl =
                    current.baseUrl,
                method = "POST",
                path =
                    "/api/v1/sync/push",
                body =
                    JSONObject().apply {
                        put(
                            "book_id",
                            book.id
                        )
                        put(
                            "device_id",
                            serverDeviceId(
                                current
                            )
                        )
                        put(
                            "changes",
                            JSONArray().apply {
                                put(change)
                            }
                        )
                    },
                token = current.token
            ) as JSONObject

        val conflicts =
            response.getJSONArray(
                "conflicts"
            )

        if (conflicts.length() > 0) {
            val item =
                conflicts.getJSONObject(0)

            db.saveSyncConflict(
                tableName =
                    item.getString(
                        "table_name"
                    ),
                syncId =
                    item.getString(
                        "sync_id"
                    ),
                localVersion =
                    item.getLong(
                        "incoming_version"
                    ),
                serverVersion =
                    item.getLong(
                        "server_version"
                    ),
                serverDeleted =
                    item.optBoolean(
                        "server_deleted",
                        false
                    ),
                serverPayload =
                    item.optJSONObject(
                        "server_payload"
                    )
                        ?: JSONObject()
            )

            throw CloudApiException(
                409,
                "云端在处理冲突期间又发生了变化，请重新选择"
            )
        }

        db.completeLocalConflictResolution(
            conflict =
                conflict,
            acceptedVersion =
                nextVersion
        )

        return syncCurrentBook(
            db,
            book
        )
    }

    fun downloadCloudBook(
        info: CloudBookInfo
    ): CloudSyncResult {
        val current =
            requireSession()

        verifyLogin()
        registerDevice(current)

        val book =
            ledgerManager
                .upsertCloudBook(
                    bookId = info.id,
                    name = info.name,
                    ownerUsername =
                        info.ownerUsername,
                    permission = info.role,
                    permissionTemplate =
                        info.permissionTemplate,
                    permissions =
                        info.permissions
                )

        val db =
            AppDatabase(
                context =
                    appContext,
                dbFileName =
                    book.databaseName,
                ledgerId =
                    book.id,
                ledgerName =
                    book.name,
                deviceId =
                    ledgerManager.deviceId,
                deviceName =
                    ledgerManager.deviceName,
                seedDefaults = false
            )

        return try {
            val pullResult =
                pullAll(
                    session = current,
                    db = db,
                    book = book,
                    startCursor =
                        db.getServerCursor()
                )

            markPurchaseActivityBackfillDone(
                book.id
            )

            if (
                info.role == "SUPERADMIN" ||
                info.role == "OWNER" ||
                info.permissions.any {
                    it in
                        BookPermissions
                            .editPermissions
                }
            ) {
                db.prepareCloudIdRanges()
            }

            val now =
                System.currentTimeMillis()

            db.setLastCloudSync(
                now,
                ""
            )

            ledgerManager
                .updateCloudState(
                    bookId = book.id,
                    cloudBookId =
                        info.id,
                    enabled = true,
                    lastSyncAt = now
                )

            CloudSyncResult(
                uploaded = 0,
                downloaded =
                    pullResult.first,
                conflicts = 0,
                serverCursor =
                    pullResult.second,
                message =
                    "已下载“${info.name}”：${pullResult.first} 条云端变化"
            )
        } finally {
            db.close()
        }
    }

    fun syncCurrentBook(
        db: AppDatabase,
        book: LedgerBook
    ): CloudSyncResult {
        val current =
            requireSession()

        verifyLogin()
        registerDevice(current)

        val cloudBook =
            resolveCloudBook(
                current,
                book
            )

        val permissionChanged =
            book.permission !=
                cloudBook.role ||
            book.permissionTemplate !=
                cloudBook.permissionTemplate ||
            book.permissions !=
                cloudBook.permissions

        ledgerManager
            .updateCloudMetadata(
                bookId =
                    cloudBook.id,
                name =
                    cloudBook.name,
                ownerUsername =
                    cloudBook.ownerUsername,
                permission =
                    cloudBook.role,
                permissionTemplate =
                    cloudBook.permissionTemplate,
                permissions =
                    cloudBook.permissions
            )

        if (permissionChanged) {
            db.setServerCursor(0L)
        }

        val canEdit =
            cloudBook.role ==
                "SUPERADMIN" ||
            cloudBook.role ==
                "OWNER" ||
            cloudBook.permissions.any {
                it in
                    BookPermissions
                        .editPermissions
            }

        if (canEdit) {
            db.prepareCloudIdRanges()
        }

        var uploadedCount = 0
        var conflictCount = 0

        if (canEdit) {
            while (true) {
                val pending =
                    db.getPendingSyncChanges(
                        100
                    )

                if (pending.isEmpty()) {
                    break
                }

                val allowedPending =
                    pending.filter {
                        change ->
                        canPushChange(
                            cloudBook,
                            change
                        )
                    }

                if (
                    allowedPending.isEmpty()
                ) {
                    break
                }

                val pushResult =
                    pushBatch(
                        current,
                        db,
                        book,
                        allowedPending
                    )

                if (
                    pushResult.acceptedLocalIds
                        .isNotEmpty()
                ) {
                    db.markSyncChangesUploaded(
                        pushResult
                            .acceptedLocalIds
                    )
                    uploadedCount +=
                        pushResult
                            .acceptedLocalIds
                            .size
                }

                pushResult.conflicts
                    .forEach {
                        conflict ->
                        db.saveSyncConflict(
                            tableName =
                                conflict.tableName,
                            syncId =
                                conflict.syncId,
                            localVersion =
                                conflict.incomingVersion,
                            serverVersion =
                                conflict.serverVersion,
                            serverDeleted =
                                conflict.serverDeleted,
                            serverPayload =
                                conflict.serverPayload
                        )
                    }

                conflictCount +=
                    pushResult.conflicts
                        .size

                if (
                    pushResult.conflicts
                        .isNotEmpty() ||
                    pushResult
                        .acceptedLocalIds
                        .isEmpty()
                ) {
                    break
                }
            }
        }

        var downloadedCount = 0
        var cursor =
            db.getServerCursor()

        if (conflictCount == 0) {
            val needsActivityBackfill =
                !isPurchaseActivityBackfillDone(
                    book.id
                )

            val pulled =
                pullAll(
                    session = current,
                    db = db,
                    book = book,
                    startCursor =
                        if (
                            needsActivityBackfill
                        ) {
                            0L
                        } else {
                            cursor
                        }
                )

            downloadedCount =
                pulled.first
            cursor =
                pulled.second

            if (
                needsActivityBackfill
            ) {
                markPurchaseActivityBackfillDone(
                    book.id
                )
            }
        }

        val now =
            System.currentTimeMillis()

        if (conflictCount == 0) {
            db.setLastCloudSync(
                now,
                ""
            )

            ledgerManager
                .updateCloudState(
                    bookId = book.id,
                    cloudBookId =
                        cloudBook.id,
                    enabled = true,
                    lastSyncAt = now
                )
        } else {
            db.setLastCloudSync(
                db.getLastCloudSyncAt(),
                "发现 $conflictCount 条版本冲突"
            )
        }

        val pendingReadOnly =
            if (!canEdit) {
                db.getPendingSyncChanges(
                    1
                ).isNotEmpty()
            } else {
                false
            }

        val message =
            when {
                conflictCount > 0 ->
                    "已上传 $uploadedCount 条，但发现 $conflictCount 条冲突，未继续覆盖云端"

                !canEdit &&
                    pendingReadOnly ->
                    "当前权限仅同步允许查看的数据；本机存在无权上传的修改"

                !canEdit ->
                    "权限同步完成：下载 $downloadedCount 条"

                else ->
                    "同步完成：上传 $uploadedCount 条，下载 $downloadedCount 条"
            }

        return CloudSyncResult(
            uploaded =
                uploadedCount,
            downloaded =
                downloadedCount,
            conflicts =
                conflictCount,
            serverCursor =
                cursor,
            message = message
        )
    }

    private fun resolveCloudBook(
        session: CloudSession,
        book: LedgerBook
    ): CloudBookInfo {
        val cloudBooks = listCloudBooks()
        val existing = cloudBooks.firstOrNull { it.id == book.id }
        if (existing != null) return existing

        if (book.cloudEnabled || book.cloudBookId.isNotBlank() || book.permission != "OWNER") {
            ledgerManager.markCloudAccessRevoked(book.id)
            throw CloudApiException(403, "该账本的云端访问权限已被移除；本机副本仍保留为只读查看")
        }

        val ownedCloudBooks = cloudBooks.filter {
            it.ownerUsername.equals(session.username, ignoreCase = true)
        }
        if (book.isDefault && ownedCloudBooks.isNotEmpty()) {
            throw CloudApiException(409, "该账号云端已经有 ${ownedCloudBooks.size} 个自有账本。为防止换手机自动生成重复“我的账本”，本机默认账本不会自动上传；请先下载已有云端账本，或在账本管理中手动创建独立云端账本。")
        }
        return createCloudBook(session, book)
    }

    fun createIndependentCloudBook(
        book: LedgerBook
    ): CloudBookInfo {
        val current =
            requireSession()

        verifyLogin()
        registerDevice(current)

        val existing =
            listCloudBooks()
                .firstOrNull {
                    it.id == book.id
                }

        if (existing != null) {
            return existing
        }

        if (
            book.cloudBookId
                .isNotBlank() &&
            !hasOwnerLevelCloudAccess(
                book.permission
            )
        ) {
            throw CloudApiException(
                403,
                "只有账本所有者或系统管理员可以重新创建该云端账本"
            )
        }

        val created =
            createCloudBook(
                current,
                book
            )

        ledgerManager
            .updateCloudMetadata(
                created.id,
                created.name,
                created.ownerUsername,
                created.role,
                created.permissionTemplate,
                created.permissions
            )

        return created
    }

    private fun createCloudBook(session: CloudSession, book: LedgerBook): CloudBookInfo {
        val item = requestJson(
            baseUrl=session.baseUrl, method="POST", path="/api/v1/books",
            body=JSONObject().apply { put("id",book.id); put("name",book.name) }, token=session.token
        ) as JSONObject
        return parseCloudBook(item)
    }

    private fun pullAll(
        session: CloudSession,
        db: AppDatabase,
        book: LedgerBook,
        startCursor: Long
    ): Pair<Int, Long> {
        var count = 0
        var cursor =
            startCursor

        do {
            val pull =
                pullBatch(
                    session,
                    book,
                    cursor
                )

            pull.events.forEach {
                event ->
                db.applyRemoteSyncEvent(
                    tableName =
                        event.tableName,
                    syncId =
                        event.syncId,
                    rowVersion =
                        event.rowVersion,
                    operation =
                        event.operation,
                    payload =
                        event.payload,
                    modifiedBy =
                        event.modifiedBy
                )
            }

            count +=
                pull.events.size

            cursor =
                pull.nextCursor

            db.setServerCursor(
                cursor
            )
        } while (pull.hasMore)

        return count to cursor
    }

    private fun purchaseActivityBackfillKey(
        bookId: String
    ): String =
        KEY_PURCHASE_ACTIVITY_BACKFILL_PREFIX +
            bookId

    private fun isPurchaseActivityBackfillDone(
        bookId: String
    ): Boolean =
        prefs.getBoolean(
            purchaseActivityBackfillKey(
                bookId
            ),
            false
        )

    private fun markPurchaseActivityBackfillDone(
        bookId: String
    ) {
        prefs.edit()
            .putBoolean(
                purchaseActivityBackfillKey(
                    bookId
                ),
                true
            )
            .apply()
    }

    private fun serverDeviceId(
        session: CloudSession
    ): String =
        serverDeviceId(session.username)

    private fun serverDeviceId(
        username: String
    ): String {
        val source =
            ledgerManager.deviceId +
                "|" +
                username
                    .trim()
                    .lowercase()

        val digest =
            MessageDigest
                .getInstance(
                    "SHA-256"
                )
                .digest(
                    source.toByteArray(
                        Charsets.UTF_8
                    )
                )

        return digest.joinToString(
            separator = ""
        ) {
            byte ->
            "%02x".format(
                byte.toInt() and 0xff
            )
        }
    }

    private fun registerDevice(
        session: CloudSession
    ) {
        requestJson(
            baseUrl =
                session.baseUrl,
            method = "POST",
            path =
                "/api/v1/devices/register",
            body =
                JSONObject().apply {
                    put(
                        "device_id",
                        serverDeviceId(
                            session
                        )
                    )
                    put(
                        "name",
                        ledgerManager.deviceName
                    )
                    put(
                        "platform",
                        "android"
                    )
                    put(
                        "app_version",
                        APP_VERSION
                    )
                },
            token = session.token
        )
    }

    private data class ServerConflictInfo(
        val tableName: String,
        val syncId: String,
        val incomingVersion: Long,
        val serverVersion: Long,
        val serverDeleted: Boolean,
        val serverPayload: JSONObject
    )

    private data class PushBatchResult(
        val acceptedLocalIds:
            List<Long>,
        val conflicts:
            List<ServerConflictInfo>
    )

    private fun pushBatch(
        session: CloudSession,
        db: AppDatabase,
        book: LedgerBook,
        pending:
            List<SyncChangeRecord>
    ): PushBatchResult {
        val changes = JSONArray()

        pending.forEach {
            change ->
            val payload =
                db.getSyncPayload(
                    change.tableName,
                    change.recordSyncId
                )
                    ?: JSONObject()

            changes.put(
                JSONObject().apply {
                    put(
                        "table_name",
                        change.tableName
                    )
                    put(
                        "sync_id",
                        change.recordSyncId
                    )
                    put(
                        "row_version",
                        change.rowVersion
                    )
                    put(
                        "operation",
                        change.operation
                    )
                    put(
                        "payload",
                        payload
                    )
                }
            )
        }

        val response =
            requestJson(
                baseUrl =
                    session.baseUrl,
                method = "POST",
                path =
                    "/api/v1/sync/push",
                body =
                    JSONObject().apply {
                        put(
                            "book_id",
                            book.id
                        )
                        put(
                            "device_id",
                            serverDeviceId(
                                session
                            )
                        )
                        put(
                            "changes",
                            changes
                        )
                    },
                token = session.token
            ) as JSONObject

        val acceptedArray =
            response.getJSONArray(
                "accepted"
            )

        val acceptedKeys =
            mutableSetOf<String>()

        for (
            i in 0 until
                acceptedArray.length()
        ) {
            val item =
                acceptedArray
                    .getJSONObject(i)

            acceptedKeys +=
                changeKey(
                    item.getString(
                        "table_name"
                    ),
                    item.getString(
                        "sync_id"
                    ),
                    item.getLong(
                        "row_version"
                    )
                )
        }

        val acceptedLocalIds =
            pending.filter {
                changeKey(
                    it.tableName,
                    it.recordSyncId,
                    it.rowVersion
                ) in acceptedKeys
            }.map {
                it.id
            }

        val conflictArray =
            response.getJSONArray(
                "conflicts"
            )

        val conflicts =
            buildList {
                for (
                    i in 0 until
                        conflictArray.length()
                ) {
                    val item =
                        conflictArray
                            .getJSONObject(i)

                    add(
                        ServerConflictInfo(
                            tableName =
                                item.getString(
                                    "table_name"
                                ),
                            syncId =
                                item.getString(
                                    "sync_id"
                                ),
                            incomingVersion =
                                item.getLong(
                                    "incoming_version"
                                ),
                            serverVersion =
                                item.getLong(
                                    "server_version"
                                ),
                            serverDeleted =
                                item.optBoolean(
                                    "server_deleted",
                                    false
                                ),
                            serverPayload =
                                item.optJSONObject(
                                    "server_payload"
                                )
                                    ?: JSONObject()
                        )
                    )
                }
            }

        return PushBatchResult(
            acceptedLocalIds =
                acceptedLocalIds,
            conflicts =
                conflicts
        )
    }

    private data class PullEvent(
        val tableName: String,
        val syncId: String,
        val rowVersion: Long,
        val operation: String,
        val payload: JSONObject,
        val modifiedBy: String
    )

    private data class PullBatch(
        val events:
            List<PullEvent>,
        val nextCursor: Long,
        val hasMore: Boolean
    )

    private fun pullBatch(
        session: CloudSession,
        book: LedgerBook,
        since: Long
    ): PullBatch {
        val encodedBook =
            URLEncoder.encode(
                book.id,
                "UTF-8"
            )

        val response =
            requestJson(
                baseUrl =
                    session.baseUrl,
                method = "GET",
                path =
                    "/api/v1/sync/pull" +
                        "?book_id=$encodedBook" +
                        "&since=$since" +
                        "&limit=200",
                body = null,
                token = session.token
            ) as JSONObject

        val array =
            response.getJSONArray(
                "events"
            )

        val events =
            buildList {
                for (
                    i in 0 until
                        array.length()
                ) {
                    val item =
                        array.getJSONObject(i)

                    add(
                        PullEvent(
                            tableName =
                                item.getString(
                                    "table_name"
                                ),
                            syncId =
                                item.getString(
                                    "sync_id"
                                ),
                            rowVersion =
                                item.getLong(
                                    "row_version"
                                ),
                            operation =
                                item.getString(
                                    "operation"
                                ),
                            payload =
                                item.optJSONObject(
                                    "payload"
                                )
                                    ?: JSONObject(),
                            modifiedBy =
                                item.optString(
                                    "modified_by_device_id",
                                    ""
                                )
                        )
                    )
                }
            }

        return PullBatch(
            events = events,
            nextCursor =
                response.getLong(
                    "next_cursor"
                ),
            hasMore =
                response.getBoolean(
                    "has_more"
                )
        )
    }

    private fun requireSession():
        CloudSession =
        session()
            ?: throw CloudApiException(
                401,
                "请先登录云端账号"
            )

    private fun normalizeBaseUrl(
        raw: String
    ): String {
        val value =
            raw.trim()
                .trimEnd('/')

        if (value.isBlank()) {
            throw IllegalArgumentException(
                "服务器地址不能为空"
            )
        }

        if (
            !value.startsWith(
                "https://"
            ) &&
            !value.startsWith(
                "http://"
            )
        ) {
            return "https://$value"
        }

        return value
    }

    private fun changeKey(
        tableName: String,
        syncId: String,
        rowVersion: Long
    ): String =
        "$tableName|$syncId|$rowVersion"

    private fun requestJson(
        baseUrl: String,
        method: String,
        path: String,
        body: JSONObject?,
        token: String?
    ): Any {
        val connection =
            URL(
                baseUrl.trimEnd('/') +
                    path
            ).openConnection()
                as HttpURLConnection

        connection.requestMethod =
            method
        connection.connectTimeout =
            15_000
        connection.readTimeout =
            30_000
        connection.setRequestProperty(
            "Accept",
            "application/json"
        )

        if (
            !token.isNullOrBlank()
        ) {
            connection.setRequestProperty(
                "Authorization",
                "Bearer $token"
            )
        }

        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty(
                "Content-Type",
                "application/json; charset=utf-8"
            )

            connection.outputStream
                .bufferedWriter(
                    Charsets.UTF_8
                )
                .use {
                    it.write(
                        body.toString()
                    )
                }
        }

        val code =
            connection.responseCode

        val input =
            if (
                code in 200..299
            ) {
                connection.inputStream
            } else {
                connection.errorStream
            }

        val text =
            input?.let {
                stream ->
                BufferedReader(
                    InputStreamReader(
                        stream,
                        Charsets.UTF_8
                    )
                ).use {
                    reader ->
                    reader.readText()
                }
            }.orEmpty()

        connection.disconnect()

        if (
            code !in 200..299
        ) {
            val detail =
                runCatching {
                    JSONObject(text)
                        .optString(
                            "detail",
                            text
                        )
                }.getOrDefault(text)

            throw CloudApiException(
                code,
                detail.ifBlank {
                    "服务器返回 $code"
                }
            )
        }

        if (text.isBlank()) {
            return JSONObject()
        }

        return JSONTokener(text)
            .nextValue()
    }

    companion object {
        private const val PREFS_NAME =
            "tianxian_cloud_account"
        private const val KEY_BASE_URL =
            "base_url"
        private const val KEY_TOKEN =
            "jwt_token"
        private const val KEY_USERNAME =
            "username"
        private const val KEY_DISPLAY_NAME =
            "display_name"
        private const val KEY_SYSTEM_ROLE =
            "system_role"

        const val DEFAULT_BASE_URL =
            "https://sync.830888.xyz"

        private const val APP_VERSION =
            "1.4.7.32"

        private const val KEY_PURCHASE_ACTIVITY_BACKFILL_PREFIX =
            "purchase_activity_backfill_v1_4_"

        private const val AUTO_SYNC_DEBOUNCE_MS =
            800L
    }
}
