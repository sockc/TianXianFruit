package com.tianxian.fruit.data

/**
 * 从已经按日期计算好的完整资金账本生成每日查询索引。
 *
 * 明细的日期过滤发生在生成此索引之后：不能因为页面只显示最近7天，
 * 就把更早未结亏损、垫资、结清断点从余额中剔除。
 */
internal object FundBalanceTimeline {
    fun indexedBalances(
        fullLedger: List<Pair<String, Double>>,
        isSettlementCenter: Boolean,
        counterparts: List<Map<String, Double>> = emptyList()
    ): Map<String, Double> {
        val result = LinkedHashMap<String, Double>(fullLedger.size)
        for ((date, accumulatedBalance) in fullLedger) {
            result[date] = if (isSettlementCenter) {
                FundBalancePolicy.centerBalance(
                    counterparts.map { it[date] ?: 0.0 }
                )
            } else {
                accumulatedBalance
            }
        }
        return result
    }
}
