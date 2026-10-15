package com.tianxian.fruit.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tianxian.fruit.data.CustomerPaymentRecord
import com.tianxian.fruit.data.CustomerTrafficSession
import com.tianxian.fruit.data.CustomerImportCoverageEngine
import java.time.YearMonth

/**
 * Always show both payment providers, regardless of the current provider/store filter.
 * The ranges mean dates with positive business receipts on THIS DEVICE, not the
 * official export range and never proof of complete import or cloud persistence.
 */
@Composable
internal fun CustomerImportCoverageStrip(
    records: List<CustomerPaymentRecord>,
    sessions: List<CustomerTrafficSession>,
    initialMonth: YearMonth
) {
    val coverage = remember(records) { CustomerImportCoverageEngine.overview(records) }
    var detailedPlatform by remember { mutableStateOf<String?>(null) }
    val wechatGreen = Color(0xFF087D4E)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        coverage.forEach { info ->
            Row(
                modifier = Modifier.fillMaxWidth().clickable {
                    detailedPlatform = info.platform
                }.padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (info.platform == "WECHAT") "微信" else "支付宝",
                    fontWeight = FontWeight.SemiBold,
                    color = if (info.platform == "WECHAT") wechatGreen else Color(0xFF2676C4),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.width(46.dp)
                )
                val dates = if (info.firstDate == null) "暂无经营收款账单"
                    else if (info.firstDate == info.lastDate) info.firstDate
                    else "${info.firstDate}～${info.lastDate}"
                Text(
                    "$dates · ${info.observedDays}天 · ${info.payments}笔",
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text("按日 ›", color = wechatGreen,
                    style = MaterialTheme.typography.labelSmall)
            }
        }
        Text(
            "以上仅为本机实际发现经营收款的日期，不代表完整导出范围；账单尚未接入云同步。",
            color = Color.Gray,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(start = 6.dp, end = 4.dp, bottom = 3.dp)
        )
    }
    if (detailedPlatform != null) {
        val platform = detailedPlatform!!
        var month by remember(platform, initialMonth) { mutableStateOf(initialMonth) }
        val days = remember(records, sessions, month) {
            CustomerImportCoverageEngine.days(records, sessions, month)
        }
        AlertDialog(
            onDismissRequest = { detailedPlatform = null },
            title = {
                Text("${if (platform == "WECHAT") "微信" else "支付宝"}账单 · ${month.year}年${month.monthValue}月")
            },
            text = {
                Column {
                    Row(Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { month = month.minusMonths(1) }) { Text("‹ 上月") }
                        Text("${month.year}-${month.monthValue.toString().padStart(2, '0')}",
                            style = MaterialTheme.typography.bodyMedium)
                        TextButton(
                            onClick = { month = month.plusMonths(1) },
                            enabled = month < YearMonth.now()
                        ) { Text("下月 ›") }
                    }
                    HorizontalDivider()
                    LazyColumn(Modifier.heightIn(max = 405.dp)) {
                        items(days, key = { it.date }) { day ->
                            val count = if (platform == "WECHAT")
                                day.wechatPayments else day.alipayPayments
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(day.date, style = MaterialTheme.typography.bodySmall)
                                Text(
                                    when {
                                        count > 0 -> "发现 ${count}笔经营收款"
                                        day.hasSavedBusiness -> "待核对 · 暂无已导入收款"
                                        else -> "无已导入收款记录"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (count > 0) wechatGreen else Color.Gray
                                )
                            }
                        }
                    }
                    Text(
                        "无记录不能证明当天零收款或账单未导入；有记录也不能证明全天账单完整。跨午夜按交易实际日期显示。",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { detailedPlatform = null }) { Text("关闭") }
            },
            shape = RoundedCornerShape(14.dp)
        )
    }
}
