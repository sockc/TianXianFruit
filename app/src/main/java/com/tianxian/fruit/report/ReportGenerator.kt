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
    SPACER
}

data class ReportLine(
    val text: String,
    val style: ReportLineStyle =
        ReportLineStyle.NORMAL
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
    private const val PNG_WIDTH = 1080
    private const val PNG_MARGIN = 64f
    private const val MAX_PNG_HEIGHT = 18000

    fun createPdf(
        context: Context,
        baseName: String,
        lines: List<ReportLine>
    ): GeneratedReport {
        require(lines.isNotEmpty()) {
            "报表没有内容"
        }

        val file =
            outputFile(
                context,
                baseName,
                "pdf"
            )

        val document = PdfDocument()

        try {
            var pageNumber = 1
            var page =
                document.startPage(
                    PdfDocument.PageInfo
                        .Builder(
                            PDF_WIDTH,
                            PDF_HEIGHT,
                            pageNumber
                        )
                        .create()
                )
            var canvas = page.canvas
            var y = PDF_MARGIN

            lines.forEach {
                line ->
                if (
                    line.style ==
                    ReportLineStyle.SPACER
                ) {
                    y += 12f
                    return@forEach
                }

                val paint =
                    pdfPaint(line.style)

                val wrapped =
                    wrapText(
                        line.text,
                        paint,
                        PDF_WIDTH -
                            PDF_MARGIN * 2
                    )

                val lineHeight =
                    pdfLineHeight(
                        line.style
                    )

                val needed =
                    wrapped.size *
                        lineHeight +
                        5f

                if (
                    y + needed >
                    PDF_HEIGHT -
                        PDF_MARGIN
                ) {
                    document.finishPage(page)
                    pageNumber += 1
                    page =
                        document.startPage(
                            PdfDocument.PageInfo
                                .Builder(
                                    PDF_WIDTH,
                                    PDF_HEIGHT,
                                    pageNumber
                                )
                                .create()
                        )
                    canvas = page.canvas
                    y = PDF_MARGIN
                }

                wrapped.forEach {
                    text ->
                    canvas.drawText(
                        text,
                        PDF_MARGIN,
                        y,
                        paint
                    )
                    y += lineHeight
                }

                y +=
                    when (line.style) {
                        ReportLineStyle.TITLE ->
                            7f

                        ReportLineStyle.SUBTITLE,
                        ReportLineStyle.SECTION ->
                            5f

                        else ->
                            2f
                    }
            }

            document.finishPage(page)

            FileOutputStream(file).use {
                document.writeTo(it)
            }
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
        require(lines.isNotEmpty()) {
            "报表没有内容"
        }

        val measured =
            measurePng(lines)

        if (
            measured >
            MAX_PNG_HEIGHT
        ) {
            throw IllegalStateException(
                "报表内容较多，图片会过长，请使用 PDF 分享"
            )
        }

        val height =
            measured.coerceAtLeast(800)

        val bitmap =
            Bitmap.createBitmap(
                PNG_WIDTH,
                height,
                Bitmap.Config.ARGB_8888
            )
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        var y = PNG_MARGIN

        lines.forEach {
            line ->
            if (
                line.style ==
                ReportLineStyle.SPACER
            ) {
                y += 24f
                return@forEach
            }

            val paint =
                pngPaint(line.style)

            val wrapped =
                wrapText(
                    line.text,
                    paint,
                    PNG_WIDTH -
                        PNG_MARGIN * 2
                )

            val lineHeight =
                pngLineHeight(
                    line.style
                )

            wrapped.forEach {
                text ->
                canvas.drawText(
                    text,
                    PNG_MARGIN,
                    y,
                    paint
                )
                y += lineHeight
            }

            y +=
                when (line.style) {
                    ReportLineStyle.TITLE ->
                        14f

                    ReportLineStyle.SUBTITLE,
                    ReportLineStyle.SECTION ->
                        10f

                    else ->
                        5f
                }
        }

        val file =
            outputFile(
                context,
                baseName,
                "png"
            )

        FileOutputStream(file).use {
            if (
                !bitmap.compress(
                    Bitmap.CompressFormat.PNG,
                    100,
                    it
                )
            ) {
                throw IllegalStateException(
                    "图片写入失败"
                )
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
        val uri =
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                report.file
            )

        val sendIntent =
            Intent(Intent.ACTION_SEND).apply {
                type = report.mimeType
                putExtra(
                    Intent.EXTRA_STREAM,
                    uri
                )
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }

        val chooser =
            Intent.createChooser(
                sendIntent,
                "分享报表"
            ).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                )
            }

        context.startActivity(chooser)
    }

    fun copyToUri(
        context: Context,
        report: GeneratedReport,
        uri: Uri
    ) {
        context.contentResolver
            .openOutputStream(uri)
            ?.use {
                output ->
                report.file
                    .inputStream()
                    .use {
                        input ->
                        input.copyTo(output)
                    }
            }
            ?: throw IllegalStateException(
                "无法打开保存位置"
            )
    }

    fun saveToDevice(
        context: Context,
        report: GeneratedReport
    ): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val isImage = report.mimeType == "image/png"
            val collection =
                if (isImage) {
                    MediaStore.Images.Media.getContentUri(
                        MediaStore.VOLUME_EXTERNAL_PRIMARY
                    )
                } else {
                    MediaStore.Downloads.getContentUri(
                        MediaStore.VOLUME_EXTERNAL_PRIMARY
                    )
                }
            val relativePath =
                if (isImage) {
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
                resolver.openOutputStream(uri, "w")
                    ?.use { output ->
                        report.file.inputStream().use { input ->
                            input.copyTo(output)
                        }
                    }
                    ?: throw IllegalStateException("无法打开保存位置")

                resolver.update(
                    uri,
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    },
                    null,
                    null
                )
                return relativePath + "/" + report.displayName
            } catch (error: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                throw error
            }
        }

        // Android 8/9 不申请旧式外部存储权限，保存到应用专属外部目录。
        val directoryType =
            if (report.mimeType == "image/png") {
                Environment.DIRECTORY_PICTURES
            } else {
                Environment.DIRECTORY_DOWNLOADS
            }
        val base =
            context.getExternalFilesDir(directoryType)
                ?: context.filesDir
        val targetDir = File(base, "TianXianFruit").apply {
            if (!exists() && !mkdirs()) {
                throw IllegalStateException("无法创建保存目录")
            }
        }
        val target = File(targetDir, report.displayName)
        report.file.inputStream().use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output)
            }
        }
        return target.absolutePath
    }

    private fun outputFile(
        context: Context,
        baseName: String,
        extension: String
    ): File {
        val dir =
            File(
                context.cacheDir,
                "reports"
            )

        if (!dir.exists()) {
            dir.mkdirs()
        }

        val safeBase =
            baseName
                .replace(
                    Regex(
                        """[\\/:*?"<>|]"""
                    ),
                    "_"
                )
                .take(80)

        val time =
            LocalDateTime.now()
                .format(
                    DateTimeFormatter.ofPattern(
                        "yyyyMMdd_HHmmss"
                    )
                )

        return File(
            dir,
            "${safeBase}_$time.$extension"
        )
    }

    private fun pdfPaint(
        style: ReportLineStyle
    ): Paint =
        Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {
            color =
                when (style) {
                    ReportLineStyle.MUTED ->
                        Color.DKGRAY

                    else ->
                        Color.BLACK
                }

            textSize =
                when (style) {
                    ReportLineStyle.TITLE ->
                        24f

                    ReportLineStyle.SUBTITLE ->
                        18f

                    ReportLineStyle.SECTION,
                    ReportLineStyle.TOTAL ->
                        13.5f

                    ReportLineStyle.MUTED ->
                        10.5f

                    else ->
                        11.5f
                }

            typeface =
                Typeface.create(
                    "sans-serif",
                    when (style) {
                        ReportLineStyle.TITLE,
                        ReportLineStyle.SUBTITLE,
                        ReportLineStyle.SECTION,
                        ReportLineStyle.TOTAL ->
                            Typeface.BOLD

                        else ->
                            Typeface.NORMAL
                    }
                )
        }

    private fun pngPaint(
        style: ReportLineStyle
    ): Paint =
        Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {
            color =
                when (style) {
                    ReportLineStyle.MUTED ->
                        Color.DKGRAY

                    else ->
                        Color.BLACK
                }

            textSize =
                when (style) {
                    ReportLineStyle.TITLE ->
                        54f

                    ReportLineStyle.SUBTITLE ->
                        40f

                    ReportLineStyle.SECTION,
                    ReportLineStyle.TOTAL ->
                        32f

                    ReportLineStyle.MUTED ->
                        25f

                    else ->
                        28f
                }

            typeface =
                Typeface.create(
                    "sans-serif",
                    when (style) {
                        ReportLineStyle.TITLE,
                        ReportLineStyle.SUBTITLE,
                        ReportLineStyle.SECTION,
                        ReportLineStyle.TOTAL ->
                            Typeface.BOLD

                        else ->
                            Typeface.NORMAL
                    }
                )
        }

    private fun pdfLineHeight(
        style: ReportLineStyle
    ): Float =
        when (style) {
            ReportLineStyle.TITLE ->
                31f

            ReportLineStyle.SUBTITLE ->
                25f

            ReportLineStyle.SECTION,
            ReportLineStyle.TOTAL ->
                20f

            else ->
                17f
        }

    private fun pngLineHeight(
        style: ReportLineStyle
    ): Float =
        when (style) {
            ReportLineStyle.TITLE ->
                70f

            ReportLineStyle.SUBTITLE ->
                56f

            ReportLineStyle.SECTION,
            ReportLineStyle.TOTAL ->
                46f

            else ->
                40f
        }

    private fun measurePng(
        lines: List<ReportLine>
    ): Int {
        var height =
            PNG_MARGIN.toInt()

        lines.forEach {
            line ->
            if (
                line.style ==
                ReportLineStyle.SPACER
            ) {
                height += 24
            } else {
                val paint =
                    pngPaint(
                        line.style
                    )
                val wrapped =
                    wrapText(
                        line.text,
                        paint,
                        PNG_WIDTH -
                            PNG_MARGIN * 2
                    )

                height +=
                    ceil(
                        wrapped.size *
                            pngLineHeight(
                                line.style
                            ) +
                            12
                    ).toInt()
            }
        }

        height +=
            PNG_MARGIN.toInt()

        return height
    }

    private fun wrapText(
        text: String,
        paint: Paint,
        maxWidth: Float
    ): List<String> {
        if (text.isBlank()) {
            return listOf("")
        }

        val result =
            mutableListOf<String>()
        val current =
            StringBuilder()

        text.forEach {
            ch ->
            val candidate =
                current.toString() + ch

            if (
                current.isNotEmpty() &&
                paint.measureText(
                    candidate
                ) > maxWidth
            ) {
                result +=
                    current.toString()
                current.clear()
            }

            current.append(ch)
        }

        if (current.isNotEmpty()) {
            result +=
                current.toString()
        }

        return if (
            result.isEmpty()
        ) {
            listOf(text)
        } else {
            result
        }
    }
}
