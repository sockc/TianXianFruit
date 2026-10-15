package com.tianxian.fruit.ui

import android.content.Context
import java.security.MessageDigest
import java.time.LocalDate

/**
 * Manual daily totals copied from WeChat 经营码 → 顾客运营.
 *
 * No payer identifiers or individual customer profiles are stored.
 * This local-only cache is deliberately NOT represented as synced ledger data:
 * it cannot be merged with the provider's bill identity data, nor is it part
 * of the TianXian cloud protocol. The UI must label it 本机记录/未同步.
 */
internal data class OfficialWechatDailyCount(
    val newCustomers: Int,
    val returningCustomers: Int
) {
    val total: Int get() = newCustomers + returningCustomers
}

internal object OfficialWechatCustomerStatsLocal {
    private const val PREF_FILE = "wechat_official_customer_daily_local_v1"

    private fun bookPrefix(bookId: String): String {
        val bookHash = MessageDigest.getInstance("SHA-256")
            .digest(bookId.toByteArray(Charsets.UTF_8))
            .take(12).joinToString("") { "%02x".format(it) }
        return "$bookHash:"
    }

    private fun key(bookId: String, date: String): String {
        LocalDate.parse(date) // reject malformed dates rather than mis-keying rows
        return bookPrefix(bookId) + date
    }

    fun get(context: Context, bookId: String, date: String): OfficialWechatDailyCount? {
        val raw = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .getString(key(bookId, date), null) ?: return null
        val values = raw.split(",")
        if (values.size != 2) return null
        val newCount = values[0].toIntOrNull() ?: return null
        val returningCount = values[1].toIntOrNull() ?: return null
        if (newCount < 0 || returningCount < 0) return null
        return OfficialWechatDailyCount(newCount, returningCount)
    }

    fun put(context: Context, bookId: String, date: String, stats: OfficialWechatDailyCount): Boolean {
        if (stats.newCustomers < 0 || stats.returningCustomers < 0 ||
            stats.total > 1000000
        ) return false
        return context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE).edit()
            .putString(key(bookId, date), "${stats.newCustomers},${stats.returningCustomers}")
            .commit()
    }

    /** Tombstone must sync; a true delete cannot be represented by removing a preference. */
    fun delete(context: Context, bookId: String, date: String): Boolean =
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE).edit()
            .putString(key(bookId, date), "DELETED").commit()

    fun hasLocalEntry(context: Context, bookId: String, date: String): Boolean =
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .contains(key(bookId, date))

    /** Null value means a local delete tombstone, not a missing entry. */
    fun allForBook(context: Context, bookId: String): Map<String, OfficialWechatDailyCount?> {
        val prefix = bookPrefix(bookId)
        return context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE).all
            .asSequence()
            .filter { (name, _) -> name.startsWith(prefix) }
            .mapNotNull { (name, raw) ->
                val date = name.removePrefix(prefix)
                val valid = runCatching { LocalDate.parse(date) }.getOrNull()
                    ?: return@mapNotNull null
                val value = raw as? String ?: return@mapNotNull null
                if (value == "DELETED") date to null
                else {
                    val fields = value.split(",")
                    val n = fields.getOrNull(0)?.toIntOrNull()
                    val o = fields.getOrNull(1)?.toIntOrNull()
                    if (n == null || o == null || n < 0 || o < 0 || n + o > 1_000_000)
                        null else date to OfficialWechatDailyCount(n, o)
                }
            }
            .sortedBy { it.first }
            .toMap()
    }

    /** Called only after remote revision and dirty-local checks have passed. */
    fun applyFromCloud(
        context: Context, bookId: String, date: String,
        stats: OfficialWechatDailyCount?
    ): Boolean = if (stats == null) delete(context, bookId, date)
    else put(context, bookId, date, stats)
}
