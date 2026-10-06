package com.tianxian.fruit.data

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentBillParserTest {
    @Test
    fun onlyStrictWechatBusinessReceiptsAreAccepted() {
        val csv =
            """
            微信支付账单
            交易时间,交易类型,交易对方,收/支,金额(元),当前状态,交易单号
            2026-10-01 18:00:00,二维码收款,张三,收入,20.00,支付成功,wx-1
            2026-10-01 18:10:00,转账,李四,收入,99.00,已收钱,wx-2
            2026-10-01 18:20:00,二维码收款,水果店,支出,15.00,支付成功,wx-3
            2026-10-01 18:30:00,红包,王五,收入,8.00,已收钱,wx-4
            """.trimIndent()

        val result =
            PaymentBillParser.parse(
                "微信支付账单.csv",
                csv.toByteArray(Charsets.UTF_8)
            )

        assertEquals(
            PaymentBillPlatform.WECHAT,
            result.platform
        )
        assertEquals(
            4,
            result.totalRows
        )
        assertEquals(
            1,
            result.acceptedRows
        )
        assertEquals(
            "张三",
            result.transactions.single().customerName
        )
        assertEquals(
            "MEDIUM",
            result.transactions.single().identityConfidence
        )
    }

    @Test
    fun alipayGb18030BusinessReceiptUsesStableBuyerIdentity() {
        val text =
            """
            支付宝交易明细
            交易时间|交易分类|交易对方|对方账号|收/支|金额|交易状态|支付宝交易号
            2026-10-02 19:20:00|经营收款|李四|buyer_10001|收入|30.50|交易成功|ali-1
            2026-10-02 19:30:00|转账|朋友|friend_2|收入|100.00|交易成功|ali-2
            """.trimIndent()

        val result =
            PaymentBillParser.parse(
                "alipay_record.csv",
                text.toByteArray(
                    Charset.forName(
                        "GB18030"
                    )
                )
            )

        assertEquals(
            PaymentBillPlatform.ALIPAY,
            result.platform
        )
        assertEquals(
            1,
            result.acceptedRows
        )
        assertEquals(
            30.5,
            result.transactions.single().netAmount,
            0.001
        )
        assertEquals(
            "HIGH",
            result.transactions.single().identityConfidence
        )
    }

    @Test
    fun merchantXlsxNativeQrPaymentIsAccepted() {
        val xlsx =
            makeInlineXlsx(
                listOf(
                    listOf(
                        "交易时间",
                        "微信订单号",
                        "用户标识",
                        "交易类型",
                        "交易状态",
                        "订单金额"
                    ),
                    listOf(
                        "2026-10-03 20:00:00",
                        "420000001",
                        "openid-1",
                        "NATIVE",
                        "SUCCESS",
                        "45.00"
                    ),
                    listOf(
                        "2026-10-03 20:05:00",
                        "420000002",
                        "openid-2",
                        "MICROPAY",
                        "SUCCESS",
                        "50.00"
                    )
                )
            )

        val result =
            PaymentBillParser.parse(
                "ALL.xlsx",
                xlsx
            )

        assertEquals(
            PaymentBillPlatform.WECHAT,
            result.platform
        )
        assertEquals(
            1,
            result.acceptedRows
        )
        assertEquals(
            "420000001",
            result.transactions.single().transactionRef
        )
        assertEquals(
            "HIGH",
            result.transactions.single().identityConfidence
        )
    }

    @Test
    fun analyticsSeparatesRepeatAndNewCustomers() {
        val records =
            listOf(
                payment(
                    id = 1,
                    customer = "A",
                    name = "老客A",
                    date = "2026-09-20",
                    amount = 20.0
                ),
                payment(
                    id = 2,
                    customer = "A",
                    name = "老客A",
                    date = "2026-10-01",
                    amount = 30.0
                ),
                payment(
                    id = 3,
                    customer = "A",
                    name = "老客A",
                    date = "2026-10-03",
                    amount = 25.0
                ),
                payment(
                    id = 4,
                    customer = "B",
                    name = "新客B",
                    date = "2026-10-03",
                    amount = 15.0
                )
            )

        val result =
            CustomerAnalyticsEngine.analyze(
                allRecords = records,
                startDate = "2026-10-01",
                endDate = "2026-10-06",
                today = LocalDate.of(
                    2026,
                    10,
                    6
                )
            )

        assertEquals(
            2,
            result.summary.customerCount
        )
        assertEquals(
            1,
            result.summary.newCustomerCount
        )
        assertEquals(
            1,
            result.summary.oldCustomerCount
        )
        assertEquals(
            1,
            result.summary.repeatCustomerCount
        )
        assertEquals(
            0.5,
            result.summary.repeatRate,
            0.001
        )
        assertTrue(
            result.profiles.first {
                it.customerKey == "A"
            }.stabilityScore >
                0
        )
    }

    // Linked refunds change money only; they must never create an extra customer visit.
    @Test
    fun refundsReduceCustomerAndStoreRevenueWithoutAddingVisits() {
        val records =
            listOf(
                payment(
                    id = 1,
                    customer = "A",
                    name = "老客A",
                    date = "2026-10-02",
                    amount = 100.0
                ),
                CustomerPaymentRecord(
                    id = 2,
                    platform = "WECHAT",
                    tradeTime = "2026-10-03 12:00:00",
                    businessDate = "2026-10-03",
                    amount = 0.0,
                    refundAmount = 20.0,
                    netAmount = -20.0,
                    customerKey = "A",
                    customerName = "老客A",
                    identityConfidence = "HIGH",
                    transactionType = "二维码收款",
                    tradeStatus = "退款",
                    storeId = 1,
                    storeName = "测试位置"
                )
            )

        val result =
            CustomerAnalyticsEngine.analyze(
                allRecords = records,
                startDate = "2026-10-01",
                endDate = "2026-10-06",
                today = LocalDate.of(
                    2026,
                    10,
                    6
                )
            )

        assertEquals(
            80.0,
            result.summary.netRevenue,
            0.001
        )
        assertEquals(
            1,
            result.summary.paymentCount
        )

        val profile =
            result.profiles.single()

        assertEquals(
            80.0,
            profile.periodAmount,
            0.001
        )
        assertEquals(
            1,
            profile.periodPayments
        )
        assertEquals(
            1,
            profile.periodVisits
        )

        val store =
            result.stores.single()

        assertEquals(
            80.0,
            store.netRevenue,
            0.001
        )
        assertEquals(
            1,
            store.paymentCount
        )
    }

    @Test
    fun morningOutgoingPaymentsBecomePurchaseCandidatesOnlyWithinRules() {
        val csv =
            """
            微信支付账单
            交易时间,交易类型,交易对方,对方账号,收/支,金额(元),当前状态,交易单号
            2026-10-05 06:20:00,二维码付款,江南果品,supplier-1,支出,320.00,支付成功,p-1
            2026-10-05 09:10:00,转账,阿强,supplier-2,支出,200.00,支付成功,p-2
            2026-10-05 05:59:00,二维码付款,早餐店,food-1,支出,50.00,支付成功,p-3
            2026-10-05 14:01:00,二维码付款,市场,market-1,支出,500.00,支付成功,p-4
            2026-10-05 08:30:00,红包,朋友,friend-1,支出,80.00,支付成功,p-5
            2026-10-05 10:00:00,信用卡还款,银行,bank-1,支出,600.00,支付成功,p-6
            2026-10-05 18:00:00,二维码收款,客户A,buyer-1,收入,25.00,支付成功,r-1
            """.trimIndent()

        val result =
            PaymentBillParser.parse(
                "微信支付账单.csv",
                csv.toByteArray(Charsets.UTF_8)
            )

        assertEquals(
            1,
            result.acceptedRows
        )
        assertEquals(
            2,
            result.purchaseCandidates.size
        )
        assertEquals(
            listOf("江南果品", "阿强"),
            result.purchaseCandidates.map {
                it.counterpartyName
            }
        )
        assertEquals(
            listOf(320.0, 200.0),
            result.purchaseCandidates.map {
                it.amount
            }
        )
    }

    @Test
    fun purchaseScoringUsesBusinessEvidenceAndSupplierLearning() {
        val qr =
            HistoricalPurchaseCandidateRecord(
                id = 1,
                platform = "WECHAT",
                tradeTime = "2026-10-05 07:00:00",
                businessDate = "2026-10-05",
                amount = 320.0,
                transactionRef = "p-1",
                counterpartyKey = "supplier",
                counterpartyName = "江南果品",
                identityConfidence = "HIGH",
                transactionType = "二维码付款",
                tradeStatus = "支付成功",
                status = "PENDING",
                sourceFile = "bill.csv",
                createdAt = 0,
                updatedAt = 0
            )

        val withBusiness =
            HistoricalPurchaseScoring.score(
                record = qr,
                hasBusinessEvidence = true,
                knownSupplier = false,
                confirmedHistoryCount = 0,
                occurrenceCount = 1
            )

        assertEquals(
            "HIGH",
            withBusiness.confidence
        )
        assertTrue(
            withBusiness.score >=
                HistoricalPurchaseScoring.HIGH_SCORE
        )

        val transfer =
            qr.copy(
                amount = 120.0,
                transactionType = "转账"
            )

        val unknown =
            HistoricalPurchaseScoring.score(
                record = transfer,
                hasBusinessEvidence = false,
                knownSupplier = false,
                confirmedHistoryCount = 0,
                occurrenceCount = 1
            )
        val supplier =
            HistoricalPurchaseScoring.score(
                record = transfer,
                hasBusinessEvidence = false,
                knownSupplier = true,
                confirmedHistoryCount = 0,
                occurrenceCount = 1
            )

        assertTrue(
            supplier.score > unknown.score
        )
        assertEquals(
            "HIGH",
            supplier.confidence
        )
    }

    @Test
    fun largePaymentsAndLinkedRefundsAreExcludedButCanBeOverridden() {
        val large =
            payment(
                id = 1,
                customer = "A",
                name = "客户A",
                date = "2026-10-05",
                amount = 2000.0
            ).copy(
                transactionRef = "trade-large"
            )
        val refund =
            CustomerPaymentRecord(
                id = 2,
                platform = "WECHAT",
                tradeTime = "2026-10-05 19:00:00",
                businessDate = "2026-10-05",
                amount = 0.0,
                refundAmount = 100.0,
                netAmount = -100.0,
                customerKey = "A",
                customerName = "客户A",
                identityConfidence = "HIGH",
                transactionType = "二维码收款",
                tradeStatus = "退款",
                storeId = 1,
                storeName = "测试位置",
                transactionRef = "trade-large"
            )
        val normal =
            payment(
                id = 3,
                customer = "B",
                name = "客户B",
                date = "2026-10-05",
                amount = 20.0
            ).copy(
                transactionRef = "trade-normal"
            )

        val settings =
            CustomerAnalysisSettings(
                excludeLargePayments = true,
                largePaymentThreshold = 2000.0
            )
        val filtered =
            CustomerAnalyticsEngine.filterRecords(
                allRecords =
                    listOf(
                        large,
                        refund,
                        normal
                    ),
                settings = settings
            )

        assertEquals(
            listOf(3L),
            filtered.included.map {
                it.id
            }
        )
        assertTrue(
            1L in filtered.autoLargeIds
        )
        assertTrue(
            2L in filtered.excludedIds
        )

        val overridden =
            CustomerAnalyticsEngine.filterRecords(
                allRecords =
                    listOf(
                        large.copy(
                            analysisIncludedOverride = true
                        ),
                        refund,
                        normal
                    ),
                settings = settings
            )

        assertEquals(
            setOf(
                1L,
                2L,
                3L
            ),
            overridden.included
                .map {
                    it.id
                }
                .toSet()
        )
    }

    @Test
    fun oldSingleVisitCustomerIsOneTimeInsteadOfNew() {
        val result =
            CustomerAnalyticsEngine.analyze(
                allRecords =
                    listOf(
                        payment(
                            id = 1,
                            customer = "A",
                            name = "一次客A",
                            date = "2026-08-01",
                            amount = 20.0
                        )
                    ),
                startDate = null,
                endDate = null,
                today =
                    LocalDate.of(
                        2026,
                        10,
                        6
                    )
            )

        assertEquals(
            "一次客",
            result.profiles
                .single()
                .lifecycle
        )
        assertEquals(
            1,
            result.lifecycles
                .first {
                    it.label ==
                        "一次客"
                }
                .count
        )
    }

    @Test
    fun hourlyTrafficCountsUniqueCustomersPerHour() {
        val rows =
            listOf(
                payment(
                    id = 1,
                    customer = "A",
                    name = "客户A",
                    date = "2026-10-06",
                    amount = 20.0
                ).copy(
                    tradeTime =
                        "2026-10-06 18:05:00"
                ),
                payment(
                    id = 2,
                    customer = "A",
                    name = "客户A",
                    date = "2026-10-06",
                    amount = 10.0
                ).copy(
                    tradeTime =
                        "2026-10-06 18:35:00"
                ),
                payment(
                    id = 3,
                    customer = "B",
                    name = "客户B",
                    date = "2026-10-06",
                    amount = 30.0
                ).copy(
                    tradeTime =
                        "2026-10-06 19:10:00"
                )
            )

        val traffic =
            CustomerAnalyticsEngine.hourlyTraffic(
                allRecords = rows,
                date = "2026-10-06"
            )

        val hour18 =
            traffic.first {
                it.hour == 18
            }
        val hour19 =
            traffic.first {
                it.hour == 19
            }

        assertEquals(
            1,
            hour18.customerCount
        )
        assertEquals(
            2,
            hour18.paymentCount
        )
        assertEquals(
            30.0,
            hour18.amount,
            0.001
        )
        assertEquals(
            1,
            hour19.customerCount
        )
    }

    @Test
    fun sameWeekdayComparisonUsesSameStoreOnly() {
        val rows =
            listOf(
                payment(
                    1,
                    "A",
                    "A",
                    "2026-10-06",
                    20.0
                ),
                payment(
                    2,
                    "B",
                    "B",
                    "2026-10-06",
                    20.0
                ),
                payment(
                    3,
                    "C",
                    "C",
                    "2026-09-29",
                    20.0
                ),
                payment(
                    4,
                    "D",
                    "D",
                    "2026-09-22",
                    20.0
                ),
                payment(
                    5,
                    "E",
                    "E",
                    "2026-09-22",
                    20.0
                ),
                payment(
                    6,
                    "X",
                    "X",
                    "2026-09-29",
                    20.0
                ).copy(
                    storeId = 2,
                    storeName = "其他位置"
                )
            )

        val comparison =
            CustomerAnalyticsEngine.sameWeekdayComparisons(
                allRecords = rows,
                date = "2026-10-06"
            )
                .first {
                    it.storeName ==
                        "测试位置"
                }

        assertEquals(
            2,
            comparison.currentCustomers
        )
        assertEquals(
            2,
            comparison.sampleDays
        )
        assertEquals(
            1.5,
            comparison.averageCustomers,
            0.001
        )
    }

    private fun payment(
        id: Long,
        customer: String,
        name: String,
        date: String,
        amount: Double
    ) =
        CustomerPaymentRecord(
            id = id,
            platform = "WECHAT",
            tradeTime = "$date 18:00:00",
            businessDate = date,
            amount = amount,
            refundAmount = 0.0,
            netAmount = amount,
            customerKey = customer,
            customerName = name,
            identityConfidence = "HIGH",
            transactionType = "二维码收款",
            tradeStatus = "成功",
            storeId = 1,
            storeName = "测试位置"
        )

    private fun makeInlineXlsx(
        rows: List<List<String>>
    ): ByteArray {
        val sheet =
            buildString {
                append(
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                        "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>"
                )
                rows.forEachIndexed {
                    rowIndex,
                    row ->
                    append(
                        "<row r=\"${rowIndex + 1}\">"
                    )
                    row.forEachIndexed {
                        columnIndex,
                        value ->
                        val ref =
                            columnName(
                                columnIndex
                            ) +
                                (
                                    rowIndex +
                                        1
                                    )
                        append(
                            "<c r=\"$ref\" t=\"inlineStr\"><is><t>${escapeXml(value)}</t></is></c>"
                        )
                    }
                    append(
                        "</row>"
                    )
                }
                append(
                    "</sheetData></worksheet>"
                )
            }

        val out =
            ByteArrayOutputStream()

        ZipOutputStream(
            out
        ).use {
            zip ->
            zip.putNextEntry(
                ZipEntry(
                    "xl/workbook.xml"
                )
            )
            zip.write(
                (
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                        "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"/>"
                    )
                    .toByteArray(
                        Charsets.UTF_8
                    )
            )
            zip.closeEntry()

            zip.putNextEntry(
                ZipEntry(
                    "xl/worksheets/sheet1.xml"
                )
            )
            zip.write(
                sheet.toByteArray(
                    Charsets.UTF_8
                )
            )
            zip.closeEntry()
        }

        return out.toByteArray()
    }

    private fun columnName(
        index: Int
    ): String {
        var value =
            index +
                1

        val result =
            StringBuilder()

        while (
            value >
            0
        ) {
            val remainder =
                (
                    value -
                        1
                    ) %
                    26

            result.append(
                (
                    'A'.code +
                        remainder
                    )
                    .toChar()
            )

            value =
                (
                    value -
                        1
                    ) /
                    26
        }

        return result
            .reverse()
            .toString()
    }

    private fun escapeXml(
        value: String
    ): String =
        value
            .replace(
                "&",
                "&amp;"
            )
            .replace(
                "<",
                "&lt;"
            )
            .replace(
                ">",
                "&gt;"
            )
}
