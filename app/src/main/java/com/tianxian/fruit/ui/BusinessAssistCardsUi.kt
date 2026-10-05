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
internal fun HistoricalPurchaseAssistCard(
    db: AppDatabase,
    date: String,
    dataVersion: Int,
    hasManualPurchase: Boolean,
    manualPurchaseTotal: Double,
    onChanged: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }
    var message by remember(date) { mutableStateOf("") }
    var userToggled by remember(date) { mutableStateOf(false) }
    var expanded by remember(date) { mutableStateOf(!hasManualPurchase) }

    val rows =
        remember(dataVersion, date) {
            db.getHistoricalPurchaseCandidates(date)
        }
    val summary =
        remember(dataVersion, date) {
            db.getHistoricalPurchaseDaySummary(date)
        }

    LaunchedEffect(date, hasManualPurchase) {
        if (!userToggled) expanded = !hasManualPurchase
    }

    val highPending =
        rows.filter {
            it.record.status == "PENDING" &&
                it.confidence == "HIGH"
        }

    val launcher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri == null || importing) return@rememberLauncherForActivityResult
            importing = true
            message = "正在扫描账单中的早间付款…"
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
                    message =
                        if (
                            outcome.purchaseCandidateInsertedRows > 0
                        ) {
                            "新增采购候选 ${outcome.purchaseCandidateInsertedRows} 笔；经营收款新增 ${outcome.insertedRows} 笔"
                        } else if (outcome.duplicateFile) {
                            "账单已处理过，没有新的采购候选"
                        } else {
                            "未发现新的早间采购候选"
                        }
                    onChanged()
                }.onFailure {
                    message = "导入失败：${it.message ?: "无法读取账单"}"
                }
                importing = false
            }
        }

    val attention =
        hasManualPurchase &&
            highPending.isNotEmpty()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (attention) AssistSoftOrange else AssistSoftGray
            )
    ) {
        Column(
            Modifier.padding(
                horizontal = 12.dp,
                vertical = 9.dp
            ),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        userToggled = true
                        expanded = !expanded
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "历史采购识别",
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        buildString {
                            if (summary.pendingCount > 0) {
                                append("待核对 ${summary.pendingCount}笔 ${assistMoney(summary.pendingAmount)}")
                            } else {
                                append("暂无待核对")
                            }
                            if (summary.confirmedCount > 0) {
                                append(" · 已确认 ${assistMoney(summary.confirmedAmount)}")
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (attention) Color(0xFF9A6700) else Color.Gray
                    )
                }
                Text(
                    if (expanded) "收起 ▲" else "展开 ▼",
                    style = MaterialTheme.typography.labelMedium,
                    color = AssistGreen
                )
            }

            if (expanded) {
                if (hasManualPurchase) {
                    Text(
                        "当天已有正式采购 ${assistMoney(manualPurchaseTotal)}。候选只用于核对，不自动补差，也不会重复计入采购。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.DarkGray
                    )
                } else {
                    Text(
                        "当天没有手工采购记录。系统仅筛选 06:00–14:00、金额≥¥40 的付款，再结合当天营业、供应商和历史频率评分。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.DarkGray
                    )
                }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
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
                        enabled = !importing,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (importing) "扫描中…" else "导入微信/支付宝账单")
                    }

                    if (highPending.isNotEmpty()) {
                        Button(
                            onClick = {
                                val count =
                                    db.confirmHighConfidenceHistoricalPurchases(date)
                                message = "已确认 $count 笔高可信历史采购"
                                onChanged()
                            },
                            modifier = Modifier.weight(0.75f)
                        ) {
                            Text("确认高可信")
                        }
                    }
                }

                if (importing) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }

                if (message.isNotBlank()) {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color =
                            if (message.startsWith("导入失败")) {
                                MaterialTheme.colorScheme.error
                            } else {
                                AssistGreen
                            }
                    )
                }

                if (rows.isEmpty()) {
                    Text(
                        "当前日期没有采购候选。可以导入覆盖该日期的完整微信/支付宝账单。",
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
                                message = "已将 ${item.record.counterpartyName} 标记为供应商"
                                onChanged()
                            }
                        )
                    }
                }

                Text(
                    "已确认历史采购仍是辅助数据：不生成商品明细、不改正式采购金额、不进入库存成本、利润或结算。",
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

@Composable
internal fun BusinessPaymentAssistCard(
    db: AppDatabase,
    date: String,
    dataVersion: Int,
    hasManualBusiness: Boolean,
    manualElectronicTotal: Double,
    onCreateBusinessRecord: () -> Unit,
    onChanged: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }
    var message by remember(date) { mutableStateOf("") }
    var userToggled by remember(date) { mutableStateOf(false) }
    var expanded by remember(date) { mutableStateOf(!hasManualBusiness) }

    val payments =
        remember(dataVersion, date) {
            db.getCustomerPayments(date, date)
        }

    val analysis =
        remember(dataVersion, payments, date) {
            CustomerAnalyticsEngine.analyze(
                allRecords = db.getCustomerPayments(),
                startDate = date,
                endDate = date
            )
        }

    LaunchedEffect(date, hasManualBusiness) {
        if (!userToggled) expanded = !hasManualBusiness
    }

    val importedTotal =
        analysis.summary.netRevenue
    val difference =
        importedTotal - manualElectronicTotal
    val meaningfulDifference =
        hasManualBusiness &&
            importedTotal > 0.005 &&
            manualElectronicTotal > 0.005 &&
            abs(difference) >= max(20.0, manualElectronicTotal * 0.10)

    val launcher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri == null || importing) return@rememberLauncherForActivityResult
            importing = true
            message = "正在识别经营收款…"
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
                    message =
                        "经营收款新增 ${outcome.insertedRows} 笔" +
                            if (outcome.purchaseCandidateInsertedRows > 0) {
                                " · 同时发现采购候选 ${outcome.purchaseCandidateInsertedRows} 笔"
                            } else {
                                ""
                            }
                    onChanged()
                }.onFailure {
                    message = "导入失败：${it.message ?: "无法读取账单"}"
                }
                importing = false
            }
        }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (meaningfulDifference) AssistSoftOrange else AssistSoftGreen
            )
    ) {
        Column(
            Modifier.padding(
                horizontal = 12.dp,
                vertical = 9.dp
            ),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        userToggled = true
                        expanded = !expanded
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "经营收款与客户",
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        when {
                            payments.isEmpty() -> "未导入当天经营收款"
                            meaningfulDifference ->
                                "${assistMoney(importedTotal)} · ${analysis.summary.paymentCount}笔 · 差额 ${signedAssistMoney(difference)}"
                            else ->
                                "${assistMoney(importedTotal)} · ${analysis.summary.paymentCount}笔 · ${analysis.summary.customerCount}位可识别客户"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (meaningfulDifference) Color(0xFF9A6700) else Color.Gray
                    )
                }
                Text(
                    if (expanded) "收起 ▲" else "展开 ▼",
                    style = MaterialTheme.typography.labelMedium,
                    color = AssistGreen
                )
            }

            if (expanded) {
                if (hasManualBusiness) {
                    Text(
                        "当天已有手工营业记录，因此默认收起。账单数据只做客户分析和参考核对，不会修改正式营业额。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.DarkGray
                    )
                } else if (payments.isNotEmpty()) {
                    Text(
                        "发现当天有经营收款，但没有手工营业记录。可以补录营业记录；系统不会直接把电子收款当成正式营业额。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF9A6700)
                    )
                } else {
                    Text(
                        "当天没有手工营业记录。导入完整账单后，只识别二维码/经营收款，同时会把早间付款送到采购页做候选分析。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.DarkGray
                    )
                }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
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
                        enabled = !importing,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (importing) "导入中…" else "导入微信/支付宝账单")
                    }

                    if (!hasManualBusiness && payments.isNotEmpty()) {
                        Button(
                            onClick = onCreateBusinessRecord,
                            modifier = Modifier.weight(0.75f)
                        ) {
                            Text("补录营业")
                        }
                    }
                }

                if (importing) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }

                if (message.isNotBlank()) {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color =
                            if (message.startsWith("导入失败")) {
                                MaterialTheme.colorScheme.error
                            } else {
                                AssistGreen
                            }
                    )
                }

                if (payments.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        AssistMetric(
                            "客户",
                            analysis.summary.customerCount.toString(),
                            Modifier.weight(1f)
                        )
                        AssistMetric(
                            "新客",
                            analysis.summary.newCustomerCount.toString(),
                            Modifier.weight(1f)
                        )
                        AssistMetric(
                            "老客",
                            analysis.summary.oldCustomerCount.toString(),
                            Modifier.weight(1f)
                        )
                        AssistMetric(
                            "平均客单",
                            assistMoney(analysis.summary.averageTicket),
                            Modifier.weight(1f)
                        )
                    }

                    if (hasManualBusiness) {
                        Text(
                            "手工微信+支付宝 ${assistMoney(manualElectronicTotal)} · 账单识别 ${assistMoney(importedTotal)}" +
                                if (meaningfulDifference) {
                                    " · ⚠ 差额 ${signedAssistMoney(difference)}"
                                } else {
                                    ""
                                },
                            style = MaterialTheme.typography.bodySmall,
                            color =
                                if (meaningfulDifference) {
                                    Color(0xFF9A6700)
                                } else {
                                    Color.Gray
                                }
                        )
                    }
                }

                Text(
                    "电子支付客户≠全部客流；现金客户不会被账单识别。经营收款数据不参与结算或利润分配。",
                    style = MaterialTheme.typography.labelSmall,
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
