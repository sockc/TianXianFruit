package com.tianxian.fruit.weather

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tianxian.fruit.MainActivity
import com.tianxian.fruit.R
import com.tianxian.fruit.data.AppDatabase
import com.tianxian.fruit.data.StoreOption
import com.tianxian.fruit.sync.CloudSyncManager
import com.tianxian.fruit.sync.LedgerManager
import com.tianxian.fruit.sync.WeatherAlert
import com.tianxian.fruit.sync.WeatherClient
import com.tianxian.fruit.sync.WeatherHour
import com.tianxian.fruit.sync.WeatherOverview
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

object WeatherNotificationScheduler {
    private const val PERIODIC_WORK =
        "tianxian_weather_notification_periodic"
    private const val IMMEDIATE_WORK =
        "tianxian_weather_notification_immediate"

    fun refresh(
        context: Context
    ) {
        val appContext =
            context.applicationContext
        val ledgerManager =
            LedgerManager(
                appContext
            )
        val book =
            ledgerManager.currentBook()
        val settings =
            WeatherNotificationSettingsManager(
                appContext
            ).load(
                book.id
            )
        val workManager =
            WorkManager.getInstance(
                appContext
            )

        if (
            !settings.enabled
        ) {
            workManager
                .cancelUniqueWork(
                    PERIODIC_WORK
                )
            return
        }

        val request =
            PeriodicWorkRequestBuilder<WeatherNotificationWorker>(
                1,
                TimeUnit.HOURS,
                15,
                TimeUnit.MINUTES
            ).build()

        workManager
            .enqueueUniquePeriodicWork(
                PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
    }

    fun checkNow(
        context: Context
    ) {
        val request =
            OneTimeWorkRequestBuilder<WeatherNotificationWorker>()
                .build()

        WorkManager
            .getInstance(
                context.applicationContext
            )
            .enqueueUniqueWork(
                IMMEDIATE_WORK,
                ExistingWorkPolicy.REPLACE,
                request
            )
    }
}

class WeatherNotificationWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(
    appContext,
    params
) {
    private data class WindowMetrics(
        val rows: List<WeatherHour>,
        val minTemp: Double?,
        val maxTemp: Double?,
        val minFeelsLike: Double?,
        val maxPop: Double,
        val totalRain: Double,
        val maxWind: Double,
        val hotHours: List<String>,
        val wetHours: List<String>
    )

    private data class Notice(
        val key: String,
        val title: String,
        val body: String,
        val severity: Int,
        val urgent: Boolean = false
    )

    override suspend fun doWork(): Result {
        val context =
            applicationContext
        val ledgerManager =
            LedgerManager(
                context
            )
        val book =
            ledgerManager.currentBook()
        val settingsManager =
            WeatherNotificationSettingsManager(
                context
            )
        val settings =
            settingsManager.load(
                book.id
            )

        if (
            !settings.enabled ||
            !notificationPermissionGranted(
                context
            )
        ) {
            return Result.success()
        }

        val cloudSyncManager =
            CloudSyncManager(
                context,
                ledgerManager
            )
        val db =
            AppDatabase(
                context = context,
                dbFileName =
                    book.databaseName,
                ledgerId =
                    book.id,
                ledgerName =
                    book.name,
                deviceId =
                    ledgerManager.deviceId,
                deviceName =
                    ledgerManager.deviceName
            )

        return try {
            val today =
                LocalDate.now()
            val store =
                resolveStore(
                    db,
                    settings,
                    today
                )
                    ?: return Result.success()

            if (
                store.latitude == null ||
                store.longitude == null
            ) {
                return Result.success()
            }

            val overview =
                loadOverview(
                    db = db,
                    cloudSyncManager =
                        cloudSyncManager,
                    bookId =
                        book.cloudBookId
                            .ifBlank {
                                book.id
                                    .takeIf {
                                        it.contains(
                                            "-"
                                        )
                                    }
                                    .orEmpty()
                            },
                    store = store,
                    date = today
                )
                    ?: return Result.success()

            val notices =
                buildNotices(
                    overview =
                        overview,
                    store = store,
                    settings =
                        settings,
                    date = today,
                    now =
                        LocalDateTime.now()
                )

            notices.forEach {
                notice ->
                if (
                    settingsManager
                        .shouldNotify(
                            ledgerId =
                                book.id,
                            key =
                                notice.key,
                            severity =
                                notice.severity
                        )
                ) {
                    postNotification(
                        context =
                            context,
                        notice =
                            notice
                    )
                }
            }

            Result.success()
        } catch (
            cancelled: CancellationException
        ) {
            throw cancelled
        } catch (
            _: Throwable
        ) {
            // Weather notifications are advisory. Never keep retrying aggressively
            // in the background; the next hourly run can use a fresh cache/network.
            Result.success()
        } finally {
            runCatching {
                db.close()
            }
            cloudSyncManager
                .shutdown()
        }
    }

    private fun resolveStore(
        db: AppDatabase,
        settings: WeatherNotificationSettings,
        date: LocalDate
    ): StoreOption? {
        val preferred =
            settings.storeId
                .takeIf {
                    it > 0L
                }
                ?.let {
                    db.getStoreById(
                        it
                    )
                }
                ?.takeIf {
                    it.latitude != null &&
                        it.longitude != null
                }

        if (
            preferred != null
        ) {
            return preferred
        }

        val automatic =
            db.resolveWeatherStore(
                date.toString()
            ).store
                ?.takeIf {
                    it.latitude != null &&
                        it.longitude != null
                }

        return automatic
            ?: db.getStores()
                .firstOrNull {
                    it.latitude != null &&
                        it.longitude != null
                }
    }

    private fun loadOverview(
        db: AppDatabase,
        cloudSyncManager: CloudSyncManager,
        bookId: String,
        store: StoreOption,
        date: LocalDate
    ): WeatherOverview? {
        val dateText =
            date.toString()
        val now =
            System.currentTimeMillis()
        val cached =
            db.getWeatherCache(
                dateText,
                store.id
            )
        val cachedOverview =
            cached?.let {
                runCatching {
                    WeatherClient
                        .parseOverview(
                            it.payloadJson,
                            historical =
                                false
                        )
                }.getOrNull()
            }

        if (
            cached != null &&
            cachedOverview != null &&
            cached.expiresAt >
                now
        ) {
            return cachedOverview
        }

        if (
            bookId.isNotBlank() &&
            cloudSyncManager
                .session() !=
            null
        ) {
            val fresh =
                runCatching {
                    WeatherClient(
                        cloudSyncManager
                    ).fetchOverview(
                        bookId =
                            bookId,
                        store = store,
                        date = date,
                        hours = 72
                    )
                }.getOrNull()

            if (
                fresh != null
            ) {
                db.saveWeatherCache(
                    date =
                        dateText,
                    storeId =
                        store.id,
                    payloadJson =
                        fresh.rawJson,
                    ttlMs =
                        45L *
                            60L *
                            1000L,
                    fetchedAt =
                        now
                )
                return fresh
            }
        }

        // A recent stale cache is still useful for an advisory reminder when the
        // phone temporarily has no network. Do not use very old forecasts.
        return cachedOverview
            ?.takeIf {
                cached != null &&
                    now -
                        cached.fetchedAt <=
                    2L *
                        60L *
                        60L *
                        1000L
            }
    }

    private fun buildNotices(
        overview: WeatherOverview,
        store: StoreOption,
        settings: WeatherNotificationSettings,
        date: LocalDate,
        now: LocalDateTime
    ): List<Notice> {
        val result =
            mutableListOf<Notice>()

        if (
            settings.alertEnabled
        ) {
            buildAlertNotice(
                overview.alerts,
                store,
                date
            )?.let(
                result::add
            )
        }

        if (
            settings.procurementEnabled &&
            inLeadWindow(
                now = now,
                date = date,
                startTime =
                    settings.procurementStartTime,
                endTime =
                    settings.procurementEndTime,
                leadHours = 2,
                graceHours = 2
            )
        ) {
            val rows =
                rowsForWindow(
                    overview.hourly,
                    date,
                    settings.procurementStartTime,
                    settings.procurementEndTime
                )
            val metrics =
                summarize(
                    rows,
                    settings
                )
            buildWindowNotice(
                kind =
                    "procurement",
                title =
                    "早上采购天气提醒 · ${store.name}",
                label =
                    "${settings.procurementStartTime}–${settings.procurementEndTime}",
                metrics =
                    metrics,
                overview =
                    overview,
                settings =
                    settings,
                date =
                    date,
                adviceContext =
                    "采购"
            )?.let(
                result::add
            )
        }

        if (
            settings.businessEnabled &&
            inLeadWindow(
                now = now,
                date = date,
                startTime =
                    store.defaultStartTime,
                endTime =
                    store.defaultEndTime,
                leadHours = 3,
                // Keep checking through the whole business window. A later
                // forecast update can therefore surface rain that was not
                // present in the pre-opening forecast.
                graceHours = 24
            )
        ) {
            val rows =
                rowsForWindow(
                    overview.hourly,
                    date,
                    store.defaultStartTime,
                    store.defaultEndTime
                )
            val metrics =
                summarize(
                    rows,
                    settings
                )
            buildWindowNotice(
                kind =
                    "business",
                title =
                    "营业天气提醒 · ${store.name}",
                label =
                    "${store.defaultStartTime}–${store.defaultEndTime}",
                metrics =
                    metrics,
                overview =
                    overview,
                settings =
                    settings,
                date =
                    date,
                adviceContext =
                    "营业"
            )?.let(
                result::add
            )
        }

        if (
            settings.rapidCoolingEnabled
        ) {
            buildRapidCoolingNotice(
                overview =
                    overview,
                settings =
                    settings,
                store =
                    store,
                date =
                    date,
                now =
                    now
            )?.let(
                result::add
            )
        }

        return result
    }

    private fun buildWindowNotice(
        kind: String,
        title: String,
        label: String,
        metrics: WindowMetrics,
        overview: WeatherOverview,
        settings: WeatherNotificationSettings,
        date: LocalDate,
        adviceContext: String
    ): Notice? {
        if (
            metrics.rows.isEmpty()
        ) {
            return null
        }

        var severity = 0
        val facts =
            mutableListOf<String>()
        val advice =
            mutableListOf<String>()

        val tempRange =
            temperatureRange(
                metrics.minTemp,
                metrics.maxTemp
            )
        if (
            tempRange.isNotBlank()
        ) {
            facts +=
                "$label $tempRange"
        } else {
            facts += label
        }

        if (
            settings.heatEnabled &&
            metrics.maxTemp !=
            null &&
            metrics.maxTemp >=
            settings.heatThresholdC
        ) {
            val maxTemp =
                metrics.maxTemp
            severity =
                max(
                    severity,
                    when {
                        maxTemp >= 40.0 -> 3
                        maxTemp >= 37.0 -> 2
                        else -> 1
                    }
                )
            facts +=
                "最高约 ${maxTemp.roundToInt()}℃"
            if (
                metrics.hotHours.isNotEmpty()
            ) {
                facts +=
                    "高温时段 ${metrics.hotHours.take(3).joinToString("、")}"
            }
            advice +=
                if (
                    adviceContext ==
                    "营业"
                ) {
                    "注意遮阳补水，水果避免暴晒"
                } else {
                    "注意防暑补水"
                }
        }

        if (
            settings.coldEnabled &&
            metrics.minTemp !=
            null &&
            metrics.minTemp <=
            settings.coldThresholdC
        ) {
            val minTemp =
                metrics.minTemp
            severity =
                max(
                    severity,
                    when {
                        minTemp <= 5.0 -> 3
                        minTemp <= 10.0 -> 2
                        else -> 1
                    }
                )
            facts +=
                "最低约 ${minTemp.roundToInt()}℃"
            advice +=
                if (
                    minTemp <=
                    10.0
                ) {
                    "注意保暖"
                } else {
                    "带件外套"
                }
        }

        val day =
            overview.daily
                .firstOrNull {
                    it.date ==
                        date.toString()
                }
                ?: overview
                    .selectedDay()
        val swing =
            if (
                day?.tempMax !=
                null &&
                day.tempMin !=
                null
            ) {
                day.tempMax -
                    day.tempMin
            } else if (
                metrics.minTemp !=
                null &&
                metrics.maxTemp !=
                null
            ) {
                metrics.maxTemp -
                    metrics.minTemp
            } else {
                0.0
            }

        if (
            settings.temperatureSwingEnabled &&
            swing >=
            settings.temperatureSwingThresholdC
        ) {
            severity =
                max(
                    severity,
                    when {
                        swing >=
                            settings.temperatureSwingThresholdC +
                                8.0 -> 3
                        swing >=
                            settings.temperatureSwingThresholdC +
                                4.0 -> 2
                        else -> 1
                    }
                )
            facts +=
                "全天温差约 ${swing.roundToInt()}℃"
            advice +=
                "建议分层穿衣"
        }

        if (
            settings.rainEnabled &&
            (
                metrics.maxPop >=
                    settings.rainProbabilityThreshold ||
                    metrics.totalRain >
                    0.05
                )
        ) {
            severity =
                max(
                    severity,
                    when {
                        metrics.maxPop >= 85.0 ||
                            metrics.totalRain >= 5.0 -> 3
                        metrics.maxPop >= 70.0 ||
                            metrics.totalRain >= 2.0 -> 2
                        else -> 1
                    }
                )
            facts +=
                if (
                    metrics.maxPop >
                    0
                ) {
                    "降雨最高 ${metrics.maxPop.roundToInt()}%"
                } else {
                    "有降雨"
                }
            if (
                metrics.wetHours.isNotEmpty()
            ) {
                facts +=
                    "重点 ${metrics.wetHours.take(3).joinToString("、")}"
            }
            advice +=
                if (
                    adviceContext ==
                    "营业"
                ) {
                    "提前做好遮雨"
                } else {
                    "带雨具"
                }
        }

        if (
            settings.windEnabled &&
            metrics.maxWind >=
            settings.windSpeedThresholdKmh
        ) {
            severity =
                max(
                    severity,
                    when {
                        metrics.maxWind >= 45.0 -> 3
                        metrics.maxWind >= 35.0 -> 2
                        else -> 1
                    }
                )
            facts +=
                "最大风速约 ${metrics.maxWind.roundToInt()}km/h"
            advice +=
                if (
                    adviceContext ==
                    "营业"
                ) {
                    "注意固定棚布和价签"
                } else {
                    "注意防风"
                }
        }

        if (
            severity <= 0 &&
            settings.onlyAbnormal
        ) {
            return null
        }

        if (
            severity <= 0
        ) {
            facts +=
                "暂未发现明显风雨或高低温风险"
        }

        val body =
            buildString {
                append(
                    facts.distinct()
                        .joinToString(
                            " · "
                        )
                )
                if (
                    advice.isNotEmpty()
                ) {
                    append(
                        "\n建议："
                    )
                    append(
                        advice.distinct()
                            .joinToString(
                                "、"
                            )
                    )
                }
            }

        return Notice(
            key =
                "${kind}_${date}",
            title =
                title,
            body =
                body,
            severity =
                severity
        )
    }

    private fun buildRapidCoolingNotice(
        overview: WeatherOverview,
        settings: WeatherNotificationSettings,
        store: StoreOption,
        date: LocalDate,
        now: LocalDateTime
    ): Notice? {
        val rows =
            overview.hourly
                .mapNotNull {
                    row ->
                    val time =
                        hourTime(
                            row
                        )
                            ?: return@mapNotNull null
                    if (
                        time < now.minusHours(
                            1
                        ) ||
                        time >
                        now.plusHours(
                            6
                        )
                    ) {
                        null
                    } else {
                        time to row
                    }
                }
                .sortedBy {
                    it.first
                }

        if (
            rows.size < 2
        ) {
            return null
        }

        val firstTemp =
            forecastTemperature(
                rows.first()
                    .second
            )
                ?: return null
        val lowest =
            rows.mapNotNull {
                forecastTemperature(
                    it.second
                )
            }.minOrNull()
                ?: return null
        val drop =
            firstTemp -
                lowest

        if (
            drop <
            settings.rapidCoolingThresholdC
        ) {
            return null
        }

        val severity =
            when {
                drop >= 10.0 -> 3
                drop >= 7.0 -> 2
                else -> 1
            }

        return Notice(
            key =
                "rapid_cooling_$date",
            title =
                "未来6小时明显降温 · ${store.name}",
            body =
                "预计从约 ${firstTemp.roundToInt()}℃ 降至 ${lowest.roundToInt()}℃，降约 ${drop.roundToInt()}℃。外出采购或晚间收摊注意及时加衣。",
            severity =
                severity
        )
    }

    private fun buildAlertNotice(
        alerts: List<WeatherAlert>,
        store: StoreOption,
        date: LocalDate
    ): Notice? {
        val active =
            alerts.filter {
                it.title.isNotBlank()
            }
        if (
            active.isEmpty()
        ) {
            return null
        }

        val signature =
            active.take(3)
                .joinToString(
                    "|"
                ) {
                    "${it.title}:${it.startTime}"
                }
                .hashCode()
                .toString()

        val titles =
            active.take(3)
                .joinToString(
                    "；"
                ) {
                    it.title
                }
        val description =
            active.firstOrNull()
                ?.description
                .orEmpty()
                .replace(
                    "\n",
                    " "
                )
                .trim()
                .take(
                    120
                )

        return Notice(
            key =
                "alert_${date}_$signature",
            title =
                "天气预警 · ${store.name}",
            body =
                if (
                    description.isBlank()
                ) {
                    titles
                } else {
                    "$titles\n$description"
                },
            severity = 3,
            urgent = true
        )
    }

    private fun summarize(
        rows: List<WeatherHour>,
        settings: WeatherNotificationSettings
    ): WindowMetrics {
        val temperatures =
            rows.mapNotNull(
                ::forecastTemperature
            )
        val feels =
            rows.mapNotNull {
                it.feelsLike
                    ?: forecastTemperature(
                        it
                    )
            }
        val maxPop =
            rows.mapNotNull {
                it.precipitationProbability
            }.maxOrNull()
                ?: 0.0
        val totalRain =
            rows.sumOf {
                forecastRain(
                    it
                )
                    ?: 0.0
            }
        val maxWind =
            rows.mapNotNull {
                it.windSpeed
            }.maxOrNull()
                ?: 0.0
        val hotHours =
            rows.mapNotNull {
                row ->
                val temp =
                    forecastTemperature(
                        row
                    )
                        ?: return@mapNotNull null
                if (
                    settings.heatEnabled &&
                    temp >=
                    settings.heatThresholdC
                ) {
                    hourTime(
                        row
                    )
                        ?.let {
                            "%02d:%02d".format(
                                it.hour,
                                it.minute
                            )
                        }
                } else {
                    null
                }
            }
        val wetHours =
            rows.mapNotNull {
                row ->
                val pop =
                    row.precipitationProbability
                        ?: 0.0
                val rain =
                    forecastRain(
                        row
                    )
                        ?: 0.0
                if (
                    pop >=
                        settings.rainProbabilityThreshold ||
                    rain >
                        0.05
                ) {
                    hourTime(
                        row
                    )
                        ?.let {
                            "%02d:%02d".format(
                                it.hour,
                                it.minute
                            )
                        }
                } else {
                    null
                }
            }

        return WindowMetrics(
            rows = rows,
            minTemp =
                temperatures.minOrNull(),
            maxTemp =
                temperatures.maxOrNull(),
            minFeelsLike =
                feels.minOrNull(),
            maxPop =
                maxPop,
            totalRain =
                totalRain,
            maxWind =
                maxWind,
            hotHours =
                hotHours
                    .distinct(),
            wetHours =
                wetHours
                    .distinct()
        )
    }

    private fun rowsForWindow(
        rows: List<WeatherHour>,
        date: LocalDate,
        startTime: String,
        endTime: String
    ): List<WeatherHour> {
        val startMinutes =
            clockMinutes(
                startTime
            )
                ?: return emptyList()
        val endMinutes =
            clockMinutes(
                endTime
            )
                ?: return emptyList()
        val start =
            date.atStartOfDay()
                .plusMinutes(
                    startMinutes.toLong()
                )
        var end =
            date.atStartOfDay()
                .plusMinutes(
                    endMinutes.toLong()
                )
        if (
            end <= start
        ) {
            end =
                end.plusDays(
                    1
                )
        }

        return rows.filter {
            row ->
            val time =
                hourTime(
                    row
                )
                    ?: return@filter false
            time >= start &&
                time < end
        }
    }

    private fun inLeadWindow(
        now: LocalDateTime,
        date: LocalDate,
        startTime: String,
        endTime: String,
        leadHours: Long,
        graceHours: Long
    ): Boolean {
        val startMinutes =
            clockMinutes(
                startTime
            )
                ?: return false
        val endMinutes =
            clockMinutes(
                endTime
            )
                ?: return false
        val start =
            date.atStartOfDay()
                .plusMinutes(
                    startMinutes.toLong()
                )
        var end =
            date.atStartOfDay()
                .plusMinutes(
                    endMinutes.toLong()
                )
        if (
            end <= start
        ) {
            end =
                end.plusDays(
                    1
                )
        }

        return now >=
            start.minusHours(
                leadHours
            ) &&
            now <=
            minOf(
                end,
                start.plusHours(
                    graceHours
                )
            )
    }

    private fun clockMinutes(
        raw: String
    ): Int? {
        val parts =
            raw.trim()
                .split(
                    ":"
                )
        if (
            parts.size != 2
        ) {
            return null
        }
        val hour =
            parts[0].toIntOrNull()
                ?: return null
        val minute =
            parts[1].toIntOrNull()
                ?: return null

        if (
            hour == 24 &&
            minute == 0
        ) {
            return 24 *
                60
        }
        if (
            hour !in 0..23 ||
            minute !in 0..59
        ) {
            return null
        }

        return hour *
            60 +
            minute
    }

    private fun hourTime(
        row: WeatherHour
    ): LocalDateTime? {
        val raw =
            row.time.trim()
        if (
            raw.isBlank()
        ) {
            return null
        }

        return runCatching {
            OffsetDateTime
                .parse(
                    raw
                )
                .toLocalDateTime()
        }.getOrNull()
            ?: runCatching {
                ZonedDateTime
                    .parse(
                        raw
                    )
                    .toLocalDateTime()
            }.getOrNull()
            ?: runCatching {
                LocalDateTime
                    .parse(
                        raw
                    )
            }.getOrNull()
    }

    private fun forecastTemperature(
        row: WeatherHour
    ): Double? =
        row.forecastTemperature
            ?: row.temperature

    private fun forecastRain(
        row: WeatherHour
    ): Double? =
        row.forecastPrecipitation
            ?: row.precipitation

    private fun temperatureRange(
        min: Double?,
        max: Double?
    ): String =
        when {
            min == null &&
                max == null -> ""
            min != null &&
                max != null &&
                abs(
                    max -
                        min
                ) >=
                0.5 ->
                "${min.roundToInt()}–${max.roundToInt()}℃"

            min != null ->
                "约 ${min.roundToInt()}℃"

            else ->
                "约 ${max!!.roundToInt()}℃"
        }

    private fun postNotification(
        context: Context,
        notice: Notice
    ) {
        ensureChannels(
            context
        )

        val channel =
            if (
                notice.urgent
            ) {
                CHANNEL_ALERT
            } else {
                CHANNEL_WEATHER
            }
        val intent =
            Intent(
                context,
                MainActivity::class.java
            ).apply {
                flags =
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        val notificationId =
            (
                notice.key +
                    notice.title
                )
                .hashCode()
                .and(
                    0x7fffffff
                )
        val pendingIntent =
            PendingIntent.getActivity(
                context,
                notificationId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
            )
        val builder =
            NotificationCompat
                .Builder(
                    context,
                    channel
                )
                .setSmallIcon(
                    R.drawable.ic_weather_notification
                )
                .setContentTitle(
                    notice.title
                )
                .setContentText(
                    notice.body
                        .substringBefore(
                            "\n"
                        )
                )
                .setStyle(
                    NotificationCompat
                        .BigTextStyle()
                        .bigText(
                            notice.body
                        )
                )
                .setContentIntent(
                    pendingIntent
                )
                .setAutoCancel(
                    true
                )
                .setOnlyAlertOnce(
                    true
                )
                .setPriority(
                    if (
                        notice.urgent
                    ) {
                        NotificationCompat
                            .PRIORITY_HIGH
                    } else {
                        NotificationCompat
                            .PRIORITY_DEFAULT
                    }
                )

        if (
            Build.VERSION.SDK_INT >=
                33 &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        try {
            NotificationManagerCompat
                .from(
                    context
                )
                .notify(
                    notificationId,
                    builder.build()
                )
        } catch (
            _: SecurityException
        ) {
            // Permission or system notification state may change between the
            // background check and actual delivery. Skip safely in that case.
            return
        }
    }

    private fun ensureChannels(
        context: Context
    ) {
        if (
            Build.VERSION.SDK_INT <
            Build.VERSION_CODES.O
        ) {
            return
        }

        val manager =
            context.getSystemService(
                NotificationManager::class.java
            )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_WEATHER,
                "天气提醒",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description =
                    "采购、营业、高温、低温、温差、降雨、大风和快速降温提醒"
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERT,
                "天气预警",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description =
                    "暴雨、大风、寒潮、强对流等天气预警"
            }
        )
    }

    private fun notificationPermissionGranted(
        context: Context
    ): Boolean {
        if (
            Build.VERSION.SDK_INT >=
            33 &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }

        return NotificationManagerCompat
            .from(
                context
            )
            .areNotificationsEnabled()
    }

    companion object {
        private const val CHANNEL_WEATHER =
            "weather_reminders"
        private const val CHANNEL_ALERT =
            "weather_alerts"
    }
}
