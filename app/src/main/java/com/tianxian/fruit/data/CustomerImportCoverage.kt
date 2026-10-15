package com.tianxian.fruit.data

import java.time.LocalDate
import java.time.YearMonth

/**
 * Coverage means positive business receipt rows actually observed on this device.
 * It does NOT certify that an export included all transactions on intervening days.
 * Use transaction timestamp dates (rather than reassigned selling dates) for
 * imported bill-file coverage.
 */
data class CustomerReceiptPlatformCoverage(
    val platform: String,
    val firstDate: String?,
    val lastDate: String?,
    val observedDays: Int,
    val payments: Int,
    val amount: Double
)

data class CustomerReceiptCoverageDay(
    val date: String,
    val wechatPayments: Int,
    val alipayPayments: Int,
    val hasSavedBusiness: Boolean
)

internal object CustomerImportCoverageEngine {
    private fun tradeDate(record: CustomerPaymentRecord): String? {
        val text = record.tradeTime.take(10)
        return runCatching { LocalDate.parse(text).toString() }.getOrNull()
    }

    private fun observed(rows: List<CustomerPaymentRecord>): List<Pair<CustomerPaymentRecord, String>> =
        rows.mapNotNull { record ->
            if (record.amount <= 0.005) return@mapNotNull null
            tradeDate(record)?.let { record to it }
        }

    fun overview(rows: List<CustomerPaymentRecord>): List<CustomerReceiptPlatformCoverage> {
        val receipts = observed(rows)
        return listOf("WECHAT", "ALIPAY").map { platform ->
            val platformRows = receipts.filter { it.first.platform == platform }
            val dates = platformRows.map { it.second }.distinct().sorted()
            CustomerReceiptPlatformCoverage(
                platform = platform,
                firstDate = dates.firstOrNull(),
                lastDate = dates.lastOrNull(),
                observedDays = dates.size,
                payments = platformRows.size,
                amount = platformRows.sumOf { it.first.netAmount }
            )
        }
    }

    /** Shows no-record days as unknown, not as certified missing or zero sales. */
    fun days(
        rows: List<CustomerPaymentRecord>,
        sessions: List<CustomerTrafficSession>,
        month: YearMonth
    ): List<CustomerReceiptCoverageDay> {
        val start = month.atDay(1)
        val end = month.atEndOfMonth()
        val counted = observed(rows)
            .asSequence()
            .filter { (_, date) -> date >= start.toString() && date <= end.toString() }
            .groupingBy { (receipt, date) -> date to receipt.platform }
            .eachCount()
        val sessionDays = sessions
            .asSequence()
            .filter { it.storeId > 0L && it.date >= start.toString() && it.date <= end.toString() }
            .map { it.date }
            .toSet()

        return (1..month.lengthOfMonth()).map { day ->
            val date = month.atDay(day).toString()
            CustomerReceiptCoverageDay(
                date = date,
                wechatPayments = counted[date to "WECHAT"] ?: 0,
                alipayPayments = counted[date to "ALIPAY"] ?: 0,
                hasSavedBusiness = date in sessionDays
            )
        }
    }

    fun importRange(rows: List<ParsedBusinessReceipt>): Triple<String, String, Int> {
        val dates = rows.asSequence().filter { it.amount > 0.005 }
            .mapNotNull { record -> runCatching {
                LocalDate.parse(record.tradeTime.take(10)).toString()
            }.getOrNull() }
            .distinct().sorted().toList()
        return Triple(dates.firstOrNull().orEmpty(), dates.lastOrNull().orEmpty(), dates.size)
    }
}
