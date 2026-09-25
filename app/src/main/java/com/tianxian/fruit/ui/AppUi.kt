package com.tianxian.fruit.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.graphics.Paint as AndroidPaint
import android.graphics.BitmapFactory
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tianxian.fruit.BuildConfig
import com.tianxian.fruit.data.*
import com.tianxian.fruit.report.ReportGenerator
import com.tianxian.fruit.report.ReportLine
import com.tianxian.fruit.report.ReportLineStyle
import com.tianxian.fruit.sync.LedgerBook
import com.tianxian.fruit.sync.BookPermissions
import com.tianxian.fruit.sync.LedgerManager
import com.tianxian.fruit.sync.CloudApiException
import com.tianxian.fruit.sync.CloudAuditInfo
import com.tianxian.fruit.sync.CloudBookInfo
import com.tianxian.fruit.sync.CloudSyncManager
import com.tianxian.fruit.sync.WeatherAlert
import com.tianxian.fruit.sync.WeatherClient
import com.tianxian.fruit.sync.WeatherOverview
import com.tianxian.fruit.sync.WeatherHour
import com.tianxian.fruit.update.AppUpdateCheckResult
import com.tianxian.fruit.update.AppUpdateInfo
import com.tianxian.fruit.update.AppUpdateManager
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val BrandGreen = Color(0xFF13A868)
private val SoftGreen = Color(0xFFE9F8F0)
private val SoftOrange = Color(0xFFFFF3E3)
private val SoftBlue = Color(0xFFEAF3FF)
private val SoftPurple = Color(0xFFF3ECFF)

enum class AppPage(val title: String, val emoji: String) {
    HOME("首页", "🏠"),
    INVENTORY("库存", "📦"),
    PURCHASE("采购", "🛒"),
    SESSION("营业", "📝"),
    SETTLEMENT("结算", "🧾"),
    MORE("更多", "☰"),
    PLAN("采购计划", "📋")
}

private enum class MorePage {
    MENU,
    BOOKS,
    CLOUD_BOOKS,
    MEMBER_PERMISSIONS,
    SYSTEM_ADMIN,
    HOME_HEADER,
    HOME_QUICK_ACTIONS,
    SECURITY,
    ABOUT,
    HISTORY,
    PURCHASE_ACTIVITY,
    STATS,
    OPERATING_ANALYSIS,
    PERSONAL_SUMMARY,
    BACKUP,
    PARTNERS,
    STORES,
    PROFIT,
    FRUITS,
    FRUIT_LIBRARY,
    REPORT
}

private enum class HomeQuickAction(
    val icon: String,
    val label: String,
    val target: MorePage
) {
    HISTORY("🧾", "历史记录", MorePage.HISTORY),
    PURCHASE_ACTIVITY("🛒", "协作采购", MorePage.PURCHASE_ACTIVITY),
    STATS("📊", "经营统计", MorePage.STATS),
    OPERATING_ANALYSIS("🧮", "经营分析", MorePage.OPERATING_ANALYSIS),
    PERSONAL_SUMMARY("👤", "个人汇总", MorePage.PERSONAL_SUMMARY),
    REPORT("📄", "生成报表", MorePage.REPORT),
    PROFIT("💰", "利润分配", MorePage.PROFIT),
    BACKUP("💾", "数据备份", MorePage.BACKUP),
    CLOUD_BOOKS("☁️", "云端账本", MorePage.CLOUD_BOOKS),
    MEMBER_PERMISSIONS("👥", "成员权限", MorePage.MEMBER_PERMISSIONS),
    PARTNERS("🤝", "合伙人", MorePage.PARTNERS),
    STORES("📍", "位置管理", MorePage.STORES),
    FRUITS("🍇", "商品管理", MorePage.FRUITS),
    SECURITY("🔐", "安全验证", MorePage.SECURITY),
    HOME_HEADER("🎨", "首页设置", MorePage.HOME_HEADER),
    ABOUT("ℹ️", "关于", MorePage.ABOUT),
    BOOKS("📚", "账本管理", MorePage.BOOKS),
    SYSTEM_ADMIN("🛡", "系统管理", MorePage.SYSTEM_ADMIN)
}

private fun homeQuickActionAllowed(
    action: HomeQuickAction,
    currentBook: LedgerBook,
    systemRole: String
): Boolean {
    fun allowed(permission: String): Boolean =
        BookPermissions.has(currentBook, systemRole, permission)

    return when (action) {
        HomeQuickAction.HISTORY -> allowed(BookPermissions.HISTORY_VIEW)
        HomeQuickAction.PURCHASE_ACTIVITY ->
            allowed(BookPermissions.PURCHASE_ACTIVITY_VIEW) ||
                allowed(BookPermissions.PURCHASE_PLAN_EDIT)
        HomeQuickAction.STATS,
        HomeQuickAction.OPERATING_ANALYSIS,
        HomeQuickAction.PERSONAL_SUMMARY -> allowed(BookPermissions.STATS_VIEW)
        HomeQuickAction.REPORT -> allowed(BookPermissions.REPORT_VIEW)
        HomeQuickAction.PROFIT -> allowed(BookPermissions.PROFIT_VIEW)
        HomeQuickAction.BACKUP ->
            currentBook.permission == "OWNER" || systemRole == "SUPERADMIN"
        HomeQuickAction.MEMBER_PERMISSIONS -> false
        HomeQuickAction.PARTNERS,
        HomeQuickAction.STORES,
        HomeQuickAction.FRUITS -> allowed(BookPermissions.BASIC_EDIT)
        HomeQuickAction.BOOKS ->
            currentBook.permission == "OWNER" || systemRole == "SUPERADMIN"
        HomeQuickAction.SYSTEM_ADMIN,
        HomeQuickAction.CLOUD_BOOKS -> systemRole == "SUPERADMIN"
        HomeQuickAction.SECURITY,
        HomeQuickAction.HOME_HEADER,
        HomeQuickAction.ABOUT -> true
    }
}

private enum class HistoryTimeFilter(val label: String) {
    ALL("全部时间"),
    TODAY("今天"),
    YESTERDAY("昨天"),
    LAST_7("近7天"),
    LAST_30("近30天"),
    LAST_90("近90天"),
    THIS_MONTH("本月"),
    LAST_MONTH("上月"),
    CUSTOM("自定义")
}

private enum class HistorySection(val label: String) {
    BUSINESS("营业历史"),
    PURCHASE("采购历史"),
    PRODUCT("商品历史"),
    PROFIT("利润历史"),
    PLAN("采购计划")
}

private enum class SettlementView(val label: String) {
    DAY("当日结算"),
    BATCH("资金余额"),
    STATS("结算记录")
}

private enum class ReportType(val label: String) {
    SETTLEMENT("资金结算报表"),
    BUSINESS("经营汇总报表")
}

private enum class ReportDetail(val label: String) {
    SIMPLE("简洁版"),
    DETAILED("详细版")
}

private enum class BusinessStatsTab(val label: String) {
    OVERVIEW("概览"),
    CALENDAR("日历"),
    TREND("趋势"),
    STORES("位置"),
    CUSTOMERS("客流")
}

private enum class BusinessTrendMetric(val label: String) {
    REVENUE("营业额"),
    PROFIT("利润"),
    CUSTOMERS("客户")
}

private enum class BusinessCalendarMetric(val label: String) {
    REVENUE("营业额"),
    PURCHASE("采购额"),
    PROFIT("利润")
}

private enum class PurchasePriceSource {
    TOTAL,
    UNIT
}

private data class ReceiptDraftRow(
    val rowId: Long,
    val partnerId: Long? = null,
    val partnerNameSnapshot: String = "",
    val wechat: String = "",
    val alipay: String = "",
    val cash: String = ""
) {
    val total: Double
        get() =
            (wechat.toDoubleOrNull() ?: 0.0) +
                (alipay.toDoubleOrNull() ?: 0.0) +
                (cash.toDoubleOrNull() ?: 0.0)

    val hasIncome: Boolean
        get() = total > 0.005
}

private data class PurchaseDraftRow(
    val rowId: Long,
    val planItemId: Long? = null,
    val fruitId: Long? = null,
    val fruitNameSnapshot: String = "",
    val unit: String = "件",
    val quantity: String = "",
    val unitWeight: String = "",
    val unitPrice: String = "",
    val totalCost: String = "",
    val buyerId: Long? = null,
    val buyerNameSnapshot: String = "",
    val priceSource: PurchasePriceSource =
        PurchasePriceSource.TOTAL
) {
    val isBlank: Boolean
        get() =
            fruitId == null &&
            quantity.isBlank() &&
            unitWeight.isBlank() &&
            unitPrice.isBlank() &&
            totalCost.isBlank()
}


private enum class PurchaseHistoryEditMode {
    ITEM,
    BUYER_DAY,
    DAY
}

private data class PurchaseHistoryEditTarget(
    val mode: PurchaseHistoryEditMode,
    val date: String,
    val details: List<PurchaseOrderDetail>,
    val focusItemId: Long? = null,
    val buyerName: String = ""
) {
    val stateKey: String
        get() =
            listOf(
                mode.name,
                date,
                focusItemId?.toString().orEmpty(),
                details.joinToString(",") {
                    it.order.id.toString()
                }
            ).joinToString("|")
}

private data class PurchaseHistoryEditDraft(
    val orderId: Long,
    val itemId: Long,
    val fruitId: Long,
    val fruitName: String,
    val unit: String,
    val quantity: String,
    val unitPrice: String,
    val totalCost: String,
    val unitWeightJin: String = "0"
)

private data class PageSyncUiContext(
    val db: AppDatabase,
    val cloudSyncManager: CloudSyncManager,
    val currentBook: LedgerBook,
    val refreshVersion: Int,
    val syncing: Boolean,
    val requestSync: () -> Unit
)

private val LocalPageSyncUiContext =
    compositionLocalOf<PageSyncUiContext?> { null }

private fun syncTablesForPageTitle(
    title: String
): Set<String> =
    when {
        title.contains("水果季节库") ->
            setOf("fruit_season_catalog", "fruit_alias", "fruit_season_region", "fruit_profile", "fruit")

        title.contains("库存") ->
            setOf("inventory_snapshot", "product_cost_reference", "daily_retail_price")

        title.contains("采购计划") ->
            setOf(
                "purchase_plan",
                "purchase_plan_item",
                "purchase_collaboration"
            )

        title.contains("采购") ->
            setOf(
                "purchase_order",
                "purchase_item",
                "purchase_activity",
                "purchase_collaboration"
            )

        title.contains("首页") || title.contains("经营建议") ->
            setOf("store_daily_record", "business_weather_history", "daily_business_score")

        title.contains("营业") ->
            setOf("store_daily_record", "business_weather_history", "daily_business_score")

        title.contains("经营分析") ->
            setOf(
                "store_daily_record",
                "purchase_order",
                "purchase_item",
                "inventory_snapshot",
                "product_cost_reference",
                "daily_retail_price",
                "business_weather_history",
                "daily_business_score"
            )

        title.contains("结算") ||
            title.contains("资金余额") ->
            setOf(
                "profit_distribution",
                "daily_cash_settlement",
                "settlement_partner",
                "settlement_transfer",
                "profit_settlement_batch",
                "profit_settlement_item"
            )

        title.contains("利润分配") ->
            setOf(
                "profit_rule",
                "profit_distribution"
            )

        title.contains("合伙人管理") ->
            setOf("partner")

        title.contains("位置管理") ||
            title.contains("摊位管理") ->
            setOf("store")

        title.contains("商品管理") ->
            setOf("fruit")

        else ->
            emptySet()
    }

private fun compactSyncTime(
    epochMillis: Long
): String =
    if (epochMillis <= 0L) {
        ""
    } else {
        Instant
            .ofEpochMilli(epochMillis)
            .atZone(ZoneId.systemDefault())
            .format(
                DateTimeFormatter.ofPattern(
                    "HH:mm"
                )
            )
    }

@Composable
private fun PageSyncStatus(
    pageTitle: String
) {
    val syncContext =
        LocalPageSyncUiContext.current
            ?: return

    val scopeTables =
        remember(pageTitle) {
            syncTablesForPageTitle(
                pageTitle
            )
        }

    val pendingCount =
        remember(
            syncContext.refreshVersion,
            scopeTables
        ) {
            syncContext.db
                .getPendingSyncChangeCount(
                    scopeTables
                )
        }

    val allPendingCount =
        remember(
            syncContext.refreshVersion
        ) {
            syncContext.db
                .getPendingSyncChangeCount()
        }

    val cloudStatus =
        remember(
            syncContext.refreshVersion
        ) {
            syncContext.db
                .getCloudSyncLocalStatus()
        }

    val tableError =
        remember(
            syncContext.refreshVersion,
            scopeTables
        ) {
            scopeTables
                .asSequence()
                .mapNotNull {
                    tableName ->
                    syncContext
                        .cloudSyncManager
                        .getTableSyncError(
                            syncContext
                                .currentBook.id,
                            tableName
                        )
                        .takeIf {
                            it.isNotBlank()
                        }
                }
                .firstOrNull()
                .orEmpty()
        }

    val effectiveError =
        if (tableError.isNotBlank()) {
            tableError
        } else if (scopeTables.isEmpty()) {
            cloudStatus.lastError
        } else {
            ""
        }

    val statusText =
        when {
            syncContext.syncing ->
                "↻ 同步中"

            syncContext.currentBook.permission ==
                "REVOKED" ->
                "! 无云权限"

            effectiveError.isNotBlank() ->
                "! 同步异常"

            pendingCount > 0 ->
                "↑ 待同步 $pendingCount"

            syncContext.currentBook.cloudEnabled -> {
                val time =
                    compactSyncTime(
                        cloudStatus.lastSyncAt
                    )
                if (time.isBlank()) {
                    "✓ 已同步"
                } else {
                    "✓ 已同步 $time"
                }
            }

            else ->
                "仅本机"
        }

    val statusColor =
        when {
            effectiveError.isNotBlank() ||
                syncContext.currentBook.permission ==
                    "REVOKED" ->
                MaterialTheme.colorScheme.error

            syncContext.syncing ->
                BrandGreen

            pendingCount > 0 ->
                Color(0xFFC37B00)

            syncContext.currentBook.cloudEnabled ->
                BrandGreen

            else ->
                Color.Gray
        }

    var showDetails by remember {
        mutableStateOf(false)
    }

    Surface(
        modifier =
            Modifier
                .clip(
                    RoundedCornerShape(14.dp)
                )
                .clickable {
                    showDetails = true
                },
        color =
            statusColor.copy(
                alpha = 0.09f
            )
    ) {
        Text(
            statusText,
            modifier =
                Modifier.padding(
                    horizontal = 9.dp,
                    vertical = 5.dp
                ),
            color = statusColor,
            style =
                MaterialTheme.typography
                    .labelSmall,
            fontWeight =
                FontWeight.SemiBold,
            maxLines = 1
        )
    }

    if (showDetails) {
        AlertDialog(
            onDismissRequest = {
                showDetails = false
            },
            title = {
                Text("同步详情")
            },
            text = {
                Column(
                    verticalArrangement =
                        Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        "当前页面：$pageTitle"
                    )
                    Text(
                        "最近同步：" +
                            formatDateTime(
                                cloudStatus.lastSyncAt
                            )
                    )
                    Text(
                        "当前页面待上传：" +
                            "$pendingCount 条"
                    )
                    if (
                        allPendingCount !=
                        pendingCount
                    ) {
                        Text(
                            "全账本待上传：" +
                                "$allPendingCount 条"
                        )
                    }
                    Text(
                        "同步序号：" +
                            cloudStatus.serverCursor
                    )
                    if (
                        effectiveError.isNotBlank()
                    ) {
                        Text(
                            "最近错误：" +
                                effectiveError,
                            color =
                                MaterialTheme
                                    .colorScheme
                                    .error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDetails = false
                        syncContext.requestSync()
                    },
                    enabled =
                        !syncContext.syncing &&
                            syncContext.currentBook
                                .permission !=
                                "REVOKED"
                ) {
                    Text("立即同步")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDetails = false
                    }
                ) {
                    Text("关闭")
                }
            }
        )
    }
}


@Composable
fun TianXianApp(
    db: AppDatabase,
    ledgerManager: LedgerManager,
    cloudSyncManager: CloudSyncManager,
    currentBook: LedgerBook,
    onSwitchBook: (String) -> Unit
) {
    var page by remember {
        mutableStateOf(
            AppPage.HOME
        )
    }
    var workDate by remember(currentBook.id) {
        mutableStateOf(LocalDate.now().toString())
    }
    var moreTarget by remember {
        mutableStateOf(
            MorePage.MENU
        )
    }
    var historyTarget by remember {
        mutableStateOf(
            HistorySection.BUSINESS
        )
    }
    var weatherDetailDate by remember(currentBook.id) {
        mutableStateOf(LocalDate.now().toString())
    }
    var weatherDetailStoreId by remember(currentBook.id) { mutableStateOf<Long?>(null) }
    var weatherDetailVisible by remember(currentBook.id) { mutableStateOf(false) }
    var weatherReturnPage by remember(currentBook.id) { mutableStateOf(AppPage.HOME) }
    var businessAdviceVisible by remember(currentBook.id) { mutableStateOf(false) }
    var businessAdviceDate by remember(currentBook.id) { mutableStateOf(LocalDate.now().toString()) }
    var businessAdviceStoreId by remember(currentBook.id) { mutableStateOf<Long?>(null) }
    var businessAdviceReturnPage by remember(currentBook.id) { mutableStateOf(AppPage.HOME) }
    var dataVersion by remember {
        mutableIntStateOf(0)
    }
    var syncRequested by remember {
        mutableStateOf(false)
    }

    val syncRefreshVersion =
        SyncUiRefreshBus.version.intValue

    LaunchedEffect(
        syncRefreshVersion
    ) {
        if (syncRefreshVersion > 0) {
            // 自动同步结束（成功或失败）后刷新状态与当前页面缓存。
            syncRequested = false
            // 不调用 notifyDataChanged()，避免再次调度云同步。
            dataVersion++
        }
    }

    var authVersion by remember {
        mutableIntStateOf(0)
    }

    val authSession =
        remember(authVersion) {
            cloudSyncManager.session()
        }

    if (authSession == null) {
        MaterialTheme(
            colorScheme =
                lightColorScheme(
                    primary = BrandGreen,
                    secondary = BrandGreen
                )
        ) {
            CloudLoginGate(
                cloudSyncManager =
                    cloudSyncManager,
                onLoggedIn = {
                    authVersion++
                }
            )
        }
        return
    }

    LaunchedEffect(
        authSession.username,
        currentBook.id
    ) {
        // MainActivity.onResume 会负责启动/回前台同步；
        // 这里再调度一次可覆盖“登录完成时 Activity 已处于 RESUMED”
        // 的场景，CloudSyncManager 会自动合并重复请求。
        cloudSyncManager
            .scheduleAutoSync(
                currentBook
            )
    }

    val context =
        LocalContext.current

    val operationSecurityManager =
        remember {
            OperationSecurityManager(
                context.applicationContext
            )
        }

    val historySecurityGate =
        remember {
            HistorySecurityGate(
                operationSecurityManager
            )
        }

    val protectHistoricalAction:
        (String, String, () -> Unit) -> Unit =
        { recordDate, description, action ->
            historySecurityGate.run(
                recordDate,
                description,
                action
            )
        }

    val ledgerUiSettingsManager =
        remember {
            LedgerUiSettingsManager(
                context.applicationContext
            )
        }

    var uiSettingsVersion by remember {
        mutableIntStateOf(0)
    }

    val updateManager =
        remember {
            AppUpdateManager(
                context.applicationContext
            )
        }

    var updateInfo by remember {
        mutableStateOf<
            AppUpdateInfo?
        >(null)
    }

    var updateChecking by remember {
        mutableStateOf(false)
    }

    var updateCheckMessage by remember {
        mutableStateOf("")
    }

    fun checkAppUpdate(
        manual: Boolean
    ) {
        if (updateChecking) {
            if (manual) {
                updateCheckMessage =
                    "正在检查更新，请稍候"
            }
            return
        }

        updateChecking = true

        val callback:
            (
                Result<
                    AppUpdateCheckResult
                >
            ) -> Unit =
            {
                result ->
                updateChecking =
                    false

                result
                    .onSuccess {
                        checked ->
                        updateCheckMessage =
                            checked.message

                        if (
                            checked.update !=
                            null
                        ) {
                            updateInfo =
                                checked.update
                        }
                    }
                    .onFailure {
                        error ->
                        if (manual) {
                            updateCheckMessage =
                                "检查更新失败：" +
                                    (
                                        error.message
                                            ?: "未知错误"
                                        )
                        }
                    }
            }

        val started =
            if (manual) {
                updateManager
                    .checkNow(
                        callback
                    )
            } else {
                updateManager
                    .checkIfDue(
                        callback
                    )
            }

        if (!started) {
            updateChecking =
                false
        }
    }

    LaunchedEffect(Unit) {
        checkAppUpdate(
            manual = false
        )
    }

    val liveCurrentBook =
        ledgerManager
            .getBook(
                currentBook.id
            )
            ?: currentBook

    fun hasPermission(
        permission: String
    ): Boolean =
        BookPermissions.has(
            book = liveCurrentBook,
            systemRole =
                authSession.systemRole,
            permission = permission
        )

    val canPurchaseEdit =
        hasPermission(
            BookPermissions.PURCHASE_CREATE
        ) ||
        hasPermission(
            BookPermissions.PURCHASE_EDIT
        )

    val canBusinessEdit =
        hasPermission(
            BookPermissions.BUSINESS_EDIT
        )

    val canSettlementEdit =
        hasPermission(
            BookPermissions.SETTLEMENT_EDIT
        )

    val canEdit =
        BookPermissions.canModifyAnything(
            liveCurrentBook,
            authSession.systemRole
        )

    fun notifyDataChanged() {
        dataVersion++

        if (
            cloudSyncManager
                .session() != null
        ) {
            cloudSyncManager
                .scheduleAutoSync(
                    liveCurrentBook
                )
        }
    }

    BackHandler(enabled = weatherDetailVisible || businessAdviceVisible || page != AppPage.HOME) {
        when {
            weatherDetailVisible -> {
                weatherDetailVisible = false
                page = weatherReturnPage
            }
            businessAdviceVisible -> {
                businessAdviceVisible = false
                page = businessAdviceReturnPage
            }
            else -> page = AppPage.HOME
        }
    }

    MaterialTheme(colorScheme = lightColorScheme(primary = BrandGreen, secondary = BrandGreen)) {
        val pageSyncContext =
            PageSyncUiContext(
                db = db,
                cloudSyncManager =
                    cloudSyncManager,
                currentBook =
                    liveCurrentBook,
                refreshVersion =
                    dataVersion +
                        syncRefreshVersion,
                syncing =
                    syncRequested ||
                        cloudSyncManager
                            .isSyncRunning(),
                requestSync = {
                    syncRequested = true
                    cloudSyncManager
                        .scheduleAutoSync(
                            liveCurrentBook
                        )
                }
            )

        CompositionLocalProvider(
            LocalPageSyncUiContext provides
                pageSyncContext
        ) {
            Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            bottomBar = {
                if (!weatherDetailVisible && !businessAdviceVisible) NavigationBar {
                    AppPage.entries
                        .filter {
                            item ->
                            when (item) {
                                AppPage.HOME,
                                AppPage.MORE ->
                                    true
                                AppPage.PURCHASE ->
                                    canPurchaseEdit
                                AppPage.SESSION ->
                                    canBusinessEdit
                                AppPage.SETTLEMENT ->
                                    canSettlementEdit
                                AppPage.INVENTORY ->
                                    true
                                AppPage.PLAN ->
                                    false
                            }
                        }
                        .forEach { item ->
                        NavigationBarItem(
                            selected = page == item,
                            enabled = true,
                            onClick = {
                                if (
                                    item ==
                                    AppPage.MORE
                                ) {
                                    moreTarget =
                                        MorePage.MENU
                                }
                                page = item
                            },
                            icon = {
                                Text(item.emoji)
                            },
                            label = {
                                Text(item.title)
                            }
                        )
                    }
                }
            }
        ) { padding ->
            Box(Modifier.padding(padding)) {
                if (weatherDetailVisible) {
                    SubPage(
                        title = "经营天气",
                        back = {
                            weatherDetailVisible = false
                            page = weatherReturnPage
                        }
                    ) {
                        WeatherDetailContent(
                            db = db,
                            dataVersion = dataVersion,
                            currentBook = liveCurrentBook,
                            cloudSyncManager = cloudSyncManager,
                            initialDate = weatherDetailDate,
                            initialStoreId = weatherDetailStoreId
                        )
                    }
                } else if (businessAdviceVisible) {
                    SubPage(
                        title = "经营建议详情",
                        back = {
                            businessAdviceVisible = false
                            page = businessAdviceReturnPage
                        }
                    ) {
                        BusinessAdviceDetailContent(
                            db = db,
                            dataVersion = dataVersion,
                            currentBook = liveCurrentBook,
                            cloudSyncManager = cloudSyncManager,
                            initialDate = businessAdviceDate,
                            initialStoreId = businessAdviceStoreId,
                            onChanged = { notifyDataChanged() }
                        )
                    }
                } else when (page) {
                    AppPage.HOME -> HomeScreen(
                        db = db,
                        dataVersion = dataVersion,
                        ledgerManager =
                            ledgerManager,
                        currentBook =
                            liveCurrentBook,
                        cloudSyncManager =
                            cloudSyncManager,
                        books =
                            ledgerManager.books().filter { it.permission != "REVOKED" },
                        ledgerUiSettingsManager =
                            ledgerUiSettingsManager,
                        uiSettingsVersion =
                            uiSettingsVersion,
                        systemRole =
                            authSession.systemRole,
                        onSwitchBook =
                            onSwitchBook,
                        onBookManage = {
                            moreTarget =
                                MorePage.BOOKS
                            page =
                                AppPage.MORE
                        },
                        onHeaderSettings = {
                            moreTarget =
                                MorePage.HOME_HEADER
                            page =
                                AppPage.MORE
                        },
                        onPurchase = {
                            if (canPurchaseEdit) {
                                page =
                                    AppPage.PURCHASE
                            }
                        },
                        onPlan = {
                            if (
                                hasPermission(
                                    BookPermissions.PURCHASE_PLAN_EDIT
                                )
                            ) {
                                page =
                                    AppPage.PLAN
                            }
                        },
                        onSession = {
                            if (canBusinessEdit) {
                                page =
                                    AppPage.SESSION
                            }
                        },
                        onStores = {
                            if (
                                hasPermission(
                                    BookPermissions.BASIC_EDIT
                                )
                            ) {
                                moreTarget =
                                    MorePage.STORES
                                page =
                                    AppPage.MORE
                            }
                        },
                        onHistory = {
                            historyTarget =
                                HistorySection.BUSINESS
                            moreTarget =
                                MorePage.HISTORY
                            page =
                                AppPage.MORE
                        },
                        onPurchaseActivity = {
                            moreTarget =
                                MorePage
                                    .PURCHASE_ACTIVITY
                            page =
                                AppPage.MORE
                        },
                        onStats = {
                            moreTarget =
                                MorePage.STATS
                            page =
                                AppPage.MORE
                        },
                        onProfit = {
                            if (
                                hasPermission(
                                    BookPermissions.PROFIT_VIEW
                                )
                            ) {
                                moreTarget =
                                    MorePage.PROFIT
                                page =
                                    AppPage.MORE
                            }
                        },
                        onReport = {
                            moreTarget =
                                MorePage.REPORT
                            page =
                                AppPage.MORE
                        },
                        onFruits = {
                            moreTarget =
                                MorePage.FRUITS
                            page =
                                AppPage.MORE
                        },
                        onInventory = {
                            page = AppPage.INVENTORY
                        },
                        onOpenWeather = { date, storeId ->
                            weatherDetailDate = date
                            weatherDetailStoreId = storeId
                            weatherReturnPage = AppPage.HOME
                            weatherDetailVisible = true
                        },
                        onOpenBusinessAdvice = { date, storeId ->
                            businessAdviceDate = date
                            businessAdviceStoreId = storeId
                            businessAdviceReturnPage = AppPage.HOME
                            businessAdviceVisible = true
                        },
                        onMore = {
                            moreTarget =
                                MorePage.MENU
                            page =
                                AppPage.MORE
                        },
                        onOpenMore = { target ->
                            moreTarget = target
                            page = AppPage.MORE
                        }
                    )
                    AppPage.INVENTORY ->
                        InventoryScreen(
                            db = db,
                            dataVersion = dataVersion,
                            workDate = workDate,
                            onWorkDateChange = { workDate = it },
                            onChanged = { notifyDataChanged() }
                        )
                    AppPage.PURCHASE ->
                        PurchaseScreen(
                            db = db,
                            dataVersion =
                                dataVersion,
                            workDate = workDate,
                            onWorkDateChange = { workDate = it },
                            onChanged = {
                                notifyDataChanged()
                            },
                            protectHistoricalAction =
                                protectHistoricalAction,
                            onOpenHistory = {
                                historyTarget =
                                    HistorySection.PURCHASE
                                moreTarget =
                                    MorePage.HISTORY
                                page =
                                    AppPage.MORE
                            }
                        )
                    AppPage.PLAN ->
                        CollaborativePurchaseContent(
                            db = db,
                            dataVersion =
                                dataVersion,
                            currentBook =
                                liveCurrentBook,
                            cloudSyncManager =
                                cloudSyncManager,
                            onChanged = {
                                notifyDataChanged()
                            }
                        )
                    AppPage.SESSION ->
                        SessionScreen(
                            db = db,
                            dataVersion = dataVersion,
                            cloudSyncManager = cloudSyncManager,
                            currentBook = liveCurrentBook,
                            workDate = workDate,
                            onWorkDateChange = { workDate = it },
                            onChanged = { notifyDataChanged() },
                            protectHistoricalAction =
                                protectHistoricalAction,
                            onOpenHistory = {
                                historyTarget =
                                    HistorySection.BUSINESS
                                moreTarget =
                                    MorePage.HISTORY
                                page =
                                    AppPage.MORE
                            },
                            onOpenWeather = { date, storeId ->
                                weatherDetailDate = date
                                weatherDetailStoreId = storeId
                                weatherReturnPage = AppPage.SESSION
                                weatherDetailVisible = true
                            }
                        )
                    AppPage.SETTLEMENT ->
                        SettlementScreen(
                            db = db,
                            dataVersion = dataVersion,
                            workDate = workDate,
                            onWorkDateChange = { workDate = it },
                            protectHistoricalAction = protectHistoricalAction,
                            onChanged = { notifyDataChanged() }
                        )
                    AppPage.MORE -> MoreScreen(
                        db = db,
                        dataVersion = dataVersion,
                        initialSub = moreTarget,
                        initialHistorySection = historyTarget,
                        ledgerManager =
                            ledgerManager,
                        cloudSyncManager =
                            cloudSyncManager,
                        currentBook =
                            liveCurrentBook,
                        systemRole =
                            authSession.systemRole,
                        canEdit =
                            canEdit,
                        operationSecurityManager =
                            operationSecurityManager,
                        protectHistoricalAction =
                            protectHistoricalAction,
                        onSwitchBook =
                            onSwitchBook,
                        onPlan = {
                            page = AppPage.PLAN
                        },
                        onOpenWeather = { date, storeId ->
                            weatherDetailDate = date
                            weatherDetailStoreId = storeId
                            weatherReturnPage = AppPage.MORE
                            weatherDetailVisible = true
                        },
                        updateChecking =
                            updateChecking,
                        updateCheckMessage =
                            updateCheckMessage,
                        onCheckUpdate = {
                            checkAppUpdate(
                                manual = true
                            )
                        },
                        ledgerUiSettingsManager =
                            ledgerUiSettingsManager,
                        uiSettingsVersion =
                            uiSettingsVersion,
                        onUiSettingsChanged = {
                            uiSettingsVersion++
                        },
                        onLogout = {
                            cloudSyncManager.logout()
                            authVersion++
                            page = AppPage.HOME
                        },
                        onChanged = {
                            notifyDataChanged()
                        }
                    )
                }
            }
        }
        }
    }

    HistorySecurityHost(
        historySecurityGate
    )

    updateInfo?.let {
        info ->
        AppUpdateDialog(
            info = info,
            onDismiss = {
                updateInfo = null
            },
            onOpenRelease = {
                updateInfo = null
                openWebPage(
                    context,
                    info.releaseUrl
                )
            }
        )
    }
}

@Composable
private fun PageHeader(
    title: String,
    subtitle: String? = null
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(
                top = 8.dp,
                bottom = 8.dp
            ),
        verticalAlignment =
            Alignment.CenterVertically,
        horizontalArrangement =
            Arrangement.spacedBy(8.dp)
    ) {
        Text(
            title,
            modifier =
                Modifier.weight(1f),
            style =
                MaterialTheme
                    .typography
                    .headlineSmall,
            fontWeight =
                FontWeight.Bold
        )

        PageSyncStatus(
            pageTitle = title
        )
    }
}

@Composable
private fun BusinessDateHeader(
    pageTitle: String,
    date: String,
    onDate: (String) -> Unit
) {
    val parsedDate =
        runCatching { LocalDate.parse(date) }
            .getOrElse { LocalDate.now() }
    var showPicker by remember { mutableStateOf(false) }
    val displayText =
        parsedDate.format(
            DateTimeFormatter.ofPattern("yyyy年M月d日")
        ) + "  " + chineseWeekday(parsedDate)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        TextButton(
            onClick = { showPicker = true },
            modifier = Modifier
                .weight(1f)
                .height(44.dp)
                .pointerInput(parsedDate) {
                    var totalDrag = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { totalDrag = 0f },
                        onHorizontalDrag = { _, dragAmount -> totalDrag += dragAmount },
                        onDragEnd = {
                            when {
                                totalDrag <= -70f -> onDate(parsedDate.plusDays(1).toString())
                                totalDrag >= 70f -> onDate(parsedDate.minusDays(1).toString())
                            }
                        },
                        onDragCancel = { totalDrag = 0f }
                    )
                },
            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp)
        ) {
            Text(
                displayText,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
        PageSyncStatus(pageTitle = pageTitle)
    }

    if (showPicker) {
        QuickDatePickerDialog(
            selectedDate = parsedDate,
            onDismiss = { showPicker = false },
            onSelect = { selected ->
                showPicker = false
                onDate(selected.toString())
            }
        )
    }
}

@Composable
private fun MetricCard(title: String, value: String, modifier: Modifier = Modifier, color: Color = SoftGreen, sub: String? = null) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = color)) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = Color.DarkGray)
        }
    }
}


private fun weatherEmoji(code: String, text: String): String {
    val t = text.lowercase(Locale.CHINA)
    val c = code.toIntOrNull() ?: -1
    return when {
        "雷" in t -> "⛈️"
        "暴雨" in t -> "🌧️"
        "雨" in t -> "🌦️"
        "雪" in t -> "🌨️"
        "雾" in t || "霾" in t -> "🌫️"
        "阴" in t -> "☁️"
        "云" in t -> "⛅"
        "晴" in t -> "☀️"
        c in 300..399 -> "🌧️"
        c in 400..499 -> "🌨️"
        c in 500..515 -> "🌫️"
        else -> "🌤️"
    }
}

private fun weatherTemp(value: Double?): String =
    value?.let { "${String.format(Locale.CHINA, "%.0f", it)}°" } ?: "—"

private fun weatherAmount(value: Double?): String =
    value?.let { String.format(Locale.CHINA, "%.1fmm", it) } ?: "—"

private fun weatherPercent(value: Double?): String =
    value?.let { "${String.format(Locale.CHINA, "%.0f", it)}%" } ?: "—"

private fun weatherHourLabel(value: String): String {
    val raw = value.substringAfter('T', value)
    return raw.take(5).ifBlank { value.takeLast(5) }
}

private fun weatherDateLabel(date: LocalDate): String {
    val today = LocalDate.now()
    val prefix = when (date) {
        today -> "今天"
        today.plusDays(1) -> "明天"
        today.minusDays(1) -> "昨天"
        else -> date.format(DateTimeFormatter.ofPattern("M月d日"))
    }
    return "$prefix · ${date.format(DateTimeFormatter.ofPattern("M月d日"))} ${chineseWeekday(date)}"
}

private fun weatherLocationSourceText(source: String, confidence: Double, sampleCount: Int): String =
    when (source) {
        "ACTUAL_RECORD" -> "当天营业位置"
        "WEEKDAY_FIXED" -> "星期固定位置"
        "WEEKDAY_HISTORY" -> {
            val pct = (confidence * 100).toInt().coerceIn(0, 100)
            "根据同星期历史安排 · $pct% · ${sampleCount}次"
        }
        "RECENT_BUSINESS" -> "最近营业位置"
        else -> "默认经营位置"
    }

private fun weatherBookId(book: LedgerBook): String =
    book.cloudBookId.ifBlank { book.id.takeIf { it.contains('-') }.orEmpty() }

private const val WEATHER_CACHE_TODAY_MS = 10 * 60 * 1000L
private const val WEATHER_CACHE_FUTURE_MS = 30 * 60 * 1000L
private const val WEATHER_CACHE_ARCHIVE_MS = 24 * 60 * 60 * 1000L

private fun weatherCacheTtl(date: LocalDate): Long =
    if (date == LocalDate.now()) WEATHER_CACHE_TODAY_MS else WEATHER_CACHE_FUTURE_MS

private fun storeTimeMinutes(value: String, fallback: Int): Int {
    if (value == "24:00") return 24 * 60
    val p = value.split(':')
    if (p.size != 2) return fallback
    val h = p[0].toIntOrNull() ?: return fallback
    val m = p[1].toIntOrNull() ?: return fallback
    if (h !in 0..23 || m !in 0..59) return fallback
    return h * 60 + m
}

private fun storeBusinessHours(
    hours: List<WeatherHour>,
    date: String,
    store: StoreOption?
): List<WeatherHour> {
    val start = storeTimeMinutes(store?.defaultStartTime ?: "16:00", 16 * 60)
    val end = storeTimeMinutes(store?.defaultEndTime ?: "24:00", 24 * 60)
    return hours.filter { h ->
        if (!h.time.startsWith(date)) return@filter false
        val hour = h.time.substringAfter('T', "").take(2).toIntOrNull() ?: return@filter false
        val bucketStart = hour * 60
        val bucketEnd = bucketStart + 60
        bucketEnd > start && bucketStart < end
    }
}

private fun businessTimeLabel(store: StoreOption?): String =
    "${store?.defaultStartTime ?: "16:00"}–${store?.defaultEndTime ?: "24:00"}"

private fun windSpeedText(value: Double?): String =
    value?.let { String.format(Locale.CHINA, "%.0fkm/h", it) } ?: "—"

private fun weatherUpdatedText(timeMillis: Long): String {
    if (timeMillis <= 0L) return ""
    return Instant.ofEpochMilli(timeMillis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("HH:mm"))
}

private data class WeatherUiState(
    val overview: WeatherOverview? = null,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val error: String = "",
    val snapshotType: String = "",
    val updatedAtMillis: Long = 0L
)

private fun weatherAlertLevel(alert: WeatherAlert): Int {
    val text = (alert.severity + " " + alert.title + " " + alert.type).lowercase(Locale.ROOT)
    return when {
        text.contains("红") || text.contains("red") || text.contains("extreme") -> 4
        text.contains("橙") || text.contains("orange") || text.contains("severe") -> 3
        text.contains("黄") || text.contains("yellow") || text.contains("moderate") -> 2
        text.contains("蓝") || text.contains("blue") || text.contains("minor") -> 1
        else -> 0
    }
}

private fun weatherAlertHomeColor(level: Int): Color =
    when {
        level >= 4 -> Color(0xFFFFE7E7)
        level == 3 -> Color(0xFFFFF0DC)
        else -> Color(0xFFFFF8D8)
    }

@Composable
private fun HomeWeatherCard(
    db: AppDatabase,
    dataVersion: Int,
    currentBook: LedgerBook,
    cloudSyncManager: CloudSyncManager,
    onOpenDetail: (String, Long?) -> Unit,
    onStoreResolved: (Long?) -> Unit = {}
) {
    val context = LocalContext.current
    val plannedPrefs = remember(currentBook.id) {
        context.getSharedPreferences("tianxian_weather_plan_${currentBook.id}", Context.MODE_PRIVATE)
    }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    var temporaryStoreId by remember(selectedDate) { mutableStateOf<Long?>(null) }
    var storeMenu by remember { mutableStateOf(false) }

    val stores = remember(dataVersion) {
        db.getStores()
    }
    val dateString = selectedDate.toString()
    val actualStores = remember(dataVersion, dateString) {
        db.getBusinessStoresForDate(dateString)
    }
    val resolution = remember(dataVersion, selectedDate) {
        db.resolveWeatherStore(dateString)
    }
    val planKey = "manual_store_$dateString"
    val persistedManualStoreId = remember(dataVersion, dateString, actualStores.size) {
        if (selectedDate == LocalDate.now() && actualStores.isEmpty()) {
            plannedPrefs.getLong(planKey, -1L).takeIf { it > 0L }
        } else null
    }
    val selectedStore = when {
        actualStores.isNotEmpty() ->
            stores.firstOrNull { it.id == temporaryStoreId }
                ?: actualStores.firstOrNull()
        temporaryStoreId != null -> stores.firstOrNull { it.id == temporaryStoreId }
        persistedManualStoreId != null -> stores.firstOrNull { it.id == persistedManualStoreId }
        else -> resolution.store ?: stores.firstOrNull()
    }
    val manuallyPlanned = actualStores.isEmpty() &&
        (temporaryStoreId != null || persistedManualStoreId != null)

    LaunchedEffect(selectedStore?.id) {
        onStoreResolved(selectedStore?.id)
    }

    var state by remember(selectedDate, selectedStore?.id) {
        mutableStateOf(WeatherUiState(loading = true))
    }

    LaunchedEffect(
        selectedDate,
        selectedStore?.id,
        selectedStore?.latitude,
        selectedStore?.longitude,
        currentBook.cloudBookId
    ) {
        val store = selectedStore
        if (store == null) {
            state = WeatherUiState(error = "还没有经营位置")
            return@LaunchedEffect
        }
        val past = selectedDate.isBefore(LocalDate.now())
        if (past) {
            val archived = withContext(Dispatchers.IO) {
                runCatching {
                    val bookId = weatherBookId(currentBook)
                    if (bookId.isBlank()) throw IllegalStateException("当前账本尚未连接云端天气服务")
                    WeatherClient(cloudSyncManager).fetchArchive(bookId, store, selectedDate)
                }
            }
            state = archived.fold(
                onSuccess = { overview ->
                    withContext(Dispatchers.IO) {
                        db.saveWeatherCache(
                            dateString, store.id, overview.rawJson, WEATHER_CACHE_ARCHIVE_MS
                        )
                    }
                    WeatherUiState(
                        overview = overview,
                        snapshotType = "SERVER_ARCHIVE",
                        updatedAtMillis = System.currentTimeMillis()
                    )
                },
                onFailure = { error ->
                    val cached = withContext(Dispatchers.IO) { db.getWeatherCache(dateString, store.id) }
                    val parsed = cached?.let {
                        runCatching { WeatherClient.parseOverview(it.payloadJson, historical = true) }.getOrNull()
                    }
                    if (parsed != null) WeatherUiState(
                        overview = parsed,
                        snapshotType = "ARCHIVE_CACHE",
                        updatedAtMillis = cached.fetchedAt
                    ) else WeatherUiState(error = error.message ?: "该日期暂无服务器天气档案")
                }
            )
            return@LaunchedEffect
        }

        if (store.latitude == null || store.longitude == null) {
            state = WeatherUiState(error = "${store.name} 尚未绑定天气经纬度")
            return@LaunchedEffect
        }

        val now = System.currentTimeMillis()
        val cached = withContext(Dispatchers.IO) {
            db.getWeatherCache(dateString, store.id)
        }
        var hasUsableCache = false
        if (cached != null) {
            val cachedOverview = runCatching {
                WeatherClient.parseOverview(cached.payloadJson, historical = false)
            }.getOrNull()
            if (cachedOverview != null) {
                hasUsableCache = true
                val stale = cached.expiresAt <= now
                state = WeatherUiState(
                    overview = cachedOverview,
                    loading = false,
                    refreshing = stale,
                    snapshotType = "CACHE",
                    updatedAtMillis = cached.fetchedAt
                )
                if (!stale) return@LaunchedEffect
            }
        }

        if (!hasUsableCache) state = WeatherUiState(loading = true)
        val refreshed = withContext(Dispatchers.IO) {
            runCatching {
                val bookId = weatherBookId(currentBook)
                if (bookId.isBlank()) throw IllegalStateException("当前账本尚未连接云端天气服务")
                val overview = WeatherClient(cloudSyncManager)
                    .fetchOverview(bookId, store, selectedDate, 120)
                val fetchedAt = System.currentTimeMillis()
                db.saveWeatherCache(
                    dateString,
                    store.id,
                    overview.rawJson,
                    weatherCacheTtl(selectedDate),
                    fetchedAt
                )
                WeatherUiState(
                    overview = overview,
                    snapshotType = "CACHE",
                    updatedAtMillis = fetchedAt
                )
            }
        }
        state = refreshed.getOrElse { error ->
            if (hasUsableCache) state.copy(refreshing = false)
            else WeatherUiState(error = error.message ?: "天气加载失败")
        }
    }

    val overview = state.overview
    val current = overview?.current
    val day = overview?.daily?.firstOrNull { it.date == dateString } ?: overview?.selectedDay()
    val businessHours = storeBusinessHours(overview?.hourly.orEmpty(), dateString, selectedStore)
    val maxPop = businessHours.mapNotNull { it.precipitationProbability }.maxOrNull()
    val rainAmount = businessHours.sumOf { it.precipitation ?: 0.0 }
    val headline = current?.text?.takeIf { selectedDate == LocalDate.now() }
        ?: day?.textDay.orEmpty().ifBlank { businessHours.firstOrNull()?.text.orEmpty() }
    val temp = current?.temperature?.takeIf { selectedDate == LocalDate.now() }
        ?: businessHours.firstOrNull()?.temperature
        ?: day?.tempMax
    val homeAlerts = remember(overview?.rawJson) {
        overview?.alerts.orEmpty()
            .filter { weatherAlertLevel(it) >= 2 }
            .sortedByDescending(::weatherAlertLevel)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .pointerInput(selectedDate) {
                var drag = 0f
                detectHorizontalDragGestures(
                    onDragStart = { drag = 0f },
                    onHorizontalDrag = { _, amount -> drag += amount },
                    onDragEnd = {
                        when {
                            drag <= -70f -> selectedDate = selectedDate.plusDays(1)
                            drag >= 70f -> selectedDate = selectedDate.minusDays(1)
                        }
                    }
                )
            },
        colors = CardDefaults.cardColors(containerColor = Color(0xFFEAF5FF)),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable { onOpenDetail(dateString, selectedStore?.id) }
                .padding(horizontal = 15.dp, vertical = 13.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("经营天气", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.width(8.dp))
                Box {
                    TextButton(onClick = { if (stores.isNotEmpty()) storeMenu = true }) {
                        Text("${selectedStore?.name ?: "选择位置"} ▾", fontWeight = FontWeight.SemiBold)
                    }
                    DropdownMenu(expanded = storeMenu, onDismissRequest = { storeMenu = false }) {
                        stores.forEach { store ->
                            val actual = actualStores.any { it.id == store.id }
                            DropdownMenuItem(
                                text = {
                                    Text((if (actual) "✓ " else "") + store.name)
                                },
                                onClick = {
                                    temporaryStoreId = store.id
                                    if (selectedDate == LocalDate.now() && actualStores.isEmpty()) {
                                        plannedPrefs.edit().putLong(planKey, store.id).apply()
                                    }
                                    storeMenu = false
                                }
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(weatherDateLabel(selectedDate), style = MaterialTheme.typography.labelMedium, color = Color.DarkGray)
            }

            when {
                state.loading -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("正在更新天气…", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
                state.error.isNotBlank() -> {
                    Text(state.error, color = Color(0xFFB26A00), style = MaterialTheme.typography.bodyMedium)
                    if (selectedStore == null) {
                        Text("请在 更多 → 位置管理 中为摆摊位置填写经纬度", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                }
                overview != null -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(weatherEmoji(current?.code ?: day?.codeDay.orEmpty(), headline), fontSize = 38.sp)
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text(headline.ifBlank { "天气" }, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                            Text(
                                "${businessTimeLabel(selectedStore)} · 降雨概率 ${weatherPercent(maxPop ?: day?.precipitationProbability)} · ${weatherAmount(rainAmount)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.DarkGray
                            )
                        }
                        Text(weatherTemp(temp), fontSize = 32.sp, fontWeight = FontWeight.Bold)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val sourceText = when {
                            actualStores.isNotEmpty() && actualStores.any { it.id == selectedStore?.id } -> "当天实际营业位置"
                            actualStores.isNotEmpty() -> "临时查看位置"
                            manuallyPlanned && selectedDate == LocalDate.now() -> "当天手动计划位置"
                            else -> weatherLocationSourceText(resolution.source, resolution.confidence, resolution.sampleCount)
                        }
                        val updateText = weatherUpdatedText(state.updatedAtMillis)
                        Text(
                            buildString {
                                append(sourceText)
                                if (updateText.isNotBlank()) append(" · 更新于 $updateText")
                                if (state.refreshing) append(" · 后台更新中")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray,
                            modifier = Modifier.weight(1f)
                        )
                        Text("24小时详情 ›", style = MaterialTheme.typography.labelSmall, color = BrandGreen)
                    }
                    if (actualStores.size > 1) {
                        Text(
                            "今日已营业 ${actualStores.size} 个位置，可在上方切换查看各自天气",
                            style = MaterialTheme.typography.labelSmall,
                            color = BrandGreen
                        )
                    }
                    if (selectedDate == LocalDate.now() && homeAlerts.isNotEmpty()) {
                        val alert = homeAlerts.first()
                        val level = weatherAlertLevel(alert)
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            color = weatherAlertHomeColor(level)
                        ) {
                            Row(
                                Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "⚠ ${alert.title.ifBlank { "天气黄色及以上预警" }}",
                                    modifier = Modifier.weight(1f),
                                    fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (homeAlerts.size > 1) {
                                    Text(
                                        "另有${homeAlerts.size - 1}条 ›",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.DarkGray
                                    )
                                } else {
                                    Text("详情 ›", style = MaterialTheme.typography.labelSmall, color = Color.DarkGray)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class BusinessAdviceUiState(
    val score: BusinessScoreRecord? = null,
    val loading: Boolean = false,
    val error: String = ""
)

private fun businessConfidenceLabel(value: String): String =
    when (value.uppercase(Locale.ROOT)) {
        "HIGH" -> "较高"
        "MEDIUM" -> "中"
        else -> "低"
    }

private fun loadAndSaveBusinessScore(
    db: AppDatabase,
    currentBook: LedgerBook,
    cloudSyncManager: CloudSyncManager,
    date: LocalDate,
    store: StoreOption
): BusinessScoreRecord? {
    val dateString = date.toString()
    if (date.isBefore(LocalDate.now())) {
        // 历史日只展示当时真正保存下来的评分，不用事后真实天气伪造“当日预测”。
        return db.getBusinessScore(dateString, store.id)
    }

    var overview: WeatherOverview? = null
    val cached = db.getWeatherCache(dateString, store.id)
    if (cached != null) {
        overview = runCatching {
            WeatherClient.parseOverview(cached.payloadJson, historical = false)
        }.getOrNull()
    }

    val cacheStale = cached == null || cached.expiresAt <= System.currentTimeMillis()
    if (
        cacheStale &&
        store.latitude != null &&
        store.longitude != null
    ) {
        val bookId = weatherBookId(currentBook)
        if (bookId.isNotBlank()) {
            runCatching {
                WeatherClient(cloudSyncManager).fetchOverview(bookId, store, date, 120)
            }.onSuccess { fresh ->
                overview = fresh
                db.saveWeatherCache(
                    dateString,
                    store.id,
                    fresh.rawJson,
                    weatherCacheTtl(date),
                    System.currentTimeMillis()
                )
            }
        }
    }

    val calculated = BusinessScoreEngine(db).calculate(date, store, overview)
    return db.saveBusinessScore(calculated) ?: calculated
}

@Composable
private fun HomeBusinessAdviceCard(
    db: AppDatabase,
    dataVersion: Int,
    currentBook: LedgerBook,
    cloudSyncManager: CloudSyncManager,
    storeId: Long?,
    onOpenDetail: (String, Long) -> Unit
) {
    val today = LocalDate.now()
    val dateString = today.toString()
    var state by remember(currentBook.id, storeId) {
        mutableStateOf(BusinessAdviceUiState(loading = true))
    }
    var refreshTick by remember(currentBook.id, storeId) { mutableStateOf(0) }

    // 首页停留时每 30 分钟重新评估一次；DB 层会对无变化结果去重。
    LaunchedEffect(currentBook.id, storeId, dateString) {
        while (true) {
            delay(30L * 60L * 1000L)
            refreshTick += 1
        }
    }

    LaunchedEffect(dataVersion, currentBook.id, currentBook.cloudBookId, storeId, dateString, refreshTick) {
        val store = storeId?.let { db.getStoreById(it) }
        if (store == null) {
            state = BusinessAdviceUiState(error = "还没有可分析的经营位置")
            return@LaunchedEffect
        }
        state = BusinessAdviceUiState(
            score = db.getBusinessScore(dateString, store.id),
            loading = true
        )
        val result = withContext(Dispatchers.IO) {
            runCatching {
                loadAndSaveBusinessScore(
                    db = db,
                    currentBook = currentBook,
                    cloudSyncManager = cloudSyncManager,
                    date = today,
                    store = store
                )
            }
        }
        state = result.fold(
            onSuccess = { score ->
                if (score != null) {
                    cloudSyncManager.scheduleAutoSync(currentBook)
                    BusinessAdviceUiState(score = score)
                } else {
                    BusinessAdviceUiState(error = "经营建议暂未生成")
                }
            },
            onFailure = { error ->
                val cached = db.getBusinessScore(dateString, store.id)
                if (cached != null) BusinessAdviceUiState(score = cached)
                else BusinessAdviceUiState(error = error.message ?: "经营建议生成失败")
            }
        )
    }

    val openDetail = state.score
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 1.dp)
            .clickable(enabled = openDetail != null) {
                openDetail?.let { onOpenDetail(dateString, it.storeId) }
            },
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFBEE)),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("今日经营建议", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                state.score?.storeName?.takeIf { it.isNotBlank() }?.let { name ->
                    Text(" · $name", style = MaterialTheme.typography.labelSmall, color = Color.Gray, maxLines = 1)
                }
                Spacer(Modifier.weight(1f))
                if (state.loading && state.score != null) {
                    CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.6.dp)
                    Spacer(Modifier.width(5.dp))
                }
                state.score?.let { score ->
                    Text(
                        "${score.totalScore}%  ›",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        color = BrandGreen
                    )
                }
            }

            when {
                state.score != null -> {
                    val score = state.score!!
                    val compact = listOf(
                        score.weatherSummary.takeIf { it.isNotBlank() },
                        score.historySummary.takeIf { it.isNotBlank() },
                        score.trendSummary.takeIf { it.contains("偏弱") || it.contains("偏强") }
                    ).filterNotNull().distinct().joinToString(" · ")
                    Text(
                        compact.ifBlank { "正在积累同位置历史数据" },
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.DarkGray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                state.loading -> {
                    Text(
                        "正在根据当前位置、天气和历史营业数据分析…",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                else -> {
                    Text(
                        state.error.ifBlank { "经营建议暂不可用" },
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun BusinessScoreBreakdownRow(
    title: String,
    score: Double,
    maxScore: Int,
    summary: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(
                "${String.format(Locale.CHINA, "%.1f", score)}/$maxScore",
                fontWeight = FontWeight.Bold,
                color = BrandGreen
            )
        }
        LinearProgressIndicator(
            progress = { (score / maxScore.toDouble()).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth()
        )
        Text(summary, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
    }
}

private fun businessReasonList(detailsJson: String, key: String): List<String> =
    runCatching {
        val arr = JSONObject(detailsJson).optJSONObject("reasons")?.optJSONArray(key) ?: return@runCatching emptyList()
        buildList {
            for (i in 0 until arr.length()) {
                arr.optString(i).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }.getOrDefault(emptyList())

private data class BusinessScoreModelUi(
    val version: String = "V1",
    val evidenceStrength: Double = 0.0,
    val calibrationCount: Int = 0,
    val calibrationAdjustment: Double = 0.0
)

private fun businessScoreModelUi(detailsJson: String): BusinessScoreModelUi =
    runCatching {
        val root = JSONObject(detailsJson.ifBlank { "{}" })
        val calibration = root.optJSONObject("calibration")
        BusinessScoreModelUi(
            version = root.optString("score_version", "V1"),
            evidenceStrength = root.optDouble("evidence_strength", 0.0),
            calibrationCount = calibration?.optInt("sample_count", 0) ?: 0,
            calibrationAdjustment = calibration?.optDouble("adjustment_points", 0.0) ?: 0.0
        )
    }.getOrDefault(BusinessScoreModelUi())

private data class BusinessScoreSnapshotUi(
    val at: Long,
    val score: Int,
    val summary: String
)

private fun businessScoreSnapshots(raw: String): List<BusinessScoreSnapshotUi> =
    runCatching {
        val arr = JSONArray(raw.ifBlank { "[]" })
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                add(
                    BusinessScoreSnapshotUi(
                        at = o.optLong("at"),
                        score = o.optInt("score"),
                        summary = o.optString("weather_summary")
                    )
                )
            }
        }
    }.getOrDefault(emptyList())

@Composable
private fun BusinessAdviceDetailContent(
    db: AppDatabase,
    dataVersion: Int,
    currentBook: LedgerBook,
    cloudSyncManager: CloudSyncManager,
    initialDate: String,
    initialStoreId: Long?,
    onChanged: () -> Unit
) {
    val date = remember(initialDate) {
        runCatching { LocalDate.parse(initialDate) }.getOrElse { LocalDate.now() }
    }
    val stores = remember(dataVersion) { db.getStores() }
    var selectedStoreId by remember(initialStoreId, stores) {
        mutableStateOf(initialStoreId ?: stores.firstOrNull()?.id)
    }
    var storeMenu by remember { mutableStateOf(false) }
    var state by remember(date, selectedStoreId) {
        mutableStateOf(BusinessAdviceUiState(loading = true))
    }
    var tagDraft by remember { mutableStateOf("") }
    var noteDraft by remember { mutableStateOf("") }

    LaunchedEffect(dataVersion, date, selectedStoreId, currentBook.cloudBookId) {
        val store = selectedStoreId?.let { db.getStoreById(it) }
        if (store == null) {
            state = BusinessAdviceUiState(error = "还没有可分析的经营位置")
            return@LaunchedEffect
        }
        val existing = db.getBusinessScore(date.toString(), store.id)
        state = BusinessAdviceUiState(score = existing, loading = !date.isBefore(LocalDate.now()))
        val resolved = withContext(Dispatchers.IO) {
            if (date.isBefore(LocalDate.now())) {
                existing
            } else {
                runCatching {
                    loadAndSaveBusinessScore(db, currentBook, cloudSyncManager, date, store)
                }.getOrNull() ?: existing
            }
        }
        state = if (resolved != null) BusinessAdviceUiState(score = resolved)
        else BusinessAdviceUiState(error = if (date.isBefore(LocalDate.now())) "该日期没有当时保存的经营评分" else "经营评分生成失败")
        tagDraft = resolved?.specialTag.orEmpty()
        noteDraft = resolved?.specialNote.orEmpty()
        if (resolved != null && !date.isBefore(LocalDate.now())) {
            cloudSyncManager.scheduleAutoSync(currentBook)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFBEE)),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("位置", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                    Spacer(Modifier.width(8.dp))
                    Box {
                        TextButton(onClick = { storeMenu = true }) {
                            Text("${stores.firstOrNull { it.id == selectedStoreId }?.name ?: "选择位置"} ▾")
                        }
                        DropdownMenu(expanded = storeMenu, onDismissRequest = { storeMenu = false }) {
                            stores.forEach { store ->
                                DropdownMenuItem(
                                    text = { Text(store.name) },
                                    onClick = {
                                        selectedStoreId = store.id
                                        storeMenu = false
                                    }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Text(date.toString(), style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                }

                when {
                    state.score != null -> {
                        val score = state.score!!
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("${score.totalScore}%", fontSize = 42.sp, fontWeight = FontWeight.Bold, color = BrandGreen)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.padding(bottom = 5.dp)) {
                                Text("经营指数", fontWeight = FontWeight.Bold)
                                Text(
                                    "可信度 ${businessConfidenceLabel(score.confidence)} · 相似样本 ${score.sampleCount} 个",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.Gray
                                )
                            }
                        }
                        Text(score.weatherSummary, fontWeight = FontWeight.SemiBold)
                        Text(score.historySummary, color = Color.DarkGray)
                    }
                    state.loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    else -> Text(state.error, color = Color.Gray)
                }
            }
        }

        state.score?.let { score ->
            val modelMeta = remember(score.detailsJson) { businessScoreModelUi(score.detailsJson) }
            val isV2 = modelMeta.version.startsWith("V2")
            Card(shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(if (isV2) "参考维度" else "指数构成", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    if (isV2) {
                        BusinessScoreBreakdownRow("天气条件", score.weatherScore, 100, score.weatherSummary)
                        BusinessScoreBreakdownRow("相似历史", score.historyScore, 100, score.historySummary)
                        BusinessScoreBreakdownRow("日期环境", score.calendarScore, 100, score.calendarSummary)
                        BusinessScoreBreakdownRow("近期趋势", score.trendScore, 100, score.trendSummary)
                        Text(
                            "V2 以同位置相似历史日为核心，四个维度采用动态证据权重；样本不足时总分自动向中性 70 回归，不再把固定权重直接相加。",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray
                        )
                    } else {
                        BusinessScoreBreakdownRow("天气指数", score.weatherScore, 35, score.weatherSummary)
                        BusinessScoreBreakdownRow("历史同期", score.historyScore, 30, score.historySummary)
                        BusinessScoreBreakdownRow("日期环境", score.calendarScore, 20, score.calendarSummary)
                        BusinessScoreBreakdownRow("近期趋势", score.trendScore, 15, score.trendSummary)
                    }
                    Text(
                        "库存与备货不计入经营指数，避免把外部经营环境和自身备货状态混在一起。",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray
                    )
                }
            }

            Card(shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("历史参考", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Row(Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text("位置基准", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                            Text(money(score.baselineRevenue), fontWeight = FontWeight.Bold)
                            Text("客流 ${String.format(Locale.CHINA, "%.0f", score.baselineCustomers)} · 客单 ${money(score.baselineTicket)}", style = MaterialTheme.typography.bodySmall)
                        }
                        Column(Modifier.weight(1f)) {
                            Text("相似历史日", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                            Text(money(score.similarRevenue), fontWeight = FontWeight.Bold)
                            Text("客流 ${String.format(Locale.CHINA, "%.0f", score.similarCustomers)} · 客单 ${money(score.similarTicket)}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            if (isV2) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF7F8FA)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("样本与自动校准", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Text(
                            "有效证据强度 ${(modelMeta.evidenceStrength * 100).roundToInt()}% · 同位置相似样本 ${score.sampleCount} 个",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            if (modelMeta.calibrationCount >= 6) {
                                "已使用 ${modelMeta.calibrationCount} 个历史经营结果校准，本次修正 ${if (modelMeta.calibrationAdjustment >= 0) "+" else ""}${String.format(Locale.CHINA, "%.1f", modelMeta.calibrationAdjustment)} 分"
                            } else {
                                "已有 ${modelMeta.calibrationCount} 个 V2 经营结果复盘；累计到 6 个后自动启用位置级校准"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.DarkGray
                        )
                        Text(
                            "其它位置的数据只在本位置样本不足时作为资料提示，不进入本位置正式评分。",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray
                        )
                    }
                }
            }

            Card(shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("为什么这样评分", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    listOf(
                        "天气" to businessReasonList(score.detailsJson, "weather"),
                        "历史同期" to businessReasonList(score.detailsJson, "history"),
                        "日期环境" to businessReasonList(score.detailsJson, "calendar"),
                        "近期趋势" to businessReasonList(score.detailsJson, "trend")
                    ).forEach { (title, reasons) ->
                        if (reasons.isNotEmpty()) {
                            Text(title, fontWeight = FontWeight.SemiBold)
                            reasons.forEach { reason ->
                                Text("• $reason", style = MaterialTheme.typography.bodySmall, color = Color.DarkGray)
                            }
                        }
                    }
                }
            }

            if (score.actualRevenue > 0.0 || score.actualCustomers > 0) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SoftGreen),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("经营结果复盘", fontWeight = FontWeight.Bold)
                        Text("实际营业额 ${money(score.actualRevenue)}")
                        if (score.baselineRevenue > 0.0) {
                            val revenueDelta = (score.actualRevenue / score.baselineRevenue - 1.0) * 100.0
                            Text(
                                "较该位置基准 ${if (revenueDelta >= 0) "+" else ""}${String.format(Locale.CHINA, "%.1f", revenueDelta)}%",
                                color = if (revenueDelta >= 0) BrandGreen else MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Text("实际客流 ${score.actualCustomers} · 实际客单 ${money(score.actualTicket)}")
                        Text("实际利润 ${money(score.actualProfit)}")
                        Text(
                            "这些实绩只用于复盘和以后校准权重，不会反向修改当天开摊前的评分。",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray
                        )
                    }
                }
            }

            Card(shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("特殊因素标记", fontWeight = FontWeight.Bold)
                    Text(
                        "只作为历史标签保存，V1 暂不直接加减经营指数，避免主观判断污染模型。",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray
                    )
                    val tags = listOf("", "工厂放假", "发薪日", "附近活动", "道路施工", "竞争促销", "临时换位", "其他")
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        tags.chunked(3).forEach { rowTags ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                rowTags.forEach { tag ->
                                    FilterChip(
                                        selected = tagDraft == tag,
                                        onClick = { tagDraft = tag },
                                        label = { Text(if (tag.isBlank()) "无" else tag) }
                                    )
                                }
                            }
                        }
                    }
                    OutlinedTextField(
                        value = noteDraft,
                        onValueChange = { noteDraft = it.take(120) },
                        label = { Text("备注（可选）") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 3
                    )
                    Button(
                        onClick = {
                            if (db.updateBusinessScoreSpecialTag(score.date, score.storeId, tagDraft, noteDraft)) {
                                state = BusinessAdviceUiState(score = db.getBusinessScore(score.date, score.storeId))
                                cloudSyncManager.scheduleAutoSync(currentBook)
                                onChanged()
                            }
                        }
                    ) {
                        Text("保存特殊因素")
                    }
                }
            }

            val snapshots = remember(score.snapshotsJson) { businessScoreSnapshots(score.snapshotsJson).takeLast(6).reversed() }
            if (snapshots.isNotEmpty()) {
                Card(shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text("今日评分变化", fontWeight = FontWeight.Bold)
                        snapshots.forEach { snap ->
                            Row(Modifier.fillMaxWidth()) {
                                Text(compactSyncTime(snap.at), modifier = Modifier.width(58.dp), color = Color.Gray)
                                Text("${snap.score}%", modifier = Modifier.width(52.dp), fontWeight = FontWeight.Bold)
                                Text(snap.summary, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            Text(
                "经营指数表示当前条件相对本位置历史基准的有利程度，不是营业额预测值，也不是预测准确率。",
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun BusinessWeatherCard(
    db: AppDatabase,
    date: String,
    store: StoreOption?,
    currentBook: LedgerBook,
    cloudSyncManager: CloudSyncManager,
    onOpenDetail: (String, Long?) -> Unit
) {
    var state by remember(date, store?.id) { mutableStateOf(WeatherUiState(loading = true)) }
    val selectedDate = runCatching { LocalDate.parse(date) }.getOrElse { LocalDate.now() }

    LaunchedEffect(date, store?.id, store?.latitude, store?.longitude, currentBook.cloudBookId) {
        val selectedStore = store
        if (selectedStore == null) {
            state = WeatherUiState(error = "当前没有营业位置")
            return@LaunchedEffect
        }
        val past = selectedDate.isBefore(LocalDate.now())
        if (past) {
            val archived = withContext(Dispatchers.IO) {
                runCatching {
                    val bookId = weatherBookId(currentBook)
                    if (bookId.isBlank()) throw IllegalStateException("账本尚未连接云端天气服务")
                    WeatherClient(cloudSyncManager).fetchArchive(bookId, selectedStore, selectedDate)
                }
            }
            state = archived.fold(
                onSuccess = { WeatherUiState(it, snapshotType = "SERVER_ARCHIVE", updatedAtMillis = System.currentTimeMillis()) },
                onFailure = { WeatherUiState(error = it.message ?: "该营业日暂无服务器逐小时天气档案") }
            )
            return@LaunchedEffect
        }
        if (selectedStore.latitude == null || selectedStore.longitude == null) {
            state = WeatherUiState(error = "当前营业位置未绑定天气坐标")
            return@LaunchedEffect
        }
        val now = System.currentTimeMillis()
        val cached = withContext(Dispatchers.IO) { db.getWeatherCache(date, selectedStore.id) }
        var hasCache = false
        if (cached != null) {
            val parsed = runCatching { WeatherClient.parseOverview(cached.payloadJson, false) }.getOrNull()
            if (parsed != null) {
                hasCache = true
                val stale = cached.expiresAt <= now
                state = WeatherUiState(parsed, refreshing = stale, snapshotType = "CACHE", updatedAtMillis = cached.fetchedAt)
                if (!stale) return@LaunchedEffect
            }
        }
        if (!hasCache) state = WeatherUiState(loading = true)
        val refreshed = withContext(Dispatchers.IO) {
            runCatching {
                val bookId = weatherBookId(currentBook)
                if (bookId.isBlank()) throw IllegalStateException("账本尚未连接云端天气服务")
                val overview = WeatherClient(cloudSyncManager).fetchOverview(bookId, selectedStore, selectedDate, 120)
                val fetchedAt = System.currentTimeMillis()
                db.saveWeatherCache(date, selectedStore.id, overview.rawJson, weatherCacheTtl(selectedDate), fetchedAt)
                WeatherUiState(overview, snapshotType = "CACHE", updatedAtMillis = fetchedAt)
            }
        }
        state = refreshed.getOrElse { error ->
            if (hasCache) state.copy(refreshing = false) else WeatherUiState(error = error.message ?: "天气加载失败")
        }
    }

    val ov = state.overview
    val bh = storeBusinessHours(ov?.hourly.orEmpty(), date, store)
    val pop = bh.mapNotNull { it.precipitationProbability }.maxOrNull()
    val day = ov?.daily?.firstOrNull { it.date == date } ?: ov?.selectedDay()
    val text = ov?.current?.text?.takeIf { selectedDate == LocalDate.now() } ?: day?.textDay.orEmpty().ifBlank { bh.firstOrNull()?.text.orEmpty() }
    val temp = ov?.current?.temperature?.takeIf { selectedDate == LocalDate.now() } ?: bh.firstOrNull()?.temperature ?: day?.tempMax

    Card(
        Modifier.fillMaxWidth().clickable(enabled = store != null) { onOpenDetail(date, store?.id) },
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF5FAFF)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        state.loading -> "🌤️ ${store?.name ?: "营业天气"} · 更新中…"
                        state.error.isNotBlank() -> "🌤️ ${store?.name ?: "营业天气"} · ${state.error}"
                        else -> "${weatherEmoji(ov?.current?.code ?: day?.codeDay.orEmpty(), text)} ${store?.name.orEmpty()} · ${weatherTemp(temp)} · ${text.ifBlank { "天气" }}"
                    },
                    modifier = Modifier.weight(1f),
                    fontWeight = if (ov != null) FontWeight.SemiBold else FontWeight.Normal,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.error.isNotBlank()) Color.Gray else Color.Unspecified
                )
                if (ov != null) Text("详情 ›", style = MaterialTheme.typography.labelSmall, color = BrandGreen)
            }
            if (ov != null) {
                Text(
                    "${businessTimeLabel(store)} · 降雨概率${weatherPercent(pop)} · 风 ${bh.firstOrNull()?.windDirection.orEmpty()} ${bh.firstOrNull()?.windScale.orEmpty()}级",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.DarkGray
                )
            }
        }
    }
}

@Composable
private fun OperatingAnalysisWeatherCard(
    db: AppDatabase,
    date: String,
    dataVersion: Int,
    currentBook: LedgerBook,
    cloudSyncManager: CloudSyncManager
) {
    val resolution = remember(dataVersion, date) { db.resolveWeatherStore(date) }
    val store = resolution.store
    var state by remember(date, store?.id) { mutableStateOf(WeatherUiState(loading = true)) }

    LaunchedEffect(date, store?.id, currentBook.cloudBookId) {
        val selectedStore = store
        if (selectedStore == null) {
            state = WeatherUiState(error = "未找到该营业日位置")
            return@LaunchedEffect
        }
        val selectedDate = runCatching { LocalDate.parse(date) }.getOrElse { LocalDate.now() }
        if (selectedDate.isAfter(LocalDate.now())) {
            state = WeatherUiState(error = "未来日期暂无营业天气档案")
            return@LaunchedEffect
        }
        val result = withContext(Dispatchers.IO) {
            runCatching {
                db.getBusinessWeatherHistory(date, selectedStore.id)?.let { local ->
                    return@runCatching WeatherClient.parseOverview(local.payloadJson, true)
                }
                val bookId = weatherBookId(currentBook)
                if (bookId.isBlank()) throw IllegalStateException("当前账本尚未连接云端天气服务")
                val overview = WeatherClient(cloudSyncManager).fetchArchive(bookId, selectedStore, selectedDate)
                val business = db.getDailyRecords(date).firstOrNull { it.storeId == selectedStore.id }
                db.cacheBusinessWeatherHistory(
                    date, selectedStore,
                    business?.actualStartTime?.ifBlank { selectedStore.defaultStartTime } ?: selectedStore.defaultStartTime,
                    business?.actualEndTime?.ifBlank { selectedStore.defaultEndTime } ?: selectedStore.defaultEndTime,
                    overview.rawJson
                )
                overview
            }
        }
        state = result.fold(
            onSuccess = { WeatherUiState(it, snapshotType = "SERVER_ARCHIVE", updatedAtMillis = System.currentTimeMillis()) },
            onFailure = { WeatherUiState(error = it.message ?: "暂无服务器营业天气档案") }
        )
    }

    val overview = state.overview
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF5FAFF)), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("天气关联", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(store?.name ?: "未确定位置", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
            }
            when {
                state.loading -> Text("正在读取服务器逐小时天气档案…", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                overview == null -> Text(
                    state.error.ifBlank { "暂无服务器营业天气档案" },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
                else -> {
                    val day = overview.daily.firstOrNull { it.date == date } ?: overview.selectedDay()
                    val bh = storeBusinessHours(overview.hourly, date, store)
                    val pop = bh.mapNotNull { it.precipitationProbability }.maxOrNull()
                    val rain = bh.sumOf { it.precipitation ?: 0.0 }
                    val text = day?.textDay.orEmpty().ifBlank { bh.firstOrNull()?.text.orEmpty() }
                    Text(
                        "${weatherEmoji(day?.codeDay.orEmpty(), text)} $text · ${businessTimeLabel(store)} 降雨 ${weatherPercent(pop)} · ${weatherAmount(rain)}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "历史营业天气已进入本地＋服务器同步档案；优先读本地，缺失时才向服务器补齐",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray
                    )
                }
            }
        }
    }
}

@Composable
private fun WeatherDetailContent(
    db: AppDatabase,
    dataVersion: Int,
    currentBook: LedgerBook,
    cloudSyncManager: CloudSyncManager,
    initialDate: String,
    initialStoreId: Long? = null
) {
    var selectedDate by remember(initialDate) {
        mutableStateOf(runCatching { LocalDate.parse(initialDate) }.getOrElse { LocalDate.now() })
    }
    var manualStoreId by remember(selectedDate) {
        mutableStateOf(if (selectedDate.toString() == initialDate) initialStoreId else null)
    }
    var storeMenu by remember { mutableStateOf(false) }
    val stores = remember(dataVersion) { db.getStores() }
    val resolution = remember(dataVersion, selectedDate) { db.resolveWeatherStore(selectedDate.toString()) }
    val store = stores.firstOrNull { it.id == manualStoreId }
        ?: resolution.store
        ?: stores.firstOrNull()
    var state by remember(selectedDate, store?.id) { mutableStateOf(WeatherUiState(loading = true)) }

    LaunchedEffect(selectedDate, store?.id, store?.latitude, store?.longitude, currentBook.cloudBookId) {
        val s = store
        if (s == null) {
            state = WeatherUiState(error = "还没有经营位置")
            return@LaunchedEffect
        }
        val date = selectedDate.toString()
        val past = selectedDate.isBefore(LocalDate.now())
        if (past) {
            val localHistory = withContext(Dispatchers.IO) { db.getBusinessWeatherHistory(date, s.id) }
            val localOverview = localHistory?.let { history ->
                runCatching { WeatherClient.parseOverview(history.payloadJson, true) }.getOrNull()
            }
            if (localOverview != null) {
                state = WeatherUiState(
                    localOverview,
                    snapshotType = "LOCAL_HISTORY",
                    updatedAtMillis = localHistory.updatedAt
                )
                return@LaunchedEffect
            }

            val archived = withContext(Dispatchers.IO) {
                runCatching {
                    val bookId = weatherBookId(currentBook)
                    if (bookId.isBlank()) throw IllegalStateException("当前账本尚未连接云端天气服务")
                    WeatherClient(cloudSyncManager).fetchArchive(bookId, s, selectedDate)
                }
            }
            state = archived.fold(
                onSuccess = { overview ->
                    withContext(Dispatchers.IO) {
                        val business = db.getStoreDailyRecord(date, s.id)
                        db.cacheBusinessWeatherHistory(
                            date = date,
                            store = s,
                            actualStartTime = business?.actualStartTime?.ifBlank { s.defaultStartTime } ?: s.defaultStartTime,
                            actualEndTime = business?.actualEndTime?.ifBlank { s.defaultEndTime } ?: s.defaultEndTime,
                            payloadJson = overview.rawJson
                        )
                        db.saveWeatherCache(date, s.id, overview.rawJson, WEATHER_CACHE_ARCHIVE_MS)
                    }
                    WeatherUiState(overview, snapshotType = "SERVER_ARCHIVE", updatedAtMillis = System.currentTimeMillis())
                },
                onFailure = { error ->
                    val cached = withContext(Dispatchers.IO) { db.getWeatherCache(date, s.id) }
                    val parsed = cached?.let {
                        runCatching { WeatherClient.parseOverview(it.payloadJson, true) }.getOrNull()
                    }
                    if (parsed != null) {
                        WeatherUiState(
                            parsed,
                            snapshotType = "ARCHIVE_CACHE",
                            updatedAtMillis = cached.fetchedAt
                        )
                    } else {
                        val message = when {
                            error is CloudApiException &&
                                error.statusCode == 404 &&
                                error.message.orEmpty().equals("Not Found", ignoreCase = true) ->
                                "服务器历史天气接口未启用，请确认 Server V1.0.12-Lucky 已部署"

                            error is CloudApiException &&
                                error.statusCode == 404 ->
                                error.message.orEmpty().ifBlank { "该营业日暂无服务器逐小时天气档案" }

                            else ->
                                error.message ?: "该日期暂无服务器历史天气档案"
                        }
                        WeatherUiState(error = message)
                    }
                }
            )
            return@LaunchedEffect
        }

        if (s.latitude == null || s.longitude == null) {
            state = WeatherUiState(error = "${s.name} 尚未绑定天气经纬度")
            return@LaunchedEffect
        }

        val now = System.currentTimeMillis()
        val cached = withContext(Dispatchers.IO) { db.getWeatherCache(date, s.id) }
        var hasCache = false
        if (cached != null) {
            val parsed = runCatching { WeatherClient.parseOverview(cached.payloadJson, false) }.getOrNull()
            if (parsed != null) {
                hasCache = true
                val stale = cached.expiresAt <= now
                state = WeatherUiState(parsed, refreshing = stale, snapshotType = "CACHE", updatedAtMillis = cached.fetchedAt)
                if (!stale) return@LaunchedEffect
            }
        }
        if (!hasCache) state = WeatherUiState(loading = true)
        val refreshed = withContext(Dispatchers.IO) {
            runCatching {
                val bookId = weatherBookId(currentBook)
                if (bookId.isBlank()) throw IllegalStateException("当前账本尚未连接云端天气服务")
                val ov = WeatherClient(cloudSyncManager).fetchOverview(bookId, s, selectedDate, 120)
                val fetchedAt = System.currentTimeMillis()
                db.saveWeatherCache(date, s.id, ov.rawJson, weatherCacheTtl(selectedDate), fetchedAt)
                WeatherUiState(ov, snapshotType = "CACHE", updatedAtMillis = fetchedAt)
            }
        }
        state = refreshed.getOrElse { error ->
            if (hasCache) state.copy(refreshing = false) else WeatherUiState(error = error.message ?: "天气加载失败")
        }
    }

    val ov = state.overview
    val dateString = selectedDate.toString()
    val day = ov?.daily?.firstOrNull { it.date == dateString } ?: ov?.selectedDay()
    val hoursForDate = ov?.hourly.orEmpty().filter { it.time.startsWith(dateString) }
    val hourly24 = remember(ov?.rawJson, selectedDate) {
        if (selectedDate == LocalDate.now()) {
            val currentKey = LocalDateTime.now()
                .withMinute(0).withSecond(0).withNano(0)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH"))
            ov?.hourly.orEmpty().filter { it.time.take(13) >= currentKey }.take(24)
        } else {
            hoursForDate.take(24)
        }
    }
    val business = storeBusinessHours(ov?.hourly.orEmpty(), dateString, store)
    val trendHours = hourly24.filter { it.temperature != null }

    LazyColumn(
        modifier = Modifier.fillMaxSize().pointerInput(selectedDate) {
            var drag = 0f
            detectHorizontalDragGestures(
                onDragStart = { drag = 0f },
                onHorizontalDrag = { _, amount -> drag += amount },
                onDragEnd = {
                    when {
                        drag <= -70f -> { selectedDate = selectedDate.plusDays(1); manualStoreId = null }
                        drag >= 70f -> { selectedDate = selectedDate.minusDays(1); manualStoreId = null }
                    }
                }
            )
        },
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFEAF5FF)), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box {
                            TextButton(onClick = { if (stores.isNotEmpty()) storeMenu = true }) {
                                Text("${store?.name ?: "经营位置"} ▾", fontWeight = FontWeight.Bold)
                            }
                            DropdownMenu(expanded = storeMenu, onDismissRequest = { storeMenu = false }) {
                                stores.forEach { option ->
                                    DropdownMenuItem(text = { Text(option.name) }, onClick = { manualStoreId = option.id; storeMenu = false })
                                }
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        Text(weatherDateLabel(selectedDate), style = MaterialTheme.typography.labelMedium, color = Color.DarkGray)
                    }
                    when {
                        state.loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                        state.error.isNotBlank() -> Text(state.error, color = Color(0xFFB26A00))
                        ov != null -> {
                            val c = ov.current?.takeIf { selectedDate == LocalDate.now() }
                            val firstHour = hourly24.firstOrNull() ?: hoursForDate.firstOrNull()
                            val text = c?.text ?: day?.textDay.orEmpty().ifBlank { firstHour?.text.orEmpty() }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(weatherEmoji(c?.code ?: day?.codeDay.orEmpty(), text), fontSize = 46.sp)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(text.ifBlank { "天气" }, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                    Text("${weatherTemp(day?.tempMin)} ～ ${weatherTemp(day?.tempMax)}", style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        buildString {
                                            append(if (manualStoreId != null) "指定位置" else weatherLocationSourceText(resolution.source, resolution.confidence, resolution.sampleCount))
                                            val updated = weatherUpdatedText(state.updatedAtMillis)
                                            if (updated.isNotBlank()) append(" · 更新于 $updated")
                                            if (state.refreshing) append(" · 后台更新中")
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.Gray
                                    )
                                }
                                Text(weatherTemp(c?.temperature ?: firstHour?.temperature ?: day?.tempMax), fontSize = 38.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        if (ov != null) {
            if (ov.alerts.isNotEmpty()) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEEEE))) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("天气预警", fontWeight = FontWeight.Bold, color = Color(0xFFC62828))
                            ov.alerts.take(3).forEach { alert ->
                                Text("⚠ ${alert.title}", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                                if (alert.description.isNotBlank()) Text(alert.description, style = MaterialTheme.typography.bodySmall, color = Color.DarkGray, maxLines = 3)
                            }
                        }
                    }
                }
            }
            if (ov.minutelySummary.isNotBlank() && selectedDate == LocalDate.now()) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFEAF7FF))) {
                        Column(Modifier.padding(12.dp)) {
                            Text("未来2小时降雨", fontWeight = FontWeight.Bold)
                            Text(ov.minutelySummary, color = Color(0xFF2C6E9B), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            item {
                Text(
                    when {
                        ov.historical -> "营业时段逐小时天气档案"
                        selectedDate == LocalDate.now() -> "未来24小时逐小时预报"
                        else -> "24小时逐小时预报"
                    },
                    fontWeight = FontWeight.Bold
                )
                Text(
                    when {
                        ov.historical -> "服务器按实际营业位置与营业时间保存；实际天气与当时预报分开记录"
                        selectedDate == LocalDate.now() -> "从当前小时开始，向后连续24小时"
                        else -> "按所选日期显示逐小时天气"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray
                )
                if (hourly24.isEmpty()) {
                    Text("该时段暂无逐小时数据", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                } else {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        hourly24.forEach { h ->
                            Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                                Column(
                                    Modifier.width(112.dp).padding(horizontal = 8.dp, vertical = 9.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Text(weatherHourLabel(h.time), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium)
                                    Text(weatherEmoji(h.code, h.text), fontSize = 24.sp)
                                    Text(h.text.ifBlank { "—" }, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                    Text(weatherTemp(h.temperature), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                    if (ov.historical) {
                                        Text(
                                            "当时预报 ${weatherPercent(h.precipitationProbability)}",
                                            color = Color(0xFF2C6E9B),
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                        if (h.forecastAvailable && h.forecastText.isNotBlank()) {
                                            Text(
                                                "预报 ${h.forecastText} ${weatherTemp(h.forecastTemperature)}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Color.Gray,
                                                maxLines = 1
                                            )
                                        }
                                        Text(
                                            if (h.actualAvailable) "实况雨量 ${weatherAmount(h.precipitation)}" else "实况待归档",
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    } else {
                                        Text("降雨 ${weatherPercent(h.precipitationProbability)}", color = Color(0xFF2C6E9B), style = MaterialTheme.typography.labelSmall)
                                        Text("雨量 ${weatherAmount(h.precipitation)}", style = MaterialTheme.typography.labelSmall)
                                    }
                                    Text("风 ${h.windDirection.orEmpty()} ${h.windScale.orEmpty()}级", style = MaterialTheme.typography.labelSmall)
                                    Text("风速 ${windSpeedText(h.windSpeed)}", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                }
                            }
                        }
                    }
                }
            }

            if (trendHours.size >= 2) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                        Column(Modifier.padding(12.dp)) {
                            Text("温度趋势", fontWeight = FontWeight.Bold)
                            Text(
                                "每个点显示温度和时间，可左右滑动查看完整24小时",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.Gray
                            )
                            val chartWidth = maxOf(
                                620.dp,
                                (trendHours.size * 58).dp
                            )
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                            ) {
                                Canvas(
                                    Modifier
                                        .width(chartWidth)
                                        .height(168.dp)
                                        .padding(horizontal = 18.dp, vertical = 8.dp)
                                ) {
                                    val temperatures = trendHours.mapNotNull { it.temperature }
                                    val minT = temperatures.minOrNull() ?: 0.0
                                    val maxT = temperatures.maxOrNull() ?: minT + 1.0
                                    val range = (maxT - minT).coerceAtLeast(1.0)
                                    val top = 28f
                                    val bottom = size.height - 34f
                                    val stepX = if (trendHours.size <= 1) {
                                        0f
                                    } else {
                                        size.width / (trendHours.size - 1)
                                    }
                                    repeat(3) { index ->
                                        val y = top + (bottom - top) * index / 2f
                                        drawLine(
                                            Color(0xFFE7E9ED),
                                            Offset(0f, y),
                                            Offset(size.width, y),
                                            strokeWidth = 1.2f
                                        )
                                    }
                                    val points = trendHours.mapIndexed { index, hour ->
                                        val temperature = hour.temperature ?: minT
                                        val x = if (trendHours.size == 1) size.width / 2f else stepX * index
                                        val y = bottom -
                                            (((temperature - minT) / range).toFloat() * (bottom - top))
                                        Offset(x, y)
                                    }
                                    points.zipWithNext().forEach { (a, b) ->
                                        drawLine(
                                            Color(0xFF3A86C8),
                                            a,
                                            b,
                                            strokeWidth = 4f
                                        )
                                    }
                                    val valuePaint = AndroidPaint().apply {
                                        isAntiAlias = true
                                        color = Color(0xFF276C9E).toArgb()
                                        textAlign = AndroidPaint.Align.CENTER
                                        textSize = 11.sp.toPx()
                                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                                    }
                                    val timePaint = AndroidPaint().apply {
                                        isAntiAlias = true
                                        color = Color.Gray.toArgb()
                                        textAlign = AndroidPaint.Align.CENTER
                                        textSize = 10.sp.toPx()
                                    }
                                    points.forEachIndexed { index, point ->
                                        drawCircle(Color.White, radius = 7f, center = point)
                                        drawCircle(Color(0xFF3A86C8), radius = 4.5f, center = point)
                                        val temperature = trendHours[index].temperature ?: return@forEachIndexed
                                        drawContext.canvas.nativeCanvas.drawText(
                                            String.format(Locale.getDefault(), "%.0f°", temperature),
                                            point.x,
                                            (point.y - 10f).coerceAtLeast(valuePaint.textSize),
                                            valuePaint
                                        )
                                        drawContext.canvas.nativeCanvas.drawText(
                                            weatherHourLabel(trendHours[index].time),
                                            point.x,
                                            size.height - 4f,
                                            timePaint
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item {
                val maxPop = business.mapNotNull { it.precipitationProbability }.maxOrNull()
                val amount = business.sumOf { it.precipitation ?: 0.0 }
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF6FBFF))) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("经营时段 ${businessTimeLabel(store)}", fontWeight = FontWeight.Bold)
                        Text(
                            if (ov.historical)
                                "当时预报最高降雨概率 ${weatherPercent(maxPop)} · 实况降雨 ${weatherAmount(amount)}"
                            else
                                "最高降雨概率 ${weatherPercent(maxPop)} · 预计降雨 ${weatherAmount(amount)}"
                        )
                        val wet = business.filter { (it.precipitationProbability ?: 0.0) >= 40 || (it.precipitation ?: 0.0) > 0.05 }
                        if (wet.isNotEmpty()) {
                            Text("重点时段：${wet.joinToString("、") { weatherHourLabel(it.time) }}", color = Color(0xFF2C6E9B), style = MaterialTheme.typography.bodySmall)
                        } else Text("经营时段暂无明显降雨信号", color = BrandGreen, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            item {
                Text(if (ov.historical) "营业日天气汇总" else "未来天气", fontWeight = FontWeight.Bold)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ov.daily.take(if (ov.historical) 1 else 10).forEach { d ->
                        Row(
                            Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(runCatching { LocalDate.parse(d.date) }.getOrNull()?.let { weatherDateLabel(it) } ?: d.date, Modifier.width(118.dp), style = MaterialTheme.typography.bodySmall)
                            Text(weatherEmoji(d.codeDay, d.textDay), modifier = Modifier.width(34.dp), fontSize = 18.sp)
                            Text(d.textDay.ifBlank { "—" }, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            Text("${weatherTemp(d.tempMin)} / ${weatherTemp(d.tempMax)}", modifier = Modifier.width(72.dp), textAlign = TextAlign.End, style = MaterialTheme.typography.bodySmall)
                            Text(weatherPercent(d.precipitationProbability), modifier = Modifier.width(52.dp), textAlign = TextAlign.End, color = Color(0xFF2C6E9B), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            item {
                Text("数据来源：${ov.attribution} · 左右滑动切换日期", style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun HomeScreen(
    db: AppDatabase,
    dataVersion: Int,
    ledgerManager: LedgerManager,
    currentBook: LedgerBook,
    cloudSyncManager: CloudSyncManager,
    books: List<LedgerBook>,
    ledgerUiSettingsManager:
        LedgerUiSettingsManager,
    uiSettingsVersion: Int,
    systemRole: String,
    onSwitchBook: (String) -> Unit,
    onBookManage: () -> Unit,
    onHeaderSettings: () -> Unit,
    onPurchase: () -> Unit,
    onPlan: () -> Unit,
    onSession: () -> Unit,
    onStores: () -> Unit,
    onHistory: () -> Unit,
    onPurchaseActivity: () -> Unit,
    onStats: () -> Unit,
    onProfit: () -> Unit,
    onReport: () -> Unit,
    onFruits: () -> Unit,
    onInventory: () -> Unit,
    onOpenWeather: (String, Long?) -> Unit,
    onOpenBusinessAdvice: (String, Long) -> Unit,
    onMore: () -> Unit,
    onOpenMore: (MorePage) -> Unit
) {
    var selectedDate by remember {
        mutableStateOf(
            LocalDate.now()
        )
    }
    var adviceStoreId by remember(currentBook.id) {
        mutableStateOf(
            db.resolveWeatherStore(LocalDate.now().toString()).store?.id
                ?: db.getStores().firstOrNull()?.id
        )
    }
    var bookMenuExpanded by remember {
        mutableStateOf(false)
    }

    val canViewBusiness =
        BookPermissions.has(
            currentBook,
            systemRole,
            BookPermissions.HOME_BUSINESS_VIEW
        )

    val canViewHistory =
        BookPermissions.has(
            currentBook,
            systemRole,
            BookPermissions.HISTORY_VIEW
        )

    val canViewStats =
        BookPermissions.has(
            currentBook,
            systemRole,
            BookPermissions.STATS_VIEW
        )

    val canViewProfit =
        BookPermissions.has(
            currentBook,
            systemRole,
            BookPermissions.PROFIT_VIEW
        )

    val canViewReport =
        BookPermissions.has(
            currentBook,
            systemRole,
            BookPermissions.REPORT_VIEW
        )

    val canViewPurchaseActivity =
        BookPermissions.has(
            currentBook,
            systemRole,
            BookPermissions.PURCHASE_ACTIVITY_VIEW
        )

    val canEditPurchasePlan =
        BookPermissions.has(
            currentBook,
            systemRole,
            BookPermissions.PURCHASE_PLAN_EDIT
        )

    val headerSettings =
        remember(
            currentBook.id,
            uiSettingsVersion
        ) {
            ledgerUiSettingsManager
                .load(
                    currentBook.id
                )
        }

    val headerBitmap =
        remember(
            currentBook.id,
            uiSettingsVersion,
            headerSettings
                .backgroundImagePath
        ) {
            headerSettings
                .backgroundImagePath
                .takeIf {
                    it.isNotBlank()
                }
                ?.let {
                    path ->
                    runCatching {
                        BitmapFactory
                            .decodeFile(
                                path
                            )
                            ?.asImageBitmap()
                    }.getOrNull()
                }
        }

    val selectedDateString =
        selectedDate.toString()

    val summary = remember(dataVersion, selectedDateString) {
        db.getDailySummary(selectedDateString)
    }
    val operatingAnalysis = remember(dataVersion, selectedDateString) {
        db.getOperatingAnalysis(selectedDateString)
    }
    val records = remember(dataVersion, selectedDateString) {
        db.getDailyRecords(selectedDateString)
    }
    val collaborationDate =
        LocalDate
            .now()
            .toString()

    val collaborationPlan =
        remember(
            dataVersion,
            collaborationDate
        ) {
            db.getPurchasePlan(
                collaborationDate
            )
        }

    val collaborationItems =
        collaborationPlan
            ?.items
            .orEmpty()

    val collaborationCompleted =
        collaborationItems.count {
            it.status == 1
        }

    val collaborationRemaining =
        collaborationItems.count {
            it.status == 0
        }

    val collaborationPlanAmount =
        collaborationItems.sumOf {
            it.estimatedAmount
        }

    val collaborationCompletedAmount =
        collaborationItems
            .filter {
                it.status == 1
            }
            .sumOf {
                it.actualAmount
            }

    val trend = remember(dataVersion, selectedDateString) {
        (6 downTo 0).map { offset ->
            val d = selectedDate.minusDays(offset.toLong())
            d to db.getDailySummary(d.toString()).revenue
        }
    }

    val monthRange = remember(selectedDate) {
        selectedDate.withDayOfMonth(1).toString() to
            selectedDate.withDayOfMonth(selectedDate.lengthOfMonth()).toString()
    }
    val rankings = remember(dataVersion, monthRange) {
        db.getRankings(monthRange.first, monthRange.second)
            .sortedByDescending { it.revenue }
    }

    val locationText =
        if (!canViewBusiness) {
            "经营数据已按权限隐藏"
        } else if (records.isEmpty()) {
            "当日未记录位置"
        } else {
            records.joinToString("、") { it.storeName }.take(30)
        }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        item {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(118.dp)
            ) {
                if (
                    headerBitmap != null
                ) {
                    Image(
                        bitmap =
                            headerBitmap,
                        contentDescription =
                            "首页顶部背景",
                        modifier =
                            Modifier.fillMaxSize(),
                        contentScale =
                            ContentScale.Crop
                    )

                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(
                                Color.Black.copy(
                                    alpha = 0.28f
                                )
                            )
                    )
                } else {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        Color(
                                            0xFF0A6E3A
                                        ),
                                        Color(
                                            0xFF148E51
                                        ),
                                        Color(
                                            0xFF0B5E34
                                        )
                                    )
                                )
                            )
                    )
                }

                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(
                            horizontal = 16.dp,
                            vertical = 12.dp
                        )
                ) {
                    Box(
                        Modifier
                            .align(Alignment.TopStart)
                            .offset(y = (-6).dp)
                    ) {
                        TextButton(
                            onClick = {
                                bookMenuExpanded =
                                    true
                            },
                            modifier =
                                Modifier.widthIn(
                                    max = 260.dp
                                )
                        ) {
                            Text(
                                "📒 " +
                                    ledgerManager
                                        .displayName(
                                            currentBook
                                        ) +
                                    "  ▼",
                                color =
                                    Color.White,
                                fontSize =
                                    12.sp,
                                fontWeight =
                                    FontWeight
                                        .SemiBold,
                                maxLines = 1
                            )
                        }

                        DropdownMenu(
                            expanded =
                                bookMenuExpanded,
                            onDismissRequest = {
                                bookMenuExpanded =
                                    false
                            }
                        ) {
                            books
                                .sortedWith(
                                    compareByDescending<
                                        LedgerBook
                                    > {
                                        it.id ==
                                            currentBook.id
                                    }.thenByDescending {
                                        it.lastSyncAt
                                    }.thenBy {
                                        it.createdAt
                                    }
                                )
                                .forEach {
                                    book ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(
                                                    (
                                                        if (
                                                            book.id ==
                                                            currentBook.id
                                                        ) {
                                                            "✓ "
                                                        } else {
                                                            ""
                                                        }
                                                    ) +
                                                        ledgerManager
                                                            .displayName(
                                                                book
                                                            ),
                                                    fontWeight =
                                                        if (
                                                            book.id ==
                                                            currentBook.id
                                                        ) {
                                                            FontWeight
                                                                .Bold
                                                        } else {
                                                            FontWeight
                                                                .Normal
                                                        }
                                                )

                                                Text(
                                                    ledgerManager
                                                        .permissionLabel(
                                                            book.permission
                                                        ) +
                                                        if (
                                                            book.cloudEnabled
                                                        ) {
                                                            " · 云端"
                                                        } else if (
                                                            book.cloudBookId
                                                                .isNotBlank()
                                                        ) {
                                                            " · 本机副本"
                                                        } else {
                                                            " · 本机"
                                                        },
                                                    style =
                                                        MaterialTheme
                                                            .typography
                                                            .labelSmall,
                                                    color =
                                                        if (
                                                            book.permission ==
                                                            "REVOKED"
                                                        ) {
                                                            MaterialTheme
                                                                .colorScheme
                                                                .error
                                                        } else {
                                                            Color.Gray
                                                        }
                                                )
                                            }
                                        },
                                        onClick = {
                                            bookMenuExpanded =
                                                false

                                            if (
                                                book.id !=
                                                currentBook.id
                                            ) {
                                                onSwitchBook(
                                                    book.id
                                                )
                                            }
                                        }
                                    )
                                }

                            if (
                                systemRole ==
                                "SUPERADMIN" ||
                                currentBook.permission == "OWNER"
                            ) {
                                HorizontalDivider()

                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "⚙ 账本管理"
                                        )
                                    },
                                    onClick = {
                                        bookMenuExpanded =
                                            false
                                        onBookManage()
                                    }
                                )
                            }
                        }
                    }

                    Column(
                        Modifier
                            .align(
                                Alignment.CenterStart
                            )
                            .padding(
                                top = 15.dp
                            )
                    ) {
                        Text(
                            headerSettings
                                .title,
                            color = Color.White,
                            fontSize = 24.sp,
                            fontWeight =
                                FontWeight.Bold,
                            maxLines = 1
                        )

                        if (
                            headerSettings
                                .subtitle
                                .isNotBlank()
                        ) {
                            Spacer(
                                Modifier.height(
                                    6.dp
                                )
                            )

                            Text(
                                headerSettings
                                    .subtitle,
                                color =
                                    Color.White
                                        .copy(
                                            alpha =
                                                0.9f
                                        ),
                                fontSize = 13.sp,
                                maxLines = 2
                            )
                        }
                    }

                    Column(
                        Modifier.align(
                            Alignment.BottomEnd
                        ),
                        horizontalAlignment =
                            Alignment.End
                    ) {
                        if (
                            headerSettings
                                .showFruitIcons
                        ) {
                            Text(
                                "🍇🍊🍓",
                                fontSize = 19.sp
                            )
                        }

                        if (
                            headerSettings
                                .tagline
                                .isNotBlank()
                        ) {
                            Text(
                                headerSettings
                                    .tagline,
                                color =
                                    Color.White
                                        .copy(
                                            alpha =
                                                0.92f
                                        ),
                                fontSize = 12.sp,
                                maxLines = 1
                            )
                        }
                    }

                    TextButton(
                        onClick =
                            onHeaderSettings,
                        modifier =
                            Modifier.align(
                                Alignment.TopEnd
                            )
                    ) {
                        Text(
                            "⚙",
                            color = Color.White,
                            fontSize = 24.sp
                        )
                    }
                }
            }
        }

        item {
            HomeWeatherCard(
                db = db,
                dataVersion = dataVersion,
                currentBook = currentBook,
                cloudSyncManager = cloudSyncManager,
                onOpenDetail = onOpenWeather,
                onStoreResolved = { adviceStoreId = it }
            )
        }

        item {
            HomeBusinessAdviceCard(
                db = db,
                dataVersion = dataVersion,
                currentBook = currentBook,
                cloudSyncManager = cloudSyncManager,
                storeId = adviceStoreId,
                onOpenDetail = onOpenBusinessAdvice
            )
        }

        item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .offset(y = (-4).dp)
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color.White
                    )
                ) {
                    Column(
                        Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CompactDateNavigator(
                            label = null,
                            date = selectedDateString,
                            modifier = Modifier.fillMaxWidth(),
                            chineseDisplay = true,
                            showWeekday = true
                        ) {
                            selectedDate =
                                runCatching { LocalDate.parse(it) }
                                    .getOrElse { LocalDate.now() }
                        }

                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("今日经营", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            PageSyncStatus(pageTitle = "首页")
                        }
                        Text(
                            "📍 $locationText",
                            maxLines = 1,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (records.isEmpty()) Color.Gray else Color.DarkGray
                        )

                        if (canViewBusiness) {
                            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                DashboardTile(
                                    "💰",
                                    "营业额",
                                    money(summary.revenue),
                                    SoftOrange,
                                    Modifier.weight(1f)
                                )
                                DashboardTile(
                                    "🛒",
                                    "采购金额",
                                    money(summary.purchaseCost),
                                    SoftBlue,
                                    Modifier.weight(1f)
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                DashboardTile(
                                    "✅",
                                    "实际利润",
                                    money(summary.profit),
                                    SoftGreen,
                                    Modifier.weight(1f)
                                )
                                DashboardTile(
                                    "🧮",
                                    "预估经营利润",
                                    when {
                                        !operatingAnalysis.inventoryComplete -> "待盘点"
                                        !operatingAnalysis.costComplete -> {
                                            val missing = operatingAnalysis.items.count { row ->
                                                !row.costAvailable && (
                                                    row.availableQuantity > 0.000001 ||
                                                        row.lossQuantity > 0.000001 ||
                                                        row.remainingQuantity > 0.000001 ||
                                                        row.consumedQuantity > 0.000001
                                                )
                                            }
                                            "缺成本${missing}种"
                                        }
                                        else -> operatingAnalysis.operatingProfit?.let { money(it) } ?: "—"
                                    },
                                    SoftPurple,
                                    Modifier.weight(1f),
                                    caption = "按库存消耗估算"
                                )
                            }
    
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = Color(0xFFF7F9FC)
                                )
                            ) {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "👥 客户",
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(Modifier.weight(1f))
                                    Text(
                                        "${summary.customers} 人",
                                        fontWeight = FontWeight.Bold,
                                        color = BrandGreen
                                    )
                                    if (records.isNotEmpty()) {
                                        Spacer(Modifier.width(12.dp))
                                        Text(
                                            "新 ${records.sumOf { it.newCustomer }}  ·  " +
                                                "老 ${records.sumOf { it.oldCustomer }}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color.Gray
                                        )
                                    }
                                }
                            }
                            }

                        val availableQuickActions =
                            HomeQuickAction.entries.filter {
                                homeQuickActionAllowed(it, currentBook, systemRole)
                            }
                        val savedQuickActionKeys =
                            ledgerUiSettingsManager.loadHomeQuickActions(currentBook.id)
                        val quickActions =
                            savedQuickActionKeys.mapNotNull { key ->
                                HomeQuickAction.entries.firstOrNull { it.name == key }
                            }.filter { it in availableQuickActions }
                                .take(8)

                        if (availableQuickActions.isNotEmpty()) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "快捷操作",
                                    modifier = Modifier.weight(1f),
                                    fontWeight = FontWeight.Bold
                                )
                                TextButton(
                                    onClick = { onOpenMore(MorePage.HOME_QUICK_ACTIONS) },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                                ) {
                                    Text("编辑", fontSize = 12.sp)
                                }
                            }

                            if (quickActions.isEmpty()) {
                                Text(
                                    "暂未设置首页快捷操作",
                                    color = Color.Gray,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            } else {
                                quickActions.chunked(4).forEachIndexed { rowIndex, rowActions ->
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        rowActions.forEachIndexed { columnIndex, action ->
                                            val colorIndex = (rowIndex * 4 + columnIndex) % 4
                                            QuickActionTile(
                                                action.icon,
                                                action.label,
                                                { onOpenMore(action.target) },
                                                Modifier.weight(1f),
                                                listOf(SoftGreen, SoftOrange, SoftBlue, SoftPurple)[colorIndex]
                                            )
                                        }
                                        repeat(4 - rowActions.size) {
                                            Spacer(Modifier.weight(1f))
                                        }
                                    }
                                    if (rowIndex != quickActions.chunked(4).lastIndex) {
                                        Spacer(Modifier.height(5.dp))
                                    }
                                }
                            }
                        }

                        if (
                            canViewPurchaseActivity ||
                            canEditPurchasePlan
                        ) {
                            Card(
                                onClick = onPurchaseActivity,
                                colors =
                                    CardDefaults.cardColors(
                                        containerColor = Color(0xFFF1FAF5)
                                    ),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 15.dp, vertical = 13.dp),
                                    verticalArrangement = Arrangement.spacedBy(9.dp)
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("🛒", fontSize = 25.sp)
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            "今日协作采购",
                                            modifier = Modifier.weight(1f),
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            if (collaborationItems.isEmpty()) {
                                                "›"
                                            } else {
                                                "$collaborationCompleted/${collaborationItems.size}  ›"
                                            },
                                            color = BrandGreen,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 17.sp
                                        )
                                    }

                                    if (collaborationItems.isEmpty()) {
                                        Text(
                                            "今日暂无采购计划，点击进入添加采购商品",
                                            color = Color.Gray,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    } else {
                                        Row(
                                            Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            Column(Modifier.weight(1f)) {
                                                Text(
                                                    "计划金额",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = Color.Gray
                                                )
                                                Text(
                                                    money(collaborationPlanAmount),
                                                    fontSize = 21.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                            Column(Modifier.weight(1f)) {
                                                Text(
                                                    "已完成金额",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = Color.Gray
                                                )
                                                Text(
                                                    money(collaborationCompletedAmount),
                                                    fontSize = 21.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = BrandGreen
                                                )
                                            }
                                        }

                                        Row(
                                            Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            Text(
                                                "已采购 $collaborationCompleted 种",
                                                modifier = Modifier.weight(1f),
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Text(
                                                "剩余 $collaborationRemaining 种",
                                                modifier = Modifier.weight(1f),
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }

                                        val progress =
                                            if (collaborationItems.isNotEmpty()) {
                                                collaborationCompleted.toFloat() / collaborationItems.size.toFloat()
                                            } else {
                                                0f
                                            }
                                        Box(
                                            Modifier
                                                .fillMaxWidth()
                                                .height(8.dp)
                                                .clip(RoundedCornerShape(99.dp))
                                                .background(Color(0xFFD8E9DF))
                                        ) {
                                            Box(
                                                Modifier
                                                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                                                    .fillMaxHeight()
                                                    .background(BrandGreen)
                                            )
                                        }

                                        val pendingPreview =
                                            collaborationItems
                                                .filter { it.status == 0 }
                                                .take(3)

                                        if (pendingPreview.isEmpty()) {
                                            Text(
                                                "✓ 今日采购已全部完成",
                                                color = BrandGreen,
                                                fontWeight = FontWeight.Bold
                                            )
                                        } else {
                                            Text(
                                                "待采购",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = Color.Gray
                                            )
                                            pendingPreview.forEach { item ->
                                                Row(Modifier.fillMaxWidth()) {
                                                    Text(
                                                        "○ ${item.fruitName}",
                                                        modifier = Modifier.weight(1f),
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                    Text(
                                                        "${fmt(item.quantity)}${item.unit}" +
                                                            if (item.estimatedAmount > 0) {
                                                                " · ${money(item.estimatedAmount)}"
                                                            } else {
                                                                ""
                                                            },
                                                        color = Color.Gray,
                                                        style = MaterialTheme.typography.bodySmall
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (
                            canViewStats &&
                            canViewBusiness
                        ) {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = Color(0xFFFCFCFD)
                                )
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            "最近7天营业额趋势",
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.weight(1f)
                                        )
                                        TextButton(onClick = onStats) {
                                            Text("查看详情 ›")
                                        }
                                    }
    
                                    RevenueTrendChart(trend)
    
                                    Row(Modifier.fillMaxWidth()) {
                                        trend.forEach { (d, _) ->
                                            Column(
                                                modifier = Modifier.weight(1f),
                                                horizontalAlignment = Alignment.CenterHorizontally
                                            ) {
                                                Text(
                                                    "${d.monthValue}/${d.dayOfMonth}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = Color.Gray,
                                                    fontSize = 9.sp,
                                                    maxLines = 1
                                                )
                                            }
                                        }
                                    }
                                }
                            }
    
                            if (rankings.isNotEmpty()) {
                                Text(
                                    "${selectedDate.monthValue}月营业额前三",
                                    fontWeight = FontWeight.Bold
                                )
    
                                rankings.take(3).forEachIndexed { index, r ->
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            "${index + 1}.",
                                            Modifier.width(28.dp),
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            r.storeName,
                                            Modifier.weight(1f)
                                        )
                                        Text(
                                            money(r.revenue),
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }                        }

                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardTile(
    icon: String,
    title: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    caption: String = ""
) {
    val tileModifier =
        if (onClick != null) modifier.clickable(onClick = onClick) else modifier
    Card(tileModifier, colors = CardDefaults.cardColors(containerColor = color), shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(icon, fontSize = 21.sp)
            Spacer(Modifier.width(9.dp))
            Column {
                Text(title, style = MaterialTheme.typography.bodySmall, color = Color.DarkGray)
                Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (caption.isNotBlank()) {
                    Text(caption, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                }
            }
        }
    }
}

@Composable
private fun QuickActionTile(icon: String, title: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color) {
    Card(onClick = onClick, modifier = modifier, colors = CardDefaults.cardColors(containerColor = color), shape = RoundedCornerShape(14.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(icon, fontSize = 22.sp)
            Spacer(Modifier.height(4.dp))
            Text(title, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

@Composable
private fun UtilityTile(icon: String, title: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(onClick = onClick, modifier = modifier, colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)), shape = RoundedCornerShape(12.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 9.dp, horizontal = 3.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(icon, fontSize = 20.sp)
            Text(title, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

@Composable
private fun RevenueTrendChart(values: List<Pair<LocalDate, Double>>) {
    val maxValue = (values.maxOfOrNull { it.second } ?: 0.0).coerceAtLeast(1.0)
    Canvas(Modifier.fillMaxWidth().height(132.dp).padding(horizontal = 8.dp, vertical = 8.dp)) {
        val left = 16f
        val right = size.width - 16f
        val top = 25f
        val bottom = size.height - 10f
        repeat(3) { i ->
            val y = top + (bottom - top) * i / 2f
            drawLine(Color(0xFFE7E9ED), Offset(left, y), Offset(right, y), strokeWidth = 1.2f)
        }
        if (values.isNotEmpty()) {
            val pts = values.mapIndexed { index, item ->
                val x = if (values.size == 1) (left + right) / 2f else left + (right - left) * index / (values.size - 1).toFloat()
                val y = bottom - ((item.second / maxValue).toFloat() * (bottom - top))
                Offset(x, y)
            }
            pts.zipWithNext().forEach { (a, b) -> drawLine(BrandGreen, a, b, strokeWidth = 4f) }
            val paint = AndroidPaint().apply {
                isAntiAlias = true
                color = BrandGreen.toArgb()
                textAlign = AndroidPaint.Align.CENTER
                textSize = 10.sp.toPx()
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            pts.forEachIndexed { index, p ->
                drawCircle(Color.White, radius = 7f, center = p)
                drawCircle(BrandGreen, radius = 4.5f, center = p)
                drawContext.canvas.nativeCanvas.drawText(
                    fmt(values[index].second),
                    p.x,
                    (p.y - 10f).coerceAtLeast(paint.textSize),
                    paint
                )
            }
        }
    }
}

private fun chineseWeekday(date: LocalDate): String = when (date.dayOfWeek.value) {
    1 -> "星期一"; 2 -> "星期二"; 3 -> "星期三"; 4 -> "星期四"
    5 -> "星期五"; 6 -> "星期六"; else -> "星期日"
}

@Composable
private fun InventoryScreen(
    db: AppDatabase,
    dataVersion: Int,
    workDate: String,
    onWorkDateChange: (String) -> Unit,
    onChanged: () -> Unit
) {
    val date = workDate
    var message by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }
    val remainingInputs = remember { mutableStateMapOf<String, String>() }
    val lossInputs = remember { mutableStateMapOf<String, String>() }
    var costEditItem by remember { mutableStateOf<InventoryDayItemRecord?>(null) }
    var costEditValue by remember { mutableStateOf("") }
    var retailEditItem by remember { mutableStateOf<InventoryDayItemRecord?>(null) }
    var retailEditValue by remember { mutableStateOf("") }

    val inventoryItems = remember(dataVersion, date) {
        db.getInventoryDayItems(date)
    }

    fun itemKey(item: InventoryDayItemRecord): String =
        "${item.fruitId}|${item.unit}"

    LaunchedEffect(dataVersion, date) {
        val latest = db.getInventoryDayItems(date)
        remainingInputs.clear()
        lossInputs.clear()
        latest.forEach { item ->
            // 库存盘点的两个可编辑字段必须明确显示 0，而不是空白占位。
            remainingInputs[itemKey(item)] = if (item.remainingQuantity == 0.0) "0" else fmt(item.remainingQuantity)
            lossInputs[itemKey(item)] = if (item.lossQuantity == 0.0) "0" else fmt(item.lossQuantity)
        }
        message = ""
        isError = false
    }

    val purchasedKinds = inventoryItems.count { it.purchasedQuantity > 0.000001 }
    val carriedKinds = inventoryItems.count {
        it.openingQuantity > 0.000001 && it.purchasedQuantity <= 0.000001
    }
    val savedCount = inventoryItems.count { it.saved }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        item {
            BusinessDateHeader(
                pageTitle = "库存",
                date = date,
                onDate = onWorkDateChange
            )
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                MiniSummaryCard(
                    "库存商品",
                    "${inventoryItems.size}种",
                    Modifier.weight(1f),
                    SoftGreen
                )
                MiniSummaryCard(
                    "今日采购",
                    "${purchasedKinds}种",
                    Modifier.weight(1f),
                    SoftBlue
                )
                MiniSummaryCard(
                    "仅结转",
                    "${carriedKinds}种",
                    Modifier.weight(1f),
                    SoftOrange
                )
            }
            if (inventoryItems.isNotEmpty()) {
                Text(
                    if (savedCount == inventoryItems.size) {
                        "本日已盘点 ${savedCount}/${inventoryItems.size} 项"
                    } else {
                        "本日尚未完整盘点 · 已保存 ${savedCount}/${inventoryItems.size} 项"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (savedCount == inventoryItems.size) BrandGreen else Color.Gray,
                    modifier = Modifier.padding(top = 5.dp)
                )
            }
        }

        if (inventoryItems.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF7F8FA)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("📦", fontSize = 30.sp)
                        Spacer(Modifier.height(5.dp))
                        Text("当天没有可盘点商品", fontWeight = FontWeight.Bold)
                        Text(
                            "完成采购后会自动出现；历史剩余库存也会自动结转。",
                            color = Color.Gray,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        } else {
            items(
                inventoryItems,
                key = { "${it.fruitId}|${it.unit}" }
            ) { item ->
                val key = itemKey(item)
                val input = remainingInputs[key].orEmpty()
                val lossInput = lossInputs[key].orEmpty()
                // 空白也按默认 0 处理；界面正常情况下会直接显示 0。
                val remaining = input.toDoubleOrNull() ?: 0.0
                val loss = lossInput.toDoubleOrNull() ?: 0.0
                val available = item.openingQuantity + item.purchasedQuantity
                val sold = available - remaining - loss

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(11.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                item.fruitName,
                                modifier = Modifier.weight(1f),
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                maxLines = 1
                            )
                            Text(
                                item.effectiveUnitCost?.let { "成本 ${money(it)}/${item.unit}" } ?: "暂无单价",
                                modifier = Modifier.clickable {
                                    costEditItem = item
                                    costEditValue = cleanNumber(item.effectiveUnitCost ?: 0.0)
                                }.padding(horizontal = 5.dp),
                                color = if (item.effectiveUnitCost != null) Color.DarkGray else Color(0xFFB26A00),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1
                            )
                            Text(
                                item.retailPricePerJin?.let { "今日零售 ${money(it)}/斤" } ?: "今日零售 暂无",
                                modifier = Modifier.clickable {
                                    retailEditItem = item
                                    retailEditValue = cleanNumber(item.retailPricePerJin ?: 0.0)
                                }.padding(horizontal = 5.dp),
                                color = BrandGreen,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1
                            )
                            Text(
                                buildString {
                                    append(
                                        if (item.purchasedQuantity > 0.000001) {
                                            "今日有采购"
                                        } else {
                                            "历史结转"
                                        }
                                    )
                                    if (item.saved) append(" · 已保存")
                                },
                                color =
                                    if (item.purchasedQuantity > 0.000001 || item.saved) {
                                        BrandGreen
                                    } else {
                                        Color.Gray
                                    },
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            CompactReadOnlyField(
                                "结转库存",
                                "${fmt(item.openingQuantity)}${item.unit}",
                                Modifier.weight(1f)
                            )
                            CompactReadOnlyField(
                                "今日采购",
                                "${fmt(item.purchasedQuantity)}${item.unit}",
                                Modifier.weight(1f)
                            )
                            CompactReadOnlyField(
                                "可售合计",
                                "${fmt(available)}${item.unit}",
                                Modifier.weight(1f)
                            )
                            PurchaseDefaultNumberField(
                                label = "损耗",
                                value = lossInput,
                                defaultValue = "0",
                                stateKey = "inventory-loss-$date-$key",
                                onValue = { lossInputs[key] = it },
                                modifier = Modifier.weight(1f)
                            )
                            PurchaseDefaultNumberField(
                                label = "剩余库存",
                                value = input,
                                defaultValue = "0",
                                stateKey = "inventory-remaining-$date-$key",
                                onValue = { remainingInputs[key] = it },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        if (sold < -0.000001) {
                            Text(
                                "损耗＋剩余库存超过可售合计 ${fmt(-sold)}${item.unit}，请核对数据",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            item {
                Button(
                    onClick = {
                        val invalid = inventoryItems.firstOrNull { item ->
                            val remaining = remainingInputs[itemKey(item)]?.toDoubleOrNull() ?: 0.0
                            val loss = lossInputs[itemKey(item)]?.toDoubleOrNull() ?: 0.0
                            val available = item.openingQuantity + item.purchasedQuantity
                            remaining < 0 ||
                                loss < 0 ||
                                remaining + loss > available + 0.000001
                        }
                        if (invalid != null) {
                            message = "${invalid.fruitName} 的损耗和剩余库存不能为负数，且合计不能超过可售库存"
                            isError = true
                        } else {
                            val saved =
                                db.saveInventoryDay(
                                    date = date,
                                    items = inventoryItems.map { item ->
                                        InventorySaveInput(
                                            fruitId = item.fruitId,
                                            fruitName = item.fruitName,
                                            unit = item.unit,
                                            lossQuantity = lossInputs[itemKey(item)]?.toDoubleOrNull() ?: 0.0,
                                            remainingQuantity = remainingInputs[itemKey(item)]?.toDoubleOrNull() ?: 0.0
                                        )
                                    }
                                )
                            if (saved) {
                                message = "库存盘点已保存"
                                isError = false
                                onChanged()
                            } else {
                                message = "库存保存失败，请检查数据后重试"
                                isError = true
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(44.dp)
                ) {
                    Text("保存库存")
                }
            }
        }

        if (message.isNotBlank()) {
            item {
                Text(
                    message,
                    color = if (isError) MaterialTheme.colorScheme.error else BrandGreen,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        item {
            Text(
                "说明：损耗默认0；无库存时剩余库存默认0。成本优先读取真实非零采购价，没有真实价格才使用人工参考成本；今日零售价按商品＋日期独立保存。",
                color = Color.Gray,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(vertical = 6.dp)
            )
        }
    }

    costEditItem?.let { item ->
        AlertDialog(
            onDismissRequest = { costEditItem = null },
            title = { Text("参考成本 · ${item.fruitName}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("真实非零采购价优先；这里保存的是人工参考成本，不修改历史采购记录。", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    CompactNumberField("成本（元/${item.unit}）", costEditValue, { costEditValue = it }, Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val value = costEditValue.toDoubleOrNull() ?: 0.0
                    if (db.saveManualCostReference(item.fruitId, item.fruitName, item.unit, value)) {
                        costEditItem = null; onChanged()
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { costEditItem = null }) { Text("取消") } }
        )
    }
    retailEditItem?.let { item ->
        AlertDialog(
            onDismissRequest = { retailEditItem = null },
            title = { Text("今日零售价 · ${item.fruitName}") },
            text = { CompactNumberField("零售价（元/斤）", retailEditValue, { retailEditValue = it }, Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(onClick = {
                    val value = retailEditValue.toDoubleOrNull() ?: 0.0
                    if (db.saveDailyRetailPrice(date, item.fruitId, item.fruitName, value)) {
                        retailEditItem = null; onChanged()
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { retailEditItem = null }) { Text("取消") } }
        )
    }

}

@Composable
private fun PurchasePlanScreen(db: AppDatabase, dataVersion: Int, onChanged: () -> Unit) {
    var date by remember { mutableStateOf(LocalDate.now().plusDays(1).toString()) }
    val fruits = remember(dataVersion) { db.getFruits() }

    var fruitId by remember { mutableStateOf<Long?>(null) }
    val fruit = fruits.firstOrNull { it.id == fruitId }
    var fruitMenu by remember { mutableStateOf(false) }
    var unit by remember { mutableStateOf("件") }
    var unitMenu by remember { mutableStateOf(false) }
    var quantity by remember { mutableStateOf("") }
    var itemRemark by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var addFruitDialog by remember { mutableStateOf(false) }
    var deletePlanConfirm by remember { mutableStateOf(false) }

    val draft = remember { mutableStateListOf<PurchasePlanLineInput>() }
    val saved = remember(dataVersion, date) { db.getPurchasePlan(date) }

    LaunchedEffect(dataVersion, date) {
        val current = db.getPurchasePlan(date)
        draft.clear()
        if (current != null) {
            note = current.plan.note
            current.items.forEach { item ->
                val f = fruits.firstOrNull { it.id == item.fruitId }
                    ?: FruitOption(item.fruitId, item.fruitName, item.unit)
                draft.add(
                    PurchasePlanLineInput(
                        fruit = f,
                        quantity = item.quantity,
                        unit = item.unit,
                        remark = item.remark,
                        status = item.status,
                        unitWeightJin = item.unitWeightJin
                    )
                )
            }
        } else {
            note = ""
        }
        fruitId = fruits.firstOrNull { it.name == "总价" }?.id
        quantity = ""
        itemRemark = ""
        unit = "件"
        message = ""
    }

    val q = quantity.toDoubleOrNull() ?: 0.0

    val statusSource = saved?.items
    val totalCount = statusSource?.size ?: draft.size
    val pendingCount = statusSource?.count { it.status == 0 } ?: draft.count { it.status == 0 }
    val purchasedCount = statusSource?.count { it.status == 1 } ?: draft.count { it.status == 1 }
    val cancelledCount = statusSource?.count { it.status == 2 } ?: draft.count { it.status == 2 }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            PageHeader("采购计划", "每种商品独立管理：待采购、已采购、取消")
        }

        item {
            CompactDateSelector("采购日期", date, Modifier.fillMaxWidth(), showWeekday = true) { date = it }
        }

        if (totalCount > 0) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F8F6))) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("总计 $totalCount 项", fontWeight = FontWeight.Bold)
                        Text("待采购 $pendingCount", color = Color(0xFF9A6A00))
                        Text("已采购 $purchasedCount", color = BrandGreen)
                        Text("取消 $cancelledCount", color = Color.Gray)
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                Box(Modifier.weight(1f)) {
                    CompactSelectButton("商品", fruit?.name ?: "总价", Modifier.fillMaxWidth()) {
                        fruitMenu = true
                    }
                    DropdownMenu(expanded = fruitMenu, onDismissRequest = { fruitMenu = false }) {
                        fruits.forEach { f ->
                            DropdownMenuItem(
                                text = { Text(f.name) },
                                onClick = {
                                    fruitId = f.id
                                    unit = "件"
                                    fruitMenu = false
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("＋新增商品") },
                            onClick = {
                                fruitMenu = false
                                addFruitDialog = true
                            }
                        )
                    }
                }

                Box(Modifier.width(82.dp)) {
                    CompactSelectButton("单位", unit, Modifier.fillMaxWidth()) { unitMenu = true }
                    DropdownMenu(expanded = unitMenu, onDismissRequest = { unitMenu = false }) {
                        listOf("箱", "筐", "件", "袋").forEach { u ->
                            DropdownMenuItem(
                                text = { Text(u) },
                                onClick = {
                                    unit = u
                                    unitMenu = false
                                }
                            )
                        }
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                CompactNumberField("计划数量", quantity, { quantity = it }, Modifier.weight(0.7f))
                CompactTextField("单项备注（可选）", itemRemark, { itemRemark = it }, Modifier.weight(1.3f))
            }
        }

        item {
            Button(
                onClick = {
                    if (fruit == null || q <= 0) {
                        message = "请选择商品并填写正确数量"
                    } else {
                        val existingIndex =
                            draft.indexOfFirst { it.fruit.id == fruit.id && it.unit == unit }
                        val oldStatus =
                            if (existingIndex >= 0) draft[existingIndex].status else 0
                        val newLine = PurchasePlanLineInput(
                            fruit = fruit,
                            quantity = q,
                            unit = unit,
                            remark = itemRemark.trim(),
                            status = oldStatus,
                            unitWeightJin = db.getLatestUnitWeightJin(fruit.id, unit, date) ?: 0.0
                        )
                        if (existingIndex >= 0) {
                            draft[existingIndex] = newLine
                            message = "已更新 ${fruit.name}"
                        } else {
                            draft.add(newLine)
                            message = "已加入 ${fruit.name}"
                        }
                        fruitId = fruits.firstOrNull { it.name == "总价" }?.id
                        quantity = ""
                        itemRemark = ""
                        unit = "件"
                    }
                },
                modifier = Modifier.fillMaxWidth().height(42.dp)
            ) {
                Text("＋ 添加采购商品")
            }
        }

        if (draft.isNotEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("采购清单", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("${draft.size} 项", color = BrandGreen)
                }
            }

            items(draft.indices.toList()) { index ->
                val line = draft[index]
                val savedItem = saved?.items?.firstOrNull {
                    it.fruitId == line.fruit.id && it.unit == line.unit
                }
                val effectiveStatus = savedItem?.status ?: line.status

                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(11.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(line.fruit.name, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${fmt(line.quantity)}${line.unit}" +
                                        if (line.remark.isBlank()) "" else " · ${line.remark}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.Gray
                                )
                            }
                            Text(
                                when (effectiveStatus) {
                                    1 -> "已采购"
                                    2 -> "已取消"
                                    else -> "待采购"
                                },
                                color = when (effectiveStatus) {
                                    1 -> BrandGreen
                                    2 -> Color.Gray
                                    else -> Color(0xFF9A6A00)
                                },
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = {
                                    fruitId = line.fruit.id
                                    unit = line.unit
                                    quantity = cleanNumber(line.quantity)
                                    itemRemark = line.remark
                                }
                            ) { Text("修改") }

                            if (savedItem != null) {
                                when (effectiveStatus) {
                                    0 -> {
                                        TextButton(
                                            onClick = {
                                                db.updatePurchasePlanItemStatus(savedItem.id, 1)
                                                message = "${line.fruit.name} 已完成采购"
                                                onChanged()
                                            }
                                        ) { Text("完成") }
                                        TextButton(
                                            onClick = {
                                                db.updatePurchasePlanItemStatus(savedItem.id, 2)
                                                message = "${line.fruit.name} 已取消"
                                                onChanged()
                                            }
                                        ) { Text("取消") }
                                    }
                                    1, 2 -> {
                                        TextButton(
                                            onClick = {
                                                db.updatePurchasePlanItemStatus(savedItem.id, 0)
                                                message = "${line.fruit.name} 已恢复待采购"
                                                onChanged()
                                            }
                                        ) { Text("待采购") }
                                    }
                                }
                            }

                            TextButton(onClick = { draft.removeAt(index) }) { Text("移除") }
                        }
                    }
                }
            }

            item {
                CompactTextField("整单备注（可选）", note, { note = it }, Modifier.fillMaxWidth())

                Button(
                    onClick = {
                        if (draft.isEmpty()) {
                            message = "采购清单不能为空"
                        } else {
                            val id = db.savePurchasePlan(date, draft.toList(), note)
                            if (id > 0) {
                                message = "采购计划已保存"
                                onChanged()
                            } else {
                                message = "采购计划保存失败"
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                ) {
                    Text(if (saved == null) "保存采购计划" else "保存采购计划修改")
                }
            }
        } else {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF7F8FA))) {
                    Text(
                        "暂未添加采购商品。选择商品和数量后添加。",
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        color = Color.Gray
                    )
                }
            }
        }

        if (saved != null) {
            item {
                TextButton(
                    onClick = { deletePlanConfirm = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("删除这张采购计划")
                }
            }
        }

        if (message.isNotBlank()) {
            item { Text(message, color = BrandGreen, style = MaterialTheme.typography.bodySmall) }
        }

        item {
            HorizontalDivider()
            Text(
                "最近采购计划",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        val recent = db.getRecentPurchasePlans(12)
        if (recent.isEmpty()) {
            item { Text("暂无历史采购计划", color = Color.Gray) }
        } else {
            items(recent, key = { it.plan.id }) { detail ->
                val p = detail.items.count { it.status == 0 }
                val done = detail.items.count { it.status == 1 }
                val cancel = detail.items.count { it.status == 2 }

                Card(
                    onClick = { date = detail.plan.planDate },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(detail.plan.planDate, fontWeight = FontWeight.Bold)
                            Text(
                                detail.items.joinToString("、", limit = 3, truncated = "…") {
                                    "${it.fruitName}${fmt(it.quantity)}${it.unit}"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                        }
                        Text(
                            if (p > 0) "待 $p · 已 $done · 取消 $cancel"
                            else if (done > 0) "已完成 $done"
                            else "已取消",
                            color = BrandGreen,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }

    if (addFruitDialog) {
        AddFruitDialog(
            onDismiss = { addFruitDialog = false },
            onSave = { name, defaultUnit ->
                val id = db.addFruit(name, defaultUnit)
                addFruitDialog = false
                if (id > 0) fruitId = id
                onChanged()
            }
        )
    }

    if (deletePlanConfirm && saved != null) {
        ConfirmDelete(
            "删除 ${saved.plan.planDate} 的采购计划？不会影响实际进货记录。",
            { deletePlanConfirm = false }
        ) {
            db.deletePurchasePlan(saved.plan.id)
            draft.clear()
            note = ""
            deletePlanConfirm = false
            message = "采购计划已删除"
            onChanged()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PurchaseScreen(
    db: AppDatabase,
    dataVersion: Int,
    workDate: String,
    onWorkDateChange: (String) -> Unit,
    onChanged: () -> Unit,
    protectHistoricalAction:
        (String, String, () -> Unit) -> Unit,
    onOpenHistory: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    val date = workDate
    val fruits = remember(dataVersion) { db.getFruits() }
    val partners = remember(dataVersion) { db.getPartners() }

    // 新增采购时采购人属于“每一种商品”，不再使用整页单一采购人。
    // 只有编辑历史正式采购单时，才保留整单采购人选择。
    var editBuyerId by remember { mutableStateOf<Long?>(null) }
    var historicalBuyerName by remember { mutableStateOf("") }
    var editBuyerMenu by remember { mutableStateOf(false) }
    var remark by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }

    var addFruitDialog by remember { mutableStateOf(false) }
    var pendingFruitRowId by remember { mutableStateOf<Long?>(null) }
    var nextRowId by remember { mutableLongStateOf(System.currentTimeMillis()) }

    fun defaultBuyerId(): Long? = partners.firstOrNull()?.id
    fun defaultBuyerName(): String = partners.firstOrNull()?.name.orEmpty()
    fun defaultProduct(): FruitOption? =
        fruits.firstOrNull { it.name == "总价" } ?: fruits.firstOrNull()

    val rows = remember {
        val product = defaultProduct()
        mutableStateListOf(
            PurchaseDraftRow(
                rowId = 1L,
                fruitId = product?.id,
                fruitNameSnapshot = product?.name.orEmpty(),
                unit = product?.defaultUnit ?: "件",
                quantity = "1",
                unitPrice = "0",
                totalCost = "0",
                buyerId = defaultBuyerId(),
                buyerNameSnapshot = defaultBuyerName(),
                priceSource = PurchasePriceSource.UNIT
            )
        )
    }

    val syncedFingerprints = remember { mutableStateMapOf<Long, String>() }
    var editingOrderId by remember { mutableStateOf<Long?>(null) }
    var deleteOrder by remember { mutableStateOf<PurchaseOrderDetail?>(null) }
    var historyActionDetail by remember { mutableStateOf<PurchaseOrderDetail?>(null) }
    var historyActionFruitName by remember { mutableStateOf("") }
    var historyEditTarget by remember {
        mutableStateOf<PurchaseHistoryEditTarget?>(null)
    }
    var editingPlanItemId by remember { mutableStateOf<Long?>(null) }
    var planActionItem by remember { mutableStateOf<PurchasePlanItemRecord?>(null) }
    var completeDialogItem by remember { mutableStateOf<PurchasePlanItemRecord?>(null) }
    var completeDialogEditing by remember { mutableStateOf(false) }
    var restoreCompletedItem by remember { mutableStateOf<PurchasePlanItemRecord?>(null) }
    var deleteCollaborationItem by remember { mutableStateOf<PurchasePlanItemRecord?>(null) }

    val history = remember(dataVersion) { db.getRecentPurchaseOrdersByDays(7) }
    val collaborationPlan = remember(dataVersion, date) { db.getPurchasePlan(date) }
    val dayPurchaseOrders =
        remember(dataVersion, date) {
            db.getPurchaseOrdersForDate(date)
        }
    val dayPurchasedTotal =
        remember(dayPurchaseOrders) {
            dayPurchaseOrders.sumOf { it.order.totalCost }
        }
    val dayPlannedTotal =
        remember(collaborationPlan) {
            collaborationPlan
                ?.items
                .orEmpty()
                .filter { it.status != 2 }
                .sumOf { it.estimatedAmount }
        }
    val latestInventorySnapshot =
        remember(dataVersion, date) {
            db.getLatestInventorySnapshotBefore(date)
        }
    val inventoryByProductUnit =
        remember(latestInventorySnapshot) {
            latestInventorySnapshot
                ?.items
                .orEmpty()
                .associateBy {
                    it.fruitId to it.unit
                }
        }
    var purchaseFormExpanded by remember(date) {
        // 顶部“新增采购”位置固定；即使当天没有记录，也不再自动展开录入区。
        mutableStateOf(false)
    }
    var completedPurchasesExpanded by remember(date) {
        mutableStateOf(false)
    }
    var batchCompleteItems by remember(date) {
        mutableStateOf<List<PurchasePlanItemRecord>>(emptyList())
    }
    var batchCompleteIndex by remember(date) {
        mutableIntStateOf(0)
    }
    var batchCompletedCount by remember(date) {
        mutableIntStateOf(0)
    }
    var batchSkippedCount by remember(date) {
        mutableIntStateOf(0)
    }

    val activeEditBuyer = partners.firstOrNull { it.id == editBuyerId }
    val historicalEditBuyer =
        if (editingOrderId != null && editBuyerId != null && activeEditBuyer == null) {
            PartnerOption(
                editBuyerId!!,
                historicalBuyerName.ifBlank {
                    db.getPartnerByIdIncludingDeleted(editBuyerId!!)?.name ?: "已删除合伙人"
                }
            )
        } else {
            null
        }
    val editBuyer = activeEditBuyer ?: historicalEditBuyer
    val editBuyerDisplayName = when {
        activeEditBuyer != null -> activeEditBuyer.name
        historicalEditBuyer != null -> "${historicalEditBuyer.name}（已删除）"
        else -> "请选择"
    }

    fun newBlankRow(): PurchaseDraftRow {
        val product = defaultProduct()
        return PurchaseDraftRow(
            rowId = nextRowId++,
            fruitId = product?.id,
            fruitNameSnapshot = product?.name.orEmpty(),
            unit = product?.defaultUnit ?: "件",
            quantity = "1",
            unitPrice = "0",
            totalCost = "0",
            buyerId = defaultBuyerId(),
            buyerNameSnapshot = defaultBuyerName(),
            priceSource = PurchasePriceSource.UNIT
        )
    }

    fun rowFingerprint(row: PurchaseDraftRow): String =
        listOf(
            row.fruitId?.toString().orEmpty(),
            row.quantity,
            row.unit,
            row.unitWeight,
            row.unitPrice,
            row.totalCost,
            row.buyerId?.toString().orEmpty()
        ).joinToString("|")

    fun resetRowsToOneBlank() {
        rows.clear()
        syncedFingerprints.clear()
        rows.add(newBlankRow())
    }

    fun updateRow(rowId: Long, updated: PurchaseDraftRow) {
        val index = rows.indexOfFirst { it.rowId == rowId }
        if (index >= 0) rows[index] = updated
    }

    fun isDefaultEmptyRow(row: PurchaseDraftRow): Boolean =
        row.fruitId == defaultProduct()?.id &&
            (row.quantity.isBlank() || row.quantity == "1") &&
            (row.unitPrice.isBlank() || row.unitPrice == "0") &&
            (row.totalCost.isBlank() || row.totalCost == "0")

    fun meaningfulRows(): List<PurchaseDraftRow> =
        rows.filterNot { it.isBlank || isDefaultEmptyRow(it) }

    fun loadRowsFromCollaborationPlan() {
        if (editingOrderId != null) return

        val detail = db.getPurchasePlan(date)
        val planItems =
            detail?.items.orEmpty()
                .filter { it.status != 2 }
                .sortedWith(
                    compareBy<PurchasePlanItemRecord> { it.status }
                        .thenBy { it.buyerName.ifBlank { "未指定采购人" } }
                        .thenBy { it.id }
                )

        rows.clear()
        syncedFingerprints.clear()
        remark = detail?.plan?.note.orEmpty()

        planItems.forEach { item ->
            val quantityValue =
                if (item.status == 1 && item.actualQuantity > 0) item.actualQuantity else item.quantity
            val totalValue =
                if (item.status == 1) item.actualAmount else item.estimatedAmount
            val rememberedPrice =
                if (quantityValue > 0 && totalValue > 0) {
                    totalValue / quantityValue
                } else {
                    db.getLatestPurchaseUnitPrice(
                        fruitId = item.fruitId,
                        unit = item.unit,
                        onOrBeforeDate = date
                    ) ?: 0.0
                }
            val row =
                PurchaseDraftRow(
                    rowId = nextRowId++,
                    planItemId = item.id,
                    fruitId = item.fruitId,
                    fruitNameSnapshot = item.fruitName,
                    unit = item.unit,
                    quantity = purchaseNumber(quantityValue),
                    unitWeight = purchaseNumber(item.unitWeightJin),
                    unitPrice = purchaseNumber(rememberedPrice),
                    totalCost =
                        if (item.status == 1) {
                            purchaseNumber(item.actualAmount)
                        } else {
                            purchaseNumber(item.estimatedAmount)
                        },
                    buyerId = item.buyerId.takeIf { it > 0L },
                    buyerNameSnapshot = item.buyerName,
                    priceSource =
                        if (item.status == 1) PurchasePriceSource.TOTAL
                        else PurchasePriceSource.UNIT
                )
            rows.add(row)
            syncedFingerprints[row.rowId] = rowFingerprint(row)
        }

        if (rows.isEmpty()) rows.add(newBlankRow())
    }

    fun collaborationItemFor(row: PurchaseDraftRow): PurchasePlanItemRecord? {
        val items = collaborationPlan?.items.orEmpty()
        // 只允许已有计划行通过 planItemId 关联协作采购。
        // 新增空白行默认商品可能与已完成商品同名（例如“总价”），
        // 如果按 fruitId 回退匹配，会被误判成“已完成”并从录入区隐藏。
        return row.planItemId
            ?.let { id -> items.firstOrNull { it.id == id } }
    }

    fun importPreviousInventory() {
        if (editingOrderId != null) return

        val snapshot = latestInventorySnapshot
        if (snapshot == null) {
            message = "所选日期之前还没有可导入的库存"
            return
        }

        val existingKeys =
            rows
                .mapNotNull { row ->
                    row.fruitId?.let { it to row.unit }
                }
                .toMutableSet()

        if (
            rows.size == 1 &&
            rows.firstOrNull()?.let { it.isBlank || isDefaultEmptyRow(it) } == true
        ) {
            rows.clear()
            syncedFingerprints.clear()
        }

        var imported = 0
        var skipped = 0

        snapshot.items.forEach { stock ->
            val key = stock.fruitId to stock.unit
            if (key in existingKeys) {
                skipped++
                return@forEach
            }

            val fruit =
                fruits.firstOrNull {
                    it.id == stock.fruitId
                }
            if (fruit == null || fruit.name == "总价") {
                skipped++
                return@forEach
            }

            val rememberedPrice =
                db.getLatestPurchaseUnitPrice(
                    fruitId = stock.fruitId,
                    unit = stock.unit,
                    onOrBeforeDate = date
                ) ?: 0.0

            rows.add(
                PurchaseDraftRow(
                    rowId = nextRowId++,
                    fruitId = stock.fruitId,
                    fruitNameSnapshot = stock.fruitName,
                    unit = stock.unit,
                    quantity = "1",
                    unitWeight = purchaseNumber(db.getLatestUnitWeightJin(stock.fruitId, stock.unit, date) ?: 0.0),
                    unitPrice = purchaseNumber(rememberedPrice),
                    totalCost = purchaseNumber(rememberedPrice),
                    buyerId = defaultBuyerId(),
                    buyerNameSnapshot = defaultBuyerName(),
                    priceSource = PurchasePriceSource.UNIT
                )
            )
            existingKeys += key
            imported++
        }

        if (rows.isEmpty()) {
            rows.add(newBlankRow())
        }

        purchaseFormExpanded = true
        message =
            if (imported > 0) {
                "已导入 ${snapshot.date} 库存 $imported 种" +
                    if (skipped > 0) " · 跳过 $skipped 种已存在/不可用商品" else ""
            } else {
                "库存商品已全部存在于当前计划"
            }
    }

    fun startBatchComplete() {
        val pending =
            db.getPurchasePlan(date)
                ?.items
                .orEmpty()
                .filter { it.status == 0 }

        if (pending.isEmpty()) {
            message = "当前没有待采购商品"
            return
        }

        batchCompleteItems = pending
        batchCompleteIndex = 0
        batchCompletedCount = 0
        batchSkippedCount = 0
        purchaseFormExpanded = false
    }

    fun loadHistoryOrderForEdit(detail: PurchaseOrderDetail) {
        editingOrderId = detail.order.id
        onWorkDateChange(detail.order.date)
        editBuyerId = detail.order.buyerId
        historicalBuyerName = detail.order.buyerName
        remark = detail.order.remark
        rows.clear()
        syncedFingerprints.clear()

        detail.items.forEach { item ->
            rows.add(
                PurchaseDraftRow(
                    rowId = nextRowId++,
                    fruitId = item.fruitId,
                    fruitNameSnapshot = item.fruitName,
                    unit = item.unit,
                    quantity = purchaseNumber(item.quantity),
                    unitWeight = purchaseNumber(item.unitWeightJin),
                    unitPrice = purchaseNumber(item.unitPrice),
                    totalCost = purchaseNumber(item.totalCost),
                    buyerId = detail.order.buyerId,
                    buyerNameSnapshot = detail.order.buyerName,
                    priceSource = PurchasePriceSource.TOTAL
                )
            )
        }
        if (rows.isEmpty()) rows.add(newBlankRow())
        message = "已载入历史采购单，可直接修改"
    }

    fun persistHistoricalEdit(lines: List<PurchaseLineInput>) {
        val editId = editingOrderId ?: return
        val selectedBuyer = editBuyer
        if (selectedBuyer == null) {
            message = "请选择采购人"
            return
        }

        val ok =
            db.updatePurchaseOrder(
                id = editId,
                date = date,
                buyer = selectedBuyer,
                lines = lines,
                remark = remark
            )

        if (ok) {
            editingOrderId = null
            editBuyerId = null
            historicalBuyerName = ""
            remark = ""
            resetRowsToOneBlank()
            message = "采购单已更新"
            onChanged()
        } else {
            message = "保存失败，请检查内容"
        }
    }

    fun saveCollaborationDrafts() {
        val filledRows = meaningfulRows()
        if (filledRows.isEmpty()) {
            message = "请至少填写一种采购商品"
            return
        }

        val latestPlanItems = db.getPurchasePlan(date)?.items.orEmpty()
        val pendingRows =
            filledRows.filterNot { row ->
                val matched =
                    row.planItemId
                        ?.let { id -> latestPlanItems.firstOrNull { it.id == id } }
                        ?: row.fruitId?.let { fruitId ->
                            latestPlanItems.firstOrNull { it.fruitId == fruitId && it.status == 1 }
                        }
                matched?.status == 1
            }

        if (pendingRows.isEmpty()) {
            message = "当前商品都已完成采购"
            return
        }

        // V1.4.7.39：只校验并保存新增/真正修改过的计划行。
        // 未改动的其他待采购商品不再阻断当前商品保存。
        val rowsToSave =
            pendingRows.filter { row ->
                row.planItemId == null ||
                    syncedFingerprints[row.rowId] != rowFingerprint(row)
            }

        if (
            rowsToSave.any {
                val quantity = it.quantity.toDoubleOrNull() ?: 0.0
                val unitPrice = it.unitPrice.toDoubleOrNull() ?: -1.0
                val explicitTotal =
                    if (it.totalCost.isBlank()) null
                    else it.totalCost.toDoubleOrNull()

                it.fruitId == null ||
                    quantity <= 0 ||
                    unitPrice < 0 ||
                    (it.totalCost.isNotBlank() && (explicitTotal == null || explicitTotal < 0))
            }
        ) {
            message = "有商品没有选择商品，或数量/单价填写不正确"
            return
        }

        var savedCount = 0
        rowsToSave.forEach { row ->
            val fruit =
                fruits.firstOrNull { it.id == row.fruitId }
                    ?: row.fruitId?.let { id ->
                        row.fruitNameSnapshot.takeIf { it.isNotBlank() }?.let { name ->
                            FruitOption(id, name, row.unit)
                        }
                    }
                    ?: return@forEach
            val buyer = row.buyerId?.let { id -> partners.firstOrNull { it.id == id } }
            val itemId =
                db.upsertPurchaseDraftToCollaboration(
                    date = date,
                    itemId = row.planItemId,
                    fruit = fruit,
                    quantity = row.quantity.toDouble(),
                    unit = row.unit,
                    unitWeightJin = row.unitWeight.toDoubleOrNull() ?: 0.0,
                    estimatedAmount =
                        row.totalCost
                            .toDoubleOrNull()
                            ?: (
                                row.quantity.toDouble() *
                                    (row.unitPrice.toDoubleOrNull() ?: 0.0)
                                ),
                    buyer = buyer
                )
            if (itemId > 0) {
                savedCount++
                val updated = row.copy(planItemId = itemId)
                updateRow(row.rowId, updated)
                syncedFingerprints[row.rowId] = rowFingerprint(updated)
            }
        }

        if (savedCount == rowsToSave.size) {
            editingPlanItemId = null
            loadRowsFromCollaborationPlan()
            purchaseFormExpanded = false
            message =
                if (rowsToSave.isEmpty()) {
                    "采购计划没有商品变更"
                } else {
                    "采购清单已保存到协作采购，完成采购后才正式入账"
                }
            onChanged()
        } else {
            message = "部分商品保存失败，请检查后重试"
        }
    }


    fun saveEditedPendingPlan(row: PurchaseDraftRow) {
        if (editingOrderId != null || row.planItemId == null || row.planItemId != editingPlanItemId) {
            message = "当前没有可保存的计划修改"
            return
        }

        val quantity = row.quantity.toDoubleOrNull() ?: 0.0
        val unitPrice = row.unitPrice.toDoubleOrNull() ?: -1.0
        val explicitTotal =
            if (row.totalCost.isBlank()) null else row.totalCost.toDoubleOrNull()

        if (
            row.fruitId == null ||
                quantity <= 0 ||
                unitPrice < 0 ||
                (row.totalCost.isNotBlank() && (explicitTotal == null || explicitTotal < 0))
        ) {
            message = "商品、数量或单价填写不正确"
            return
        }

        val fruit =
            fruits.firstOrNull { it.id == row.fruitId }
                ?: row.fruitId?.let { id ->
                    row.fruitNameSnapshot.takeIf { it.isNotBlank() }?.let { name ->
                        FruitOption(id, name, row.unit)
                    }
                }
        if (fruit == null) {
            message = "商品已失效，请重新选择"
            return
        }

        val buyer = row.buyerId?.let { id -> partners.firstOrNull { it.id == id } }
        if (buyer == null) {
            message = "请选择采购人"
            return
        }

        val amount = explicitTotal ?: (quantity * unitPrice)
        val itemId =
            db.upsertPurchaseDraftToCollaboration(
                date = date,
                itemId = row.planItemId,
                fruit = fruit,
                quantity = quantity,
                unit = row.unit,
                estimatedAmount = amount,
                unitWeightJin = row.unitWeight.toDoubleOrNull() ?: 0.0,
                buyer = buyer
            )

        if (itemId > 0L) {
            editingPlanItemId = null
            loadRowsFromCollaborationPlan()
            purchaseFormExpanded = false
            message = "${fruit.name} 计划修改已保存"
            onChanged()
        } else {
            message = "修改保存失败，请重试"
        }
    }

    fun completeCollaborationDrafts() {
        val filledRows = meaningfulRows()
        if (filledRows.isEmpty()) {
            message = "请至少填写一种采购商品"
            return
        }

        val latestPlanItems = db.getPurchasePlan(date)?.items.orEmpty()
        // 主按钮“完成采购”只处理本次新录入的商品，或当前明确进入修改状态的计划商品。
        // 已保存的计划采购不会再被顺带完成；需要完成计划时，点击对应待采购商品单独操作。
        val completionCandidates =
            filledRows.filter { row ->
                row.planItemId == null || row.planItemId == editingPlanItemId
            }
        val pendingRows =
            completionCandidates.filterNot { row ->
                val matched =
                    row.planItemId
                        ?.let { id -> latestPlanItems.firstOrNull { it.id == id } }
                matched?.status == 1
            }

        if (pendingRows.isEmpty()) {
            message =
                if (filledRows.any { it.planItemId != null }) {
                    "计划采购不会自动完成，请点击待采购商品单独完成"
                } else {
                    "当前商品都已完成采购"
                }
            return
        }

        val invalidData =
            pendingRows.firstOrNull {
                it.fruitId == null ||
                    (it.quantity.toDoubleOrNull() ?: 0.0) <= 0 ||
                    (it.totalCost.toDoubleOrNull() ?: -1.0) < 0
            }
        if (invalidData != null) {
            message = "完成采购前请确认实际数量和总价"
            return
        }

        val missingBuyer =
            pendingRows.firstOrNull { row ->
                row.buyerId == null || partners.none { it.id == row.buyerId }
            }
        if (missingBuyer != null) {
            val name =
                missingBuyer.fruitNameSnapshot.ifBlank {
                    missingBuyer.fruitId
                        ?.let { id -> fruits.firstOrNull { it.id == id }?.name }
                        .orEmpty()
                }
            message = if (name.isBlank()) "完成采购前请选择采购人" else "$name 还没有选择采购人"
            return
        }

        val targets =
            pendingRows.mapNotNull { row ->
                val fruit =
                    fruits.firstOrNull { it.id == row.fruitId }
                        ?: row.fruitId?.let { id ->
                            row.fruitNameSnapshot.takeIf { it.isNotBlank() }?.let { name ->
                                FruitOption(id, name, row.unit)
                            }
                        }
                val buyer = row.buyerId?.let { id -> partners.firstOrNull { it.id == id } }
                if (fruit == null || buyer == null) null else Triple(row, fruit, buyer)
            }

        if (targets.size != pendingRows.size) {
            message = "有商品或采购人已失效，请重新选择后再完成采购"
            return
        }

        // 先把当前输入完整保存为采购计划，再逐项转为正式采购。
        // 所有输入已经在上面完成预校验，避免常见的“完成一半才发现缺采购人”。
        val prepared = mutableListOf<Pair<Long, Triple<PurchaseDraftRow, FruitOption, PartnerOption>>>()
        for (target in targets) {
            val row = target.first
            val fruit = target.second
            val buyer = target.third
            val itemId =
                db.upsertPurchaseDraftToCollaboration(
                    date = date,
                    itemId = row.planItemId,
                    fruit = fruit,
                    quantity = row.quantity.toDouble(),
                    unit = row.unit,
                    unitWeightJin = row.unitWeight.toDoubleOrNull() ?: 0.0,
                    estimatedAmount = row.totalCost.toDouble(),
                    buyer = buyer,
                    mergePendingSameProduct = row.planItemId != null
                )
            if (itemId <= 0L) {
                message = "${fruit.name} 保存采购数据失败，请重试"
                return
            }
            prepared += itemId to target
        }

        var completedCount = 0
        var failedMessage = ""
        for ((itemId, target) in prepared) {
            val row = target.first
            val buyer = target.third
            val result =
                db.completeCollaborationPlanItem(
                    itemId = itemId,
                    buyer = buyer,
                    actualQuantity = row.quantity.toDouble(),
                    actualAmount = row.totalCost.toDouble(),
                    recorderUsername = "",
                    recorderDisplayName = ""
                )
            if (!result.success) {
                failedMessage = result.message
                break
            }
            completedCount++
        }

        loadRowsFromCollaborationPlan()
        purchaseFormExpanded = false
        completedPurchasesExpanded = true
        onChanged()

        message =
            when {
                completedCount == prepared.size ->
                    "已完成采购 ${completedCount} 项，已计入当天进货"
                completedCount > 0 ->
                    "已完成 $completedCount 项；其余未完成：${failedMessage.ifBlank { "请刷新后重试" }}"
                else ->
                    failedMessage.ifBlank { "完成采购失败，请刷新后重试" }
            }
    }

    LaunchedEffect(date, editingOrderId) {
        if (editingOrderId == null) loadRowsFromCollaborationPlan()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item {
            BusinessDateHeader(
                pageTitle = "采购",
                date = date,
                onDate = onWorkDateChange
            )
        }

        if (editingOrderId != null) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF7D9))) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "正在编辑采购单 #$editingOrderId",
                            Modifier.weight(1f),
                            fontWeight = FontWeight.Bold
                        )
                        TextButton(
                            onClick = {
                                editingOrderId = null
                                editBuyerId = null
                                historicalBuyerName = ""
                                remark = ""
                                resetRowsToOneBlank()
                                message = "已取消编辑"
                            }
                        ) { Text("取消") }
                    }
                }
            }
        }

        item {
            if (editingOrderId == null) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(22.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "计划金额：${money(dayPlannedTotal)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.Gray
                        )
                        Spacer(Modifier.width(18.dp))
                        Text(
                            "已采购金额：${money(dayPurchasedTotal)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.Gray
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    Box(Modifier.fillMaxWidth()) {
                        CompactSelectButton(
                            "采购人",
                            editBuyerDisplayName,
                            Modifier.fillMaxWidth()
                        ) { editBuyerMenu = true }
                        DropdownMenu(
                            expanded = editBuyerMenu,
                            onDismissRequest = { editBuyerMenu = false }
                        ) {
                            partners.forEach { p ->
                                DropdownMenuItem(
                                    text = { Text(p.name) },
                                    onClick = {
                                        editBuyerId = p.id
                                        historicalBuyerName = ""
                                        editBuyerMenu = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        if (
            editingOrderId == null &&
            !purchaseFormExpanded
        ) {
            item {
                OutlinedButton(
                    onClick = {
                        focusManager.clearFocus()
                        if (rows.none { it.planItemId == null }) {
                            rows.add(newBlankRow())
                        }
                        purchaseFormExpanded = true
                        message = ""
                    },
                    modifier = Modifier.fillMaxWidth().height(38.dp)
                ) {
                    Text("＋ 新增采购")
                }
            }
        }

        val importInventoryRowId =
            if (editingOrderId == null) {
                rows.firstOrNull { candidate ->
                    val candidateItem = collaborationItemFor(candidate)
                    candidate.planItemId == null ||
                        (
                            candidateItem?.status == 0 &&
                                editingPlanItemId == candidateItem.id
                        )
                }?.rowId
            } else {
                null
            }

        val groupedPendingItems =
            collaborationPlan
                ?.items
                .orEmpty()
                .filter { it.status == 0 }
                .groupBy { it.buyerId to it.buyerName.ifBlank { "未指定采购人" } }
        val pendingGroupFirstItemIds =
            groupedPendingItems.values.mapNotNull { group -> group.firstOrNull()?.id }.toSet()

        val visiblePurchaseRows =
            rows.filterNot { row ->
                editingOrderId == null &&
                    collaborationItemFor(row)?.status == 1
            }

        items(
            visiblePurchaseRows,
            key = { row -> "purchase_draft_${row.rowId}" }
        ) { row ->
            val collaborationItem = collaborationItemFor(row)
            val savedPending =
                editingOrderId == null &&
                    collaborationItem?.status == 0 &&
                    row.planItemId != null
            val editingSavedPending =
                savedPending && editingPlanItemId == collaborationItem?.id

            when {
                savedPending && !editingSavedPending -> {
                    val planItem = collaborationItem!!
                    val groupKey = planItem.buyerId to planItem.buyerName.ifBlank { "未指定采购人" }
                    val groupItems = groupedPendingItems[groupKey].orEmpty()
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        if (planItem.id in pendingGroupFirstItemIds) {
                            Row(
                                Modifier.fillMaxWidth().padding(top = 3.dp, start = 2.dp, end = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    planItem.buyerName.ifBlank { "未指定采购人" },
                                    modifier = Modifier.weight(1f),
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF4B5563)
                                )
                                Text(
                                    "${groupItems.size}项  ·  ${money(groupItems.sumOf { it.estimatedAmount })}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = BrandGreen,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                        PurchasePlanStatusCard(
                            item = planItem,
                            completed = false,
                            inventoryQuantity =
                                row.fruitId?.let { id ->
                                    inventoryByProductUnit[id to row.unit]?.remainingQuantity
                                },
                            referenceUnitPrice = row.unitPrice.toDoubleOrNull(),
                            showBuyer = false,
                            onClick = {
                                protectHistoricalAction(
                                    date,
                                    "查看或修改 ${planItem.fruitName} 的历史采购"
                                ) {
                                    planActionItem = planItem
                                }
                            }
                        )
                    }
                }

                else -> {
                    if (editingOrderId != null || purchaseFormExpanded) {
                        // FIX2：采购录入不再随输入自动写入协作采购。
                        // 只有点击“计划采购”/“保存计划”/“完成采购”时才持久化，避免输入数量时误保存。
                        PurchaseDraftRowEditor(
                            row = row,
                            fruits = fruits,
                            partners = partners,
                            completedItem = null,
                            showBuyer = editingOrderId == null,
                            canDelete = rows.size > 1,
                            inventoryQuantity =
                                row.fruitId
                                    ?.let { id ->
                                        inventoryByProductUnit[
                                            id to row.unit
                                        ]?.remainingQuantity
                                    },
                            lookupUnitPrice = { fruitId, unit ->
                                db.getLatestPurchaseUnitPrice(
                                    fruitId = fruitId,
                                    unit = unit,
                                    onOrBeforeDate = date
                                )
                            },
                            lookupUnitWeight = { fruitId, unit ->
                                db.getLatestUnitWeightJin(fruitId, unit, date)
                            },
                            showImportInventory =
                                editingOrderId == null &&
                                    row.rowId == importInventoryRowId,
                            onImportInventory = {
                                focusManager.clearFocus()
                                importPreviousInventory()
                            },
                            onChange = { updated -> updateRow(row.rowId, updated) },
                        onAddFruit = {
                            pendingFruitRowId = row.rowId
                            addFruitDialog = true
                        },
                        onDelete = {
                            val deleteDraftRow = {
                                if (editingOrderId == null && row.planItemId != null) {
                                    db.deleteCollaborationPlanItem(row.planItemId)
                                    editingPlanItemId = null
                                    onChanged()
                                }
                                syncedFingerprints.remove(row.rowId)
                                val index = rows.indexOfFirst { it.rowId == row.rowId }
                                if (index >= 0) rows.removeAt(index)
                                if (rows.isEmpty()) rows.add(newBlankRow())
                            }

                            if (editingOrderId == null && row.planItemId != null) {
                                protectHistoricalAction(
                                    date,
                                    "删除历史采购计划 ${row.fruitNameSnapshot.ifBlank { "商品" }}"
                                ) {
                                    deleteDraftRow()
                                }
                            } else {
                                deleteDraftRow()
                            }
                        }
                    )

                    if (editingSavedPending) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = {
                                    focusManager.clearFocus()
                                    editingPlanItemId = null
                                    loadRowsFromCollaborationPlan()
                                    purchaseFormExpanded = false
                                    message = "已取消修改"
                                }
                            ) {
                                Text("取消修改")
                            }
                            TextButton(
                                onClick = {
                                    focusManager.clearFocus()
                                    saveEditedPendingPlan(row)
                                }
                            ) {
                                Text("完成修改")
                            }
                        }
                    }
                    }
                }
            }
        }

        val pendingPlanItems =
            collaborationPlan
                ?.items
                .orEmpty()
                .filter { it.status == 0 }

        if (
            editingOrderId == null &&
                editingPlanItemId == null &&
                !purchaseFormExpanded &&
                pendingPlanItems.isNotEmpty()
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = {
                            protectHistoricalAction(
                                date,
                                "一键完成 $date 的计划采购"
                            ) {
                                startBatchComplete()
                            }
                        }
                    ) {
                        Text("一键完成采购")
                    }
                }
            }
        }

        if ((editingOrderId != null || purchaseFormExpanded) && editingPlanItemId == null) {
        item {
            OutlinedButton(
                onClick = {
                    focusManager.clearFocus()
                    if (rows.lastOrNull()?.let { it.isBlank || isDefaultEmptyRow(it) } == true) {
                        // 空白行已经存在时不再创建重复草稿；该行现在会正常显示。
                        message = ""
                    } else {
                        rows.add(newBlankRow())
                        message = ""
                    }
                },
                modifier = Modifier.fillMaxWidth().height(38.dp)
            ) { Text("＋ 添加商品") }
        }

        val activeRows =
            meaningfulRows().filter { row ->
                editingOrderId != null || collaborationItemFor(row)?.status != 1
            }
        if (activeRows.isNotEmpty()) {
            item {
                val totals = activeRows.map { it.totalCost.toDoubleOrNull() }
                val allTotalsEntered = totals.all { it != null && it >= 0.0 }
                val total = totals.filterNotNull().sum()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "本次采购 ${activeRows.size} 项",
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        if (allTotalsEntered) "合计 ${money(total)}" else "合计 —",
                        fontWeight = FontWeight.Bold,
                        color = BrandGreen
                    )
                }
            }
        }

        item {
            if (editingOrderId != null) {
                CompactTextField(
                    "备注（可选）",
                    remark,
                    { remark = it },
                    Modifier.fillMaxWidth()
                )
            }

            if (editingOrderId == null) {
                val hasSavedPending =
                    meaningfulRows().any { row ->
                        row.planItemId != null && collaborationItemFor(row)?.status == 0
                    }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = if (editingOrderId == null) 1.dp else 7.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            focusManager.clearFocus()
                            saveCollaborationDrafts()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (hasSavedPending) "保存计划" else "计划采购")
                    }

                    Button(
                        onClick = {
                            focusManager.clearFocus()
                            completeCollaborationDrafts()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("完成采购")
                    }
                }
            } else {
                Button(
                    onClick = {
                        focusManager.clearFocus()
                        val filledRows = meaningfulRows()
                        when {
                            editBuyer == null -> message = "请选择采购人"
                            filledRows.isEmpty() -> message = "请至少填写一种采购商品"
                            filledRows.any {
                                it.fruitId == null ||
                                    (it.quantity.toDoubleOrNull() ?: 0.0) <= 0 ||
                                    (it.totalCost.toDoubleOrNull() ?: -1.0) < 0
                            } -> message = "有商品没有选择商品，或数量/总价没有填写正确"
                            else -> {
                                val lines =
                                    filledRows.mapNotNull { r ->
                                        val activeFruit = fruits.firstOrNull { it.id == r.fruitId }
                                        val resolvedFruit =
                                            activeFruit ?: r.fruitId?.let { id ->
                                                r.fruitNameSnapshot.takeIf { it.isNotBlank() }?.let { name ->
                                                    FruitOption(id, name, r.unit)
                                                }
                                            }
                                        resolvedFruit?.let { f ->
                                            PurchaseLineInput(
                                                fruit = f,
                                                unit = r.unit,
                                                quantity = r.quantity.toDouble(),
                                                totalCost = r.totalCost.toDouble(),
                                                unitWeightJin = r.unitWeight.toDoubleOrNull() ?: 0.0
                                            )
                                        }
                                    }
                                if (lines.size != filledRows.size) {
                                    message = "有历史商品无法识别，请重新选择该商品"
                                } else {
                                    persistHistoricalEdit(lines)
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 7.dp)
                ) {
                    Text("保存修改")
                }
            }
        }
        }

        if (message.isNotBlank()) {
            item { Text(message, color = BrandGreen, style = MaterialTheme.typography.bodySmall) }
        }

        if (editingOrderId == null) {
            val completedItems =
                collaborationPlan
                    ?.items
                    .orEmpty()
                    .filter { it.status == 1 }

            if (completedItems.isNotEmpty()) {
                item {
                    HorizontalDivider()
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "已完成采购",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "${completedItems.size} 项",
                            style = MaterialTheme.typography.bodySmall,
                            color = BrandGreen
                        )
                        Spacer(Modifier.width(6.dp))
                        TextButton(
                            onClick = {
                                completedPurchasesExpanded =
                                    !completedPurchasesExpanded
                            },
                            contentPadding = PaddingValues(horizontal = 6.dp)
                        ) {
                            Text(
                                if (completedPurchasesExpanded) "收起" else "展示"
                            )
                        }
                    }
                }

                if (completedPurchasesExpanded) {
                    items(
                        completedItems,
                        key = { item -> "purchase_completed_${item.id}" }
                    ) { completedItem ->
                        PurchasePlanStatusCard(
                            item = completedItem,
                            completed = true,
                            onClick = {
                                protectHistoricalAction(
                                    date,
                                    "修改或删除 $date · ${completedItem.fruitName} 已采购记录"
                                ) {
                                    planActionItem = completedItem
                                }
                            }
                        )
                    }
                }
            }
        }

        item {
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = onOpenHistory,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(
                        "采购历史  ›",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Text("最近7个采购日", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
        }

        if (history.isEmpty()) {
            item { Text("暂无采购记录", color = Color.Gray) }
        }

        val historyByDate = history.groupBy { it.order.date }.toSortedMap(reverseOrder())
        historyByDate.forEach { entry ->
            val historyDate = entry.key
            val dayOrders = entry.value
            val dayTotal = dayOrders.sumOf { it.order.totalCost }
            val dayFruitCount = dayOrders.flatMap { it.items }.distinctBy { it.fruitId }.size

            item(key = "purchase_day_$historyDate") {
                Row(
                    Modifier.fillMaxWidth().padding(top = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val parsedHistoryDate = runCatching { LocalDate.parse(historyDate) }.getOrNull()
                    Text(
                        historyDate +
                            (parsedHistoryDate?.let { "  ${chineseWeekday(it)}" } ?: ""),
                        modifier =
                            Modifier
                                .weight(1f)
                                .clickable {
                                    protectHistoricalAction(
                                        historyDate,
                                        "编辑 $historyDate 全部采购记录"
                                    ) {
                                        historyEditTarget =
                                            PurchaseHistoryEditTarget(
                                                mode =
                                                    PurchaseHistoryEditMode.DAY,
                                                date = historyDate,
                                                details = dayOrders
                                            )
                                    }
                                },
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Text(
                        "$dayFruitCount 种 · ${money(dayTotal)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = BrandGreen,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            val buyerGroups =
                dayOrders.groupBy { detail -> detail.order.buyerId to detail.order.buyerName }
            buyerGroups.entries.forEachIndexed { index, buyerEntry ->
                val buyerDetails = buyerEntry.value
                val buyerName = buyerEntry.key.second.ifBlank { "未指定采购人" }
                val buyerTotal = buyerDetails.sumOf { it.order.totalCost }
                val buyerFruitCount =
                    buyerDetails.flatMap { it.items }.distinctBy { it.fruitId }.size

                item(key = "purchase_buyer_${historyDate}_${buyerEntry.key.first}_$index") {
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    buyerName,
                                    modifier =
                                        Modifier
                                            .weight(1f)
                                            .clickable {
                                                protectHistoricalAction(
                                                    historyDate,
                                                    "编辑 $historyDate · $buyerName 的采购记录"
                                                ) {
                                                    historyEditTarget =
                                                        PurchaseHistoryEditTarget(
                                                            mode =
                                                                PurchaseHistoryEditMode.BUYER_DAY,
                                                            date =
                                                                historyDate,
                                                            details =
                                                                buyerDetails,
                                                            buyerName =
                                                                buyerName
                                                        )
                                                }
                                            },
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "$buyerFruitCount 种 · ${money(buyerTotal)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = BrandGreen,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            buyerDetails.forEach { detail ->
                                detail.items.forEach { item ->
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .combinedClickable(
                                                onClick = {
                                                    protectHistoricalAction(
                                                        historyDate,
                                                        "修改 $historyDate · ${item.fruitName} 采购记录"
                                                    ) {
                                                        historyEditTarget =
                                                            PurchaseHistoryEditTarget(
                                                                mode =
                                                                    PurchaseHistoryEditMode.ITEM,
                                                                date =
                                                                    historyDate,
                                                                details =
                                                                    listOf(detail),
                                                                focusItemId =
                                                                    item.id,
                                                                buyerName =
                                                                    buyerName
                                                            )
                                                    }
                                                },
                                                onLongClick = {
                                                    protectHistoricalAction(
                                                        historyDate,
                                                        "删除 $historyDate · ${item.fruitName} 采购记录"
                                                    ) {
                                                        deleteOrder = detail
                                                    }
                                                }
                                            )
                                            .padding(vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            "${item.fruitName}  ${fmt(item.quantity)}${item.unit} · ${money(item.totalCost)}",
                                            modifier = Modifier.weight(1f),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        Text(
                                            "${money(item.unitPrice)}/${item.unit}  ›",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.Gray
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    historyEditTarget?.let { target ->
        PurchaseHistoryEditDialog(
            db = db,
            target = target,
            fruits = fruits,
            partners = partners,
            onDismiss = {
                historyEditTarget = null
            },
            onSaved = { savedMessage ->
                historyEditTarget = null
                message = savedMessage
                onChanged()
            }
        )
    }

    if (addFruitDialog) {
        AddFruitDialog(
            onDismiss = {
                addFruitDialog = false
                pendingFruitRowId = null
            },
            onSave = { name, defaultUnit ->
                val id = db.addFruit(name, defaultUnit)
                if (id > 0) {
                    val targetId = pendingFruitRowId
                    if (targetId != null) {
                        val row = rows.firstOrNull { it.rowId == targetId }
                        if (row != null) {
                            updateRow(
                                targetId,
                                row.copy(
                                    fruitId = id,
                                    fruitNameSnapshot = name.trim(),
                                    unit = defaultUnit.ifBlank { "件" },
                                    unitPrice = "0",
                                    totalCost = "0",
                                    priceSource = PurchasePriceSource.UNIT
                                )
                            )
                        }
                    }
                    message = "商品已添加"
                    onChanged()
                } else {
                    message = "商品保存失败"
                }
                addFruitDialog = false
                pendingFruitRowId = null
            }
        )
    }

    historyActionDetail?.let { detail ->
        AlertDialog(
            onDismissRequest = {
                historyActionDetail = null
                historyActionFruitName = ""
            },
            title = { Text(historyActionFruitName.ifBlank { "采购记录" }) },
            text = {
                Text(
                    "${detail.order.date} · ${detail.order.buyerName}\n" +
                        "修改会打开该商品所属采购单；删除会删除整张采购单。"
                )
            },
            confirmButton = {
                Row {
                    TextButton(
                        onClick = {
                            historyActionDetail = null
                            val label =
                                historyActionFruitName.ifBlank {
                                    "采购记录"
                                }
                            historyActionFruitName = ""
                            protectHistoricalAction(
                                detail.order.date,
                                "修改 ${detail.order.date} · $label"
                            ) {
                                loadHistoryOrderForEdit(detail)
                            }
                        }
                    ) { Text("编辑") }
                    TextButton(
                        onClick = {
                            historyActionDetail = null
                            val label =
                                historyActionFruitName.ifBlank {
                                    "采购记录"
                                }
                            historyActionFruitName = ""
                            protectHistoricalAction(
                                detail.order.date,
                                "删除 ${detail.order.date} · $label"
                            ) {
                                deleteOrder = detail
                            }
                        }
                    ) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        historyActionDetail = null
                        historyActionFruitName = ""
                    }
                ) { Text("关闭") }
            }
        )
    }

    deleteOrder?.let { detail ->
        ConfirmDelete(
            "删除 ${detail.order.date} 的采购单？如果来自协作采购，对应商品会恢复为未完成。",
            { deleteOrder = null }
        ) {
            db.deletePurchaseOrder(detail.order.id)
            if (editingOrderId == detail.order.id) {
                editingOrderId = null
                editBuyerId = null
                historicalBuyerName = ""
                remark = ""
                resetRowsToOneBlank()
            }
            deleteOrder = null
            onChanged()
        }
    }

    planActionItem?.let { item ->
        val completed = item.status == 1
        val quantity = if (completed && item.actualQuantity > 0) item.actualQuantity else item.quantity
        val amount = if (completed) item.actualAmount else item.estimatedAmount

        AlertDialog(
            onDismissRequest = { planActionItem = null },
            title = { Text(item.fruitName) },
            text = {
                Text(
                    if (completed) {
                        "已完成采购\n数量 ${fmt(quantity)}${item.unit} · 总价 ${money(amount)}" +
                            if (item.buyerName.isNotBlank()) " · ${item.buyerName}" else ""
                    } else {
                        val referencePrice =
                            if (quantity > 0 && amount > 0) amount / quantity else 0.0
                        "待采购\n数量 ${fmt(quantity)}${item.unit} · " +
                            "参考单价 ${money(referencePrice)}/${item.unit} · 计划总价 ${money(amount)}" +
                            if (item.buyerName.isNotBlank()) " · ${item.buyerName}" else ""
                    }
                )
            },
            confirmButton = {
                Row {
                    TextButton(
                        onClick = {
                            protectHistoricalAction(
                                date,
                                "修改 ${item.fruitName} 的历史采购记录"
                            ) {
                                planActionItem = null
                                if (completed) {
                                    completeDialogItem = item
                                    completeDialogEditing = true
                                } else {
                                    editingPlanItemId = item.id
                                    purchaseFormExpanded = true
                                    message = "正在修改 ${item.fruitName}"
                                }
                            }
                        }
                    ) { Text("修改") }

                    if (completed) {
                        TextButton(
                            onClick = {
                                protectHistoricalAction(
                                    date,
                                    "把 ${item.fruitName} 的历史采购恢复为未完成"
                                ) {
                                    planActionItem = null
                                    restoreCompletedItem = item
                                }
                            }
                        ) { Text("恢复未完成") }
                    } else {
                        TextButton(
                            onClick = {
                                protectHistoricalAction(
                                    date,
                                    "完成 ${item.fruitName} 的历史采购"
                                ) {
                                    planActionItem = null
                                    completeDialogItem = item
                                    completeDialogEditing = false
                                }
                            }
                        ) { Text("完成采购") }
                    }

                    TextButton(
                        onClick = {
                            protectHistoricalAction(
                                date,
                                "删除 ${item.fruitName} 的历史采购记录"
                            ) {
                                planActionItem = null
                                deleteCollaborationItem = item
                            }
                        }
                    ) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { planActionItem = null }) { Text("关闭") }
            }
        )
    }

    completeDialogItem?.let { item ->
        CollaborationCompleteDialog(
            db = db,
            item = item,
            editingCompleted = completeDialogEditing,
            recorderUsername = "",
            recorderDisplayName = "",
            onDismiss = { completeDialogItem = null },
            onCompleted = { resultMessage ->
                message = resultMessage
                completeDialogItem = null
                editingPlanItemId = null
                loadRowsFromCollaborationPlan()
                completedPurchasesExpanded = true
                purchaseFormExpanded = false
                onChanged()
            }
        )
    }

    batchCompleteItems
        .getOrNull(batchCompleteIndex)
        ?.let { item ->
            val referencePrice =
                if (item.quantity > 0 && item.estimatedAmount > 0) {
                    item.estimatedAmount / item.quantity
                } else {
                    db.getLatestPurchaseUnitPrice(
                        fruitId = item.fruitId,
                        unit = item.unit,
                        onOrBeforeDate = date
                    )
                }

            fun finishOrAdvance(
                completedDelta: Int,
                skippedDelta: Int
            ) {
                val completedNow =
                    batchCompletedCount + completedDelta
                val skippedNow =
                    batchSkippedCount + skippedDelta
                val nextIndex =
                    batchCompleteIndex + 1

                batchCompletedCount = completedNow
                batchSkippedCount = skippedNow

                if (nextIndex >= batchCompleteItems.size) {
                    batchCompleteItems = emptyList()
                    batchCompleteIndex = 0
                    loadRowsFromCollaborationPlan()
                    completedPurchasesExpanded =
                        completedNow > 0
                    purchaseFormExpanded = false
                    message =
                        "一键完成结束：完成 $completedNow 项" +
                            if (skippedNow > 0) " · 跳过 $skippedNow 项" else ""
                } else {
                    batchCompleteIndex = nextIndex
                }
            }

            CollaborationCompleteDialog(
                db = db,
                item = item,
                editingCompleted = false,
                recorderUsername = "",
                recorderDisplayName = "",
                onDismiss = {
                    batchCompleteItems = emptyList()
                    batchCompleteIndex = 0
                    message =
                        "已结束一键完成：完成 $batchCompletedCount 项" +
                            if (batchSkippedCount > 0) {
                                " · 跳过 $batchSkippedCount 项"
                            } else {
                                ""
                            }
                },
                onCompleted = {
                    finishOrAdvance(
                        completedDelta = 1,
                        skippedDelta = 0
                    )
                    onChanged()
                },
                batchMode = true,
                onSkip = {
                    finishOrAdvance(
                        completedDelta = 0,
                        skippedDelta = 1
                    )
                },
                referenceUnitPrice = referencePrice
            )
        }

    restoreCompletedItem?.let { item ->
        AlertDialog(
            onDismissRequest = { restoreCompletedItem = null },
            title = { Text("恢复未完成？") },
            text = {
                Text(
                    "${item.fruitName} 会重新回到待采购，对应正式采购记录会同步移除，不再计入当天进货。"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        protectHistoricalAction(
                            date,
                            "恢复 ${item.fruitName} 的历史采购"
                        ) {
                            val result = db.restoreCompletedCollaborationPlanItem(item.id)
                            message = result.message
                            restoreCompletedItem = null
                            if (result.success) {
                                loadRowsFromCollaborationPlan()
                                onChanged()
                            }
                        }
                    }
                ) { Text("确认恢复") }
            },
            dismissButton = {
                TextButton(onClick = { restoreCompletedItem = null }) { Text("取消") }
            }
        )
    }

    deleteCollaborationItem?.let { item ->
        ConfirmDelete(
            if (item.status == 1) {
                "删除 ${item.fruitName} 的已完成采购？对应正式采购记录也会同步删除。"
            } else {
                "删除待采购商品 ${item.fruitName}？"
            },
            { deleteCollaborationItem = null }
        ) {
            protectHistoricalAction(
                date,
                "删除 ${item.fruitName} 的历史采购记录"
            ) {
                val success =
                    if (item.status == 1) {
                        val result = db.deleteCompletedCollaborationPlanItem(item.id)
                        message = result.message
                        result.success
                    } else {
                        val ok = db.deleteCollaborationPlanItem(item.id)
                        message = if (ok) "${item.fruitName} 已删除" else "删除失败"
                        ok
                    }
                deleteCollaborationItem = null
                editingPlanItemId = null
                if (success) {
                    loadRowsFromCollaborationPlan()
                    onChanged()
                }
            }
        }
    }
}

@Composable
private fun PurchaseHistoryEditDialog(
    db: AppDatabase,
    target: PurchaseHistoryEditTarget,
    fruits: List<FruitOption>,
    partners: List<PartnerOption>,
    onDismiss: () -> Unit,
    onSaved: (String) -> Unit
) {
    val drafts =
        remember(target.stateKey) {
            mutableStateListOf<PurchaseHistoryEditDraft>()
                .apply {
                    target.details.forEach { detail ->
                        detail.items.forEach { item ->
                            add(
                                PurchaseHistoryEditDraft(
                                    orderId =
                                        detail.order.id,
                                    itemId =
                                        item.id,
                                    fruitId =
                                        item.fruitId,
                                    fruitName =
                                        item.fruitName,
                                    unit =
                                        item.unit,
                                    quantity =
                                        cleanNumber(
                                            item.quantity
                                        ),
                                    unitPrice =
                                        cleanNumber(
                                            if (item.unitPrice > 0.0 || item.totalCost <= 0.0) {
                                                item.unitPrice.coerceAtLeast(0.0)
                                            } else if (item.quantity > 0.0) {
                                                item.totalCost / item.quantity
                                            } else {
                                                0.0
                                            }
                                        ),
                                    totalCost =
                                        cleanNumber(
                                            item.totalCost
                                        ),
                                    unitWeightJin = cleanNumber(item.unitWeightJin)
                                )
                            )
                        }
                    }
                }
        }

    val selectedBuyers =
        remember(target.stateKey) {
            mutableStateMapOf<Long, Long>()
                .apply {
                    target.details.forEach {
                        put(
                            it.order.id,
                            it.order.buyerId
                        )
                    }
                }
        }

    var error by remember(target.stateKey) {
        mutableStateOf("")
    }

    val visibleDrafts =
        if (
            target.mode ==
            PurchaseHistoryEditMode.ITEM
        ) {
            drafts.filter {
                it.itemId == target.focusItemId
            }
        } else {
            drafts.toList()
        }

    fun updateDraft(
        itemId: Long,
        updated: PurchaseHistoryEditDraft
    ) {
        val index =
            drafts.indexOfFirst {
                it.itemId == itemId
            }
        if (index >= 0) {
            drafts[index] = updated
        }
    }

    fun saveAll() {
        error = ""

        target.details.forEach { detail ->
            val orderRows =
                drafts.filter {
                    it.orderId ==
                        detail.order.id
                }

            if (orderRows.isEmpty()) {
                error =
                    "${detail.order.buyerName} 没有可保存的采购商品"
                return
            }

            val lines =
                mutableListOf<PurchaseLineInput>()

            orderRows.forEach { row ->
                val quantity =
                    row.quantity.toDoubleOrNull()
                val total =
                    row.totalCost.toDoubleOrNull()

                if (
                    quantity == null ||
                    quantity <= 0
                ) {
                    error =
                        "${row.fruitName} 的数量必须大于0"
                    return
                }

                if (
                    total == null ||
                    total < 0
                ) {
                    error =
                        "${row.fruitName} 的总价不正确"
                    return
                }

                val fruit =
                    fruits.firstOrNull {
                        it.id == row.fruitId
                    } ?: FruitOption(
                        row.fruitId,
                        row.fruitName,
                        row.unit
                    )

                lines +=
                    PurchaseLineInput(
                        fruit = fruit,
                        unit = row.unit,
                        quantity = quantity,
                        totalCost = total,
                        unitWeightJin = row.unitWeightJin.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
                    )
            }

            if (error.isNotBlank()) {
                return
            }

            val buyerId =
                selectedBuyers[
                    detail.order.id
                ] ?: detail.order.buyerId

            val buyer =
                partners.firstOrNull {
                    it.id == buyerId
                } ?: if (
                    buyerId ==
                    detail.order.buyerId
                ) {
                    PartnerOption(
                        detail.order.buyerId,
                        detail.order.buyerName
                    )
                } else {
                    null
                }

            if (buyer == null) {
                error = "请选择有效采购人"
                return
            }

            val ok =
                db.updatePurchaseOrder(
                    id = detail.order.id,
                    date = detail.order.date,
                    buyer = buyer,
                    lines = lines,
                    remark = detail.order.remark
                )

            if (!ok) {
                error =
                    "${detail.order.buyerName} 的采购记录保存失败"
                return
            }
        }

        if (error.isBlank()) {
            onSaved(
                when (target.mode) {
                    PurchaseHistoryEditMode.ITEM ->
                        "采购商品已更新"

                    PurchaseHistoryEditMode.BUYER_DAY ->
                        "${target.date} · ${target.buyerName} 的采购已更新"

                    PurchaseHistoryEditMode.DAY ->
                        "${target.date} 当天全部采购已更新"
                }
            )
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when (target.mode) {
                    PurchaseHistoryEditMode.ITEM ->
                        "编辑采购商品"

                    PurchaseHistoryEditMode.BUYER_DAY ->
                        "${target.buyerName} · 当天采购"

                    PurchaseHistoryEditMode.DAY ->
                        "${target.date} · 全部采购"
                }
            )
        },
        text = {
            Column(
                verticalArrangement =
                    Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    when (target.mode) {
                        PurchaseHistoryEditMode.ITEM ->
                            "只修改当前商品；保存后停留在采购历史当前位置。"

                        PurchaseHistoryEditMode.BUYER_DAY ->
                            "一次修改该采购人当天的全部采购商品。"

                        PurchaseHistoryEditMode.DAY ->
                            "按采购人分组修改当天全部采购商品，也可调整采购人。"
                    },
                    style =
                        MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )

                Text(
                    "可修改当前批次每件重量；输入单价自动计算总价，输入总价自动反算单价。",
                    style = MaterialTheme.typography.labelSmall,
                    color = BrandGreen
                )

                LazyColumn(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 480.dp),
                    verticalArrangement =
                        Arrangement.spacedBy(8.dp)
                ) {
                    val grouped =
                        visibleDrafts.groupBy {
                            it.orderId
                        }

                    grouped.forEach {
                        (orderId, groupRows) ->
                        val detail =
                            target.details
                                .first {
                                    it.order.id ==
                                        orderId
                                }

                        item(
                            key =
                                "history_edit_order_$orderId"
                        ) {
                            Column(
                                verticalArrangement =
                                    Arrangement.spacedBy(
                                        6.dp
                                    )
                            ) {
                                if (
                                    target.mode ==
                                    PurchaseHistoryEditMode.DAY
                                ) {
                                    HistoryPurchaseBuyerSelector(
                                        partners =
                                            partners,
                                        historicalBuyer =
                                            PartnerOption(
                                                detail.order.buyerId,
                                                detail.order.buyerName
                                            ),
                                        selectedId =
                                            selectedBuyers[
                                                orderId
                                            ] ?: detail.order.buyerId,
                                        onSelected = {
                                            selectedBuyers[
                                                orderId
                                            ] = it
                                        }
                                    )
                                } else if (
                                    target.mode !=
                                    PurchaseHistoryEditMode.ITEM
                                ) {
                                    Text(
                                        detail.order.buyerName,
                                        fontWeight =
                                            FontWeight.Bold
                                    )
                                }
                            }
                        }

                        items(
                            groupRows,
                            key = {
                                "history_edit_item_${it.itemId}"
                            }
                        ) { row ->
                            HistoryPurchaseItemEditor(
                                row = row,
                                fruits = fruits,
                                onChange = {
                                    updateDraft(
                                        row.itemId,
                                        it
                                    )
                                    error = ""
                                }
                            )
                        }
                    }
                }

                if (error.isNotBlank()) {
                    Text(
                        error,
                        color =
                            MaterialTheme.colorScheme.error,
                        style =
                            MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    saveAll()
                }
            ) {
                Text("保存修改")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss
            ) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun HistoryPurchaseBuyerSelector(
    partners: List<PartnerOption>,
    historicalBuyer: PartnerOption,
    selectedId: Long,
    onSelected: (Long) -> Unit
) {
    var menu by remember {
        mutableStateOf(false)
    }

    val active =
        partners.firstOrNull {
            it.id == selectedId
        }
    val displayName =
        active?.name
            ?: if (
                selectedId ==
                historicalBuyer.id
            ) {
                historicalBuyer.name
            } else {
                "请选择采购人"
            }

    Box(Modifier.fillMaxWidth()) {
        CompactSelectButton(
            "采购人",
            displayName,
            Modifier.fillMaxWidth()
        ) {
            menu = true
        }

        DropdownMenu(
            expanded = menu,
            onDismissRequest = {
                menu = false
            }
        ) {
            if (
                partners.none {
                    it.id ==
                        historicalBuyer.id
                }
            ) {
                DropdownMenuItem(
                    text = {
                        Text(
                            "${historicalBuyer.name}（历史）"
                        )
                    },
                    onClick = {
                        onSelected(
                            historicalBuyer.id
                        )
                        menu = false
                    }
                )
            }

            partners.forEach { partner ->
                DropdownMenuItem(
                    text = {
                        Text(partner.name)
                    },
                    onClick = {
                        onSelected(
                            partner.id
                        )
                        menu = false
                    }
                )
            }
        }
    }
}

@Composable
private fun HistoryPurchaseItemEditor(
    row: PurchaseHistoryEditDraft,
    fruits: List<FruitOption>,
    onChange: (PurchaseHistoryEditDraft) -> Unit
) {
    var fruitMenu by remember(row.itemId) {
        mutableStateOf(false)
    }
    var unitMenu by remember(row.itemId) {
        mutableStateOf(false)
    }

    val activeFruit =
        fruits.firstOrNull {
            it.id == row.fruitId
        }
    val fruitName =
        activeFruit?.name
            ?: row.fruitName

    fun totalFrom(quantityText: String, unitPriceText: String): String? {
        val quantity = quantityText.toDoubleOrNull() ?: return null
        val unitPrice = unitPriceText.toDoubleOrNull() ?: return null
        if (quantity < 0.0 || unitPrice < 0.0) return null
        return cleanNumber(quantity * unitPrice)
    }

    fun unitPriceFrom(quantityText: String, totalText: String): String? {
        val quantity = quantityText.toDoubleOrNull() ?: return null
        val total = totalText.toDoubleOrNull() ?: return null
        if (quantity <= 0.0 || total < 0.0) return null
        return cleanNumber(total / quantity)
    }

    Card(
        Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    Color(0xFFF8FAFC)
            )
    ) {
        Column(
            Modifier.padding(9.dp),
            verticalArrangement =
                Arrangement.spacedBy(7.dp)
        ) {
            Box(Modifier.fillMaxWidth()) {
                CompactSelectButton(
                    "商品",
                    fruitName,
                    Modifier.fillMaxWidth()
                ) {
                    fruitMenu = true
                }

                DropdownMenu(
                    expanded = fruitMenu,
                    onDismissRequest = {
                        fruitMenu = false
                    }
                ) {
                    if (
                        activeFruit == null
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "${row.fruitName}（历史）"
                                )
                            },
                            onClick = {
                                fruitMenu = false
                            }
                        )
                    }

                    fruits.forEach { fruit ->
                        DropdownMenuItem(
                            text = {
                                Text(fruit.name)
                            },
                            onClick = {
                                onChange(
                                    row.copy(
                                        fruitId =
                                            fruit.id,
                                        fruitName =
                                            fruit.name,
                                        unit =
                                            if (
                                                row.unit.isBlank()
                                            ) {
                                                fruit.defaultUnit
                                            } else {
                                                row.unit
                                            }
                                    )
                                )
                                fruitMenu = false
                            }
                        )
                    }
                }
            }

            // 数量与单位单独一行，避免小屏幕把价格字段挤得过窄。
            Row(
                horizontalArrangement =
                    Arrangement.spacedBy(6.dp)
            ) {
                NumberField(
                    "数量",
                    row.quantity,
                    { text ->
                        val recalculated =
                            totalFrom(
                                text,
                                row.unitPrice
                            )
                        onChange(
                            row.copy(
                                quantity = text,
                                totalCost =
                                    recalculated
                                        ?: row.totalCost
                            )
                        )
                    },
                    Modifier.weight(1f)
                )

                Box(Modifier.weight(0.8f)) {
                    CompactSelectButton(
                        "单位",
                        row.unit,
                        Modifier.fillMaxWidth()
                    ) {
                        unitMenu = true
                    }

                    DropdownMenu(
                        expanded = unitMenu,
                        onDismissRequest = {
                            unitMenu = false
                        }
                    ) {
                        listOf(
                            "件",
                            "斤",
                            "箱",
                            "盒",
                            "袋",
                            "筐"
                        ).forEach { unit ->
                            DropdownMenuItem(
                                text = {
                                    Text(unit)
                                },
                                onClick = {
                                    onChange(
                                        row.copy(
                                            unit = unit
                                        )
                                    )
                                    unitMenu = false
                                }
                            )
                        }
                    }
                }
            }

            // V1.4.7.57：历史已完成采购可以补录/修改该批次自己的每件重量。
            // 重量、单价、总价同排，历史规格只作用于当前采购明细，不反向改其它批次。
            Row(
                horizontalArrangement =
                    Arrangement.spacedBy(6.dp)
            ) {
                NumberField(
                    "重量(斤/${row.unit})",
                    row.unitWeightJin,
                    { text ->
                        onChange(
                            row.copy(
                                unitWeightJin = text
                            )
                        )
                    },
                    Modifier.weight(1.1f)
                )

                NumberField(
                    "单价",
                    row.unitPrice,
                    { text ->
                        val recalculated =
                            totalFrom(
                                row.quantity,
                                text
                            )
                        onChange(
                            row.copy(
                                unitPrice = text,
                                totalCost =
                                    recalculated
                                        ?: row.totalCost
                            )
                        )
                    },
                    Modifier.weight(0.9f)
                )

                NumberField(
                    "总价",
                    row.totalCost,
                    { text ->
                        val recalculated =
                            unitPriceFrom(
                                row.quantity,
                                text
                            )
                        onChange(
                            row.copy(
                                totalCost = text,
                                unitPrice =
                                    recalculated
                                        ?: row.unitPrice
                            )
                        )
                    },
                    Modifier.weight(1f)
                )
            }

            val quantity =
                row.quantity.toDoubleOrNull()
                    ?: 0.0
            val unitPrice =
                row.unitPrice.toDoubleOrNull()
                    ?: 0.0
            val total =
                row.totalCost.toDoubleOrNull()
                    ?: 0.0

            if (
                quantity > 0 &&
                unitPrice >= 0 &&
                total >= 0
            ) {
                Text(
                    "${cleanNumber(quantity)}${row.unit} × ${money(unitPrice)}/${row.unit} = ${money(total)}",
                    style =
                        MaterialTheme.typography.labelSmall,
                    color = Color.Gray
                )
            }
        }
    }
}

@Composable
private fun PurchasePlanStatusCard(
    item: PurchasePlanItemRecord,
    completed: Boolean,
    inventoryQuantity: Double? = null,
    referenceUnitPrice: Double? = null,
    showBuyer: Boolean = true,
    onClick: () -> Unit
) {
    val quantity =
        if (completed && item.actualQuantity > 0) item.actualQuantity else item.quantity
    val amount =
        if (completed) item.actualAmount else item.estimatedAmount
    val unitPrice =
        when {
            completed && quantity > 0 -> amount / quantity
            referenceUnitPrice != null -> referenceUnitPrice.coerceAtLeast(0.0)
            quantity > 0 && amount > 0 -> amount / quantity
            else -> 0.0
        }
    val buyer = item.buyerName.ifBlank { "未指定采购人" }

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (completed) Color(0xFFE7F7ED)
                    else Color(0xFFF7F5F8)
            )
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (completed) "✓" else "○",
                    color = if (completed) BrandGreen else Color(0xFF8A6D00),
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    item.fruitName,
                    fontWeight = FontWeight.Bold
                )
                if (!completed) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "剩余库存 " +
                            (inventoryQuantity?.let { "${fmt(it)}${item.unit}" } ?: "—"),
                        color = Color(0xFFD32F2F),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    if (completed) "已完成采购" else "待采购  ›",
                    color = if (completed) BrandGreen else Color(0xFF8A6D00),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            val detailText =
                if (completed) {
                    "数量 ${fmt(quantity)}${item.unit}   " +
                        "单价 ${money(unitPrice)}/${item.unit}   " +
                        "总价 ${money(amount)}" + if (showBuyer) "   $buyer" else ""
                } else {
                    "计划数量 ${fmt(quantity)}${item.unit}   " +
                        "单价 ${money(unitPrice)}/${item.unit}   " +
                        "计划总额 ${money(amount)}" + if (showBuyer) "   $buyer" else ""
                }

            Text(
                detailText,
                style = MaterialTheme.typography.bodySmall,
                color = if (completed) Color(0xFF35624A) else Color.DarkGray
            )
        }
    }
}

@Composable
private fun PurchaseDraftRowEditor(
    row: PurchaseDraftRow,
    fruits: List<FruitOption>,
    partners: List<PartnerOption>,
    completedItem: PurchasePlanItemRecord?,
    showBuyer: Boolean,
    canDelete: Boolean,
    inventoryQuantity: Double?,
    lookupUnitPrice: (Long, String) -> Double?,
    lookupUnitWeight: (Long, String) -> Double?,
    showImportInventory: Boolean,
    onImportInventory: () -> Unit,
    onChange: (PurchaseDraftRow) -> Unit,
    onAddFruit: () -> Unit,
    onDelete: () -> Unit
) {
    var fruitMenu by remember(row.rowId) { mutableStateOf(false) }
    var unitMenu by remember(row.rowId) { mutableStateOf(false) }
    var buyerMenu by remember(row.rowId) { mutableStateOf(false) }

    val selectedFruit = fruits.firstOrNull { it.id == row.fruitId }
    val fruitDisplay =
        selectedFruit?.name
            ?: row.fruitNameSnapshot.takeIf { it.isNotBlank() }
            ?: "总价"

    val selectedBuyer = partners.firstOrNull { it.id == row.buyerId }
    val buyerDisplay =
        selectedBuyer?.name
            ?: row.buyerNameSnapshot.takeIf { it.isNotBlank() }
            ?: "未指定"

    if (completedItem != null) {
        val quantityValue =
            completedItem.actualQuantity.takeIf { it > 0 }
                ?: row.quantity.toDoubleOrNull()
                ?: 0.0
        val totalValue = completedItem.actualAmount
        val unitPriceValue =
            if (quantityValue > 0) totalValue / quantityValue else 0.0
        val completedBuyer = completedItem.buyerName.ifBlank { buyerDisplay }

        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFE7F7ED))
        ) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("✓", color = BrandGreen, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(7.dp))
                    Text(
                        completedItem.fruitName.ifBlank { fruitDisplay },
                        modifier = Modifier.weight(1f),
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "已完成采购",
                        color = BrandGreen,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Text(
                    "数量 ${fmt(quantityValue)}${completedItem.unit}   " +
                        "单价 ${money(unitPriceValue)}/${completedItem.unit}   " +
                        "总价 ${money(totalValue)}" +
                        if (completedBuyer.isNotBlank()) "   $completedBuyer" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF35624A)
                )
            }
        }
        return
    }

    fun calculatedTotal(quantityText: String, unitPriceText: String): String {
        val quantity = quantityText.toDoubleOrNull() ?: return "0"
        val unitPrice = unitPriceText.toDoubleOrNull() ?: return "0"
        return purchaseNumber((quantity * unitPrice).coerceAtLeast(0.0))
    }

    fun updateQuantity(value: String) {
        val quantity = value.toDoubleOrNull() ?: 0.0
        val updated =
            when (row.priceSource) {
                PurchasePriceSource.UNIT ->
                    row.copy(
                        quantity = value,
                        totalCost = calculatedTotal(value, row.unitPrice)
                    )

                PurchasePriceSource.TOTAL -> {
                    val total = row.totalCost.toDoubleOrNull()
                    row.copy(
                        quantity = value,
                        unitPrice =
                            if (
                                quantity > 0 &&
                                value.isNotBlank() &&
                                total != null &&
                                total >= 0
                            ) {
                                fmt(total / quantity)
                            } else {
                                row.unitPrice
                            }
                    )
                }
            }
        onChange(updated)
    }

    fun updateUnitPrice(value: String) {
        onChange(
            row.copy(
                unitPrice = value,
                totalCost = calculatedTotal(row.quantity, value),
                priceSource = PurchasePriceSource.UNIT
            )
        )
    }

    fun updateTotal(value: String) {
        val quantity = row.quantity.toDoubleOrNull() ?: 0.0
        val total = value.toDoubleOrNull()
        onChange(
            row.copy(
                totalCost = value,
                unitPrice =
                    if (
                        quantity > 0 &&
                        value.isNotBlank() &&
                        total != null &&
                        total >= 0
                    ) {
                        fmt(total / quantity)
                    } else {
                        row.unitPrice
                    },
                priceSource =
                    if (value.isBlank()) PurchasePriceSource.UNIT
                    else PurchasePriceSource.TOTAL
            )
        )
    }

    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF7F5F8))
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                Box(Modifier.weight(1.5f)) {
                    OutlinedButton(
                        onClick = { fruitMenu = true },
                        modifier = Modifier.fillMaxWidth().height(36.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text(
                            fruitDisplay,
                            color = Color(0xFFD99A00),
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }

                    DropdownMenu(
                        expanded = fruitMenu,
                        onDismissRequest = { fruitMenu = false }
                    ) {
                        fruits.forEach { fruit ->
                            DropdownMenuItem(
                                text = { Text(fruit.name) },
                                onClick = {
                                    val targetUnit =
                                        fruit.defaultUnit.ifBlank { "件" }
                                    val remembered =
                                        lookupUnitPrice(
                                            fruit.id,
                                            targetUnit
                                        ) ?: 0.0
                                    onChange(
                                        row.copy(
                                            fruitId = fruit.id,
                                            fruitNameSnapshot = fruit.name,
                                            unit = targetUnit,
                                            unitWeight = purchaseNumber(lookupUnitWeight(fruit.id, targetUnit) ?: 0.0),
                                            unitPrice = purchaseNumber(remembered),
                                            totalCost =
                                                calculatedTotal(
                                                    row.quantity,
                                                    purchaseNumber(remembered)
                                                ),
                                            priceSource = PurchasePriceSource.UNIT
                                        )
                                    )
                                    fruitMenu = false
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("＋新增商品") },
                            onClick = {
                                fruitMenu = false
                                onAddFruit()
                            }
                        )
                    }
                }

                if (showBuyer) {
                    Box(Modifier.weight(0.9f)) {
                        CompactSelectButton(
                            "采购人",
                            buyerDisplay,
                            Modifier.fillMaxWidth()
                        ) { buyerMenu = true }
                        DropdownMenu(
                            expanded = buyerMenu,
                            onDismissRequest = { buyerMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("未指定") },
                                onClick = {
                                    onChange(
                                        row.copy(
                                            buyerId = null,
                                            buyerNameSnapshot = ""
                                        )
                                    )
                                    buyerMenu = false
                                }
                            )
                            partners.forEach { partner ->
                                DropdownMenuItem(
                                    text = { Text(partner.name) },
                                    onClick = {
                                        onChange(
                                            row.copy(
                                                buyerId = partner.id,
                                                buyerNameSnapshot = partner.name
                                            )
                                        )
                                        buyerMenu = false
                                    }
                                )
                            }
                        }
                    }
                }

                if (showImportInventory) {
                    OutlinedButton(
                        onClick = onImportInventory,
                        modifier = Modifier.height(36.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text(
                            "导入库存",
                            fontSize = 12.sp,
                            maxLines = 1
                        )
                    }
                }

                if (canDelete) {
                    IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                        Text("🗑", fontSize = 16.sp)
                    }
                } else {
                    Spacer(Modifier.width(36.dp))
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                Column(modifier = Modifier.weight(15f)) {
                    Text("库存", style = MaterialTheme.typography.labelSmall, color = Color.Gray, maxLines = 1)
                    Box(
                        Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFFF0F3F5)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(inventoryQuantity?.let { cleanNumber(it) } ?: "0", fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
                PurchaseDefaultNumberField(
                    label = "数量", value = row.quantity, defaultValue = "1",
                    stateKey = "${row.rowId}:quantity", onValue = { updateQuantity(it) },
                    modifier = Modifier.weight(15f)
                )
                Box(Modifier.weight(10f)) {
                    CompactSelectButton("单位", row.unit, Modifier.fillMaxWidth()) { unitMenu = true }
                    DropdownMenu(expanded = unitMenu, onDismissRequest = { unitMenu = false }) {
                        listOf("箱", "筐", "件", "袋").forEach { unit ->
                            DropdownMenuItem(text = { Text(unit) }, onClick = {
                                val remembered = row.fruitId?.let { lookupUnitPrice(it, unit) } ?: 0.0
                                onChange(row.copy(
                                    unit = unit,
                                    unitWeight = purchaseNumber(row.fruitId?.let { lookupUnitWeight(it, unit) } ?: 0.0),
                                    unitPrice = purchaseNumber(remembered),
                                    totalCost = calculatedTotal(row.quantity, purchaseNumber(remembered)),
                                    priceSource = PurchasePriceSource.UNIT
                                ))
                                unitMenu = false
                            })
                        }
                    }
                }
                PurchaseDefaultNumberField(
                    label = "重量", value = row.unitWeight, defaultValue = "0",
                    stateKey = "${row.rowId}:unitWeight",
                    onValue = { onChange(row.copy(unitWeight = it)) },
                    modifier = Modifier.weight(18f)
                )
                PurchaseDefaultNumberField(
                    label = "单价", value = row.unitPrice, defaultValue = "0",
                    stateKey = "${row.rowId}:unitPrice", onValue = { updateUnitPrice(it) },
                    modifier = Modifier.weight(17f)
                )
                CompactNumberField(
                    label = "总价", value = row.totalCost, onValue = { updateTotal(it) },
                    modifier = Modifier.weight(25f)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionScreen(
    db: AppDatabase,
    dataVersion: Int,
    cloudSyncManager: CloudSyncManager,
    currentBook: LedgerBook,
    workDate: String,
    onWorkDateChange: (String) -> Unit,
    onChanged: () -> Unit,
    protectHistoricalAction:
        (String, String, () -> Unit) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenWeather: (String, Long?) -> Unit
) {
    val date = workDate
    val stores = remember(dataVersion) { db.getStores() }
    val partners = remember(dataVersion) { db.getPartners() }

    var storeId by remember { mutableStateOf<Long?>(null) }
    var historicalStoreName by remember { mutableStateOf("") }
    var storeMenu by remember { mutableStateOf(false) }
    var addStoreDialog by remember { mutableStateOf(false) }

    var nextReceiptRowId by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val receiptRows = remember {
        mutableStateListOf(
            ReceiptDraftRow(
                rowId = nextReceiptRowId++,
                partnerId = partners.firstOrNull()?.id,
                partnerNameSnapshot = partners.firstOrNull()?.name.orEmpty()
            )
        )
    }

    var expense by remember { mutableStateOf("") }
    var expensePayerId by remember { mutableStateOf<Long?>(null) }
    var historicalExpensePayerName by remember { mutableStateOf("") }
    var expensePayerMenu by remember { mutableStateOf(false) }
    var openingStock by remember { mutableStateOf("") }
    var closingStock by remember { mutableStateOf("") }
    var newCustomer by remember { mutableStateOf("") }
    var oldCustomer by remember { mutableStateOf("") }
    var actualStartTime by remember { mutableStateOf("16:00") }
    var actualEndTime by remember { mutableStateOf("24:00") }

    var editingRecordId by remember { mutableStateOf<Long?>(null) }
    var newBusinessFormExpanded by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }
    var deleteRecord by remember { mutableStateOf<StoreDailyRecord?>(null) }

    val activeSelectedStore = stores.firstOrNull { it.id == storeId }
    val historicalSelectedStore = if (editingRecordId != null && storeId != null && activeSelectedStore == null) {
        StoreOption(
            storeId!!,
            historicalStoreName.ifBlank { db.getStoreByIdIncludingDeleted(storeId!!)?.name ?: "已删除位置" },
            ""
        )
    } else null
    val selectedStore = activeSelectedStore ?: historicalSelectedStore
    val storeDisplayName = when {
        activeSelectedStore != null -> activeSelectedStore.name
        historicalSelectedStore != null -> "${historicalSelectedStore.name}（已删除）"
        else -> "请添加位置"
    }

    val activeExpensePayer = partners.firstOrNull { it.id == expensePayerId }
    val expensePayerDisplayName = when {
        activeExpensePayer != null -> activeExpensePayer.name
        editingRecordId != null && expensePayerId != null ->
            "${historicalExpensePayerName.ifBlank { db.getPartnerByIdIncludingDeleted(expensePayerId!!)?.name ?: "已删除合伙人" }}（已删除）"
        else -> "未指定"
    }

    val todayRecords =
        remember(
            dataVersion,
            date
        ) {
            db.getDailyRecords(
                date
            )
        }

    val recent7Records =
        remember(
            dataVersion,
            date
        ) {
            db.getRecentBusinessDayRecords(
                beforeDateExclusive =
                    date,
                dayLimit = 7
            )
        }

    val recent7DayGroups =
        remember(recent7Records) {
            recent7Records
                .groupBy { it.date }
                .toList()
                .sortedByDescending { it.first }
        }

    val sharedPurchase = remember(dataVersion, date) { db.getPurchaseTotal(date) }

    fun clearForm(keepDate: Boolean = true) {
        if (!keepDate) onWorkDateChange(LocalDate.now().toString())
        editingRecordId = null
        historicalStoreName = ""
        historicalExpensePayerName = ""
        expense = ""
        closingStock = ""
        newCustomer = ""
        oldCustomer = ""
        receiptRows.clear()
        receiptRows.add(
            ReceiptDraftRow(
                rowId = nextReceiptRowId++,
                partnerId = partners.firstOrNull()?.id,
                partnerNameSnapshot = partners.firstOrNull()?.name.orEmpty()
            )
        )
        expensePayerId = partners.firstOrNull()?.id
        openingStock = ""
        closingStock = ""
        val defaultStore = stores.firstOrNull { it.id == storeId }
        actualStartTime = defaultStore?.defaultStartTime ?: "16:00"
        actualEndTime = defaultStore?.defaultEndTime ?: "24:00"
    }

    fun loadRecord(r: StoreDailyRecord) {
        editingRecordId = r.id
        onWorkDateChange(r.date)
        storeId = r.storeId
        historicalStoreName = r.storeName
        receiptRows.clear()
        val savedSplits =
            if (r.receiptSplits.isNotEmpty()) {
                r.receiptSplits
            } else {
                val legacyId =
                    listOf(
                        r.wechatCollectorId,
                        r.alipayCollectorId,
                        r.cashCollectorId
                    ).firstOrNull { it > 0 } ?: 0L
                val legacyName =
                    listOf(
                        r.wechatCollectorName,
                        r.alipayCollectorName,
                        r.cashCollectorName
                    ).firstOrNull { it.isNotBlank() && it != "未指定" } ?: "未指定"
                if (r.revenue > 0.005) {
                    listOf(ReceiptSplitRecord(legacyId, legacyName, r.revenue))
                } else {
                    emptyList()
                }
            }
        val hasChannelDetails =
            savedSplits.any {
                it.wechatIncome > 0.005 ||
                    it.alipayIncome > 0.005 ||
                    it.cashIncome > 0.005
            }

        if (hasChannelDetails) {
            savedSplits.forEach { split ->
                receiptRows.add(
                    ReceiptDraftRow(
                        rowId = nextReceiptRowId++,
                        partnerId = split.partnerId.takeIf { it > 0L },
                        partnerNameSnapshot = split.partnerName,
                        wechat = cleanNumber(split.wechatIncome),
                        alipay = cleanNumber(split.alipayIncome),
                        cash = cleanNumber(split.cashIncome)
                    )
                )
            }
        } else if (savedSplits.size == 1) {
            val split = savedSplits.first()
            receiptRows.add(
                ReceiptDraftRow(
                    rowId = nextReceiptRowId++,
                    partnerId = split.partnerId.takeIf { it > 0L },
                    partnerNameSnapshot = split.partnerName,
                    wechat = cleanNumber(r.wechatIncome),
                    alipay = cleanNumber(r.alipayIncome),
                    cash = cleanNumber(r.cashIncome)
                )
            )
        } else if (savedSplits.isNotEmpty()) {
            // V1.4.7 初版只保存了“每人总收款”，没有保存每人的微信/支付宝/现金拆分。
            // 为了编辑旧记录时不丢数据，按每人总收款占比临时拆回三个渠道；
            // 总微信/支付宝/现金及每人总额保持不变。新保存后会写入精确渠道明细。
            var leftWechat = r.wechatIncome
            var leftAlipay = r.alipayIncome
            var leftCash = r.cashIncome
            savedSplits.forEachIndexed { index, split ->
                val ratio =
                    if (r.revenue > 0.005) {
                        split.amount / r.revenue
                    } else {
                        0.0
                    }
                val rowWechat =
                    if (index == savedSplits.lastIndex) leftWechat
                    else uiRoundMoney(r.wechatIncome * ratio).also { leftWechat = uiRoundMoney(leftWechat - it) }
                val rowAlipay =
                    if (index == savedSplits.lastIndex) leftAlipay
                    else uiRoundMoney(r.alipayIncome * ratio).also { leftAlipay = uiRoundMoney(leftAlipay - it) }
                val rowCash =
                    if (index == savedSplits.lastIndex) leftCash
                    else uiRoundMoney(r.cashIncome * ratio).also { leftCash = uiRoundMoney(leftCash - it) }

                receiptRows.add(
                    ReceiptDraftRow(
                        rowId = nextReceiptRowId++,
                        partnerId = split.partnerId.takeIf { it > 0L },
                        partnerNameSnapshot = split.partnerName,
                        wechat = cleanNumber(rowWechat),
                        alipay = cleanNumber(rowAlipay),
                        cash = cleanNumber(rowCash)
                    )
                )
            }
        }
        if (receiptRows.isEmpty()) {
            receiptRows.add(
                ReceiptDraftRow(
                    rowId = nextReceiptRowId++,
                    partnerId = partners.firstOrNull()?.id,
                    partnerNameSnapshot = partners.firstOrNull()?.name.orEmpty()
                )
            )
        }
        expense = cleanNumber(r.expense)
        expensePayerId = r.expensePayerId.takeIf { it > 0 }
        historicalExpensePayerName = r.expensePayerName
        openingStock = cleanNumber(r.openingStockValue)
        closingStock = cleanNumber(r.stockLeftValue)
        newCustomer = r.newCustomer.toString()
        oldCustomer = r.oldCustomer.toString()
        val recordStore = stores.firstOrNull { it.id == r.storeId }
        actualStartTime = r.actualStartTime.ifBlank { recordStore?.defaultStartTime ?: "16:00" }
        actualEndTime = r.actualEndTime.ifBlank { recordStore?.defaultEndTime ?: "24:00" }
        val missingMultiReceipt =
            savedSplits.any { it.partnerName == "多人收款明细未同步" }
        message =
            if (missingMultiReceipt) {
                "这条记录的多人收款明细尚未同步，请先让原录入设备升级 FIX1 并完成同步后再修改"
            } else {
                "已载入 ${r.storeName} 的营业记录，可直接修改"
            }
        isError = missingMultiReceipt
    }

    LaunchedEffect(dataVersion, stores.map { it.id }, partners.map { it.id }, editingRecordId) {
        if (editingRecordId == null) {
            if (storeId == null || stores.none { it.id == storeId }) {
                storeId = db.resolveWeatherStore(date).store?.id ?: stores.firstOrNull()?.id
                historicalStoreName = ""
            }
            if (expensePayerId != null && partners.none { it.id == expensePayerId }) {
                expensePayerId = partners.firstOrNull()?.id
                historicalExpensePayerName = ""
            }
        }
    }

    LaunchedEffect(date) {
        if (editingRecordId == null) {
            newBusinessFormExpanded = false
        }
    }

    // V1.4.7.29: the legacy monetary opening/closing inventory fields are no longer
    // part of new business-entry workflow. Historical values stay untouched when editing.
    LaunchedEffect(date, storeId) {
        if (editingRecordId == null) {
            openingStock = ""
            closingStock = ""
        }
    }

    val w = receiptRows.sumOf { it.wechat.toDoubleOrNull() ?: 0.0 }
    val a = receiptRows.sumOf { it.alipay.toDoubleOrNull() ?: 0.0 }
    val c = receiptRows.sumOf { it.cash.toDoubleOrNull() ?: 0.0 }
    val e = expense.toDoubleOrNull() ?: 0.0
    val open = openingStock.toDoubleOrNull() ?: 0.0
    val close = closingStock.toDoubleOrNull() ?: 0.0
    val n = newCustomer.toIntOrNull() ?: 0
    val o = oldCustomer.toIntOrNull() ?: 0
    val revenue = w + a + c
    val contribution = revenue + close - open - e
    val businessWeatherStore =
        stores.firstOrNull { it.id == storeId } ?: db.resolveWeatherStore(date).store
    val actualBusinessWeatherStores = remember(dataVersion, date) {
        db.getBusinessStoresForDate(date)
    }
    val businessWeatherStores =
        if (actualBusinessWeatherStores.isNotEmpty()) actualBusinessWeatherStores
        else listOfNotNull(businessWeatherStore)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        item {
            BusinessDateHeader(
                pageTitle = "营业",
                date = date,
                onDate = onWorkDateChange
            )
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                businessWeatherStores.distinctBy { it.id }.forEach { weatherStore ->
                    BusinessWeatherCard(
                        db = db,
                        date = date,
                        store = weatherStore,
                        currentBook = currentBook,
                        cloudSyncManager = cloudSyncManager,
                        onOpenDetail = onOpenWeather
                    )
                }
            }
        }

        if (editingRecordId != null) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF7D9))) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("正在编辑营业记录 #$editingRecordId", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                        TextButton(onClick = {
                            clearForm()
                            newBusinessFormExpanded = false
                            message = "已取消编辑"
                            isError = false
                        }) { Text("取消编辑") }
                    }
                }
            }
        }

        if (todayRecords.isNotEmpty()) {
            item {
                Text(
                    "当天营业记录",
                    fontWeight =
                        FontWeight.Bold,
                    style =
                        MaterialTheme
                            .typography
                            .bodyMedium
                )
            }

            items(
                todayRecords,
                key = {
                    "today_business_${it.id}"
                }
            ) {
                record ->
                Card(
                    onClick = {
                        protectHistoricalAction(
                            record.date,
                            "修改 ${record.date} · ${record.storeName} 营业记录"
                        ) {
                            loadRecord(
                                record
                            )
                        }
                    },
                    modifier =
                        Modifier.fillMaxWidth(),
                    colors =
                        CardDefaults.cardColors(
                            containerColor =
                                if (
                                    editingRecordId ==
                                    record.id
                                ) {
                                    Color(
                                        0xFFE9F8F0
                                    )
                                } else {
                                    Color(
                                        0xFFF7F7F8
                                    )
                                }
                        )
                ) {
                    Column(
                        Modifier.padding(
                            horizontal = 12.dp,
                            vertical = 9.dp
                        ),
                        verticalArrangement =
                            Arrangement.spacedBy(
                                5.dp
                            )
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment =
                                Alignment.CenterVertically
                        ) {
                            Text(
                                if (
                                    record.storeName ==
                                    "共用货品"
                                ) {
                                    "未知位置（旧数据）"
                                } else {
                                    record.storeName
                                },
                                modifier =
                                    Modifier.weight(
                                        1f
                                    ),
                                fontWeight =
                                    FontWeight.Bold,
                                maxLines = 1
                            )

                            Text(
                                "营业额 ${money(record.revenue)}",
                                color =
                                    BrandGreen,
                                fontWeight =
                                    FontWeight.SemiBold
                            )
                        }

                        val receiptSummary =
                            if (record.receiptSplits.isNotEmpty()) {
                                record.receiptSplits
                                    .joinToString(" · ") { split ->
                                        "${split.partnerName} ${money(split.amount)}"
                                    }
                            } else {
                                val legacyName =
                                    listOf(
                                        record.wechatCollectorName,
                                        record.alipayCollectorName,
                                        record.cashCollectorName
                                    ).firstOrNull { it.isNotBlank() && it != "未指定" }.orEmpty()
                                if (legacyName.isNotBlank() && record.revenue > 0) {
                                    "$legacyName ${money(record.revenue)}"
                                } else {
                                    ""
                                }
                            }

                        Text(
                            "微信 ${money(record.wechatIncome)}   " +
                                "支付宝 ${money(record.alipayIncome)}   " +
                                "现金 ${money(record.cashIncome)}" +
                                if (receiptSummary.isNotBlank()) {
                                    "   ·   $receiptSummary"
                                } else {
                                    ""
                                },
                            style =
                                MaterialTheme
                                    .typography
                                    .bodySmall,
                            color =
                                Color.DarkGray,
                            maxLines = 2
                        )
                    }
                }
            }
        }

        val showBusinessEntryForm =
            editingRecordId != null ||
                todayRecords.isEmpty() ||
                newBusinessFormExpanded

        if (!showBusinessEntryForm) {
            item {
                OutlinedButton(
                    onClick = {
                        clearForm()
                        newBusinessFormExpanded = true
                        message = ""
                        isError = false
                    },
                    modifier = Modifier.fillMaxWidth().height(44.dp)
                ) {
                    Text("＋ 新增营业记录")
                }
            }
        } else {

        item {
            Box(Modifier.fillMaxWidth()) {
                CompactSelectButton(
                    "位置",
                    storeDisplayName,
                    Modifier.fillMaxWidth()
                ) { storeMenu = true }

                DropdownMenu(expanded = storeMenu, onDismissRequest = { storeMenu = false }) {
                    stores.forEach { s ->
                        DropdownMenuItem(
                            text = { Text(s.name) },
                            onClick = {
                                storeId = s.id
                                historicalStoreName = ""
                                actualStartTime = s.defaultStartTime
                                actualEndTime = s.defaultEndTime
                                storeMenu = false
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("＋新增位置") },
                        onClick = {
                            storeMenu = false
                            addStoreDialog = true
                        }
                    )
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StoreTimePickerField("实际开始", actualStartTime, false, Modifier.weight(1f)) { actualStartTime = it }
                StoreTimePickerField("实际结束", actualEndTime, true, Modifier.weight(1f)) { actualEndTime = it }
            }
            Text(
                "历史天气按实际开始前3小时 → 实际结束保存；当天多位置分别记录。",
                style = MaterialTheme.typography.labelSmall, color = Color.Gray
            )
        }

        if (receiptRows.size > 1) {
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "收款明细",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "营业额 ${money(revenue)}",
                        color = BrandGreen,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CompactReadOnlyField("微信合计", money(w), Modifier.weight(1f))
                    CompactReadOnlyField("支付宝合计", money(a), Modifier.weight(1f))
                    CompactReadOnlyField("现金合计", money(c), Modifier.weight(1f))
                }
            }
        }

        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                CompactNumberField(
                    "日常开销",
                    expense,
                    { expense = it },
                    Modifier.weight(1f)
                )

                Box(Modifier.weight(1.15f)) {
                    CompactSelectButton(
                        "费用付款人",
                        expensePayerDisplayName,
                        Modifier.fillMaxWidth()
                    ) { expensePayerMenu = true }

                    DropdownMenu(
                        expanded = expensePayerMenu,
                        onDismissRequest = { expensePayerMenu = false }
                    ) {
                        partners.forEach { p ->
                            DropdownMenuItem(
                                text = { Text(p.name) },
                                onClick = {
                                    expensePayerId = p.id
                                    historicalExpensePayerName = ""
                                    expensePayerMenu = false
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("未指定") },
                            onClick = {
                                expensePayerId = null
                                historicalExpensePayerName = ""
                                expensePayerMenu = false
                            }
                        )
                    }
                }

            }
        }

        item {
            receiptRows.forEachIndexed { index, row ->
                ReceiptSplitDraftRow(
                    row = row,
                    partners = partners,
                    historical = editingRecordId != null,
                    showSubtotal = receiptRows.size > 1,
                    canDelete = index > 0,
                    onChange = { updated ->
                        val target = receiptRows.indexOfFirst { it.rowId == updated.rowId }
                        if (target >= 0) receiptRows[target] = updated
                    },
                    onDelete = {
                        val target = receiptRows.indexOfFirst { it.rowId == row.rowId }
                        if (target >= 0) receiptRows.removeAt(target)
                    }
                )
                if (index != receiptRows.lastIndex) Spacer(Modifier.height(5.dp))
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.End
            ) {
                OutlinedButton(
                    onClick = {
                        val unused =
                            partners.firstOrNull { p ->
                                receiptRows.none { it.partnerId == p.id }
                            }
                        receiptRows.add(
                            ReceiptDraftRow(
                                rowId = nextReceiptRowId++,
                                partnerId = unused?.id,
                                partnerNameSnapshot = unused?.name.orEmpty()
                            )
                        )
                    },
                    modifier = Modifier.height(30.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    enabled = partners.isNotEmpty()
                ) {
                    Text(
                        if (receiptRows.size > 1) "＋ 再添加" else "＋ 添加收款人",
                        fontSize = 12.sp
                    )
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CompactIntegerField("新客", newCustomer, { newCustomer = it }, Modifier.weight(1f))
                CompactIntegerField("老客", oldCustomer, { oldCustomer = it }, Modifier.weight(1f))
                CompactReadOnlyField("客户合计", "${n + o}", Modifier.weight(1f))
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MiniSummaryCard("本摊营业额", money(revenue), Modifier.weight(1f), SoftGreen)
                MiniSummaryCard("经营贡献*", money(contribution), Modifier.weight(1f), SoftOrange)
            }
            Text(
                "*经营贡献尚未扣除共用进货；今日共用进货 ${money(sharedPurchase)}，最终利润以“结算/首页”为准。",
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray,
                modifier = Modifier.padding(top = 3.dp)
            )
        }

        item {
            Button(
                onClick = {
                    val requestedStore = storeId?.let { id ->
                        db.getStoreById(id) ?: if (editingRecordId != null) {
                            StoreOption(
                                id,
                                historicalStoreName.ifBlank { db.getStoreByIdIncludingDeleted(id)?.name ?: "已删除位置" },
                                ""
                            )
                        } else null
                    }
                    if (requestedStore == null) {
                        message = "保存失败：请选择有效位置"
                        isError = true
                        return@Button
                    }

                    val normalizedReceiptRows =
                        receiptRows.filter { it.hasIncome }

                    if (revenue > 0.005 && normalizedReceiptRows.isEmpty()) {
                        message = "保存失败：请填写至少一位收款人的微信、支付宝或现金"
                        isError = true
                        return@Button
                    }

                    if (normalizedReceiptRows.any { it.partnerId == null }) {
                        message = "保存失败：请为每一组有收款金额的记录选择收款人"
                        isError = true
                        return@Button
                    }

                    val requestedReceiptSplits =
                        normalizedReceiptRows
                            .map { row ->
                                val active = row.partnerId?.let { db.getPartnerById(it) }
                                val historical =
                                    if (editingRecordId != null && row.partnerId != null) {
                                        db.getPartnerByIdIncludingDeleted(row.partnerId!!)
                                    } else {
                                        null
                                    }
                                val rowWechat = row.wechat.toDoubleOrNull() ?: 0.0
                                val rowAlipay = row.alipay.toDoubleOrNull() ?: 0.0
                                val rowCash = row.cash.toDoubleOrNull() ?: 0.0
                                ReceiptSplitRecord(
                                    partnerId = active?.id ?: historical?.id ?: 0L,
                                    partnerName =
                                        active?.name
                                            ?: historical?.name
                                            ?: row.partnerNameSnapshot.ifBlank { "未指定" },
                                    amount = rowWechat + rowAlipay + rowCash,
                                    wechatIncome = rowWechat,
                                    alipayIncome = rowAlipay,
                                    cashIncome = rowCash
                                )
                            }
                            .groupBy { it.partnerId to it.partnerName }
                            .map { (key, parts) ->
                                val rowWechat = parts.sumOf { it.wechatIncome }
                                val rowAlipay = parts.sumOf { it.alipayIncome }
                                val rowCash = parts.sumOf { it.cashIncome }
                                ReceiptSplitRecord(
                                    partnerId = key.first,
                                    partnerName = key.second,
                                    amount = rowWechat + rowAlipay + rowCash,
                                    wechatIncome = rowWechat,
                                    alipayIncome = rowAlipay,
                                    cashIncome = rowCash
                                )
                            }
                    val legacyCollector =
                        requestedReceiptSplits.singleOrNull()?.let { split ->
                            split.partnerId.takeIf { it > 0L }?.let { id ->
                                PartnerOption(id, split.partnerName)
                            }
                        }

                    val requestedExpensePayer = expensePayerId?.let { id ->
                        db.getPartnerById(id) ?: if (editingRecordId != null) {
                            PartnerOption(
                                id,
                                historicalExpensePayerName.ifBlank { db.getPartnerByIdIncludingDeleted(id)?.name ?: "已删除合伙人" }
                            )
                        } else null
                    }
                    if (e > 0 && expensePayerId != null && requestedExpensePayer == null) {
                        message = "保存失败：费用付款人已失效，请重新选择"
                        isError = true
                        return@Button
                    }

                    val result = db.saveStoreDailyRecord(
                        recordId = editingRecordId,
                        date = date,
                        store = requestedStore,
                        wechat = w,
                        wechatCollector = legacyCollector,
                        alipay = a,
                        alipayCollector = legacyCollector,
                        cash = c,
                        cashCollector = legacyCollector,
                        receiptSplits = requestedReceiptSplits,
                        expense = e,
                        expensePayer = requestedExpensePayer,
                        openingStock = open,
                        closingStock = close,
                        newCustomer = n,
                        oldCustomer = o,
                        actualStartTime = actualStartTime,
                        actualEndTime = actualEndTime
                    )

                    message = result.message
                    isError = !result.success

                    if (result.success) {
                        // 保存完成后立即退出编辑模式，避免继续显示“正在编辑营业记录”。
                        clearForm()
                        newBusinessFormExpanded = false
                        onChanged()
                    }
                },
                modifier = Modifier.fillMaxWidth().height(44.dp),
                contentPadding = PaddingValues(vertical = 5.dp)
            ) {
                Text(if (editingRecordId == null) "保存营业记录" else "保存修改")
            }

            if (message.isNotBlank()) {
                Text(
                    message,
                    color = if (isError) MaterialTheme.colorScheme.error else BrandGreen,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
        }
        }


        item {
            HorizontalDivider(
                modifier =
                    Modifier.padding(
                        top = 4.dp
                    )
            )
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment =
                    Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onOpenHistory,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(
                        "营业历史  ›",
                        modifier = Modifier.fillMaxWidth(),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Text(
                    "最近7个营业日",
                    style =
                        MaterialTheme
                            .typography
                            .bodySmall,
                    color = Color.Gray
                )
            }
        }

        if (recent7Records.isEmpty()) {
            item {
                Text(
                    "暂无历史营业记录",
                    color = Color.Gray,
                    style =
                        MaterialTheme
                            .typography
                            .bodySmall
                )
            }
        }

        items(
            recent7DayGroups,
            key = { (dayDate, _) ->
                "recent_business_day_$dayDate"
            }
        ) { (dayDate, dayRecords) ->
            val dayRevenue = dayRecords.sumOf { it.revenue }
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFFCFCFC)
                )
            ) {
                Column(
                    Modifier.padding(
                        horizontal = 12.dp,
                        vertical = 9.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${dayDate.takeLast(5)} · " +
                                runCatching {
                                    chineseWeekday(LocalDate.parse(dayDate))
                                }.getOrDefault(""),
                            modifier = Modifier.weight(1f),
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "${dayRecords.size}个位置 · ${money(dayRevenue)}",
                            color = BrandGreen,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    dayRecords.forEachIndexed { index, record ->
                        if (index > 0) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                            )
                        }

                        Column(
                            Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        protectHistoricalAction(
                                            record.date,
                                            "修改 ${record.date} · ${record.storeName} 营业记录"
                                        ) {
                                            loadRecord(record)
                                        }
                                    },
                                    onLongClick = {
                                        protectHistoricalAction(
                                            record.date,
                                            "删除 ${record.date} · ${record.storeName} 营业记录"
                                        ) {
                                            deleteRecord = record
                                        }
                                    }
                                )
                                .padding(vertical = 3.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    if (record.storeName == "共用货品") {
                                        "未知位置（旧数据）"
                                    } else {
                                        record.storeName
                                    },
                                    modifier = Modifier.weight(1f),
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    "营业额 ${money(record.revenue)}",
                                    color = BrandGreen,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            val receiptSummary =
                                if (record.receiptSplits.isNotEmpty()) {
                                    record.receiptSplits
                                        .joinToString(" · ") { split ->
                                            "${split.partnerName} ${money(split.amount)}"
                                        }
                                } else {
                                    val legacyName =
                                        listOf(
                                            record.wechatCollectorName,
                                            record.alipayCollectorName,
                                            record.cashCollectorName
                                        ).firstOrNull {
                                            it.isNotBlank() && it != "未指定"
                                        }.orEmpty()
                                    if (legacyName.isNotBlank() && record.revenue > 0) {
                                        "$legacyName ${money(record.revenue)}"
                                    } else {
                                        ""
                                    }
                                }

                            Text(
                                "微信 ${money(record.wechatIncome)}   " +
                                    "支付宝 ${money(record.alipayIncome)}   " +
                                    "现金 ${money(record.cashIncome)}" +
                                    if (receiptSummary.isNotBlank()) {
                                        "   ·   $receiptSummary"
                                    } else {
                                        ""
                                    },
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray,
                                maxLines = 2
                            )
                        }
                    }

                    Text(
                        "点位置编辑 · 长按位置删除",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray
                    )
                }
            }
        }

    }

    if (addStoreDialog) {
        AddStoreDialog(
            onDismiss = { addStoreDialog = false },
            initial = null
        ) { name, address, latitude, longitude, startTime, endTime ->
            val newId = db.addStore(name, address, latitude, longitude, startTime, endTime)
            addStoreDialog = false
            if (newId > 0) storeId = newId
            onChanged()
        }
    }

    deleteRecord?.let { r ->
        ConfirmDelete(
            "删除 ${r.date} ${r.storeName} 的营业记录？删除后该日旧利润分配/资金结算也会自动作废。",
            { deleteRecord = null }
        ) {
            if (db.deleteStoreDailyRecord(r.id)) {
                if (editingRecordId == r.id) clearForm()
                message = "营业记录已删除；该日需要重新确认利润分配/结算"
                isError = false
                onChanged()
            } else {
                message = "删除失败：记录不存在"
                isError = true
            }
            deleteRecord = null
        }
    }
}

@Composable
private fun ReceiptSplitDraftRow(
    row: ReceiptDraftRow,
    partners: List<PartnerOption>,
    historical: Boolean,
    showSubtotal: Boolean,
    canDelete: Boolean,
    onChange: (ReceiptDraftRow) -> Unit,
    onDelete: () -> Unit
) {
    var menu by remember(row.rowId) { mutableStateOf(false) }
    val selected = partners.firstOrNull { it.id == row.partnerId }
    val display =
        when {
            selected != null -> selected.name
            row.partnerId != null && historical && row.partnerNameSnapshot.isNotBlank() ->
                "${row.partnerNameSnapshot}（已删除）"
            row.partnerNameSnapshot.isNotBlank() -> row.partnerNameSnapshot
            else -> "请选择收款人"
        }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFF8FAF9)
        )
    ) {
        Column(
            Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                Box(Modifier.weight(1.25f)) {
                    CompactSelectButton(
                        "收款人",
                        display,
                        Modifier.fillMaxWidth()
                    ) { menu = true }
                    DropdownMenu(
                        expanded = menu,
                        onDismissRequest = { menu = false }
                    ) {
                        partners.forEach { partner ->
                            DropdownMenuItem(
                                text = { Text(partner.name) },
                                onClick = {
                                    onChange(
                                        row.copy(
                                            partnerId = partner.id,
                                            partnerNameSnapshot = partner.name
                                        )
                                    )
                                    menu = false
                                }
                            )
                        }
                    }
                }

                CompactNumberField(
                    "微信",
                    row.wechat,
                    { onChange(row.copy(wechat = it)) },
                    Modifier.weight(1f)
                )
                CompactNumberField(
                    "支付宝",
                    row.alipay,
                    { onChange(row.copy(alipay = it)) },
                    Modifier.weight(1f)
                )
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    if (showSubtotal) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(15.dp),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "小计 ${money(row.total)}",
                                fontSize = 9.sp,
                                color = BrandGreen,
                                maxLines = 1
                            )
                            if (canDelete) {
                                Spacer(Modifier.width(3.dp))
                                Text(
                                    "删除",
                                    fontSize = 9.sp,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.clickable(onClick = onDelete)
                                )
                            }
                        }
                    }
                    CompactNumberField(
                        "现金",
                        row.cash,
                        { onChange(row.copy(cash = it)) },
                        Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun MoneyCollectorRow(
    label: String,
    amount: String,
    onAmount: (String) -> Unit,
    partners: List<PartnerOption>,
    selectedId: Long?,
    onSelected: (Long?) -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        NumberField(label, amount, onAmount, Modifier.weight(1f))
        Box(Modifier.weight(1.15f)) {
            OutlinedButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth()) {
                Text(partners.firstOrNull { it.id == selectedId }?.name ?: "收款人", maxLines = 1)
                Spacer(Modifier.weight(1f)); Text("▼")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                partners.forEach { p -> DropdownMenuItem(text = { Text(p.name) }, onClick = { onSelected(p.id); menu = false }) }
                DropdownMenuItem(text = { Text("未指定") }, onClick = { onSelected(null); menu = false })
            }
        }
    }
}

@Composable
private fun SettlementScreen(
    db: AppDatabase,
    dataVersion: Int,
    workDate: String,
    onWorkDateChange: (String) -> Unit,
    protectHistoricalAction: (String, String, () -> Unit) -> Unit,
    onChanged: () -> Unit
) {
    var view by remember { mutableStateOf(SettlementView.DAY) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SettlementView.entries.forEach { item ->
                if (view == item) {
                    Button(
                        onClick = { view = item },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) {
                        Text(item.label, fontSize = 12.sp)
                    }
                } else {
                    OutlinedButton(
                        onClick = { view = item },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) {
                        Text(item.label, fontSize = 12.sp)
                    }
                }
            }
        }

        when (view) {
            SettlementView.DAY ->
                SettlementDayContent(
                    db = db,
                    dataVersion = dataVersion,
                    workDate = workDate,
                    onWorkDateChange = onWorkDateChange,
                    protectHistoricalAction = protectHistoricalAction,
                    onChanged = onChanged
                )

            SettlementView.BATCH ->
                SettlementBatchContent(
                    db = db,
                    dataVersion = dataVersion,
                    onChanged = onChanged
                )

            SettlementView.STATS ->
                SettlementStatsContent(
                    db = db,
                    dataVersion = dataVersion,
                    protectHistoricalAction = protectHistoricalAction,
                    onChanged = onChanged
                )
        }
    }
}

private fun buildDailySettlementShareLines(
    date: String,
    summary: DailySummary,
    profitRows: List<ProfitDistributionRecord>,
    bundle: CashSettlementBundle,
    settlementCenter: PartnerOption?
): List<ReportLine> {
    val lines = mutableListOf<ReportLine>()
    val statusText =
        when (bundle.settlement.status) {
            1 -> "已结清"
            2 -> "部分结算"
            else -> "未结算"
        }

    lines += ReportLine("天鲜果业", ReportLineStyle.TITLE)
    lines += ReportLine("当日结算分享", ReportLineStyle.SUBTITLE)
    lines += ReportLine("结算日期：$date  ·  状态：$statusText", ReportLineStyle.MUTED)
    lines += ReportLine("", ReportLineStyle.SPACER)

    lines += ReportLine("当日经营汇总", ReportLineStyle.SECTION)
    lines += ReportLine("营业额：${money(summary.revenue)}", ReportLineStyle.TOTAL)
    lines += ReportLine("采购：${money(summary.purchaseCost)}  ·  费用：${money(summary.expense)}")
    lines += ReportLine(
        if (summary.profit < -0.005) {
            "当日亏损：${money(-summary.profit)}"
        } else {
            "当日利润：${money(summary.profit)}"
        },
        if (summary.profit < -0.005) ReportLineStyle.NEGATIVE else ReportLineStyle.POSITIVE
    )
    lines += ReportLine("客户：${summary.customers}  ·  营业位置数：${summary.storeCount}", ReportLineStyle.MUTED)

    lines += ReportLine("", ReportLineStyle.SPACER)
    lines += ReportLine(
        if (summary.profit < -0.005) "当日亏损分担" else "当日利润分配",
        ReportLineStyle.SECTION
    )
    if (profitRows.isEmpty()) {
        lines += ReportLine("暂无已保存的利润 / 亏损分配", ReportLineStyle.WARNING)
    } else {
        profitRows.sortedBy { it.partnerId }.forEach { row ->
            val amountText =
                if (row.allocatedProfit < -0.005) {
                    "分担 ${money(-row.allocatedProfit)}"
                } else {
                    "应分 ${money(row.allocatedProfit)}"
                }
            lines += ReportLine(
                "${row.partnerName}  ${fmt(profitRatioPercent(row.ratio))}%  ·  $amountText",
                if (row.allocatedProfit < -0.005) {
                    ReportLineStyle.NEGATIVE
                } else {
                    ReportLineStyle.POSITIVE
                }
            )
        }
    }

    lines += ReportLine("", ReportLineStyle.SPACER)
    lines += ReportLine("资金轧差方案", ReportLineStyle.SECTION)
    settlementCenter?.let {
        lines += ReportLine("资金中心：${it.name}", ReportLineStyle.MUTED)
    }
    bundle.partners.sortedBy { it.partnerId }.forEach { partner ->
        val balanceText =
            when {
                partner.balance > 0.005 -> "应收 ${money(partner.balance)}"
                partner.balance < -0.005 -> "应补 ${money(-partner.balance)}"
                else -> "已平"
            }
        lines += ReportLine(
            "${partner.partnerName}  ·  $balanceText",
            when {
                partner.balance > 0.005 -> ReportLineStyle.POSITIVE
                partner.balance < -0.005 -> ReportLineStyle.NEGATIVE
                else -> ReportLineStyle.NORMAL
            }
        )
        lines += ReportLine(
            "采购垫付 ${money(partner.purchasePaid)}  + 费用垫付 ${money(partner.expensePaid)}  + 利润/亏损 ${money(partner.profitShare)}  - 已收 ${money(partner.revenueReceived)}"
        )
        lines += ReportLine("最终应留：${money(partner.shouldKeep)}", ReportLineStyle.MUTED)
    }

    lines += ReportLine("", ReportLineStyle.SPACER)
    lines += ReportLine("最少转账方案", ReportLineStyle.SECTION)
    if (bundle.transfers.isEmpty()) {
        lines += ReportLine("无需转账，资金已平衡", ReportLineStyle.POSITIVE)
    } else {
        bundle.transfers.forEach { transfer ->
            val state =
                when {
                    transfer.pendingAmount <= 0.005 -> "已结清"
                    transfer.settledAmount > 0.005 ->
                        "已结 ${money(transfer.settledAmount)} · 剩 ${money(transfer.pendingAmount)}"
                    else -> "未结算"
                }
            lines += ReportLine(
                "${transfer.fromPartnerName} → ${transfer.toPartnerName}  ${money(transfer.amount)}  · $state",
                if (transfer.pendingAmount <= 0.005) {
                    ReportLineStyle.POSITIVE
                } else {
                    ReportLineStyle.TOTAL
                }
            )
        }
    }

    return lines
}

private fun reportLinesToPlainText(lines: List<ReportLine>): String =
    lines.filter { it.style != ReportLineStyle.SPACER }
        .joinToString("\n") { it.text }

@Composable
private fun SettlementDayContent(
    db: AppDatabase,
    dataVersion: Int,
    workDate: String,
    onWorkDateChange: (String) -> Unit,
    protectHistoricalAction: (String, String, () -> Unit) -> Unit,
    onChanged: () -> Unit
) {
    val context = LocalContext.current
    val date = workDate
    var message by remember { mutableStateOf("") }
    var deleteId by remember { mutableStateOf<Long?>(null) }
    var settleTransfer by remember {
        mutableStateOf<SettlementTransferRecord?>(null)
    }
    var settleInput by remember {
        mutableStateOf("")
    }
    var settleInputKey by remember {
        mutableStateOf(0L)
    }

    val summary = remember(dataVersion, date) {
        db.getDailySummary(date)
    }
    val purchases = remember(dataVersion, date) {
        db.getPurchaseTotalsByPartner(date)
    }
    val receipts = remember(dataVersion, date) {
        db.getReceiptsByPartner(date)
    }
    val expenses = remember(dataVersion, date) {
        db.getExpenseTotalsByPartner(date)
    }
    val profitRows = remember(dataVersion, date) {
        db.getProfitDistribution(date)
    }
    val bundle = remember(dataVersion, date) {
        db.getCashSettlement(date)
    }
    val settlementCenter = remember(dataVersion) {
        db.getSettlementCenter()
    }

    LaunchedEffect(
        dataVersion,
        date,
        bundle?.settlement?.id,
        settlementCenter?.id
    ) {
        val currentBundle = bundle
        val center = settlementCenter
        if (
            currentBundle != null &&
            center != null &&
            currentBundle.transfers.isNotEmpty() &&
            currentBundle.transfers.none {
                it.settledAmount > 0.005
            } &&
            currentBundle.transfers.any {
                it.fromPartnerId != center.id &&
                    it.toPartnerId != center.id
            }
        ) {
            val regenerated =
                db.generateCashSettlement(date)
            if (regenerated.success) {
                onChanged()
            }
        }
    }

    LaunchedEffect(
        dataVersion,
        date,
        summary.profit,
        profitRows.size
    ) {
        if (profitRows.isEmpty() && kotlin.math.abs(summary.profit) > 0.005) {
            val rules = db.getProfitRules()
            if (rules.isNotEmpty()) {
                val partners = db.getPartners()
                val allocations =
                    rules.mapNotNull { rule ->
                        partners
                            .firstOrNull { it.id == rule.partnerId }
                            ?.let { p -> p to rule.percent }
                    }

                if (
                    allocations.isNotEmpty() &&
                    kotlin.math.abs(
                        allocations.sumOf { it.second } - 100.0
                    ) < 0.01
                ) {
                    if (
                        db.saveProfitDistribution(
                            date,
                            allocations
                        )
                    ) {
                        onChanged()
                    }
                }
            }
        }
    }

    val history =
        remember(dataVersion) {
            db.getRecentCashSettlements(20)
        }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            horizontal = 14.dp,
            vertical = 4.dp
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            PageHeader(
                "当日结算",
                "先确认当日利润 / 亏损分担，再按资金中心执行实际转账"
            )
        }

        item {
            CompactDateNavigator(
                label = null,
                date = date,
                modifier = Modifier.fillMaxWidth(),
                chineseDisplay = true,
                showWeekday = true
            ) {
                onWorkDateChange(it)
                message = ""
            }
        }

        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MiniSummaryCard(
                    "营业额",
                    money(summary.revenue),
                    Modifier.weight(1f),
                    SoftGreen
                )
                MiniSummaryCard(
                    "进货",
                    money(summary.purchaseCost),
                    Modifier.weight(1f),
                    SoftPurple
                )
                MiniSummaryCard(
                    "利润",
                    money(summary.profit),
                    Modifier.weight(1f),
                    SoftOrange
                )
            }
        }

        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        val currentBundle = bundle
                        if (currentBundle == null) {
                            message = "请先生成当日资金轧差方案后再分享"
                        } else {
                            runCatching {
                                val lines = buildDailySettlementShareLines(
                                    date = date,
                                    summary = summary,
                                    profitRows = profitRows,
                                    bundle = currentBundle,
                                    settlementCenter = settlementCenter
                                )
                                ReportGenerator.createPng(
                                    context = context,
                                    baseName = "天鲜果业_当日结算_${date.replace("-", "")}",
                                    lines = lines
                                )
                            }.onSuccess { report ->
                                runCatching {
                                    ReportGenerator.share(context, report)
                                }.onSuccess {
                                    message = "已打开当日结算图片分享"
                                }.onFailure {
                                    message = "分享失败：${it.message ?: "未知错误"}"
                                }
                            }.onFailure {
                                message = "分享图片生成失败：${it.message ?: "未知错误"}"
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = bundle != null
                ) {
                    Text("分享图片")
                }

                OutlinedButton(
                    onClick = {
                        val currentBundle = bundle
                        if (currentBundle == null) {
                            message = "请先生成当日资金轧差方案后再分享"
                        } else {
                            runCatching {
                                val lines = buildDailySettlementShareLines(
                                    date = date,
                                    summary = summary,
                                    profitRows = profitRows,
                                    bundle = currentBundle,
                                    settlementCenter = settlementCenter
                                )
                                ReportGenerator.shareText(
                                    context = context,
                                    text = reportLinesToPlainText(lines),
                                    title = "分享当日结算"
                                )
                            }.onSuccess {
                                message = "已打开当日结算文本分享"
                            }.onFailure {
                                message = "分享失败：${it.message ?: "未知错误"}"
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = bundle != null
                ) {
                    Text("分享文本")
                }
            }
        }

        item {
            Text(
                if (summary.profit < -0.005) {
                    "当日亏损分担"
                } else {
                    "当日利润分担"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        if (profitRows.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFF8FAFC)
                    )
                ) {
                    Text(
                        when {
                            summary.profit > 0.005 ->
                                "当天利润分配正在生成或尚未保存。"
                            summary.profit < -0.005 ->
                                "当天为亏损，请先保存亏损分担比例。"
                            else ->
                                "当天利润为0，无需利润 / 亏损分担。"
                        },
                        modifier = Modifier.padding(12.dp),
                        color = Color.Gray
                    )
                }
            }
        } else {
            items(
                profitRows,
                // V1.4.7.22: old multi-device data can contain more than one
                // active cloud row for the same date + partner. Using that
                // logical pair as a Compose key crashes LazyColumn with
                // "Key ... was already used". The local SQLite id is unique
                // even when legacy logical duplicates exist.
                key = { "profit_distribution_${it.id}" }
            ) { row ->
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                row.partnerName,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "${fmt(profitRatioPercent(row.ratio))}%",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                        }
                        Text(
                            when {
                                row.allocatedProfit > 0.005 ->
                                    "+${money(row.allocatedProfit)}"
                                row.allocatedProfit < -0.005 ->
                                    "-${money(-row.allocatedProfit)}"
                                else -> money(0.0)
                            },
                            fontWeight = FontWeight.Bold,
                            color =
                                if (row.allocatedProfit < -0.005) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    BrandGreen
                                }
                        )
                    }
                }
            }
        }

        if (message.isNotBlank()) {
            item {
                Text(
                    message,
                    color =
                        if (
                            message.contains("已确认") ||
                            message.contains("已生成") ||
                            message.contains("成功")
                        ) {
                            BrandGreen
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        item {
            HorizontalDivider()

            Text(
                "资金轧差方案",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                "这里计算当天采购垫付、费用、收款和利润/亏损形成的资金差额；当天未结清的金额会继续进入“资金余额”。",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
        }

        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFF8FAFC)
                )
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "结算前核对",
                        fontWeight = FontWeight.Bold
                    )
                    SummaryRow(
                        "进货垫付合计",
                        money(purchases.sumOf { it.amount })
                    )
                    SummaryRow(
                        "实际收款合计",
                        money(receipts.sumOf { it.amount })
                    )
                    SummaryRow(
                        "费用垫付合计",
                        money(expenses.sumOf { it.amount })
                    )
                    SummaryRow(
                        "已保存利润/亏损分担",
                        money(
                            profitRows.sumOf {
                                it.allocatedProfit
                            }
                        )
                    )
                }
            }
        }

        item {
            Button(
                onClick = {
                    val result =
                        db.generateCashSettlement(date)
                    message = result.message
                    if (result.success) onChanged()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (bundle == null) {
                        "生成当日资金轧差方案"
                    } else {
                        "重新生成资金轧差方案"
                    }
                )
            }
        }

        bundle?.let { b ->
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "每人最终余额",
                        style =
                            MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                when (b.settlement.status) {
                                    1 -> "已结清"
                                    2 -> "部分结算"
                                    else -> "未结算"
                                }
                            )
                        }
                    )
                }
            }

            items(
                b.partners,
                key = { "cp${it.id}" }
            ) { p ->
                val relatedTransfers =
                    if (p.balance >= 0) {
                        b.transfers.filter {
                            it.toPartnerId == p.partnerId
                        }
                    } else {
                        b.transfers.filter {
                            it.fromPartnerId == p.partnerId
                        }
                    }
                val settledAmount =
                    relatedTransfers.sumOf {
                        it.settledAmount
                    }
                val totalNeed =
                    kotlin.math.abs(p.balance)
                val remainingAmount =
                    (totalNeed - settledAmount)
                        .coerceAtLeast(0.0)

                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(11.dp),
                        verticalArrangement =
                            Arrangement.spacedBy(4.dp)
                    ) {
                        Row {
                            Text(
                                p.partnerName,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                when {
                                    p.balance > 0.005 ->
                                        "应收 ${money(p.balance)}"

                                    p.balance < -0.005 ->
                                        "应补 ${money(-p.balance)}"

                                    else ->
                                        "已平"
                                },
                                fontWeight = FontWeight.Bold,
                                color =
                                    if (p.balance > 0.005) {
                                        BrandGreen
                                    } else if (
                                        p.balance < -0.005
                                    ) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        Color.Gray
                                    }
                            )
                        }

                        Text(
                            "采购垫付 +${money(p.purchasePaid)} · " +
                                "费用 +${money(p.expensePaid)} · " +
                                if (p.profitShare < -0.005) {
                                    "亏损 -${money(-p.profitShare)} · 已收 -${money(p.revenueReceived)}"
                                } else {
                                    "利润 +${money(p.profitShare)} · 已收 -${money(p.revenueReceived)}"
                                },
                            style =
                                MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )

                        if (totalNeed > 0.005) {
                            Text(
                                when {
                                    settledAmount <= 0.005 ->
                                        "当日资金：未结算 · 结转 ${money(totalNeed)}"

                                    remainingAmount <= 0.005 ->
                                        "当日资金：已结清 ${money(settledAmount)}"

                                    else ->
                                        "当日资金：已结 ${money(settledAmount)} · 结转 ${money(remainingAmount)}"
                                },
                                style =
                                    MaterialTheme.typography.bodySmall,
                                color =
                                    if (remainingAmount <= 0.005) {
                                        BrandGreen
                                    } else {
                                        Color.DarkGray
                                    },
                                fontWeight =
                                    FontWeight.SemiBold
                            )

                            Text(
                                if (p.purchasePaid > 0.005) {
                                    "采购 / 当日资金请在下方转账方案逐笔确认，未结金额会自动进入资金余额。"
                                } else {
                                    "当日资金请在下方转账方案逐笔确认，未结金额会自动进入资金余额。"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.Gray
                            )
                        }
                    }
                }
            }

            item {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "最少转账方案",
                        style =
                            MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    settlementCenter?.let { center ->
                        Text(
                            "资金中心 · ${center.name}",
                            style = MaterialTheme.typography.bodySmall,
                            color = BrandGreen,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                if (b.transfers.isEmpty()) {
                    Text(
                        "无需转账，已经平账。",
                        color = BrandGreen
                    )
                }
            }

            items(
                b.transfers,
                key = { "ct${it.id}" }
            ) { t ->
                Card(
                    colors =
                        CardDefaults.cardColors(
                            containerColor = SoftGreen
                        ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    "${t.fromPartnerName}  →  ${t.toPartnerName}",
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    money(t.amount),
                                    fontWeight = FontWeight.Bold,
                                    color = BrandGreen
                                )
                            }
                            Text(
                                when {
                                    t.pendingAmount <= 0.005 -> "已结清"
                                    t.settledAmount > 0.005 ->
                                        "已结 ${money(t.settledAmount)} · 剩 ${money(t.pendingAmount)}"
                                    else -> "未结算"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color =
                                    if (t.pendingAmount <= 0.005) {
                                        BrandGreen
                                    } else {
                                        Color.Gray
                                    }
                            )
                        }
                        TextButton(
                            onClick = {
                                settleTransfer = t
                                settleInput = cleanNumber(t.amount)
                                settleInputKey = System.nanoTime()
                            },
                            contentPadding = PaddingValues(
                                horizontal = 6.dp,
                                vertical = 0.dp
                            )
                        ) {
                            Text("处理")
                        }
                    }
                }
            }

            item {
                if (b.settlement.status != 1) {
                    Button(
                        onClick = {
                            if (
                                db.confirmCashSettlement(
                                    b.settlement.id
                                )
                            ) {
                                message =
                                    "资金轧差方案已确认执行"
                                onChanged()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("全部确认当日资金已结清")
                    }
                }

                TextButton(
                    onClick = {
                        deleteId = b.settlement.id
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("删除本次资金轧差记录")
                }
            }
        }

        if (history.isNotEmpty()) {
            item {
                HorizontalDivider()
                Text(
                    "最近资金轧差记录",
                    style =
                        MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            items(
                history,
                key = { "cash${it.id}" }
            ) { h ->
                RecordCard {
                    Column(Modifier.weight(1f)) {
                        Text(
                            h.date,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "营业 ${money(h.revenue)} · " +
                                "进货 ${money(h.purchaseCost)} · " +
                                "利润 ${money(h.profit)}",
                            style =
                                MaterialTheme.typography.bodySmall
                        )
                    }
                    Column(
                        horizontalAlignment = Alignment.End
                    ) {
                        Text(
                            when (h.status) {
                                1 -> "已结清"
                                2 -> "部分结算"
                                else -> "未结算"
                            },
                            color =
                                when (h.status) {
                                    1 -> BrandGreen
                                    2 -> Color(0xFF8A6D00)
                                    else -> MaterialTheme.colorScheme.error
                                },
                            fontWeight = FontWeight.SemiBold
                        )
                        if (h.effectiveStatusSourceDate.isNotBlank()) {
                            Text(
                                "含 ${h.effectiveStatusSourceDate.substring(5)} 截至今日结清",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.Gray
                            )
                        }
                    }
                }
            }
        }
    }

    settleTransfer?.let { transfer ->
        val maxAmount = transfer.amount
        val currentSettled = transfer.settledAmount

        AlertDialog(
            onDismissRequest = {
                settleTransfer = null
                settleInput = ""
            },
            title = {
                Text(
                    "${transfer.fromPartnerName} → ${transfer.toPartnerName}"
                )
            },
            text = {
                Column(
                    verticalArrangement =
                        Arrangement.spacedBy(10.dp)
                ) {
                    DefaultNumberField(
                        label = "结算金额",
                        value = settleInput,
                        defaultValue = cleanNumber(maxAmount),
                        stateKey = "cash-transfer-${transfer.id}-$settleInputKey",
                        onValue = { settleInput = it },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text(
                        "方案 ${money(maxAmount)} · 已结 ${money(currentSettled)} · 剩 ${money(transfer.pendingAmount)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray
                    )
                    Text(
                        "输入的是本笔累计结算金额；保留默认金额直接结算，即视为本笔全部结清。",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val amount =
                            settleInput
                                .toDoubleOrNull()

                        if (amount == null) {
                            message = "请输入结算金额"
                        } else if (
                            amount < 0 ||
                            amount > maxAmount + 0.005
                        ) {
                            message =
                                "结算金额不能超过 ${money(maxAmount)}"
                        } else if (
                            db.setCashSettlementTransferSettledAmount(
                                transfer.id,
                                amount
                            )
                        ) {
                            message =
                                when {
                                    amount <= 0.005 ->
                                        "${transfer.fromPartnerName} → ${transfer.toPartnerName} 已设为未结算"
                                    amount + 0.005 >= maxAmount ->
                                        "${transfer.fromPartnerName} → ${transfer.toPartnerName} 已结清"
                                    else ->
                                        "本笔已结 ${money(amount)}，剩余自动结转"
                                }
                            settleTransfer = null
                            settleInput = ""
                            onChanged()
                        }
                    }
                ) {
                    Text("结算")
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            if (
                                db.setCashSettlementTransferSettledAmount(
                                    transfer.id,
                                    0.0
                                )
                            ) {
                                message =
                                    "${transfer.fromPartnerName} → ${transfer.toPartnerName} 已改为未结算"
                                settleTransfer = null
                                settleInput = ""
                                onChanged()
                            }
                        }
                    ) {
                        Text("改为未结算")
                    }
                    TextButton(
                        onClick = {
                            settleTransfer = null
                            settleInput = ""
                        }
                    ) {
                        Text("取消")
                    }
                }
            }
        )
    }

    deleteId?.let { id ->
        ConfirmDelete(
            "删除这张资金轧差记录？不会删除进货、营业、利润分配或利润结算确认。",
            { deleteId = null }
        ) {
            protectHistoricalAction(
                date,
                "删除已结算资金轧差记录"
            ) {
                db.deleteCashSettlement(id)
                deleteId = null
                message = "资金轧差记录已删除"
                onChanged()
            }
        }
    }
}

@Composable
private fun SettlementBatchContent(
    db: AppDatabase,
    dataVersion: Int,
    onChanged: () -> Unit
) {
    val context = LocalContext.current
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    var filter by remember { mutableStateOf(HistoryTimeFilter.LAST_30) }
    var customStart by remember {
        mutableStateOf(LocalDate.now().minusDays(29).toString())
    }
    var customEnd by remember {
        mutableStateOf(LocalDate.now().toString())
    }
    var selectedPartnerId by remember { mutableStateOf<Long?>(null) }
    var filterMenu by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var settleThroughPartnerId by remember { mutableStateOf<Long?>(null) }
    val expandedDates = remember { mutableStateMapOf<String, Boolean>() }

    val anchor =
        runCatching { LocalDate.parse(date) }
            .getOrDefault(LocalDate.now())
    val range =
        resolveTimeRange(
            filter,
            customStart,
            customEnd,
            anchor
        )
    val rangeStart = range.first
    val rangeEnd = range.second?.let { if (it > date) date else it } ?: date
    val invalidCustom =
        filter == HistoryTimeFilter.CUSTOM &&
            customStart > customEnd

    val partners =
        remember(dataVersion) {
            db.getPartners()
        }
    val balances =
        remember(dataVersion, date) {
            db.getPartnerFundBalances(endDate = date)
                .associateBy { it.partnerId }
        }
    val windows =
        remember(dataVersion, date, partners) {
            partners.associate { partner ->
                partner.id to
                    db.getPartnerOutstandingWindow(
                        partner.id,
                        date
                    )
            }
        }
    val periodSummary =
        remember(
            dataVersion,
            rangeStart,
            rangeEnd,
            invalidCustom
        ) {
            if (invalidCustom) {
                null
            } else {
                db.getFundPeriodSummary(
                    rangeStart,
                    rangeEnd
                )
            }
        }
    val partnerPeriodStats =
        remember(
            dataVersion,
            rangeStart,
            rangeEnd,
            invalidCustom
        ) {
            if (invalidCustom) {
                emptyMap()
            } else {
                db.getPartnerFundPeriodSummaries(
                    rangeStart,
                    rangeEnd
                ).associateBy {
                    it.partnerId
                }
            }
        }
    val settlementCenter =
        remember(dataVersion) {
            db.getSettlementCenter()
        }

    LaunchedEffect(partners) {
        if (
            selectedPartnerId != null &&
            partners.none { it.id == selectedPartnerId }
        ) {
            selectedPartnerId = null
        }
    }

    fun balanceText(value: Double): String =
        when {
            value > 0.005 -> "应收 ${money(value)}"
            value < -0.005 -> "应补 ${money(-value)}"
            else -> "已结清"
        }

    val selectedPartner =
        partners.firstOrNull { it.id == selectedPartnerId }
    val selectedRows =
        remember(
            dataVersion,
            selectedPartner?.id,
            rangeStart,
            rangeEnd,
            invalidCustom
        ) {
            if (selectedPartner != null && !invalidCustom) {
                db.getPartnerDailyFundBalances(
                    selectedPartner.id,
                    rangeStart,
                    rangeEnd
                )
            } else {
                emptyList()
            }
        }
    val selectedSettlementEvents =
        remember(
            dataVersion,
            selectedPartner?.id,
            rangeStart,
            rangeEnd,
            invalidCustom
        ) {
            if (selectedPartner != null && !invalidCustom) {
                db.getPartnerSettlementEvents(
                    selectedPartner.id,
                    rangeStart,
                    rangeEnd
                )
            } else {
                emptyList()
            }
        }

    val centerPendingReceivable =
        settlementCenter?.let { center ->
            partners
                .filter { it.id != center.id }
                .sumOf { partner ->
                    (-(balances[partner.id]?.currentBalance ?: 0.0))
                        .coerceAtLeast(0.0)
                }
        } ?: 0.0
    val centerPendingPayable =
        settlementCenter?.let { center ->
            partners
                .filter { it.id != center.id }
                .sumOf { partner ->
                    (balances[partner.id]?.currentBalance ?: 0.0)
                        .coerceAtLeast(0.0)
                }
        } ?: 0.0

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            horizontal = 14.dp,
            vertical = 4.dp
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            PageHeader(
                "资金余额",
                "最少转账方案确认后的累计资金状态"
            )
        }

        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (selectedPartnerId == null) {
                    Button(
                        onClick = { selectedPartnerId = null },
                        contentPadding = PaddingValues(horizontal = 14.dp)
                    ) { Text("总览") }
                } else {
                    OutlinedButton(
                        onClick = { selectedPartnerId = null },
                        contentPadding = PaddingValues(horizontal = 14.dp)
                    ) { Text("总览") }
                }

                partners.forEach { partner ->
                    if (selectedPartnerId == partner.id) {
                        Button(
                            onClick = { selectedPartnerId = partner.id },
                            contentPadding = PaddingValues(horizontal = 14.dp)
                        ) { Text(partner.name, maxLines = 1) }
                    } else {
                        OutlinedButton(
                            onClick = { selectedPartnerId = partner.id },
                            contentPadding = PaddingValues(horizontal = 14.dp)
                        ) { Text(partner.name, maxLines = 1) }
                    }
                }
            }
        }

        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                Row(
                    Modifier
                        .weight(1.18f)
                        .height(40.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = {
                            date =
                                runCatching {
                                    LocalDate.parse(date).minusDays(1).toString()
                                }.getOrDefault(LocalDate.now().minusDays(1).toString())
                            message = ""
                        },
                        modifier = Modifier.width(34.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("‹", fontSize = 23.sp)
                    }

                    OutlinedButton(
                        onClick = {
                            showDatePicker(context, date) {
                                date = it
                                message = ""
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp),
                        contentPadding = PaddingValues(horizontal = 5.dp)
                    ) {
                        Text(
                            date +
                                runCatching {
                                    " ${chineseWeekday(LocalDate.parse(date))}"
                                }.getOrDefault(""),
                            fontSize = 12.sp,
                            maxLines = 1
                        )
                    }

                    TextButton(
                        onClick = {
                            date =
                                runCatching {
                                    LocalDate.parse(date).plusDays(1).toString()
                                }.getOrDefault(LocalDate.now().plusDays(1).toString())
                            message = ""
                        },
                        modifier = Modifier.width(34.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("›", fontSize = 23.sp)
                    }
                }

                Box(Modifier.weight(0.82f)) {
                    CompactSelectButton(
                        "日期明细",
                        filter.label,
                        Modifier.fillMaxWidth()
                    ) { filterMenu = true }
                    DropdownMenu(
                        expanded = filterMenu,
                        onDismissRequest = { filterMenu = false }
                    ) {
                        HistoryTimeFilter.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                onClick = {
                                    filter = option
                                    filterMenu = false
                                    message = ""
                                }
                            )
                        }
                    }
                }
            }
        }

        if (filter == HistoryTimeFilter.CUSTOM) {
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CompactDateSelector(
                        "开始日期",
                        customStart,
                        Modifier.weight(1f),
                        onDate = {
                            customStart = it
                            message = ""
                        }
                    )
                    CompactDateSelector(
                        "结束日期",
                        customEnd,
                        Modifier.weight(1f),
                        onDate = {
                            customEnd = it
                            message = ""
                        }
                    )
                }
            }
        }

        if (invalidCustom) {
            item {
                Text(
                    "开始日期不能晚于结束日期",
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        if (selectedPartnerId == null) {
            periodSummary?.let { period ->
                item {
                    Text(
                        "统计期间 ${period.startDate.ifBlank { "最早记录" }} ～ ${period.endDate} · ${period.businessDayCount}个营业日",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.DarkGray
                    )
                }
                item {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MiniSummaryCard(
                            "采购金额",
                            money(period.purchaseAmount),
                            Modifier.weight(1f),
                            SoftPurple
                        )
                        MiniSummaryCard(
                            "营业额",
                            money(period.revenueAmount),
                            Modifier.weight(1f),
                            SoftGreen
                        )
                        MiniSummaryCard(
                            "利润",
                            money(period.profitAmount),
                            Modifier.weight(1f),
                            SoftOrange
                        )
                    }
                }
                item {
                    MiniSummaryCard(
                        "已执行资金结算",
                        money(period.settlementAmount),
                        Modifier.fillMaxWidth(),
                        Color(0xFFF4F5F7)
                    )
                }
            }

            settlementCenter?.let { center ->
                item {
                    Text(
                        "资金中心：${center.name}",
                        style = MaterialTheme.typography.bodySmall,
                        color = BrandGreen,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            val pendingPeople =
                partners.count { partner ->
                    partner.id != settlementCenter?.id &&
                        kotlin.math.abs(
                            balances[partner.id]?.currentBalance ?: 0.0
                        ) > 0.005
                }
            val totalReceivable = centerPendingReceivable
            val totalPayable = centerPendingPayable

            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MiniSummaryCard(
                        "待结人数",
                        "${pendingPeople}人",
                        Modifier.weight(1f),
                        SoftPurple
                    )
                    MiniSummaryCard(
                        "中心待收",
                        money(totalReceivable),
                        Modifier.weight(1f),
                        SoftGreen
                    )
                    MiniSummaryCard(
                        "中心待付",
                        money(totalPayable),
                        Modifier.weight(1f),
                        SoftOrange
                    )
                }
            }

            if (partners.isEmpty()) {
                item {
                    Text("暂无合伙人。", color = Color.Gray)
                }
            }

            items(
                partners,
                key = { "fund_overview_${it.id}" }
            ) { partner ->
                val balance =
                    balances[partner.id]?.currentBalance ?: 0.0
                val window = windows[partner.id]
                Card(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            selectedPartnerId = partner.id
                        }
                ) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                partner.name,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                balanceText(balance),
                                fontWeight = FontWeight.Bold,
                                color = when {
                                    balance > 0.005 -> BrandGreen
                                    balance < -0.005 -> MaterialTheme.colorScheme.error
                                    else -> Color.Gray
                                }
                            )
                        }

                        val period =
                            partnerPeriodStats[partner.id]
                        if (period != null) {
                            Text(
                                "个人采购 ${money(period.purchasePaid)} · " +
                                    "个人收款 ${money(period.revenueReceived)} · " +
                                    if (period.profitShare < -0.005) {
                                        "个人亏损 -${money(-period.profitShare)}"
                                    } else {
                                        "个人利润 ${money(period.profitShare)}"
                                    },
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.DarkGray
                            )
                            Text(
                                "期间已执行转账 ${money(period.settlementSent + period.settlementReceived)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.DarkGray
                            )
                        }

                        if (partner.id == settlementCenter?.id) {
                            val centerBalance = balances[partner.id]
                            val personalOperating =
                                (centerBalance?.purchasePaid ?: 0.0) +
                                    (centerBalance?.expensePaid ?: 0.0) +
                                    (centerBalance?.profitShare ?: 0.0) -
                                    (centerBalance?.revenueReceived ?: 0.0)
                            Text(
                                "资金中心 · 个人经营资金差 ${money(personalOperating)} · " +
                                    "待收 ${money(centerPendingReceivable)} · " +
                                    "待付 ${money(centerPendingPayable)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = BrandGreen
                            )
                        }

                        if (
                            window != null &&
                            kotlin.math.abs(balance) > 0.005
                        ) {
                            Text(
                                "未结范围 ${window.rangeStartDate} ～ $date · ${window.businessDayCount}个营业日",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.DarkGray
                            )
                        } else {
                            Text(
                                "截至 $date 已结清",
                                style = MaterialTheme.typography.bodySmall,
                                color = BrandGreen
                            )
                        }

                        if (!window?.lastClearedDate.isNullOrBlank()) {
                            Text(
                                "最近归零 ${window?.lastClearedDate}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.Gray
                            )
                        }

                        Text(
                            "点击查看日期明细",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray
                        )
                    }
                }
            }
        } else {
            val partner = selectedPartner
            if (partner != null && !invalidCustom) {
                val balance =
                    balances[partner.id]?.currentBalance ?: 0.0
                val window = windows[partner.id]

                item {
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = when {
                                balance > 0.005 -> SoftGreen
                                balance < -0.005 -> SoftOrange
                                else -> Color(0xFFF4F5F7)
                            }
                        )
                    ) {
                        Column(
                            Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                partner.name,
                                fontWeight = FontWeight.Bold,
                                fontSize = 17.sp
                            )
                            Text(
                                if (partner.id == settlementCenter?.id) {
                                    "净待结 ${balanceText(balance)}"
                                } else {
                                    balanceText(balance)
                                },
                                fontSize = 27.sp,
                                fontWeight = FontWeight.Bold,
                                color = when {
                                    balance > 0.005 -> BrandGreen
                                    balance < -0.005 -> MaterialTheme.colorScheme.error
                                    else -> Color.Gray
                                }
                            )

                            if (partner.id == settlementCenter?.id) {
                                Text(
                                    "资金中心",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = BrandGreen,
                                    fontWeight = FontWeight.SemiBold
                                )
                                val centerBalance = balances[partner.id]
                                val personalOperating =
                                    (centerBalance?.purchasePaid ?: 0.0) +
                                        (centerBalance?.expensePaid ?: 0.0) +
                                        (centerBalance?.profitShare ?: 0.0) -
                                        (centerBalance?.revenueReceived ?: 0.0)
                                SummaryRow(
                                    "个人经营资金差",
                                    when {
                                        personalOperating > 0.005 -> "+${money(personalOperating)}"
                                        personalOperating < -0.005 -> "-${money(-personalOperating)}"
                                        else -> money(0.0)
                                    }
                                )
                                SummaryRow(
                                    "当前待收合伙人",
                                    money(centerPendingReceivable)
                                )
                                SummaryRow(
                                    "当前待付合伙人",
                                    money(centerPendingPayable)
                                )
                                SummaryRow(
                                    "累计代收合伙人",
                                    money(centerBalance?.settlementReceived ?: 0.0)
                                )
                                SummaryRow(
                                    "累计代付合伙人",
                                    money(centerBalance?.settlementSent ?: 0.0)
                                )
                            }

                            partnerPeriodStats[partner.id]?.let { period ->
                                SummaryRow(
                                    "期间个人采购",
                                    money(period.purchasePaid)
                                )
                                SummaryRow(
                                    "期间营业收款",
                                    money(period.revenueReceived)
                                )
                                SummaryRow(
                                    if (period.profitShare < -0.005) {
                                        "期间个人亏损"
                                    } else {
                                        "期间个人利润"
                                    },
                                    if (period.profitShare < -0.005) {
                                        "-${money(-period.profitShare)}"
                                    } else {
                                        money(period.profitShare)
                                    }
                                )
                                val executed =
                                    period.settlementSent +
                                        period.settlementReceived
                                SummaryRow(
                                    "期间已执行转账",
                                    money(executed)
                                )
                            }

                            if (
                                window != null &&
                                kotlin.math.abs(balance) > 0.005
                            ) {
                                SummaryRow(
                                    "未结时间范围",
                                    "${window.rangeStartDate} ～ $date"
                                )
                                SummaryRow(
                                    "涉及营业日",
                                    "${window.businessDayCount}天"
                                )
                            }
                            if (!window?.lastClearedDate.isNullOrBlank()) {
                                SummaryRow(
                                    "最近归零",
                                    window?.lastClearedDate ?: ""
                                )
                            }
                            if (window != null) {
                                SummaryRow(
                                    "累计已执行转账",
                                    money(window.settledTransferAmount)
                                )
                            }

                            if (
                                kotlin.math.abs(balance) > 0.005 &&
                                partner.id != settlementCenter?.id
                            ) {
                                Button(
                                    onClick = {
                                        settleThroughPartnerId = partner.id
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("结清截至今日")
                                }
                            } else {
                                OutlinedButton(
                                    onClick = {},
                                    enabled = false,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        if (partner.id == settlementCenter?.id) {
                                            "资金中心无需单独结清"
                                        } else {
                                            "截至今日已结清"
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    Text(
                        "经营日期明细 · ${filter.label}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (selectedRows.isEmpty()) {
                    item {
                        Text(
                            "当前时间范围没有该合伙人的经营日期明细。",
                            color = Color.Gray
                        )
                    }
                }

                items(
                    selectedRows,
                    key = { "partner_fund_day_${partner.id}_${it.date}" }
                ) { row ->
                    val expanded = expandedDates[row.date] == true
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val parsed =
                                    runCatching { LocalDate.parse(row.date) }
                                        .getOrNull()
                                Text(
                                    row.date +
                                        (parsed?.let {
                                            " · ${chineseWeekday(it)}"
                                        } ?: ""),
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    balanceText(row.remainingBalance),
                                    fontWeight = FontWeight.SemiBold,
                                    color = when {
                                        row.remainingBalance > 0.005 -> BrandGreen
                                        row.remainingBalance < -0.005 -> MaterialTheme.colorScheme.error
                                        else -> Color.Gray
                                    }
                                )
                            }

                            SummaryRow(
                                "当日净额",
                                when {
                                    row.dayBalance > 0.005 -> "+${money(row.dayBalance)}"
                                    row.dayBalance < -0.005 -> "-${money(-row.dayBalance)}"
                                    else -> money(0.0)
                                }
                            )
                            SummaryRow(
                                "截至当日累计未结（含已执行结算）",
                                balanceText(row.remainingBalance),
                                bold = true
                            )

                            TextButton(
                                onClick = {
                                    expandedDates[row.date] = !expanded
                                },
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text(if (expanded) "收起资金构成" else "查看资金构成")
                            }

                            if (expanded) {
                                Card(
                                    Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = Color(0xFFF8FAFC)
                                    )
                                ) {
                                    Column(Modifier.padding(10.dp)) {
                                        SummaryRow(
                                            "采购垫付",
                                            "+${money(row.purchasePaid)}"
                                        )
                                        SummaryRow(
                                            "费用垫付",
                                            "+${money(row.expensePaid)}"
                                        )
                                        SummaryRow(
                                            "营业收款",
                                            "-${money(row.revenueReceived)}"
                                        )
                                        SummaryRow(
                                            if (row.profitShare >= 0) {
                                                "利润分配"
                                            } else {
                                                "亏损分担"
                                            },
                                            if (row.profitShare >= 0) {
                                                "+${money(row.profitShare)}"
                                            } else {
                                                "-${money(-row.profitShare)}"
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                    Text(
                        "结算事件 · ${filter.label}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (selectedSettlementEvents.isEmpty()) {
                    item {
                        Text(
                            "当前时间范围没有已执行的资金结算事件。",
                            color = Color.Gray
                        )
                    }
                }

                items(
                    selectedSettlementEvents,
                    key = { "partner_settlement_event_${partner.id}_${it.id}" }
                ) { transfer ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val parsed =
                                    runCatching { LocalDate.parse(transfer.date) }
                                        .getOrNull()
                                Text(
                                    transfer.date +
                                        (parsed?.let { " · ${chineseWeekday(it)}" } ?: ""),
                                    modifier = Modifier.weight(1f),
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    money(transfer.settledAmount),
                                    fontWeight = FontWeight.Bold,
                                    color = BrandGreen
                                )
                            }
                            Text(
                                when (transfer.settlementKind) {
                                    "CUTOFF", "CUTOFF_CENTER" -> "结清截至今日"
                                    else -> "最少转账方案"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = BrandGreen
                            )
                            Text(
                                "${transfer.fromPartnerName} → ${transfer.toPartnerName}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (transfer.confirmedAt > 0L) {
                                Text(
                                    "确认时间 ${settlementTimeText(transfer.confirmedAt)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.Gray
                                )
                            }
                        }
                    }
                }
            }
        }

        if (message.isNotBlank()) {
            item {
                Text(
                    message,
                    color = if (
                        message.contains("失败") ||
                        message.contains("不足") ||
                        message.contains("核对")
                    ) {
                        MaterialTheme.colorScheme.error
                    } else {
                        BrandGreen
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }

    settleThroughPartnerId?.let { partnerId ->
        val partner = partners.firstOrNull { it.id == partnerId }
        val balance = balances[partnerId]?.currentBalance ?: 0.0
        val window = windows[partnerId]
        if (partner != null) {
            ConfirmActionDialog(
                title = "结清截至今日",
                text = buildString {
                    append(partner.name)
                    append("截至 ")
                    append(date)
                    append(" 当前")
                    append(
                        if (balance >= 0) {
                            "应收 ${money(balance)}"
                        } else {
                            "应补 ${money(-balance)}"
                        }
                    )
                    if (window != null && window.rangeStartDate.isNotBlank()) {
                        append("。未结范围 ${window.rangeStartDate} ～ $date，共 ${window.businessDayCount} 个营业日")
                    }
                    append("。确认后会生成真实资金转账并写入结算记录。")
                },
                confirmText = "确认结清",
                onDismiss = {
                    settleThroughPartnerId = null
                }
            ) {
                val result =
                    db.settlePartnerThroughDate(
                        partnerId,
                        date
                    )
                message = result.message
                settleThroughPartnerId = null
                if (result.success) onChanged()
            }
        }
    }
}

@Composable
private fun SettlementStatsContent(
    db: AppDatabase,
    dataVersion: Int,
    protectHistoricalAction: (String, String, () -> Unit) -> Unit,
    onChanged: () -> Unit
) {
    var filter by remember {
        mutableStateOf(HistoryTimeFilter.THIS_MONTH)
    }
    val today = LocalDate.now()
    var customStart by remember {
        mutableStateOf(today.withDayOfMonth(1).toString())
    }
    var customEnd by remember {
        mutableStateOf(today.toString())
    }
    var undoCutoffKey by remember {
        mutableStateOf<String?>(null)
    }

    val invalidCustom =
        filter == HistoryTimeFilter.CUSTOM &&
            customStart > customEnd
    val range =
        resolveTimeRange(
            filter,
            customStart,
            customEnd,
            today
        )
    val queryStart =
        if (invalidCustom) "9999-12-31" else range.first
    val queryEnd =
        if (invalidCustom) "0000-01-01" else range.second

    val records =
        remember(dataVersion, queryStart, queryEnd) {
            db.getFundSettlementHistory(
                queryStart,
                queryEnd,
                160
            )
        }
    val totalSettled =
        records
            .filter { it.status != 4 }
            .sumOf { it.settledAmount }
    val fullySettled =
        records.count { it.status == 1 }
    val partial =
        records.count { it.status == 2 }
    val revoked =
        records.count { it.status == 3 }
    val invalidLegacy =
        records.count { it.status == 4 }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            horizontal = 14.dp,
            vertical = 4.dp
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            PageHeader(
                "结算记录",
                "记录最少转账方案和“结清截至今日”的实际资金转账"
            )
        }

        item {
            TimeFilterSelector(
                filter = filter,
                onFilterChange = {
                    filter = it
                },
                customStart = customStart,
                onCustomStart = {
                    customStart = it
                },
                customEnd = customEnd,
                onCustomEnd = {
                    customEnd = it
                }
            )
        }

        if (invalidCustom) {
            item {
                Text(
                    "开始日期不能晚于结束日期",
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MiniSummaryCard(
                    "记录次数",
                    "${records.size}次",
                    Modifier.weight(1f),
                    SoftPurple
                )
                MiniSummaryCard(
                    "已结清",
                    "${fullySettled}次",
                    Modifier.weight(1f),
                    SoftGreen
                )
                MiniSummaryCard(
                    "实际转账",
                    money(totalSettled),
                    Modifier.weight(1f),
                    SoftOrange
                )
            }
        }

        if (partial > 0) {
            item {
                Text(
                    "其中 ${partial} 次为部分结算",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF8A6D00)
                )
            }
        }

        if (revoked > 0) {
            item {
                Text(
                    "其中 ${revoked} 次“结清截至今日”已撤销",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
        }

        if (invalidLegacy > 0) {
            item {
                Text(
                    "其中 ${invalidLegacy} 次旧版非资金中心结算已失效，不再计入资金余额",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
        }

        if (records.isEmpty()) {
            item {
                Text(
                    "当前时间范围暂无已执行的资金结算记录。",
                    color = Color.Gray
                )
            }
        }

        items(
            records,
            key = { "fund_settlement_record_${it.recordKey}" }
        ) { record ->
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                when (record.kind) {
                                    "CUTOFF", "CUTOFF_CENTER" ->
                                        if (record.focusPartnerName.isNotBlank()) {
                                            "结清截至今日 · ${record.focusPartnerName}"
                                        } else {
                                            "结清截至今日"
                                        }

                                    "CUTOFF_LEGACY_INVALID" ->
                                        "旧版结算 · 已失效"
                                    "LEGACY" -> "历史资金结算"
                                    else -> "最少转账方案"
                                },
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Text(
                                "结算日 ${record.settlementDate}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                        }

                        Text(
                            when (record.status) {
                                1 -> "已结清"
                                2 -> "部分结算"
                                3 -> "已撤销"
                                4 -> "旧版失效"
                                else -> "未结算"
                            },
                            fontWeight = FontWeight.Bold,
                            color =
                                when (record.status) {
                                    1 -> BrandGreen
                                    2 -> Color(0xFF8A6D00)
                                    3 -> Color.Gray
                                    4 -> Color.Gray
                                    else -> MaterialTheme.colorScheme.error
                                }
                        )
                    }

                    val dayText =
                        if (record.businessDayCount > 0) {
                            "${record.businessDayCount}个营业日"
                        } else {
                            "营业日数量未记录"
                        }
                    Text(
                        "时间范围 ${record.periodStart} ～ ${record.periodEnd} · $dayText",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.DarkGray
                    )
                    if (record.status == 4) {
                        Text(
                            "原因：资金中心规则启用前生成，转账双方均不是当前资金中心；该记录仅保留历史，不再参与资金余额计算。",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    }

                    HorizontalDivider()

                    record.transfers.forEach { transfer ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "${transfer.fromPartnerName} → ${transfer.toPartnerName}",
                                modifier = Modifier.weight(1f),
                                fontWeight = FontWeight.SemiBold
                            )
                            Column(
                                horizontalAlignment = Alignment.End
                            ) {
                                Text(
                                    if (record.status == 3 || record.status == 4) {
                                        "原结算 ${money(transfer.amount)}"
                                    } else {
                                        money(transfer.settledAmount)
                                    },
                                    fontWeight = FontWeight.Bold,
                                    color =
                                        if (record.status == 3 || record.status == 4) {
                                            Color.Gray
                                        } else {
                                            BrandGreen
                                        }
                                )
                                if (
                                    record.status != 3 &&
                                    record.status != 4 &&
                                    transfer.settledAmount + 0.005 <
                                    transfer.amount
                                ) {
                                    Text(
                                        "应转 ${money(transfer.amount)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.Gray
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider()
                    SummaryRow(
                        if (record.status == 3 || record.status == 4) {
                            "原结算金额"
                        } else {
                            "本次实际结算"
                        },
                        if (record.status == 3 || record.status == 4) {
                            money(record.totalAmount)
                        } else {
                            money(record.settledAmount)
                        },
                        bold = true
                    )
                    if (record.confirmedAt > 0L) {
                        Text(
                            if (record.status == 3 || record.status == 4) {
                                "原确认时间 ${settlementTimeText(record.confirmedAt)}"
                            } else {
                                "确认时间 ${settlementTimeText(record.confirmedAt)}"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray
                        )
                    }

                    if (
                        record.kind in setOf("CUTOFF", "CUTOFF_CENTER") &&
                        record.status != 3
                    ) {
                        OutlinedButton(
                            onClick = {
                                undoCutoffKey = record.recordKey
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("撤销本次结清")
                        }
                    }
                }
            }
        }
    }

    undoCutoffKey?.let { batchKey ->
        ConfirmActionDialog(
            title = "撤销本次结清",
            text =
                "撤销后，这次“结清截至今日”的实际转账会重新计入资金余额，原营业、采购和利润记录不会改变。",
            confirmText = "确认撤销",
            onDismiss = {
                undoCutoffKey = null
            }
        ) {
            protectHistoricalAction(
                "",
                "撤销结清已结算资金记录"
            ) {
                val ok = db.undoCutoffSettlement(batchKey)
                undoCutoffKey = null
                if (ok) {
                    onChanged()
                }
            }
        }
    }
}


@Composable
private fun MoreScreen(
    db: AppDatabase,
    dataVersion: Int,
    initialSub: MorePage = MorePage.MENU,
    initialHistorySection: HistorySection = HistorySection.BUSINESS,
    ledgerManager: LedgerManager,
    cloudSyncManager: CloudSyncManager,
    currentBook: LedgerBook,
    systemRole: String,
    canEdit: Boolean,
    operationSecurityManager:
        OperationSecurityManager,
    protectHistoricalAction:
        (String, String, () -> Unit) -> Unit,
    onSwitchBook: (String) -> Unit,
    onPlan: () -> Unit,
    onOpenWeather: (String, Long?) -> Unit,
    updateChecking: Boolean,
    updateCheckMessage: String,
    onCheckUpdate: () -> Unit,
    ledgerUiSettingsManager:
        LedgerUiSettingsManager,
    uiSettingsVersion: Int,
    onUiSettingsChanged: () -> Unit,
    onLogout: () -> Unit,
    onChanged: () -> Unit
) {
    var sub by remember(initialSub) { mutableStateOf(initialSub) }

    fun goBack() {
        sub =
            MorePage.MENU
    }

    BackHandler(enabled = sub != MorePage.MENU) {
        goBack()
    }

    when (sub) {
        MorePage.MENU -> {
            fun allowed(
                permission: String
            ): Boolean =
                BookPermissions.has(
                    currentBook,
                    systemRole,
                    permission
                )

            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .background(
                        Color(0xFFF6F6F6)
                    ),
                contentPadding =
                    PaddingValues(
                        top = 3.dp,
                        bottom = 12.dp
                    )
            ) {
                item {
                    SettingsSection(
                        "账本与数据"
                    ) {
                        if (
                            systemRole ==
                            "SUPERADMIN" ||
                            currentBook.permission == "OWNER"
                        ) {
                            SettingsRow(
                                "📚",
                                "账本管理"
                            ) {
                                sub =
                                    MorePage.BOOKS
                            }
                            SettingsDivider()
                        }

                        if (
                            allowed(
                                BookPermissions.HISTORY_VIEW
                            )
                        ) {
                            if (
                                systemRole !=
                                "SUPERADMIN"
                            ) {
                                // 普通成员没有“账本管理”，历史记录作为本组第一项。
                            }
                            SettingsRow(
                                "🧾",
                                "历史记录"
                            ) {
                                sub =
                                    MorePage.HISTORY
                            }
                        }

                        if (
                            allowed(
                                BookPermissions.PURCHASE_ACTIVITY_VIEW
                            ) ||
                            allowed(
                                BookPermissions.PURCHASE_PLAN_EDIT
                            )
                        ) {
                            SettingsDivider()
                            SettingsRow(
                                "🛒",
                                "协作采购"
                            ) {
                                sub =
                                    MorePage.PURCHASE_ACTIVITY
                            }
                        }

                        if (
                            allowed(
                                BookPermissions.STATS_VIEW
                            )
                        ) {
                            SettingsDivider()
                            SettingsRow(
                                "📊",
                                "经营统计"
                            ) {
                                sub =
                                    MorePage.STATS
                            }

                            SettingsDivider()
                            SettingsRow(
                                "🧮",
                                "经营分析"
                            ) {
                                sub =
                                    MorePage.OPERATING_ANALYSIS
                            }

                            SettingsDivider()
                            SettingsRow(
                                "🌤️",
                                "经营天气"
                            ) {
                                onOpenWeather(LocalDate.now().toString(), null)
                            }

                            SettingsDivider()
                            SettingsRow(
                                "👤",
                                "个人汇总"
                            ) {
                                sub =
                                    MorePage.PERSONAL_SUMMARY
                            }
                        }

                        if (
                            allowed(
                                BookPermissions.REPORT_VIEW
                            )
                        ) {
                            SettingsDivider()
                            SettingsRow(
                                "📄",
                                "生成报表"
                            ) {
                                sub =
                                    MorePage.REPORT
                            }
                        }

                        if (
                            currentBook.permission ==
                            "OWNER" ||
                            systemRole ==
                            "SUPERADMIN"
                        ) {
                            SettingsDivider()
                            SettingsRow(
                                "💾",
                                "数据备份"
                            ) {
                                sub =
                                    MorePage.BACKUP
                            }
                        }
                    }
                }

                item {
                    SettingsSection(
                        "水果资料"
                    ) {
                        SettingsRow(
                            "🍉",
                            "水果季节库"
                        ) {
                            sub = MorePage.FRUIT_LIBRARY
                        }
                    }
                }

                if (systemRole == "SUPERADMIN") {
                    item {
                        SettingsSection(
                            "云端与协作"
                        ) {
                            SettingsRow(
                                "☁️",
                                "云端共享账本"
                            ) {
                                sub =
                                    MorePage.CLOUD_BOOKS
                            }

                        }
                    }
                }

                if (
                    allowed(
                        BookPermissions.BASIC_EDIT
                    ) ||
                    allowed(
                        BookPermissions.PROFIT_VIEW
                    )
                ) {
                    item {
                        SettingsSection(
                            "经营设置"
                        ) {
                            var needDivider =
                                false

                            if (
                                allowed(
                                    BookPermissions.BASIC_EDIT
                                )
                            ) {
                                SettingsRow(
                                    "👥",
                                    "合伙人管理"
                                ) {
                                    sub =
                                        MorePage.PARTNERS
                                }
                                SettingsDivider()
                                SettingsRow(
                                    "📍",
                                    "位置管理"
                                ) {
                                    sub =
                                        MorePage.STORES
                                }
                                SettingsDivider()
                                SettingsRow(
                                    "📦",
                                    "商品管理"
                                ) {
                                    sub =
                                        MorePage.FRUITS
                                }
                                needDivider =
                                    true
                            }

                            if (
                                allowed(
                                    BookPermissions.PROFIT_VIEW
                                )
                            ) {
                                if (needDivider) {
                                    SettingsDivider()
                                }
                                SettingsRow(
                                    "💰",
                                    "利润分配"
                                ) {
                                    sub =
                                        MorePage.PROFIT
                                }
                                needDivider =
                                    true
                            }

                        }
                    }
                }

                item {
                    SettingsSection(
                        "安全"
                    ) {
                        SettingsRow(
                            "🔐",
                            "安全与验证",
                            trailing =
                                if (operationSecurityManager.hasPassword()) {
                                    "已设置"
                                } else {
                                    "未设置"
                                }
                        ) {
                            sub =
                                MorePage.SECURITY
                        }
                    }
                }

                item {
                    SettingsSection(
                        "界面与显示"
                    ) {
                        SettingsRow(
                            "🎨",
                            "首页顶部设置"
                        ) {
                            sub =
                                MorePage.HOME_HEADER
                        }
                        SettingsDivider()
                        SettingsRow(
                            "⚡",
                            "首页快捷操作"
                        ) {
                            sub =
                                MorePage.HOME_QUICK_ACTIONS
                        }
                    }
                }

                if (
                    systemRole ==
                    "SUPERADMIN"
                ) {
                    item {
                        SettingsSection(
                            "系统"
                        ) {
                            SettingsRow(
                                "🛡",
                                "系统管理"
                            ) {
                                sub =
                                    MorePage.SYSTEM_ADMIN
                            }
                        }
                    }
                }

                item {
                    SettingsSection(
                        "帮助与关于"
                    ) {
                        SettingsRow(
                            icon = "ℹ️",
                            title = "关于天鲜账本",
                            trailing =
                                "V${BuildConfig.VERSION_NAME}"
                        ) {
                            sub =
                                MorePage.ABOUT
                        }
                    }
                }

                item {
                    SettingsSection(
                        "当前账号"
                    ) {
                        val session =
                            cloudSyncManager.session()

                        SettingsRow(
                            icon = "👤",
                            title =
                                session?.let {
                                    "${it.displayName}（${it.username}）"
                                } ?: "未登录",
                            trailing =
                                if (
                                    systemRole ==
                                    "SUPERADMIN"
                                ) {
                                    "超级管理员"
                                } else {
                                    ""
                                },
                            showArrow = false
                        ) {
                        }

                        SettingsDivider()

                        SettingsRow(
                            icon = "↪",
                            title = "退出登录",
                            titleColor =
                                MaterialTheme
                                    .colorScheme
                                    .error,
                            showArrow = false,
                            onClick =
                                onLogout
                        )
                    }
                }
            }
        }

        MorePage.SECURITY -> {
            SubPage(
                "安全与验证",
                {
                    sub =
                        MorePage.MENU
                }
            ) {
                SecuritySettingsContent(
                    manager = operationSecurityManager,
                    cloudSyncManager = cloudSyncManager
                )
            }
        }

        MorePage.HOME_HEADER -> {
            SubPage(
                "首页顶部设置",
                {
                    sub =
                        MorePage.MENU
                }
            ) {
                HomeHeaderSettingsContent(
                    ledgerUiSettingsManager =
                        ledgerUiSettingsManager,
                    currentBook =
                        currentBook,
                    uiSettingsVersion =
                        uiSettingsVersion,
                    onChanged =
                        onUiSettingsChanged
                )
            }
        }

        MorePage.HOME_QUICK_ACTIONS -> {
            SubPage(
                "首页快捷操作",
                {
                    sub = MorePage.MENU
                }
            ) {
                HomeQuickActionsSettingsContent(
                    manager = ledgerUiSettingsManager,
                    currentBook = currentBook,
                    systemRole = systemRole,
                    uiSettingsVersion = uiSettingsVersion,
                    onChanged = onUiSettingsChanged
                )
            }
        }

        MorePage.ABOUT -> {
            SubPage(
                "关于天鲜账本",
                {
                    sub =
                        MorePage.MENU
                }
            ) {
                AboutAppContent(
                    updateChecking =
                        updateChecking,
                    updateCheckMessage =
                        updateCheckMessage,
                    onCheckUpdate =
                        onCheckUpdate
                )
            }
        }

        MorePage.BOOKS -> {
            SubPage(
                "账本管理",
                { sub = MorePage.MENU }
            ) {
                LedgerManagementContent(
                    db = db,
                    ledgerManager =
                        ledgerManager,
                    cloudSyncManager =
                        cloudSyncManager,
                    currentBook =
                        currentBook,
                    onSwitchBook =
                        onSwitchBook,
                    onChanged =
                        onChanged
                )
            }
        }

        MorePage.CLOUD_BOOKS -> {
            SubPage(
                "云端共享账本",
                {
                    sub =
                        MorePage.MENU
                }
            ) {
                CloudSharedBooksContent(
                    cloudSyncManager =
                        cloudSyncManager,
                    ledgerManager =
                        ledgerManager,
                    currentBook =
                        currentBook,
                    onSwitchBook =
                        onSwitchBook,
                    onChanged =
                        onChanged
                )
            }
        }

        MorePage.MEMBER_PERMISSIONS -> {
            SubPage(
                "成员与权限",
                {
                    sub =
                        MorePage.MENU
                }
            ) {
                MemberPermissionContent(
                    cloudSyncManager =
                        cloudSyncManager,
                    ledgerManager =
                        ledgerManager,
                    currentBook =
                        currentBook,
                    onChanged =
                        onChanged
                )
            }
        }

        MorePage.SYSTEM_ADMIN -> {
            SubPage("系统管理", { sub = MorePage.MENU }) {
                SystemAdminContent(cloudSyncManager = cloudSyncManager)
            }
        }

        MorePage.HISTORY -> {
            SubPage(
                "历史记录",
                {
                    sub =
                        MorePage.MENU
                }
            ) {
                HistoryContent(
                    db = db,
                    dataVersion = dataVersion,
                    canEdit =
                        BookPermissions.has(
                            currentBook,
                            systemRole,
                            BookPermissions.BUSINESS_EDIT
                        ) ||
                            BookPermissions.has(
                                currentBook,
                                systemRole,
                                BookPermissions.PURCHASE_EDIT
                            ),
                    protectHistoricalAction =
                        protectHistoricalAction,
                    initialSection = initialHistorySection,
                    onChanged = onChanged
                )
            }
        }

        MorePage.PURCHASE_ACTIVITY -> {
            SubPage(
                "协作采购",
                {
                    sub =
                        MorePage.MENU
                }
            ) {
                CollaborativePurchaseContent(
                    db = db,
                    dataVersion =
                        dataVersion,
                    currentBook =
                        currentBook,
                    cloudSyncManager =
                        cloudSyncManager,
                    onChanged =
                        onChanged
                )
            }
        }

        MorePage.STATS -> {
            SubPage(
                "经营统计",
                {
                    sub =
                        MorePage.MENU
                }
            ) {
                StatsContent(
                    db,
                    dataVersion
                )
            }
        }

        MorePage.OPERATING_ANALYSIS -> {
            SubPage(
                "经营分析",
                {
                    sub = MorePage.MENU
                }
            ) {
                OperatingAnalysisContent(
                    db = db,
                    dataVersion = dataVersion,
                    currentBook = currentBook,
                    cloudSyncManager = cloudSyncManager
                )
            }
        }

        MorePage.PERSONAL_SUMMARY -> {
            SubPage(
                "个人汇总",
                {
                    sub =
                        MorePage.MENU
                }
            ) {
                PersonalSummaryContent(
                    db = db,
                    dataVersion = dataVersion
                )
            }
        }

        MorePage.BACKUP -> {
            SubPage(
                "数据备份",
                {
                    sub =
                        MorePage.MENU
                }
            ) {
                BackupContent(
                    db = db
                )
            }
        }

        MorePage.PARTNERS -> {
            SubPage(
                "合伙人管理",
                { sub = MorePage.MENU }
            ) {
                PartnerContent(
                    db,
                    dataVersion,
                    onChanged
                )
            }
        }

        MorePage.STORES -> {
            SubPage(
                "位置管理",
                { sub = MorePage.MENU }
            ) {
                StoreContent(
                    db,
                    dataVersion,
                    onChanged
                )
            }
        }

        MorePage.FRUIT_LIBRARY -> {
            SubPage(
                "水果季节库",
                { sub = MorePage.MENU }
            ) {
                FruitSeasonLibraryContent(
                    db = db,
                    dataVersion = dataVersion,
                    canEdit = BookPermissions.has(currentBook, systemRole, BookPermissions.BASIC_EDIT),
                    onChanged = onChanged
                )
            }
        }

        MorePage.FRUITS -> {
            SubPage(
                "商品管理",
                { sub = MorePage.MENU }
            ) {
                FruitManagementContent(
                    db,
                    dataVersion,
                    onChanged
                )
            }
        }

        MorePage.PROFIT -> {
            SubPage(
                "利润分配",
                { sub = MorePage.MENU }
            ) {
                ProfitContent(
                    db = db,
                    dataVersion = dataVersion,
                    canEdit = BookPermissions.has(
                        currentBook,
                        systemRole,
                        BookPermissions.PROFIT_EDIT
                    ),
                    protectHistoricalAction = protectHistoricalAction,
                    onChanged = onChanged
                )
            }
        }

        MorePage.REPORT -> {
            SubPage(
                "生成报表",
                { sub = MorePage.MENU }
            ) {
                ReportContent(
                    db = db,
                    dataVersion = dataVersion
                )
            }
        }
    }
}


@Composable
private fun OperatingAnalysisContent(
    db: AppDatabase,
    dataVersion: Int,
    currentBook: LedgerBook,
    cloudSyncManager: CloudSyncManager
) {
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    var showCalculation by remember(date) { mutableStateOf(true) }
    val analysis = remember(dataVersion, date) { db.getOperatingAnalysis(date) }

    fun percent(value: Double?): String =
        value?.let { String.format(Locale.CHINA, "%.1f%%", it * 100.0) } ?: "—"

    val relevantCostItems =
        analysis.items.filter {
            it.availableQuantity > 0.000001 ||
                it.lossQuantity > 0.000001 ||
                it.remainingQuantity > 0.000001 ||
                it.consumedQuantity > 0.000001
        }
    val missingCostItems = relevantCostItems.filter { !it.costAvailable }
    val knownCostCount = relevantCostItems.size - missingCostItems.size
    val statusText =
        when {
            !analysis.hasBusinessData -> "暂无经营数据"
            !analysis.inventoryComplete -> "库存未完整盘点 · 当前结果仅供参考"
            !analysis.costComplete ->
                "成本覆盖 $knownCostCount/${relevantCostItems.size} · 缺：" +
                    missingCostItems.take(3).joinToString("、") { "${it.fruitName}(${it.unit})" } +
                    if (missingCostItems.size > 3) " 等${missingCostItems.size}种" else ""
            !analysis.previousDayAligned -> "结转库存不是昨日盘点 · 按最近库存估算"
            else -> "数据完整 · 可用于当日经营复盘"
        }
    val statusColor =
        when {
            !analysis.hasBusinessData -> Color.Gray
            analysis.inventoryComplete && analysis.costComplete && analysis.previousDayAligned -> BrandGreen
            else -> Color(0xFFB26A00)
        }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            CompactDateSelector(
                label = "分析日期",
                date = date,
                modifier = Modifier.fillMaxWidth(),
                showWeekday = true,
                onDate = { date = it }
            )
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("数据状态", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        statusText,
                        modifier = Modifier.weight(1f),
                        color = statusColor,
                        textAlign = TextAlign.End,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        if (missingCostItems.isNotEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF7EA)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("待补成本", fontWeight = FontWeight.Bold, color = Color(0xFFB26A00))
                        missingCostItems.forEach { row ->
                            Text(
                                "• ${row.fruitName} · ${row.unit} · 结转 ${fmt(row.openingQuantity)} · 今日采购 ${fmt(row.purchasedQuantity)}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Text(
                            "已知成本商品继续参与计算；补齐后本页会自动恢复完整利润口径。",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray
                        )
                    }
                }
            }
        }

        item {
            OperatingAnalysisWeatherCard(
                db = db,
                date = date,
                dataVersion = dataVersion,
                currentBook = currentBook,
                cloudSyncManager = cloudSyncManager
            )
        }

        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MetricCard(
                    title = if (analysis.costComplete) "预估经营利润" else "已知成本口径利润",
                    value = analysis.operatingProfit?.let { money(it) } ?: "—",
                    modifier = Modifier.weight(1f),
                    color = if ((analysis.operatingProfit ?: 0.0) >= 0) SoftGreen else Color(0xFFFFECEC),
                    sub = if (analysis.costComplete) {
                        "利润率 ${percent(analysis.operatingMargin)}"
                    } else {
                        "成本覆盖 $knownCostCount/${relevantCostItems.size}"
                    }
                )
                MetricCard(
                    title = if (analysis.costComplete) "预估毛利" else "已知成本口径毛利",
                    value = analysis.grossProfit?.let { money(it) } ?: "—",
                    modifier = Modifier.weight(1f),
                    color = SoftBlue,
                    sub = if (analysis.costComplete) "毛利率 ${percent(analysis.grossMargin)}" else "缺成本 ${missingCostItems.size}种"
                )
            }
        }

        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MetricCard(
                    "营业额",
                    money(analysis.revenue),
                    Modifier.weight(1f),
                    SoftGreen
                )
                MetricCard(
                    "销售耗用成本",
                    analysis.consumedCost?.let { money(it) } ?: "—",
                    Modifier.weight(1f),
                    SoftOrange
                )
            }
        }

        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MetricCard(
                    "今日入库估值",
                    money(analysis.purchaseCost),
                    Modifier.weight(1f),
                    Color(0xFFFFF7EA)
                )
                MetricCard(
                    "损耗成本",
                    analysis.lossCost?.let { money(it) } ?: "—",
                    Modifier.weight(1f),
                    Color(0xFFFFECEC)
                )
            }
        }

        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MetricCard(
                    "日常开销",
                    money(analysis.expense),
                    Modifier.weight(1f),
                    Color(0xFFF6F0FF)
                )
                MetricCard(
                    "损耗商品",
                    "${analysis.items.count { it.lossQuantity > 0.000001 }}种",
                    Modifier.weight(1f),
                    Color(0xFFF7F8FA)
                )
            }
        }

        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MetricCard(
                    "结转库存成本",
                    analysis.openingInventoryCost?.let { money(it) } ?: "—",
                    Modifier.weight(1f),
                    Color(0xFFF3F6FA)
                )
                MetricCard(
                    "剩余库存成本",
                    analysis.closingInventoryCost?.let { money(it) } ?: "—",
                    Modifier.weight(1f),
                    Color(0xFFF3F6FA)
                )
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { showCalculation = !showCalculation },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("计算明细", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text(if (showCalculation) "收起" else "展开", color = BrandGreen)
                    }
                    if (showCalculation) {
                        AnalysisFormulaRow("结转库存成本", analysis.openingInventoryCost?.let { money(it) } ?: "—")
                        AnalysisFormulaRow("＋ 今日入库估值", money(analysis.purchaseCost))
                        AnalysisFormulaRow("－ 今日剩余库存", analysis.closingInventoryCost?.let { money(it) } ?: "—")
                        AnalysisFormulaRow("－ 损耗成本", analysis.lossCost?.let { money(it) } ?: "—")
                        HorizontalDivider(color = Color(0xFFEAEAEA))
                        AnalysisFormulaRow(
                            if (analysis.costComplete) "＝ 销售耗用成本" else "＝ 已知销售耗用成本",
                            analysis.consumedCost?.let { money(it) } ?: "—",
                            true
                        )
                        AnalysisFormulaRow(
                            if (analysis.costComplete) "营业额 － 销售耗用" else "营业额 － 已知销售耗用",
                            analysis.grossProfit?.let { money(it) } ?: "—",
                            true
                        )
                        AnalysisFormulaRow(
                            "预估毛利 － 损耗成本 － 日常开销",
                            analysis.operatingProfit?.let { money(it) } ?: "—",
                            true
                        )
                    }
                }
            }
        }

        item {
            Text(
                "商品成本明细",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            )
        }

        if (analysis.items.isEmpty()) {
            item {
                Text(
                    "当天没有可分析的库存或采购商品。",
                    color = Color.Gray,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
        } else {
            items(
                items = analysis.items,
                key = { "${it.fruitId}|${it.unit}" }
            ) { item ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFAFBFC)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 11.dp, vertical = 9.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                item.fruitName,
                                modifier = Modifier.weight(1f),
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                when {
                                    item.quantityAnomaly -> "库存异常"
                                    !item.inventorySaved -> "待盘点"
                                    !item.costAvailable -> "缺成本"
                                    else -> "已盘点"
                                },
                                color = when {
                                    item.quantityAnomaly -> MaterialTheme.colorScheme.error
                                    !item.inventorySaved || !item.costAvailable -> Color(0xFFB26A00)
                                    else -> BrandGreen
                                },
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                        Text(
                            "结转 ${fmt(item.openingQuantity)}${item.unit}  ＋ 采购 ${fmt(item.purchasedQuantity)}${item.unit}  － 损耗 ${fmt(item.lossQuantity)}${item.unit}  － 剩余 ${fmt(item.remainingQuantity)}${item.unit}  ＝ 销售耗用 ${fmt(item.consumedQuantity)}${item.unit}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "加权成本 ${item.averageUnitCost?.let { money(it) + "/" + item.unit } ?: "—"}  ·  销售成本 ${item.consumedCost?.let { money(it) } ?: "—"}  ·  损耗成本 ${item.lossCost?.let { money(it) } ?: "—"}  ·  剩余成本 ${item.closingCost?.let { money(it) } ?: "—"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.DarkGray
                        )
                        Text(
                            "估算重量 ${item.estimatedSoldWeightJin?.let { fmt(it) + "斤" } ?: "—"}  ·  今日零售 ${item.retailPricePerJin?.let { money(it) + "/斤" } ?: "—"}  ·  估算收入 ${item.estimatedSalesRevenue?.let { money(it) } ?: "—"}  ·  单品估算毛利 ${item.estimatedProductGrossProfit?.let { money(it) } ?: "—"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF7A5A00)
                        )
                        if (item.openingQuantity > 0.000001 && item.previousSnapshotDate != null) {
                            Text(
                                "结转来源：${item.previousSnapshotDate}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (item.previousSnapshotDate == runCatching { LocalDate.parse(date).minusDays(1).toString() }.getOrDefault("")) Color.Gray else Color(0xFFB26A00)
                            )
                        }
                    }
                }
            }
        }

        item {
            Text(
                "说明：成本按真实非零采购价优先、人工参考成本兜底；缺少个别商品成本时，已知商品继续计算并明确标记“已知成本口径”。库存仍以箱/筐/件/袋为主单位。每件重量只用于估算换算，因此销售重量、按零售价推算的单品收入与单品利润统一标记为“估算”。损耗与销售耗用分开计算。",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }
    }
}

@Composable
private fun AnalysisFormulaRow(
    label: String,
    value: String,
    bold: Boolean = false
) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            color = if (bold) Color.Unspecified else Color.DarkGray,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal
        )
        Text(
            value,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium
        )
    }
}

private enum class PersonalTrendMetric(val label: String) {
    RECEIPT("收款"),
    PURCHASE("采购"),
    PROFIT("利润")
}

private data class PersonalReceiptChannels(
    val wechat: Double,
    val alipay: Double,
    val cash: Double
)

@Composable
private fun PersonalSummaryContent(
    db: AppDatabase,
    dataVersion: Int
) {
    val today = LocalDate.now()
    var filter by remember {
        mutableStateOf(HistoryTimeFilter.LAST_30)
    }
    var customStart by remember {
        mutableStateOf(today.minusDays(29).toString())
    }
    var customEnd by remember {
        mutableStateOf(today.toString())
    }
    var selectedPartnerId by remember {
        mutableStateOf<Long?>(null)
    }
    var trendMetric by remember {
        mutableStateOf(PersonalTrendMetric.RECEIPT)
    }

    val invalidCustom =
        filter == HistoryTimeFilter.CUSTOM &&
            customStart > customEnd
    val range =
        resolveTimeRange(
            filter,
            customStart,
            customEnd,
            today
        )
    val queryStart =
        if (invalidCustom) "9999-12-31" else range.first
    val queryEnd =
        if (invalidCustom) {
            "0000-01-01"
        } else {
            range.second ?: today.toString()
        }

    val partnerSummaries =
        remember(
            dataVersion,
            queryStart,
            queryEnd,
            invalidCustom
        ) {
            if (invalidCustom) {
                emptyList()
            } else {
                db.getPartnerFundPeriodSummaries(
                    queryStart,
                    queryEnd
                )
            }
        }

    val activePartners =
        remember(dataVersion) {
            db.getPartners()
        }
    val orderedSummaries =
        remember(
            partnerSummaries,
            activePartners
        ) {
            val byId =
                partnerSummaries.associateBy {
                    it.partnerId
                }
            val activeIds =
                activePartners.map { it.id }.toSet()
            buildList {
                activePartners.forEach { p ->
                    byId[p.id]?.let(::add)
                }
                partnerSummaries
                    .filter { it.partnerId !in activeIds }
                    .forEach(::add)
            }
        }

    BackHandler(
        enabled = selectedPartnerId != null
    ) {
        selectedPartnerId = null
    }

    val selectedSummary =
        selectedPartnerId?.let { id ->
            orderedSummaries.firstOrNull {
                it.partnerId == id
            }
        }

    if (selectedPartnerId == null) {
        LazyColumn(
            Modifier
                .fillMaxSize()
                .background(Color(0xFFF6F6F6)),
            contentPadding =
                PaddingValues(
                    horizontal = 12.dp,
                    vertical = 10.dp
                ),
            verticalArrangement =
                Arrangement.spacedBy(10.dp)
        ) {
            item {
                TimeFilterSelector(
                    filter = filter,
                    onFilterChange = {
                        filter = it
                    },
                    customStart = customStart,
                    onCustomStart = {
                        customStart = it
                    },
                    customEnd = customEnd,
                    onCustomEnd = {
                        customEnd = it
                    }
                )
            }

            if (invalidCustom) {
                item {
                    Text(
                        "开始日期不能晚于结束日期",
                        color =
                            MaterialTheme.colorScheme.error
                    )
                }
            } else if (orderedSummaries.isEmpty()) {
                item {
                    Card(
                        Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "当前时间范围暂无合伙人数据",
                            Modifier.padding(16.dp),
                            color = Color.Gray
                        )
                    }
                }
            } else {
                items(
                    orderedSummaries,
                    key = { it.partnerId }
                ) { summary ->
                    val sourceGap =
                        summary.purchasePaid +
                            summary.expensePaid +
                            summary.profitShare -
                            summary.revenueReceived
                    val afterTransfer =
                        sourceGap +
                            summary.settlementSent -
                            summary.settlementReceived

                    Card(
                        onClick = {
                            selectedPartnerId =
                                summary.partnerId
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors =
                            CardDefaults.cardColors(
                                containerColor = Color.White
                            )
                    ) {
                        Column(
                            Modifier.padding(14.dp),
                            verticalArrangement =
                                Arrangement.spacedBy(9.dp)
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment =
                                    Alignment.CenterVertically
                            ) {
                                Text(
                                    summary.partnerName,
                                    style =
                                        MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    "查看明细 ›",
                                    color = BrandGreen,
                                    style =
                                        MaterialTheme.typography.bodySmall
                                )
                            }

                            Row(
                                horizontalArrangement =
                                    Arrangement.spacedBy(8.dp)
                            ) {
                                MiniSummaryCard(
                                    "采购额",
                                    money(summary.purchasePaid),
                                    Modifier.weight(1f),
                                    SoftOrange
                                )
                                MiniSummaryCard(
                                    "收款",
                                    money(summary.revenueReceived),
                                    Modifier.weight(1f),
                                    SoftBlue
                                )
                            }
                            Row(
                                horizontalArrangement =
                                    Arrangement.spacedBy(8.dp)
                            ) {
                                MiniSummaryCard(
                                    "个人利润",
                                    money(summary.profitShare),
                                    Modifier.weight(1f),
                                    SoftGreen
                                )
                                MiniSummaryCard(
                                    "个人开销",
                                    money(summary.expensePaid),
                                    Modifier.weight(1f),
                                    SoftPurple
                                )
                            }

                            HorizontalDivider(
                                color = Color(0xFFECECEC)
                            )

                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement =
                                    Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "实际转入 ${money(summary.settlementReceived)}",
                                    style =
                                        MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF2F6EB5)
                                )
                                Text(
                                    "实际转出 ${money(summary.settlementSent)}",
                                    style =
                                        MaterialTheme.typography.bodySmall,
                                    color = Color(0xFFB06A18)
                                )
                            }
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement =
                                    Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "期间资金差 ${money(sourceGap)}",
                                    style =
                                        MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    "转账后净差 ${money(afterTransfer)}",
                                    style =
                                        MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color =
                                        if (
                                            kotlin.math.abs(
                                                afterTransfer
                                            ) <= 0.005
                                        ) {
                                            BrandGreen
                                        } else {
                                            Color(0xFF555555)
                                        }
                                )
                            }
                        }
                    }
                }
            }
        }
        return
    }

    val summary = selectedSummary
    if (summary == null) {
        LaunchedEffect(Unit) {
            selectedPartnerId = null
        }
        return
    }

    val partnerId = summary.partnerId
    val daily =
        remember(
            dataVersion,
            partnerId,
            queryStart,
            queryEnd,
            invalidCustom
        ) {
            if (invalidCustom) {
                emptyList()
            } else {
                db.getPartnerDailyFundBalances(
                    partnerId = partnerId,
                    startDate = queryStart,
                    endDate = queryEnd
                )
            }
        }
    val transfers =
        remember(
            dataVersion,
            partnerId,
            queryStart,
            queryEnd,
            invalidCustom
        ) {
            if (invalidCustom) {
                emptyList()
            } else {
                db.getPartnerSettlementEvents(
                    partnerId = partnerId,
                    startDate = queryStart,
                    endDate = queryEnd
                )
            }
        }
    val purchaseOrders =
        remember(
            dataVersion,
            partnerId,
            queryStart,
            queryEnd,
            invalidCustom
        ) {
            if (invalidCustom) {
                emptyList()
            } else {
                db.getPurchaseOrdersBetween(
                    queryStart,
                    queryEnd
                ).filter {
                    it.order.buyerId == partnerId
                }
            }
        }
    val businessRecords =
        remember(
            dataVersion,
            queryStart,
            queryEnd,
            invalidCustom
        ) {
            if (invalidCustom) {
                emptyList()
            } else {
                db.getDailyRecordsBetween(
                    queryStart,
                    queryEnd
                )
            }
        }
    val receiptChannels =
        remember(
            businessRecords,
            partnerId
        ) {
            var wechat = 0.0
            var alipay = 0.0
            var cash = 0.0
            businessRecords.forEach { record ->
                if (record.receiptSplits.isNotEmpty()) {
                    record.receiptSplits
                        .filter {
                            it.partnerId == partnerId
                        }
                        .forEach {
                            wechat += it.wechatIncome
                            alipay += it.alipayIncome
                            cash += it.cashIncome
                        }
                } else {
                    if (
                        record.wechatCollectorId ==
                        partnerId
                    ) {
                        wechat += record.wechatIncome
                    }
                    if (
                        record.alipayCollectorId ==
                        partnerId
                    ) {
                        alipay += record.alipayIncome
                    }
                    if (
                        record.cashCollectorId ==
                        partnerId
                    ) {
                        cash += record.cashIncome
                    }
                }
            }
            PersonalReceiptChannels(
                wechat,
                alipay,
                cash
            )
        }

    val totalPeriodProfit =
        orderedSummaries.sumOf {
            it.profitShare
        }
    val profitRatio =
        if (
            kotlin.math.abs(totalPeriodProfit) >
            0.005
        ) {
            summary.profitShare /
                totalPeriodProfit *
                100.0
        } else {
            0.0
        }
    val purchaseDays =
        purchaseOrders
            .map { it.order.date }
            .distinct()
            .size
    val receiptDays =
        daily.count {
            kotlin.math.abs(
                it.revenueReceived
            ) > 0.005
        }
    val activityDays = daily.size
    val averagePurchase =
        if (purchaseOrders.isEmpty()) {
            0.0
        } else {
            summary.purchasePaid /
                purchaseOrders.size
        }
    val distinctFruitCount =
        purchaseOrders
            .flatMap { it.items }
            .map { it.fruitId to it.fruitName }
            .distinct()
            .size
    val highestProfit =
        daily.maxOfOrNull {
            it.profitShare
        } ?: 0.0
    val lowestProfit =
        daily.minOfOrNull {
            it.profitShare
        } ?: 0.0
    val sourceGap =
        summary.purchasePaid +
            summary.expensePaid +
            summary.profitShare -
            summary.revenueReceived
    val afterTransfer =
        sourceGap +
            summary.settlementSent -
            summary.settlementReceived

    val trendValues =
        remember(
            daily,
            trendMetric
        ) {
            daily
                .asReversed()
                .mapNotNull { row ->
                    runCatching {
                        LocalDate.parse(row.date)
                    }.getOrNull()?.let { date ->
                        val value =
                            when (trendMetric) {
                                PersonalTrendMetric.RECEIPT ->
                                    row.revenueReceived
                                PersonalTrendMetric.PURCHASE ->
                                    row.purchasePaid
                                PersonalTrendMetric.PROFIT ->
                                    row.profitShare
                            }
                        date to value
                    }
                }
        }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .background(Color(0xFFF6F6F6)),
        contentPadding =
            PaddingValues(
                horizontal = 12.dp,
                vertical = 10.dp
            ),
        verticalArrangement =
            Arrangement.spacedBy(10.dp)
    ) {
        item {
            TextButton(
                onClick = {
                    selectedPartnerId = null
                },
                contentPadding =
                    PaddingValues(horizontal = 0.dp)
            ) {
                Text("← 返回合伙人")
            }
            Text(
                summary.partnerName,
                style =
                    MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        }

        item {
            TimeFilterSelector(
                filter = filter,
                onFilterChange = {
                    filter = it
                },
                customStart = customStart,
                onCustomStart = {
                    customStart = it
                },
                customEnd = customEnd,
                onCustomEnd = {
                    customEnd = it
                }
            )
        }

        item {
            Card(
                Modifier.fillMaxWidth(),
                colors =
                    CardDefaults.cardColors(
                        containerColor = Color.White
                    )
            ) {
                Column(
                    Modifier.padding(12.dp),
                    verticalArrangement =
                        Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "核心汇总",
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        horizontalArrangement =
                            Arrangement.spacedBy(8.dp)
                    ) {
                        MiniSummaryCard(
                            "采购额",
                            money(summary.purchasePaid),
                            Modifier.weight(1f),
                            SoftOrange
                        )
                        MiniSummaryCard(
                            "收款",
                            money(summary.revenueReceived),
                            Modifier.weight(1f),
                            SoftBlue
                        )
                    }
                    Row(
                        horizontalArrangement =
                            Arrangement.spacedBy(8.dp)
                    ) {
                        MiniSummaryCard(
                            "个人利润",
                            money(summary.profitShare),
                            Modifier.weight(1f),
                            SoftGreen
                        )
                        MiniSummaryCard(
                            "个人开销",
                            money(summary.expensePaid),
                            Modifier.weight(1f),
                            SoftPurple
                        )
                    }
                }
            }
        }

        item {
            Card(
                Modifier.fillMaxWidth(),
                colors =
                    CardDefaults.cardColors(
                        containerColor = Color.White
                    )
            ) {
                Column(
                    Modifier.padding(12.dp),
                    verticalArrangement =
                        Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "参与与效率",
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "参与日 $activityDays · 采购日 $purchaseDays · 收款日 $receiptDays",
                        style =
                            MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "采购单 ${purchaseOrders.size} · 采购商品 $distinctFruitCount 种 · 平均单次采购 ${money(averagePurchase)}",
                        style =
                            MaterialTheme.typography.bodySmall,
                        color = Color.DarkGray
                    )
                    val avgDays = activityDays.coerceAtLeast(1)
                    Text(
                        "日均采购 ${money(summary.purchasePaid / avgDays)} · 日均收款 ${money(summary.revenueReceived / avgDays)} · 日均利润 ${money(summary.profitShare / avgDays)}",
                        style =
                            MaterialTheme.typography.bodySmall,
                        color = Color.DarkGray
                    )
                    Text(
                        "期间利润占比 ${cleanPercent(profitRatio)}% · 最高单日利润 ${money(highestProfit)} · 最低 ${money(lowestProfit)}",
                        style =
                            MaterialTheme.typography.bodySmall,
                        color = Color.DarkGray
                    )
                }
            }
        }

        item {
            Card(
                Modifier.fillMaxWidth(),
                colors =
                    CardDefaults.cardColors(
                        containerColor = Color.White
                    )
            ) {
                Column(
                    Modifier.padding(12.dp),
                    verticalArrangement =
                        Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "收款构成",
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        horizontalArrangement =
                            Arrangement.spacedBy(8.dp)
                    ) {
                        MiniSummaryCard(
                            "微信",
                            money(receiptChannels.wechat),
                            Modifier.weight(1f),
                            SoftGreen
                        )
                        MiniSummaryCard(
                            "支付宝",
                            money(receiptChannels.alipay),
                            Modifier.weight(1f),
                            SoftBlue
                        )
                        MiniSummaryCard(
                            "现金",
                            money(receiptChannels.cash),
                            Modifier.weight(1f),
                            SoftOrange
                        )
                    }
                    val classifiedReceipt =
                        receiptChannels.wechat +
                            receiptChannels.alipay +
                            receiptChannels.cash
                    val unclassifiedReceipt =
                        summary.revenueReceived -
                            classifiedReceipt
                    if (
                        kotlin.math.abs(
                            unclassifiedReceipt
                        ) > 0.005
                    ) {
                        Text(
                            "旧记录/未分类收款 ${money(unclassifiedReceipt)}",
                            style =
                                MaterialTheme.typography.labelSmall,
                            color = Color.Gray
                        )
                    }
                }
            }
        }

        item {
            Card(
                Modifier.fillMaxWidth(),
                colors =
                    CardDefaults.cardColors(
                        containerColor = Color.White
                    )
            ) {
                Column(
                    Modifier.padding(12.dp),
                    verticalArrangement =
                        Arrangement.spacedBy(7.dp)
                ) {
                    Text(
                        "资金往来",
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "实际转入 ${money(summary.settlementReceived)} · 实际转出 ${money(summary.settlementSent)}"
                    )
                    Text(
                        "期间资金差 ${money(sourceGap)} · 转账后净差 ${money(afterTransfer)}",
                        color =
                            if (
                                kotlin.math.abs(afterTransfer) <=
                                0.005
                            ) {
                                BrandGreen
                            } else {
                                Color.DarkGray
                            }
                    )
                    if (transfers.isEmpty()) {
                        Text(
                            "当前范围没有实际资金转账记录",
                            style =
                                MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    } else {
                        transfers.take(8).forEach { transfer ->
                            val incoming =
                                transfer.toPartnerId ==
                                    partnerId
                            Text(
                                buildString {
                                    append(transfer.date)
                                    append("  ")
                                    append(
                                        if (incoming) {
                                            "转入 "
                                        } else {
                                            "转出 "
                                        }
                                    )
                                    append(
                                        money(
                                            transfer.settledAmount
                                        )
                                    )
                                    append("  ")
                                    append(
                                        if (incoming) {
                                            "来自 ${transfer.fromPartnerName}"
                                        } else {
                                            "给 ${transfer.toPartnerName}"
                                        }
                                    )
                                },
                                style =
                                    MaterialTheme.typography.bodySmall,
                                color = Color.DarkGray
                            )
                        }
                        if (transfers.size > 8) {
                            Text(
                                "另有 ${transfers.size - 8} 条资金往来，可在下方每日明细查看。",
                                style =
                                    MaterialTheme.typography.labelSmall,
                                color = Color.Gray
                            )
                        }
                    }
                }
            }
        }

        item {
            Card(
                Modifier.fillMaxWidth(),
                colors =
                    CardDefaults.cardColors(
                        containerColor = Color.White
                    )
            ) {
                Column(
                    Modifier.padding(12.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment =
                            Alignment.CenterVertically
                    ) {
                        Text(
                            "个人趋势",
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        PersonalTrendMetric.entries.forEach { metric ->
                            FilterChip(
                                selected =
                                    trendMetric == metric,
                                onClick = {
                                    trendMetric = metric
                                },
                                label = {
                                    Text(metric.label)
                                },
                                modifier =
                                    Modifier.padding(start = 4.dp)
                            )
                        }
                    }
                    PersonalSummaryTrendChart(
                        trendValues,
                        when (trendMetric) {
                            PersonalTrendMetric.RECEIPT ->
                                Color(0xFF3A7BD5)
                            PersonalTrendMetric.PURCHASE ->
                                Color(0xFFE0A11A)
                            PersonalTrendMetric.PROFIT ->
                                BrandGreen
                        }
                    )
                }
            }
        }

        item {
            Text(
                "每日详细记录",
                style =
                    MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        if (daily.isEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        "当前时间范围暂无个人记录",
                        Modifier.padding(14.dp),
                        color = Color.Gray
                    )
                }
            }
        } else {
            items(
                daily,
                key = { it.date }
            ) { row ->
                Card(
                    Modifier.fillMaxWidth(),
                    colors =
                        CardDefaults.cardColors(
                            containerColor = Color.White
                        )
                ) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement =
                            Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            row.date,
                            fontWeight = FontWeight.Bold
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement =
                                Arrangement.SpaceBetween
                        ) {
                            Text(
                                "采购 ${money(row.purchasePaid)}",
                                color = Color(0xFF9A6A10)
                            )
                            Text(
                                "收款 ${money(row.revenueReceived)}",
                                color = Color(0xFF2F6EB5)
                            )
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement =
                                Arrangement.SpaceBetween
                        ) {
                            Text(
                                "利润 ${money(row.profitShare)}",
                                color =
                                    if (row.profitShare < 0) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        BrandGreen
                                    }
                            )
                            Text(
                                "开销 ${money(row.expensePaid)}",
                                color = Color(0xFF72569A)
                            )
                        }
                        Text(
                            "当日资金差 ${money(row.dayBalance)}",
                            style =
                                MaterialTheme.typography.bodySmall,
                            color = Color.DarkGray
                        )
                        row.transfers
                            .filter {
                                it.settledAmount > 0.005
                            }
                            .forEach { transfer ->
                                val incoming =
                                    transfer.toPartnerId ==
                                        partnerId
                                Text(
                                    if (incoming) {
                                        "↳ 实际转入 ${money(transfer.settledAmount)} · ${transfer.fromPartnerName}"
                                    } else {
                                        "↳ 实际转出 ${money(transfer.settledAmount)} · ${transfer.toPartnerName}"
                                    },
                                    style =
                                        MaterialTheme.typography.labelSmall,
                                    color = Color.Gray
                                )
                            }
                    }
                }
            }
        }

        if (purchaseOrders.isNotEmpty()) {
            item {
                Text(
                    "采购商品明细",
                    style =
                        MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            items(
                purchaseOrders,
                key = { it.order.id }
            ) { order ->
                Card(
                    Modifier.fillMaxWidth(),
                    colors =
                        CardDefaults.cardColors(
                            containerColor = Color.White
                        )
                ) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement =
                            Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement =
                                Arrangement.SpaceBetween
                        ) {
                            Text(
                                order.order.date,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                money(order.order.totalCost),
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF9A6A10)
                            )
                        }
                        order.items.forEach { line ->
                            Text(
                                "${line.fruitName}  ${cleanPercent(line.quantity)}${line.unit}  ${money(line.totalCost)}",
                                style =
                                    MaterialTheme.typography.bodySmall,
                                color = Color.DarkGray
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PersonalSummaryTrendChart(
    values: List<Pair<LocalDate, Double>>,
    color: Color
) {
    val minValue = (values.minOfOrNull { it.second } ?: 0.0).coerceAtMost(0.0)
    val maxValue = (values.maxOfOrNull { it.second } ?: 0.0).coerceAtLeast(0.0)
    val span = (maxValue - minValue).coerceAtLeast(1.0)
    Canvas(Modifier.fillMaxWidth().height(132.dp).padding(horizontal = 8.dp, vertical = 8.dp)) {
        val left = 16f
        val right = size.width - 16f
        val top = 26f
        val bottom = size.height - 12f
        repeat(3) { index ->
            val y = top + (bottom - top) * index / 2f
            drawLine(Color(0xFFE7E9ED), Offset(left, y), Offset(right, y), strokeWidth = 1.2f)
        }
        val zeroY = bottom - (((0.0 - minValue) / span).toFloat() * (bottom - top))
        drawLine(Color(0xFFB8BDC5), Offset(left, zeroY), Offset(right, zeroY), strokeWidth = 1.6f)
        if (values.isNotEmpty()) {
            val points = values.mapIndexed { index, item ->
                val x = if (values.size == 1) (left + right) / 2f else left + (right - left) * index / (values.size - 1).toFloat()
                val y = bottom - (((item.second - minValue) / span).toFloat() * (bottom - top))
                Offset(x, y)
            }
            points.zipWithNext().forEach { (a, b) -> drawLine(color, a, b, strokeWidth = 3.5f) }
            val labelIndices = if (values.size <= 7) {
                values.indices.toSet()
            } else {
                setOf(0, values.lastIndex, values.indices.maxByOrNull { values[it].second } ?: 0, values.indices.minByOrNull { values[it].second } ?: 0)
            }
            val paint = AndroidPaint().apply {
                isAntiAlias = true
                this.color = color.toArgb()
                textAlign = AndroidPaint.Align.CENTER
                textSize = 10.sp.toPx()
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            points.forEachIndexed { index, p ->
                drawCircle(Color.White, radius = 6f, center = p)
                drawCircle(color, radius = 4f, center = p)
                if (index in labelIndices) {
                    val labelY = if (values[index].second >= 0) p.y - 9f else p.y + paint.textSize + 9f
                    drawContext.canvas.nativeCanvas.drawText(
                        fmt(values[index].second),
                        p.x,
                        labelY.coerceIn(paint.textSize, size.height - 2f),
                        paint
                    )
                }
            }
        }
    }
}

@Composable
private fun ReportContent(
    db: AppDatabase,
    dataVersion: Int
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    var reportType by remember {
        mutableStateOf(ReportType.SETTLEMENT)
    }
    var reportTypeMenu by remember {
        mutableStateOf(false)
    }
    var detail by remember {
        mutableStateOf(ReportDetail.DETAILED)
    }
    var detailMenu by remember {
        mutableStateOf(false)
    }

    var filter by remember {
        mutableStateOf(HistoryTimeFilter.THIS_MONTH)
    }
    val today = LocalDate.now()
    var customStart by remember {
        mutableStateOf(today.withDayOfMonth(1).toString())
    }
    var customEnd by remember {
        mutableStateOf(today.toString())
    }

    var message by remember {
        mutableStateOf("")
    }
    val invalidCustom =
        filter == HistoryTimeFilter.CUSTOM &&
            customStart > customEnd

    val range =
        resolveTimeRange(
            filter,
            customStart,
            customEnd,
            today
        )

    val queryStart =
        if (invalidCustom) {
            "9999-12-31"
        } else {
            range.first
        }

    val queryEnd =
        if (invalidCustom) {
            "0000-01-01"
        } else {
            range.second
        }

    val periodLabel =
        when {
            invalidCustom ->
                "日期范围无效"

            queryStart == null || queryEnd == null ->
                "全部时间"

            queryStart == queryEnd ->
                queryStart

            else ->
                "$queryStart ～ $queryEnd"
        }

    val profitRows =
        remember(
            dataVersion,
            queryStart,
            queryEnd
        ) {
            db.getProfitDistributionsBetween(
                queryStart,
                queryEnd
            )
        }

    val cashSettlements =
        remember(
            dataVersion,
            queryStart,
            queryEnd
        ) {
            db.getCashSettlementsBetween(
                queryStart,
                queryEnd
            )
        }



    val businessRecords =
        remember(
            dataVersion,
            queryStart,
            queryEnd
        ) {
            db.getDailyRecordsBetween(
                queryStart,
                queryEnd
            )
        }

    val purchaseOrders =
        remember(
            dataVersion,
            queryStart,
            queryEnd
        ) {
            db.getPurchaseOrdersBetween(
                queryStart,
                queryEnd
            )
        }

    val businessDates =
        remember(
            businessRecords,
            purchaseOrders
        ) {
            (
                businessRecords.map { it.date } +
                    purchaseOrders.map {
                        it.order.date
                    }
                )
                .distinct()
                .sorted()
        }

    val businessSummaries =
        remember(
            dataVersion,
            businessDates
        ) {
            businessDates.map {
                db.getDailySummary(it)
            }
        }

    val rankings =
        remember(
            dataVersion,
            queryStart,
            queryEnd
        ) {
            db.getRankings(
                queryStart,
                queryEnd
            )
        }


    val reportLines =
        remember(
            reportType,
            detail,
            periodLabel,
            profitRows,
            cashSettlements,
            businessSummaries,
            businessRecords,
            rankings
        ) {
            buildReportLines(
                reportType = reportType,
                detail = detail,
                periodLabel = periodLabel,
                profitRows = profitRows,
                cashSettlements = cashSettlements,
                businessSummaries = businessSummaries,
                businessRecords = businessRecords,
                rankings = rankings
            )
        }

    fun reportBaseName(): String {
        val suffix =
            when (reportType) {
                ReportType.SETTLEMENT ->
                    "资金结算"

                ReportType.BUSINESS ->
                    "经营汇总"
            }

        val datePart =
            when {
                queryStart == null ||
                    queryEnd == null ->
                    "全部"

                queryStart == queryEnd ->
                    queryStart.replace("-", "")

                else ->
                    queryStart.replace("-", "") +
                        "_" +
                        queryEnd.replace("-", "")
            }

        return "天鲜果业_${suffix}_$datePart"
    }

    fun validateBeforeGenerate(): Boolean {
        if (invalidCustom) {
            message = "开始日期不能晚于结束日期"
            return false
        }


        if (reportLines.isEmpty()) {
            message = "当前条件没有可以生成的报表数据"
            return false
        }

        return true
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding =
            PaddingValues(
                horizontal = 14.dp,
                vertical = 6.dp
            ),
        verticalArrangement =
            Arrangement.spacedBy(10.dp)
    ) {
        item {
            PageHeader(
                "生成报表",
                "按时间生成经营 / 资金报表，可直接分享到微信等应用"
            )
        }

        item {
            Box(Modifier.fillMaxWidth()) {
                CompactSelectButton(
                    "报表类型",
                    reportType.label,
                    Modifier.fillMaxWidth()
                ) {
                    reportTypeMenu = true
                }

                DropdownMenu(
                    expanded =
                        reportTypeMenu,
                    onDismissRequest = {
                        reportTypeMenu = false
                    }
                ) {
                    ReportType.entries.forEach {
                        option ->
                        DropdownMenuItem(
                            text = {
                                Text(option.label)
                            },
                            onClick = {
                                reportType = option
                                reportTypeMenu = false
                                message = ""
                            }
                        )
                    }
                }
            }
        }

        item {
            TimeFilterSelector(
                filter = filter,
                onFilterChange = {
                    filter = it
                    message = ""
                },
                customStart = customStart,
                onCustomStart = {
                    customStart = it
                    message = ""
                },
                customEnd = customEnd,
                onCustomEnd = {
                    customEnd = it
                    message = ""
                }
            )
        }

        if (invalidCustom) {
            item {
                Text(
                    "开始日期不能晚于结束日期",
                    color =
                        MaterialTheme.colorScheme.error
                )
            }
        }

        item {
            Box(Modifier.fillMaxWidth()) {
                CompactSelectButton(
                    "报表详细程度",
                    detail.label,
                    Modifier.fillMaxWidth()
                ) {
                    detailMenu = true
                }

                DropdownMenu(
                    expanded = detailMenu,
                    onDismissRequest = {
                        detailMenu = false
                    }
                ) {
                    ReportDetail.entries.forEach {
                        option ->
                        DropdownMenuItem(
                            text = {
                                Text(option.label)
                            },
                            onClick = {
                                detail = option
                                detailMenu = false
                            }
                        )
                    }
                }
            }
        }

        item {
            Card(
                colors =
                    CardDefaults.cardColors(
                        containerColor =
                            Color(0xFFF7FAF8)
                    )
            ) {
                Column(
                    Modifier.padding(12.dp),
                    verticalArrangement =
                        Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        "报表预览",
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        reportType.label,
                        color = BrandGreen,
                        fontWeight =
                            FontWeight.SemiBold
                    )
                    Text(
                        "统计期间：$periodLabel",
                        style =
                            MaterialTheme.typography
                                .bodySmall
                    )

                    Text(
                        "格式：${detail.label}",
                        style =
                            MaterialTheme.typography
                                .bodySmall
                    )

                    HorizontalDivider()

                    reportLines
                        .filter {
                            it.style !=
                                ReportLineStyle.SPACER
                        }
                        .take(8)
                        .forEach { line ->
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    line.text,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight =
                                        if (
                                            line.style == ReportLineStyle.SECTION ||
                                            line.style == ReportLineStyle.TOTAL
                                        ) {
                                            FontWeight.Bold
                                        } else {
                                            FontWeight.Normal
                                        },
                                    modifier = Modifier.weight(1f)
                                )
                                if (line.rightText.isNotBlank()) {
                                    Text(
                                        line.rightText,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }

                    if (
                        reportLines.count {
                            it.style !=
                                ReportLineStyle.SPACER
                        } > 8
                    ) {
                        Text(
                            "……完整内容将在生成的报表中显示",
                            color = Color.Gray,
                            style =
                                MaterialTheme
                                    .typography
                                    .bodySmall
                        )
                    }
                }
            }
        }

        item {
            Text(
                "生成并分享",
                fontWeight = FontWeight.Bold
            )
        }

        item {
            Row(
                horizontalArrangement =
                    Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        if (
                            validateBeforeGenerate()
                        ) {
                            runCatching {
                                ReportGenerator.createPdf(
                                    context = context,
                                    baseName =
                                        reportBaseName(),
                                    lines = reportLines
                                )
                            }
                                .onSuccess {
                                    ReportGenerator.share(
                                        context,
                                        it
                                    )
                                    message =
                                        "已打开系统分享面板"
                                }
                                .onFailure {
                                    message =
                                        "PDF生成失败：${it.message}"
                                }
                        }
                    },
                    modifier =
                        Modifier.weight(1f)
                ) {
                    Text("PDF 分享")
                }

                Button(
                    onClick = {
                        if (
                            validateBeforeGenerate()
                        ) {
                            runCatching {
                                ReportGenerator.createPng(
                                    context = context,
                                    baseName =
                                        reportBaseName(),
                                    lines = reportLines
                                )
                            }
                                .onSuccess {
                                    ReportGenerator.share(
                                        context,
                                        it
                                    )
                                    message =
                                        "已打开系统分享面板"
                                }
                                .onFailure {
                                    message =
                                        "图片生成失败：${it.message}"
                                }
                        }
                    },
                    modifier =
                        Modifier.weight(1f)
                ) {
                    Text("图片分享")
                }
            }
        }

        item {
            Row(
                horizontalArrangement =
                    Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        if (
                            validateBeforeGenerate()
                        ) {
                            runCatching {
                                val report = ReportGenerator.createPdf(
                                    context = context,
                                    baseName =
                                        reportBaseName(),
                                    lines = reportLines
                                )
                                ReportGenerator.saveToDevice(
                                    context,
                                    report
                                )
                            }
                                .onSuccess { path ->
                                    message = "PDF 已保存：$path"
                                }
                                .onFailure {
                                    message =
                                        "保存失败：${it.message ?: "未知错误"}"
                                }
                        }
                    },
                    modifier =
                        Modifier.weight(1f)
                ) {
                    Text("保存PDF")
                }

                OutlinedButton(
                    onClick = {
                        if (
                            validateBeforeGenerate()
                        ) {
                            runCatching {
                                val report = ReportGenerator.createPng(
                                    context = context,
                                    baseName =
                                        reportBaseName(),
                                    lines = reportLines
                                )
                                ReportGenerator.saveToDevice(
                                    context,
                                    report
                                )
                            }
                                .onSuccess { path ->
                                    message = "图片已保存：$path"
                                }
                                .onFailure {
                                    message =
                                        "保存失败：${it.message ?: "未知错误"}"
                                }
                        }
                    },
                    modifier =
                        Modifier.weight(1f)
                ) {
                    Text("保存图片")
                }
            }
        }

        if (message.isNotBlank()) {
            item {
                Text(
                    message,
                    color =
                        if (
                            message.contains("失败") ||
                            message.contains("请")
                        ) {
                            MaterialTheme
                                .colorScheme
                                .error
                        } else {
                            BrandGreen
                        },
                    style =
                        MaterialTheme.typography
                            .bodySmall
                )
            }
        }

        item {
            Text(
                "说明：经营汇总报表用于看位置经营表现；资金结算报表按经营日汇总位置营业额、当日采购、利润分配、资金轧差方案和最少转账方案。报表不展示“待结/已结”状态。所有导出的 PDF / 图片右下角都会标注生成时间。",
                color = Color.Gray,
                style =
                    MaterialTheme.typography
                        .bodySmall
            )
        }
    }
}

private fun buildReportLines(
    reportType: ReportType,
    detail: ReportDetail,
    periodLabel: String,
    profitRows: List<ProfitDistributionRecord>,
    cashSettlements: List<CashSettlementBundle>,
    businessSummaries: List<DailySummary>,
    businessRecords: List<StoreDailyRecord>,
    rankings: List<RankingRecord>
): List<ReportLine> =
    when (reportType) {
        ReportType.SETTLEMENT ->
            buildFundSettlementReportLines(
                periodLabel = periodLabel,
                detail = detail,
                profitRows = profitRows,
                cashSettlements = cashSettlements,
                businessSummaries = businessSummaries,
                businessRecords = businessRecords
            )

        ReportType.BUSINESS ->
            buildBusinessReportLines(
                periodLabel = periodLabel,
                detail = detail,
                summaries = businessSummaries,
                businessRecords = businessRecords,
                rankings = rankings
            )
    }

private fun buildFundSettlementReportLines(
    periodLabel: String,
    detail: ReportDetail,
    profitRows: List<ProfitDistributionRecord>,
    cashSettlements: List<CashSettlementBundle>,
    businessSummaries: List<DailySummary>,
    businessRecords: List<StoreDailyRecord>
): List<ReportLine> {
    val operatingDates =
        businessRecords.map { it.date }
            .distinct()
            .sortedDescending()

    if (operatingDates.isEmpty()) return emptyList()

    val summaryByDate = businessSummaries.associateBy { it.date }
    val recordsByDate = businessRecords.groupBy { it.date }
    val profitsByDate = profitRows.groupBy { it.date }
    val settlementByDate = cashSettlements.associateBy { it.settlement.date }

    val periodRevenue = operatingDates.sumOf { summaryByDate[it]?.revenue ?: 0.0 }
    val periodPurchase = businessSummaries.sumOf { it.purchaseCost }
    val periodProfit = operatingDates.sumOf { summaryByDate[it]?.profit ?: 0.0 }

    val lines = mutableListOf<ReportLine>()
    lines += ReportLine("天鲜果业", ReportLineStyle.TITLE)
    lines += ReportLine("资金结算报表", ReportLineStyle.SUBTITLE)
    lines += ReportLine("统计期间：$periodLabel", ReportLineStyle.MUTED)
    lines += ReportLine("", ReportLineStyle.SPACER)

    lines += ReportLine("期间资金经营汇总", ReportLineStyle.SECTION)
    lines += ReportLine("经营日数", ReportLineStyle.NORMAL, "${operatingDates.size} 天")
    lines += ReportLine("期间营业额", ReportLineStyle.TOTAL, money(periodRevenue))
    lines += ReportLine("期间采购金额", ReportLineStyle.NORMAL, money(periodPurchase))
    lines += ReportLine(
        if (periodProfit < -0.005) "期间总亏损" else "期间总利润",
        if (periodProfit < -0.005) ReportLineStyle.NEGATIVE else ReportLineStyle.POSITIVE,
        money(kotlin.math.abs(periodProfit))
    )

    if (detail == ReportDetail.DETAILED) {
        lines += ReportLine("", ReportLineStyle.SPACER)
        lines += ReportLine("每日资金结算明细", ReportLineStyle.SECTION)

        operatingDates.forEach { date ->
            val summary = summaryByDate[date] ?: return@forEach
            val weekday = runCatching { chineseWeekday(LocalDate.parse(date)) }.getOrDefault("")
            val dayRecords = recordsByDate[date].orEmpty()
            val dayProfitRows = profitsByDate[date].orEmpty().sortedBy { it.partnerId }
            val bundle = settlementByDate[date]

            lines += ReportLine(
                listOf(date, weekday).filter { it.isNotBlank() }.joinToString("  "),
                ReportLineStyle.SECTION
            )

            val locationGroups =
                dayRecords.groupBy { it.storeId to it.storeName }
                    .toList()
                    .sortedBy { it.first.second }

            if (locationGroups.isEmpty()) {
                lines += ReportLine("经营位置", ReportLineStyle.MUTED, "未记录")
            } else {
                locationGroups.forEach { (_, rows) ->
                    val storeName = rows.first().storeName.ifBlank { "未命名位置" }
                    lines += ReportLine(
                        "位置 · $storeName",
                        ReportLineStyle.NORMAL,
                        "营业额 ${money(rows.sumOf { it.revenue })}"
                    )
                }
            }

            lines += ReportLine("当日采购金额", ReportLineStyle.TOTAL, money(summary.purchaseCost))
            lines += ReportLine(
                if (summary.profit < -0.005) "当日总亏损" else "当日总利润",
                if (summary.profit < -0.005) ReportLineStyle.NEGATIVE else ReportLineStyle.POSITIVE,
                money(kotlin.math.abs(summary.profit))
            )

            lines += ReportLine("利润分配", ReportLineStyle.SECTION)
            if (dayProfitRows.isEmpty()) {
                lines += ReportLine("当日未保存利润 / 亏损分配", ReportLineStyle.MUTED)
            } else {
                dayProfitRows.forEach { row ->
                    val ratio = "${fmt(profitRatioPercent(row.ratio))}%"
                    val label =
                        if (row.allocatedProfit < -0.005) {
                            "${row.partnerName} · $ratio · 亏损分担"
                        } else {
                            "${row.partnerName} · $ratio · 利润分配"
                        }
                    lines += ReportLine(
                        label,
                        if (row.allocatedProfit < -0.005) {
                            ReportLineStyle.NEGATIVE
                        } else {
                            ReportLineStyle.POSITIVE
                        },
                        money(kotlin.math.abs(row.allocatedProfit))
                    )
                }
            }

            lines += ReportLine("资金轧差方案", ReportLineStyle.SECTION)
            if (bundle == null) {
                lines += ReportLine("当日尚未生成资金轧差方案", ReportLineStyle.MUTED)
            } else if (bundle.partners.isEmpty()) {
                lines += ReportLine("当日无需资金轧差", ReportLineStyle.MUTED)
            } else {
                bundle.partners.sortedBy { it.partnerId }.forEach { partner ->
                    when {
                        partner.balance > 0.005 ->
                            lines += ReportLine(
                                "${partner.partnerName} · 应收",
                                ReportLineStyle.POSITIVE,
                                money(partner.balance)
                            )
                        partner.balance < -0.005 ->
                            lines += ReportLine(
                                "${partner.partnerName} · 应补",
                                ReportLineStyle.NEGATIVE,
                                money(-partner.balance)
                            )
                        else ->
                            lines += ReportLine(
                                "${partner.partnerName} · 资金已平",
                                ReportLineStyle.MUTED,
                                money(0.0)
                            )
                    }
                }
            }

            lines += ReportLine("最少转账方案", ReportLineStyle.SECTION)
            when {
                bundle == null ->
                    lines += ReportLine("当日尚未生成最少转账方案", ReportLineStyle.MUTED)
                bundle.transfers.isEmpty() ->
                    lines += ReportLine("无需转账，资金已平衡", ReportLineStyle.POSITIVE)
                else ->
                    bundle.transfers.forEach { transfer ->
                        lines += ReportLine(
                            "${transfer.fromPartnerName} → ${transfer.toPartnerName}",
                            ReportLineStyle.NORMAL,
                            money(transfer.amount)
                        )
                    }
            }

            lines += ReportLine("", ReportLineStyle.SPACER)
        }
    }

    return lines
}

private fun buildBusinessReportLines(
    periodLabel: String,
    detail: ReportDetail,
    summaries: List<DailySummary>,
    businessRecords: List<StoreDailyRecord>,
    rankings: List<RankingRecord>
): List<ReportLine> {
    if (summaries.isEmpty() && rankings.isEmpty()) return emptyList()

    val totalRevenue = summaries.sumOf { it.revenue }
    val totalProfit = summaries.sumOf { it.profit }
    val totalPurchase = summaries.sumOf { it.purchaseCost }
    val totalExpense = summaries.sumOf { it.expense }
    val totalCustomers = summaries.sumOf { it.customers }
    val operatingDayCount = businessRecords.map { it.date }.distinct().size

    val lines = mutableListOf<ReportLine>()
    lines += ReportLine("天鲜果业", ReportLineStyle.TITLE)
    lines += ReportLine("经营汇总报表", ReportLineStyle.SUBTITLE)
    lines += ReportLine("统计期间：$periodLabel", ReportLineStyle.MUTED)
    lines += ReportLine("", ReportLineStyle.SPACER)
    lines += ReportLine("经营汇总", ReportLineStyle.SECTION)
    lines += ReportLine("营业额", ReportLineStyle.TOTAL, money(totalRevenue))
    lines += ReportLine(
        if (totalProfit < -0.005) "亏损" else "利润",
        if (totalProfit < -0.005) ReportLineStyle.NEGATIVE else ReportLineStyle.POSITIVE,
        money(kotlin.math.abs(totalProfit))
    )
    lines += ReportLine("采购金额", ReportLineStyle.NORMAL, money(totalPurchase))
    lines += ReportLine("业务费用", ReportLineStyle.NORMAL, money(totalExpense))
    lines += ReportLine("客户数", ReportLineStyle.NORMAL, "$totalCustomers 人")

    if (operatingDayCount > 0) {
        lines += ReportLine("经营日数", ReportLineStyle.NORMAL, "$operatingDayCount 天")
        lines += ReportLine("日均营业额", ReportLineStyle.NORMAL, money(totalRevenue / operatingDayCount))
        lines += ReportLine("日均利润", ReportLineStyle.NORMAL, money(totalProfit / operatingDayCount))
    }

    if (rankings.isNotEmpty()) {
        lines += ReportLine("", ReportLineStyle.SPACER)
        lines += ReportLine("位置排行", ReportLineStyle.SECTION)
        rankings.sortedByDescending { it.revenue }.forEachIndexed { index, row ->
            val avgRevenue = if (row.days > 0) row.revenue / row.days else 0.0
            lines += ReportLine(
                "${index + 1}. ${row.storeName} · 经营 ${row.days} 日",
                ReportLineStyle.NORMAL,
                "营业额 ${money(row.revenue)}"
            )
            lines += ReportLine(
                "日均营业额 ${money(avgRevenue)} · 利润 ${money(row.profit)} · 客户 ${row.customers}",
                ReportLineStyle.MUTED
            )
        }
    }

    if (detail == ReportDetail.DETAILED) {
        lines += ReportLine("", ReportLineStyle.SPACER)
        lines += ReportLine("每日经营明细", ReportLineStyle.SECTION)

        val recordsByDate = businessRecords.groupBy { it.date }
        summaries.filter { it.date in recordsByDate.keys }
            .sortedByDescending { it.date }
            .forEach { row ->
                val dayRecords = recordsByDate[row.date].orEmpty()
                val weekday = runCatching { chineseWeekday(LocalDate.parse(row.date)) }.getOrDefault("")

                lines += ReportLine(
                    listOf(row.date, weekday).filter { it.isNotBlank() }.joinToString("  "),
                    ReportLineStyle.SECTION
                )
                lines += ReportLine("当日营业额", ReportLineStyle.TOTAL, money(row.revenue))
                lines += ReportLine(
                    if (row.profit < -0.005) "当日亏损" else "当日利润",
                    if (row.profit < -0.005) ReportLineStyle.NEGATIVE else ReportLineStyle.POSITIVE,
                    money(kotlin.math.abs(row.profit))
                )
                lines += ReportLine("当日采购", ReportLineStyle.NORMAL, money(row.purchaseCost))
                lines += ReportLine("当日费用", ReportLineStyle.NORMAL, money(row.expense))
                lines += ReportLine("当日客户", ReportLineStyle.NORMAL, "${row.customers} 人")

                dayRecords.groupBy { it.storeId to it.storeName }
                    .toList()
                    .sortedBy { it.first.second }
                    .forEach { (_, storeRows) ->
                        val storeName = storeRows.first().storeName.ifBlank { "未命名位置" }
                        val revenue = storeRows.sumOf { it.revenue }
                        val profit = storeRows.sumOf { it.profit }
                        val customers = storeRows.sumOf { it.customerTotal }
                        lines += ReportLine(
                            "位置 · $storeName",
                            ReportLineStyle.NORMAL,
                            "营业额 ${money(revenue)}"
                        )
                        lines += ReportLine(
                            "利润 ${money(profit)} · 客户 $customers",
                            ReportLineStyle.MUTED
                        )
                    }
                lines += ReportLine("", ReportLineStyle.SPACER)
            }
    }

    return lines
}

@Composable
private fun LedgerManagementContent(
    db: AppDatabase,
    ledgerManager: LedgerManager,
    cloudSyncManager: CloudSyncManager,
    currentBook: LedgerBook,
    onSwitchBook: (String) -> Unit,
    onChanged: () -> Unit
) {
    var refresh by remember {
        mutableIntStateOf(0)
    }
    var cloudRefresh by remember {
        mutableIntStateOf(0)
    }

    val books =
        remember(refresh) {
            ledgerManager.books()
        }

    val status =
        remember(refresh) {
            db.getSyncFoundationStatus()
        }

    val cloudSession =
        remember(cloudRefresh) {
            cloudSyncManager.session()
        }

    val cloudLocalStatus =
        remember(
            refresh,
            cloudRefresh
        ) {
            db.getCloudSyncLocalStatus()
        }

    val liveCurrentBook =
        remember(
            refresh,
            cloudRefresh
        ) {
            ledgerManager
                .getBook(
                    currentBook.id
                )
                ?: currentBook
        }

    val conflicts =
        remember(
            refresh,
            cloudRefresh
        ) {
            db.getSyncConflicts()
        }

    var showSyncDetails by remember {
        mutableStateOf(false)
    }
    var showConflictDialog by remember {
        mutableStateOf(false)
    }
    var showAuditDialog by remember {
        mutableStateOf(false)
    }
    var auditRows by remember {
        mutableStateOf<
            List<CloudAuditInfo>
        >(
            emptyList()
        )
    }

    var cloudBooks by remember {
        mutableStateOf<
            List<CloudBookInfo>
        >(
            emptyList()
        )
    }
    var cloudBooksLoaded by remember {
        mutableStateOf(false)
    }

    val manageableCloudBooks = remember(cloudBooks, cloudSession?.systemRole) {
        if (cloudSession?.systemRole == "SUPERADMIN") cloudBooks
        else cloudBooks.filter { it.role == "OWNER" }
    }

    val manageableLocalBooks = remember(books, cloudSession?.systemRole) {
        if (cloudSession?.systemRole == "SUPERADMIN") books
        else books.filter { it.permission == "OWNER" }
    }

    var cloudBusy by remember {
        mutableStateOf(false)
    }
    var cloudMessage by remember {
        mutableStateOf("")
    }
    var message by remember {
        mutableStateOf("")
    }

    var serverUrl by remember {
        mutableStateOf(
            cloudSyncManager
                .defaultBaseUrl()
        )
    }
    var cloudUsername by remember {
        mutableStateOf(
            cloudSession
                ?.username
                .orEmpty()
        )
    }
    var cloudPassword by remember {
        mutableStateOf("")
    }

    var addDialog by remember {
        mutableStateOf(false)
    }
    var renameBook by remember {
        mutableStateOf<LedgerBook?>(null)
    }
    var deleteBook by remember {
        mutableStateOf<LedgerBook?>(null)
    }
    var renameCloudBook by remember { mutableStateOf<CloudBookInfo?>(null) }
    var deleteCloudBook by remember { mutableStateOf<CloudBookInfo?>(null) }
    var transferCloudBook by remember { mutableStateOf<CloudBookInfo?>(null) }
    var trashBooks by remember { mutableStateOf<List<CloudBookInfo>>(emptyList()) }
    var showTrash by remember { mutableStateOf(false) }
    fun runCloudTask(
        busyText: String,
        block: () -> String,
        onSuccess: () -> Unit = {}
    ) {
        if (cloudBusy) return

        cloudBusy = true
        cloudMessage = busyText

        Thread {
            val result =
                runCatching {
                    block()
                }

            Handler(
                Looper.getMainLooper()
            ).post {
                cloudBusy = false

                result.fold(
                    onSuccess = {
                        text ->
                        cloudMessage = text
                        onSuccess()
                    },
                    onFailure = {
                        error ->
                        cloudMessage =
                            when (error) {
                                is CloudApiException ->
                                    "云端错误 ${error.statusCode}：${error.message}"

                                else ->
                                    "操作失败：${error.message ?: error.javaClass.simpleName}"
                            }
                    }
                )

                cloudPassword = ""
                cloudRefresh++
                refresh++
                onChanged()
            }
        }.start()
    }

    fun refreshCloudBooks() {
        var loaded:
            List<CloudBookInfo> =
            emptyList()

        runCloudTask(
            "正在刷新云端账本…",
            {
                loaded =
                    cloudSyncManager
                        .listCloudBooks()

                "已找到 ${loaded.size} 个可访问的云端账本"
            },
            {
                cloudBooks =
                    loaded
                cloudBooksLoaded =
                    true
            }
        )
    }

    fun loadAudit() {
        var loaded:
            List<CloudAuditInfo> =
            emptyList()

        runCloudTask(
            busyText =
                "正在读取修改记录…",
            block = {
                loaded =
                    cloudSyncManager
                        .listAudit(
                            liveCurrentBook.id,
                            100
                        )

                "已读取 ${loaded.size} 条修改记录"
            },
            onSuccess = {
                auditRows = loaded
                showAuditDialog =
                    true
            }
        )
    }


    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding =
            PaddingValues(16.dp),
        verticalArrangement =
            Arrangement.spacedBy(10.dp)
    ) {
        item {
            Card(
                colors =
                    CardDefaults.cardColors(
                        containerColor =
                            Color(0xFFF2FAF5)
                    )
            ) {
                Column(
                    Modifier.padding(14.dp),
                    verticalArrangement =
                        Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        "当前账本",
                        color = Color.Gray,
                        style =
                            MaterialTheme
                                .typography
                                .bodySmall
                    )

                    Text(
                        ledgerManager.displayName(
                            liveCurrentBook
                        ),
                        style =
                            MaterialTheme
                                .typography
                                .titleLarge,
                        fontWeight =
                            FontWeight.Bold
                    )

                    Text(
                        "权限：" +
                            ledgerManager
                                .permissionLabel(
                                    liveCurrentBook
                                        .permission
                                ),
                        color =
                            if (
                                liveCurrentBook
                                    .permission ==
                                    "VIEWER"
                            ) {
                                Color(
                                    0xFFC37B00
                                )
                            } else {
                                Color.Unspecified
                            }
                    )

                    Text(
                        "设备：" +
                            ledgerManager
                                .deviceName,
                        style =
                            MaterialTheme
                                .typography
                                .bodySmall
                    )

                    val syncStatusText =
                        when {
                            liveCurrentBook
                                .permission ==
                                "REVOKED" ->
                                "已失去云端权限"

                            conflicts
                                .isNotEmpty() ->
                                "需要处理 ${conflicts.size} 条冲突"

                            cloudLocalStatus
                                .lastError
                                .isNotBlank() ->
                                "同步异常"

                            status.pendingChanges >
                                0 ->
                                "待同步 ${status.pendingChanges} 条"

                            liveCurrentBook
                                .cloudEnabled ->
                                "已同步"

                            else ->
                                "仅本机"
                        }

                    Text(
                        "同步状态：$syncStatusText",
                        color =
                            when {
                                conflicts
                                    .isNotEmpty() ->
                                    MaterialTheme
                                        .colorScheme
                                        .error

                                liveCurrentBook
                                    .permission ==
                                    "REVOKED" ->
                                    MaterialTheme
                                        .colorScheme
                                        .error

                                liveCurrentBook
                                    .cloudEnabled &&
                                    status
                                        .pendingChanges ==
                                        0 ->
                                    BrandGreen

                                else ->
                                    Color.Gray
                            },
                        fontWeight =
                            FontWeight.SemiBold
                    )

                    Text(
                        "自动同步：已开启（启动、回到前台、保存数据后）",
                        style =
                            MaterialTheme
                                .typography
                                .bodySmall,
                        color = Color.Gray
                    )

                    if (
                        cloudLocalStatus
                            .lastSyncAt > 0L
                    ) {
                        Text(
                            "最近同步：" +
                                formatDateTime(
                                    cloudLocalStatus
                                        .lastSyncAt
                                ),
                            style =
                                MaterialTheme
                                    .typography
                                    .bodySmall,
                            color = Color.Gray
                        )
                    }

                    if (
                        conflicts
                            .isNotEmpty()
                    ) {
                        Button(
                            onClick = {
                                showConflictDialog =
                                    true
                            },
                            modifier =
                                Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "处理同步冲突（${conflicts.size}）"
                            )
                        }
                    }

                    if (
                        cloudSession != null &&
                        liveCurrentBook
                            .cloudEnabled
                    ) {
                        OutlinedButton(
                            onClick = {
                                loadAudit()
                            },
                            enabled =
                                !cloudBusy,
                            modifier =
                                Modifier.fillMaxWidth()
                        ) {
                            Text("查看修改记录")
                        }
                    }

                    TextButton(
                        onClick = {
                            showSyncDetails =
                                !showSyncDetails
                        },
                        modifier =
                            Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (
                                showSyncDetails
                            ) {
                                "收起同步详情"
                            } else {
                                "同步详情"
                            }
                        )
                    }

                    if (showSyncDetails) {
                        Text(
                            "设备ID：" +
                                status.deviceId
                                    .take(8) +
                                "…",
                            style =
                                MaterialTheme
                                    .typography
                                    .bodySmall,
                            color = Color.Gray
                        )

                        Text(
                            "待上传变更：" +
                                "${status.pendingChanges} 条",
                            style =
                                MaterialTheme
                                    .typography
                                    .bodySmall,
                            color = Color.Gray
                        )

                        Text(
                            "同步序号：" +
                                cloudLocalStatus
                                    .serverCursor,
                            style =
                                MaterialTheme
                                    .typography
                                    .bodySmall,
                            color = Color.Gray
                        )

                        if (
                            cloudLocalStatus
                                .lastError
                                .isNotBlank()
                        ) {
                            Text(
                                "最近错误：" +
                                    cloudLocalStatus
                                        .lastError,
                                style =
                                    MaterialTheme
                                        .typography
                                        .bodySmall,
                                color =
                                    MaterialTheme
                                        .colorScheme
                                        .error
                            )
                        }
                    }
                }
            }
        }

        item {
            Card(
                colors =
                    CardDefaults.cardColors(
                        containerColor =
                            Color(0xFFF4F7FF)
                    )
            ) {
                Column(
                    Modifier.padding(14.dp),
                    verticalArrangement =
                        Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "云端账号",
                        style =
                            MaterialTheme
                                .typography
                                .titleMedium,
                        fontWeight =
                            FontWeight.Bold
                    )

                    if (cloudSession == null) {
                        OutlinedTextField(
                            value = serverUrl,
                            onValueChange = {
                                serverUrl = it
                            },
                            label = {
                                Text("服务器地址")
                            },
                            singleLine = true,
                            modifier =
                                Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value =
                                cloudUsername,
                            onValueChange = {
                                cloudUsername = it
                            },
                            label = {
                                Text("用户名")
                            },
                            singleLine = true,
                            modifier =
                                Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value =
                                cloudPassword,
                            onValueChange = {
                                cloudPassword = it
                            },
                            label = {
                                Text("密码")
                            },
                            singleLine = true,
                            visualTransformation =
                                PasswordVisualTransformation(),
                            modifier =
                                Modifier.fillMaxWidth()
                        )

                        Button(
                            onClick = {
                                if (
                                    cloudUsername
                                        .trim()
                                        .isBlank() ||
                                    cloudPassword
                                        .isBlank()
                                ) {
                                    cloudMessage =
                                        "请输入用户名和密码"
                                } else {
                                    var loaded:
                                        List<CloudBookInfo> =
                                        emptyList()

                                    runCloudTask(
                                        "正在登录云端…",
                                        {
                                            val session =
                                                cloudSyncManager
                                                    .login(
                                                        baseUrl =
                                                            serverUrl,
                                                        username =
                                                            cloudUsername,
                                                        password =
                                                            cloudPassword
                                                    )

                                            loaded =
                                                cloudSyncManager
                                                    .listCloudBooks()

                                            "登录成功：${session.displayName}"
                                        },
                                        {
                                            cloudBooks =
                                                loaded
                                            cloudBooksLoaded =
                                                true
                                        }
                                    )
                                }
                            },
                            enabled =
                                !cloudBusy,
                            modifier =
                                Modifier.fillMaxWidth()
                        ) {
                            if (cloudBusy) {
                                CircularProgressIndicator(
                                    modifier =
                                        Modifier.size(
                                            18.dp
                                        ),
                                    strokeWidth = 2.dp
                                )
                                Spacer(
                                    Modifier.width(
                                        8.dp
                                    )
                                )
                            }
                            Text("登录云端")
                        }
                    } else {
                        Text(
                            "${cloudSession.displayName}（${cloudSession.username}）" +
                                if (cloudSession.systemRole == "SUPERADMIN") " · 超级管理员" else "",
                            fontWeight = FontWeight.SemiBold
                        )

                        Text(
                            cloudSession.baseUrl,
                            style =
                                MaterialTheme
                                    .typography
                                    .bodySmall,
                            color = Color.Gray
                        )

                        Button(
                            onClick = {
                                runCloudTask(
                                    busyText =
                                        "正在同步“${currentBook.name}”…",
                                    block = {
                                        cloudSyncManager
                                            .syncCurrentBook(
                                                db = db,
                                                book =
                                                    ledgerManager
                                                        .getBook(
                                                            currentBook.id
                                                        )
                                                        ?: currentBook
                                            )
                                            .message
                                    }
                                )
                            },
                            enabled =
                                !cloudBusy &&
                                liveCurrentBook
                                    .permission !=
                                    "REVOKED",
                            modifier =
                                Modifier.fillMaxWidth()
                        ) {
                            if (cloudBusy) {
                                CircularProgressIndicator(
                                    modifier =
                                        Modifier.size(
                                            18.dp
                                        ),
                                    strokeWidth = 2.dp
                                )
                                Spacer(
                                    Modifier.width(
                                        8.dp
                                    )
                                )
                            }

                            Text(
                                when (
                                    liveCurrentBook
                                        .permission
                                ) {
                                    "REVOKED" ->
                                        "已失去云端权限"

                                    "VIEWER" ->
                                        "立即刷新只读账本"

                                    else ->
                                        "立即同步当前账本"
                                }
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                refreshCloudBooks()
                            },
                            enabled =
                                !cloudBusy,
                            modifier =
                                Modifier.fillMaxWidth()
                        ) {
                            Text("刷新云端账本列表")
                        }

                    }

                    if (
                        cloudMessage
                            .isNotBlank()
                    ) {
                        Text(
                            cloudMessage,
                            color =
                                if (
                                    cloudMessage
                                        .contains(
                                            "失败"
                                        ) ||
                                    cloudMessage
                                        .contains(
                                            "错误"
                                        ) ||
                                    cloudMessage
                                        .contains(
                                            "冲突"
                                        )
                                ) {
                                    MaterialTheme
                                        .colorScheme
                                        .error
                                } else {
                                    BrandGreen
                                },
                            style =
                                MaterialTheme
                                    .typography
                                    .bodySmall
                        )
                    }
                }
            }
        }

        if (cloudSession != null) {
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment =
                        Alignment.CenterVertically
                ) {
                    Text(
                        if (cloudSession?.systemRole == "SUPERADMIN") "云端共享账本" else "我的云端账本",
                        style =
                            MaterialTheme
                                .typography
                                .titleMedium,
                        fontWeight =
                            FontWeight.Bold,
                        modifier =
                            Modifier.weight(1f)
                    )

                    if (!cloudBooksLoaded) {
                        Text(
                            "点击上方刷新",
                            style =
                                MaterialTheme
                                    .typography
                                    .bodySmall,
                            color = Color.Gray
                        )
                    }
                }
            }

            items(
                manageableCloudBooks,
                key = {
                    "cloud_book_${it.id}"
                }
            ) {
                info ->
                val localBook =
                    books.firstOrNull {
                        it.id == info.id
                    }

                Card(
                    Modifier.fillMaxWidth()
                ) {
                    Column(
                        Modifier.padding(
                            12.dp
                        ),
                        verticalArrangement =
                            Arrangement.spacedBy(
                                6.dp
                            )
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment =
                                Alignment.CenterVertically
                        ) {
                            Column(
                                Modifier.weight(1f)
                            ) {
                                Text(
                                    if (
                                        info.ownerUsername
                                            .isNotBlank()
                                    ) {
                                        if (
                                            info.name ==
                                            "我的账本"
                                        ) {
                                            "${info.ownerUsername}的账本"
                                        } else {
                                            "${info.name}（${info.ownerUsername}）"
                                        }
                                    } else {
                                        info.name
                                    },
                                    fontWeight =
                                        FontWeight.Bold
                                )

                                Text(
                                    ledgerManager
                                        .permissionLabel(
                                            info.role
                                        ) +
                                        if (
                                            localBook !=
                                            null
                                        ) {
                                            " · 本机已有"
                                        } else {
                                            " · 云端"
                                        },
                                    style =
                                        MaterialTheme
                                            .typography
                                            .bodySmall,
                                    color =
                                        if (
                                            info.role ==
                                            "VIEWER"
                                        ) {
                                            Color(
                                                0xFFC37B00
                                            )
                                        } else {
                                            BrandGreen
                                        }
                                )

                                Text(
                                    "数据 ${info.recordCount} · 成员 ${info.memberCount}" +
                                        if (info.createdAt.isNotBlank()) " · " + info.createdAt.replace("T", " ").take(10) else "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.Gray
                                )
                            }

                            if (
                                localBook ==
                                null
                            ) {
                                TextButton(
                                    onClick = {
                                        runCloudTask(
                                            busyText =
                                                "正在下载“${info.name}”…",
                                            block = {
                                                cloudSyncManager
                                                    .downloadCloudBook(
                                                        info
                                                    )
                                                    .message
                                            }
                                        )
                                    },
                                    enabled =
                                        !cloudBusy
                                ) {
                                    Text("下载")
                                }
                            } else if (
                                localBook.id !=
                                currentBook.id
                            ) {
                                TextButton(
                                    onClick = {
                                        onSwitchBook(
                                            localBook.id
                                        )
                                    }
                                ) {
                                    Text("切换")
                                }
                            } else {
                                Text(
                                    "当前",
                                    color =
                                        BrandGreen,
                                    fontWeight =
                                        FontWeight.Bold
                                )
                            }
                        }

                        if (info.role == "OWNER" || info.role == "SUPERADMIN") {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (cloudSession?.systemRole == "SUPERADMIN") {
                                    OutlinedButton(
                                        onClick = {
                                            cloudMessage =
                                                "请在“系统管理 → 用户 → 配置账本访问权限”中设置谁能查看或编辑该账本"
                                        },
                                        enabled = !cloudBusy,
                                        modifier = Modifier.weight(1f)
                                    ) { Text("访问权限") }
                                }
                                OutlinedButton(
                                    onClick = { renameCloudBook = info },
                                    enabled = !cloudBusy,
                                    modifier = Modifier.weight(1f)
                                ) { Text("改名") }
                                OutlinedButton(
                                    onClick = { deleteCloudBook = info },
                                    enabled = !cloudBusy,
                                    modifier = Modifier.weight(1f)
                                ) { Text("删除云端") }
                            }
                            TextButton(
                                onClick = { transferCloudBook = info },
                                enabled = !cloudBusy,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("转移账本所有权") }
                        }
                    }
                }
            }
        }

        if (cloudSession != null) {
            item {
                OutlinedButton(
                    onClick = {
                        runCloudTask(
                            busyText = "正在读取云端回收站…",
                            block = {
                                trashBooks = cloudSyncManager.listDeletedCloudBooks()
                                "回收站 ${trashBooks.size} 个账本"
                            },
                            onSuccess = { showTrash = true }
                        )
                    },
                    enabled = !cloudBusy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("🗑 云端账本回收站") }
            }
        }

        item {
            Text(
                "本机账本",
                style =
                    MaterialTheme
                        .typography
                        .titleMedium,
                fontWeight =
                    FontWeight.Bold
            )
        }

        items(
            manageableLocalBooks,
            key = {
                "ledger_${it.id}"
            }
        ) {
            book ->
            val isCurrent =
                book.id ==
                    currentBook.id

            Card(
                Modifier.fillMaxWidth()
            ) {
                Column(
                    Modifier.padding(12.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment =
                            Alignment.CenterVertically
                    ) {
                        Column(
                            Modifier.weight(1f)
                        ) {
                            Text(
                                ledgerManager
                                    .displayName(
                                        book
                                    ),
                                fontWeight =
                                    FontWeight.Bold
                            )

                            Text(
                                (
                                    if (isCurrent) {
                                        "当前账本 · "
                                    } else {
                                        ""
                                    }
                                ) +
                                    ledgerManager
                                        .permissionLabel(
                                            book.permission
                                        ) +
                                    if (
                                        book.cloudEnabled
                                    ) {
                                        " · 云端"
                                    } else {
                                        " · 本机"
                                    },
                                style =
                                    MaterialTheme
                                        .typography
                                        .bodySmall,
                                color =
                                    if (isCurrent) {
                                        BrandGreen
                                    } else {
                                        Color.Gray
                                    }
                            )
                        }

                        if (!isCurrent) {
                            TextButton(
                                onClick = {
                                    onSwitchBook(
                                        book.id
                                    )
                                }
                            ) {
                                Text("切换")
                            }
                        }
                    }

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement =
                            Arrangement.End
                    ) {
                        if (
                            !book.cloudEnabled
                        ) {
                            TextButton(
                                onClick = {
                                    renameBook =
                                        book
                                }
                            ) {
                                Text("改名")
                            }
                        }

                        if (
                            cloudSession != null && !book.cloudEnabled &&
                            book.cloudBookId.isBlank() && book.permission == "OWNER"
                        ) {
                            TextButton(
                                onClick = {
                                    runCloudTask(
                                        busyText = "正在创建独立云端账本…",
                                        block = {
                                            val info = cloudSyncManager.createIndependentCloudBook(book)
                                            cloudBooks = cloudSyncManager.listCloudBooks()
                                            "已创建云端账本：${info.name}"
                                        }
                                    )
                                },
                                enabled = !cloudBusy
                            ) { Text("上传为新云端账本") }
                        }

                        if (
                            !book.isDefault &&
                            !isCurrent
                        ) {
                            TextButton(
                                onClick = {
                                    deleteBook =
                                        book
                                }
                            ) {
                                Text(
                                    if (
                                        book.cloudEnabled
                                    ) {
                                        "删除本机副本"
                                    } else {
                                        "删除"
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            Button(
                onClick = {
                    addDialog = true
                },
                modifier =
                    Modifier.fillMaxWidth()
            ) {
                Text("＋ 新建独立账本")
            }
        }

        item {
            Card(
                colors =
                    CardDefaults.cardColors(
                        containerColor =
                            Color(0xFFF8F8FA)
                    )
            ) {
                Column(
                    Modifier.padding(12.dp)
                ) {
                    Text(
                        "V1.4.4 高频录入优化",
                        fontWeight =
                            FontWeight.Bold
                    )

                    Text(
                        "• 营业页当天记录继续顶置，营业历史移动到保存按钮下方，并显示最近7个实际营业日。",
                        style =
                            MaterialTheme
                                .typography
                                .bodySmall
                    )

                    Text(
                        "• 采购页显示最近7个实际采购日，按日期分组并显示当天采购合计。",
                        style =
                            MaterialTheme
                                .typography
                                .bodySmall
                    )

                    Text(
                        "• 位置管理和商品管理改用 ↑ / ↓ 点击排序，点击后立即保存并参与云同步。",
                        style =
                            MaterialTheme
                                .typography
                                .bodySmall
                    )
                }
            }
        }

        if (
            message.isNotBlank()
        ) {
            item {
                Text(
                    message,
                    color = BrandGreen,
                    style =
                        MaterialTheme
                            .typography
                            .bodySmall
                )
            }
        }
    }

    if (addDialog) {
        LedgerNameDialog(
            title = "新建账本",
            initial = "",
            onDismiss = {
                addDialog = false
            }
        ) {
            name ->
            val book =
                ledgerManager
                    .createBook(name)

            addDialog = false

            if (book != null) {
                message =
                    "已创建“${book.name}”"
                refresh++
                onChanged()
            }
        }
    }

    renameBook?.let {
        book ->
        LedgerNameDialog(
            title = "修改账本名称",
            initial = book.name,
            onDismiss = {
                renameBook = null
            }
        ) {
            name ->
            if (
                ledgerManager.renameBook(
                    book.id,
                    name
                )
            ) {
                if (
                    book.id ==
                    currentBook.id
                ) {
                    db.updateLedgerMetaName(
                        name
                    )
                }

                message =
                    "账本名称已修改"
                refresh++
                onChanged()
            }

            renameBook = null
        }
    }

    deleteBook?.let {
        book ->
        ConfirmDelete(
            if (book.cloudEnabled) {
                "删除本机的“${book.name}”副本？云端账本和其他设备数据不会删除，以后仍可重新下载。"
            } else {
                "删除账本“${book.name}”？这会删除本机该账本数据库。"
            },
            {
                deleteBook = null
            }
        ) {
            if (
                ledgerManager.deleteBook(
                    book.id
                )
            ) {
                message =
                    if (
                        book.cloudEnabled
                    ) {
                        "本机副本已删除"
                    } else {
                        "账本已删除"
                    }
                refresh++
                onChanged()
            }

            deleteBook = null
        }
    }

    renameCloudBook?.let { info ->
        LedgerNameDialog(
            title = "修改云端账本名称",
            initial = info.name,
            onDismiss = { renameCloudBook = null }
        ) { name ->
            runCloudTask(
                busyText = "正在修改云端账本名称…",
                block = {
                    val changed = cloudSyncManager.renameCloudBook(info.id, name)
                    cloudBooks = cloudSyncManager.listCloudBooks()
                    "已改名为“${changed.name}”"
                }
            )
            renameCloudBook = null
        }
    }

    transferCloudBook?.let { info ->
        var targetUsername by remember(info.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { transferCloudBook = null },
            title = { Text("转移账本所有权") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("当前所有者：${info.ownerUsername}")
                    OutlinedTextField(
                        value = targetUsername,
                        onValueChange = { targetUsername = it },
                        label = { Text("新所有者用户名") },
                        singleLine = true
                    )
                    Text("转移后原所有者保留可编辑权限。", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            },
            confirmButton = {
                Button(onClick = {
                    val username = targetUsername.trim()
                    if (username.isBlank()) return@Button
                    transferCloudBook = null
                    runCloudTask(
                        busyText = "正在转移所有权…",
                        block = {
                            cloudSyncManager.transferCloudBookOwner(info.id, username)
                            cloudBooks = cloudSyncManager.listCloudBooks()
                            "账本所有权已转移给 $username"
                        }
                    )
                }) { Text("确认转移") }
            },
            dismissButton = { TextButton(onClick = { transferCloudBook = null }) { Text("取消") } }
        )
    }

    deleteCloudBook?.let { info ->
        ConfirmActionDialog(
            title = "删除云端账本",
            text = "把“${info.name}”移入云端回收站？其他设备下次同步后会失去该账本云端访问权限。本机数据库副本不会自动删除。",
            confirmText = "移入回收站",
            onDismiss = { deleteCloudBook = null }
        ) {
            runCloudTask(
                busyText = "正在删除云端账本…",
                block = {
                    cloudSyncManager.deleteCloudBook(info.id)
                    cloudBooks = cloudSyncManager.listCloudBooks()
                    "云端账本已移入回收站"
                }
            )
            deleteCloudBook = null
        }
    }

    if (showTrash) {
        AlertDialog(
            onDismissRequest = { showTrash = false },
            title = { Text("云端账本回收站") },
            text = {
                Column(
                    Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (trashBooks.isEmpty()) Text("回收站为空", color = Color.Gray)
                    trashBooks.forEach { info ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("${info.name}（${info.ownerUsername}）", fontWeight = FontWeight.Bold)
                                Text(
                                    "数据 ${info.recordCount} · 成员 ${info.memberCount}" +
                                        if (info.deletedAt.isNotBlank()) " · 删除 " + info.deletedAt.replace("T", " ").take(16) else "",
                                    style = MaterialTheme.typography.bodySmall, color = Color.Gray
                                )
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    TextButton(
                                        onClick = {
                                            runCloudTask(
                                                busyText = "正在恢复账本…",
                                                block = {
                                                    cloudSyncManager.restoreCloudBook(info.id)
                                                    trashBooks = cloudSyncManager.listDeletedCloudBooks()
                                                    cloudBooks = cloudSyncManager.listCloudBooks()
                                                    "账本已恢复"
                                                }
                                            )
                                        }, enabled = !cloudBusy
                                    ) { Text("恢复") }
                                    if (cloudSession?.systemRole == "SUPERADMIN") {
                                        TextButton(
                                            onClick = {
                                                runCloudTask(
                                                    busyText = "正在永久清空云端数据…",
                                                    block = {
                                                        cloudSyncManager.purgeCloudBook(info.id)
                                                        trashBooks = cloudSyncManager.listDeletedCloudBooks()
                                                        "云端业务数据已永久清空；审计记录保留"
                                                    }
                                                )
                                            }, enabled = !cloudBusy
                                        ) { Text("永久清空", color = MaterialTheme.colorScheme.error) }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showTrash = false }) { Text("关闭") } }
        )
    }

    if (
        showConflictDialog &&
        conflicts.isNotEmpty()
    ) {
        SyncConflictDialog(
            conflicts = conflicts,
            busy = cloudBusy,
            onDismiss = {
                showConflictDialog =
                    false
            },
            onUseCloud = {
                conflict ->
                runCloudTask(
                    busyText =
                        "正在采用云端版本…",
                    block = {
                        cloudSyncManager
                            .resolveConflictUseCloud(
                                db = db,
                                book =
                                    liveCurrentBook,
                                conflict =
                                    conflict
                            )
                            .message
                    },
                    onSuccess = {
                        if (
                            db.getSyncConflictCount() ==
                            0
                        ) {
                            showConflictDialog =
                                false
                        }
                    }
                )
            },
            onUseLocal = {
                conflict ->
                runCloudTask(
                    busyText =
                        "正在保留本机版本…",
                    block = {
                        cloudSyncManager
                            .resolveConflictUseLocal(
                                db = db,
                                book =
                                    liveCurrentBook,
                                conflict =
                                    conflict
                            )
                            .message
                    },
                    onSuccess = {
                        if (
                            db.getSyncConflictCount() ==
                            0
                        ) {
                            showConflictDialog =
                                false
                        }
                    }
                )
            }
        )
    }

    if (showAuditDialog) {
        CloudAuditDialog(
            rows = auditRows,
            onDismiss = {
                showAuditDialog =
                    false
            }
        )
    }

}

@Composable
private fun SyncConflictDialog(
    conflicts: List<SyncConflictRecord>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onUseCloud: (SyncConflictRecord) -> Unit,
    onUseLocal: (SyncConflictRecord) -> Unit
) {
    if (conflicts.isEmpty()) {
        return
    }

    val conflict =
        conflicts.first()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "同步冲突 · ${syncTableLabel(conflict.tableName)}"
            )
        },
        text = {
            Column(
                Modifier
                    .heightIn(
                        max = 500.dp
                    )
                    .verticalScroll(
                        rememberScrollState()
                    ),
                verticalArrangement =
                    Arrangement.spacedBy(
                        8.dp
                    )
            ) {
                Text(
                    "还有 ${conflicts.size} 条冲突待处理",
                    color =
                        MaterialTheme
                            .colorScheme
                            .error,
                    fontWeight =
                        FontWeight.Bold
                )

                Text(
                    "本机版本 ${conflict.localVersion} · 云端版本 ${conflict.serverVersion}",
                    style =
                        MaterialTheme
                            .typography
                            .bodySmall,
                    color = Color.Gray
                )

                Text(
                    conflictPayloadDiff(
                        conflict
                    ),
                    style =
                        MaterialTheme
                            .typography
                            .bodySmall
                )

                Text(
                    "采用云端：放弃本机这条未同步修改。\n保留本机：以当前本机内容生成一个比云端更新的新版本。",
                    style =
                        MaterialTheme
                            .typography
                            .bodySmall,
                    color = Color.Gray
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onUseLocal(
                        conflict
                    )
                },
                enabled = !busy
            ) {
                Text("保留本机")
            }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = {
                        onUseCloud(
                            conflict
                        )
                    },
                    enabled = !busy
                ) {
                    Text("采用云端")
                }

                TextButton(
                    onClick = onDismiss,
                    enabled = !busy
                ) {
                    Text("稍后处理")
                }
            }
        }
    )
}

@Composable
private fun CloudAuditDialog(
    rows: List<CloudAuditInfo>,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("修改记录")
        },
        text = {
            Column(
                Modifier
                    .heightIn(
                        max = 520.dp
                    )
                    .verticalScroll(
                        rememberScrollState()
                    ),
                verticalArrangement =
                    Arrangement.spacedBy(
                        10.dp
                    )
            ) {
                if (rows.isEmpty()) {
                    Text(
                        "暂无云端修改记录",
                        color = Color.Gray
                    )
                }

                rows.forEach {
                    row ->
                    Card(
                        Modifier.fillMaxWidth()
                    ) {
                        Column(
                            Modifier.padding(
                                10.dp
                            ),
                            verticalArrangement =
                                Arrangement.spacedBy(
                                    3.dp
                                )
                        ) {
                            Text(
                                "${auditActionLabel(row.action)} · ${syncTableLabel(row.tableName)}",
                                fontWeight =
                                    FontWeight.Bold
                            )

                            Text(
                                "${row.displayName.ifBlank { row.username }} · ${row.deviceName.ifBlank { "未知设备" }}",
                                style =
                                    MaterialTheme
                                        .typography
                                        .bodySmall,
                                color = Color.Gray
                            )

                            Text(
                                row.createdAt
                                    .replace(
                                        "T",
                                        " "
                                    )
                                    .take(19),
                                style =
                                    MaterialTheme
                                        .typography
                                        .bodySmall,
                                color = Color.Gray
                            )

                            val summary =
                                auditPayloadDiff(
                                    row.beforePayload,
                                    row.afterPayload
                                )

                            if (
                                summary.isNotBlank()
                            ) {
                                Text(
                                    summary,
                                    style =
                                        MaterialTheme
                                            .typography
                                            .bodySmall
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss
            ) {
                Text("关闭")
            }
        }
    )
}

private fun syncTableLabel(
    tableName: String
): String =
    when (tableName) {
        "fruit" -> "商品"
        "store" -> "位置"
        "partner" -> "合伙人"
        "purchase_plan" ->
            "采购清单"
        "purchase_plan_item" ->
            "采购清单商品"
        "purchase_order" ->
            "进货单"
        "purchase_item" ->
            "进货商品"
        "store_daily_record" ->
            "营业记录"
        "profit_rule" ->
            "利润规则"
        "profit_distribution" ->
            "利润分配"
        "daily_cash_settlement" ->
            "资金轧差"
        "settlement_partner" ->
            "结算人员"
        "settlement_transfer" ->
            "转账方案"
        "profit_settlement_batch" ->
            "利润结算批次"
        "profit_settlement_item" ->
            "利润结算明细"
        "inventory_snapshot" ->
            "库存"
        "fruit_season_catalog" -> "水果资料"
        "fruit_alias" -> "水果别名"
        "fruit_season_region" -> "产区季节"
        "fruit_profile" -> "水果属性"
        else -> tableName
    }

private fun auditActionLabel(
    action: String
): String =
    when (action) {
        "UPSERT" -> "新增/修改"
        "DELETE" -> "删除"
        else -> action
    }

private fun conflictPayloadDiff(
    conflict: SyncConflictRecord
): String {
    val local =
        runCatching {
            JSONObject(
                conflict.localPayload
            )
        }.getOrDefault(
            JSONObject()
        )

    val cloud =
        runCatching {
            JSONObject(
                conflict.serverPayload
            )
        }.getOrDefault(
            JSONObject()
        )

    return payloadDiffText(
        local,
        cloud,
        leftLabel = "本机",
        rightLabel = "云端",
        cloudDeleted =
            conflict.serverDeleted
    )
}

private fun auditPayloadDiff(
    before: JSONObject?,
    after: JSONObject?
): String {
    if (
        before == null &&
        after == null
    ) {
        return ""
    }

    if (before == null) {
        return "新增：" +
            compactPayload(
                after
                    ?: JSONObject()
            )
    }

    if (after == null) {
        return "删除：" +
            compactPayload(
                before
            )
    }

    return payloadDiffText(
        before,
        after,
        leftLabel = "原",
        rightLabel = "新",
        cloudDeleted = false
    )
}

private fun payloadDiffText(
    left: JSONObject,
    right: JSONObject,
    leftLabel: String,
    rightLabel: String,
    cloudDeleted: Boolean
): String {
    if (cloudDeleted) {
        return "$rightLabel：记录已删除\n$leftLabel：" +
            compactPayload(left)
    }

    val ignored =
        setOf(
            "id",
            "sync_id",
            "sync_status",
            "row_version",
            "modified_by",
            "created_at",
            "updated_at"
        )

    val keys =
        linkedSetOf<String>()

    left.keys().forEach {
        if (it !in ignored) {
            keys += it
        }
    }

    right.keys().forEach {
        if (it !in ignored) {
            keys += it
        }
    }

    val lines =
        keys.mapNotNull {
            key ->
            val l =
                jsonDisplayValue(
                    left.opt(key)
                )
            val r =
                jsonDisplayValue(
                    right.opt(key)
                )

            if (l == r) {
                null
            } else {
                "$key：$leftLabel $l → $rightLabel $r"
            }
        }.take(8)

    return if (lines.isEmpty()) {
        "$leftLabel：" +
            compactPayload(left) +
            "\n$rightLabel：" +
            compactPayload(right)
    } else {
        lines.joinToString("\n")
    }
}

private fun compactPayload(
    obj: JSONObject
): String {
    val ignored =
        setOf(
            "id",
            "sync_id",
            "sync_status",
            "row_version",
            "modified_by",
            "created_at",
            "updated_at"
        )

    val parts =
        mutableListOf<String>()

    obj.keys().forEach {
        key ->
        if (
            key !in ignored &&
            parts.size < 6
        ) {
            parts +=
                "$key=" +
                    jsonDisplayValue(
                        obj.opt(key)
                    )
        }
    }

    return if (parts.isEmpty()) {
        "无可显示字段"
    } else {
        parts.joinToString("，")
    }
}

private fun jsonDisplayValue(
    value: Any?
): String =
    when {
        value == null ||
            value ==
            JSONObject.NULL ->
            "空"

        else ->
            value.toString()
                .take(60)
    }

@Composable
private fun AppUpdateDialog(
    info: AppUpdateInfo,
    onDismiss: () -> Unit,
    onOpenRelease: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "发现新版本 V${info.versionName}"
            )
        },
        text = {
            Column(
                Modifier
                    .heightIn(
                        max = 480.dp
                    )
                    .verticalScroll(
                        rememberScrollState()
                    ),
                verticalArrangement =
                    Arrangement.spacedBy(
                        10.dp
                    )
            ) {
                Text(
                    "当前版本：V${BuildConfig.VERSION_NAME}"
                )
                Text(
                    "最新版本：V${info.versionName}",
                    fontWeight =
                        FontWeight.Bold,
                    color = BrandGreen
                )

                if (
                    info.publishedAt
                        .isNotBlank()
                ) {
                    Text(
                        "发布时间：" +
                            info.publishedAt
                                .replace(
                                    "T",
                                    " "
                                )
                                .replace(
                                    "Z",
                                    ""
                                )
                                .take(16),
                        style =
                            MaterialTheme
                                .typography
                                .bodySmall,
                        color = Color.Gray
                    )
                }

                HorizontalDivider()

                Text(
                    "更新内容",
                    fontWeight =
                        FontWeight.Bold
                )

                Text(
                    info.releaseNotes
                        .ifBlank {
                            "GitHub 已发布新版本。"
                        },
                    style =
                        MaterialTheme
                            .typography
                            .bodyMedium
                )

                if (
                    info.apkUrl
                        .isNotBlank()
                ) {
                    Text(
                        "Release 中已检测到 APK 安装包。",
                        style =
                            MaterialTheme
                                .typography
                                .bodySmall,
                        color = Color.Gray
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick =
                    onOpenRelease
            ) {
                Text("前往 GitHub 下载")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss
            ) {
                Text("稍后")
            }
        }
    )
}

private fun openWebPage(
    context: Context,
    url: String
) {
    runCatching {
        context.startActivity(
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse(url)
            ).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                )
            }
        )
    }
}

@Composable
private fun LedgerNameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var name by remember(initial) {
        mutableStateOf(initial)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(title)
        },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                },
                label = {
                    Text("账本名称")
                },
                singleLine = true
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    if (
                        name.trim()
                            .isNotBlank()
                    ) {
                        onSave(
                            name.trim()
                        )
                    }
                }
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss
            ) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun HomeHeaderSettingsContent(
    ledgerUiSettingsManager:
        LedgerUiSettingsManager,
    currentBook: LedgerBook,
    uiSettingsVersion: Int,
    onChanged: () -> Unit
) {
    val scope = rememberCoroutineScope()

    val loaded =
        remember(
            currentBook.id,
            uiSettingsVersion
        ) {
            ledgerUiSettingsManager
                .load(
                    currentBook.id
                )
        }

    var title by remember(
        currentBook.id,
        uiSettingsVersion
    ) {
        mutableStateOf(
            loaded.title
        )
    }

    var subtitle by remember(
        currentBook.id,
        uiSettingsVersion
    ) {
        mutableStateOf(
            loaded.subtitle
        )
    }

    var tagline by remember(
        currentBook.id,
        uiSettingsVersion
    ) {
        mutableStateOf(
            loaded.tagline
        )
    }

    var showFruitIcons by remember(
        currentBook.id,
        uiSettingsVersion
    ) {
        mutableStateOf(
            loaded.showFruitIcons
        )
    }

    var backgroundPath by remember(
        currentBook.id,
        uiSettingsVersion
    ) {
        mutableStateOf(
            loaded.backgroundImagePath
        )
    }

    var message by remember {
        mutableStateOf("")
    }

    val imageLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.PickVisualMedia()
        ) { uri ->
            if (uri != null) {
                message = "正在处理图片…"
                scope.launch {
                    val result =
                        withContext(Dispatchers.IO) {
                            ledgerUiSettingsManager.saveBackgroundImage(
                                currentBook.id,
                                uri
                            )
                        }
                    result
                        .onSuccess { path ->
                            backgroundPath = path
                            message = "背景图片已更换"
                            onChanged()
                        }
                        .onFailure { error ->
                            message =
                                "图片设置失败：" +
                                    (error.message ?: "无法读取该图片")
                        }
                }
            }
        }

    val previewBitmap =
        remember(
            backgroundPath,
            uiSettingsVersion
        ) {
            backgroundPath
                .takeIf {
                    it.isNotBlank()
                }
                ?.let {
                    path ->
                    runCatching {
                        BitmapFactory
                            .decodeFile(
                                path
                            )
                            ?.asImageBitmap()
                    }.getOrNull()
                }
        }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .background(
                Color(
                    0xFFF6F6F6
                )
            ),
        contentPadding =
            PaddingValues(
                16.dp
            ),
        verticalArrangement =
            Arrangement.spacedBy(
                14.dp
            )
    ) {
        item {
            Text(
                "当前账本：${currentBook.name}",
                color = Color.Gray,
                style =
                    MaterialTheme
                        .typography
                        .bodySmall
            )
        }

        item {
            Card(
                Modifier
                    .fillMaxWidth()
                    .height(
                        170.dp
                    )
            ) {
                Box(
                    Modifier.fillMaxSize()
                ) {
                    if (
                        previewBitmap !=
                        null
                    ) {
                        Image(
                            bitmap =
                                previewBitmap,
                            contentDescription =
                                "首页顶部预览",
                            modifier =
                                Modifier
                                    .fillMaxSize(),
                            contentScale =
                                ContentScale.Crop
                        )
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(
                                    Color.Black
                                        .copy(
                                            alpha =
                                                0.28f
                                        )
                                )
                        )
                    } else {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(
                                    Brush
                                        .linearGradient(
                                            listOf(
                                                Color(
                                                    0xFF0A6E3A
                                                ),
                                                Color(
                                                    0xFF148E51
                                                ),
                                                Color(
                                                    0xFF0B5E34
                                                )
                                            )
                                        )
                                )
                        )
                    }

                    Column(
                        Modifier
                            .align(
                                Alignment
                                    .CenterStart
                            )
                            .padding(
                                start = 18.dp
                            )
                    ) {
                        Text(
                            title
                                .ifBlank {
                                    "天鲜果业"
                                },
                            color =
                                Color.White,
                            fontSize =
                                28.sp,
                            fontWeight =
                                FontWeight.Bold,
                            maxLines = 1
                        )

                        if (
                            subtitle
                                .isNotBlank()
                        ) {
                            Spacer(
                                Modifier.height(
                                    5.dp
                                )
                            )
                            Text(
                                subtitle,
                                color =
                                    Color.White
                                        .copy(
                                            alpha =
                                                0.9f
                                        ),
                                fontSize =
                                    13.sp,
                                maxLines = 2
                            )
                        }
                    }

                    Column(
                        Modifier
                            .align(
                                Alignment
                                    .BottomEnd
                            )
                            .padding(
                                12.dp
                            ),
                        horizontalAlignment =
                            Alignment.End
                    ) {
                        if (
                            showFruitIcons
                        ) {
                            Text(
                                "🍇🍊🍓",
                                fontSize =
                                    22.sp
                            )
                        }

                        if (
                            tagline
                                .isNotBlank()
                        ) {
                            Text(
                                tagline,
                                color =
                                    Color.White,
                                fontSize =
                                    11.sp,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }

        item {
            Card(
                Modifier.fillMaxWidth()
            ) {
                Column(
                    Modifier.padding(
                        14.dp
                    ),
                    verticalArrangement =
                        Arrangement.spacedBy(
                            12.dp
                        )
                ) {
                    Text(
                        "文字",
                        fontWeight =
                            FontWeight.Bold
                    )

                    OutlinedTextField(
                        value = title,
                        onValueChange = {
                            value ->
                            title =
                                value.take(
                                    20
                                )
                        },
                        modifier =
                            Modifier
                                .fillMaxWidth(),
                        label = {
                            Text("主标题")
                        },
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = subtitle,
                        onValueChange = {
                            value ->
                            subtitle =
                                value.take(
                                    50
                                )
                        },
                        modifier =
                            Modifier
                                .fillMaxWidth(),
                        label = {
                            Text("副标题")
                        },
                        minLines = 1,
                        maxLines = 2
                    )

                    OutlinedTextField(
                        value = tagline,
                        onValueChange = {
                            value ->
                            tagline =
                                value.take(
                                    35
                                )
                        },
                        modifier =
                            Modifier
                                .fillMaxWidth(),
                        label = {
                            Text(
                                "右下角文案"
                            )
                        },
                        singleLine = true
                    )

                    Row(
                        Modifier
                            .fillMaxWidth(),
                        verticalAlignment =
                            Alignment
                                .CenterVertically
                    ) {
                        Column(
                            Modifier.weight(
                                1f
                            )
                        ) {
                            Text(
                                "水果装饰"
                            )
                            Text(
                                "显示 🍇🍊🍓",
                                style =
                                    MaterialTheme
                                        .typography
                                        .bodySmall,
                                color =
                                    Color.Gray
                            )
                        }

                        Switch(
                            checked =
                                showFruitIcons,
                            onCheckedChange = {
                                showFruitIcons =
                                    it
                            }
                        )
                    }
                }
            }
        }

        item {
            Card(
                Modifier.fillMaxWidth()
            ) {
                Column(
                    Modifier.padding(
                        14.dp
                    ),
                    verticalArrangement =
                        Arrangement.spacedBy(
                            10.dp
                        )
                ) {
                    Text(
                        "顶部背景",
                        fontWeight =
                            FontWeight.Bold
                    )

                    Text(
                        if (
                            backgroundPath
                                .isBlank()
                        ) {
                            "当前使用默认绿色渐变背景"
                        } else {
                            "当前使用自定义图片"
                        },
                        style =
                            MaterialTheme
                                .typography
                                .bodySmall,
                        color = Color.Gray
                    )

                    Row(
                        Modifier
                            .fillMaxWidth(),
                        horizontalArrangement =
                            Arrangement
                                .spacedBy(
                                    8.dp
                                )
                    ) {
                        Button(
                            onClick = {
                                runCatching {
                                    imageLauncher.launch(
                                        PickVisualMediaRequest(
                                            ActivityResultContracts.PickVisualMedia.ImageOnly
                                        )
                                    )
                                }.onFailure { error ->
                                    message =
                                        "无法打开图片选择器：" +
                                            (error.message ?: "系统图片选择器不可用")
                                }
                            },
                            modifier =
                                Modifier.weight(
                                    1f
                                )
                        ) {
                            Text(
                                if (
                                    backgroundPath
                                        .isBlank()
                                ) {
                                    "选择图片"
                                } else {
                                    "更换图片"
                                }
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                ledgerUiSettingsManager
                                    .removeBackgroundImage(
                                        currentBook.id
                                    )
                                backgroundPath =
                                    ""
                                message =
                                    "已恢复默认背景"
                                onChanged()
                            },
                            enabled =
                                backgroundPath
                                    .isNotBlank(),
                            modifier =
                                Modifier.weight(
                                    1f
                                )
                        ) {
                            Text("恢复背景")
                        }
                    }

                    Text(
                        "使用 Android 系统图片选择器，不需要开放整个相册权限。图片会压缩后保存到 APP 私有目录。",
                        style =
                            MaterialTheme
                                .typography
                                .bodySmall,
                        color = Color.Gray
                    )
                }
            }
        }

        item {
            Button(
                onClick = {
                    ledgerUiSettingsManager
                        .save(
                            currentBook.id,
                            LedgerHeaderSettings(
                                title =
                                    title
                                        .trim()
                                        .ifBlank {
                                            "天鲜果业"
                                        },
                                subtitle =
                                    subtitle
                                        .trim(),
                                tagline =
                                    tagline
                                        .trim(),
                                showFruitIcons =
                                    showFruitIcons,
                                backgroundImagePath =
                                    backgroundPath
                            )
                        )

                    message =
                        "首页顶部设置已保存"
                    onChanged()
                },
                modifier =
                    Modifier.fillMaxWidth()
            ) {
                Text("保存设置")
            }
        }

        item {
            OutlinedButton(
                onClick = {
                    ledgerUiSettingsManager
                        .reset(
                            currentBook.id
                        )

                    val defaults =
                        ledgerUiSettingsManager
                            .load(
                                currentBook.id
                            )

                    title =
                        defaults.title
                    subtitle =
                        defaults.subtitle
                    tagline =
                        defaults.tagline
                    showFruitIcons =
                        defaults
                            .showFruitIcons
                    backgroundPath =
                        ""

                    message =
                        "已恢复默认首页顶部"
                    onChanged()
                },
                modifier =
                    Modifier.fillMaxWidth()
            ) {
                Text("恢复全部默认")
            }
        }

        if (
            message.isNotBlank()
        ) {
            item {
                Text(
                    message,
                    color =
                        if (
                            message.contains(
                                "失败"
                            )
                        ) {
                            MaterialTheme
                                .colorScheme
                                .error
                        } else {
                            BrandGreen
                        },
                    style =
                        MaterialTheme
                            .typography
                            .bodySmall
                )
            }
        }

        item {
            Text(
                "说明：这些界面设置按账本区分并保存在当前设备，不参与经营数据云同步。换手机后业务数据可以同步，但背景图片和首页文案需要在新设备重新设置。",
                style =
                    MaterialTheme
                        .typography
                        .bodySmall,
                color = Color.Gray
            )
        }
    }
}

@Composable
private fun AboutAppContent(
    updateChecking: Boolean,
    updateCheckMessage: String,
    onCheckUpdate: () -> Unit
) {
    val context =
        LocalContext.current

    LazyColumn(
        Modifier
            .fillMaxSize()
            .background(
                Color(
                    0xFFF6F6F6
                )
            ),
        contentPadding =
            PaddingValues(
                bottom = 24.dp
            )
    ) {
        item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme
                            .colorScheme
                            .surface
                    )
                    .padding(
                        24.dp
                    ),
                horizontalAlignment =
                    Alignment.CenterHorizontally
            ) {
                Text(
                    "🍇",
                    fontSize = 48.sp
                )
                Spacer(
                    Modifier.height(
                        8.dp
                    )
                )
                Text(
                    "天鲜账本",
                    style =
                        MaterialTheme
                            .typography
                            .headlineSmall,
                    fontWeight =
                        FontWeight.Bold
                )
                Text(
                    "V${BuildConfig.VERSION_NAME} · build ${BuildConfig.VERSION_CODE}",
                    color = Color.Gray,
                    style =
                        MaterialTheme
                            .typography
                            .bodyMedium
                )
            }
        }

        item {
            SettingsSection(
                title = "版本"
            ) {
                SettingsRow(
                    icon = "⬆",
                    title =
                        if (
                            updateChecking
                        ) {
                            "正在检查更新…"
                        } else {
                            "检查更新"
                        },
                    subtitle =
                        updateCheckMessage
                            .ifBlank {
                                "自动每 12 小时检查一次 GitHub Release"
                            },
                    onClick =
                        onCheckUpdate
                )

                SettingsDivider()

                SettingsRow(
                    icon = "📝",
                    title = "本版更新",
                    subtitle =
                        "新增经营分析与预估经营利润，修复快捷操作设置滚动",
                    onClick = {
                    }
                )
            }
        }

        item {
            SettingsSection(
                title = "项目"
            ) {
                SettingsRow(
                    icon = "🌐",
                    title = "GitHub 项目",
                    subtitle =
                        "sockc/TianXianFruit",
                    onClick = {
                        openWebPage(
                            context,
                            "https://github.com/sockc/TianXianFruit"
                        )
                    }
                )

                SettingsDivider()

                SettingsRow(
                    icon = "📦",
                    title = "GitHub Releases",
                    subtitle =
                        "查看和下载已发布版本",
                    onClick = {
                        openWebPage(
                            context,
                            AppUpdateManager
                                .RELEASES_URL
                        )
                    }
                )
            }
        }

        item {
            Column(
                Modifier.padding(
                    horizontal = 20.dp,
                    vertical = 16.dp
                )
            ) {
                Text(
                    "天鲜账本用于水果经营中的采购、营业、利润、结算和多账本云同步。",
                    style =
                        MaterialTheme
                            .typography
                            .bodySmall,
                    color = Color.Gray
                )

                Spacer(
                    Modifier.height(
                        6.dp
                    )
                )

                Text(
                    "当前云同步服务需要 TianXian Sync Server V1.0.11-Lucky。",
                    style =
                        MaterialTheme
                            .typography
                            .bodySmall,
                    color = Color.Gray
                )
            }
        }
    }
}

@Composable
internal fun SettingsSection(
    title: String,
    content:
        @Composable
        ColumnScope.() -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(
                top = 7.dp
            )
    ) {
        Text(
            title,
            modifier =
                Modifier.padding(
                    horizontal = 17.dp,
                    vertical = 3.dp
                ),
            color =
                Color(0xFF8A8A8A),
            style =
                MaterialTheme
                    .typography
                    .labelMedium
        )

        Card(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = 9.dp
                    ),
            shape =
                RoundedCornerShape(11.dp),
            colors =
                CardDefaults.cardColors(
                    containerColor =
                        MaterialTheme
                            .colorScheme
                            .surface
                )
        ) {
            Column(
                content = content
            )
        }
    }
}

@Composable
internal fun SettingsRow(
    icon: String,
    title: String,
    subtitle: String? = null,
    trailing: String? = null,
    titleColor: Color =
        Color.Unspecified,
    showArrow: Boolean = true,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = Color.Transparent
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = 13.dp,
                    vertical = 8.dp
                ),
            verticalAlignment =
                Alignment.CenterVertically
        ) {
            Text(
                icon,
                fontSize = 18.sp,
                modifier =
                    Modifier.width(29.dp)
            )

            Text(
                title,
                modifier =
                    Modifier.weight(1f),
                style =
                    MaterialTheme
                        .typography
                        .bodyMedium,
                fontWeight =
                    FontWeight.Medium,
                color = titleColor
            )

            if (
                !trailing.isNullOrBlank()
            ) {
                Text(
                    trailing,
                    style =
                        MaterialTheme
                            .typography
                            .bodySmall,
                    color =
                        Color(0xFF8A8A8A)
                )
                Spacer(
                    Modifier.width(5.dp)
                )
            }

            if (showArrow) {
                Text(
                    "›",
                    color =
                        Color(0xFF9B9B9B),
                    fontSize = 21.sp
                )
            }
        }
    }
}

@Composable
internal fun SettingsDivider() {
    HorizontalDivider(
        modifier =
            Modifier.padding(
                start = 42.dp
            ),
        color =
            Color(0xFFE9E9E9)
    )
}

@Composable
private fun HomeQuickActionsSettingsContent(
    manager: LedgerUiSettingsManager,
    currentBook: LedgerBook,
    systemRole: String,
    uiSettingsVersion: Int,
    onChanged: () -> Unit
) {
    val available = remember(currentBook.id, currentBook.permission, currentBook.cloudEnabled, systemRole) {
        HomeQuickAction.entries.filter {
            homeQuickActionAllowed(it, currentBook, systemRole)
        }
    }
    val selected = remember(currentBook.id, uiSettingsVersion, available) {
        val allowedKeys = available.map { it.name }.toSet()
        mutableStateListOf<String>().apply {
            addAll(
                manager.loadHomeQuickActions(currentBook.id)
                    .filter { it in allowedKeys }
            )
        }
    }

    fun persist() {
        manager.saveHomeQuickActions(currentBook.id, selected.toList())
        onChanged()
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "首页最多显示 8 个快捷操作，按下方顺序每行 4 个。",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray
        )

        val selectedActions = selected.mapNotNull { key ->
            available.firstOrNull { it.name == key }
        }

        if (selectedActions.isNotEmpty()) {
            Text("已显示", fontWeight = FontWeight.Bold)
            selectedActions.forEachIndexed { index, action ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 9.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(action.icon, fontSize = 19.sp)
                        Spacer(Modifier.width(7.dp))
                        Text(action.label, modifier = Modifier.weight(1f))
                        IconButton(
                            onClick = {
                                if (index > 0) {
                                    val key = selected.removeAt(index)
                                    selected.add(index - 1, key)
                                    persist()
                                }
                            },
                            enabled = index > 0,
                            modifier = Modifier.size(34.dp)
                        ) { Text("↑") }
                        IconButton(
                            onClick = {
                                if (index < selectedActions.lastIndex) {
                                    val actualIndex = selected.indexOf(action.name)
                                    if (actualIndex >= 0 && actualIndex < selected.lastIndex) {
                                        val key = selected.removeAt(actualIndex)
                                        selected.add(actualIndex + 1, key)
                                        persist()
                                    }
                                }
                            },
                            enabled = index < selectedActions.lastIndex,
                            modifier = Modifier.size(34.dp)
                        ) { Text("↓") }
                        TextButton(
                            onClick = {
                                selected.remove(action.name)
                                persist()
                            },
                            contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp)
                        ) {
                            Text("隐藏", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        val hidden = available.filter { it.name !in selected }
        if (hidden.isNotEmpty()) {
            Text("可添加", fontWeight = FontWeight.Bold)
            hidden.forEach { action ->
                Surface(
                    onClick = {
                        if (selected.size < 8) {
                            selected.add(action.name)
                            persist()
                        }
                    },
                    shape = RoundedCornerShape(9.dp),
                    color = Color(0xFFF8FAF9)
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(action.icon, fontSize = 18.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(action.label, modifier = Modifier.weight(1f))
                        Text(
                            if (selected.size < 8) "+ 添加" else "最多8个",
                            color = if (selected.size < 8) BrandGreen else Color.Gray,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuCard(
    title: String,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Text(
                "›",
                color = BrandGreen,
                fontSize = 24.sp
            )
        }
    }
}

@Composable
private fun SubPage(
    title: String,
    back: () -> Unit,
    content: @Composable () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = 8.dp,
                    vertical = 4.dp
                ),
            verticalAlignment =
                Alignment.CenterVertically
        ) {
            TextButton(
                onClick = back
            ) {
                Text("← 返回")
            }
            Text(
                title,
                modifier =
                    Modifier.weight(1f),
                fontWeight =
                    FontWeight.Bold
            )
            PageSyncStatus(
                pageTitle = title
            )
        }
        Box(Modifier.weight(1f)) {
            content()
        }
    }
}

@Composable
private fun HistoryContent(
    db: AppDatabase,
    dataVersion: Int,
    canEdit: Boolean,
    protectHistoricalAction:
        (String, String, () -> Unit) -> Unit,
    initialSection: HistorySection = HistorySection.BUSINESS,
    onChanged: () -> Unit
) {
    var section by remember(initialSection) {
        mutableStateOf(initialSection)
    }
    var selectedProductId by remember { mutableStateOf<Long?>(null) }
    var productSearch by remember { mutableStateOf("") }
    var timeFilter by remember {
        mutableStateOf(HistoryTimeFilter.LAST_30)
    }
    var filterMenu by remember {
        mutableStateOf(false)
    }

    val today = LocalDate.now()

    var customStart by remember {
        mutableStateOf(today.withDayOfMonth(1).toString())
    }
    var customEnd by remember {
        mutableStateOf(today.toString())
    }

    val range = remember(timeFilter, customStart, customEnd) {
        when (timeFilter) {
            HistoryTimeFilter.ALL -> null to null
            HistoryTimeFilter.TODAY -> today.toString() to today.toString()
            HistoryTimeFilter.YESTERDAY -> {
                val d = today.minusDays(1)
                d.toString() to d.toString()
            }
            HistoryTimeFilter.LAST_7 ->
                today.minusDays(6).toString() to today.toString()
            HistoryTimeFilter.LAST_30 ->
                today.minusDays(29).toString() to today.toString()
            HistoryTimeFilter.LAST_90 ->
                today.minusDays(89).toString() to today.toString()
            HistoryTimeFilter.THIS_MONTH ->
                today.withDayOfMonth(1).toString() to today.toString()
            HistoryTimeFilter.LAST_MONTH -> {
                val firstThisMonth = today.withDayOfMonth(1)
                val firstLastMonth = firstThisMonth.minusMonths(1)
                firstLastMonth.toString() to firstThisMonth.minusDays(1).toString()
            }
            HistoryTimeFilter.CUSTOM -> customStart to customEnd
        }
    }

    val invalidCustomRange =
        timeFilter == HistoryTimeFilter.CUSTOM && customStart > customEnd
    val queryStart =
        if (invalidCustomRange) "9999-12-31" else range.first
    val queryEnd =
        if (invalidCustomRange) "0000-01-01" else range.second

    BackHandler(enabled = section == HistorySection.PRODUCT && selectedProductId != null) {
        selectedProductId = null
    }

    val purchases = remember(dataVersion, queryStart, queryEnd) {
        db.getPurchaseOrdersBetween(queryStart, queryEnd)
    }
    val sessions = remember(dataVersion, queryStart, queryEnd) {
        db.getDailyRecordsBetween(queryStart, queryEnd)
    }
    val purchasePlans = remember(dataVersion, queryStart, queryEnd) {
        db.getPurchasePlansBetween(queryStart, queryEnd)
    }
    val productHistorySummaries = remember(dataVersion, queryStart, queryEnd) {
        db.getProductHistorySummaries(queryStart, queryEnd)
    }
    val filteredProductHistory = remember(productHistorySummaries, productSearch) {
        val keyword = productSearch.trim()
        if (keyword.isBlank()) productHistorySummaries
        else productHistorySummaries.filter { it.fruitName.contains(keyword, ignoreCase = true) }
    }
    val selectedProductHistory = remember(dataVersion, queryStart, queryEnd, selectedProductId) {
        selectedProductId?.let { db.getProductHistoryDetail(it, queryStart, queryEnd) }
    }
    val profitHistoryDates = remember(sessions, purchases) {
        (
            sessions.map { it.date } +
                purchases.map { it.order.date }
            )
            .distinct()
            .sortedDescending()
    }
    val profitHistoryByDate = remember(dataVersion, profitHistoryDates) {
        profitHistoryDates.map { historyDate ->
            Triple(
                historyDate,
                db.getDailySummary(historyDate),
                db.getEffectiveProfitShares(historyDate)
            )
        }
    }
    val profitPeriodTotal = remember(profitHistoryByDate) {
        profitHistoryByDate.sumOf { it.second.profit }
    }
    val profitPartnerTotals = remember(profitHistoryByDate) {
        profitHistoryByDate
            .flatMap { it.third }
            .groupBy { it.partnerId to it.partnerName }
            .map { (key, rows) ->
                PartnerMoneySummary(
                    partnerId = key.first,
                    partnerName = key.second,
                    amount = rows.sumOf { it.amount }
                )
            }
            .sortedByDescending { it.amount }
    }
    val businessHistoryByDate = remember(sessions) {
        sessions
            .groupBy { it.date }
            .toList()
            .sortedByDescending { it.first }
    }
    val purchaseHistoryByDate = remember(purchases) {
        purchases
            .groupBy { it.order.date }
            .toList()
            .sortedByDescending { it.first }
    }

    var deleteOrder by remember {
        mutableStateOf<PurchaseOrderDetail?>(null)
    }
    var deleteSession by remember {
        mutableStateOf<StoreDailyRecord?>(null)
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                HistorySection.entries.forEach { option ->
                    FilterChip(
                        selected = section == option,
                        onClick = {
                            section = option
                            if (option != HistorySection.PRODUCT) selectedProductId = null
                        },
                        label = { Text(option.label) }
                    )
                }
            }
        }

        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                Box(Modifier.weight(1f)) {
                    CompactSelectButton(
                        "查看范围",
                        timeFilter.label,
                        Modifier.fillMaxWidth()
                    ) {
                        filterMenu = true
                    }

                    DropdownMenu(
                        expanded = filterMenu,
                        onDismissRequest = { filterMenu = false }
                    ) {
                        HistoryTimeFilter.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                onClick = {
                                    timeFilter = option
                                    filterMenu = false
                                }
                            )
                        }
                    }
                }
            }
        }

        if (timeFilter == HistoryTimeFilter.CUSTOM) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CompactDateSelector(
                        "开始日期",
                        customStart,
                        Modifier.weight(1f)
                    ) { customStart = it }
                    CompactDateSelector(
                        "结束日期",
                        customEnd,
                        Modifier.weight(1f)
                    ) { customEnd = it }
                }

                if (invalidCustomRange) {
                    Text(
                        "开始日期不能晚于结束日期",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

        if (!invalidCustomRange && range.first != null && range.second != null) {
            item {
                Text(
                    "${range.first} ～ ${range.second}",
                    color = Color.Gray,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        item {
            val countText =
                when (section) {
                    HistorySection.BUSINESS ->
                        "${businessHistoryByDate.size} 个营业日 · ${sessions.size} 条记录"
                    HistorySection.PURCHASE ->
                        "${purchaseHistoryByDate.size} 个采购日 · ${purchases.size} 张采购单"
                    HistorySection.PRODUCT -> "${productHistorySummaries.size} 种商品"
                    HistorySection.PROFIT -> "${profitHistoryByDate.size} 天利润记录"
                    HistorySection.PLAN -> "${purchasePlans.size} 张采购计划"
                }
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFF5F8F6)
                )
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 11.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        section.label,
                        modifier = Modifier.weight(1f),
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        countText,
                        style = MaterialTheme.typography.bodySmall,
                        color = BrandGreen
                    )
                }
            }
        }

        if (section == HistorySection.PROFIT && profitHistoryByDate.isNotEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFF7FBF8)
                    )
                ) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            "时间范围利润汇总",
                            fontWeight = FontWeight.Bold
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "总利润",
                                modifier = Modifier.weight(1f),
                                color = Color.Gray
                            )
                            Text(
                                money(profitPeriodTotal),
                                fontWeight = FontWeight.Bold,
                                color =
                                    if (profitPeriodTotal >= 0.0) BrandGreen
                                    else MaterialTheme.colorScheme.error
                            )
                        }
                        HorizontalDivider()
                        profitPartnerTotals.forEach { row ->
                            Row(Modifier.fillMaxWidth()) {
                                Text(
                                    row.partnerName,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    money(row.amount),
                                    fontWeight = FontWeight.SemiBold,
                                    color =
                                        if (row.amount >= 0.0) BrandGreen
                                        else MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }

        when (section) {
            HistorySection.BUSINESS -> {
                if (businessHistoryByDate.isEmpty()) {
                    item {
                        Text("当前时间范围暂无营业记录", color = Color.Gray)
                    }
                }

                businessHistoryByDate.forEach { (historyDate, daySessions) ->
                    item(key = "business-history-$historyDate") {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text(
                                    "$historyDate ${runCatching { chineseWeekday(LocalDate.parse(historyDate)) }.getOrDefault("")}",
                                    fontWeight = FontWeight.Bold
                                )
                                val daySummary = db.getDailySummary(historyDate)
                                Text(
                                    "${daySessions.size} 个位置/记录 · 营业 ${money(daySummary.revenue)} · " +
                                        "利润 ${money(daySummary.profit)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.Gray
                                )
                                HorizontalDivider(Modifier.padding(vertical = 7.dp))

                                daySessions.forEachIndexed { index, s ->
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(s.storeName, fontWeight = FontWeight.SemiBold)
                                            Text(
                                                "营业 ${money(s.revenue)} · 利润 ${money(s.profit)} · 客户 ${s.customerTotal}",
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                        if (canEdit) {
                                            TextButton(
                                                onClick = {
                                                    protectHistoricalAction(
                                                        s.date,
                                                        "删除 ${s.date} · ${s.storeName} 营业记录"
                                                    ) {
                                                        deleteSession = s
                                                    }
                                                }
                                            ) {
                                                Text("删除")
                                            }
                                        }
                                    }
                                    if (index < daySessions.lastIndex) {
                                        HorizontalDivider(Modifier.padding(vertical = 5.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            HistorySection.PURCHASE -> {
                if (purchaseHistoryByDate.isEmpty()) {
                    item {
                        Text("当前时间范围暂无采购记录", color = Color.Gray)
                    }
                }

                purchaseHistoryByDate.forEach { (historyDate, dayPurchases) ->
                    item(key = "purchase-history-$historyDate") {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text(
                                    "$historyDate ${runCatching { chineseWeekday(LocalDate.parse(historyDate)) }.getOrDefault("")}",
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "${dayPurchases.size} 张采购单 · 合计 ${money(dayPurchases.sumOf { it.order.totalCost })}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.Gray
                                )
                                HorizontalDivider(Modifier.padding(vertical = 7.dp))

                                val buyerGroups =
                                    dayPurchases
                                        .groupBy { detail ->
                                            if (detail.order.buyerId > 0L) {
                                                "id:${detail.order.buyerId}"
                                            } else {
                                                "name:${detail.order.buyerName}"
                                            }
                                        }
                                        .values
                                        .toList()

                                buyerGroups.forEachIndexed { groupIndex, buyerOrders ->
                                    val buyerName =
                                        buyerOrders.firstOrNull()?.order?.buyerName
                                            ?.ifBlank { "未指定采购人" }
                                            ?: "未指定采购人"
                                    val buyerTotal =
                                        buyerOrders.sumOf { it.order.totalCost }
                                    val buyerItems =
                                        buyerOrders.flatMap { it.items }

                                    Row(
                                        Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                buyerName,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Text(
                                                "${buyerItems.size} 项商品 · 采购 ${money(buyerTotal)}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = BrandGreen
                                            )
                                        }

                                        if (canEdit && buyerOrders.size == 1) {
                                            val detail = buyerOrders.first()
                                            TextButton(
                                                onClick = {
                                                    protectHistoricalAction(
                                                        detail.order.date,
                                                        "删除 ${detail.order.date} · $buyerName 采购单"
                                                    ) {
                                                        deleteOrder = detail
                                                    }
                                                }
                                            ) {
                                                Text("删除")
                                            }
                                        }
                                    }

                                    buyerItems.forEach { item ->
                                        Text(
                                            "• ${item.fruitName} ${fmt(item.quantity)}${item.unit}" +
                                                (item.unitWeightJin.takeIf { it > 0.000001 }?.let { " · ${fmt(it)}斤/${item.unit}" } ?: "") +
                                                " · ${money(item.totalCost)}",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }

                                    if (canEdit && buyerOrders.size > 1) {
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            buyerOrders.forEachIndexed { orderIndex, detail ->
                                                TextButton(
                                                    onClick = {
                                                        protectHistoricalAction(
                                                            detail.order.date,
                                                            "删除 ${detail.order.date} · $buyerName 采购单"
                                                        ) {
                                                            deleteOrder = detail
                                                        }
                                                    }
                                                ) {
                                                    Text("删除单${orderIndex + 1}")
                                                }
                                            }
                                        }
                                    }

                                    if (groupIndex < buyerGroups.lastIndex) {
                                        HorizontalDivider(Modifier.padding(vertical = 7.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            HistorySection.PRODUCT -> {
                if (selectedProductId == null) {
                    item {
                        OutlinedTextField(
                            value = productSearch,
                            onValueChange = { productSearch = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("搜索商品") },
                            placeholder = { Text("例如：无子红提") }
                        )
                    }

                    if (filteredProductHistory.isEmpty()) {
                        item {
                            Text(
                                if (productSearch.isBlank()) "当前时间范围暂无商品历史" else "没有匹配的商品",
                                color = Color.Gray
                            )
                        }
                    } else {
                        items(
                            filteredProductHistory,
                            key = { "product-history-${it.fruitId}" }
                        ) { summary ->
                            val quantityText =
                                summary.unitSummaries
                                    .filter { it.quantity > 0.000001 }
                                    .joinToString(" · ") { "${fmt(it.quantity)}${it.unit}" }
                                    .ifBlank { "无采购数量" }
                            val latestPurchaseText =
                                if (summary.latestPurchaseUnitPrice != null && summary.latestPurchaseUnit.isNotBlank()) {
                                    "${money(summary.latestPurchaseUnitPrice)}/${summary.latestPurchaseUnit}"
                                } else {
                                    "暂无采购价"
                                }
                            val latestRetailText =
                                summary.latestRetailPricePerJin?.let { "${money(it)}/斤" }
                                    ?: "暂无零售价"

                            Card(
                                onClick = { selectedProductId = summary.fruitId },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = Color.White)
                            ) {
                                Column(
                                    Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            summary.fruitName,
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.titleMedium,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text("查看详情 ›", color = BrandGreen, style = MaterialTheme.typography.bodySmall)
                                    }
                                    Text(
                                        "采购 ${summary.purchaseCount}次 · ${summary.purchaseDayCount}个采购日 · 合计 ${money(summary.totalPurchaseAmount)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.DarkGray
                                    )
                                    Text(
                                        "本期数量 $quantityText" +
                                            if (summary.totalKnownWeightJin > 0.000001) " · 按已录规格约 ${fmt(summary.totalKnownWeightJin)}斤" else "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.DarkGray
                                    )
                                    Text(
                                        "最近采购 $latestPurchaseText · 最近零售 $latestRetailText",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = BrandGreen
                                    )
                                }
                            }
                        }
                    }
                } else {
                    item {
                        TextButton(onClick = { selectedProductId = null }) {
                            Text("← 返回商品列表")
                        }
                    }

                    val detail = selectedProductHistory
                    if (detail == null) {
                        item {
                            Text("当前时间范围没有该商品记录", color = Color.Gray)
                        }
                    } else {
                        val summary = detail.summary
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFF7FBF8)),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Column(
                                    Modifier.padding(13.dp),
                                    verticalArrangement = Arrangement.spacedBy(7.dp)
                                ) {
                                    Text(
                                        summary.fruitName,
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.titleLarge
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        MiniSummaryCard(
                                            "采购次数",
                                            "${summary.purchaseCount}次",
                                            Modifier.weight(1f),
                                            SoftOrange
                                        )
                                        MiniSummaryCard(
                                            "采购金额",
                                            money(summary.totalPurchaseAmount),
                                            Modifier.weight(1f),
                                            SoftBlue
                                        )
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        MiniSummaryCard(
                                            "规格折合",
                                            if (summary.totalKnownWeightJin > 0.000001) "约${fmt(summary.totalKnownWeightJin)}斤" else "暂无",
                                            Modifier.weight(1f),
                                            SoftGreen
                                        )
                                        MiniSummaryCard(
                                            "零售价记录",
                                            "${summary.retailDayCount}天",
                                            Modifier.weight(1f),
                                            SoftPurple
                                        )
                                    }
                                }
                            }
                        }

                        item {
                            Text("采购汇总", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        }

                        item {
                            Card(
                                Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = Color.White)
                            ) {
                                Column(
                                    Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (summary.unitSummaries.isEmpty()) {
                                        Text("本期没有采购记录", color = Color.Gray)
                                    } else {
                                        summary.unitSummaries.forEachIndexed { index, unit ->
                                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                                Row(Modifier.fillMaxWidth()) {
                                                    Text(
                                                        "${fmt(unit.quantity)}${unit.unit} · ${unit.purchaseCount}次",
                                                        modifier = Modifier.weight(1f),
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                    Text(money(unit.totalCost), fontWeight = FontWeight.SemiBold)
                                                }
                                                Text(
                                                    "加权采购价 ${unit.weightedAverageUnitPrice?.let { money(it) + "/" + unit.unit } ?: "—"} · " +
                                                        "最低 ${unit.minUnitPrice?.let { money(it) } ?: "—"} · 最高 ${unit.maxUnitPrice?.let { money(it) } ?: "—"}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = Color.DarkGray
                                                )
                                                Text(
                                                    "最近 ${unit.latestUnitPrice?.let { money(it) + "/" + unit.unit } ?: "—"}" +
                                                        (unit.latestUnitWeightJin?.let { " · 最近规格 ${fmt(it)}斤/${unit.unit}" } ?: "") +
                                                        if (unit.estimatedWeightJin > 0.000001) " · 本期按已录规格约 ${fmt(unit.estimatedWeightJin)}斤" else "",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = Color(0xFF7A5A00)
                                                )
                                            }
                                            if (index < summary.unitSummaries.lastIndex) HorizontalDivider()
                                        }
                                    }
                                }
                            }
                        }

                        item {
                            Text("零售价历史", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        }

                        item {
                            Card(
                                Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = Color.White)
                            ) {
                                Column(
                                    Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(Modifier.fillMaxWidth()) {
                                        Text(
                                            "最近 ${summary.latestRetailPricePerJin?.let { money(it) + "/斤" } ?: "—"}",
                                            modifier = Modifier.weight(1f),
                                            fontWeight = FontWeight.SemiBold,
                                            color = BrandGreen
                                        )
                                        Text("记录 ${summary.retailDayCount}天", color = Color.Gray)
                                    }
                                    Text(
                                        "最低 ${summary.minRetailPricePerJin?.let { money(it) + "/斤" } ?: "—"} · " +
                                            "最高 ${summary.maxRetailPricePerJin?.let { money(it) + "/斤" } ?: "—"}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.DarkGray
                                    )
                                    if (detail.retailPrices.isEmpty()) {
                                        Text("本期没有零售价记录", color = Color.Gray)
                                    } else {
                                        HorizontalDivider()
                                        detail.retailPrices.take(60).forEach { row ->
                                            Row(Modifier.fillMaxWidth()) {
                                                Text(row.date, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                                Text("${money(row.pricePerJin)}/斤", style = MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                        if (detail.retailPrices.size > 60) {
                                            Text("仅显示最近60条，共 ${detail.retailPrices.size} 条", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                        }

                        item {
                            Text("采购明细", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        }

                        if (detail.purchases.isEmpty()) {
                            item { Text("本期没有采购明细", color = Color.Gray) }
                        } else {
                            items(
                                detail.purchases.take(100),
                                key = { "product-purchase-${it.itemId}" }
                            ) { row ->
                                Card(
                                    Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFAFBFC))
                                ) {
                                    Column(
                                        Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
                                        verticalArrangement = Arrangement.spacedBy(3.dp)
                                    ) {
                                        Row(Modifier.fillMaxWidth()) {
                                            Text(row.date, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                                            Text(money(row.totalCost), fontWeight = FontWeight.Bold)
                                        }
                                        Text(
                                            "${fmt(row.quantity)}${row.unit} · 单价 ${row.unitPrice?.let { money(it) + "/" + row.unit } ?: "—"}" +
                                                (row.unitWeightJin?.let { " · ${fmt(it)}斤/${row.unit}" } ?: ""),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        Text(
                                            listOfNotNull(
                                                row.estimatedWeightJin?.let { "折合约 ${fmt(it)}斤" },
                                                row.buyerName.takeIf { it.isNotBlank() }?.let { "采购人 $it" },
                                                row.storeName.takeIf { it.isNotBlank() }?.let { "${it}" }
                                            ).joinToString(" · "),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.Gray
                                        )
                                    }
                                }
                            }
                            if (detail.purchases.size > 100) {
                                item {
                                    Text("采购明细仅显示最近100条，共 ${detail.purchases.size} 条", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }

                        item {
                            Text("库存与损耗", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        }

                        item {
                            Card(
                                Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = Color.White)
                            ) {
                                Column(
                                    Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    val lossText = summary.lossByUnit
                                        .filter { it.quantity > 0.000001 }
                                        .joinToString(" · ") { "${fmt(it.quantity)}${it.unit}" }
                                        .ifBlank { "0" }
                                    val remainingText = summary.latestRemainingByUnit
                                        .joinToString(" · ") { "${fmt(it.quantity)}${it.unit}" }
                                        .ifBlank { "暂无" }
                                    Text("本期累计损耗：$lossText")
                                    Text("本期最后一次盘点剩余：$remainingText")
                                    if (detail.inventory.isNotEmpty()) {
                                        HorizontalDivider()
                                        detail.inventory.take(60).forEach { row ->
                                            Row(Modifier.fillMaxWidth()) {
                                                Text(row.date, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                                Text(
                                                    "损耗 ${fmt(row.lossQuantity)}${row.unit} · 剩余 ${fmt(row.remainingQuantity)}${row.unit}",
                                                    style = MaterialTheme.typography.bodySmall
                                                )
                                            }
                                        }
                                        if (detail.inventory.size > 60) {
                                            Text("库存记录仅显示最近60条，共 ${detail.inventory.size} 条", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                        }

                        item {
                            Text(
                                "说明：采购次数按包含该商品的正式采购单计算；采购均价按数量加权。箱/件/筐等只有录入每件重量时才能折合为斤，未录规格的历史数量仍按原单位保留。零售价为每日主零售价，不等同于实际成交均价。",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }
                    }
                }
            }

            HistorySection.PROFIT -> {
                if (profitHistoryByDate.isEmpty()) {
                    item {
                        Text("当前时间范围暂无利润记录", color = Color.Gray)
                    }
                }

                profitHistoryByDate.forEach { (historyDate, daySummary, shares) ->
                    item(key = "profit-$historyDate") {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(11.dp)) {
                                Text(
                                    "$historyDate ${runCatching { chineseWeekday(LocalDate.parse(historyDate)) }.getOrDefault("")}",
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "当日利润 ${money(daySummary.profit)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color =
                                        if (daySummary.profit >= 0.0) BrandGreen
                                        else MaterialTheme.colorScheme.error
                                )
                                shares.forEach { share ->
                                    val ratio =
                                        if (kotlin.math.abs(daySummary.profit) > 0.005) {
                                            share.amount / daySummary.profit * 100.0
                                        } else {
                                            0.0
                                        }
                                    Text(
                                        "• ${share.partnerName} ${fmt(ratio)}%  ${money(share.amount)}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                }
            }

            HistorySection.PLAN -> {
                if (purchasePlans.isEmpty()) {
                    item {
                        Text("当前时间范围暂无采购计划", color = Color.Gray)
                    }
                }

                items(purchasePlans, key = { "plan${it.plan.id}" }) { detail ->
                    val pending = detail.items.count { it.status == 0 }
                    val purchased = detail.items.count { it.status == 1 }
                    val cancelled = detail.items.count { it.status == 2 }

                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(11.dp)) {
                            Text(
                                detail.plan.planDate,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "待采购 $pending · 已采购 $purchased · 取消 $cancelled",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                            detail.items.forEach { item ->
                                val statusText =
                                    when (item.status) {
                                        1 -> "已采购"
                                        2 -> "取消"
                                        else -> "待采购"
                                    }
                                Text(
                                    "• ${item.fruitName} " +
                                        "${fmt(item.quantity)}${item.unit} · $statusText",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (canEdit) {
        deleteOrder?.let { p ->
            ConfirmDelete(
                "删除这张采购单？",
                { deleteOrder = null }
            ) {
                db.deletePurchaseOrder(p.order.id)
                deleteOrder = null
                onChanged()
            }
        }

        deleteSession?.let { s ->
            ConfirmDelete(
                "删除 ${s.date} ${s.storeName} 营业记录？",
                { deleteSession = null }
            ) {
                db.deleteStoreDailyRecord(s.id)
                deleteSession = null
                onChanged()
            }
        }
    }
}

@Composable
private fun StatsContent(
    db: AppDatabase,
    dataVersion: Int
) {
    var filter by remember {
        mutableStateOf(
            HistoryTimeFilter.THIS_MONTH
        )
    }

    var tab by remember {
        mutableStateOf(
            BusinessStatsTab.OVERVIEW
        )
    }

    var trendMetric by remember {
        mutableStateOf(
            BusinessTrendMetric.REVENUE
        )
    }

    var calendarStoreName by remember {
        mutableStateOf<String?>(null)
    }

    var calendarMetrics by remember {
        mutableStateOf(
            setOf(
                BusinessCalendarMetric.REVENUE,
                BusinessCalendarMetric.PURCHASE,
                BusinessCalendarMetric.PROFIT
            )
        )
    }

    var calendarPartnerIds by remember {
        mutableStateOf<Set<Long>>(emptySet())
    }

    val today =
        LocalDate.now()

    var customStart by remember {
        mutableStateOf(
            today
                .withDayOfMonth(1)
                .toString()
        )
    }

    var customEnd by remember {
        mutableStateOf(
            today.toString()
        )
    }

    val invalidCustom =
        filter ==
            HistoryTimeFilter.CUSTOM &&
            customStart >
                customEnd

    val range =
        resolveTimeRange(
            filter,
            customStart,
            customEnd,
            today
        )

    val queryStart =
        if (invalidCustom) {
            "9999-12-31"
        } else {
            range.first
        }

    val queryEnd =
        if (invalidCustom) {
            "0000-01-01"
        } else {
            range.second
        }

    val records =
        remember(
            dataVersion,
            queryStart,
            queryEnd
        ) {
            db.getDailyRecordsBetween(
                queryStart,
                queryEnd
            )
        }

    val purchases =
        remember(
            dataVersion,
            queryStart,
            queryEnd
        ) {
            db.getPurchaseOrdersBetween(
                queryStart,
                queryEnd
            )
        }

    val profitDistributions =
        remember(
            dataVersion,
            queryStart,
            queryEnd
        ) {
            db.getProfitDistributionsBetween(
                queryStart,
                queryEnd
            )
        }

    val statsPartners =
        remember(
            dataVersion,
            purchases,
            profitDistributions,
            records
        ) {
            val byId = linkedMapOf<Long, PartnerOption>()
            db.getPartners().forEach { byId[it.id] = it }
            purchases.forEach { detail ->
                if (detail.order.buyerId > 0L && detail.order.buyerName.isNotBlank()) {
                    byId.putIfAbsent(
                        detail.order.buyerId,
                        PartnerOption(detail.order.buyerId, detail.order.buyerName)
                    )
                }
            }
            profitDistributions.forEach { row ->
                if (row.partnerId > 0L && row.partnerName.isNotBlank()) {
                    byId.putIfAbsent(
                        row.partnerId,
                        PartnerOption(row.partnerId, row.partnerName)
                    )
                }
            }
            records.forEach { record ->
                record.receiptSplits.forEach { split ->
                    if (split.partnerId > 0L && split.partnerName.isNotBlank()) {
                        byId.putIfAbsent(
                            split.partnerId,
                            PartnerOption(split.partnerId, split.partnerName)
                        )
                    }
                }
            }
            byId.values.sortedBy { it.name }
        }

    val statsStores =
        remember(dataVersion) {
            db.getStores()
        }

    val statsStoreNames =
        remember(
            records,
            statsStores
        ) {
            val namesInRange =
                records
                    .map { it.storeName }
                    .filter { it.isNotBlank() }
                    .distinct()
            val managedOrder =
                statsStores
                    .map { it.name }
                    .filter { it in namesInRange }
            val historicalOrDisabled =
                namesInRange
                    .filter { it !in managedOrder }
                    .sorted()
            managedOrder + historicalOrDisabled
        }

    val statsStoreOrderIndex =
        remember(statsStores) {
            statsStores
                .mapIndexed { index, store -> store.name to index }
                .toMap()
        }

    LaunchedEffect(
        statsStoreNames,
        calendarStoreName
    ) {
        if (
            calendarStoreName != null &&
            calendarStoreName !in statsStoreNames
        ) {
            calendarStoreName = null
        }
    }

    LaunchedEffect(calendarStoreName) {
        // 个人采购和个人利润没有位置归属，具体位置下只看整体数据。
        // 采购也未绑定位置，因此具体位置不能准确计算利润，直接隐藏利润指标。
        if (calendarStoreName != null) {
            calendarPartnerIds = emptySet()
            calendarMetrics =
                calendarMetrics
                    .minus(BusinessCalendarMetric.PROFIT)
                    .ifEmpty { setOf(BusinessCalendarMetric.REVENUE) }
        }
    }

    LaunchedEffect(statsPartners) {
        val valid = statsPartners.map { it.id }.toSet()
        val normalized = calendarPartnerIds.intersect(valid)
        if (normalized != calendarPartnerIds) {
            calendarPartnerIds = normalized
        }
    }

    val recordsByDate =
        remember(
            records
        ) {
            records.groupBy { it.date }
        }

    val purchaseDetailsByDate =
        remember(purchases) {
            purchases.groupBy { it.order.date }
        }

    val activityDates =
        remember(
            records,
            purchases
        ) {
            (
                records.map {
                    it.date
                } +
                    purchases.map {
                        it.order.date
                    }
                )
                .distinct()
                .sorted()
        }

    val summaries =
        remember(
            dataVersion,
            activityDates
        ) {
            activityDates.map {
                date ->
                db.getDailySummary(
                    date
                )
            }
        }

    val rankings =
        remember(
            dataVersion,
            queryStart,
            queryEnd
        ) {
            db.getRankings(
                queryStart,
                queryEnd
            )
        }

    val previousRange =
        remember(
            queryStart,
            queryEnd,
            invalidCustom
        ) {
            if (
                invalidCustom ||
                queryStart == null ||
                queryEnd == null
            ) {
                null
            } else {
                runCatching {
                    val start =
                        LocalDate.parse(
                            queryStart
                        )
                    val end =
                        LocalDate.parse(
                            queryEnd
                        )
                    val dayCount =
                        java.time.temporal
                            .ChronoUnit
                            .DAYS
                            .between(
                                start,
                                end
                            ) + 1L

                    val previousEnd =
                        start.minusDays(
                            1
                        )

                    val previousStart =
                        previousEnd
                            .minusDays(
                                dayCount - 1
                            )

                    previousStart
                        .toString() to
                        previousEnd
                            .toString()
                }.getOrNull()
            }
        }

    val previousRecords =
        remember(
            dataVersion,
            previousRange
        ) {
            previousRange?.let {
                db.getDailyRecordsBetween(
                    it.first,
                    it.second
                )
            } ?: emptyList()
        }

    val previousPurchases =
        remember(
            dataVersion,
            previousRange
        ) {
            previousRange?.let {
                db.getPurchaseOrdersBetween(
                    it.first,
                    it.second
                )
            } ?: emptyList()
        }

    val previousDates =
        remember(
            previousRecords,
            previousPurchases
        ) {
            (
                previousRecords.map {
                    it.date
                } +
                    previousPurchases.map {
                        it.order.date
                    }
                )
                .distinct()
                .sorted()
        }

    val previousSummaries =
        remember(
            dataVersion,
            previousDates
        ) {
            previousDates.map {
                date ->
                db.getDailySummary(
                    date
                )
            }
        }

    fun sumRevenue(
        source: List<DailySummary>
    ): Double =
        source.sumOf {
            it.revenue
        }

    fun sumProfit(
        source: List<DailySummary>
    ): Double =
        source.sumOf {
            it.profit
        }

    fun sumPurchase(
        source: List<DailySummary>
    ): Double =
        source.sumOf {
            it.purchaseCost
        }

    fun sumExpense(
        source: List<DailySummary>
    ): Double =
        source.sumOf {
            it.expense
        }

    fun sumCustomers(
        source: List<DailySummary>
    ): Int =
        source.sumOf {
            it.customers
        }

    fun changePercent(
        current: Double,
        previous: Double
    ): Double? =
        when {
            kotlin.math.abs(
                previous
            ) > 0.000001 ->
                (
                    current -
                        previous
                    ) /
                    kotlin.math.abs(
                        previous
                    ) *
                    100.0

            kotlin.math.abs(
                current
            ) < 0.000001 ->
                0.0

            else ->
                null
        }

    fun changeLabel(
        current: Double,
        previous: Double,
        suffix: String = "%"
    ): String {
        val change =
            changePercent(
                current,
                previous
            )
                ?: return "上期无可比数据"

        val arrow =
            when {
                change > 0.05 ->
                    "↑"

                change < -0.05 ->
                    "↓"

                else ->
                    "→"
            }

        return "$arrow" +
            String.format(
                Locale.CHINA,
                "%.1f",
                kotlin.math.abs(
                    change
                )
            ) +
            suffix
    }

    val totalRevenue =
        sumRevenue(
            summaries
        )

    val totalProfit =
        sumProfit(
            summaries
        )

    val totalPurchase =
        sumPurchase(
            summaries
        )

    val totalExpense =
        sumExpense(
            summaries
        )

    val totalCustomers =
        sumCustomers(
            summaries
        )

    val activeDays =
        records.map {
            it.date
        }
            .distinct()
            .size

    val newCustomers =
        records.sumOf {
            it.newCustomer
        }

    val oldCustomers =
        records.sumOf {
            it.oldCustomer
        }

    val averageTicket =
        if (totalCustomers > 0) {
            totalRevenue /
                totalCustomers
        } else {
            0.0
        }

    val profitRate =
        if (
            kotlin.math.abs(
                totalRevenue
            ) > 0.000001
        ) {
            totalProfit /
                totalRevenue *
                100.0
        } else {
            0.0
        }

    val purchaseInputRate =
        if (
            kotlin.math.abs(
                totalRevenue
            ) > 0.000001
        ) {
            totalPurchase /
                totalRevenue *
                100.0
        } else {
            0.0
        }

    val expenseRate =
        if (
            kotlin.math.abs(
                totalRevenue
            ) > 0.000001
        ) {
            totalExpense /
                totalRevenue *
                100.0
        } else {
            0.0
        }

    val customerProfit =
        if (totalCustomers > 0) {
            totalProfit /
                totalCustomers
        } else {
            0.0
        }

    val previousRevenue =
        sumRevenue(
            previousSummaries
        )

    val previousProfit =
        sumProfit(
            previousSummaries
        )

    val previousPurchase =
        sumPurchase(
            previousSummaries
        )

    val previousCustomers =
        sumCustomers(
            previousSummaries
        )

    val previousAverageTicket =
        if (
            previousCustomers >
            0
        ) {
            previousRevenue /
                previousCustomers
        } else {
            0.0
        }

    val sortedSummaries =
        summaries.sortedBy {
            it.date
        }

    val bestRevenueDay =
        summaries.maxByOrNull {
            it.revenue
        }

    val bestProfitDay =
        summaries.maxByOrNull {
            it.profit
        }

    val worstProfitDay =
        summaries.minByOrNull {
            it.profit
        }

    val bestCustomerDay =
        summaries.maxByOrNull {
            it.customers
        }

    val bestStore =
        rankings
            .filter {
                it.days > 0
            }
            .maxByOrNull {
                it.profit /
                    it.days
            }

    val bestRevenueStore =
        rankings
            .filter {
                it.days > 0
            }
            .maxByOrNull {
                it.revenue /
                    it.days
            }

    val averageDailyRevenue =
        if (activeDays > 0) {
            totalRevenue / activeDays
        } else {
            0.0
        }

    val averageDailyProfit =
        if (activeDays > 0) {
            totalProfit / activeDays
        } else {
            0.0
        }

    val averageDailyCustomers =
        if (activeDays > 0) {
            totalCustomers.toDouble() / activeDays
        } else {
            0.0
        }

    val alerts =
        remember(
            totalRevenue,
            totalProfit,
            totalPurchase,
            totalCustomers,
            previousRevenue,
            previousCustomers,
            previousAverageTicket,
            averageTicket
        ) {
            buildList {
                if (
                    totalProfit <
                    -0.005
                ) {
                    add(
                        "本期经营利润为负，需要重点检查进货投入和业务费用。"
                    )
                }

                val revenueChange =
                    changePercent(
                        totalRevenue,
                        previousRevenue
                    )

                if (
                    revenueChange !=
                    null &&
                    revenueChange <
                    -20.0
                ) {
                    add(
                        "营业额较上一相同周期下降 " +
                            String.format(
                                Locale.CHINA,
                                "%.1f",
                                kotlin.math.abs(
                                    revenueChange
                                )
                            ) +
                            "%。"
                    )
                }

                val customerChange =
                    changePercent(
                        totalCustomers
                            .toDouble(),
                        previousCustomers
                            .toDouble()
                    )

                if (
                    customerChange !=
                    null &&
                    customerChange <
                    -20.0
                ) {
                    add(
                        "客户数较上一相同周期下降 " +
                            String.format(
                                Locale.CHINA,
                                "%.1f",
                                kotlin.math.abs(
                                    customerChange
                                )
                            ) +
                            "%。"
                    )
                }

                val ticketChange =
                    changePercent(
                        averageTicket,
                        previousAverageTicket
                    )

                if (
                    ticketChange !=
                    null &&
                    ticketChange <
                    -15.0
                ) {
                    add(
                        "客单价下降较明显，当前为 " +
                            money(
                                averageTicket
                            ) +
                            "。"
                    )
                }

                if (
                    totalRevenue >
                    0.0 &&
                    purchaseInputRate >
                    85.0
                ) {
                    add(
                        "本期进货投入率为 " +
                            String.format(
                                Locale.CHINA,
                                "%.1f%%",
                                purchaseInputRate
                            ) +
                            "，建议关注资金投入节奏。"
                    )
                }
            }
        }

    val summaryText =
        buildString {
            if (
                previousRange != null
            ) {
                append(
                    "本期营业额 " +
                        money(
                            totalRevenue
                        ) +
                        "，"
                )

                val revenueChange =
                    changePercent(
                        totalRevenue,
                        previousRevenue
                    )

                if (
                    revenueChange != null
                ) {
                    append(
                        "较上一周期" +
                            if (
                                revenueChange >=
                                0
                            ) {
                                "增长 "
                            } else {
                                "下降 "
                            } +
                            String.format(
                                Locale.CHINA,
                                "%.1f%%",
                                kotlin.math.abs(
                                    revenueChange
                                )
                            ) +
                            "；"
                    )
                }

                append(
                    "利润 " +
                        money(
                            totalProfit
                        ) +
                        "，利润率 " +
                        String.format(
                            Locale.CHINA,
                            "%.1f%%",
                            profitRate
                        ) +
                        "。"
                )
            } else {
                append(
                    "累计营业额 " +
                        money(
                            totalRevenue
                        ) +
                        "，利润 " +
                        money(
                            totalProfit
                        ) +
                        "，利润率 " +
                        String.format(
                            Locale.CHINA,
                            "%.1f%%",
                            profitRate
                        ) +
                        "。"
                )
            }

            if (bestStore != null) {
                append(
                    " ${bestStore.storeName} 的日均利润最高。"
                )
            }
        }

    val weekdayStats =
        (1..7).map {
            dayOfWeek ->
            val rows =
                summaries.filter {
                    runCatching {
                        LocalDate
                            .parse(
                                it.date
                            )
                            .dayOfWeek
                            .value ==
                            dayOfWeek
                    }.getOrDefault(
                        false
                    )
                }

            val revenue =
                rows.sumOf {
                    it.revenue
                }

            val profit =
                rows.sumOf {
                    it.profit
                }

            val customers =
                rows.sumOf {
                    it.customers
                }

            Triple(
                dayOfWeek,
                BusinessWeekdayStat(
                    revenue =
                        revenue,
                    profit =
                        profit,
                    customers =
                        customers,
                    days =
                        rows.size
                ),
                rows
            )
        }

    val bestWeekday =
        weekdayStats
            .filter {
                it.second.days > 0
            }
            .maxByOrNull {
                it.second.revenue /
                    it.second.days
            }

    val bestWeekdayName =
        bestWeekday?.first?.let { day ->
            when (day) {
                1 -> "星期一"
                2 -> "星期二"
                3 -> "星期三"
                4 -> "星期四"
                5 -> "星期五"
                6 -> "星期六"
                else -> "星期日"
            }
        }

    val calendarMonth =
        runCatching {
            LocalDate
                .parse(
                    queryEnd
                        ?: today
                            .toString()
                )
                .withDayOfMonth(
                    1
                )
        }.getOrDefault(
            today.withDayOfMonth(
                1
            )
        )

    val calendarVisibleRecords =
        remember(
            records,
            calendarStoreName
        ) {
            calendarStoreName?.let { selectedStore ->
                records.filter {
                    it.storeName == selectedStore
                }
            } ?: records
        }

    val calendarSelectedPartners =
        statsPartners.filter { it.id in calendarPartnerIds }

    val calendarVisibleDates =
        calendarVisibleRecords
            .map { it.date }
            .toSet()

    val calendarBusinessDays =
        calendarVisibleDates.size

    val dailySummaryByDate =
        remember(dataVersion, activityDates) {
            activityDates.associateWith { db.getDailySummary(it) }
        }

    val effectiveProfitSharesByDate =
        remember(dataVersion, activityDates) {
            activityDates.associateWith { db.getEffectiveProfitShares(it) }
        }

    fun calendarValueForDate(
        date: String,
        metric: BusinessCalendarMetric
    ): Pair<Double, Boolean> {
        val allRecords = recordsByDate[date].orEmpty()
        val visibleRecords =
            if (calendarStoreName == null) {
                allRecords
            } else {
                allRecords.filter { it.storeName == calendarStoreName }
            }
        val hasSelectedStoreBusiness =
            calendarStoreName == null || visibleRecords.isNotEmpty()

        return when (metric) {
            BusinessCalendarMetric.REVENUE -> {
                val value =
                    if (calendarPartnerIds.isEmpty()) {
                        visibleRecords.sumOf { it.revenue }
                    } else {
                        visibleRecords.sumOf { record ->
                            calendarPartnerIds.sumOf { id ->
                                record.receiptAmountForPartner(id)
                            }
                        }
                    }
                value to
                    if (calendarPartnerIds.isEmpty()) {
                        visibleRecords.isNotEmpty()
                    } else {
                        kotlin.math.abs(value) > 0.005
                    }
            }

            BusinessCalendarMetric.PURCHASE -> {
                val datePurchases =
                    purchaseDetailsByDate[date].orEmpty().filter { detail ->
                        calendarPartnerIds.isEmpty() ||
                            detail.order.buyerId in calendarPartnerIds
                    }
                val value = datePurchases.sumOf { it.order.totalCost }
                value to (datePurchases.isNotEmpty() && hasSelectedStoreBusiness)
            }

            BusinessCalendarMetric.PROFIT -> {
                if (calendarPartnerIds.isEmpty()) {
                    val value =
                        if (calendarStoreName == null) {
                            dailySummaryByDate[date]?.profit ?: 0.0
                        } else {
                            // 采购未绑定位置；具体位置下保留该位置原始经营利润口径。
                            visibleRecords.sumOf { it.profit }
                        }
                    value to visibleRecords.isNotEmpty()
                } else {
                    val rows = effectiveProfitSharesByDate[date].orEmpty()
                        .filter { it.partnerId in calendarPartnerIds }
                    val value = rows.sumOf { it.amount }
                    value to rows.isNotEmpty()
                }
            }
        }
    }

    val calendarMetricTotals =
        calendarMetrics.associateWith { metric ->
            activityDates.sumOf { date ->
                calendarValueForDate(date, metric).first
            }
        }

    val calendarMetricDays =
        calendarMetrics.associateWith { metric ->
            activityDates.count { date ->
                calendarValueForDate(date, metric).second
            }
        }

    val calendarMetricAverages =
        calendarMetrics.associateWith { metric ->
            val days = calendarMetricDays[metric] ?: 0
            if (days > 0) {
                (calendarMetricTotals[metric] ?: 0.0) / days
            } else {
                0.0
            }
        }

    val calendarMetricLabel =
        calendarMetrics
            .sortedBy { it.ordinal }
            .joinToString(" / ") { metric ->
                if (metric == BusinessCalendarMetric.REVENUE && calendarPartnerIds.isNotEmpty()) {
                    "收款额"
                } else {
                    metric.label
                }
            }

    val calendarPartnerLabel =
        if (calendarPartnerIds.isEmpty()) {
            "全部"
        } else {
            calendarSelectedPartners.joinToString("、") { it.name }
        }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding =
            PaddingValues(
                14.dp
            ),
        verticalArrangement =
            Arrangement.spacedBy(
                10.dp
            )
    ) {
        item {
            TimeFilterSelector(
                filter = filter,
                onFilterChange = {
                    filter = it
                },
                customStart =
                    customStart,
                onCustomStart = {
                    customStart = it
                },
                customEnd =
                    customEnd,
                onCustomEnd = {
                    customEnd = it
                }
            )
        }

        if (invalidCustom) {
            item {
                Text(
                    "开始日期不能晚于结束日期",
                    color =
                        MaterialTheme
                            .colorScheme
                            .error
                )
            }
        }

        item {
            ScrollableTabRow(
                selectedTabIndex =
                    tab.ordinal,
                edgePadding = 0.dp
            ) {
                BusinessStatsTab
                    .entries
                    .forEach {
                        item ->
                        Tab(
                            selected =
                                tab ==
                                    item,
                            onClick = {
                                tab =
                                    item
                            },
                            text = {
                                Text(
                                    item.label
                                )
                            }
                        )
                    }
            }
        }

        when (tab) {
            BusinessStatsTab.OVERVIEW -> {
                item {
                    Card(
                        colors =
                            CardDefaults.cardColors(
                                containerColor = Color(0xFFF5FAF7)
                            )
                    ) {
                        Text(
                            summaryText,
                            modifier = Modifier.padding(14.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricCard(
                            "营业额",
                            money(totalRevenue),
                            Modifier.weight(1f),
                            SoftGreen,
                            if (previousRange != null) {
                                changeLabel(totalRevenue, previousRevenue)
                            } else null
                        )
                        MetricCard(
                            "利润",
                            money(totalProfit),
                            Modifier.weight(1f),
                            SoftOrange,
                            if (previousRange != null) {
                                changeLabel(totalProfit, previousProfit)
                            } else null
                        )
                    }
                }

                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricCard(
                            "经营天数",
                            "$activeDays 天",
                            Modifier.weight(1f),
                            SoftBlue
                        )
                        MetricCard(
                            "日均营业额",
                            money(averageDailyRevenue),
                            Modifier.weight(1f),
                            SoftPurple
                        )
                    }
                }

                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricCard(
                            "采购金额",
                            money(totalPurchase),
                            Modifier.weight(1f),
                            SoftOrange,
                            if (previousRange != null) {
                                changeLabel(totalPurchase, previousPurchase)
                            } else null
                        )
                        MetricCard(
                            "利润率",
                            String.format(Locale.CHINA, "%.1f%%", profitRate),
                            Modifier.weight(1f),
                            SoftGreen
                        )
                    }
                }

                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricCard(
                            "客户数",
                            "$totalCustomers 人",
                            Modifier.weight(1f),
                            SoftBlue,
                            if (previousRange != null) {
                                changeLabel(
                                    totalCustomers.toDouble(),
                                    previousCustomers.toDouble()
                                )
                            } else null
                        )
                        MetricCard(
                            "客单价",
                            money(averageTicket),
                            Modifier.weight(1f),
                            SoftPurple,
                            if (previousRange != null) {
                                changeLabel(averageTicket, previousAverageTicket)
                            } else null
                        )
                    }
                }

                item {
                    Text(
                        "经营效率",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(5.dp))
                    Card {
                        Column(
                            Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            SummaryRow(
                                "采购占营业额",
                                String.format(Locale.CHINA, "%.1f%%", purchaseInputRate)
                            )
                            SummaryRow("业务费用", money(totalExpense))
                            SummaryRow(
                                "费用率",
                                String.format(Locale.CHINA, "%.1f%%", expenseRate)
                            )
                            SummaryRow("日均利润", money(averageDailyProfit))
                            SummaryRow(
                                "日均客户",
                                String.format(Locale.CHINA, "%.1f 人", averageDailyCustomers)
                            )
                            SummaryRow("单客利润", money(customerProfit))
                        }
                    }
                }

                if (previousRange != null) {
                    item {
                        Text(
                            "与上一周期对比",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(5.dp))
                        Card {
                            Column(
                                Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                SummaryRow(
                                    "营业额",
                                    changeLabel(totalRevenue, previousRevenue)
                                )
                                SummaryRow(
                                    "利润",
                                    changeLabel(totalProfit, previousProfit)
                                )
                                SummaryRow(
                                    "采购金额",
                                    changeLabel(totalPurchase, previousPurchase)
                                )
                                SummaryRow(
                                    "客户数",
                                    changeLabel(
                                        totalCustomers.toDouble(),
                                        previousCustomers.toDouble()
                                    )
                                )
                                SummaryRow(
                                    "客单价",
                                    changeLabel(averageTicket, previousAverageTicket)
                                )
                            }
                        }
                    }
                }

                item {
                    Text(
                        "经营提醒",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(5.dp))
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor =
                                if (alerts.isEmpty()) {
                                    Color(0xFFF5FAF7)
                                } else {
                                    Color(0xFFFFF8E8)
                                }
                        )
                    ) {
                        Column(
                            Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (alerts.isEmpty()) {
                                Text(
                                    "当前时间范围没有发现明显经营异常。",
                                    color = BrandGreen
                                )
                            } else {
                                alerts.forEach { warning ->
                                    Text("⚠ $warning")
                                }
                            }
                        }
                    }
                }

                item {
                    Text(
                        "经营亮点",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(5.dp))
                    Card {
                        Column(Modifier.padding(12.dp)) {
                            SummaryRow(
                                "最高营业额经营日",
                                bestRevenueDay?.let {
                                    "${it.date} · ${money(it.revenue)}"
                                } ?: "暂无"
                            )
                            SummaryRow(
                                "最佳位置",
                                bestRevenueStore?.let {
                                    val dailyRevenue =
                                        if (it.days > 0) it.revenue / it.days else 0.0
                                    "${it.storeName} · 日均 ${money(dailyRevenue)}"
                                } ?: "暂无"
                            )
                            SummaryRow(
                                "最佳星期",
                                if (bestWeekday != null && bestWeekdayName != null) {
                                    val avg =
                                        bestWeekday.second.revenue /
                                            bestWeekday.second.days
                                    "$bestWeekdayName · 日均 ${money(avg)}"
                                } else {
                                    "暂无"
                                }
                            )
                            SummaryRow(
                                "最高利润",
                                bestProfitDay?.let {
                                    "${it.date} · ${money(it.profit)}"
                                } ?: "暂无"
                            )
                            SummaryRow(
                                "最低利润",
                                worstProfitDay?.let {
                                    "${it.date} · ${money(it.profit)}"
                                } ?: "暂无"
                            )
                            SummaryRow(
                                "最高客流",
                                bestCustomerDay?.let {
                                    "${it.date} · ${it.customers} 人"
                                } ?: "暂无"
                            )
                        }
                    }
                }
            }

            BusinessStatsTab.TREND -> {
                item {
                    Row(
                        Modifier
                            .fillMaxWidth(),
                        horizontalArrangement =
                            Arrangement
                                .spacedBy(
                                    6.dp
                                )
                    ) {
                        BusinessTrendMetric
                            .entries
                            .forEach {
                                metric ->
                                FilterChip(
                                    selected =
                                        trendMetric ==
                                            metric,
                                    onClick = {
                                        trendMetric =
                                            metric
                                    },
                                    label = {
                                        Text(
                                            metric.label
                                        )
                                    },
                                    modifier =
                                        Modifier.weight(
                                            1f
                                        )
                                )
                            }
                    }
                }

                val trendRows =
                    sortedSummaries
                        .takeLast(
                            31
                        )

                val maxTrendValue =
                    trendRows
                        .maxOfOrNull {
                            row ->
                            when (
                                trendMetric
                            ) {
                                BusinessTrendMetric.REVENUE ->
                                    row.revenue

                                BusinessTrendMetric.PROFIT ->
                                    kotlin.math
                                        .abs(
                                            row.profit
                                        )

                                BusinessTrendMetric.CUSTOMERS ->
                                    row.customers
                                        .toDouble()
                            }
                        }
                        ?.coerceAtLeast(
                            1.0
                        )
                        ?: 1.0

                if (
                    trendRows.isEmpty()
                ) {
                    item {
                        EmptyHint(
                            "当前时间范围暂无经营数据"
                        )
                    }
                }

                items(
                    trendRows,
                    key = {
                        "business_trend_${it.date}"
                    }
                ) {
                    row ->
                    val value =
                        when (
                            trendMetric
                        ) {
                            BusinessTrendMetric.REVENUE ->
                                row.revenue

                            BusinessTrendMetric.PROFIT ->
                                row.profit

                            BusinessTrendMetric.CUSTOMERS ->
                                row.customers
                                    .toDouble()
                        }

                    BusinessTrendRow(
                        date = row.date,
                        label =
                            when (
                                trendMetric
                            ) {
                                BusinessTrendMetric.REVENUE ->
                                    money(
                                        value
                                    )

                                BusinessTrendMetric.PROFIT ->
                                    money(
                                        value
                                    )

                                BusinessTrendMetric.CUSTOMERS ->
                                    "${row.customers} 人"
                            },
                        ratio =
                            (
                                kotlin.math
                                    .abs(
                                        value
                                    ) /
                                    maxTrendValue
                                )
                                .toFloat()
                                .coerceIn(
                                    0f,
                                    1f
                                ),
                        negative =
                            value < 0
                    )
                }
            }

            BusinessStatsTab.STORES -> {
                if (
                    rankings.isEmpty()
                ) {
                    item {
                        EmptyHint(
                            "当前时间范围暂无位置数据"
                        )
                    }
                }

                items(
                    rankings
                        .sortedWith(
                            compareBy<RankingRecord> {
                                statsStoreOrderIndex[it.storeName] ?: Int.MAX_VALUE
                            }.thenBy { it.storeName }
                        ),
                    key = {
                        "store_stats_${it.storeName}"
                    }
                ) {
                    row ->
                    val storeProfitRate =
                        if (
                            kotlin.math.abs(
                                row.revenue
                            ) >
                            0.000001
                        ) {
                            row.profit /
                                row.revenue *
                                100.0
                        } else {
                            0.0
                        }

                    val storeTicket =
                        if (
                            row.customers >
                            0
                        ) {
                            row.revenue /
                                row.customers
                        } else {
                            0.0
                        }

                    val dailyRevenue =
                        if (
                            row.days > 0
                        ) {
                            row.revenue /
                                row.days
                        } else {
                            0.0
                        }

                    val dailyProfit =
                        if (
                            row.days > 0
                        ) {
                            row.profit /
                                row.days
                        } else {
                            0.0
                        }

                    Card(
                        Modifier.fillMaxWidth()
                    ) {
                        Column(
                            Modifier.padding(
                                13.dp
                            ),
                            verticalArrangement =
                                Arrangement
                                    .spacedBy(
                                        4.dp
                                    )
                        ) {
                            Text(
                                row.storeName,
                                style =
                                    MaterialTheme
                                        .typography
                                        .titleMedium,
                                fontWeight =
                                    FontWeight.Bold
                            )

                            Row(
                                Modifier
                                    .fillMaxWidth(),
                                horizontalArrangement =
                                    Arrangement
                                        .SpaceBetween
                            ) {
                                Text(
                                    "营业额 ${money(row.revenue)}"
                                )
                                Text(
                                    "利润 ${money(row.profit)}",
                                    fontWeight =
                                        FontWeight
                                            .SemiBold
                                )
                            }

                            Text(
                                "利润率 " +
                                    String.format(
                                        Locale.CHINA,
                                        "%.1f%%",
                                        storeProfitRate
                                    ) +
                                    " · 客户 ${row.customers} 人 · 客单价 ${money(storeTicket)}",
                                style =
                                    MaterialTheme
                                        .typography
                                        .bodySmall,
                                color = Color.Gray
                            )

                            Text(
                                "经营 ${row.days} 天 · 日均营业额 ${money(dailyRevenue)}",
                                style =
                                    MaterialTheme
                                        .typography
                                        .bodySmall,
                                color = BrandGreen
                            )

                            Text(
                                "日均利润 ${money(dailyProfit)}",
                                style =
                                    MaterialTheme
                                        .typography
                                        .bodySmall,
                                color = Color.Gray
                            )
                        }
                    }
                }

                item {
                    Text(
                        "说明：共用采购金额仍按各位置营业额比例分摊，仅用于位置间利润效率比较。",
                        style =
                            MaterialTheme
                                .typography
                                .labelSmall,
                        color = Color.Gray
                    )
                }
            }

            BusinessStatsTab.CUSTOMERS -> {
                item {
                    Row(
                        horizontalArrangement =
                            Arrangement
                                .spacedBy(
                                    8.dp
                                )
                    ) {
                        MetricCard(
                            "新客户",
                            "$newCustomers 人",
                            Modifier.weight(
                                1f
                            ),
                            SoftGreen
                        )

                        MetricCard(
                            "老客户",
                            "$oldCustomers 人",
                            Modifier.weight(
                                1f
                            ),
                            SoftBlue
                        )
                    }
                }

                item {
                    Card {
                        Column(
                            Modifier.padding(
                                13.dp
                            )
                        ) {
                            SummaryRow(
                                "新客占比",
                                if (
                                    totalCustomers >
                                    0
                                ) {
                                    String.format(
                                        Locale.CHINA,
                                        "%.1f%%",
                                        newCustomers
                                            .toDouble() /
                                            totalCustomers *
                                            100.0
                                    )
                                } else {
                                    "0.0%"
                                }
                            )

                            SummaryRow(
                                "客单价",
                                money(
                                    averageTicket
                                )
                            )

                            SummaryRow(
                                "单客利润",
                                money(
                                    customerProfit
                                )
                            )
                        }
                    }
                }

                item {
                    Text(
                        "星期表现",
                        style =
                            MaterialTheme
                                .typography
                                .titleMedium,
                        fontWeight =
                            FontWeight.Bold
                    )
                }

                items(
                    weekdayStats,
                    key = {
                        "weekday_${it.first}"
                    }
                ) {
                    item ->
                    val day =
                        item.first

                    val row =
                        item.second

                    val dayName =
                        when (day) {
                            1 -> "星期一"
                            2 -> "星期二"
                            3 -> "星期三"
                            4 -> "星期四"
                            5 -> "星期五"
                            6 -> "星期六"
                            else -> "星期日"
                        }

                    Card(
                        Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.padding(
                                12.dp
                            ),
                            verticalAlignment =
                                Alignment
                                    .CenterVertically
                        ) {
                            Column(
                                Modifier.weight(
                                    1f
                                )
                            ) {
                                Text(
                                    dayName,
                                    fontWeight =
                                        FontWeight
                                            .SemiBold
                                )
                                Text(
                                    "${row.days} 个经营日 · ${row.customers} 人",
                                    style =
                                        MaterialTheme
                                            .typography
                                            .bodySmall,
                                    color =
                                        Color.Gray
                                )
                            }

                            Column(
                                horizontalAlignment =
                                    Alignment.End
                            ) {
                                Text(
                                    money(
                                        row.revenue
                                    ),
                                    fontWeight =
                                        FontWeight
                                            .SemiBold
                                )
                                Text(
                                    "利润 ${money(row.profit)}",
                                    style =
                                        MaterialTheme
                                            .typography
                                            .bodySmall,
                                    color =
                                        if (
                                            row.profit >=
                                            0
                                        ) {
                                            BrandGreen
                                        } else {
                                            MaterialTheme
                                                .colorScheme
                                                .error
                                        }
                                )
                            }
                        }
                    }
                }
            }

            BusinessStatsTab.CALENDAR -> {
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = calendarStoreName == null,
                            onClick = { calendarStoreName = null },
                            label = { Text("全部位置") }
                        )

                        statsStoreNames.forEach { storeName ->
                            FilterChip(
                                selected = calendarStoreName == storeName,
                                onClick = {
                                    calendarStoreName = storeName
                                    calendarPartnerIds = emptySet()
                                    calendarMetrics =
                                        calendarMetrics
                                            .minus(BusinessCalendarMetric.PROFIT)
                                            .ifEmpty { setOf(BusinessCalendarMetric.REVENUE) }
                                },
                                label = { Text(storeName) }
                            )
                        }
                    }
                }

                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        BusinessCalendarMetric.entries
                            .filter { metric ->
                                calendarStoreName == null ||
                                    metric != BusinessCalendarMetric.PROFIT
                            }
                            .forEach { metric ->
                                FilterChip(
                                    selected = metric in calendarMetrics,
                                    onClick = {
                                        calendarMetrics =
                                            if (metric in calendarMetrics) {
                                                if (calendarMetrics.size > 1) {
                                                    calendarMetrics - metric
                                                } else {
                                                    calendarMetrics
                                                }
                                            } else {
                                                calendarMetrics + metric
                                            }
                                    },
                                    label = { Text(metric.label) }
                                )
                            }
                    }
                }

                item {
                    if (calendarStoreName == null) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "合伙人数据",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.Gray
                            )
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                FilterChip(
                                    selected = calendarPartnerIds.isEmpty(),
                                    onClick = { calendarPartnerIds = emptySet() },
                                    label = { Text("全部") }
                                )
                                statsPartners.forEach { partner ->
                                    FilterChip(
                                        selected = partner.id in calendarPartnerIds,
                                        onClick = {
                                            calendarPartnerIds =
                                                if (partner.id in calendarPartnerIds) {
                                                    calendarPartnerIds - partner.id
                                                } else {
                                                    calendarPartnerIds + partner.id
                                                }
                                        },
                                        label = { Text(partner.name) }
                                    )
                                }
                            }
                        }
                    } else {
                        Text(
                            "具体位置只显示营业额和采购额。采购没有位置归属，无法准确计算单个位置利润；切换到“全部位置”后可查看利润和合伙人数据。",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    }
                }

                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFFF5FAF7)
                        )
                    ) {
                        Column(
                            Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            SummaryRow(
                                "位置",
                                calendarStoreName ?: "全部位置"
                            )
                            SummaryRow(
                                "经营日",
                                "${calendarBusinessDays} 天"
                            )
                            SummaryRow(
                                "显示数据",
                                calendarMetricLabel
                            )
                            SummaryRow(
                                "合伙人",
                                calendarPartnerLabel
                            )
                            calendarMetrics
                                .sortedBy { it.ordinal }
                                .forEach { metric ->
                                    val label =
                                        if (metric == BusinessCalendarMetric.REVENUE && calendarPartnerIds.isNotEmpty()) {
                                            "收款额"
                                        } else {
                                            metric.label
                                        }
                                    SummaryRow(
                                        "${label}合计",
                                        money(calendarMetricTotals[metric] ?: 0.0)
                                    )
                                    SummaryRow(
                                        "${label}日均",
                                        money(calendarMetricAverages[metric] ?: 0.0)
                                    )
                                }
                        }
                    }
                }

                item {
                    BusinessCalendarCard(
                        month = calendarMonth,
                        recordsByDate = recordsByDate,
                        purchaseDetailsByDate = purchaseDetailsByDate,
                        dailySummaryByDate = dailySummaryByDate,
                        effectiveProfitSharesByDate = effectiveProfitSharesByDate,
                        selectedStoreName = calendarStoreName,
                        metrics = calendarMetrics,
                        selectedPartnerIds = calendarPartnerIds,
                        selectedPartnerNames = calendarPartnerLabel
                    )
                }

                item {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        if (BusinessCalendarMetric.REVENUE in calendarMetrics) {
                            CalendarLegendDot(Color(0xFF1976D2), "营业额")
                        }
                        if (BusinessCalendarMetric.PURCHASE in calendarMetrics) {
                            CalendarLegendDot(Color(0xFFF9A825), "采购额")
                        }
                        if (BusinessCalendarMetric.PROFIT in calendarMetrics) {
                            CalendarLegendDot(BrandGreen, "利润")
                            CalendarLegendDot(MaterialTheme.colorScheme.error, "负利润")
                        }
                    }
                }

                item {
                    Text(
                        if (calendarStoreName == null) {
                            "日历格内只显示所选指标数字：营业额蓝色、采购额黄色、正利润绿色、负利润红色。指标和合伙人都支持多选；“全部”表示整体数据。采购仍按采购日统计。"
                        } else {
                            "具体位置模式不显示利润：采购金额没有位置归属，无法准确计算单个位置利润。日历仅显示所选营业额/采购额。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }
            }
        }
    }
}

private data class BusinessWeekdayStat(
    val revenue: Double,
    val profit: Double,
    val customers: Int,
    val days: Int
)

@Composable
private fun EmptyHint(
    text: String
) {
    Card(
        modifier =
            Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    Color(
                        0xFFF7F7F7
                    )
            )
    ) {
        Text(
            text,
            modifier =
                Modifier.padding(
                    horizontal = 14.dp,
                    vertical = 16.dp
                ),
            style =
                MaterialTheme
                    .typography
                    .bodyMedium,
            color = Color.Gray
        )
    }
}

@Composable
private fun BusinessTrendRow(
    date: String,
    label: String,
    ratio: Float,
    negative: Boolean
) {
    Card(
        Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier.padding(
                horizontal = 12.dp,
                vertical = 9.dp
            ),
            verticalArrangement =
                Arrangement.spacedBy(
                    5.dp
                )
        ) {
            Row(
                Modifier.fillMaxWidth()
            ) {
                Text(
                    date,
                    Modifier.weight(
                        1f
                    ),
                    style =
                        MaterialTheme
                            .typography
                            .bodySmall,
                    color = Color.Gray
                )

                Text(
                    label,
                    fontWeight =
                        FontWeight.SemiBold,
                    color =
                        if (negative) {
                            MaterialTheme
                                .colorScheme
                                .error
                        } else {
                            BrandGreen
                        }
                )
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(
                        7.dp
                    )
                    .clip(
                        RoundedCornerShape(
                            8.dp
                        )
                    )
                    .background(
                        Color(
                            0xFFE9EDF1
                        )
                    )
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(
                            ratio.coerceAtLeast(
                                0.02f
                            )
                        )
                        .fillMaxHeight()
                        .background(
                            if (negative) {
                                Color(
                                    0xFFFFD8D5
                                )
                            } else {
                                BrandGreen
                            }
                        )
                )
            }
        }
    }
}

private fun StoreDailyRecord.receiptAmountForPartner(partnerId: Long): Double {
    if (partnerId <= 0L) return 0.0

    if (receiptSplits.isNotEmpty()) {
        return receiptSplits
            .filter { it.partnerId == partnerId }
            .sumOf { it.amount }
    }

    var total = 0.0
    if (wechatCollectorId == partnerId) total += wechatIncome
    if (alipayCollectorId == partnerId) total += alipayIncome
    if (cashCollectorId == partnerId) total += cashIncome
    return total
}

@Composable
private fun CalendarLegendDot(
    color: Color,
    label: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(RoundedCornerShape(50))
                .background(color)
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.Gray
        )
    }
}

@Composable
private fun BusinessCalendarCard(
    month: LocalDate,
    recordsByDate: Map<String, List<StoreDailyRecord>>,
    purchaseDetailsByDate: Map<String, List<PurchaseOrderDetail>>,
    dailySummaryByDate: Map<String, DailySummary>,
    effectiveProfitSharesByDate: Map<String, List<PartnerMoneySummary>>,
    selectedStoreName: String? = null,
    metrics: Set<BusinessCalendarMetric>,
    selectedPartnerIds: Set<Long>,
    selectedPartnerNames: String
) {
    val first = month.withDayOfMonth(1)
    val daysInMonth = first.lengthOfMonth()
    val leading = first.dayOfWeek.value - 1
    val totalCells = (leading + daysInMonth + 6) / 7 * 7
    val orderedMetrics = metrics.sortedBy { it.ordinal }
    val cellHeight = (44 + orderedMetrics.size * 15).dp

    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text(
                first.format(DateTimeFormatter.ofPattern("yyyy年M月")),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Text(
                (selectedStoreName ?: "全部位置") + " · " + selectedPartnerNames,
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )

            Row(Modifier.fillMaxWidth()) {
                listOf("一", "二", "三", "四", "五", "六", "日")
                    .forEach { day ->
                        Text(
                            day,
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
            }

            for (rowStart in 0 until totalCells step 7) {
                Row(Modifier.fillMaxWidth()) {
                    repeat(7) { column ->
                        val cell = rowStart + column
                        val day = cell - leading + 1

                        if (day !in 1..daysInMonth) {
                            Spacer(
                                Modifier
                                    .weight(1f)
                                    .height(cellHeight)
                            )
                        } else {
                            val date = first.withDayOfMonth(day).toString()
                            val allRecords = recordsByDate[date].orEmpty()
                            val visibleRecords =
                                if (selectedStoreName == null) {
                                    allRecords
                                } else {
                                    allRecords.filter { it.storeName == selectedStoreName }
                                }
                            val hasSelectedStoreBusiness =
                                selectedStoreName == null || visibleRecords.isNotEmpty()

                            val values = orderedMetrics.map { metric ->
                                val value: Double
                                val hasData: Boolean
                                when (metric) {
                                    BusinessCalendarMetric.REVENUE -> {
                                        value =
                                            if (selectedPartnerIds.isEmpty()) {
                                                visibleRecords.sumOf { it.revenue }
                                            } else {
                                                visibleRecords.sumOf { record ->
                                                    selectedPartnerIds.sumOf { id ->
                                                        record.receiptAmountForPartner(id)
                                                    }
                                                }
                                            }
                                        hasData =
                                            if (selectedPartnerIds.isEmpty()) {
                                                visibleRecords.isNotEmpty()
                                            } else {
                                                kotlin.math.abs(value) > 0.005
                                            }
                                    }

                                    BusinessCalendarMetric.PURCHASE -> {
                                        val rows =
                                            purchaseDetailsByDate[date]
                                                .orEmpty()
                                                .filter { detail ->
                                                    selectedPartnerIds.isEmpty() ||
                                                        detail.order.buyerId in selectedPartnerIds
                                                }
                                        value = rows.sumOf { it.order.totalCost }
                                        hasData = rows.isNotEmpty() && hasSelectedStoreBusiness
                                    }

                                    BusinessCalendarMetric.PROFIT -> {
                                        if (selectedPartnerIds.isEmpty()) {
                                            value =
                                                if (selectedStoreName == null) {
                                                    dailySummaryByDate[date]?.profit ?: 0.0
                                                } else {
                                                    visibleRecords.sumOf { it.profit }
                                                }
                                            hasData = visibleRecords.isNotEmpty()
                                        } else {
                                            val rows =
                                                effectiveProfitSharesByDate[date]
                                                    .orEmpty()
                                                    .filter { it.partnerId in selectedPartnerIds }
                                            value = rows.sumOf { it.amount }
                                            hasData = rows.isNotEmpty()
                                        }
                                    }
                                }
                                Triple(metric, value, hasData)
                            }

                            val anyData = values.any { it.third }

                            Column(
                                Modifier
                                    .weight(1f)
                                    .padding(1.dp)
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(
                                        if (anyData) Color(0xFFF8FAFC) else Color.Transparent
                                    )
                                    .padding(horizontal = 2.dp, vertical = 3.dp)
                                    .height(cellHeight),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(1.dp)
                            ) {
                                Text(
                                    day.toString(),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight =
                                        if (anyData) FontWeight.SemiBold else FontWeight.Normal
                                )

                                values.forEach { (metric, value, hasData) ->
                                    val valueColor =
                                        when (metric) {
                                            BusinessCalendarMetric.REVENUE -> Color(0xFF1976D2)
                                            BusinessCalendarMetric.PURCHASE -> Color(0xFFF9A825)
                                            BusinessCalendarMetric.PROFIT ->
                                                if (value < -0.005) {
                                                    MaterialTheme.colorScheme.error
                                                } else {
                                                    BrandGreen
                                                }
                                        }
                                    Text(
                                        if (hasData) fmt(value) else "",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = valueColor,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BackupContent(
    db: AppDatabase
) {
    val context = LocalContext.current
    var message by remember { mutableStateOf("") }
    var pendingBackupJson by remember { mutableStateOf<String?>(null) }

    val exportLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) { uri ->
            val json = pendingBackupJson
            pendingBackupJson = null
            if (uri == null || json == null) return@rememberLauncherForActivityResult

            runCatching {
                context.contentResolver.openOutputStream(uri, "w")
                    ?.bufferedWriter(Charsets.UTF_8)
                    ?.use { writer ->
                        writer.write(json)
                        writer.flush()
                    }
                    ?: throw IllegalStateException("无法打开保存位置")
            }.onSuccess {
                message = "备份已导出"
            }.onFailure { error ->
                message = "导出失败：${error.message ?: "未知错误"}"
            }
        }

    fun backupFileName(): String =
        "天鲜果业备份_${LocalDate.now()}_${System.currentTimeMillis()}.json"

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFFF5FAF7)
            )
        ) {
            Column(
                Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    "数据备份",
                    fontWeight = FontWeight.Bold,
                    color = BrandGreen
                )
                Text(
                    "导出当前账本的 JSON 完整数据。备份与云同步互不影响；导出失败只会提示错误，不会退出 APP。",
                    color = Color.DarkGray,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Button(
            onClick = {
                runCatching {
                    val json = db.exportJson()
                    ReportGenerator.saveTextToDownloads(
                        context = context,
                        displayName = backupFileName(),
                        content = json
                    )
                }.onSuccess { path ->
                    message = "备份成功：$path"
                }.onFailure { error ->
                    message = "备份失败：${error.message ?: "未知错误"}"
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("立即备份")
        }

        OutlinedButton(
            onClick = {
                runCatching {
                    val json = db.exportJson()
                    pendingBackupJson = json
                    exportLauncher.launch(backupFileName())
                }.onFailure { error ->
                    pendingBackupJson = null
                    message = "打开保存位置失败：${error.message ?: "未知错误"}"
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("另存为…")
        }

        Text(
            "默认保存到 Download/TianXianFruit/Backup；“另存为”可自行选择位置。",
            color = Color.Gray,
            style = MaterialTheme.typography.bodySmall
        )

        if (message.isNotBlank()) {
            Text(
                message,
                color =
                    if (message.contains("失败")) {
                        MaterialTheme.colorScheme.error
                    } else {
                        BrandGreen
                    }
            )
        }
    }
}

@Composable
private fun PartnerContent(db: AppDatabase, dataVersion: Int, onChanged: () -> Unit) {
    val partners = remember(dataVersion) { db.getPartners() }
    var addDialog by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf<PartnerOption?>(null) }
    var delete by remember { mutableStateOf<PartnerOption?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("当前 ${partners.size} 位合伙人，人数不设上限。删除只代表今后不再参与新记录；历史采购、营业、利润和结算仍保留原 ID 与当时姓名，并可继续编辑。", color = Color.Gray) }
        items(partners, key = { it.id }) { p ->
            RecordCard {
                Text(p.name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                TextButton(onClick = { edit = p }) { Text("改名") }
                TextButton(onClick = { delete = p }) { Text("删除") }
            }
        }
        item { Button(onClick = { addDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("＋ 添加合伙人") } }
    }
    if (addDialog) PartnerNameDialog("添加合伙人", "", { addDialog = false }) { name ->
        db.addPartner(name); addDialog = false; onChanged()
    }
    edit?.let { p ->
        PartnerNameDialog("修改合伙人名称", p.name, { edit = null }) { name ->
            db.updatePartnerName(p.id, name); edit = null; onChanged()
        }
    }
    delete?.let { p -> ConfirmDelete("删除合伙人“${p.name}”？删除后不再出现在新记录选择列表中；已有采购、营业、利润分配和结算历史继续保留，并可编辑原历史记录。", { delete = null }) {
        db.deletePartner(p.id); delete = null; onChanged()
    } }
}

@Composable
private fun SortArrowButtons(
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    Row(
        horizontalArrangement =
            Arrangement.spacedBy(0.dp),
        verticalAlignment =
            Alignment.CenterVertically
    ) {
        TextButton(
            onClick = onMoveUp,
            enabled = canMoveUp,
            modifier =
                Modifier.width(38.dp),
            contentPadding =
                PaddingValues(0.dp)
        ) {
            Text(
                "↑",
                fontSize = 20.sp,
                fontWeight =
                    FontWeight.Bold
            )
        }

        TextButton(
            onClick = onMoveDown,
            enabled = canMoveDown,
            modifier =
                Modifier.width(38.dp),
            contentPadding =
                PaddingValues(0.dp)
        ) {
            Text(
                "↓",
                fontSize = 20.sp,
                fontWeight =
                    FontWeight.Bold
            )
        }
    }
}

@Composable
private fun FruitManagementContent(
    db: AppDatabase,
    dataVersion: Int,
    onChanged: () -> Unit
) {
    val fruits =
        remember(
            dataVersion
        ) {
            db.getFruitAdminRecords()
        }

    var orderedFruits by remember(
        dataVersion
    ) {
        mutableStateOf(
            fruits
        )
    }

    var addDialog by remember {
        mutableStateOf(false)
    }

    var editFruit by remember {
        mutableStateOf<
            FruitAdminRecord?
        >(null)
    }

    var disableFruit by remember {
        mutableStateOf<
            FruitAdminRecord?
        >(null)
    }

    var message by remember {
        mutableStateOf("")
    }

    fun moveFruit(
        fruitId: Long,
        direction: Int
    ) {
        val from =
            orderedFruits
                .indexOfFirst {
                    it.id ==
                        fruitId
                }

        if (from < 0) {
            return
        }

        val to =
            from +
                direction

        if (
            to !in
            orderedFruits.indices
        ) {
            return
        }

        val current =
            orderedFruits[from]
        val target =
            orderedFruits[to]

        if (
            !current.enabled ||
            !target.enabled
        ) {
            return
        }

        val reordered =
            orderedFruits
                .toMutableList()
                .apply {
                    this[from] =
                        target
                    this[to] =
                        current
                }

        val activeIds =
            reordered
                .filter {
                    it.enabled
                }
                .map {
                    it.id
                }

        if (
            db.reorderFruits(
                activeIds
            )
        ) {
            orderedFruits =
                reordered
            message =
                "商品顺序已调整"
            onChanged()
        } else {
            message =
                "商品排序保存失败"
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding =
            PaddingValues(
                16.dp
            ),
        verticalArrangement =
            Arrangement.spacedBy(
                7.dp
            )
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment =
                    Alignment.CenterVertically
            ) {
                Text(
                    "点击 ↑ / ↓ 调整商品顺序，修改后立即保存",
                    modifier =
                        Modifier.weight(
                            1f
                        ),
                    color = Color.Gray,
                    style =
                        MaterialTheme
                            .typography
                            .bodySmall
                )
            }
        }

        items(
            orderedFruits,
            key = {
                it.id
            }
        ) {
            f ->
            RecordCard {
                val fruitIndex =
                    orderedFruits
                        .indexOfFirst {
                            it.id == f.id
                        }

                SortArrowButtons(
                    canMoveUp =
                        f.enabled &&
                            fruitIndex > 0 &&
                            orderedFruits[
                                fruitIndex - 1
                            ].enabled,
                    canMoveDown =
                        f.enabled &&
                            fruitIndex >= 0 &&
                            fruitIndex <
                                orderedFruits
                                    .lastIndex &&
                            orderedFruits[
                                fruitIndex + 1
                            ].enabled,
                    onMoveUp = {
                        moveFruit(
                            f.id,
                            -1
                        )
                    },
                    onMoveDown = {
                        moveFruit(
                            f.id,
                            1
                        )
                    }
                )

                Column(
                    Modifier.weight(
                        1f
                    )
                ) {
                    Row(
                        verticalAlignment =
                            Alignment.CenterVertically
                    ) {
                        Text(
                            f.name,
                            fontWeight =
                                FontWeight.Bold
                        )

                        if (!f.enabled) {
                            Spacer(
                                Modifier.width(
                                    6.dp
                                )
                            )

                            Text(
                                "已停用",
                                color = Color.Gray,
                                style =
                                    MaterialTheme
                                        .typography
                                        .labelSmall
                            )
                        }
                    }

                    Text(
                        "默认单位：${f.defaultUnit}",
                        style =
                            MaterialTheme
                                .typography
                                .bodySmall,
                        color = Color.Gray
                    )
                }

                if (f.enabled) {
                    if (f.name == "总价") {
                        Text(
                            "系统项",
                            color = Color.Gray,
                            style = MaterialTheme.typography.labelSmall
                        )
                    } else {
                        TextButton(
                            onClick = {
                                editFruit = f
                            }
                        ) {
                            Text("编辑")
                        }

                        TextButton(
                            onClick = {
                                disableFruit = f
                            }
                        ) {
                            Text("删除")
                        }
                    }
                } else {
                    TextButton(
                        onClick = {
                            if (
                                db.restoreFruit(
                                    f.id
                                )
                            ) {
                                message =
                                    "${f.name} 已恢复"
                                onChanged()
                            }
                        }
                    ) {
                        Text("恢复")
                    }
                }
            }
        }

        item {
            Button(
                onClick = {
                    addDialog = true
                },
                modifier =
                    Modifier.fillMaxWidth()
            ) {
                Text(
                    "＋ 新增商品"
                )
            }

            if (
                message.isNotBlank()
            ) {
                Text(
                    message,
                    color =
                        if (
                            message.contains(
                                "失败"
                            )
                        ) {
                            MaterialTheme
                                .colorScheme
                                .error
                        } else {
                            BrandGreen
                        },
                    style =
                        MaterialTheme
                            .typography
                            .bodySmall
                )
            }
        }
    }

    if (addDialog) {
        AddFruitDialog(
            onDismiss = {
                addDialog = false
            },
            onSave = {
                name,
                unit ->
                val id =
                    db.addFruit(
                        name,
                        unit
                    )

                addDialog = false

                message =
                    if (id > 0) {
                        "商品已保存"
                    } else {
                        "保存失败，商品名称可能重复"
                    }

                onChanged()
            }
        )
    }

    editFruit?.let {
        f ->
        FruitEditDialog(
            fruit = f,
            onDismiss = {
                editFruit = null
            },
            onSave = {
                name,
                unit ->
                val ok =
                    db.updateFruit(
                        f.id,
                        name,
                        unit
                    )

                message =
                    if (ok) {
                        "商品已修改"
                    } else {
                        "修改失败"
                    }

                editFruit = null
                onChanged()
            }
        )
    }

    disableFruit?.let {
        f ->
        ConfirmDelete(
            "停用“${f.name}”？它将不再出现在新的采购列表中，历史记录不会删除。",
            {
                disableFruit = null
            }
        ) {
            if (
                db.disableFruit(
                    f.id
                )
            ) {
                message =
                    "${f.name} 已停用"
                onChanged()
            }

            disableFruit = null
        }
    }
}

@Composable
private fun StoreContent(
    db: AppDatabase,
    dataVersion: Int,
    onChanged: () -> Unit
) {
    val stores =
        remember(
            dataVersion
        ) {
            db.getStores()
        }

    var orderedStores by remember(
        dataVersion
    ) {
        mutableStateOf(
            stores
        )
    }

    var addDialog by remember {
        mutableStateOf(false)
    }

    var delete by remember {
        mutableStateOf<
            StoreOption?
        >(null)
    }
    var editStore by remember { mutableStateOf<StoreOption?>(null) }
    var weekdayMenu by remember { mutableStateOf<Int?>(null) }

    var message by remember {
        mutableStateOf("")
    }

    fun moveStore(
        storeId: Long,
        direction: Int
    ) {
        val from =
            orderedStores
                .indexOfFirst {
                    it.id ==
                        storeId
                }

        if (from < 0) {
            return
        }

        val to =
            from +
                direction

        if (
            to !in
            orderedStores.indices
        ) {
            return
        }

        val reordered =
            orderedStores
                .toMutableList()
                .apply {
                    val current =
                        this[from]
                    this[from] =
                        this[to]
                    this[to] =
                        current
                }

        if (
            db.reorderStores(
                reordered.map {
                    it.id
                }
            )
        ) {
            orderedStores =
                reordered
            message =
                "位置顺序已调整"
            onChanged()
        } else {
            message =
                "位置排序保存失败"
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding =
            PaddingValues(
                16.dp
            ),
        verticalArrangement =
            Arrangement.spacedBy(
                7.dp
            )
    ) {
        item {
            Text(
                "点击 ↑ / ↓ 调整位置顺序；天气功能需要为位置绑定经纬度。",
                color = Color.Gray,
                style = MaterialTheme.typography.bodySmall
            )
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF7FAF8))) {
                Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("星期固定位置", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                    Text("未固定的星期会按“同星期历史 → 最近营业位置”自动推断。", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    val base = LocalDate.now()
                    (1..7).forEach { weekday ->
                        val fixed = db.getWeekdayWeatherStore(weekday)
                        val target = base.plusDays(((weekday - base.dayOfWeek.value + 7) % 7).toLong())
                        val inferred = if (fixed == null) db.resolveWeatherStore(target.toString()) else null
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("星期${listOf("一","二","三","四","五","六","日")[weekday - 1]}", modifier = Modifier.width(54.dp), style = MaterialTheme.typography.labelSmall)
                            Text(
                                fixed?.name ?: inferred?.store?.name ?: "暂无规律",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (fixed != null) BrandGreen else Color.DarkGray
                            )
                            Box {
                                TextButton(onClick = { weekdayMenu = weekday }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                                    Text(if (fixed != null) "固定 ▾" else "自动 ▾", fontSize = 11.sp)
                                }
                                DropdownMenu(expanded = weekdayMenu == weekday, onDismissRequest = { weekdayMenu = null }) {
                                    DropdownMenuItem(
                                        text = { Text("自动推断") },
                                        onClick = {
                                            db.setWeekdayWeatherStore(weekday, null)
                                            weekdayMenu = null
                                            onChanged()
                                        }
                                    )
                                    stores.forEach { option ->
                                        DropdownMenuItem(
                                            text = { Text(option.name) },
                                            onClick = {
                                                db.setWeekdayWeatherStore(weekday, option.id)
                                                weekdayMenu = null
                                                onChanged()
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        items(
            orderedStores,
            key = {
                it.id
            }
        ) {
            s ->
            RecordCard {
                val storeIndex =
                    orderedStores
                        .indexOfFirst {
                            it.id == s.id
                        }

                SortArrowButtons(
                    canMoveUp =
                        storeIndex > 0,
                    canMoveDown =
                        storeIndex >= 0 &&
                            storeIndex <
                                orderedStores
                                    .lastIndex,
                    onMoveUp = {
                        moveStore(
                            s.id,
                            -1
                        )
                    },
                    onMoveDown = {
                        moveStore(
                            s.id,
                            1
                        )
                    }
                )

                Column(
                    Modifier.weight(
                        1f
                    )
                ) {
                    Text(
                        s.name,
                        fontWeight =
                            FontWeight.Bold
                    )

                    if (s.address.isNotBlank()) {
                        Text(
                            s.address,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    }
                    Text(
                        "默认营业 ${s.defaultStartTime}–${s.defaultEndTime}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.DarkGray
                    )
                    Text(
                        if (s.latitude != null && s.longitude != null)
                            "天气定位 ${String.format(Locale.CHINA, "%.4f", s.latitude)}, ${String.format(Locale.CHINA, "%.4f", s.longitude)}"
                        else "天气定位：未绑定经纬度",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (s.latitude != null && s.longitude != null) BrandGreen else Color(0xFFB26A00)
                    )
                }

                TextButton(onClick = { editStore = s }) { Text("编辑") }
                TextButton(onClick = { delete = s }) { Text("删除") }
            }
        }

        item {
            Button(
                onClick = {
                    addDialog = true
                },
                modifier =
                    Modifier.fillMaxWidth()
            ) {
                Text(
                    "＋ 新增位置"
                )
            }

            if (
                message.isNotBlank()
            ) {
                Text(
                    message,
                    color =
                        if (
                            message.contains(
                                "失败"
                            )
                        ) {
                            MaterialTheme
                                .colorScheme
                                .error
                        } else {
                            BrandGreen
                        },
                    style =
                        MaterialTheme
                            .typography
                            .bodySmall
                )
            }
        }
    }

    if (addDialog) {
        AddStoreDialog(
            onDismiss = { addDialog = false },
            initial = null
        ) { name, address, latitude, longitude, startTime, endTime ->
            val id = db.addStore(name, address, latitude, longitude, startTime, endTime)

            addDialog = false
            message =
                if (id > 0) {
                    "位置已保存"
                } else {
                    "保存失败，位置名称可能重复"
                }
            onChanged()
        }
    }

    editStore?.let { store ->
        AddStoreDialog(
            onDismiss = { editStore = null },
            initial = store
        ) { name, address, latitude, longitude, startTime, endTime ->
            val ok = db.updateStore(store.id, name, address, latitude, longitude, startTime, endTime)
            message = if (ok) "位置、营业时间和天气坐标已更新" else "位置修改失败"
            editStore = null
            if (ok) onChanged()
        }
    }

    delete?.let {
        s ->
        ConfirmDelete(
            "删除位置“${s.name}”？历史营业和采购记录不会被删除。",
            {
                delete = null
            }
        ) {
            db.deleteStore(
                s.id
            )
            delete = null
            onChanged()
        }
    }
}

@Composable
private fun ProfitContent(
    db: AppDatabase,
    dataVersion: Int,
    canEdit: Boolean,
    protectHistoricalAction:
        (String, String, () -> Unit) -> Unit,
    onChanged: () -> Unit
) {
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    val partners = remember(dataVersion) { db.getPartners() }
    val savedRules = remember(dataVersion) { db.getProfitRules() }
    val savedCenter = remember(dataVersion) { db.getSettlementCenter() }
    val summary = remember(dataVersion, date) { db.getDailySummary(date) }
    val percentages = remember { mutableStateMapOf<Long, String>() }
    var settlementCenterId by remember {
        mutableStateOf<Long?>(null)
    }
    var message by remember { mutableStateOf("") }
    var deleteDate by remember { mutableStateOf<String?>(null) }
    var clearSettlementPartner by remember {
        mutableStateOf<PartnerProfitSettlementSummary?>(null)
    }
    val settlementCorrectionStats = remember(dataVersion) {
        db.getPartnerProfitSettlementSummary(null, null)
            .filter { it.settledProfit > 0.005 }
    }
    val saved = remember(dataVersion, date) { db.getProfitDistribution(date) }
    val history = remember(dataVersion) { db.getRecentProfitDistributions(200).groupBy { it.date }.toSortedMap(reverseOrder()) }

    LaunchedEffect(dataVersion, partners.map { it.id }) {
        if (
            settlementCenterId == null ||
            partners.none { it.id == settlementCenterId }
        ) {
            settlementCenterId =
                savedCenter
                    ?.id
                    ?.takeIf { centerId ->
                        partners.any { it.id == centerId }
                    }
                    ?: partners.firstOrNull()?.id
        }

        val ruleMap = savedRules.associate { it.partnerId to it.percent }
        percentages.keys.retainAll(partners.map { it.id }.toSet())
        if (ruleMap.isNotEmpty()) {
            partners.forEach { p -> percentages[p.id] = cleanPercent(ruleMap[p.id] ?: 0.0) }
        } else if (partners.size == 4) {
            val defaults = listOf(33.0, 13.4, 26.8, 26.8)
            partners.forEachIndexed { index, p -> percentages[p.id] = cleanPercent(defaults[index]) }
        } else if (partners.isNotEmpty()) {
            val equal = 100.0 / partners.size
            var used = 0.0
            partners.forEachIndexed { index, p ->
                val value = if (index == partners.lastIndex) 100.0 - used else roundPercent(equal)
                percentages[p.id] = cleanPercent(value)
                used += value
            }
        }
    }

    val totalPercent = partners.sumOf { percentages[it.id]?.toDoubleOrNull() ?: 0.0 }
    val allocations = partners.map { p -> p to (percentages[p.id]?.toDoubleOrNull() ?: 0.0) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { DateField("分配日期", date) { date = it } }
        item {
            MetricCard(
                if (summary.profit < 0) "当天亏损" else "当天可分利润",
                money(summary.profit),
                Modifier.fillMaxWidth(),
                SoftOrange
            )
        }
        item {
            Text("分配百分比", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("当前合计：${String.format(Locale.CHINA, "%.2f", totalPercent)}%（必须等于100%）", color = if (kotlin.math.abs(totalPercent - 100.0) < 0.01) BrandGreen else MaterialTheme.colorScheme.error)
        }
        items(partners, key = { "rule${it.id}" }) { p ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(p.name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = percentages[p.id] ?: "",
                    onValueChange = {
                        v ->
                        if (
                            canEdit &&
                            v.matches(
                                Regex(
                                    "^\\d*(\\.\\d{0,2})?$"
                                )
                            )
                        ) {
                            percentages[p.id] = v
                        }
                    },
                    enabled = canEdit,
                    modifier = Modifier.width(110.dp).height(50.dp),
                    suffix = { Text("%") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    textStyle = MaterialTheme.typography.bodyMedium
                )
            }
        }
        item {
            Text(
                "资金中心",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                partners.forEach { partner ->
                    if (settlementCenterId == partner.id) {
                        Button(
                            onClick = {
                                if (canEdit) {
                                    settlementCenterId = partner.id
                                }
                            },
                            enabled = canEdit,
                            contentPadding =
                                PaddingValues(horizontal = 14.dp)
                        ) {
                            Text(partner.name)
                        }
                    } else {
                        OutlinedButton(
                            onClick = {
                                if (canEdit) {
                                    settlementCenterId = partner.id
                                }
                            },
                            enabled = canEdit,
                            contentPadding =
                                PaddingValues(horizontal = 14.dp)
                        ) {
                            Text(partner.name)
                        }
                    }
                }
            }
            Text(
                "所有实际资金转账统一经过资金中心，非资金中心合伙人之间不直接转账。",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
        }
        if (canEdit) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        if (partners.isEmpty()) message = "请先添加合伙人"
                        else if (kotlin.math.abs(totalPercent - 100.0) >= 0.01) message = "百分比合计必须等于100%"
                        else {
                            protectHistoricalAction(
                                date,
                                "修改利润分配规则和资金中心"
                            ) {
                                db.saveProfitRules(
                                    allocations,
                                    settlementCenterId
                                )
                                message =
                                    "利润分配规则和资金中心已保存"
                                onChanged()
                            }
                        }
                    }, modifier = Modifier.weight(1f)) { Text("保存百分比规则") }
                    Button(onClick = {
                        if (kotlin.math.abs(summary.profit) <= 0.005) message = "当天利润为0，无需生成分配"
                        else if (partners.isEmpty()) message = "请先添加合伙人"
                        else if (kotlin.math.abs(totalPercent - 100.0) >= 0.01) message = "百分比合计必须等于100%"
                        else {
                            val saveAction = {
                                val ok = db.saveProfitDistribution(date, allocations)
                                message = if (ok) "利润分配表已独立保存" else "保存失败"
                                if (ok) onChanged()
                            }
                            if (saved.isNotEmpty()) {
                                protectHistoricalAction(
                                    date,
                                    "修改利润分配 $date"
                                ) {
                                    saveAction()
                                }
                            } else {
                                saveAction()
                            }
                        }
                    }, modifier = Modifier.weight(1f)) { Text("保存当日分配") }
                }
                if (message.isNotBlank()) Text(message, color = BrandGreen, modifier = Modifier.padding(top = 6.dp))
            }        } else {
            item {
                Text(
                    "当前成员为只读权限。",
                    color = Color.Gray,
                    style =
                        MaterialTheme
                            .typography
                            .bodySmall
                )
            }
        }

        if (kotlin.math.abs(summary.profit) > 0.005 && partners.isNotEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = SoftGreen)) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            if (summary.profit < 0) "亏损分担预览" else "分配预览",
                            fontWeight = FontWeight.Bold
                        )
                        allocations.forEach { (p, pct) -> SummaryRow("${p.name} · ${cleanPercent(pct)}%", money(summary.profit * pct / 100.0)) }
                    }
                }
            }
        }
        if (saved.isNotEmpty()) {
            item {
                RecordCard {
                    Column(Modifier.weight(1f)) {
                        Text("当前日期已保存", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("利润基数：${money(saved.first().sourceProfit)}", style = MaterialTheme.typography.bodySmall)
                        saved.forEach { r -> Text("${r.partnerName} · ${cleanPercent(r.ratio * 100)}%：${money(r.allocatedProfit)}", style = MaterialTheme.typography.bodySmall) }
                    }
                    if (canEdit) {
                        TextButton(
                            onClick = {
                                protectHistoricalAction(
                                    date,
                                    "删除利润分配 $date"
                                ) {
                                    deleteDate = date
                                }
                            }
                        ) {
                            Text("删除")
                        }
                    }
                }
            }
        }
        item { HorizontalDivider(); Text("利润分配历史", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        history.entries.take(30).forEach { (d, rows) ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("$d · 利润 ${money(rows.firstOrNull()?.sourceProfit ?: 0.0)}", fontWeight = FontWeight.Bold)
                            rows.forEach { r -> Text("${r.partnerName} ${cleanPercent(r.ratio * 100)}%：${money(r.allocatedProfit)}", style = MaterialTheme.typography.bodySmall) }
                        }
                        if (canEdit) {
                            TextButton(
                                onClick = {
                                    protectHistoricalAction(
                                        d,
                                        "删除利润分配 $d"
                                    ) {
                                        deleteDate = d
                                    }
                                }
                            ) {
                                Text("删除")
                            }
                        }
                    }
                }
            }
        }
        if (canEdit && settlementCorrectionStats.isNotEmpty()) {
            item {
                HorizontalDivider()
                Text(
                    "利润结算纠错",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "这里只处理明确的利润结算记录，不会修改利润分配、当日结算或资金余额。仅在确认历史利润被误标为已结算时使用。",
                    color = Color.Gray,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            settlementCorrectionStats.forEach { stat ->
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(stat.partnerName, fontWeight = FontWeight.Bold)
                                Text(
                                    "已记录结算利润 ${money(stat.settledProfit)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.Gray
                                )
                            }
                            TextButton(
                                onClick = { clearSettlementPartner = stat }
                            ) {
                                Text("撤销历史结算")
                            }
                        }
                    }
                }
            }
        }
    }
    if (canEdit) {
        clearSettlementPartner?.let { stat ->
            ConfirmActionDialog(
                title = "撤销 ${stat.partnerName} 的历史利润结算？",
                text = "这会把 ${stat.partnerName} 当前所有利润结算明细撤销为未结算，只用于纠正误标数据；利润分配和当日结算不会改变。",
                confirmText = "确认撤销",
                onDismiss = { clearSettlementPartner = null }
            ) {
                protectHistoricalAction(
                    "2000-01-01",
                    "撤销历史利润结算 ${stat.partnerName}"
                ) {
                    val count = db.clearProfitSettlementsForPartner(stat.partnerId)
                    message =
                        if (count > 0) {
                            "已撤销 ${stat.partnerName} 的 $count 条利润结算明细"
                        } else {
                            "没有找到可撤销的利润结算记录"
                        }
                    clearSettlementPartner = null
                    onChanged()
                }
            }
        }
        deleteDate?.let { d ->
            ConfirmDelete("删除 $d 的整张利润分配历史？不会删除当天营业和总账。", { deleteDate = null }) {
            db.deleteProfitDistribution(d); deleteDate = null; onChanged()
            }
        }
    }
}

private fun roleLabel(role: String): String = when (role) {
    "CUSTOM_PERCENT" -> "自定义比例"
    "PRIMARY_33" -> "33%"
    "SMALL_1" -> "小份1"
    "LARGE_2" -> "大份2"
    else -> role
}

@Composable
private fun RankingSection(title: String, rankings: List<RankingRecord>, value: (RankingRecord) -> String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (rankings.isEmpty()) Text("暂无数据", color = Color.Gray)
        rankings.take(10).forEachIndexed { i, r ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}.", Modifier.width(28.dp), fontWeight = FontWeight.Bold)
                Column(Modifier.weight(1f)) { Text(r.storeName); Text("${r.days} 个营业日", style = MaterialTheme.typography.bodySmall, color = Color.Gray) }
                Text(value(r), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, Modifier.weight(1f), fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
        Text(value, fontWeight = if (bold) FontWeight.Bold else FontWeight.SemiBold)
    }
}

@Composable
private fun SelectButton(label: String, value: String, modifier: Modifier = Modifier, expanded: Boolean, onExpanded: (Boolean) -> Unit) {
    OutlinedButton(onClick = { onExpanded(!expanded) }, modifier = modifier) {
        Column(Modifier.weight(1f)) { Text(label, style = MaterialTheme.typography.labelSmall, color = Color.Gray); Text(value, maxLines = 1) }
        Text("▼")
    }
}

@Composable
private fun DateField(label: String, date: String, onDate: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val parsed = runCatching { LocalDate.parse(date) }.getOrNull()
    val display = date + (parsed?.let { "  ${chineseWeekday(it)}" } ?: "")
    OutlinedButton(
        onClick = { showDatePicker(context, date, onDate) },
        modifier = Modifier.fillMaxWidth().height(48.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Text(
            "$label：$display",
            Modifier.weight(1f),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text("📅", fontSize = 14.sp)
    }
}

private fun showDatePicker(context: Context, current: String, onDate: (String) -> Unit) {
    val d = runCatching { LocalDate.parse(current) }.getOrDefault(LocalDate.now())
    DatePickerDialog(context, { _, y, m, day -> onDate(LocalDate.of(y, m + 1, day).toString()) }, d.year, d.monthValue - 1, d.dayOfMonth).show()
}

@Composable
private fun DefaultNumberField(
    label: String,
    value: String,
    defaultValue: String,
    stateKey: String,
    onValue: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var fieldValue by remember(stateKey) {
        mutableStateOf(
            TextFieldValue(
                text = value,
                selection = TextRange(0, value.length)
            )
        )
    }
    var firstEditPending by remember(stateKey) { mutableStateOf(true) }

    LaunchedEffect(value) {
        if (fieldValue.text != value) {
            fieldValue =
                TextFieldValue(
                    text = value,
                    selection =
                        if (firstEditPending && value == defaultValue) {
                            TextRange(0, value.length)
                        } else {
                            TextRange(value.length)
                        }
                )
        }
    }

    OutlinedTextField(
        value = fieldValue,
        onValueChange = { next ->
            val candidate =
                if (
                    firstEditPending &&
                    fieldValue.text == defaultValue &&
                    next.text != defaultValue
                ) {
                    when {
                        next.text.length > defaultValue.length &&
                            next.text.startsWith(defaultValue) ->
                            next.text.removePrefix(defaultValue)

                        next.text.length > defaultValue.length &&
                            next.text.endsWith(defaultValue) ->
                            next.text.removeSuffix(defaultValue)

                        else -> next.text
                    }
                } else {
                    next.text
                }

            if (candidate.matches(Regex("^\\d*(\\.\\d{0,2})?$"))) {
                firstEditPending = false
                fieldValue =
                    TextFieldValue(
                        text = candidate,
                        selection = TextRange(candidate.length)
                    )
                onValue(candidate)
            }
        },
        label = { Text(label) },
        modifier =
            modifier.onFocusChanged { state ->
                if (
                    state.isFocused &&
                    firstEditPending &&
                    fieldValue.text == defaultValue
                ) {
                    fieldValue =
                        fieldValue.copy(
                            selection = TextRange(0, fieldValue.text.length)
                        )
                }
            },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    )
}

@Composable
private fun NumberField(label: String, value: String, onValue: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value, onValueChange = { if (it.matches(Regex("^\\d*(\\.\\d{0,2})?$"))) onValue(it) },
        label = { Text(label) }, modifier = modifier, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    )
}

@Composable
private fun IntegerField(label: String, value: String, onValue: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value, onValueChange = { if (it.all(Char::isDigit)) onValue(it) },
        label = { Text(label) }, modifier = modifier, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
    )
}

@Composable
private fun CollectorSelector(partners: List<PartnerOption>, selectedId: Long?, onSelected: (Long?) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth()) {
            Text("收款归属", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
            Spacer(Modifier.width(10.dp))
            Text(partners.firstOrNull { it.id == selectedId }?.name ?: "未指定", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            Text("▼")
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            partners.forEach { p -> DropdownMenuItem(text = { Text(p.name) }, onClick = { onSelected(p.id); menu = false }) }
            DropdownMenuItem(text = { Text("未指定") }, onClick = { onSelected(null); menu = false })
        }
    }
}

@Composable
private fun CompactSelectButton(label: String, value: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        OutlinedButton(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().height(40.dp),
            contentPadding = PaddingValues(horizontal = 9.dp, vertical = 2.dp)
        ) {
            Text(value, modifier = Modifier.weight(1f), maxLines = 1, fontSize = 13.sp)
            Text("▼", fontSize = 10.sp)
        }
    }
}

private fun resolveTimeRange(
    filter: HistoryTimeFilter,
    customStart: String,
    customEnd: String,
    today: LocalDate = LocalDate.now()
): Pair<String?, String?> =
    when (filter) {
        HistoryTimeFilter.ALL ->
            null to null

        HistoryTimeFilter.TODAY ->
            today.toString() to today.toString()

        HistoryTimeFilter.YESTERDAY -> {
            val d = today.minusDays(1)
            d.toString() to d.toString()
        }

        HistoryTimeFilter.LAST_7 ->
            today.minusDays(6).toString() to
                today.toString()

        HistoryTimeFilter.LAST_30 ->
            today.minusDays(29).toString() to
                today.toString()

        HistoryTimeFilter.LAST_90 ->
            today.minusDays(89).toString() to
                today.toString()

        HistoryTimeFilter.THIS_MONTH ->
            today.withDayOfMonth(1).toString() to
                today.toString()

        HistoryTimeFilter.LAST_MONTH -> {
            val firstThisMonth =
                today.withDayOfMonth(1)
            val firstLastMonth =
                firstThisMonth.minusMonths(1)
            firstLastMonth.toString() to
                firstThisMonth
                    .minusDays(1)
                    .toString()
        }

        HistoryTimeFilter.CUSTOM ->
            customStart to customEnd
    }

@Composable
private fun TimeFilterSelector(
    filter: HistoryTimeFilter,
    onFilterChange: (HistoryTimeFilter) -> Unit,
    customStart: String,
    onCustomStart: (String) -> Unit,
    customEnd: String,
    onCustomEnd: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var menu by remember {
        mutableStateOf(false)
    }

    val range =
        resolveTimeRange(
            filter,
            customStart,
            customEnd
        )

    Column(
        modifier,
        verticalArrangement =
            Arrangement.spacedBy(6.dp)
    ) {
        Box(Modifier.fillMaxWidth()) {
            CompactSelectButton(
                "时间范围",
                filter.label,
                Modifier.fillMaxWidth()
            ) {
                menu = true
            }

            DropdownMenu(
                expanded = menu,
                onDismissRequest = {
                    menu = false
                }
            ) {
                HistoryTimeFilter.entries.forEach {
                    option ->
                    DropdownMenuItem(
                        text = {
                            Text(option.label)
                        },
                        onClick = {
                            onFilterChange(option)
                            menu = false
                        }
                    )
                }
            }
        }

        if (filter == HistoryTimeFilter.CUSTOM) {
            Row(
                horizontalArrangement =
                    Arrangement.spacedBy(8.dp)
            ) {
                CompactDateSelector(
                    "开始日期",
                    customStart,
                    Modifier.weight(1f),
                    onDate = onCustomStart
                )
                CompactDateSelector(
                    "结束日期",
                    customEnd,
                    Modifier.weight(1f),
                    onDate = onCustomEnd
                )
            }
        } else if (
            range.first != null &&
            range.second != null
        ) {
            Text(
                "${range.first} ～ ${range.second}",
                color = Color.Gray,
                style =
                    MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun QuickDatePickerDialog(
    selectedDate: LocalDate,
    onDismiss: () -> Unit,
    onSelect: (LocalDate) -> Unit
) {
    var visibleMonth by remember(selectedDate) {
        mutableStateOf(selectedDate.withDayOfMonth(1))
    }
    val today = LocalDate.now()
    val weekLabels = listOf("一", "二", "三", "四", "五", "六", "日")
    val firstOffset = visibleMonth.dayOfWeek.value - 1
    val daysInMonth = visibleMonth.lengthOfMonth()
    val cellCount = ((firstOffset + daysInMonth + 6) / 7) * 7

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        title = {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { visibleMonth = visibleMonth.minusMonths(1) },
                    modifier = Modifier.size(38.dp)
                ) {
                    Text("‹", fontSize = 28.sp, color = BrandGreen)
                }
                Text(
                    visibleMonth.format(DateTimeFormatter.ofPattern("yyyy年M月")),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                IconButton(
                    onClick = { visibleMonth = visibleMonth.plusMonths(1) },
                    modifier = Modifier.size(38.dp)
                ) {
                    Text("›", fontSize = 28.sp, color = BrandGreen)
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    weekLabels.forEach { label ->
                        Text(
                            label,
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.Gray
                        )
                    }
                }

                repeat(cellCount / 7) { rowIndex ->
                    Row(Modifier.fillMaxWidth()) {
                        repeat(7) { columnIndex ->
                            val index = rowIndex * 7 + columnIndex
                            val dayNumber = index - firstOffset + 1
                            val cellDate =
                                if (dayNumber in 1..daysInMonth) {
                                    visibleMonth.withDayOfMonth(dayNumber)
                                } else {
                                    null
                                }
                            val selected = cellDate == selectedDate
                            val isToday = cellDate == today
                            val cellModifier =
                                Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .padding(2.dp)
                                    .clip(RoundedCornerShape(9.dp))
                                    .then(
                                        when {
                                            selected -> Modifier.background(BrandGreen)
                                            isToday -> Modifier.background(SoftGreen)
                                            else -> Modifier
                                        }
                                    )
                                    .clickable(enabled = cellDate != null) {
                                        cellDate?.let(onSelect)
                                    }

                            Box(
                                cellModifier,
                                contentAlignment = Alignment.Center
                            ) {
                                if (cellDate != null) {
                                    Text(
                                        cellDate.dayOfMonth.toString(),
                                        color = if (selected) Color.White else Color.DarkGray,
                                        fontWeight =
                                            if (selected || isToday) FontWeight.Bold
                                            else FontWeight.Normal
                                    )
                                }
                            }
                        }
                    }
                }

                TextButton(
                    onClick = { onSelect(today) },
                    modifier = Modifier.align(Alignment.End),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text("今天")
                }
            }
        }
    )
}

@Composable
internal fun CompactDateNavigator(
    label: String?,
    date: String,
    modifier: Modifier = Modifier,
    chineseDisplay: Boolean = false,
    showWeekday: Boolean = false,
    onDate: (String) -> Unit
) {
    val parsedDate =
        runCatching { LocalDate.parse(date) }
            .getOrElse { LocalDate.now() }
    var showPicker by remember { mutableStateOf(false) }

    val displayText =
        if (chineseDisplay) {
            buildString {
                append(
                    parsedDate.format(
                        DateTimeFormatter.ofPattern("yyyy年M月d日")
                    )
                )
                if (showWeekday) {
                    append("  ")
                    append(chineseWeekday(parsedDate))
                }
            }
        } else {
            parsedDate.toString()
        }

    Column(modifier) {
        if (!label.isNullOrBlank()) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray
            )
        }

        Row(
            Modifier
                .fillMaxWidth()
                .pointerInput(parsedDate) {
                    var totalDrag = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { totalDrag = 0f },
                        onHorizontalDrag = { _, dragAmount ->
                            totalDrag += dragAmount
                        },
                        onDragEnd = {
                            when {
                                totalDrag <= -70f ->
                                    onDate(parsedDate.plusDays(1).toString())
                                totalDrag >= 70f ->
                                    onDate(parsedDate.minusDays(1).toString())
                            }
                        },
                        onDragCancel = { totalDrag = 0f }
                    )
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = { showPicker = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    displayText,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    fontSize = if (chineseDisplay) 16.sp else 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                Text(
                    "📅",
                    fontSize = 14.sp
                )
            }
        }
    }

    if (showPicker) {
        QuickDatePickerDialog(
            selectedDate = parsedDate,
            onDismiss = { showPicker = false },
            onSelect = { selected ->
                showPicker = false
                onDate(selected.toString())
            }
        )
    }
}

@Composable
private fun CompactDateSelector(
    label: String,
    date: String,
    modifier: Modifier = Modifier,
    showWeekday: Boolean = false,
    onDate: (String) -> Unit
) {
    val parsed = runCatching { LocalDate.parse(date) }.getOrElse { LocalDate.now() }
    var showPicker by remember { mutableStateOf(false) }
    val display =
        if (showWeekday) {
            "$date  ${chineseWeekday(parsed)}"
        } else {
            date
        }
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        OutlinedButton(
            onClick = { showPicker = true },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 3.dp)
        ) {
            Text(
                display,
                modifier = Modifier.weight(1f),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            Text("📅", fontSize = 14.sp)
        }
    }

    if (showPicker) {
        QuickDatePickerDialog(
            selectedDate = parsed,
            onDismiss = { showPicker = false },
            onSelect = { selected ->
                showPicker = false
                onDate(selected.toString())
            }
        )
    }
}

@Composable
private fun CompactNumberField(label: String, value: String, onValue: (String) -> Unit, modifier: Modifier = Modifier) {
    CompactBasicField(
        label = label,
        value = value,
        modifier = modifier,
        keyboardType = KeyboardType.Decimal,
        accept = { it.matches(Regex("^\\d*(\\.\\d{0,2})?$")) },
        onValue = onValue
    )
}

@Composable
private fun PurchaseDefaultNumberField(
    label: String,
    value: String,
    defaultValue: String,
    stateKey: String,
    onValue: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var fieldValue by remember(stateKey) {
        mutableStateOf(
            TextFieldValue(
                text = value,
                selection = TextRange(value.length)
            )
        )
    }
    var defaultSelectionPending by remember(stateKey) { mutableStateOf(true) }
    var firstEditPending by remember(stateKey) { mutableStateOf(true) }

    LaunchedEffect(value) {
        if (fieldValue.text != value) {
            fieldValue =
                TextFieldValue(
                    text = value,
                    selection = TextRange(value.length)
                )
        }
    }

    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.Gray,
            maxLines = 1
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, Color(0xFFB8BDC5), RoundedCornerShape(8.dp))
                .background(Color.White)
                .padding(horizontal = 4.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            BasicTextField(
                value = fieldValue,
                onValueChange = { next ->
                    val candidate =
                        if (
                            firstEditPending &&
                            fieldValue.text == defaultValue &&
                            next.text != defaultValue
                        ) {
                            when {
                                next.text.length > defaultValue.length &&
                                    next.text.startsWith(defaultValue) ->
                                    next.text.removePrefix(defaultValue)

                                next.text.length > defaultValue.length &&
                                    next.text.endsWith(defaultValue) ->
                                    next.text.removeSuffix(defaultValue)

                                else -> next.text
                            }
                        } else {
                            next.text
                        }

                    if (candidate.matches(Regex("^\\d*(\\.\\d{0,2})?$"))) {
                        firstEditPending = false
                        fieldValue =
                            TextFieldValue(
                                text = candidate,
                                selection = TextRange(candidate.length)
                            )
                        onValue(candidate)
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                textStyle =
                    MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 13.sp,
                        color = Color(0xFF222222)
                    ),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .onFocusChanged { state ->
                            if (
                                state.isFocused &&
                                defaultSelectionPending &&
                                fieldValue.text == defaultValue
                            ) {
                                fieldValue =
                                    fieldValue.copy(
                                        selection = TextRange(0, fieldValue.text.length)
                                    )
                                defaultSelectionPending = false
                            }
                        }
            )
        }
    }
}

@Composable
private fun CompactIntegerField(label: String, value: String, onValue: (String) -> Unit, modifier: Modifier = Modifier) {
    CompactBasicField(
        label = label,
        value = value,
        modifier = modifier,
        keyboardType = KeyboardType.Number,
        accept = { it.all(Char::isDigit) },
        onValue = onValue
    )
}

@Composable
private fun CompactBasicField(
    label: String,
    value: String,
    modifier: Modifier,
    keyboardType: KeyboardType,
    accept: (String) -> Boolean,
    onValue: (String) -> Unit
) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.Gray, maxLines = 1)
        Box(
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, Color(0xFFB8BDC5), RoundedCornerShape(8.dp))
                .background(Color.White)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            BasicTextField(
                value = value,
                onValueChange = { if (accept(it)) onValue(it) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, color = Color(0xFF222222)),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun CompactReadOnlyField(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.Gray, maxLines = 1)
        Box(
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, Color(0xFFD5D9DE), RoundedCornerShape(8.dp))
                .background(Color(0xFFF7F8FA))
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(value, fontSize = 13.sp, maxLines = 1)
        }
    }
}

@Composable
private fun CompactTextField(label: String, value: String, onValue: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        Box(
            Modifier
                .fillMaxWidth()
                .height(38.dp)
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, Color(0xFFB8BDC5), RoundedCornerShape(8.dp))
                .background(Color.White)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValue,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, color = Color(0xFF222222)),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun MiniSummaryCard(title: String, value: String, modifier: Modifier = Modifier, color: Color = SoftGreen) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = color), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(horizontal = 11.dp, vertical = 8.dp)) {
            Text(title, style = MaterialTheme.typography.labelSmall, color = Color.DarkGray)
            Text(value, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        }
    }
}

private fun cleanPercent(v: Double): String = if (kotlin.math.abs(v - v.toLong()) < 0.0001) v.toLong().toString() else String.format(Locale.CHINA, "%.2f", v).trimEnd('0').trimEnd('.')
private fun roundPercent(v: Double): Double = kotlin.math.round(v * 100.0) / 100.0

@Composable
private fun RecordCard(content: @Composable RowScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) { Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, content = content) }
}

@Composable
private fun AddFruitDialog(onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("件") }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("新增商品") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("商品名称") }, singleLine = true)
                Text("默认单位")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("箱", "筐", "件", "袋").forEach { u -> FilterChip(selected = unit == u, onClick = { unit = u }, label = { Text(u) }) } }
            }
        },
        confirmButton = { Button(onClick = { if (name.isNotBlank()) onSave(name, unit) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun FruitEditDialog(
    fruit: FruitAdminRecord,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var name by remember(fruit.id) { mutableStateOf(fruit.name) }
    var unit by remember(fruit.id) { mutableStateOf(fruit.defaultUnit) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑商品") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("商品名称") },
                    singleLine = true
                )
                Text("默认单位")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("箱", "筐", "件", "袋").forEach { u ->
                        FilterChip(
                            selected = unit == u,
                            onClick = { unit = u },
                            label = { Text(u) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { if (name.isNotBlank()) onSave(name.trim(), unit) }) {
                Text("保存")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun StoreTimePickerField(
    label: String,
    value: String,
    isEndTime: Boolean,
    modifier: Modifier = Modifier,
    onValue: (String) -> Unit
) {
    val context = LocalContext.current
    val normalized = if (value == "24:00") "00:00" else value
    val parts = normalized.split(':')
    val initialHour = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: if (isEndTime) 0 else 16
    val initialMinute = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
    OutlinedButton(
        onClick = {
            TimePickerDialog(
                context,
                { _, hour, minute ->
                    val picked = if (isEndTime && hour == 0 && minute == 0) {
                        "24:00"
                    } else {
                        String.format(Locale.CHINA, "%02d:%02d", hour, minute)
                    }
                    onValue(picked)
                },
                initialHour,
                initialMinute,
                true
            ).show()
        },
        modifier = modifier
    ) {
        Column(horizontalAlignment = Alignment.Start) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            Text(value, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun AddStoreDialog(
    onDismiss: () -> Unit,
    initial: StoreOption? = null,
    onSave: (String, String, Double?, Double?, String, String) -> Unit
) {
    var name by remember(initial?.id) { mutableStateOf(initial?.name.orEmpty()) }
    var address by remember(initial?.id) { mutableStateOf(initial?.address.orEmpty()) }
    var latitude by remember(initial?.id) { mutableStateOf(initial?.latitude?.toString().orEmpty()) }
    var longitude by remember(initial?.id) { mutableStateOf(initial?.longitude?.toString().orEmpty()) }
    var startTime by remember(initial?.id) { mutableStateOf(initial?.defaultStartTime ?: "16:00") }
    var endTime by remember(initial?.id) { mutableStateOf(initial?.defaultEndTime ?: "24:00") }
    val lat = latitude.toDoubleOrNull()
    val lon = longitude.toDoubleOrNull()
    val coordsValid = (latitude.isBlank() && longitude.isBlank()) ||
        (lat != null && lat in -90.0..90.0 && lon != null && lon in -180.0..180.0)
    val timeValid = storeTimeMinutes(endTime, 24 * 60) > storeTimeMinutes(startTime, 16 * 60)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新增位置" else "编辑位置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("位置简称，例如：龙归A摊") }, singleLine = true)
                OutlinedTextField(address, { address = it }, label = { Text("详细位置（可选）") })
                Text("默认营业时间", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StoreTimePickerField("开始时间", startTime, false, Modifier.weight(1f)) { startTime = it }
                    StoreTimePickerField("结束时间", endTime, true, Modifier.weight(1f)) { endTime = it }
                }
                if (!timeValid) {
                    Text("结束时间必须晚于开始时间；结束到午夜请选择 24:00。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
                Text("天气定位", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    OutlinedTextField(
                        latitude,
                        { v -> if (v.matches(Regex("^-?\\d*(\\.\\d{0,6})?$"))) latitude = v },
                        label = { Text("纬度") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        longitude,
                        { v -> if (v.matches(Regex("^-?\\d*(\\.\\d{0,6})?$"))) longitude = v },
                        label = { Text("经度") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    "位置名称、稳定位置ID、经纬度和默认营业时间会随位置资料一起同步；本版不增加地图选点。",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (coordsValid) Color.Gray else MaterialTheme.colorScheme.error
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(name.trim(), address.trim(), lat, lon, startTime, endTime) },
                enabled = name.isNotBlank() && coordsValid && timeValid
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun PartnerNameDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, label = { Text("姓名/称呼") }, singleLine = true) },
        confirmButton = { Button(onClick = { if (name.isNotBlank()) onSave(name) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun ConfirmActionDialog(
    title: String,
    text: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(title)
        },
        text = {
            Text(text)
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(confirmText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun ConfirmDelete(text: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("确认删除") }, text = { Text(text) },
        confirmButton = { Button(onClick = onConfirm) { Text("删除") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

private fun money(v: Double): String = "¥" + if (kotlin.math.abs(v - v.toLong()) < 0.005) v.toLong().toString() else String.format(Locale.CHINA, "%.2f", v)

private fun settlementTimeText(epochMillis: Long): String {
    if (epochMillis <= 0L) return ""
    return Instant
        .ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .format(
            DateTimeFormatter.ofPattern(
                "yyyy-MM-dd HH:mm"
            )
        )
}

private fun formatDateTime(
    epochMillis: Long
): String =
    if (epochMillis <= 0L) {
        "从未"
    } else {
        Instant
            .ofEpochMilli(
                epochMillis
            )
            .atZone(
                ZoneId.systemDefault()
            )
            .format(
                DateTimeFormatter.ofPattern(
                    "yyyy-MM-dd HH:mm"
                )
            )
    }

private fun fmt(v: Double): String = if (kotlin.math.abs(v - v.toLong()) < 0.005) v.toLong().toString() else String.format(Locale.CHINA, "%.2f", v)

private fun profitRatioPercent(
    ratio: Double
): Double =
    if (
        kotlin.math.abs(ratio) <= 1.000001
    ) {
        ratio * 100.0
    } else {
        ratio
    }

private fun uiRoundMoney(v: Double): Double = kotlin.math.round(v * 100.0) / 100.0

private fun cleanNumber(v: Double): String = if (v == 0.0) "" else fmt(v)

// 采购录入中的 0 是有效值，不能像普通空字段一样折叠为空字符串。
private fun purchaseNumber(v: Double): String = fmt(v)
