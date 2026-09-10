package com.tianxian.fruit.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

data class FruitOption(val id: Long, val name: String, val defaultUnit: String)
data class StoreOption(val id: Long, val name: String, val address: String)
data class PurchaseRecord(
    val id: Long,
    val date: String,
    val fruitId: Long,
    val fruitName: String,
    val unit: String,
    val quantity: Double,
    val totalCost: Double,
    val unitPrice: Double,
    val supplier: String,
    val remark: String
)
data class SessionRecord(
    val id: Long,
    val date: String,
    val storeId: Long,
    val storeName: String,
    val wechatIncome: Double,
    val alipayIncome: Double,
    val cashIncome: Double,
    val revenue: Double,
    val expense: Double,
    val openingStockValue: Double,
    val stockLeftValue: Double,
    val purchaseCost: Double,
    val profit: Double,
    val newCustomer: Int,
    val oldCustomer: Int
) {
    val customerTotal: Int get() = newCustomer + oldCustomer
}
data class RankingRecord(
    val storeName: String,
    val revenue: Double,
    val profit: Double,
    val customers: Int,
    val days: Int
)

class AppDatabase(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE fruit(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL UNIQUE,
                default_unit TEXT NOT NULL DEFAULT '斤',
                enabled INTEGER NOT NULL DEFAULT 1,
                sync_id TEXT NOT NULL,
                sync_status INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE store(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL UNIQUE,
                address TEXT NOT NULL DEFAULT '',
                enabled INTEGER NOT NULL DEFAULT 1,
                sync_id TEXT NOT NULL,
                sync_status INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE purchase(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                date TEXT NOT NULL,
                fruit_id INTEGER NOT NULL,
                fruit_name TEXT NOT NULL,
                unit TEXT NOT NULL,
                quantity REAL NOT NULL,
                total_cost REAL NOT NULL,
                unit_price REAL NOT NULL,
                supplier TEXT NOT NULL DEFAULT '',
                remark TEXT NOT NULL DEFAULT '',
                sync_id TEXT NOT NULL,
                sync_status INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_purchase_date ON purchase(date)")
        db.execSQL("CREATE INDEX idx_purchase_fruit ON purchase(fruit_id)")
        db.execSQL(
            """
            CREATE TABLE daily_session(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                date TEXT NOT NULL UNIQUE,
                store_id INTEGER NOT NULL,
                store_name TEXT NOT NULL,
                wechat_income REAL NOT NULL DEFAULT 0,
                alipay_income REAL NOT NULL DEFAULT 0,
                cash_income REAL NOT NULL DEFAULT 0,
                revenue REAL NOT NULL DEFAULT 0,
                expense REAL NOT NULL DEFAULT 0,
                opening_stock_value REAL NOT NULL DEFAULT 0,
                stock_left_value REAL NOT NULL DEFAULT 0,
                purchase_cost REAL NOT NULL DEFAULT 0,
                profit REAL NOT NULL DEFAULT 0,
                new_customer INTEGER NOT NULL DEFAULT 0,
                old_customer INTEGER NOT NULL DEFAULT 0,
                sync_id TEXT NOT NULL,
                sync_status INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        seed(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // V1 目前没有迁移。后续版本必须只做 ALTER/MIGRATION，不能删除用户数据。
    }

    private fun seed(db: SQLiteDatabase) {
        listOf("巨峰葡萄", "阳光玫瑰", "蓝莓", "草莓", "西瓜", "芒果", "荔枝").forEach {
            val values = baseSyncValues().apply {
                put("name", it)
                put("default_unit", "斤")
                put("enabled", 1)
            }
            db.insert("fruit", null, values)
        }
    }

    fun getFruits(): List<FruitOption> = readableDatabase.rawQuery(
        "SELECT id,name,default_unit FROM fruit WHERE enabled=1 ORDER BY id",
        null
    ).use { c -> buildList { while (c.moveToNext()) add(FruitOption(c.long("id"), c.str("name"), c.str("default_unit"))) } }

    fun addFruit(name: String, defaultUnit: String): Long {
        val clean = name.trim()
        if (clean.isBlank()) return -1
        val values = baseSyncValues().apply {
            put("name", clean)
            put("default_unit", defaultUnit)
            put("enabled", 1)
        }
        return writableDatabase.insertWithOnConflict("fruit", null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun getStores(): List<StoreOption> = readableDatabase.rawQuery(
        "SELECT id,name,address FROM store WHERE enabled=1 ORDER BY id DESC",
        null
    ).use { c -> buildList { while (c.moveToNext()) add(StoreOption(c.long("id"), c.str("name"), c.str("address"))) } }

    fun addStore(name: String, address: String): Long {
        val clean = name.trim()
        if (clean.isBlank()) return -1
        val values = baseSyncValues().apply {
            put("name", clean)
            put("address", address.trim())
            put("enabled", 1)
        }
        return writableDatabase.insertWithOnConflict("store", null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun addPurchase(
        date: String,
        fruit: FruitOption,
        unit: String,
        quantity: Double,
        totalCost: Double,
        supplier: String,
        remark: String
    ): Long {
        val price = if (quantity > 0) totalCost / quantity else 0.0
        val values = baseSyncValues().apply {
            put("date", date)
            put("fruit_id", fruit.id)
            put("fruit_name", fruit.name)
            put("unit", unit)
            put("quantity", quantity)
            put("total_cost", totalCost)
            put("unit_price", price)
            put("supplier", supplier.trim())
            put("remark", remark.trim())
        }
        return writableDatabase.insert("purchase", null, values)
    }

    fun deletePurchase(id: Long) {
        writableDatabase.delete("purchase", "id=?", arrayOf(id.toString()))
    }

    fun getPurchaseTotal(date: String): Double = readableDatabase.rawQuery(
        "SELECT COALESCE(SUM(total_cost),0) AS total FROM purchase WHERE date=?",
        arrayOf(date)
    ).use { c -> if (c.moveToFirst()) c.dbl("total") else 0.0 }

    fun getRecentPurchases(limit: Int = 100): List<PurchaseRecord> = readableDatabase.rawQuery(
        "SELECT * FROM purchase ORDER BY date DESC,id DESC LIMIT ?",
        arrayOf(limit.toString())
    ).use { c -> purchaseList(c) }

    fun getFruitPriceHistory(fruitId: Long, limit: Int = 8): List<PurchaseRecord> = readableDatabase.rawQuery(
        "SELECT * FROM purchase WHERE fruit_id=? ORDER BY date DESC,id DESC LIMIT ?",
        arrayOf(fruitId.toString(), limit.toString())
    ).use { c -> purchaseList(c) }

    private fun purchaseList(c: Cursor): List<PurchaseRecord> = buildList {
        while (c.moveToNext()) {
            add(
                PurchaseRecord(
                    c.long("id"), c.str("date"), c.long("fruit_id"), c.str("fruit_name"), c.str("unit"),
                    c.dbl("quantity"), c.dbl("total_cost"), c.dbl("unit_price"), c.str("supplier"), c.str("remark")
                )
            )
        }
    }

    fun getSession(date: String): SessionRecord? = readableDatabase.rawQuery(
        "SELECT * FROM daily_session WHERE date=? LIMIT 1",
        arrayOf(date)
    ).use { c -> if (c.moveToFirst()) session(c) else null }

    fun getPreviousClosingStock(date: String): Double = readableDatabase.rawQuery(
        "SELECT stock_left_value FROM daily_session WHERE date<? ORDER BY date DESC LIMIT 1",
        arrayOf(date)
    ).use { c -> if (c.moveToFirst()) c.dbl("stock_left_value") else 0.0 }

    fun saveSession(
        date: String,
        store: StoreOption,
        wechat: Double,
        alipay: Double,
        cash: Double,
        expense: Double,
        openingStock: Double,
        closingStock: Double,
        newCustomer: Int,
        oldCustomer: Int
    ) {
        val purchaseCost = getPurchaseTotal(date)
        val revenue = wechat + alipay + cash
        val profit = revenue + closingStock - openingStock - purchaseCost - expense
        val old = getSession(date)
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("date", date)
            put("store_id", store.id)
            put("store_name", store.name)
            put("wechat_income", wechat)
            put("alipay_income", alipay)
            put("cash_income", cash)
            put("revenue", revenue)
            put("expense", expense)
            put("opening_stock_value", openingStock)
            put("stock_left_value", closingStock)
            put("purchase_cost", purchaseCost)
            put("profit", profit)
            put("new_customer", newCustomer)
            put("old_customer", oldCustomer)
            put("sync_status", 0)
            put("updated_at", now)
            if (old == null) {
                put("sync_id", UUID.randomUUID().toString())
                put("created_at", now)
            }
        }
        if (old == null) writableDatabase.insert("daily_session", null, values)
        else writableDatabase.update("daily_session", values, "id=?", arrayOf(old.id.toString()))
    }

    fun deleteSession(id: Long) {
        writableDatabase.delete("daily_session", "id=?", arrayOf(id.toString()))
    }

    fun getRecentSessions(limit: Int = 120): List<SessionRecord> = readableDatabase.rawQuery(
        "SELECT * FROM daily_session ORDER BY date DESC LIMIT ?",
        arrayOf(limit.toString())
    ).use { c -> buildList { while (c.moveToNext()) add(session(c)) } }

    fun getSessionsBetween(start: String?, end: String?): List<SessionRecord> {
        val sql: String
        val args: Array<String>
        if (start == null || end == null) {
            sql = "SELECT * FROM daily_session ORDER BY date DESC"
            args = emptyArray()
        } else {
            sql = "SELECT * FROM daily_session WHERE date>=? AND date<=? ORDER BY date DESC"
            args = arrayOf(start, end)
        }
        return readableDatabase.rawQuery(sql, args).use { c -> buildList { while (c.moveToNext()) add(session(c)) } }
    }

    fun getRankings(start: String? = null, end: String? = null): List<RankingRecord> {
        val where = if (start != null && end != null) "WHERE date>=? AND date<=?" else ""
        val args = if (start != null && end != null) arrayOf(start, end) else emptyArray()
        return readableDatabase.rawQuery(
            """
            SELECT store_name,
                   COALESCE(SUM(revenue),0) revenue_sum,
                   COALESCE(SUM(profit),0) profit_sum,
                   COALESCE(SUM(new_customer+old_customer),0) customer_sum,
                   COUNT(*) day_count
            FROM daily_session
            $where
            GROUP BY store_name
            """.trimIndent(), args
        ).use { c -> buildList {
            while (c.moveToNext()) add(
                RankingRecord(
                    c.str("store_name"), c.dbl("revenue_sum"), c.dbl("profit_sum"),
                    c.int("customer_sum"), c.int("day_count")
                )
            )
        } }
    }

    fun exportJson(): String {
        val root = JSONObject()
        root.put("schemaVersion", DB_VERSION)
        root.put("exportedAt", System.currentTimeMillis())
        root.put("fruit", tableAsJson("fruit"))
        root.put("store", tableAsJson("store"))
        root.put("purchase", tableAsJson("purchase"))
        root.put("daily_session", tableAsJson("daily_session"))
        return root.toString(2)
    }

    private fun tableAsJson(table: String): JSONArray {
        val array = JSONArray()
        readableDatabase.rawQuery("SELECT * FROM $table", null).use { c ->
            while (c.moveToNext()) {
                val obj = JSONObject()
                for (i in 0 until c.columnCount) {
                    val name = c.getColumnName(i)
                    when (c.getType(i)) {
                        Cursor.FIELD_TYPE_INTEGER -> obj.put(name, c.getLong(i))
                        Cursor.FIELD_TYPE_FLOAT -> obj.put(name, c.getDouble(i))
                        Cursor.FIELD_TYPE_STRING -> obj.put(name, c.getString(i))
                        Cursor.FIELD_TYPE_NULL -> obj.put(name, JSONObject.NULL)
                        else -> obj.put(name, c.getString(i))
                    }
                }
                array.put(obj)
            }
        }
        return array
    }

    private fun session(c: Cursor) = SessionRecord(
        c.long("id"), c.str("date"), c.long("store_id"), c.str("store_name"),
        c.dbl("wechat_income"), c.dbl("alipay_income"), c.dbl("cash_income"), c.dbl("revenue"),
        c.dbl("expense"), c.dbl("opening_stock_value"), c.dbl("stock_left_value"),
        c.dbl("purchase_cost"), c.dbl("profit"), c.int("new_customer"), c.int("old_customer")
    )

    private fun baseSyncValues(): ContentValues {
        val now = System.currentTimeMillis()
        return ContentValues().apply {
            put("sync_id", UUID.randomUUID().toString())
            put("sync_status", 0)
            put("created_at", now)
            put("updated_at", now)
        }
    }

    private fun Cursor.str(name: String): String = getString(getColumnIndexOrThrow(name)) ?: ""
    private fun Cursor.long(name: String): Long = getLong(getColumnIndexOrThrow(name))
    private fun Cursor.int(name: String): Int = getInt(getColumnIndexOrThrow(name))
    private fun Cursor.dbl(name: String): Double = getDouble(getColumnIndexOrThrow(name))

    companion object {
        const val DB_NAME = "tianxian_fruit.db"
        const val DB_VERSION = 1
    }
}

fun currentMonthRange(today: LocalDate = LocalDate.now()): Pair<String, String> {
    return today.withDayOfMonth(1).toString() to today.withDayOfMonth(today.lengthOfMonth()).toString()
}
