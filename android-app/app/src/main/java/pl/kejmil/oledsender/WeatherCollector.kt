package pl.kejmil.oledsender

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class WeatherCollector(
    context: Context,
    private val onEvent: (WidgetEvent) -> Unit,
    private val onLog: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var lastFetchAt = 0L
    private var running = true

    fun refreshIfNeeded() {
        val now = System.currentTimeMillis()
        if (now - lastFetchAt < WEATHER_REFRESH_MS) {
            return
        }
        lastFetchAt = now
        refresh()
    }

    fun close() {
        running = false
    }

    @SuppressLint("MissingPermission")
    private fun refresh() {
        if (!hasLocationPermission()) {
            onLog("Weather: brak lokalizacji, zostaje parsing powiadomien pogodowych")
            return
        }
        val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val location = bestLastKnownLocation(locationManager)
        if (location == null) {
            onLog("Weather: brak ostatniej znanej lokalizacji")
            return
        }

        executor.execute {
            try {
                val weather = fetchWeather(location)
                if (!running) {
                    return@execute
                }
                val payload = JSONObject()
                    .put("temp", "${weather.temperature.roundToInt()} C")
                    .put("desc", weather.description)
                    .put("city", weather.city)
                val event = WidgetEvent(
                    type = WidgetType.WEATHER,
                    sourceKey = "weather:open-meteo",
                    priority = WidgetType.WEATHER.defaultPriority,
                    payload = payload,
                    timeoutMs = 15 * 60 * 1000L
                )
                handler.post { onEvent(event) }
            } catch (error: Exception) {
                onLog("Weather fetch failed: ${error.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun bestLastKnownLocation(locationManager: LocationManager?): Location? {
        if (locationManager == null) {
            return null
        }
        val providers = listOf(
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        return providers.mapNotNull { provider ->
            runCatching {
                if (locationManager.isProviderEnabled(provider)) {
                    locationManager.getLastKnownLocation(provider)
                } else {
                    null
                }
            }.getOrNull()
        }.maxByOrNull { it.time }
    }

    private fun fetchWeather(location: Location): WeatherResult {
        val latitude = "%.5f".format(Locale.US, location.latitude)
        val longitude = "%.5f".format(Locale.US, location.longitude)
        val url =
            "https://api.open-meteo.com/v1/forecast?latitude=$latitude&longitude=$longitude&current=temperature_2m,weather_code&timezone=auto"
        val text = openText(url)
        val current = JSONObject(text).getJSONObject("current")
        val temperature = current.getDouble("temperature_2m")
        val code = current.optInt("weather_code", -1)
        val city = cityName(location)
        return WeatherResult(
            temperature = temperature,
            description = weatherDescription(code),
            city = city
        )
    }

    private fun openText(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = HTTP_TIMEOUT_MS
        connection.readTimeout = HTTP_TIMEOUT_MS
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", "KejmilOLED")
        connection.connect()
        try {
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${connection.responseCode}")
            }
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun cityName(location: Location): String {
        return runCatching {
            @Suppress("DEPRECATION")
            val addresses = Geocoder(appContext, Locale.getDefault())
                .getFromLocation(location.latitude, location.longitude, 1)
            val address = addresses?.firstOrNull()
            address?.locality
                ?: address?.subAdminArea
                ?: address?.adminArea
                ?: "%.2f, %.2f".format(Locale.US, location.latitude, location.longitude)
        }.getOrElse {
            "%.2f, %.2f".format(Locale.US, location.latitude, location.longitude)
        }
    }

    private fun weatherDescription(code: Int): String {
        return when (code) {
            0 -> "bezchmurnie"
            1, 2 -> "czesciowo slonecznie"
            3 -> "pochmurno"
            45, 48 -> "mgla"
            51, 53, 55 -> "mzawka"
            56, 57 -> "marznaca mzawka"
            61, 63, 65 -> "deszcz"
            66, 67 -> "marznacy deszcz"
            71, 73, 75, 77 -> "snieg"
            80, 81, 82 -> "przelotny deszcz"
            85, 86 -> "opady sniegu"
            95 -> "burza"
            96, 99 -> "burza z gradem"
            else -> "pogoda"
        }
    }

    private fun hasLocationPermission(): Boolean {
        return appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            appContext.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    private data class WeatherResult(
        val temperature: Double,
        val description: String,
        val city: String
    )

    companion object {
        private const val WEATHER_REFRESH_MS = 30 * 60 * 1000L
        private const val HTTP_TIMEOUT_MS = 12000
    }
}
