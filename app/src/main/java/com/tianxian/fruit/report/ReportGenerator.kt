package com.tianxian.fruit.report

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.ceil

enum class ReportLineStyle {
    TITLE,
    SUBTITLE,
    SECTION,
    NORMAL,
    MUTED,
    TOTAL,
    POSITIVE,
    NEGATIVE,
    WARNING,
    SPACER
}

data class ReportLine(
    val text: String,
    val style: ReportLineStyle = ReportLineStyle.NORMAL,
    val rightText: String = ""
)

data class GeneratedReport(
    val file: File,
    val mimeType: String,
    val displayName: String
)

object ReportGenerator {

    private const val PDF_WIDTH = 595
    private const val PDF_HEIGHT = 842
    private const val PDF_MARGIN = 42f
    private const val PDF_FOOTER_RESERVED = 34f
    private const val PDF_HEADER_BOTTOM = 78f
    private const val PNG_WIDTH = 1080
    private const val PNG_MARGIN = 64f
    private const val PNG_FOOTER_RESERVED = 100
    private const val PNG_HEADER_BOTTOM = 158f
    private const val MAX_PNG_HEIGHT = 18000

    private val BRAND_GREEN = Color.rgb(19, 168, 104)
    private val DARK_GREEN = Color.rgb(10, 112, 72)
    private val SOFT_GREEN = Color.rgb(235, 248, 241)
    private val SOFT_BLUE = Color.rgb(236, 244, 255)
    private val SOFT_ORANGE = Color.rgb(255, 246, 231)
    private val NEGATIVE_RED = Color.rgb(198, 55, 55)
    private val WARNING_ORANGE = Color.rgb(190, 112, 14)
    private val TEXT_DARK = Color.rgb(35, 39, 42)
    private val TEXT_MUTED = Color.rgb(105, 112, 118)
    private val BORDER_LIGHT = Color.rgb(221, 230, 225)

    fun createPdf(
        context: Context,
        baseName: String,
        lines: List<ReportLine>
    ): GeneratedReport {
        require(lines.isNotEmpty()) { "报表没有内容" }

        val file = outputFile(context, baseName, "pdf")
        val generatedAt = nowDisplayText()
        val document = PdfDocument()

        try {
            var pageNumber = 1
            var page = startPdfPage(document, pageNumber)
            var canvas = page.canvas
            drawPdfBrandHeader(canvas)
            var y = PDF_HEADER_BOTTOM

            fun finishCurrentPage() {
                drawPdfFooter(canvas, pageNumber, generatedAt)
                document.finishPage(page)
            }

            lines.forEach { line ->
                if (line.style == ReportLineStyle.SPACER) {
                    y += 12f
                    return@forEach
                }

                if (line.style == ReportLineStyle.TITLE && line.text == "天鲜果业") {
                    return@forEach
                }

                val paint = pdfPaint(line.style)
                val maxWidth = PDF_WIDTH - PDF_MARGIN * 2
                val rightWidth =
                    if (line.rightText.isBlank()) 0f else paint.measureText(line.rightText)
                val columnGap = if (rightWidth > 0f) 16f else 0f
                val leftWidth =
                    (maxWidth - rightWidth - columnGap)
                        .coerceAtLeast(maxWidth * 0.48f)
                val wrapped = wrapText(line.text, paint, leftWidth)
                val lineHeight = pdfLineHeight(line.style)
                val blockHeight = wrapped.size * lineHeight
                val needed = blockHeight + 7f

                if (y + needed > PDF_HEIGHT - PDF_MARGIN - PDF_FOOTER_RESERVED) {
                    finishCurrentPage()
                    pageNumber += 1
                    page = startPdfPage(document, pageNumber)
                    canvas = page.canvas
                    drawPdfBrandHeader(canvas)
                    y = PDF_HEADER_BOTTOM
                }

                drawPdfBlockBackground(
                    canvas = canvas,
                    style = line.style,
                    topBaseline = y,
                    blockHeight = blockHeight,
                    lineHeight = lineHeight
                )

                val rightPaint = Paint(paint).apply { textAlign = Paint.Align.RIGHT }
                wrapped.forEachIndexed { index, text ->
                    canvas.drawText(text, PDF_MARGIN, y, paint)
                    if (index == 0 && line.rightText.isNotBlank()) {
                        canvas.drawText(
                            line.rightText,
                            PDF_WIDTH - PDF_MARGIN,
                            y,
                            rightPaint
                        )
                    }
                    y += lineHeight
                }

                y += when (line.style) {
                    ReportLineStyle.TITLE -> 8f
                    ReportLineStyle.SUBTITLE,
                    ReportLineStyle.SECTION -> 6f
                    ReportLineStyle.TOTAL -> 4f
                    else -> 2f
                }
            }

            finishCurrentPage()
            FileOutputStream(file).use { document.writeTo(it) }
        } finally {
            document.close()
        }

        return GeneratedReport(
            file = file,
            mimeType = "application/pdf",
            displayName = file.name
        )
    }

    fun createPng(
        context: Context,
        baseName: String,
        lines: List<ReportLine>
    ): GeneratedReport {
        require(lines.isNotEmpty()) { "报表没有内容" }

        val measured = measurePng(lines) + PNG_FOOTER_RESERVED
        if (measured > MAX_PNG_HEIGHT) {
            throw IllegalStateException("报表内容较多，图片会过长，请使用 PDF 分享")
        }

        val height = measured.coerceAtLeast(900)
        val bitmap = Bitmap.createBitmap(
            PNG_WIDTH,
            height,
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        drawPngBrandHeader(canvas)

        var y = PNG_HEADER_BOTTOM
        lines.forEach { line ->
            if (line.style == ReportLineStyle.SPACER) {
                y += 24f
                return@forEach
            }

            if (line.style == ReportLineStyle.TITLE && line.text == "天鲜果业") {
                return@forEach
            }

            val paint = pngPaint(line.style)
            val maxWidth = PNG_WIDTH - PNG_MARGIN * 2
            val rightWidth =
                if (line.rightText.isBlank()) 0f else paint.measureText(line.rightText)
            val columnGap = if (rightWidth > 0f) 34f else 0f
            val leftWidth =
                (maxWidth - rightWidth - columnGap)
                    .coerceAtLeast(maxWidth * 0.48f)
            val wrapped = wrapText(line.text, paint, leftWidth)
            val lineHeight = pngLineHeight(line.style)
            val blockHeight = wrapped.size * lineHeight

            drawPngBlockBackground(
                canvas = canvas,
                style = line.style,
                topBaseline = y,
                blockHeight = blockHeight,
                lineHeight = lineHeight
            )

            val rightPaint = Paint(paint).apply { textAlign = Paint.Align.RIGHT }
            wrapped.forEachIndexed { index, text ->
                canvas.drawText(text, PNG_MARGIN, y, paint)
                if (index == 0 && line.rightText.isNotBlank()) {
                    canvas.drawText(
                        line.rightText,
                        PNG_WIDTH - PNG_MARGIN,
                        y,
                        rightPaint
                    )
                }
                y += lineHeight
            }

            y += when (line.style) {
                ReportLineStyle.TITLE -> 16f
                ReportLineStyle.SUBTITLE,
                ReportLineStyle.SECTION -> 12f
                ReportLineStyle.TOTAL -> 9f
                else -> 5f
            }
        }

        drawPngFooter(canvas, height, nowDisplayText())

        val file = outputFile(context, baseName, "png")
        FileOutputStream(file).use {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) {
                throw IllegalStateException("图片写入失败")
            }
        }
        bitmap.recycle()

        return GeneratedReport(
            file = file,
            mimeType = "image/png",
            displayName = file.name
        )
    }

    fun share(
        context: Context,
        report: GeneratedReport
    ) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            report.file
        )

        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = report.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooser = Intent.createChooser(sendIntent, "分享报表").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    fun shareText(
        context: Context,
        text: String,
        title: String = "分享结算"
    ) {
        require(text.isNotBlank()) { "分享内容为空" }
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val chooser = Intent.createChooser(sendIntent, title).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    fun copyToUri(
        context: Context,
        report: GeneratedReport,
        uri: Uri
    ) {
        context.contentResolver.openOutputStream(uri)?.use { output ->
            report.file.inputStream().use { input -> input.copyTo(output) }
        } ?: throw IllegalStateException("无法打开保存位置")
    }

    fun saveTextToDownloads(
        context: Context,
        displayName: String,
        content: String,
        mimeType: String = "application/json"
    ): String {
        require(displayName.isNotBlank()) { "备份文件名为空" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val relativePath = Environment.DIRECTORY_DOWNLOADS + "/TianXianFruit/Backup"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                values
            ) ?: throw IllegalStateException("无法创建备份文件")
            try {
                resolver.openOutputStream(uri, "w")?.bufferedWriter(Charsets.UTF_8)?.use {
                    it.write(content)
                    it.flush()
                } ?: throw IllegalStateException("无法写入备份文件")
                resolver.update(
                    uri,
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    },
                    null,
                    null
                )
                return "$relativePath/$displayName"
            } catch (error: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                throw error
            }
        }

        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context.filesDir
        val targetDir = File(base, "TianXianFruit/Backup").apply {
            if (!exists() && !mkdirs()) {
                throw IllegalStateException("无法创建备份目录")
            }
        }
        val target = File(targetDir, displayName)
        target.bufferedWriter(Charsets.UTF_8).use {
            it.write(content)
        }
        return target.absolutePath
    }

    fun saveToDevice(
        context: Context,
        report: GeneratedReport
    ): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val isImage = report.mimeType == "image/png"
            val collection = if (isImage) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
            val relativePath = if (isImage) {
                Environment.DIRECTORY_PICTURES + "/TianXianFruit"
            } else {
                Environment.DIRECTORY_DOWNLOADS + "/TianXianFruit"
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, report.displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, report.mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values)
                ?: throw IllegalStateException("无法创建保存文件")

            try {
                resolver.openOutputStream(uri, "w")?.use { output ->
                    report.file.inputStream().use { input -> input.copyTo(output) }
                } ?: throw IllegalStateException("无法打开保存位置")

                resolver.update(
                    uri,
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    },
                    null,
                    null
                )
                return "$relativePath/${report.displayName}"
            } catch (error: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                throw error
            }
        }

        val directoryType = if (report.mimeType == "image/png") {
            Environment.DIRECTORY_PICTURES
        } else {
            Environment.DIRECTORY_DOWNLOADS
        }
        val base = context.getExternalFilesDir(directoryType) ?: context.filesDir
        val targetDir = File(base, "TianXianFruit").apply {
            if (!exists() && !mkdirs()) {
                throw IllegalStateException("无法创建保存目录")
            }
        }
        val target = File(targetDir, report.displayName)
        report.file.inputStream().use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output) }
        }
        return target.absolutePath
    }

    private fun startPdfPage(
        document: PdfDocument,
        pageNumber: Int
    ): PdfDocument.Page =
        document.startPage(
            PdfDocument.PageInfo.Builder(PDF_WIDTH, PDF_HEIGHT, pageNumber).create()
        )

    private fun drawPdfBrandHeader(canvas: Canvas) {
        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BRAND_GREEN }
        canvas.drawRoundRect(
            PDF_MARGIN,
            16f,
            PDF_WIDTH - PDF_MARGIN,
            61f,
            10f,
            10f,
            background
        )
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 22f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        canvas.drawText("天鲜果业", PDF_MARGIN + 18f, 46f, textPaint)
    }

    private fun drawPngBrandHeader(canvas: Canvas) {
        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BRAND_GREEN }
        canvas.drawRoundRect(
            PNG_MARGIN,
            24f,
            PNG_WIDTH - PNG_MARGIN,
            132f,
            26f,
            26f,
            background
        )
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 54f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        canvas.drawText("天鲜果业", PNG_MARGIN + 34f, 94f, textPaint)
    }

    private fun drawPdfBlockBackground(
        canvas: Canvas,
        style: ReportLineStyle,
        topBaseline: Float,
        blockHeight: Float,
        lineHeight: Float
    ) {
        val background = styleBackground(style) ?: return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = background }
        val top = topBaseline - lineHeight * 0.78f
        val bottom = topBaseline + blockHeight - lineHeight * 0.1f
        canvas.drawRoundRect(
            PDF_MARGIN - 8f,
            top,
            PDF_WIDTH - PDF_MARGIN + 8f,
            bottom,
            7f,
            7f,
            paint
        )
    }

    private fun drawPngBlockBackground(
        canvas: Canvas,
        style: ReportLineStyle,
        topBaseline: Float,
        blockHeight: Float,
        lineHeight: Float
    ) {
        val background = styleBackground(style) ?: return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = background }
        val top = topBaseline - lineHeight * 0.8f
        val bottom = topBaseline + blockHeight - lineHeight * 0.08f
        canvas.drawRoundRect(
            PNG_MARGIN - 18f,
            top,
            PNG_WIDTH - PNG_MARGIN + 18f,
            bottom,
            18f,
            18f,
            paint
        )
    }

    private fun styleBackground(style: ReportLineStyle): Int? =
        when (style) {
            ReportLineStyle.SECTION -> SOFT_GREEN
            ReportLineStyle.TOTAL -> SOFT_BLUE
            ReportLineStyle.WARNING -> SOFT_ORANGE
            else -> null
        }

    private fun drawPdfFooter(
        canvas: Canvas,
        pageNumber: Int,
        generatedAt: String
    ) {
        val divider = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = BORDER_LIGHT
            strokeWidth = 1f
        }
        canvas.drawLine(
            PDF_MARGIN,
            PDF_HEIGHT - 28f,
            PDF_WIDTH - PDF_MARGIN,
            PDF_HEIGHT - 28f,
            divider
        )

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT_MUTED
            textSize = 8.5f
            textAlign = Paint.Align.RIGHT
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        canvas.drawText(
            "生成时间：$generatedAt  ·  第${pageNumber}页",
            PDF_WIDTH - PDF_MARGIN,
            PDF_HEIGHT - 12f,
            paint
        )
    }

    private fun drawPngFooter(
        canvas: Canvas,
        height: Int,
        generatedAt: String
    ) {
        val divider = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = BORDER_LIGHT
            strokeWidth = 2f
        }
        canvas.drawLine(
            PNG_MARGIN,
            height - 72f,
            PNG_WIDTH - PNG_MARGIN,
            height - 72f,
            divider
        )
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT_MUTED
            textSize = 24f
            textAlign = Paint.Align.RIGHT
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        canvas.drawText(
            "生成时间：$generatedAt",
            PNG_WIDTH - PNG_MARGIN,
            height - 30f,
            paint
        )
    }

    private fun outputFile(
        context: Context,
        baseName: String,
        extension: String
    ): File {
        val dir = File(context.cacheDir, "reports")
        if (!dir.exists() && !dir.mkdirs()) {
            throw IllegalStateException("无法创建报表缓存目录")
        }

        val safeBase = baseName
            .replace(Regex("""[\\/:*?\"<>|]"""), "_")
            .take(80)
        val time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        return File(dir, "${safeBase}_$time.$extension")
    }

    private fun pdfPaint(style: ReportLineStyle): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = styleTextColor(style)
            textSize = when (style) {
                ReportLineStyle.TITLE -> 24f
                ReportLineStyle.SUBTITLE -> 18f
                ReportLineStyle.SECTION,
                ReportLineStyle.TOTAL -> 13.5f
                ReportLineStyle.POSITIVE,
                ReportLineStyle.NEGATIVE,
                ReportLineStyle.WARNING -> 12f
                ReportLineStyle.MUTED -> 10.5f
                else -> 11.5f
            }
            typeface = Typeface.create(
                "sans-serif",
                when (style) {
                    ReportLineStyle.TITLE,
                    ReportLineStyle.SUBTITLE,
                    ReportLineStyle.SECTION,
                    ReportLineStyle.TOTAL,
                    ReportLineStyle.POSITIVE,
                    ReportLineStyle.NEGATIVE,
                    ReportLineStyle.WARNING -> Typeface.BOLD
                    else -> Typeface.NORMAL
                }
            )
        }

    private fun pngPaint(style: ReportLineStyle): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = styleTextColor(style)
            textSize = when (style) {
                ReportLineStyle.TITLE -> 54f
                ReportLineStyle.SUBTITLE -> 40f
                ReportLineStyle.SECTION,
                ReportLineStyle.TOTAL -> 32f
                ReportLineStyle.POSITIVE,
                ReportLineStyle.NEGATIVE,
                ReportLineStyle.WARNING -> 30f
                ReportLineStyle.MUTED -> 25f
                else -> 28f
            }
            typeface = Typeface.create(
                "sans-serif",
                when (style) {
                    ReportLineStyle.TITLE,
                    ReportLineStyle.SUBTITLE,
                    ReportLineStyle.SECTION,
                    ReportLineStyle.TOTAL,
                    ReportLineStyle.POSITIVE,
                    ReportLineStyle.NEGATIVE,
                    ReportLineStyle.WARNING -> Typeface.BOLD
                    else -> Typeface.NORMAL
                }
            )
        }

    private fun styleTextColor(style: ReportLineStyle): Int =
        when (style) {
            ReportLineStyle.TITLE -> BRAND_GREEN
            ReportLineStyle.SUBTITLE,
            ReportLineStyle.SECTION,
            ReportLineStyle.TOTAL -> DARK_GREEN
            ReportLineStyle.POSITIVE -> BRAND_GREEN
            ReportLineStyle.NEGATIVE -> NEGATIVE_RED
            ReportLineStyle.WARNING -> WARNING_ORANGE
            ReportLineStyle.MUTED -> TEXT_MUTED
            else -> TEXT_DARK
        }

    private fun pdfLineHeight(style: ReportLineStyle): Float =
        when (style) {
            ReportLineStyle.TITLE -> 31f
            ReportLineStyle.SUBTITLE -> 25f
            ReportLineStyle.SECTION,
            ReportLineStyle.TOTAL -> 20f
            else -> 17f
        }

    private fun pngLineHeight(style: ReportLineStyle): Float =
        when (style) {
            ReportLineStyle.TITLE -> 70f
            ReportLineStyle.SUBTITLE -> 56f
            ReportLineStyle.SECTION,
            ReportLineStyle.TOTAL -> 46f
            else -> 40f
        }

    private fun measurePng(lines: List<ReportLine>): Int {
        var height = PNG_HEADER_BOTTOM.toInt() + 16
        lines.forEach { line ->
            if (line.style == ReportLineStyle.TITLE && line.text == "天鲜果业") {
                return@forEach
            }
            if (line.style == ReportLineStyle.SPACER) {
                height += 24
            } else {
                val paint = pngPaint(line.style)
                val maxWidth = PNG_WIDTH - PNG_MARGIN * 2
                val rightWidth =
                    if (line.rightText.isBlank()) 0f else paint.measureText(line.rightText)
                val columnGap = if (rightWidth > 0f) 34f else 0f
                val leftWidth =
                    (maxWidth - rightWidth - columnGap)
                        .coerceAtLeast(maxWidth * 0.48f)
                val wrapped = wrapText(line.text, paint, leftWidth)
                height += ceil(
                    wrapped.size * pngLineHeight(line.style) + 16
                ).toInt()
            }
        }
        height += PNG_MARGIN.toInt()
        return height
    }

    private fun wrapText(
        text: String,
        paint: Paint,
        maxWidth: Float
    ): List<String> {
        if (text.isBlank()) return listOf("")

        val result = mutableListOf<String>()
        val current = StringBuilder()
        text.forEach { ch ->
            val candidate = current.toString() + ch
            if (current.isNotEmpty() && paint.measureText(candidate) > maxWidth) {
                result += current.toString()
                current.clear()
            }
            current.append(ch)
        }
        if (current.isNotEmpty()) result += current.toString()
        return if (result.isEmpty()) listOf(text) else result
    }

    private fun nowDisplayText(): String =
        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
}
