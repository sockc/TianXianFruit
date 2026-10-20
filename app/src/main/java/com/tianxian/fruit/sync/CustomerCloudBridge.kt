package com.tianxian.fruit.sync

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.tianxian.fruit.data.AppDatabase
import com.tianxian.fruit.ui.OfficialWechatCustomerStatsLocal
import com.tianxian.fruit.ui.OfficialWechatDailyCount
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Syncs analysis data through the separate Server V1.0.19 customer API.
 * Never writes or deletes official sales, purchase, stock or settlement rows.
 *
 * The stable remote keys are (ledger, provider, dedupe_key/file_hash); local
 * SQLite row IDs and store_id are NEVER sent as remote identities.
 */
internal object CustomerCloudBridge {
    private const val MAX_BATCH = 180
    private fun digest(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun JSONObject.str(key: String): String = optString(key, "")
    private fun android.database.Cursor.string(name: String): String =
        getString(getColumnIndexOrThrow(name)) ?: ""
    private fun android.database.Cursor.long(name: String): Long =
        getLong(getColumnIndexOrThrow(name))
    private fun android.database.Cursor.num(name: String): Double =
        getDouble(getColumnIndexOrThrow(name))
    private fun android.database.Cursor.integer(name: String): Int =
        getInt(getColumnIndexOrThrow(name))

    data class SyncItem(
        val kind: String,
        val key: String,
        val payload: JSONObject,
        val hash: String,
        val revision: Long
    ) {
        fun json(): JSONObject = JSONObject()
            .put("kind", kind).put("key", key).put("payload", payload)
            .apply {
                if (kind in setOf("OFFICIAL", "ASSIGNMENT", "COLLECTOR", "ANALYSIS", "SETTINGS", "SOURCE_LINK") && revision > 0)
                    put("expected_revision", revision)
            }
    }

    private data class Ack(val hash: String, val revision: Long)
    private fun ackMap(db: SQLiteDatabase): Map<Pair<String, String>, Ack> =
        db.rawQuery("SELECT kind,entity_key,payload_hash,remote_revision FROM customer_cloud_ack", null).use { c ->
            buildMap {
                while (c.moveToNext()) {
                    put(c.string("kind") to c.string("entity_key"),
                        Ack(c.string("payload_hash"), c.long("remote_revision")))
                }
            }
        }

    private fun jsonItem(kind: String, key: String, payload: JSONObject, ack: Ack?): SyncItem =
        SyncItem(kind, key, payload, digest(payload.toString()), ack?.revision ?: 0L)

    private fun officialPayload(date: String, stats: OfficialWechatDailyCount?): JSONObject =
        JSONObject().put("date", date).put("new_customers", stats?.newCustomers ?: 0)
            .put("returning_customers", stats?.returningCustomers ?: 0)
            .put("deleted", stats == null)

    private fun assignmentPayload(date: String, storeSyncId: String): JSONObject =
        JSONObject().put("date", date).put("store_sync_id", storeSyncId)
            .put("deleted", storeSyncId.isBlank())

    private fun collectorPayload(platform: String, hash: String, partner: String): JSONObject =
        JSONObject().put("platform", platform).put("file_hash", hash)
            .put("partner_sync_id", partner).put("deleted", partner.isBlank())

    private fun analysisPayload(platform: String, receiptKey: String, state: String): JSONObject =
        JSONObject().put("platform", platform).put("dedupe_key", receiptKey)
            .put("state", state)

    private fun settingsPayload(excludeLarge: Boolean, threshold: Double): JSONObject =
        JSONObject().put("exclude_large_payments", excludeLarge)
            .put("large_payment_threshold", threshold)

    private fun sourceLinkPayload(platform: String, receiptKey: String, fileHash: String): JSONObject =
        JSONObject().put("platform", platform).put("dedupe_key", receiptKey)
            .put("file_hash", fileHash)

    private fun analysisState(excluded: Boolean, included: Boolean): String = when {
        included -> "INCLUDED"
        excluded -> "EXCLUDED"
        else -> "DEFAULT"
    }

    private fun pending(
        context: Context, db: AppDatabase, bookId: String, limit: Int,
        includeCollector: Boolean = true,
        includeAnalysis: Boolean = false,
        includeSettings: Boolean = false,
        includeSourceLinks: Boolean = false
    ): List<SyncItem> {
        val ack = ackMap(db.readableDatabase)
        val results = mutableListOf<SyncItem>()
        fun include(kind: String, key: String, payload: JSONObject): Boolean {
            val prev = ack[kind to key]
            val item = jsonItem(kind, key, payload, prev)
            // A server-origin row is immutable. Never echo it back or overwrite
            // a verified cloud row with platform/source metadata reconstructed locally.
            if (prev?.hash != item.hash && prev?.hash != "REMOTE") results.add(item)
            return results.size >= limit
        }

        // Keep source batches ahead of their receipts for lineage reconstruction.
        val batches = db.getPaymentImportBatches(1_000_000)
        for (b in batches) {
            if (b.platform !in listOf("WECHAT", "ALIPAY") || b.fileHash.length != 64) continue
            val key = "${b.platform}|${b.fileHash}"
            val body = JSONObject()
                .put("platform", b.platform).put("file_hash", b.fileHash)
                .put("file_name", b.fileName)
                .put("imported_at", b.importedAt)
                .put("total_rows", b.totalRows).put("accepted_rows", b.acceptedRows)
                .put("duplicate_rows", b.duplicateRows).put("ignored_rows", b.ignoredRows)
                .put("refund_rows", b.refundRows).put("error_rows", b.errorRows)
                .put("first_receipt_date", b.firstReceiptDate)
                .put("last_receipt_date", b.lastReceiptDate)
                .put("receipt_days", b.receiptDays).put("receipt_count", b.receiptCount)
                .put("coverage_source", b.coverageSource)
            if (include("BATCH", key, body)) return results
        }

        db.readableDatabase.rawQuery(
            """
            SELECT p.*,COALESCE(s.sync_id,'') AS store_sync_id,
                   COALESCE(b.file_hash,'') AS source_file_hash
            FROM customer_payment_transaction p
            LEFT JOIN store s ON s.id=p.store_id
            LEFT JOIN payment_import_batch b ON b.id=p.source_batch_id
            ORDER BY p.id
            """.trimIndent(), null
        ).use { c ->
            while (c.moveToNext() && results.size < limit) {
                val platform = c.string("platform")
                val key = "$platform|${c.string("dedupe_key")}"
                if (platform !in listOf("WECHAT", "ALIPAY")) continue
                val body = JSONObject()
                    .put("platform", platform).put("dedupe_key", c.string("dedupe_key"))
                    .put("trade_time", c.string("trade_time"))
                    .put("business_date", c.string("business_date"))
                    .put("amount", c.num("amount"))
                    .put("refund_amount", c.num("refund_amount"))
                    .put("net_amount", c.num("net_amount"))
                    .put("transaction_ref", c.string("transaction_ref"))
                    .put("customer_key", c.string("customer_key"))
                    .put("customer_name", c.string("customer_name"))
                    .put("identity_confidence", c.string("identity_confidence"))
                    .put("transaction_type", c.string("transaction_type"))
                    .put("trade_status", c.string("trade_status"))
                    .put("store_sync_id", c.string("store_sync_id"))
                    .put("store_name", c.string("store_name"))
                    .put("source_file_hash", c.string("source_file_hash"))
                    .put("source_file", c.string("source_file"))
                include("RECEIPT", key, body)
            }
        }

        if (results.size < limit) {
            for ((date, stats) in OfficialWechatCustomerStatsLocal.allForBook(context, bookId)) {
                if (include("OFFICIAL", date, officialPayload(date, stats))) break
            }
        }
        if (results.size < limit) {
            for ((date, stableStoreId) in db.getCustomerStoreDayAssignments().toSortedMap()) {
                if (include("ASSIGNMENT", date, assignmentPayload(date, stableStoreId))) break
            }
        }
        if (results.size < limit && includeCollector) {
            for ((key, partner) in db.getCustomerBillCollectorAssignments().toSortedMap()) {
                val divider = key.indexOf('|')
                if (divider <= 0) continue
                val platform = key.substring(0, divider)
                val hash = key.substring(divider + 1)
                if (include("COLLECTOR", key, collectorPayload(platform, hash, partner))) break
            }
        }
        // These kinds are opt-in server protocol extensions: never send an
        // unrecognized kind to an older server that would silently drop it.
        if (results.size < limit && includeAnalysis) {
            db.readableDatabase.rawQuery("""
                SELECT p.platform,p.dedupe_key,p.analysis_excluded,p.analysis_included_override
                FROM customer_payment_transaction p
                WHERE p.analysis_excluded=1 OR p.analysis_included_override=1 OR EXISTS(
                    SELECT 1 FROM customer_cloud_ack a
                    WHERE a.kind='ANALYSIS'
                      AND a.entity_key=p.platform||'|'||p.dedupe_key
                )
                ORDER BY p.id
            """.trimIndent(), null).use { c ->
                while (c.moveToNext() && results.size < limit) {
                    val platform = c.string("platform")
                    val receiptKey = c.string("dedupe_key")
                    val state = analysisState(
                        c.integer("analysis_excluded") == 1,
                        c.integer("analysis_included_override") == 1
                    )
                    include("ANALYSIS", "$platform|$receiptKey",
                        analysisPayload(platform, receiptKey, state))
                }
            }
        }
        if (results.size < limit && includeSettings) {
            val setting = db.getCustomerAnalysisSettings()
            val customized = !setting.excludeLargePayments ||
                kotlin.math.abs(setting.largePaymentThreshold - 2000.0) > 0.001
            if (customized || ack.containsKey("SETTINGS" to "global")) {
                include("SETTINGS", "global",
                    settingsPayload(setting.excludeLargePayments, setting.largePaymentThreshold))
            }
        }
        if (results.size < limit && includeSourceLinks) {
            db.readableDatabase.rawQuery("""
                SELECT platform,dedupe_key,file_hash
                FROM customer_payment_source_link
                ORDER BY platform,dedupe_key,file_hash
            """.trimIndent(), null).use { c ->
                while (c.moveToNext() && results.size < limit) {
                    val platform = c.string("platform")
                    val receiptKey = c.string("dedupe_key")
                    val fileHash = c.string("file_hash")
                    include("SOURCE_LINK", "$platform|$receiptKey|$fileHash",
                        sourceLinkPayload(platform, receiptKey, fileHash))
                }
            }
        }
        return results
    }

    private fun ack(db: SQLiteDatabase, kind: String, key: String,
                    payloadHash: String, revision: Long) {
        db.insertWithOnConflict("customer_cloud_ack", null, ContentValues().apply {
            put("kind", kind); put("entity_key", key)
            put("payload_hash", payloadHash); put("remote_revision", revision)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun serverCursor(db: SQLiteDatabase): Long =
        db.rawQuery("SELECT next_cursor FROM customer_cloud_cursor WHERE id=1", null).use {
            if (it.moveToFirst()) it.getLong(0) else 0L
        }

    private fun applyRemote(context: Context, db: AppDatabase, bookId: String, changes: JSONArray, next: Long) {
        val database = db.writableDatabase
        // Mutable within one downloaded event page: a single key may have
        // several sequential revisions on the server. Each later event must
        // see the ACK from the immediately preceding event.
        val knownAck = ackMap(database).toMutableMap()
        val currentIdentity = database.rawQuery(
            "SELECT server_identity FROM customer_cloud_cursor WHERE id=1", null
        ).use { if (it.moveToFirst()) it.getString(0) ?: "" else "" }
        database.beginTransaction()
        try {
            for (i in 0 until changes.length()) {
                val event = changes.getJSONObject(i)
                val kind = event.getString("kind")
                val key = event.getString("key")
                val body = event.getJSONObject("payload")
                val version = event.optLong("revision", 1L)
                when (kind) {
                    "BATCH" -> {
                        val platform = body.str("platform")
                        val hash = body.str("file_hash")
                        if ("$platform|$hash" != key) continue
                        val inserted = database.insertWithOnConflict("payment_import_batch", null, ContentValues().apply {
                            put("platform", platform); put("file_hash", hash)
                            put("file_name", body.str("file_name"))
                            put("total_rows", body.optInt("total_rows"))
                            put("accepted_rows", body.optInt("accepted_rows"))
                            put("duplicate_rows", body.optInt("duplicate_rows"))
                            put("ignored_rows", body.optInt("ignored_rows"))
                            put("refund_rows", body.optInt("refund_rows"))
                            put("error_rows", body.optInt("error_rows"))
                            put("imported_at", body.optLong("imported_at"))
                            put("observed_first_date", body.str("first_receipt_date"))
                            put("observed_last_date", body.str("last_receipt_date"))
                            put("observed_receipt_days", body.optInt("receipt_days"))
                            put("observed_receipt_rows", body.optInt("receipt_count"))
                        }, SQLiteDatabase.CONFLICT_IGNORE)
                        if (inserted > 0L) ack(database, kind, key, "REMOTE", version)
                    }
                    "RECEIPT" -> {
                        val platform = body.str("platform")
                        val receiptKey = body.str("dedupe_key")
                        if ("$platform|$receiptKey" != key || receiptKey.isBlank()) continue
                        val stableStore = body.str("store_sync_id")
                        val storeId = if (stableStore.isNotBlank()) database.rawQuery(
                            "SELECT id FROM store WHERE sync_id=? LIMIT 1", arrayOf(stableStore)
                        ).use { if (it.moveToFirst()) it.getLong(0) else 0L } else 0L
                        val fileHash = body.str("source_file_hash")
                        val batchId = if (fileHash.isNotBlank()) database.rawQuery(
                            "SELECT id FROM payment_import_batch WHERE file_hash=? LIMIT 1", arrayOf(fileHash)
                        ).use { if (it.moveToFirst()) it.getLong(0) else 0L } else 0L
                        val inserted = database.insertWithOnConflict(
                            "customer_payment_transaction", null, ContentValues().apply {
                                put("platform", platform); put("dedupe_key", receiptKey)
                                put("transaction_ref", body.str("transaction_ref"))
                                put("trade_time", body.str("trade_time"))
                                put("business_date", body.str("business_date"))
                                put("amount", body.optDouble("amount"))
                                put("refund_amount", body.optDouble("refund_amount"))
                                put("net_amount", body.optDouble("net_amount"))
                                put("customer_key", body.str("customer_key"))
                                put("customer_name", body.str("customer_name"))
                                put("identity_confidence", body.str("identity_confidence").ifBlank { "LOW" })
                                put("transaction_type", body.str("transaction_type"))
                                put("trade_status", body.str("trade_status"))
                                put("store_id", storeId)
                                put("store_name", body.str("store_name"))
                                put("source_batch_id", batchId)
                                put("source_file", body.str("source_file"))
                                put("created_at", System.currentTimeMillis())
                            }, SQLiteDatabase.CONFLICT_IGNORE
                        )
                        if (inserted > 0L) ack(database, kind, key, "REMOTE", version)
                    }
                    "SOURCE_LINK" -> {
                        val platform = body.str("platform")
                        val receiptKey = body.str("dedupe_key")
                        val fileHash = body.str("file_hash")
                        if ("$platform|$receiptKey|$fileHash" != key ||
                            platform !in setOf("WECHAT", "ALIPAY") ||
                            fileHash.length != 64) continue
                        database.insertWithOnConflict(
                            "customer_payment_source_link", null, ContentValues().apply {
                                put("platform", platform)
                                put("dedupe_key", receiptKey)
                                put("file_hash", fileHash)
                            }, SQLiteDatabase.CONFLICT_IGNORE
                        )
                        ack(database, kind, key, digest(body.toString()), version)
                        knownAck[kind to key] = Ack(digest(body.toString()), version)
                    }
                    "ANALYSIS" -> {
                        val platform = body.str("platform")
                        val receiptKey = body.str("dedupe_key")
                        val state = body.str("state")
                        if ("$platform|$receiptKey" != key ||
                            platform !in setOf("WECHAT", "ALIPAY") ||
                            state !in setOf("DEFAULT", "EXCLUDED", "INCLUDED")) continue
                        val local = database.rawQuery("""
                            SELECT analysis_excluded,analysis_included_override
                            FROM customer_payment_transaction
                            WHERE platform=? AND dedupe_key=? LIMIT 1
                        """.trimIndent(), arrayOf(platform, receiptKey)).use { c ->
                            if (!c.moveToFirst()) null else analysisState(
                                c.integer("analysis_excluded") == 1,
                                c.integer("analysis_included_override") == 1
                            )
                        } ?: throw IllegalStateException("云端账单处理状态的原交易尚未恢复：$key")
                        val previous = knownAck[kind to key]
                        val currentHash = digest(analysisPayload(platform, receiptKey, local).toString())
                        if ((previous != null && previous.hash != currentHash) ||
                            (previous == null && local != "DEFAULT")) continue
                        database.update(
                            "customer_payment_transaction", ContentValues().apply {
                                put("analysis_excluded", if (state == "EXCLUDED") 1 else 0)
                                put("analysis_included_override", if (state == "INCLUDED") 1 else 0)
                                put("exclusion_reason", when (state) {
                                    "EXCLUDED" -> "手动排除"
                                    "INCLUDED" -> "手动计入"
                                    else -> ""
                                })
                            }, "platform=? AND dedupe_key=?", arrayOf(platform, receiptKey)
                        )
                        val hash = digest(analysisPayload(platform, receiptKey, state).toString())
                        ack(database, kind, key, hash, version)
                        knownAck[kind to key] = Ack(hash, version)
                    }
                    "SETTINGS" -> {
                        if (key != "global") continue
                        val exclude = body.optBoolean("exclude_large_payments", true)
                        val threshold = body.optDouble("large_payment_threshold", 2000.0)
                        if (!threshold.isFinite() || threshold < 1.0) continue
                        val current = db.getCustomerAnalysisSettings()
                        val previous = knownAck[kind to key]
                        val localHash = digest(settingsPayload(
                            current.excludeLargePayments, current.largePaymentThreshold).toString())
                        val customized = !current.excludeLargePayments ||
                            kotlin.math.abs(current.largePaymentThreshold - 2000.0) > 0.001
                        if ((previous != null && previous.hash != localHash) ||
                            (previous == null && customized)) continue
                        if (!db.saveCustomerAnalysisSettings(
                                com.tianxian.fruit.data.CustomerAnalysisSettings(exclude, threshold))) {
                            throw IllegalStateException("云端大额过滤设置恢复失败")
                        }
                        val hash = digest(settingsPayload(exclude, threshold).toString())
                        ack(database, kind, key, hash, version)
                        knownAck[kind to key] = Ack(hash, version)
                    }
                    "OFFICIAL" -> {
                        val local = OfficialWechatCustomerStatsLocal.get(context, bookId, key)
                        val current = officialPayload(key, local)
                        val previous = knownAck[kind to key]
                        val locallyDirty =
                            OfficialWechatCustomerStatsLocal.hasLocalEntry(context, bookId, key) &&
                                previous?.hash != digest(current.toString())
                        // An unsynced manually entered value is NOT overwritten.
                        if (locallyDirty) continue
                        val deleted = body.optBoolean("deleted", false)
                        val remote = if (deleted) null else OfficialWechatDailyCount(
                            body.optInt("new_customers"), body.optInt("returning_customers")
                        )
                        if (!OfficialWechatCustomerStatsLocal.applyFromCloud(context, bookId, key, remote)) {
                            throw IllegalStateException("写入云端官方顾客记录失败")
                        }
                        val digestValue = digest(officialPayload(key, remote).toString())
                        ack(database, kind, key, digestValue, version)
                        knownAck[kind to key] = Ack(digestValue, version)
                    }
                    "ASSIGNMENT" -> {
                        if (key != body.str("date")) continue
                        val chosen = if (body.optBoolean("deleted", false)) ""
                            else body.str("store_sync_id")
                        val local = db.getCustomerStoreDayAssignments()[key]
                        val lastAck = knownAck[kind to key]
                        // Never overwrite an unsynced local choice or undo.
                        val localDirty = local != null &&
                            lastAck?.hash != digest(assignmentPayload(key, local).toString())
                        if (localDirty) continue
                        if (!db.applyCustomerStoreDayAssignmentFromCloud(key, chosen)) {
                            throw IllegalStateException("恢复云端统一归属失败：$key")
                        }
                        val digestValue = digest(assignmentPayload(key, chosen).toString())
                        ack(database, kind, key, digestValue, version)
                        knownAck[kind to key] = Ack(digestValue, version)
                    }
                    "COLLECTOR" -> {
                        val platform = body.str("platform")
                        val hash = body.str("file_hash")
                        if ("$platform|$hash" != key) continue
                        val partner = if (body.optBoolean("deleted", false)) ""
                            else body.str("partner_sync_id")
                        val current = db.getCustomerBillCollectorAssignments()[key]
                        val previous = knownAck[kind to key]
                        val locallyDirty = current != null &&
                            previous?.hash != digest(collectorPayload(platform, hash, current).toString())
                        if (locallyDirty) continue
                        if (!db.applyCustomerBillCollectorFromCloud(platform, hash, partner)) {
                            throw IllegalStateException("恢复云端账单收款人关联失败")
                        }
                        val currentHash = digest(collectorPayload(platform, hash, partner).toString())
                        ack(database, kind, key, currentHash, version)
                        knownAck[kind to key] = Ack(currentHash, version)
                    }
                    else -> throw IllegalStateException("未知客流同步数据类型：$kind")
                }
            }
            database.insertWithOnConflict("customer_cloud_cursor", null, ContentValues().apply {
                put("id", 1); put("server_identity", currentIdentity); put("next_cursor", next)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
    }

    data class Result(val uploaded: Int, val downloaded: Int)
    fun sync(
        context: Context, db: AppDatabase, bookId: String, identity: String,
        canWrite: Boolean,
        request: (String, String, JSONObject?) -> JSONObject
    ): Result {
        val endpoint = "/api/v1/books/$bookId/customer-analytics"
        // Return no false success on an older server: capabilities is mandatory.
        val caps = request("GET", "$endpoint/capabilities", null)
        if (!caps.optBoolean("supported") || caps.optInt("version") != 1) {
            throw IllegalStateException("服务器尚未支持客流同步 V1")
        }
        val features = caps.optJSONArray("features")
        val supported = (0 until (features?.length() ?: 0))
            .map { features?.optString(it).orEmpty() }.toSet()
        val supportsCollector = "bill_collector" in supported
        val supportsAnalysis = "receipt_analysis_state" in supported
        val supportsSettings = "analysis_settings" in supported
        val supportsSourceLinks = "bill_source_links" in supported
        val database = db.writableDatabase
        database.beginTransaction()
        try {
            val previous = database.rawQuery(
                "SELECT server_identity FROM customer_cloud_cursor WHERE id=1", null
            ).use { if (it.moveToFirst()) it.getString(0) ?: "" else "" }
            if (previous != identity) {
                database.delete("customer_cloud_ack", null, null)
                database.insertWithOnConflict("customer_cloud_cursor", null, ContentValues().apply {
                    put("id", 1); put("server_identity", identity); put("next_cursor", 0)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }

        var totalDownload = 0
        fun pull() {
            for (page in 0 until 400) {
                val before = serverCursor(database)
                val response = request("GET", "$endpoint/changes?cursor=$before&limit=200&include_assignments=true" +
                    (if (supportsCollector) "&include_collectors=true" else "") +
                    (if (supportsAnalysis) "&include_analysis=true" else "") +
                    (if (supportsSettings) "&include_settings=true" else "") +
                    (if (supportsSourceLinks) "&include_source_links=true" else ""), null)
                val changes = response.getJSONArray("changes")
                val next = response.getLong("next_cursor")
                if (next < before || (changes.length() > 0 && next == before)) {
                    throw IllegalStateException("客流同步游标异常")
                }
                applyRemote(context, db, bookId, changes, next)
                totalDownload += changes.length()
                if (!response.optBoolean("has_more")) return
            }
            throw IllegalStateException("客流云端变化超过单次安全页数，保留游标稍后重试")
        }

        // Download before upload so an offline device sees server additions.
        pull()
        var uploaded = 0
        if (canWrite) {
            for (page in 0 until 400) {
                val batch = pending(context, db, bookId, MAX_BATCH, supportsCollector,
                    supportsAnalysis, supportsSettings, supportsSourceLinks)
                if (batch.isEmpty()) break
                val body = JSONObject().put("items", JSONArray().apply {
                    batch.forEach { put(it.json()) }
                })
                val response = request("POST", "$endpoint/push", body)
                val confirmed = response.getJSONArray("items")
                if (confirmed.length() != batch.size) throw IllegalStateException("服务器账单确认笔数异常")
                database.beginTransaction()
                try {
                    for (index in batch.indices) {
                        val res = confirmed.getJSONObject(index)
                        val item = batch[index]
                        if (res.getString("kind") != item.kind || res.getString("key") != item.key) {
                            throw IllegalStateException("账单云端响应交易键不一致")
                        }
                        val state = res.optString("status")
                        if (state !in listOf("accepted", "unchanged", "updated")) {
                            throw IllegalStateException("账单同步存在未经确认的记录：${item.key}")
                        }
                        ack(database, item.kind, item.key, item.hash, res.optLong("revision", 1L))
                        uploaded++
                    }
                    database.setTransactionSuccessful()
                } finally { database.endTransaction() }
                if (batch.size < MAX_BATCH) break
            }
            if (pending(context, db, bookId, 1, supportsCollector,
                supportsAnalysis, supportsSettings, supportsSourceLinks).isNotEmpty()) {
                throw IllegalStateException("待同步客流记录较多，已分批上传，仍有未确认记录")
            }
        }
        pull()
        val status = context.getSharedPreferences("customer_cloud_sync_status_v1", Context.MODE_PRIVATE)
        val unsyncedCollector = !supportsCollector &&
            db.getCustomerBillCollectorAssignments().isNotEmpty()
        val issues = mutableListOf<String>()
        if (unsyncedCollector) issues += "服务器不支持账单收款人关联同步"
        val unsyncedAnalysis = !supportsAnalysis && database.rawQuery(
            "SELECT 1 FROM customer_payment_transaction " +
                "WHERE analysis_excluded=1 OR analysis_included_override=1 LIMIT 1", null
        ).use { it.moveToFirst() }
        if (unsyncedAnalysis) issues += "人工排除/计入未同步"
        val currentSettings = db.getCustomerAnalysisSettings()
        if (!supportsSettings && (!currentSettings.excludeLargePayments ||
                kotlin.math.abs(currentSettings.largePaymentThreshold - 2000.0) > 0.001)) {
            issues += "大额收款过滤设置未同步"
        }
        val overlap = !supportsSourceLinks && database.rawQuery("""
            SELECT 1 FROM customer_payment_source_link
            GROUP BY platform,dedupe_key HAVING COUNT(*)>1 LIMIT 1
        """.trimIndent(), null).use { it.moveToFirst() }
        if (overlap) issues += "重叠账单来源关联尚未同步"
        status.edit().putLong("last_$bookId", System.currentTimeMillis())
            .putInt("uploaded_$bookId", uploaded)
            .putString("error_$bookId", issues.joinToString("；"))
            .apply()
        return Result(uploaded, totalDownload)
    }

    fun syncStatus(context: Context, bookId: String): String {
        val ts = context.getSharedPreferences("customer_cloud_sync_status_v1", Context.MODE_PRIVATE)
            .getLong("last_$bookId", 0L)
        val error = context.getSharedPreferences("customer_cloud_sync_status_v1", Context.MODE_PRIVATE)
            .getString("error_$bookId", "").orEmpty()
        return when {
            error.isNotBlank() -> "客流同步待处理：$error"
            ts > 0L -> "已连接客流云同步"
            else -> "本机未同步"
        }
    }
}
