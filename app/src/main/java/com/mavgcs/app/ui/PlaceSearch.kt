package com.mavgcs.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.abs

/** A place the search found, and where it is. */
internal data class Place(val name: String, val lat: Double, val lon: Double)

/** What came back from asking. */
internal sealed interface SearchOutcome {
    data class Found(val places: List<Place>) : SearchOutcome

    data object NothingFound : SearchOutcome

    data class Failed(val why: String) : SearchOutcome
}

/**
 * A pair of numbers typed straight in, rather than a name to look up.
 *
 * Worth catching before the network is touched: coordinates are what a pilot
 * already has in hand when a position is read out over the radio, and they are
 * the only search that works with no internet at all -- which at a field, on
 * the radio's own wifi, is the usual state of things.
 */
internal fun typedCoordinates(text: String): Place? {
    val parts = text.trim().split(',', ' ', ';').filter { it.isNotBlank() }
    if (parts.size != 2) return null
    val lat = parts[0].toDoubleOrNull() ?: return null
    val lon = parts[1].toDoubleOrNull() ?: return null
    if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
    return Place("%.6f, %.6f".format(Locale.ROOT, lat, lon), lat, lon)
}

/**
 * Looks a place up by name, asking two gazetteers at once.
 *
 * Both are free and need no key, which is the whole reason for choosing them:
 * Google's geocoder and Yandex's both want a billed API key, and a search box
 * is a great deal to ask a pilot to register an account for.
 *
 * They are asked together because they fail differently rather than equally.
 * Nominatim matches strictly and answers with well ordered, structured names;
 * Photon is fuzzier and will find a Turkish place typed without its Turkish
 * letters, where Nominatim returns nothing at all. Searching "Sakarya
 * Universitesi" on an English keyboard is the ordinary case, not the odd one.
 *
 * Asked once per press of the search key, never per keystroke: Nominatim's
 * terms allow no more than a request a second and no bulk use, and a box that
 * searched as it was typed would break both on a single word.
 *
 * [near] biases the answers towards the aircraft without excluding anything. A
 * pilot searching for a village usually means the one they are flying near,
 * but may be planning for somewhere else entirely.
 */
internal suspend fun searchPlaces(
    query: String,
    near: Pair<Double, Double>?,
): SearchOutcome = coroutineScope {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return@coroutineScope SearchOutcome.NothingFound
    typedCoordinates(trimmed)?.let { return@coroutineScope SearchOutcome.Found(listOf(it)) }

    // On the IO threads explicitly. These inherit the caller's context
    // otherwise, and the caller is a composable's scope, which is the main
    // thread -- where Android refuses to open a socket at all. The refusal
    // arrives as an exception like any other and was being reported as the
    // network being down, on a tablet whose network was fine.
    val strict = async(Dispatchers.IO) { runCatching { nominatim(trimmed, near) } }
    val fuzzy = async(Dispatchers.IO) { runCatching { photon(trimmed, near) } }
    val first = strict.await()
    val second = fuzzy.await()

    if (first.isFailure && second.isFailure) {
        val reason = first.exceptionOrNull()
        return@coroutineScope SearchOutcome.Failed(
            if (reason is IOException) {
                // The usual cause in the field: the tablet is on the radio's
                // wifi, which reaches the aircraft and nothing else.
                "No answer - this needs an internet connection."
            } else {
                // Anything else is a fault worth naming rather than blaming
                // on the network, which is the mistake this line replaces.
                "Search failed: " + (reason?.javaClass?.simpleName ?: "unknown")
            },
        )
    }
    // The strict answers lead, and the fuzzy one only adds what the first did
    // not already find. That way a good exact match is never pushed down the
    // list by a loose one, while a query the strict engine cannot parse at all
    // is still answered.
    val merged = mutableListOf<Place>()
    (first.getOrDefault(emptyList()) + second.getOrDefault(emptyList())).forEach { place ->
        if (merged.none { it.isSameSpotAs(place) }) merged += place
    }
    if (merged.isEmpty()) {
        SearchOutcome.NothingFound
    } else {
        SearchOutcome.Found(merged.take(MAX_RESULTS))
    }
}

/**
 * Whether two answers are the same place named twice.
 *
 * Compared by position rather than by name, because the two gazetteers write
 * the same village differently and neither spelling is wrong.
 */
private fun Place.isSameSpotAs(other: Place): Boolean =
    abs(lat - other.lat) < SAME_SPOT_DEGREES && abs(lon - other.lon) < SAME_SPOT_DEGREES

private fun nominatim(query: String, near: Pair<Double, Double>?): List<Place> {
    val address = buildString {
        append("https://nominatim.openstreetmap.org/search?format=jsonv2&limit=")
        append(MAX_RESULTS)
        append("&q=")
        append(URLEncoder.encode(query, "UTF-8"))
        near?.let { (lat, lon) ->
            // Left, top, right, bottom. Not bounded, so this only prefers.
            append("&viewbox=")
            append(
                "%.4f,%.4f,%.4f,%.4f".format(
                    Locale.ROOT,
                    lon - NEAR_DEGREES, lat + NEAR_DEGREES,
                    lon + NEAR_DEGREES, lat - NEAR_DEGREES,
                ),
            )
        }
    }
    val results = JSONArray(fetch(address))
    return (0 until results.length()).mapNotNull { index ->
        val item = results.optJSONObject(index) ?: return@mapNotNull null
        val lat = item.optString("lat").toDoubleOrNull() ?: return@mapNotNull null
        val lon = item.optString("lon").toDoubleOrNull() ?: return@mapNotNull null
        val name = item.optString("display_name").ifBlank { return@mapNotNull null }
        Place(name, lat, lon)
    }
}

private fun photon(query: String, near: Pair<Double, Double>?): List<Place> {
    val address = buildString {
        append("https://photon.komoot.io/api/?limit=")
        append(MAX_RESULTS)
        append("&q=")
        append(URLEncoder.encode(query, "UTF-8"))
        near?.let { (lat, lon) ->
            append("%.4f".format(Locale.ROOT, lat).let { "&lat=$it" })
            append("%.4f".format(Locale.ROOT, lon).let { "&lon=$it" })
        }
    }
    val features = JSONObject(fetch(address)).optJSONArray("features") ?: return emptyList()
    return (0 until features.length()).mapNotNull { index ->
        val feature = features.optJSONObject(index) ?: return@mapNotNull null
        val point = feature.optJSONObject("geometry")?.optJSONArray("coordinates")
            ?: return@mapNotNull null
        val lon = point.optDouble(0).takeIf { !it.isNaN() } ?: return@mapNotNull null
        val lat = point.optDouble(1).takeIf { !it.isNaN() } ?: return@mapNotNull null
        val about = feature.optJSONObject("properties") ?: return@mapNotNull null
        // Built from the parts, because this one answers with fields rather
        // than the single written-out line the other returns.
        val name = listOfNotNull(
            about.optString("name").ifBlank { null },
            about.optString("city").ifBlank { null },
            about.optString("state").ifBlank { null },
            about.optString("country").ifBlank { null },
        ).joinToString(", ").ifBlank { return@mapNotNull null }
        Place(name, lat, lon)
    }
}

private fun fetch(address: String): String {
    val connection = (URL(address).openConnection() as HttpURLConnection).apply {
        // Nominatim turns away anything that does not say what it is.
        setRequestProperty("User-Agent", USER_AGENT)
        setRequestProperty("Accept", "application/json")
        connectTimeout = TIMEOUT_MS
        readTimeout = TIMEOUT_MS
    }
    return try {
        connection.inputStream.bufferedReader().use { it.readText() }
    } finally {
        connection.disconnect()
    }
}

private const val USER_AGENT =
    "MavGCS-Android (https://github.com/kolabuzlu/MavGCS-Android)"
private const val MAX_RESULTS = 6
private const val TIMEOUT_MS = 12_000

/** How far around the aircraft counts as nearby, in degrees. */
private const val NEAR_DEGREES = 1.5

/** About a hundred metres, inside which two answers are one place. */
private const val SAME_SPOT_DEGREES = 0.001

/**
 * A place to type a name, and the answers to it, over the top of the map.
 *
 * Nothing is looked up until the search key is pressed. Searching as the pilot
 * types would put a request on the network for every letter, which the free
 * gazetteers behind this ask not to be done and which on a tablet holding a
 * radio link is bandwidth better spent on the aircraft.
 */
@Composable
internal fun MapSearchBox(
    near: Pair<Double, Double>?,
    onPick: (Place) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    // One style for the field and its placeholder both. Given only a font
    // size, a Text keeps the theme's own line height -- twice the size here --
    // so a placeholder written that way stands taller than the field it sits
    // over, and the box shrank the moment anything was typed into it.
    val typing = TextStyle(color = scheme.onSurface, fontSize = 12.sp)
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<Place>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }

    fun run() {
        if (query.isBlank() || searching) return
        keyboard?.hide()
        searching = true
        results = emptyList()
        message = null
        scope.launch {
            when (val outcome = searchPlaces(query, near)) {
                is SearchOutcome.Found -> results = outcome.places
                SearchOutcome.NothingFound -> message = "Nothing found."
                is SearchOutcome.Failed -> message = outcome.why
            }
            searching = false
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black.copy(alpha = 0.72f))
                .padding(horizontal = 8.dp, vertical = 5.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp),
            )
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = typing,
                cursorBrush = SolidColor(scheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { run() }),
                modifier = Modifier.weight(1f),
                decorationBox = { field ->
                    // Laid over the field, not above it. Emitted as two
                    // children they stack, so the box stood one line taller
                    // while it was empty and shrank the moment anything was
                    // typed into it.
                    Box {
                        if (query.isEmpty()) {
                            Text(
                                text = "Search a place",
                                style = typing.copy(color = scheme.onSurfaceVariant),
                                maxLines = 1,
                            )
                        }
                        field()
                    }
                },
            )
            if (query.isNotEmpty()) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Clear the search",
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(15.dp)
                        .clickable {
                            query = ""
                            results = emptyList()
                            message = null
                        },
                )
            }
        }
        val note = when {
            searching -> "Searching\u2026"
            else -> message
        }
        if (note != null) {
            Text(
                text = note,
                fontSize = 11.sp,
                color = scheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.72f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        if (results.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.82f)),
            ) {
                results.forEach { place ->
                    Text(
                        text = place.name,
                        fontSize = 11.sp,
                        color = scheme.onSurface,
                        maxLines = 2,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                // The answers go once one is taken: the map is
                                // about to move to it, and a list left open
                                // over the picture is in the way.
                                results = emptyList()
                                message = null
                                onPick(place)
                            }
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                    )
                }
            }
        }
    }
}
