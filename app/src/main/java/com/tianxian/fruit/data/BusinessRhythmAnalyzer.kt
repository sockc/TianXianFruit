package com.tianxian.fruit.data

import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * 经营节奏诊断 V1。只使用同一位置的已营业日期，不把“连续经营”当作销量下降的因果证据。
 * 不写入数据库：每天根据已经同步的原始经营记录重算，避免分析快照引起同步冲突。
 */
data class RhythmBusinessDay(
    val date: LocalDate,
    val revenue: Double,
    val customers: Int = 0,
    val newCustomers: Int = 0,
    val oldCustomers: Int = 0
)

data class BusinessRhythmReport(
    val date: LocalDate,
    val hasActual: Boolean,
    val consecutiveDays: Int,
    val comparableDays: Int,
    val revenue: Double?,
    val weekdayAverage: Double?,
    val revenueChangePercent: Double?,
    val customers: Int?,
    val averageTicket: Double?,
    val customerChangePercent: Double?,
    val ticketChangePercent: Double?,
    val newCustomerShare: Double?,
    val oldCustomerChangePercent: Double?,
    val evidence: String,
    val headline: String,
    val reasons: List<String>,
    val recommendation: String
)

object BusinessRhythmAnalyzer {
    private const val MIN_COMPARABLE = 3

    fun evaluate(
        targetDate: LocalDate,
        history: List<RhythmBusinessDay>
    ): BusinessRhythmReport {
        val byDate = history.asSequence()
            .filter { it.revenue > 0.0 && it.date <= targetDate }
            .groupBy { it.date }
            .mapValues { (_, rows) -> rows.maxByOrNull { it.revenue }!! }
        val actual = byDate[targetDate]
        val consecutiveDays = generateSequence(targetDate) { it.minusDays(1) }
            .takeWhile { byDate.containsKey(it) }
            .count()
            .let { count ->
                if (actual != null) count else {
                    1 + generateSequence(targetDate.minusDays(1)) { it.minusDays(1) }
                        .takeWhile { byDate.containsKey(it) }
                        .count()
                }
            }
        val comparable = byDate.values
            .asSequence()
            .filter { it.date < targetDate && it.date.dayOfWeek == targetDate.dayOfWeek }
            .sortedByDescending { it.date }
            .take(12)
            .toList()
        val reliableComparable = comparable.size >= MIN_COMPARABLE
        val average = comparable.takeIf { reliableComparable }?.map { it.revenue }?.average()
        val revenueChange = changePercent(actual?.revenue, average)

        // 缺失/未分配的交易不能被视为新客户；只有真实填写的顾客数才可算客单价。
        val customerHistory = comparable.filter { it.customers > 0 }
        val customers = actual?.customers?.takeIf { it > 0 }
        val baselineCustomers = customerHistory
            .takeIf { it.size >= MIN_COMPARABLE }?.map { it.customers.toDouble() }?.average()
        val customerChange = changePercent(customers?.toDouble(), baselineCustomers)
        val ticket = if (actual != null && customers != null) actual.revenue / customers else null
        val baselineTicket = customerHistory
            .takeIf { it.size >= MIN_COMPARABLE }
            ?.map { it.revenue / it.customers }?.average()
        val ticketChange = changePercent(ticket, baselineTicket)

        val hasClassifiedCustomers = actual != null &&
            actual.customers > 0 &&
            actual.newCustomers >= 0 &&
            actual.oldCustomers >= 0 &&
            actual.newCustomers + actual.oldCustomers == actual.customers
        val newShare = if (hasClassifiedCustomers) {
            actual!!.newCustomers.toDouble() / actual.customers
        } else null
        val oldHistory = comparable.filter {
            it.customers > 0 && it.newCustomers + it.oldCustomers == it.customers
        }
        val baselineOld = oldHistory
            .takeIf { it.size >= MIN_COMPARABLE }
            ?.map { it.oldCustomers.toDouble() }?.average()
        val oldChange = changePercent(
            actual?.oldCustomers?.takeIf { hasClassifiedCustomers }?.toDouble(),
            baselineOld
        )

        val reasons = mutableListOf<String>()
        val hasDrop = revenueChange != null && revenueChange <= -20.0
        val oldDrop = oldChange != null && oldChange <= -20.0

        if (actual == null) {
            if (consecutiveDays >= 3) {
                reasons += "若今日继续在此位置经营，将连续第${consecutiveDays}天；先关注老客购买周期。"
            } else {
                reasons += "按历史营业记录推算，今日是连续经营第${consecutiveDays}天。"
            }
            if (!reliableComparable) {
                reasons += "历史同星期样本仅${comparable.size}天，不足以估算今日营业额。"
            }
        } else {
            if (reliableComparable) {
                val sign = if ((revenueChange ?: 0.0) > 0) "+" else ""
                reasons += "营业额较本位置历史同星期${comparable.size}天均值${sign}${revenueChange!!.roundToInt()}%。"
            } else {
                reasons += "历史同星期只有${comparable.size}天，暂不判断营业额是否异常。"
            }
            if (customers == null) {
                reasons += "本位置当天缺少客户人数，无法可靠判断客流和客单价。"
            } else {
                if (customerChange != null && customerChange <= -15.0) {
                    reasons += "客户人数较历史同星期下降${-customerChange.roundToInt()}%。"
                }
                if (ticketChange != null && ticketChange <= -15.0) {
                    reasons += "客单价较历史同星期下降${-ticketChange.roundToInt()}%。"
                }
                if (newShare != null && newShare >= 0.65) {
                    reasons += "记录的新客占比${(newShare * 100).roundToInt()}%；须结合老客绝对人数，不可单凭比例判断老客流失。"
                }
            }
        }

        val possibleSaturation = actual != null && consecutiveDays >= 3 && hasDrop && oldDrop
        val headline = when {
            possibleSaturation -> "疑似老客需求提前释放"
            actual == null && consecutiveDays >= 3 -> "连续经营风险待观察"
            actual == null -> "今日经营节奏提示"
            hasDrop -> "营业额低于历史同星期"
            else -> "暂未发现明确的节奏异常"
        }
        if (possibleSaturation) {
            reasons += "连续经营${consecutiveDays}天且老客绝对人数、营业额均回落，疑似需求重叠；也可能受天气、调休或货品影响。"
        } else if (actual != null && consecutiveDays >= 3 && hasDrop) {
            reasons += "连续经营${consecutiveDays}天出现营业额回落，但老客下降证据不足，不能直接归因于需求饱和。"
        }
        if (actual != null && oldChange == null) {
            reasons += "老客可比数据不足，暂不能验证需求提前释放。"
        }
        val evidence = when {
            !reliableComparable -> "INSUFFICIENT"
            actual != null && customers != null && customerHistory.size >= MIN_COMPARABLE -> "MODERATE"
            else -> "PARTIAL"
        }
        val recommendation = when {
            possibleSaturation -> "建议观察隔日与连续经营在相同周期内的利润、客户数和损耗，暂不直接判定应停摊。"
            actual == null && consecutiveDays >= 3 -> "可保守备货，重点关注老客购买间隔；结合天气、工厂排班与库存再决定是否营业。"
            hasDrop -> "优先核对天气、工厂排班、商品结构和购买人数；继续收集相同星期样本。"
            !reliableComparable -> "同星期历史样本不足，先记录营业结果，不建议依据此指标调整频率。"
            else -> "维持观察。建议按同一位置、相同星期比较营业额与客单价。"
        }

        return BusinessRhythmReport(
            date = targetDate,
            hasActual = actual != null,
            consecutiveDays = consecutiveDays,
            comparableDays = comparable.size,
            revenue = actual?.revenue,
            weekdayAverage = average,
            revenueChangePercent = revenueChange,
            customers = customers,
            averageTicket = ticket,
            customerChangePercent = customerChange,
            ticketChangePercent = ticketChange,
            newCustomerShare = newShare,
            oldCustomerChangePercent = oldChange,
            evidence = evidence,
            headline = headline,
            reasons = reasons.distinct(),
            recommendation = recommendation
        )
    }

    private fun changePercent(actual: Double?, average: Double?): Double? =
        if (actual != null && average != null && average > 0.0) {
            (actual / average - 1.0) * 100.0
        } else null
}
