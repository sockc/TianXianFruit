package com.tianxian.fruit.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tianxian.fruit.data.AppDatabase
import com.tianxian.fruit.data.BusinessRhythmAnalyzer
import com.tianxian.fruit.data.BusinessRhythmReport
import com.tianxian.fruit.data.RhythmBusinessDay
import com.tianxian.fruit.data.RetailPriceSignal
import com.tianxian.fruit.data.RetailPriceSignalAnalyzer
import com.tianxian.fruit.data.RainImpactAnalyzer
import com.tianxian.fruit.data.RainImpactReport
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 客户分析下的经营节奏页。读已有营业记录，不增加服务器表、不额外写同步数据。
 * 详细经营诊断只使用该位置有真实营业额的日期；未归属客户数据不给出“老客流失”结论。
 */
@Composable
internal fun CustomerBusinessRhythmContent(
    db: AppDatabase,
    dataVersion: Int,
    startDate: String?,
    endDate: String?
) {
    val allRecords = remember(dataVersion) { db.getRecentDailyRecords(700) }
    val cards = remember(allRecords, startDate, endDate) {
        allRecords
            .filter { it.revenue > 0.0 }
            .groupBy { it.storeId }
            .mapNotNull { (_, rows) ->
                val visible = rows.filter {
                    (startDate == null || it.date >= startDate) &&
                        (endDate == null || it.date <= endDate)
                }
                val latest = visible.maxByOrNull { it.date } ?: return@mapNotNull null
                val days = rows.mapNotNull { row ->
                    runCatching { LocalDate.parse(row.date) }.getOrNull()?.let { day ->
                        RhythmBusinessDay(
                            date = day,
                            revenue = row.revenue,
                            customers = row.customerTotal,
                            newCustomers = row.newCustomer,
                            oldCustomers = row.oldCustomer
                        )
                    }
                }
                val latestDate = runCatching { LocalDate.parse(latest.date) }.getOrNull()
                    ?: return@mapNotNull null
                RhythmStoreCard(
                    storeId = latest.storeId,
                    storeName = latest.storeName,
                    actual = BusinessRhythmAnalyzer.evaluate(latestDate, days),
                    tomorrow = BusinessRhythmAnalyzer.evaluate(LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(1), days)
                )
            }
            .sortedByDescending { it.actual.date }
    }

    // Show product price clues only for the most recent business day in this view.
    // Stock and retail prices are ledger-wide; do not assign their effects to a single stall.
    val examinedDate = cards.maxOfOrNull { it.actual.date }
    val priceSignals by produceState(
        initialValue = emptyList<RetailPriceSignal>(),
        key1 = dataVersion,
        key2 = examinedDate
    ) {
        value = withContext(Dispatchers.IO) {
            examinedDate?.let { day ->
                runCatching { RetailPriceSignalAnalyzer.fromDatabase(db, day) }
                    .getOrDefault(emptyList())
            }.orEmpty()
        }
    }

    val ledgerStock by produceState<LedgerStockHint?>(
        initialValue = null,
        key1 = dataVersion,
        key2 = examinedDate
    ) {
        value = withContext(Dispatchers.IO) {
            examinedDate?.let { date ->
                runCatching {
                    val data = db.getOperatingAnalysis(date.toString())
                    LedgerStockHint(
                        complete = data.inventoryComplete,
                        products = data.items
                            .filter { it.inventorySaved && !it.quantityAnomaly && it.consumedQuantity > 0.001 }
                            .sortedByDescending { it.estimatedSalesRevenue ?: 0.0 }
                            .take(3)
                            .map { item ->
                                val weight = item.estimatedSoldWeightJin?.let { "，估计${String.format(Locale.CHINA, "%.1f", it)}斤" }.orEmpty()
                                "${item.fruitName}：消耗${String.format(Locale.CHINA, "%.1f", item.consumedQuantity)}${item.unit}$weight"
                            },
                        unsavedCount = data.items.count { !it.inventorySaved },
                        anomalyCount = data.items.count { it.quantityAnomaly }
                    )
                }.getOrNull()
            }
        }
    }

    if (cards.isEmpty()) {
        Text(
            "该时间范围内没有可分析的营业记录。经营节奏使用正式营业额，不依赖已导入的收款账单。",
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("经营节奏分析", fontWeight = FontWeight.Bold)
            Text(
                "按位置比较连续出摊天数与历史同星期；营业额下降不代表一定是老客需求饱和。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (examinedDate != null) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Text("商品价格线索 · ${examinedDate}", fontWeight = FontWeight.SemiBold)
                        val discounted = priceSignals.filter { it.isReduced }
                        if (discounted.isEmpty()) {
                            Text(
                                "当前没有发现可比较的已记录降价。若未保存今日零售价，无法判断商品是否实际调价。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Text(
                                "有 ${discounted.size} 款商品的已记录零售价下调：",
                                style = MaterialTheme.typography.bodySmall
                            )
                            discounted.take(4).forEach { signal ->
                                Text(
                                    "• ${signal.fruitName} ${rhythmPrice(signal.oldPrice)} → ${rhythmPrice(signal.newPrice)}/${signal.priceUnit}" +
                                        if (signal.hasCarryover) "（有结转库存）" else "",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Text(
                                "售价下调可能影响客单价；有结转库存时也可能与隔夜货有关，但不能仅凭降价认定水果不新鲜。",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        ledgerStock?.let { stock ->
                            HorizontalDivider()
                            Text("库存消耗线索（全账本）", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodySmall)
                            stock.products.forEach { product ->
                                Text("• $product", style = MaterialTheme.typography.bodySmall)
                            }
                            if (!stock.complete) {
                                Text(
                                    "库存盘点尚不完整：${stock.unsavedCount}款未盘点、${stock.anomalyCount}款数量异常；未盘点商品不视作售罄。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else if (stock.products.isEmpty()) {
                                Text("本日没有可确认的库存销售耗用记录。", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Text(
                            "零售价和库存当前按全账本统计，多位置无法确定属于哪个摊位；每日售价也不代表每笔实际成交价。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        items(cards, key = { it.storeId }) { entry ->
            val storeName = entry.storeName
            val actual = entry.actual
            val tomorrow = entry.tomorrow
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                )
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(storeName, fontWeight = FontWeight.Bold)
                    Text(
                        "${actual.date} · 最近经营诊断 · 连续${actual.consecutiveDays}天",
                        style = MaterialTheme.typography.labelMedium
                    )
                    Text(actual.headline, color = MaterialTheme.colorScheme.primary)
                    val comparison = actual.revenueChangePercent
                    Text(
                        "营业额 ${rhythmMoney(actual.revenue)} · 同星期样本 ${actual.comparableDays}天" +
                            if (comparison != null) " · 较历史 ${String.format(Locale.CHINA, "%+.0f", comparison)}%" else " · 历史不足",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (actual.customers != null) {
                        Text(
                            "客户 ${actual.customers} · 客单价 ${rhythmMoney(actual.averageTicket)}" +
                                (actual.newCustomerShare?.let { " · 新客 ${(it * 100).toInt()}%" } ?: ""),
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        Text("客户数据不足：不推断新老客变化", style = MaterialTheme.typography.bodySmall)
                    }
                    actual.reasons.take(4).forEach { reason ->
                        Text("• $reason", style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider()
                    Text("明日出摊提示", fontWeight = FontWeight.SemiBold)
                    Text(
                        tomorrow.recommendation,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "证据：${when(actual.evidence) {
                            "MODERATE" -> "部分客流可比"
                            "PARTIAL" -> "仅营业额可比"
                            else -> "同星期样本不足"
                        }}。尚未控制天气、工厂调休和品种变化；建议结合库存及利润判断。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            RainImpactDiagnosisCard(
                db = db,
                dataVersion = dataVersion,
                storeId = entry.storeId,
                date = actual.date
            )
        }
    }
}

private data class LedgerStockHint(
    val complete: Boolean,
    val products: List<String>,
    val unsavedCount: Int,
    val anomalyCount: Int
)

private data class RhythmStoreCard(
    val storeId: Long,
    val storeName: String,
    val actual: com.tianxian.fruit.data.BusinessRhythmReport,
    val tomorrow: com.tianxian.fruit.data.BusinessRhythmReport
)

@Composable
private fun RainImpactDiagnosisCard(
    db: AppDatabase,
    dataVersion: Int,
    storeId: Long,
    date: LocalDate
) {
    val diagnosis by produceState<RainImpactReport?>(
        initialValue = null,
        key1 = dataVersion,
        key2 = storeId,
        key3 = date
    ) {
        value = withContext(Dispatchers.IO) {
            runCatching { RainImpactAnalyzer.fromDatabase(db, date, storeId) }.getOrNull()
        }
    }
    var expanded by remember(storeId, date) { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("逐小时雨天经营诊断 · ${date}", fontWeight = FontWeight.SemiBold)
            val report = diagnosis
            if (report == null) {
                Text(
                    "正在读取本位置历史天气和收款记录；若持续无结果，请检查天气归档与账单数据。",
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                Text(
                    "本摊实际营业额 ${rainMoney(report.totalOperatingRevenue)} · 实况 ${report.actualWeatherHours}/${report.expectedHours}小时 · 降雨 ${report.wetHours}小时",
                    style = MaterialTheme.typography.bodySmall
                )
                if (report.wetHours > 0) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(report.before, report.rainy, report.after).forEach { period ->
                            Column(Modifier.weight(1f)) {
                                Text(period.label, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.labelSmall)
                                Text("${period.paymentCount}笔", style = MaterialTheme.typography.bodySmall)
                                Text(rainMoney(period.electronicRevenue), style = MaterialTheme.typography.bodySmall)
                                Text("${period.observedHours}小时实况", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                report.observations.take(3).forEach { observation ->
                    Text("• $observation", style = MaterialTheme.typography.bodySmall)
                }
                if (report.missing.isNotEmpty()) {
                    TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
                        Text("缺少 ${report.missing.size} 项资料 ${if (expanded) "⌃" else "⌄"}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (expanded) {
                        report.missing.forEach { missing ->
                            Text("• $missing", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Text(
                    "分时金额仅统计已导入并匹配本摊位的电子收款，现金不含在内；商品和库存按全账本核算，不能据此认定雨天导致全部营业额变化。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun rainMoney(value: Double): String =
    "¥" + String.format(Locale.CHINA, "%.0f", value)

private fun rhythmPrice(value: Double): String =
    "¥" + String.format(Locale.CHINA, "%.2f", value).trimEnd('0').trimEnd('.')

private fun rhythmMoney(value: Double?): String =
    if (value == null) "未知" else "¥" + String.format(Locale.CHINA, "%.0f", value)
