package com.tianxian.fruit.ui

import com.tianxian.fruit.sync.WeatherDay
import com.tianxian.fruit.sync.WeatherHour
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

/**
 * 首页重点降雨提示：营业时段 > 开摊前三小时 > 当日其他时段 > 日级预报。
 * 日级预报只能说明“可能有雨”，不能伪造具体小时。
 * 只分析未来小时段，避免把今天已经发生的降雨当作未来预警。
 */
internal data class HomeRainReminder(
    val headline: String,
    val detail: String,
    val strong: Boolean
)

internal object HomeRainReminderPolicy {
    fun evaluate(
        date: LocalDate,
        now: LocalDateTime,
        dayHours: List<WeatherHour>,
        preOpenHours: List<WeatherHour>,
        businessHours: List<WeatherHour>,
        day: WeatherDay?
    ): HomeRainReminder? {
        if (date.isBefore(now.toLocalDate())) return null

        fun future(hour: WeatherHour): Boolean {
            val parsed = runCatching {
                LocalDateTime.parse(hour.time.replace(' ', 'T').take(16))
            }.getOrNull()
            return parsed == null || parsed.isAfter(now.minusHours(1))
        }

        fun wet(hour: WeatherHour): Boolean {
            val label = hour.text + " " + hour.forecastText
            return (hour.precipitation ?: 0.0) > 0.05 ||
                (hour.precipitationProbability ?: 0.0) >= 40.0 ||
                label.contains("雨") ||
                label.contains("雷暴") ||
                hour.code.toIntOrNull() in 300..399
        }

        val business = businessHours.filter(::future).filter(::wet)
        val preOpen = preOpenHours.filter(::future).filter(::wet)
        val daytime = dayHours.filter(::future).filter(::wet)
        val (label, relevant) = when {
            business.isNotEmpty() -> "营业时段" to business
            preOpen.isNotEmpty() -> "开摊前" to preOpen
            daytime.isNotEmpty() -> "当天其他时段" to daytime
            else -> null to emptyList()
        }
        if (label != null) {
            val strong = relevant.any {
                (it.precipitation ?: 0.0) >= 2.0 ||
                    (it.precipitationProbability ?: 0.0) >= 70.0 ||
                    "暴雨" in it.text ||
                    "雷暴" in it.text ||
                    "雷阵雨" in it.text
            }
            val slots = relevant.map { it.time.substringAfter('T', it.time).take(5) }
                .filter { it.length == 5 && it[2] == ':' }
                .distinct()
                .sorted()
            val whenText = if (slots.isEmpty()) "" else
                slots.take(4).joinToString("、") + (if (slots.size > 4) "等时段" else "附近")
            val maxProbability = relevant.mapNotNull { it.precipitationProbability }.maxOrNull()
            val probabilityText = maxProbability?.let {
                " · 最高降雨概率 ${it.toInt().coerceIn(0, 100)}%"
            }.orEmpty()
            return HomeRainReminder(
                headline = if (strong) "⚠ ${label}有明显降雨风险" else "🌧 ${label}有雨，请留意",
                detail = listOf(whenText, "提前准备防雨，注意顾客出行和水果遮盖")
                    .filter { it.isNotBlank() }.joinToString(" · ") + probabilityText,
                strong = strong
            )
        }

        // 每日预报兜底：只有在还有可用的预报时才提示，不能根据日级数据编造降雨时间。
        val dailyText = day?.textDay.orEmpty() + " " + day?.textNight.orEmpty()
        val dailyRain = day != null && (
            (day.precipitation ?: 0.0) > 0.05 ||
                (day.precipitationProbability ?: 0.0) >= 40.0 ||
                dailyText.contains("雨") ||
                day.codeDay.toIntOrNull() in 300..399 ||
                day.codeNight.toIntOrNull() in 300..399
        )
        if (!dailyRain) return null
        if (date == now.toLocalDate() && now.hour >= 23) return null
        val pop = day.precipitationProbability?.let {
            " · 降雨概率 ${it.toInt().coerceIn(0, 100)}%"
        }.orEmpty()
        return HomeRainReminder(
            headline = "🌧 今日预报有雨".replace("今日", if (date == now.toLocalDate()) "今日" else "所选日期"),
            detail = "暂无可靠逐小时降雨时间，请留意天气变化" + pop,
            strong = (day.precipitation ?: 0.0) >= 2.0 ||
                (day.precipitationProbability ?: 0.0) >= 70.0 ||
                dailyText.contains("暴雨")
        )
    }
}
