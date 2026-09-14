package com.mavgcs.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mavgcs.app.cache.CacheLimit
import com.mavgcs.app.cache.CacheStats
import com.mavgcs.app.cache.MAP_CACHE_LIMITS
import com.mavgcs.app.cache.MapTileCache
import com.mavgcs.app.cache.TERRAIN_CACHE_LIMITS
import com.mavgcs.app.cache.formatCacheSize
import com.mavgcs.app.mavlink.CesiumSettings
import com.mavgcs.app.mavlink.StreamRates
import com.mavgcs.app.mavlink.TelemetrySettings
import com.mavgcs.app.terrain.TerrainDiskCache
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How often the readout is refreshed, as on the desktop. */
private const val STATS_REFRESH_MS = 2_000L

/** Past this much of the limit the oldest items start being dropped. */
private const val CACHE_WARN_FRACTION = 0.9f

private val CacheBarColor = Color(0xFF37A8DB)
private val CacheBarFullColor = Color(0xFFE0A030)

private enum class CacheKind(val title: String, val noun: String) {
    MAP("Map", "tile"),
    TERRAIN("Terrain", "file"),
}

/**
 * Settings, which for now is the offline cache: how much map imagery and
 * elevation data to keep, how much of it is held, and a way to throw it away.
 *
 * Caching is what makes an area flown once keep working at a field with no
 * coverage, so the sizes are the pilot's to choose and the readout has to be
 * honest about what is actually saved.
 */
@Composable
fun SettingsDialog(onDismiss: () -> Unit, onRatesChanged: (StreamRates) -> Unit) {
    val context = LocalContext.current
    var mapLimit by remember { mutableStateOf(MapTileCache.limitMb()) }
    var terrainLimit by remember { mutableStateOf(TerrainDiskCache.limitMb()) }
    var mapStats by remember { mutableStateOf(CacheStats()) }
    var terrainStats by remember { mutableStateOf(CacheStats()) }
    var confirming by remember { mutableStateOf<CacheKind?>(null) }
    var cesiumToken by remember { mutableStateOf(CesiumSettings.token(context)) }
    var attitudeHz by remember { mutableStateOf(TelemetrySettings.attitudeHz(context)) }
    var positionHz by remember { mutableStateOf(TelemetrySettings.positionHz(context)) }
    var fullTelemetry by remember { mutableStateOf(TelemetrySettings.fullTelemetry(context)) }

    // A rate change takes effect at once on a live link rather than waiting for
    // the next connection, which is what you want when the picture is stuttering.
    fun pushRates() {
        onRatesChanged(TelemetrySettings.current(context))
    }
    var refresh by remember { mutableStateOf(0) }

    // Both sizes change as tiles stream in, not only when the control is
    // touched, so the readout is polled rather than pushed.
    LaunchedEffect(refresh) {
        while (true) {
            mapStats = withContext(Dispatchers.IO) { MapTileCache.stats() }
            terrainStats = withContext(Dispatchers.IO) { TerrainDiskCache.stats() }
            delay(STATS_REFRESH_MS)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = "3D view",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "The FPV view streams terrain and imagery from " +
                        "Cesium Ion, which needs a free account. Create one at " +
                        "cesium.com/ion and paste the access token here. It " +
                        "stays on this tablet.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = cesiumToken,
                    onValueChange = {
                        // Pasted tokens often arrive with a stray newline.
                        cesiumToken = it.trim()
                        CesiumSettings.setToken(context, cesiumToken)
                    },
                    label = { Text("Cesium Ion token", fontSize = 11.sp) },
                    placeholder = { Text("eyJhbGciOi…", fontSize = 12.sp) },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontSize = 12.sp),
                    modifier = Modifier.fillMaxWidth(),
                )
                HorizontalDivider()
                Text(
                    text = "Telemetry rates",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "A radio link has a fixed budget, and it drops whatever " +
                        "overflows without regard for what mattered. Asking for less " +
                        "means what you do ask for actually arrives.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                RateRow(
                    label = "Attitude",
                    hz = attitudeHz,
                    enabled = !fullTelemetry,
                ) {
                    attitudeHz = it
                    TelemetrySettings.setAttitudeHz(context, it)
                    pushRates()
                }
                RateRow(
                    label = "GPS position",
                    hz = positionHz,
                    enabled = !fullTelemetry,
                ) {
                    positionHz = it
                    TelemetrySettings.setPositionHz(context, it)
                    pushRates()
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.clickable {
                        fullTelemetry = !fullTelemetry
                        TelemetrySettings.setFullTelemetry(context, fullTelemetry)
                        pushRates()
                    },
                ) {
                    Checkbox(
                        checked = fullTelemetry,
                        onCheckedChange = null,
                    )
                    Text("Full MAVLink telemetry", fontSize = 13.sp)
                }
                Text(
                    text = "Stream everything the flight controller sends at its own " +
                        "rates, ignoring the two settings above. For fast links, and " +
                        "for capturing everything. Off by default: the reduced set is " +
                        "what fits a slow RC link.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider()
                Text(
                    text = "Offline cache",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Anywhere already saved keeps working with no " +
                        "connection. \"No Cache\" stops saving anything new and " +
                        "keeps what is there — only Clear removes it.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CacheSection(
                    kind = CacheKind.MAP,
                    limits = MAP_CACHE_LIMITS,
                    limitMb = mapLimit,
                    stats = mapStats,
                    onLimit = {
                        mapLimit = it
                        MapTileCache.setLimitMb(context, it)
                        refresh++
                    },
                    onClear = { confirming = CacheKind.MAP },
                )
                HorizontalDivider()
                CacheSection(
                    kind = CacheKind.TERRAIN,
                    limits = TERRAIN_CACHE_LIMITS,
                    limitMb = terrainLimit,
                    stats = terrainStats,
                    onLimit = {
                        terrainLimit = it
                        TerrainDiskCache.setLimitMb(context, it)
                        refresh++
                    },
                    onClear = { confirming = CacheKind.TERRAIN },
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )

    confirming?.let { kind ->
        ClearCacheDialog(
            kind = kind,
            stats = if (kind == CacheKind.MAP) mapStats else terrainStats,
            onDismiss = { confirming = null },
            onCleared = {
                confirming = null
                refresh++
            },
        )
    }
}

/** One telemetry rate, as the handful of choices a radio link can carry. */
@Composable
private fun RateRow(
    label: String,
    hz: Float,
    enabled: Boolean,
    onPick: (Float) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.weight(1f),
        )
        TelemetrySettings.CHOICES.forEach { choice ->
            val chosen = choice == hz
            OutlinedButton(
                onClick = { onPick(choice) },
                enabled = enabled,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                colors = if (chosen) {
                    ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    ButtonDefaults.outlinedButtonColors()
                },
            ) {
                Text(choice.roundToInt().toString(), fontSize = 12.sp)
            }
        }
        Text(
            text = "Hz",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CacheSection(
    kind: CacheKind,
    limits: List<CacheLimit>,
    limitMb: Int,
    stats: CacheStats,
    onLimit: (Int) -> Unit,
    onClear: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = kind.title,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            LimitDropdown(limits = limits, limitMb = limitMb, onPick = onLimit)
            OutlinedButton(
                onClick = onClear,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 12.dp,
                    vertical = 4.dp,
                ),
            ) {
                Text("Clear", fontSize = 12.sp)
            }
        }
        CacheBar(stats = stats)
        Text(
            text = statusLine(stats, kind.noun),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** "120 MB / 500 MB  1234 tiles", or what is stored when saving is off. */
private fun statusLine(stats: CacheStats, noun: String): String {
    val items = "${stats.items} $noun" + if (stats.items == 1L) "" else "s"
    return if (stats.limitBytes > 0) {
        "${formatCacheSize(stats.usedBytes)} / ${formatCacheSize(stats.limitBytes)}   $items"
    } else {
        "${formatCacheSize(stats.usedBytes)} stored   $items   (not saving)"
    }
}

@Composable
private fun CacheBar(stats: CacheStats) {
    val fraction = if (stats.limitBytes > 0) {
        (stats.usedBytes.toFloat() / stats.limitBytes.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)),
    ) {
        if (fraction > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(3.dp))
                    // Near the top the oldest items are already being dropped,
                    // which is worth seeing before an area goes missing.
                    .background(
                        if (fraction >= CACHE_WARN_FRACTION) {
                            CacheBarFullColor
                        } else {
                            CacheBarColor
                        },
                    ),
            )
        }
    }
}

@Composable
private fun LimitDropdown(limits: List<CacheLimit>, limitMb: Int, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = limits.firstOrNull { it.megabytes == limitMb }?.label ?: "$limitMb MB"
    Box {
        OutlinedButton(
            onClick = { open = true },
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 12.dp,
                vertical = 4.dp,
            ),
        ) {
            Text(label, fontSize = 12.sp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            limits.forEach { limit ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = limit.label,
                            fontWeight = if (limit.megabytes == limitMb) {
                                FontWeight.Bold
                            } else {
                                FontWeight.Normal
                            },
                        )
                    },
                    onClick = {
                        open = false
                        onPick(limit.megabytes)
                    },
                )
            }
        }
    }
}

/**
 * Clearing is worth a question. What it deletes was collected deliberately, and
 * getting it back needs the internet connection whose absence is the reason for
 * having it.
 */
@Composable
private fun ClearCacheDialog(
    kind: CacheKind,
    stats: CacheStats,
    onDismiss: () -> Unit,
    onCleared: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clear ${kind.title.lowercase()} cache?") },
        text = {
            Text(
                "This deletes ${formatCacheSize(stats.usedBytes)} of saved " +
                    "${kind.title.lowercase()} data. Anywhere that was working " +
                    "offline will need a connection again.",
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // Deleting thousands of tiles, or waiting on the sampler to
                    // let go of the terrain lock, is not main-thread work.
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            when (kind) {
                                CacheKind.MAP -> MapTileCache.clear()
                                CacheKind.TERRAIN -> TerrainDiskCache.clear()
                            }
                        }
                    }
                    onCleared()
                },
            ) {
                Text("Clear")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
