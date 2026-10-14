package com.tianxian.fruit.data

/**
 * Observed (not forecast) hourly weather associated with a saved selling shift.
 * Use a unique store and business date; hours are local clock hours (0–23).
 */
data class CustomerObservedWeatherHour(
    val storeId: Long,
    val businessDate: String,
    val hour: Int,
    val condition: String,
    val precipitationMm: Double?,
    val raining: Boolean
)

data class CustomerWeatherHourTraffic(
    val hour: Int,
    val weather: String,
    val precipitationMm: Double?,
    val paymentCount: Int,
    val baselineCount: Double?,
    val raining: Boolean
)

data class CustomerRainDryComparison(
    val rainySamples: Int,
    val drySamples: Int,
    val rainyAverage: Double?,
    val dryAverage: Double?,
    val difference: Double?
) {
    val reliable: Boolean get() = rainySamples >= 3 && drySamples >= 3
}

data class CustomerWeatherTrafficResult(
    val storeId: Long,
    val storeName: String,
    val date: String,
    val observed: List<CustomerWeatherHourTraffic>,
    val rainDry: CustomerRainDryComparison?,
    val source: String,
    val notes: List<String>
)

internal object CustomerTrafficWeatherAnalyzer {
    /**
     * Only business days having a saved session and imported positive receipts
     * are evaluated, using same-store + same-weekday dates within the lookback
     * selection, with a consistent observed payment channel set.
     */
    fun analyze(
        comparisons: List<CustomerTrafficComparison>,
        payments: List<CustomerPaymentRecord>,
        weather: List<CustomerObservedWeatherHour>,
        platform: String = "ALL"
    ): List<CustomerWeatherTrafficResult> {
        val positive = payments.filter { it.netAmount > 0.005 &&
            (platform == "ALL" || it.platform == platform) }
        val paymentByDay = positive.groupBy { it.storeId to it.businessDate }
        val weatherByDay = weather.groupBy { it.storeId to it.businessDate }
        return comparisons.map { comparison ->
            val storeId = comparison.storeId
            val date = comparison.date
            val current = paymentByDay[storeId to date].orEmpty()
            val currentSources = current.map { it.platform }.toSet()
            val forecastless = weatherByDay[storeId to date].orEmpty()
                .associateBy { it.hour }
            val paymentHours = current.groupingBy {
                it.tradeTime.substringAfter(' ', "").take(2).toIntOrNull()
            }.eachCount()
            val observed = comparison.hourRows.mapNotNull { row ->
                val actual = forecastless[row.hour] ?: return@mapNotNull null
                CustomerWeatherHourTraffic(
                    hour = row.hour,
                    weather = actual.condition,
                    precipitationMm = actual.precipitationMm,
                    paymentCount = paymentHours[row.hour] ?: 0,
                    baselineCount = row.historicAverage,
                    raining = actual.raining
                )
            }
            // Compare only historic same-weekday observation pairs,
            // never per-customer (anonymous bills do not identify people).
            val rain = mutableListOf<Int>()
            val dry = mutableListOf<Int>()
            comparison.historicDates.forEach { historyDate ->
                val dayRecords = paymentByDay[storeId to historyDate].orEmpty()
                if (dayRecords.isEmpty() || dayRecords.map { it.platform }.toSet() != currentSources)
                    return@forEach
                val observedHours = weatherByDay[storeId to historyDate].orEmpty()
                    .associateBy { it.hour }
                val historyHours = dayRecords.groupingBy {
                    it.tradeTime.substringAfter(' ', "").take(2).toIntOrNull()
                }.eachCount()
                comparison.hourRows.forEach { hour ->
                    val sample = observedHours[hour.hour] ?: return@forEach
                    if (sample.raining) rain.add(historyHours[hour.hour] ?: 0)
                    else dry.add(historyHours[hour.hour] ?: 0)
                }
            }
            val stats = if (rain.isEmpty() && dry.isEmpty()) null else {
                val rainyAverage = rain.takeIf { it.isNotEmpty() }?.average()
                val dryAverage = dry.takeIf { it.isNotEmpty() }?.average()
                CustomerRainDryComparison(
                    rainySamples = rain.size,
                    drySamples = dry.size,
                    rainyAverage = rainyAverage,
                    dryAverage = dryAverage,
                    difference = if (rain.size >= 3 && dry.size >= 3 &&
                        dryAverage != null && dryAverage > 0.001 && rainyAverage != null
                    ) (rainyAverage - dryAverage) / dryAverage else null
                )
            }
            val notes = buildList {
                if (observed.isEmpty()) add("当天缺少营业时段的历史实际逐小时天气，不能关联降雨与收款。")
                if (observed.size < comparison.hourRows.size) add("只有${observed.size}/${comparison.hourRows.size}个完成时段有实况天气，部分缺失。")
                if (stats == null || !stats.reliable) add("历史同位置、同星期雨天和非雨天各需至少3个可比较小时样本；目前证据不足。")
                add("天气与付款的同时变化不能证明因果关系，且付款笔数不等于独立顾客数。")
            }
            CustomerWeatherTrafficResult(
                storeId=storeId,storeName=comparison.storeName,date=date,
                observed=observed,rainDry=stats,source="逐小时实际天气（非预报）",
                notes=notes
            )
        }
    }
}
