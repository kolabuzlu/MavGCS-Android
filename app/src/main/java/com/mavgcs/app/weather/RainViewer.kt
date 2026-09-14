package com.mavgcs.app.weather

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/** One radar frame: the host serving it and the opaque path identifying it. */
data class RadarFrame(val host: String, val path: String)

/** A fetched radar tile and the slippy-map indices it covers. */
data class RadarTile(val x: Int, val y: Int, val zoom: Int, val image: Bitmap)

/**
 * RainViewer publishes an index of the radar frames it currently holds. Each
 * carries an opaque id rather than a predictable timestamp, so the newest frame
 * has to be looked up rather than constructed, and re-read as frames age out.
 */
object RainViewer {
    private const val INDEX_URL = "https://api.rainviewer.com/public/weather-maps.json"
    private const val TIMEOUT_MS = 8_000

    /**
     * The deepest zoom the free tiles are served at. Asking for more returns a
     * placard image reading "Zoom Level Not Supported", with a 200 status, so
     * the limit has to be respected rather than discovered from the response.
     */
    const val MAX_RADAR_ZOOM = 7

    /** The most recent past frame, or null if the index could not be read. */
    suspend fun latestFrame(): RadarFrame? = withContext(Dispatchers.IO) {
        runCatching {
            val body = read(INDEX_URL)
            val root = JSONObject(body)
            val past = root.getJSONObject("radar").getJSONArray("past")
            RadarFrame(
                host = root.getString("host"),
                path = past.getJSONObject(past.length() - 1).getString("path"),
            )
        }.getOrNull()
    }

    /**
     * Every tile of [frame] at [MAX_RADAR_ZOOM] that the box touches. One tile
     * spans hundreds of kilometres at that zoom, so this is normally a single
     * image, and at most four where the area straddles a tile boundary.
     */
    suspend fun tilesCovering(
        frame: RadarFrame,
        northLat: Double,
        southLat: Double,
        westLon: Double,
        eastLon: Double,
    ): List<RadarTile> = withContext(Dispatchers.IO) {
        val zoom = MAX_RADAR_ZOOM
        val xRange = tileX(westLon, zoom)..tileX(eastLon, zoom)
        val yRange = tileY(northLat, zoom)..tileY(southLat, zoom)
        buildList {
            for (x in xRange) {
                for (y in yRange) {
                    fetchTile(frame, zoom, x, y)?.let { add(RadarTile(x, y, zoom, it)) }
                }
            }
        }
    }

    private fun fetchTile(frame: RadarFrame, zoom: Int, x: Int, y: Int): Bitmap? = runCatching {
        // The trailing segments are the colour scheme, then smoothing and snow.
        val url = "${frame.host}${frame.path}/256/$zoom/$x/$y/4/1_1.png"
        connect(url).inputStream.use { BitmapFactory.decodeStream(it) }
    }.getOrNull()

    private fun read(url: String): String =
        connect(url).inputStream.bufferedReader().use { it.readText() }

    private fun connect(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
        }

    // --- slippy map tile maths, so the fetched tiles can be placed on the map ---

    fun tileX(lon: Double, zoom: Int): Int {
        val count = 1 shl zoom
        return floor((lon + 180.0) / 360.0 * count).toInt().coerceIn(0, count - 1)
    }

    fun tileY(lat: Double, zoom: Int): Int {
        val count = 1 shl zoom
        val radians = Math.toRadians(lat)
        val value = (1.0 - ln(tan(radians) + 1.0 / cos(radians)) / PI) / 2.0 * count
        return floor(value).toInt().coerceIn(0, count - 1)
    }

    /** Longitude of a tile's western edge. */
    fun tileWestLon(x: Int, zoom: Int): Double = x.toDouble() / (1 shl zoom) * 360.0 - 180.0

    /** Latitude of a tile's northern edge. */
    fun tileNorthLat(y: Int, zoom: Int): Double {
        val n = PI - 2.0 * PI * y / (1 shl zoom)
        return Math.toDegrees(atan(sinh(n)))
    }
}
