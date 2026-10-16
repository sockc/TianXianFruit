package com.tianxian.fruit.data

/**
 * Read-only aggregate of explicitly saved business-form new/returning counts.
 * It is not inferred from anonymous bill receipts or claimed as an independent
 * WeChat official measurement. Date and location follow the customer's filters,
 * while payer/collector/platform filters do not apply to business-form counts.
 */
internal data class BusinessCustomerRecordSummary(
    val newCustomers: Long,
    val returningCustomers: Long,
    val recordCount: Int,
    val operatingDays: Int,
    val storeCount: Int
) {
    val total: Long get() = newCustomers + returningCustomers
    val available: Boolean get() = recordCount > 0
}

internal object BusinessCustomerRecordSummaryEngine {
    fun summarize(
        records: List<StoreDailyRecord>,
        startDate: String?,
        endDate: String?,
        storeId: Long = 0L
    ): BusinessCustomerRecordSummary {
        // Store -1 is the analysis-only "unmatched bills" filter; official
        // business records never belong to that virtual location.
        val included = if (storeId < 0L) emptyList() else records.filter {
            it.storeId > 0L &&
                (storeId == 0L || it.storeId == storeId) &&
                (startDate == null || it.date >= startDate) &&
                (endDate == null || it.date <= endDate)
        }
        return BusinessCustomerRecordSummary(
            newCustomers = included.sumOf { it.newCustomer.coerceAtLeast(0).toLong() },
            returningCustomers = included.sumOf { it.oldCustomer.coerceAtLeast(0).toLong() },
            recordCount = included.size,
            operatingDays = included.map { it.date }.distinct().size,
            storeCount = included.map { it.storeId }.distinct().size
        )
    }
}
