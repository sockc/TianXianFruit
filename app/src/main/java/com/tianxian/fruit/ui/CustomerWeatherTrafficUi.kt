package com.tianxian.fruit.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tianxian.fruit.data.CustomerWeatherTrafficResult
import java.util.Locale

@Composable
internal fun CustomerWeatherTrafficCard(result: CustomerWeatherTrafficResult) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("实际天气与付款 · ${result.storeName}", fontWeight = FontWeight.Bold)
            Text("仅使用已存档的营业时段逐小时实际天气，绝不混入预报。",
                style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            if (result.observed.isNotEmpty()) {
                Row(Modifier.fillMaxWidth()) {
                    Text("小时 / 天气", Modifier.weight(2f), style=MaterialTheme.typography.labelSmall)
                    Text("付款", Modifier.weight(1f), style=MaterialTheme.typography.labelSmall)
                    Text("历史均值", Modifier.weight(1f), style=MaterialTheme.typography.labelSmall)
                }
                result.observed.forEach { hour ->
                    Row(Modifier.fillMaxWidth()) {
                        Text("${hour.hour}时 ${if (hour.raining) "雨" else "无雨"} ${hour.weather}",
                            Modifier.weight(2f), style=MaterialTheme.typography.bodySmall, maxLines=1)
                        Text("${hour.paymentCount}笔", Modifier.weight(1f),
                            style=MaterialTheme.typography.bodySmall)
                        Text(hour.baselineCount?.let {
                            String.format(Locale.CHINA,"%.1f笔",it)
                        } ?: "—", Modifier.weight(1f), style=MaterialTheme.typography.bodySmall)
                    }
                }
            }
            result.rainDry?.let { summary ->
                HorizontalDivider()
                Text("历史雨时和无雨时（同位置同星期）",
                    fontWeight=FontWeight.SemiBold,style=MaterialTheme.typography.bodySmall)
                Text("雨时${summary.rainySamples}小时 · 均${summary.rainyAverage?.let {
                    String.format(Locale.CHINA,"%.1f",it)
                } ?: "—"}笔；无雨${summary.drySamples}小时 · 均${summary.dryAverage?.let {
                    String.format(Locale.CHINA,"%.1f",it)
                } ?: "—"}笔",style=MaterialTheme.typography.bodySmall)
                if (summary.reliable && summary.difference != null) {
                    Text("历史差异 ${String.format(Locale.CHINA,"%+.1f%%",
                        summary.difference*100.0)}，不代表天气造成变化。",
                        style=MaterialTheme.typography.bodySmall,color=Color(0xFF087D4E))
                }
            }
            result.notes.forEach {
                Text("· $it",style=MaterialTheme.typography.labelSmall,color=Color(0xFF9A6700))
            }
        }
    }
}
