package com.tianxian.fruit.data

import com.tianxian.fruit.sync.WeatherClient
import com.tianxian.fruit.sync.WeatherHour
import com.tianxian.fruit.sync.WeatherOverview
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.min

/**
 * V1.4.7.54 今日经营建议 V1。
 *
 * 经营指数只使用开摊前可获得的数据：天气、同位置历史、日期环境、近期趋势。
 * 当日实际营业额/客流/利润只用于事后复盘，不参与当天评分，避免数据泄漏。
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
        val similarity: Int
    )

    fun calculate(
        date: LocalDate,
        store: StoreOption,
        weather: WeatherOverview?
    ): BusinessScoreRecord {
        val dateString = date.toString()
        val positionHistory = db.getStoreDailyRecordsBefore(store.id, dateString, 180)
        val ledgerFallback = if (positionHistory.size < 4) {
            db.getRecentDailyRecords(360)
                .asSequence()
                .filter { it.date < dateString && it.storeId != store.id }
                .distinctBy { "${it.date}|${it.storeId}" }
                .take(180)
                .toList()
        } else {
            emptyList()
        }
        // 样本不足时按“同位置 -> 全账本”降级，但可信度不会因此虚高。
        val fallbackScope = positionHistory.size < 4 && ledgerFallback.isNotEmpty()
        val analysisHistory = if (fallbackScope) {
            (positionHistory + ledgerFallback)
                .distinctBy { "${it.date}|${it.storeId}" }
                .sortedByDescending { it.date }
                .take(180)
        } else {
            positionHistory
        }
        val calendar = calendarInfo(date)
        val weatherMetrics = weather?.let { weatherMetrics(it, store) }

        // 基准优先只使用本位置；新位置完全没有历史时才安全降级为全账本基准。
        val baselinePool = positionHistory.take(90).ifEmpty { analysisHistory.take(90) }
        val baselineRevenue = median(baselinePool.map { it.revenue }.filter { it > 0.0 })
        val baselineCustomers = median(baselinePool.map { it.customerTotal.toDouble() }.filter { it > 0.0 })
        val baselineTicket = median(
            baselinePool.mapNotNull { r ->
                r.customerTotal.takeIf { it > 0 }?.let { r.revenue / it.toDouble() }
            }.filter { it > 0.0 }
        )

        val historical = analysisHistory.map { record ->
            val histDate = runCatching { LocalDate.parse(record.date) }.getOrNull() ?: date.minusYears(10)
            val histCalendar = calendarInfo(histDate)
            val histStore = db.getStoreById(record.storeId) ?: store
            val histWeather = db.getBusinessWeatherHistory(record.date, record.storeId)?.let { archived ->
                runCatching {
                    weatherMetrics(
                        WeatherClient.parseOverview(archived.payloadJson, historical = true),
                        histStore
                    )
                }.getOrNull()
            }
            HistoricalDay(
                record = record,
                calendar = histCalendar,
                weather = histWeather,
                similarity = similarityScore(calendar, weatherMetrics, histCalendar, histWeather) +
                    if (record.storeId == store.id) 2 else 0
            )
        }

        val strongSimilar = historical
            .filter { it.similarity >= 9 }
            .sortedWith(compareByDescending<HistoricalDay> { it.similarity }.thenByDescending { it.record.date })
            .take(12)
        val similar = if (strongSimilar.size >= 4) strongSimilar else historical
            .sortedWith(compareByDescending<HistoricalDay> { it.similarity }.thenByDescending { it.record.date })
            .take(8)

        val similarRevenue = median(similar.map { it.record.revenue }.filter { it > 0.0 })
        val similarCustomers = median(similar.map { it.record.customerTotal.toDouble() }.filter { it > 0.0 })
        val similarTicket = median(
            similar.mapNotNull { h ->
                h.record.customerTotal.takeIf { it > 0 }?.let { h.record.revenue / it.toDouble() }
            }.filter { it > 0.0 }
        )

        val weatherPart = scoreWeather(weatherMetrics)
        val historyPart = scoreHistorical(
            baselineRevenue,
            baselineCustomers,
            baselineTicket,
            similarRevenue,
            similarCustomers,
            similarTicket,
            similar.size,
            fallbackScope
        )
        val calendarHistory = positionHistory.ifEmpty { analysisHistory }
        val calendarPart = scoreCalendar(calendar, calendarHistory, baselineRevenue)
        // 近期趋势必须坚持同位置，不用其它点位替代，避免串位误判。
        val trendPart = scoreTrend(positionHistory, baselineRevenue, baselineCustomers, baselineTicket)

        val total = (
            weatherPart.first +
                historyPart.first +
                calendarPart.first +
                trendPart.first
            ).roundToInt().coerceIn(0, 100)

        val sameStoreSimilarCount = similar.count { it.record.storeId == store.id }
        val confidence = when {
            fallbackScope -> "LOW"
            weatherMetrics != null && sameStoreSimilarCount >= 10 -> "HIGH"
            sameStoreSimilarCount >= 5 -> "MEDIUM"
            else -> "LOW"
        }

        val details = JSONObject().apply {
            put("score_version", "V1")
            put("sample_scope", if (fallbackScope) "LEDGER_FALLBACK" else "STORE")
            put("position_sample_count", positionHistory.size)
            put("same_store_similar_count", sameStoreSimilarCount)
            put("weights", JSONObject().apply {
                put("weather", 35)
                put("history", 30)
                put("calendar", 20)
                put("trend", 15)
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
                put("weather", JSONArray(weatherPart.third))
                put("history", JSONArray(historyPart.third))
                put("calendar", JSONArray(calendarPart.third))
                put("trend", JSONArray(trendPart.third))
            })
            put("similar_dates", JSONArray().apply {
                similar.forEach { h ->
                    put(JSONObject().apply {
                        put("date", h.record.date)
                        put("score", h.similarity)
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
            weatherScore = weatherPart.first,
            historyScore = historyPart.first,
            calendarScore = calendarPart.first,
            trendScore = trendPart.first,
            confidence = confidence,
            sampleCount = similar.size,
            weatherSummary = weatherPart.second,
            historySummary = historyPart.second,
            calendarSummary = calendarPart.second,
            trendSummary = trendPart.second,
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

    private fun scoreWeather(metrics: WeatherMetrics?): Triple<Double, String, List<String>> {
        if (metrics == null) {
            return Triple(24.5, "天气数据暂不可用", listOf("天气暂不可用，按中性值计分"))
        }

        var score = 35.0
        val reasons = mutableListOf<String>()

        when {
            metrics.maxPop >= 80 -> { score -= 11; reasons += "营业时段降雨概率较高 -11" }
            metrics.maxPop >= 60 -> { score -= 8; reasons += "营业时段有明显降雨风险 -8" }
            metrics.maxPop >= 40 -> { score -= 5; reasons += "营业时段可能有雨 -5" }
            metrics.maxPop >= 20 -> { score -= 2; reasons += "营业时段有轻微降雨概率 -2" }
            else -> reasons += "营业时段降雨风险低"
        }

        when {
            metrics.businessRain >= 5.0 -> { score -= 7; reasons += "预计营业期间雨量较大 -7" }
            metrics.businessRain >= 2.0 -> { score -= 5; reasons += "预计营业期间有明显降雨 -5" }
            metrics.businessRain >= 0.5 -> { score -= 3; reasons += "预计营业期间有小雨 -3" }
            metrics.businessRain > 0.05 -> { score -= 1; reasons += "预计营业期间有微量降雨 -1" }
        }

        when {
            metrics.preOpenRain >= 3.0 -> { score -= 5; reasons += "开摊前3小时降雨明显 -5" }
            metrics.preOpenRain >= 0.5 -> { score -= 3; reasons += "开摊前3小时有雨 -3" }
            metrics.preOpenRain > 0.05 -> { score -= 1; reasons += "开摊前3小时有零星雨 -1" }
            else -> reasons += "开摊前3小时基本无雨"
        }

        metrics.avgTemp?.let { temp ->
            when {
                temp in 20.0..29.0 -> reasons += "温度适中"
                temp in 16.0..32.0 -> { score -= 2; reasons += "温度略偏离舒适区 -2" }
                temp in 11.0..35.0 -> { score -= 5; reasons += "温度偏冷或偏热 -5" }
                else -> { score -= 8; reasons += "温度条件较差 -8" }
            }
        }

        metrics.avgHumidity?.let { humidity ->
            when {
                humidity >= 90 -> { score -= 3; reasons += "湿度很高 -3" }
                humidity >= 82 -> { score -= 1; reasons += "湿度偏高 -1" }
            }
        }

        metrics.maxWindSpeed?.let { wind ->
            when {
                wind >= 35 -> { score -= 5; reasons += "风速较大 -5" }
                wind >= 25 -> { score -= 3; reasons += "风力偏大 -3" }
                wind >= 16 -> { score -= 1; reasons += "有一定风力 -1" }
            }
        }

        if (metrics.hasAlert) {
            score -= 5
            reasons += "存在天气预警 -5"
        }

        val tempText = metrics.avgTemp?.roundToInt()?.let { "${it}℃" }.orEmpty()
        val rainText = if (metrics.rainy) "有雨风险" else "无雨"
        val headline = metrics.headline.ifBlank { if (metrics.rainy) "天气有波动" else "天气稳定" }
        val summary = listOf(headline, rainText, tempText.takeIf { it.isNotBlank() }?.let { "$it${temperatureLabel(metrics.avgTemp)}" })
            .filterNotNull()
            .joinToString(" · ")

        return Triple(score.coerceIn(5.0, 35.0), summary, reasons)
    }

    private fun scoreHistorical(
        baselineRevenue: Double,
        baselineCustomers: Double,
        baselineTicket: Double,
        similarRevenue: Double,
        similarCustomers: Double,
        similarTicket: Double,
        sampleCount: Int,
        fallbackScope: Boolean
    ): Triple<Double, String, List<String>> {
        if (sampleCount <= 0 || baselineRevenue <= 0.0 || similarRevenue <= 0.0) {
            return Triple(18.0, "历史相似数据较少", listOf("缺少足够同位置历史样本，按中性偏低权重计分"))
        }

        val ratios = mutableListOf<Pair<Double, Double>>()
        ratios += (similarRevenue / baselineRevenue) to 0.55
        if (baselineCustomers > 0 && similarCustomers > 0) ratios += (similarCustomers / baselineCustomers) to 0.30
        if (baselineTicket > 0 && similarTicket > 0) ratios += (similarTicket / baselineTicket) to 0.15
        val weight = ratios.sumOf { it.second }.takeIf { it > 0 } ?: 1.0
        val ratio = ratios.sumOf { it.first * it.second } / weight
        val factor = (0.80 + (ratio - 1.0) * 0.90).coerceIn(0.35, 1.0)
        val score = 30.0 * factor
        val summary = if (fallbackScope) {
            "同位置数据较少 · 已参考全账本"
        } else {
            when {
                ratio >= 1.12 -> "以往同期表现较好"
                ratio <= 0.88 -> "以往同期经营偏弱"
                else -> "以往同期经营稳定"
            }
        }
        val reasons = mutableListOf<String>()
        reasons += if (fallbackScope) {
            "同位置样本不足，降级匹配到 $sampleCount 个全账本相似营业日"
        } else {
            "匹配到 $sampleCount 个同位置相似营业日"
        }
        reasons += "相似日营业额约为位置基准的 ${(ratioOf(similarRevenue, baselineRevenue) * 100).roundToInt()}%"
        if (baselineCustomers > 0 && similarCustomers > 0) {
            reasons += "相似日客流约为位置基准的 ${(ratioOf(similarCustomers, baselineCustomers) * 100).roundToInt()}%"
        }
        if (baselineTicket > 0 && similarTicket > 0) {
            reasons += "相似日客单价约为位置基准的 ${(ratioOf(similarTicket, baselineTicket) * 100).roundToInt()}%"
        }
        return Triple(score.coerceIn(6.0, 30.0), summary, reasons)
    }

    private fun scoreCalendar(
        target: CalendarInfo,
        historical: List<StoreDailyRecord>,
        baselineRevenue: Double
    ): Triple<Double, String, List<String>> {
        if (historical.isEmpty() || baselineRevenue <= 0.0) {
            return Triple(15.0, target.phaseLabel, listOf("日期样本不足，日期环境按中性值计分"))
        }

        fun averageFor(predicate: (LocalDate) -> Boolean): Double {
            val values = historical.mapNotNull { r ->
                val d = runCatching { LocalDate.parse(r.date) }.getOrNull() ?: return@mapNotNull null
                r.revenue.takeIf { it > 0 && predicate(d) }
            }
            return median(values)
        }

        var score = 15.0
        val reasons = mutableListOf<String>()

        val sameWeekday = averageFor { it.dayOfWeek == target.date.dayOfWeek }
        if (sameWeekday > 0) {
            val delta = ((sameWeekday / baselineRevenue) - 1.0).coerceIn(-0.25, 0.25)
            score += delta * 10.0
            reasons += "同星期历史表现 ${(sameWeekday / baselineRevenue * 100).roundToInt()}%"
        } else {
            // 星期样本缺失时才回退到工作日/周末，避免同一日期属性重复加权。
            val sameWorkType = averageFor { calendarInfo(it).isWorkday == target.isWorkday }
            if (sameWorkType > 0) {
                val delta = ((sameWorkType / baselineRevenue) - 1.0).coerceIn(-0.25, 0.25)
                score += delta * 8.0
                reasons += if (target.isWorkday) "工作日历史已参与评分" else "周末/假日历史已参与评分"
            }
        }

        val samePhase = averageFor { calendarInfo(it).phase == target.phase }
        if (samePhase > 0 && target.phase != "NORMAL") {
            val delta = ((samePhase / baselineRevenue) - 1.0).coerceIn(-0.25, 0.25)
            score += delta * 8.0
            reasons += "${target.phaseLabel}历史表现 ${(samePhase / baselineRevenue * 100).roundToInt()}%"
        }

        val sameMonthBucket = averageFor { monthBucket(it) == target.monthBucket }
        if (sameMonthBucket > 0) {
            val delta = ((sameMonthBucket / baselineRevenue) - 1.0).coerceIn(-0.25, 0.25)
            score += delta * 5.0
            reasons += "月内消费周期已参与评分"
        }

        if (target.holidayName.isNotBlank()) reasons += target.phaseLabel
        return Triple(score.coerceIn(5.0, 20.0), target.phaseLabel, reasons)
    }

    private fun scoreTrend(
        historical: List<StoreDailyRecord>,
        baselineRevenue: Double,
        baselineCustomers: Double,
        baselineTicket: Double
    ): Triple<Double, String, List<String>> {
        val recent = historical.take(5)
        if (recent.size < 3 || baselineRevenue <= 0) {
            return Triple(10.5, "近期趋势数据较少", listOf("同位置近期记录不足3次"))
        }
        val recentRevenue = median(recent.map { it.revenue }.filter { it > 0 })
        val recentCustomers = median(recent.map { it.customerTotal.toDouble() }.filter { it > 0 })
        val recentTicket = median(recent.mapNotNull { r ->
            r.customerTotal.takeIf { it > 0 }?.let { r.revenue / it.toDouble() }
        }.filter { it > 0 })

        val ratios = mutableListOf<Pair<Double, Double>>()
        if (recentRevenue > 0) ratios += (recentRevenue / baselineRevenue) to 0.55
        if (recentCustomers > 0 && baselineCustomers > 0) ratios += (recentCustomers / baselineCustomers) to 0.30
        if (recentTicket > 0 && baselineTicket > 0) ratios += (recentTicket / baselineTicket) to 0.15
        val weight = ratios.sumOf { it.second }.takeIf { it > 0 } ?: 1.0
        val ratio = ratios.sumOf { it.first * it.second } / weight
        val factor = (0.80 + (ratio - 1.0) * 0.90).coerceIn(0.35, 1.0)
        val score = 15.0 * factor
        val summary = when {
            ratio >= 1.10 -> "近期经营趋势偏强"
            ratio <= 0.90 -> "近期经营趋势偏弱"
            else -> "近期经营趋势稳定"
        }
        val reasons = mutableListOf("最近 ${recent.size} 次同位置营业已参与趋势判断")
        reasons += "近期营业额约为位置基准的 ${(ratioOf(recentRevenue, baselineRevenue) * 100).roundToInt()}%"
        if (baselineCustomers > 0 && recentCustomers > 0) {
            reasons += "近期客流约为位置基准的 ${(ratioOf(recentCustomers, baselineCustomers) * 100).roundToInt()}%"
        }
        return Triple(score.coerceIn(4.0, 15.0), summary, reasons)
    }

    private fun similarityScore(
        targetCalendar: CalendarInfo,
        targetWeather: WeatherMetrics?,
        historyCalendar: CalendarInfo,
        historyWeather: WeatherMetrics?
    ): Int {
        var score = 0
        if (targetCalendar.date.dayOfWeek == historyCalendar.date.dayOfWeek) score += 4
        if (targetCalendar.isWorkday == historyCalendar.isWorkday) score += 3
        if (targetCalendar.phase == historyCalendar.phase) score += if (targetCalendar.phase == "NORMAL") 2 else 5
        if (targetCalendar.monthBucket == historyCalendar.monthBucket) score += 1
        val rawSeasonGap = abs(targetCalendar.date.dayOfYear - historyCalendar.date.dayOfYear)
        val seasonGap = min(rawSeasonGap, 365 - rawSeasonGap)
        score += when {
            seasonGap <= 45 -> 2
            seasonGap <= 75 -> 1
            else -> 0
        }
        if (targetWeather != null && historyWeather != null) {
            if (targetWeather.rainy == historyWeather.rainy) score += 4
            val t1 = targetWeather.avgTemp
            val t2 = historyWeather.avgTemp
            if (t1 != null && t2 != null) {
                val diff = abs(t1 - t2)
                score += when {
                    diff <= 3.0 -> 3
                    diff <= 6.0 -> 2
                    diff <= 9.0 -> 1
                    else -> 0
                }
            }
        }
        return score
    }

    fun calendarInfo(date: LocalDate): CalendarInfo {
        val holiday = holidays2026.firstOrNull { date in it.start..it.end }
        val nextHoliday = holidays2026
            .map { it to java.time.temporal.ChronoUnit.DAYS.between(date, it.start) }
            .filter { it.second in 1..3 }
            .minByOrNull { it.second }
        val previousHoliday = holidays2026
            .map { it to java.time.temporal.ChronoUnit.DAYS.between(it.end, date) }
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

    private fun weatherMetrics(overview: WeatherOverview, store: StoreOption): WeatherMetrics {
        val startHour = parseHour(store.defaultStartTime, 16)
        val endHour = parseHour(store.defaultEndTime, 24).let { if (it <= startHour) 24 else it }
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
     * 2026 中国大陆法定节假日，按国务院办公厅 2025-11-04 公布的放假调休日期。
     * 其它年份安全降级为普通工作日/周末，不猜测尚未公布的调休日。
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
