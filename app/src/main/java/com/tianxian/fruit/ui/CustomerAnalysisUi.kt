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
    onChanged: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val today = LocalDate.now()
    val currentMonth =
        today.withDayOfMonth(1)

    var importing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var range by remember { mutableStateOf(CustomerRange.THIS_MONTH) }
    var monthAnchor by remember {
        mutableStateOf(
            currentMonth
        )
    }
    var dayAnchor by remember {
        mutableStateOf(
            today
        )
    }
    // Keep the filters and navigation tabs fixed. The old onPreScroll handler
    // hid/showed them for every small drag delta, resizing the list while
    // the finger was still moving and causing visible jumps.
    val chromeVisible = true
    var tab by remember { mutableStateOf(CustomerAnalysisTab.OVERVIEW) }
    var customerSection by remember { mutableStateOf(CustomerDetailSection.REPEAT) }
    var showRhythmDetails by remember { mutableStateOf(false) }
    var customStart by remember { mutableStateOf(today.withDayOfMonth(1)) }
    var customEnd by remember { mutableStateOf(today) }
    var selectedPlatform by remember { mutableStateOf(CustomerPaymentPlatform.ALL) }
    var selectedStoreId by remember { mutableLongStateOf(0L) }
    var showStoreMenu by remember { mutableStateOf(false) }
    var showRangeMenu by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    val rawRecords =
        remember(dataVersion) {
            db.getCustomerPayments()
        }
    val storeOptions = remember(rawRecords) {
        rawRecords.filter { it.storeId > 0L }.groupBy { it.storeId }
            .map { (id, rows) ->
                id to rows.firstOrNull { it.storeName.isNotBlank() }
                    ?.storeName.orEmpty().ifBlank { "位置 #$id" }
            }.sortedBy { it.second }
    }
    val hasUnmatchedStore = remember(rawRecords) { rawRecords.any { it.storeId <= 0L } }
    val scopedRawRecords = remember(rawRecords, selectedPlatform, selectedStoreId) {
        PaymentTrafficAnalytics.select(rawRecords, selectedPlatform.name, selectedStoreId)
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
            db.getPaymentImportBatches(10)
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
                    val end =
                        if (
                            monthAnchor ==
                            currentMonth
                        ) {
                            today
                        } else {
                            monthAnchor.withDayOfMonth(
                                monthAnchor.lengthOfMonth()
                            )
                        }
                    monthAnchor.toString() to
                        end.toString()
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

    val periodPlatformCoverage = remember(rawRecords, selectedStoreId, dateRange) {
        PaymentTrafficAnalytics.coverage(
            PaymentTrafficAnalytics.select(rawRecords, storeId = selectedStoreId).filter {
                (dateRange.first == null || it.businessDate >= dateRange.first!!) &&
                    (dateRange.second == null || it.businessDate <= dateRange.second!!)
            }
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
                    runCatching { importPaymentFile(db, uri, context.contentResolver) }
                }
            }
            val imported = results.mapNotNull { it.getOrNull() }
            val failures = results.mapNotNull { it.exceptionOrNull()?.message }
            message = "账单 ${imported.size}/${uris.size} 个成功 · 新增 ${imported.sumOf { it.insertedRows }} 笔 · " +
                "重复 ${imported.sumOf { it.duplicateRows }} 笔 · 忽略 ${imported.sumOf { it.ignoredRows }} 笔 · " +
                "异常 ${imported.sumOf { it.errorRows }} 笔" +
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
                    fontWeight =
                        FontWeight.Bold,
                    color =
                        Color(
                            0xFF087D4E
                        ),
                    modifier =
                        Modifier.weight(
                            1f
                        )
                )
                TextButton(
                    onClick = {
                        launcher.launch(
                            arrayOf(
                                "text/*",
                                "text/csv",
                                "application/csv",
                                "application/zip",
                                "application/vnd.ms-excel",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                "application/octet-stream"
                            )
                        )
                    },
                    enabled =
                        canImport &&
                            !importing,
                    contentPadding =
                        PaddingValues(
                            horizontal = 8.dp
                        )
                ) {
                    Text(
                        if (importing) {
                            "导入中"
                        } else {
                            "导入"
                        }
                    )
                }
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
                modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (range == CustomerRange.THIS_MONTH || range == CustomerRange.TODAY) Text(
                        "‹",
                        modifier = Modifier.width(30.dp).clickable {
                            when (range) {
                                CustomerRange.THIS_MONTH -> monthAnchor = monthAnchor.minusMonths(1)
                                CustomerRange.TODAY -> dayAnchor = dayAnchor.minusDays(1)
                                else -> Unit
                            }
                        },
                        color = Color(0xFF087D4E),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Box {
                        Text(
                            "$rangeLabel ▾",
                            modifier = Modifier.clickable { showRangeMenu = true }.padding(vertical = 9.dp, horizontal = 3.dp),
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1
                        )
                        DropdownMenu(
                            expanded = showRangeMenu,
                            onDismissRequest = { showRangeMenu = false }
                        ) {
                            CustomerRange.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.label) },
                                    onClick = {
                                        showRangeMenu = false
                                        when (option) {
                                            CustomerRange.THIS_MONTH -> {
                                                monthAnchor = currentMonth
                                                range = option
                                            }
                                            CustomerRange.TODAY -> pickDate(dayAnchor) {
                                                dayAnchor = it
                                                range = CustomerRange.TODAY
                                            }
                                            CustomerRange.CUSTOM -> pickDate(customStart) { start ->
                                                pickDate(maxOf(start, customEnd), minDate = start) { finish ->
                                                    customStart = start
                                                    customEnd = finish
                                                    range = CustomerRange.CUSTOM
                                                }
                                            }
                                            else -> range = option
                                        }
                                    }
                                )
                            }
                        }
                    }
                    if (range == CustomerRange.THIS_MONTH || range == CustomerRange.TODAY) Text(
                        "›",
                        modifier = Modifier.width(30.dp).clickable(
                            enabled = (range == CustomerRange.THIS_MONTH && monthAnchor < currentMonth) ||
                                (range == CustomerRange.TODAY && dayAnchor < today)
                        ) {
                            when (range) {
                                CustomerRange.THIS_MONTH ->
                                    if (monthAnchor < currentMonth) monthAnchor = monthAnchor.plusMonths(1)
                                CustomerRange.TODAY ->
                                    if (dayAnchor < today) dayAnchor = dayAnchor.plusDays(1)
                                else -> Unit
                            }
                        },
                        color = if ((range == CustomerRange.TODAY && dayAnchor >= today) ||
                            (range == CustomerRange.THIS_MONTH && monthAnchor >= currentMonth))
                            Color.LightGray else Color(0xFF087D4E),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Box {
                    val storeName = when (selectedStoreId) {
                        0L -> "全部位置"
                        -1L -> "未匹配"
                        else -> storeOptions.firstOrNull { it.first == selectedStoreId }?.second ?: "已失效位置"
                    }
                    Text(
                        "$storeName ▾",
                        modifier = Modifier.clickable { showStoreMenu = true }.padding(8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1
                    )
                    DropdownMenu(expanded = showStoreMenu, onDismissRequest = { showStoreMenu = false }) {
                        DropdownMenuItem(text = { Text("全部位置") }, onClick = {
                            selectedStoreId = 0L
                            showStoreMenu = false
                        })
                        storeOptions.forEach { (id, name) ->
                            DropdownMenuItem(text = { Text(name) }, onClick = {
                                selectedStoreId = id
                                showStoreMenu = false
                            })
                        }
                        if (hasUnmatchedStore) DropdownMenuItem(text = { Text("未匹配位置") }, onClick = {
                            selectedStoreId = -1L
                            showStoreMenu = false
                        })
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
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
            }
            if (importing) {
                LinearProgressIndicator(
                    Modifier.fillMaxWidth()
                )
            }
            if (message.isNotBlank()) {
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
                            Color(
                                0xFF087D4E
                            )
                        },
                    style =
                        MaterialTheme
                            .typography
                            .labelSmall
                )
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

                rawRecords.isEmpty() ->
                    EmptyCustomerAnalysis()

                scopedRawRecords.isEmpty() ->
                    SimpleEmpty("当前支付平台或营业位置没有已导入的收款记录。")

                tab ==
                    CustomerAnalysisTab.RECORDS ->
                    CustomerPaymentRecordsContent(
                        records =
                            periodRawRecords,
                        importBatches = importBatches,
                        settings =
                            settings,
                        excludedIds =
                            filterResult.excludedIds,
                        autoLargeIds =
                            filterResult.autoLargeIds,
                        onStateChange = {
                            id,
                            state ->
                            db.setCustomerPaymentAnalysisState(
                                id,
                                state
                            )
                            onChanged()
                        }
                    )

                allRecords.isEmpty() ->
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
                                dailyTraffic =
                                    dailyTraffic,
                                sameWeekdayComparisons =
                                    sameWeekdayComparisons,
                                singleDay =
                                    range == CustomerRange.TODAY,
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
                                ledgerId = ledgerId,
                                officialDate = if (range == CustomerRange.TODAY) dayAnchor.toString() else null,
                                showWechatOfficial = selectedStoreId == 0L &&
                                    selectedPlatform != CustomerPaymentPlatform.ALIPAY,
                                canEditOfficial = canImport
                            )

                        CustomerAnalysisTab.RECORDS -> Unit
                    }
            }
        }
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
    onStateChange: (Long, String) -> Unit
) {
    var filter by remember {
        mutableStateOf(
            CustomerRecordFilter.ALL
        )
    }

    var showImportHistory by remember { mutableStateOf(false) }

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
            TextButton(onClick = { showImportHistory = !showImportHistory }) {
                Text(if (showImportHistory) "收起导入历史⌃" else "导入历史⌄")
            }
        }
        AnimatedVisibility(showImportHistory) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                ImportBatchList(importBatches.take(5))
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
private fun ImportBatchList(batches: List<PaymentImportBatchRecord>) {
    if (batches.isEmpty()) {
        Text("还没有导入记录", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        batches.forEach { batch ->
            Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFFF8FAF9)) {
                Column(Modifier.padding(8.dp)) {
                    Text(
                        (if (batch.platform == "WECHAT") "微信" else "支付宝") + " · " + batch.fileName,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "识别 ${batch.acceptedRows} · 重复 ${batch.duplicateRows} · ${formatTime(batch.importedAt)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray
                    )
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
    ledgerId: String,
    officialDate: String?,
    showWechatOfficial: Boolean,
    canEditOfficial: Boolean
) {
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            if (showWechatOfficial) {
                OfficialWechatDailyCard(
                    ledgerId = ledgerId,
                    date = officialDate,
                    canEdit = canEditOfficial
                )
            }
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

/**
 * 微信经营码的顾客运营页面有官方新老客统计，但导出交易账单没有
 * 稳定付款方标识。这里只允许手动保存当天的官方汇总，且明确标记
 * 本机存储、不参与账单推算或服务端同步。
 */
@Composable
private fun OfficialWechatDailyCard(
    ledgerId: String,
    date: String?,
    canEdit: Boolean
) {
    val context = LocalContext.current
    var saved by remember(ledgerId, date) {
        mutableStateOf(date?.let { OfficialWechatCustomerStatsLocal.get(context, ledgerId, it) })
    }
    var editing by remember(ledgerId, date) { mutableStateOf(false) }
    var saveError by remember(ledgerId, date) { mutableStateOf(false) }

    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.fillMaxWidth().padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("微信官方新老客", fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f))
                if (date != null && canEdit) {
                    TextButton(onClick = { editing = true },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
                        Text(if (saved == null) "录入" else "修改")
                    }
                }
            }
            if (date == null) {
                Text("切换顶部日期到“单日”，即可查看或录入微信经营码的官方新老客人数。",
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            } else {
                saved?.let { stats ->
                    Text("${date} · 新客 ${stats.newCustomers} · 老客 ${stats.returningCustomers} · 共 ${stats.total}人",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF087D4E))
                    if (stats.total > 0) {
                        Text("官方老客占比 ${String.format(Locale.CHINA, "%.1f", stats.returningCustomers * 100.0 / stats.total)}%",
                            style = MaterialTheme.typography.bodySmall)
                    }
                } ?: Text("${date} 暂无微信官方顾客统计",
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                if (saved != null && canEdit) {
                    TextButton(onClick = {
                        if (OfficialWechatCustomerStatsLocal.delete(context, ledgerId, date)) {
                            saved = null
                            saveError = false
                        } else saveError = true
                    }, contentPadding = PaddingValues(0.dp)) {
                        Text("删除本日录入", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (saveError) {
                Text("本机保存失败，请检查设备存储空间。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error)
            }
            Text("仅为手动摘录的微信经营码官方人数；本机保存、尚不支持云同步。不得与支付宝人数相加，也不能推算个人复购。",
                style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        }
    }

    if (editing && date != null) {
        var newText by remember(editing, ledgerId, date) {
            mutableStateOf(saved?.newCustomers?.toString().orEmpty())
        }
        var oldText by remember(editing, ledgerId, date) {
            mutableStateOf(saved?.returningCustomers?.toString().orEmpty())
        }
        val newCount = newText.toIntOrNull()
        val oldCount = oldText.toIntOrNull()
        val valid = newCount != null && oldCount != null &&
            newCount >= 0 && oldCount >= 0 &&
            newCount.toLong() + oldCount.toLong() <= 1_000_000L
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("微信官方顾客统计 · $date") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("请根据微信经营码「顾客运营」中“今日”的统计手动填写，不要录入猜测人数。",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = newText,
                        onValueChange = { newText = it.filter(Char::isDigit).take(7) },
                        label = { Text("新客人数") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    OutlinedTextField(
                        value = oldText,
                        onValueChange = { oldText = it.filter(Char::isDigit).take(7) },
                        label = { Text("老客人数") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    Text("本机临时记录，暂不随账本同步到其他设备。",
                        style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                }
            },
            confirmButton = {
                TextButton(enabled = valid, onClick = {
                    if (newCount != null && oldCount != null) {
                        val stats = OfficialWechatDailyCount(newCount, oldCount)
                        if (OfficialWechatCustomerStatsLocal.put(context, ledgerId, date, stats)) {
                            saved = stats
                            editing = false
                            saveError = false
                        } else saveError = true
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun CustomerOverview(
    result: CustomerAnalysisResult,
    hourlyTraffic: List<CustomerHourlyPoint>,
    platformHourlyTraffic: List<PaymentPlatformHour>,
    singleDayRecords: List<CustomerPaymentRecord>,
    platformCoverage: List<PaymentPlatformCoverage>,
    dailyTraffic: List<CustomerDailyTrafficPoint>,
    sameWeekdayComparisons: List<CustomerSameWeekdayComparison>,
    singleDay: Boolean,
    payments: List<CustomerPaymentRecord>,
    onRhythm: () -> Unit,
    onSelectDay: (String) -> Unit
) {
    val s = result.summary
    var showQualityDetails by remember { mutableStateOf(false) }
    var showStores by remember { mutableStateOf(false) }
    val reliablePayments = remember(payments) {
        payments.count { PaymentTrafficAnalytics.isIdentified(it) }
    }
    val identityCoverage = if (payments.isNotEmpty()) {
        (reliablePayments * 100.0 / payments.size).toInt()
    } else null
    val missingPlatform = platformCoverage.filter { it.paymentCount == 0 }
    val anonymousPayments = payments.size - reliablePayments
    val unresolved = payments.count { it.storeId <= 0L }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Metric("电子收款", money(s.netRevenue), Modifier.weight(1f))
                    Metric("付款笔数", s.paymentCount.toString(), Modifier.weight(1f))
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
                        }
                    }
                }
            }
        }
        if (singleDay) {
            item {
                HourlyCustomerTrafficCard(
                    points = hourlyTraffic,
                    platformHours = platformHourlyTraffic,
                    dayRecords = singleDayRecords
                )
            }
            if (sameWeekdayComparisons.isNotEmpty()) {
                item { SameWeekdayCustomerCard(sameWeekdayComparisons) }
            }
        } else if (dailyTraffic.isNotEmpty()) {
            item {
                DailyCustomerTrafficCard(
                    points = dailyTraffic,
                    onSelectDay = onSelectDay
                )
            }
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
    dayRecords: List<CustomerPaymentRecord>
) {
    var metric by remember { mutableStateOf(CustomerTrafficMetric.PAYMENTS) }
    val byHour = remember(platformHours) { platformHours.associateBy { it.hour } }
    val active = points.filter { it.paymentCount > 0 || kotlin.math.abs(it.amount) > 0.005 }
    // Display zero-traffic hours too: do not make short periods look like the full shift.
    val firstHour = if (active.any { it.hour < 16 }) 0 else 16
    val lastHour = 23
    val visible = points.filter { it.hour in firstHour..lastHour }
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
