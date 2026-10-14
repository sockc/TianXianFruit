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

    private fun key(bookId: String, date: String): String {
        LocalDate.parse(date) // reject malformed dates rather than mis-keying rows
        val bookHash = MessageDigest.getInstance("SHA-256")
            .digest(bookId.toByteArray(Charsets.UTF_8))
            .take(12).joinToString("") { "%02x".format(it) }
        return "$bookHash:$date"
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

    fun delete(context: Context, bookId: String, date: String): Boolean =
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE).edit()
            .remove(key(bookId, date)).commit()
}
