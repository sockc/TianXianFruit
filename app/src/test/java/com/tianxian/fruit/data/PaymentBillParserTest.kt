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
