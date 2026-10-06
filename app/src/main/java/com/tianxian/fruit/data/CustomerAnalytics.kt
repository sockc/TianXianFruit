package com.tianxian.fruit.data

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class CustomerPaymentRecord(
    val id: Long,
    val platform: String,
    val tradeTime: String,
    val businessDate: String,
    val amount: Double,
    val refundAmount: Double,
    val netAmount: Double,
    val customerKey: String,
    val customerName: String,
    val identityConfidence: String,
    val transactionType: String,
    val tradeStatus: String,
    val storeId: Long,
    val storeName: String,
    val transactionRef: String = "",
    val analysisExcluded: Boolean = false,
    val analysisIncludedOverride: Boolean = false,
    val exclusionReason: String = ""
)

data class CustomerAnalysisSettings(
    val excludeLargePayments: Boolean = true,
    val largePaymentThreshold: Double = 2000.0
)

data class CustomerPaymentFilterResult(
    val included: List<CustomerPaymentRecord>,
    val excludedIds: Set<Long>,
    val autoLargeIds: Set<Long>
)

data class PaymentImportBatchRecord(
    val id: Long,
    val platform: String,
    val fileName: String,
    val fileHash: String,
    val totalRows: Int,
    val acceptedRows: Int,
    val duplicateRows: Int,
    val ignoredRows: Int,
    val refundRows: Int,
    val errorRows: Int,
    val importedAt: Long
)

data class PaymentImportOutcome(
    val batchId: Long,
    val platform: String,
    val fileName: String,
    val totalRows: Int,
    val acceptedRows: Int,
    val insertedRows: Int,
    val duplicateRows: Int,
    val ignoredRows: Int,
    val refundRows: Int,
    val errorRows: Int,
    val unmatchedStoreRows: Int,
    val duplicateFile: Boolean,
    val purchaseCandidateRows: Int = 0,
    val purchaseCandidateInsertedRows: Int = 0,
    val purchaseCandidateDuplicateRows: Int = 0
)

data class CustomerAnalysisSummary(
    val netRevenue: Double,
    val paymentCount: Int,
    val customerCount: Int,
    val identifiedCustomerCount: Int,
    val newCustomerCount: Int,
    val oldCustomerCount: Int,
    val repeatCustomerCount: Int,
    val repeatRate: Double,
    val averageTicket: Double,
    val averageCustomerValue: Double,
    val unmatchedStoreCount: Int,
    val highConfidenceCount: Int,
    val mediumConfidenceCount: Int,
    val lowConfidenceCount: Int
)

data class CustomerProfileAnalysis(
    val customerKey: String,
    val customerName: String,
    val platformLabel: String,
    val identityConfidence: String,
    val periodAmount: Double,
    val periodPayments: Int,
    val periodVisits: Int,
    val lifetimeAmount: Double,
    val lifetimePayments: Int,
    val lifetimeVisits: Int,
    val firstDate: String,
    val lastDate: String,
    val daysSinceLast: Long,
    val averageTicket: Double,
    val averageIntervalDays: Double?,
    val commonStore: String,
    val lifecycle: String,
    val stabilityScore: Int
)

data class CustomerStoreAnalysis(
    val storeName: String,
    val netRevenue: Double,
    val paymentCount: Int,
    val customerCount: Int,
    val repeatCustomerCount: Int,
    val repeatRate: Double,
    val averageTicket: Double
)

data class CustomerLifecycleCount(
    val label: String,
    val count: Int
)

data class CustomerChangeBreakdown(
    val available: Boolean,
    val currentRevenue: Double = 0.0,
    val previousRevenue: Double = 0.0,
    val revenueChange: Double = 0.0,
    val currentCustomers: Int = 0,
    val previousCustomers: Int = 0,
    val customerChange: Int = 0,
    val currentAverageCustomerValue: Double = 0.0,
    val previousAverageCustomerValue: Double = 0.0,
    val customerCountEffect: Double = 0.0,
    val customerValueEffect: Double = 0.0,
    val mainReason: String = ""
)

data class CustomerHourlyPoint(
    val hour: Int,
    val customerCount: Int,
    val newCustomerCount: Int,
    val oldCustomerCount: Int,
    val paymentCount: Int,
    val amount: Double
)

data class CustomerDailyTrafficPoint(
    val date: String,
    val customerCount: Int,
    val paymentCount: Int,
    val amount: Double
)

data class CustomerSameWeekdayComparison(
    val storeName: String,
    val currentCustomers: Int,
    val averageCustomers: Double,
    val sampleDays: Int,
    val difference: Int,
    val percentChange: Double?
)

data class CustomerAnalysisResult(
    val summary: CustomerAnalysisSummary,
    val profiles: List<CustomerProfileAnalysis>,
    val stores: List<CustomerStoreAnalysis>,
    val lifecycles: List<CustomerLifecycleCount>,
    val change: CustomerChangeBreakdown
)

internal object CustomerAnalyticsEngine {
    fun hourlyTraffic(
        allRecords: List<CustomerPaymentRecord>,
        date: String
    ): List<CustomerHourlyPoint> {
        val positiveAll =
            allRecords.filter {
                it.netAmount > 0.005
            }
        val firstDateByCustomer =
            positiveAll
                .groupBy {
                    it.customerKey
                }
                .mapValues {
                    entry ->
                    entry.value
                        .minOf {
                            it.businessDate
                        }
                }

        val dayPositive =
            positiveAll.filter {
                it.businessDate == date
            }
        val dayNet =
            allRecords.filter {
                it.businessDate == date
            }

        return (0..23).map {
            hour ->
            val positiveHour =
                dayPositive.filter {
                    parseHour(
                        it.tradeTime
                    ) == hour
                }
            val netHour =
                dayNet.filter {
                    parseHour(
                        it.tradeTime
                    ) == hour
                }
            val customers =
                positiveHour
                    .map {
                        it.customerKey
                    }
                    .toSet()
            val newCustomers =
                customers.count {
                    firstDateByCustomer[it] ==
                        date
                }

            CustomerHourlyPoint(
                hour = hour,
                customerCount =
                    customers.size,
                newCustomerCount =
                    newCustomers,
                oldCustomerCount =
                    (
                        customers.size -
                            newCustomers
                        ).coerceAtLeast(
                            0
                        ),
                paymentCount =
                    positiveHour.size,
                amount =
                    roundMoney(
                        netHour.sumOf {
                            it.netAmount
                        }
                    )
            )
        }
    }

    fun dailyTraffic(
        allRecords: List<CustomerPaymentRecord>,
        startDate: String,
        endDate: String
    ): List<CustomerDailyTrafficPoint> {
        val start =
            runCatching {
                LocalDate.parse(
                    startDate
                )
            }.getOrNull()
                ?: return emptyList()
        val end =
            runCatching {
                LocalDate.parse(
                    endDate
                )
            }.getOrNull()
                ?: return emptyList()
        if (end < start) {
            return emptyList()
        }

        val positive =
            allRecords.filter {
                it.netAmount > 0.005
            }
        val netByDate =
            allRecords
                .filter {
                    it.businessDate >=
                        startDate &&
                        it.businessDate <=
                            endDate
                }
                .groupBy {
                    it.businessDate
                }

        return generateSequence(
            start
        ) {
            previous ->
            previous.plusDays(
                1
            ).takeIf {
                it <= end
            }
        }
            .map {
                date ->
                val key =
                    date.toString()
                val dayPositive =
                    positive.filter {
                        it.businessDate ==
                            key
                    }
                CustomerDailyTrafficPoint(
                    date = key,
                    customerCount =
                        dayPositive
                            .map {
                                it.customerKey
                            }
                            .toSet()
                            .size,
                    paymentCount =
                        dayPositive.size,
                    amount =
                        roundMoney(
                            netByDate[key]
                                .orEmpty()
                                .sumOf {
                                    it.netAmount
                                }
                        )
                )
            }
            .toList()
    }

    fun sameWeekdayComparisons(
        allRecords: List<CustomerPaymentRecord>,
        date: String,
        previousLimit: Int = 4
    ): List<CustomerSameWeekdayComparison> {
        val target =
            runCatching {
                LocalDate.parse(
                    date
                )
            }.getOrNull()
                ?: return emptyList()

        val positive =
            allRecords.filter {
                it.netAmount > 0.005 &&
                    it.storeName.isNotBlank() &&
                    it.storeName !=
                        "未匹配位置"
            }
        val current =
            positive.filter {
                it.businessDate ==
                    date
            }
        val stores =
            current
                .map {
                    it.storeName
                }
                .distinct()

        return stores.mapNotNull {
            storeName ->
            val currentCustomers =
                current
                    .filter {
                        it.storeName ==
                            storeName
                    }
                    .map {
                        it.customerKey
                    }
                    .toSet()
                    .size

            val previous =
                positive
                    .asSequence()
                    .filter {
                        it.storeName ==
                            storeName &&
                            it.businessDate <
                                date
                    }
                    .groupBy {
                        it.businessDate
                    }
                    .mapNotNull {
                        (day, rows) ->
                        val parsed =
                            runCatching {
                                LocalDate.parse(
                                    day
                                )
                            }.getOrNull()
                                ?: return@mapNotNull null

                        if (
                            parsed.dayOfWeek !=
                            target.dayOfWeek
                        ) {
                            null
                        } else {
                            day to
                                rows
                                    .map {
                                        it.customerKey
                                    }
                                    .toSet()
                                    .size
                        }
                    }
                    .sortedByDescending {
                        it.first
                    }
                    .take(
                        previousLimit
                            .coerceAtLeast(
                                1
                            )
                    )

            if (previous.isEmpty()) {
                null
            } else {
                val average =
                    previous
                        .map {
                            it.second
                        }
                        .average()
                val difference =
                    currentCustomers -
                        average.roundToInt()
                val percent =
                    if (average > 0.005) {
                        (
                            currentCustomers -
                                average
                            ) /
                            average
                    } else {
                        null
                    }

                CustomerSameWeekdayComparison(
                    storeName =
                        storeName,
                    currentCustomers =
                        currentCustomers,
                    averageCustomers =
                        average,
                    sampleDays =
                        previous.size,
                    difference =
                        difference,
                    percentChange =
                        percent
                )
            }
        }
    }

    private fun parseHour(
        tradeTime: String
    ): Int? {
        if (
            tradeTime.length <
            13
        ) {
            return null
        }

        return tradeTime
            .substring(
                11,
                13
            )
            .toIntOrNull()
            ?.takeIf {
                it in 0..23
            }
    }

    fun filterRecords(
        allRecords: List<CustomerPaymentRecord>,
        settings: CustomerAnalysisSettings
    ): CustomerPaymentFilterResult {
        val autoLargeRootRefs =
            if (settings.excludeLargePayments) {
                allRecords
                    .filter {
                        !it.analysisIncludedOverride &&
                            it.amount > 0.005 &&
                            it.amount >= settings.largePaymentThreshold &&
                            it.transactionRef.isNotBlank()
                    }
                    .map {
                        it.platform + "|" + it.transactionRef
                    }
                    .toSet()
            } else {
                emptySet()
            }

        val manualRootRefs =
            allRecords
                .filter {
                    it.analysisExcluded &&
                        !it.analysisIncludedOverride &&
                        it.amount > 0.005 &&
                        it.transactionRef.isNotBlank()
                }
                .map {
                    it.platform + "|" + it.transactionRef
                }
                .toSet()

        val excludedRefs =
            autoLargeRootRefs + manualRootRefs

        val excludedIds =
            allRecords
                .filter { record ->
                    (
                        record.analysisExcluded &&
                            !record.analysisIncludedOverride
                        ) ||
                        (
                            !record.analysisIncludedOverride &&
                                record.transactionRef.isNotBlank() &&
                                (
                                    record.platform +
                                        "|" +
                                        record.transactionRef
                                    ) in excludedRefs
                            ) ||
                        (
                            !record.analysisIncludedOverride &&
                                settings.excludeLargePayments &&
                                record.amount > 0.005 &&
                                record.amount >= settings.largePaymentThreshold
                            )
                }
                .map { it.id }
                .toSet()

        val autoLargeIds =
            allRecords
                .filter { record ->
                    !record.analysisIncludedOverride &&
                        settings.excludeLargePayments &&
                        (
                            (
                                record.amount > 0.005 &&
                                    record.amount >= settings.largePaymentThreshold
                                ) ||
                                (
                                    record.transactionRef.isNotBlank() &&
                                        (
                                            record.platform +
                                                "|" +
                                                record.transactionRef
                                            ) in autoLargeRootRefs
                                    )
                            )
                }
                .map { it.id }
                .toSet()

        return CustomerPaymentFilterResult(
            included =
                allRecords.filterNot {
                    it.id in excludedIds
                },
            excludedIds = excludedIds,
            autoLargeIds = autoLargeIds
        )
    }

    fun analyze(
        allRecords: List<CustomerPaymentRecord>,
        startDate: String?,
        endDate: String?,
        today: LocalDate = LocalDate.now()
    ): CustomerAnalysisResult {
        val positiveAll =
            allRecords
                .filter {
                    it.netAmount >
                        0.005
                }

        val filtered =
            allRecords
                .filter {
                    record ->
                    (
                        startDate == null ||
                            record.businessDate >=
                            startDate
                        ) &&
                        (
                            endDate == null ||
                                record.businessDate <=
                                endDate
                            )
                }

        val positive =
            filtered
                .filter {
                    it.netAmount >
                        0.005
                }

        val allByCustomer =
            allRecords.groupBy {
                it.customerKey
            }

        val positiveAllByCustomer =
            positiveAll.groupBy {
                it.customerKey
            }

        val periodAllByCustomer =
            filtered.groupBy {
                it.customerKey
            }

        val periodByCustomer =
            positive.groupBy {
                it.customerKey
            }

        val firstDateByCustomer =
            positiveAllByCustomer.mapValues {
                (_, records) ->
                records.minOf {
                    it.businessDate
                }
            }

        val periodCustomers =
            periodByCustomer.keys

        val newCustomers =
            if (
                startDate ==
                null
            ) {
                emptySet()
            } else {
                periodCustomers
                    .filter {
                        firstDateByCustomer[it]
                            ?.let {
                                first ->
                                first >=
                                    startDate
                            } ==
                            true
                    }
                    .toSet()
            }

        val oldCustomers =
            periodCustomers -
                newCustomers

        val repeatCustomers =
            periodCustomers
                .filter {
                    key ->
                    positiveAllByCustomer[
                        key
                    ]
                        .orEmpty()
                        .map {
                            it.businessDate
                        }
                        .distinct()
                        .size >=
                        2
                }
                .toSet()

        val netRevenue =
            roundMoney(
                filtered.sumOf {
                    it.netAmount
                }
            )

        val paymentCount =
            positive.size

        val averageTicket =
            if (
                paymentCount >
                0
            ) {
                roundMoney(
                    netRevenue /
                        paymentCount
                )
            } else {
                0.0
            }

        val customerCount =
            periodCustomers.size

        val averageCustomerValue =
            if (
                customerCount >
                0
            ) {
                roundMoney(
                    netRevenue /
                        customerCount
                )
            } else {
                0.0
            }

        val confidenceByCustomer =
            periodByCustomer.mapValues {
                (_, records) ->
                when {
                    records.any {
                        it.identityConfidence ==
                            "HIGH"
                    } ->
                        "HIGH"

                    records.any {
                        it.identityConfidence ==
                            "MEDIUM"
                    } ->
                        "MEDIUM"

                    else ->
                        "LOW"
                }
            }

        val amount90ByCustomer =
            allRecords
                .filter {
                    runCatching {
                        LocalDate.parse(
                            it.businessDate
                        )
                    }.getOrNull()
                        ?.let {
                            date ->
                            !date.isBefore(
                                today.minusDays(
                                    89
                                )
                            )
                        } ==
                        true
                }
                .groupBy {
                    it.customerKey
                }
                .mapValues {
                    (_, records) ->
                    records.sumOf {
                        it.netAmount
                    }
                }

        val highValueThreshold =
            percentile80(
                amount90ByCustomer
                    .values
                    .filter {
                        it >
                            0.005
                    }
            )

        val profiles =
            periodCustomers
                .map {
                    key ->
                    buildProfile(
                        customerKey = key,
                        periodRecords =
                            periodAllByCustomer[
                                key
                            ].orEmpty(),
                        lifetimeRecords =
                            allByCustomer[
                                key
                            ].orEmpty(),
                        today = today,
                        highValueThreshold =
                            highValueThreshold,
                        amount90 =
                            amount90ByCustomer[
                                key
                            ] ?: 0.0
                    )
                }
                .sortedWith(
                    compareByDescending<
                        CustomerProfileAnalysis
                    > {
                        it.periodAmount
                    }
                        .thenByDescending {
                            it.periodVisits
                        }
                )

        val stores =
            filtered
                .groupBy {
                    it.storeName
                        .ifBlank {
                            "未匹配位置"
                        }
                }
                .mapNotNull {
                    (
                        storeName,
                        records
                    ) ->
                    val positiveRecords =
                        records.filter {
                            it.netAmount >
                                0.005
                        }

                    if (
                        positiveRecords.isEmpty()
                    ) {
                        return@mapNotNull null
                    }

                    val keys =
                        positiveRecords.map {
                            it.customerKey
                        }
                            .toSet()

                    val repeat =
                        keys.count {
                            key ->
                            positiveRecords
                                .filter {
                                    it.customerKey ==
                                        key
                                }
                                .map {
                                    it.businessDate
                                }
                                .distinct()
                                .size >=
                                2
                        }

                    val amount =
                        roundMoney(
                            records.sumOf {
                                it.netAmount
                            }
                        )

                    CustomerStoreAnalysis(
                        storeName =
                            storeName,
                        netRevenue =
                            amount,
                        paymentCount =
                            positiveRecords.size,
                        customerCount =
                            keys.size,
                        repeatCustomerCount =
                            repeat,
                        repeatRate =
                            if (
                                keys.isNotEmpty()
                            ) {
                                repeat.toDouble() /
                                    keys.size
                            } else {
                                0.0
                            },
                        averageTicket =
                            if (
                                positiveRecords.isNotEmpty()
                            ) {
                                roundMoney(
                                    amount /
                                        positiveRecords.size
                                )
                            } else {
                                0.0
                            }
                    )
                }
                .sortedByDescending {
                    it.netRevenue
                }

        val lifecycles =
            LIFECYCLE_ORDER
                .map {
                    label ->
                    CustomerLifecycleCount(
                        label =
                            label,
                        count =
                            profiles.count {
                                it.lifecycle ==
                                    label
                            }
                    )
                }

        val summary =
            CustomerAnalysisSummary(
                netRevenue =
                    netRevenue,
                paymentCount =
                    paymentCount,
                customerCount =
                    customerCount,
                identifiedCustomerCount =
                    confidenceByCustomer.count {
                        it.value !=
                            "LOW"
                    },
                newCustomerCount =
                    newCustomers.size,
                oldCustomerCount =
                    oldCustomers.size,
                repeatCustomerCount =
                    repeatCustomers.size,
                repeatRate =
                    if (
                        customerCount >
                        0
                    ) {
                        repeatCustomers.size
                            .toDouble() /
                            customerCount
                    } else {
                        0.0
                    },
                averageTicket =
                    averageTicket,
                averageCustomerValue =
                    averageCustomerValue,
                unmatchedStoreCount =
                    positive.count {
                        it.storeName
                            .isBlank()
                    },
                highConfidenceCount =
                    confidenceByCustomer.count {
                        it.value ==
                            "HIGH"
                    },
                mediumConfidenceCount =
                    confidenceByCustomer.count {
                        it.value ==
                            "MEDIUM"
                    },
                lowConfidenceCount =
                    confidenceByCustomer.count {
                        it.value ==
                            "LOW"
                    }
            )

        return CustomerAnalysisResult(
            summary = summary,
            profiles = profiles,
            stores = stores,
            lifecycles = lifecycles,
            change =
                buildChange(
                    allRecords =
                        allRecords,
                    startDate =
                        startDate,
                    endDate =
                        endDate
                )
        )
    }

    private fun buildProfile(
        customerKey: String,
        periodRecords: List<CustomerPaymentRecord>,
        lifetimeRecords: List<CustomerPaymentRecord>,
        today: LocalDate,
        highValueThreshold: Double,
        amount90: Double
    ): CustomerProfileAnalysis {
        val lifetimeSorted =
            lifetimeRecords.sortedBy {
                it.tradeTime
            }

        val lifetimePositive =
            lifetimeSorted.filter {
                it.netAmount >
                    0.005
            }

        val periodPositive =
            periodRecords.filter {
                it.netAmount >
                    0.005
            }

        val dates =
            lifetimePositive
                .map {
                    it.businessDate
                }
                .distinct()
                .sorted()

        val firstDate =
            dates.firstOrNull()
                .orEmpty()

        val lastDate =
            dates.lastOrNull()
                .orEmpty()

        val daysSinceLast =
            runCatching {
                ChronoUnit.DAYS
                    .between(
                        LocalDate.parse(
                            lastDate
                        ),
                        today
                    )
            }
                .getOrDefault(
                    0L
                )
                .coerceAtLeast(
                    0L
                )

        val intervals =
            dates.zipWithNext {
                left,
                right ->
                ChronoUnit.DAYS
                    .between(
                        LocalDate.parse(
                            left
                        ),
                        LocalDate.parse(
                            right
                        )
                    )
                    .toDouble()
            }
                .filter {
                    it >
                        0.0
                }

        val averageInterval =
            intervals
                .takeIf {
                    it.isNotEmpty()
                }
                ?.average()

        val visits30 =
            lifetimePositive
                .filter {
                    runCatching {
                        LocalDate.parse(
                            it.businessDate
                        )
                    }.getOrNull()
                        ?.let {
                            date ->
                            !date.isBefore(
                                today.minusDays(
                                    29
                                )
                            )
                        } ==
                        true
                }
                .map {
                    it.businessDate
                }
                .distinct()
                .size

        val lifecycle =
            when {
                firstDate.isNotBlank() &&
                    runCatching {
                        LocalDate.parse(
                            firstDate
                        )
                    }.getOrNull()
                        ?.let {
                            !it.isBefore(
                                today.minusDays(
                                    7
                                )
                            )
                        } ==
                        true &&
                    dates.size <=
                    1 ->
                    "新客"

                daysSinceLast >=
                    45 &&
                    dates.size >=
                    3 ->
                    "沉睡客"

                dates.size >=
                    3 &&
                    averageInterval !=
                    null &&
                    daysSinceLast >
                    maxOf(
                        14.0,
                        averageInterval *
                            2.0
                    ) ->
                    "可能流失"

                visits30 >=
                    8 ->
                    "高频客"

                highValueThreshold >
                    0.005 &&
                    amount90 >=
                    highValueThreshold &&
                    dates.size >=
                    2 ->
                    "高价值客"

                dates.size >=
                    2 &&
                    daysSinceLast <=
                    30 ->
                    "活跃老客"

                dates.size >=
                    2 ->
                    "普通老客"

                dates.size <=
                    1 ->
                    "一次客"

                else ->
                    "一次客"
            }

        val commonStore =
            lifetimePositive
                .filter {
                    it.storeName
                        .isNotBlank()
                }
                .groupingBy {
                    it.storeName
                }
                .eachCount()
                .maxByOrNull {
                    it.value
                }
                ?.key
                .orEmpty()

        val lifetimeAmount =
            roundMoney(
                lifetimeSorted.sumOf {
                    it.netAmount
                }
            )

        val periodAmount =
            roundMoney(
                periodRecords.sumOf {
                    it.netAmount
                }
            )

        val displayName =
            lifetimeSorted
                .lastOrNull {
                    it.customerName
                        .isNotBlank()
                }
                ?.customerName
                ?: "匿名客户"

        val confidence =
            when {
                lifetimeSorted.any {
                    it.identityConfidence ==
                        "HIGH"
                } ->
                    "HIGH"

                lifetimeSorted.any {
                    it.identityConfidence ==
                        "MEDIUM"
                } ->
                    "MEDIUM"

                else ->
                    "LOW"
            }

        return CustomerProfileAnalysis(
            customerKey =
                customerKey,
            customerName =
                displayName,
            platformLabel =
                lifetimeSorted
                    .map {
                        when (
                            it.platform
                        ) {
                            "WECHAT" ->
                                "微信"

                            "ALIPAY" ->
                                "支付宝"

                            else ->
                                it.platform
                        }
                    }
                    .distinct()
                    .joinToString(
                        "+"
                    ),
            identityConfidence =
                confidence,
            periodAmount =
                periodAmount,
            periodPayments =
                periodPositive.size,
            periodVisits =
                periodPositive
                    .map {
                        it.businessDate
                    }
                    .distinct()
                    .size,
            lifetimeAmount =
                lifetimeAmount,
            lifetimePayments =
                lifetimePositive.size,
            lifetimeVisits =
                dates.size,
            firstDate =
                firstDate,
            lastDate =
                lastDate,
            daysSinceLast =
                daysSinceLast,
            averageTicket =
                if (
                    lifetimePositive.isNotEmpty()
                ) {
                    roundMoney(
                        lifetimeAmount /
                            lifetimePositive.size
                    )
                } else {
                    0.0
                },
            averageIntervalDays =
                averageInterval,
            commonStore =
                commonStore,
            lifecycle =
                lifecycle,
            stabilityScore =
                stabilityScore(
                    daysSinceLast =
                        daysSinceLast,
                    visits30 =
                        visits30,
                    intervals =
                        intervals,
                    averageInterval =
                        averageInterval
                )
        )
    }

    private fun stabilityScore(
        daysSinceLast: Long,
        visits30: Int,
        intervals: List<Double>,
        averageInterval: Double?
    ): Int {
        val expectedInterval =
            averageInterval
                ?.coerceAtLeast(
                    1.0
                ) ?: 30.0

        val recency =
            (
                1.0 -
                    (
                        daysSinceLast /
                            (
                                expectedInterval *
                                    2.0
                                )
                        )
                        .coerceIn(
                            0.0,
                            1.0
                        )
                ) *
                40.0

        val frequency =
            (
                visits30 /
                    8.0
                )
                .coerceIn(
                    0.0,
                    1.0
                ) *
                30.0

        val regularity =
            if (
                intervals.size >=
                2
            ) {
                val mean =
                    intervals.average()
                        .coerceAtLeast(
                            1.0
                        )

                val variance =
                    intervals.sumOf {
                        value ->
                        val d =
                            value -
                                mean

                        d *
                            d
                    } /
                        intervals.size

                val cv =
                    sqrt(
                        variance
                    ) /
                        mean

                (
                    1.0 -
                        cv.coerceIn(
                            0.0,
                            1.0
                        )
                    ) *
                    30.0
            } else {
                10.0
            }

        return (
            recency +
                frequency +
                regularity
            )
            .roundToInt()
            .coerceIn(
                0,
                100
            )
    }

    private fun buildChange(
        allRecords: List<CustomerPaymentRecord>,
        startDate: String?,
        endDate: String?
    ): CustomerChangeBreakdown {
        if (
            startDate ==
            null ||
            endDate ==
            null
        ) {
            return CustomerChangeBreakdown(
                available = false
            )
        }

        val start =
            runCatching {
                LocalDate.parse(
                    startDate
                )
            }.getOrNull()
                ?: return CustomerChangeBreakdown(
                    available = false
                )

        val end =
            runCatching {
                LocalDate.parse(
                    endDate
                )
            }.getOrNull()
                ?: return CustomerChangeBreakdown(
                    available = false
                )

        val days =
            ChronoUnit.DAYS
                .between(
                    start,
                    end
                ) +
                1

        if (
            days <=
            0
        ) {
            return CustomerChangeBreakdown(
                available = false
            )
        }

        val previousEnd =
            start.minusDays(
                1
            )

        val previousStart =
            previousEnd.minusDays(
                days -
                    1
            )

        val current =
            compactSummary(
                allRecords.filter {
                    it.businessDate >=
                        start.toString() &&
                        it.businessDate <=
                        end.toString()
                }
            )

        val previous =
            compactSummary(
                allRecords.filter {
                    it.businessDate >=
                        previousStart.toString() &&
                        it.businessDate <=
                        previousEnd.toString()
                }
            )

        if (
            current.customers ==
            0 &&
            previous.customers ==
            0
        ) {
            return CustomerChangeBreakdown(
                available = false
            )
        }

        val customerCountEffect =
            roundMoney(
                (
                    current.customers -
                        previous.customers
                    ) *
                    previous.averageCustomerValue
            )

        val customerValueEffect =
            roundMoney(
                current.customers *
                    (
                        current.averageCustomerValue -
                            previous.averageCustomerValue
                        )
            )

        val mainReason =
            when {
                abs(
                    customerCountEffect
                ) >
                    abs(
                        customerValueEffect
                    ) *
                    1.15 ->
                    "主要由客户数变化"

                abs(
                    customerValueEffect
                ) >
                    abs(
                        customerCountEffect
                    ) *
                    1.15 ->
                    "主要由客均贡献变化"

                else ->
                    "客户数与客均贡献共同影响"
            }

        return CustomerChangeBreakdown(
            available = true,
            currentRevenue =
                current.revenue,
            previousRevenue =
                previous.revenue,
            revenueChange =
                roundMoney(
                    current.revenue -
                        previous.revenue
                ),
            currentCustomers =
                current.customers,
            previousCustomers =
                previous.customers,
            customerChange =
                current.customers -
                    previous.customers,
            currentAverageCustomerValue =
                current.averageCustomerValue,
            previousAverageCustomerValue =
                previous.averageCustomerValue,
            customerCountEffect =
                customerCountEffect,
            customerValueEffect =
                customerValueEffect,
            mainReason =
                mainReason
        )
    }

    private data class CompactSummary(
        val revenue: Double,
        val customers: Int,
        val averageCustomerValue: Double
    )

    private fun compactSummary(
        records: List<CustomerPaymentRecord>
    ): CompactSummary {
        val positives =
            records.filter {
                it.netAmount >
                    0.005
            }

        val revenue =
            roundMoney(
                records.sumOf {
                    it.netAmount
                }
            )

        val customers =
            positives.map {
                it.customerKey
            }
                .toSet()
                .size

        return CompactSummary(
            revenue =
                revenue,
            customers =
                customers,
            averageCustomerValue =
                if (
                    customers >
                    0
                ) {
                    roundMoney(
                        revenue /
                            customers
                    )
                } else {
                    0.0
                }
        )
    }

    private fun percentile80(
        values: Collection<Double>
    ): Double {
        if (
            values.isEmpty()
        ) {
            return 0.0
        }

        val sorted =
            values.sorted()

        val index =
            (
                (
                    sorted.size -
                        1
                    ) *
                    0.8
                )
                .roundToInt()
                .coerceIn(
                    0,
                    sorted.lastIndex
                )

        return sorted[
            index
        ]
    }

    private fun roundMoney(
        value: Double
    ): Double =
        kotlin.math.round(
            value *
                100.0
        ) /
            100.0

    private val LIFECYCLE_ORDER =
        listOf(
            "新客",
            "一次客",
            "活跃老客",
            "高频客",
            "高价值客",
            "可能流失",
            "沉睡客",
            "普通老客"
        )
}
