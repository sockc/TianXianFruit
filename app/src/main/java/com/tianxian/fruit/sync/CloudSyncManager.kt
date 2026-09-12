package com.tianxian.fruit.sync

import android.content.Context
import com.tianxian.fruit.data.AppDatabase
import com.tianxian.fruit.data.SyncChangeRecord
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class CloudSession(
    val baseUrl: String,
    val username: String,
    val displayName: String,
    val token: String
)

data class CloudBookInfo(
    val id: String,
    val name: String,
    val role: String,
    val ownerUserId: String,
    val deleted: Boolean
)

data class CloudMemberInfo(
    val userId: String,
    val username: String,
    val displayName: String,
    val role: String
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
                token = token
            )

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
            .apply()

        registerDevice(result)
        return result
    }

    fun registerAccount(
        baseUrl: String,
        username: String,
        displayName: String,
        password: String,
        registrationCode: String
    ): String {
        val cleanBase =
            normalizeBaseUrl(baseUrl)

        val response =
            requestJson(
                baseUrl = cleanBase,
                method = "POST",
                path =
                    "/api/v1/auth/register",
                body =
                    JSONObject().apply {
                        put(
                            "username",
                            username.trim()
                        )
                        put(
                            "display_name",
                            displayName.trim()
                        )
                        put(
                            "password",
                            password
                        )
                        put(
                            "registration_code",
                            registrationCode.trim()
                        )
                    },
                token = null
            ) as JSONObject

        return response.getString(
            "username"
        )
    }

    fun logout() {
        prefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_USERNAME)
            .remove(KEY_DISPLAY_NAME)
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
            .apply()

        return refreshed
    }

    fun listCloudBooks():
        List<CloudBookInfo> {
        val current =
            requireSession()

        val array =
            requestJson(
                baseUrl =
                    current.baseUrl,
                method = "GET",
                path = "/api/v1/books",
                body = null,
                token = current.token
            ) as JSONArray

        val result =
            buildList {
                for (
                    i in 0 until
                        array.length()
                ) {
                    val item =
                        array.getJSONObject(i)

                    add(
                        CloudBookInfo(
                            id =
                                item.getString(
                                    "id"
                                ),
                            name =
                                item.getString(
                                    "name"
                                ),
                            role =
                                item.getString(
                                    "role"
                                ),
                            ownerUserId =
                                item.getString(
                                    "owner_user_id"
                                ),
                            deleted =
                                item.optBoolean(
                                    "deleted",
                                    false
                                )
                        )
                    )
                }
            }.filterNot {
                it.deleted
            }

        result.forEach {
            info ->
            if (
                ledgerManager
                    .getBook(
                        info.id
                    ) != null
            ) {
                ledgerManager
                    .updateCloudMetadata(
                        bookId = info.id,
                        name = info.name,
                        permission =
                            info.role
                    )
            }
        }

        return result
    }

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
                i in 0 until
                    array.length()
            ) {
                val item =
                    array.getJSONObject(i)

                add(
                    CloudMemberInfo(
                        userId =
                            item.getString(
                                "user_id"
                            ),
                        username =
                            item.getString(
                                "username"
                            ),
                        displayName =
                            item.getString(
                                "display_name"
                            ),
                        role =
                            item.getString(
                                "role"
                            )
                    )
                )
            }
        }
    }

    fun addOrUpdateMember(
        bookId: String,
        username: String,
        role: String
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
                            "role",
                            role
                        )
                    },
                token = current.token
            ) as JSONObject

        return CloudMemberInfo(
            userId =
                item.getString(
                    "user_id"
                ),
            username =
                item.getString(
                    "username"
                ),
            displayName =
                item.getString(
                    "display_name"
                ),
            role =
                item.getString(
                    "role"
                )
        )
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

    fun renameCloudBook(
        bookId: String,
        name: String
    ): CloudBookInfo {
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
                method = "PATCH",
                path =
                    "/api/v1/books/$encoded",
                body =
                    JSONObject().apply {
                        put(
                            "name",
                            name.trim()
                        )
                    },
                token = current.token
            ) as JSONObject

        val result =
            CloudBookInfo(
                id =
                    item.getString(
                        "id"
                    ),
                name =
                    item.getString(
                        "name"
                    ),
                role =
                    item.getString(
                        "role"
                    ),
                ownerUserId =
                    item.getString(
                        "owner_user_id"
                    ),
                deleted =
                    item.optBoolean(
                        "deleted",
                        false
                    )
            )

        ledgerManager
            .updateCloudMetadata(
                result.id,
                result.name,
                result.role
            )

        return result
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
                    permission = info.role
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

            if (
                info.role == "OWNER" ||
                info.role == "EDITOR"
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

        ledgerManager
            .updateCloudMetadata(
                bookId =
                    cloudBook.id,
                name =
                    cloudBook.name,
                permission =
                    cloudBook.role
            )

        val canEdit =
            cloudBook.role ==
                "OWNER" ||
                cloudBook.role ==
                "EDITOR"

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

                val pushResult =
                    pushBatch(
                        current,
                        db,
                        book,
                        pending
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

                conflictCount +=
                    pushResult.conflicts

                if (
                    pushResult.conflicts > 0 ||
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
            val pulled =
                pullAll(
                    session = current,
                    db = db,
                    book = book,
                    startCursor =
                        cursor
                )

            downloadedCount =
                pulled.first
            cursor =
                pulled.second
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
                    "只读账本已下载 $downloadedCount 条；本机存在不可上传的修改，请不要在只读账本编辑"

                !canEdit ->
                    "只读同步完成：下载 $downloadedCount 条"

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
        val cloudBooks =
            listCloudBooks()

        val existing =
            cloudBooks.firstOrNull {
                it.id == book.id
            }

        if (existing != null) {
            return existing
        }

        if (
            book.permission != "OWNER"
        ) {
            throw CloudApiException(
                403,
                "该共享账本已不在你的云端权限列表中"
            )
        }

        val item =
            requestJson(
                baseUrl =
                    session.baseUrl,
                method = "POST",
                path = "/api/v1/books",
                body =
                    JSONObject().apply {
                        put(
                            "id",
                            book.id
                        )
                        put(
                            "name",
                            book.name
                        )
                    },
                token =
                    session.token
            ) as JSONObject

        return CloudBookInfo(
            id =
                item.getString("id"),
            name =
                item.getString("name"),
            role =
                item.getString("role"),
            ownerUserId =
                item.getString(
                    "owner_user_id"
                ),
            deleted =
                item.optBoolean(
                    "deleted",
                    false
                )
        )
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
                        ledgerManager.deviceId
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

    private data class PushBatchResult(
        val acceptedLocalIds:
            List<Long>,
        val conflicts: Int
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
                            ledgerManager.deviceId
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

        return PushBatchResult(
            acceptedLocalIds =
                acceptedLocalIds,
            conflicts =
                response
                    .getJSONArray(
                        "conflicts"
                    )
                    .length()
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

        const val DEFAULT_BASE_URL =
            "https://sync.830888.xyz"

        private const val APP_VERSION =
            "1.3.2"
    }
}
