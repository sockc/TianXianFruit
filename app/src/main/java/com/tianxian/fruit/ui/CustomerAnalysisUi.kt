package com.tianxian.fruit.ui

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tianxian.fruit.data.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class CustomerRange(val label: String) {
    TODAY("单日"),
    LAST_7("近7天"),
    THIS_MONTH("本月"),
    LAST_30("近30天"),
    ALL("全部"),
    CUSTOM("自定义")
}

private enum class CustomerAnalysisTab(val label: String) {
    OVERVIEW("概览"),
    CUSTOMERS("客户"),
    RECORDS("账单")
}

private enum class CustomerDetailSection(val label: String) {
    REPEAT("复购"),
    RANKING("排行"),
    LIFECYCLE("生命周期")
}

private enum class CustomerRecordFilter(val label: String) {
    ALL("全部"),
    NORMAL("正常"),
    LARGE("大额"),
    EXCLUDED("已排除")
}

private enum class CustomerPaymentPlatform(val label: String) {
    ALL("合并"), WECHAT("微信"), ALIPAY("支付宝")
}

private enum class CustomerTrafficMetric(val label: String) {
    CUSTOMERS("可识别客户"),
    AMOUNT("金额"),
    PAYMENTS("笔数")
}

private enum class CustomerDailyMetric(val label: String) {
    PAYMENTS("付款笔数"),
    AMOUNT("收款金额"),
    AVERAGE("笔均收款")
}

private enum class CustomerRankingMode(val label: String) {
    AMOUNT("消费金额"),
    VISITS("消费次数"),
    RECENT("最近消费"),
    STABILITY("稳定度")
}

@Composable
internal fun CustomerAnalysisContent(
    db: AppDatabase,
    dataVersion: Int,
    canImport: Boolean,
    ledgerId: String,
    initialDate: String? = null,
    initialStoreId: Long? = null,
    onChanged: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember(context, ledgerId) {
        context.getSharedPreferences("customer_analytics_filters_${ledgerId.hashCode()}", android.content.Context.MODE_PRIVATE)
    }
    val today = LocalDate.now()
    val routeDate = remember(initialDate) {
        initialDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    }
    val currentMonth =
        today.withDayOfMonth(1)

    var importing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var range by remember(ledgerId, initialDate) {
        mutableStateOf(
            if (routeDate != null) CustomerRange.TODAY else
                CustomerRange.entries.firstOrNull {
                    it.name == prefs.getString("range", "")
                } ?: CustomerRange.THIS_MONTH
        )
    }
    var monthAnchor by remember(ledgerId, initialDate) {
        mutableStateOf(
            runCatching { LocalDate.parse(prefs.getString("month", "") ?: "") }
                .getOrNull() ?: currentMonth
        )
    }
    var dayAnchor by remember(ledgerId, initialDate) {
        mutableStateOf(routeDate ?: runCatching {
            LocalDate.parse(prefs.getString("day", "") ?: "")
        }.getOrNull() ?: today)
    }
    // Keep the filters and navigation tabs fixed. The old onPreScroll handler
    // hid/showed them for every small drag delta, resizing the list while
    // the finger was still moving and causing visible jumps.
    val chromeVisible = true
    var tab by remember { mutableStateOf(CustomerAnalysisTab.OVERVIEW) }
    var customerSection by remember { mutableStateOf(CustomerDetailSection.REPEAT) }
    var showRhythmDetails by remember { mutableStateOf(false) }
    var customStart by remember(ledgerId, initialDate) {
        mutableStateOf(runCatching { LocalDate.parse(prefs.getString("custom_start", "") ?: "") }
            .getOrNull() ?: today.withDayOfMonth(1))
    }
    var customEnd by remember(ledgerId, initialDate) {
        mutableStateOf(runCatching { LocalDate.parse(prefs.getString("custom_end", "") ?: "") }
            .getOrNull() ?: today)
    }
    var selectedPlatform by remember(ledgerId, initialDate) {
        mutableStateOf(if (routeDate != null) CustomerPaymentPlatform.ALL else
            CustomerPaymentPlatform.entries.firstOrNull {
                it.name == prefs.getString("platform", "")
            } ?: CustomerPaymentPlatform.ALL)
    }
    var selectedStoreId by remember(ledgerId, initialDate, initialStoreId) {
        // A business-title shortcut explicitly means ALL stores; a row shortcut
        // means its saved store. Only ordinary navigation restores last filters.
        mutableLongStateOf(
            if (routeDate != null) initialStoreId ?: 0L
            else prefs.getLong("store", 0L)
        )
    }
    LaunchedEffect(ledgerId, range, monthAnchor, dayAnchor, customStart, customEnd,
        selectedPlatform, selectedStoreId) {
        prefs.edit()
            .putString("range", range.name)
            .putString("month", monthAnchor.toString())
            .putString("day", dayAnchor.toString())
            .putString("custom_start", customStart.toString())
            .putString("custom_end", customEnd.toString())
            .putString("platform", selectedPlatform.name)
            .putLong("store", selectedStoreId)
            .apply()
    }
    var showStoreMenu by remember { mutableStateOf(false) }
    var showCalendarDialog by remember { mutableStateOf(false) }
    var showBillStatus by remember { mutableStateOf(false) }
    var showImportCollectorPicker by remember { mutableStateOf(false) }
    var importingCollectorSyncId by remember { mutableStateOf("") }
    var editBatchCollector by remember { mutableStateOf<PaymentImportBatchRecord?>(null) }
    var selectedCollectorSyncId by remember { mutableStateOf("*") }
    var showCollectorMenu by remember { mutableStateOf(false) }
    var showMessageDetails by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    var locationEditTick by remember { mutableIntStateOf(0) }
    var showUnifiedStoreDialog by remember { mutableStateOf(false) }
    val trafficSessions = remember(dataVersion) {
        db.getCustomerTrafficSessions()
    }
    val enabledStores = remember(dataVersion, locationEditTick) { db.getStores() }
    val locationAssignments = remember(dataVersion, locationEditTick) {
        db.getCustomerStoreDayAssignments()
    }
    val autoRecords = remember(dataVersion, trafficSessions) {
        CustomerTrafficDeepDive.attributeBusinessDays(
            db.getCustomerPayments(), trafficSessions
        )
    }
    val collectorByBatch = remember(dataVersion) { db.getCustomerCollectorByBatchId() }
    val collectorPartnerIds = remember(dataVersion) { db.getCustomerCollectorPartnerIds() }
    val dailyHistory = remember(dataVersion) { db.getDailyRecordsBetween(null, null) }
    val collectorAttribution = remember(autoRecords, collectorByBatch, collectorPartnerIds, dailyHistory) {
        CustomerCollectorStoreAttribution.resolve(autoRecords, dailyHistory,
            collectorByBatch, collectorPartnerIds, trafficSessions)
    }
    val rawRecords = remember(collectorAttribution, enabledStores, locationAssignments) {
        CustomerUnifiedStoreAttribution.apply(collectorAttribution.rows, locationAssignments,
            enabledStores, collectorAttribution.collectorMatchedIds)
    }
    val unmatchedBeforeUnified = remember(collectorAttribution, dayAnchor) {
        CustomerUnifiedStoreAttribution.unmatchedOnDate(collectorAttribution.rows,
            dayAnchor.toString(), collectorAttribution.collectorMatchedIds)
    }
    val assignedStoreName = enabledStores.firstOrNull {
        it.syncId.isNotBlank() && it.syncId == locationAssignments[dayAnchor.toString()]
    }?.name
    val storeOptions = remember(rawRecords, trafficSessions, dataVersion) {
        val options = linkedMapOf<Long, String>()
        db.getStores().forEach { options[it.id] = it.name }
        trafficSessions.forEach {
            if (it.storeId > 0L && it.storeName.isNotBlank())
                options.putIfAbsent(it.storeId, it.storeName)
        }
        rawRecords.forEach {
            if (it.storeId > 0L && it.storeName.isNotBlank())
                options.putIfAbsent(it.storeId, it.storeName)
        }
        options.entries.map { it.key to it.value }.sortedBy { it.second }
    }
    val businessDatesForSelectedStore = remember(trafficSessions, selectedStoreId) {
        if (selectedStoreId <= 0L) emptyList() else trafficSessions.asSequence()
            .filter { it.storeId == selectedStoreId }
            .mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() }
            .distinct().sorted().toList()
    }
    val prevBusinessDay = businessDatesForSelectedStore.lastOrNull { it < dayAnchor }
    val nextBusinessDay = businessDatesForSelectedStore.firstOrNull { it > dayAnchor }
    val hasUnmatchedStore = remember(rawRecords, collectorAttribution) {
        rawRecords.any { it.storeId <= 0L && it.id !in collectorAttribution.collectorMatchedIds }
    }
    val collectorOptions = remember(dataVersion) { db.getCollectorSyncOptions() }
    val collectorAssignments = remember(dataVersion) { db.getCustomerBillCollectorAssignments() }
    val scopedRawRecords = remember(
        rawRecords, selectedPlatform, selectedStoreId, selectedCollectorSyncId,
        collectorByBatch
    ) {
        PaymentTrafficAnalytics.select(rawRecords, selectedPlatform.name, selectedStoreId).filter { record ->
            // Shared collector groups are known multi-store receipts, not
            // unknown receipts; do not place them in the "未匹配" filter.
            (selectedStoreId != -1L ||
                record.storeName != CustomerCollectorStoreAttribution.SHARED_STORE_NAME) &&
            when (selectedCollectorSyncId) {
                "*" -> true
                "?" -> collectorByBatch[record.sourceBatchId].isNullOrBlank()
                else -> collectorByBatch[record.sourceBatchId] == selectedCollectorSyncId
            }
        }
    }
    val settings =
        remember(dataVersion) {
            db.getCustomerAnalysisSettings()
        }
    val filterResult =
        remember(
            scopedRawRecords,
            settings
        ) {
            CustomerAnalyticsEngine.filterRecords(
                allRecords = scopedRawRecords,
                settings = settings
            )
        }
    val allRecords =
        filterResult.included
    val importBatches = remember(dataVersion, showSettings, tab) {
        if (showSettings || tab == CustomerAnalysisTab.RECORDS) {
            db.getPaymentImportBatches(200)
        } else emptyList()
    }

    val dateRange =
        remember(
            range,
            today,
            monthAnchor,
            dayAnchor,
            customStart,
            customEnd
        ) {
            when (range) {
                CustomerRange.TODAY ->
                    dayAnchor.toString() to
                        dayAnchor.toString()

                CustomerRange.LAST_7 ->
                    today.minusDays(6)
                        .toString() to
                        today.toString()

                CustomerRange.THIS_MONTH -> {
                    monthAnchor.toString() to
                        monthAnchor.withDayOfMonth(monthAnchor.lengthOfMonth()).toString()
                }

                CustomerRange.LAST_30 ->
                    today.minusDays(29)
                        .toString() to
                        today.toString()

                CustomerRange.ALL ->
                    null to null

                CustomerRange.CUSTOM ->
                    customStart.toString() to customEnd.toString()
            }
        }

    val businessCustomerSummary = remember(dailyHistory, dateRange, selectedStoreId) {
        BusinessCustomerRecordSummaryEngine.summarize(
            dailyHistory, dateRange.first, dateRange.second, selectedStoreId
        )
    }

    // The shared collector's payment remains in the overall totals ONCE, but
    // appears as a labelled group in each participating store's analysis.
    val sharedForFilters = remember(collectorAttribution, rawRecords, settings, dateRange,
        selectedPlatform, selectedStoreId, selectedCollectorSyncId, collectorPartnerIds) {
        val admitted = CustomerAnalyticsEngine.filterRecords(rawRecords, settings).included
            .filter { it.netAmount > 0.005 }.associateBy { it.id }
        collectorAttribution.shared.mapNotNull { group ->
            if ((dateRange.first != null && group.date < dateRange.first!!) ||
                (dateRange.second != null && group.date > dateRange.second!!) ||
                (selectedPlatform != CustomerPaymentPlatform.ALL &&
                    group.platform != selectedPlatform.name) ||
                (selectedStoreId > 0L && selectedStoreId !in group.storeIds) ||
                (selectedStoreId == -1L) ||
                (selectedCollectorSyncId != "*" &&
                    (selectedCollectorSyncId == "?" ||
                        collectorPartnerIds[selectedCollectorSyncId] != group.partnerId))) {
                return@mapNotNull null
            }
            val rows = group.receiptIds.mapNotNull(admitted::get)
            if (rows.isEmpty()) null else group.copy(
                receiptIds = rows.map { it.id }.toSet(),
                payments = rows.size,
                amount = rows.sumOf { it.netAmount }
            )
        }
    }

    val periodPlatformCoverage = remember(rawRecords, selectedStoreId, dateRange) {
        PaymentTrafficAnalytics.coverage(
            PaymentTrafficAnalytics.select(rawRecords, storeId = selectedStoreId).filter {
                (dateRange.first == null || it.businessDate >= dateRange.first!!) &&
                    (dateRange.second == null || it.businessDate <= dateRange.second!!)
            }
        )
    }

    val singleDayReconciliations = remember(
        rawRecords, dataVersion, range, dayAnchor, selectedStoreId
    ) {
        if (range != CustomerRange.TODAY || selectedStoreId == -1L) emptyList()
        else CustomerPaymentReconciliationEngine.inspect(
            db.getDailyRecordsBetween(dayAnchor.toString(), dayAnchor.toString()),
            rawRecords, dayAnchor.toString(), selectedStoreId,
            sharedCollectors = collectorAttribution.shared
        )
    }

    val receiptAudit = remember(rawRecords, trafficSessions, dateRange, selectedStoreId, selectedPlatform) {
        PaymentReceiptAuditor.inspect(
            rows = rawRecords,
            sessions = trafficSessions,
            startDate = dateRange.first,
            endDate = dateRange.second,
            storeId = selectedStoreId,
            platform = selectedPlatform.name
        )
    }

    val analysis =
        remember(
            allRecords,
            dateRange,
            today
        ) {
            CustomerAnalyticsEngine.analyze(
                allRecords = allRecords,
                startDate = dateRange.first,
                endDate = dateRange.second,
                today = today
            )
        }

    val periodPayments = remember(allRecords, dateRange) {
        allRecords.filter {
            it.netAmount > 0.005 &&
                (dateRange.first == null || it.businessDate >= dateRange.first!!) &&
                (dateRange.second == null || it.businessDate <= dateRange.second!!)
        }
    }

    val periodRawRecords =
        remember(
            scopedRawRecords,
            dateRange
        ) {
            scopedRawRecords.filter { record ->
                (
                    dateRange.first == null ||
                        record.businessDate >=
                        dateRange.first!!
                    ) &&
                    (
                        dateRange.second == null ||
                            record.businessDate <=
                            dateRange.second!!
                        )
            }
        }

    val rangeLabel =
        when (range) {
            CustomerRange.THIS_MONTH ->
                monthAnchor.format(
                    DateTimeFormatter.ofPattern(
                        "yyyy年M月"
                    )
                )

            CustomerRange.TODAY ->
                dayAnchor.format(
                    DateTimeFormatter.ofPattern(
                        "yyyy年M月d日"
                    )
                )

            CustomerRange.CUSTOM ->
                if (customStart.dayOfMonth == 1 &&
                    customEnd.dayOfMonth == customEnd.lengthOfMonth() &&
                    customStart != customEnd.withDayOfMonth(1)) {
                    if (customStart.year == customEnd.year)
                        "${customStart.year}年${customStart.monthValue}–${customEnd.monthValue}月"
                    else
                        "${customStart.year}/${customStart.monthValue}–${customEnd.year}/${customEnd.monthValue}月"
                } else
                    customStart.format(DateTimeFormatter.ofPattern("M/d")) + "–" +
                        customEnd.format(DateTimeFormatter.ofPattern("M/d"))

            else ->
                range.label
        }

    fun pickDate(
        initial: LocalDate,
        minDate: LocalDate? = null,
        onSelected: (LocalDate) -> Unit
    ) {
        val dialog = android.app.DatePickerDialog(
            context,
            { _, year, month, day -> onSelected(LocalDate.of(year, month + 1, day)) },
            initial.year, initial.monthValue - 1, initial.dayOfMonth
        )
        val zone = java.time.ZoneId.systemDefault()
        minDate?.let { dialog.datePicker.minDate = it.atStartOfDay(zone).toInstant().toEpochMilli() }
        dialog.datePicker.maxDate = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        dialog.show()
    }

    val hourlyTraffic =
        remember(
            allRecords,
            range,
            dayAnchor
        ) {
            if (
                range ==
                CustomerRange.TODAY
            ) {
                CustomerAnalyticsEngine.hourlyTraffic(
                    allRecords =
                        allRecords,
                    date =
                        dayAnchor.toString()
                )
            } else {
                emptyList()
            }
        }

    val platformHourlyTraffic = remember(allRecords, range, dayAnchor) {
        if (range == CustomerRange.TODAY) PaymentTrafficAnalytics.hourly(allRecords, dayAnchor.toString())
        else emptyList()
    }
    val singleDayRecords = remember(allRecords, range, dayAnchor) {
        if (range == CustomerRange.TODAY) allRecords.filter { it.businessDate == dayAnchor.toString() }
        else emptyList()
    }
    val sameWeekdayComparisons =
        remember(
            allRecords,
            range,
            dayAnchor
        ) {
            if (
                range ==
                CustomerRange.TODAY
            ) {
                CustomerAnalyticsEngine.sameWeekdayComparisons(
                    allRecords =
                        allRecords,
                    date =
                        dayAnchor.toString()
                )
            } else {
                emptyList()
            }
        }

    val trafficComparisons = remember(allRecords, trafficSessions, range, dayAnchor,
        selectedStoreId, selectedPlatform) {
        if (range == CustomerRange.TODAY) {
            CustomerTrafficDeepDive.compare(
                rows = allRecords,
                sessions = trafficSessions,
                targetDate = dayAnchor.toString(),
                platform = selectedPlatform.name,
                selectedStoreId = selectedStoreId
            )
        } else emptyList()
    }
    val observedWeather = remember(dataVersion, trafficComparisons) {
        trafficComparisons.flatMap { compare ->
            (listOf(compare.date) + compare.historicDates).distinct().mapNotNull { day ->
                db.getBusinessWeatherHistory(day, compare.storeId)?.let { history ->
                    CustomerObservedWeatherParser.fromHistory(
                        history.copy(date = day, storeId = compare.storeId)
                    )
                }
            }.flatten()
        }
    }
    val weatherResults = remember(trafficComparisons, allRecords, observedWeather, selectedPlatform) {
        CustomerTrafficWeatherAnalyzer.analyze(
            comparisons = trafficComparisons,
            payments = allRecords,
            weather = observedWeather,
            platform = selectedPlatform.name
        )
    }
    val paymentAmountBands = remember(periodPayments) {
        CustomerTrafficDeepDive.amountBands(periodPayments)
    }
    val storeTrafficPatterns = remember(allRecords, trafficSessions, dateRange, selectedPlatform) {
        CustomerTrafficDeepDive.storePatterns(
            allRecords, trafficSessions, selectedPlatform.name,
            dateRange.second ?: today.toString()
        )
    }

    val dailyTraffic =
        remember(
            allRecords,
            range,
            dateRange
        ) {
            if (
                range != CustomerRange.TODAY &&
                dateRange.first != null &&
                dateRange.second != null
            ) {
                CustomerAnalyticsEngine.dailyTraffic(
                    allRecords =
                        allRecords,
                    startDate =
                        dateRange.first!!,
                    endDate =
                        dateRange.second!!
                )
            } else {
                emptyList()
            }
        }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty() || importing) return@rememberLauncherForActivityResult
        importing = true
        message = "正在导入 ${uris.size} 个账单…"
        scope.launch {
            val results = withContext(Dispatchers.IO) {
                uris.take(20).map { uri ->
                    runCatching {
                        importPaymentFile(db, uri, context.contentResolver).also { outcome ->
                            if (!db.setCustomerBillCollectorForBatch(
                                    outcome.batchId, importingCollectorSyncId
                                )) {
                                throw IllegalStateException("账单已解析，但设置收款人失败，请在导入历史里补选")
                            }
                        }
                    }
                }
            }
            val imported = results.mapNotNull { it.getOrNull() }
            val failures = results.mapNotNull { it.exceptionOrNull()?.message }
            val newestRanges = imported.filter { it.firstReceiptDate.isNotBlank() }
                .take(3).joinToString(" · ") { item ->
                    val provider = if (item.platform == "WECHAT") "微信" else "支付宝"
                    "$provider ${item.firstReceiptDate}～${item.lastReceiptDate}（${item.receiptDays}天）"
                }
            message = "账单 ${imported.size}/${uris.size} 个成功 · 新增 ${imported.sumOf { it.insertedRows }} 笔 · " +
                "重复 ${imported.sumOf { it.duplicateRows }} 笔 · 忽略 ${imported.sumOf { it.ignoredRows }} 笔 · " +
                "异常 ${imported.sumOf { it.errorRows }} 笔 · 未匹配位置 ${imported.sumOf { it.unmatchedStoreRows }} 笔" +
                (if (newestRanges.isNotBlank()) " · 发现收款日期：$newestRanges" else "") +
                (if (uris.size > 20) " · 单次最多20个文件" else "") +
                (if (failures.isNotEmpty()) " · ${failures.size} 个失败：${failures.first()}" else "")
            if (imported.isNotEmpty()) onChanged()
            importing = false
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(
                Color(
                    0xFFF7F8F8
                )
            )
    ) {
        AnimatedVisibility(
            visible =
                chromeVisible
        ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = 10.dp,
                    vertical = 4.dp
                ),
            verticalArrangement =
                Arrangement.spacedBy(
                    2.dp
                )
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment =
                    androidx.compose.ui.Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onBack,
                    modifier = Modifier.width(32.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("‹", style = MaterialTheme.typography.headlineSmall)
                }
                Text(
                    "客户分析",
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF087D4E),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1
                )
                Text(
                    "$rangeLabel ▾",
                    modifier = Modifier.weight(1f).clickable {
                        showCalendarDialog = true
                    }.padding(horizontal = 6.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    color = Color(0xFF087D4E),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                TextButton(
                    onClick = {
                        showImportCollectorPicker = true
                    },
                    enabled = canImport && !importing,
                    contentPadding = PaddingValues(horizontal = 5.dp)
                ) { Text(if (importing) "导入中" else "导入") }
                /* File picker follows the collector confirmation. */
                
                TextButton(
                    onClick = {
                        showSettings = true
                    },
                    contentPadding =
                        PaddingValues(
                            horizontal = 8.dp
                        )
                ) {
                    Text("⚙", style = MaterialTheme.typography.titleLarge)
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CustomerPaymentPlatform.entries.forEach { option ->
                    val selected = selectedPlatform == option
                    Surface(
                        color = if (selected) Color.White else Color(0xFFE8EFEB),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f).clickable { selectedPlatform = option }
                    ) {
                        Text(
                            option.label,
                            modifier = Modifier.padding(vertical = 9.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            color = if (selected) Color(0xFF087D4E) else Color.DarkGray,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                Box(contentAlignment = Alignment.CenterEnd, modifier = Modifier.weight(1.25f)) {
                    val storeName = when (selectedStoreId) {
                        0L -> "全部位置"
                        -1L -> "未匹配"
                        else -> storeOptions.firstOrNull { it.first == selectedStoreId }?.second ?: "已失效"
                    }
                    Text(
                        "$storeName ▾",
                        modifier = Modifier.fillMaxWidth().clickable { showStoreMenu = true }
                            .padding(horizontal = 3.dp, vertical = 9.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF087D4E),
                        maxLines = 1,
                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    DropdownMenu(expanded = showStoreMenu, onDismissRequest = { showStoreMenu = false }) {
                        DropdownMenuItem(text = { Text("全部位置") }, onClick = {
                            selectedStoreId = 0L; showStoreMenu = false
                        })
                        storeOptions.forEach { (id, name) ->
                            DropdownMenuItem(text = { Text(name) }, onClick = {
                                selectedStoreId = id; showStoreMenu = false
                            })
                        }
                        if (hasUnmatchedStore) DropdownMenuItem(text = { Text("未匹配位置") }, onClick = {
                            selectedStoreId = -1L; showStoreMenu = false
                        })
                    }
                }
                Box(modifier = Modifier.weight(1.1f), contentAlignment = Alignment.CenterEnd) {
                    val collectorLabel = when (selectedCollectorSyncId) {
                        "*" -> "全部收款人"
                        "?" -> "未指定"
                        else -> collectorOptions.firstOrNull { it.first == selectedCollectorSyncId }?.second
                            ?: "已失效人员"
                    }
                    Text("$collectorLabel ▾",
                        modifier = Modifier.fillMaxWidth().clickable { showCollectorMenu = true }
                            .padding(horizontal = 2.dp, vertical = 9.dp),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
                        style = MaterialTheme.typography.labelSmall, color = Color(0xFF087D4E))
                    DropdownMenu(expanded = showCollectorMenu,
                        onDismissRequest = { showCollectorMenu = false }) {
                        DropdownMenuItem(text = { Text("全部收款人") }, onClick = {
                            selectedCollectorSyncId = "*"; showCollectorMenu = false
                        })
                        collectorOptions.forEach { (id, name) ->
                            DropdownMenuItem(text = { Text(name) }, onClick = {
                                selectedCollectorSyncId = id; showCollectorMenu = false
                            })
                        }
                        DropdownMenuItem(text = { Text("未指定收款人") }, onClick = {
                            selectedCollectorSyncId = "?"; showCollectorMenu = false
                        })
                    }
                }
            }
            if (importing) {
                LinearProgressIndicator(
                    Modifier.fillMaxWidth()
                )
            }
            if (message.isNotBlank()) {
                Row(Modifier.fillMaxWidth().clickable { showMessageDetails = true },
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (message.contains("失败") || message.contains("异常")) "账单处理需要核对"
                        else "账单处理完成",
                        modifier = Modifier.weight(1f),
                        color = if (message.contains("失败")) MaterialTheme.colorScheme.error
                            else Color(0xFF087D4E),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1
                    )
                    Text("详情 ›", color = Color(0xFF087D4E),
                        style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        }

        AnimatedVisibility(
            visible =
                chromeVisible
        ) {
        TabRow(
            selectedTabIndex = tab.ordinal
        ) {
            CustomerAnalysisTab.entries.forEach {
                item ->
                Tab(
                    selected =
                        tab == item,
                    onClick = {
                        tab = item
                        showRhythmDetails = false
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

        // Only the content below the fixed header consumes remaining height.
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when {
                showRhythmDetails && tab == CustomerAnalysisTab.OVERVIEW ->
                    Column(Modifier.fillMaxSize()) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { showRhythmDetails = false }) { Text("‹ 返回概览") }
                            Text("经营节奏 · 正式营业数据",
                                style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        }
                        Box(Modifier.weight(1f)) {
                            CustomerBusinessRhythmContent(
                                db = db,
                                dataVersion = dataVersion,
                                startDate = dateRange.first,
                                endDate = dateRange.second,
                                storeFilterId = selectedStoreId
                            )
                        }
                    }

                rawRecords.isEmpty() && tab != CustomerAnalysisTab.RECORDS &&
                    tab != CustomerAnalysisTab.CUSTOMERS ->
                    EmptyCustomerAnalysis()

                scopedRawRecords.isEmpty() && sharedForFilters.isEmpty() &&
                    tab != CustomerAnalysisTab.RECORDS &&
                    tab != CustomerAnalysisTab.CUSTOMERS ->
                    SimpleEmpty("当前支付平台、营业位置或收款人没有已导入的收款记录。")

                tab ==
                    CustomerAnalysisTab.RECORDS ->
                    Column(Modifier.fillMaxSize()) {
                        Text(
                            if (showBillStatus) "账单日期与同步状态 ⌃" else "账单日期与同步状态 ›",
                            modifier = Modifier.fillMaxWidth().clickable { showBillStatus = !showBillStatus }
                                .padding(horizontal = 12.dp, vertical = 5.dp),
                            color = Color(0xFF087D4E),
                            style = MaterialTheme.typography.labelSmall
                        )
                        AnimatedVisibility(showBillStatus) {
                            CustomerImportCoverageStrip(
                                            records = rawRecords,
                                            sessions = trafficSessions,
                                            initialMonth = java.time.YearMonth.from(
                                                if (range == CustomerRange.THIS_MONTH) monthAnchor else
                                                    if (range == CustomerRange.TODAY) dayAnchor else today
                                            ),
                                            ledgerId = ledgerId,
                                            dataVersion = dataVersion.toLong()
                                        )
                        }
                        Box(Modifier.weight(1f)) {
                            CustomerPaymentRecordsContent(
                                records = periodRawRecords,
                                importBatches = importBatches,
                                settings = settings,
                                excludedIds = filterResult.excludedIds,
                                autoLargeIds = filterResult.autoLargeIds,
                                onStateChange = { id, state ->
                                    db.setCustomerPaymentAnalysisState(id, state)
                                    onChanged()
                                },
                                collectorAssignments = collectorAssignments,
                                collectorOptions = collectorOptions,
                                onAssignBatch = { editBatchCollector = it },
                                onAssignBatches = { batches, collector ->
                                    val ok = batches.all { batch ->
                                        db.setCustomerBillCollector(batch.platform, batch.fileHash, collector)
                                    }
                                    if (ok) onChanged()
                                    ok
                                }
                            )
                        }
                    }

                allRecords.isEmpty() && sharedForFilters.isEmpty() &&
                    tab != CustomerAnalysisTab.CUSTOMERS ->
                    SimpleEmpty(
                        "当前经营收款都被分析规则排除了，可到“设置”或“收款记录”中调整。"
                    )

                else ->
                    when (tab) {
                        CustomerAnalysisTab.OVERVIEW ->
                            CustomerOverview(
                                result =
                                    analysis,
                                hourlyTraffic =
                                    hourlyTraffic,
                                 platformHourlyTraffic = platformHourlyTraffic,
                                 singleDayRecords = singleDayRecords,
                                 platformCoverage = periodPlatformCoverage,
                                 receiptAudit = receiptAudit,
                                 reconciliations = singleDayReconciliations,
                                 sharedCollectorGroups = sharedForFilters,
                                dailyTraffic =
                                    dailyTraffic,
                                sameWeekdayComparisons =
                                    sameWeekdayComparisons,
                                trafficComparisons = trafficComparisons,
                                weatherResults = weatherResults,
                                amountBands = paymentAmountBands,
                                storePatterns = storeTrafficPatterns,
                                savedSessionsForDay = trafficSessions.count {
                                    it.date == dayAnchor.toString() &&
                                        (selectedStoreId == 0L || it.storeId == selectedStoreId)
                                },
                                selectedStoreId = selectedStoreId,
                                singleDay =
                                    range == CustomerRange.TODAY,
                                unmatchedBeforeUnified = unmatchedBeforeUnified,
                                assignedStoreName = assignedStoreName,
                                onUnifiedStore = { showUnifiedStoreDialog = true },
                                payments = periodPayments,
                                onRhythm = { showRhythmDetails = true },
                                onSelectDay = {
                                    selected ->
                                    runCatching {
                                        LocalDate.parse(
                                            selected
                                        )
                                    }.getOrNull()
                                        ?.let {
                                            day ->
                                            dayAnchor =
                                                day
                                            range =
                                                CustomerRange.TODAY
                                        }
                                }
                            )

                        CustomerAnalysisTab.CUSTOMERS ->
                            CustomerDetailsContent(
                                result = analysis,
                                section = customerSection,
                                onSectionChange = { customerSection = it },
                                businessCustomerSummary = businessCustomerSummary,
                                selectedPeriod = rangeLabel
                            )

                        CustomerAnalysisTab.RECORDS -> Unit
                    }
            }
        }
    }

    if (showImportCollectorPicker) {
        CustomerBillCollectorPicker(
            options = collectorOptions,
            current = importingCollectorSyncId,
            title = "导入账单 · 选择收款人",
            onDismiss = { showImportCollectorPicker = false },
            onConfirm = { chosen ->
                importingCollectorSyncId = chosen
                showImportCollectorPicker = false
                launcher.launch(arrayOf(
                    "text/*", "text/csv", "application/csv", "application/zip",
                    "application/vnd.ms-excel",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/octet-stream"
                ))
            }
        )
    }
    editBatchCollector?.let { batch ->
        CustomerBillCollectorPicker(
            options = collectorOptions,
            current = collectorAssignments["${batch.platform}|${batch.fileHash}"].orEmpty(),
            title = "历史账单 · 收款人",
            onDismiss = { editBatchCollector = null },
            onConfirm = { chosen ->
                if (db.setCustomerBillCollector(batch.platform, batch.fileHash, chosen)) {
                    editBatchCollector = null
                    onChanged()
                } else message = "保存历史账单收款人失败，请稍后重试"
            }
        )
    }

    if (showCalendarDialog) {
        CustomerCalendarDialog(
            initialDate = if (range == CustomerRange.TODAY) dayAnchor else
                if (range == CustomerRange.THIS_MONTH) monthAnchor else customStart,
            initialChoice = when (range) {
                CustomerRange.TODAY -> CustomerCalendarSelection.day(dayAnchor)
                CustomerRange.THIS_MONTH -> CustomerCalendarSelection.month(
                    java.time.YearMonth.from(monthAnchor)
                )
                CustomerRange.CUSTOM ->
                    if (customStart.dayOfMonth == 1 &&
                        customEnd.dayOfMonth == customEnd.lengthOfMonth()) {
                        CustomerCalendarSelection.month(
                            java.time.YearMonth.from(customStart),
                            java.time.YearMonth.from(customEnd)
                        )
                    } else CustomerCalendarSelection.days(customStart, customEnd)
                else -> null
            },
            onDismiss = { showCalendarDialog = false },
            onConfirm = { choice ->
                when (choice.kind) {
                    CustomerCalendarChoice.Kind.DAY -> {
                        dayAnchor = choice.start
                        range = CustomerRange.TODAY
                    }
                    CustomerCalendarChoice.Kind.MONTH -> {
                        monthAnchor = choice.start
                        range = CustomerRange.THIS_MONTH
                    }
                    CustomerCalendarChoice.Kind.MONTHS, CustomerCalendarChoice.Kind.DAYS -> {
                        if (choice.start == choice.end) {
                            dayAnchor = choice.start
                            range = CustomerRange.TODAY
                        } else {
                            customStart = choice.start
                            customEnd = choice.end
                            range = CustomerRange.CUSTOM
                        }
                    }
                }
                showCalendarDialog = false
            },
            onQuickRange = { kind ->
                when (kind) {
                    "TODAY", "YESTERDAY" -> {
                        dayAnchor = if (kind == "TODAY") today else today.minusDays(1)
                        range = CustomerRange.TODAY
                    }
                    "THIS_MONTH", "LAST_MONTH" -> {
                        monthAnchor = if (kind == "THIS_MONTH") currentMonth
                            else currentMonth.minusMonths(1)
                        range = CustomerRange.THIS_MONTH
                    }
                    "LAST_7" -> range = CustomerRange.LAST_7
                    "LAST_30" -> range = CustomerRange.LAST_30
                    "ALL" -> range = CustomerRange.ALL
                }
                showCalendarDialog = false
            }
        )
    }
    if (showMessageDetails) {
        AlertDialog(
            onDismissRequest = { showMessageDetails = false },
            title = { Text("账单处理详情") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { showMessageDetails = false }) { Text("关闭") }
            }
        )
    }

    if (showUnifiedStoreDialog) {
        CustomerUnifiedStoreDialog(
            db = db,
            date = dayAnchor.toString(),
            unmatchedPayments = unmatchedBeforeUnified,
            defaultStoreId = selectedStoreId,
            onDismiss = { showUnifiedStoreDialog = false },
            onSaved = {
                showUnifiedStoreDialog = false
                locationEditTick++
                onChanged()
            }
        )
    }

    if (showSettings) {
        CustomerAnalysisSettingsDialog(
            settings = settings,
            importBatches =
                importBatches,
            onDismiss = {
                showSettings = false
            },
            onSave = {
                updated ->
                db.saveCustomerAnalysisSettings(
                    updated
                )
                showSettings = false
                onChanged()
            }
        )
    }
}

@Composable
private fun CustomerAnalysisSettingsDialog(
    settings: CustomerAnalysisSettings,
    importBatches: List<PaymentImportBatchRecord>,
    onDismiss: () -> Unit,
    onSave: (CustomerAnalysisSettings) -> Unit
) {
    var enabled by remember(
        settings
    ) {
        mutableStateOf(
            settings.excludeLargePayments
        )
    }
    var thresholdText by remember(
        settings
    ) {
        mutableStateOf(
            cleanSettingNumber(
                settings.largePaymentThreshold
            )
        )
    }
    val threshold =
        thresholdText
            .toDoubleOrNull()
    val valid =
        threshold != null &&
            threshold >= 1.0

    AlertDialog(
        onDismissRequest =
            onDismiss,
        title = {
            Text("客户分析设置")
        },
        text = {
            Column(
                verticalArrangement =
                    Arrangement.spacedBy(
                        10.dp
                    )
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment =
                        androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Column(
                        Modifier.weight(
                            1f
                        )
                    ) {
                        Text(
                            "排除大额收款",
                            fontWeight =
                                FontWeight.SemiBold
                        )
                        Text(
                            "达到阈值的单笔收款不进入客户分析。",
                            style =
                                MaterialTheme
                                    .typography
                                    .labelSmall,
                            color =
                                Color.Gray
                        )
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = {
                            enabled = it
                        }
                    )
                }

                OutlinedTextField(
                    value =
                        thresholdText,
                    onValueChange = {
                        value ->
                        thresholdText =
                            value.filter {
                                ch ->
                                ch.isDigit() ||
                                    ch == '.'
                            }
                    },
                    label = {
                        Text(
                            "大额阈值（元）"
                        )
                    },
                    enabled = enabled,
                    singleLine = true,
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType =
                                KeyboardType.Decimal
                        ),
                    modifier =
                        Modifier.fillMaxWidth()
                )

                Text(
                    "默认 ¥2000。只影响客户分析、排行、客单价、复购和生命周期；原始账单与正式营业额不会删除或修改。",
                    style =
                        MaterialTheme
                            .typography
                            .bodySmall,
                    color =
                        Color.DarkGray
                )

                if (
                    importBatches.isNotEmpty()
                ) {
                    HorizontalDivider()
                    Text(
                        "最近导入",
                        fontWeight =
                            FontWeight.SemiBold
                    )
                    importBatches
                        .take(
                            3
                        )
                        .forEach {
                            batch ->
                            Text(
                                (
                                    if (
                                        batch.platform ==
                                        "WECHAT"
                                    ) {
                                        "微信"
                                    } else {
                                        "支付宝"
                                    }
                                    ) +
                                    " · " +
                                    batch.fileName +
                                    " · " +
                                    formatTime(
                                        batch.importedAt
                                    ),
                                style =
                                    MaterialTheme
                                        .typography
                                        .labelSmall,
                                color =
                                    Color.Gray,
                                maxLines = 1
                            )
                        }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        CustomerAnalysisSettings(
                            excludeLargePayments =
                                enabled,
                            largePaymentThreshold =
                                threshold
                                    ?: settings.largePaymentThreshold
                        )
                    )
                },
                enabled =
                    !enabled ||
                        valid
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(
                onClick =
                    onDismiss
            ) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun CustomerPaymentRecordsContent(
    records: List<CustomerPaymentRecord>,
    importBatches: List<PaymentImportBatchRecord>,
    settings: CustomerAnalysisSettings,
    excludedIds: Set<Long>,
    autoLargeIds: Set<Long>,
    onStateChange: (Long, String) -> Unit,
    collectorAssignments: Map<String, String>,
    collectorOptions: List<Pair<String, String>>,
    onAssignBatch: (PaymentImportBatchRecord) -> Unit,
    onAssignBatches: (List<PaymentImportBatchRecord>, String) -> Boolean
) {
    var filter by remember {
        mutableStateOf(
            CustomerRecordFilter.ALL
        )
    }

    var showImportHistory by remember { mutableStateOf(false) }
    var showBatchCollectorPicker by remember { mutableStateOf(false) }
    var selectedBatchKeys by remember { mutableStateOf(emptySet<String>()) }
    var batchSaveError by remember { mutableStateOf(false) }

    val payments =
        remember(
            records
        ) {
            records
                .filter {
                    it.amount >
                        0.005
                }
                .sortedWith(
                    compareByDescending<CustomerPaymentRecord> {
                        it.tradeTime
                    }
                        .thenByDescending {
                            it.id
                        }
                )
        }

    val visible =
        remember(
            payments,
            filter,
            excludedIds,
            settings
        ) {
            payments.filter {
                record ->
                val excluded =
                    record.id in
                        excludedIds
                val large =
                    record.amount >=
                        settings.largePaymentThreshold

                when (filter) {
                    CustomerRecordFilter.ALL ->
                        true

                    CustomerRecordFilter.NORMAL ->
                        !excluded

                    CustomerRecordFilter.LARGE ->
                        large

                    CustomerRecordFilter.EXCLUDED ->
                        excluded
                }
            }
        }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("收款记录 · ${visible.size}笔", style = MaterialTheme.typography.bodySmall,
                color = Color.Gray)
            TextButton(onClick = { showImportHistory = true }) {
                Text("账单归属 ›")
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(
                    rememberScrollState()
                )
                .padding(
                    horizontal = 10.dp,
                    vertical = 5.dp
                ),
            horizontalArrangement =
                Arrangement.spacedBy(
                    6.dp
                )
        ) {
            CustomerRecordFilter.entries.forEach {
                item ->
                FilterChip(
                    selected =
                        filter == item,
                    onClick = {
                        filter = item
                    },
                    label = {
                        Text(
                            item.label
                        )
                    }
                )
            }
        }

        if (
            settings.excludeLargePayments
        ) {
            Text(
                "单笔 ≥ ${money(settings.largePaymentThreshold)} 默认不进入客户分析；可在单笔记录中选择“仍计入”。",
                modifier =
                    Modifier.padding(
                        horizontal = 12.dp,
                        vertical = 2.dp
                    ),
                style =
                    MaterialTheme
                        .typography
                        .labelSmall,
                color =
                    Color.Gray
            )
        }

        if (
            visible.isEmpty()
        ) {
            SimpleEmpty(
                "当前筛选没有收款记录。"
            )
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding =
                    PaddingValues(
                        horizontal = 10.dp,
                        vertical = 6.dp
                    ),
                verticalArrangement =
                    Arrangement.spacedBy(
                        6.dp
                    )
            ) {
                items(
                    visible,
                    key = {
                        it.id
                    }
                ) {
                    record ->
                    val excluded =
                        record.id in
                            excludedIds
                    val autoLarge =
                        record.id in
                            autoLargeIds
                    val large =
                        record.amount >=
                            settings.largePaymentThreshold
                    val platform =
                        if (
                            record.platform ==
                            "WECHAT"
                        ) {
                            "微信"
                        } else {
                            "支付宝"
                        }

                    Card(
                        colors =
                            CardDefaults.cardColors(
                                containerColor =
                                    if (excluded) {
                                        Color(
                                            0xFFFFF6E8
                                        )
                                    } else {
                                        Color.White
                                    }
                            )
                    ) {
                        Column(
                            Modifier.padding(
                                horizontal = 10.dp,
                                vertical = 8.dp
                            ),
                            verticalArrangement =
                                Arrangement.spacedBy(
                                    3.dp
                                )
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment =
                                    androidx.compose.ui.Alignment.CenterVertically
                            ) {
                                Column(
                                    Modifier.weight(
                                        1f
                                    )
                                ) {
                                    Text(
                                        record.customerName
                                            .ifBlank {
                                                "匿名客户"
                                            },
                                        fontWeight =
                                            FontWeight.SemiBold,
                                        maxLines = 1
                                    )
                                    Text(
                                        "${formatTradeDateTime(record.tradeTime)} · $platform" +
                                            if (
                                                record.storeName
                                                    .isNotBlank()
                                            ) {
                                                " · ${record.storeName}"
                                            } else {
                                                ""
                                            },
                                        style =
                                            MaterialTheme
                                                .typography
                                                .labelSmall,
                                        color =
                                            Color.Gray,
                                        maxLines = 1
                                    )
                                }
                                Text(
                                    money(
                                        record.amount
                                    ),
                                    fontWeight =
                                        FontWeight.Bold,
                                    color =
                                        if (excluded) {
                                            Color(
                                                0xFF9A6700
                                            )
                                        } else {
                                            Color(
                                                0xFF087D4E
                                            )
                                        }
                                )
                            }

                            val statusText =
                                when {
                                    record.analysisIncludedOverride ->
                                        "已确认计入" +
                                            if (large) {
                                                " · 大额"
                                            } else {
                                                ""
                                            }

                                    record.analysisExcluded ->
                                        "已手动排除"

                                    excluded &&
                                        autoLarge ->
                                        "已按大额规则排除"

                                    large ->
                                        "大额收款"

                                    else ->
                                        "正常经营收款"
                                }

                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment =
                                    androidx.compose.ui.Alignment.CenterVertically
                            ) {
                                Text(
                                    statusText,
                                    modifier =
                                        Modifier.weight(
                                            1f
                                        ),
                                    style =
                                        MaterialTheme
                                            .typography
                                            .bodySmall,
                                    color =
                                        when {
                                            record.analysisIncludedOverride ->
                                                Color(
                                                    0xFF087D4E
                                                )

                                            excluded ->
                                                Color(
                                                    0xFF9A6700
                                                )

                                            else ->
                                                Color.Gray
                                        }
                                )

                                when {
                                    record.analysisIncludedOverride ->
                                        TextButton(
                                            onClick = {
                                                onStateChange(
                                                    record.id,
                                                    "DEFAULT"
                                                )
                                            },
                                            contentPadding =
                                                PaddingValues(
                                                    horizontal = 6.dp
                                                )
                                        ) {
                                            Text(
                                                "恢复默认"
                                            )
                                        }

                                    excluded &&
                                        (
                                            large ||
                                                autoLarge
                                            ) ->
                                        TextButton(
                                            onClick = {
                                                onStateChange(
                                                    record.id,
                                                    "INCLUDED"
                                                )
                                            },
                                            contentPadding =
                                                PaddingValues(
                                                    horizontal = 6.dp
                                                )
                                        ) {
                                            Text(
                                                "仍计入"
                                            )
                                        }

                                    excluded ->
                                        TextButton(
                                            onClick = {
                                                onStateChange(
                                                    record.id,
                                                    "DEFAULT"
                                                )
                                            },
                                            contentPadding =
                                                PaddingValues(
                                                    horizontal = 6.dp
                                                )
                                        ) {
                                            Text(
                                                "恢复计入"
                                            )
                                        }

                                    else ->
                                        TextButton(
                                            onClick = {
                                                onStateChange(
                                                    record.id,
                                                    "EXCLUDED"
                                                )
                                            },
                                            contentPadding =
                                                PaddingValues(
                                                    horizontal = 6.dp
                                                )
                                        ) {
                                            Text(
                                                "排除此笔"
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
    if (showImportHistory) {
        var billQuery by remember { mutableStateOf("") }
        val matchingBatches = remember(importBatches, billQuery, collectorAssignments, collectorOptions) {
            val keyword = billQuery.trim()
            if (keyword.isBlank()) importBatches else importBatches.filter { batch ->
                val collectorId = collectorAssignments["${batch.platform}|${batch.fileHash}"].orEmpty()
                val name = collectorOptions.firstOrNull { it.first == collectorId }?.second
                    ?: "未指定"
                listOf(batch.fileName, batch.platform, batch.firstReceiptDate,
                    batch.lastReceiptDate, name).any { it.contains(keyword, ignoreCase = true) }
            }
        }
        AlertDialog(
            onDismissRequest = { showImportHistory = false },
            title = { Text("历史账单 · 指定收款人", style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("点击每份账单右侧的收款人即可修改归属；不会修改原始账单或营业额。",
                        style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    OutlinedTextField(
                        value = billQuery, onValueChange = { billQuery = it },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        placeholder = { Text("搜索文件、日期或收款人") },
                        textStyle = MaterialTheme.typography.bodySmall
                    )
                    Row(Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("已选 ${selectedBatchKeys.size}份",
                            style = MaterialTheme.typography.labelSmall)
                        TextButton(onClick = {
                            showImportHistory = false
                            showBatchCollectorPicker = true
                        }, enabled = selectedBatchKeys.isNotEmpty()) {
                            Text("批量指定收款人 ›")
                        }
                    }
                    Box(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                        ImportBatchList(
                            matchingBatches, collectorAssignments, collectorOptions,
                            onAssignBatch = {
                                showImportHistory = false
                                onAssignBatch(it)
                            },
                            selectedBatchKeys = selectedBatchKeys,
                            onToggle = { key ->
                                selectedBatchKeys = if (key in selectedBatchKeys)
                                    selectedBatchKeys - key else selectedBatchKeys + key
                            }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showImportHistory = false }) { Text("关闭") }
            }
        )
    }
    if (showBatchCollectorPicker) {
        CustomerBillCollectorPicker(
            options = collectorOptions,
            current = "",
            title = "批量账单 · 指定收款人",
            onDismiss = { showBatchCollectorPicker = false; showImportHistory = true },
            onConfirm = { selectedCollector ->
                val chosen = importBatches.filter {
                    "${it.platform}|${it.fileHash}" in selectedBatchKeys
                }
                batchSaveError = !onAssignBatches(chosen, selectedCollector)
                if (!batchSaveError) {
                    selectedBatchKeys = emptySet()
                    showBatchCollectorPicker = false
                }
            }
        )
    }
    if (batchSaveError) {
        AlertDialog(
            onDismissRequest = { batchSaveError = false },
            title = { Text("批量保存未完成") },
            text = { Text("部分账单可能已更改归属，请重新同步并核对后继续。") },
            confirmButton = {
                TextButton(onClick = { batchSaveError = false }) { Text("知道了") }
            }
        )
    }
}

private fun importPaymentFile(
    db: AppDatabase,
    uri: Uri,
    resolver: ContentResolver
): PaymentImportOutcome {
    var fileName = "账单"
    var declaredSize = -1L
    resolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
        null,
        null,
        null
    )?.use { cursor ->
        if (cursor.moveToFirst()) {
            cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                .takeIf { it >= 0 }
                ?.let { fileName = cursor.getString(it) ?: fileName }
            cursor.getColumnIndex(OpenableColumns.SIZE)
                .takeIf { it >= 0 && !cursor.isNull(it) }
                ?.let { declaredSize = cursor.getLong(it) }
        }
    }

    val limit = 30L * 1024L * 1024L
    if (declaredSize > limit) {
        throw IllegalArgumentException("账单文件超过 30MB，请缩小导出时间范围")
    }

    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
        ?: throw IllegalArgumentException("无法打开所选文件")
    if (bytes.size.toLong() > limit) {
        throw IllegalArgumentException("账单文件超过 30MB，请缩小导出时间范围")
    }

    val parsed = PaymentBillParser.parse(fileName, bytes)
    return db.importCustomerPaymentBill(
        fileName = fileName,
        fileHash = PaymentBillParser.fileHash(bytes),
        parsed = parsed
    )

}

@Composable
private fun ImportOutcomeCard(outcome: PaymentImportOutcome) {
    Surface(shape = RoundedCornerShape(10.dp), color = Color.White) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                if (outcome.platform == "WECHAT") "微信账单" else "支付宝账单",
                fontWeight = FontWeight.Bold
            )
            Text(
                "总行数 ${outcome.totalRows} · 识别 ${outcome.acceptedRows} · 新增 ${outcome.insertedRows} · 重复 ${outcome.duplicateRows}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "非经营交易 ${outcome.ignoredRows} · 退款 ${outcome.refundRows} · 未匹配位置 ${outcome.unmatchedStoreRows}",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
        }
    }
}

@Composable
private fun ImportBatchList(
    batches: List<PaymentImportBatchRecord>,
    assignments: Map<String, String>,
    collectorOptions: List<Pair<String, String>>,
    onAssignBatch: (PaymentImportBatchRecord) -> Unit,
    selectedBatchKeys: Set<String> = emptySet(),
    onToggle: ((String) -> Unit)? = null
) {
    if (batches.isEmpty()) {
        Text("还没有导入记录", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
        return
    }
    LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(batches) { batch ->
            Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFFF8FAF9)) {
                Column(Modifier.padding(8.dp)) {
                    if (onToggle != null) {
                        val key = "${batch.platform}|${batch.fileHash}"
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = key in selectedBatchKeys,
                                onCheckedChange = { onToggle(key) })
                            Text("加入批量指定", style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.clickable { onToggle(key) })
                        }
                    }
                    Text(
                        (if (batch.platform == "WECHAT") "微信" else "支付宝") + " · " + batch.fileName,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        if (batch.firstReceiptDate.isBlank())
                            "无已识别经营收款日期 · 可能为非经营交易或退款文件"
                        else "${if (batch.coverageSource == "FILE") "文件收款日期" else "历史已保存收款日期"}：" +
                            "${batch.firstReceiptDate} ～ ${batch.lastReceiptDate} · " +
                            "${batch.receiptDays}个有收款日 · ${batch.receiptCount}笔" +
                            if (batch.coverageSource == "INSERTED_ONLY")
                                "（旧版去重后留存记录，范围可能不全）" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF087D4E)
                    )
                    Row(Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "识别 ${batch.acceptedRows} · 重复 ${batch.duplicateRows} · ${formatTime(batch.importedAt)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray, modifier = Modifier.weight(1f)
                        )
                        val syncId = assignments["${batch.platform}|${batch.fileHash}"].orEmpty()
                        val collectorName = collectorOptions.firstOrNull { it.first == syncId }?.second
                            ?: "未指定"
                        Text("$collectorName ›",
                            modifier = Modifier.clickable { onAssignBatch(batch) }
                                .padding(horizontal = 4.dp, vertical = 5.dp),
                            color = Color(0xFF087D4E),
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyCustomerAnalysis() {
    SimpleEmpty(
        "还没有经营收款数据\n\n导入微信或支付宝官方账单后，只提取二维码收款 / 经营收款。普通转账、红包、提现和其他交易不会进入客户分析。"
    )
}

@Composable
private fun CustomerDetailsContent(
    result: CustomerAnalysisResult,
    section: CustomerDetailSection,
    onSectionChange: (CustomerDetailSection) -> Unit,
    businessCustomerSummary: BusinessCustomerRecordSummary,
    selectedPeriod: String
) {
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            BusinessRecordCustomerCard(businessCustomerSummary, selectedPeriod)
            Text(
                "历史营业记录的新客/老客为经营表填写的数据；旧记录曾可能被匿名账单错误回填，请以营业表核对为准。本页不会改写营业记录。",
                style = MaterialTheme.typography.labelSmall, color = Color(0xFF9A6700)
            )
            Text(
                if (result.summary.customerCount == 0)
                    "账单暂无可靠的付款人标识，新老客和复购情况无法判定"
                else "账单可识别样本：首次出现 ${result.summary.newCustomerCount} · 历史出现 ${result.summary.oldCustomerCount} · 样本复购率 ${percent(result.summary.repeatRate)}",
                style = MaterialTheme.typography.bodySmall,
                color = if (result.summary.customerCount == 0) Color(0xFF9A6700) else Color(0xFF087D4E),
                fontWeight = FontWeight.Medium
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CustomerDetailSection.entries.forEach { item ->
                    val selected = section == item
                    Surface(
                        modifier = Modifier.weight(1f).clickable { onSectionChange(item) },
                        color = if (selected) Color(0xFFE3F2E9) else Color.White,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(item.label, modifier = Modifier.padding(vertical = 10.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            color = if (selected) Color(0xFF087D4E) else Color.DarkGray,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
        }
        Box(Modifier.weight(1f)) {
            when (section) {
                CustomerDetailSection.REPEAT -> CustomerRepeatAnalysis(result.profiles)
                CustomerDetailSection.RANKING -> CustomerRankingAnalysis(result.profiles)
                CustomerDetailSection.LIFECYCLE ->
                    CustomerLifecycleAnalysis(result.lifecycles, result.profiles)
            }
        }
    }
}

/** Same source as the business form; not a second editable WeChat-only cache. */
@Composable
private fun BusinessRecordCustomerCard(
    stats: BusinessCustomerRecordSummary,
    selectedPeriod: String
) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.fillMaxWidth().padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("营业记录新老客", fontWeight = FontWeight.SemiBold)
            if (!stats.available) {
                Text("$selectedPeriod · 暂无营业记录",
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("新客 ${stats.newCustomers}", style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF087D4E))
                    Text("老客 ${stats.returningCustomers}", style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF087D4E))
                    Text("合计 ${stats.total}", style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold)
                }
                Text("$selectedPeriod · ${stats.operatingDays}个营业日 · ${stats.storeCount}个位置",
                    style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                if (stats.total > 0L) {
                    Text(
                        "老客占比 ${String.format(Locale.CHINA, "%.1f",
                            stats.returningCustomers * 100.0 / stats.total)}%",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Text(
                "直接汇总经营表已保存的新客、老客；按所选日期和营业位置统计。与收款人、支付渠道筛选无关，也不等同于微信官方独立统计。历史的0可能是未填或实际为0。",
                style = MaterialTheme.typography.labelSmall, color = Color.Gray
            )
        }
    }
}

@Composable
private fun CustomerOverview(
    result: CustomerAnalysisResult,
    hourlyTraffic: List<CustomerHourlyPoint>,
    platformHourlyTraffic: List<PaymentPlatformHour>,
    singleDayRecords: List<CustomerPaymentRecord>,
    platformCoverage: List<PaymentPlatformCoverage>,
    receiptAudit: PaymentReceiptAudit,
    reconciliations: List<CustomerPaymentReconciliation>,
    sharedCollectorGroups: List<SharedCollectorTraffic>,
    dailyTraffic: List<CustomerDailyTrafficPoint>,
    sameWeekdayComparisons: List<CustomerSameWeekdayComparison>,
    trafficComparisons: List<CustomerTrafficComparison>,
    weatherResults: List<CustomerWeatherTrafficResult>,
    amountBands: List<CustomerPaymentAmountBand>,
    storePatterns: List<CustomerStoreTrafficPattern>,
    savedSessionsForDay: Int,
    selectedStoreId: Long,
    singleDay: Boolean,
    unmatchedBeforeUnified: Int,
    assignedStoreName: String?,
    onUnifiedStore: () -> Unit,
    payments: List<CustomerPaymentRecord>,
    onRhythm: () -> Unit,
    onSelectDay: (String) -> Unit
) {
    val s = result.summary
    var showQualityDetails by remember { mutableStateOf(false) }
    var showReconciliation by remember { mutableStateOf(false) }
    var showStores by remember { mutableStateOf(false) }
    val reliablePayments = remember(payments) {
        payments.count { PaymentTrafficAnalytics.isIdentified(it) }
    }
    val identityCoverage = if (payments.isNotEmpty()) {
        (reliablePayments * 100.0 / payments.size).toInt()
    } else null
    val missingPlatform = platformCoverage.filter { it.paymentCount == 0 }
    val anonymousPayments = payments.size - reliablePayments
    val unresolved = payments.count { it.storeId <= 0L &&
        it.storeName != CustomerCollectorStoreAttribution.SHARED_STORE_NAME }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (sharedCollectorGroups.isNotEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF6E6))) {
                    Column(Modifier.fillMaxWidth().padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("多位置共享客流 · 不可作为单摊独立客流",
                            fontWeight = FontWeight.SemiBold, color = Color(0xFF9A6700),
                            style = MaterialTheme.typography.bodySmall)
                        sharedCollectorGroups.take(8).forEach { group ->
                            val platformName = if (group.platform == "WECHAT") "微信" else "支付宝"
                            Text("${group.date} · ${group.partnerName} · $platformName " +
                                "${group.payments}笔 / ${money(group.amount)}",
                                style = MaterialTheme.typography.bodySmall)
                            Text("涉及${group.stores.size}个位置：${group.storeNames}",
                                style = MaterialTheme.typography.labelSmall, color = Color.DarkGray)
                        }
                        if (sharedCollectorGroups.size > 8) {
                            Text("另有${sharedCollectorGroups.size - 8}组共享数据",
                                style = MaterialTheme.typography.labelSmall)
                        }
                        Text("合并数据只计一次；单位置的统计和历史对比不分摊这部分账单。",
                            style = MaterialTheme.typography.labelSmall, color = Color(0xFF9A6700))
                    }
                }
            }
        }
        if (singleDay && (unmatchedBeforeUnified > 0 || assignedStoreName != null)) {
            item {
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = onUnifiedStore)
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (assignedStoreName != null)
                            "统一归属：$assignedStoreName · 人为归类"
                        else "未匹配位置 $unmatchedBeforeUnified 笔",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF9A6700)
                    )
                    Text("指定位置 ›", style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF087D4E))
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Metric(if (selectedStoreId > 0L) "独立电子收款" else "电子收款", money(s.netRevenue), Modifier.weight(1f))
                    Metric(if (selectedStoreId > 0L) "独立付款笔数" else "付款笔数", s.paymentCount.toString(), Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Metric("可靠识别付款方", if (s.customerCount > 0) s.customerCount.toString() else "暂无", Modifier.weight(1f))
                    Metric("笔均收款", money(s.averageTicket), Modifier.weight(1f))
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().clickable { showQualityDetails = !showQualityDetails }
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "微信${if (platformCoverage.any { it.platform == "WECHAT" && it.paymentCount > 0 }) " ✓" else " —"}" +
                                "  支付宝${if (platformCoverage.any { it.platform == "ALIPAY" && it.paymentCount > 0 }) " ✓" else " —"}" +
                                "  · 身份覆盖 ${identityCoverage?.let { "$it%" } ?: "暂无"}",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelMedium, maxLines = 2
                        )
                        Text(if (showQualityDetails) "收起⌃" else "详情⌄",
                            color = Color(0xFF087D4E), style = MaterialTheme.typography.labelSmall)
                    }
                    AnimatedVisibility(showQualityDetails) {
                        Column(Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "当前范围：有效付款 ${payments.size}笔 · 可靠身份 ${reliablePayments}笔 · 匿名或不可靠 ${anonymousPayments}笔" +
                                    (if (unresolved > 0) " · 未匹配位置 ${unresolved}笔" else ""),
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text("身份覆盖率按可识别付款笔数÷全部有效付款笔数计算；不是到店客流覆盖率。",
                                style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                            if (missingPlatform.isNotEmpty()) {
                                Text("有支付平台未导入经营收款，合并数据可能不完整。",
                                    style = MaterialTheme.typography.labelSmall, color = Color(0xFF9A6700))
                            }
                            PaymentSourceCoverageCard(platformCoverage)
                            Text(
                                "账单核对 · 未匹配位置 ${receiptAudit.unmatchedPayments}笔 · 退款 ${receiptAudit.refundRows}条" +
                                    " · 流水号重复疑点 ${receiptAudit.repeatedTransactionRefs}组",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.DarkGray
                            )
                            if (receiptAudit.assignedOutsideSavedWindow > 0) {
                                Text(
                                    "有 ${receiptAudit.assignedOutsideSavedWindow}笔历史收款的位置与已保存营业时段不符；可能是旧版错误匹配，请人工核对，未自动修改原始账单。",
                                    style = MaterialTheme.typography.labelSmall, color = Color(0xFF9A6700)
                                )
                            }
                            if (receiptAudit.sessionsWithoutMatchedReceipts.isNotEmpty()) {
                                Text(
                                    "以下营业场次暂无已匹配的当前渠道收款，不代表0笔或未营业：",
                                    style = MaterialTheme.typography.labelSmall, color = Color(0xFF9A6700)
                                )
                                receiptAudit.sessionsWithoutMatchedReceipts.take(4).forEach { day ->
                                    Text("· $day", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                }
                                if (receiptAudit.sessionsWithoutMatchedReceipts.size > 4) {
                                    Text("另有 ${receiptAudit.sessionsWithoutMatchedReceipts.size - 4}个场次待核对",
                                        style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                }
                            }
                            Text("数据缺失可能是未导入、该渠道无交易或位置未匹配；上述核对仅作提示，不回写历史交易。",
                                style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        }
                    }
                }
            }
        }
        if (singleDay) {
            if (reconciliations.isNotEmpty()) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                        Column(Modifier.fillMaxWidth().padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    showReconciliation = !showReconciliation
                                },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("电子收款核对", modifier = Modifier.weight(1f),
                                    fontWeight = FontWeight.SemiBold)
                                Text(if (showReconciliation) "收起⌃" else "详情 ›",
                                    color = Color(0xFF087D4E),
                                    style = MaterialTheme.typography.labelSmall)
                            }
                            Text(
                                "仅对照手工营业额与已导入、已归属该位置的电子收款；账单不完整时差额不代表漏收。",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.Gray
                            )
                            AnimatedVisibility(showReconciliation) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    reconciliations.forEach { item ->
                                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text(item.storeName, fontWeight = FontWeight.SemiBold,
                                                style = MaterialTheme.typography.bodySmall)
                                            Text(
                                                "微信 营业表 ${money(item.wechatBusiness)} · 已导入 ${money(item.wechatObserved)}（${item.wechatPayments}笔）",
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                            Text(
                                                "支付宝 营业表 ${money(item.alipayBusiness)} · 已导入 ${money(item.alipayObserved)}（${item.alipayPayments}笔）",
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                            Text(
                                                if (item.hasSharedAccount)
                                                    "涉及共享收款：微信${item.sharedWechatPayments}笔、支付宝${item.sharedAlipayPayments}笔。不能以单摊差额判断少收。"
                                                else if (item.observedPayments == 0)
                                                    "当日暂无已归属经营收款，不能判定收款为零"
                                                else "账面与已观察电子收款差异：${money(item.difference)}（待核对）",
                                                color = Color(0xFF9A6700),
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                            if (item.unmatchedPaymentsForDay > 0) {
                                                Text(
                                                    "还有 ${item.unmatchedPaymentsForDay}笔当日收款尚未匹配位置，不能擅自计入本摊。",
                                                    style = MaterialTheme.typography.labelSmall, color = Color.Gray
                                                )
                                            }
                                        }
                                    }
                                    Text("现金不对账。跨日退款、导出范围不完整、位置误匹配均可能影响差异。此处不会修改正式营业记录。",
                                        style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                }
                            }
                        }
                    }
                }
            }
            item {
                CustomerTrafficCoverageNote(
                    singleDay = true,
                    sessions = savedSessionsForDay,
                    unmatchedPayments = payments.count { it.storeId <= 0L &&
                        it.storeName != CustomerCollectorStoreAttribution.SHARED_STORE_NAME },
                    selectedStoreId = selectedStoreId,
                    hasWechat = payments.any { it.platform == "WECHAT" },
                    hasAlipay = payments.any { it.platform == "ALIPAY" }
                )
            }
            item {
                HourlyCustomerTrafficCard(
                    points = hourlyTraffic,
                    platformHours = platformHourlyTraffic,
                    dayRecords = singleDayRecords,
                    businessHours = trafficComparisons.firstOrNull()?.hourRows?.map { it.hour }
                )
            }
            if (trafficComparisons.isNotEmpty()) {
                items(trafficComparisons, key = { "traffic_${it.storeId}" }) {
                    CustomerHourlyComparisonCard(it)
                }
                items(weatherResults, key = { "weather_${it.storeId}" }) {
                    CustomerWeatherTrafficCard(it)
                }
            } else {
                item {
                    Text("没有可匹配的正式营业位置或完整小时区间；无法验证同位置历史客流。",
                        color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                }
            }
        } else if (dailyTraffic.isNotEmpty()) {
            item {
                DailyCustomerTrafficCard(
                    points = dailyTraffic,
                    onSelectDay = onSelectDay
                )
            }
        }
        item { CustomerPaymentAmountBandCard(amountBands) }
        if (storePatterns.isNotEmpty()) {
            item { CustomerStoreTrafficPatternCard(storePatterns) }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("可靠身份客户样本", fontWeight = FontWeight.SemiBold)
                    Text(
                        if (s.customerCount == 0)
                            "身份不足：暂不能判断真实的新客、老客及复购率"
                        else "记录首次出现 ${s.newCustomerCount} · 历史出现 ${s.oldCustomerCount} · 样本复购率 ${percent(s.repeatRate)}",
                        color = if (s.customerCount == 0) Color(0xFF9A6700) else Color(0xFF087D4E),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text("只统计有稳定标识的付款方；脱敏昵称不参与，首次出现不等于首次到店。微信官方新老客请到“客户”页单独录入；跨平台不合并。",
                        style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                }
            }
        }
        if (result.stores.size > 1) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column {
                        Row(
                            Modifier.fillMaxWidth().clickable { showStores = !showStores }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("营业位置对比 · ${result.stores.size} 个位置",
                                fontWeight = FontWeight.SemiBold)
                            Text(if (showStores) "收起⌃" else "展开⌄",
                                style = MaterialTheme.typography.labelSmall, color = Color(0xFF087D4E))
                        }
                        AnimatedVisibility(showStores) {
                            Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                result.stores.forEach { store ->
                                    HorizontalDivider()
                                    Row(Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(store.storeName, style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.weight(1f), maxLines = 1)
                                        Text("${store.paymentCount}笔 · ${money(store.netRevenue)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color(0xFF087D4E))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (result.change.available) {
            item { RevenueChangeCard(result.change) }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = onRhythm).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("经营节奏与天气影响", fontWeight = FontWeight.SemiBold)
                        Text("查看同星期、正式营业记录和降雨诊断",
                            style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    }
                    Text("查看 ›", color = Color(0xFF087D4E))
                }
            }
        }
    }
}

@Composable
private fun PaymentSourceCoverageCard(coverage: List<PaymentPlatformCoverage>) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("电子收款账单覆盖", fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                coverage.forEach { item ->
                    Column(Modifier.weight(1f)) {
                        Text(if (item.platform == "WECHAT") "微信" else "支付宝",
                            color = if (item.platform == "WECHAT") Color(0xFF087D4E) else Color(0xFF2777C5),
                            fontWeight = FontWeight.SemiBold)
                        Text(if (item.paymentCount == 0) "未导入" else "${item.paymentCount}笔",
                            style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (item.firstDate == null) "没有经营收款账单"
                            else if (item.firstDate == item.lastDate) item.firstDate
                            else "${item.firstDate} ~ ${item.lastDate}",
                            style = MaterialTheme.typography.labelSmall, color = Color.Gray
                        )
                    }
                }
            }
            if (coverage.any { it.paymentCount == 0 }) {
                Text("有支付平台尚无数据，合并统计可能不完整。",
                    color = Color(0xFF9C5B05), style = MaterialTheme.typography.labelSmall)
            }
            Text("统计仅含已导入的经营收款；现金不在内。跨平台无法可靠识别同一个人，客户数不能视为实际到店人数。",
                color = Color.Gray, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun HourlyCustomerTrafficCard(
    points: List<CustomerHourlyPoint>,
    platformHours: List<PaymentPlatformHour>,
    dayRecords: List<CustomerPaymentRecord>,
    businessHours: List<Int>? = null
) {
    var metric by remember { mutableStateOf(CustomerTrafficMetric.PAYMENTS) }
    val byHour = remember(platformHours) { platformHours.associateBy { it.hour } }
    val active = points.filter { it.paymentCount > 0 || kotlin.math.abs(it.amount) > 0.005 }
    // Display zero-traffic hours too: do not make short periods look like the full shift.
    // Clock hours across midnight remain in selling-session order.
    val visible = if (!businessHours.isNullOrEmpty()) {
        businessHours.mapNotNull { hour -> points.firstOrNull { it.hour == hour } }
    } else {
        (16..23).mapNotNull { h -> points.firstOrNull { it.hour == h } } +
            (0..5).mapNotNull { h -> points.firstOrNull { it.hour == h } }
    }
    var selectedHour by remember(points) {
        mutableIntStateOf(active.maxByOrNull { it.paymentCount }?.hour ?: 18)
    }
    var detailsExpanded by remember { mutableStateOf(false) }
    fun platformValue(hour: Int, platform: String): Double {
        val row = byHour[hour] ?: return 0.0
        return when (metric) {
            CustomerTrafficMetric.PAYMENTS ->
                (if (platform == "WECHAT") row.wechatPayments else row.alipayPayments).toDouble()
            CustomerTrafficMetric.CUSTOMERS ->
                (if (platform == "WECHAT") row.wechatCustomers else row.alipayCustomers).toDouble()
            CustomerTrafficMetric.AMOUNT ->
                (if (platform == "WECHAT") row.wechatAmount else row.alipayAmount).coerceAtLeast(0.0)
        }
    }
    val maxValue = visible.maxOfOrNull {
        platformValue(it.hour, "WECHAT") + platformValue(it.hour, "ALIPAY")
    }?.coerceAtLeast(1.0) ?: 1.0
    val selected = visible.firstOrNull { it.hour == selectedHour }
    val part = byHour[selectedHour]
    val detailRows = remember(dayRecords, selectedHour) {
        dayRecords.filter {
            it.tradeTime.drop(11).take(2).toIntOrNull() == selectedHour
        }.sortedWith(compareByDescending<CustomerPaymentRecord> { it.tradeTime }.thenByDescending { it.id })
    }

    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("逐小时电子支付客流", fontWeight = FontWeight.Bold)
            Text("绿色微信 · 蓝色支付宝；按付款笔数、可识别客户或净收款切换",
                style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            Row(Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CustomerTrafficMetric.entries.forEach { item ->
                    FilterChip(selected = metric == item, onClick = { metric = item },
                        label = { Text(item.label) })
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Bottom) {
                visible.forEach { point ->
                    val w = platformValue(point.hour, "WECHAT")
                    val a = platformValue(point.hour, "ALIPAY")
                    val total = w + a
                    val label = if (metric == CustomerTrafficMetric.AMOUNT)
                        total.toInt().toString() else total.toInt().toString()
                    Column(
                        Modifier.width(44.dp).clickable { selectedHour = point.hour; detailsExpanded = true },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(label, maxLines = 1, style = MaterialTheme.typography.labelSmall,
                            color = if (selectedHour == point.hour) Color(0xFF087D4E) else Color.Gray)
                        Box(Modifier.height(92.dp).width(26.dp),
                            contentAlignment = Alignment.BottomCenter) {
                            Column(verticalArrangement = Arrangement.Bottom,
                                horizontalAlignment = Alignment.CenterHorizontally) {
                                if (a > 0.0) {
                                    Box(Modifier.width(22.dp)
                                        .height((2f + 74f * (a / maxValue).toFloat()).dp)
                                        .background(Color(0xFF4D9AE8), RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)))
                                }
                                if (w > 0.0) {
                                    Box(Modifier.width(22.dp)
                                        .height((2f + 74f * (w / maxValue).toFloat()).dp)
                                        .background(Color(0xFF22AA70), RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)))
                                }
                            }
                        }
                        Text("${point.hour}时", style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (selectedHour == point.hour) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
            if (selected != null && part != null) {
                HorizontalDivider()
                Text("${selectedHour}:00–${selectedHour}:59 · ${selected.paymentCount}笔 · ${money(selected.amount)} · 可识别客户 ${selected.customerCount}",
                    style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                Text("微信 ${part.wechatPayments}笔 / ${money(part.wechatAmount)}；支付宝 ${part.alipayPayments}笔 / ${money(part.alipayAmount)}",
                    style = MaterialTheme.typography.bodySmall)
                Text("新客 ${selected.newCustomerCount} · 老客 ${selected.oldCustomerCount}（仅计算有可靠标识的付款方）",
                    style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                TextButton(onClick = { detailsExpanded = !detailsExpanded },
                    contentPadding = PaddingValues(horizontal = 0.dp)) {
                    Text(if (detailsExpanded) "收起此小时明细 ▲" else "查看此小时交易明细 ▼")
                }
                AnimatedVisibility(visible = detailsExpanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        detailRows.take(12).forEach { record ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                val who = if (PaymentTrafficAnalytics.isIdentified(record))
                                    record.customerName.ifBlank { "可识别付款方" } else "匿名交易"
                                Text("${record.tradeTime.takeLast(8)} · ${if (record.platform == "WECHAT") "微信" else "支付宝"} · $who",
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1, style = MaterialTheme.typography.labelSmall)
                                Text(money(record.netAmount), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        if (detailRows.size > 12) {
                            Text("另外 ${detailRows.size - 12} 条请到“收款记录”查看。",
                                style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        }
                    }
                }
            }
            active.maxByOrNull { it.paymentCount }?.let { peak ->
                Text("付款高峰：${peak.hour}:00–${peak.hour}:59 · ${peak.paymentCount}笔",
                    color = Color(0xFF087D4E),
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodySmall)
            }
            Text("付款笔数不等于实际客流，同一顾客可能多次付款或跨平台付款。",
                style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        }
    }
}

@Composable
private fun DailyCustomerTrafficCard(
    points: List<CustomerDailyTrafficPoint>,
    onSelectDay: (String) -> Unit
) {
    var metric by remember { mutableStateOf(CustomerDailyMetric.PAYMENTS) }
    fun value(p: CustomerDailyTrafficPoint): Double = when (metric) {
        CustomerDailyMetric.PAYMENTS -> p.paymentCount.toDouble()
        CustomerDailyMetric.AMOUNT -> p.amount
        CustomerDailyMetric.AVERAGE ->
            if (p.paymentCount > 0) p.amount / p.paymentCount else 0.0
    }
    val maxValue = (points.maxOfOrNull { value(it) } ?: 0.0).coerceAtLeast(1.0)

    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("每日付款趋势", fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                CustomerDailyMetric.entries.forEach { option ->
                    FilterChip(selected = metric == option, onClick = { metric = option },
                        label = { Text(option.label) })
                }
            }
            Text("包含当前筛选范围内的微信与支付宝有效交易；点某天查看逐小时数据。",
                style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.Bottom) {
                points.forEach { point ->
                    val count = value(point)
                    val ratio = (count.coerceAtLeast(0.0) / maxValue).toFloat().coerceIn(0f, 1f)
                    Column(
                        Modifier.width(43.dp).clickable { onSelectDay(point.date) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(
                            if (count == 0.0) "0"
                            else if (metric == CustomerDailyMetric.AVERAGE)
                                String.format(Locale.CHINA, "%.1f", count)
                            else String.format(Locale.CHINA, "%.0f", count),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            color = Color(0xFF087D4E)
                        )
                        Box(Modifier.height(82.dp).width(26.dp),
                            contentAlignment = Alignment.BottomCenter) {
                            Box(
                                Modifier.fillMaxWidth()
                                    .height(if (count > 0.0) (5f + 70f * ratio).dp else 2.dp)
                                    .background(Color(0xFF5CB98D),
                                        RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                            )
                        }
                        Text(
                            runCatching { LocalDate.parse(point.date)
                                .format(DateTimeFormatter.ofPattern("M/d")) }
                                .getOrDefault(point.date.takeLast(5)),
                            style = MaterialTheme.typography.labelSmall, color = Color.Gray
                        )
                    }
                }
            }
            Text("付款笔数 ≠ 独立顾客人数；0笔也可能是尚未导入该日账单。",
                style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        }
    }
}

@Composable
private fun SameWeekdayCustomerCard(
    rows: List<CustomerSameWeekdayComparison>
) {
    Card(
        colors =
            CardDefaults.cardColors(
                containerColor =
                    Color.White
            )
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
            Text(
                "同位置 · 同星期比较",
                fontWeight =
                    FontWeight.Bold
            )

            rows.forEachIndexed {
                index,
                row ->
                if (
                    index >
                    0
                ) {
                    HorizontalDivider()
                }

                val changeText =
                    row.percentChange
                        ?.let {
                            value ->
                            val sign =
                                if (
                                    value >=
                                    0
                                ) {
                                    "+"
                                } else {
                                    ""
                                }
                            "$sign${String.format(Locale.CHINA, "%.1f%%", value * 100.0)}"
                        }
                        ?: "—"

                Text(
                    row.storeName,
                    fontWeight =
                        FontWeight.SemiBold
                )
                Text(
                    "当天 ${row.currentCustomers} 人 · 最近 ${row.sampleDays} 个同星期平均 ${String.format(Locale.CHINA, "%.1f", row.averageCustomers)} 人 · ${signedInt(row.difference)} 人（$changeText）",
                    style =
                        MaterialTheme
                            .typography
                            .bodySmall,
                    color =
                        if (
                            row.difference >=
                            0
                        ) {
                            Color(
                                0xFF087D4E
                            )
                        } else {
                            Color(
                                0xFF9A6700
                            )
                        }
                )
            }
        }
    }
}

private fun trafficValueLabel(
    metric: CustomerTrafficMetric,
    value: Double
): String =
    when (metric) {
        CustomerTrafficMetric.CUSTOMERS,
        CustomerTrafficMetric.PAYMENTS ->
            value
                .toInt()
                .toString()

        CustomerTrafficMetric.AMOUNT ->
            if (
                value >=
                1000
            ) {
                String.format(
                    Locale.CHINA,
                    "%.1fk",
                    value /
                        1000.0
                )
            } else {
                value
                    .toInt()
                    .toString()
            }
    }

@Composable
private fun RevenueChangeCard(change: CustomerChangeBreakdown) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("经营收款变化拆解", fontWeight = FontWeight.Bold)
            Text(change.mainReason, color = Color(0xFF087D4E), fontWeight = FontWeight.SemiBold)
            Text(
                "经营收款 ${signedMoney(change.revenueChange)}；客户 ${signedInt(change.customerChange)} 人。",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "客户数影响约 ${signedMoney(change.customerCountEffect)} · 客均贡献影响约 ${signedMoney(change.customerValueEffect)}",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
            Text(
                "本期客均 ${money(change.currentAverageCustomerValue)}，上一等长周期 ${money(change.previousAverageCustomerValue)}。",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
        }
    }
}

@Composable
private fun CustomerRepeatAnalysis(profiles: List<CustomerProfileAnalysis>) {
    val rows = profiles
        .filter { it.lifetimeVisits >= 2 }
        .sortedWith(
            compareByDescending<CustomerProfileAnalysis> { it.stabilityScore }
                .thenByDescending { it.periodVisits }
        )

    if (rows.isEmpty()) {
        SimpleEmpty("当前周期还没有形成可分析的复购客户。")
        return
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            CustomerRepeatIntervalSummary(rows)
        }
        item {
            Text(
                "优先看“多久来一次”和“稳定指数”，比只看累计消费更适合判断固定位置的老客。",
                color = Color.Gray,
                style = MaterialTheme.typography.bodySmall
            )
        }
        items(rows, key = { it.customerKey }) { CustomerProfileCard(it, true) }
    }
}

@Composable
private fun CustomerRepeatIntervalSummary(profiles: List<CustomerProfileAnalysis>) {
    val reliable = profiles.filter {
        it.identityConfidence != "LOW" &&
            it.lifetimeVisits >= 2 &&
            (it.averageIntervalDays ?: 0.0) > 0.0
    }
    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text("老客复购间隔", fontWeight = FontWeight.Bold)
            if (reliable.isEmpty()) {
                Text("稳定身份且至少购买两次的客户不足，暂不能判断最佳出摊间隔。",
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            } else {
                val bands = listOf(
                    "约1天" to reliable.count { it.averageIntervalDays!! < 1.5 },
                    "2—3天" to reliable.count { it.averageIntervalDays!! in 1.5..3.5 },
                    "4—7天" to reliable.count { it.averageIntervalDays!! > 3.5 && it.averageIntervalDays!! <= 7.5 },
                    "超过7天" to reliable.count { it.averageIntervalDays!! > 7.5 }
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    bands.forEach { (label, count) ->
                        Column(Modifier.weight(1f)) {
                            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            Text("$count", fontWeight = FontWeight.Bold, color = Color(0xFF087D4E))
                            Text("${(count * 100.0 / reliable.size).toInt()}%",
                                style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        }
                    }
                }
                Text(
                    "共 ${reliable.size} 位可分析复购客户。按每人平均再次购买间隔分组，不等于推荐出摊天数；需结合利润、天气和工厂排班。",
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray
                )
            }
        }
    }
}

@Composable
private fun CustomerRankingAnalysis(profiles: List<CustomerProfileAnalysis>) {
    var mode by remember { mutableStateOf(CustomerRankingMode.AMOUNT) }
    val sorted = remember(profiles, mode) {
        when (mode) {
            CustomerRankingMode.AMOUNT -> profiles.sortedByDescending { it.periodAmount }
            CustomerRankingMode.VISITS ->
                profiles.sortedWith(
                    compareByDescending<CustomerProfileAnalysis> { it.periodVisits }
                        .thenByDescending { it.periodAmount }
                )
            CustomerRankingMode.RECENT -> profiles.sortedByDescending { it.lastDate }
            CustomerRankingMode.STABILITY -> profiles.sortedByDescending { it.stabilityScore }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            CustomerRankingMode.entries.forEach { item ->
                FilterChip(
                    selected = mode == item,
                    onClick = { mode = item },
                    label = { Text(item.label) }
                )
            }
        }
        if (sorted.isEmpty()) {
            SimpleEmpty("当前周期没有客户数据。")
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(sorted.take(100), key = { it.customerKey }) { CustomerProfileCard(it, false) }
            }
        }
    }
}

@Composable
private fun CustomerLifecycleAnalysis(
    counts: List<CustomerLifecycleCount>,
    profiles: List<CustomerProfileAnalysis>
) {
    var selected by remember { mutableStateOf<String?>(null) }
    val visible = remember(profiles, selected) {
        selected?.let { label -> profiles.filter { it.lifecycle == label } } ?: profiles
    }

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text("生命周期", fontWeight = FontWeight.Bold)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                counts.filter { it.count > 0 }.forEach { item ->
                    FilterChip(
                        selected = selected == item.label,
                        onClick = { selected = if (selected == item.label) null else item.label },
                        label = { Text("${item.label} ${item.count}") }
                    )
                }
            }
            Text(
                "可能流失按客户自己的复购间隔判断；沉睡客要求历史至少 3 次消费且 45 天未出现。",
                color = Color.Gray,
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (visible.isEmpty()) {
            SimpleEmpty("没有符合条件的客户。")
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(visible, key = { it.customerKey }) { CustomerProfileCard(it, true) }
            }
        }
    }
}

@Composable
private fun CustomerStoreAnalysisContent(stores: List<CustomerStoreAnalysis>) {
    if (stores.isEmpty()) {
        SimpleEmpty("当前周期没有可分析的位置数据。")
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(
                "位置只用于客户分析，不修改营业记录。多位置同时营业且无法唯一判断时进入“未匹配位置”。",
                color = Color.Gray,
                style = MaterialTheme.typography.bodySmall
            )
        }
        items(stores, key = { it.storeName }) { store ->
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        Text(store.storeName, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text(money(store.netRevenue), color = Color(0xFF087D4E), fontWeight = FontWeight.Bold)
                    }
                    Text(
                        "客户 ${store.customerCount} · 收款 ${store.paymentCount} 笔 · 平均客单 ${money(store.averageTicket)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "位置内复购客户 ${store.repeatCustomerCount} · 复购率 ${percent(store.repeatRate)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }
            }
        }
    }
}

@Composable
private fun CustomerProfileCard(profile: CustomerProfileAnalysis, showRepeat: Boolean) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(profile.customerName, fontWeight = FontWeight.Bold)
                    Text(
                        "${profile.platformLabel} · ${confidenceLabel(profile.identityConfidence)} · ${profile.lifecycle}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray
                    )
                }
                Text(money(profile.periodAmount), color = Color(0xFF087D4E), fontWeight = FontWeight.Bold)
            }
            Text(
                "本期 ${profile.periodVisits} 次 / ${profile.periodPayments} 笔 · 历史 ${profile.lifetimeVisits} 次 · 最近 ${profile.lastDate}",
                style = MaterialTheme.typography.bodySmall
            )
            if (showRepeat) {
                val interval = profile.averageIntervalDays?.let {
                    String.format(Locale.CHINA, "%.1f 天", it)
                } ?: "资料不足"
                val storeText = if (profile.commonStore.isNotBlank()) " · 常去 ${profile.commonStore}" else ""
                Text(
                    "平均间隔 $interval · 稳定指数 ${profile.stabilityScore}分$storeText",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 9.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = Color.Gray, maxLines = 1)
            Text(value, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
private fun SimpleEmpty(text: String) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(text, color = Color.Gray)
    }
}

private fun cleanSettingNumber(
    value: Double
): String =
    if (
        kotlin.math.abs(
            value -
                value.toLong()
        ) <
        0.000001
    ) {
        value.toLong()
            .toString()
    } else {
        String.format(
            Locale.CHINA,
            "%.2f",
            value
        )
            .trimEnd('0')
            .trimEnd('.')
    }

private fun formatTradeDateTime(
    value: String
): String {
    if (
        value.length >=
        16
    ) {
        return value.substring(
            5,
            16
        )
    }
    return value
}

private fun confidenceLabel(value: String): String = when (value) {
    "HIGH" -> "稳定标识"
    "MEDIUM" -> "昵称识别"
    else -> "匿名"
}

private fun money(value: Double): String = String.format(Locale.CHINA, "¥%.2f", value)

private fun signedMoney(value: Double): String =
    if (value >= 0) "+" + money(value) else "-" + money(kotlin.math.abs(value))

private fun signedInt(value: Int): String = if (value >= 0) "+$value" else value.toString()

private fun percent(value: Double): String =
    String.format(Locale.CHINA, "%.1f%%", value * 100.0)

private fun formatTime(timestamp: Long): String =
    runCatching {
        Instant.ofEpochMilli(timestamp)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
    }.getOrDefault("")
