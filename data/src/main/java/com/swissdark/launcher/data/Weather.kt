package com.swissdark.launcher.data

import androidx.compose.runtime.Immutable
import com.swissdark.launcher.domain.Cities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/** One reading for Home's clock line: "27° Partly cloudy". */
@Immutable
data class WeatherNow(val tempC: Int, val text: String)

/** Pure JSON → [WeatherNow]; WMO weather codes → the few words that read at 3 m. Unit-tested. */
object WeatherParse {
    fun parse(json: String): WeatherNow? {
        return try {
            val cur = Json.parseToJsonElement(json).jsonObject["current"]?.jsonObject ?: return null
            val t = cur["temperature_2m"]?.jsonPrimitive?.double ?: return null
            val code = cur["weather_code"]?.jsonPrimitive?.int ?: return null
            val text = text(code)
            if (text.isEmpty()) return null
            WeatherNow(t.roundToInt(), text)
        } catch (_: Exception) {
            null
        }
    }

    /** WMO 4677-1 codes, shortened. Empty for codes this launcher does not name. */
    fun text(code: Int): String = when (code) {
        0 -> "Clear"
        1 -> "Mostly clear"
        2 -> "Partly cloudy"
        3 -> "Cloudy"
        45, 48 -> "Fog"
        in 51..57 -> "Drizzle"
        in 61..67 -> "Rain"
        in 71..77 -> "Snow"
        in 80..82 -> "Showers"
        85, 86 -> "Snow"
        in 95..99 -> "Storm"
        else -> ""
    }
}

/**
 * Weather for Home (open-meteo, no API key, no permission). One fetch at start, then every
 * 30 min while the launcher lives; a city change fetches at once. The last good value is
 * cached on disk and shown (stale) until a fresh one lands; nothing is shown if it has
 * never succeeded. All failures are silent: the line simply stays away.
 */
class WeatherStore(
    private val scope: CoroutineScope,
    private val http: okhttp3.OkHttpClient,
    private val cache: File,
    private val city: () -> Cities.City,
) {
    private val _now = MutableStateFlow<WeatherNow?>(null)
    val now: StateFlow<WeatherNow?> = _now
    private val started = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            // Off main: a small file read plus JSON parse before the first fetch.
            cached()?.let { _now.value = it }
            while (isActive) {
                fetchAndStore()
                delay(30 * 60_000L)
            }
        }
    }

    /** Called when the owner picks another city: the new city's weather replaces it at once. */
    fun refreshNow() {
        scope.launch { fetchAndStore() }
    }

    private suspend fun fetchAndStore() {
        val body = fetch(city())
        if (body != null) WeatherParse.parse(body)?.let {
            _now.value = it
            runCatching { cache.writeText(System.currentTimeMillis().toString() + "|" + body) }
        }
    }

    private fun cached(): WeatherNow? = runCatching {
        val s = cache.takeIf { it.isFile }?.readText() ?: return null
        val ts = s.substringBefore('|').toLongOrNull() ?: return null
        if (System.currentTimeMillis() - ts > 6 * 3_600_000L) return null
        WeatherParse.parse(s.substringAfter('|'))
    }.getOrNull()

    private suspend fun fetch(c: Cities.City): String? = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://api.open-meteo.com/v1/forecast" +
                "?latitude=${c.latitude}&longitude=${c.longitude}&current=temperature_2m,weather_code&timezone=auto"
            http.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { r ->
                if (r.isSuccessful) r.body?.string() else null
            }
        }.getOrNull()
    }
}
