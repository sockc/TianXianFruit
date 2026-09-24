package com.tianxian.fruit.data

import com.tianxian.fruit.sync.WeatherClient
import com.tianxian.fruit.sync.WeatherHour
import com.tianxian.fruit.sync.WeatherOverview
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * V1.4.7.56 今日经营建议 V2。
 *
 * 核心原则：
 * 1. 每个位置建立自己的经营基准，优先使用最近 60 个有效营业日的中位数。
 * 2. 以“同位置相似历史日”为核心证据，不再把固定权重简单相加。
 * 3. 天气、日期、近期趋势作为相似度/修正证据；样本不足时自动向中性 70 分回归。
 * 4. 当日实绩只用于营业后复盘和后续自动校准，绝不参与当天开摊前评分。
 */
class BusinessScoreEngine(
    private val db: AppDatabase
) {
    data class WeatherMetrics(
        val headline: String = "",
        val maxPop: Double = 0.0,
        val preOpenRain: Double = 0.0,
        val businessRain: Double = 0.0,
        val avgTemp: Double? = null,
        val avgHumidity: Double? = null,
        val maxWindSpeed: Double? = null,
        val hasAlert: Boolean = false,
        val rainy: Boolean = false
    )

    data class CalendarInfo(
        val date: LocalDate,
        val isWorkday: Boolean,
        val isWeekend: Boolean,
        val weekdayLabel: String,
        val phase: String,
        val phaseLabel: String,
        val holidayName: String,
        val monthBucket: Int
    )

    private data class HistoricalDay(
        val record: StoreDailyRecord,
        val calendar: CalendarInfo,
        val weather: WeatherMetrics?,
        val similarity: Int,
        val weight: Double
    )

    private data class ComponentResult(
        val index: Double,
        val summary: String,
        val reasons: List<String>,
        val sampleCount: Int = 0,
        val ratio: Double = 1.0
    )

    fun calculate(
        date: LocalDate,
        store: StoreOption,
        weather: WeatherOverview?
    ): BusinessScoreRecord {
        val dateString = date.toString()
        val positionHistory = db.getStoreDailyRecordsBefore(store.id, dateString, 180)
            .filter { it.revenue > 0.0 }
            .take(180)
        val calendar = calendarInfo(date)
        val weatherMetrics = weather?.let { weatherMetrics(it, store) }

        // 位置基准：只用本位置，最近 60 个有效营业日；中位数比平均数更抗异常值。
        val baselinePool = positionHistory.take(60)
        val baselineRevenue = median(baselinePool.map { it.revenue })
        val baselineCustomers = median(
            baselinePool.map { it.customerTotal.toDouble() }.filter { it > 0.0 }
        )
        val baselineTicket = median(
            baselinePool.mapNotNull { r ->
                r.customerTotal.takeIf { it > 0 }?.let { r.revenue / it.toDouble() }
            }.filter { it > 0.0 }
        )

        val historical = positionHistory.mapNotNull { record ->
            val histDate = runCatching { LocalDate.parse(record.date) }.getOrNull() ?: return@mapNotNull null
            val histCalendar = calendarInfo(histDate)
            val histStore = db.getStoreById(record.storeId) ?: store
            val histWeather = db.getBusinessWeatherHistory(record.date, record.storeId)?.let { archived ->
                runCatching {
                    weatherMetrics(
                        WeatherClient.parseOverview(archived.payloadJson, historical = true),
                        histStore,
                        archived.actualStartTime,
                        archived.actualEndTime
                    )
                }.getOrNull()
            }
            val similarity = similarityScore(
                targetDate = date,
                targetCalendar = calendar,
                targetWeather = weatherMetrics,
                historyDate = histDate,
                historyCalendar = histCalendar,
                historyWeather = histWeather
            )
            HistoricalDay(
                record = record,
                calendar = histCalendar,
                weather = histWeather,
                similarity = similarity,
                weight = similarityWeight(similarity, date, histDate)
            )
        }

        // 先找高相似样本；不足时再放宽，但始终只使用同位置数据参与正式评分。
        val strongSimilar = historical
            .filter { it.similarity >= 14 }
            .sortedWith(compareByDescending<HistoricalDay> { it.similarity }.thenByDescending { it.record.date })
            .take(12)
        val similar = if (strongSimilar.size >= 4) {
            strongSimilar
        } else {
            historical
                .filter { it.similarity >= 8 }
                .sortedWith(compareByDescending<HistoricalDay> { it.similarity }.thenByDescending { it.record.date })
                .take(10)
        }

        // 只作“资料补充”的全账本样本，不参与正式总分，避免不同位置串位误导。
        val ledgerFallbackCount = if (positionHistory.size < 4) {
            db.getRecentDailyRecords(180)
                .count { it.date < dateString && it.storeId != store.id && it.revenue > 0.0 }
                .coerceAtMost(99)
        } else 0

        val similarRevenue = weightedMetric(similar) { it.record.revenue }
        val similarCustomers = weightedMetric(similar) {
            it.record.customerTotal.toDouble().takeIf { v -> v > 0.0 }
        }
        val similarTicket = weightedMetric(similar) {
            it.record.customerTotal.takeIf { c -> c > 0 }?.let { c -> it.record.revenue / c.toDouble() }
        }

        val historyPart = scoreHistoricalV2(
            baselineRevenue = baselineRevenue,
            baselineCustomers = baselineCustomers,
            baselineTicket = baselineTicket,
            similarRevenue = similarRevenue,
            similarCustomers = similarCustomers,
            similarTicket = similarTicket,
            similar = similar
        )
        val weatherPart = scoreWeatherV2(weatherMetrics)
        val calendarPart = scoreCalendarV2(calendar, positionHistory, baselineRevenue)
        val trendPart = scoreTrendV2(positionHistory, baselineRevenue, baselineCustomers, baselineTicket)

        // 动态证据权重：相似日足够时历史权重自动提高；样本不足时其它维度只能提供有限修正。
        val historyWeight = when {
            similar.size >= 10 -> 0.52
            similar.size >= 6 -> 0.46
            similar.size >= 3 -> 0.36
            similar.isNotEmpty() -> 0.22
            else -> 0.08
        }
        val weatherWeight = if (weatherMetrics != null) 0.24 else 0.06
        val calendarWeight = if (positionHistory.size >= 8) 0.14 else 0.09
        val trendWeight = if (positionHistory.size >= 3) 0.16 else 0.08
        val totalWeight = historyWeight + weatherWeight + calendarWeight + trendWeight
        val rawIndex = (
            historyPart.index * historyWeight +
                weatherPart.index * weatherWeight +
                calendarPart.index * calendarWeight +
                trendPart.index * trendWeight
            ) / totalWeight

        val calibration = calibrationAdjustment(store.id, dateString)
        val calibratedRaw = (rawIndex + calibration.second).coerceIn(25.0, 100.0)

        // 样本越少，越向“中性 70”回归，避免 2~3 个样本也算出 95% 的假精度。
        val evidenceStrength = evidenceStrength(
            similarCount = similar.size,
            positionCount = positionHistory.size,
            hasWeather = weatherMetrics != null,
            calibrationCount = calibration.first
        )
        val total = (70.0 + (calibratedRaw - 70.0) * evidenceStrength)
            .roundToInt()
            .coerceIn(35, 100)

        val confidence = when {
            similar.size >= 10 && positionHistory.size >= 20 && weatherMetrics != null -> "HIGH"
            similar.size >= 5 && positionHistory.size >= 10 -> "MEDIUM"
            else -> "LOW"
        }

        val details = JSONObject().apply {
            put("score_version", "V2_SIMILAR_DAY")
            put("model", "STORE_BASELINE_SIMILAR_DAY_CALIBRATED")
            put("position_sample_count", positionHistory.size)
            put("same_store_similar_count", similar.size)
            put("ledger_fallback_count", ledgerFallbackCount)
            put("evidence_strength", evidenceStrength)
            put("dynamic_weights", JSONObject().apply {
                put("history", historyWeight / totalWeight)
                put("weather", weatherWeight / totalWeight)
                put("calendar", calendarWeight / totalWeight)
                put("trend", trendWeight / totalWeight)
            })
            put("calibration", JSONObject().apply {
                put("sample_count", calibration.first)
                put("adjustment_points", calibration.second)
                put("active", calibration.first >= 6)
            })
            put("calendar", JSONObject().apply {
                put("weekday", calendar.weekdayLabel)
                put("is_workday", calendar.isWorkday)
                put("is_weekend", calendar.isWeekend)
                put("phase", calendar.phase)
                put("phase_label", calendar.phaseLabel)
                put("holiday_name", calendar.holidayName)
                put("month_bucket", calendar.monthBucket)
            })
            put("weather", JSONObject().apply {
                weatherMetrics?.let { m ->
                    put("headline", m.headline)
                    put("max_pop", m.maxPop)
                    put("pre_open_rain", m.preOpenRain)
                    put("business_rain", m.businessRain)
                    put("avg_temp", m.avgTemp)
                    put("avg_humidity", m.avgHumidity)
                    put("max_wind_speed", m.maxWindSpeed)
                    put("has_alert", m.hasAlert)
                    put("rainy", m.rainy)
                }
            })
            put("reasons", JSONObject().apply {
                put("weather", JSONArray(weatherPart.reasons))
                put("history", JSONArray(historyPart.reasons))
                put("calendar", JSONArray(calendarPart.reasons))
                put("trend", JSONArray(trendPart.reasons + calibrationReason(calibration)))
            })
            put("similar_dates", JSONArray().apply {
                similar.forEach { h ->
                    put(JSONObject().apply {
                        put("date", h.record.date)
                        put("similarity", h.similarity)
                        put("weight", h.weight)
                        put("revenue", h.record.revenue)
                        put("customers", h.record.customerTotal)
                        put("ticket", if (h.record.customerTotal > 0) h.record.revenue / h.record.customerTotal else 0.0)
                    })
                }
            })
        }.toString()

        val now = System.currentTimeMillis()
        return BusinessScoreRecord(
            date = dateString,
            storeId = store.id,
            storeSyncId = store.syncId,
            storeName = store.name,
            totalScore = total,
            // V2 起四个字段表示 0~100 的“维度指数”，不再表示固定分值贡献。
            weatherScore = weatherPart.index,
            historyScore = historyPart.index,
            calendarScore = calendarPart.index,
            trendScore = trendPart.index,
            confidence = confidence,
            sampleCount = similar.size,
            weatherSummary = weatherPart.summary,
            historySummary = historyPart.summary,
            calendarSummary = calendarPart.summary,
            trendSummary = trendPart.summary,
            baselineRevenue = baselineRevenue,
            baselineCustomers = baselineCustomers,
            baselineTicket = baselineTicket,
            similarRevenue = similarRevenue,
            similarCustomers = similarCustomers,
            similarTicket = similarTicket,
            detailsJson = details,
            specialTag = "",
            specialNote = "",
            snapshotsJson = "[]",
            actualRevenue = 0.0,
            actualCustomers = 0,
            actualTicket = 0.0,
            actualProfit = 0.0,
            generatedAt = now,
            updatedAt = now
        )
    }

    private fun scoreHistoricalV2(
        baselineRevenue: Double,
        baselineCustomers: Double,
        baselineTicket: Double,
        similarRevenue: Double,
        similarCustomers: Double,
        similarTicket: Double,
        similar: List<HistoricalDay>
    ): ComponentResult {
        if (similar.isEmpty() || baselineRevenue <= 0.0 || similarRevenue <= 0.0) {
            return ComponentResult(
                index = 70.0,
                summary = "历史参考不足",
                reasons = listOf("同位置高相似营业日不足，历史维度回归中性，不用其它位置强行替代"),
                sampleCount = 0
            )
        }

        val ratios = mutableListOf<Pair<Double, Double>>()
        ratios += (similarRevenue / baselineRevenue).coerceIn(0.55, 1.45) to 0.55
        if (baselineCustomers > 0 && similarCustomers > 0) {
            ratios += (similarCustomers / baselineCustomers).coerceIn(0.55, 1.45) to 0.30
        }
        if (baselineTicket > 0 && similarTicket > 0) {
            ratios += (similarTicket / baselineTicket).coerceIn(0.65, 1.35) to 0.15
        }
        val weight = ratios.sumOf { it.second }.takeIf { it > 0.0 } ?: 1.0
        val ratio = ratios.sumOf { it.first * it.second } / weight
        val index = (70.0 + (ratio - 1.0) * 120.0).coerceIn(30.0, 100.0)
        val summary = when {
            similar.size < 4 -> "相似样本较少"
            ratio >= 1.12 -> "以往同期表现较好"
            ratio <= 0.88 -> "以往同期经营偏弱"
            else -> "以往同期经营稳定"
        }
        val reasons = mutableListOf<String>()
        reasons += "找到 ${similar.size} 个同位置相似营业日"
        reasons += "相似日营业额约为位置基准的 ${(ratioOf(similarRevenue, baselineRevenue) * 100).roundToInt()}%"
        if (baselineCustomers > 0 && similarCustomers > 0) {
            reasons += "相似日客流约为位置基准的 ${(ratioOf(similarCustomers, baselineCustomers) * 100).roundToInt()}%"
        }
        if (baselineTicket > 0 && similarTicket > 0) {
            reasons += "相似日客单价约为位置基准的 ${(ratioOf(similarTicket, baselineTicket) * 100).roundToInt()}%"
        }
        if (similar.size < 5) reasons += "样本少于5个，总分会自动向中性值回归"
        return ComponentResult(index, summary, reasons, similar.size, ratio)
    }

    private fun scoreWeatherV2(metrics: WeatherMetrics?): ComponentResult {
        if (metrics == null) {
            return ComponentResult(70.0, "天气数据暂不可用", listOf("缺少天气数据，天气维度按中性处理"))
        }
        var index = 92.0
        val reasons = mutableListOf<String>()

        when {
            metrics.maxPop >= 80 -> { index -= 25; reasons += "营业时段降雨概率高" }
            metrics.maxPop >= 60 -> { index -= 18; reasons += "营业时段有明显降雨风险" }
            metrics.maxPop >= 40 -> { index -= 11; reasons += "营业时段可能有雨" }
            metrics.maxPop >= 20 -> { index -= 5; reasons += "营业时段有轻微降雨概率" }
            else -> reasons += "营业时段降雨风险低"
        }
        when {
            metrics.businessRain >= 5.0 -> { index -= 18; reasons += "预计营业期间雨量较大" }
            metrics.businessRain >= 2.0 -> { index -= 12; reasons += "预计营业期间有明显降雨" }
            metrics.businessRain >= 0.5 -> { index -= 7; reasons += "预计营业期间有小雨" }
            metrics.businessRain > 0.05 -> { index -= 3; reasons += "预计营业期间有零星雨" }
        }
        when {
            metrics.preOpenRain >= 3.0 -> { index -= 14; reasons += "开摊前3小时降雨明显" }
            metrics.preOpenRain >= 0.5 -> { index -= 8; reasons += "开摊前3小时有雨" }
            metrics.preOpenRain > 0.05 -> { index -= 3; reasons += "开摊前3小时有零星雨" }
            else -> reasons += "开摊前3小时基本无雨"
        }
        metrics.avgTemp?.let { temp ->
            when {
                temp in 20.0..29.0 -> reasons += "温度适中"
                temp in 16.0..32.0 -> { index -= 4; reasons += "温度略偏离舒适区" }
                temp in 11.0..35.0 -> { index -= 9; reasons += "温度偏冷或偏热" }
                else -> { index -= 16; reasons += "温度条件较差" }
            }
        }
        metrics.avgHumidity?.let { humidity ->
            if (humidity >= 90) { index -= 5; reasons += "湿度很高" }
            else if (humidity >= 82) { index -= 2; reasons += "湿度偏高" }
        }
        metrics.maxWindSpeed?.let { wind ->
            if (wind >= 35) { index -= 12; reasons += "风速较大" }
            else if (wind >= 25) { index -= 7; reasons += "风力偏大" }
            else if (wind >= 16) { index -= 3; reasons += "有一定风力" }
        }
        if (metrics.hasAlert) {
            index -= 14
            reasons += "存在天气预警"
        }

        val tempText = metrics.avgTemp?.roundToInt()?.let { "${it}℃" }.orEmpty()
        val rainText = if (metrics.rainy) "有雨风险" else "无雨"
        val headline = metrics.headline.ifBlank { if (metrics.rainy) "天气有波动" else "天气稳定" }
        val summary = listOf(
            headline,
            rainText,
            tempText.takeIf { it.isNotBlank() }?.let { "$it${temperatureLabel(metrics.avgTemp)}" }
        ).filterNotNull().joinToString(" · ")
        return ComponentResult(index.coerceIn(20.0, 100.0), summary, reasons)
    }

    private fun scoreCalendarV2(
        target: CalendarInfo,
        historical: List<StoreDailyRecord>,
        baselineRevenue: Double
    ): ComponentResult {
        if (historical.size < 4 || baselineRevenue <= 0.0) {
            return ComponentResult(70.0, target.phaseLabel, listOf("同位置日期样本不足，日期环境按中性处理"))
        }

        fun medianFor(predicate: (LocalDate) -> Boolean): Pair<Double, Int> {
            val values = historical.mapNotNull { r ->
                val d = runCatching { LocalDate.parse(r.date) }.getOrNull() ?: return@mapNotNull null
                r.revenue.takeIf { it > 0 && predicate(d) }
            }
            return median(values) to values.size
        }

        val reasons = mutableListOf<String>()
        val signals = mutableListOf<Pair<Double, Double>>()
        var used = 0

        val (sameWeekday, weekdayCount) = medianFor { it.dayOfWeek == target.date.dayOfWeek }
        if (weekdayCount >= 3 && sameWeekday > 0) {
            signals += (sameWeekday / baselineRevenue).coerceIn(0.65, 1.35) to 0.65
            used += weekdayCount
            reasons += "同${target.weekdayLabel}样本 $weekdayCount 个，表现约为基准的 ${(sameWeekday / baselineRevenue * 100).roundToInt()}%"
        } else {
            val (sameWorkType, workCount) = medianFor { calendarInfo(it).isWorkday == target.isWorkday }
            if (workCount >= 4 && sameWorkType > 0) {
                signals += (sameWorkType / baselineRevenue).coerceIn(0.70, 1.30) to 0.45
                used += workCount
                reasons += if (target.isWorkday) "工作日历史已作为补充" else "周末/假日历史已作为补充"
            }
        }

        if (target.phase != "NORMAL") {
            val (samePhase, phaseCount) = medianFor { calendarInfo(it).phase == target.phase }
            if (phaseCount >= 2 && samePhase > 0) {
                signals += (samePhase / baselineRevenue).coerceIn(0.65, 1.35) to 0.35
                used += phaseCount
                reasons += "${target.phaseLabel}有 $phaseCount 个历史样本"
            } else {
                reasons += "${target.phaseLabel}历史样本不足，不主观加减分"
            }
        }

        if (signals.isEmpty()) {
            return ComponentResult(70.0, target.phaseLabel, reasons.ifEmpty { listOf("日期样本不足") })
        }
        val w = signals.sumOf { it.second }
        val ratio = signals.sumOf { it.first * it.second } / w
        val index = (70.0 + (ratio - 1.0) * 95.0).coerceIn(40.0, 100.0)
        return ComponentResult(index, target.phaseLabel, reasons, used, ratio)
    }

    private fun scoreTrendV2(
        historical: List<StoreDailyRecord>,
        baselineRevenue: Double,
        baselineCustomers: Double,
        baselineTicket: Double
    ): ComponentResult {
        val recent = historical.take(5)
        if (recent.size < 3 || baselineRevenue <= 0.0) {
            return ComponentResult(70.0, "近期趋势数据较少", listOf("同位置近期有效记录不足3次"), recent.size)
        }
        val recentRevenue = median(recent.map { it.revenue })
        val recentCustomers = median(recent.map { it.customerTotal.toDouble() }.filter { it > 0.0 })
        val recentTicket = median(recent.mapNotNull { r ->
            r.customerTotal.takeIf { it > 0 }?.let { r.revenue / it.toDouble() }
        }.filter { it > 0.0 })

        val ratios = mutableListOf<Pair<Double, Double>>()
        ratios += (recentRevenue / baselineRevenue).coerceIn(0.60, 1.40) to 0.45
        if (recentCustomers > 0 && baselineCustomers > 0) {
            ratios += (recentCustomers / baselineCustomers).coerceIn(0.60, 1.40) to 0.40
        }
        if (recentTicket > 0 && baselineTicket > 0) {
            ratios += (recentTicket / baselineTicket).coerceIn(0.70, 1.30) to 0.15
        }
        val w = ratios.sumOf { it.second }
        val ratio = ratios.sumOf { it.first * it.second } / w
        val index = (70.0 + (ratio - 1.0) * 100.0).coerceIn(35.0, 100.0)

        val revenueRatio = ratioOf(recentRevenue, baselineRevenue)
        val customerRatio = ratioOf(recentCustomers, baselineCustomers)
        val ticketRatio = ratioOf(recentTicket, baselineTicket)
        val summary = when {
            customerRatio in 0.01..0.90 && ticketRatio >= 0.95 -> "近期客流偏弱"
            ticketRatio in 0.01..0.90 && customerRatio >= 0.95 -> "近期客单价偏弱"
            ratio >= 1.10 -> "近期经营趋势偏强"
            ratio <= 0.90 -> "近期经营趋势偏弱"
            else -> "近期经营趋势稳定"
        }
        val reasons = mutableListOf("最近 ${recent.size} 次同位置营业已参与趋势判断")
        reasons += "近期营业额约为位置基准的 ${(revenueRatio * 100).roundToInt()}%"
        if (baselineCustomers > 0 && recentCustomers > 0) {
            reasons += "近期客流约为位置基准的 ${(customerRatio * 100).roundToInt()}%"
        }
        if (baselineTicket > 0 && recentTicket > 0) {
            reasons += "近期客单价约为位置基准的 ${(ticketRatio * 100).roundToInt()}%"
        }
        return ComponentResult(index, summary, reasons, recent.size, ratio)
    }

    private fun similarityScore(
        targetDate: LocalDate,
        targetCalendar: CalendarInfo,
        targetWeather: WeatherMetrics?,
        historyDate: LocalDate,
        historyCalendar: CalendarInfo,
        historyWeather: WeatherMetrics?
    ): Int {
        var score = 0
        if (targetCalendar.date.dayOfWeek == historyCalendar.date.dayOfWeek) {
            score += 5
        } else if (targetCalendar.isWorkday == historyCalendar.isWorkday) {
            // 同星期已经包含工作日属性，不重复叠加。
            score += 2
        }
        if (targetCalendar.phase == historyCalendar.phase) {
            score += if (targetCalendar.phase == "NORMAL") 3 else 6
        }
        if (targetCalendar.monthBucket == historyCalendar.monthBucket) score += 1

        val rawSeasonGap = abs(targetCalendar.date.dayOfYear - historyCalendar.date.dayOfYear)
        val seasonGap = min(rawSeasonGap, 365 - rawSeasonGap)
        score += when {
            seasonGap <= 30 -> 3
            seasonGap <= 60 -> 2
            seasonGap <= 90 -> 1
            else -> 0
        }

        if (targetWeather != null && historyWeather != null) {
            if (targetWeather.rainy == historyWeather.rainy) score += 5
            if (rainBand(targetWeather.preOpenRain) == rainBand(historyWeather.preOpenRain)) score += 2
            val t1 = targetWeather.avgTemp
            val t2 = historyWeather.avgTemp
            if (t1 != null && t2 != null) {
                val diff = abs(t1 - t2)
                score += when {
                    diff <= 2.0 -> 4
                    diff <= 5.0 -> 2
                    diff <= 8.0 -> 1
                    else -> 0
                }
            }
        }

        val daysAgo = ChronoUnit.DAYS.between(historyDate, targetDate).coerceAtLeast(0)
        score += when {
            daysAgo <= 30 -> 3
            daysAgo <= 60 -> 2
            daysAgo <= 120 -> 1
            else -> 0
        }
        return score
    }

    private fun similarityWeight(similarity: Int, targetDate: LocalDate, historyDate: LocalDate): Double {
        val daysAgo = ChronoUnit.DAYS.between(historyDate, targetDate).coerceAtLeast(0)
        val recency = when {
            daysAgo <= 30 -> 1.25
            daysAgo <= 60 -> 1.15
            daysAgo <= 120 -> 1.05
            else -> 0.95
        }
        return similarity.coerceAtLeast(1).toDouble() * recency
    }

    private fun weightedMetric(
        samples: List<HistoricalDay>,
        value: (HistoricalDay) -> Double?
    ): Double {
        val pairs = samples.mapNotNull { sample ->
            val v = value(sample) ?: return@mapNotNull null
            if (!v.isFinite() || v <= 0.0) null else v to sample.weight
        }
        if (pairs.isEmpty()) return 0.0
        val totalWeight = pairs.sumOf { it.second }.takeIf { it > 0.0 } ?: return 0.0
        return pairs.sumOf { it.first * it.second } / totalWeight
    }

    private fun evidenceStrength(
        similarCount: Int,
        positionCount: Int,
        hasWeather: Boolean,
        calibrationCount: Int
    ): Double {
        var strength = 0.38
        strength += when {
            similarCount >= 10 -> 0.38
            similarCount >= 6 -> 0.30
            similarCount >= 3 -> 0.20
            similarCount > 0 -> 0.10
            else -> 0.0
        }
        if (positionCount >= 20) strength += 0.12 else if (positionCount >= 10) strength += 0.07
        if (hasWeather) strength += 0.07
        if (calibrationCount >= 6) strength += 0.05
        return strength.coerceIn(0.38, 1.0)
    }

    private fun calibrationAdjustment(storeId: Long, date: String): Pair<Int, Double> {
        val completed = db.getBusinessScoresBefore(storeId, date, 30)
            .filter { score ->
                score.actualRevenue > 0.0 &&
                    score.baselineRevenue > 0.0 &&
                    runCatching { JSONObject(score.detailsJson).optString("score_version") }
                        .getOrDefault("") == "V2_SIMILAR_DAY"
            }
            .take(20)
        if (completed.size < 6) return completed.size to 0.0

        val errors = completed.map { score ->
            val predictedRatio = (1.0 + (score.totalScore - 70.0) / 120.0).coerceIn(0.60, 1.35)
            val actualRatio = (score.actualRevenue / score.baselineRevenue).coerceIn(0.55, 1.45)
            (actualRatio - predictedRatio).coerceIn(-0.20, 0.20)
        }
        val adjustment = (median(errors) * 120.0).coerceIn(-8.0, 8.0)
        return completed.size to adjustment
    }

    private fun calibrationReason(calibration: Pair<Int, Double>): List<String> {
        val count = calibration.first
        val adjustment = calibration.second
        return when {
            count < 6 -> listOf("已有 $count 个 V2 经营结果复盘；满6个后开始自动校准")
            abs(adjustment) < 0.5 -> listOf("已用 $count 个历史复盘自动校准，本次无需明显修正")
            adjustment > 0 -> listOf("已用 $count 个历史复盘自动校准，本位置模型修正 +${String.format("%.1f", adjustment)}")
            else -> listOf("已用 $count 个历史复盘自动校准，本位置模型修正 ${String.format("%.1f", adjustment)}")
        }
    }

    private fun rainBand(value: Double): Int = when {
        value >= 3.0 -> 3
        value >= 0.5 -> 2
        value > 0.05 -> 1
        else -> 0
    }

    fun calendarInfo(date: LocalDate): CalendarInfo {
        val holiday = holidays2026.firstOrNull { date in it.start..it.end }
        val nextHoliday = holidays2026
            .map { it to ChronoUnit.DAYS.between(date, it.start) }
            .filter { it.second in 1..3 }
            .minByOrNull { it.second }
        val previousHoliday = holidays2026
            .map { it to ChronoUnit.DAYS.between(it.end, date) }
            .filter { it.second in 1..3 }
            .minByOrNull { it.second }

        val phase: String
        val phaseLabel: String
        val holidayName: String
        when {
            holiday != null -> {
                phase = "IN"
                holidayName = holiday.name
                phaseLabel = "${holiday.name}节中"
            }
            nextHoliday != null -> {
                phase = "PRE"
                holidayName = nextHoliday.first.name
                phaseLabel = "${nextHoliday.first.name}节前${nextHoliday.second}天"
            }
            previousHoliday != null -> {
                phase = "POST"
                holidayName = previousHoliday.first.name
                phaseLabel = "${previousHoliday.first.name}节后${previousHoliday.second}天"
            }
            else -> {
                phase = "NORMAL"
                holidayName = ""
                phaseLabel = if (isWorkday(date)) "普通工作日" else "普通周末"
            }
        }

        val workday = isWorkday(date)
        return CalendarInfo(
            date = date,
            isWorkday = workday,
            isWeekend = !workday,
            weekdayLabel = weekdayLabel(date.dayOfWeek),
            phase = phase,
            phaseLabel = phaseLabel,
            holidayName = holidayName,
            monthBucket = monthBucket(date)
        )
    }

    private fun isWorkday(date: LocalDate): Boolean {
        if (date.year == 2026) {
            if (date in makeupWorkdays2026) return true
            if (holidays2026.any { date in it.start..it.end }) return false
        }
        return date.dayOfWeek !in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
    }

    private fun monthBucket(date: LocalDate): Int = when (date.dayOfMonth) {
        in 1..10 -> 1
        in 11..20 -> 2
        else -> 3
    }

    private fun weekdayLabel(day: DayOfWeek): String = when (day) {
        DayOfWeek.MONDAY -> "周一"
        DayOfWeek.TUESDAY -> "周二"
        DayOfWeek.WEDNESDAY -> "周三"
        DayOfWeek.THURSDAY -> "周四"
        DayOfWeek.FRIDAY -> "周五"
        DayOfWeek.SATURDAY -> "周六"
        DayOfWeek.SUNDAY -> "周日"
    }

    private fun weatherMetrics(
        overview: WeatherOverview,
        store: StoreOption,
        actualStartTime: String? = null,
        actualEndTime: String? = null
    ): WeatherMetrics {
        val startHour = parseHour(actualStartTime?.takeIf { it.isNotBlank() } ?: store.defaultStartTime, 16)
        val endHour = parseHour(actualEndTime?.takeIf { it.isNotBlank() } ?: store.defaultEndTime, 24)
            .let { if (it <= startHour) 24 else it }
        val preStart = (startHour - 3).coerceAtLeast(0)
        val hours = overview.hourly
        val preHours = hours.filter { hourInWindow(it, preStart, startHour) }
        val businessHours = hours.filter { hourInWindow(it, startHour, endHour) }
        val relevantBusiness = businessHours.ifEmpty { hours }
        val day = overview.selectedDay()
        val maxPop = relevantBusiness.mapNotNull { it.precipitationProbability }.maxOrNull()
            ?: day?.precipitationProbability
            ?: 0.0
        val businessRain = relevantBusiness.sumOf { it.precipitation ?: 0.0 }
        val preRain = preHours.sumOf { it.precipitation ?: 0.0 }
        val temps = relevantBusiness.mapNotNull { it.temperature }
        val humidities = relevantBusiness.mapNotNull { it.humidity }
        val winds = relevantBusiness.mapNotNull { it.windSpeed }
        val avgTemp = temps.averageOrNull() ?: listOfNotNull(day?.tempMin, day?.tempMax).averageOrNull()
        val avgHumidity = humidities.averageOrNull() ?: day?.humidity
        val maxWind = winds.maxOrNull()
        val headline = overview.current?.text?.takeIf { overview.date == LocalDate.now().toString() }
            ?: day?.textDay
            ?: relevantBusiness.firstOrNull()?.text
            ?: "天气"
        val rainy = businessRain > 0.05 || preRain > 0.05 || maxPop >= 40 || headline.contains("雨")
        return WeatherMetrics(
            headline = headline,
            maxPop = maxPop,
            preOpenRain = preRain,
            businessRain = businessRain,
            avgTemp = avgTemp,
            avgHumidity = avgHumidity,
            maxWindSpeed = maxWind,
            hasAlert = overview.alerts.isNotEmpty(),
            rainy = rainy
        )
    }

    private fun parseHour(value: String, fallback: Int): Int =
        value.trim().substringBefore(':').toIntOrNull()?.coerceIn(0, 24) ?: fallback

    private fun hourInWindow(hour: WeatherHour, start: Int, end: Int): Boolean {
        val h = hour.time.substringAfter('T', "").take(2).toIntOrNull() ?: return false
        return h >= start && h < end
    }

    private fun temperatureLabel(temp: Double?): String = when {
        temp == null -> ""
        temp in 20.0..29.0 -> "适中"
        temp < 20.0 -> "偏凉"
        else -> "偏热"
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2.0
    }

    private fun ratioOf(value: Double, baseline: Double): Double =
        if (value > 0 && baseline > 0) value / baseline else 0.0

    private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()

    private data class HolidayPeriod(val name: String, val start: LocalDate, val end: LocalDate)

    /**
     * 2026 中国大陆法定节假日日期继续沿用项目现有配置。
     * 其它年份安全降级为普通工作日/周末，不猜测尚未配置的调休日。
     */
    private val holidays2026 = listOf(
        HolidayPeriod("元旦", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 3)),
        HolidayPeriod("春节", LocalDate.of(2026, 2, 15), LocalDate.of(2026, 2, 23)),
        HolidayPeriod("清明", LocalDate.of(2026, 4, 4), LocalDate.of(2026, 4, 6)),
        HolidayPeriod("劳动节", LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 5)),
        HolidayPeriod("端午", LocalDate.of(2026, 6, 19), LocalDate.of(2026, 6, 21)),
        HolidayPeriod("中秋", LocalDate.of(2026, 9, 25), LocalDate.of(2026, 9, 27)),
        HolidayPeriod("国庆", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 7))
    )

    private val makeupWorkdays2026 = setOf(
        LocalDate.of(2026, 1, 4),
        LocalDate.of(2026, 2, 14),
        LocalDate.of(2026, 2, 28),
        LocalDate.of(2026, 5, 9),
        LocalDate.of(2026, 9, 20),
        LocalDate.of(2026, 10, 10)
    )
}
