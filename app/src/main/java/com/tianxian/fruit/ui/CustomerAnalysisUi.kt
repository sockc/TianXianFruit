package com.tianxian.fruit.ui

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
    TODAY("今天"),
    LAST_7("近7天"),
    THIS_MONTH("本月"),
    LAST_30("近30天"),
    ALL("全部")
}

private enum class CustomerAnalysisTab(val label: String) {
    OVERVIEW("概览"),
    REPEAT("复购"),
    RANKING("排行"),
    LIFECYCLE("生命周期"),
    STORE("位置"),
    RECORDS("收款记录")
}

private enum class CustomerRecordFilter(val label: String) {
    ALL("全部"),
    NORMAL("正常"),
    LARGE("大额"),
    EXCLUDED("已排除")
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
    onChanged: () -> Unit
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
    var tab by remember { mutableStateOf(CustomerAnalysisTab.OVERVIEW) }
    var showRangeMenu by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    val rawRecords =
        remember(dataVersion) {
            db.getCustomerPayments()
        }
    val settings =
        remember(dataVersion) {
            db.getCustomerAnalysisSettings()
        }
    val filterResult =
        remember(
            rawRecords,
            settings
        ) {
            CustomerAnalyticsEngine.filterRecords(
                allRecords = rawRecords,
                settings = settings
            )
        }
    val allRecords =
        filterResult.included
    val importBatches =
        remember(
            dataVersion,
            showSettings
        ) {
            if (showSettings) {
                db.getPaymentImportBatches(5)
            } else {
                emptyList()
            }
        }

    val dateRange =
        remember(
            range,
            today,
            monthAnchor
        ) {
            when (range) {
                CustomerRange.TODAY ->
                    today.toString() to
                        today.toString()

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
            }
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

    val periodRawRecords =
        remember(
            rawRecords,
            dateRange
        ) {
            rawRecords.filter { record ->
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
            else ->
                range.label
        }

    val launcher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (
                uri == null ||
                importing
            ) {
                return@rememberLauncherForActivityResult
            }
            importing = true
            message = "正在解析账单…"
            scope.launch {
                val result =
                    withContext(
                        Dispatchers.IO
                    ) {
                        runCatching {
                            importPaymentFile(
                                db = db,
                                uri = uri,
                                resolver =
                                    context.contentResolver
                            )
                        }
                    }
                result.onSuccess { outcome ->
                    message =
                        if (
                            outcome.duplicateFile
                        ) {
                            "这个账单文件已经导入过，没有重复写入"
                        } else {
                            "导入完成：新增 ${outcome.insertedRows} 笔经营收款"
                        }
                    onChanged()
                }.onFailure { error ->
                    message =
                        "导入失败：${error.message ?: "无法读取账单"}"
                }
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
                    Text("设置")
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment =
                    androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement =
                    Arrangement.Center
            ) {
                TextButton(
                    onClick = {
                        monthAnchor =
                            monthAnchor.minusMonths(
                                1
                            )
                    },
                    enabled =
                        range ==
                            CustomerRange.THIS_MONTH,
                    contentPadding =
                        PaddingValues(
                            horizontal = 8.dp
                        )
                ) {
                    Text("‹")
                }

                Box {
                    TextButton(
                        onClick = {
                            showRangeMenu = true
                        },
                        contentPadding =
                            PaddingValues(
                                horizontal = 10.dp
                            )
                    ) {
                        Text(
                            "$rangeLabel ▼",
                            fontWeight =
                                FontWeight.SemiBold
                        )
                    }
                    DropdownMenu(
                        expanded =
                            showRangeMenu,
                        onDismissRequest = {
                            showRangeMenu = false
                        }
                    ) {
                        listOf(
                            CustomerRange.THIS_MONTH,
                            CustomerRange.TODAY,
                            CustomerRange.LAST_7,
                            CustomerRange.LAST_30,
                            CustomerRange.ALL
                        ).forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        option.label
                                    )
                                },
                                onClick = {
                                    range =
                                        option
                                    if (
                                        option ==
                                        CustomerRange.THIS_MONTH
                                    ) {
                                        monthAnchor =
                                            currentMonth
                                    }
                                    showRangeMenu =
                                        false
                                }
                            )
                        }
                    }
                }

                TextButton(
                    onClick = {
                        if (
                            monthAnchor <
                            currentMonth
                        ) {
                            monthAnchor =
                                monthAnchor.plusMonths(
                                    1
                                )
                        }
                    },
                    enabled =
                        range ==
                            CustomerRange.THIS_MONTH &&
                            monthAnchor <
                            currentMonth,
                    contentPadding =
                        PaddingValues(
                            horizontal = 8.dp
                        )
                ) {
                    Text("›")
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

        ScrollableTabRow(
            selectedTabIndex =
                tab.ordinal,
            edgePadding = 4.dp
        ) {
            CustomerAnalysisTab.entries.forEach {
                item ->
                Tab(
                    selected =
                        tab == item,
                    onClick = {
                        tab = item
                    },
                    text = {
                        Text(
                            item.label
                        )
                    }
                )
            }
        }

        Box(
            Modifier.fillMaxSize()
        ) {
            when {
                rawRecords.isEmpty() ->
                    EmptyCustomerAnalysis()

                tab ==
                    CustomerAnalysisTab.RECORDS ->
                    CustomerPaymentRecordsContent(
                        records =
                            periodRawRecords,
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
                                analysis
                            )

                        CustomerAnalysisTab.REPEAT ->
                            CustomerRepeatAnalysis(
                                analysis.profiles
                            )

                        CustomerAnalysisTab.RANKING ->
                            CustomerRankingAnalysis(
                                analysis.profiles
                            )

                        CustomerAnalysisTab.LIFECYCLE ->
                            CustomerLifecycleAnalysis(
                                analysis.lifecycles,
                                analysis.profiles
                            )

                        CustomerAnalysisTab.STORE ->
                            CustomerStoreAnalysisContent(
                                analysis.stores
                            )

                        CustomerAnalysisTab.RECORDS ->
                            Unit
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
private fun CustomerOverview(result: CustomerAnalysisResult) {
    val s = result.summary
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Metric("客户", s.customerCount.toString(), Modifier.weight(1f))
                Metric("新客", s.newCustomerCount.toString(), Modifier.weight(1f))
                Metric("老客", s.oldCustomerCount.toString(), Modifier.weight(1f))
                Metric("复购率", percent(s.repeatRate), Modifier.weight(1f))
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Metric("经营收款", money(s.netRevenue), Modifier.weight(1f))
                Metric("平均客单", money(s.averageTicket), Modifier.weight(1f))
                Metric("客均贡献", money(s.averageCustomerValue), Modifier.weight(1f))
                Metric("收款笔数", s.paymentCount.toString(), Modifier.weight(1f))
            }
        }
        if (result.change.available) item { RevenueChangeCard(result.change) }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("客户识别质量", fontWeight = FontWeight.Bold)
                    Text(
                        "稳定标识 ${s.highConfidenceCount} · 昵称识别 ${s.mediumConfidenceCount} · 匿名 ${s.lowConfidenceCount}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "复购优先依赖稳定付款方标识；只有昵称时标记为中等可信，避免把复购率包装成绝对准确。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                    if (s.unmatchedStoreCount > 0) {
                        HorizontalDivider()
                        Text(
                            "有 ${s.unmatchedStoreCount} 笔收款未能唯一匹配营业位置，多位置同时间营业时不会自动猜位置。",
                            color = Color(0xFF9A6700),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC))) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("与营业账完全分开", fontWeight = FontWeight.Bold)
                    Text(
                        "这里的经营收款只用于客户行为、复购和客流分析。营业表仍是正式营业额来源，结算与利润逻辑不会读取这里。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.DarkGray
                    )
                }
            }
        }
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
