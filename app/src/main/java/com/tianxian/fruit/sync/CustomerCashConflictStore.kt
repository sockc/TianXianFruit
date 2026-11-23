package com.tianxian.fruit.sync

import android.content.Context
import org.json.JSONObject

/**
 * Book-scoped, auxiliary UI evidence of a cash-switch-only mismatch.
 * This is not a financial record, revision override, or sync acknowledgment.
 */
internal data class CustomerCashConflict(
    val date: String,
    val partnerSyncId: String,
    val storeSyncId: String,
    val localHash: String,
    val cloudRevision: Long,
    val localCash: Boolean,
    val cloudCash: Boolean,
    val localOrigin: NaturalCashFlagPresence
)

internal object CustomerCashConflictStore {
    private fun prefs(context: Context, bookId: String) =
        context.getSharedPreferences("customer_cash_conflict_${bookId}", Context.MODE_PRIVATE)

    fun read(context: Context, bookId: String): CustomerCashConflict? = runCatching {
        val raw = prefs(context, bookId).getString("pending", null) ?: return@runCatching null
        val json = JSONObject(raw)
        val origin = NaturalCashFlagPresence.valueOf(json.getString("local_origin"))
        CustomerCashConflict(
            date = json.getString("date"),
            partnerSyncId = json.getString("partner_sync_id"),
            storeSyncId = json.getString("store_sync_id"),
            localHash = json.getString("local_hash"),
            cloudRevision = json.getLong("cloud_revision"),
            localCash = json.getBoolean("local_cash"),
            cloudCash = json.getBoolean("cloud_cash"),
            localOrigin = origin
        ).takeIf {
            it.date.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) &&
                it.partnerSyncId.isNotBlank() && it.storeSyncId.isNotBlank() &&
                it.localHash.length == 64 && it.cloudRevision > 0L &&
                it.localCash != it.cloudCash
        }
    }.getOrNull()

    fun save(context: Context, bookId: String, item: CustomerCashConflict): Boolean =
        prefs(context, bookId).edit().putString("pending", JSONObject()
            .put("date", item.date)
            .put("partner_sync_id", item.partnerSyncId)
            .put("store_sync_id", item.storeSyncId)
            .put("local_hash", item.localHash)
            .put("cloud_revision", item.cloudRevision)
            .put("local_cash", item.localCash)
            .put("cloud_cash", item.cloudCash)
            .put("local_origin", item.localOrigin.name)
            .toString()
        ).commit()

    fun clear(context: Context, bookId: String) {
        prefs(context, bookId).edit().remove("pending").apply()
    }
}
