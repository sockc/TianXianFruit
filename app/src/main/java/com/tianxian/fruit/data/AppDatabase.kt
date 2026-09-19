package com.tianxian.fruit.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.Locale
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
    val remark: String,
    val createdAt: Long
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
    val items: List<PurchaseItemRecord>,
    val activity: PurchaseActivityMeta? = null
)

object PurchaseTypes {
    const val PLANNED =
        "PLANNED"
    const val TEMPORARY =
        "TEMPORARY"

    fun normalize(
        value: String
    ): String =
        if (
            value ==
            TEMPORARY
        ) {
            TEMPORARY
        } else {
            PLANNED
        }

    fun label(
        value: String
    ): String =
        if (
            normalize(value) ==
            TEMPORARY
        ) {
            "临时采购"
        } else {
            "计划采购"
        }
}

data class PurchaseActivityMeta(
    val id: Long,
    val orderSyncId: String,
    val purchaseType: String,
    val recorderUsername: String,
    val recorderDisplayName: String,
    val createdAt: Long,
    val updatedAt: Long
)

data class PurchaseDuplicateRecord(
    val orderId: Long,
    val date: String,
    val buyerName: String,
    val fruitId: Long,
    val fruitName: String,
    val unit: String,
    val quantity: Double,
    val totalCost: Double,
    val unitPrice: Double,
    val purchaseType: String,
    val createdAt: Long
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

data class ReceiptSplitRecord(
    val partnerId: Long,
    val partnerName: String,
    val amount: Double,
    val wechatIncome: Double = 0.0,
    val alipayIncome: Double = 0.0,
    val cashIncome: Double = 0.0
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
    val receiptSplits: List<ReceiptSplitRecord>,
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
    val amount: Double,
    val settledAmount: Double,
    val settlementKind: String = "DAILY",
    val batchKey: String = "",
    val rangeStartDate: String = "",
    val rangeEndDate: String = "",
    val businessDayCount: Int = 1,
    val confirmedAt: Long = 0L,
    val focusPartnerId: Long = 0L,
    val focusPartnerName: String = ""
) {
    val pendingAmount: Double
        get() = (amount - settledAmount).coerceAtLeast(0.0)
}

data class PartnerFundBalanceSummary(
    val partnerId: Long,
    val partnerName: String,
    val purchasePaid: Double,
    val expensePaid: Double,
    val revenueReceived: Double,
    val profitShare: Double,
    val settlementSent: Double,
    val settlementReceived: Double,
    val currentBalance: Double
)


data class FundPeriodSummary(
    val startDate: String,
    val endDate: String,
    val businessDayCount: Int,
    val purchaseAmount: Double,
    val revenueAmount: Double,
    val profitAmount: Double,
    val settlementAmount: Double
)

data class PartnerFundPeriodSummary(
    val partnerId: Long,
    val partnerName: String,
    val purchasePaid: Double,
    val expensePaid: Double,
    val revenueReceived: Double,
    val profitShare: Double,
    val settlementSent: Double,
    val settlementReceived: Double
)

data class PartnerDailyFundBalanceRecord(
    val date: String,
    val partnerId: Long,
    val partnerName: String,
    val purchasePaid: Double,
    val expensePaid: Double,
    val revenueReceived: Double,
    val profitShare: Double,
    val dayBalance: Double,
    val settledAmount: Double,
    val remainingBalance: Double,
    val settlementId: Long,
    val transfers: List<SettlementTransferRecord>
) {
    val status: Int
        get() = when {
            kotlin.math.abs(dayBalance) <= 0.005 -> 3 // 无需结算
            settledAmount <= 0.005 -> 0 // 未结算
            kotlin.math.abs(remainingBalance) <= 0.005 -> 1 // 已结清
            else -> 2 // 部分结算
        }
}

data class PartnerDateSettlementResult(
    val success: Boolean,
    val message: String
)

data class FundBalanceSettlementResult(
    val success: Boolean,
    val message: String,
    val totalAmount: Double = 0.0,
    val transferCount: Int = 0
)


data class PartnerOutstandingWindow(
    val partnerId: Long,
    val partnerName: String,
    val endDate: String,
    val currentBalance: Double,
    val rangeStartDate: String,
    val rangeEndDate: String,
    val businessDayCount: Int,
    val lastClearedDate: String,
    val settledTransferAmount: Double
)

data class FundSettlementHistoryRecord(
    val recordKey: String,
    val kind: String,
    val settlementDate: String,
    val periodStart: String,
    val periodEnd: String,
    val businessDayCount: Int,
    val focusPartnerId: Long,
    val focusPartnerName: String,
    val totalAmount: Double,
    val settledAmount: Double,
    val status: Int,
    val confirmedAt: Long,
    val transfers: List<SettlementTransferRecord>
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
    val updatedAt: Long,
    val confirmedAt: Long = 0L
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
    val status: Int = 0,
    val estimatedAmount: Double = 0.0
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
    val status: Int,
    val syncId: String = "",
    val estimatedAmount: Double = 0.0,
    val actualQuantity: Double = 0.0,
    val actualAmount: Double = 0.0,
    val buyerId: Long = 0L,
    val buyerName: String = "",
    val completedByUsername: String = "",
    val completedByDisplayName: String = "",
    val completedAt: Long = 0L,
    val purchaseOrderSyncId: String = ""
)

data class PurchasePlanDetail(
    val plan: PurchasePlanRecord,
    val items: List<PurchasePlanItemRecord>
)

data class CollaborationCompleteResult(
    val success: Boolean,
    val message: String,
    val orderId: Long = -1L
)

private data class CollaborationLinkTarget(
    val itemId: Long,
    val itemSyncId: String,
    val planId: Long,
    val line: PurchaseLineInput
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

data class CloudSyncLocalStatus(
    val serverCursor: Long,
    val lastSyncAt: Long,
    val lastError: String
)

data class SyncConflictRecord(
    val id: Long,
    val tableName: String,
    val recordSyncId: String,
    val localVersion: Long,
    val serverVersion: Long,
    val serverDeleted: Boolean,
    val localPayload: String,
    val serverPayload: String,
    val detectedAt: Long
)

class AppDatabase(
    context: Context,
    val dbFileName: String = DB_NAME,
    val ledgerId: String = "local-default",
    val ledgerName: String = "我的账本",
    private val deviceId: String = "legacy-device",
    private val deviceName: String = "Android设备",
    private val seedDefaults: Boolean = true
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
        createV12PurchaseActivityTable(
            db
        )
        createV13PurchaseCollaborationTable(
            db
        )
        createV14SortOrder(
            db
        )
        createV15MultiReceipt(
            db
        )
        createV17SettlementProgress(
            db
        )
        // ROLLBACK1: keep the DB20 schema envelope so this FIX6-functionality
        // build can safely open databases already upgraded by FIX7-FIX11.
        // These columns are compatibility-only here; FIX6 business logic ignores them.
        createV18CompatibilitySchema(
            db
        )
        createV19CompatibilitySchema(
            db
        )
        createV20CompatibilitySchema(
            db
        )
        createV21FundSettlementExtension(
            db
        )
        createV22SettlementCenterCleanup(
            db
        )
        createV8SyncFoundation(
            db,
            initialData = false
        )
        createV9CloudSync(db)
        createV10SyncTriggerFix(db)
        createV11ConflictSupport(db)
        createV12PurchaseActivity(
            db
        )
        createV13PurchaseCollaboration(
            db
        )

        if (seedDefaults) {
            seedFruits(db)
            seedPartners(db)
        }
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
        if (oldVersion < 9) {
            createV9CloudSync(db)
        }
        if (oldVersion < 10) {
            createV10SyncTriggerFix(db)
        }
        if (oldVersion < 11) {
            createV11ConflictSupport(db)
        }
        if (oldVersion < 12) {
            createV12PurchaseActivity(
                db
            )
        }
        if (oldVersion < 13) {
            createV13PurchaseCollaboration(
                db
            )
        }
        if (oldVersion < 14) {
            createV14SortOrder(
                db
            )
        }
        if (oldVersion < 15) {
            createV15MultiReceipt(
                db
            )
        }
        if (oldVersion < 16) {
            createV16ReceiptDetailResync(
                db
            )
        }
        if (oldVersion < 17) {
            createV17SettlementProgress(
                db
            )
        }
        if (oldVersion < 18) {
            createV18CompatibilitySchema(
                db
            )
        }
        if (oldVersion < 19) {
            createV19CompatibilitySchema(
                db
            )
        }
        if (oldVersion < 20) {
            createV20CompatibilitySchema(
                db
            )
        }
        if (oldVersion < 21) {
            createV21FundSettlementExtension(
                db
            )
        }
        if (oldVersion < 22) {
            createV22SettlementCenterCleanup(
                db
            )
        }
    }

    private fun createV22SettlementCenterCleanup(
        db: SQLiteDatabase
    ) {
        // V1.4.7.15: classify old "结清截至今日" records once.
        // Records that already went through the configured settlement center stay valid;
        // old B<->C / C<->B cutoff records are retained for history but excluded from balances.
        var centerId =
            db.rawQuery(
                """
                SELECT r.partner_id
                FROM profit_rule r
                JOIN partner p ON p.id=r.partner_id
                WHERE r.deleted=0
                  AND p.deleted=0
                  AND p.enabled=1
                  AND COALESCE(r.is_settlement_center,0)=1
                ORDER BY r.id DESC
                LIMIT 1
                """.trimIndent(),
                null
            ).use { c ->
                if (c.moveToFirst()) c.long("partner_id") else 0L
            }

        if (centerId <= 0L) {
            centerId =
                db.rawQuery(
                    """
                    SELECT r.partner_id
                    FROM profit_rule r
                    JOIN partner p ON p.id=r.partner_id
                    WHERE r.deleted=0
                      AND p.deleted=0
                      AND p.enabled=1
                    ORDER BY r.id
                    LIMIT 1
                    """.trimIndent(),
                    null
                ).use { c ->
                    if (c.moveToFirst()) c.long("partner_id") else 0L
                }
        }
        if (centerId <= 0L) {
            centerId =
                db.rawQuery(
                    """
                    SELECT id
                    FROM partner
                    WHERE deleted=0 AND enabled=1
                    ORDER BY id
                    LIMIT 1
                    """.trimIndent(),
                    null
                ).use { c ->
                    if (c.moveToFirst()) c.long("id") else 0L
                }
        }

        if (centerId <= 0L) return

        val now = System.currentTimeMillis()
        db.execSQL(
            """
            UPDATE settlement_transfer
            SET settlement_kind=CASE
                    WHEN from_partner_id=? OR to_partner_id=?
                        THEN 'CUTOFF_CENTER'
                    ELSE 'CUTOFF_LEGACY_INVALID'
                END,
                sync_status=2,
                updated_at=?
            WHERE deleted=0
              AND settlement_id=0
              AND settlement_kind='CUTOFF'
              AND confirmed_at>0
            """.trimIndent(),
            arrayOf<Any?>(centerId, centerId, now)
        )
    }

    private fun createV21FundSettlementExtension(
        db: SQLiteDatabase
    ) {
        if (!columnExists(db, "daily_cash_settlement", "confirmed_at")) {
            db.execSQL(
                "ALTER TABLE daily_cash_settlement ADD COLUMN confirmed_at INTEGER NOT NULL DEFAULT 0"
            )
        }
        if (!columnExists(db, "settlement_transfer", "settlement_kind")) {
            db.execSQL(
                "ALTER TABLE settlement_transfer ADD COLUMN settlement_kind TEXT NOT NULL DEFAULT 'DAILY'"
            )
        }
        if (!columnExists(db, "settlement_transfer", "batch_key")) {
            db.execSQL(
                "ALTER TABLE settlement_transfer ADD COLUMN batch_key TEXT NOT NULL DEFAULT ''"
            )
        }
        if (!columnExists(db, "settlement_transfer", "range_start_date")) {
            db.execSQL(
                "ALTER TABLE settlement_transfer ADD COLUMN range_start_date TEXT NOT NULL DEFAULT ''"
            )
        }
        if (!columnExists(db, "settlement_transfer", "range_end_date")) {
            db.execSQL(
                "ALTER TABLE settlement_transfer ADD COLUMN range_end_date TEXT NOT NULL DEFAULT ''"
            )
        }
        if (!columnExists(db, "settlement_transfer", "business_day_count")) {
            db.execSQL(
                "ALTER TABLE settlement_transfer ADD COLUMN business_day_count INTEGER NOT NULL DEFAULT 1"
            )
        }
        if (!columnExists(db, "settlement_transfer", "confirmed_at")) {
            db.execSQL(
                "ALTER TABLE settlement_transfer ADD COLUMN confirmed_at INTEGER NOT NULL DEFAULT 0"
            )
        }
        if (!columnExists(db, "settlement_transfer", "focus_partner_id")) {
            db.execSQL(
                "ALTER TABLE settlement_transfer ADD COLUMN focus_partner_id INTEGER NOT NULL DEFAULT 0"
            )
        }
        if (!columnExists(db, "settlement_transfer", "focus_partner_name")) {
            db.execSQL(
                "ALTER TABLE settlement_transfer ADD COLUMN focus_partner_name TEXT NOT NULL DEFAULT ''"
            )
        }

        db.execSQL(
            """
            UPDATE daily_cash_settlement
            SET confirmed_at=CASE
                WHEN confirmed_at<=0 AND status IN(1,2) THEN updated_at
                ELSE confirmed_at
            END
            """.trimIndent()
        )
        db.execSQL(
            """
            UPDATE settlement_transfer
            SET
                settlement_kind=CASE
                    WHEN settlement_id=0 AND settlement_kind='DAILY' THEN 'LEGACY'
                    ELSE settlement_kind
                END,
                range_start_date=CASE
                    WHEN range_start_date='' THEN date
                    ELSE range_start_date
                END,
                range_end_date=CASE
                    WHEN range_end_date='' THEN date
                    ELSE range_end_date
                END,
                business_day_count=CASE
                    WHEN settlement_id=0 AND batch_key='' THEN 0
                    WHEN business_day_count<=0 THEN 1
                    ELSE business_day_count
                END,
                confirmed_at=CASE
                    WHEN confirmed_at<=0 AND settled_amount>0.005 THEN updated_at
                    ELSE confirmed_at
                END
            WHERE deleted=0
            """.trimIndent()
        )
    }

    // DB18-DB22 preserve compatibility while this branch evolves from the V1.4.7.6 baseline.
    // ROLLBACK1 preserves only their schema so an existing DB20 database can be
    // opened without downgrade/delete. No FIX7-FIX11 settlement behavior is enabled.
    private fun createV20CompatibilitySchema(
        db: SQLiteDatabase
    ) {
        if (
            !columnExists(
                db,
                "partner",
                "sort_order"
            )
        ) {
            db.execSQL(
                "ALTER TABLE partner " +
                    "ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0"
            )
        }
        db.execSQL(
            "UPDATE partner SET sort_order=id WHERE sort_order<=0"
        )
    }

    private fun createV19CompatibilitySchema(
        db: SQLiteDatabase
    ) {
        if (
            !columnExists(
                db,
                "profit_rule",
                "is_settlement_center"
            )
        ) {
            db.execSQL(
                "ALTER TABLE profit_rule " +
                    "ADD COLUMN is_settlement_center INTEGER NOT NULL DEFAULT 0"
            )
        }
    }

    private fun createV18CompatibilitySchema(
        db: SQLiteDatabase
    ) {
        if (!columnExists(db, "profit_rule", "settlement_cycle")) {
            db.execSQL(
                "ALTER TABLE profit_rule ADD COLUMN settlement_cycle TEXT NOT NULL DEFAULT 'DAILY'"
            )
        }
        if (!columnExists(db, "profit_rule", "settlement_weekday")) {
            db.execSQL(
                "ALTER TABLE profit_rule ADD COLUMN settlement_weekday INTEGER NOT NULL DEFAULT 7"
            )
        }
        if (!columnExists(db, "profit_rule", "settlement_month_day")) {
            db.execSQL(
                "ALTER TABLE profit_rule ADD COLUMN settlement_month_day INTEGER NOT NULL DEFAULT 0"
            )
        }
        if (!columnExists(db, "profit_distribution", "settlement_cycle")) {
            db.execSQL(
                "ALTER TABLE profit_distribution ADD COLUMN settlement_cycle TEXT NOT NULL DEFAULT 'DAILY'"
            )
        }
        if (!columnExists(db, "profit_distribution", "settlement_weekday")) {
            db.execSQL(
                "ALTER TABLE profit_distribution ADD COLUMN settlement_weekday INTEGER NOT NULL DEFAULT 7"
            )
        }
        if (!columnExists(db, "profit_distribution", "settlement_month_day")) {
            db.execSQL(
                "ALTER TABLE profit_distribution ADD COLUMN settlement_month_day INTEGER NOT NULL DEFAULT 0"
            )
        }
    }

    private fun createV17SettlementProgress(
        db: SQLiteDatabase
    ) {
        if (
            !columnExists(
                db,
                "settlement_transfer",
                "settled_amount"
            )
        ) {
            db.execSQL(
                "ALTER TABLE settlement_transfer " +
                    "ADD COLUMN settled_amount REAL NOT NULL DEFAULT 0"
            )
        }

        // FIX3 migration: settlements confirmed before this version were
        // all-or-nothing, therefore every planned transfer was actually settled.
        db.execSQL(
            """
            UPDATE settlement_transfer
            SET settled_amount=amount
            WHERE deleted=0
              AND settlement_id IN(
                    SELECT id
                    FROM daily_cash_settlement
                    WHERE deleted=0 AND status=1
              )
            """.trimIndent()
        )
    }

    private fun createV16ReceiptDetailResync(
        db: SQLiteDatabase
    ) {
        if (
            tableExists(db, "store_daily_record") &&
            columnExists(db, "store_daily_record", "receipt_splits_json")
        ) {
            // FIX1 重新触发已有多人收款记录的同步。
            // V1.4.7 初版已经有 receipt_splits_json，但部分另一台旧设备只显示
            // 兼容字段“多人收款”。升级后把真实明细重新推送一次。
            db.execSQL(
                "UPDATE store_daily_record " +
                    "SET receipt_splits_json=receipt_splits_json " +
                    "WHERE deleted=0 AND receipt_splits_json<>'[]'"
            )
        }
    }

    private fun createV15MultiReceipt(
        db: SQLiteDatabase
    ) {
        if (
            !columnExists(
                db,
                "store_daily_record",
                "receipt_splits_json"
            )
        ) {
            db.execSQL(
                "ALTER TABLE store_daily_record " +
                    "ADD COLUMN receipt_splits_json TEXT NOT NULL DEFAULT '[]'"
            )
        }
    }

    private fun createV14SortOrder(
        db: SQLiteDatabase
    ) {
        if (
            !columnExists(
                db,
                "fruit",
                "sort_order"
            )
        ) {
            db.execSQL(
                "ALTER TABLE fruit " +
                    "ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0"
            )
        }

        if (
            !columnExists(
                db,
                "store",
                "sort_order"
            )
        ) {
            db.execSQL(
                "ALTER TABLE store " +
                    "ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0"
            )
        }

        db.execSQL(
            """
            UPDATE fruit
            SET sort_order=
                CASE
                    WHEN created_at>0
                        THEN created_at
                    ELSE id
                END
            WHERE sort_order=0
            """.trimIndent()
        )

        db.execSQL(
            """
            UPDATE store
            SET sort_order=
                CASE
                    WHEN created_at>0
                        THEN -created_at
                    ELSE -id
                END
            WHERE sort_order=0
            """.trimIndent()
        )
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
                sort_order INTEGER NOT NULL DEFAULT 0,
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
                sort_order INTEGER NOT NULL DEFAULT 0,
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
                receipt_splits_json TEXT NOT NULL DEFAULT '[]',
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
                confirmed_at INTEGER NOT NULL DEFAULT 0,
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
                settled_amount REAL NOT NULL DEFAULT 0,
                settlement_kind TEXT NOT NULL DEFAULT 'DAILY',
                batch_key TEXT NOT NULL DEFAULT '',
                range_start_date TEXT NOT NULL DEFAULT '',
                range_end_date TEXT NOT NULL DEFAULT '',
                business_day_count INTEGER NOT NULL DEFAULT 1,
                confirmed_at INTEGER NOT NULL DEFAULT 0,
                focus_partner_id INTEGER NOT NULL DEFAULT 0,
                focus_partner_name TEXT NOT NULL DEFAULT '',
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
                            put("receipt_splits_json", "[]")
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

    private fun createV12PurchaseActivityTable(
        db: SQLiteDatabase
    ) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS purchase_activity(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                order_sync_id TEXT NOT NULL UNIQUE,
                purchase_type TEXT NOT NULL DEFAULT 'PLANNED',
                recorder_username TEXT NOT NULL DEFAULT '',
                recorder_display_name TEXT NOT NULL DEFAULT '',
                deleted INTEGER NOT NULL DEFAULT 0,
                sync_id TEXT NOT NULL,
                sync_status INTEGER NOT NULL DEFAULT 0,
                row_version INTEGER NOT NULL DEFAULT 1,
                modified_by TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS " +
                "idx_purchase_activity_order_sync " +
                "ON purchase_activity(order_sync_id)"
        )
    }

    private fun createV12PurchaseActivity(
        db: SQLiteDatabase
    ) {
        createV12PurchaseActivityTable(
            db
        )

        createSyncTriggers(
            db
        )
    }

    private fun createV13PurchaseCollaborationTable(
        db: SQLiteDatabase
    ) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS purchase_collaboration(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                plan_item_sync_id TEXT NOT NULL UNIQUE,
                estimated_amount REAL NOT NULL DEFAULT 0,
                actual_quantity REAL NOT NULL DEFAULT 0,
                actual_amount REAL NOT NULL DEFAULT 0,
                buyer_id INTEGER NOT NULL DEFAULT 0,
                buyer_name TEXT NOT NULL DEFAULT '',
                completed_by_username TEXT NOT NULL DEFAULT '',
                completed_by_display_name TEXT NOT NULL DEFAULT '',
                completed_at INTEGER NOT NULL DEFAULT 0,
                purchase_order_sync_id TEXT NOT NULL DEFAULT '',
                deleted INTEGER NOT NULL DEFAULT 0,
                sync_id TEXT NOT NULL,
                sync_status INTEGER NOT NULL DEFAULT 0,
                row_version INTEGER NOT NULL DEFAULT 1,
                modified_by TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS " +
                "idx_purchase_collaboration_plan_item " +
                "ON purchase_collaboration(plan_item_sync_id)"
        )
    }

    private fun createV13PurchaseCollaboration(
        db: SQLiteDatabase
    ) {
        if (
            !columnExists(
                db,
                "purchase_order",
                "status"
            )
        ) {
            db.execSQL(
                "ALTER TABLE purchase_order " +
                    "ADD COLUMN status INTEGER NOT NULL DEFAULT 0"
            )
        }

        createV13PurchaseCollaborationTable(
            db
        )

        createSyncTriggers(
            db
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
                updated_at INTEGER NOT NULL,
                remote_apply INTEGER NOT NULL DEFAULT 0
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
                    arrayOf<Any?>(
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
            arrayOf<Any?>(
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
                arrayOf<Any?>(
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
                arrayOf<Any?>(
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

    private fun createV9CloudSync(
        db: SQLiteDatabase
    ) {
        if (
            !columnExists(
                db,
                "sync_context",
                "remote_apply"
            )
        ) {
            db.execSQL(
                "ALTER TABLE sync_context " +
                    "ADD COLUMN remote_apply " +
                    "INTEGER NOT NULL DEFAULT 0"
            )
        }

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS sync_cloud_state(
                book_id TEXT PRIMARY KEY,
                server_cursor INTEGER NOT NULL DEFAULT 0,
                last_sync_at INTEGER NOT NULL DEFAULT 0,
                last_error TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            INSERT OR IGNORE INTO sync_cloud_state(
                book_id,
                server_cursor,
                last_sync_at,
                last_error
            ) VALUES(?,0,0,'')
            """.trimIndent(),
            arrayOf(ledgerId)
        )

        createSyncTriggers(db)
    }

    private fun createV10SyncTriggerFix(
        db: SQLiteDatabase
    ) {
        createSyncTriggers(db)
    }

    private fun createV11ConflictSupport(
        db: SQLiteDatabase
    ) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS sync_conflict(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                table_name TEXT NOT NULL,
                record_sync_id TEXT NOT NULL,
                local_version INTEGER NOT NULL,
                server_version INTEGER NOT NULL,
                server_deleted INTEGER NOT NULL DEFAULT 0,
                local_payload TEXT NOT NULL DEFAULT '{}',
                server_payload TEXT NOT NULL DEFAULT '{}',
                detected_at INTEGER NOT NULL,
                UNIQUE(table_name,record_sync_id)
            )
            """.trimIndent()
        )

        db.execSQL(
            "CREATE INDEX IF NOT EXISTS " +
                "idx_sync_conflict_detected " +
                "ON sync_conflict(detected_at)"
        )
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
                WHEN COALESCE(
                    (
                        SELECT remote_apply
                        FROM sync_context
                        WHERE id=1
                    ),
                    0
                )=0
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
                WHEN
                    NEW.row_version=OLD.row_version
                    AND OLD.modified_by<>''
                    AND COALESCE(
                        (
                            SELECT remote_apply
                            FROM sync_context
                            WHERE id=1
                        ),
                        0
                    )=0
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
            "purchase_activity",
            "purchase_collaboration",
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

    fun getSyncPayload(
        tableName: String,
        syncId: String
    ): JSONObject? {
        if (
            tableName !in
                syncableTables()
        ) {
            return null
        }

        return readableDatabase
            .rawQuery(
                "SELECT * FROM $tableName " +
                    "WHERE sync_id=? LIMIT 1",
                arrayOf(syncId)
            )
            .use {
                c ->
                if (!c.moveToFirst()) {
                    null
                } else {
                    cursorRowAsJson(c)
                }
            }
    }

    fun getServerCursor(): Long =
        readableDatabase.rawQuery(
            """
            SELECT server_cursor
            FROM sync_cloud_state
            WHERE book_id=?
            LIMIT 1
            """.trimIndent(),
            arrayOf(ledgerId)
        ).use {
            c ->
            if (c.moveToFirst()) {
                c.long(
                    "server_cursor"
                )
            } else {
                0L
            }
        }

    fun setServerCursor(
        cursor: Long
    ) {
        writableDatabase.execSQL(
            """
            INSERT INTO sync_cloud_state(
                book_id,
                server_cursor,
                last_sync_at,
                last_error
            ) VALUES(?,?,0,'')
            ON CONFLICT(book_id)
            DO UPDATE SET
                server_cursor=excluded.server_cursor
            """.trimIndent(),
            arrayOf<Any?>(
                ledgerId,
                cursor
            )
        )
    }

    fun getCloudSyncLocalStatus():
        CloudSyncLocalStatus =
        readableDatabase.rawQuery(
            """
            SELECT
                server_cursor,
                last_sync_at,
                last_error
            FROM sync_cloud_state
            WHERE book_id=?
            LIMIT 1
            """.trimIndent(),
            arrayOf(ledgerId)
        ).use {
            c ->
            if (c.moveToFirst()) {
                CloudSyncLocalStatus(
                    serverCursor =
                        c.long(
                            "server_cursor"
                        ),
                    lastSyncAt =
                        c.long(
                            "last_sync_at"
                        ),
                    lastError =
                        c.str(
                            "last_error"
                        )
                )
            } else {
                CloudSyncLocalStatus(
                    serverCursor = 0L,
                    lastSyncAt = 0L,
                    lastError = ""
                )
            }
        }

    fun getLastCloudSyncAt(): Long =
        getCloudSyncLocalStatus()
            .lastSyncAt

    fun setLastCloudSync(
        time: Long,
        error: String
    ) {
        writableDatabase.execSQL(
            """
            INSERT INTO sync_cloud_state(
                book_id,
                server_cursor,
                last_sync_at,
                last_error
            ) VALUES(
                ?,
                COALESCE(
                    (
                        SELECT server_cursor
                        FROM sync_cloud_state
                        WHERE book_id=?
                    ),
                    0
                ),
                ?,
                ?
            )
            ON CONFLICT(book_id)
            DO UPDATE SET
                last_sync_at=excluded.last_sync_at,
                last_error=excluded.last_error
            """.trimIndent(),
            arrayOf<Any?>(
                ledgerId,
                ledgerId,
                time,
                error
            )
        )
    }

    fun saveSyncConflict(
        tableName: String,
        syncId: String,
        localVersion: Long,
        serverVersion: Long,
        serverDeleted: Boolean,
        serverPayload: JSONObject
    ) {
        if (
            tableName !in
                syncableTables()
        ) {
            return
        }

        val localPayload =
            getSyncPayload(
                tableName,
                syncId
            )
                ?: JSONObject()

        writableDatabase.execSQL(
            """
            INSERT INTO sync_conflict(
                table_name,
                record_sync_id,
                local_version,
                server_version,
                server_deleted,
                local_payload,
                server_payload,
                detected_at
            ) VALUES(?,?,?,?,?,?,?,?)
            ON CONFLICT(table_name,record_sync_id)
            DO UPDATE SET
                local_version=excluded.local_version,
                server_version=excluded.server_version,
                server_deleted=excluded.server_deleted,
                local_payload=excluded.local_payload,
                server_payload=excluded.server_payload,
                detected_at=excluded.detected_at
            """.trimIndent(),
            arrayOf<Any?>(
                tableName,
                syncId,
                localVersion,
                serverVersion,
                if (serverDeleted) 1 else 0,
                localPayload.toString(),
                serverPayload.toString(),
                System.currentTimeMillis()
            )
        )
    }

    fun getSyncConflicts():
        List<SyncConflictRecord> =
        readableDatabase.rawQuery(
            """
            SELECT
                id,
                table_name,
                record_sync_id,
                local_version,
                server_version,
                server_deleted,
                local_payload,
                server_payload,
                detected_at
            FROM sync_conflict
            ORDER BY detected_at
            """.trimIndent(),
            null
        ).use {
            c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        SyncConflictRecord(
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
                            localVersion =
                                c.long(
                                    "local_version"
                                ),
                            serverVersion =
                                c.long(
                                    "server_version"
                                ),
                            serverDeleted =
                                c.int(
                                    "server_deleted"
                                ) == 1,
                            localPayload =
                                c.str(
                                    "local_payload"
                                ),
                            serverPayload =
                                c.str(
                                    "server_payload"
                                ),
                            detectedAt =
                                c.long(
                                    "detected_at"
                                )
                        )
                    )
                }
            }
        }

    fun getSyncConflictCount(): Int =
        readableDatabase.rawQuery(
            "SELECT COUNT(*) AS c " +
                "FROM sync_conflict",
            null
        ).use {
            c ->
            if (c.moveToFirst()) {
                c.int("c")
            } else {
                0
            }
        }

    fun clearSyncConflict(
        tableName: String,
        syncId: String
    ) {
        writableDatabase.delete(
            "sync_conflict",
            "table_name=? " +
                "AND record_sync_id=?",
            arrayOf(
                tableName,
                syncId
            )
        )
    }

    fun resolveConflictUseCloud(
        conflict: SyncConflictRecord
    ) {
        val payload =
            runCatching {
                JSONObject(
                    conflict.serverPayload
                )
            }.getOrDefault(
                JSONObject()
            )

        markRecordChangesUploaded(
            conflict.tableName,
            conflict.recordSyncId
        )

        applyRemoteRecord(
            tableName =
                conflict.tableName,
            syncId =
                conflict.recordSyncId,
            rowVersion =
                conflict.serverVersion,
            operation =
                if (
                    conflict.serverDeleted
                ) {
                    "DELETE"
                } else {
                    "UPSERT"
                },
            payload = payload,
            modifiedBy =
                "cloud-conflict",
            force = true
        )

        clearSyncConflict(
            conflict.tableName,
            conflict.recordSyncId
        )
    }

    fun completeLocalConflictResolution(
        conflict: SyncConflictRecord,
        acceptedVersion: Long
    ) {
        val db =
            writableDatabase

        db.beginTransaction()

        try {
            setRemoteApply(
                db,
                true
            )

            db.update(
                conflict.tableName,
                ContentValues().apply {
                    put(
                        "row_version",
                        acceptedVersion
                    )
                    put(
                        "modified_by",
                        deviceId
                    )
                    put(
                        "sync_status",
                        0
                    )
                    put(
                        "updated_at",
                        System.currentTimeMillis()
                    )
                },
                "sync_id=?",
                arrayOf(
                    conflict.recordSyncId
                )
            )

            db.execSQL(
                """
                UPDATE sync_change_log
                SET uploaded=1
                WHERE table_name=?
                  AND record_sync_id=?
                """.trimIndent(),
                arrayOf<Any?>(
                    conflict.tableName,
                    conflict.recordSyncId
                )
            )

            db.delete(
                "sync_conflict",
                "table_name=? " +
                    "AND record_sync_id=?",
                arrayOf(
                    conflict.tableName,
                    conflict.recordSyncId
                )
            )

            setRemoteApply(
                db,
                false
            )

            db.setTransactionSuccessful()
        } finally {
            runCatching {
                setRemoteApply(
                    db,
                    false
                )
            }
            db.endTransaction()
        }
    }

    private fun markRecordChangesUploaded(
        tableName: String,
        syncId: String
    ) {
        writableDatabase.execSQL(
            """
            UPDATE sync_change_log
            SET uploaded=1
            WHERE table_name=?
              AND record_sync_id=?
            """.trimIndent(),
            arrayOf<Any?>(
                tableName,
                syncId
            )
        )
    }

    fun applyRemoteSyncEvent(
        tableName: String,
        syncId: String,
        rowVersion: Long,
        operation: String,
        payload: JSONObject,
        modifiedBy: String
    ) {
        applyRemoteRecord(
            tableName =
                tableName,
            syncId =
                syncId,
            rowVersion =
                rowVersion,
            operation =
                operation,
            payload =
                payload,
            modifiedBy =
                modifiedBy,
            force = false
        )
    }

    private fun applyRemoteRecord(
        tableName: String,
        syncId: String,
        rowVersion: Long,
        operation: String,
        payload: JSONObject,
        modifiedBy: String,
        force: Boolean
    ) {
        if (
            tableName !in
                syncableTables()
        ) {
            return
        }

        val db =
            writableDatabase

        val existingState =
            db.rawQuery(
                "SELECT id,row_version FROM $tableName " +
                    "WHERE sync_id=? LIMIT 1",
                arrayOf(syncId)
            ).use {
                c ->
                if (c.moveToFirst()) {
                    c.long("id") to
                        c.long("row_version")
                } else {
                    null
                }
            }

        if (
            !force &&
            existingState != null &&
            existingState.second >=
                rowVersion
        ) {
            return
        }

        db.beginTransaction()

        try {
            setRemoteApply(
                db,
                true
            )

            val existingId =
                existingState?.first

            val values =
                jsonToContentValues(
                    payload,
                    tableColumns(
                        db,
                        tableName
                    ),
                    tableName
                ).apply {
                    remove("sync_status")
                    put(
                        "sync_id",
                        syncId
                    )
                    put(
                        "row_version",
                        rowVersion
                    )
                    put(
                        "modified_by",
                        modifiedBy
                    )
                    put(
                        "sync_status",
                        0
                    )

                    if (
                        operation ==
                        "DELETE"
                    ) {
                        put(
                            "deleted",
                            1
                        )
                    }
                }

            if (existingId != null) {
                values.remove("id")

                db.update(
                    tableName,
                    values,
                    "id=?",
                    arrayOf(
                        existingId.toString()
                    )
                )
            } else if (
                operation != "DELETE" ||
                payload.length() > 0
            ) {
                val inserted =
                    db.insertWithOnConflict(
                        tableName,
                        null,
                        values,
                        SQLiteDatabase
                            .CONFLICT_ABORT
                    )

                if (inserted < 0) {
                    throw IllegalStateException(
                        "云端记录写入失败：$tableName / $syncId"
                    )
                }
            }

            setRemoteApply(
                db,
                false
            )

            db.setTransactionSuccessful()
        } finally {
            runCatching {
                setRemoteApply(
                    db,
                    false
                )
            }
            db.endTransaction()
        }
    }

    fun prepareCloudIdRanges() {
        val prefix =
            deviceIdRangePrefix()

        val base =
            (prefix + 1L) *
                1_000_000_000L

        val db =
            writableDatabase

        syncableTables().forEach {
            table ->
            val currentSeq =
                db.rawQuery(
                    "SELECT seq FROM sqlite_sequence " +
                        "WHERE name=? LIMIT 1",
                    arrayOf(table)
                ).use {
                    c ->
                    if (c.moveToFirst()) {
                        c.getLong(0)
                    } else {
                        0L
                    }
                }

            val maxId =
                db.rawQuery(
                    "SELECT COALESCE(MAX(id),0) " +
                        "FROM $table",
                    null
                ).use {
                    c ->
                    if (c.moveToFirst()) {
                        c.getLong(0)
                    } else {
                        0L
                    }
                }

            val target =
                maxOf(
                    currentSeq,
                    maxId,
                    base
                )

            val changed =
                db.update(
                    "sqlite_sequence",
                    ContentValues().apply {
                        put(
                            "seq",
                            target
                        )
                    },
                    "name=?",
                    arrayOf(table)
                )

            if (changed == 0) {
                db.insert(
                    "sqlite_sequence",
                    null,
                    ContentValues().apply {
                        put(
                            "name",
                            table
                        )
                        put(
                            "seq",
                            target
                        )
                    }
                )
            }
        }
    }

    private fun deviceIdRangePrefix():
        Long {
        val digest =
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(
                    deviceId.toByteArray(
                        Charsets.UTF_8
                    )
                )

        var value = 0L

        for (i in 0 until 8) {
            value =
                (value shl 8) or
                    (
                        digest[i]
                            .toLong() and
                            0xffL
                        )
        }

        return (
            value and
                Long.MAX_VALUE
            ) %
            100_000_000L
    }

    private fun setRemoteApply(
        db: SQLiteDatabase,
        enabled: Boolean
    ) {
        db.execSQL(
            """
            UPDATE sync_context
            SET remote_apply=?,
                updated_at=?
            WHERE id=1
            """.trimIndent(),
            arrayOf<Any?>(
                if (enabled) 1 else 0,
                System.currentTimeMillis()
            )
        )
    }

    private fun cursorRowAsJson(
        c: Cursor
    ): JSONObject {
        val obj =
            JSONObject()

        for (
            i in 0 until
                c.columnCount
        ) {
            val name =
                c.getColumnName(i)

            if (
                name ==
                "sync_status"
            ) {
                continue
            }

            when (
                c.getType(i)
            ) {
                Cursor.FIELD_TYPE_INTEGER ->
                    obj.put(
                        name,
                        c.getLong(i)
                    )

                Cursor.FIELD_TYPE_FLOAT ->
                    obj.put(
                        name,
                        c.getDouble(i)
                    )

                Cursor.FIELD_TYPE_STRING ->
                    obj.put(
                        name,
                        c.getString(i)
                    )

                Cursor.FIELD_TYPE_NULL ->
                    obj.put(
                        name,
                        JSONObject.NULL
                    )

                else ->
                    obj.put(
                        name,
                        c.getString(i)
                    )
            }
        }

        return obj
    }

    private fun jsonToContentValues(
        obj: JSONObject,
        allowedColumns: Set<String>? = null,
        tableName: String = ""
    ): ContentValues {
        val values =
            ContentValues()

        val keys =
            obj.keys()

        while (keys.hasNext()) {
            val key =
                keys.next()

            if (
                allowedColumns != null &&
                key !in allowedColumns
            ) {
                Log.w(
                    "TianXianSync",
                    "忽略未知云端字段 " +
                        "$tableName.$key"
                )
                continue
            }

            val value =
                obj.opt(key)

            when (value) {
                null,
                JSONObject.NULL ->
                    values.putNull(key)

                is Int ->
                    values.put(
                        key,
                        value
                    )

                is Long ->
                    values.put(
                        key,
                        value
                    )

                is Double ->
                    values.put(
                        key,
                        value
                    )

                is Float ->
                    values.put(
                        key,
                        value
                    )

                is Boolean ->
                    values.put(
                        key,
                        if (value) 1 else 0
                    )

                else ->
                    values.put(
                        key,
                        value.toString()
                    )
            }
        }

        return values
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
            arrayOf<Any?>(
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
            arrayOf<Any?>(
                clean,
                System.currentTimeMillis()
            )
        )
    }

    private fun seedFruits(db: SQLiteDatabase) {
        listOf(
            "巨峰葡萄",
            "阳光玫瑰",
            "蓝莓",
            "草莓",
            "西瓜",
            "芒果",
            "荔枝"
        ).forEachIndexed {
            index,
            name ->
            val values = baseSyncValues().apply {
                put("name", name)
                put("default_unit", "件")
                put("enabled", 1)
                put(
                    "sort_order",
                    index.toLong()
                )
            }
            db.insertWithOnConflict(
                "fruit",
                null,
                values,
                SQLiteDatabase.CONFLICT_IGNORE
            )
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
        "SELECT id,name,default_unit FROM fruit " +
            "WHERE enabled=1 " +
            "ORDER BY sort_order ASC,id ASC",
        null
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                add(
                    FruitOption(
                        c.long("id"),
                        c.str("name"),
                        c.str("default_unit")
                    )
                )
            }
        }
    }

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

            val nextOrder =
                readableDatabase.rawQuery(
                    "SELECT COALESCE(MAX(sort_order),0)+1 AS n " +
                        "FROM fruit WHERE enabled=1",
                    null
                ).use {
                    c ->
                    if (c.moveToFirst()) {
                        c.long("n")
                    } else {
                        1L
                    }
                }

            writableDatabase.update(
                "fruit",
                ContentValues().apply {
                    put("enabled", 1)
                    put("default_unit", defaultUnit)
                    put(
                        "sort_order",
                        nextOrder
                    )
                    put("sync_status", 2)
                    put("updated_at", System.currentTimeMillis())
                },
                "id=?",
                arrayOf(id.toString())
            )
            return id
        }

        val nextOrder =
            readableDatabase.rawQuery(
                "SELECT COALESCE(MAX(sort_order),0)+1 AS n FROM fruit",
                null
            ).use {
                c ->
                if (c.moveToFirst()) {
                    c.long("n")
                } else {
                    1L
                }
            }

        return writableDatabase.insert("fruit", null, baseSyncValues().apply {
            put("name", clean)
            put("default_unit", defaultUnit)
            put("enabled", 1)
            put("sort_order", nextOrder)
        })
    }

    fun getAllFruits(includeDisabled: Boolean = false): List<FruitOption> =
        readableDatabase.rawQuery(
            if (includeDisabled)
                "SELECT id,name,default_unit FROM fruit " +
                    "ORDER BY enabled DESC,sort_order ASC,id ASC"
            else
                "SELECT id,name,default_unit FROM fruit " +
                    "WHERE enabled=1 " +
                    "ORDER BY sort_order ASC,id ASC",
            null
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(FruitOption(c.long("id"), c.str("name"), c.str("default_unit")))
                }
            }
        }

    fun getFruitAdminRecords(): List<FruitAdminRecord> = readableDatabase.rawQuery(
        "SELECT id,name,default_unit,enabled FROM fruit " +
            "ORDER BY enabled DESC,sort_order ASC,id ASC",
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

    fun reorderFruits(
        orderedIds: List<Long>
    ): Boolean {
        if (orderedIds.isEmpty()) {
            return true
        }

        val db =
            writableDatabase

        db.beginTransaction()

        return try {
            val now =
                System.currentTimeMillis()

            orderedIds.forEachIndexed {
                index,
                id ->
                db.update(
                    "fruit",
                    ContentValues().apply {
                        put(
                            "sort_order",
                            index.toLong()
                        )
                        put(
                            "sync_status",
                            2
                        )
                        put(
                            "updated_at",
                            now
                        )
                    },
                    "id=?",
                    arrayOf(
                        id.toString()
                    )
                )
            }

            db.setTransactionSuccessful()
            true
        } finally {
            db.endTransaction()
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

    fun restoreFruit(
        id: Long
    ): Boolean {
        val nextOrder =
            readableDatabase.rawQuery(
                "SELECT COALESCE(MAX(sort_order),0)+1 AS n " +
                    "FROM fruit WHERE enabled=1",
                null
            ).use {
                c ->
                if (c.moveToFirst()) {
                    c.long("n")
                } else {
                    1L
                }
            }

        return writableDatabase.update(
            "fruit",
            ContentValues().apply {
                put("enabled", 1)
                put(
                    "sort_order",
                    nextOrder
                )
                put("sync_status", 2)
                put("updated_at", System.currentTimeMillis())
            },
            "id=?",
            arrayOf(id.toString())
        ) > 0
    }

    fun getStores(): List<StoreOption> = readableDatabase.rawQuery(
        "SELECT id,name,address FROM store " +
            "WHERE enabled=1 AND deleted=0 " +
            "ORDER BY sort_order ASC,id ASC",
        null
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                add(
                    StoreOption(
                        c.long("id"),
                        c.str("name"),
                        c.str("address")
                    )
                )
            }
        }
    }

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

    fun addStore(
        name: String,
        address: String
    ): Long {
        val clean =
            name.trim()

        if (clean.isBlank()) {
            return -1
        }

        val firstOrder =
            readableDatabase.rawQuery(
                "SELECT COALESCE(MIN(sort_order),0)-1 AS n FROM store",
                null
            ).use {
                c ->
                if (c.moveToFirst()) {
                    c.long("n")
                } else {
                    -1L
                }
            }

        return writableDatabase.insertWithOnConflict(
            "store",
            null,
            baseSyncValues().apply {
                put("name", clean)
                put(
                    "address",
                    address.trim()
                )
                put("enabled", 1)
                put("deleted", 0)
                put(
                    "sort_order",
                    firstOrder
                )
            },
            SQLiteDatabase.CONFLICT_IGNORE
        )
    }

    fun reorderStores(
        orderedIds: List<Long>
    ): Boolean {
        if (orderedIds.isEmpty()) {
            return true
        }

        val db =
            writableDatabase

        db.beginTransaction()

        return try {
            val now =
                System.currentTimeMillis()

            orderedIds.forEachIndexed {
                index,
                id ->
                db.update(
                    "store",
                    ContentValues().apply {
                        put(
                            "sort_order",
                            index.toLong()
                        )
                        put(
                            "sync_status",
                            2
                        )
                        put(
                            "updated_at",
                            now
                        )
                    },
                    "id=?",
                    arrayOf(
                        id.toString()
                    )
                )
            }

            db.setTransactionSuccessful()
            true
        } finally {
            db.endTransaction()
        }
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
        remark: String,
        purchaseType: String =
            PurchaseTypes.PLANNED,
        recorderUsername: String = "",
        recorderDisplayName: String = ""
    ): Long {
        if (lines.isEmpty()) return -1

        val activeBuyer =
            getPartnerById(
                buyer.id
            )
                ?: return -1

        val db =
            writableDatabase

        db.beginTransaction()

        try {
            val total =
                lines.sumOf {
                    it.totalCost
                }

            val orderId =
                db.insert(
                    "purchase_order",
                    null,
                    baseSyncValues().apply {
                        put(
                            "date",
                            date
                        )
                        put(
                            "buyer_id",
                            activeBuyer.id
                        )
                        put(
                            "buyer_name",
                            activeBuyer.name
                        )
                        put(
                            "store_id",
                            0
                        )
                        put(
                            "store_name",
                            "共用货品"
                        )
                        put(
                            "total_cost",
                            total
                        )
                        put(
                            "remark",
                            remark.trim()
                        )
                        put(
                            "deleted",
                            0
                        )
                    }
                )

            if (orderId <= 0) {
                return -1
            }

            lines.forEach {
                line ->
                val price =
                    if (
                        line.quantity >
                        0
                    ) {
                        line.totalCost /
                            line.quantity
                    } else {
                        0.0
                    }

                db.insert(
                    "purchase_item",
                    null,
                    baseSyncValues().apply {
                        put(
                            "order_id",
                            orderId
                        )
                        put(
                            "fruit_id",
                            line.fruit.id
                        )
                        put(
                            "fruit_name",
                            line.fruit.name
                        )
                        put(
                            "unit",
                            line.unit
                        )
                        put(
                            "quantity",
                            line.quantity
                        )
                        put(
                            "total_cost",
                            line.totalCost
                        )
                        put(
                            "unit_price",
                            price
                        )
                        put(
                            "deleted",
                            0
                        )
                    }
                )
            }

            // Purchase changes affect both profit allocation and the daily cash/fund settlement.
            // If the day was already settled, force derived results to be regenerated
            // instead of keeping a stale settlement against the old purchase amount.
            invalidateProfitDistributionForDate(db, date)
            invalidateCashSettlementForDate(db, date)

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
        remark: String,
        purchaseType: String =
            PurchaseTypes.PLANNED,
        recorderUsername: String = "",
        recorderDisplayName: String = ""
    ): Boolean {
        if (lines.isEmpty()) return false

        val oldSnapshot =
            readableDatabase.rawQuery(
                "SELECT date,buyer_id,buyer_name " +
                    "FROM purchase_order " +
                    "WHERE id=? AND deleted=0 LIMIT 1",
                arrayOf(
                    id.toString()
                )
            ).use {
                c ->
                if (c.moveToFirst()) {
                    Triple(
                        c.str("date"),
                        c.long("buyer_id"),
                        c.str("buyer_name")
                    )
                } else {
                    null
                }
            }
                ?: return false

        val oldDate = oldSnapshot.first
        val oldBuyer =
            PartnerOption(
                oldSnapshot.second,
                oldSnapshot.third
            )

        val activeBuyer =
            getPartnerById(
                buyer.id
            )

        val resolvedBuyer =
            when {
                activeBuyer != null ->
                    activeBuyer

                buyer.id ==
                    oldBuyer.id ->
                    oldBuyer

                else ->
                    return false
            }

        val db =
            writableDatabase

        db.beginTransaction()

        try {
            val now =
                System.currentTimeMillis()

            val total =
                lines.sumOf {
                    it.totalCost
                }

            val changed =
                db.update(
                    "purchase_order",
                    ContentValues().apply {
                        put(
                            "date",
                            date
                        )
                        put(
                            "buyer_id",
                            resolvedBuyer.id
                        )
                        put(
                            "buyer_name",
                            resolvedBuyer.name
                        )
                        put(
                            "store_id",
                            0
                        )
                        put(
                            "store_name",
                            "共用货品"
                        )
                        put(
                            "total_cost",
                            total
                        )
                        put(
                            "remark",
                            remark.trim()
                        )
                        put(
                            "sync_status",
                            2
                        )
                        put(
                            "updated_at",
                            now
                        )
                    },
                    "id=? AND deleted=0",
                    arrayOf(
                        id.toString()
                    )
                )

            if (changed <= 0) {
                return false
            }

            db.update(
                "purchase_item",
                ContentValues().apply {
                    put(
                        "deleted",
                        1
                    )
                    put(
                        "sync_status",
                        2
                    )
                    put(
                        "updated_at",
                        now
                    )
                },
                "order_id=? AND deleted=0",
                arrayOf(
                    id.toString()
                )
            )

            lines.forEach {
                line ->
                val price =
                    if (
                        line.quantity >
                        0
                    ) {
                        line.totalCost /
                            line.quantity
                    } else {
                        0.0
                    }

                db.insert(
                    "purchase_item",
                    null,
                    baseSyncValues().apply {
                        put(
                            "order_id",
                            id
                        )
                        put(
                            "fruit_id",
                            line.fruit.id
                        )
                        put(
                            "fruit_name",
                            line.fruit.name
                        )
                        put(
                            "unit",
                            line.unit
                        )
                        put(
                            "quantity",
                            line.quantity
                        )
                        put(
                            "total_cost",
                            line.totalCost
                        )
                        put(
                            "unit_price",
                            price
                        )
                        put(
                            "deleted",
                            0
                        )
                    }
                )
            }

            invalidateProfitDistributionForDate(db, date)
            invalidateCashSettlementForDate(db, date)
            if (oldDate != date) {
                invalidateProfitDistributionForDate(db, oldDate)
                invalidateCashSettlementForDate(db, oldDate)
            }

            db.setTransactionSuccessful()

            return true
        } finally {
            db.endTransaction()
        }
    }

    fun deletePurchaseOrder(
        id: Long
    ) {
        val now = System.currentTimeMillis()
        val db = writableDatabase
        val orderSyncId = getPurchaseOrderSyncId(db, id)
        val oldDate =
            db.rawQuery(
                "SELECT date FROM purchase_order WHERE id=? LIMIT 1",
                arrayOf(id.toString())
            ).use { c ->
                if (c.moveToFirst()) c.str("date") else ""
            }

        db.beginTransaction()

        try {
            val affectedPlanIds = mutableSetOf<Long>()

            if (orderSyncId.isNotBlank()) {
                val linkedPlanItems =
                    db.rawQuery(
                        "SELECT ppi.id,ppi.plan_id,ppi.sync_id " +
                            "FROM purchase_plan_item ppi " +
                            "JOIN purchase_collaboration pc " +
                            "ON pc.plan_item_sync_id=ppi.sync_id " +
                            "WHERE pc.purchase_order_sync_id=? " +
                            "AND pc.deleted=0 AND ppi.deleted=0",
                        arrayOf(orderSyncId)
                    ).use { c ->
                        buildList {
                            while (c.moveToNext()) {
                                add(
                                    Triple(
                                        c.long("id"),
                                        c.long("plan_id"),
                                        c.str("sync_id")
                                    )
                                )
                            }
                        }
                    }

                linkedPlanItems.forEach { linked ->
                    affectedPlanIds += linked.second
                    db.update(
                        "purchase_plan_item",
                        ContentValues().apply {
                            put("status", 0)
                            put("sync_status", 2)
                            put("updated_at", now)
                        },
                        "id=?",
                        arrayOf(linked.first.toString())
                    )

                    db.update(
                        "purchase_collaboration",
                        ContentValues().apply {
                            put("actual_quantity", 0)
                            put("actual_amount", 0)
                            put("completed_by_username", "")
                            put("completed_by_display_name", "")
                            put("completed_at", 0)
                            put("purchase_order_sync_id", "")
                            put("sync_status", 2)
                            put("updated_at", now)
                        },
                        "plan_item_sync_id=? AND deleted=0",
                        arrayOf(linked.third)
                    )
                }
            }

            db.update(
                "purchase_order",
                ContentValues().apply {
                    put("deleted", 1)
                    put("sync_status", 2)
                    put("updated_at", now)
                },
                "id=?",
                arrayOf(id.toString())
            )

            db.update(
                "purchase_item",
                ContentValues().apply {
                    put("deleted", 1)
                    put("sync_status", 2)
                    put("updated_at", now)
                },
                "order_id=?",
                arrayOf(id.toString())
            )

            if (orderSyncId.isNotBlank()) {
                db.update(
                    "purchase_activity",
                    ContentValues().apply {
                        put("deleted", 1)
                        put("sync_status", 2)
                        put("updated_at", now)
                    },
                    "order_sync_id=?",
                    arrayOf(orderSyncId)
                )
            }

            affectedPlanIds.forEach { planId ->
                refreshPurchasePlanStatus(db, planId)
            }

            if (oldDate.isNotBlank()) {
                invalidateProfitDistributionForDate(db, oldDate)
                invalidateCashSettlementForDate(db, oldDate)
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getPurchasePlan(
        date: String
    ): PurchasePlanDetail? {
        val plan =
            readableDatabase.rawQuery(
                "SELECT * FROM purchase_plan " +
                    "WHERE plan_date=? AND deleted=0 LIMIT 1",
                arrayOf(
                    date
                )
            ).use {
                c ->
                if (
                    !c.moveToFirst()
                ) {
                    null
                } else {
                    PurchasePlanRecord(
                        c.long("id"),
                        c.str("plan_date"),
                        c.int("status"),
                        c.str("note")
                    )
                }
            }
                ?: return null

        val items =
            readableDatabase.rawQuery(
                """
                SELECT
                    ppi.*,
                    COALESCE(pc.estimated_amount,0) AS estimated_amount,
                    COALESCE(pc.actual_quantity,0) AS actual_quantity,
                    COALESCE(pc.actual_amount,0) AS actual_amount,
                    COALESCE(pc.buyer_id,0) AS collab_buyer_id,
                    COALESCE(pc.buyer_name,'') AS collab_buyer_name,
                    COALESCE(pc.completed_by_username,'') AS completed_by_username,
                    COALESCE(pc.completed_by_display_name,'') AS completed_by_display_name,
                    COALESCE(pc.completed_at,0) AS completed_at,
                    COALESCE(pc.purchase_order_sync_id,'') AS purchase_order_sync_id
                FROM purchase_plan_item ppi
                LEFT JOIN purchase_collaboration pc
                  ON pc.plan_item_sync_id=ppi.sync_id
                 AND pc.deleted=0
                WHERE
                    ppi.plan_id=?
                    AND ppi.deleted=0
                ORDER BY
                    CASE WHEN ppi.status=0 THEN 0 ELSE 1 END,
                    ppi.id
                """.trimIndent(),
                arrayOf(
                    plan.id.toString()
                )
            ).use {
                c ->
                buildList {
                    while (
                        c.moveToNext()
                    ) {
                        add(
                            PurchasePlanItemRecord(
                                id =
                                    c.long("id"),
                                planId =
                                    c.long("plan_id"),
                                fruitId =
                                    c.long("fruit_id"),
                                fruitName =
                                    c.str("fruit_name"),
                                quantity =
                                    c.dbl("quantity"),
                                unit =
                                    c.str("unit"),
                                remark =
                                    c.str("remark"),
                                status =
                                    c.int("status"),
                                syncId =
                                    c.str("sync_id"),
                                estimatedAmount =
                                    c.dbl("estimated_amount"),
                                actualQuantity =
                                    c.dbl("actual_quantity"),
                                actualAmount =
                                    c.dbl("actual_amount"),
                                buyerId =
                                    c.long("collab_buyer_id"),
                                buyerName =
                                    c.str("collab_buyer_name"),
                                completedByUsername =
                                    c.str("completed_by_username"),
                                completedByDisplayName =
                                    c.str("completed_by_display_name"),
                                completedAt =
                                    c.long("completed_at"),
                                purchaseOrderSyncId =
                                    c.str("purchase_order_sync_id")
                            )
                        )
                    }
                }
            }

        return PurchasePlanDetail(
            plan,
            items
        )
    }

    fun upsertCollaborationPlanItem(
        date: String,
        itemId: Long?,
        fruit: FruitOption,
        quantity: Double,
        unit: String,
        estimatedAmount: Double,
        remark: String = ""
    ): Long {
        if (
            quantity <= 0 ||
            estimatedAmount < 0
        ) {
            return -1
        }

        val db =
            writableDatabase
        val now =
            System.currentTimeMillis()

        db.beginTransaction()

        try {
            val existingPlanId =
                db.rawQuery(
                    "SELECT id FROM purchase_plan " +
                        "WHERE plan_date=? LIMIT 1",
                    arrayOf(
                        date
                    )
                ).use {
                    c ->
                    if (
                        c.moveToFirst()
                    ) {
                        c.long("id")
                    } else {
                        null
                    }
                }

            val planId =
                if (
                    existingPlanId == null
                ) {
                    db.insert(
                        "purchase_plan",
                        null,
                        baseSyncValues().apply {
                            put(
                                "plan_date",
                                date
                            )
                            put(
                                "status",
                                0
                            )
                            put(
                                "note",
                                ""
                            )
                            put(
                                "deleted",
                                0
                            )
                        }
                    )
                } else {
                    db.update(
                        "purchase_plan",
                        ContentValues().apply {
                            put(
                                "deleted",
                                0
                            )
                            put(
                                "sync_status",
                                2
                            )
                            put(
                                "updated_at",
                                now
                            )
                        },
                        "id=?",
                        arrayOf(
                            existingPlanId
                                .toString()
                        )
                    )
                    existingPlanId
                }

            if (
                planId <= 0
            ) {
                return -1
            }

            val resolvedItemId =
                if (
                    itemId != null
                ) {
                    val currentStatus =
                        db.rawQuery(
                            "SELECT status FROM purchase_plan_item " +
                                "WHERE id=? AND plan_id=? " +
                                "AND deleted=0 LIMIT 1",
                            arrayOf(
                                itemId.toString(),
                                planId.toString()
                            )
                        ).use {
                            c ->
                            if (
                                c.moveToFirst()
                            ) {
                                c.int("status")
                            } else {
                                null
                            }
                        }

                    if (
                        currentStatus == null ||
                        currentStatus != 0
                    ) {
                        return -1
                    }

                    val changed =
                        db.update(
                            "purchase_plan_item",
                            ContentValues().apply {
                                put(
                                    "fruit_id",
                                    fruit.id
                                )
                                put(
                                    "fruit_name",
                                    fruit.name
                                )
                                put(
                                    "quantity",
                                    quantity
                                )
                                put(
                                    "unit",
                                    unit
                                )
                                put(
                                    "remark",
                                    remark.trim()
                                )
                                put(
                                    "sync_status",
                                    2
                                )
                                put(
                                    "updated_at",
                                    now
                                )
                            },
                            "id=? AND deleted=0",
                            arrayOf(
                                itemId.toString()
                            )
                        )

                    if (
                        changed <= 0
                    ) {
                        return -1
                    }

                    itemId
                } else {
                    val duplicateId =
                        db.rawQuery(
                            """
                            SELECT ppi.id
                            FROM purchase_plan_item ppi
                            WHERE
                                ppi.plan_id=?
                                AND ppi.fruit_id=?
                                AND ppi.unit=?
                                AND ppi.status=0
                                AND ppi.deleted=0
                            LIMIT 1
                            """.trimIndent(),
                            arrayOf(
                                planId.toString(),
                                fruit.id.toString(),
                                unit
                            )
                        ).use {
                            c ->
                            if (
                                c.moveToFirst()
                            ) {
                                c.long("id")
                            } else {
                                null
                            }
                        }

                    if (
                        duplicateId != null
                    ) {
                        db.update(
                            "purchase_plan_item",
                            ContentValues().apply {
                                put(
                                    "fruit_name",
                                    fruit.name
                                )
                                put(
                                    "quantity",
                                    quantity
                                )
                                put(
                                    "remark",
                                    remark.trim()
                                )
                                put(
                                    "sync_status",
                                    2
                                )
                                put(
                                    "updated_at",
                                    now
                                )
                            },
                            "id=?",
                            arrayOf(
                                duplicateId.toString()
                            )
                        )
                        duplicateId
                    } else {
                        db.insert(
                            "purchase_plan_item",
                            null,
                            baseSyncValues().apply {
                                put(
                                    "plan_id",
                                    planId
                                )
                                put(
                                    "fruit_id",
                                    fruit.id
                                )
                                put(
                                    "fruit_name",
                                    fruit.name
                                )
                                put(
                                    "quantity",
                                    quantity
                                )
                                put(
                                    "unit",
                                    unit
                                )
                                put(
                                    "remark",
                                    remark.trim()
                                )
                                put(
                                    "status",
                                    0
                                )
                                put(
                                    "deleted",
                                    0
                                )
                            }
                        )
                    }
                }

            if (
                resolvedItemId <= 0
            ) {
                return -1
            }

            val planItemSyncId =
                db.rawQuery(
                    "SELECT sync_id FROM purchase_plan_item " +
                        "WHERE id=? LIMIT 1",
                    arrayOf(
                        resolvedItemId.toString()
                    )
                ).use {
                    c ->
                    if (
                        c.moveToFirst()
                    ) {
                        c.str("sync_id")
                    } else {
                        ""
                    }
                }

            if (
                planItemSyncId.isBlank()
            ) {
                return -1
            }

            val collaborationId =
                db.rawQuery(
                    "SELECT id FROM purchase_collaboration " +
                        "WHERE plan_item_sync_id=? LIMIT 1",
                    arrayOf(
                        planItemSyncId
                    )
                ).use {
                    c ->
                    if (
                        c.moveToFirst()
                    ) {
                        c.long("id")
                    } else {
                        null
                    }
                }

            if (
                collaborationId == null
            ) {
                val insertedCollaboration =
                    db.insert(
                    "purchase_collaboration",
                    null,
                    baseSyncValues().apply {
                        put(
                            "plan_item_sync_id",
                            planItemSyncId
                        )
                        put(
                            "estimated_amount",
                            roundMoney(
                                estimatedAmount
                            )
                        )
                        put(
                            "actual_quantity",
                            0
                        )
                        put(
                            "actual_amount",
                            0
                        )
                        put(
                            "buyer_id",
                            0
                        )
                        put(
                            "buyer_name",
                            ""
                        )
                        put(
                            "completed_by_username",
                            ""
                        )
                        put(
                            "completed_by_display_name",
                            ""
                        )
                        put(
                            "completed_at",
                            0
                        )
                        put(
                            "purchase_order_sync_id",
                            ""
                        )
                        put(
                            "deleted",
                            0
                        )
                    }
                )

                if (
                    insertedCollaboration <= 0
                ) {
                    return -1
                }
            } else {
                db.update(
                    "purchase_collaboration",
                    ContentValues().apply {
                        put(
                            "estimated_amount",
                            roundMoney(
                                estimatedAmount
                            )
                        )
                        put(
                            "deleted",
                            0
                        )
                        put(
                            "sync_status",
                            2
                        )
                        put(
                            "updated_at",
                            now
                        )
                    },
                    "id=?",
                    arrayOf(
                        collaborationId
                            .toString()
                    )
                )
            }

            refreshPurchasePlanStatus(
                db,
                planId
            )

            db.setTransactionSuccessful()

            return resolvedItemId
        } finally {
            db.endTransaction()
        }
    }

    fun upsertPurchaseDraftToCollaboration(
        date: String,
        itemId: Long?,
        fruit: FruitOption,
        quantity: Double,
        unit: String,
        estimatedAmount: Double,
        buyer: PartnerOption? = null
    ): Long {
        if (quantity <= 0 || estimatedAmount < 0) {
            return -1
        }

        val existing =
            if (itemId != null) {
                readableDatabase.rawQuery(
                    "SELECT ppi.id,ppi.remark " +
                        "FROM purchase_plan_item ppi " +
                        "JOIN purchase_plan pp ON pp.id=ppi.plan_id " +
                        "WHERE ppi.id=? AND pp.plan_date=? " +
                        "AND ppi.deleted=0 AND ppi.status=0 LIMIT 1",
                    arrayOf(
                        itemId.toString(),
                        date
                    )
                ).use { c ->
                    if (c.moveToFirst()) {
                        c.long("id") to c.str("remark")
                    } else {
                        null
                    }
                }
            } else {
                readableDatabase.rawQuery(
                    "SELECT ppi.id,ppi.remark " +
                        "FROM purchase_plan_item ppi " +
                        "JOIN purchase_plan pp ON pp.id=ppi.plan_id " +
                        "WHERE pp.plan_date=? AND pp.deleted=0 " +
                        "AND ppi.fruit_id=? AND ppi.deleted=0 " +
                        "AND ppi.status=0 ORDER BY ppi.id LIMIT 1",
                    arrayOf(
                        date,
                        fruit.id.toString()
                    )
                ).use { c ->
                    if (c.moveToFirst()) {
                        c.long("id") to c.str("remark")
                    } else {
                        null
                    }
                }
            }

        val resolvedItemId =
            upsertCollaborationPlanItem(
                date = date,
                itemId = existing?.first,
                fruit = fruit,
                quantity = quantity,
                unit = unit,
                estimatedAmount = estimatedAmount,
                remark = existing?.second.orEmpty()
            )

        if (resolvedItemId <= 0) {
            return resolvedItemId
        }

        val now = System.currentTimeMillis()
        val planItemSyncId =
            readableDatabase.rawQuery(
                "SELECT sync_id FROM purchase_plan_item " +
                    "WHERE id=? AND deleted=0 AND status=0 LIMIT 1",
                arrayOf(resolvedItemId.toString())
            ).use { c ->
                if (c.moveToFirst()) c.str("sync_id") else ""
            }

        if (planItemSyncId.isNotBlank()) {
            writableDatabase.update(
                "purchase_collaboration",
                ContentValues().apply {
                    put("buyer_id", buyer?.id ?: 0L)
                    put("buyer_name", buyer?.name.orEmpty())
                    put("sync_status", 2)
                    put("updated_at", now)
                },
                "plan_item_sync_id=? AND deleted=0",
                arrayOf(planItemSyncId)
            )
        }

        return resolvedItemId
    }

    fun updatePurchasePlanNote(
        date: String,
        note: String
    ): Boolean {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        val planId =
            db.rawQuery(
                "SELECT id FROM purchase_plan WHERE plan_date=? AND deleted=0 LIMIT 1",
                arrayOf(date)
            ).use { c ->
                if (c.moveToFirst()) c.long("id") else null
            } ?: return note.isBlank()

        return db.update(
            "purchase_plan",
            ContentValues().apply {
                put("note", note.trim())
                put("sync_status", 2)
                put("updated_at", now)
            },
            "id=?",
            arrayOf(planId.toString())
        ) > 0
    }

    fun deleteCollaborationPlanItem(
        itemId: Long
    ): Boolean {
        val db =
            writableDatabase
        val now =
            System.currentTimeMillis()

        val item =
            db.rawQuery(
                "SELECT plan_id,sync_id,status " +
                    "FROM purchase_plan_item " +
                    "WHERE id=? AND deleted=0 LIMIT 1",
                arrayOf(
                    itemId.toString()
                )
            ).use {
                c ->
                if (
                    c.moveToFirst()
                ) {
                    Triple(
                        c.long("plan_id"),
                        c.str("sync_id"),
                        c.int("status")
                    )
                } else {
                    null
                }
            }
                ?: return false

        if (
            item.third != 0
        ) {
            return false
        }

        db.beginTransaction()

        try {
            val changed =
                db.update(
                    "purchase_plan_item",
                    ContentValues().apply {
                        put(
                            "deleted",
                            1
                        )
                        put(
                            "sync_status",
                            2
                        )
                        put(
                            "updated_at",
                            now
                        )
                    },
                    "id=?",
                    arrayOf(
                        itemId.toString()
                    )
                )

            db.update(
                "purchase_collaboration",
                ContentValues().apply {
                    put(
                        "deleted",
                        1
                    )
                    put(
                        "sync_status",
                        2
                    )
                    put(
                        "updated_at",
                        now
                    )
                },
                "plan_item_sync_id=?",
                arrayOf(
                    item.second
                )
            )

            refreshPurchasePlanStatus(
                db,
                item.first
            )

            db.setTransactionSuccessful()

            return changed > 0
        } finally {
            db.endTransaction()
        }
    }

    fun completeCollaborationPlanItem(
        itemId: Long,
        buyer: PartnerOption,
        actualQuantity: Double,
        actualAmount: Double,
        recorderUsername: String = "",
        recorderDisplayName: String = ""
    ): CollaborationCompleteResult {
        if (
            actualQuantity <= 0 ||
            actualAmount <= 0
        ) {
            return CollaborationCompleteResult(
                false,
                "实际数量和金额必须大于0"
            )
        }

        val activeBuyer =
            getPartnerById(
                buyer.id
            )
                ?: return CollaborationCompleteResult(
                    false,
                    "采购人已失效，请重新选择"
                )

        val db =
            writableDatabase

        db.beginTransaction()

        try {
            val row =
                db.rawQuery(
                    """
                    SELECT
                        ppi.plan_id,
                        ppi.fruit_id,
                        ppi.fruit_name,
                        ppi.unit,
                        ppi.status,
                        ppi.sync_id,
                        pp.plan_date
                    FROM purchase_plan_item ppi
                    JOIN purchase_plan pp
                      ON pp.id=ppi.plan_id
                    WHERE
                        ppi.id=?
                        AND ppi.deleted=0
                        AND pp.deleted=0
                    LIMIT 1
                    """.trimIndent(),
                    arrayOf(
                        itemId.toString()
                    )
                ).use {
                    c ->
                    if (
                        c.moveToFirst()
                    ) {
                        arrayOf<Any>(
                            c.long("plan_id"),
                            c.long("fruit_id"),
                            c.str("fruit_name"),
                            c.str("unit"),
                            c.int("status"),
                            c.str("sync_id"),
                            c.str("plan_date")
                        )
                    } else {
                        null
                    }
                }
                    ?: return CollaborationCompleteResult(
                        false,
                        "采购计划项目不存在"
                    )

            val planId =
                row[0] as Long
            val fruitId =
                row[1] as Long
            val fruitName =
                row[2] as String
            val unit =
                row[3] as String
            val status =
                row[4] as Int
            val planItemSyncId =
                row[5] as String
            val date =
                row[6] as String

            if (
                status == 1
            ) {
                return CollaborationCompleteResult(
                    false,
                    "该水果已经完成采购，请刷新后查看"
                )
            }

            if (
                status != 0
            ) {
                return CollaborationCompleteResult(
                    false,
                    "当前项目不可完成采购"
                )
            }

            val orderSyncId =
                "collab-order-" +
                    planItemSyncId
            val purchaseItemSyncId =
                "collab-item-" +
                    planItemSyncId
            val now =
                System.currentTimeMillis()

            var orderId =
                db.rawQuery(
                    "SELECT id FROM purchase_order " +
                        "WHERE sync_id=? LIMIT 1",
                    arrayOf(
                        orderSyncId
                    )
                ).use {
                    c ->
                    if (
                        c.moveToFirst()
                    ) {
                        c.long("id")
                    } else {
                        -1L
                    }
                }

            if (
                orderId <= 0
            ) {
                orderId =
                    db.insert(
                        "purchase_order",
                        null,
                        baseSyncValues().apply {
                            put(
                                "sync_id",
                                orderSyncId
                            )
                            put(
                                "date",
                                date
                            )
                            put(
                                "buyer_id",
                                activeBuyer.id
                            )
                            put(
                                "buyer_name",
                                activeBuyer.name
                            )
                            put(
                                "store_id",
                                0
                            )
                            put(
                                "store_name",
                                "共用货品"
                            )
                            put(
                                "total_cost",
                                roundMoney(
                                    actualAmount
                                )
                            )
                            put(
                                "remark",
                                "协作采购：$fruitName"
                            )
                            put(
                                "status",
                                0
                            )
                            put(
                                "deleted",
                                0
                            )
                        }
                    )
            } else {
                db.update(
                    "purchase_order",
                    ContentValues().apply {
                        put("date", date)
                        put("buyer_id", activeBuyer.id)
                        put("buyer_name", activeBuyer.name)
                        put("store_id", 0)
                        put("store_name", "共用货品")
                        put("total_cost", roundMoney(actualAmount))
                        put("remark", "协作采购：$fruitName")
                        put("status", 0)
                        put("deleted", 0)
                        put("sync_status", 2)
                        put("updated_at", now)
                    },
                    "id=?",
                    arrayOf(orderId.toString())
                )
            }

            if (
                orderId <= 0
            ) {
                return CollaborationCompleteResult(
                    false,
                    "正式采购单生成失败"
                )
            }

            val existingPurchaseItemId =
                db.rawQuery(
                    "SELECT id FROM purchase_item " +
                        "WHERE sync_id=? LIMIT 1",
                    arrayOf(
                        purchaseItemSyncId
                    )
                ).use { c ->
                    if (c.moveToFirst()) {
                        c.long("id")
                    } else {
                        -1L
                    }
                }

            val unitPrice =
                actualAmount /
                    actualQuantity

            if (
                existingPurchaseItemId <= 0
            ) {
                val purchaseItemId =
                    db.insert(
                        "purchase_item",
                        null,
                        baseSyncValues().apply {
                            put(
                                "sync_id",
                                purchaseItemSyncId
                            )
                            put(
                                "order_id",
                                orderId
                            )
                            put(
                                "fruit_id",
                                fruitId
                            )
                            put(
                                "fruit_name",
                                fruitName
                            )
                            put(
                                "unit",
                                unit
                            )
                            put(
                                "quantity",
                                actualQuantity
                            )
                            put(
                                "total_cost",
                                roundMoney(
                                    actualAmount
                                )
                            )
                            put(
                                "unit_price",
                                roundMoney(
                                    unitPrice
                                )
                            )
                            put(
                                "deleted",
                                0
                            )
                        }
                    )

                if (
                    purchaseItemId <= 0
                ) {
                    return CollaborationCompleteResult(
                        false,
                        "正式采购商品生成失败"
                    )
                }
            } else {
                db.update(
                    "purchase_item",
                    ContentValues().apply {
                        put("order_id", orderId)
                        put("fruit_id", fruitId)
                        put("fruit_name", fruitName)
                        put("unit", unit)
                        put("quantity", actualQuantity)
                        put("total_cost", roundMoney(actualAmount))
                        put("unit_price", roundMoney(unitPrice))
                        put("deleted", 0)
                        put("sync_status", 2)
                        put("updated_at", now)
                    },
                    "id=?",
                    arrayOf(existingPurchaseItemId.toString())
                )
            }

            val collaborationId =
                db.rawQuery(
                    "SELECT id FROM purchase_collaboration " +
                        "WHERE plan_item_sync_id=? LIMIT 1",
                    arrayOf(
                        planItemSyncId
                    )
                ).use {
                    c ->
                    if (
                        c.moveToFirst()
                    ) {
                        c.long("id")
                    } else {
                        null
                    }
                }

            if (
                collaborationId == null
            ) {
                val insertedCollaboration =
                    db.insert(
                    "purchase_collaboration",
                    null,
                    baseSyncValues().apply {
                        put(
                            "plan_item_sync_id",
                            planItemSyncId
                        )
                        put(
                            "estimated_amount",
                            0
                        )
                        put(
                            "actual_quantity",
                            actualQuantity
                        )
                        put(
                            "actual_amount",
                            roundMoney(
                                actualAmount
                            )
                        )
                        put(
                            "buyer_id",
                            activeBuyer.id
                        )
                        put(
                            "buyer_name",
                            activeBuyer.name
                        )
                        put(
                            "completed_by_username",
                            recorderUsername.trim()
                        )
                        put(
                            "completed_by_display_name",
                            recorderDisplayName.trim()
                        )
                        put(
                            "completed_at",
                            now
                        )
                        put(
                            "purchase_order_sync_id",
                            orderSyncId
                        )
                        put(
                            "deleted",
                            0
                        )
                    }
                )

                if (
                    insertedCollaboration <= 0
                ) {
                    return CollaborationCompleteResult(
                        false,
                        "协作采购状态保存失败"
                    )
                }
            } else {
                db.update(
                    "purchase_collaboration",
                    ContentValues().apply {
                        put(
                            "actual_quantity",
                            actualQuantity
                        )
                        put(
                            "actual_amount",
                            roundMoney(
                                actualAmount
                            )
                        )
                        put(
                            "buyer_id",
                            activeBuyer.id
                        )
                        put(
                            "buyer_name",
                            activeBuyer.name
                        )
                        put(
                            "completed_by_username",
                            recorderUsername.trim()
                        )
                        put(
                            "completed_by_display_name",
                            recorderDisplayName.trim()
                        )
                        put(
                            "completed_at",
                            now
                        )
                        put(
                            "purchase_order_sync_id",
                            orderSyncId
                        )
                        put(
                            "deleted",
                            0
                        )
                        put(
                            "sync_status",
                            2
                        )
                        put(
                            "updated_at",
                            now
                        )
                    },
                    "id=?",
                    arrayOf(
                        collaborationId.toString()
                    )
                )
            }

            db.update(
                "purchase_plan_item",
                ContentValues().apply {
                    put(
                        "status",
                        1
                    )
                    put(
                        "sync_status",
                        2
                    )
                    put(
                        "updated_at",
                        now
                    )
                },
                "id=? AND status=0 AND deleted=0",
                arrayOf(
                    itemId.toString()
                )
            )

            refreshPurchasePlanStatus(
                db,
                planId
            )

            db.setTransactionSuccessful()

            return CollaborationCompleteResult(
                true,
                "$fruitName 已完成采购并加入采购表",
                orderId
            )
        } finally {
            db.endTransaction()
        }
    }

    fun linkPurchaseOrderToCollaboration(
        orderId: Long,
        date: String,
        buyer: PartnerOption,
        lines: List<PurchaseLineInput>,
        recorderUsername: String = "",
        recorderDisplayName: String = ""
    ): Int {
        if (orderId <= 0 || lines.isEmpty()) {
            return 0
        }

        val activeBuyer =
            getPartnerById(buyer.id)
                ?: return 0

        val orderSyncId =
            getPurchaseOrderSyncId(
                writableDatabase,
                orderId
            )

        if (orderSyncId.isBlank()) {
            return 0
        }

        val targets =
            lines.mapNotNull { line ->
                val existing =
                    readableDatabase.rawQuery(
                        "SELECT ppi.id,ppi.sync_id,ppi.plan_id " +
                            "FROM purchase_plan_item ppi " +
                            "JOIN purchase_plan pp ON pp.id=ppi.plan_id " +
                            "WHERE pp.plan_date=? AND pp.deleted=0 " +
                            "AND ppi.fruit_id=? AND ppi.deleted=0 " +
                            "AND ppi.status=0 ORDER BY ppi.id LIMIT 1",
                        arrayOf(
                            date,
                            line.fruit.id.toString()
                        )
                    ).use { c ->
                        if (c.moveToFirst()) {
                            Triple(
                                c.long("id"),
                                c.str("sync_id"),
                                c.long("plan_id")
                            )
                        } else {
                            null
                        }
                    }

                val resolved =
                    existing ?: run {
                        val createdId =
                            upsertPurchaseDraftToCollaboration(
                                date = date,
                                itemId = null,
                                fruit = line.fruit,
                                quantity = line.quantity,
                                unit = line.unit,
                                estimatedAmount = line.totalCost
                            )

                        if (createdId <= 0) {
                            null
                        } else {
                            readableDatabase.rawQuery(
                                "SELECT id,sync_id,plan_id FROM purchase_plan_item " +
                                    "WHERE id=? AND deleted=0 LIMIT 1",
                                arrayOf(createdId.toString())
                            ).use { c ->
                                if (c.moveToFirst()) {
                                    Triple(
                                        c.long("id"),
                                        c.str("sync_id"),
                                        c.long("plan_id")
                                    )
                                } else {
                                    null
                                }
                            }
                        }
                    }

                resolved?.let {
                    CollaborationLinkTarget(
                        itemId = it.first,
                        itemSyncId = it.second,
                        planId = it.third,
                        line = line
                    )
                }
            }

        if (targets.isEmpty()) {
            return 0
        }

        val db = writableDatabase
        val now = System.currentTimeMillis()
        var linked = 0

        db.beginTransaction()
        try {
            targets.forEach { target ->
                val collaborationId =
                    db.rawQuery(
                        "SELECT id FROM purchase_collaboration " +
                            "WHERE plan_item_sync_id=? LIMIT 1",
                        arrayOf(target.itemSyncId)
                    ).use { c ->
                        if (c.moveToFirst()) c.long("id") else null
                    }

                val values =
                    ContentValues().apply {
                        put("estimated_amount", roundMoney(target.line.totalCost))
                        put("actual_quantity", target.line.quantity)
                        put("actual_amount", roundMoney(target.line.totalCost))
                        put("buyer_id", activeBuyer.id)
                        put("buyer_name", activeBuyer.name)
                        put("completed_by_username", recorderUsername.trim())
                        put("completed_by_display_name", recorderDisplayName.trim())
                        put("completed_at", now)
                        put("purchase_order_sync_id", orderSyncId)
                        put("deleted", 0)
                        put("sync_status", 2)
                        put("updated_at", now)
                    }

                if (collaborationId == null) {
                    db.insert(
                        "purchase_collaboration",
                        null,
                        baseSyncValues().apply {
                            put("plan_item_sync_id", target.itemSyncId)
                            putAll(values)
                        }
                    )
                } else {
                    db.update(
                        "purchase_collaboration",
                        values,
                        "id=?",
                        arrayOf(collaborationId.toString())
                    )
                }

                val changed =
                    db.update(
                        "purchase_plan_item",
                        ContentValues().apply {
                            put("status", 1)
                            put("sync_status", 2)
                            put("updated_at", now)
                        },
                        "id=? AND status=0 AND deleted=0",
                        arrayOf(target.itemId.toString())
                    )

                if (changed > 0) {
                    linked++
                }
            }

            targets.map { it.planId }.distinct().forEach { planId ->
                refreshPurchasePlanStatus(db, planId)
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        return linked
    }

    fun updateCompletedCollaborationPlanItem(
        itemId: Long,
        buyer: PartnerOption,
        actualQuantity: Double,
        actualAmount: Double,
        recorderUsername: String = "",
        recorderDisplayName: String = ""
    ): CollaborationCompleteResult {
        if (actualQuantity <= 0 || actualAmount <= 0) {
            return CollaborationCompleteResult(
                false,
                "实际数量和金额必须大于0"
            )
        }

        val activeBuyer =
            getPartnerById(buyer.id)
                ?: return CollaborationCompleteResult(
                    false,
                    "采购人已失效，请重新选择"
                )

        val row =
            readableDatabase.rawQuery(
                "SELECT ppi.plan_id,ppi.fruit_id,ppi.fruit_name,ppi.unit," +
                    "ppi.status,ppi.sync_id," +
                    "COALESCE(pc.purchase_order_sync_id,'') AS order_sync_id " +
                    "FROM purchase_plan_item ppi " +
                    "LEFT JOIN purchase_collaboration pc " +
                    "ON pc.plan_item_sync_id=ppi.sync_id AND pc.deleted=0 " +
                    "WHERE ppi.id=? AND ppi.deleted=0 LIMIT 1",
                arrayOf(itemId.toString())
            ).use { c ->
                if (c.moveToFirst()) {
                    arrayOf<Any>(
                        c.long("plan_id"),
                        c.long("fruit_id"),
                        c.str("fruit_name"),
                        c.str("unit"),
                        c.int("status"),
                        c.str("sync_id"),
                        c.str("order_sync_id")
                    )
                } else {
                    null
                }
            } ?: return CollaborationCompleteResult(false, "协作采购记录不存在")

        if ((row[4] as Int) != 1) {
            return CollaborationCompleteResult(false, "只有已完成采购才能修改")
        }

        val fruitId = row[1] as Long
        val orderSyncId = row[6] as String
        if (orderSyncId.isBlank()) {
            return CollaborationCompleteResult(false, "未找到对应正式采购记录")
        }

        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            val order =
                db.rawQuery(
                    "SELECT id,buyer_id FROM purchase_order " +
                        "WHERE sync_id=? AND deleted=0 LIMIT 1",
                    arrayOf(orderSyncId)
                ).use { c ->
                    if (c.moveToFirst()) {
                        c.long("id") to c.long("buyer_id")
                    } else {
                        null
                    }
                } ?: return CollaborationCompleteResult(false, "对应采购单不存在")

            val matchingCount =
                db.rawQuery(
                    "SELECT COUNT(*) AS cnt FROM purchase_item " +
                        "WHERE order_id=? AND fruit_id=? AND deleted=0",
                    arrayOf(order.first.toString(), fruitId.toString())
                ).use { c -> if (c.moveToFirst()) c.int("cnt") else 0 }

            if (matchingCount != 1) {
                return CollaborationCompleteResult(
                    false,
                    "同一采购单存在多个同名水果，请到采购历史修改"
                )
            }

            val activeItemCount =
                db.rawQuery(
                    "SELECT COUNT(*) AS cnt FROM purchase_item " +
                        "WHERE order_id=? AND deleted=0",
                    arrayOf(order.first.toString())
                ).use { c -> if (c.moveToFirst()) c.int("cnt") else 0 }

            if (activeItemCount > 1 && order.second != activeBuyer.id) {
                return CollaborationCompleteResult(
                    false,
                    "该采购单包含多个水果，采购人请到采购历史修改整单"
                )
            }

            val unitPrice = actualAmount / actualQuantity
            db.update(
                "purchase_item",
                ContentValues().apply {
                    put("quantity", actualQuantity)
                    put("total_cost", roundMoney(actualAmount))
                    put("unit_price", roundMoney(unitPrice))
                    put("sync_status", 2)
                    put("updated_at", now)
                },
                "order_id=? AND fruit_id=? AND deleted=0",
                arrayOf(order.first.toString(), fruitId.toString())
            )

            val newTotal =
                db.rawQuery(
                    "SELECT COALESCE(SUM(total_cost),0) AS total FROM purchase_item " +
                        "WHERE order_id=? AND deleted=0",
                    arrayOf(order.first.toString())
                ).use { c -> if (c.moveToFirst()) c.dbl("total") else 0.0 }

            db.update(
                "purchase_order",
                ContentValues().apply {
                    put("total_cost", roundMoney(newTotal))
                    if (activeItemCount == 1) {
                        put("buyer_id", activeBuyer.id)
                        put("buyer_name", activeBuyer.name)
                    }
                    put("sync_status", 2)
                    put("updated_at", now)
                },
                "id=?",
                arrayOf(order.first.toString())
            )

            db.update(
                "purchase_collaboration",
                ContentValues().apply {
                    put("actual_quantity", actualQuantity)
                    put("actual_amount", roundMoney(actualAmount))
                    put("buyer_id", activeBuyer.id)
                    put("buyer_name", activeBuyer.name)
                    if (recorderUsername.isNotBlank()) {
                        put("completed_by_username", recorderUsername.trim())
                    }
                    if (recorderDisplayName.isNotBlank()) {
                        put("completed_by_display_name", recorderDisplayName.trim())
                    }
                    put("sync_status", 2)
                    put("updated_at", now)
                },
                "plan_item_sync_id=? AND deleted=0",
                arrayOf(row[5] as String)
            )

            db.setTransactionSuccessful()
            return CollaborationCompleteResult(
                true,
                "${row[2] as String} 已更新",
                order.first
            )
        } finally {
            db.endTransaction()
        }
    }

    fun restoreCompletedCollaborationPlanItem(
        itemId: Long
    ): CollaborationCompleteResult =
        changeCompletedCollaborationState(
            itemId = itemId,
            deletePlanItem = false
        )

    fun deleteCompletedCollaborationPlanItem(
        itemId: Long
    ): CollaborationCompleteResult =
        changeCompletedCollaborationState(
            itemId = itemId,
            deletePlanItem = true
        )

    private fun changeCompletedCollaborationState(
        itemId: Long,
        deletePlanItem: Boolean
    ): CollaborationCompleteResult {
        val row =
            readableDatabase.rawQuery(
                "SELECT ppi.plan_id,ppi.fruit_id,ppi.fruit_name,ppi.status,ppi.sync_id," +
                    "COALESCE(pc.purchase_order_sync_id,'') AS order_sync_id " +
                    "FROM purchase_plan_item ppi " +
                    "LEFT JOIN purchase_collaboration pc " +
                    "ON pc.plan_item_sync_id=ppi.sync_id AND pc.deleted=0 " +
                    "WHERE ppi.id=? AND ppi.deleted=0 LIMIT 1",
                arrayOf(itemId.toString())
            ).use { c ->
                if (c.moveToFirst()) {
                    arrayOf<Any>(
                        c.long("plan_id"),
                        c.long("fruit_id"),
                        c.str("fruit_name"),
                        c.int("status"),
                        c.str("sync_id"),
                        c.str("order_sync_id")
                    )
                } else {
                    null
                }
            } ?: return CollaborationCompleteResult(false, "协作采购记录不存在")

        if ((row[3] as Int) != 1) {
            return CollaborationCompleteResult(false, "该水果还没有完成采购")
        }

        val planId = row[0] as Long
        val fruitId = row[1] as Long
        val fruitName = row[2] as String
        val planItemSyncId = row[4] as String
        val orderSyncId = row[5] as String
        val db = writableDatabase
        val now = System.currentTimeMillis()

        db.beginTransaction()
        try {
            if (orderSyncId.isNotBlank()) {
                val orderId =
                    db.rawQuery(
                        "SELECT id FROM purchase_order " +
                            "WHERE sync_id=? AND deleted=0 LIMIT 1",
                        arrayOf(orderSyncId)
                    ).use { c -> if (c.moveToFirst()) c.long("id") else -1L }

                if (orderId > 0) {
                    val matchingCount =
                        db.rawQuery(
                            "SELECT COUNT(*) AS cnt FROM purchase_item " +
                                "WHERE order_id=? AND fruit_id=? AND deleted=0",
                            arrayOf(orderId.toString(), fruitId.toString())
                        ).use { c -> if (c.moveToFirst()) c.int("cnt") else 0 }

                    if (matchingCount > 1) {
                        return CollaborationCompleteResult(
                            false,
                            "同一采购单存在多个同名水果，请到采购历史处理"
                        )
                    }

                    if (matchingCount == 1) {
                        db.update(
                            "purchase_item",
                            ContentValues().apply {
                                put("deleted", 1)
                                put("sync_status", 2)
                                put("updated_at", now)
                            },
                            "order_id=? AND fruit_id=? AND deleted=0",
                            arrayOf(orderId.toString(), fruitId.toString())
                        )
                    }

                    val remaining =
                        db.rawQuery(
                            "SELECT COUNT(*) AS cnt,COALESCE(SUM(total_cost),0) AS total " +
                                "FROM purchase_item WHERE order_id=? AND deleted=0",
                            arrayOf(orderId.toString())
                        ).use { c ->
                            if (c.moveToFirst()) {
                                c.int("cnt") to c.dbl("total")
                            } else {
                                0 to 0.0
                            }
                        }

                    if (remaining.first == 0) {
                        db.update(
                            "purchase_order",
                            ContentValues().apply {
                                put("deleted", 1)
                                put("sync_status", 2)
                                put("updated_at", now)
                            },
                            "id=?",
                            arrayOf(orderId.toString())
                        )

                        db.update(
                            "purchase_activity",
                            ContentValues().apply {
                                put("deleted", 1)
                                put("sync_status", 2)
                                put("updated_at", now)
                            },
                            "order_sync_id=?",
                            arrayOf(orderSyncId)
                        )
                    } else {
                        db.update(
                            "purchase_order",
                            ContentValues().apply {
                                put("total_cost", roundMoney(remaining.second))
                                put("sync_status", 2)
                                put("updated_at", now)
                            },
                            "id=?",
                            arrayOf(orderId.toString())
                        )
                    }
                }
            }

            if (deletePlanItem) {
                db.update(
                    "purchase_plan_item",
                    ContentValues().apply {
                        put("deleted", 1)
                        put("sync_status", 2)
                        put("updated_at", now)
                    },
                    "id=?",
                    arrayOf(itemId.toString())
                )

                db.update(
                    "purchase_collaboration",
                    ContentValues().apply {
                        put("deleted", 1)
                        put("sync_status", 2)
                        put("updated_at", now)
                    },
                    "plan_item_sync_id=?",
                    arrayOf(planItemSyncId)
                )
            } else {
                db.update(
                    "purchase_plan_item",
                    ContentValues().apply {
                        put("status", 0)
                        put("sync_status", 2)
                        put("updated_at", now)
                    },
                    "id=?",
                    arrayOf(itemId.toString())
                )

                db.update(
                    "purchase_collaboration",
                    ContentValues().apply {
                        put("actual_quantity", 0)
                        put("actual_amount", 0)
                        put("buyer_id", 0)
                        put("buyer_name", "")
                        put("completed_by_username", "")
                        put("completed_by_display_name", "")
                        put("completed_at", 0)
                        put("purchase_order_sync_id", "")
                        put("deleted", 0)
                        put("sync_status", 2)
                        put("updated_at", now)
                    },
                    "plan_item_sync_id=?",
                    arrayOf(planItemSyncId)
                )
            }

            refreshPurchasePlanStatus(db, planId)
            db.setTransactionSuccessful()

            return CollaborationCompleteResult(
                true,
                if (deletePlanItem) {
                    "$fruitName 已删除"
                } else {
                    "$fruitName 已恢复为未完成"
                }
            )
        } finally {
            db.endTransaction()
        }
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

    fun getPurchaseOrders(
        limit: Int = 100
    ): List<PurchaseOrderDetail> {
        val orders =
            readableDatabase.rawQuery(
                "SELECT * FROM purchase_order " +
                    "WHERE deleted=0 " +
                    "ORDER BY date DESC,created_at DESC,id DESC " +
                    "LIMIT ?",
                arrayOf(
                    limit.toString()
                )
            ).use {
                c ->
                buildList {
                    while (
                        c.moveToNext()
                    ) {
                        add(
                            order(c)
                        )
                    }
                }
            }

        return orders.map {
            row ->
            PurchaseOrderDetail(
                order = row,
                items =
                    getPurchaseItems(
                        row.id
                    ),
                activity =
                    getPurchaseActivity(
                        row.id
                    )
            )
        }
    }

    fun getPurchaseOrdersForDate(
        date: String
    ): List<PurchaseOrderDetail> {
        val orders =
            readableDatabase.rawQuery(
                "SELECT * FROM purchase_order " +
                    "WHERE date=? AND deleted=0 " +
                    "ORDER BY created_at DESC,id DESC",
                arrayOf(
                    date
                )
            ).use {
                c ->
                buildList {
                    while (
                        c.moveToNext()
                    ) {
                        add(
                            order(c)
                        )
                    }
                }
            }

        return orders.map {
            row ->
            PurchaseOrderDetail(
                order = row,
                items =
                    getPurchaseItems(
                        row.id
                    ),
                activity =
                    getPurchaseActivity(
                        row.id
                    )
            )
        }
    }


    fun getRecentPurchaseOrdersByDays(
        dayLimit: Int = 7
    ): List<PurchaseOrderDetail> {
        if (dayLimit <= 0) {
            return emptyList()
        }

        val dates =
            readableDatabase.rawQuery(
                """
                SELECT DISTINCT date
                FROM purchase_order
                WHERE deleted=0
                ORDER BY date DESC
                LIMIT ?
                """.trimIndent(),
                arrayOf(
                    dayLimit.toString()
                )
            ).use {
                c ->
                buildList {
                    while (
                        c.moveToNext()
                    ) {
                        add(
                            c.str(
                                "date"
                            )
                        )
                    }
                }
            }

        if (dates.isEmpty()) {
            return emptyList()
        }

        val placeholders =
            dates.joinToString(
                ","
            ) {
                "?"
            }

        val orders =
            readableDatabase.rawQuery(
                """
                SELECT *
                FROM purchase_order
                WHERE
                    deleted=0
                    AND date IN($placeholders)
                ORDER BY
                    date DESC,
                    created_at DESC,
                    id DESC
                """.trimIndent(),
                dates.toTypedArray()
            ).use {
                c ->
                buildList {
                    while (
                        c.moveToNext()
                    ) {
                        add(
                            order(c)
                        )
                    }
                }
            }

        return orders.map {
            row ->
            PurchaseOrderDetail(
                order = row,
                items =
                    getPurchaseItems(
                        row.id
                    ),
                activity =
                    getPurchaseActivity(
                        row.id
                    )
            )
        }
    }

    fun getPurchaseOrdersBetween(
        start: String?,
        end: String?
    ): List<PurchaseOrderDetail> {
        val where =
            if (
                start != null &&
                end != null
            ) {
                "AND date>=? AND date<=?"
            } else {
                ""
            }

        val args =
            if (
                start != null &&
                end != null
            ) {
                arrayOf(
                    start,
                    end
                )
            } else {
                emptyArray()
            }

        val orders =
            readableDatabase.rawQuery(
                "SELECT * FROM purchase_order " +
                    "WHERE deleted=0 $where " +
                    "ORDER BY date DESC,created_at DESC,id DESC",
                args
            ).use {
                c ->
                buildList {
                    while (
                        c.moveToNext()
                    ) {
                        add(
                            order(c)
                        )
                    }
                }
            }

        return orders.map {
            row ->
            PurchaseOrderDetail(
                order = row,
                items =
                    getPurchaseItems(
                        row.id
                    ),
                activity =
                    getPurchaseActivity(
                        row.id
                    )
            )
        }
    }

    fun getDuplicatePurchases(
        date: String,
        fruitIds: List<Long>,
        excludeOrderId: Long? = null
    ): List<PurchaseDuplicateRecord> {
        val ids =
            fruitIds
                .distinct()
                .filter {
                    it > 0
                }

        if (ids.isEmpty()) {
            return emptyList()
        }

        val placeholders =
            ids.joinToString(
                ","
            ) {
                "?"
            }

        val args =
            buildList {
                add(date)
                ids.forEach {
                    add(
                        it.toString()
                    )
                }
                excludeOrderId
                    ?.let {
                        add(
                            it.toString()
                        )
                    }
            }

        val exclude =
            if (
                excludeOrderId != null
            ) {
                "AND po.id<>?"
            } else {
                ""
            }

        return readableDatabase.rawQuery(
            """
            SELECT
                po.id AS order_id,
                po.date,
                po.buyer_name,
                pi.fruit_id,
                pi.fruit_name,
                pi.unit,
                pi.quantity,
                pi.total_cost,
                pi.unit_price,
                COALESCE(
                    pa.purchase_type,
                    'PLANNED'
                ) AS purchase_type,
                po.created_at
            FROM purchase_item pi
            JOIN purchase_order po
              ON po.id=pi.order_id
            LEFT JOIN purchase_activity pa
              ON pa.order_sync_id=po.sync_id
             AND pa.deleted=0
            WHERE
                po.date=?
                AND po.deleted=0
                AND pi.deleted=0
                AND pi.fruit_id IN($placeholders)
                $exclude
            ORDER BY
                po.created_at DESC,
                po.id DESC
            """.trimIndent(),
            args.toTypedArray()
        ).use {
            c ->
            buildList {
                while (
                    c.moveToNext()
                ) {
                    add(
                        PurchaseDuplicateRecord(
                            orderId =
                                c.long(
                                    "order_id"
                                ),
                            date =
                                c.str(
                                    "date"
                                ),
                            buyerName =
                                c.str(
                                    "buyer_name"
                                ),
                            fruitId =
                                c.long(
                                    "fruit_id"
                                ),
                            fruitName =
                                c.str(
                                    "fruit_name"
                                ),
                            unit =
                                c.str(
                                    "unit"
                                ),
                            quantity =
                                c.dbl(
                                    "quantity"
                                ),
                            totalCost =
                                c.dbl(
                                    "total_cost"
                                ),
                            unitPrice =
                                c.dbl(
                                    "unit_price"
                                ),
                            purchaseType =
                                PurchaseTypes
                                    .normalize(
                                        c.str(
                                            "purchase_type"
                                        )
                                    ),
                            createdAt =
                                c.long(
                                    "created_at"
                                )
                        )
                    )
                }
            }
        }
    }

    private fun getPurchaseActivity(
        orderId: Long
    ): PurchaseActivityMeta? =
        readableDatabase.rawQuery(
            """
            SELECT pa.*
            FROM purchase_activity pa
            JOIN purchase_order po
              ON po.sync_id=pa.order_sync_id
            WHERE
                po.id=?
                AND pa.deleted=0
            LIMIT 1
            """.trimIndent(),
            arrayOf(
                orderId.toString()
            )
        ).use {
            c ->
            if (
                c.moveToFirst()
            ) {
                PurchaseActivityMeta(
                    id =
                        c.long(
                            "id"
                        ),
                    orderSyncId =
                        c.str(
                            "order_sync_id"
                        ),
                    purchaseType =
                        PurchaseTypes
                            .normalize(
                                c.str(
                                    "purchase_type"
                                )
                            ),
                    recorderUsername =
                        c.str(
                            "recorder_username"
                        ),
                    recorderDisplayName =
                        c.str(
                            "recorder_display_name"
                        ),
                    createdAt =
                        c.long(
                            "created_at"
                        ),
                    updatedAt =
                        c.long(
                            "updated_at"
                        )
                )
            } else {
                null
            }
        }

    private fun getPurchaseOrderSyncId(
        db: SQLiteDatabase,
        orderId: Long
    ): String =
        db.rawQuery(
            "SELECT sync_id " +
                "FROM purchase_order " +
                "WHERE id=? LIMIT 1",
            arrayOf(
                orderId.toString()
            )
        ).use {
            c ->
            if (
                c.moveToFirst()
            ) {
                c.str(
                    "sync_id"
                )
            } else {
                ""
            }
        }

    private fun upsertPurchaseActivity(
        db: SQLiteDatabase,
        orderId: Long,
        purchaseType: String,
        recorderUsername: String,
        recorderDisplayName: String
    ) {
        val orderSyncId =
            getPurchaseOrderSyncId(
                db,
                orderId
            )

        if (
            orderSyncId
                .isBlank()
        ) {
            return
        }

        val normalizedType =
            PurchaseTypes
                .normalize(
                    purchaseType
                )

        val existing =
            db.rawQuery(
                """
                SELECT
                    id,
                    recorder_username,
                    recorder_display_name
                FROM purchase_activity
                WHERE order_sync_id=?
                LIMIT 1
                """.trimIndent(),
                arrayOf(
                    orderSyncId
                )
            ).use {
                c ->
                if (
                    c.moveToFirst()
                ) {
                    Triple(
                        c.long(
                            "id"
                        ),
                        c.str(
                            "recorder_username"
                        ),
                        c.str(
                            "recorder_display_name"
                        )
                    )
                } else {
                    null
                }
            }

        if (existing == null) {
            db.insert(
                "purchase_activity",
                null,
                baseSyncValues().apply {
                    put(
                        "order_sync_id",
                        orderSyncId
                    )
                    put(
                        "purchase_type",
                        normalizedType
                    )
                    put(
                        "recorder_username",
                        recorderUsername
                            .trim()
                    )
                    put(
                        "recorder_display_name",
                        recorderDisplayName
                            .trim()
                    )
                    put(
                        "deleted",
                        0
                    )
                }
            )
        } else {
            val now =
                System.currentTimeMillis()

            db.update(
                "purchase_activity",
                ContentValues().apply {
                    put(
                        "purchase_type",
                        normalizedType
                    )

                    if (
                        existing.second
                            .isBlank() &&
                        recorderUsername
                            .isNotBlank()
                    ) {
                        put(
                            "recorder_username",
                            recorderUsername
                                .trim()
                        )
                    }

                    if (
                        existing.third
                            .isBlank() &&
                        recorderDisplayName
                            .isNotBlank()
                    ) {
                        put(
                            "recorder_display_name",
                            recorderDisplayName
                                .trim()
                        )
                    }

                    put(
                        "deleted",
                        0
                    )
                    put(
                        "sync_status",
                        2
                    )
                    put(
                        "updated_at",
                        now
                    )
                },
                "id=?",
                arrayOf(
                    existing.first
                        .toString()
                )
            )
        }
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

        var invalidated = false

        ids.forEach { settlementId ->
            val settledAmount =
                db.rawQuery(
                    """
                    SELECT COALESCE(SUM(settled_amount),0) AS amount
                    FROM settlement_transfer
                    WHERE settlement_id=? AND deleted=0
                    """.trimIndent(),
                    arrayOf(settlementId.toString())
                ).use { c ->
                    if (c.moveToFirst()) c.dbl("amount") else 0.0
                }

            // Once money was actually transferred, keep that transfer as an
            // immutable cash movement. Source-data edits may change the current
            // balance, but must not make a real payment disappear from history.
            if (settledAmount > 0.005) {
                return@forEach
            }

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
            invalidated = true
        }
        return invalidated
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
        receiptSplits: List<ReceiptSplitRecord> = emptyList(),
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

        val normalizedReceiptSplits =
            receiptSplits
                .mapNotNull { split ->
                    val wechatPart = roundMoney(split.wechatIncome)
                    val alipayPart = roundMoney(split.alipayIncome)
                    val cashPart = roundMoney(split.cashIncome)
                    val channelTotal = roundMoney(wechatPart + alipayPart + cashPart)
                    val normalizedAmount =
                        if (channelTotal > 0.005) channelTotal else roundMoney(split.amount)
                    if (normalizedAmount <= 0.005) {
                        null
                    } else if (split.partnerId <= 0L) {
                        split.copy(
                            partnerId = 0L,
                            partnerName = split.partnerName.ifBlank { "未指定" },
                            amount = normalizedAmount,
                            wechatIncome = wechatPart,
                            alipayIncome = alipayPart,
                            cashIncome = cashPart
                        )
                    } else {
                        val active = getPartnerById(split.partnerId)
                        val historical =
                            editingOld
                                ?.receiptSplits
                                ?.firstOrNull { it.partnerId == split.partnerId }
                                ?.let { PartnerOption(it.partnerId, it.partnerName) }
                                ?: editingOld?.let { old ->
                                    when (split.partnerId) {
                                        old.wechatCollectorId ->
                                            PartnerOption(old.wechatCollectorId, old.wechatCollectorName)
                                        old.alipayCollectorId ->
                                            PartnerOption(old.alipayCollectorId, old.alipayCollectorName)
                                        old.cashCollectorId ->
                                            PartnerOption(old.cashCollectorId, old.cashCollectorName)
                                        else -> null
                                    }
                                }
                        val resolved =
                            active
                                ?: historical
                                ?: return StoreDailySaveResult(
                                    false,
                                    "保存失败：收款人 ${split.partnerName.ifBlank { split.partnerId.toString() }} 已失效，请重新选择"
                                )
                        split.copy(
                            partnerId = resolved.id,
                            partnerName = resolved.name,
                            amount = normalizedAmount,
                            wechatIncome = wechatPart,
                            alipayIncome = alipayPart,
                            cashIncome = cashPart
                        )
                    }
                }

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
        val effectiveReceiptSplits =
            when {
                normalizedReceiptSplits.isNotEmpty() -> normalizedReceiptSplits
                revenue > 0.005 -> {
                    val legacyParts =
                        listOf(
                            ReceiptSplitRecord(
                                partnerId = freshWechatCollector?.id ?: 0L,
                                partnerName = freshWechatCollector?.name ?: "未指定",
                                amount = roundMoney(wechat),
                                wechatIncome = roundMoney(wechat)
                            ),
                            ReceiptSplitRecord(
                                partnerId = freshAlipayCollector?.id ?: 0L,
                                partnerName = freshAlipayCollector?.name ?: "未指定",
                                amount = roundMoney(alipay),
                                alipayIncome = roundMoney(alipay)
                            ),
                            ReceiptSplitRecord(
                                partnerId = freshCashCollector?.id ?: 0L,
                                partnerName = freshCashCollector?.name ?: "未指定",
                                amount = roundMoney(cash),
                                cashIncome = roundMoney(cash)
                            )
                        )
                            .filter { it.amount > 0.005 }

                    legacyParts
                        .groupBy { it.partnerId to it.partnerName }
                        .map { (key, parts) ->
                            val rowWechat = roundMoney(parts.sumOf { it.wechatIncome })
                            val rowAlipay = roundMoney(parts.sumOf { it.alipayIncome })
                            val rowCash = roundMoney(parts.sumOf { it.cashIncome })
                            ReceiptSplitRecord(
                                partnerId = key.first,
                                partnerName = key.second,
                                amount = roundMoney(rowWechat + rowAlipay + rowCash),
                                wechatIncome = rowWechat,
                                alipayIncome = rowAlipay,
                                cashIncome = rowCash
                            )
                        }
                }
                else -> emptyList()
            }
        val receiptSplitTotal = roundMoney(effectiveReceiptSplits.sumOf { it.amount })
        if (kotlin.math.abs(receiptSplitTotal - revenue) > 0.01) {
            return StoreDailySaveResult(
                false,
                "保存失败：收款归属合计 ${receiptSplitTotal} 元，与营业额 ${revenue} 元不一致"
            )
        }
        val receiptSplitsJson = encodeReceiptSplits(effectiveReceiptSplits)
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

        val legacyCollector = effectiveReceiptSplits.singleOrNull()
        val legacyCollectorId = legacyCollector?.partnerId ?: 0L
        val legacyCollectorName =
            if (effectiveReceiptSplits.size > 1) "多人收款"
            else legacyCollector?.partnerName ?: "未指定"

        val ownershipChanged = oldRecord == null ||
            encodeReceiptSplits(oldRecord.receiptSplits) != receiptSplitsJson ||
            oldRecord.expensePayerId != (freshExpensePayer?.id ?: 0L)

        db.beginTransaction()
        try {
            val values = ContentValues().apply {
                put("date", date)
                put("store_id", freshStore.id)
                put("store_name", freshStore.name)

                put("wechat_income", wechat)
                put("wechat_collector_id", legacyCollectorId)
                put("wechat_collector_name", legacyCollectorName)

                put("alipay_income", alipay)
                put("alipay_collector_id", legacyCollectorId)
                put("alipay_collector_name", legacyCollectorName)

                put("cash_income", cash)
                put("cash_collector_id", legacyCollectorId)
                put("cash_collector_name", legacyCollectorName)
                put("receipt_splits_json", receiptSplitsJson)

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

    fun getDailyRecords(
        date: String
    ): List<StoreDailyRecord> =
        readableDatabase.rawQuery(
            """
            SELECT d.*
            FROM store_daily_record d
            LEFT JOIN store s
              ON s.id=d.store_id
            WHERE
                d.date=?
                AND d.deleted=0
            ORDER BY
                COALESCE(
                    s.sort_order,
                    d.id
                ) ASC,
                d.id ASC
            """.trimIndent(),
            arrayOf(date)
        ).use {
            c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        dailyRecord(c)
                    )
                }
            }
        }


    fun getRecentBusinessDayRecords(
        beforeDateExclusive: String,
        dayLimit: Int = 7
    ): List<StoreDailyRecord> {
        if (
            beforeDateExclusive.isBlank() ||
            dayLimit <= 0
        ) {
            return emptyList()
        }

        val dates =
            readableDatabase.rawQuery(
                """
                SELECT DISTINCT date
                FROM store_daily_record
                WHERE
                    deleted=0
                    AND date<?
                ORDER BY date DESC
                LIMIT ?
                """.trimIndent(),
                arrayOf(
                    beforeDateExclusive,
                    dayLimit.toString()
                )
            ).use {
                c ->
                buildList {
                    while (
                        c.moveToNext()
                    ) {
                        add(
                            c.str(
                                "date"
                            )
                        )
                    }
                }
            }

        if (dates.isEmpty()) {
            return emptyList()
        }

        val placeholders =
            dates.joinToString(
                ","
            ) {
                "?"
            }

        return readableDatabase.rawQuery(
            """
            SELECT d.*
            FROM store_daily_record d
            LEFT JOIN store s
              ON s.id=d.store_id
            WHERE
                d.deleted=0
                AND d.date IN($placeholders)
            ORDER BY
                d.date DESC,
                COALESCE(
                    s.sort_order,
                    d.id
                ) ASC,
                d.id ASC
            """.trimIndent(),
            dates.toTypedArray()
        ).use {
            c ->
            buildList {
                while (
                    c.moveToNext()
                ) {
                    add(
                        dailyRecord(c)
                    )
                }
            }
        }
    }

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
            if (r.receiptSplits.isNotEmpty()) {
                r.receiptSplits.forEach { split ->
                    add(split.partnerId, split.partnerName, split.amount)
                }
            } else {
                add(r.wechatCollectorId, r.wechatCollectorName, r.wechatIncome)
                add(r.alipayCollectorId, r.alipayCollectorName, r.alipayIncome)
                add(r.cashCollectorId, r.cashCollectorName, r.cashIncome)
            }
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
                c.long("updated_at"), c.long("confirmed_at")
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
                c.long("to_partner_id"), c.str("to_partner_name"), c.dbl("amount"),
                c.dbl("settled_amount"), c.str("settlement_kind"), c.str("batch_key"),
                c.str("range_start_date"), c.str("range_end_date"),
                c.int("business_day_count"), c.long("confirmed_at"),
                c.long("focus_partner_id"), c.str("focus_partner_name")
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
            c.long("updated_at"), c.long("confirmed_at")
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

        val existingSettlement = getCashSettlement(date)
        if (
            existingSettlement != null &&
            existingSettlement.transfers.any {
                it.settledAmount > 0.005
            }
        ) {
            return CashSettlementResult(
                false,
                "当天已经有实际结算款，不能直接重新生成。若之前标记错误，请先把相关转账改为未结算后再重新生成。",
                existingSettlement
            )
        }

        val profitRows = getProfitDistribution(date)
        if (profitRows.isEmpty() && kotlin.math.abs(summary.profit) > 0.005) {
            return CashSettlementResult(
                false,
                if (summary.profit < 0) {
                    "请先到“更多 → 利润分配”保存当天亏损分担"
                } else {
                    "请先到“更多 → 利润分配”保存当天利润分配"
                }
            )
        }

        val records = getDailyRecords(date)
        val unassignedReceipts = records.sumOf { record ->
            if (record.receiptSplits.isNotEmpty()) {
                record.receiptSplits
                    .filter { it.partnerId <= 0L }
                    .sumOf { it.amount }
            } else {
                (if (record.wechatIncome > 0 && record.wechatCollectorId <= 0) record.wechatIncome else 0.0) +
                    (if (record.alipayIncome > 0 && record.alipayCollectorId <= 0) record.alipayIncome else 0.0) +
                    (if (record.cashIncome > 0 && record.cashCollectorId <= 0) record.cashIncome else 0.0)
            }
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
        val settlementCenter =
            getSettlementCenter()
                ?: return CashSettlementResult(
                    false,
                    "请先到“更多 → 利润分配”设置资金中心"
                )

        val partnerIds = linkedSetOf<Long>().apply {
            add(settlementCenter.id)
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
                ?: if (id == settlementCenter.id) {
                    settlementCenter.name
                } else {
                    "合伙人$id"
                }
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

        data class TransferTmp(
            val fromId: Long,
            val fromName: String,
            val toId: Long,
            val toName: String,
            val amount: Double
        )

        // 统一采用“资金中心 ↔ 合伙人”的星型结算。
        // 非资金中心之间永远不直接生成 B → C / C → B。
        val transfers =
            rows
                .filter {
                    it.id != settlementCenter.id &&
                        kotlin.math.abs(it.balance) > 0.005
                }
                .map { row ->
                    if (row.balance > 0.005) {
                        TransferTmp(
                            settlementCenter.id,
                            settlementCenter.name,
                            row.id,
                            row.name,
                            roundMoney(row.balance)
                        )
                    } else {
                        TransferTmp(
                            row.id,
                            row.name,
                            settlementCenter.id,
                            settlementCenter.name,
                            roundMoney(-row.balance)
                        )
                    }
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
                    put("settled_amount", 0)
                    put("settlement_kind", "DAILY")
                    put("batch_key", "daily-$settlementId")
                    put("range_start_date", date)
                    put("range_end_date", date)
                    put("business_day_count", 1)
                    put("confirmed_at", 0)
                    put("focus_partner_id", 0)
                    put("focus_partner_name", "")
                    put("deleted", 0)
                })
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        return CashSettlementResult(true, "今日结算方案已生成", getCashSettlement(date))
    }

    fun confirmCashSettlement(id: Long): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val now = System.currentTimeMillis()
            db.execSQL(
                """
                UPDATE settlement_transfer
                SET settled_amount=amount,
                    confirmed_at=?,
                    sync_status=2,
                    updated_at=?
                WHERE settlement_id=? AND deleted=0
                """.trimIndent(),
                arrayOf<Any?>(now, now, id)
            )
            val changed =
                db.update(
                    "daily_cash_settlement",
                    ContentValues().apply {
                        put("status", 1)
                        put("confirmed_at", now)
                        put("sync_status", 2)
                        put("updated_at", now)
                    },
                    "id=? AND deleted=0",
                    arrayOf(id.toString())
                ) > 0
            db.setTransactionSuccessful()
            changed
        } finally {
            db.endTransaction()
        }
    }

    fun setCashSettlementTransferSettledAmount(
        transferId: Long,
        targetAmount: Double
    ): Boolean {
        val row =
            readableDatabase.rawQuery(
                """
                SELECT settlement_id,amount
                FROM settlement_transfer
                WHERE id=? AND deleted=0
                LIMIT 1
                """.trimIndent(),
                arrayOf(transferId.toString())
            ).use { c ->
                if (c.moveToFirst()) {
                    c.long("settlement_id") to c.dbl("amount")
                } else {
                    null
                }
            } ?: return false

        val settlementId = row.first
        val maxAmount = roundMoney(row.second)
        val target =
            roundMoney(
                targetAmount.coerceIn(
                    0.0,
                    maxAmount
                )
            )

        val db = writableDatabase
        db.beginTransaction()
        return try {
            val now = System.currentTimeMillis()
            val changed =
                db.update(
                    "settlement_transfer",
                    ContentValues().apply {
                        put("settled_amount", target)
                        put("confirmed_at", if (target > 0.005) now else 0L)
                        put("sync_status", 2)
                        put("updated_at", now)
                    },
                    "id=? AND deleted=0",
                    arrayOf(transferId.toString())
                ) > 0
            if (changed) {
                refreshCashSettlementStatus(
                    db,
                    settlementId,
                    now
                )
            }
            db.setTransactionSuccessful()
            changed
        } finally {
            db.endTransaction()
        }
    }

    fun setCashSettlementPartnerSettledAmount(
        settlementId: Long,
        partnerId: Long,
        targetAmount: Double
    ): Boolean {
        val bundle = getCashSettlementById(settlementId) ?: return false
        val partner =
            bundle.partners.firstOrNull { it.partnerId == partnerId }
                ?: return false

        val maxAmount = roundMoney(kotlin.math.abs(partner.balance))
        val target = roundMoney(targetAmount.coerceIn(0.0, maxAmount))
        val related =
            if (partner.balance >= 0) {
                bundle.transfers.filter { it.toPartnerId == partnerId }
            } else {
                bundle.transfers.filter { it.fromPartnerId == partnerId }
            }

        if (related.isEmpty() && maxAmount > 0.005) return false

        val db = writableDatabase
        db.beginTransaction()
        try {
            val now = System.currentTimeMillis()
            var remaining = target
            related.forEach { transfer ->
                val settled = roundMoney(minOf(transfer.amount, remaining))
                db.update(
                    "settlement_transfer",
                    ContentValues().apply {
                        put("settled_amount", settled)
                        put("confirmed_at", if (settled > 0.005) now else 0L)
                        put("sync_status", 2)
                        put("updated_at", now)
                    },
                    "id=? AND deleted=0",
                    arrayOf(transfer.id.toString())
                )
                remaining = roundMoney((remaining - settled).coerceAtLeast(0.0))
            }
            refreshCashSettlementStatus(db, settlementId, now)
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    private fun refreshCashSettlementStatus(
        db: SQLiteDatabase,
        settlementId: Long,
        now: Long = System.currentTimeMillis()
    ) {
        val amounts =
            db.rawQuery(
                """
                SELECT
                    COALESCE(SUM(amount),0) AS total_amount,
                    COALESCE(SUM(settled_amount),0) AS settled_amount
                FROM settlement_transfer
                WHERE settlement_id=? AND deleted=0
                """.trimIndent(),
                arrayOf(settlementId.toString())
            ).use { c ->
                if (c.moveToFirst()) {
                    c.dbl("total_amount") to c.dbl("settled_amount")
                } else {
                    0.0 to 0.0
                }
            }

        val status =
            when {
                amounts.first <= 0.005 -> 1
                amounts.second <= 0.005 -> 0
                amounts.second + 0.005 >= amounts.first -> 1
                else -> 2
            }

        db.update(
            "daily_cash_settlement",
            ContentValues().apply {
                put("status", status)
                put(
                    "confirmed_at",
                    if (amounts.second > 0.005) now else 0L
                )
                put("sync_status", 2)
                put("updated_at", now)
            },
            "id=? AND deleted=0",
            arrayOf(settlementId.toString())
        )
    }

    private fun getCashSettlementById(id: Long): CashSettlementBundle? {
        val date =
            readableDatabase.rawQuery(
                "SELECT date FROM daily_cash_settlement WHERE id=? AND deleted=0 LIMIT 1",
                arrayOf(id.toString())
            ).use { c ->
                if (c.moveToFirst()) c.str("date") else ""
            }
        if (date.isBlank()) return null
        return getCashSettlement(date)?.takeIf { it.settlement.id == id }
    }

    /**
     * V1.4.7.17: every standalone historical transfer (settlement_id=0) must
     * involve the currently configured settlement center before it may affect
     * fund balances.
     *
     * Older builds did not always persist "结清截至今日" as CUTOFF. During
     * schema upgrades some of those rows were converted from DAILY to LEGACY.
     * Filtering only CUTOFF/CUTOFF_CENTER therefore missed exactly the old
     * B<->C rows we need to retire. The structural rule is more reliable:
     * settlement_id=0 + neither side is the current center = legacy invalid.
     *
     * Keep this as a runtime guard because cloud sync can restore an old row
     * after the DB migration has already finished.
     */
    private fun effectiveFundTransferPredicate(): String {
        val centerId = getSettlementCenter()?.id ?: 0L
        return if (centerId > 0L) {
            """
            settlement_kind!='CUTOFF_LEGACY_INVALID'
            AND NOT (
                settlement_id=0
                AND from_partner_id<>$centerId
                AND to_partner_id<>$centerId
            )
            """.trimIndent()
        } else {
            "settlement_kind!='CUTOFF_LEGACY_INVALID'"
        }
    }

    /**
     * Self-heal old cloud rows as well. Runtime balance correctness does not
     * depend on this update; effectiveFundTransferPredicate() blocks every
     * standalone non-center transfer even when its old kind is LEGACY/DAILY.
     * This update only makes the history classification explicit and syncable.
     */
    private fun normalizeLegacyCutoffTransfersForCurrentCenter() {
        val centerId = getSettlementCenter()?.id ?: return
        if (centerId <= 0L) return

        val now = System.currentTimeMillis()
        val db = writableDatabase

        // Standalone transfers that already follow the active center can be
        // normalized to the canonical CUTOFF_CENTER label. Do not rewrite
        // DAILY rows tied to a real daily settlement (settlement_id>0).
        db.execSQL(
            """
            UPDATE settlement_transfer
            SET settlement_kind='CUTOFF_CENTER',
                sync_status=2,
                updated_at=?
            WHERE deleted=0
              AND settlement_id=0
              AND settlement_kind IN ('CUTOFF','LEGACY','DAILY')
              AND (from_partner_id=? OR to_partner_id=?)
              AND (confirmed_at>0 OR settled_amount>0.005)
            """.trimIndent(),
            arrayOf<Any?>(now, centerId, centerId)
        )

        // Any settled standalone transfer that bypasses the active center is
        // an old accounting-model record. This deliberately includes LEGACY
        // and DAILY labels because old migrations used those labels for some
        // "结清截至今日" rows.
        db.execSQL(
            """
            UPDATE settlement_transfer
            SET settlement_kind='CUTOFF_LEGACY_INVALID',
                sync_status=2,
                updated_at=?
            WHERE deleted=0
              AND settlement_id=0
              AND settlement_kind!='CUTOFF_LEGACY_INVALID'
              AND from_partner_id<>?
              AND to_partner_id<>?
              AND (confirmed_at>0 OR settled_amount>0.005)
            """.trimIndent(),
            arrayOf<Any?>(now, centerId, centerId)
        )
    }

    fun getPartnerFundBalances(
        endDate: String? = null
    ): List<PartnerFundBalanceSummary> {
        normalizeLegacyCutoffTransfersForCurrentCenter()
        val transferPredicate = effectiveFundTransferPredicate()
        data class Acc(
            var name: String = "",
            var purchase: Double = 0.0,
            var expense: Double = 0.0,
            var receipt: Double = 0.0,
            var profit: Double = 0.0,
            var sent: Double = 0.0,
            var received: Double = 0.0
        )

        val dateWhere =
            if (endDate != null) "AND date<=?" else ""
        val dateArgs =
            if (endDate != null) {
                arrayOf(endDate, endDate, endDate)
            } else {
                emptyArray()
            }

        val dates =
            readableDatabase.rawQuery(
                """
                SELECT date FROM purchase_order
                WHERE deleted=0 $dateWhere
                UNION
                SELECT date FROM store_daily_record
                WHERE deleted=0 $dateWhere
                UNION
                SELECT date FROM profit_distribution
                WHERE deleted=0 $dateWhere
                ORDER BY date
                """.trimIndent(),
                dateArgs
            ).use { c ->
                buildList {
                    while (c.moveToNext()) add(c.str("date"))
                }
            }

        val map = linkedMapOf<Long, Acc>()

        fun acc(id: Long, name: String): Acc {
            val row = map.getOrPut(id) { Acc(name = name) }
            if (name.isNotBlank()) row.name = name
            return row
        }

        dates.forEach { date ->
            getPurchaseTotalsByPartner(date)
                .filter { it.partnerId > 0 }
                .forEach {
                    acc(it.partnerId, it.partnerName).purchase += it.amount
                }
            getExpenseTotalsByPartner(date)
                .filter { it.partnerId > 0 }
                .forEach {
                    acc(it.partnerId, it.partnerName).expense += it.amount
                }
            getReceiptsByPartner(date)
                .filter { it.partnerId > 0 }
                .forEach {
                    acc(it.partnerId, it.partnerName).receipt += it.amount
                }
            getProfitDistribution(date)
                .filter { it.partnerId > 0 }
                .forEach {
                    acc(it.partnerId, it.partnerName).profit += it.allocatedProfit
                }
        }

        val transferWhere =
            if (endDate != null) "AND date<=?" else ""
        val transferArgs =
            if (endDate != null) arrayOf(endDate) else emptyArray()

        readableDatabase.rawQuery(
            """
            SELECT
                from_partner_id,
                from_partner_name,
                to_partner_id,
                to_partner_name,
                COALESCE(settled_amount,0) AS settled_amount
            FROM settlement_transfer
            WHERE deleted=0
              AND settled_amount>0.005
              AND $transferPredicate
              $transferWhere
            """.trimIndent(),
            transferArgs
        ).use { c ->
            while (c.moveToNext()) {
                val amount = c.dbl("settled_amount")
                acc(
                    c.long("from_partner_id"),
                    c.str("from_partner_name")
                ).sent += amount
                acc(
                    c.long("to_partner_id"),
                    c.str("to_partner_name")
                ).received += amount
            }
        }

        return map.map { (id, a) ->
            val purchase = roundMoney(a.purchase)
            val expense = roundMoney(a.expense)
            val receipt = roundMoney(a.receipt)
            val profit = roundMoney(a.profit)
            val sent = roundMoney(a.sent)
            val received = roundMoney(a.received)
            val current =
                roundMoney(
                    purchase +
                        expense +
                        profit -
                        receipt +
                        sent -
                        received
                )
            PartnerFundBalanceSummary(
                partnerId = id,
                partnerName = a.name.ifBlank { "合伙人$id" },
                purchasePaid = purchase,
                expensePaid = expense,
                revenueReceived = receipt,
                profitShare = profit,
                settlementSent = sent,
                settlementReceived = received,
                currentBalance = current
            )
        }.sortedBy { it.partnerId }
    }

    fun getFundPeriodSummary(
        startDate: String?,
        endDate: String
    ): FundPeriodSummary {
        normalizeLegacyCutoffTransfersForCurrentCenter()
        val transferPredicate = effectiveFundTransferPredicate()
        val whereStart =
            if (startDate != null) "AND date>=?" else ""
        val args =
            if (startDate != null) {
                arrayOf(startDate, endDate)
            } else {
                arrayOf(endDate)
            }
        val dates =
            readableDatabase.rawQuery(
                """
                SELECT DISTINCT date
                FROM store_daily_record
                WHERE deleted=0
                  $whereStart
                  AND date<=?
                ORDER BY date
                """.trimIndent(),
                args
            ).use { c ->
                buildList {
                    while (c.moveToNext()) add(c.str("date"))
                }
            }

        val summaries = dates.map { getDailySummary(it) }
        val settlementWhereStart =
            if (startDate != null) "AND date>=?" else ""
        val settlementArgs =
            if (startDate != null) {
                arrayOf(startDate, endDate)
            } else {
                arrayOf(endDate)
            }
        val settlementAmount =
            readableDatabase.rawQuery(
                """
                SELECT COALESCE(SUM(settled_amount),0) AS amount
                FROM settlement_transfer
                WHERE deleted=0
                  AND settled_amount>0.005
                  AND $transferPredicate
                  $settlementWhereStart
                  AND date<=?
                """.trimIndent(),
                settlementArgs
            ).use { c ->
                if (c.moveToFirst()) roundMoney(c.dbl("amount")) else 0.0
            }

        return FundPeriodSummary(
            startDate =
                startDate ?: dates.firstOrNull().orEmpty(),
            endDate = endDate,
            businessDayCount = dates.size,
            purchaseAmount =
                roundMoney(summaries.sumOf { it.purchaseCost }),
            revenueAmount =
                roundMoney(summaries.sumOf { it.revenue }),
            profitAmount =
                roundMoney(summaries.sumOf { it.profit }),
            settlementAmount = settlementAmount
        )
    }

    fun getPartnerFundPeriodSummaries(
        startDate: String?,
        endDate: String
    ): List<PartnerFundPeriodSummary> {
        normalizeLegacyCutoffTransfersForCurrentCenter()
        val transferPredicate = effectiveFundTransferPredicate()
        data class Acc(
            var name: String = "",
            var purchase: Double = 0.0,
            var expense: Double = 0.0,
            var receipt: Double = 0.0,
            var profit: Double = 0.0,
            var sent: Double = 0.0,
            var received: Double = 0.0
        )

        val whereStart =
            if (startDate != null) "AND date>=?" else ""
        val dateArgs =
            if (startDate != null) {
                arrayOf(startDate, endDate)
            } else {
                arrayOf(endDate)
            }
        val dates =
            readableDatabase.rawQuery(
                """
                SELECT DISTINCT date
                FROM store_daily_record
                WHERE deleted=0
                  $whereStart
                  AND date<=?
                ORDER BY date
                """.trimIndent(),
                dateArgs
            ).use { c ->
                buildList {
                    while (c.moveToNext()) add(c.str("date"))
                }
            }

        val map = linkedMapOf<Long, Acc>()
        fun acc(id: Long, name: String): Acc {
            val row = map.getOrPut(id) { Acc(name = name) }
            if (name.isNotBlank()) row.name = name
            return row
        }

        dates.forEach { date ->
            getPurchaseTotalsByPartner(date)
                .filter { it.partnerId > 0 }
                .forEach {
                    acc(it.partnerId, it.partnerName).purchase += it.amount
                }
            getExpenseTotalsByPartner(date)
                .filter { it.partnerId > 0 }
                .forEach {
                    acc(it.partnerId, it.partnerName).expense += it.amount
                }
            getReceiptsByPartner(date)
                .filter { it.partnerId > 0 }
                .forEach {
                    acc(it.partnerId, it.partnerName).receipt += it.amount
                }
            getProfitDistribution(date)
                .filter { it.partnerId > 0 }
                .forEach {
                    acc(it.partnerId, it.partnerName).profit += it.allocatedProfit
                }
        }

        val transferWhereStart =
            if (startDate != null) "AND date>=?" else ""
        val transferArgs =
            if (startDate != null) {
                arrayOf(startDate, endDate)
            } else {
                arrayOf(endDate)
            }
        readableDatabase.rawQuery(
            """
            SELECT
                from_partner_id,
                from_partner_name,
                to_partner_id,
                to_partner_name,
                COALESCE(settled_amount,0) AS settled_amount
            FROM settlement_transfer
            WHERE deleted=0
              AND settled_amount>0.005
              AND $transferPredicate
              $transferWhereStart
              AND date<=?
            """.trimIndent(),
            transferArgs
        ).use { c ->
            while (c.moveToNext()) {
                val amount = c.dbl("settled_amount")
                acc(
                    c.long("from_partner_id"),
                    c.str("from_partner_name")
                ).sent += amount
                acc(
                    c.long("to_partner_id"),
                    c.str("to_partner_name")
                ).received += amount
            }
        }

        getPartners().forEach {
            acc(it.id, it.name)
        }

        return map.map { (id, a) ->
            PartnerFundPeriodSummary(
                partnerId = id,
                partnerName = a.name.ifBlank { "合伙人$id" },
                purchasePaid = roundMoney(a.purchase),
                expensePaid = roundMoney(a.expense),
                revenueReceived = roundMoney(a.receipt),
                profitShare = roundMoney(a.profit),
                settlementSent = roundMoney(a.sent),
                settlementReceived = roundMoney(a.received)
            )
        }.sortedBy { it.partnerId }
    }

    private fun readSettlementTransfer(c: Cursor): SettlementTransferRecord =
        SettlementTransferRecord(
            c.long("id"),
            c.long("settlement_id"),
            c.str("date"),
            c.long("from_partner_id"),
            c.str("from_partner_name"),
            c.long("to_partner_id"),
            c.str("to_partner_name"),
            c.dbl("amount"),
            c.dbl("settled_amount"),
            c.str("settlement_kind"),
            c.str("batch_key"),
            c.str("range_start_date"),
            c.str("range_end_date"),
            c.int("business_day_count"),
            c.long("confirmed_at"),
            c.long("focus_partner_id"),
            c.str("focus_partner_name")
        )

    private fun getSettlementTransfersForPartnerOnDate(
        partnerId: Long,
        date: String
    ): List<SettlementTransferRecord> {
        val transferPredicate = effectiveFundTransferPredicate()
        return readableDatabase.rawQuery(
            """
            SELECT *
            FROM settlement_transfer
            WHERE deleted=0
              AND $transferPredicate
              AND date=?
              AND (from_partner_id=? OR to_partner_id=?)
            ORDER BY id
            """.trimIndent(),
            arrayOf(date, partnerId.toString(), partnerId.toString())
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(readSettlementTransfer(c))
                }
            }
        }
    }

    private fun countBusinessDays(
        startDate: String,
        endDate: String
    ): Int {
        if (startDate.isBlank() || endDate.isBlank()) return 0
        val count =
            readableDatabase.rawQuery(
                """
                SELECT COUNT(DISTINCT date) AS c
                FROM store_daily_record
                WHERE deleted=0
                  AND date>=?
                  AND date<=?
                """.trimIndent(),
                arrayOf(startDate, endDate)
            ).use { c ->
                if (c.moveToFirst()) c.int("c") else 0
            }
        return count
    }

    fun getPartnerOutstandingWindow(
        partnerId: Long,
        endDate: String = LocalDate.now().toString()
    ): PartnerOutstandingWindow? {
        normalizeLegacyCutoffTransfersForCurrentCenter()
        val transferPredicate = effectiveFundTransferPredicate()
        val summary =
            getPartnerFundBalances(endDate)
                .firstOrNull { it.partnerId == partnerId }
                ?: return null

        val dates =
            readableDatabase.rawQuery(
                """
                SELECT date FROM (
                    SELECT date FROM purchase_order
                    WHERE deleted=0 AND date<=?
                    UNION
                    SELECT date FROM store_daily_record
                    WHERE deleted=0 AND date<=?
                    UNION
                    SELECT date FROM profit_distribution
                    WHERE deleted=0 AND date<=?
                    UNION
                    SELECT date FROM settlement_transfer
                    WHERE deleted=0
                      AND $transferPredicate
                      AND settled_amount>0.005
                      AND date<=?
                )
                ORDER BY date
                """.trimIndent(),
                arrayOf(endDate, endDate, endDate, endDate)
            ).use { c ->
                buildList {
                    while (c.moveToNext()) add(c.str("date"))
                }
            }

        var cumulative = 0.0
        var lastClearedDate = ""
        var lastClearedIndex = -1
        var totalSettled = 0.0
        val rawActivity = linkedMapOf<String, Double>()

        dates.forEachIndexed { index, date ->
            val purchase =
                getPurchaseTotalsByPartner(date)
                    .firstOrNull { it.partnerId == partnerId }
                    ?.amount ?: 0.0
            val expense =
                getExpenseTotalsByPartner(date)
                    .firstOrNull { it.partnerId == partnerId }
                    ?.amount ?: 0.0
            val receipt =
                getReceiptsByPartner(date)
                    .firstOrNull { it.partnerId == partnerId }
                    ?.amount ?: 0.0
            val profit =
                getProfitDistribution(date)
                    .firstOrNull { it.partnerId == partnerId }
                    ?.allocatedProfit ?: 0.0
            val raw = roundMoney(purchase + expense + profit - receipt)
            rawActivity[date] = raw

            val transfers =
                getSettlementTransfersForPartnerOnDate(partnerId, date)
                    .filter { it.settledAmount > 0.005 }
            val sent =
                transfers
                    .filter { it.fromPartnerId == partnerId }
                    .sumOf { it.settledAmount }
            val received =
                transfers
                    .filter { it.toPartnerId == partnerId }
                    .sumOf { it.settledAmount }
            totalSettled += transfers.sumOf { it.settledAmount }
            cumulative = roundMoney(cumulative + raw + sent - received)
            val partnerHadActivity =
                kotlin.math.abs(raw) > 0.005 || transfers.isNotEmpty()
            if (
                partnerHadActivity &&
                kotlin.math.abs(cumulative) <= 0.005
            ) {
                lastClearedDate = date
                lastClearedIndex = index
            }
        }

        val current = roundMoney(summary.currentBalance)
        var rangeStart = ""
        if (kotlin.math.abs(current) > 0.005) {
            val startIndex = (lastClearedIndex + 1).coerceAtLeast(0)
            rangeStart =
                dates.drop(startIndex)
                    .firstOrNull { date ->
                        kotlin.math.abs(rawActivity[date] ?: 0.0) > 0.005
                    }
                    ?: dates.drop(startIndex).firstOrNull().orEmpty()
        }
        val businessDays =
            if (rangeStart.isBlank()) 0
            else countBusinessDays(rangeStart, endDate).let { count ->
                if (count > 0) count
                else dates.count { it >= rangeStart && it <= endDate }
            }

        return PartnerOutstandingWindow(
            partnerId = partnerId,
            partnerName = summary.partnerName,
            endDate = endDate,
            currentBalance = current,
            rangeStartDate = rangeStart,
            rangeEndDate = if (rangeStart.isBlank()) "" else endDate,
            businessDayCount = businessDays,
            lastClearedDate = lastClearedDate,
            settledTransferAmount = roundMoney(totalSettled)
        )
    }

    fun settlePartnerThroughDate(
        partnerId: Long,
        endDate: String = LocalDate.now().toString()
    ): FundBalanceSettlementResult {
        val center =
            getSettlementCenter()
                ?: return FundBalanceSettlementResult(
                    false,
                    "请先到利润分配设置资金中心"
                )
        if (partnerId == center.id) {
            return FundBalanceSettlementResult(
                false,
                "${center.name} 是资金中心，不需要单独执行“结清截至今日”"
            )
        }

        val target =
            getPartnerFundBalances(endDate)
                .firstOrNull {
                    it.partnerId == partnerId &&
                        kotlin.math.abs(it.currentBalance) > 0.005
                }
                ?: return FundBalanceSettlementResult(
                    true,
                    "截至 $endDate 已经结清，无需再次处理"
                )

        val window =
            getPartnerOutstandingWindow(partnerId, endDate)
                ?: return FundBalanceSettlementResult(
                    false,
                    "没有找到该合伙人的资金记录"
                )

        val amount = roundMoney(kotlin.math.abs(target.currentBalance))
        if (amount <= 0.005) {
            return FundBalanceSettlementResult(
                true,
                "截至 $endDate 已经结清，无需再次处理"
            )
        }

        val fromId: Long
        val fromName: String
        val toId: Long
        val toName: String
        if (target.currentBalance > 0.005) {
            fromId = center.id
            fromName = center.name
            toId = target.partnerId
            toName = target.partnerName
        } else {
            fromId = target.partnerId
            fromName = target.partnerName
            toId = center.id
            toName = center.name
        }

        val db = writableDatabase
        val now = System.currentTimeMillis()
        val batchKey = UUID.randomUUID().toString()
        db.beginTransaction()
        return try {
            val inserted =
                db.insert(
                    "settlement_transfer",
                    null,
                    baseSyncValues().apply {
                        put("settlement_id", 0)
                        put("date", endDate)
                        put("from_partner_id", fromId)
                        put("from_partner_name", fromName)
                        put("to_partner_id", toId)
                        put("to_partner_name", toName)
                        put("amount", amount)
                        put("settled_amount", amount)
                        put("settlement_kind", "CUTOFF_CENTER")
                        put("batch_key", batchKey)
                        put(
                            "range_start_date",
                            window.rangeStartDate.ifBlank { endDate }
                        )
                        put("range_end_date", endDate)
                        put(
                            "business_day_count",
                            window.businessDayCount
                        )
                        put("confirmed_at", now)
                        put("focus_partner_id", target.partnerId)
                        put("focus_partner_name", target.partnerName)
                        put("deleted", 0)
                    }
                )
            if (inserted <= 0) {
                throw IllegalStateException("写入结清记录失败")
            }

            db.setTransactionSuccessful()
            FundBalanceSettlementResult(
                true,
                "${target.partnerName} 已结清截至 $endDate 的累计未结 ${moneyForMessage(amount)} 元：$fromName → $toName",
                amount,
                1
            )
        } catch (e: Exception) {
            FundBalanceSettlementResult(
                false,
                "结清失败：${e.message ?: "未知错误"}"
            )
        } finally {
            db.endTransaction()
        }
    }

    private fun moneyForMessage(value: Double): String =
        String.format(Locale.CHINA, "%.2f", roundMoney(value))

    fun getFundSettlementHistory(
        startDate: String? = null,
        endDate: String? = null,
        limit: Int = 120
    ): List<FundSettlementHistoryRecord> {
        normalizeLegacyCutoffTransfersForCurrentCenter()
        val records = mutableListOf<FundSettlementHistoryRecord>()

        getCashSettlementsBetween(startDate, endDate).forEach { bundle ->
            val settledTransfers =
                bundle.transfers.filter { it.settledAmount > 0.005 }
            if (settledTransfers.isNotEmpty()) {
                val total = roundMoney(bundle.transfers.sumOf { it.amount })
                val settled = roundMoney(settledTransfers.sumOf { it.settledAmount })
                val status =
                    when {
                        total <= 0.005 -> 1
                        settled + 0.005 >= total -> 1
                        settled > 0.005 -> 2
                        else -> 0
                    }
                val confirmed =
                    listOf(
                        bundle.settlement.confirmedAt,
                        settledTransfers.maxOfOrNull { it.confirmedAt } ?: 0L,
                        bundle.settlement.updatedAt
                    ).maxOrNull() ?: 0L
                records +=
                    FundSettlementHistoryRecord(
                        recordKey = "daily-${bundle.settlement.id}",
                        kind = "DAILY",
                        settlementDate = bundle.settlement.date,
                        periodStart = bundle.settlement.date,
                        periodEnd = bundle.settlement.date,
                        businessDayCount = 1,
                        focusPartnerId = 0L,
                        focusPartnerName = "",
                        totalAmount = total,
                        settledAmount = settled,
                        status = status,
                        confirmedAt = confirmed,
                        transfers = bundle.transfers
                    )
            }
        }

        val clauses =
            mutableListOf(
                "deleted=0",
                "settlement_id=0",
                "(settled_amount>0.005 OR (settlement_kind IN ('CUTOFF','CUTOFF_CENTER','CUTOFF_LEGACY_INVALID') AND confirmed_at>0))"
            )
        val args = mutableListOf<String>()
        if (startDate != null) {
            clauses += "date>=?"
            args += startDate
        }
        if (endDate != null) {
            clauses += "date<=?"
            args += endDate
        }
        val looseTransfers =
            readableDatabase.rawQuery(
                "SELECT * FROM settlement_transfer WHERE ${clauses.joinToString(" AND ")} ORDER BY date DESC,id DESC",
                args.toTypedArray()
            ).use { c ->
                buildList {
                    while (c.moveToNext()) add(readSettlementTransfer(c))
                }
            }

        looseTransfers
            .groupBy { transfer ->
                transfer.batchKey.ifBlank { "legacy-${transfer.id}" }
            }
            .forEach { (key, rows) ->
                val first = rows.first()
                val total = roundMoney(rows.sumOf { it.amount })
                val settled = roundMoney(rows.sumOf { it.settledAmount })
                records +=
                    FundSettlementHistoryRecord(
                        recordKey = key,
                        kind = first.settlementKind.ifBlank { "LEGACY" },
                        settlementDate = first.date,
                        periodStart = first.rangeStartDate.ifBlank { first.date },
                        periodEnd = first.rangeEndDate.ifBlank { first.date },
                        businessDayCount = first.businessDayCount.coerceAtLeast(0),
                        focusPartnerId = first.focusPartnerId,
                        focusPartnerName = first.focusPartnerName,
                        totalAmount = total,
                        settledAmount = settled,
                        status =
                            when {
                                first.settlementKind == "CUTOFF_LEGACY_INVALID" -> 4
                                first.settlementKind in
                                    setOf("CUTOFF", "CUTOFF_CENTER") &&
                                    settled <= 0.005 &&
                                    rows.any { it.confirmedAt > 0L } -> 3
                                settled + 0.005 >= total -> 1
                                settled > 0.005 -> 2
                                else -> 0
                            },
                        confirmedAt = rows.maxOfOrNull { it.confirmedAt } ?: 0L,
                        transfers = rows
                    )
            }

        return records
            .sortedWith(
                compareByDescending<FundSettlementHistoryRecord> { it.settlementDate }
                    .thenByDescending { it.confirmedAt }
            )
            .take(limit)
    }

    fun undoCutoffSettlement(batchKey: String): Boolean {
        if (batchKey.isBlank()) return false
        val exists =
            readableDatabase.rawQuery(
                """
                SELECT COUNT(*) AS c
                FROM settlement_transfer
                WHERE deleted=0
                  AND settlement_id=0
                  AND settlement_kind IN ('CUTOFF','CUTOFF_CENTER')
                  AND batch_key=?
                  AND confirmed_at>0
                """.trimIndent(),
                arrayOf(batchKey)
            ).use { c ->
                c.moveToFirst() && c.int("c") > 0
            }
        if (!exists) return false

        val now = System.currentTimeMillis()
        return writableDatabase.update(
            "settlement_transfer",
            ContentValues().apply {
                put("settled_amount", 0)
                // confirmed_at 保留原确认时间，用于在结算记录中显示“已撤销”
                put("sync_status", 2)
                put("updated_at", now)
            },
            "deleted=0 AND settlement_id=0 AND settlement_kind IN ('CUTOFF','CUTOFF_CENTER') AND batch_key=?",
            arrayOf(batchKey)
        ) > 0
    }

    fun getPartnerDailyFundBalances(
        partnerId: Long,
        startDate: String? = null,
        endDate: String? = null
    ): List<PartnerDailyFundBalanceRecord> {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (startDate != null) {
            clauses += "date>=?"
            args += startDate
        }
        if (endDate != null) {
            clauses += "date<=?"
            args += endDate
        }
        val where =
            if (clauses.isEmpty()) ""
            else "WHERE " + clauses.joinToString(" AND ")

        val dates =
            readableDatabase.rawQuery(
                """
                SELECT date FROM (
                    SELECT date FROM purchase_order WHERE deleted=0
                    UNION
                    SELECT date FROM store_daily_record WHERE deleted=0
                    UNION
                    SELECT date FROM profit_distribution WHERE deleted=0
                )
                $where
                ORDER BY date DESC
                """.trimIndent(),
                args.toTypedArray()
            ).use { c ->
                buildList {
                    while (c.moveToNext()) add(c.str("date"))
                }
            }

        return dates.mapNotNull { date ->
            val purchase =
                getPurchaseTotalsByPartner(date)
                    .firstOrNull { it.partnerId == partnerId }
            val expense =
                getExpenseTotalsByPartner(date)
                    .firstOrNull { it.partnerId == partnerId }
            val receipt =
                getReceiptsByPartner(date)
                    .firstOrNull { it.partnerId == partnerId }
            val profit =
                getProfitDistribution(date)
                    .firstOrNull { it.partnerId == partnerId }
            val bundle = getCashSettlement(date)
            val settlementPartner =
                bundle?.partners?.firstOrNull { it.partnerId == partnerId }

            val name =
                settlementPartner?.partnerName
                    ?: profit?.partnerName
                    ?: purchase?.partnerName
                    ?: receipt?.partnerName
                    ?: expense?.partnerName
                    ?: getPartnerFundBalances(endDate = date)
                        .firstOrNull { it.partnerId == partnerId }
                        ?.partnerName
                    ?: "合伙人$partnerId"

            val purchaseAmount = roundMoney(purchase?.amount ?: 0.0)
            val expenseAmount = roundMoney(expense?.amount ?: 0.0)
            val receiptAmount = roundMoney(receipt?.amount ?: 0.0)
            val profitAmount = roundMoney(profit?.allocatedProfit ?: 0.0)
            val sourceBalance =
                roundMoney(
                    purchaseAmount +
                        expenseAmount +
                        profitAmount -
                        receiptAmount
                )

            val relatedTransfers =
                getSettlementTransfersForPartnerOnDate(
                    partnerId,
                    date
                )

            val settledAmount =
                roundMoney(
                    relatedTransfers
                        .filter { it.settledAmount > 0.005 }
                        .sumOf { it.settledAmount }
                )

            // “资金余额”是当日结算后的扩展视图。
            // 这里的 remainingBalance 表示截至该日的累计未结余额，
            // 包括当天最少转账方案以及“结清截至今日”产生的真实转账。
            val remaining =
                getPartnerFundBalances(endDate = date)
                    .firstOrNull { it.partnerId == partnerId }
                    ?.currentBalance
                    ?.let(::roundMoney)
                    ?: 0.0

            val hasActivity =
                kotlin.math.abs(purchaseAmount) > 0.005 ||
                    kotlin.math.abs(expenseAmount) > 0.005 ||
                    kotlin.math.abs(receiptAmount) > 0.005 ||
                    kotlin.math.abs(profitAmount) > 0.005 ||
                    relatedTransfers.isNotEmpty()
            if (!hasActivity) {
                null
            } else {
                PartnerDailyFundBalanceRecord(
                    date = date,
                    partnerId = partnerId,
                    partnerName = name,
                    purchasePaid = purchaseAmount,
                    expensePaid = expenseAmount,
                    revenueReceived = receiptAmount,
                    profitShare = profitAmount,
                    dayBalance = sourceBalance,
                    settledAmount = settledAmount,
                    remainingBalance = remaining,
                    settlementId = bundle?.settlement?.id ?: 0L,
                    transfers = relatedTransfers
                )
            }
        }
    }

    fun getPartnerSettlementEvents(
        partnerId: Long,
        startDate: String? = null,
        endDate: String? = null
    ): List<SettlementTransferRecord> {
        normalizeLegacyCutoffTransfersForCurrentCenter()
        val transferPredicate = effectiveFundTransferPredicate()
        val clauses = mutableListOf(
            "deleted=0",
            "settled_amount>0.005",
            transferPredicate,
            "(from_partner_id=? OR to_partner_id=?)"
        )
        val args = mutableListOf(
            partnerId.toString(),
            partnerId.toString()
        )
        if (startDate != null) {
            clauses += "date>=?"
            args += startDate
        }
        if (endDate != null) {
            clauses += "date<=?"
            args += endDate
        }

        return readableDatabase.rawQuery(
            "SELECT * FROM settlement_transfer WHERE ${clauses.joinToString(" AND ")} ORDER BY date DESC, confirmed_at DESC, id DESC",
            args.toTypedArray()
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(readSettlementTransfer(c))
            }
        }
    }

    fun settlePartnerForDate(
        date: String,
        partnerId: Long
    ): PartnerDateSettlementResult {
        var bundle = getCashSettlement(date)
        if (bundle == null) {
            val generated = generateCashSettlement(date)
            if (!generated.success) {
                return PartnerDateSettlementResult(false, generated.message)
            }
            bundle = generated.bundle ?: getCashSettlement(date)
        }

        val partner =
            bundle?.partners?.firstOrNull { it.partnerId == partnerId }
                ?: return PartnerDateSettlementResult(false, "当天没有该合伙人的资金结算数据")
        val amount = roundMoney(kotlin.math.abs(partner.balance))
        if (amount <= 0.005) {
            return PartnerDateSettlementResult(true, "当天无需转账")
        }
        val changed =
            setCashSettlementPartnerSettledAmount(
                bundle.settlement.id,
                partnerId,
                amount
            )
        return if (changed) {
            PartnerDateSettlementResult(true, "${partner.partnerName} · $date 已结清 ${roundMoney(amount)} 元")
        } else {
            PartnerDateSettlementResult(false, "结算失败，请检查当天资金轧差方案")
        }
    }

    fun markPartnerDateUnsettled(
        date: String,
        partnerId: Long
    ): PartnerDateSettlementResult {
        val bundle = getCashSettlement(date)
            ?: return PartnerDateSettlementResult(false, "当天还没有结算记录")
        val partner =
            bundle.partners.firstOrNull { it.partnerId == partnerId }
                ?: return PartnerDateSettlementResult(false, "当天没有该合伙人的资金结算数据")
        val changed =
            setCashSettlementPartnerSettledAmount(
                bundle.settlement.id,
                partnerId,
                0.0
            )
        return if (changed) {
            PartnerDateSettlementResult(true, "${partner.partnerName} · $date 已改为未结算；对应转账双方余额已同步恢复")
        } else {
            PartnerDateSettlementResult(false, "修改失败")
        }
    }

    fun settleCurrentFundBalances(
        partnerIds: Set<Long>,
        settlementDate: String = LocalDate.now().toString()
    ): FundBalanceSettlementResult {
        if (partnerIds.isEmpty()) {
            return FundBalanceSettlementResult(false, "请至少选择两位需要结算的合伙人")
        }

        val selected =
            getPartnerFundBalances()
                .filter {
                    it.partnerId in partnerIds &&
                        kotlin.math.abs(it.currentBalance) > 0.005
                }

        if (selected.size < 2) {
            return FundBalanceSettlementResult(false, "至少需要一位应补和一位应收的合伙人")
        }

        data class MutableFund(
            val id: Long,
            val name: String,
            var amount: Double
        )

        val payers =
            selected
                .filter { it.currentBalance < -0.005 }
                .map {
                    MutableFund(
                        it.partnerId,
                        it.partnerName,
                        roundMoney(-it.currentBalance)
                    )
                }
                .toMutableList()
        val receivers =
            selected
                .filter { it.currentBalance > 0.005 }
                .map {
                    MutableFund(
                        it.partnerId,
                        it.partnerName,
                        roundMoney(it.currentBalance)
                    )
                }
                .toMutableList()

        if (payers.isEmpty() || receivers.isEmpty()) {
            return FundBalanceSettlementResult(
                false,
                "所选合伙人没有可以互相轧差的应收和应补余额"
            )
        }

        data class Transfer(
            val fromId: Long,
            val fromName: String,
            val toId: Long,
            val toName: String,
            val amount: Double
        )

        val transfers = mutableListOf<Transfer>()
        var i = 0
        var j = 0
        while (i < payers.size && j < receivers.size) {
            val amount =
                roundMoney(
                    minOf(
                        payers[i].amount,
                        receivers[j].amount
                    )
                )
            if (amount > 0.005) {
                transfers +=
                    Transfer(
                        payers[i].id,
                        payers[i].name,
                        receivers[j].id,
                        receivers[j].name,
                        amount
                    )
            }
            payers[i].amount =
                roundMoney(payers[i].amount - amount)
            receivers[j].amount =
                roundMoney(receivers[j].amount - amount)
            if (payers[i].amount <= 0.005) i++
            if (receivers[j].amount <= 0.005) j++
        }

        if (transfers.isEmpty()) {
            return FundBalanceSettlementResult(false, "当前没有可执行的资金结算")
        }

        val db = writableDatabase
        db.beginTransaction()
        return try {
            transfers.forEach { t ->
                val id =
                    db.insert(
                        "settlement_transfer",
                        null,
                        baseSyncValues().apply {
                            // settlement_id=0 表示 FIX3 的累计资金余额统一结算，
                            // 不属于某一天的资金轧差方案。
                            put("settlement_id", 0)
                            put("date", settlementDate)
                            put("from_partner_id", t.fromId)
                            put("from_partner_name", t.fromName)
                            put("to_partner_id", t.toId)
                            put("to_partner_name", t.toName)
                            put("amount", t.amount)
                            put("settled_amount", t.amount)
                            put("settlement_kind", "LEGACY")
                            put("batch_key", "legacy-${UUID.randomUUID()}")
                            put("range_start_date", settlementDate)
                            put("range_end_date", settlementDate)
                            put("business_day_count", 0)
                            put("confirmed_at", System.currentTimeMillis())
                            put("focus_partner_id", 0)
                            put("focus_partner_name", "")
                            put("deleted", 0)
                        }
                    )
                if (id <= 0) {
                    throw IllegalStateException("写入统一资金结算失败")
                }
            }
            db.setTransactionSuccessful()
            val total = roundMoney(transfers.sumOf { it.amount })
            FundBalanceSettlementResult(
                true,
                "统一结算已完成 ${roundMoney(total)} 元，共 ${transfers.size} 笔",
                total,
                transfers.size
            )
        } catch (e: Exception) {
            FundBalanceSettlementResult(
                false,
                "统一结算失败：${e.message ?: "未知错误"}"
            )
        } finally {
            db.endTransaction()
        }
    }

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

    fun getSettlementCenter(): PartnerOption? {
        val explicit =
            readableDatabase.rawQuery(
                """
                SELECT p.id,p.name
                FROM profit_rule r
                JOIN partner p ON p.id=r.partner_id
                WHERE r.deleted=0
                  AND p.deleted=0
                  AND p.enabled=1
                  AND COALESCE(r.is_settlement_center,0)=1
                ORDER BY r.id DESC
                LIMIT 1
                """.trimIndent(),
                null
            ).use { c ->
                if (c.moveToFirst()) {
                    PartnerOption(
                        c.long("id"),
                        c.str("name")
                    )
                } else {
                    null
                }
            }
        if (explicit != null) return explicit

        val fallbackRule =
            readableDatabase.rawQuery(
                """
                SELECT p.id,p.name
                FROM profit_rule r
                JOIN partner p ON p.id=r.partner_id
                WHERE r.deleted=0
                  AND p.deleted=0
                  AND p.enabled=1
                ORDER BY r.id
                LIMIT 1
                """.trimIndent(),
                null
            ).use { c ->
                if (c.moveToFirst()) {
                    PartnerOption(
                        c.long("id"),
                        c.str("name")
                    )
                } else {
                    null
                }
            }
        return fallbackRule ?: getPartners().firstOrNull()
    }

    fun setSettlementCenter(partnerId: Long): Boolean {
        val partner =
            getPartnerById(partnerId)
                ?: return false
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val now = System.currentTimeMillis()
            db.update(
                "profit_rule",
                ContentValues().apply {
                    put("is_settlement_center", 0)
                    put("sync_status", 2)
                    put("updated_at", now)
                },
                "deleted=0",
                null
            )
            val changed =
                db.update(
                    "profit_rule",
                    ContentValues().apply {
                        put("is_settlement_center", 1)
                        put("sync_status", 2)
                        put("updated_at", now)
                    },
                    "partner_id=? AND deleted=0",
                    arrayOf(partner.id.toString())
                ) > 0
            db.setTransactionSuccessful()
            changed
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

    fun saveProfitRules(
        rules: List<Pair<PartnerOption, Double>>,
        settlementCenterId: Long? = null
    ): Boolean {
        if (rules.isEmpty()) return false
        val total = rules.sumOf { it.second }
        if (kotlin.math.abs(total - 100.0) >= 0.01) return false

        val validIds = rules.map { it.first.id }.toSet()
        val chosenCenterId =
            settlementCenterId
                ?.takeIf { it in validIds }
                ?: getSettlementCenter()
                    ?.id
                    ?.takeIf { it in validIds }
                ?: rules.first().first.id

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
                    put(
                        "is_settlement_center",
                        if (partner.id == chosenCenterId) 1 else 0
                    )
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
        if (kotlin.math.abs(profit) <= 0.005) return false

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

        val dailyRows =
            getProfitSettlementDaily(start, end)

        val selectedNet =
            dailyRows
                .filter { it.partnerId in partnerIds }
                .groupBy { it.partnerId }
                .mapValues { (_, rows) ->
                    roundMoney(rows.sumOf { it.pendingProfit })
                }

        val payablePartnerIds =
            selectedNet
                .filterValues { it > 0.005 }
                .keys

        val pendingRows =
            dailyRows.filter {
                it.partnerId in payablePartnerIds &&
                    kotlin.math.abs(it.pendingProfit) > 0.005
            }

        if (pendingRows.isEmpty()) {
            return ProfitSettlementCreateResult(
                false,
                "所选合伙人抵扣亏损后没有可发放的净利润"
            )
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
                message = "已确认利润净额 ${roundMoney(total)} 元（已自动抵扣同范围亏损）",
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
        listOf("fruit", "store", "partner", "purchase_plan", "purchase_plan_item", "purchase_order", "purchase_item", "purchase_activity", "purchase_collaboration", "store_daily_record", "profit_rule", "profit_distribution", "daily_cash_settlement", "settlement_partner", "settlement_transfer", "profit_settlement_batch", "profit_settlement_item").forEach { table ->
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

    private fun order(
        c: Cursor
    ) =
        PurchaseOrderRecord(
            id =
                c.long("id"),
            date =
                c.str("date"),
            buyerId =
                c.long("buyer_id"),
            buyerName =
                c.str("buyer_name"),
            storeId =
                c.long("store_id"),
            storeName =
                c.str("store_name"),
            totalCost =
                c.dbl("total_cost"),
            remark =
                c.str("remark"),
            createdAt =
                c.long("created_at")
        )

    private fun encodeReceiptSplits(
        splits: List<ReceiptSplitRecord>
    ): String {
        val array = JSONArray()
        splits
            .filter { it.amount > 0.005 }
            .forEach { split ->
                array.put(
                    JSONObject().apply {
                        put("partner_id", split.partnerId)
                        put("partner_name", split.partnerName)
                        put("amount", roundMoney(split.amount))
                        put("wechat_income", roundMoney(split.wechatIncome))
                        put("alipay_income", roundMoney(split.alipayIncome))
                        put("cash_income", roundMoney(split.cashIncome))
                    }
                )
            }
        return array.toString()
    }

    private fun decodeReceiptSplits(
        raw: String
    ): List<ReceiptSplitRecord> =
        runCatching {
            val array = JSONArray(raw.ifBlank { "[]" })
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val wechat = roundMoney(item.optDouble("wechat_income", 0.0))
                    val alipay = roundMoney(item.optDouble("alipay_income", 0.0))
                    val cash = roundMoney(item.optDouble("cash_income", 0.0))
                    val channelTotal = roundMoney(wechat + alipay + cash)
                    val amount =
                        if (channelTotal > 0.005) {
                            channelTotal
                        } else {
                            roundMoney(item.optDouble("amount", 0.0))
                        }
                    if (amount <= 0.005) continue
                    add(
                        ReceiptSplitRecord(
                            partnerId = item.optLong("partner_id", 0L),
                            partnerName = item.optString("partner_name", "未指定").ifBlank { "未指定" },
                            amount = amount,
                            wechatIncome = wechat,
                            alipayIncome = alipay,
                            cashIncome = cash
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())

    private fun receiptSplitsFromCursor(c: Cursor): List<ReceiptSplitRecord> {
        val stored =
            decodeReceiptSplits(
                if (c.getColumnIndex("receipt_splits_json") >= 0) {
                    c.str("receipt_splits_json")
                } else {
                    "[]"
                }
            )
        if (stored.isNotEmpty()) return stored

        // V1.4.6 及更早记录没有 receipt_splits_json。按原来的三个支付渠道
        // 回放收款归属，避免旧记录中不同渠道由不同人收款时被合并错人。
        // V1.4.7 初版如果另一台旧设备只拿到兼容字段，会看到“多人收款”。
        // “多人收款”不是实际合伙人，不能把整笔钱错误记到这个虚拟名称下。
        val legacyNames =
            listOf(
                c.str("wechat_collector_name"),
                c.str("alipay_collector_name"),
                c.str("cash_collector_name")
            )
        if (legacyNames.any { it == "多人收款" }) {
            val revenue =
                roundMoney(
                    c.dbl("wechat_income") +
                        c.dbl("alipay_income") +
                        c.dbl("cash_income")
                )
            return if (revenue > 0.005) {
                listOf(
                    ReceiptSplitRecord(
                        partnerId = 0L,
                        partnerName = "多人收款明细未同步",
                        amount = revenue,
                        wechatIncome = c.dbl("wechat_income"),
                        alipayIncome = c.dbl("alipay_income"),
                        cashIncome = c.dbl("cash_income")
                    )
                )
            } else {
                emptyList()
            }
        }

        return listOf(
            ReceiptSplitRecord(
                partnerId = c.long("wechat_collector_id"),
                partnerName = c.str("wechat_collector_name").ifBlank { "未指定" },
                amount = c.dbl("wechat_income"),
                wechatIncome = c.dbl("wechat_income")
            ),
            ReceiptSplitRecord(
                partnerId = c.long("alipay_collector_id"),
                partnerName = c.str("alipay_collector_name").ifBlank { "未指定" },
                amount = c.dbl("alipay_income"),
                alipayIncome = c.dbl("alipay_income")
            ),
            ReceiptSplitRecord(
                partnerId = c.long("cash_collector_id"),
                partnerName = c.str("cash_collector_name").ifBlank { "未指定" },
                amount = c.dbl("cash_income"),
                cashIncome = c.dbl("cash_income")
            )
        )
            .filter { it.amount > 0.005 }
            .groupBy { it.partnerId to it.partnerName }
            .map { (key, parts) ->
                ReceiptSplitRecord(
                    partnerId = key.first,
                    partnerName = key.second,
                    amount = roundMoney(parts.sumOf { it.amount }),
                    wechatIncome = roundMoney(parts.sumOf { it.wechatIncome }),
                    alipayIncome = roundMoney(parts.sumOf { it.alipayIncome }),
                    cashIncome = roundMoney(parts.sumOf { it.cashIncome })
                )
            }
    }

    private fun dailyRecord(c: Cursor) = StoreDailyRecord(
        c.long("id"), c.str("date"), c.long("store_id"), c.str("store_name"),
        c.dbl("wechat_income"), c.long("wechat_collector_id"), c.str("wechat_collector_name"),
        c.dbl("alipay_income"), c.long("alipay_collector_id"), c.str("alipay_collector_name"),
        c.dbl("cash_income"), c.long("cash_collector_id"), c.str("cash_collector_name"),
        receiptSplitsFromCursor(c),
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

    private fun tableColumns(
        db: SQLiteDatabase,
        table: String
    ): Set<String> =
        db.rawQuery(
            "PRAGMA table_info($table)",
            null
        ).use {
            c ->
            buildSet {
                while (
                    c.moveToNext()
                ) {
                    add(
                        c.str("name")
                    )
                }
            }
        }

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
        const val DB_VERSION = 22
    }
}

private fun roundMoney(v: Double): Double = round(v * 100.0) / 100.0

fun currentMonthRange(today: LocalDate = LocalDate.now()): Pair<String, String> =
    today.withDayOfMonth(1).toString() to today.withDayOfMonth(today.lengthOfMonth()).toString()
