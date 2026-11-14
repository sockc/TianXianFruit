package com.tianxian.fruit.data

import com.tianxian.fruit.sync.WeatherHour
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/** One saved business-day archive. Never put live forecasts in this list. */
internal data class CustomerWeatherImpactDay(
    val session: CustomerTrafficSession,
    val hours: List<WeatherHour>,
    val archivePresent: Boolean
)

internal data class CustomerWeatherImpactHour(
    val hour: Int,
    val rainy: Boolean,
    val payments: Int,
    val electronicRevenue: Double,
    val condition: String
)

internal data class CustomerWeatherImpactHistory(
    val rainyDays: Int,
    val dryDays: Int,
    val comparableSlots: Int,
    val rainyPaymentsPerHour: Double?,
    val dryPaymentsPerHour: Double?,
    val rainyRevenuePerHour: Double?,
    val dryRevenuePerHour: Double?,
    val reliable: Boolean
) {
    val paymentDifference: Double? get() =
        if (reliable && rainyPaymentsPerHour != null && dryPaymentsPerHour != null &&
            dryPaymentsPerHour > 0.001
        ) (rainyPaymentsPerHour - dryPaymentsPerHour) / dryPaymentsPerHour else null
}

internal data class CustomerWeatherImpactSummary(
    val sessionCount: Int,
    val archiveCount: Int,
    val expectedHours: Int,
    val observedHours: Int,
    val before: RainImpactPeriod,
    val rainy: RainImpactPeriod,
    val after: RainImpactPeriod,
    val dry: RainImpactPeriod,
    val hourly: List<CustomerWeatherImpactHour>,
    val history: CustomerWeatherImpactHistory,
    val warnings: List<String>
)

/**
 * Read-only weather/receipt analysis. Only archived actual hours and positively
 * attributed, non-excluded receipts are eligible. Never infer cash or buyers.
 */
internal object CustomerWeatherImpactAnalyzer {
    private fun timestamp(text: String): LocalDateTime? = runCatching {
        LocalDateTime.parse(text.trim().replace(' ', 'T').take(16))
    }.getOrNull()

    private fun wet(hour: WeatherHour): Boolean =
        (hour.precipitation ?: 0.0) > 0.05 ||
            hour.text.contains("雨") || hour.text.contains("雷暴") ||
            (hour.code.toIntOrNull() ?: -1) in 300..399

    private fun total(label: String, periods: List<RainImpactPeriod>) = RainImpactPeriod(
        label = label,
        observedHours = periods.sumOf { it.observedHours },
        paymentCount = periods.sumOf { it.paymentCount },
        electronicRevenue = periods.sumOf { it.electronicRevenue },
        identifiedCustomers = 0 // Never sum unique customers across days.
    )

    fun analyze(
        selected: List<CustomerWeatherImpactDay>,
        historical: List<CustomerWeatherImpactDay>,
        receipts: List<CustomerPaymentRecord>,
        platform: String,
        sharedPaymentCount: Int = 0,
        now: LocalDateTime = LocalDateTime.now()
    ): CustomerWeatherImpactSummary {
        val selectedDays = selected.distinctBy { it.session.storeId to it.session.date }
            .sortedBy { it.session.date }
        val storeIds = selectedDays.map { it.session.storeId }.toSet()
        val positive = receipts.filter {
            it.netAmount > 0.005 &&
                (platform == "ALL" || it.platform == platform)
        }
        val owned = positive.filter { it.storeId in storeIds }
        val byStoreDay = owned.groupBy { it.storeId to it.businessDate }

        val reports = selectedDays.map { day ->
            val complete = CustomerTrafficDeepDive.finishedHours(day.session, now).toSet()
            val actual = day.hours.filter {
                it.actualAvailable && timestamp(it.time)?.let { at ->
                    at.hour in complete && !at.plusHours(1).isAfter(now)
                } == true
            }
            RainImpactAnalyzer.analyze(
                date = LocalDate.parse(day.session.date),
                storeId = day.session.storeId,
                startTime = day.session.startTime,
                endTime = day.session.endTime,
                storeRevenue = 0.0, // Electronic-only report, no fabricated cash value.
                hourly = actual,
                payments = byStoreDay[day.session.storeId to day.session.date].orEmpty(),
                hasActualArchive = day.archivePresent
            )
        }

        val actualAvailable = reports.sumOf { it.actualWeatherHours }
        val expected = reports.sumOf { it.expectedHours }
        val observedDays = reports.count { it.actualWeatherHours > 0 }
        val targetDates = selectedDays.map { it.session.date }.toSet()
        val targetWeekdays = selectedDays.map {
            LocalDate.parse(it.session.date).dayOfWeek.value
        }.toSet()
        val targetHoursByWeekday = selectedDays.groupBy {
            LocalDate.parse(it.session.date).dayOfWeek.value
        }.mapValues { (_, dates) ->
            dates.flatMap { CustomerTrafficDeepDive.finishedHours(it.session, now) }.toSet()
        }
        val targetSources = selectedDays.groupBy {
            LocalDate.parse(it.session.date).dayOfWeek.value
        }.mapValues { (_, days) ->
            days.map { day ->
                byStoreDay[day.session.storeId to day.session.date].orEmpty()
                    .map { it.platform }.toSet()
            }.toSet()
        }
        val fullChannelCoverage = selectedDays.isNotEmpty() && selectedDays.all { day ->
            val channels = byStoreDay[day.session.storeId to day.session.date].orEmpty()
                .map { it.platform }.toSet()
            if (platform == "ALL") channels.containsAll(setOf("WECHAT", "ALIPAY"))
            else platform in channels
        }

        data class Sample(
            val date: String, val weekday: Int, val hour: Int,
            val raining: Boolean, val count: Int, val amount: Double
        )
        val samples = historical.asSequence().filter { day ->
            day.session.storeId in storeIds &&
                day.session.date !in targetDates &&
                selectedDays.isNotEmpty() &&
                day.session.date < selectedDays.first().session.date &&
                LocalDate.parse(day.session.date).dayOfWeek.value in targetWeekdays &&
                day.archivePresent
        }.flatMap { day ->
            val weekday = LocalDate.parse(day.session.date).dayOfWeek.value
            val hours = targetHoursByWeekday[weekday].orEmpty()
                .intersect(CustomerTrafficDeepDive.finishedHours(day.session, now).toSet())
            val dayReceipts = byStoreDay[day.session.storeId to day.session.date].orEmpty()
            val channels = dayReceipts.map { it.platform }.toSet()
            val signatures = targetSources[weekday].orEmpty()
            // Compare exactly the same imported channels, never WECHAT-only
            // against WECHAT+ALIPAY combined.
            if (hours.isEmpty() || dayReceipts.isEmpty() ||
                channels !in signatures || (platform == "ALL" &&
                    !channels.containsAll(setOf("WECHAT", "ALIPAY")))) {
                emptySequence()
            } else {
                val payments = dayReceipts.groupBy { timestamp(it.tradeTime)?.hour }
                day.hours.asSequence().filter { hour ->
                    hour.actualAvailable && timestamp(hour.time)?.hour in hours
                }.distinctBy { it.time.take(16) }.map { hour ->
                    val h = timestamp(hour.time)!!.hour
                    val grouped = payments[h].orEmpty()
                    Sample(day.session.date, weekday, h, wet(hour),
                        grouped.size, grouped.sumOf { it.netAmount })
                }
            }
        }.toList()

        // Match identical weekday + clock-hour buckets. A 16:00 dry hour must
        // never be compared directly against a 22:00 rainy hour.
        val paired = samples.groupBy { it.weekday to it.hour }.values.mapNotNull { group ->
            val wetRows = group.filter { it.raining }
            val dryRows = group.filterNot { it.raining }
            if (wetRows.isEmpty() || dryRows.isEmpty()) null
            else wetRows to dryRows
        }
        val rainyDays = paired.flatMap { it.first }.map { it.date }.toSet().size
        val dryDays = paired.flatMap { it.second }.map { it.date }.toSet().size
        val reliable = fullChannelCoverage && paired.isNotEmpty() &&
            rainyDays >= 3 && dryDays >= 3
        val history = CustomerWeatherImpactHistory(
            rainyDays = rainyDays,
            dryDays = dryDays,
            comparableSlots = paired.size,
            rainyPaymentsPerHour = paired.takeIf { it.isNotEmpty() }
                ?.map { (wetRows, _) -> wetRows.map { it.count }.average() }?.average(),
            dryPaymentsPerHour = paired.takeIf { it.isNotEmpty() }
                ?.map { (_, dryRows) -> dryRows.map { it.count }.average() }?.average(),
            rainyRevenuePerHour = paired.takeIf { it.isNotEmpty() }
                ?.map { (wetRows, _) -> wetRows.map { it.amount }.average() }?.average(),
            dryRevenuePerHour = paired.takeIf { it.isNotEmpty() }
                ?.map { (_, dryRows) -> dryRows.map { it.amount }.average() }?.average(),
            reliable = reliable
        )

        // Show a single selected business day's hourly observations in real
        // chronological order, including any next-day midnight shift hours.
        val hourly = if (selectedDays.size == 1) {
            val day = selectedDays.single()
            val complete = CustomerTrafficDeepDive.finishedHours(day.session, now).toSet()
            val dayReceipts = byStoreDay[day.session.storeId to day.session.date].orEmpty()
            val receiptsByHour = dayReceipts.groupBy {
                timestamp(it.tradeTime)?.truncatedTo(ChronoUnit.HOURS)
            }
            day.hours.asSequence().filter {
                it.actualAvailable && timestamp(it.time)?.let { time ->
                    time.hour in complete && !time.plusHours(1).isAfter(now)
                } == true
            }.distinctBy { it.time.take(16) }.sortedBy { it.time }.map { hour ->
                val time = timestamp(hour.time)!!
                val payments = receiptsByHour[time.truncatedTo(ChronoUnit.HOURS)].orEmpty()
                CustomerWeatherImpactHour(
                    hour = time.hour, rainy = wet(hour),
                    payments = payments.size, electronicRevenue = payments.sumOf { it.netAmount },
                    condition = hour.text
                )
            }.toList()
        } else emptyList()

        val warnings = buildList {
            if (selectedDays.isEmpty()) add("所选范围没有该位置的正式营业记录。")
            if (selectedDays.isNotEmpty() && observedDays == 0)
                add("没有该位置已归档的逐小时实际天气，不能用天气预报代替实况。")
            else if (expected > actualAvailable)
                add("天气实况仅覆盖 ${actualAvailable}/${expected} 个营业小时，未覆盖时段不计入分析。")
            if (selectedDays.isNotEmpty() && !fullChannelCoverage)
                add(if (platform == "ALL")
                    "部分营业日微信或支付宝账单缺失，合并数据不完整；暂不计算历史差异百分比。"
                    else "部分营业日没有所选渠道的收款记录，无法确认是否完整导入账单。")
            if (sharedPaymentCount > 0)
                add("所选范围有 ${sharedPaymentCount} 笔多位置共享收款，无法准确拆分到该位置，已排除。")
            if (reports.any { report -> report.missing.any {
                    it.contains("缺少有效交易时间") || it.contains("没有落在已归档")
                } }) add("部分交易时间不完整或不在实况覆盖小时，未参与逐小时统计。")
            if (!history.reliable)
                add("同位置、同星期、相同小时与相同渠道的历史雨/无雨样本不足（两组各至少3个不同营业日）；不展示历史变化率。")
            add("仅统计已归属该位置的电子付款笔数与金额，不含现金；付款笔数≠独立顾客数。")
            add("降雨与收款同期变化不代表因果关系，还可能受价格、品类及工厂排班影响。")
        }

        return CustomerWeatherImpactSummary(
            sessionCount = selectedDays.size,
            archiveCount = selectedDays.count { it.archivePresent },
            expectedHours = expected,
            observedHours = actualAvailable,
            before = total("雨前", reports.map { it.before }),
            rainy = total("雨中", reports.map { it.rainy }),
            after = total("雨后", reports.map { it.after }),
            dry = total("无雨", reports.map { it.dry }),
            hourly = hourly,
            history = history,
            warnings = warnings.distinct()
        )
    }
}
