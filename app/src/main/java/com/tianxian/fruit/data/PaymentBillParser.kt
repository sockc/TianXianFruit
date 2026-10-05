package com.tianxian.fruit.data

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

enum class PaymentBillPlatform(
    val label: String
) {
    WECHAT("微信"),
    ALIPAY("支付宝")
}

data class ParsedBusinessReceipt(
    val platform: PaymentBillPlatform,
    val tradeTime: String,
    val businessDate: String,
    val amount: Double,
    val refundAmount: Double,
    val netAmount: Double,
    val transactionRef: String,
    val dedupeKey: String,
    val customerKey: String,
    val customerName: String,
    val identityConfidence: String,
    val transactionType: String,
    val tradeStatus: String
)

data class PaymentBillParseResult(
    val platform: PaymentBillPlatform,
    val totalRows: Int,
    val acceptedRows: Int,
    val ignoredRows: Int,
    val refundRows: Int,
    val errorRows: Int,
    val transactions: List<ParsedBusinessReceipt>,
    val warnings: List<String> = emptyList()
)

internal object PaymentBillParser {
    private const val MAX_UNZIPPED_BYTES = 40 * 1024 * 1024

    fun parse(
        fileName: String,
        bytes: ByteArray
    ): PaymentBillParseResult {
        require(bytes.isNotEmpty()) {
            "账单文件为空"
        }

        if (
            bytes.size >= 8 &&
            bytes[0] == 0xD0.toByte() &&
            bytes[1] == 0xCF.toByte() &&
            bytes[2] == 0x11.toByte() &&
            bytes[3] == 0xE0.toByte()
        ) {
            throw IllegalArgumentException(
                "暂不支持旧版 XLS，请在微信/支付宝导出时选择 CSV 或 XLSX"
            )
        }

        val table =
            if (isZip(bytes)) {
                parseZipOrXlsx(
                    fileName = fileName,
                    bytes = bytes
                )
            } else {
                parseTextTable(
                    decodeText(bytes)
                )
            }

        if (table.isEmpty()) {
            throw IllegalArgumentException(
                "账单中没有可读取的数据"
            )
        }

        val headerIndex =
            findHeaderRow(table)
                ?: throw IllegalArgumentException(
                    "没有找到微信/支付宝账单表头"
                )

        val headers =
            table[headerIndex]
                .map(::cleanCell)

        val platform =
            detectPlatform(
                fileName = fileName,
                headers = headers,
                rows =
                    table.subList(
                        0,
                        minOf(
                            table.size,
                            headerIndex + 8
                        )
                    )
            ) ?: throw IllegalArgumentException(
                "无法判断账单来源，请使用微信或支付宝官方导出的账单"
            )

        val headerMap =
            linkedMapOf<String, Int>().apply {
                headers.forEachIndexed {
                    index,
                    header ->
                    val key =
                        normalizeHeader(
                            header
                        )
                    if (
                        key.isNotBlank() &&
                        !containsKey(key)
                    ) {
                        put(
                            key,
                            index
                        )
                    }
                }
            }

        var totalRows = 0
        var ignoredRows = 0
        var refundRows = 0
        var errorRows = 0

        val accepted =
            mutableListOf<
                ParsedBusinessReceipt
            >()

        table
            .drop(headerIndex + 1)
            .forEach {
                row ->
                if (
                    row.all {
                        cleanCell(it)
                            .isBlank()
                    }
                ) {
                    return@forEach
                }

                totalRows += 1

                runCatching {
                    parseBusinessReceipt(
                        platform = platform,
                        headers = headerMap,
                        row = row
                    )
                }.onSuccess {
                    receipt ->
                    if (receipt == null) {
                        ignoredRows += 1
                    } else {
                        accepted += receipt
                        if (
                            receipt.refundAmount >
                            0.005
                        ) {
                            refundRows += 1
                        }
                    }
                }.onFailure {
                    errorRows += 1
                }
            }

        return PaymentBillParseResult(
            platform = platform,
            totalRows = totalRows,
            acceptedRows =
                accepted.size,
            ignoredRows =
                ignoredRows,
            refundRows =
                refundRows,
            errorRows =
                errorRows,
            transactions =
                accepted,
            warnings =
                buildList {
                    if (
                        accepted.isEmpty()
                    ) {
                        add(
                            "没有识别到二维码收款或经营收款"
                        )
                    }
                    if (
                        accepted.any {
                            it.identityConfidence ==
                                "LOW"
                        }
                    ) {
                        add(
                            "部分交易缺少稳定客户标识，只能作为匿名客户统计"
                        )
                    }
                }
        )
    }

    fun fileHash(
        bytes: ByteArray
    ): String =
        sha256Hex(
            bytes
        )

    private fun parseBusinessReceipt(
        platform: PaymentBillPlatform,
        headers: Map<String, Int>,
        row: List<String>
    ): ParsedBusinessReceipt? {
        fun value(
            vararg aliases: String
        ): String {
            aliases.forEach {
                alias ->
                val index =
                    headers[
                        normalizeHeader(
                            alias
                        )
                    ] ?: return@forEach
                if (
                    index in row.indices
                ) {
                    val v =
                        cleanCell(
                            row[index]
                        )
                    if (
                        v.isNotBlank()
                    ) {
                        return v
                    }
                }
            }
            return ""
        }

        val direction =
            value(
                "收/支",
                "收支",
                "资金方向",
                "收入/支出",
                "账务类型"
            )

        if (
            direction.contains(
                "支出"
            ) ||
            direction.equals(
                "支",
                ignoreCase = true
            )
        ) {
            return null
        }

        val type =
            value(
                "交易类型",
                "交易分类",
                "业务类型",
                "交易方式",
                "收款类型",
                "资金类型"
            )

        val product =
            value(
                "商品",
                "商品名称",
                "商品说明",
                "名称",
                "业务名称"
            )

        val remark =
            value(
                "备注",
                "付款备注",
                "商户数据包"
            )

        val typeEvidence =
            listOf(
                type,
                product,
                remark
            )
                .joinToString(
                    "|"
                )
                .uppercase(
                    Locale.ROOT
                )

        val strictReceipt =
            STRICT_RECEIPT_PHRASES
                .any {
                    phrase ->
                    typeEvidence.contains(
                        phrase.uppercase(
                            Locale.ROOT
                        )
                    )
                } ||
                type
                    .trim()
                    .equals(
                        "NATIVE",
                        ignoreCase = true
                    )

        if (
            !strictReceipt
        ) {
            return null
        }

        val status =
            value(
                "交易状态",
                "当前状态",
                "状态",
                "资金状态"
            )

        val rawAmount =
            money(
                value(
                    "商家实收",
                    "实收金额",
                    "应结订单金额",
                    "订单金额(元)",
                    "订单金额",
                    "金额(元)",
                    "金额",
                    "交易金额",
                    "总金额"
                )
            )

        val explicitRefund =
            money(
                value(
                    "退款金额",
                    "申请退款金额",
                    "商家退款金额",
                    "退款"
                )
            )

        val refundState =
            status.contains(
                "退款",
                ignoreCase = true
            ) ||
                status.equals(
                    "REFUND",
                    ignoreCase = true
                )

        if (
            isRejectedStatus(
                status
            ) &&
            !refundState
        ) {
            return null
        }

        val refundAmount =
            when {
                explicitRefund >
                    0.005 ->
                    explicitRefund

                refundState &&
                    rawAmount >
                    0.005 ->
                    rawAmount

                else ->
                    0.0
            }

        val paymentAmount =
            if (
                refundState
            ) {
                0.0
            } else {
                rawAmount
            }

        if (
            paymentAmount <=
            0.005 &&
            refundAmount <=
            0.005
        ) {
            return null
        }

        val timeRaw =
            value(
                "交易时间",
                "创建时间",
                "支付时间",
                "付款时间",
                "时间",
                "入账时间"
            )

        val tradeTime =
            normalizeDateTime(
                timeRaw
            ) ?: return null

        val businessDate =
            tradeTime
                .take(10)

        val transactionRef =
            value(
                "微信订单号",
                "微信支付订单号",
                "支付宝交易号",
                "交易订单号",
                "交易单号",
                "订单号",
                "商户订单号",
                "商家订单号"
            )
                .ifBlank {
                    sha256Hex(
                        (
                            platform.name +
                                "|" +
                                tradeTime +
                                "|" +
                                paymentAmount +
                                "|" +
                                refundAmount +
                                "|" +
                                typeEvidence
                            )
                            .toByteArray(
                                Charsets.UTF_8
                            )
                    )
                        .take(24)
                }

        val stableIdentity =
            value(
                "用户标识",
                "OPENID",
                "买家账号",
                "买家信息",
                "付款方账号",
                "对方账号",
                "对方支付宝账号",
                "支付宝账号",
                "买家支付宝账号"
            )

        val customerDisplay =
            value(
                "交易对方",
                "付款方",
                "付款方名称",
                "买家名称",
                "对方名称",
                "对方昵称",
                "用户昵称",
                "买家"
            )

        val identity =
            when {
                stableIdentity
                    .isNotBlank() ->
                    Triple(
                        sha256Hex(
                            (
                                platform.name +
                                    "|ID|" +
                                    normalizeIdentity(
                                        stableIdentity
                                    )
                                )
                                .toByteArray(
                                    Charsets.UTF_8
                                )
                        ),
                        customerDisplay
                            .ifBlank {
                                maskedCustomerLabel(
                                    platform,
                                    stableIdentity
                                )
                            },
                        "HIGH"
                    )

                customerDisplay
                    .isNotBlank() ->
                    Triple(
                        sha256Hex(
                            (
                                platform.name +
                                    "|NAME|" +
                                    normalizeIdentity(
                                        customerDisplay
                                    )
                                )
                                .toByteArray(
                                    Charsets.UTF_8
                                )
                        ),
                        customerDisplay,
                        "MEDIUM"
                    )

                else ->
                    Triple(
                        sha256Hex(
                            (
                                platform.name +
                                    "|ANON|" +
                                    transactionRef +
                                    "|" +
                                    tradeTime
                                )
                                .toByteArray(
                                    Charsets.UTF_8
                                )
                        ),
                        "匿名客户",
                        "LOW"
                    )
            }

        val netAmount =
            roundMoneyLocal(
                paymentAmount -
                    refundAmount
            )

        val dedupeKey =
            sha256Hex(
                (
                    platform.name +
                        "|" +
                        transactionRef +
                        "|" +
                        tradeTime +
                        "|" +
                        roundMoneyLocal(
                            paymentAmount
                        ) +
                        "|" +
                        roundMoneyLocal(
                            refundAmount
                        ) +
                        "|" +
                        status
                    )
                    .toByteArray(
                        Charsets.UTF_8
                    )
            )

        return ParsedBusinessReceipt(
            platform = platform,
            tradeTime = tradeTime,
            businessDate =
                businessDate,
            amount =
                roundMoneyLocal(
                    paymentAmount
                ),
            refundAmount =
                roundMoneyLocal(
                    refundAmount
                ),
            netAmount =
                netAmount,
            transactionRef =
                transactionRef,
            dedupeKey =
                dedupeKey,
            customerKey =
                identity.first,
            customerName =
                identity.second,
            identityConfidence =
                identity.third,
            transactionType =
                type.ifBlank {
                    product
                }
                    .ifBlank {
                        "经营收款"
                    },
            tradeStatus =
                status.ifBlank {
                    if (
                        refundAmount >
                        0.005
                    ) {
                        "退款"
                    } else {
                        "成功"
                    }
                }
        )
    }

    private fun isRejectedStatus(
        status: String
    ): Boolean {
        if (
            status.isBlank()
        ) {
            return false
        }

        val upper =
            status.uppercase(
                Locale.ROOT
            )

        return REJECTED_STATUS_TOKENS
            .any {
                upper.contains(
                    it
                )
            }
    }

    private fun parseZipOrXlsx(
        fileName: String,
        bytes: ByteArray
    ): List<List<String>> {
        val entries =
            unzip(
                bytes
            )

        if (
            entries.containsKey(
                "xl/workbook.xml"
            )
        ) {
            return parseXlsxEntries(
                entries
            )
        }

        val preferred =
            entries.entries
                .filter {
                    !it.key.endsWith(
                        "/"
                    )
                }
                .sortedBy {
                    entry ->
                    when {
                        entry.key.endsWith(
                            ".csv",
                            ignoreCase = true
                        ) -> 0

                        entry.key.endsWith(
                            ".txt",
                            ignoreCase = true
                        ) -> 1

                        entry.key.endsWith(
                            ".xlsx",
                            ignoreCase = true
                        ) -> 2

                        else -> 9
                    }
                }
                .firstOrNull {
                    entry ->
                    entry.key.endsWith(
                        ".csv",
                        ignoreCase = true
                    ) ||
                        entry.key.endsWith(
                            ".txt",
                            ignoreCase = true
                        ) ||
                        entry.key.endsWith(
                            ".xlsx",
                            ignoreCase = true
                        )
                }
                ?: throw IllegalArgumentException(
                    "压缩包中没有找到 CSV/TXT/XLSX 账单"
                )

        return if (
            preferred.key.endsWith(
                ".xlsx",
                ignoreCase = true
            )
        ) {
            parseZipOrXlsx(
                preferred.key,
                preferred.value
            )
        } else {
            parseTextTable(
                decodeText(
                    preferred.value
                )
            )
        }
    }

    private fun unzip(
        bytes: ByteArray
    ): Map<String, ByteArray> {
        val result =
            linkedMapOf<
                String,
                ByteArray
            >()

        var total = 0

        ZipInputStream(
            ByteArrayInputStream(
                bytes
            )
        ).use {
            zip ->
            while (true) {
                val entry =
                    zip.nextEntry
                        ?: break

                if (
                    entry.isDirectory
                ) {
                    continue
                }

                val content =
                    zip.readBytes()

                total +=
                    content.size

                if (
                    total >
                    MAX_UNZIPPED_BYTES
                ) {
                    throw IllegalArgumentException(
                        "账单解压后过大，请缩小导出时间范围"
                    )
                }

                result[
                    entry.name
                        .replace(
                            "\\",
                            "/"
                        )
                ] =
                    content
            }
        }

        return result
    }

    private fun parseXlsxEntries(
        entries: Map<String, ByteArray>
    ): List<List<String>> {
        val sharedStrings =
            entries[
                "xl/sharedStrings.xml"
            ]?.let(
                ::parseSharedStrings
            )
                ?: emptyList()

        val sheetEntry =
            entries.entries
                .filter {
                    it.key.matches(
                        Regex(
                            "xl/worksheets/sheet\\d+\\.xml"
                        )
                    )
                }
                .minByOrNull {
                    it.key
                }
                ?: throw IllegalArgumentException(
                    "XLSX 中没有工作表"
                )

        val document =
            document(
                sheetEntry.value
            )

        val rows =
            document
                .getElementsByTagName(
                    "row"
                )

        return buildList {
            for (
                rowIndex in
                0 until rows.length
            ) {
                val row =
                    rows.item(
                        rowIndex
                    ) as? Element
                        ?: continue

                val cells =
                    row.getElementsByTagName(
                        "c"
                    )

                val values =
                    linkedMapOf<
                        Int,
                        String
                    >()

                var maxColumn = -1

                for (
                    cellIndex in
                    0 until cells.length
                ) {
                    val cell =
                        cells.item(
                            cellIndex
                        ) as? Element
                            ?: continue

                    val ref =
                        cell.getAttribute(
                            "r"
                        )

                    val column =
                        columnIndex(
                            ref
                        )
                            .takeIf {
                                it >= 0
                            }
                            ?: cellIndex

                    val type =
                        cell.getAttribute(
                            "t"
                        )

                    val raw =
                        when (
                            type
                        ) {
                            "inlineStr" ->
                                cell
                                    .getElementsByTagName(
                                        "t"
                                    )
                                    .let {
                                        nodes ->
                                        buildString {
                                            for (
                                                i in
                                                0 until nodes.length
                                            ) {
                                                append(
                                                    nodes.item(
                                                        i
                                                    )
                                                        .textContent
                                                )
                                            }
                                        }
                                    }

                            else ->
                                cell
                                    .getElementsByTagName(
                                        "v"
                                    )
                                    .item(
                                        0
                                    )
                                    ?.textContent
                                    .orEmpty()
                        }

                    val value =
                        if (
                            type == "s"
                        ) {
                            raw.toIntOrNull()
                                ?.let {
                                    sharedStrings
                                        .getOrNull(
                                            it
                                        )
                                }
                                .orEmpty()
                        } else {
                            raw
                        }

                    values[
                        column
                    ] =
                        value

                    maxColumn =
                        maxOf(
                            maxColumn,
                            column
                        )
                }

                if (
                    maxColumn >= 0
                ) {
                    add(
                        (0..maxColumn)
                            .map {
                                values[it]
                                    .orEmpty()
                            }
                    )
                }
            }
        }
    }

    private fun parseSharedStrings(
        bytes: ByteArray
    ): List<String> {
        val document =
            document(
                bytes
            )

        val items =
            document
                .getElementsByTagName(
                    "si"
                )

        return buildList {
            for (
                index in
                0 until items.length
            ) {
                val item =
                    items.item(
                        index
                    ) as? Element
                        ?: continue

                val texts =
                    item.getElementsByTagName(
                        "t"
                    )

                add(
                    buildString {
                        for (
                            i in
                            0 until texts.length
                        ) {
                            append(
                                texts.item(
                                    i
                                )
                                    .textContent
                            )
                        }
                    }
                )
            }
        }
    }

    private fun document(
        bytes: ByteArray
    ) =
        DocumentBuilderFactory
            .newInstance()
            .apply {
                isNamespaceAware =
                    false
                runCatching {
                    setFeature(
                        "http://apache.org/xml/features/disallow-doctype-decl",
                        true
                    )
                }
                runCatching {
                    setFeature(
                        "http://xml.org/sax/features/external-general-entities",
                        false
                    )
                }
                runCatching {
                    setFeature(
                        "http://xml.org/sax/features/external-parameter-entities",
                        false
                    )
                }
            }
            .newDocumentBuilder()
            .parse(
                ByteArrayInputStream(
                    bytes
                )
            )

    private fun columnIndex(
        cellRef: String
    ): Int {
        val letters =
            cellRef.takeWhile {
                it.isLetter()
            }
                .uppercase(
                    Locale.ROOT
                )

        if (
            letters.isBlank()
        ) {
            return -1
        }

        var value = 0

        letters.forEach {
            c ->
            value =
                value * 26 +
                    (
                        c.code -
                            'A'.code +
                            1
                        )
        }

        return value - 1
    }

    private fun parseTextTable(
        text: String
    ): List<List<String>> {
        val normalized =
            text
                .replace(
                    "\u0000",
                    ""
                )
                .replace(
                    "\r\n",
                    "\n"
                )
                .replace(
                    '\r',
                    '\n'
                )

        val delimiter =
            chooseDelimiter(
                normalized
            )

        return parseDelimited(
            normalized,
            delimiter
        )
    }

    private fun chooseDelimiter(
        text: String
    ): Char {
        val lines =
            text.lineSequence()
                .filter {
                    it.isNotBlank()
                }
                .take(80)
                .toList()

        val candidates =
            listOf(
                ',',
                '\t',
                '|'
            )

        return candidates
            .maxByOrNull {
                delimiter ->
                lines.sumOf {
                    line ->
                    val count =
                        line.count {
                            it ==
                                delimiter
                        }

                    val headerBonus =
                        if (
                            KNOWN_HEADER_WORDS
                                .any {
                                    word ->
                                    line.contains(
                                        word,
                                        ignoreCase = true
                                    )
                                }
                        ) {
                            count * 3
                        } else {
                            count
                        }

                    headerBonus
                }
            } ?: ','
    }

    private fun parseDelimited(
        text: String,
        delimiter: Char
    ): List<List<String>> {
        val rows =
            mutableListOf<
                MutableList<String>
            >()

        var row =
            mutableListOf<String>()

        val cell =
            StringBuilder()

        var quoted = false
        var index = 0

        fun endCell() {
            row +=
                cell.toString()
            cell.setLength(
                0
            )
        }

        fun endRow() {
            endCell()
            if (
                row.any {
                    it.isNotBlank()
                }
            ) {
                rows +=
                    row
            }
            row =
                mutableListOf()
        }

        while (
            index <
            text.length
        ) {
            val ch =
                text[index]

            when {
                ch == '"' -> {
                    if (
                        quoted &&
                        index + 1 <
                        text.length &&
                        text[index + 1] ==
                        '"'
                    ) {
                        cell.append(
                            '"'
                        )
                        index += 1
                    } else {
                        quoted =
                            !quoted
                    }
                }

                ch == delimiter &&
                    !quoted ->
                    endCell()

                ch == '\n' &&
                    !quoted ->
                    endRow()

                else ->
                    cell.append(
                        ch
                    )
            }

            index += 1
        }

        if (
            cell.isNotEmpty() ||
            row.isNotEmpty()
        ) {
            endRow()
        }

        return rows
    }

    private fun findHeaderRow(
        rows: List<List<String>>
    ): Int? =
        rows
            .take(120)
            .mapIndexed {
                index,
                row ->
                val normalized =
                    row.map {
                        normalizeHeader(
                            it
                        )
                    }

                val score =
                    normalized.count {
                        cell ->
                        KNOWN_HEADER_NORMALIZED
                            .contains(
                                cell
                            )
                    }

                index to
                    score
            }
            .filter {
                it.second >=
                    2
            }
            .maxByOrNull {
                it.second
            }
            ?.first

    private fun detectPlatform(
        fileName: String,
        headers: List<String>,
        rows: List<List<String>>
    ): PaymentBillPlatform? {
        val text =
            (
                fileName +
                    "|" +
                    headers.joinToString(
                        "|"
                    ) +
                    "|" +
                    rows
                        .flatten()
                        .take(120)
                        .joinToString(
                            "|"
                        )
                )
                .lowercase(
                    Locale.ROOT
                )

        return when {
            text.contains(
                "微信"
            ) ||
                text.contains(
                    "wechat"
                ) ||
                headers.any {
                    it.contains(
                        "微信订单号"
                    ) ||
                        it.contains(
                            "微信支付订单号"
                        )
                } ->
                PaymentBillPlatform.WECHAT

            text.contains(
                "支付宝"
            ) ||
                text.contains(
                    "alipay"
                ) ||
                headers.any {
                    it.contains(
                        "支付宝交易号"
                    )
                } ->
                PaymentBillPlatform.ALIPAY

            else ->
                null
        }
    }

    private fun decodeText(
        bytes: ByteArray
    ): String {
        if (
            bytes.size >= 3 &&
            bytes[0] ==
            0xEF.toByte() &&
            bytes[1] ==
            0xBB.toByte() &&
            bytes[2] ==
            0xBF.toByte()
        ) {
            return String(
                bytes,
                3,
                bytes.size - 3,
                StandardCharsets.UTF_8
            )
        }

        if (
            bytes.size >= 2 &&
            bytes[0] ==
            0xFF.toByte() &&
            bytes[1] ==
            0xFE.toByte()
        ) {
            return String(
                bytes,
                2,
                bytes.size - 2,
                StandardCharsets.UTF_16LE
            )
        }

        if (
            bytes.size >= 2 &&
            bytes[0] ==
            0xFE.toByte() &&
            bytes[1] ==
            0xFF.toByte()
        ) {
            return String(
                bytes,
                2,
                bytes.size - 2,
                StandardCharsets.UTF_16BE
            )
        }

        return try {
            val decoder =
                StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(
                        CodingErrorAction.REPORT
                    )
                    .onUnmappableCharacter(
                        CodingErrorAction.REPORT
                    )

            decoder
                .decode(
                    ByteBuffer.wrap(
                        bytes
                    )
                )
                .toString()
        } catch (
            _: CharacterCodingException
        ) {
            String(
                bytes,
                Charset.forName(
                    "GB18030"
                )
            )
        }
    }

    private fun normalizeDateTime(
        raw: String
    ): String? {
        val clean =
            cleanCell(
                raw
            )

        if (
            clean.isBlank()
        ) {
            return null
        }

        clean.toDoubleOrNull()
            ?.takeIf {
                it >
                    30_000.0
            }
            ?.let {
                serial ->
                val whole =
                    serial.toLong()

                val fraction =
                    serial -
                        whole

                val date =
                    LocalDate
                        .of(
                            1899,
                            12,
                            30
                        )
                        .plusDays(
                            whole
                        )

                val seconds =
                    (
                        fraction *
                            86_400.0
                        )
                        .toLong()
                        .coerceIn(
                            0,
                            86_399
                        )

                val time =
                    LocalTime
                        .MIDNIGHT
                        .plusSeconds(
                            seconds
                        )

                return date
                    .atTime(
                        time
                    )
                    .format(
                        OUTPUT_DATE_TIME
                    )
            }

        DATE_TIME_FORMATTERS
            .forEach {
                formatter ->
                try {
                    return LocalDateTime
                        .parse(
                            clean,
                            formatter
                        )
                        .format(
                            OUTPUT_DATE_TIME
                        )
                } catch (
                    _: DateTimeParseException
                ) {
                    // Try next pattern.
                }
            }

        DATE_FORMATTERS
            .forEach {
                formatter ->
                try {
                    return LocalDate
                        .parse(
                            clean,
                            formatter
                        )
                        .atStartOfDay()
                        .format(
                            OUTPUT_DATE_TIME
                        )
                } catch (
                    _: DateTimeParseException
                ) {
                    // Try next pattern.
                }
            }

        return null
    }

    private fun money(
        raw: String
    ): Double {
        val clean =
            cleanCell(
                raw
            )
                .replace(
                    "¥",
                    ""
                )
                .replace(
                    "￥",
                    ""
                )
                .replace(
                    ",",
                    ""
                )
                .replace(
                    "元",
                    ""
                )
                .replace(
                    "+",
                    ""
                )
                .trim()

        if (
            clean.isBlank()
        ) {
            return 0.0
        }

        return clean
            .toDoubleOrNull()
            ?.let {
                kotlin.math.abs(
                    it
                )
            } ?: 0.0
    }

    private fun maskedCustomerLabel(
        platform: PaymentBillPlatform,
        stableIdentity: String
    ): String {
        val compact =
            stableIdentity
                .filter {
                    it.isLetterOrDigit()
                }

        val suffix =
            compact
                .takeLast(
                    4
                )
                .ifBlank {
                    "****"
                }

        return platform.label +
            "客户·" +
            suffix
    }

    private fun cleanCell(
        value: String
    ): String =
        value
            .replace(
                "\uFEFF",
                ""
            )
            .trim()
            .trim(
                '"',
                '\'',
                '`'
            )
            .trim()

    private fun normalizeHeader(
        value: String
    ): String =
        cleanCell(
            value
        )
            .lowercase(
                Locale.ROOT
            )
            .replace(
                "（",
                "("
            )
            .replace(
                "）",
                ")"
            )
            .replace(
                Regex(
                    "[\\s:_：/\\-]"
                ),
                ""
            )

    private fun normalizeIdentity(
        value: String
    ): String =
        cleanCell(
            value
        )
            .lowercase(
                Locale.ROOT
            )
            .replace(
                Regex(
                    "\\s+"
                ),
                ""
            )

    private fun sha256Hex(
        bytes: ByteArray
    ): String =
        MessageDigest
            .getInstance(
                "SHA-256"
            )
            .digest(
                bytes
            )
            .joinToString(
                ""
            ) {
                "%02x".format(
                    it
                )
            }

    private fun isZip(
        bytes: ByteArray
    ): Boolean =
        bytes.size >=
            4 &&
            bytes[0] ==
            'P'.code.toByte() &&
            bytes[1] ==
            'K'.code.toByte()

    private fun roundMoneyLocal(
        value: Double
    ): Double =
        kotlin.math.round(
            value *
                100.0
        ) / 100.0

    private val STRICT_RECEIPT_PHRASES =
        listOf(
            "二维码收款",
            "经营收款",
            "收款码收款",
            "收钱码收款",
            "商家收款",
            "扫码收款",
            "二维码经营收款"
        )

    private val REJECTED_STATUS_TOKENS =
        listOf(
            "失败",
            "关闭",
            "待付款",
            "待支付",
            "未支付",
            "REVOKED",
            "CLOSED",
            "FAIL"
        )

    private val KNOWN_HEADER_WORDS =
        listOf(
            "交易时间",
            "交易类型",
            "交易分类",
            "交易对方",
            "金额",
            "交易状态",
            "微信订单号",
            "支付宝交易号",
            "用户标识",
            "收/支"
        )

    private val KNOWN_HEADER_NORMALIZED =
        KNOWN_HEADER_WORDS
            .flatMap {
                word ->
                listOf(
                    word,
                    when (
                        word
                    ) {
                        "金额" ->
                            "金额(元)"

                        else ->
                            word
                    }
                )
            }
            .map(
                ::normalizeHeader
            )
            .toSet() +
            setOf(
                "订单金额",
                "应结订单金额",
                "微信支付订单号",
                "交易订单号",
                "交易单号",
                "商品名称",
                "商品说明",
                "当前状态",
                "买家信息",
                "付款方"
            )
                .map(
                    ::normalizeHeader
                )

    private val OUTPUT_DATE_TIME =
        DateTimeFormatter
            .ofPattern(
                "yyyy-MM-dd HH:mm:ss"
            )

    private val DATE_TIME_FORMATTERS =
        listOf(
            "yyyy-MM-dd HH:mm:ss",
            "yyyy/MM/dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "yyyy/MM/dd HH:mm",
            "yyyy年MM月dd日 HH:mm:ss",
            "yyyy年MM月dd日 HH:mm"
        )
            .map(
                DateTimeFormatter::ofPattern
            )

    private val DATE_FORMATTERS =
        listOf(
            "yyyy-MM-dd",
            "yyyy/MM/dd",
            "yyyy年MM月dd日"
        )
            .map(
                DateTimeFormatter::ofPattern
            )
}
