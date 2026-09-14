package com.mavgcs.app.adsb

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** One aeroplane the feeds can see, in the units they report it in. */
data class AdsbContact(
    val key: String,
    val callsign: String,
    val lat: Double,
    val lon: Double,
    val altFt: Double?,
    val groundSpeedKt: Double?,
    val trackDeg: Double?,
    val vertRateFpm: Double?,
    val type: String,
    val squawk: String,
)

/**
 * Nearby manned traffic, from free public ADS-B feeds.
 *
 * Both are community run and neither needs a key or an account. They are also
 * individually unreliable, which is why both are asked and the answers merged:
 * each network only sees what its own volunteers' receivers pick up, so the
 * union is meaningfully larger than either, and it keeps working when one of
 * them is down.
 */
object AdsbProvider {

    private const val TAG = "AdsbProvider"

    /**
     * Asked for well beyond what is drawn.
     *
     * The display keeps to 50km, the same circle the weather radar covers, but
     * a request costs the same whatever radius it names and the margin means
     * traffic is already in hand by the time it matters.
     */
    private const val QUERY_RADIUS_NM = 150

    private const val TIMEOUT_MS = 6_000

    /**
     * How long to leave a provider alone after it fails.
     *
     * A dead endpoint is not quick: the connect timeout does not bound DNS and
     * TLS retries, so one that is down can tie up a poll for far longer than
     * the timeout suggests. Backing off keeps a dead feed from throttling the
     * live one.
     */
    private const val COOLDOWN_MS = 120_000L

    private class Source(
        val name: String,
        val url: (Double, Double) -> String,
        val list: (JSONObject) -> JSONArray?,
    )

    // adsb.fi first: the more responsive of the two. Their JSON differs in
    // which key holds the list, hence a reader each.
    private val sources = listOf(
        Source(
            name = "adsb.fi",
            url = { lat, lon ->
                "https://opendata.adsb.fi/api/v2/lat/%.4f/lon/%.4f/dist/%d"
                    .format(java.util.Locale.ROOT, lat, lon, QUERY_RADIUS_NM)
            },
            list = { it.optJSONArray("aircraft") },
        ),
        Source(
            name = "adsb.lol",
            url = { lat, lon ->
                "https://api.adsb.lol/v2/point/%.4f/%.4f/%d"
                    .format(java.util.Locale.ROOT, lat, lon, QUERY_RADIUS_NM)
            },
            list = { it.optJSONArray("ac") },
        ),
    )

    private val retryAt = mutableMapOf<String, Long>()

    /**
     * Everything both feeds can see around a point, de-duplicated.
     *
     * Keyed on the ICAO hex, which is the aeroplane's own address, so the same
     * aircraft seen by both networks is one contact rather than two.
     */
    suspend fun around(lat: Double, lon: Double): List<AdsbContact> =
        withContext(Dispatchers.IO) {
            val merged = LinkedHashMap<String, AdsbContact>()
            for (source in sources) {
                if (System.currentTimeMillis() < (retryAt[source.name] ?: 0L)) continue
                val body = runCatching { read(source.url(lat, lon)) }.getOrElse { error ->
                    Log.w(TAG, "${source.name} unreachable: ${error.message}")
                    retryAt[source.name] = System.currentTimeMillis() + COOLDOWN_MS
                    null
                } ?: continue
                retryAt.remove(source.name)

                val list = runCatching { source.list(JSONObject(body)) }.getOrNull() ?: continue
                for (i in 0 until list.length()) {
                    val ac = list.optJSONObject(i) ?: continue
                    parse(ac)?.let { merged.putIfAbsent(it.key, it) }
                }
            }
            merged.values.toList()
        }

    private fun parse(ac: JSONObject): AdsbContact? {
        val lat = ac.optDouble("lat").takeIf { !it.isNaN() } ?: return null
        val lon = ac.optDouble("lon").takeIf { !it.isNaN() } ?: return null
        // These feeds say "ground" where they would otherwise give a number,
        // for aircraft sitting on the airport surface. Not traffic worth
        // drawing on a flight map.
        if (ac.opt("alt_baro") == "ground") return null

        val hex = ac.optString("hex").takeIf { it.isNotBlank() }
        val flight = ac.optString("flight").trim()
        // baro_rate is the one to want; geom_rate is what is there when it is not.
        val vert = ac.optDouble("baro_rate").takeIf { !it.isNaN() }
            ?: ac.optDouble("geom_rate").takeIf { !it.isNaN() }

        return AdsbContact(
            key = hex ?: "$flight:$lat:$lon",
            callsign = flight.ifBlank { hex ?: "?" },
            lat = lat,
            lon = lon,
            altFt = ac.optDouble("alt_baro").takeIf { !it.isNaN() },
            groundSpeedKt = ac.optDouble("gs").takeIf { !it.isNaN() },
            trackDeg = ac.optDouble("track").takeIf { !it.isNaN() },
            vertRateFpm = vert,
            type = ac.optString("t"),
            squawk = ac.optString("squawk"),
        )
    }

    private fun read(url: String): String =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            // These are volunteer-run feeds; say who is asking.
            setRequestProperty("User-Agent", "MavGCS")
        }.inputStream.bufferedReader().use { it.readText() }
}
