package com.tianxian.fruit.update

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.tianxian.fruit.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

data class AppUpdateInfo(
    val tagName: String,
    val versionName: String,
    val releaseUrl: String,
    val apkUrl: String,
    val releaseNotes: String,
    val publishedAt: String
)

data class AppUpdateCheckResult(
    val update: AppUpdateInfo?,
    val latestVersionName: String,
    val message: String
)

class AppUpdateManager(
    context: Context
) {
    private val appContext =
        context.applicationContext

    private val prefs =
        appContext.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

    private val mainHandler =
        Handler(
            Looper.getMainLooper()
        )

    private val running =
        AtomicBoolean(false)

    fun checkIfDue(
        callback:
            (Result<AppUpdateCheckResult>) ->
                Unit
    ): Boolean {
        val last =
            prefs.getLong(
                KEY_LAST_AUTO_CHECK,
                0L
            )

        if (
            System.currentTimeMillis() -
            last <
            AUTO_CHECK_INTERVAL_MS
        ) {
            return false
        }

        return checkLatest(
            manual = false,
            callback = callback
        )
    }

    fun checkNow(
        callback:
            (Result<AppUpdateCheckResult>) ->
                Unit
    ): Boolean =
        checkLatest(
            manual = true,
            callback = callback
        )

    private fun checkLatest(
        manual: Boolean,
        callback:
            (Result<AppUpdateCheckResult>) ->
                Unit
    ): Boolean {
        if (
            !running.compareAndSet(
                false,
                true
            )
        ) {
            if (manual) {
                mainHandler.post {
                    callback(
                        Result.failure(
                            IllegalStateException(
                                "正在检查更新，请稍候"
                            )
                        )
                    )
                }
            }
            return false
        }

        Thread {
            val result =
                runCatching {
                    fetchLatestRelease()
                }

            if (result.isSuccess) {
                prefs.edit()
                    .putLong(
                        KEY_LAST_AUTO_CHECK,
                        System.currentTimeMillis()
                    )
                    .apply()
            }

            running.set(false)

            mainHandler.post {
                callback(result)
            }
        }.apply {
            name =
                "TianXian-UpdateCheck"
            isDaemon = true
        }.start()

        return true
    }

    private fun fetchLatestRelease():
        AppUpdateCheckResult {
        val connection =
            URL(API_URL)
                .openConnection()
                as HttpURLConnection

        try {
            connection.requestMethod =
                "GET"
            connection.connectTimeout =
                CONNECT_TIMEOUT_MS
            connection.readTimeout =
                READ_TIMEOUT_MS
            connection.setRequestProperty(
                "Accept",
                "application/vnd.github+json"
            )
            connection.setRequestProperty(
                "User-Agent",
                "TianXianFruit/" +
                    BuildConfig.VERSION_NAME
            )
            connection.setRequestProperty(
                "X-GitHub-Api-Version",
                "2022-11-28"
            )

            val code =
                connection.responseCode

            if (code !in 200..299) {
                val detail =
                    runCatching {
                        connection
                            .errorStream
                            ?.bufferedReader()
                            ?.use {
                                it.readText()
                            }
                    }.getOrNull()
                        .orEmpty()

                throw IllegalStateException(
                    when (code) {
                        403 ->
                            "GitHub 暂时限制了更新查询，请稍后再试"

                        404 ->
                            "没有找到公开的 GitHub Release"

                        else ->
                            "GitHub 更新查询失败：HTTP $code" +
                                if (
                                    detail.isNotBlank()
                                ) {
                                    " · " +
                                        detail.take(
                                            120
                                        )
                                } else {
                                    ""
                                }
                    }
                )
            }

            val text =
                connection
                    .inputStream
                    .bufferedReader()
                    .use {
                        it.readText()
                    }

            val json =
                JSONObject(text)

            val tag =
                json.optString(
                    "tag_name",
                    ""
                )
                    .trim()

            if (tag.isBlank()) {
                throw IllegalStateException(
                    "GitHub Release 没有版本标签"
                )
            }

            val latestVersion =
                normalizeVersion(
                    tag
                )

            if (
                latestVersion
                    .isBlank()
            ) {
                throw IllegalStateException(
                    "无法识别 GitHub 版本号：$tag"
                )
            }

            val releaseUrl =
                json.optString(
                    "html_url",
                    RELEASES_URL
                )
                    .ifBlank {
                        RELEASES_URL
                    }

            val assets =
                json.optJSONArray(
                    "assets"
                )

            var apkUrl = ""

            if (assets != null) {
                for (
                    i in 0 until
                        assets.length()
                ) {
                    val asset =
                        assets.getJSONObject(i)

                    val name =
                        asset.optString(
                            "name",
                            ""
                        )

                    if (
                        name.endsWith(
                            ".apk",
                            ignoreCase = true
                        )
                    ) {
                        val candidate =
                            asset.optString(
                                "browser_download_url",
                                ""
                            )

                        if (
                            candidate
                                .isNotBlank()
                        ) {
                            apkUrl =
                                candidate

                            if (
                                name.contains(
                                    "TianXianFruit",
                                    ignoreCase = true
                                )
                            ) {
                                break
                            }
                        }
                    }
                }
            }

            val info =
                AppUpdateInfo(
                    tagName = tag,
                    versionName =
                        latestVersion,
                    releaseUrl =
                        releaseUrl,
                    apkUrl =
                        apkUrl,
                    releaseNotes =
                        json.optString(
                            "body",
                            ""
                        )
                            .trim()
                            .take(
                                MAX_NOTES_LENGTH
                            ),
                    publishedAt =
                        json.optString(
                            "published_at",
                            ""
                        )
                )

            val newer =
                compareVersions(
                    latestVersion,
                    BuildConfig.VERSION_NAME
                ) > 0

            return if (newer) {
                AppUpdateCheckResult(
                    update = info,
                    latestVersionName =
                        latestVersion,
                    message =
                        "发现新版本 V$latestVersion"
                )
            } else {
                AppUpdateCheckResult(
                    update = null,
                    latestVersionName =
                        latestVersion,
                    message =
                        "已是最新版本 V${BuildConfig.VERSION_NAME}"
                )
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun normalizeVersion(
        raw: String
    ): String =
        raw.trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore(
                "+"
            )
            .trim()

    private fun compareVersions(
        candidate: String,
        current: String
    ): Int {
        val left =
            parseVersion(
                candidate
            )
        val right =
            parseVersion(
                current
            )

        val length =
            maxOf(
                left.first.size,
                right.first.size
            )

        for (i in 0 until length) {
            val a =
                left.first
                    .getOrElse(i) {
                        0
                    }
            val b =
                right.first
                    .getOrElse(i) {
                        0
                    }

            if (a != b) {
                return a.compareTo(b)
            }
        }

        val leftPre =
            left.second
        val rightPre =
            right.second

        return when {
            leftPre.isBlank() &&
                rightPre.isNotBlank() ->
                1

            leftPre.isNotBlank() &&
                rightPre.isBlank() ->
                -1

            else ->
                leftPre.compareTo(
                    rightPre
                )
        }
    }

    private fun parseVersion(
        value: String
    ): Pair<List<Int>, String> {
        val normalized =
            normalizeVersion(
                value
            )

        val main =
            normalized
                .substringBefore("-")

        val pre =
            normalized
                .substringAfter(
                    "-",
                    ""
                )

        val numbers =
            main.split(".")
                .map {
                    part ->
                    part.takeWhile {
                        it.isDigit()
                    }
                        .toIntOrNull()
                        ?: 0
                }

        return numbers to pre
    }

    companion object {
        private const val PREFS_NAME =
            "tianxian_update"

        private const val KEY_LAST_AUTO_CHECK =
            "last_auto_check"

        private const val AUTO_CHECK_INTERVAL_MS =
            12L * 60L * 60L * 1000L

        private const val CONNECT_TIMEOUT_MS =
            8_000

        private const val READ_TIMEOUT_MS =
            10_000

        private const val MAX_NOTES_LENGTH =
            4_000

        private const val API_URL =
            "https://api.github.com/repos/sockc/TianXianFruit/releases/latest"

        const val RELEASES_URL =
            "https://github.com/sockc/TianXianFruit/releases"
    }
}
