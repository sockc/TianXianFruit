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
    val sessions = remember(dataVersion) { db.getCustomerTrafficSessions() }
    val source = remember(dataVersion, sessions) {
        CustomerTrafficDeepDive.attributeBusinessDays(db.getCustomerPayments(), sessions)
    }
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

    ModalBottomSheet(onDismissRequest = onClose) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(0.78f)
                .verticalScroll(rememberScrollState())
                .padding(start=16.dp, end=16.dp, bottom=24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("${record.storeName} · ${record.date}",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("营业客流速览", color=Color(0xFF087D4E),
                style=MaterialTheme.typography.labelMedium)
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
            if (unmatched > 0) {
                Text("当日另有${unmatched}笔未匹配位置的收款，未计入此位置。",
                    style=MaterialTheme.typography.bodySmall,color=Color(0xFF9A6700))
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
}
