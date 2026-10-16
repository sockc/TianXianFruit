package com.tianxian.fruit.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tianxian.fruit.data.*
import java.util.Locale

/**
 * No unknown payment is silently assigned to the selected store.
 * Preview is separate from the editable two-line business record.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CustomerTrafficQuickPreviewSheet(
    db: AppDatabase,
    dataVersion: Int,
    record: StoreDailyRecord,
    onClose: () -> Unit,
    onFullAnalysis: () -> Unit
) {
    var locationEditTick by remember { mutableIntStateOf(0) }
    var showUnifiedStoreDialog by remember { mutableStateOf(false) }
    val sessions = remember(dataVersion) { db.getCustomerTrafficSessions() }
    val stores = remember(dataVersion, locationEditTick) { db.getStores() }
    val assignments = remember(dataVersion, locationEditTick) { db.getCustomerStoreDayAssignments() }
    val autoRecords = remember(dataVersion, sessions) {
        CustomerTrafficDeepDive.attributeBusinessDays(db.getCustomerPayments(), sessions)
    }
    val collectorByBatch = remember(dataVersion) { db.getCustomerCollectorByBatchId() }
    val collectorPartners = remember(dataVersion) { db.getCustomerCollectorPartnerIds() }
    val dailyHistory = remember(dataVersion) { db.getDailyRecordsBetween(null, null) }
    val collectorAttribution = remember(autoRecords, dailyHistory, collectorByBatch, collectorPartners) {
        CustomerCollectorStoreAttribution.resolve(autoRecords, dailyHistory,
            collectorByBatch, collectorPartners, sessions)
    }
    val source = remember(collectorAttribution, assignments, stores) {
        CustomerUnifiedStoreAttribution.apply(collectorAttribution.rows, assignments,
            stores, collectorAttribution.collectorMatchedIds)
    }
    val unmatchedBeforeUnified = remember(collectorAttribution, record.date) {
        CustomerUnifiedStoreAttribution.unmatchedOnDate(collectorAttribution.rows,
            record.date, collectorAttribution.collectorMatchedIds)
    }
    val manualAssignmentName = stores.firstOrNull {
        it.syncId == assignments[record.date] && it.syncId.isNotBlank()
    }?.name
    val settings = remember(dataVersion) { db.getCustomerAnalysisSettings() }
    val payments = remember(source, settings) {
        CustomerAnalyticsEngine.filterRecords(source, settings).included
    }
    val exclusive = payments.filter {
        record.storeId > 0L && it.businessDate == record.date &&
            it.storeId == record.storeId && it.netAmount > 0.005
    }
    val sharedGroups = remember(collectorAttribution, record.date, record.storeId) {
        collectorAttribution.shared.filter {
            it.date == record.date && record.storeId in it.storeIds
        }
    }
    val sharedIds = sharedGroups.flatMap { it.receiptIds }.toSet()
    val sharedPayments = payments.filter { it.id in sharedIds && it.netAmount > 0.005 }
    // A receipt is shown once in the combined display even when the same
    // collector appears on several stores in the day's business record.
    val selected = (exclusive + sharedPayments).distinctBy { it.id }
    val comparisons = remember(payments, sessions, record.date, record.storeId) {
        if (record.storeId <= 0L) emptyList()
        else CustomerTrafficDeepDive.compare(
            payments, sessions, record.date, selectedStoreId = record.storeId
        )
    }
    val sharedComparisons = remember(sharedGroups, collectorAttribution, payments, sessions) {
        sharedGroups.mapNotNull { group ->
            CustomerSharedHourlyComparison.compare(
                selected = group,
                groups = collectorAttribution.shared,
                included = payments,
                sessions = sessions
            )
        }
    }
    val unmatched = payments.count {
        it.businessDate == record.date && it.storeId <= 0L &&
            it.id !in collectorAttribution.collectorMatchedIds && it.netAmount > 0.005
    }
    val amount = selected.sumOf { it.netAmount }
    val hourly = selected.groupingBy {
        it.tradeTime.substringAfter(' ', "").take(2).toIntOrNull()
    }.eachCount()
    val peak = hourly.maxByOrNull { it.value }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(0.95f)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("${record.storeName} · ${record.date}",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (sharedGroups.isNotEmpty()) {
                Text("组合客流（本位置独立收款＋多个位置共享收款），不是本位置单独客流。",
                    style = MaterialTheme.typography.bodySmall, color = Color(0xFF9A6700))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween) {
                Text("付款 ${selected.size}笔")
                Text("电子 ¥${String.format(Locale.CHINA,"%.0f",amount)}")
                Text("笔均 ¥${String.format(Locale.CHINA,"%.1f",
                    if(selected.isEmpty()) 0.0 else amount/selected.size)}")
            }
            peak?.let {
                Text("付款高峰：${it.key}时（${it.value}笔）",
                    style=MaterialTheme.typography.bodySmall)
            }
            Text("微信 ${selected.count { it.platform == "WECHAT" }}笔 · " +
                "支付宝 ${selected.count { it.platform == "ALIPAY" }}笔",
                style=MaterialTheme.typography.bodySmall)
            if (sharedGroups.isNotEmpty()) {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF6E6))) {
                    Column(Modifier.fillMaxWidth().padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("共享收款涉及${sharedGroups.flatMap { it.stores }.distinctBy { it.first }.size}个位置",
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF9A6700))
                        sharedGroups.forEach { group ->
                            val receipts = sharedPayments.filter { it.id in group.receiptIds }
                            if (receipts.isNotEmpty()) {
                                val payName = if (group.platform == "WECHAT") "微信" else "支付宝"
                                Text("${group.partnerName} · $payName · ${receipts.size}笔 / ¥" +
                                    String.format(Locale.CHINA, "%.2f", receipts.sumOf { it.netAmount }),
                                    style = MaterialTheme.typography.bodySmall)
                                Text("关联位置：${group.storeNames}",
                                    style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Text("本位置独立 ${exclusive.size}笔；共享 ${sharedPayments.size}笔。共享金额只计一次，" +
                            "不得把合并数据写入任意单个位置营业额或单摊历史对比。",
                            style = MaterialTheme.typography.labelSmall, color = Color(0xFF9A6700))
                    }
                }
            }
            // Shared payments have their OWN hourly and historic comparison.
            // Never reuse the single-store baseline for a combined payment stream.
            if (sharedGroups.isNotEmpty()) {
                Text("共享收款逐小时与历史对比",
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium)
                if (sharedComparisons.isEmpty()) {
                    Text("共享收款缺少已保存的完整营业小时，暂不能比较；上方仍保留合并付款及高峰数据。",
                        style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                } else sharedComparisons.forEach { result ->
                    CustomerHourlyComparisonCard(result, sharedScope = true)
                }
            }
            if (exclusive.isNotEmpty()) {
                comparisons.firstOrNull()?.let {
                    Text("本位置独立收款历史对比",
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium)
                    CustomerHourlyComparisonCard(it)
                } ?: Text("本位置独立收款暂无完整营业时段可比。",
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            } else if (sharedGroups.isEmpty()) {
                Text("没有可独立归属本位置的历史客流。",
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
            if (record.storeId <= 0L) {
                Text("这条旧营业记录没有明确营业位置，不能自动分配未匹配账单。",
                    style=MaterialTheme.typography.bodySmall,color=Color(0xFF9A6700))
            }
            if (manualAssignmentName != null) {
                Text("本日统一归属：$manualAssignmentName（人为归类，非真实交易位置核实）",
                    style = MaterialTheme.typography.bodySmall, color = Color(0xFF9A6700))
            }
            if (unmatched > 0 || manualAssignmentName != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween,
                    verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                    Text("当日未匹配 ${unmatched} 笔",
                        style = MaterialTheme.typography.bodySmall, color = Color(0xFF9A6700))
                    TextButton(onClick = { showUnifiedStoreDialog = true }) {
                        Text("统一指定位置 ›")
                    }
                }
            }
            Text("付款笔数不是独立顾客数；未导入的交易不计入。本页面不修改营业额或原始账单。",
                style=MaterialTheme.typography.labelSmall,color=Color.Gray)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick=onClose,modifier=Modifier.weight(1f)) { Text("关闭") }
                Button(onClick=onFullAnalysis,modifier=Modifier.weight(1.5f)) {
                    Text("查看完整分析 ›")
                }
            }
        }
    }
    if (showUnifiedStoreDialog) {
        CustomerUnifiedStoreDialog(
            db = db,
            date = record.date,
            unmatchedPayments = unmatchedBeforeUnified,
            defaultStoreId = record.storeId,
            onDismiss = { showUnifiedStoreDialog = false },
            onSaved = {
                showUnifiedStoreDialog = false
                locationEditTick++
            }
        )
    }
}
