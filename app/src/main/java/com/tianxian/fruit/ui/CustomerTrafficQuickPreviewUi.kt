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
    val source = remember(autoRecords, assignments, stores) {
        CustomerUnifiedStoreAttribution.apply(autoRecords, assignments, stores)
    }
    val unmatchedBeforeUnified = remember(autoRecords, record.date) {
        CustomerUnifiedStoreAttribution.unmatchedOnDate(autoRecords, record.date)
    }
    val manualAssignmentName = stores.firstOrNull {
        it.syncId == assignments[record.date] && it.syncId.isNotBlank()
    }?.name
    val settings = remember(dataVersion) { db.getCustomerAnalysisSettings() }
    val payments = remember(source, settings) {
        CustomerAnalyticsEngine.filterRecords(source, settings).included
    }
    val selected = payments.filter {
        record.storeId > 0L && it.businessDate == record.date &&
            it.storeId == record.storeId && it.netAmount > 0.005
    }
    val comparisons = remember(payments, sessions, record.date, record.storeId) {
        if (record.storeId <= 0L) emptyList()
        else CustomerTrafficDeepDive.compare(
            payments, sessions, record.date, selectedStoreId = record.storeId
        )
    }
    val unmatched = payments.count {
        it.businessDate == record.date && it.storeId <= 0L && it.netAmount > 0.005
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
            comparisons.firstOrNull()?.let { CustomerHourlyComparisonCard(it) }
                ?: Text("缺少可比较的营业时间或记录，暂不能计算同位置历史变化。",
                    style=MaterialTheme.typography.bodySmall,color=Color.Gray)
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
