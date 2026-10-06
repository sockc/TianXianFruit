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

    val launcher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri == null || importing) {
                return@rememberLauncherForActivityResult
            }
            importing = true
            onResult("正在识别账单…")
            scope.launch {
                val result =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            importAssistBillFile(
                                db = db,
                                uri = uri,
                                resolver = context.contentResolver
                            )
                        }
                    }
                result.onSuccess { outcome ->
                    onResult(
                        when {
                            outcome.duplicateFile ->
                                "账单已处理过，没有新增数据"
                            outcome.insertedRows > 0 ||
                                outcome.purchaseCandidateInsertedRows > 0 ->
                                "导入完成：经营收款 ${outcome.insertedRows} 笔 · 采购候选 ${outcome.purchaseCandidateInsertedRows} 笔"
                            else ->
                                "账单已读取，没有发现新的经营收款或采购候选"
                        }
                    )
                    onChanged()
                }.onFailure {
                    onResult(
                        "导入失败：${it.message ?: "无法读取账单"}"
                    )
                }
                importing = false
            }
        }

    Text(
        if (importing) "导入中" else label,
        modifier =
            Modifier
                .clickable(
                    enabled = !importing
                ) {
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
                }
                .padding(
                    horizontal = 5.dp,
                    vertical = 2.dp
                ),
        style = MaterialTheme.typography.labelMedium,
        color =
            if (importing) {
                Color.Gray
            } else {
                AssistGreen
            }
    )
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
    val analysis: CustomerAnalysisResult,
    val wechatTotal: Double,
    val alipayTotal: Double,
    val rawDayCount: Int
)

@Composable
internal fun BusinessPaymentAssistCard(
    db: AppDatabase,
    date: String,
    dataVersion: Int,
    hasManualBusiness: Boolean,
    manualElectronicTotal: Double,
    onSupplementBusiness: (Double, Double) -> Unit
) {
    var expanded by remember(date) {
        mutableStateOf(false)
    }

    val snapshot by
        produceState<BusinessAssistSnapshot?>(
            null,
            db,
            dataVersion,
            date
        ) {
            value =
                withContext(Dispatchers.IO) {
                    val settings =
                        db.getCustomerAnalysisSettings()
                    val allRawPayments =
                        db.getCustomerPayments()
                    val filteredAll =
                        CustomerAnalyticsEngine
                            .filterRecords(
                                allRecords =
                                    allRawPayments,
                                settings = settings
                            )
                            .included
                    val dayPayments =
                        filteredAll.filter {
                            it.businessDate == date
                        }
                    val analysis =
                        CustomerAnalyticsEngine.analyze(
                            allRecords =
                                filteredAll,
                            startDate = date,
                            endDate = date
                        )
                    BusinessAssistSnapshot(
                        analysis = analysis,
                        wechatTotal =
                            dayPayments
                                .filter {
                                    it.platform == "WECHAT"
                                }
                                .sumOf {
                                    it.netAmount
                                }
                                .coerceAtLeast(0.0),
                        alipayTotal =
                            dayPayments
                                .filter {
                                    it.platform == "ALIPAY"
                                }
                                .sumOf {
                                    it.netAmount
                                }
                                .coerceAtLeast(0.0),
                        rawDayCount =
                            allRawPayments.count {
                                it.businessDate == date
                            }
                    )
                }
        }

    val current = snapshot
    if (current == null) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors =
                CardDefaults.cardColors(
                    containerColor =
                        AssistSoftGreen
                )
        ) {
            Row(
                Modifier.padding(
                    horizontal = 10.dp,
                    vertical = 7.dp
                ),
                verticalAlignment =
                    Alignment.CenterVertically
            ) {
                Text(
                    "账单收款",
                    fontWeight =
                        FontWeight.SemiBold,
                    style =
                        MaterialTheme.typography
                            .bodySmall
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "读取中…",
                    style =
                        MaterialTheme.typography
                            .labelSmall,
                    color = Color.Gray
                )
            }
        }
        return
    }

    val analysis = current.analysis
    val wechatTotal = current.wechatTotal
    val alipayTotal = current.alipayTotal
    val importedTotal =
        analysis.summary.netRevenue
    val difference =
        importedTotal - manualElectronicTotal
    val meaningfulDifference =
        hasManualBusiness &&
            importedTotal > 0.005 &&
            manualElectronicTotal > 0.005 &&
            abs(difference) >=
                max(
                    20.0,
                    manualElectronicTotal * 0.10
                )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (meaningfulDifference) {
                        AssistSoftOrange
                    } else {
                        AssistSoftGreen
                    }
            )
    ) {
        Column(
            Modifier.padding(
                horizontal = 10.dp,
                vertical = 4.dp
            ),
            verticalArrangement =
                Arrangement.spacedBy(3.dp)
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
                        "账单收款",
                        fontWeight =
                            FontWeight.SemiBold,
                        style =
                            MaterialTheme.typography
                                .bodySmall
                    )
                    Text(
                        when {
                            current.rawDayCount > 0 &&
                                analysis.summary.paymentCount == 0 ->
                                "当天账单收款已按分析规则排除"
                            meaningfulDifference ->
                                "${assistMoney(importedTotal)} · ${analysis.summary.paymentCount}笔 · 差额 ${signedAssistMoney(difference)}"
                            else ->
                                "${assistMoney(importedTotal)} · ${analysis.summary.paymentCount}笔 · ${analysis.summary.customerCount}客"
                        },
                        style =
                            MaterialTheme.typography
                                .labelSmall,
                        color =
                            if (meaningfulDifference) {
                                Color(0xFF9A6700)
                            } else {
                                Color.Gray
                            },
                        maxLines = 1
                    )
                }

                if (
                    analysis.summary.paymentCount >
                    0
                ) {
                    TextButton(
                        onClick = {
                            onSupplementBusiness(
                                wechatTotal,
                                alipayTotal
                            )
                        },
                        contentPadding =
                            PaddingValues(
                                horizontal = 4.dp,
                                vertical = 0.dp
                            )
                    ) {
                        Text(
                            "补入",
                            style =
                                MaterialTheme.typography
                                    .labelMedium,
                            color = AssistGreen
                        )
                    }
                }

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
                        if (expanded) {
                            "收起"
                        } else {
                            "详情"
                        },
                        style =
                            MaterialTheme.typography
                                .labelMedium,
                        color = AssistGreen
                    )
                }
            }

            if (expanded) {
                Text(
                    if (hasManualBusiness) {
                        "补入只覆盖微信/支付宝，现金及其他手工营业数据保持不动。"
                    } else {
                        "账单由采购页统一导入；这里仅显示当天经营收款数据。"
                    },
                    style =
                        MaterialTheme.typography
                            .labelSmall,
                    color =
                        if (
                            !hasManualBusiness &&
                            analysis.summary
                                .paymentCount > 0
                        ) {
                            Color(0xFF9A6700)
                        } else {
                            Color.DarkGray
                        }
                )

                if (
                    analysis.summary.paymentCount >
                    0
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement =
                            Arrangement.spacedBy(
                                7.dp
                            )
                    ) {
                        AssistMetric(
                            "客户",
                            analysis.summary
                                .customerCount
                                .toString(),
                            Modifier.weight(1f)
                        )
                        AssistMetric(
                            "新客",
                            analysis.summary
                                .newCustomerCount
                                .toString(),
                            Modifier.weight(1f)
                        )
                        AssistMetric(
                            "老客",
                            analysis.summary
                                .oldCustomerCount
                                .toString(),
                            Modifier.weight(1f)
                        )
                        AssistMetric(
                            "平均客单",
                            assistMoney(
                                analysis.summary
                                    .averageTicket
                            ),
                            Modifier.weight(1f)
                        )
                    }

                    if (hasManualBusiness) {
                        Text(
                            "手工微信+支付宝 ${assistMoney(manualElectronicTotal)} · 账单 ${assistMoney(importedTotal)}" +
                                if (
                                    meaningfulDifference
                                ) {
                                    " · ⚠ ${signedAssistMoney(difference)}"
                                } else {
                                    ""
                                },
                            style =
                                MaterialTheme.typography
                                    .labelSmall,
                            color =
                                if (
                                    meaningfulDifference
                                ) {
                                    Color(
                                        0xFF9A6700
                                    )
                                } else {
                                    Color.Gray
                                }
                        )
                    }
                }

                Text(
                    "电子支付客户不含现金客流；账单辅助数据不参与结算或利润分配。",
                    style =
                        MaterialTheme.typography
                            .labelSmall,
                    color = Color.Gray
                )
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
