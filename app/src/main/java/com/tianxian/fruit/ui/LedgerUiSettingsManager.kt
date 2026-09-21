package com.tianxian.fruit.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

data class LedgerHeaderSettings(
    val title: String = "天鲜果业",
    val subtitle: String =
        "每一天努力，收获更甜的生活",
    val tagline: String =
        "新鲜水果 · 从这里开始！",
    val showFruitIcons: Boolean = true,
    val backgroundImagePath: String = ""
)

class LedgerUiSettingsManager(
    context: Context
) {
    private val appContext =
        context.applicationContext

    private val prefs =
        appContext.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

    private val imageDir =
        File(
            appContext.filesDir,
            "ledger_header"
        ).apply {
            mkdirs()
        }

    fun load(
        ledgerId: String
    ): LedgerHeaderSettings {
        val prefix =
            keyPrefix(ledgerId)

        return LedgerHeaderSettings(
            title =
                prefs.getString(
                    "${prefix}_title",
                    DEFAULT_TITLE
                )
                    .orEmpty()
                    .ifBlank {
                        DEFAULT_TITLE
                    },
            subtitle =
                prefs.getString(
                    "${prefix}_subtitle",
                    DEFAULT_SUBTITLE
                )
                    .orEmpty(),
            tagline =
                prefs.getString(
                    "${prefix}_tagline",
                    DEFAULT_TAGLINE
                )
                    .orEmpty(),
            showFruitIcons =
                prefs.getBoolean(
                    "${prefix}_fruit_icons",
                    true
                ),
            backgroundImagePath =
                prefs.getString(
                    "${prefix}_background",
                    ""
                )
                    .orEmpty()
                    .takeIf {
                        path ->
                        path.isNotBlank() &&
                            File(path)
                                .exists()
                    }
                    .orEmpty()
        )
    }

    fun save(
        ledgerId: String,
        settings: LedgerHeaderSettings
    ) {
        val prefix =
            keyPrefix(ledgerId)

        prefs.edit()
            .putString(
                "${prefix}_title",
                settings.title
                    .trim()
                    .ifBlank {
                        DEFAULT_TITLE
                    }
            )
            .putString(
                "${prefix}_subtitle",
                settings.subtitle
                    .trim()
            )
            .putString(
                "${prefix}_tagline",
                settings.tagline
                    .trim()
            )
            .putBoolean(
                "${prefix}_fruit_icons",
                settings.showFruitIcons
            )
            .putString(
                "${prefix}_background",
                settings.backgroundImagePath
            )
            .apply()
    }

    fun loadHomeQuickActions(
        ledgerId: String
    ): List<String> {
        val prefix = keyPrefix(ledgerId)
        val raw = prefs.getString(
            "${prefix}_home_quick_actions",
            DEFAULT_HOME_QUICK_ACTIONS
        ).orEmpty()

        return raw.split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(8)
    }

    fun saveHomeQuickActions(
        ledgerId: String,
        actionKeys: List<String>
    ) {
        val prefix = keyPrefix(ledgerId)
        prefs.edit()
            .putString(
                "${prefix}_home_quick_actions",
                actionKeys
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .take(8)
                    .joinToString(",")
            )
            .apply()
    }

    fun saveBackgroundImage(
        ledgerId: String,
        source: Uri
    ): Result<String> =
        runCatching {
            val resolver =
                appContext
                    .contentResolver

            val original =
                resolver.openInputStream(
                    source
                )?.use {
                    stream ->
                    BitmapFactory
                        .decodeStream(
                            stream
                        )
                }
                    ?: throw IllegalArgumentException(
                        "无法读取所选图片"
                    )

            val scaled =
                scaleDown(
                    original,
                    MAX_IMAGE_WIDTH,
                    MAX_IMAGE_HEIGHT
                )

            val output =
                File(
                    imageDir,
                    "header_" +
                        safeId(ledgerId) +
                        ".jpg"
                )

            FileOutputStream(
                output
            ).use {
                stream ->
                if (
                    !scaled.compress(
                        Bitmap.CompressFormat.JPEG,
                        JPEG_QUALITY,
                        stream
                    )
                ) {
                    throw IllegalStateException(
                        "保存背景图片失败"
                    )
                }
            }

            if (
                scaled !== original
            ) {
                original.recycle()
            }
            scaled.recycle()

            val current =
                load(ledgerId)

            save(
                ledgerId,
                current.copy(
                    backgroundImagePath =
                        output.absolutePath
                )
            )

            output.absolutePath
        }

    fun removeBackgroundImage(
        ledgerId: String
    ) {
        val current =
            load(ledgerId)

        if (
            current.backgroundImagePath
                .isNotBlank()
        ) {
            runCatching {
                File(
                    current.backgroundImagePath
                ).delete()
            }
        }

        save(
            ledgerId,
            current.copy(
                backgroundImagePath = ""
            )
        )
    }

    fun reset(
        ledgerId: String
    ) {
        removeBackgroundImage(
            ledgerId
        )

        val prefix =
            keyPrefix(ledgerId)

        prefs.edit()
            .remove(
                "${prefix}_title"
            )
            .remove(
                "${prefix}_subtitle"
            )
            .remove(
                "${prefix}_tagline"
            )
            .remove(
                "${prefix}_fruit_icons"
            )
            .remove(
                "${prefix}_background"
            )
            .remove(
                "${prefix}_home_quick_actions"
            )
            .apply()
    }

    private fun scaleDown(
        bitmap: Bitmap,
        maxWidth: Int,
        maxHeight: Int
    ): Bitmap {
        if (
            bitmap.width <=
                maxWidth &&
            bitmap.height <=
                maxHeight
        ) {
            return bitmap
        }

        val widthRatio =
            maxWidth.toFloat() /
                bitmap.width

        val heightRatio =
            maxHeight.toFloat() /
                bitmap.height

        val ratio =
            minOf(
                widthRatio,
                heightRatio
            )

        val width =
            (
                bitmap.width *
                    ratio
                )
                .toInt()
                .coerceAtLeast(1)

        val height =
            (
                bitmap.height *
                    ratio
                )
                .toInt()
                .coerceAtLeast(1)

        return Bitmap.createScaledBitmap(
            bitmap,
            width,
            height,
            true
        )
    }

    private fun keyPrefix(
        ledgerId: String
    ): String =
        "ledger_" +
            safeId(
                ledgerId
            )

    private fun safeId(
        value: String
    ): String =
        value.replace(
            Regex(
                "[^A-Za-z0-9_-]"
            ),
            "_"
        )

    companion object {
        private const val PREFS_NAME =
            "tianxian_ledger_ui"

        private const val DEFAULT_TITLE =
            "天鲜果业"

        private const val DEFAULT_SUBTITLE =
            "每一天努力，收获更甜的生活"

        private const val DEFAULT_TAGLINE =
            "新鲜水果 · 从这里开始！"

        private const val DEFAULT_HOME_QUICK_ACTIONS =
            "STATS,PROFIT,REPORT,HISTORY"

        private const val MAX_IMAGE_WIDTH =
            1920

        private const val MAX_IMAGE_HEIGHT =
            1080

        private const val JPEG_QUALITY =
            88
    }
}
