package com.tianxian.fruit.weather

import android.content.Context

data class WeatherNotificationSettings(
    val enabled: Boolean = false,
    val storeId: Long = 0L,
    val procurementEnabled: Boolean = true,
    val procurementStartTime: String = "06:00",
    val procurementEndTime: String = "10:00",
    val businessEnabled: Boolean = true,
    val heatEnabled: Boolean = true,
    val heatThresholdC: Double = 35.0,
    val coldEnabled: Boolean = true,
    val coldThresholdC: Double = 15.0,
    val temperatureSwingEnabled: Boolean = true,
    val temperatureSwingThresholdC: Double = 8.0,
    val rainEnabled: Boolean = true,
    val rainProbabilityThreshold: Double = 40.0,
    val windEnabled: Boolean = true,
    val windSpeedThresholdKmh: Double = 25.0,
    val rapidCoolingEnabled: Boolean = true,
    val rapidCoolingThresholdC: Double = 5.0,
    val alertEnabled: Boolean = true,
    val onlyAbnormal: Boolean = true
)

class WeatherNotificationSettingsManager(
    context: Context
) {
    private val prefs =
        context.applicationContext
            .getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE
            )

    fun load(
        ledgerId: String
    ): WeatherNotificationSettings {
        val prefix =
            prefix(ledgerId)

        fun doubleValue(
            key: String,
            default: Double
        ): Double =
            prefs.getString(
                "${prefix}_$key",
                null
            )
                ?.toDoubleOrNull()
                ?: default

        return WeatherNotificationSettings(
            enabled =
                prefs.getBoolean(
                    "${prefix}_enabled",
                    false
                ),
            storeId =
                prefs.getLong(
                    "${prefix}_store_id",
                    0L
                ),
            procurementEnabled =
                prefs.getBoolean(
                    "${prefix}_procurement_enabled",
                    true
                ),
            procurementStartTime =
                prefs.getString(
                    "${prefix}_procurement_start",
                    "06:00"
                ) ?: "06:00",
            procurementEndTime =
                prefs.getString(
                    "${prefix}_procurement_end",
                    "10:00"
                ) ?: "10:00",
            businessEnabled =
                prefs.getBoolean(
                    "${prefix}_business_enabled",
                    true
                ),
            heatEnabled =
                prefs.getBoolean(
                    "${prefix}_heat_enabled",
                    true
                ),
            heatThresholdC =
                doubleValue(
                    "heat_threshold",
                    35.0
                ),
            coldEnabled =
                prefs.getBoolean(
                    "${prefix}_cold_enabled",
                    true
                ),
            coldThresholdC =
                doubleValue(
                    "cold_threshold",
                    15.0
                ),
            temperatureSwingEnabled =
                prefs.getBoolean(
                    "${prefix}_swing_enabled",
                    true
                ),
            temperatureSwingThresholdC =
                doubleValue(
                    "swing_threshold",
                    8.0
                ),
            rainEnabled =
                prefs.getBoolean(
                    "${prefix}_rain_enabled",
                    true
                ),
            rainProbabilityThreshold =
                doubleValue(
                    "rain_threshold",
                    40.0
                ),
            windEnabled =
                prefs.getBoolean(
                    "${prefix}_wind_enabled",
                    true
                ),
            windSpeedThresholdKmh =
                doubleValue(
                    "wind_threshold",
                    25.0
                ),
            rapidCoolingEnabled =
                prefs.getBoolean(
                    "${prefix}_cooling_enabled",
                    true
                ),
            rapidCoolingThresholdC =
                doubleValue(
                    "cooling_threshold",
                    5.0
                ),
            alertEnabled =
                prefs.getBoolean(
                    "${prefix}_alert_enabled",
                    true
                ),
            onlyAbnormal =
                prefs.getBoolean(
                    "${prefix}_only_abnormal",
                    true
                )
        )
    }

    fun save(
        ledgerId: String,
        settings: WeatherNotificationSettings
    ) {
        val prefix =
            prefix(ledgerId)

        prefs.edit()
            .putBoolean(
                "${prefix}_enabled",
                settings.enabled
            )
            .putLong(
                "${prefix}_store_id",
                settings.storeId
            )
            .putBoolean(
                "${prefix}_procurement_enabled",
                settings.procurementEnabled
            )
            .putString(
                "${prefix}_procurement_start",
                settings.procurementStartTime
            )
            .putString(
                "${prefix}_procurement_end",
                settings.procurementEndTime
            )
            .putBoolean(
                "${prefix}_business_enabled",
                settings.businessEnabled
            )
            .putBoolean(
                "${prefix}_heat_enabled",
                settings.heatEnabled
            )
            .putString(
                "${prefix}_heat_threshold",
                settings.heatThresholdC
                    .toString()
            )
            .putBoolean(
                "${prefix}_cold_enabled",
                settings.coldEnabled
            )
            .putString(
                "${prefix}_cold_threshold",
                settings.coldThresholdC
                    .toString()
            )
            .putBoolean(
                "${prefix}_swing_enabled",
                settings.temperatureSwingEnabled
            )
            .putString(
                "${prefix}_swing_threshold",
                settings.temperatureSwingThresholdC
                    .toString()
            )
            .putBoolean(
                "${prefix}_rain_enabled",
                settings.rainEnabled
            )
            .putString(
                "${prefix}_rain_threshold",
                settings.rainProbabilityThreshold
                    .toString()
            )
            .putBoolean(
                "${prefix}_wind_enabled",
                settings.windEnabled
            )
            .putString(
                "${prefix}_wind_threshold",
                settings.windSpeedThresholdKmh
                    .toString()
            )
            .putBoolean(
                "${prefix}_cooling_enabled",
                settings.rapidCoolingEnabled
            )
            .putString(
                "${prefix}_cooling_threshold",
                settings.rapidCoolingThresholdC
                    .toString()
            )
            .putBoolean(
                "${prefix}_alert_enabled",
                settings.alertEnabled
            )
            .putBoolean(
                "${prefix}_only_abnormal",
                settings.onlyAbnormal
            )
            .apply()
    }

    /**
     * Returns true only for the first notice of this key, or when the same
     * condition has escalated to a higher severity. This keeps hourly weather
     * checks from repeating the same notification all day.
     */
    fun shouldNotify(
        ledgerId: String,
        key: String,
        severity: Int
    ): Boolean {
        val safeSeverity =
            severity.coerceIn(
                0,
                9
            )
        val noticeKey =
            "${prefix(ledgerId)}_notice_" +
                key.replace(
                    Regex(
                        "[^A-Za-z0-9_-]"
                    ),
                    "_"
                )
        val previous =
            prefs.getInt(
                noticeKey,
                -1
            )

        if (
            previous >=
                safeSeverity
        ) {
            return false
        }

        prefs.edit()
            .putInt(
                noticeKey,
                safeSeverity
            )
            .apply()

        return true
    }

    private fun prefix(
        ledgerId: String
    ): String =
        "ledger_" +
            ledgerId.replace(
                Regex(
                    "[^A-Za-z0-9_-]"
                ),
                "_"
            )

    companion object {
        private const val PREFS_NAME =
            "tianxian_weather_notifications"
    }
}
