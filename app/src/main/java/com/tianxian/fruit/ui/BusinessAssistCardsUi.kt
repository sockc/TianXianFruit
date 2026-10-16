package com.tianxian.fruit.ui

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tianxian.fruit.data.*
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val AssistGreen = Color(0xFF13A868)
private val AssistSoftGreen = Color(0xFFE9F8F0)
private val AssistSoftOrange = Color(0xFFFFF3E3)
private val AssistSoftGray = Color(0xFFF7F7F8)

@Composable
internal fun AssistBillImportButton(
    db: AppDatabase,
    label: String = "导入",
    onChanged: () -> Unit,
    onResult: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }
    var chooseCollector by remember { mutableStateOf(false) }
    var selectedCollector by remember { mutableStateOf("") }
    val collectorOptions = remember(db) { db.getCollectorSyncOptions() }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null && !importing) {
            importing = true
            onResult("正在识别账单…")
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        importAssistBillFile(db, uri, context.contentResolver).also { outcome ->
                            if (!db.setCustomerBillCollectorForBatch(
                                    outcome.batchId, selectedCollector)) {
                                throw IllegalStateException(
                                    "账单已保存，但收款人归属未保存，请在客户分析导入历史中补选"
                                )
                            }
                        }
                    }
                }
                result.onSuccess { outcome ->
                    onResult(
                        when {
                            outcome.duplicateFile -> "账单已处理过；已更新收款人关联"
                            outcome.insertedRows > 0 || outcome.purchaseCandidateInsertedRows > 0 ->
                                "导入完成：经营收款 ${outcome.insertedRows}笔 · 采购候选 ${outcome.purchaseCandidateInsertedRows}笔"
                            else -> "账单已读取，没有发现新的经营收款或采购候选"
                        }
                    )
                    onChanged()
                }.onFailure {
                    onResult("导入失败或归属待补选：${it.message ?: "无法读取账单"}")
                }
                importing = false
            }
        }
    }
    Text(
        if (importing) "导入中" else label,
        modifier = Modifier.clickable(enabled = !importing) { chooseCollector = true }
            .padding(horizontal = 5.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelMedium,
        color = if (importing) Color.Gray else AssistGreen
    )
    if (chooseCollector) {
        CustomerBillCollectorPicker(
            options = collectorOptions,
            current = selectedCollector,
            title = "导入账单 · 选择收款人",
            onDismiss = { chooseCollector = false },
            onConfirm = { chosen ->
                selectedCollector = chosen
                chooseCollector = false
                launcher.launch(arrayOf(
                    "text/*", "text/csv", "application/csv", "application/zip",
                    "application/vnd.ms-excel",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/octet-stream"
                ))
            }
        )
    }
}

@Composable
internal fun HistoricalPurchaseAssistCard(
    db: AppDatabase,
    date: String,
    dataVersion: Int,
    hasManualPurchase: Boolean,
    manualPurchaseTotal: Double,
    onChanged: () -> Unit
) {
    var message by remember(date) { mutableStateOf("") }
    var expanded by remember(date) { mutableStateOf(false) }

    val rows =
        remember(dataVersion, date) {
            db.getHistoricalPurchaseCandidates(date)
        }
    val summary =
        remember(dataVersion, date) {
            db.getHistoricalPurchaseDaySummary(date)
        }
    val highPending =
        rows.filter {
            it.record.status == "PENDING" &&
                it.confidence == "HIGH"
        }
    val attention =
        hasManualPurchase &&
            highPending.isNotEmpty()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (attention) {
                        AssistSoftOrange
                    } else {
                        AssistSoftGray
                    }
            )
    ) {
        Column(
            Modifier.padding(
                horizontal = 10.dp,
                vertical = 4.dp
            ),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    Modifier.weight(1f)
                ) {
                    Text(
                        "账单采购",
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        buildString {
                            if (summary.pendingCount > 0) {
                                append(
                                    "待核对 ${summary.pendingCount}笔 ${assistMoney(summary.pendingAmount)}"
                                )
                            } else {
                                append("暂无待核对")
                            }
                            if (summary.confirmedCount > 0) {
                                append(
                                    " · 已确认 ${assistMoney(summary.confirmedAmount)}"
                                )
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color =
                            if (attention) {
                                Color(0xFF9A6700)
                            } else {
                                Color.Gray
                            },
                        maxLines = 1
                    )
                }

                AssistBillImportButton(
                    db = db,
                    label = "追加",
                    onChanged = onChanged,
                    onResult = {
                        message = it
                    }
                )

                TextButton(
                    onClick = {
                        expanded = !expanded
                    },
                    contentPadding =
                        PaddingValues(
                            horizontal = 4.dp,
                            vertical = 0.dp
                        )
                ) {
                    Text(
                        if (expanded) "收起" else "详情",
                        style = MaterialTheme.typography.labelMedium,
                        color = AssistGreen
                    )
                }
            }

            if (message.isNotBlank()) {
                Text(
                    message,
                    style = MaterialTheme.typography.labelSmall,
                    color =
                        if (message.startsWith("导入失败")) {
                            MaterialTheme.colorScheme.error
                        } else {
                            AssistGreen
                        },
                    maxLines = 2
                )
            }

            if (expanded) {
                Text(
                    if (hasManualPurchase) {
                        "正式采购 ${assistMoney(manualPurchaseTotal)}；候选仅用于核对，不重复计入采购。"
                    } else {
                        "筛选 06:00–14:00、金额≥¥40 的付款，并结合供应商与历史频率评分。"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.DarkGray
                )

                if (highPending.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = {
                                val count =
                                    db.confirmHighConfidenceHistoricalPurchases(date)
                                message =
                                    "已确认 $count 笔高可信历史采购"
                                onChanged()
                            }
                        ) {
                            Text("确认高可信")
                        }
                    }
                }

                if (rows.isEmpty()) {
                    Text(
                        "当前账单没有采购候选。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                } else {
                    rows.forEach { item ->
                        HistoricalPurchaseCandidateRow(
                            item = item,
                            onConfirm = {
                                db.setHistoricalPurchaseCandidateStatus(
                                    item.record.id,
                                    "CONFIRMED"
                                )
                                onChanged()
                            },
                            onIgnore = {
                                db.setHistoricalPurchaseCandidateStatus(
                                    item.record.id,
                                    "IGNORED"
                                )
                                onChanged()
                            },
                            onSupplier = {
                                db.markHistoricalPurchaseSupplier(
                                    item.record.id
                                )
                                message =
                                    "已将 ${item.record.counterpartyName} 标记为供应商"
                                onChanged()
                            }
                        )
                    }
                }

                Text(
                    "辅助采购不生成正式商品明细，不进入库存成本、利润或结算。",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray
                )
            }
        }
    }
}

@Composable
private fun HistoricalPurchaseCandidateRow(
    item: HistoricalPurchaseCandidateAnalysis,
    onConfirm: () -> Unit,
    onIgnore: () -> Unit,
    onSupplier: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(9.dp),
        color = Color.White
    ) {
        Column(
            Modifier.padding(9.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${item.record.tradeTime.takeLast(8).take(5)} · ${platformLabel(item.record.platform)} · ${item.record.counterpartyName}",
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                    Text(
                        "${confidenceLabel(item.confidence)} ${item.score}分 · ${item.reasons.take(3).joinToString(" · ")}",
                        style = MaterialTheme.typography.labelSmall,
                        color =
                            when (item.confidence) {
                                "HIGH" -> AssistGreen
                                "MEDIUM" -> Color(0xFF9A6700)
                                else -> Color.Gray
                            }
                    )
                }
                Text(
                    assistMoney(item.record.amount),
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFD97706)
                )
            }

            if (item.record.status == "CONFIRMED") {
                Text(
                    "✓ 已确认历史采购",
                    style = MaterialTheme.typography.bodySmall,
                    color = AssistGreen
                )
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    TextButton(onClick = onConfirm) {
                        Text("确认采购")
                    }
                    TextButton(onClick = onIgnore) {
                        Text("不是采购")
                    }
                    if (!item.knownSupplier) {
                        TextButton(onClick = onSupplier) {
                            Text("标记供应商")
                        }
                    } else {
                        Text(
                            "已是供应商",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = AssistGreen
                        )
                    }
                }
            }
        }
    }
}

private data class BusinessAssistSnapshot(
    val wechatTotal: Double,
    val alipayTotal: Double,
    val paymentCount: Int,
    val rawDayCount: Int,
    val coverage: BusinessBillCoverage,
    val canSupplement: Boolean
)

internal data class BusinessPaymentSupplement(
    val wechatTotal: Double,
    val alipayTotal: Double
)

private object BusinessAssistMemoryCache {
    private val values = LinkedHashMap<String, BusinessAssistSnapshot>()
    private fun key(db: AppDatabase, date: String) =
        System.identityHashCode(db).toString() + "|" + date

    fun get(db: AppDatabase, date: String): BusinessAssistSnapshot? =
        synchronized(values) { values[key(db, date)] }

    fun put(db: AppDatabase, date: String, snapshot: BusinessAssistSnapshot) {
        synchronized(values) {
            val item = key(db, date)
            values.remove(item)
            values[item] = snapshot
            while (values.size > 64) {
                values.remove(values.entries.firstOrNull()?.key ?: break)
            }
        }
    }
}

/**
 * Compact read-only business bill status. This is not a day-total deficit
 * calculation. Full explanations are moved into the explicit details sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BusinessPaymentAssistCard(
    db: AppDatabase,
    date: String,
    dataVersion: Int,
    hasManualBusiness: Boolean,
    manualElectronicTotal: Double,
    onSupplementBusiness: (BusinessPaymentSupplement) -> Unit
) {
    var showDetail by remember(date) { mutableStateOf(false) }
    val cached = remember(db, date) { BusinessAssistMemoryCache.get(db, date) }
    val snapshot by produceState<BusinessAssistSnapshot?>(cached, db, dataVersion, date) {
        val fresh = withContext(Dispatchers.IO) {
            val settings = db.getCustomerAnalysisSettings()
            val raw = db.getCustomerPayments(endDate = date)
            val included = CustomerAnalyticsEngine.filterRecords(raw, settings).included
            val day = included.filter { it.businessDate == date &&
                it.amount > 0.005 && it.netAmount > 0.005 }
            val daily = db.getDailyRecordsBetween(date, date)
            val coverage = BusinessBillCoverageEngine.review(
                date = date, savedRecords = daily,
                payments = day, byBatch = db.getCustomerCollectorByBatchId(),
                partnerBySyncId = db.getCustomerCollectorPartnerIds()
            )
            // The existing business form callback replaces BOTH electronic
            // channels. Allow it only for one recorded store, one account,
            // no unresolved receipts and no absent expected channel.
            val singleStore = daily.size == 1 && daily.single().storeId > 0L
            val knownOne = coverage.rows.isNotEmpty() &&
                coverage.rows.map { it.partnerId }.distinct().size == 1 &&
                coverage.rows.none { it.shared } &&
                coverage.rows.all { it.observed } &&
                coverage.importedOutsideBusiness == 0 &&
                coverage.importedUnassigned == 0
            val wechat = day.filter { it.platform == "WECHAT" }.sumOf { it.netAmount }
            val alipay = day.filter { it.platform == "ALIPAY" }.sumOf { it.netAmount }
            BusinessAssistSnapshot(
                wechatTotal = wechat,
                alipayTotal = alipay,
                paymentCount = day.size,
                rawDayCount = raw.count { it.businessDate == date },
                coverage = coverage,
                canSupplement = hasManualBusiness && singleStore && knownOne &&
                    (wechat > 0.005 || alipay > 0.005)
            )
        }
        BusinessAssistMemoryCache.put(db, date, fresh)
        value = fresh
    }
    val current = snapshot
    // Zero-record dates should not occupy a large bill card.
    if (current == null || current.rawDayCount == 0) return
    val amount = current.wechatTotal + current.alipayTotal
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AssistSoftGreen)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("账单收款", fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodySmall)
                Text("${assistMoney(amount)} · ${current.paymentCount}笔 · " +
                    "微信${if (current.wechatTotal > 0.005) "有" else "—"} " +
                    "支付宝${if (current.alipayTotal > 0.005) "有" else "—"}",
                    style = MaterialTheme.typography.bodySmall, maxLines = 1)
                Text(current.coverage.status, color = Color(0xFF9A6700),
                    style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
            TextButton(
                onClick = { showDetail = true },
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
            ) {
                Text("核对 ›", style = MaterialTheme.typography.labelMedium,
                    color = AssistGreen)
            }
        }
    }
    if (showDetail) {
        val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { showDetail = false }, sheetState = state) {
            Column(
                Modifier.fillMaxWidth().fillMaxHeight(0.88f)
                    .verticalScroll(androidx.compose.foundation.rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Text("电子账单收款核对 · $date",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
                Text("已导入 ${assistMoney(amount)} · ${current.paymentCount}笔 · 微信 " +
                    "${assistMoney(current.wechatTotal)} · 支付宝 ${assistMoney(current.alipayTotal)}",
                    style = MaterialTheme.typography.bodySmall)
                Text("按收款人＋渠道核对；已有交易不代表账单覆盖完整，未验证导出期间不计算整日漏收差额。",
                    style = MaterialTheme.typography.labelSmall, color = Color(0xFF9A6700))
                if (current.coverage.rows.isEmpty()) {
                    Text("营业表没有可以匹配的收款人和渠道。请核对营业记录的收款人配置。",
                        style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                } else {
                    current.coverage.rows.forEach { group ->
                        val channel = if (group.platform == "WECHAT") "微信" else "支付宝"
                        HorizontalDivider()
                        Text("${group.collectorName} · $channel",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold)
                        Text("营业表 ${assistMoney(group.businessAmount)} · 账单 ${assistMoney(group.importedAmount)} " +
                            "（${group.importedPayments}笔）",
                            style = MaterialTheme.typography.bodySmall)
                        Text(if (!group.observed) "待导入/待确认该账号账单"
                            else "已有交易，完整性未验证",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF9A6700))
                        if (group.shared) {
                            Text("多位置共享：${group.storeNames.joinToString("、")}。不能把账号合计分摊给某一摊。",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF9A6700))
                        }
                    }
                }
                if (current.coverage.importedUnassigned > 0) {
                    Text("另有${current.coverage.importedUnassigned}笔未指定收款人 " +
                        "${assistMoney(current.coverage.importedUnassignedAmount)}，不参与单人核对。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF9A6700))
                }
                if (current.coverage.importedOutsideBusiness > 0) {
                    Text("另有${current.coverage.importedOutsideBusiness}笔指定了收款人，" +
                        "但当天营业表没有对应渠道；请核对记录。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF9A6700))
                }
                Text("付款笔数不等于独立顾客人数。现金、新老客、库存和利润不会被账单核对修改。",
                    style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                if (current.canSupplement) {
                    TextButton(onClick = {
                        showDetail = false
                        onSupplementBusiness(
                            BusinessPaymentSupplement(current.wechatTotal, current.alipayTotal))
                    }) {
                        Text("补入营业表（需再次确认）")
                    }
                } else {
                    Text("补入：如涉及多个位置/多个收款人或账单缺失，" +
                        "请先进入具体营业记录核对修改，禁止用整日账单覆盖任何一摊。",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray)
                }
                TextButton(onClick = { showDetail = false }) { Text("关闭") }
            }
        }
    }
}

@Composable
private fun AssistMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = Color.White
    ) {
        Column(
            Modifier.padding(
                horizontal = 7.dp,
                vertical = 7.dp
            )
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray,
                maxLines = 1
            )
            Text(
                value,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}

private fun importAssistBillFile(
    db: AppDatabase,
    uri: Uri,
    resolver: ContentResolver
): PaymentImportOutcome {
    var fileName = "账单"
    var declaredSize = -1L

    resolver.query(
        uri,
        arrayOf(
            OpenableColumns.DISPLAY_NAME,
            OpenableColumns.SIZE
        ),
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

    val bytes =
        resolver.openInputStream(uri)
            ?.use { it.readBytes() }
            ?: throw IllegalArgumentException("无法打开所选文件")

    if (bytes.size.toLong() > limit) {
        throw IllegalArgumentException("账单文件超过 30MB，请缩小导出时间范围")
    }

    val parsed =
        PaymentBillParser.parse(
            fileName,
            bytes
        )

    return db.importCustomerPaymentBill(
        fileName = fileName,
        fileHash = PaymentBillParser.fileHash(bytes),
        parsed = parsed
    )
}

private fun assistMoney(value: Double): String =
    String.format(
        Locale.CHINA,
        "¥%.2f",
        value
    )

private fun signedAssistMoney(value: Double): String =
    if (value >= 0) {
        "+" + assistMoney(value)
    } else {
        "-" + assistMoney(abs(value))
    }

private fun platformLabel(platform: String): String =
    when (platform) {
        "WECHAT" -> "微信"
        "ALIPAY" -> "支付宝"
        else -> platform
    }

private fun confidenceLabel(value: String): String =
    when (value) {
        "HIGH" -> "高可信"
        "MEDIUM" -> "待确认"
        else -> "低可信"
    }
