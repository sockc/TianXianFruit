package com.tianxian.fruit.ui

import android.app.DatePickerDialog
import android.graphics.BitmapFactory
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.tianxian.fruit.update.AppUpdateCheckResult
import com.tianxian.fruit.update.AppUpdateInfo
import com.tianxian.fruit.update.AppUpdateManager
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val BrandGreen = Color(0xFF13A868)
private val SoftGreen = Color(0xFFE9F8F0)
private val SoftOrange = Color(0xFFFFF3E3)
private val SoftBlue = Color(0xFFEAF3FF)
private val SoftPurple = Color(0xFFF3ECFF)

enum class AppPage(val title: String, val emoji: String) {
    HOME("首页", "🏠"),
    PURCHASE("采购", "📦"),
    SESSION("营业", "📝"),
    SETTLEMENT("结算", "🧾"),
    MORE("更多", "☰"),
    PLAN("采购", "🛒"),
    INVENTORY("库存", "📦")
}

private enum class MorePage {
    MENU,
    BOOKS,
    CLOUD_BOOKS,
    MEMBER_PERMISSIONS,
    SYSTEM_ADMIN,
    HOME_HEADER,
    SECURITY,
    ABOUT,
    HISTORY,
    PURCHASE_ACTIVITY,
    STATS,
    BACKUP,
    PARTNERS,
    STORES,
    PROFIT,
    FRUITS,
    REPORT
}

private enum class HistoryTimeFilter(val label: String) {
    ALL("全部时间"),
    TODAY("今天"),
    YESTERDAY("昨天"),
    LAST_7("近7天"),
    LAST_30("近30天"),
    THIS_MONTH("本月"),
    LAST_MONTH("上月"),
    CUSTOM("自定义")
}

private enum class HistorySection(val label: String) {
    BUSINESS("营业历史"),
    PURCHASE("采购历史"),
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
    val totalCost: String
)


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
    var dataVersion by remember {
        mutableIntStateOf(0)
    }

    val syncRefreshVersion =
        SyncUiRefreshBus.version.intValue

    LaunchedEffect(
        syncRefreshVersion
    ) {
        if (syncRefreshVersion > 0) {
            // 自动同步完成后只刷新当前 Compose 数据缓存；
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

    BackHandler(enabled = page != AppPage.HOME) { page = AppPage.HOME }

    MaterialTheme(colorScheme = lightColorScheme(primary = BrandGreen, secondary = BrandGreen)) {
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            bottomBar = {
                NavigationBar {
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
                                AppPage.PLAN,
                                AppPage.INVENTORY ->
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
                when (page) {
                    AppPage.HOME -> HomeScreen(
                        db = db,
                        dataVersion = dataVersion,
                        ledgerManager =
                            ledgerManager,
                        currentBook =
                            liveCurrentBook,
                        books =
                            ledgerManager.books(),
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
                        onMore = {
                            moreTarget =
                                MorePage.MENU
                            page =
                                AppPage.MORE
                        }
                    )
                    AppPage.INVENTORY ->
                        InventoryScreen(
                            db = db,
                            dataVersion = dataVersion,
                            onChanged = { notifyDataChanged() }
                        )
                    AppPage.PURCHASE ->
                        PurchaseScreen(
                            db = db,
                            dataVersion =
                                dataVersion,
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
                            }
                        )
                    AppPage.SETTLEMENT ->
                        SettlementScreen(
                            db = db,
                            dataVersion = dataVersion,
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
    Column(
        Modifier
            .fillMaxWidth()
            .padding(
                top = 8.dp,
                bottom = 8.dp
            )
    ) {
        Text(
            title,
            style =
                MaterialTheme
                    .typography
                    .headlineSmall,
            fontWeight =
                FontWeight.Bold
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

@Composable
private fun HomeScreen(
    db: AppDatabase,
    dataVersion: Int,
    ledgerManager: LedgerManager,
    currentBook: LedgerBook,
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
    onMore: () -> Unit
) {
    var selectedDate by remember {
        mutableStateOf(
            LocalDate.now()
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
    val records = remember(dataVersion, selectedDateString) {
        db.getDailyRecords(selectedDateString)
    }
    val inventoryPreview = remember(dataVersion, selectedDateString) {
        db.getInventoryDayItems(selectedDateString)
    }
    val inventoryPreviewText =
        when {
            inventoryPreview.isEmpty() -> "暂无库存"
            inventoryPreview.all { it.saved } -> "${inventoryPreview.size}种 · 已盘点"
            else -> "${inventoryPreview.size}种 · 待盘点"
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
                    .height(170.dp)
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
                        Modifier.align(
                            Alignment.TopStart
                        )
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
                                "SUPERADMIN"
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
                                top = 24.dp
                            )
                    ) {
                        Text(
                            headerSettings
                                .title,
                            color = Color.White,
                            fontSize = 30.sp,
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
                                fontSize = 24.sp
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
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .offset(y = (-12).dp)
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
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text("📍", fontSize = 16.sp)
                            Spacer(Modifier.width(5.dp))
                            Text(
                                locationText,
                                maxLines = 1,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (records.isEmpty()) Color.Gray else Color.DarkGray
                            )
                        }

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
                                    "📈",
                                    "利润",
                                    money(summary.profit),
                                    SoftGreen,
                                    Modifier.weight(1f)
                                )
                            }
    
                            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                DashboardTile(
                                    "🛒",
                                    "进货成本",
                                    money(summary.purchaseCost),
                                    SoftBlue,
                                    Modifier.weight(1f)
                                )
                                DashboardTile(
                                    "📦",
                                    "剩余库存",
                                    inventoryPreviewText,
                                    Color(0xFFFFF7D9),
                                    Modifier.weight(1f),
                                    onClick = onInventory
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

                        val quickActions =
                            buildList {
                                if (canViewStats) {
                                    add(
                                        Triple(
                                            "📊",
                                            "经营统计",
                                            onStats
                                        )
                                    )
                                }
                                if (canViewProfit) {
                                    add(
                                        Triple(
                                            "💰",
                                            "利润分配",
                                            onProfit
                                        )
                                    )
                                }
                                if (canViewReport) {
                                    add(
                                        Triple(
                                            "📄",
                                            "生成报表",
                                            onReport
                                        )
                                    )
                                }
                                if (canViewHistory) {
                                    add(
                                        Triple(
                                            "🧾",
                                            "历史记录",
                                            onHistory
                                        )
                                    )
                                }
                            }

                        if (quickActions.isNotEmpty()) {
                            Text(
                                "快捷操作",
                                fontWeight =
                                    FontWeight.Bold
                            )

                            Row(
                                horizontalArrangement =
                                    Arrangement.spacedBy(8.dp)
                            ) {
                                quickActions
                                    .take(4)
                                    .forEachIndexed {
                                        index,
                                        action ->
                                        QuickActionTile(
                                            action.first,
                                            action.second,
                                            action.third,
                                            Modifier.weight(1f),
                                            listOf(
                                                SoftGreen,
                                                SoftOrange,
                                                SoftBlue,
                                                SoftPurple
                                            )[index]
                                        )
                                    }

                                repeat(
                                    (4 -
                                        quickActions
                                            .take(4)
                                            .size)
                                        .coerceAtLeast(0)
                                ) {
                                    Spacer(
                                        Modifier.weight(1f)
                                    )
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
                                        trend.forEach { (d, revenue) ->
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
                                                Text(
                                                    fmt(revenue),
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = BrandGreen,
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
    onClick: (() -> Unit)? = null
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
    Canvas(Modifier.fillMaxWidth().height(112.dp).padding(horizontal = 5.dp, vertical = 6.dp)) {
        val left = 8f
        val right = size.width - 8f
        val top = 8f
        val bottom = size.height - 8f

        repeat(3) { i ->
            val y = top + (bottom - top) * i / 2f
            drawLine(Color(0xFFE7E9ED), Offset(left, y), Offset(right, y), strokeWidth = 1.2f)
        }

        if (values.size > 1) {
            val pts = values.mapIndexed { index, item ->
                val x = left + (right - left) * index / (values.size - 1).toFloat()
                val y = bottom - ((item.second / maxValue).toFloat() * (bottom - top))
                Offset(x, y)
            }
            pts.zipWithNext().forEach { (a, b) ->
                drawLine(BrandGreen, a, b, strokeWidth = 4f)
            }
            pts.forEach { p ->
                drawCircle(Color.White, radius = 7f, center = p)
                drawCircle(BrandGreen, radius = 4.5f, center = p)
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
    onChanged: () -> Unit
) {
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    var message by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }
    val remainingInputs = remember { mutableStateMapOf<String, String>() }

    val inventoryItems = remember(dataVersion, date) {
        db.getInventoryDayItems(date)
    }

    fun itemKey(item: InventoryDayItemRecord): String =
        "${item.fruitId}|${item.unit}"

    LaunchedEffect(dataVersion, date) {
        val latest = db.getInventoryDayItems(date)
        remainingInputs.clear()
        latest.forEach { item ->
            remainingInputs[itemKey(item)] = cleanNumber(item.remainingQuantity)
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
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        item {
            PageHeader("剩余库存", "独立库存清单 · 已同步 · 暂不参与利润、结算和报表")
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF7D9)),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text("🧪 库存试验模块", fontWeight = FontWeight.Bold, color = Color(0xFF7B5C00))
                    Text(
                        "自动读取当日已完成采购，排除“总价”；上一次已保存的剩余库存会继续结转。剩余库存现已独立同步，但仍不参与利润、结算、报表，也暂不回写采购表下一日库存。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF6F6250)
                    )
                }
            }
        }

        item {
            CompactDateNavigator(
                label = "盘点日期",
                date = date,
                modifier = Modifier.fillMaxWidth(),
                chineseDisplay = true,
                showWeekday = true,
                onDate = { date = it }
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
                val remaining = input.toDoubleOrNull()
                val available = item.openingQuantity + item.purchasedQuantity
                val delta = if (remaining != null) available - remaining else null

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(item.fruitName, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                                Text(
                                    if (item.purchasedQuantity > 0.000001) "今日有采购" else "历史库存结转",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (item.purchasedQuantity > 0.000001) BrandGreen else Color.Gray
                                )
                            }
                            if (item.saved) {
                                Text(
                                    "已保存",
                                    color = BrandGreen,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
                                "可用合计",
                                "${fmt(available)}${item.unit}",
                                Modifier.weight(1f)
                            )
                        }

                        CompactNumberField(
                            "剩余库存（${item.unit}）",
                            input,
                            { remainingInputs[key] = it },
                            Modifier.fillMaxWidth()
                        )

                        if (delta != null) {
                            val text =
                                if (delta >= -0.000001) {
                                    "本日减少 ${fmt(delta.coerceAtLeast(0.0))}${item.unit}"
                                } else {
                                    "盘点比可用库存多 ${fmt(-delta)}${item.unit}，请核对采购或结转记录"
                                }
                            Text(
                                text,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (delta >= -0.000001) Color.Gray else MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            item {
                Button(
                    onClick = {
                        val invalid = inventoryItems.firstOrNull { item ->
                            val value = remainingInputs[itemKey(item)]?.toDoubleOrNull()
                            value == null || value < 0
                        }
                        if (invalid != null) {
                            message = "${invalid.fruitName} 的剩余库存必须填写0或正数"
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
                                            remainingQuantity = remainingInputs[itemKey(item)]!!.toDouble()
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
                    Text("保存剩余库存")
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
                "说明：该模块暂不改变经营利润、资金轧差、最少转账方案、经营统计或报表。正式启用前将另行设计损耗、商品毛利和云同步规则。",
                color = Color.Gray,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(vertical = 6.dp)
            )
        }
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
                        status = item.status
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
                        listOf("斤", "筐", "箱", "件").forEach { u ->
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
                            status = oldStatus
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
    onChanged: () -> Unit,
    protectHistoricalAction:
        (String, String, () -> Unit) -> Unit,
    onOpenHistory: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
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
                buyerId = defaultBuyerId(),
                buyerNameSnapshot = defaultBuyerName()
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
            buyerId = defaultBuyerId(),
            buyerNameSnapshot = defaultBuyerName()
        )
    }

    fun rowFingerprint(row: PurchaseDraftRow): String =
        listOf(
            row.fruitId?.toString().orEmpty(),
            row.quantity,
            row.unit,
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
            row.quantity.isBlank() &&
            row.unitPrice.isBlank() &&
            row.totalCost.isBlank()

    fun meaningfulRows(): List<PurchaseDraftRow> =
        rows.filterNot { it.isBlank || isDefaultEmptyRow(it) }

    fun loadRowsFromCollaborationPlan() {
        if (editingOrderId != null) return

        val detail = db.getPurchasePlan(date)
        val planItems = detail?.items.orEmpty().filter { it.status != 2 }

        rows.clear()
        syncedFingerprints.clear()
        remark = detail?.plan?.note.orEmpty()

        planItems.forEach { item ->
            val quantityValue =
                if (item.status == 1 && item.actualQuantity > 0) item.actualQuantity else item.quantity
            val totalValue =
                if (item.status == 1) item.actualAmount else item.estimatedAmount
            val row =
                PurchaseDraftRow(
                    rowId = nextRowId++,
                    planItemId = item.id,
                    fruitId = item.fruitId,
                    fruitNameSnapshot = item.fruitName,
                    unit = item.unit,
                    quantity = cleanNumber(quantityValue),
                    unitPrice =
                        if (quantityValue > 0 && totalValue > 0) cleanNumber(totalValue / quantityValue) else "",
                    totalCost = if (totalValue > 0) cleanNumber(totalValue) else "",
                    buyerId = item.buyerId.takeIf { it > 0L },
                    buyerNameSnapshot = item.buyerName,
                    priceSource = PurchasePriceSource.TOTAL
                )
            rows.add(row)
            syncedFingerprints[row.rowId] = rowFingerprint(row)
        }

        if (rows.isEmpty()) rows.add(newBlankRow())
    }

    fun collaborationItemFor(row: PurchaseDraftRow): PurchasePlanItemRecord? {
        val items = collaborationPlan?.items.orEmpty()
        return row.planItemId
            ?.let { id -> items.firstOrNull { it.id == id } }
            ?: row.fruitId?.let { fruitId ->
                items.firstOrNull { it.fruitId == fruitId && it.status != 2 }
            }
    }

    fun loadHistoryOrderForEdit(detail: PurchaseOrderDetail) {
        editingOrderId = detail.order.id
        date = detail.order.date
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
                    quantity = cleanNumber(item.quantity),
                    unitPrice = cleanNumber(item.unitPrice),
                    totalCost = cleanNumber(item.totalCost),
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

        if (
            pendingRows.any {
                it.fruitId == null ||
                    (it.quantity.toDoubleOrNull() ?: 0.0) <= 0 ||
                    (it.totalCost.toDoubleOrNull() ?: -1.0) < 0
            }
        ) {
            message = "有商品没有选择商品，或数量/总价没有填写正确"
            return
        }

        var savedCount = 0
        pendingRows.forEach { row ->
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
                    estimatedAmount = row.totalCost.toDouble(),
                    buyer = buyer
                )
            if (itemId > 0) {
                savedCount++
                val updated = row.copy(planItemId = itemId)
                updateRow(row.rowId, updated)
                syncedFingerprints[row.rowId] = rowFingerprint(updated)
            }
        }

        if (savedCount == pendingRows.size) {
            db.updatePurchasePlanNote(date, remark)
            message = "采购清单已保存到协作采购，完成采购后才正式入账"
            onChanged()
        } else {
            message = "部分商品保存失败，请检查后重试"
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
            message = "有商品没有选择商品，或数量/总价没有填写正确"
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

        db.updatePurchasePlanNote(date, remark)

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
        item { PageHeader("采购", null) }

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
                                date = LocalDate.now().toString()
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
                CompactDateNavigator(
                    label = "日期",
                    date = date,
                    modifier = Modifier.fillMaxWidth(),
                    chineseDisplay = true,
                    showWeekday = true
                ) { date = it }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    CompactDateNavigator(
                        label = "日期",
                        date = date,
                        modifier = Modifier.weight(1f),
                        chineseDisplay = true,
                        showWeekday = true
                    ) { date = it }

                    Box(Modifier.width(110.dp)) {
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

        items(rows, key = { row -> "purchase_draft_${row.rowId}" }) { row ->
            val collaborationItem = collaborationItemFor(row)
            val completed = editingOrderId == null && collaborationItem?.status == 1
            val savedPending =
                editingOrderId == null &&
                    collaborationItem?.status == 0 &&
                    row.planItemId != null
            val editingSavedPending =
                savedPending && editingPlanItemId == collaborationItem?.id

            when {
                completed -> {
                    // 已完成项目统一放到操作按钮下方，避免和待采购混在一起。
                }

                savedPending && !editingSavedPending -> {
                    PurchasePlanStatusCard(
                        item = collaborationItem!!,
                        completed = false,
                        onClick = {
                            protectHistoricalAction(
                                date,
                                "查看或修改 ${collaborationItem.fruitName} 的历史采购"
                            ) {
                                planActionItem = collaborationItem
                            }
                        }
                    )
                }

                else -> {
                    // FIX2：采购录入不再随输入自动写入协作采购。
                    // 只有点击“计划采购”/“保存计划”/“完成采购”时才持久化，避免输入数量时误保存。
                    PurchaseDraftRowEditor(
                        row = row,
                        fruits = fruits,
                        partners = partners,
                        completedItem = null,
                        showBuyer = editingOrderId == null,
                        canDelete = rows.size > 1,
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
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(
                                onClick = {
                                    focusManager.clearFocus()
                                    editingPlanItemId = null
                                    message = "${collaborationItem?.fruitName.orEmpty()} 修改已保存"
                                }
                            ) {
                                Text("完成修改")
                            }
                        }
                    }
                }
            }
        }

        item {
            OutlinedButton(
                onClick = {
                    focusManager.clearFocus()
                    if (rows.lastOrNull()?.let { it.isBlank || isDefaultEmptyRow(it) } == true) {
                        message = "下面已经有一组空白商品，直接填写即可"
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
                val total = activeRows.sumOf { it.totalCost.toDoubleOrNull() ?: 0.0 }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "本次采购 ${activeRows.size} 项",
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Text("合计 ${money(total)}", fontWeight = FontWeight.Bold, color = BrandGreen)
                }
            }
        }

        item {
            CompactTextField(
                "备注（可选）",
                remark,
                { remark = it },
                Modifier.fillMaxWidth()
            )

            if (editingOrderId == null) {
                val hasSavedPending =
                    meaningfulRows().any { row ->
                        row.planItemId != null && collaborationItemFor(row)?.status == 0
                    }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 7.dp),
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
                                                totalCost = r.totalCost.toDouble()
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
                    }
                }

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
                                    unit = "件"
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
                    (if (completed) "已完成采购" else "待采购") +
                        "\n数量 ${fmt(quantity)}${item.unit} · 总价 ${money(amount)}" +
                        if (item.buyerName.isNotBlank()) " · ${item.buyerName}" else ""
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
                onChanged()
            }
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
                                    totalCost =
                                        cleanNumber(
                                            item.totalCost
                                        )
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
                        totalCost = total
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
                Arrangement.spacedBy(6.dp)
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

            Row(
                horizontalArrangement =
                    Arrangement.spacedBy(6.dp)
            ) {
                NumberField(
                    "数量",
                    row.quantity,
                    {
                        onChange(
                            row.copy(
                                quantity = it
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

                NumberField(
                    "总价",
                    row.totalCost,
                    {
                        onChange(
                            row.copy(
                                totalCost = it
                            )
                        )
                    },
                    Modifier.weight(1f)
                )
            }

            val quantity =
                row.quantity.toDoubleOrNull()
                    ?: 0.0
            val total =
                row.totalCost.toDoubleOrNull()
                    ?: 0.0

            if (
                quantity > 0 &&
                total >= 0
            ) {
                Text(
                    "单价 ${money(total / quantity)}/${row.unit}",
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
    onClick: () -> Unit
) {
    val quantity =
        if (completed && item.actualQuantity > 0) item.actualQuantity else item.quantity
    val amount =
        if (completed) item.actualAmount else item.estimatedAmount
    val unitPrice = if (quantity > 0 && amount >= 0) amount / quantity else 0.0
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
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.Bold
                )
                Text(
                    if (completed) "已完成采购" else "待采购  ›",
                    color = if (completed) BrandGreen else Color(0xFF8A6D00),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Text(
                "数量 ${fmt(quantity)}${item.unit}   " +
                    (if (completed || unitPrice > 0) "单价 ${money(unitPrice)}/${item.unit}   " else "") +
                    "总价 ${money(amount)}   $buyer",
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
            completedItem.actualQuantity.takeIf { it > 0 } ?: row.quantity.toDoubleOrNull() ?: 0.0
        val totalValue =
            completedItem.actualAmount
        val unitPriceValue = if (quantityValue > 0 && totalValue > 0) totalValue / quantityValue else 0.0
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

    fun updateQuantity(value: String) {
        val quantity = value.toDoubleOrNull() ?: 0.0
        val updated =
            when (row.priceSource) {
                PurchasePriceSource.UNIT -> {
                    val unitPrice = row.unitPrice.toDoubleOrNull() ?: 0.0
                    row.copy(
                        quantity = value,
                        totalCost =
                            if (quantity > 0 && value.isNotBlank() && unitPrice >= 0) fmt(quantity * unitPrice)
                            else row.totalCost
                    )
                }

                PurchasePriceSource.TOTAL -> {
                    val total = row.totalCost.toDoubleOrNull() ?: 0.0
                    row.copy(
                        quantity = value,
                        unitPrice =
                            if (quantity > 0 && value.isNotBlank() && total >= 0) fmt(total / quantity)
                            else row.unitPrice
                    )
                }
            }
        onChange(updated)
    }

    fun updateUnitPrice(value: String) {
        val quantity = row.quantity.toDoubleOrNull() ?: 0.0
        val unitPrice = value.toDoubleOrNull() ?: 0.0
        onChange(
            row.copy(
                unitPrice = value,
                totalCost =
                    if (quantity > 0 && value.isNotBlank() && unitPrice >= 0) {
                        fmt(quantity * unitPrice)
                    } else if (value.isBlank()) {
                        ""
                    } else {
                        row.totalCost
                    },
                priceSource = PurchasePriceSource.UNIT
            )
        )
    }

    fun updateTotal(value: String) {
        val quantity = row.quantity.toDoubleOrNull() ?: 0.0
        val total = value.toDoubleOrNull() ?: 0.0
        onChange(
            row.copy(
                totalCost = value,
                unitPrice =
                    if (quantity > 0 && value.isNotBlank() && total >= 0) {
                        fmt(total / quantity)
                    } else if (value.isBlank()) {
                        ""
                    } else {
                        row.unitPrice
                    },
                priceSource = PurchasePriceSource.TOTAL
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
                Box(Modifier.weight(1.65f)) {
                    CompactSelectButton(
                        "商品",
                        fruitDisplay,
                        Modifier.fillMaxWidth()
                    ) { fruitMenu = true }

                    DropdownMenu(
                        expanded = fruitMenu,
                        onDismissRequest = { fruitMenu = false }
                    ) {
                        fruits.forEach { fruit ->
                            DropdownMenuItem(
                                text = { Text(fruit.name) },
                                onClick = {
                                    onChange(
                                        row.copy(
                                            fruitId = fruit.id,
                                            fruitNameSnapshot = fruit.name,
                                            unit = "件"
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

                CompactNumberField(
                    "数量",
                    row.quantity,
                    { updateQuantity(it) },
                    Modifier.weight(0.72f)
                )

                Box(Modifier.width(74.dp)) {
                    CompactSelectButton("单位", row.unit, Modifier.fillMaxWidth()) { unitMenu = true }
                    DropdownMenu(
                        expanded = unitMenu,
                        onDismissRequest = { unitMenu = false }
                    ) {
                        listOf("斤", "筐", "箱", "件").forEach { unit ->
                            DropdownMenuItem(
                                text = { Text(unit) },
                                onClick = {
                                    onChange(row.copy(unit = unit))
                                    unitMenu = false
                                }
                            )
                        }
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
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                CompactNumberField(
                    "单价",
                    row.unitPrice,
                    { updateUnitPrice(it) },
                    Modifier.weight(1f)
                )
                CompactNumberField(
                    "总价",
                    row.totalCost,
                    { updateTotal(it) },
                    Modifier.weight(1f)
                )

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
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionScreen(
    db: AppDatabase,
    dataVersion: Int,
    onChanged: () -> Unit,
    protectHistoricalAction:
        (String, String, () -> Unit) -> Unit,
    onOpenHistory: () -> Unit
) {
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
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
        if (!keepDate) date = LocalDate.now().toString()
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
    }

    fun loadRecord(r: StoreDailyRecord) {
        editingRecordId = r.id
        date = r.date
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
                storeId = stores.firstOrNull()?.id
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

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        item { PageHeader("营业记录", null) }

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

        item {
            CompactDateNavigator(
                label = "营业日期",
                date = date,
                modifier = Modifier.fillMaxWidth(),
                chineseDisplay = true,
                showWeekday = true
            ) { date = it }
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

        item {
            receiptRows.forEachIndexed { index, row ->
                ReceiptSplitDraftRow(
                    row = row,
                    partners = partners,
                    historical = editingRecordId != null,
                    canDelete = receiptRows.size > 1,
                    onChange = { updated ->
                        val target = receiptRows.indexOfFirst { it.rowId == updated.rowId }
                        if (target >= 0) receiptRows[target] = updated
                    },
                    onDelete = {
                        val target = receiptRows.indexOfFirst { it.rowId == row.rowId }
                        if (target >= 0) receiptRows.removeAt(target)
                    }
                )
                if (index != receiptRows.lastIndex) Spacer(Modifier.height(7.dp))
            }

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
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .height(42.dp),
                enabled = partners.isNotEmpty()
            ) {
                Text("＋ 再添加收款人")
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
                CompactNumberField("日常开销", expense, { expense = it }, Modifier.weight(1f))

                Box(Modifier.weight(1f)) {
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

            Spacer(Modifier.height(5.dp))
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
                        oldCustomer = o
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
            onDismiss = { addStoreDialog = false }
        ) { name, address ->
            val newId = db.addStore(name, address)
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
            Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(Modifier.weight(1f)) {
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

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "小计",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray
                    )
                    Text(
                        money(row.total),
                        fontWeight = FontWeight.Bold,
                        color = BrandGreen
                    )
                }

                if (canDelete) {
                    TextButton(
                        onClick = onDelete,
                        modifier = Modifier.height(38.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                    ) {
                        Text(
                            "删除",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
                CompactNumberField(
                    "现金",
                    row.cash,
                    { onChange(row.copy(cash = it)) },
                    Modifier.weight(1f)
                )
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
    protectHistoricalAction: (String, String, () -> Unit) -> Unit,
    onChanged: () -> Unit
) {
    val context = LocalContext.current
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    var message by remember { mutableStateOf("") }
    var deleteId by remember { mutableStateOf<Long?>(null) }
    var settleTransfer by remember {
        mutableStateOf<SettlementTransferRecord?>(null)
    }
    var settleInput by remember {
        mutableStateOf("")
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
                label = "结算日期",
                date = date,
                modifier = Modifier.fillMaxWidth(),
                chineseDisplay = true,
                showWeekday = true
            ) {
                date = it
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
                        verticalAlignment =
                            Alignment.CenterVertically
                    ) {
                        Text(
                            "${t.fromPartnerName}  →  " +
                                t.toPartnerName,
                            modifier = Modifier.weight(1f),
                            fontWeight = FontWeight.Bold
                        )
                        Column(
                            horizontalAlignment = Alignment.End
                        ) {
                            Text(
                                money(t.amount),
                                fontWeight = FontWeight.Bold,
                                color = BrandGreen
                            )
                            if (t.settledAmount > 0.005) {
                                Text(
                                    if (t.pendingAmount <= 0.005) {
                                        "已结清"
                                    } else {
                                        "已结 ${money(t.settledAmount)} · 剩 ${money(t.pendingAmount)}"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.Gray
                                )
                            } else {
                                Text(
                                    "未结算",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.Gray
                                )
                            }
                            TextButton(
                                onClick = {
                                    settleTransfer = t
                                    settleInput =
                                        if (t.settledAmount > 0.005) {
                                            cleanNumber(t.settledAmount)
                                        } else {
                                            ""
                                        }
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
                            }
                    )
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
                    Text(
                        "这笔转账单独记录结算状态，不会自动把其他合伙人的转账一起标记为已结算。",
                        style =
                            MaterialTheme.typography.bodySmall
                    )

                    NumberField(
                        "累计已结算金额",
                        settleInput,
                        {
                            settleInput = it
                        },
                        Modifier.fillMaxWidth()
                    )

                    Text(
                        "当前已结 ${money(currentSettled)} · 最多 ${money(maxAmount)}",
                        style =
                            MaterialTheme.typography.labelSmall,
                        color = Color.Gray
                    )
                }
            },
            confirmButton = {
                Column(
                    horizontalAlignment = Alignment.End
                ) {
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
                                        "${transfer.fromPartnerName} → ${transfer.toPartnerName} 已设为未结算"
                                    settleTransfer = null
                                    settleInput = ""
                                    onChanged()
                                }
                            }
                        ) {
                            Text("未结算")
                        }

                        TextButton(
                            onClick = {
                                if (
                                    db.setCashSettlementTransferSettledAmount(
                                        transfer.id,
                                        maxAmount
                                    )
                                ) {
                                    message =
                                        "${transfer.fromPartnerName} → ${transfer.toPartnerName} 已全部结清"
                                    settleTransfer = null
                                    settleInput = ""
                                    onChanged()
                                }
                            }
                        ) {
                            Text("全部结算")
                        }
                    }

                    Button(
                        onClick = {
                            val amount =
                                settleInput
                                    .toDoubleOrNull()

                            if (amount == null) {
                                message =
                                    "请输入累计已结算金额"
                            } else if (
                                amount < 0 ||
                                amount >
                                maxAmount + 0.005
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
                                    if (
                                        amount + 0.005 >=
                                        maxAmount
                                    ) {
                                        "${transfer.fromPartnerName} → ${transfer.toPartnerName} 已全部结清"
                                    } else {
                                        "本笔已结 ${money(amount)}，剩余自动结转"
                                    }
                                settleTransfer = null
                                settleInput = ""
                                onChanged()
                            }
                        }
                    ) {
                        Text("保存部分结算")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        settleTransfer = null
                        settleInput = ""
                    }
                ) {
                    Text("取消")
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
                            "SUPERADMIN"
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
                        "云端与协作"
                    ) {
                        SettingsRow(
                            "☁️",
                            "云端共享账本"
                        ) {
                            sub =
                                MorePage.CLOUD_BOOKS
                        }

                        if (
                            currentBook.cloudEnabled
                        ) {
                            SettingsDivider()
                            SettingsRow(
                                "👥",
                                "成员与权限"
                            ) {
                                sub =
                                    MorePage.MEMBER_PERMISSIONS
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
                        "云端共享账本",
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
                cloudBooks,
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
                                OutlinedButton(
                                    onClick = {
                                        cloudMessage =
                                            "成员权限请从“更多 → 成员与权限”管理"
                                    },
                                    enabled = !cloudBusy,
                                    modifier = Modifier.weight(1f)
                                ) { Text("成员") }
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
            books,
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
            "剩余库存"
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
    val context =
        LocalContext.current

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
            ActivityResultContracts
                .GetContent()
        ) {
            uri ->
            if (uri != null) {
                ledgerUiSettingsManager
                    .saveBackgroundImage(
                        currentBook.id,
                        uri
                    )
                    .onSuccess {
                        path ->
                        backgroundPath =
                            path
                        message =
                            "背景图片已更换"
                        onChanged()
                    }
                    .onFailure {
                        error ->
                        message =
                            "图片设置失败：" +
                                (
                                    error.message
                                        ?: "未知错误"
                                    )
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
                                imageLauncher
                                    .launch(
                                        "image/*"
                                    )
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
                        "更多页分组、首页顶部个性化、更新入口移入关于",
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
                    "当前云同步服务需要 TianXian Sync Server V1.0.7-Lucky。",
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
private fun SubPage(title: String, back: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = back) { Text("← 返回") }
            Text(title, fontWeight = FontWeight.Bold)
        }
        Box(Modifier.weight(1f)) { content() }
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
    var timeFilter by remember {
        mutableStateOf(HistoryTimeFilter.ALL)
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

    val purchases = remember(dataVersion, queryStart, queryEnd) {
        db.getPurchaseOrdersBetween(queryStart, queryEnd)
    }
    val sessions = remember(dataVersion, queryStart, queryEnd) {
        db.getDailyRecordsBetween(queryStart, queryEnd)
    }
    val profitRows = remember(dataVersion, queryStart, queryEnd) {
        db.getProfitDistributionsBetween(queryStart, queryEnd)
    }
    val purchasePlans = remember(dataVersion, queryStart, queryEnd) {
        db.getPurchasePlansBetween(queryStart, queryEnd)
    }
    val profitByDate = remember(profitRows) {
        profitRows
            .groupBy { it.date }
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
                        onClick = { section = option },
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
                    HistorySection.BUSINESS -> "${sessions.size} 条营业记录"
                    HistorySection.PURCHASE -> "${purchases.size} 张采购单"
                    HistorySection.PROFIT -> "${profitByDate.size} 天利润记录"
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

        when (section) {
            HistorySection.BUSINESS -> {
                if (sessions.isEmpty()) {
                    item {
                        Text("当前时间范围暂无营业记录", color = Color.Gray)
                    }
                }

                items(sessions, key = { "s${it.id}" }) { s ->
                    RecordCard {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${s.date} · ${s.storeName}",
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "营业 ${money(s.revenue)} · " +
                                    "利润 ${money(s.profit)} · " +
                                    "客户 ${s.customerTotal}",
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
                }
            }

            HistorySection.PURCHASE -> {
                if (purchases.isEmpty()) {
                    item {
                        Text("当前时间范围暂无采购记录", color = Color.Gray)
                    }
                }

                items(purchases, key = { "p${it.order.id}" }) { p ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "${p.order.date} · ${p.order.buyerName}",
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text("采购 · ${money(p.order.totalCost)}")
                                }

                                if (canEdit) {
                                    TextButton(
                                        onClick = {
                                            protectHistoricalAction(
                                                p.order.date,
                                                "删除 ${p.order.date} · ${p.order.buyerName} 采购单"
                                            ) {
                                                deleteOrder = p
                                            }
                                        }
                                    ) {
                                        Text("删除")
                                    }
                                }
                            }

                            p.items.forEach { i ->
                                Text(
                                    "• ${i.fruitName} " +
                                        "${fmt(i.quantity)}${i.unit} " +
                                        money(i.totalCost),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }

            HistorySection.PROFIT -> {
                if (profitByDate.isEmpty()) {
                    item {
                        Text("当前时间范围暂无利润记录", color = Color.Gray)
                    }
                }

                profitByDate.forEach { entry ->
                    val historyDate = entry.first
                    val rows = entry.second
                    item(key = "profit-$historyDate") {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(11.dp)) {
                                Text(historyDate, fontWeight = FontWeight.Bold)
                                Text(
                                    "利润 " +
                                        money(rows.firstOrNull()?.sourceProfit ?: 0.0) +
                                        " · 已分配 " +
                                        money(rows.sumOf { it.allocatedProfit }),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.Gray
                                )
                                rows.forEach { r ->
                                    Text(
                                        "• ${r.partnerName} " +
                                            "${fmt(profitRatioPercent(r.ratio))}%  " +
                                            money(r.allocatedProfit),
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

    val statsStoreNames =
        remember(
            records
        ) {
            records
                .map { it.storeName }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()
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
        if (calendarStoreName != null) {
            calendarPartnerIds = emptySet()
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
                        .sortedByDescending {
                            row ->
                            if (
                                row.days >
                                0
                            ) {
                                row.revenue /
                                    row.days
                            } else {
                                row.revenue
                            }
                        },
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
                                onClick = { calendarStoreName = storeName },
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
                        BusinessCalendarMetric.entries.forEach { metric ->
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
                            "个人采购和个人利润没有位置归属，切换到“全部位置”后可多选合伙人数据。",
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
                        CalendarLegendDot(Color(0xFF1976D2), "营业额")
                        CalendarLegendDot(Color(0xFFF9A825), "采购额")
                        CalendarLegendDot(BrandGreen, "利润")
                        CalendarLegendDot(MaterialTheme.colorScheme.error, "负利润")
                    }
                }

                item {
                    Text(
                        "日历格内只显示所选指标数字：营业额蓝色、采购额黄色、正利润绿色、负利润红色。指标和合伙人都支持多选；“全部”表示整体数据。采购仍按采购日统计，不做位置分摊。",
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
                "点击 ↑ / ↓ 调整位置顺序，修改后立即保存",
                color = Color.Gray,
                style =
                    MaterialTheme
                        .typography
                        .bodySmall
            )
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

                    if (
                        s.address
                            .isNotBlank()
                    ) {
                        Text(
                            s.address,
                            style =
                                MaterialTheme
                                    .typography
                                    .bodySmall,
                            color = Color.Gray
                        )
                    }
                }

                TextButton(
                    onClick = {
                        delete = s
                    }
                ) {
                    Text("删除")
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
            {
                addDialog = false
            }
        ) {
            name,
            address ->
            val id =
                db.addStore(
                    name,
                    address
                )

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
internal fun CompactDateNavigator(
    label: String?,
    date: String,
    modifier: Modifier = Modifier,
    chineseDisplay: Boolean = false,
    showWeekday: Boolean = false,
    onDate: (String) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val parsedDate =
        runCatching { LocalDate.parse(date) }
            .getOrElse { LocalDate.now() }

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
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            IconButton(
                onClick = {
                    onDate(parsedDate.minusDays(1).toString())
                },
                modifier = Modifier.size(40.dp)
            ) {
                Text(
                    "‹",
                    color = BrandGreen,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            OutlinedButton(
                onClick = {
                    showDatePicker(
                        context,
                        parsedDate.toString(),
                        onDate
                    )
                },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    displayText,
                    modifier = Modifier.weight(1f),
                    fontSize = if (chineseDisplay) 16.sp else 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                Text(
                    "📅",
                    fontSize = 14.sp
                )
            }

            IconButton(
                onClick = {
                    onDate(parsedDate.plusDays(1).toString())
                },
                modifier = Modifier.size(40.dp)
            ) {
                Text(
                    "›",
                    color = BrandGreen,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
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
    val context = androidx.compose.ui.platform.LocalContext.current
    val parsed = runCatching { LocalDate.parse(date) }.getOrNull()
    val display =
        if (showWeekday && parsed != null) {
            "$date  ${chineseWeekday(parsed)}"
        } else {
            date
        }
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        OutlinedButton(
            onClick = { showDatePicker(context, date, onDate) },
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
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("斤", "筐", "箱", "件").forEach { u -> FilterChip(selected = unit == u, onClick = { unit = u }, label = { Text(u) }) } }
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
                    listOf("斤", "筐", "箱", "件").forEach { u ->
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
private fun AddStoreDialog(onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("新增位置") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("位置简称，例如：龙归A摊") }, singleLine = true)
            OutlinedTextField(address, { address = it }, label = { Text("详细位置（可选）") })
        } },
        confirmButton = { Button(onClick = { if (name.isNotBlank()) onSave(name, address) }) { Text("保存") } },
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
