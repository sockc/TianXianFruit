package com.tianxian.fruit.sync

import com.tianxian.fruit.data.StoreOption
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate

data class WeatherCurrent(
    val time: String = "",
    val text: String = "",
    val code: String = "999",
    val temperature: Double? = null,
    val feelsLike: Double? = null,
    val humidity: Double? = null,
    val windDirection: String = "",
    val windScale: String = "",
    val windSpeed: Double? = null,
    val precipitation: Double? = null,
    val visibilityKm: Double? = null
)

data class WeatherHour(
    val time: String,
    val text: String,
    val code: String,
    val temperature: Double?,
    val feelsLike: Double?,
    val humidity: Double?,
    val precipitation: Double?,
    val precipitationProbability: Double?,
    val windDirection: String,
    val windScale: String,
    val windSpeed: Double?,
    val forecastText: String = "",
    val forecastCode: String = "999",
    val forecastTemperature: Double? = null,
    val forecastPrecipitation: Double? = null,
    val actualAvailable: Boolean = false,
    val forecastAvailable: Boolean = false
)

data class WeatherDay(
    val date: String,
    val textDay: String,
    val textNight: String,
    val codeDay: String,
    val codeNight: String,
    val tempMax: Double?,
    val tempMin: Double?,
    val precipitation: Double?,
    val precipitationProbability: Double?,
    val humidity: Double?,
    val sunrise: String,
    val sunset: String
)

data class WeatherAlert(
    val title: String,
    val severity: String,
    val type: String,
    val startTime: String,
    val endTime: String,
    val description: String
)

data class WeatherOverview(
    val date: String,
    val latitude: Double,
    val longitude: Double,
    val updatedAt: String,
    val current: WeatherCurrent?,
    val hourly: List<WeatherHour>,
    val daily: List<WeatherDay>,
    val minutelySummary: String,
    val alerts: List<WeatherAlert>,
    val attribution: String,
    val rawJson: String,
    val historical: Boolean = false
) {
    fun selectedDay(): WeatherDay? = daily.firstOrNull { it.date == date } ?: daily.firstOrNull()

    fun businessHours(startHour: Int = 16, endHour: Int = 24): List<WeatherHour> =
        hourly.filter { row ->
            val local = row.time.substringAfter('T', "").take(2).toIntOrNull() ?: return@filter false
            local in startHour until endHour
        }
}

class WeatherClient(
    private val cloudSyncManager: CloudSyncManager
) {
    fun fetchOverview(
        bookId: String,
        store: StoreOption,
        date: LocalDate,
        hours: Int = 72
    ): WeatherOverview {
        val session = cloudSyncManager.session()
            ?: throw IllegalStateException("尚未登录云端，无法获取天气")
        val lat = store.latitude ?: throw IllegalArgumentException("该位置尚未设置纬度")
        val lon = store.longitude ?: throw IllegalArgumentException("该位置尚未设置经度")
        val encodedBook = URLEncoder.encode(bookId, "UTF-8")
        val path = buildString {
            append("/api/v1/weather/overview?book_id=")
            append(encodedBook)
            append("&lat=")
            append(lat)
            append("&lon=")
            append(lon)
            append("&date=")
            append(date)
            append("&hours=")
            append(hours.coerceIn(24, 120))
        }
        val json = requestJson(session, path)
        return parseOverview(json, historical = false)
    }

    fun fetchHistoricalDay(
        bookId: String,
        store: StoreOption,
        date: LocalDate
    ): WeatherOverview {
        val session = cloudSyncManager.session()
            ?: throw IllegalStateException("尚未登录云端，无法读取历史天气")
        val lat = store.latitude ?: throw IllegalArgumentException("该位置尚未设置纬度")
        val lon = store.longitude ?: throw IllegalArgumentException("该位置尚未设置经度")
        val encodedBook = URLEncoder.encode(bookId, "UTF-8")
        val path = buildString {
            append("/api/v1/weather/history-day?book_id=")
            append(encodedBook)
            append("&lat=")
            append(lat)
            append("&lon=")
            append(lon)
            append("&date=")
            append(date)
        }
        val json = requestJson(session, path)
        return parseOverview(json, historical = true)
    }

    fun fetchArchive(
        bookId: String,
        store: StoreOption,
        date: LocalDate
    ): WeatherOverview {
        val session = cloudSyncManager.session()
            ?: throw IllegalStateException("尚未登录云端，无法读取历史天气")
        val stableStoreId = store.syncId.trim()
        if (stableStoreId.isBlank()) {
            throw IllegalStateException("该位置尚未同步稳定位置ID")
        }
        val encodedBook = URLEncoder.encode(bookId, "UTF-8")
        val encodedStore = URLEncoder.encode(stableStoreId, "UTF-8")
        val path = buildString {
            append("/api/v1/weather/archive?book_id=")
            append(encodedBook)
            append("&store_sync_id=")
            append(encodedStore)
            append("&date=")
            append(date)
        }
        val json = requestJson(session, path)
        return parseOverview(json, historical = true)
    }

    companion object {
        fun parseOverview(jsonText: String, historical: Boolean = false): WeatherOverview =
            parseOverview(JSONObject(jsonText), historical)

        fun parseOverview(root: JSONObject, historical: Boolean = false): WeatherOverview {
            fun nullableDouble(obj: JSONObject?, key: String): Double? {
                if (obj == null || !obj.has(key) || obj.isNull(key)) return null
                return when (val v = obj.opt(key)) {
                    is Number -> v.toDouble()
                    else -> v?.toString()?.toDoubleOrNull()
                }
            }
            val currentObj = root.optJSONObject("current")
            val current = currentObj?.let {
                WeatherCurrent(
                    time = it.optString("time"),
                    text = it.optString("text"),
                    code = it.optString("code", "999"),
                    temperature = nullableDouble(it, "temperature"),
                    feelsLike = nullableDouble(it, "feels_like"),
                    humidity = nullableDouble(it, "humidity"),
                    windDirection = it.optString("wind_direction"),
                    windScale = it.optString("wind_scale"),
                    windSpeed = nullableDouble(it, "wind_speed"),
                    precipitation = nullableDouble(it, "precipitation"),
                    visibilityKm = nullableDouble(it, "visibility_km")
                )
            }
            val hourly = buildList {
                val arr = root.optJSONArray("hourly") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val it = arr.optJSONObject(i) ?: continue
                    add(
                        WeatherHour(
                            time = it.optString("time"),
                            text = it.optString("text"),
                            code = it.optString("code", "999"),
                            temperature = nullableDouble(it, "temperature"),
                            feelsLike = nullableDouble(it, "feels_like"),
                            humidity = nullableDouble(it, "humidity"),
                            precipitation = nullableDouble(it, "precipitation"),
                            precipitationProbability = nullableDouble(it, "precipitation_probability"),
                            windDirection = it.optString("wind_direction"),
                            windScale = it.optString("wind_scale"),
                            windSpeed = nullableDouble(it, "wind_speed"),
                            forecastText = it.optString("forecast_text"),
                            forecastCode = it.optString("forecast_code", "999"),
                            forecastTemperature = nullableDouble(it, "forecast_temperature"),
                            forecastPrecipitation = nullableDouble(it, "forecast_precipitation"),
                            actualAvailable = it.optBoolean("actual_available", false),
                            forecastAvailable = it.optBoolean("forecast_available", false)
                        )
                    )
                }
            }
            val daily = buildList {
                val arr = root.optJSONArray("daily") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val it = arr.optJSONObject(i) ?: continue
                    add(
                        WeatherDay(
                            date = it.optString("date"),
                            textDay = it.optString("text_day"),
                            textNight = it.optString("text_night"),
                            codeDay = it.optString("code_day", "999"),
                            codeNight = it.optString("code_night", "999"),
                            tempMax = nullableDouble(it, "temp_max"),
                            tempMin = nullableDouble(it, "temp_min"),
                            precipitation = nullableDouble(it, "precipitation"),
                            precipitationProbability = nullableDouble(it, "precipitation_probability"),
                            humidity = nullableDouble(it, "humidity"),
                            sunrise = it.optString("sunrise"),
                            sunset = it.optString("sunset")
                        )
                    )
                }
            }
            val alerts = buildList {
                val arr = root.optJSONArray("alerts") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val it = arr.optJSONObject(i) ?: continue
                    add(
                        WeatherAlert(
                            title = it.optString("title"),
                            severity = it.optString("severity"),
                            type = it.optString("type"),
                            startTime = it.optString("start_time"),
                            endTime = it.optString("end_time"),
                            description = it.optString("description")
                        )
                    )
                }
            }
            return WeatherOverview(
                date = root.optString("date", LocalDate.now().toString()),
                latitude = root.optDouble("latitude", 0.0),
                longitude = root.optDouble("longitude", 0.0),
                updatedAt = root.optString("updated_at", Instant.now().toString()),
                current = current,
                hourly = hourly,
                daily = daily,
                minutelySummary = root.optString("minutely_summary"),
                alerts = alerts,
                attribution = root.optString("attribution", "QWeather 和风天气"),
                rawJson = root.toString(),
                historical = historical
            )
        }
    }

    private fun requestJson(session: CloudSession, path: String): JSONObject {
        val conn = (URL(session.baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 18_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer ${session.token}")
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let { input ->
                BufferedReader(InputStreamReader(input)).use { it.readText() }
            }.orEmpty()
            if (code !in 200..299) {
                val detail = runCatching {
                    (JSONTokener(text).nextValue() as? JSONObject)?.optString("detail")
                }.getOrNull().orEmpty()
                throw CloudApiException(
                    code,
                    detail.ifBlank { "天气服务请求失败：HTTP $code" }
                )
            }
            return (JSONTokener(text).nextValue() as? JSONObject)
                ?: throw IllegalStateException("天气服务返回格式错误")
        } finally {
            conn.disconnect()
        }
    }
}
