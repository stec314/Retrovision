package dev.retrovision.app.enrich

import android.util.Base64
import dev.retrovision.app.data.AppDao
import dev.retrovision.app.data.EnrichRow
import dev.retrovision.app.data.Prefs
import dev.retrovision.core.model.MacAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

sealed class Query {
    class WifiBssid(val mac: MacAddress) : Query()
    class WifiSsid(val ssid: String) : Query()
    class BleAddress(val mac: MacAddress) : Query()

    val cacheKey: String
        get() = when (this) {
            is WifiBssid -> "wifi:$mac"
            is WifiSsid -> "ssid:$ssid"
            is BleAddress -> "ble:$mac"
        }
}

class EnrichResult(val source: String, val lines: List<String>, val fromCache: Boolean = false)

class EnrichException(message: String) : Exception(message)

/** A lookup service. Add one by implementing this and registering it in [Enrichers]. */
interface Enricher {
    val id: String
    val label: String
    fun supports(q: Query): Boolean
    fun configured(p: Prefs): Boolean
    suspend fun lookup(q: Query): EnrichResult
}

private const val CACHE_TTL_MS = 30L * 24 * 3600_000

class Enrichers(private val prefs: Prefs, private val dao: AppDao) {
    fun all(): List<Enricher> = listOf(
        WigleEnricher(prefs.wigleName, prefs.wigleToken),
        BeaconDbEnricher(),
    )

    fun available(q: Query): List<Enricher> = all().filter {
        it.supports(q) && it.configured(prefs) && (it.id != "beacondb" || prefs.beaconDbEnabled)
    }

    /** Cached for 30 days: repeated taps do not spend the WiGLE daily quota. */
    suspend fun lookup(e: Enricher, q: Query, now: Long = System.currentTimeMillis()): EnrichResult {
        val key = "${e.id}|${q.cacheKey}"
        dao.enrichment(key)?.let { row ->
            if (now - row.fetchedMs < CACHE_TTL_MS) {
                return EnrichResult(e.label, row.json.split('\n'), fromCache = true)
            }
        }
        val r = e.lookup(q)
        dao.putEnrichment(EnrichRow(key, e.id, r.lines.joinToString("\n"), now))
        return r
    }
}

internal suspend fun httpJson(
    url: String,
    method: String = "GET",
    headers: Map<String, String> = emptyMap(),
    body: String? = null,
): Pair<Int, JSONObject?> = withContext(Dispatchers.IO) {
    val c = URL(url).openConnection() as HttpURLConnection
    try {
        c.requestMethod = method
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", "Retrovision/0.1 (+https://github.com/stec314/Retrovision)")
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        code to runCatching { JSONObject(text) }.getOrNull()
    } finally {
        c.disconnect()
    }
}

/**
 * WiGLE (wigle.net) crowd-sourced Wi-Fi/Bluetooth database. Needs a free account's
 * "API Name" and "API Token" (wigle.net/account); the free tier has a daily query limit.
 * Only the looked-up identifier (BSSID / SSID / Bluetooth address) is sent.
 */
class WigleEnricher(private val name: String, private val token: String) : Enricher {
    override val id = "wigle"
    override val label = "WiGLE"
    override fun supports(q: Query) = true
    override fun configured(p: Prefs) = p.wigleName.isNotEmpty() && p.wigleToken.isNotEmpty()

    override suspend fun lookup(q: Query): EnrichResult {
        val enc = { s: String -> URLEncoder.encode(s, "UTF-8") }
        val path = when (q) {
            is Query.WifiBssid -> "network/search?netid=${enc(q.mac.toString())}&resultsPerPage=10"
            is Query.WifiSsid -> "network/search?ssid=${enc(q.ssid)}&resultsPerPage=10"
            is Query.BleAddress -> "bluetooth/search?netid=${enc(q.mac.toString())}&resultsPerPage=10"
        }
        val auth = "Basic " + Base64.encodeToString("$name:$token".toByteArray(), Base64.NO_WRAP)
        val (code, json) = httpJson("https://api.wigle.net/api/v2/$path", headers = mapOf("Authorization" to auth))
        if (code == 401) throw EnrichException("WiGLE: credenziali non valide")
        if (code == 429) throw EnrichException("WiGLE: limite giornaliero raggiunto")
        if (json == null) throw EnrichException("WiGLE: risposta non valida (HTTP $code)")
        if (!json.optBoolean("success", false)) {
            throw EnrichException("WiGLE: " + json.optString("message", "errore (HTTP $code)"))
        }
        val results: JSONArray = json.optJSONArray("results") ?: JSONArray()
        if (results.length() == 0) return EnrichResult(label, listOf("Nessun risultato su WiGLE"))
        val lines = ArrayList<String>()
        lines += "${json.optInt("totalResults", results.length())} risultati"
        for (i in 0 until minOf(results.length(), 10)) {
            val r = results.getJSONObject(i)
            val where = listOf(r.optString("road"), r.optString("city"), r.optString("country"))
                .filter { it.isNotBlank() && it != "null" }.joinToString(", ")
            lines += "%s  %s  (%.5f, %.5f)  %s  ultimo: %s".format(
                r.optString("netid"), r.optString("ssid", r.optString("name", "")),
                r.optDouble("trilat"), r.optDouble("trilong"), where,
                r.optString("lastupdt").take(10),
            )
        }
        return EnrichResult(label, lines)
    }
}

/**
 * BeaconDB (beacondb.net), open Mozilla-Location-Service-compatible geolocation.
 * Takes Wi-Fi BSSIDs only; it may answer "not found" for a single access point.
 */
class BeaconDbEnricher : Enricher {
    override val id = "beacondb"
    override val label = "BeaconDB"
    override fun supports(q: Query) = q is Query.WifiBssid
    override fun configured(p: Prefs) = true

    override suspend fun lookup(q: Query): EnrichResult {
        q as Query.WifiBssid
        val body = JSONObject().put("considerIp", false).put(
            "wifiAccessPoints", JSONArray().put(JSONObject().put("macAddress", q.mac.toString())),
        ).toString()
        val (code, json) = httpJson("https://api.beacondb.net/v1/geolocate", "POST", body = body)
        if (code == 404) return EnrichResult(label, listOf("BeaconDB: posizione non trovata"))
        if (json == null || code !in 200..299) throw EnrichException("BeaconDB: HTTP $code")
        val loc = json.optJSONObject("location") ?: return EnrichResult(label, listOf("BeaconDB: nessun dato"))
        return EnrichResult(
            label,
            listOf("%.5f, %.5f  (±%.0f m)".format(loc.optDouble("lat"), loc.optDouble("lng"), json.optDouble("accuracy"))),
        )
    }
}

/** Offline: Bluetooth SIG company identifiers for the most common vendors. */
object BleVendors {
    private val names = mapOf(
        0x004C to "Apple", 0x0006 to "Microsoft", 0x0075 to "Samsung", 0x00E0 to "Google",
        0x0087 to "Garmin", 0x0157 to "Huami/Amazfit", 0x02E5 to "Espressif", 0x0059 to "Nordic Semiconductor",
        0x000F to "Broadcom", 0x0001 to "Nokia", 0x0002 to "Intel", 0x000D to "Texas Instruments",
        0x038F to "Xiaomi", 0x0171 to "Amazon", 0x0499 to "Ruuvi", 0x0822 to "Adafruit",
    )

    fun name(companyId: Int): String? = names[companyId]
}
