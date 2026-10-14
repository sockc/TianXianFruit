package com.tianxian.fruit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tianxian.fruit.data.*
import java.util.Locale
import kotlin.math.abs

private val deepGreen = Color(0xFF087D4E)
private fun deepMoney(amount: Double): String =
    "¥" + String.format(Locale.CHINA, "%.0f", amount)
private fun deepPercent(ratio: Double): String =
    (if (ratio >= 0) "+" else "") +
        String.format(Locale.CHINA, "%.0f%%", ratio * 100)

@Composable
internal fun CustomerHourlyComparisonCard(
    result: CustomerTrafficComparison
) {
    var selectedHour by remember(result.date, result.storeId) {
        mutableIntStateOf(result.peakHour ?: result.hourRows.firstOrNull()?.hour ?: 18)
    }
    val maxValue = result.hourRows.maxOfOrNull {
        maxOf(it.payments.toDouble(), it.historicAverage ?: 0.0)
    }?.coerceAtLeast(1.0) ?: 1.0
    val selected = result.hourRows.firstOrNull { it.hour == selectedHour }
    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("逐小时付款对比 · ${result.storeName}", fontWeight = FontWeight.Bold)
            Text("绿色本次 · 灰色最近4个同位置、同星期可比营业日均值；仅比较已结束的小时。",
                style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Bottom) {
                result.hourRows.forEach { row ->
                    val actualHeight = (row.payments / maxValue * 73.0).toFloat()
                    val baselineHeight = ((row.historicAverage ?: 0.0) / maxValue * 73.0).toFloat()
                    Column(Modifier.width(54.dp).clickable { selectedHour = row.hour },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("${row.payments} / ${row.historicAverage?.let {
                                String.format(Locale.CHINA,"%.1f",it) } ?: "—"}",
                            maxLines = 1, style = MaterialTheme.typography.labelSmall,
                            color = if (row.hour == selectedHour) deepGreen else Color.Gray)
                        Row(Modifier.height(84.dp), verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            Box(Modifier.width(16.dp)
                                .height(if (row.payments == 0) 2.dp else (4f + actualHeight).dp)
                                .background(Color(0xFF22AA70),
                                    RoundedCornerShape(topStart=3.dp,topEnd=3.dp)))
                            Box(Modifier.width(16.dp)
                                .height(if (row.historicAverage == null) 2.dp
                                    else (4f + baselineHeight).dp)
                                .background(Color(0xFFB7C1CB),
                                    RoundedCornerShape(topStart=3.dp,topEnd=3.dp)))
                        }
                        Text("${row.hour}时", style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (row.hour == selectedHour) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
            selected?.let { row ->
                HorizontalDivider()
                Text("${row.hour}:00–${row.hour}:59 · 本次${row.payments}笔 · 历史${row.historicAverage?.let {
                    String.format(Locale.CHINA,"%.1f",it)+"笔"
                } ?: "样本不足"} · ${row.differencePercent?.let(::deepPercent) ?: "—"}",
                    style = MaterialTheme.typography.bodySmall)
            }
            Text("本次已完成小时 ${result.currentPayments}笔；历史相同时段均值 " +
                (result.baselinePayments?.let {
                    String.format(Locale.CHINA, "%.1f笔", it)
                } ?: "—") + "；可比样本${result.historicDates.size}个营业日。",
                style = MaterialTheme.typography.bodySmall)
            val peak = result.peakHour
            if (result.sampleSufficient && peak != null) {
                val decline = result.decliningHours
                Text(
                    if (decline.isNotEmpty())
                        "经营观察：${peak}时付款较多；${decline.joinToString("、") { "${it}时" }}低于历史均值至少20%。注意核对商品、价格和天气。"
                    else "经营观察：${peak}时是本次已完成时段的付款高峰；未发现达到阈值的明显小时下降。",
                    style = MaterialTheme.typography.bodySmall, color = deepGreen
                )
            } else {
                Text("样本不足：只展示交易走势，暂不判断客流下降是否持续。",
                    style = MaterialTheme.typography.bodySmall, color = Color(0xFF9A6700))
            }
            if (result.warnings.isNotEmpty()) {
                var showNotes by remember { mutableStateOf(false) }
                TextButton(onClick = { showNotes = !showNotes },
                    contentPadding = PaddingValues(0.dp)) {
                    Text(if (showNotes) "收起数据说明⌃" else "数据可信度与限制⌄")
                }
                if (showNotes) {
                    result.warnings.forEach {
                        Text("· $it", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    }
                    Text("参与对比的日期：${result.historicDates.joinToString("、").ifEmpty { "暂无" }}",
                        style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                }
            }
        }
    }
}

@Composable
internal fun CustomerPaymentAmountBandCard(
    bands: List<CustomerPaymentAmountBand>
) {
    val total = bands.sumOf { it.payments }
    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("付款金额分布", fontWeight = FontWeight.Bold)
            if (total == 0) {
                Text("当前范围暂无有效付款。", style = MaterialTheme.typography.bodySmall)
            } else bands.forEach { band ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(band.title, modifier = Modifier.width(66.dp),
                        style = MaterialTheme.typography.bodySmall)
                    Box(Modifier.weight(1f).height(8.dp)
                        .background(Color(0xFFE8EDF0), RoundedCornerShape(4.dp))) {
                        Box(Modifier.fillMaxWidth(band.share.toFloat().coerceIn(0f,1f))
                            .fillMaxHeight().background(Color(0xFF5CB98D),
                                RoundedCornerShape(4.dp)))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("${band.payments}笔  ${String.format(Locale.CHINA, "%.0f%%",band.share*100)}",
                        style = MaterialTheme.typography.labelSmall)
                }
            }
            Text("仅按单笔交易金额分组，无法据此判断顾客购买了哪种水果。",
                style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        }
    }
}

@Composable
internal fun CustomerStoreTrafficPatternCard(
    patterns: List<CustomerStoreTrafficPattern>
) {
    if (patterns.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
                Text("各位置最近7个有账单的营业日", fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                Text(if (expanded) "收起⌃" else "展开⌄", color = deepGreen,
                    style = MaterialTheme.typography.labelSmall)
            }
            if (expanded) {
                patterns.forEach { p ->
                    HorizontalDivider()
                    Text("${p.storeName} · ${p.operatingDays}日 · 日均${String.format(Locale.CHINA,"%.1f",p.averagePayments)}笔 · " +
                        "${deepMoney(p.averageRevenue)} · 高峰${p.peakHour?.let{"${it}时"} ?: "—"}",
                        style = MaterialTheme.typography.bodySmall)
                }
                Text("仅统计保存过营业记录且已导入有效收款的日期；未导入的营业日不按0笔计算。",
                    style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            }
        }
    }
}

@Composable
internal fun CustomerTrafficCoverageNote(
    singleDay: Boolean,
    sessions: Int,
    unmatchedPayments: Int,
    selectedStoreId: Long,
    hasWechat: Boolean,
    hasAlipay: Boolean
) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF9EB))) {
        Column(Modifier.fillMaxWidth().padding(horizontal=12.dp, vertical=9.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("账单覆盖核对", style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold)
            val channels = listOfNotNull(
                if (hasWechat) "微信✓" else null,
                if (hasAlipay) "支付宝✓" else null
            ).joinToString(" · ").ifEmpty { "尚无交易记录" }
            Text(channels +
                (if (singleDay) " · 当日保存营业位置${sessions}个" else "") +
                (if (unmatchedPayments > 0) " · 未匹配位置${unmatchedPayments}笔" else ""),
                style = MaterialTheme.typography.bodySmall)
            Text("交易存在不代表该时段账单完整；零记录不等于零客流，历史未营业日不纳入平均。",
                style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        }
    }
}
