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
import kotlin.math.round

data class FruitOption(val id: Long, val name: String, val defaultUnit: String)
data class StoreOption(val id: Long, val name: String, val address: String)
data class PartnerOption(val id: Long, val name: String)

data class PurchaseLineInput(
    val fruit: FruitOption,
    val unit: String,
    val quantity: Double,
    val totalCost: Double
)

data class PurchaseOrderRecord(
    val id: Long,
    val date: String,
    val buyerId: Long,
    val buyerName: String,
    val storeId: Long,
    val storeName: String,
    val totalCost: Double,
    val remark: String
)

data class PurchaseItemRecord(
    val id: Long,
    val orderId: Long,
    val fruitId: Long,
    val fruitName: String,
    val unit: String,
    val quantity: Double,
    val totalCost: Double,
    val unitPrice: Double
)

data class PurchaseOrderDetail(
    val order: PurchaseOrderRecord,
    val items: List<PurchaseItemRecord>
)

data class PriceHistoryRecord(
    val date: String,
    val fruitName: String,
    val unit: String,
    val quantity: Double,
    val totalCost: Double,
    val unitPrice: Double,
    val buyerName: String,
    val storeName: String
)

data class StoreDailyRecord(
    val id: Long,
    val date: String,
    val storeId: Long,
    val storeName: String,
    val wechatIncome: Double,
    val wechatCollectorId: Long,
    val wechatCollectorName: String,
    val alipayIncome: Double,
    val alipayCollectorId: Long,
    val alipayCollectorName: String,
    val cashIncome: Double,
    val cashCollectorId: Long,
    val cashCollectorName: String,
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

data class PartnerMoneySummary(
    val partnerId: Long,
    val partnerName: String,
    val amount: Double
)

data class DailySummary(
    val date: String,
    val revenue: Double,
    val purchaseCost: Double,
    val expense: Double,
    val openingStockValue: Double,
    val closingStockValue: Double,
    val profit: Double,
    val customers: Int,
    val storeCount: Int
)

data class ProfitDistributionRecord(
    val id: Long,
    val date: String,
    val partnerId: Long,
    val partnerName: String,
    val role: String,
    val ratio: Double,
    val weight: Int,
    val sourceProfit: Double,
    val allocatedProfit: Double
)

data class ProfitRuleRecord(
    val partnerId: Long,
    val partnerName: String,
    val percent: Double
)

class AppDatabase(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        createFruitTable(db)
        createStoreTable(db)
        createV2Tables(db)
        createV3Tables(db)
        seedFruits(db)
        seedPartners(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) migrateV1ToV2(db)
        if (oldVersion < 3) createV3Tables(db)
    }

    private fun createFruitTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS fruit(
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
    }

    private fun createStoreTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS store(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL UNIQUE,
                address TEXT NOT NULL DEFAULT '',
                enabled INTEGER NOT NULL DEFAULT 1,
                deleted INTEGER NOT NULL DEFAULT 0,
                sync_id TEXT NOT NULL,
                sync_status INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    private fun createV2Tables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS partner(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                enabled INTEGER NOT NULL DEFAULT 1,
                deleted INTEGER NOT NULL DEFAULT 0,
                sync_id TEXT NOT NULL,
                sync_status INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS purchase_order(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                date TEXT NOT NULL,
                buyer_id INTEGER NOT NULL DEFAULT 0,
                buyer_name TEXT NOT NULL DEFAULT '未指定',
                store_id INTEGER NOT NULL DEFAULT 0,
                store_name TEXT NOT NULL DEFAULT '未指定',
                total_cost REAL NOT NULL DEFAULT 0,
                remark TEXT NOT NULL DEFAULT '',
                deleted INTEGER NOT NULL DEFAULT 0,
                sync_id TEXT NOT NULL,
                sync_status INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_purchase_order_date ON purchase_order(date)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_purchase_order_buyer ON purchase_order(buyer_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_purchase_order_store ON purchase_order(store_id)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS purchase_item(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                order_id INTEGER NOT NULL,
                fruit_id INTEGER NOT NULL,
                fruit_name TEXT NOT NULL,
                unit TEXT NOT NULL,
                quantity REAL NOT NULL,
                total_cost REAL NOT NULL,
                unit_price REAL NOT NULL,
                deleted INTEGER NOT NULL DEFAULT 0,
                sync_id TEXT NOT NULL,
                sync_status INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_purchase_item_order ON purchase_item(order_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_purchase_item_fruit ON purchase_item(fruit_id)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS store_daily_record(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                date TEXT NOT NULL,
                store_id INTEGER NOT NULL,
                store_name TEXT NOT NULL,
                wechat_income REAL NOT NULL DEFAULT 0,
                wechat_collector_id INTEGER NOT NULL DEFAULT 0,
                wechat_collector_name TEXT NOT NULL DEFAULT '未指定',
                alipay_income REAL NOT NULL DEFAULT 0,
                alipay_collector_id INTEGER NOT NULL DEFAULT 0,
                alipay_collector_name TEXT NOT NULL DEFAULT '未指定',
                cash_income REAL NOT NULL DEFAULT 0,
                cash_collector_id INTEGER NOT NULL DEFAULT 0,
                cash_collector_name TEXT NOT NULL DEFAULT '未指定',
                revenue REAL NOT NULL DEFAULT 0,
                expense REAL NOT NULL DEFAULT 0,
                opening_stock_value REAL NOT NULL DEFAULT 0,
                stock_left_value REAL NOT NULL DEFAULT 0,
                purchase_cost REAL NOT NULL DEFAULT 0,
                profit REAL NOT NULL DEFAULT 0,
                new_customer INTEGER NOT NULL DEFAULT 0,
                old_customer INTEGER NOT NULL DEFAULT 0,
                deleted INTEGER NOT NULL DEFAULT 0,
                sync_id TEXT NOT NULL,
                sync_status INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                UNIQUE(date, store_id)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_store_daily_date ON store_daily_record(date)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS profit_distribution(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                date TEXT NOT NULL,
                partner_id INTEGER NOT NULL,
                partner_name TEXT NOT NULL,
                role TEXT NOT NULL,
                ratio REAL NOT NULL DEFAULT 0,
                weight INTEGER NOT NULL DEFAULT 0,
                source_profit REAL NOT NULL DEFAULT 0,
                allocated_profit REAL NOT NULL DEFAULT 0,
                deleted INTEGER NOT NULL DEFAULT 0,
                sync_id TEXT NOT NULL,
                sync_status INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_profit_distribution_date ON profit_distribution(date)")
    }

    private fun createV3Tables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS profit_rule(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                partner_id INTEGER NOT NULL,
                percent REAL NOT NULL DEFAULT 0,
                deleted INTEGER NOT NULL DEFAULT 0,
                sync_id TEXT NOT NULL,
                sync_status INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_profit_rule_partner ON profit_rule(partner_id)")
    }

    private fun migrateV1ToV2(db: SQLiteDatabase) {
        db.beginTransaction()
        try {
            if (!columnExists(db, "store", "deleted")) {
                db.execSQL("ALTER TABLE store ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
            }
            createV2Tables(db)
            seedPartners(db)

            if (tableExists(db, "purchase")) {
                db.rawQuery("SELECT * FROM purchase ORDER BY id", null).use { c ->
                    while (c.moveToNext()) {
                        val now = System.currentTimeMillis()
                        val orderValues = baseSyncValues().apply {
                            put("date", c.str("date"))
                            put("buyer_id", 0)
                            put("buyer_name", "历史未指定")
                            put("store_id", 0)
                            put("store_name", "历史未指定")
                            put("total_cost", c.dbl("total_cost"))
                            put("remark", c.str("remark"))
                            put("deleted", 0)
                        }
                        val orderId = db.insert("purchase_order", null, orderValues)
                        if (orderId > 0) {
                            val itemValues = baseSyncValues().apply {
                                put("order_id", orderId)
                                put("fruit_id", c.long("fruit_id"))
                                put("fruit_name", c.str("fruit_name"))
                                put("unit", c.str("unit"))
                                put("quantity", c.dbl("quantity"))
                                put("total_cost", c.dbl("total_cost"))
                                put("unit_price", c.dbl("unit_price"))
                                put("deleted", 0)
                            }
                            db.insert("purchase_item", null, itemValues)
                        }
                    }
                }
            }

            if (tableExists(db, "daily_session")) {
                db.rawQuery("SELECT * FROM daily_session ORDER BY id", null).use { c ->
                    while (c.moveToNext()) {
                        val values = baseSyncValues().apply {
                            put("date", c.str("date"))
                            put("store_id", c.long("store_id"))
                            put("store_name", c.str("store_name"))
                            put("wechat_income", c.dbl("wechat_income"))
                            put("wechat_collector_id", 0)
                            put("wechat_collector_name", "历史未指定")
                            put("alipay_income", c.dbl("alipay_income"))
                            put("alipay_collector_id", 0)
                            put("alipay_collector_name", "历史未指定")
                            put("cash_income", c.dbl("cash_income"))
                            put("cash_collector_id", 0)
                            put("cash_collector_name", "历史未指定")
                            put("revenue", c.dbl("revenue"))
                            put("expense", c.dbl("expense"))
                            put("opening_stock_value", c.dbl("opening_stock_value"))
                            put("stock_left_value", c.dbl("stock_left_value"))
                            put("purchase_cost", c.dbl("purchase_cost"))
                            put("profit", c.dbl("profit"))
                            put("new_customer", c.int("new_customer"))
                            put("old_customer", c.int("old_customer"))
                            put("deleted", 0)
                        }
                        db.insertWithOnConflict("store_daily_record", null, values, SQLiteDatabase.CONFLICT_IGNORE)
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun seedFruits(db: SQLiteDatabase) {
        listOf("巨峰葡萄", "阳光玫瑰", "蓝莓", "草莓", "西瓜", "芒果", "荔枝").forEach {
            val values = baseSyncValues().apply {
                put("name", it)
                put("default_unit", "斤")
                put("enabled", 1)
            }
            db.insertWithOnConflict("fruit", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        }
    }

    private fun seedPartners(db: SQLiteDatabase) {
        val count = db.rawQuery("SELECT COUNT(*) AS c FROM partner WHERE deleted=0", null).use { c -> if (c.moveToFirst()) c.int("c") else 0 }
        if (count == 0) {
            listOf("合伙人1", "合伙人2", "合伙人3", "合伙人4").forEach { name ->
                db.insert("partner", null, baseSyncValues().apply {
                    put("name", name)
                    put("enabled", 1)
                    put("deleted", 0)
                })
            }
        }
    }

    fun getFruits(): List<FruitOption> = readableDatabase.rawQuery(
        "SELECT id,name,default_unit FROM fruit WHERE enabled=1 ORDER BY id", null
    ).use { c -> buildList { while (c.moveToNext()) add(FruitOption(c.long("id"), c.str("name"), c.str("default_unit"))) } }

    fun addFruit(name: String, defaultUnit: String): Long {
        val clean = name.trim()
        if (clean.isBlank()) return -1
        return writableDatabase.insertWithOnConflict("fruit", null, baseSyncValues().apply {
            put("name", clean); put("default_unit", defaultUnit); put("enabled", 1)
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun getStores(): List<StoreOption> = readableDatabase.rawQuery(
        "SELECT id,name,address FROM store WHERE enabled=1 AND deleted=0 ORDER BY id DESC", null
    ).use { c -> buildList { while (c.moveToNext()) add(StoreOption(c.long("id"), c.str("name"), c.str("address"))) } }

    fun addStore(name: String, address: String): Long {
        val clean = name.trim()
        if (clean.isBlank()) return -1
        return writableDatabase.insertWithOnConflict("store", null, baseSyncValues().apply {
            put("name", clean); put("address", address.trim()); put("enabled", 1); put("deleted", 0)
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun deleteStore(id: Long) {
        writableDatabase.update("store", ContentValues().apply {
            put("enabled", 0); put("deleted", 1); put("sync_status", 2); put("updated_at", System.currentTimeMillis())
        }, "id=?", arrayOf(id.toString()))
    }

    fun getPartners(): List<PartnerOption> = readableDatabase.rawQuery(
        "SELECT id,name FROM partner WHERE enabled=1 AND deleted=0 ORDER BY id", null
    ).use { c -> buildList { while (c.moveToNext()) add(PartnerOption(c.long("id"), c.str("name"))) } }

    fun addPartner(name: String): Long {
        val clean = name.trim()
        if (clean.isBlank()) return -1
        val duplicate = readableDatabase.rawQuery("SELECT id FROM partner WHERE name=? AND deleted=0 LIMIT 1", arrayOf(clean)).use { it.moveToFirst() }
        if (duplicate) return -1
        return writableDatabase.insert("partner", null, baseSyncValues().apply {
            put("name", clean); put("enabled", 1); put("deleted", 0)
        })
    }

    fun updatePartnerName(id: Long, name: String): Boolean {
        val clean = name.trim()
        if (clean.isBlank()) return false
        val duplicate = readableDatabase.rawQuery(
            "SELECT id FROM partner WHERE name=? AND deleted=0 AND id<>? LIMIT 1",
            arrayOf(clean, id.toString())
        ).use { it.moveToFirst() }
        if (duplicate) return false
        return writableDatabase.update("partner", ContentValues().apply {
            put("name", clean)
            put("sync_status", 2)
            put("updated_at", System.currentTimeMillis())
        }, "id=?", arrayOf(id.toString())) > 0
    }

    fun deletePartner(id: Long) {
        writableDatabase.update("partner", ContentValues().apply {
            put("enabled", 0); put("deleted", 1); put("sync_status", 2); put("updated_at", System.currentTimeMillis())
        }, "id=?", arrayOf(id.toString()))
    }

    fun addPurchaseOrder(
        date: String,
        buyer: PartnerOption,
        lines: List<PurchaseLineInput>,
        remark: String
    ): Long {
        if (lines.isEmpty()) return -1
        val db = writableDatabase
        db.beginTransaction()
        try {
            val total = lines.sumOf { it.totalCost }
            val orderId = db.insert("purchase_order", null, baseSyncValues().apply {
                put("date", date)
                put("buyer_id", buyer.id); put("buyer_name", buyer.name)
                put("store_id", 0); put("store_name", "共用货品")
                put("total_cost", total); put("remark", remark.trim()); put("deleted", 0)
            })
            if (orderId <= 0) return -1
            lines.forEach { line ->
                val price = if (line.quantity > 0) line.totalCost / line.quantity else 0.0
                db.insert("purchase_item", null, baseSyncValues().apply {
                    put("order_id", orderId)
                    put("fruit_id", line.fruit.id); put("fruit_name", line.fruit.name)
                    put("unit", line.unit); put("quantity", line.quantity)
                    put("total_cost", line.totalCost); put("unit_price", price)
                    put("deleted", 0)
                })
            }
            db.setTransactionSuccessful()
            return orderId
        } finally {
            db.endTransaction()
        }
    }

    fun deletePurchaseOrder(id: Long) {
        val now = System.currentTimeMillis()
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.update("purchase_order", ContentValues().apply {
                put("deleted", 1); put("sync_status", 2); put("updated_at", now)
            }, "id=?", arrayOf(id.toString()))
            db.update("purchase_item", ContentValues().apply {
                put("deleted", 1); put("sync_status", 2); put("updated_at", now)
            }, "order_id=?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun getPurchaseOrders(limit: Int = 100): List<PurchaseOrderDetail> {
        val orders = readableDatabase.rawQuery(
            "SELECT * FROM purchase_order WHERE deleted=0 ORDER BY date DESC,id DESC LIMIT ?", arrayOf(limit.toString())
        ).use { c -> buildList { while (c.moveToNext()) add(order(c)) } }
        return orders.map { PurchaseOrderDetail(it, getPurchaseItems(it.id)) }
    }

    private fun getPurchaseItems(orderId: Long): List<PurchaseItemRecord> = readableDatabase.rawQuery(
        "SELECT * FROM purchase_item WHERE order_id=? AND deleted=0 ORDER BY id", arrayOf(orderId.toString())
    ).use { c -> buildList {
        while (c.moveToNext()) add(PurchaseItemRecord(
            c.long("id"), c.long("order_id"), c.long("fruit_id"), c.str("fruit_name"), c.str("unit"),
            c.dbl("quantity"), c.dbl("total_cost"), c.dbl("unit_price")
        ))
    } }

    fun getPurchaseTotal(date: String): Double = readableDatabase.rawQuery(
        "SELECT COALESCE(SUM(total_cost),0) AS total FROM purchase_order WHERE date=? AND deleted=0", arrayOf(date)
    ).use { c -> if (c.moveToFirst()) c.dbl("total") else 0.0 }

    fun getPurchaseTotalByStore(date: String, storeId: Long): Double = readableDatabase.rawQuery(
        "SELECT COALESCE(SUM(total_cost),0) AS total FROM purchase_order WHERE date=? AND store_id=? AND deleted=0",
        arrayOf(date, storeId.toString())
    ).use { c -> if (c.moveToFirst()) c.dbl("total") else 0.0 }

    fun getPurchaseTotalsByPartner(date: String): List<PartnerMoneySummary> = readableDatabase.rawQuery(
        """
        SELECT buyer_id,buyer_name,COALESCE(SUM(total_cost),0) amount
        FROM purchase_order WHERE date=? AND deleted=0
        GROUP BY buyer_id,buyer_name ORDER BY amount DESC
        """.trimIndent(), arrayOf(date)
    ).use { c -> buildList { while (c.moveToNext()) add(PartnerMoneySummary(c.long("buyer_id"), c.str("buyer_name"), c.dbl("amount"))) } }

    fun getFruitPriceHistory(fruitId: Long, limit: Int = 20): List<PriceHistoryRecord> = readableDatabase.rawQuery(
        """
        SELECT po.date,pi.fruit_name,pi.unit,pi.quantity,pi.total_cost,pi.unit_price,po.buyer_name,po.store_name
        FROM purchase_item pi JOIN purchase_order po ON po.id=pi.order_id
        WHERE pi.fruit_id=? AND pi.deleted=0 AND po.deleted=0
        ORDER BY po.date DESC,pi.id DESC LIMIT ?
        """.trimIndent(), arrayOf(fruitId.toString(), limit.toString())
    ).use { c -> buildList {
        while (c.moveToNext()) add(PriceHistoryRecord(
            c.str("date"), c.str("fruit_name"), c.str("unit"), c.dbl("quantity"), c.dbl("total_cost"),
            c.dbl("unit_price"), c.str("buyer_name"), c.str("store_name")
        ))
    } }

    fun getStoreDailyRecord(date: String, storeId: Long): StoreDailyRecord? = readableDatabase.rawQuery(
        "SELECT * FROM store_daily_record WHERE date=? AND store_id=? AND deleted=0 LIMIT 1", arrayOf(date, storeId.toString())
    ).use { c -> if (c.moveToFirst()) dailyRecord(c) else null }

    fun getPreviousClosingStock(storeId: Long, date: String): Double = readableDatabase.rawQuery(
        "SELECT stock_left_value FROM store_daily_record WHERE store_id=? AND date<? AND deleted=0 ORDER BY date DESC LIMIT 1",
        arrayOf(storeId.toString(), date)
    ).use { c -> if (c.moveToFirst()) c.dbl("stock_left_value") else 0.0 }

    fun saveStoreDailyRecord(
        date: String,
        store: StoreOption,
        wechat: Double,
        wechatCollector: PartnerOption?,
        alipay: Double,
        alipayCollector: PartnerOption?,
        cash: Double,
        cashCollector: PartnerOption?,
        expense: Double,
        openingStock: Double,
        closingStock: Double,
        newCustomer: Int,
        oldCustomer: Int
    ) {
        val purchaseCost = 0.0
        val revenue = wechat + alipay + cash
        // 共用进货不直接归属某个摊位；这里只保存该摊的经营贡献。
        // 每日总利润在 getDailySummary() 中统一扣除当天全部共用进货。
        val profit = revenue + closingStock - openingStock - expense
        val old = getStoreDailyRecord(date, store.id)
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("date", date); put("store_id", 0); put("store_name", "共用货品")
            put("wechat_income", wechat); put("wechat_collector_id", wechatCollector?.id ?: 0); put("wechat_collector_name", wechatCollector?.name ?: "未指定")
            put("alipay_income", alipay); put("alipay_collector_id", alipayCollector?.id ?: 0); put("alipay_collector_name", alipayCollector?.name ?: "未指定")
            put("cash_income", cash); put("cash_collector_id", cashCollector?.id ?: 0); put("cash_collector_name", cashCollector?.name ?: "未指定")
            put("revenue", revenue); put("expense", expense)
            put("opening_stock_value", openingStock); put("stock_left_value", closingStock)
            put("purchase_cost", purchaseCost); put("profit", profit)
            put("new_customer", newCustomer); put("old_customer", oldCustomer)
            put("deleted", 0); put("sync_status", 0); put("updated_at", now)
            if (old == null) { put("sync_id", UUID.randomUUID().toString()); put("created_at", now) }
        }
        if (old == null) writableDatabase.insert("store_daily_record", null, values)
        else writableDatabase.update("store_daily_record", values, "id=?", arrayOf(old.id.toString()))
    }

    fun deleteStoreDailyRecord(id: Long) {
        writableDatabase.update("store_daily_record", ContentValues().apply {
            put("deleted", 1); put("sync_status", 2); put("updated_at", System.currentTimeMillis())
        }, "id=?", arrayOf(id.toString()))
    }

    fun getDailyRecords(date: String): List<StoreDailyRecord> = readableDatabase.rawQuery(
        "SELECT * FROM store_daily_record WHERE date=? AND deleted=0 ORDER BY id", arrayOf(date)
    ).use { c -> buildList { while (c.moveToNext()) add(dailyRecord(c)) } }

    fun getRecentDailyRecords(limit: Int = 120): List<StoreDailyRecord> = readableDatabase.rawQuery(
        "SELECT * FROM store_daily_record WHERE deleted=0 ORDER BY date DESC,id DESC LIMIT ?", arrayOf(limit.toString())
    ).use { c -> buildList { while (c.moveToNext()) add(dailyRecord(c)) } }

    fun getDailyRecordsBetween(start: String?, end: String?): List<StoreDailyRecord> {
        val where = if (start != null && end != null) "AND date>=? AND date<=?" else ""
        val args = if (start != null && end != null) arrayOf(start, end) else emptyArray()
        return readableDatabase.rawQuery("SELECT * FROM store_daily_record WHERE deleted=0 $where ORDER BY date DESC,id DESC", args)
            .use { c -> buildList { while (c.moveToNext()) add(dailyRecord(c)) } }
    }

    fun getRankings(start: String? = null, end: String? = null): List<RankingRecord> {
        val records = getDailyRecordsBetween(start, end)
        if (records.isEmpty()) return emptyList()

        data class Acc(
            var revenue: Double = 0.0,
            var profit: Double = 0.0,
            var customers: Int = 0,
            val days: MutableSet<String> = mutableSetOf()
        )

        val acc = linkedMapOf<Pair<Long, String>, Acc>()
        records.groupBy { it.date }.forEach { (date, dayRecords) ->
            val commonPurchase = getPurchaseTotal(date)
            val dayRevenue = dayRecords.sumOf { it.revenue }

            dayRecords.forEach { r ->
                val allocatedPurchase = when {
                    commonPurchase == 0.0 -> 0.0
                    dayRevenue > 0.0 -> commonPurchase * (r.revenue / dayRevenue)
                    dayRecords.isNotEmpty() -> commonPurchase / dayRecords.size
                    else -> 0.0
                }
                val attributedProfit =
                    r.revenue + r.stockLeftValue - r.openingStockValue - r.expense - allocatedPurchase

                val key = r.storeId to r.storeName
                val a = acc.getOrPut(key) { Acc() }
                a.revenue += r.revenue
                a.profit += attributedProfit
                a.customers += r.customerTotal
                a.days += date
            }
        }

        return acc.map { (key, a) ->
            RankingRecord(
                storeName = key.second,
                revenue = roundMoney(a.revenue),
                profit = roundMoney(a.profit),
                customers = a.customers,
                days = a.days.size
            )
        }
    }

    fun getDailySummary(date: String): DailySummary {
        val records = getDailyRecords(date)
        val revenue = records.sumOf { it.revenue }
        val expense = records.sumOf { it.expense }
        val opening = records.sumOf { it.openingStockValue }
        val closing = records.sumOf { it.stockLeftValue }
        val purchase = getPurchaseTotal(date)
        val profit = revenue + closing - opening - purchase - expense
        return DailySummary(date, revenue, purchase, expense, opening, closing, profit, records.sumOf { it.customerTotal }, records.size)
    }

    fun getReceiptsByPartner(date: String): List<PartnerMoneySummary> {
        val map = linkedMapOf<Long, Pair<String, Double>>()
        fun add(id: Long, name: String, amount: Double) {
            if (amount == 0.0) return
            val old = map[id]
            map[id] = name to ((old?.second ?: 0.0) + amount)
        }
        getDailyRecords(date).forEach { r ->
            add(r.wechatCollectorId, r.wechatCollectorName, r.wechatIncome)
            add(r.alipayCollectorId, r.alipayCollectorName, r.alipayIncome)
            add(r.cashCollectorId, r.cashCollectorName, r.cashIncome)
        }
        return map.map { PartnerMoneySummary(it.key, it.value.first, it.value.second) }.sortedByDescending { it.amount }
    }

    fun getProfitRules(): List<ProfitRuleRecord> = readableDatabase.rawQuery(
        """
        SELECT r.partner_id,p.name,r.percent
        FROM profit_rule r
        JOIN partner p ON p.id=r.partner_id
        WHERE r.deleted=0 AND p.deleted=0 AND p.enabled=1
        ORDER BY p.id
        """.trimIndent(), null
    ).use { c -> buildList {
        while (c.moveToNext()) add(ProfitRuleRecord(c.long("partner_id"), c.str("name"), c.dbl("percent")))
    } }

    fun saveProfitRules(rules: List<Pair<PartnerOption, Double>>): Boolean {
        if (rules.isEmpty()) return false
        val total = rules.sumOf { it.second }
        if (kotlin.math.abs(total - 100.0) >= 0.01) return false
        val db = writableDatabase
        db.beginTransaction()
        try {
            val now = System.currentTimeMillis()
            db.update("profit_rule", ContentValues().apply {
                put("deleted", 1); put("sync_status", 2); put("updated_at", now)
            }, "deleted=0", null)
            rules.forEach { (partner, percent) ->
                db.insert("profit_rule", null, baseSyncValues().apply {
                    put("partner_id", partner.id)
                    put("percent", percent)
                    put("deleted", 0)
                })
            }
            db.setTransactionSuccessful()
            return true
        } finally { db.endTransaction() }
    }

    fun saveProfitDistribution(date: String, allocations: List<Pair<PartnerOption, Double>>): Boolean {
        if (allocations.isEmpty()) return false
        val totalPercent = allocations.sumOf { it.second }
        if (kotlin.math.abs(totalPercent - 100.0) >= 0.01) return false
        val profit = getDailySummary(date).profit
        if (profit <= 0) return false

        val db = writableDatabase
        db.beginTransaction()
        try {
            val now = System.currentTimeMillis()
            db.update("profit_distribution", ContentValues().apply {
                put("deleted", 1); put("sync_status", 2); put("updated_at", now)
            }, "date=? AND deleted=0", arrayOf(date))

            var allocatedSoFar = 0.0
            allocations.forEachIndexed { index, (partner, percent) ->
                val amount = if (index == allocations.lastIndex) {
                    roundMoney(profit - allocatedSoFar)
                } else {
                    roundMoney(profit * percent / 100.0).also { allocatedSoFar += it }
                }
                db.insert("profit_distribution", null, baseSyncValues().apply {
                    put("date", date)
                    put("partner_id", partner.id)
                    put("partner_name", partner.name)
                    put("role", "CUSTOM_PERCENT")
                    put("ratio", percent / 100.0)
                    put("weight", 0)
                    put("source_profit", profit)
                    put("allocated_profit", amount)
                    put("deleted", 0)
                })
            }
            db.setTransactionSuccessful()
            return true
        } finally { db.endTransaction() }
    }

    fun deleteProfitDistribution(date: String) {
        writableDatabase.update("profit_distribution", ContentValues().apply {
            put("deleted", 1)
            put("sync_status", 2)
            put("updated_at", System.currentTimeMillis())
        }, "date=? AND deleted=0", arrayOf(date))
    }

    fun getProfitDistribution(date: String): List<ProfitDistributionRecord> = readableDatabase.rawQuery(
        "SELECT * FROM profit_distribution WHERE date=? AND deleted=0 ORDER BY allocated_profit DESC,id", arrayOf(date)
    ).use { c -> profitList(c) }

    fun getRecentProfitDistributions(limit: Int = 80): List<ProfitDistributionRecord> = readableDatabase.rawQuery(
        "SELECT * FROM profit_distribution WHERE deleted=0 ORDER BY date DESC,id LIMIT ?", arrayOf(limit.toString())
    ).use { c -> profitList(c) }

    private fun profitList(c: Cursor): List<ProfitDistributionRecord> = buildList {
        while (c.moveToNext()) add(ProfitDistributionRecord(
            c.long("id"), c.str("date"), c.long("partner_id"), c.str("partner_name"), c.str("role"),
            c.dbl("ratio"), c.int("weight"), c.dbl("source_profit"), c.dbl("allocated_profit")
        ))
    }

    fun exportJson(): String {
        val root = JSONObject()
        root.put("schemaVersion", DB_VERSION)
        root.put("exportedAt", System.currentTimeMillis())
        listOf("fruit", "store", "partner", "purchase_order", "purchase_item", "store_daily_record", "profit_rule", "profit_distribution").forEach { table ->
            root.put(table, tableAsJson(table))
        }
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

    private fun order(c: Cursor) = PurchaseOrderRecord(
        c.long("id"), c.str("date"), c.long("buyer_id"), c.str("buyer_name"), c.long("store_id"), c.str("store_name"), c.dbl("total_cost"), c.str("remark")
    )

    private fun dailyRecord(c: Cursor) = StoreDailyRecord(
        c.long("id"), c.str("date"), c.long("store_id"), c.str("store_name"),
        c.dbl("wechat_income"), c.long("wechat_collector_id"), c.str("wechat_collector_name"),
        c.dbl("alipay_income"), c.long("alipay_collector_id"), c.str("alipay_collector_name"),
        c.dbl("cash_income"), c.long("cash_collector_id"), c.str("cash_collector_name"),
        c.dbl("revenue"), c.dbl("expense"), c.dbl("opening_stock_value"), c.dbl("stock_left_value"),
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

    private fun tableExists(db: SQLiteDatabase, table: String): Boolean = db.rawQuery(
        "SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)
    ).use { it.moveToFirst() }

    private fun columnExists(db: SQLiteDatabase, table: String, column: String): Boolean = db.rawQuery("PRAGMA table_info($table)", null).use { c ->
        var found = false
        while (c.moveToNext()) if (c.str("name") == column) { found = true; break }
        found
    }

    private fun Cursor.str(name: String): String = getString(getColumnIndexOrThrow(name)) ?: ""
    private fun Cursor.long(name: String): Long = getLong(getColumnIndexOrThrow(name))
    private fun Cursor.int(name: String): Int = getInt(getColumnIndexOrThrow(name))
    private fun Cursor.dbl(name: String): Double = getDouble(getColumnIndexOrThrow(name))

    companion object {
        const val DB_NAME = "tianxian_fruit.db"
        const val DB_VERSION = 3
    }
}

private fun roundMoney(v: Double): Double = round(v * 100.0) / 100.0

fun currentMonthRange(today: LocalDate = LocalDate.now()): Pair<String, String> =
    today.withDayOfMonth(1).toString() to today.withDayOfMonth(today.lengthOfMonth()).toString()
