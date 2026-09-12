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
    val expensePayerId: Long,
    val expensePayerName: String,
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

data class CashSettlementPartnerRecord(
    val id: Long,
    val settlementId: Long,
    val date: String,
    val partnerId: Long,
    val partnerName: String,
    val purchasePaid: Double,
    val expensePaid: Double,
    val revenueReceived: Double,
    val profitShare: Double,
    val shouldKeep: Double,
    val balance: Double
)

data class SettlementTransferRecord(
    val id: Long,
    val settlementId: Long,
    val date: String,
    val fromPartnerId: Long,
    val fromPartnerName: String,
    val toPartnerId: Long,
    val toPartnerName: String,
    val amount: Double
)

data class DailyCashSettlementRecord(
    val id: Long,
    val date: String,
    val revenue: Double,
    val purchaseCost: Double,
    val expense: Double,
    val profit: Double,
    val status: Int,
    val createdAt: Long,
    val updatedAt: Long
)

data class CashSettlementBundle(
    val settlement: DailyCashSettlementRecord,
    val partners: List<CashSettlementPartnerRecord>,
    val transfers: List<SettlementTransferRecord>
)

data class CashSettlementResult(
    val success: Boolean,
    val message: String,
    val bundle: CashSettlementBundle? = null
)

data class ProfitSettlementBatchRecord(
    val id: Long,
    val settlementDate: String,
    val periodStart: String,
    val periodEnd: String,
    val totalAmount: Double,
    val note: String,
    val createdAt: Long
)

data class ProfitSettlementItemRecord(
    val id: Long,
    val batchId: Long,
    val profitDate: String,
    val partnerId: Long,
    val partnerName: String,
    val profitAmount: Double,
    val createdAt: Long
)

data class DailyPartnerProfitSettlement(
    val date: String,
    val partnerId: Long,
    val partnerName: String,
    val earnedProfit: Double,
    val settledProfit: Double,
    val pendingProfit: Double,
    val lastSettlementDate: String
)

data class PartnerProfitSettlementSummary(
    val partnerId: Long,
    val partnerName: String,
    val earnedProfit: Double,
    val settledProfit: Double,
    val pendingProfit: Double
)

data class ProfitSettlementCreateResult(
    val success: Boolean,
    val message: String,
    val batchId: Long = -1L,
    val totalAmount: Double = 0.0,
    val itemCount: Int = 0
)

data class StoreDailySaveResult(
    val success: Boolean,
    val message: String,
    val recordId: Long = -1L,
    val profitDistributionInvalidated: Boolean = false,
    val cashSettlementInvalidated: Boolean = false
)


data class FruitAdminRecord(
    val id: Long,
    val name: String,
    val defaultUnit: String,
    val enabled: Boolean
)

data class PurchasePlanLineInput(
    val fruit: FruitOption,
    val quantity: Double,
    val unit: String,
    val remark: String = "",
    val status: Int = 0
)

data class PurchasePlanRecord(
    val id: Long,
    val planDate: String,
    val status: Int,
    val note: String
)

data class PurchasePlanItemRecord(
    val id: Long,
    val planId: Long,
    val fruitId: Long,
    val fruitName: String,
    val quantity: Double,
    val unit: String,
    val remark: String,
    val status: Int
)

data class PurchasePlanDetail(
    val plan: PurchasePlanRecord,
    val items: List<PurchasePlanItemRecord>
)

data class SyncFoundationStatus(
    val bookId: String,
    val bookName: String,
    val deviceId: String,
    val pendingChanges: Int,
    val lastChangeAt: Long
)

data class SyncChangeRecord(
    val id: Long,
    val tableName: String,
    val recordSyncId: String,
    val operation: String,
    val rowVersion: Long,
    val deviceId: String,
    val changedAt: Long
)

class AppDatabase(
    context: Context,
    val dbFileName: String = DB_NAME,
    val ledgerId: String = "local-default",
    val ledgerName: String = "我的账本",
    private val deviceId: String = "legacy-device",
    private val deviceName: String = "Android设备"
) : SQLiteOpenHelper(
    context,
    dbFileName,
    null,
    DB_VERSION
) {
    override fun onConfigure(
        db: SQLiteDatabase
    ) {
        super.onConfigure(db)
        db.execSQL(
            "PRAGMA recursive_triggers=OFF"
        )
    }

    override fun onCreate(db: SQLiteDatabase) {
        createFruitTable(db)
        createStoreTable(db)
        createV2Tables(db)
        createV3Tables(db)
        createV4Tables(db)
        createV5Tables(db)
        migrateV5ToV6(db)
        createV7Tables(db)
        createV8SyncFoundation(
            db,
            initialData = false
        )
        seedFruits(db)
        seedPartners(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) migrateV1ToV2(db)
        if (oldVersion < 3) createV3Tables(db)
        if (oldVersion < 4) createV4Tables(db)
        if (oldVersion < 5) createV5Tables(db)
        if (oldVersion < 6) migrateV5ToV6(db)
        if (oldVersion < 7) createV7Tables(db)
        if (oldVersion < 8) {
            createV8SyncFoundation(
                db,
                initialData = true
            )
        }
    }

    override fun onOpen(
        db: SQLiteDatabase
    ) {
        super.onOpen(db)

        if (
            tableExists(
                db,
                "sync_context"
            )
        ) {
            ensureSyncContext(db)
            ensureBookMeta(db)
        }
    }

    private fun createFruitTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS fruit(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL UNIQUE,
                default_unit TEXT NOT NULL DEFAULT '件',
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
                status INTEGER NOT NULL DEFAULT 0,
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

    private fun createV4Tables(db: SQLiteDatabase) {
        if (!columnExists(db, "store_daily_record", "expense_payer_id")) {
            db.execSQL("ALTER TABLE store_daily_record ADD COLUMN expense_payer_id INTEGER NOT NULL DEFAULT 0")
        }
        if (!columnExists(db, "store_daily_record", "expense_payer_name")) {
            db.execSQL("ALTER TABLE store_daily_record ADD COLUMN expense_payer_name TEXT NOT NULL DEFAULT '未指定'")
        }

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS daily_cash_settlement(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                date TEXT NOT NULL,
                revenue REAL NOT NULL DEFAULT 0,
                purchase_cost REAL NOT NULL DEFAULT 0,
                expense REAL NOT NULL DEFAULT 0,
                profit REAL NOT NULL DEFAULT 0,
                status INTEGER NOT NULL DEFAULT 0,
                deleted INTEGER NOT NULL DEFAULT 0,
                sync_id TEXT NOT NULL,
                sync_status INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_cash_settlement_date ON daily_cash_settlement(date)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS settlement_partner(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                settlement_id INTEGER NOT NULL,
                date TEXT NOT NULL,
                partner_id INTEGER NOT NULL,
                partner_name TEXT NOT NULL,
                purchase_paid REAL NOT NULL DEFAULT 0,
                expense_paid REAL NOT NULL DEFAULT 0,
                revenue_received REAL NOT NULL DEFAULT 0,
                profit_share REAL NOT NULL DEFAULT 0,
                should_keep REAL NOT NULL DEFAULT 0,
                balance REAL NOT NULL DEFAULT 0,
                deleted INTEGER NOT NULL DEFAULT 0,
                sync_id TEXT NOT NULL,
                sync_status INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_settlement_partner_sid ON settlement_partner(settlement_id)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS settlement_transfer(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                settlement_id INTEGER NOT NULL,
                date TEXT NOT NULL,
                from_partner_id INTEGER NOT NULL,
                from_partner_name TEXT NOT NULL,
                to_partner_id INTEGER NOT NULL,
                to_partner_name TEXT NOT NULL,
                amount REAL NOT NULL DEFAULT 0,
                deleted INTEGER NOT NULL DEFAULT 0,
                sync_id TEXT NOT NULL,
                sync_status INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_settlement_transfer_sid ON settlement_transfer(settlement_id)")
    }


    private fun createV5Tables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS purchase_plan(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                plan_date TEXT NOT NULL UNIQUE,
                status INTEGER NOT NULL DEFAULT 0,
                note TEXT NOT NULL DEFAULT '',
                deleted INTEGER NOT NULL DEFAULT 0,
                sync_id TEXT NOT NULL,
                sync_status INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_purchase_plan_date ON purchase_plan(plan_date)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS purchase_plan_item(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                plan_id INTEGER NOT NULL,
                fruit_id INTEGER NOT NULL,
                fruit_name TEXT NOT NULL,
                quantity REAL NOT NULL DEFAULT 0,
                unit TEXT NOT NULL DEFAULT '件',
                remark TEXT NOT NULL DEFAULT '',
                deleted INTEGER NOT NULL DEFAULT 0,
                sync_id TEXT NOT NULL,
                sync_status INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_purchase_plan_item_pid ON purchase_plan_item(plan_id)")
    }

    private fun migrateV5ToV6(db: SQLiteDatabase) {
        if (!columnExists(db, "purchase_plan_item", "status")) {
            db.execSQL(
                "ALTER TABLE purchase_plan_item ADD COLUMN status INTEGER NOT NULL DEFAULT 0"
            )
        }
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

    private fun createV7Tables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS profit_settlement_batch(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                settlement_date TEXT NOT NULL,
                period_start TEXT NOT NULL,
                period_end TEXT NOT NULL,
                total_amount REAL NOT NULL DEFAULT 0,
                note TEXT NOT NULL DEFAULT '',
                deleted INTEGER NOT NULL DEFAULT 0,
                sync_id TEXT NOT NULL,
                sync_status INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_profit_settlement_batch_date " +
                "ON profit_settlement_batch(settlement_date)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_profit_settlement_batch_period " +
                "ON profit_settlement_batch(period_start,period_end)"
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS profit_settlement_item(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                batch_id INTEGER NOT NULL,
                profit_date TEXT NOT NULL,
                partner_id INTEGER NOT NULL,
                partner_name TEXT NOT NULL,
                profit_amount REAL NOT NULL DEFAULT 0,
                deleted INTEGER NOT NULL DEFAULT 0,
                sync_id TEXT NOT NULL,
                sync_status INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_profit_settlement_item_batch " +
                "ON profit_settlement_item(batch_id)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_profit_settlement_item_date_partner " +
                "ON profit_settlement_item(profit_date,partner_id)"
        )
    }

    private fun createV8SyncFoundation(
        db: SQLiteDatabase,
        initialData: Boolean
    ) {
        val now =
            System.currentTimeMillis()

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS sync_context(
                id INTEGER PRIMARY KEY CHECK(id=1),
                book_id TEXT NOT NULL,
                book_name TEXT NOT NULL,
                device_id TEXT NOT NULL,
                device_name TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS book_meta(
                book_id TEXT PRIMARY KEY,
                book_name TEXT NOT NULL,
                owner_device_id TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS sync_change_log(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                table_name TEXT NOT NULL,
                record_sync_id TEXT NOT NULL,
                operation TEXT NOT NULL,
                row_version INTEGER NOT NULL,
                device_id TEXT NOT NULL,
                changed_at INTEGER NOT NULL,
                uploaded INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )

        db.execSQL(
            "CREATE INDEX IF NOT EXISTS " +
                "idx_sync_change_pending " +
                "ON sync_change_log(" +
                "uploaded,changed_at)"
        )

        ensureSyncContext(db)
        ensureBookMeta(db)

        syncableTables().forEach {
            table ->
            if (
                !columnExists(
                    db,
                    table,
                    "row_version"
                )
            ) {
                db.execSQL(
                    "ALTER TABLE $table " +
                        "ADD COLUMN row_version " +
                        "INTEGER NOT NULL DEFAULT 1"
                )
            }

            if (
                !columnExists(
                    db,
                    table,
                    "modified_by"
                )
            ) {
                db.execSQL(
                    "ALTER TABLE $table " +
                        "ADD COLUMN modified_by " +
                        "TEXT NOT NULL DEFAULT ''"
                )
            }

            if (
                !columnExists(
                    db,
                    table,
                    "deleted"
                )
            ) {
                db.execSQL(
                    "ALTER TABLE $table " +
                        "ADD COLUMN deleted " +
                        "INTEGER NOT NULL DEFAULT 0"
                )
            }

            ensureSyncIds(
                db,
                table
            )

            db.execSQL(
                "CREATE INDEX IF NOT EXISTS " +
                    "idx_${table}_sync_id " +
                    "ON $table(sync_id)"
            )
        }

        if (initialData) {
            syncableTables().forEach {
                table ->
                db.execSQL(
                    "UPDATE $table SET " +
                        "row_version=" +
                        "CASE WHEN row_version<1 " +
                        "THEN 1 ELSE row_version END," +
                        "modified_by=?," +
                        "sync_status=2," +
                        "updated_at=" +
                        "CASE WHEN updated_at<=0 " +
                        "THEN ? ELSE updated_at END",
                    arrayOf(
                        deviceId,
                        now
                    )
                )

                db.execSQL(
                    """
                    INSERT INTO sync_change_log(
                        table_name,
                        record_sync_id,
                        operation,
                        row_version,
                        device_id,
                        changed_at,
                        uploaded
                    )
                    SELECT
                        ?,
                        sync_id,
                        CASE
                            WHEN deleted=1
                                THEN 'DELETE'
                            ELSE 'UPSERT'
                        END,
                        row_version,
                        ?,
                        updated_at,
                        0
                    FROM $table
                    WHERE sync_id<>''
                    """.trimIndent(),
                    arrayOf(
                        table,
                        deviceId
                    )
                )
            }
        }

        createSyncTriggers(db)
    }

    private fun ensureSyncContext(
        db: SQLiteDatabase
    ) {
        val now =
            System.currentTimeMillis()

        db.execSQL(
            """
            INSERT OR REPLACE INTO sync_context(
                id,
                book_id,
                book_name,
                device_id,
                device_name,
                updated_at
            ) VALUES(1,?,?,?,?,?)
            """.trimIndent(),
            arrayOf(
                ledgerId,
                ledgerName,
                deviceId,
                deviceName,
                now
            )
        )
    }

    private fun ensureBookMeta(
        db: SQLiteDatabase
    ) {
        val now =
            System.currentTimeMillis()

        val exists =
            db.rawQuery(
                "SELECT book_id " +
                    "FROM book_meta " +
                    "WHERE book_id=? LIMIT 1",
                arrayOf(ledgerId)
            ).use {
                it.moveToFirst()
            }

        if (!exists) {
            db.execSQL(
                """
                INSERT INTO book_meta(
                    book_id,
                    book_name,
                    owner_device_id,
                    created_at,
                    updated_at
                ) VALUES(?,?,?,?,?)
                """.trimIndent(),
                arrayOf(
                    ledgerId,
                    ledgerName,
                    deviceId,
                    now,
                    now
                )
            )
        } else {
            db.execSQL(
                """
                UPDATE book_meta
                SET book_name=?,
                    updated_at=?
                WHERE book_id=?
                """.trimIndent(),
                arrayOf(
                    ledgerName,
                    now,
                    ledgerId
                )
            )
        }
    }

    private fun ensureSyncIds(
        db: SQLiteDatabase,
        table: String
    ) {
        db.rawQuery(
            "SELECT id,sync_id " +
                "FROM $table " +
                "WHERE sync_id IS NULL " +
                "OR sync_id=''",
            null
        ).use {
            c ->
            while (c.moveToNext()) {
                db.update(
                    table,
                    ContentValues().apply {
                        put(
                            "sync_id",
                            UUID.randomUUID()
                                .toString()
                        )
                    },
                    "id=?",
                    arrayOf(
                        c.long("id")
                            .toString()
                    )
                )
            }
        }
    }

    private fun createSyncTriggers(
        db: SQLiteDatabase
    ) {
        syncableTables().forEach {
            table ->
            db.execSQL(
                "DROP TRIGGER IF EXISTS " +
                    "sync_${table}_ai"
            )
            db.execSQL(
                "DROP TRIGGER IF EXISTS " +
                    "sync_${table}_au"
            )

            db.execSQL(
                """
                CREATE TRIGGER sync_${table}_ai
                AFTER INSERT ON $table
                BEGIN
                    UPDATE $table
                    SET
                        row_version=
                            CASE
                                WHEN NEW.row_version<1
                                    THEN 1
                                ELSE NEW.row_version
                            END,
                        modified_by=(
                            SELECT device_id
                            FROM sync_context
                            WHERE id=1
                        ),
                        sync_status=2,
                        updated_at=
                            CAST(
                                strftime('%s','now')
                                AS INTEGER
                            ) * 1000
                    WHERE id=NEW.id;

                    INSERT INTO sync_change_log(
                        table_name,
                        record_sync_id,
                        operation,
                        row_version,
                        device_id,
                        changed_at,
                        uploaded
                    )
                    SELECT
                        '$table',
                        sync_id,
                        CASE
                            WHEN deleted=1
                                THEN 'DELETE'
                            ELSE 'UPSERT'
                        END,
                        row_version,
                        modified_by,
                        updated_at,
                        0
                    FROM $table
                    WHERE id=NEW.id;
                END
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TRIGGER sync_${table}_au
                AFTER UPDATE ON $table
                WHEN NEW.row_version=OLD.row_version
                BEGIN
                    UPDATE $table
                    SET
                        row_version=
                            OLD.row_version + 1,
                        modified_by=(
                            SELECT device_id
                            FROM sync_context
                            WHERE id=1
                        ),
                        sync_status=2,
                        updated_at=
                            CAST(
                                strftime('%s','now')
                                AS INTEGER
                            ) * 1000
                    WHERE id=NEW.id;

                    INSERT INTO sync_change_log(
                        table_name,
                        record_sync_id,
                        operation,
                        row_version,
                        device_id,
                        changed_at,
                        uploaded
                    )
                    SELECT
                        '$table',
                        sync_id,
                        CASE
                            WHEN deleted=1
                                THEN 'DELETE'
                            ELSE 'UPSERT'
                        END,
                        row_version,
                        modified_by,
                        updated_at,
                        0
                    FROM $table
                    WHERE id=NEW.id;
                END
                """.trimIndent()
            )
        }
    }

    private fun syncableTables(): List<String> =
        listOf(
            "fruit",
            "store",
            "partner",
            "purchase_plan",
            "purchase_plan_item",
            "purchase_order",
            "purchase_item",
            "store_daily_record",
            "profit_rule",
            "profit_distribution",
            "daily_cash_settlement",
            "settlement_partner",
            "settlement_transfer",
            "profit_settlement_batch",
            "profit_settlement_item"
        )

    fun getSyncFoundationStatus():
        SyncFoundationStatus {
        val pending =
            readableDatabase.rawQuery(
                "SELECT COUNT(*) AS c " +
                    "FROM sync_change_log " +
                    "WHERE uploaded=0",
                null
            ).use {
                if (it.moveToFirst()) {
                    it.int("c")
                } else {
                    0
                }
            }

        val lastChange =
            readableDatabase.rawQuery(
                "SELECT COALESCE(" +
                    "MAX(changed_at),0) AS t " +
                    "FROM sync_change_log",
                null
            ).use {
                if (it.moveToFirst()) {
                    it.long("t")
                } else {
                    0L
                }
            }

        return SyncFoundationStatus(
            bookId = ledgerId,
            bookName = ledgerName,
            deviceId = deviceId,
            pendingChanges = pending,
            lastChangeAt = lastChange
        )
    }

    fun getPendingSyncChanges(
        limit: Int = 200
    ): List<SyncChangeRecord> =
        readableDatabase.rawQuery(
            """
            SELECT
                id,
                table_name,
                record_sync_id,
                operation,
                row_version,
                device_id,
                changed_at
            FROM sync_change_log
            WHERE uploaded=0
            ORDER BY id
            LIMIT ?
            """.trimIndent(),
            arrayOf(
                limit.toString()
            )
        ).use {
            c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        SyncChangeRecord(
                            id =
                                c.long("id"),
                            tableName =
                                c.str(
                                    "table_name"
                                ),
                            recordSyncId =
                                c.str(
                                    "record_sync_id"
                                ),
                            operation =
                                c.str(
                                    "operation"
                                ),
                            rowVersion =
                                c.long(
                                    "row_version"
                                ),
                            deviceId =
                                c.str(
                                    "device_id"
                                ),
                            changedAt =
                                c.long(
                                    "changed_at"
                                )
                        )
                    )
                }
            }
        }

    fun markSyncChangesUploaded(
        ids: List<Long>
    ) {
        if (ids.isEmpty()) return

        val placeholders =
            ids.joinToString(",") {
                "?"
            }

        writableDatabase.execSQL(
            "UPDATE sync_change_log " +
                "SET uploaded=1 " +
                "WHERE id IN(" +
                placeholders +
                ")",
            ids.toTypedArray()
        )
    }

    fun updateLedgerMetaName(
        name: String
    ) {
        val clean = name.trim()
        if (clean.isBlank()) return

        writableDatabase.execSQL(
            """
            UPDATE book_meta
            SET book_name=?,
                updated_at=?
            WHERE book_id=?
            """.trimIndent(),
            arrayOf(
                clean,
                System.currentTimeMillis(),
                ledgerId
            )
        )

        writableDatabase.execSQL(
            """
            UPDATE sync_context
            SET book_name=?,
                updated_at=?
            WHERE id=1
            """.trimIndent(),
            arrayOf(
                clean,
                System.currentTimeMillis()
            )
        )
    }

    private fun seedFruits(db: SQLiteDatabase) {
        listOf("巨峰葡萄", "阳光玫瑰", "蓝莓", "草莓", "西瓜", "芒果", "荔枝").forEach {
            val values = baseSyncValues().apply {
                put("name", it)
                put("default_unit", "件")
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

        val existing = readableDatabase.rawQuery(
            "SELECT id,enabled FROM fruit WHERE name=? LIMIT 1",
            arrayOf(clean)
        ).use { c ->
            if (c.moveToFirst()) c.long("id") to c.int("enabled") else null
        }

        if (existing != null) {
            val id = existing.first
            writableDatabase.update(
                "fruit",
                ContentValues().apply {
                    put("enabled", 1)
                    put("default_unit", defaultUnit)
                    put("sync_status", 2)
                    put("updated_at", System.currentTimeMillis())
                },
                "id=?",
                arrayOf(id.toString())
            )
            return id
        }

        return writableDatabase.insert("fruit", null, baseSyncValues().apply {
            put("name", clean)
            put("default_unit", defaultUnit)
            put("enabled", 1)
        })
    }

    fun getAllFruits(includeDisabled: Boolean = false): List<FruitOption> =
        readableDatabase.rawQuery(
            if (includeDisabled)
                "SELECT id,name,default_unit FROM fruit ORDER BY id"
            else
                "SELECT id,name,default_unit FROM fruit WHERE enabled=1 ORDER BY id",
            null
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(FruitOption(c.long("id"), c.str("name"), c.str("default_unit")))
                }
            }
        }

    fun getFruitAdminRecords(): List<FruitAdminRecord> = readableDatabase.rawQuery(
        "SELECT id,name,default_unit,enabled FROM fruit ORDER BY enabled DESC,id",
        null
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                add(
                    FruitAdminRecord(
                        c.long("id"),
                        c.str("name"),
                        c.str("default_unit"),
                        c.int("enabled") == 1
                    )
                )
            }
        }
    }

    fun updateFruit(id: Long, name: String, defaultUnit: String): Boolean {
        val clean = name.trim()
        if (clean.isBlank()) return false
        return writableDatabase.update(
            "fruit",
            ContentValues().apply {
                put("name", clean)
                put("default_unit", defaultUnit)
                put("updated_at", System.currentTimeMillis())
                put("sync_status", 2)
            },
            "id=?",
            arrayOf(id.toString())
        ) > 0
    }

    // 商品删除采用停用，不删除历史账。
    fun disableFruit(id: Long): Boolean {
        return writableDatabase.update(
            "fruit",
            ContentValues().apply {
                put("enabled", 0)
                put("sync_status", 2)
                put("updated_at", System.currentTimeMillis())
            },
            "id=?",
            arrayOf(id.toString())
        ) > 0
    }

    fun restoreFruit(id: Long): Boolean {
        return writableDatabase.update(
            "fruit",
            ContentValues().apply {
                put("enabled", 1)
                put("sync_status", 2)
                put("updated_at", System.currentTimeMillis())
            },
            "id=?",
            arrayOf(id.toString())
        ) > 0
    }

    fun getStores(): List<StoreOption> = readableDatabase.rawQuery(
        "SELECT id,name,address FROM store WHERE enabled=1 AND deleted=0 ORDER BY id DESC", null
    ).use { c -> buildList { while (c.moveToNext()) add(StoreOption(c.long("id"), c.str("name"), c.str("address"))) } }

    fun getStoreById(id: Long): StoreOption? = readableDatabase.rawQuery(
        "SELECT id,name,address FROM store WHERE id=? AND enabled=1 AND deleted=0 LIMIT 1",
        arrayOf(id.toString())
    ).use { c ->
        if (c.moveToFirst()) StoreOption(c.long("id"), c.str("name"), c.str("address")) else null
    }

    fun getStoreByIdIncludingDeleted(id: Long): StoreOption? = readableDatabase.rawQuery(
        "SELECT id,name,address FROM store WHERE id=? LIMIT 1",
        arrayOf(id.toString())
    ).use { c ->
        if (c.moveToFirst()) StoreOption(c.long("id"), c.str("name"), c.str("address")) else null
    }

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

    fun getPartnerById(id: Long): PartnerOption? = readableDatabase.rawQuery(
        "SELECT id,name FROM partner WHERE id=? AND enabled=1 AND deleted=0 LIMIT 1",
        arrayOf(id.toString())
    ).use { c ->
        if (c.moveToFirst()) PartnerOption(c.long("id"), c.str("name")) else null
    }

    fun getPartnerByIdIncludingDeleted(id: Long): PartnerOption? = readableDatabase.rawQuery(
        "SELECT id,name FROM partner WHERE id=? LIMIT 1",
        arrayOf(id.toString())
    ).use { c ->
        if (c.moveToFirst()) PartnerOption(c.long("id"), c.str("name")) else null
    }

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
        val activeBuyer = getPartnerById(buyer.id) ?: return -1
        val db = writableDatabase
        db.beginTransaction()
        try {
            val total = lines.sumOf { it.totalCost }
            val orderId = db.insert("purchase_order", null, baseSyncValues().apply {
                put("date", date)
                put("buyer_id", activeBuyer.id); put("buyer_name", activeBuyer.name)
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

    fun updatePurchaseOrder(
        id: Long,
        date: String,
        buyer: PartnerOption,
        lines: List<PurchaseLineInput>,
        remark: String
    ): Boolean {
        if (lines.isEmpty()) return false

        val oldBuyer = readableDatabase.rawQuery(
            "SELECT buyer_id,buyer_name FROM purchase_order WHERE id=? AND deleted=0 LIMIT 1",
            arrayOf(id.toString())
        ).use { c ->
            if (c.moveToFirst()) PartnerOption(c.long("buyer_id"), c.str("buyer_name")) else null
        } ?: return false

        val activeBuyer = getPartnerById(buyer.id)
        val resolvedBuyer = when {
            activeBuyer != null -> activeBuyer
            buyer.id == oldBuyer.id -> oldBuyer
            else -> return false
        }

        val db = writableDatabase
        db.beginTransaction()
        try {
            val now = System.currentTimeMillis()
            val total = lines.sumOf { it.totalCost }
            val changed = db.update("purchase_order", ContentValues().apply {
                put("date", date)
                put("buyer_id", resolvedBuyer.id)
                put("buyer_name", resolvedBuyer.name)
                put("store_id", 0)
                put("store_name", "共用货品")
                put("total_cost", total)
                put("remark", remark.trim())
                put("sync_status", 2)
                put("updated_at", now)
            }, "id=? AND deleted=0", arrayOf(id.toString()))
            if (changed <= 0) return false

            db.update("purchase_item", ContentValues().apply {
                put("deleted", 1)
                put("sync_status", 2)
                put("updated_at", now)
            }, "order_id=? AND deleted=0", arrayOf(id.toString()))

            lines.forEach { line ->
                val price = if (line.quantity > 0) line.totalCost / line.quantity else 0.0
                db.insert("purchase_item", null, baseSyncValues().apply {
                    put("order_id", id)
                    put("fruit_id", line.fruit.id)
                    put("fruit_name", line.fruit.name)
                    put("unit", line.unit)
                    put("quantity", line.quantity)
                    put("total_cost", line.totalCost)
                    put("unit_price", price)
                    put("deleted", 0)
                })
            }
            db.setTransactionSuccessful()
            return true
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

    fun getPurchasePlan(date: String): PurchasePlanDetail? {
        val plan = readableDatabase.rawQuery(
            "SELECT * FROM purchase_plan WHERE plan_date=? AND deleted=0 LIMIT 1",
            arrayOf(date)
        ).use { c ->
            if (!c.moveToFirst()) null
            else PurchasePlanRecord(
                c.long("id"),
                c.str("plan_date"),
                c.int("status"),
                c.str("note")
            )
        } ?: return null

        val items = readableDatabase.rawQuery(
            "SELECT * FROM purchase_plan_item WHERE plan_id=? AND deleted=0 ORDER BY id",
            arrayOf(plan.id.toString())
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        PurchasePlanItemRecord(
                            c.long("id"),
                            c.long("plan_id"),
                            c.long("fruit_id"),
                            c.str("fruit_name"),
                            c.dbl("quantity"),
                            c.str("unit"),
                            c.str("remark"),
                            c.int("status")
                        )
                    )
                }
            }
        }
        return PurchasePlanDetail(plan, items)
    }

    fun savePurchasePlan(
        date: String,
        lines: List<PurchasePlanLineInput>,
        note: String = ""
    ): Long {
        if (lines.isEmpty()) return -1
        val db = writableDatabase
        val now = System.currentTimeMillis()

        db.beginTransaction()
        try {
            val existingId = db.rawQuery(
                "SELECT id FROM purchase_plan WHERE plan_date=? LIMIT 1",
                arrayOf(date)
            ).use { c -> if (c.moveToFirst()) c.long("id") else null }

            val planId = if (existingId == null) {
                db.insert("purchase_plan", null, baseSyncValues().apply {
                    put("plan_date", date)
                    put("status", 0)
                    put("note", note.trim())
                    put("deleted", 0)
                })
            } else {
                db.update(
                    "purchase_plan",
                    ContentValues().apply {
                        put("status", 0)
                        put("note", note.trim())
                        put("deleted", 0)
                        put("sync_status", 2)
                        put("updated_at", now)
                    },
                    "id=?",
                    arrayOf(existingId.toString())
                )
                existingId
            }

            if (planId <= 0) return -1

            db.update(
                "purchase_plan_item",
                ContentValues().apply {
                    put("deleted", 1)
                    put("sync_status", 2)
                    put("updated_at", now)
                },
                "plan_id=? AND deleted=0",
                arrayOf(planId.toString())
            )

            lines.forEach { line ->
                db.insert("purchase_plan_item", null, baseSyncValues().apply {
                    put("plan_id", planId)
                    put("fruit_id", line.fruit.id)
                    put("fruit_name", line.fruit.name)
                    put("quantity", line.quantity)
                    put("unit", line.unit)
                    put("remark", line.remark.trim())
                    put("status", line.status.coerceIn(0, 2))
                    put("deleted", 0)
                })
            }

            refreshPurchasePlanStatus(db, planId)
            db.setTransactionSuccessful()
            return planId
        } finally {
            db.endTransaction()
        }
    }

    private fun refreshPurchasePlanStatus(db: SQLiteDatabase, planId: Long) {
        val counts = db.rawQuery(
            """
            SELECT
                COUNT(*) AS total,
                SUM(CASE WHEN status=0 THEN 1 ELSE 0 END) AS pending,
                SUM(CASE WHEN status=1 THEN 1 ELSE 0 END) AS purchased,
                SUM(CASE WHEN status=2 THEN 1 ELSE 0 END) AS cancelled
            FROM purchase_plan_item
            WHERE plan_id=? AND deleted=0
            """.trimIndent(),
            arrayOf(planId.toString())
        ).use { c ->
            if (c.moveToFirst()) {
                intArrayOf(
                    c.int("total"),
                    c.int("pending"),
                    c.int("purchased"),
                    c.int("cancelled")
                )
            } else intArrayOf(0, 0, 0, 0)
        }

        val total = counts[0]
        val pending = counts[1]
        val purchased = counts[2]
        val cancelled = counts[3]

        val aggregateStatus = when {
            total == 0 -> 0
            cancelled == total -> 2
            pending == 0 && purchased > 0 -> 1
            else -> 0
        }

        db.update(
            "purchase_plan",
            ContentValues().apply {
                put("status", aggregateStatus)
                put("sync_status", 2)
                put("updated_at", System.currentTimeMillis())
            },
            "id=?",
            arrayOf(planId.toString())
        )
    }

    fun updatePurchasePlanItemStatus(itemId: Long, status: Int): Boolean {
        if (status !in 0..2) return false
        val db = writableDatabase
        val planId = db.rawQuery(
            "SELECT plan_id FROM purchase_plan_item WHERE id=? AND deleted=0 LIMIT 1",
            arrayOf(itemId.toString())
        ).use { c -> if (c.moveToFirst()) c.long("plan_id") else null } ?: return false

        db.beginTransaction()
        try {
            val changed = db.update(
                "purchase_plan_item",
                ContentValues().apply {
                    put("status", status)
                    put("sync_status", 2)
                    put("updated_at", System.currentTimeMillis())
                },
                "id=? AND deleted=0",
                arrayOf(itemId.toString())
            )
            if (changed <= 0) return false
            refreshPurchasePlanStatus(db, planId)
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    fun updatePurchasePlanStatus(planId: Long, status: Int): Boolean {
        if (status !in 0..2) return false
        val db = writableDatabase
        db.beginTransaction()
        try {
            val now = System.currentTimeMillis()
            val changed = db.update(
                "purchase_plan",
                ContentValues().apply {
                    put("status", status)
                    put("sync_status", 2)
                    put("updated_at", now)
                },
                "id=? AND deleted=0",
                arrayOf(planId.toString())
            )
            db.update(
                "purchase_plan_item",
                ContentValues().apply {
                    put("status", status)
                    put("sync_status", 2)
                    put("updated_at", now)
                },
                "plan_id=? AND deleted=0",
                arrayOf(planId.toString())
            )
            db.setTransactionSuccessful()
            return changed > 0
        } finally {
            db.endTransaction()
        }
    }

    fun deletePurchasePlan(planId: Long): Boolean {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            val changed = db.update(
                "purchase_plan",
                ContentValues().apply {
                    put("deleted", 1)
                    put("sync_status", 2)
                    put("updated_at", now)
                },
                "id=?",
                arrayOf(planId.toString())
            )
            db.update(
                "purchase_plan_item",
                ContentValues().apply {
                    put("deleted", 1)
                    put("sync_status", 2)
                    put("updated_at", now)
                },
                "plan_id=?",
                arrayOf(planId.toString())
            )
            db.setTransactionSuccessful()
            return changed > 0
        } finally {
            db.endTransaction()
        }
    }

    fun getRecentPurchasePlans(limit: Int = 30): List<PurchasePlanDetail> =
        readableDatabase.rawQuery(
            "SELECT plan_date FROM purchase_plan WHERE deleted=0 ORDER BY plan_date DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    getPurchasePlan(c.str("plan_date"))?.let { add(it) }
                }
            }
        }

    fun getPurchasePlansBetween(start: String?, end: String?): List<PurchasePlanDetail> {
        val where = if (start != null && end != null) "AND plan_date>=? AND plan_date<=?" else ""
        val args = if (start != null && end != null) arrayOf(start, end) else emptyArray()
        return readableDatabase.rawQuery(
            "SELECT plan_date FROM purchase_plan WHERE deleted=0 $where ORDER BY plan_date DESC",
            args
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    getPurchasePlan(c.str("plan_date"))?.let { add(it) }
                }
            }
        }
    }

    fun getPurchaseOrders(limit: Int = 100): List<PurchaseOrderDetail> {
        val orders = readableDatabase.rawQuery(
            "SELECT * FROM purchase_order WHERE deleted=0 ORDER BY date DESC,id DESC LIMIT ?", arrayOf(limit.toString())
        ).use { c -> buildList { while (c.moveToNext()) add(order(c)) } }
        return orders.map { PurchaseOrderDetail(it, getPurchaseItems(it.id)) }
    }

    fun getPurchaseOrdersBetween(start: String?, end: String?): List<PurchaseOrderDetail> {
        val where = if (start != null && end != null) "AND date>=? AND date<=?" else ""
        val args = if (start != null && end != null) arrayOf(start, end) else emptyArray()
        val orders = readableDatabase.rawQuery(
            "SELECT * FROM purchase_order WHERE deleted=0 $where ORDER BY date DESC,id DESC",
            args
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

    fun getFruitPriceHistoryBetween(
        fruitId: Long,
        start: String?,
        end: String?,
        limit: Int = 100
    ): List<PriceHistoryRecord> {
        val rangeWhere =
            if (start != null && end != null) "AND po.date>=? AND po.date<=?" else ""
        val args =
            if (start != null && end != null) {
                arrayOf(fruitId.toString(), start, end, limit.toString())
            } else {
                arrayOf(fruitId.toString(), limit.toString())
            }

        return readableDatabase.rawQuery(
            """
            SELECT po.date,pi.fruit_name,pi.unit,pi.quantity,pi.total_cost,pi.unit_price,
                   po.buyer_name,po.store_name
            FROM purchase_item pi
            JOIN purchase_order po ON po.id=pi.order_id
            WHERE pi.fruit_id=? AND pi.deleted=0 AND po.deleted=0 $rangeWhere
            ORDER BY po.date DESC,pi.id DESC
            LIMIT ?
            """.trimIndent(),
            args
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        PriceHistoryRecord(
                            c.str("date"),
                            c.str("fruit_name"),
                            c.str("unit"),
                            c.dbl("quantity"),
                            c.dbl("total_cost"),
                            c.dbl("unit_price"),
                            c.str("buyer_name"),
                            c.str("store_name")
                        )
                    )
                }
            }
        }
    }

    fun getStoreDailyRecord(date: String, storeId: Long): StoreDailyRecord? = readableDatabase.rawQuery(
        "SELECT * FROM store_daily_record WHERE date=? AND store_id=? AND deleted=0 LIMIT 1", arrayOf(date, storeId.toString())
    ).use { c -> if (c.moveToFirst()) dailyRecord(c) else null }

    fun getPreviousClosingStock(storeId: Long, date: String): Double = readableDatabase.rawQuery(
        "SELECT stock_left_value FROM store_daily_record WHERE store_id=? AND date<? AND deleted=0 ORDER BY date DESC LIMIT 1",
        arrayOf(storeId.toString(), date)
    ).use { c -> if (c.moveToFirst()) c.dbl("stock_left_value") else 0.0 }

    fun getStoreDailyRecordById(id: Long): StoreDailyRecord? = readableDatabase.rawQuery(
        "SELECT * FROM store_daily_record WHERE id=? AND deleted=0 LIMIT 1",
        arrayOf(id.toString())
    ).use { c -> if (c.moveToFirst()) dailyRecord(c) else null }

    private fun invalidateCashSettlementForDate(db: SQLiteDatabase, date: String): Boolean {
        val now = System.currentTimeMillis()
        val ids = db.rawQuery(
            "SELECT id FROM daily_cash_settlement WHERE date=? AND deleted=0",
            arrayOf(date)
        ).use { c -> buildList { while (c.moveToNext()) add(c.long("id")) } }

        if (ids.isEmpty()) return false

        ids.forEach { settlementId ->
            db.update("daily_cash_settlement", ContentValues().apply {
                put("deleted", 1)
                put("sync_status", 2)
                put("updated_at", now)
            }, "id=?", arrayOf(settlementId.toString()))

            db.update("settlement_partner", ContentValues().apply {
                put("deleted", 1)
                put("sync_status", 2)
                put("updated_at", now)
            }, "settlement_id=?", arrayOf(settlementId.toString()))

            db.update("settlement_transfer", ContentValues().apply {
                put("deleted", 1)
                put("sync_status", 2)
                put("updated_at", now)
            }, "settlement_id=?", arrayOf(settlementId.toString()))
        }
        return true
    }

    private fun invalidateProfitDistributionForDate(db: SQLiteDatabase, date: String): Boolean {
        val now = System.currentTimeMillis()
        val count = db.update("profit_distribution", ContentValues().apply {
            put("deleted", 1)
            put("sync_status", 2)
            put("updated_at", now)
        }, "date=? AND deleted=0", arrayOf(date))
        return count > 0
    }

    fun saveStoreDailyRecord(
        recordId: Long? = null,
        date: String,
        store: StoreOption,
        wechat: Double,
        wechatCollector: PartnerOption?,
        alipay: Double,
        alipayCollector: PartnerOption?,
        cash: Double,
        cashCollector: PartnerOption?,
        expense: Double,
        expensePayer: PartnerOption?,
        openingStock: Double,
        closingStock: Double,
        newCustomer: Int,
        oldCustomer: Int
    ): StoreDailySaveResult {
        val editingOld = recordId?.let { getStoreDailyRecordById(it) }

        val activeStore = getStoreById(store.id)
        val freshStore = when {
            activeStore != null -> activeStore
            editingOld != null && editingOld.storeId == store.id ->
                StoreOption(editingOld.storeId, editingOld.storeName, "")
            else -> return StoreDailySaveResult(false, "保存失败：所选摊位不存在或已被删除")
        }

        fun freshPartner(p: PartnerOption?, oldId: Long, oldName: String): PartnerOption? {
            if (p == null) return null
            val active = getPartnerById(p.id)
            if (active != null) return active
            if (editingOld != null && p.id == oldId && oldId > 0) {
                return PartnerOption(oldId, oldName)
            }
            return null
        }

        val freshWechatCollector = freshPartner(
            wechatCollector,
            editingOld?.wechatCollectorId ?: 0L,
            editingOld?.wechatCollectorName ?: "未指定"
        )
        val freshAlipayCollector = freshPartner(
            alipayCollector,
            editingOld?.alipayCollectorId ?: 0L,
            editingOld?.alipayCollectorName ?: "未指定"
        )
        val freshCashCollector = freshPartner(
            cashCollector,
            editingOld?.cashCollectorId ?: 0L,
            editingOld?.cashCollectorName ?: "未指定"
        )
        val freshExpensePayer = freshPartner(
            expensePayer,
            editingOld?.expensePayerId ?: 0L,
            editingOld?.expensePayerName ?: "未指定"
        )

        if (wechat > 0 && wechatCollector != null && freshWechatCollector == null) {
            return StoreDailySaveResult(false, "保存失败：微信收款归属已失效，请重新选择")
        }
        if (alipay > 0 && alipayCollector != null && freshAlipayCollector == null) {
            return StoreDailySaveResult(false, "保存失败：支付宝收款归属已失效，请重新选择")
        }
        if (cash > 0 && cashCollector != null && freshCashCollector == null) {
            return StoreDailySaveResult(false, "保存失败：现金收款归属已失效，请重新选择")
        }
        if (expense > 0 && expensePayer != null && freshExpensePayer == null) {
            return StoreDailySaveResult(false, "保存失败：费用付款人已失效，请重新选择")
        }

        val db = writableDatabase
        val revenue = roundMoney(wechat + alipay + cash)
        val profit = roundMoney(revenue + closingStock - openingStock - expense)
        val now = System.currentTimeMillis()

        // A date+store pair is unique. If another active row already occupies it,
        // editing must not overwrite that other row silently.
        val activeConflictId = if (recordId == null) {
            db.rawQuery(
                """
                SELECT id FROM store_daily_record
                WHERE date=? AND store_id=? AND deleted=0
                LIMIT 1
                """.trimIndent(),
                arrayOf(date, freshStore.id.toString())
            ).use { c -> if (c.moveToFirst()) c.long("id") else null }
        } else {
            db.rawQuery(
                """
                SELECT id FROM store_daily_record
                WHERE date=? AND store_id=? AND deleted=0 AND id<>?
                LIMIT 1
                """.trimIndent(),
                arrayOf(date, freshStore.id.toString(), recordId.toString())
            ).use { c -> if (c.moveToFirst()) c.long("id") else null }
        }

        if (activeConflictId != null) {
            return StoreDailySaveResult(
                false,
                "保存失败：$date 的“${freshStore.name}”已经有营业记录，请点击下方该记录的“编辑”"
            )
        }

        // When creating a new entry, an old soft-deleted row with the same
        // date+store would otherwise violate UNIQUE(date, store_id).
        val reusableDeletedId = if (recordId == null) {
            db.rawQuery(
                "SELECT id FROM store_daily_record WHERE date=? AND store_id=? AND deleted=1 LIMIT 1",
                arrayOf(date, freshStore.id.toString())
            ).use { c -> if (c.moveToFirst()) c.long("id") else null }
        } else null

        val targetId = recordId ?: reusableDeletedId
        val oldRecord = when {
            editingOld != null -> editingOld
            targetId != null -> db.rawQuery(
                "SELECT * FROM store_daily_record WHERE id=? LIMIT 1",
                arrayOf(targetId.toString())
            ).use { c -> if (c.moveToFirst()) dailyRecord(c) else null }
            else -> null
        }

        val financialChanged = oldRecord == null ||
            kotlin.math.abs(oldRecord.revenue - revenue) > 0.005 ||
            kotlin.math.abs(oldRecord.expense - expense) > 0.005 ||
            kotlin.math.abs(oldRecord.openingStockValue - openingStock) > 0.005 ||
            kotlin.math.abs(oldRecord.stockLeftValue - closingStock) > 0.005

        val ownershipChanged = oldRecord == null ||
            oldRecord.wechatCollectorId != (freshWechatCollector?.id ?: 0L) ||
            oldRecord.alipayCollectorId != (freshAlipayCollector?.id ?: 0L) ||
            oldRecord.cashCollectorId != (freshCashCollector?.id ?: 0L) ||
            oldRecord.expensePayerId != (freshExpensePayer?.id ?: 0L)

        db.beginTransaction()
        try {
            val values = ContentValues().apply {
                put("date", date)
                put("store_id", freshStore.id)
                put("store_name", freshStore.name)

                put("wechat_income", wechat)
                put("wechat_collector_id", freshWechatCollector?.id ?: 0)
                put("wechat_collector_name", freshWechatCollector?.name ?: "未指定")

                put("alipay_income", alipay)
                put("alipay_collector_id", freshAlipayCollector?.id ?: 0)
                put("alipay_collector_name", freshAlipayCollector?.name ?: "未指定")

                put("cash_income", cash)
                put("cash_collector_id", freshCashCollector?.id ?: 0)
                put("cash_collector_name", freshCashCollector?.name ?: "未指定")

                put("revenue", revenue)
                put("expense", expense)
                put("expense_payer_id", freshExpensePayer?.id ?: 0)
                put("expense_payer_name", freshExpensePayer?.name ?: "未指定")

                put("opening_stock_value", openingStock)
                put("stock_left_value", closingStock)
                put("purchase_cost", 0.0)
                put("profit", profit)
                put("new_customer", newCustomer)
                put("old_customer", oldCustomer)

                put("deleted", 0)
                put("sync_status", if (targetId == null) 0 else 2)
                put("updated_at", now)
            }

            val savedId: Long
            if (targetId == null) {
                values.put("sync_id", UUID.randomUUID().toString())
                values.put("created_at", now)
                savedId = db.insert("store_daily_record", null, values)
                if (savedId <= 0) {
                    return StoreDailySaveResult(false, "保存失败：数据库无法新增营业记录")
                }
            } else {
                val changed = db.update(
                    "store_daily_record",
                    values,
                    "id=?",
                    arrayOf(targetId.toString())
                )
                if (changed <= 0) {
                    return StoreDailySaveResult(false, "保存失败：找不到要修改的营业记录")
                }
                savedId = targetId
            }

            // Derived results must never block source-data edits.
            // If money/profit-driving fields changed, old profit distribution
            // and old cash settlement are stale, so soft-delete them.
            val profitInvalidated = if (financialChanged) {
                invalidateProfitDistributionForDate(db, date)
            } else false

            val cashInvalidated = if (financialChanged || ownershipChanged) {
                invalidateCashSettlementForDate(db, date)
            } else false

            // If an edited record was moved to another date, invalidate old date too.
            var oldDateProfitInvalidated = false
            var oldDateCashInvalidated = false
            if (editingOld != null && editingOld.date != date) {
                oldDateProfitInvalidated = invalidateProfitDistributionForDate(db, editingOld.date)
                oldDateCashInvalidated = invalidateCashSettlementForDate(db, editingOld.date)
            }

            db.setTransactionSuccessful()

            val pInvalid = profitInvalidated || oldDateProfitInvalidated
            val cInvalid = cashInvalidated || oldDateCashInvalidated
            val extra = when {
                pInvalid -> "；营业金额/库存/费用有变化，原利润分配和当日结算已自动作废，请重新生成"
                cInvalid -> "；收款或费用归属有变化，原当日资金结算已自动作废，请重新生成"
                else -> ""
            }
            return StoreDailySaveResult(
                true,
                if (recordId == null) "营业记录已保存$extra" else "营业记录已修改$extra",
                savedId,
                pInvalid,
                cInvalid
            )
        } finally {
            db.endTransaction()
        }
    }

    fun deleteStoreDailyRecord(id: Long): Boolean {
        val old = getStoreDailyRecordById(id) ?: return false
        val db = writableDatabase
        db.beginTransaction()
        try {
            val now = System.currentTimeMillis()
            val changed = db.update("store_daily_record", ContentValues().apply {
                put("deleted", 1)
                put("sync_status", 2)
                put("updated_at", now)
            }, "id=?", arrayOf(id.toString()))
            if (changed <= 0) return false

            invalidateProfitDistributionForDate(db, old.date)
            invalidateCashSettlementForDate(db, old.date)

            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
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

    fun getExpenseTotalsByPartner(date: String): List<PartnerMoneySummary> = readableDatabase.rawQuery(
        """
        SELECT expense_payer_id AS partner_id, expense_payer_name AS partner_name,
               COALESCE(SUM(expense),0) AS amount
        FROM store_daily_record
        WHERE date=? AND deleted=0 AND expense>0
        GROUP BY expense_payer_id,expense_payer_name
        ORDER BY amount DESC
        """.trimIndent(), arrayOf(date)
    ).use { c -> buildList {
        while (c.moveToNext()) add(PartnerMoneySummary(c.long("partner_id"), c.str("partner_name"), c.dbl("amount")))
    } }

    fun getCashSettlement(date: String): CashSettlementBundle? {
        val settlement = readableDatabase.rawQuery(
            "SELECT * FROM daily_cash_settlement WHERE date=? AND deleted=0 ORDER BY id DESC LIMIT 1",
            arrayOf(date)
        ).use { c ->
            if (!c.moveToFirst()) null else DailyCashSettlementRecord(
                c.long("id"), c.str("date"), c.dbl("revenue"), c.dbl("purchase_cost"),
                c.dbl("expense"), c.dbl("profit"), c.int("status"), c.long("created_at"),
                c.long("updated_at")
            )
        } ?: return null

        val partners = readableDatabase.rawQuery(
            "SELECT * FROM settlement_partner WHERE settlement_id=? AND deleted=0 ORDER BY id",
            arrayOf(settlement.id.toString())
        ).use { c -> buildList {
            while (c.moveToNext()) add(CashSettlementPartnerRecord(
                c.long("id"), c.long("settlement_id"), c.str("date"),
                c.long("partner_id"), c.str("partner_name"),
                c.dbl("purchase_paid"), c.dbl("expense_paid"), c.dbl("revenue_received"),
                c.dbl("profit_share"), c.dbl("should_keep"), c.dbl("balance")
            ))
        } }

        val transfers = readableDatabase.rawQuery(
            "SELECT * FROM settlement_transfer WHERE settlement_id=? AND deleted=0 ORDER BY id",
            arrayOf(settlement.id.toString())
        ).use { c -> buildList {
            while (c.moveToNext()) add(SettlementTransferRecord(
                c.long("id"), c.long("settlement_id"), c.str("date"),
                c.long("from_partner_id"), c.str("from_partner_name"),
                c.long("to_partner_id"), c.str("to_partner_name"), c.dbl("amount")
            ))
        } }

        return CashSettlementBundle(settlement, partners, transfers)
    }

    fun getRecentCashSettlements(limit: Int = 20): List<DailyCashSettlementRecord> = readableDatabase.rawQuery(
        "SELECT * FROM daily_cash_settlement WHERE deleted=0 ORDER BY date DESC,id DESC LIMIT ?",
        arrayOf(limit.toString())
    ).use { c -> buildList {
        while (c.moveToNext()) add(DailyCashSettlementRecord(
            c.long("id"), c.str("date"), c.dbl("revenue"), c.dbl("purchase_cost"),
            c.dbl("expense"), c.dbl("profit"), c.int("status"), c.long("created_at"),
            c.long("updated_at")
        ))
    } }

    fun getCashSettlementsBetween(
        start: String?,
        end: String?
    ): List<CashSettlementBundle> {
        val where =
            if (start != null && end != null) {
                "AND date>=? AND date<=?"
            } else {
                ""
            }

        val args =
            if (start != null && end != null) {
                arrayOf(start, end)
            } else {
                emptyArray()
            }

        val dates = readableDatabase.rawQuery(
            "SELECT DISTINCT date FROM daily_cash_settlement " +
                "WHERE deleted=0 $where ORDER BY date DESC",
            args
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(c.str("date"))
                }
            }
        }

        return dates.mapNotNull {
            getCashSettlement(it)
        }
    }

    fun generateCashSettlement(date: String): CashSettlementResult {
        val summary = getDailySummary(date)
        if (summary.revenue <= 0.0) return CashSettlementResult(false, "当天还没有营业额")

        val profitRows = getProfitDistribution(date)
        if (profitRows.isEmpty()) {
            return CashSettlementResult(false, "请先到“更多 → 利润分配”保存当天利润分配")
        }

        val records = getDailyRecords(date)
        val unassignedReceipts = records.sumOf {
            (if (it.wechatIncome > 0 && it.wechatCollectorId <= 0) it.wechatIncome else 0.0) +
            (if (it.alipayIncome > 0 && it.alipayCollectorId <= 0) it.alipayIncome else 0.0) +
            (if (it.cashIncome > 0 && it.cashCollectorId <= 0) it.cashIncome else 0.0)
        }
        if (unassignedReceipts > 0.005) {
            return CashSettlementResult(false, "还有 ${roundMoney(unassignedReceipts)} 元收款没有指定归属")
        }

        val unassignedExpense = records.sumOf {
            if (it.expense > 0 && it.expensePayerId <= 0) it.expense else 0.0
        }
        if (unassignedExpense > 0.005) {
            return CashSettlementResult(false, "还有 ${roundMoney(unassignedExpense)} 元费用没有指定付款人")
        }

        val inventoryDelta = roundMoney(summary.closingStockValue - summary.openingStockValue)
        if (kotlin.math.abs(inventoryDelta) > 0.01) {
            return CashSettlementResult(
                false,
                "开摊/收摊库存相差 ${roundMoney(inventoryDelta)} 元。当天现金轧差要求库存差为0；请先核对库存或当天暂不做现金结清。"
            )
        }

        val purchaseMap = getPurchaseTotalsByPartner(date).associateBy { it.partnerId }
        val receiptMap = getReceiptsByPartner(date).associateBy { it.partnerId }
        val expenseMap = getExpenseTotalsByPartner(date).associateBy { it.partnerId }
        val profitMap = profitRows.associateBy { it.partnerId }

        val partnerIds = linkedSetOf<Long>().apply {
            addAll(purchaseMap.keys.filter { it > 0 })
            addAll(receiptMap.keys.filter { it > 0 })
            addAll(expenseMap.keys.filter { it > 0 })
            addAll(profitMap.keys.filter { it > 0 })
        }

        data class Row(
            val id: Long, val name: String, val purchase: Double, val expense: Double,
            val receipt: Double, val profit: Double, val keep: Double, val balance: Double
        )

        val rows = partnerIds.map { id ->
            val name = profitMap[id]?.partnerName
                ?: purchaseMap[id]?.partnerName
                ?: receiptMap[id]?.partnerName
                ?: expenseMap[id]?.partnerName
                ?: "合伙人$id"
            val purchase = purchaseMap[id]?.amount ?: 0.0
            val expense = expenseMap[id]?.amount ?: 0.0
            val receipt = receiptMap[id]?.amount ?: 0.0
            val profit = profitMap[id]?.allocatedProfit ?: 0.0
            val keep = roundMoney(purchase + expense + profit)
            Row(id, name, purchase, expense, receipt, profit, keep, roundMoney(keep - receipt))
        }

        val balanceTotal = roundMoney(rows.sumOf { it.balance })
        if (kotlin.math.abs(balanceTotal) > 0.01) {
            return CashSettlementResult(false, "当前账目还有 ${roundMoney(balanceTotal)} 元无法轧平，请核对进货、收款、费用和利润分配")
        }

        data class MutableBalance(val id: Long, val name: String, var amount: Double)
        val payers = rows.filter { it.balance < -0.005 }
            .map { MutableBalance(it.id, it.name, roundMoney(-it.balance)) }.toMutableList()
        val receivers = rows.filter { it.balance > 0.005 }
            .map { MutableBalance(it.id, it.name, roundMoney(it.balance)) }.toMutableList()

        data class TransferTmp(val fromId: Long, val fromName: String, val toId: Long, val toName: String, val amount: Double)
        val transfers = mutableListOf<TransferTmp>()
        var i = 0
        var j = 0
        while (i < payers.size && j < receivers.size) {
            val amount = roundMoney(minOf(payers[i].amount, receivers[j].amount))
            if (amount > 0.0) transfers += TransferTmp(payers[i].id, payers[i].name, receivers[j].id, receivers[j].name, amount)
            payers[i].amount = roundMoney(payers[i].amount - amount)
            receivers[j].amount = roundMoney(receivers[j].amount - amount)
            if (payers[i].amount <= 0.005) i++
            if (receivers[j].amount <= 0.005) j++
        }

        val db = writableDatabase
        db.beginTransaction()
        try {
            val now = System.currentTimeMillis()
            val oldIds = db.rawQuery(
                "SELECT id FROM daily_cash_settlement WHERE date=? AND deleted=0", arrayOf(date)
            ).use { c -> buildList { while (c.moveToNext()) add(c.long("id")) } }
            oldIds.forEach { oldId ->
                db.update("daily_cash_settlement", ContentValues().apply {
                    put("deleted", 1); put("sync_status", 2); put("updated_at", now)
                }, "id=?", arrayOf(oldId.toString()))
                db.update("settlement_partner", ContentValues().apply {
                    put("deleted", 1); put("sync_status", 2); put("updated_at", now)
                }, "settlement_id=?", arrayOf(oldId.toString()))
                db.update("settlement_transfer", ContentValues().apply {
                    put("deleted", 1); put("sync_status", 2); put("updated_at", now)
                }, "settlement_id=?", arrayOf(oldId.toString()))
            }

            val settlementId = db.insert("daily_cash_settlement", null, baseSyncValues().apply {
                put("date", date)
                put("revenue", summary.revenue)
                put("purchase_cost", summary.purchaseCost)
                put("expense", summary.expense)
                put("profit", summary.profit)
                put("status", 0)
                put("deleted", 0)
            })
            if (settlementId <= 0) return CashSettlementResult(false, "创建结算单失败")

            rows.forEach { r ->
                db.insert("settlement_partner", null, baseSyncValues().apply {
                    put("settlement_id", settlementId)
                    put("date", date)
                    put("partner_id", r.id)
                    put("partner_name", r.name)
                    put("purchase_paid", r.purchase)
                    put("expense_paid", r.expense)
                    put("revenue_received", r.receipt)
                    put("profit_share", r.profit)
                    put("should_keep", r.keep)
                    put("balance", r.balance)
                    put("deleted", 0)
                })
            }
            transfers.forEach { t ->
                db.insert("settlement_transfer", null, baseSyncValues().apply {
                    put("settlement_id", settlementId)
                    put("date", date)
                    put("from_partner_id", t.fromId)
                    put("from_partner_name", t.fromName)
                    put("to_partner_id", t.toId)
                    put("to_partner_name", t.toName)
                    put("amount", t.amount)
                    put("deleted", 0)
                })
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        return CashSettlementResult(true, "今日结算方案已生成", getCashSettlement(date))
    }

    fun confirmCashSettlement(id: Long): Boolean = writableDatabase.update(
        "daily_cash_settlement",
        ContentValues().apply {
            put("status", 1)
            put("sync_status", 2)
            put("updated_at", System.currentTimeMillis())
        },
        "id=? AND deleted=0",
        arrayOf(id.toString())
    ) > 0

    fun deleteCashSettlement(id: Long) {
        val now = System.currentTimeMillis()
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.update("daily_cash_settlement", ContentValues().apply {
                put("deleted", 1); put("sync_status", 2); put("updated_at", now)
            }, "id=?", arrayOf(id.toString()))
            db.update("settlement_partner", ContentValues().apply {
                put("deleted", 1); put("sync_status", 2); put("updated_at", now)
            }, "settlement_id=?", arrayOf(id.toString()))
            db.update("settlement_transfer", ContentValues().apply {
                put("deleted", 1); put("sync_status", 2); put("updated_at", now)
            }, "settlement_id=?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
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
            invalidateCashSettlementForDate(db, date)
            db.setTransactionSuccessful()
            return true
        } finally { db.endTransaction() }
    }

    fun deleteProfitDistribution(date: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val now = System.currentTimeMillis()
            db.update("profit_distribution", ContentValues().apply {
                put("deleted", 1)
                put("sync_status", 2)
                put("updated_at", now)
            }, "date=? AND deleted=0", arrayOf(date))
            invalidateCashSettlementForDate(db, date)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getProfitDistribution(date: String): List<ProfitDistributionRecord> = readableDatabase.rawQuery(
        "SELECT * FROM profit_distribution WHERE date=? AND deleted=0 ORDER BY allocated_profit DESC,id", arrayOf(date)
    ).use { c -> profitList(c) }

    fun getRecentProfitDistributions(limit: Int = 80): List<ProfitDistributionRecord> = readableDatabase.rawQuery(
        "SELECT * FROM profit_distribution WHERE deleted=0 ORDER BY date DESC,id LIMIT ?", arrayOf(limit.toString())
    ).use { c -> profitList(c) }

    fun getProfitDistributionsBetween(start: String?, end: String?): List<ProfitDistributionRecord> {
        val where = if (start != null && end != null) "AND date>=? AND date<=?" else ""
        val args = if (start != null && end != null) arrayOf(start, end) else emptyArray()
        return readableDatabase.rawQuery(
            "SELECT * FROM profit_distribution WHERE deleted=0 $where ORDER BY date DESC,id",
            args
        ).use { c -> profitList(c) }
    }

    private fun profitList(c: Cursor): List<ProfitDistributionRecord> = buildList {
        while (c.moveToNext()) add(ProfitDistributionRecord(
            c.long("id"), c.str("date"), c.long("partner_id"), c.str("partner_name"), c.str("role"),
            c.dbl("ratio"), c.int("weight"), c.dbl("source_profit"), c.dbl("allocated_profit")
        ))
    }

    fun getProfitSettlementDaily(
        start: String?,
        end: String?
    ): List<DailyPartnerProfitSettlement> {
        val rangeWhere =
            if (start != null && end != null) "AND pd.date>=? AND pd.date<=?" else ""
        val args =
            if (start != null && end != null) arrayOf(start, end) else emptyArray()

        return readableDatabase.rawQuery(
            """
            SELECT
                pd.date AS profit_date,
                pd.partner_id AS partner_id,
                MAX(pd.partner_name) AS partner_name,
                COALESCE(SUM(pd.allocated_profit),0) AS earned_profit,
                COALESCE((
                    SELECT SUM(psi.profit_amount)
                    FROM profit_settlement_item psi
                    WHERE psi.deleted=0
                      AND psi.profit_date=pd.date
                      AND psi.partner_id=pd.partner_id
                ),0) AS settled_profit,
                COALESCE((
                    SELECT MAX(psb.settlement_date)
                    FROM profit_settlement_item psi2
                    JOIN profit_settlement_batch psb ON psb.id=psi2.batch_id
                    WHERE psi2.deleted=0
                      AND psb.deleted=0
                      AND psi2.profit_date=pd.date
                      AND psi2.partner_id=pd.partner_id
                ),'') AS last_settlement_date
            FROM profit_distribution pd
            WHERE pd.deleted=0 $rangeWhere
            GROUP BY pd.date,pd.partner_id
            ORDER BY pd.date DESC,pd.partner_id
            """.trimIndent(),
            args
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val earned = roundMoney(c.dbl("earned_profit"))
                    val settled = roundMoney(c.dbl("settled_profit"))
                    add(
                        DailyPartnerProfitSettlement(
                            date = c.str("profit_date"),
                            partnerId = c.long("partner_id"),
                            partnerName = c.str("partner_name"),
                            earnedProfit = earned,
                            settledProfit = settled,
                            pendingProfit = roundMoney(earned - settled),
                            lastSettlementDate = c.str("last_settlement_date")
                        )
                    )
                }
            }
        }
    }

    fun getPartnerProfitSettlementSummary(
        start: String?,
        end: String?
    ): List<PartnerProfitSettlementSummary> {
        val daily = getProfitSettlementDaily(start, end)
        if (daily.isEmpty()) return emptyList()

        data class Acc(
            var name: String = "",
            var earned: Double = 0.0,
            var settled: Double = 0.0
        )

        val map = linkedMapOf<Long, Acc>()
        daily.forEach { row ->
            val acc = map.getOrPut(row.partnerId) {
                Acc(name = row.partnerName)
            }
            if (row.partnerName.isNotBlank()) acc.name = row.partnerName
            acc.earned += row.earnedProfit
            acc.settled += row.settledProfit
        }

        return map.map { (partnerId, acc) ->
            val earned = roundMoney(acc.earned)
            val settled = roundMoney(acc.settled)
            PartnerProfitSettlementSummary(
                partnerId = partnerId,
                partnerName = acc.name.ifBlank { "合伙人$partnerId" },
                earnedProfit = earned,
                settledProfit = settled,
                pendingProfit = roundMoney(earned - settled)
            )
        }.sortedBy { it.partnerId }
    }

    fun createProfitSettlementBatch(
        start: String,
        end: String,
        partnerIds: Set<Long>,
        settlementDate: String = LocalDate.now().toString(),
        note: String = ""
    ): ProfitSettlementCreateResult {
        if (start > end) {
            return ProfitSettlementCreateResult(false, "开始日期不能晚于结束日期")
        }
        if (partnerIds.isEmpty()) {
            return ProfitSettlementCreateResult(false, "请至少选择一位合伙人")
        }

        val pendingRows =
            getProfitSettlementDaily(start, end)
                .filter { it.partnerId in partnerIds && it.pendingProfit > 0.005 }

        if (pendingRows.isEmpty()) {
            return ProfitSettlementCreateResult(false, "所选范围没有待结算利润")
        }

        val total = roundMoney(pendingRows.sumOf { it.pendingProfit })
        val db = writableDatabase
        db.beginTransaction()
        try {
            val batchId =
                db.insert(
                    "profit_settlement_batch",
                    null,
                    baseSyncValues().apply {
                        put("settlement_date", settlementDate)
                        put("period_start", start)
                        put("period_end", end)
                        put("total_amount", total)
                        put("note", note)
                        put("deleted", 0)
                    }
                )

            if (batchId <= 0) {
                return ProfitSettlementCreateResult(false, "创建利润结算批次失败")
            }

            pendingRows.forEach { row ->
                val itemId =
                    db.insert(
                        "profit_settlement_item",
                        null,
                        baseSyncValues().apply {
                            put("batch_id", batchId)
                            put("profit_date", row.date)
                            put("partner_id", row.partnerId)
                            put("partner_name", row.partnerName)
                            put("profit_amount", row.pendingProfit)
                            put("deleted", 0)
                        }
                    )
                if (itemId <= 0) {
                    throw IllegalStateException("创建利润结算明细失败")
                }
            }

            db.setTransactionSuccessful()
            return ProfitSettlementCreateResult(
                success = true,
                message = "已确认 ${pendingRows.size} 条利润结算，共 ${roundMoney(total)} 元",
                batchId = batchId,
                totalAmount = total,
                itemCount = pendingRows.size
            )
        } catch (e: Exception) {
            return ProfitSettlementCreateResult(
                false,
                "利润结算失败：${e.message ?: "未知错误"}"
            )
        } finally {
            db.endTransaction()
        }
    }

    fun getProfitSettlementBatches(
        startSettlementDate: String? = null,
        endSettlementDate: String? = null,
        limit: Int = 100
    ): List<ProfitSettlementBatchRecord> {
        val where =
            if (startSettlementDate != null && endSettlementDate != null) {
                "AND settlement_date>=? AND settlement_date<=?"
            } else {
                ""
            }
        val args =
            if (startSettlementDate != null && endSettlementDate != null) {
                arrayOf(
                    startSettlementDate,
                    endSettlementDate,
                    limit.toString()
                )
            } else {
                arrayOf(limit.toString())
            }

        return readableDatabase.rawQuery(
            """
            SELECT *
            FROM profit_settlement_batch
            WHERE deleted=0 $where
            ORDER BY settlement_date DESC,id DESC
            LIMIT ?
            """.trimIndent(),
            args
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        ProfitSettlementBatchRecord(
                            id = c.long("id"),
                            settlementDate = c.str("settlement_date"),
                            periodStart = c.str("period_start"),
                            periodEnd = c.str("period_end"),
                            totalAmount = c.dbl("total_amount"),
                            note = c.str("note"),
                            createdAt = c.long("created_at")
                        )
                    )
                }
            }
        }
    }

    fun getProfitSettlementBatchesForProfitPeriod(
        startProfitDate: String?,
        endProfitDate: String?,
        limit: Int = 100
    ): List<ProfitSettlementBatchRecord> {
        val where =
            if (startProfitDate != null && endProfitDate != null) {
                "AND psi.profit_date>=? AND psi.profit_date<=?"
            } else {
                ""
            }
        val args =
            if (startProfitDate != null && endProfitDate != null) {
                arrayOf(
                    startProfitDate,
                    endProfitDate,
                    limit.toString()
                )
            } else {
                arrayOf(limit.toString())
            }

        return readableDatabase.rawQuery(
            """
            SELECT
                psb.id,
                psb.settlement_date,
                psb.period_start,
                psb.period_end,
                COALESCE(SUM(psi.profit_amount),0) AS total_amount,
                psb.note,
                psb.created_at
            FROM profit_settlement_batch psb
            JOIN profit_settlement_item psi
              ON psi.batch_id=psb.id
             AND psi.deleted=0
            WHERE psb.deleted=0 $where
            GROUP BY
                psb.id,
                psb.settlement_date,
                psb.period_start,
                psb.period_end,
                psb.note,
                psb.created_at
            ORDER BY psb.settlement_date DESC,psb.id DESC
            LIMIT ?
            """.trimIndent(),
            args
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        ProfitSettlementBatchRecord(
                            id = c.long("id"),
                            settlementDate =
                                c.str("settlement_date"),
                            periodStart =
                                c.str("period_start"),
                            periodEnd =
                                c.str("period_end"),
                            totalAmount =
                                c.dbl("total_amount"),
                            note = c.str("note"),
                            createdAt =
                                c.long("created_at")
                        )
                    )
                }
            }
        }
    }

    fun getProfitSettlementItems(
        batchId: Long
    ): List<ProfitSettlementItemRecord> =
        readableDatabase.rawQuery(
            """
            SELECT *
            FROM profit_settlement_item
            WHERE batch_id=? AND deleted=0
            ORDER BY profit_date DESC,partner_id,id
            """.trimIndent(),
            arrayOf(batchId.toString())
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        ProfitSettlementItemRecord(
                            id = c.long("id"),
                            batchId = c.long("batch_id"),
                            profitDate = c.str("profit_date"),
                            partnerId = c.long("partner_id"),
                            partnerName = c.str("partner_name"),
                            profitAmount = c.dbl("profit_amount"),
                            createdAt = c.long("created_at")
                        )
                    )
                }
            }
        }

    fun deleteProfitSettlementBatch(batchId: Long): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val now = System.currentTimeMillis()
            val batchChanged =
                db.update(
                    "profit_settlement_batch",
                    ContentValues().apply {
                        put("deleted", 1)
                        put("sync_status", 2)
                        put("updated_at", now)
                    },
                    "id=? AND deleted=0",
                    arrayOf(batchId.toString())
                )

            db.update(
                "profit_settlement_item",
                ContentValues().apply {
                    put("deleted", 1)
                    put("sync_status", 2)
                    put("updated_at", now)
                },
                "batch_id=? AND deleted=0",
                arrayOf(batchId.toString())
            )

            db.setTransactionSuccessful()
            return batchChanged > 0
        } finally {
            db.endTransaction()
        }
    }

    fun exportJson(): String {
        val root = JSONObject()
        root.put("schemaVersion", DB_VERSION)
        root.put("exportedAt", System.currentTimeMillis())
        root.put("bookId", ledgerId)
        root.put("bookName", ledgerName)
        root.put("deviceId", deviceId)
        root.put(
            "pendingSyncChanges",
            getSyncFoundationStatus()
                .pendingChanges
        )
        listOf("fruit", "store", "partner", "purchase_plan", "purchase_plan_item", "purchase_order", "purchase_item", "store_daily_record", "profit_rule", "profit_distribution", "daily_cash_settlement", "settlement_partner", "settlement_transfer", "profit_settlement_batch", "profit_settlement_item").forEach { table ->
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
        c.dbl("revenue"), c.dbl("expense"), c.long("expense_payer_id"), c.str("expense_payer_name"),
        c.dbl("opening_stock_value"), c.dbl("stock_left_value"),
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
        const val DB_VERSION = 8
    }
}

private fun roundMoney(v: Double): Double = round(v * 100.0) / 100.0

fun currentMonthRange(today: LocalDate = LocalDate.now()): Pair<String, String> =
    today.withDayOfMonth(1).toString() to today.withDayOfMonth(today.lengthOfMonth()).toString()
