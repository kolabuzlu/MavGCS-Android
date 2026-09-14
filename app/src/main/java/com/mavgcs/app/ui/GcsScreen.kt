package com.mavgcs.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.BitmapFactory
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mavgcs.app.R
import com.mavgcs.app.mavlink.FlightModes
import com.mavgcs.app.mavlink.GcsCommand
import com.mavgcs.app.mavlink.GuidedAction
import com.mavgcs.app.mavlink.LinkType
import com.mavgcs.app.mavlink.PlaneModeButton
import com.mavgcs.app.cache.MapTileCache
import com.mavgcs.app.mavlink.MissionWaypoint
import com.mavgcs.app.mavlink.TelemetrySettings
import com.mavgcs.app.mavlink.UdpMode
import com.mavgcs.app.mavlink.VehicleState
import com.mavgcs.app.weather.RadarFrame
import com.mavgcs.app.weather.RadarTile
import com.mavgcs.app.weather.RainViewer
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.delay
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay
import java.util.Locale

/**
 * The left column has to fit without scrolling, and tablets differ a lot in
 * height: the emulator is 800dp tall in landscape, a Galaxy Tab A9 only 601dp.
 * Anything that does not fit is clipped rather than reachable, so these sizes
 * come from the height actually available instead of being fixed.
 */
private data class LayoutMetrics(
    /** Short screen: everything on it has to earn its height. */
    val compact: Boolean,
    val columnWidth: Dp,
    val outerPadding: Dp,
    val gap: Dp,
    val columnPadding: Dp,
    val armHeight: Dp,
    val controlHeight: Dp,
    val controlGap: Dp,
    val gridLabel: TextUnit,
    val gridValue: TextUnit,
    val gridRowGap: Dp,
)

private fun metricsFor(width: Dp, height: Dp): LayoutMetrics {
    // The control column earns more width on a wide screen; on a narrow one the
    // map needs what is left. Height decides everything else.
    val columnWidth = when {
        width >= 1200.dp -> 500.dp
        width >= 1050.dp -> 460.dp
        else -> 420.dp
    }
    return if (height < 700.dp) {
        LayoutMetrics(
            compact = true,
            columnWidth = columnWidth,
            outerPadding = 8.dp,
            gap = 6.dp,
            columnPadding = 10.dp,
            armHeight = 34.dp,
            controlHeight = 30.dp,
            controlGap = 6.dp,
            gridLabel = 8.sp,
            gridValue = 12.sp,
            gridRowGap = 6.dp,
        )
    } else {
        LayoutMetrics(
            compact = false,
            columnWidth = columnWidth,
            outerPadding = 12.dp,
            gap = 12.dp,
            columnPadding = 14.dp,
            armHeight = 44.dp,
            controlHeight = 38.dp,
            controlGap = 8.dp,
            gridLabel = 9.sp,
            gridValue = 14.sp,
            gridRowGap = 10.dp,
        )
    }
}

@Composable
fun GcsScreen(viewModel: GcsViewModel = viewModel()) {
    val vehicle by viewModel.vehicle.collectAsStateWithLifecycle()
    val form by viewModel.form.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme
    var flyTarget by remember { mutableStateOf<GeoPoint?>(null) }
    // The marker outlives the prompt: once the command has gone, the bar has
    // done its job, but the point the aircraft is heading for is worth keeping
    // on the map.
    var awaitingFly by remember { mutableStateOf(false) }
    var showFlyDialog by remember { mutableStateOf(false) }
    var showFlyToLatLon by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    // Points the pilot has clicked but not yet sent, and the batch that was
    // sent last -- kept apart so Update knows what is actually on the vehicle.
    var queueWaypoints by remember { mutableStateOf(false) }
    val waypointQueue = remember { mutableStateListOf<QueuedWaypoint>() }
    var sentMission by remember { mutableStateOf<List<QueuedWaypoint>>(emptyList()) }
    var editingWaypoint by remember { mutableStateOf<Int?>(null) }
    var missionAltitude by remember { mutableStateOf(DEFAULT_MISSION_ALTITUDE_M) }
    var showMissionAltitude by remember { mutableStateOf(false) }
    // Only the vehicle's acknowledgement settles what it is actually holding.
    LaunchedEffect(vehicle.missionAccepted) {
        if (vehicle.missionAccepted > 0) {
            sentMission = sentMission.map {
                it.copy(sentAltitudeM = it.effectiveAltitude(missionAltitude))
            }
        }
    }
    var followUav by remember { mutableStateOf(true) }
    var hybridMap by remember { mutableStateOf(false) }
    var showGuides by remember { mutableStateOf(true) }
    // Bumped to ask the map to drop its trail; the map owns the points.
    var clearTrailToken by remember { mutableStateOf(0) }
    var showWeather by remember { mutableStateOf(false) }
    var radarFrame by remember { mutableStateOf<RadarFrame?>(null) }

    // Frames age out of RainViewer's index, so the newest one has to be
    // re-read while the layer is on, and dropped when it is switched off.
    var radarTiles by remember { mutableStateOf<List<RadarTile>>(emptyList()) }
    LaunchedEffect(showWeather) {
        if (!showWeather) {
            radarFrame = null
            radarTiles = emptyList()
            return@LaunchedEffect
        }
        while (true) {
            RainViewer.latestFrame()?.let { radarFrame = it }
            delay(WEATHER_REFRESH_MS)
        }
    }

    // Keyed on the tile the aircraft sits in rather than its position: one tile
    // spans hundreds of kilometres, so this refetches when the frame rolls over
    // or the aircraft crosses a tile, not on every telemetry update.
    val radarKey = vehicle.lat?.let { lat ->
        vehicle.lon?.let { lon ->
            val zoom = RainViewer.MAX_RADAR_ZOOM
            "${RainViewer.tileX(lon, zoom)}/${RainViewer.tileY(lat, zoom)}"
        }
    }
    LaunchedEffect(radarFrame, radarKey) {
        val frame = radarFrame
        val lat = vehicle.lat
        val lon = vehicle.lon
        if (frame == null || lat == null || lon == null) {
            radarTiles = emptyList()
            return@LaunchedEffect
        }
        val latSpan = WEATHER_RADIUS_METRES / METRES_PER_DEGREE_LAT
        val lonSpan = latSpan / cos(Math.toRadians(lat)).coerceAtLeast(0.01)
        radarTiles = RainViewer.tilesCovering(
            frame = frame,
            northLat = lat + latSpan,
            southLat = lat - latSpan,
            westLon = lon - lonSpan,
            eastLon = lon + lonSpan,
        )
    }
    // Named apart from 'context', which Kotlin now treats as a soft keyword
    // in a position like this.
    val appContext = LocalContext.current
    val configuration = LocalConfiguration.current
    val metrics = metricsFor(configuration.screenWidthDp.dp, configuration.screenHeightDp.dp)
    // The compass and the radar share this. A short screen cannot carry two
    // 200dp dials above the credit line, so both come down together rather
    // than one of them being dropped.
    val instrumentSize = if (metrics.compact) 150.dp else 200.dp

    Surface(modifier = Modifier.fillMaxSize(), color = scheme.background) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(metrics.outerPadding),
            horizontalArrangement = Arrangement.spacedBy(metrics.gap),
        ) {
            Column(
                modifier = Modifier
                    .width(metrics.columnWidth)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(16.dp))
                    .background(scheme.surface)
                    .padding(metrics.columnPadding),
                verticalArrangement = Arrangement.spacedBy(metrics.gap),
            ) {
                ArmPad(
                    enabled = vehicle.linkUp,
                    armed = vehicle.armed,
                    metrics = metrics,
                    onCommand = viewModel::command,
                )
                FlightModePanel(
                    enabled = vehicle.linkUp,
                    currentMode = vehicle.mode,
                    metrics = metrics,
                    onSelect = viewModel::setFlightMode,
                    onFlyToLatLon = { showFlyToLatLon = true },
                )

                GuidedControlPanel(
                    enabled = vehicle.linkUp,
                    metrics = metrics,
                    onSend = viewModel::sendGuided,
                )
                // Weighted rather than fixed: the HUD takes whatever height the
                // column has left, so a taller tablet fills instead of leaving a
                // gap under the data grid, and a short one still just fits.
                FlightHud(
                    vehicle = vehicle,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
                TelemetryGrid(vehicle, metrics)
            }
            Column(
                modifier = Modifier
                    .weight(1.35f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .height(TopPanelHeight),
                        verticalArrangement = Arrangement.spacedBy(metrics.controlGap),
                    ) {
                        MessagesPanel(
                            statusLog = vehicle.statusLog,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        )
                        SystemsPanel(
                            vehicle = vehicle,
                                                fontSize = if (metrics.compact) 9.sp else 10.sp,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    ConnectionPanel(
                        form = form,
                        onType = viewModel::setType,
                        onHost = viewModel::setHost,
                        onUdpMode = viewModel::setUdpMode,
                        onPort = viewModel::setPort,
                        onToggle = {
                            viewModel.toggleConnection(TelemetrySettings.current(appContext))
                        },
                        onSettings = { showSettings = true },
                        modifier = Modifier
                            .width(340.dp)
                            .height(TopPanelHeight),
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .border(1.dp, scheme.outline, RoundedCornerShape(16.dp)),
                ) {
                    VehicleMap(
                        vehicle = vehicle,
                        flyTarget = flyTarget,
                        followUav = followUav,
                        hybrid = hybridMap,
                        showGuides = showGuides,
                        clearTrailToken = clearTrailToken,
                        missionPoints = waypointQueue + sentMission,
                        queuedCount = waypointQueue.size,
                        missionAltitudeM = missionAltitude,
                        // Only a point the aircraft actually holds can be the
                        // one it is flying to, and its item 1 is our first.
                        activeWaypoint = vehicle.currentWaypointSeq
                            ?.minus(1)
                            ?.takeIf { it in sentMission.indices }
                            ?.plus(waypointQueue.size),
                        onWaypointTap = { editingWaypoint = it },
                        radarTiles = radarTiles,
                        onMapTap = { point ->
                            if (queueWaypoints) {
                                waypointQueue.add(QueuedWaypoint(point))
                            } else {
                                flyTarget = point
                                awaitingFly = true
                            }
                        },
                    )
                    // One row across the top of the map. Hybrid is pushed to
                    // the far edge: it picks the imagery, where the rest act on
                    // what is drawn over it.
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .fillMaxWidth()
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MapToggle("Follow UAV", followUav) { followUav = !followUav }
                        MapToggle("Vectors", showGuides) { showGuides = !showGuides }
                        MapButton("Clear Trail") { clearTrailToken++ }
                        MapToggle("Weather", showWeather) { showWeather = !showWeather }
                        MissionControls(
                            queueing = queueWaypoints,
                            queued = waypointQueue.size,
                            hasSentMission = sentMission.isNotEmpty(),
                            onToggleQueue = { queueWaypoints = !queueWaypoints },
                            onStart = { showMissionAltitude = true },
                            onUpdate = {
                                viewModel.uploadMission(
                                    waypoints = sentMission.map {
                                        MissionWaypoint(
                                            it.point.latitude,
                                            it.point.longitude,
                                            it.altitudeM,
                                        )
                                    },
                                    altitudeM = missionAltitude,
                                    restart = false,
                                )
                            },
                            onClear = {
                                waypointQueue.clear()
                                sentMission = emptyList()
                            },
                            onClearVehicle = {
                                waypointQueue.clear()
                                sentMission = emptyList()
                                viewModel.clearMission()
                            },
                        )
                        Spacer(Modifier.weight(1f))
                        MapToggle("Hybrid", hybridMap) { hybridMap = !hybridMap }
                    }
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        EtaReadout(vehicle = vehicle)
                        MapCoordinates(lat = vehicle.lat, lon = vehicle.lon)
                        MapCredit()
                    }
                    flyTarget?.takeIf { awaitingFly }?.let { target ->
                        FlyHereBar(
                            target = target,
                            enabled = vehicle.linkUp,
                            onFly = { showFlyDialog = true },
                            onClear = {
                                flyTarget = null
                                awaitingFly = false
                            },
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    }
                    // Compass over radar over the credit line, one gutter
                    // between each, so the corner reads as a single stack of
                    // instruments rather than three things that happen to be
                    // near each other. On a short screen they shrink together:
                    // two full-size dials would not fit above the attribution.
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp),
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CompassRose(vehicle = vehicle, size = instrumentSize)
                        TerrainRadar(vehicle = vehicle, size = instrumentSize)
                        Text(
                            text = "Esri, Maxar, Earthstar Geographics",
                            fontSize = 9.sp,
                            color = Color.White.copy(alpha = 0.8f),
                            modifier = Modifier
                                .background(Color.Black.copy(alpha = 0.45f))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }

    if (showMissionAltitude) {
        MissionAltitudeDialog(
            waypoints = waypointQueue.size,
            altitudeM = missionAltitude,
            onDismiss = { showMissionAltitude = false },
            onStart = { altitude ->
                showMissionAltitude = false
                missionAltitude = altitude
                viewModel.uploadMission(
                    waypoints = waypointQueue.map {
                        MissionWaypoint(it.point.latitude, it.point.longitude, it.altitudeM)
                    },
                    altitudeM = altitude,
                )
                // The markers stay up as a record of what was sent; only Clear
                // takes them away. Starting a mission also supersedes any
                // pending single-point target.
                sentMission = waypointQueue.toList()
                waypointQueue.clear()
                flyTarget = null
                awaitingFly = false
            },
        )
    }

    editingWaypoint?.let { index ->
        val queued = waypointQueue.size
        val waypoint = waypointQueue.getOrNull(index)
            ?: sentMission.getOrNull(index - queued)
        if (waypoint == null) {
            editingWaypoint = null
        } else {
            WaypointAltitudeDialog(
                number = index + 1,
                altitudeM = waypoint.altitudeM ?: missionAltitude,
                onDismiss = { editingWaypoint = null },
                onApply = { altitude ->
                    editingWaypoint = null
                    if (index < queued) {
                        waypointQueue[index] = waypoint.copy(altitudeM = altitude)
                    } else {
                        sentMission = sentMission.toMutableList().also {
                            it[index - queued] = waypoint.copy(altitudeM = altitude)
                        }
                    }
                },
            )
        }
    }

    if (showSettings) {
        SettingsDialog(
            onDismiss = { showSettings = false },
            onRatesChanged = viewModel::applyStreamRates,
        )
    }

    if (showFlyToLatLon) {
        FlyToLatLonDialog(
            vehicle = vehicle,
            onDismiss = { showFlyToLatLon = false },
            onConfirm = { lat, lon, altitude ->
                showFlyToLatLon = false
                flyTarget = GeoPoint(lat, lon)
                awaitingFly = false
                viewModel.flyTo(lat, lon, altitude)
            },
        )
    }

    val target = flyTarget
    if (showFlyDialog && target != null) {
        FlyHereDialog(
            target = target,
            currentAltitude = vehicle.altRelM,
            onDismiss = { showFlyDialog = false },
            onConfirm = { altitude ->
                showFlyDialog = false
                awaitingFly = false
                viewModel.flyTo(target.latitude, target.longitude, altitude)
            },
        )
    }
}

@Composable
private fun MapCoordinates(lat: Double?, lon: Double?, modifier: Modifier = Modifier) {
    Row(
        // Same ground and corner as the ETA and credit boxes it stacks with,
        // so the three read as one corner rather than three separate labels.
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        MapCoordinate("LAT", lat)
        MapCoordinate("LON", lon)
    }
}

/** Modes that fly the aircraft to a place, and so have an arrival to time. */
private val EtaNavModes = setOf("AUTO", "GUIDED", "RTL", "AUTOLAND", "QRTL")

private const val ETA_MIN_GS_MPS = 1.0f
private const val ETA_MIN_DIST_M = 1.0f
private const val ETA_MAX_SECONDS = 100f * 3600f

/**
 * Time to the waypoint, from distance and ground speed.
 *
 * The box is always up, dashed when there is no arrival to time. A box that
 * comes and goes looks like a fault, and its absence is indistinguishable
 * from a reading of nothing; a dash plainly says the question has no answer
 * right now.
 */
@Composable
private fun EtaReadout(vehicle: VehicleState, modifier: Modifier = Modifier) {
    val value = etaValue(vehicle)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text("ETA to WP : ", color = MapReadoutColor, fontSize = 11.sp, maxLines = 1)
        Text(
            text = value,
            color = MapReadoutColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

/**
 * The figure, or a dash where there is none to give.
 *
 * Dashed rather than guessed where the arithmetic runs away: standing still
 * divides by nothing, and no waypoint reports zero distance, which would read
 * as "arrived" if it were let through.
 */
private fun etaValue(vehicle: VehicleState): String {
    if (!vehicle.linkUp || vehicle.mode !in EtaNavModes) {
        return "--"
    }
    val distance = vehicle.distToWpM ?: return "--"
    val groundSpeed = vehicle.groundSpeedMs ?: return "--"
    if (distance < ETA_MIN_DIST_M || groundSpeed < ETA_MIN_GS_MPS) {
        return "--"
    }
    val seconds = distance / groundSpeed
    if (seconds > ETA_MAX_SECONDS) {
        return "--"
    }
    return etaClock(seconds)
}

/** m:ss under an hour, h:mm:ss over it. */
private fun etaClock(seconds: Float): String {
    val total = seconds.roundToInt()
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val secs = total % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(Locale.ROOT, hours, minutes, secs)
    } else {
        "%d:%02d".format(Locale.ROOT, minutes, secs)
    }
}

@Composable
private fun MapCredit(modifier: Modifier = Modifier) {
    Text(
        text = "Created by Derin Hakan Karakurt",
        // The same weight as the LAT / LON captions above it: a credit line
        // should not read louder than the coordinates it sits under.
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 11.sp,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun MapCoordinate(label: String, value: Double?) {
    val scheme = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, fontSize = 11.sp, color = scheme.onSurfaceVariant)
        Text(
            text = value?.let { "%.6f".format(Locale.ROOT, it) } ?: NO_DATA,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            color = scheme.onSurface,
        )
    }
}

@Composable
private fun MapButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(scheme.surface.copy(alpha = 0.9f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Accented rather than boxed: this one acts instead of holding a state.
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = scheme.primary)
    }
}

@Composable
private fun MapToggle(
    label: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(scheme.surface.copy(alpha = 0.9f))
            .clickable(onClick = onToggle)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = if (checked) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
            contentDescription = null,
            tint = if (checked) scheme.primary else scheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Text(label, fontSize = 12.sp, color = scheme.onSurface)
    }
}

@Composable
private fun FlyHereBar(
    target: GeoPoint,
    enabled: Boolean,
    onFly: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .padding(bottom = 26.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(scheme.surface.copy(alpha = 0.92f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "%.6f, %.6f".format(Locale.ROOT, target.latitude, target.longitude),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = scheme.onSurface,
        )
        Button(
            onClick = onFly,
            enabled = enabled,
            modifier = Modifier.height(38.dp),
            shape = ControlCorner,
            colors = ButtonDefaults.buttonColors(
                containerColor = scheme.secondary,
                contentColor = scheme.onSecondary,
            ),
        ) {
            Text("FLY HERE", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        TextButton(onClick = onClear) {
            Text("Clear", fontSize = 12.sp)
        }
    }
}

@Composable
private fun FlyHereDialog(
    target: GeoPoint,
    currentAltitude: Float?,
    onDismiss: () -> Unit,
    onConfirm: (Float) -> Unit,
) {
    var text by remember {
        mutableStateOf(currentAltitude?.takeIf { it > 1f }?.let { "%.0f".format(Locale.ROOT, it) } ?: "100")
    }
    val altitude = text.toFloatOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Fly to here") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "%.6f, %.6f".format(Locale.ROOT, target.latitude, target.longitude),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { entered ->
                        text = entered.filter { it.isDigit() || it == '.' }.take(6)
                    },
                    label = { Text("Altitude above home (m)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Text(
                    text = "Switches the vehicle to GUIDED and flies to this point.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { altitude?.let(onConfirm) },
                enabled = altitude != null && altitude > 0f,
            ) {
                Text("Fly")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private const val NO_DATA = "--"

/** Messages and Connection share one height so their bottoms line up. */
private val TopPanelHeight = 184.dp

private data class TelemetryField(val label: String, val value: String)

@Composable
private fun TelemetryGrid(vehicle: VehicleState, metrics: LayoutMetrics) {
    val fields = listOf(
        TelemetryField("AirSpeed (m/s)", vehicle.airSpeedMs.format(1)),
        TelemetryField("GroundSpeed (m/s)", vehicle.groundSpeedMs.format(1)),
        TelemetryField("Vertical Speed (m/s)", vehicle.climbMs.format(1)),
        TelemetryField("Altitude (m)", vehicle.altRelM.format(1)),
        TelemetryField("Rangefinder (m)", vehicle.rangefinderM.format(2)),
        TelemetryField("Dist to Home (m)", vehicle.distToHomeM.format(0)),
        TelemetryField("Dist to WP (m)", vehicle.distToWpM.format(0)),
        TelemetryField("Sat Count", vehicle.satellites?.takeIf { it > 0 }?.toString() ?: NO_DATA),
        TelemetryField("Roll (deg)", vehicle.rollDeg.format(1)),
        TelemetryField("Pitch (deg)", vehicle.pitchDeg.format(1)),
        TelemetryField("Yaw (deg)", vehicle.yawDeg.format(1)),
        TelemetryField("Gps HDOP", vehicle.hdop.format(2)),
        TelemetryField("Wind Direction (deg)", vehicle.windDirectionDeg.format(0)),
        TelemetryField("Wind Velocity (kph)", vehicle.windSpeedMs?.times(3.6f).format(1)),
        TelemetryField("QNH", vehicle.qnhHpa.format(1)),
        TelemetryField("Terrain Alt (m)", vehicle.terrainAltM.format(1)),
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 4.dp, vertical = metrics.gridRowGap),
        verticalArrangement = Arrangement.spacedBy(metrics.gridRowGap),
    ) {
        fields.chunked(4).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                row.forEach { field ->
                    TelemetryCell(field, metrics, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun TelemetryCell(
    field: TelemetryField,
    metrics: LayoutMetrics,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = field.label,
            fontSize = metrics.gridLabel,
            lineHeight = metrics.gridLabel * 1.25f,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
        Text(
            text = field.value,
            fontSize = metrics.gridValue,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

/** How long a button must be held before its hold action fires. */
private const val HOLD_MILLIS = 3000

@Composable
private fun ArmPad(
    enabled: Boolean,
    armed: Boolean,
    metrics: LayoutMetrics,
    onCommand: (GcsCommand) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(metrics.controlGap)) {
        // Each button lights for the state the vehicle is actually in rather
        // than for the action it performs: green while armed, red while not, so
        // a glance says whether the props are live.
        HoldButton(
            label = "ARM",
            holdLabel = "FORCE\u2026",
            enabled = enabled,
            containerColor = if (armed) scheme.primary else scheme.surfaceVariant,
            contentColor = if (armed) scheme.onPrimary else scheme.onSurface,
            border = if (armed) null else BorderStroke(1.dp, scheme.outline),
            height = metrics.armHeight,
            onHold = { onCommand(GcsCommand.FORCE_ARM) },
            modifier = Modifier.weight(1f),
            onTap = { onCommand(GcsCommand.ARM) },
        )
        // Hold-only, like DISARM: the hold is the confirmation, and there is
        // no tap action to fire by accident. Grey rather than red -- it reports
        // no state, it just does a job on the ground.
        HoldButton(
            label = "PREFLIGHT CALIBRATION",
            holdLabel = HOLD_ELLIPSIS,
            // Two words on two lines, so the full name fits a third of the row.
            fontSize = if (metrics.compact) 9.sp else 10.sp,
            enabled = enabled,
            containerColor = scheme.surfaceVariant,
            contentColor = scheme.onSurface,
            border = BorderStroke(1.dp, scheme.outline),
            height = metrics.armHeight,
            onHold = { onCommand(GcsCommand.PREFLIGHT_CALIBRATION) },
            modifier = Modifier.weight(1f),
        )
        HoldButton(
            label = "DISARM",
            holdLabel = "HOLD\u2026",
            enabled = enabled,
            containerColor = if (armed) scheme.surfaceVariant else scheme.error,
            contentColor = if (armed) scheme.onSurface else Color.White,
            border = if (armed) BorderStroke(1.dp, scheme.outline) else null,
            height = metrics.armHeight,
            onHold = { onCommand(GcsCommand.DISARM) },
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * A button whose hold does something the tap does not: disarming cuts the
 * motors and force arming skips the pre-arm checks, so neither should be one
 * careless press away. The hold fires only on reaching the full duration, and
 * releasing early sends nothing.
 *
 * A completed hold also has to swallow the tap, because the release that ends
 * it still reaches onClick and would otherwise fire both actions.
 */
@Composable
private fun HoldButton(
    label: String,
    holdLabel: String,
    enabled: Boolean,
    containerColor: Color,
    contentColor: Color,
    border: BorderStroke?,
    height: Dp,
    onHold: () -> Unit,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 13.sp,
    onTap: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val holding = pressed && enabled
    var holdFired by remember { mutableStateOf(false) }
    val progress by animateFloatAsState(
        targetValue = if (holding) 1f else 0f,
        animationSpec = if (holding) {
            tween(durationMillis = HOLD_MILLIS, easing = LinearEasing)
        } else {
            snap()
        },
        finishedListener = { value ->
            if (value >= 1f) {
                holdFired = true
                onHold()
            }
        },
        label = "hold",
    )
    Button(
        onClick = {
            if (holdFired) {
                holdFired = false
            } else {
                onTap?.invoke()
            }
        },
        enabled = enabled,
        interactionSource = interaction,
        modifier = modifier.height(height),
        shape = ControlCorner,
        border = border,
        contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (progress > 0f) {
                // White works as the fill over both the red and the grey state.
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxHeight()
                        .fillMaxWidth(progress)
                        .background(Color.White.copy(alpha = 0.28f)),
                )
            }
            Text(
                text = if (holding) holdLabel else label,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 4.dp),
                fontSize = fontSize,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                lineHeight = fontSize * 1.1f,
            )
        }
    }
}

@Composable
internal fun GroupBox(
    title: String,
    modifier: Modifier = Modifier,
    contentGap: Dp = 8.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current
    var label by remember { mutableStateOf(IntSize.Zero) }
    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = TitleLine)
                .drawBehind { drawNotchedBorder(scheme.outline, label.width.toFloat()) }
                .padding(start = 10.dp, end = 10.dp, top = 12.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(contentGap),
            content = content,
        )
        Text(
            text = title,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = scheme.onSurfaceVariant,
            // The inherited line height is set for body text and would pad the
            // box well above these 11sp caps, putting the line below the middle
            // of the letters. Letting the font say how tall its own line is
            // makes the box and the letters concentric.
            style = LocalTextStyle.current.copy(lineHeight = TextUnit.Unspecified),
            // Sitting astride the top line, which is cut away behind it. A
            // filled backing would do the same job for the line but would also
            // paint over whatever the panel happens to be next to.
            modifier = Modifier
                .offset(
                    x = TitleInset,
                    // Against the middle of the stroke, not the edge of the box
                    // it is drawn inside: half a stroke of daylight between the
                    // two is visible at this size.
                    y = TitleLine + BorderWidth / 2 -
                        with(density) { label.height.toDp() } / 2,
                )
                .onSizeChanged { label = it },
        )
    }
}

/** Where the top line sits, measured from the top of the panel. */
private val TitleLine = 7.dp

private val BorderWidth = 1.dp

/** How far along that line the title starts. */
private val TitleInset = 12.dp

/** Clear space either side of the title, so the line does not crowd it. */
private val TitleGap = 5.dp

/** The panel outline, broken where the title crosses it. */
private fun DrawScope.drawNotchedBorder(color: Color, labelWidth: Float) {
    val stroke = BorderWidth.toPx()
    val half = stroke / 2
    val outline = androidx.compose.ui.graphics.Path().apply {
        addRoundRect(
            RoundRect(
                rect = androidx.compose.ui.geometry.Rect(
                    half,
                    half,
                    size.width - half,
                    size.height - half,
                ),
                cornerRadius = CornerRadius(8.dp.toPx()),
            ),
        )
    }
    if (labelWidth <= 0f) {
        drawPath(outline, color, style = Stroke(stroke))
        return
    }
    val notch = androidx.compose.ui.graphics.Path().apply {
        addRect(
            androidx.compose.ui.geometry.Rect(
                left = TitleInset.toPx() - TitleGap.toPx(),
                top = -stroke,
                right = TitleInset.toPx() + labelWidth + TitleGap.toPx(),
                bottom = stroke * 2,
            ),
        )
    }
    clipPath(notch, ClipOp.Difference) {
        drawPath(outline, color, style = Stroke(stroke))
    }
}

private val SegmentedHeight = 34.dp

/**
 * A row of mutually exclusive choices, sized to its labels.
 *
 * Chips would say the same thing, but each group of them wants a row to
 * itself, and the connection panel has no spare height to give: whatever it
 * takes comes off the map.
 */
@Composable
private fun <T> SegmentedChoice(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = Modifier
            .height(SegmentedHeight)
            .clip(shape)
            .border(1.dp, scheme.outline, shape),
    ) {
        options.forEachIndexed { index, (value, label) ->
            if (index > 0) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(1.dp)
                        .background(scheme.outline),
                )
            }
            val chosen = value == selected
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxHeight()
                    .background(if (chosen) scheme.primary else Color.Transparent)
                    .clickable { onSelect(value) }
                    .padding(horizontal = 10.dp),
            ) {
                Text(
                    text = label,
                    fontSize = 12.sp,
                    fontWeight = if (chosen) FontWeight.Medium else FontWeight.Normal,
                    color = if (chosen) scheme.onPrimary else scheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ConnectionPanel(
    form: ConnectionForm,
    onType: (LinkType) -> Unit,
    onHost: (String) -> Unit,
    onPort: (String) -> Unit,
    onToggle: () -> Unit,
    onUdpMode: (UdpMode) -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    GroupBox(
        title = "Connection",
        modifier = modifier,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SegmentedChoice(
                options = listOf(LinkType.UDP to "UDP", LinkType.TCP to "TCP"),
                selected = form.type,
                onSelect = onType,
            )
            // Which way round the UDP link goes. TCP has only one answer, so
            // the question is not asked there.
            if (form.type == LinkType.UDP) {
                SegmentedChoice(
                    options = listOf(
                        UdpMode.LISTEN to "Listen",
                        UdpMode.CONNECT to "Connect To",
                    ),
                    selected = form.udpMode,
                    onSelect = onUdpMode,
                )
            }
            Spacer(Modifier.weight(1f))
            OutlinedButton(
                onClick = onSettings,
                modifier = Modifier.size(SegmentedHeight),
                contentPadding = PaddingValues(0.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Settings",
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = form.host,
                onValueChange = onHost,
                label = {
                    Text(
                        text = if (form.hostEditable) "Host" else "Bind address",
                        fontSize = 11.sp,
                    )
                },
                singleLine = true,
                modifier = Modifier.weight(1.6f),
                enabled = !form.listening && form.hostEditable,
            )
            OutlinedTextField(
                value = form.port,
                onValueChange = onPort,
                label = { Text("Port", fontSize = 11.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f),
                enabled = !form.listening,
            )
        }
        Button(
            onClick = onToggle,
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp),
            shape = ControlCorner,
            contentPadding = PaddingValues(horizontal = 8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (form.listening) scheme.error else scheme.primary,
                // White on the red. The palette's onError is dark, which reads
                // as a disabled button rather than the live action it is.
                contentColor = if (form.listening) Color.White else scheme.onPrimary,
            ),
        ) {
            Text(if (form.listening) "Disconnect" else "Connect", fontSize = 14.sp)
        }
    }
}

@Composable
private fun MessagesPanel(statusLog: List<String>, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    GroupBox(
        title = "Messages",
        modifier = modifier,
    ) {
        val scroll = rememberScrollState()
        // Keep the newest line in view; the vehicle can produce a burst of them
        // during a pre-arm check, and the panel is only a few lines tall.
        LaunchedEffect(statusLog.size) {
            scroll.animateScrollTo(scroll.maxValue)
        }
        val watermark = ImageBitmap.imageResource(R.drawable.mavgcsback)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                // Right-aligned and behind the log, as on the desktop. Drawn on
                // the box rather than on the scrolling column so it stays put
                // while messages run past it.
                .drawBehind {
                    val side = size.height
                    drawImage(
                        image = watermark,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(watermark.width, watermark.height),
                        dstOffset = IntOffset((size.width - side).roundToInt(), 0),
                        dstSize = IntSize(side.roundToInt(), side.roundToInt()),
                        alpha = WATERMARK_ALPHA,
                    )
                },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll),
            ) {
                statusLog.forEach { line ->
                    Text(line, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }
        }
    }
}

/** Faint enough that a message never has to compete with it. */
private const val WATERMARK_ALPHA = 0.55f

/** Shown while a hold is in progress. */
private const val HOLD_ELLIPSIS = "HOLD…"

@Composable
private fun FlightModePanel(
    enabled: Boolean,
    currentMode: String,
    metrics: LayoutMetrics,
    onSelect: (PlaneModeButton) -> Unit,
    onFlyToLatLon: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    GroupBox(
        title = "Flight Mode",
        modifier = Modifier.fillMaxWidth(),
        contentGap = metrics.controlGap,
    ) {
        FlightModes.planeModeRows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(metrics.controlGap)) {
                row.forEach { mode ->
                    ModeButton(
                        label = mode.label,
                        active = currentMode == mode.label,
                        enabled = enabled,
                        height = metrics.controlHeight,
                        modifier = Modifier.weight(1f),
                        // RTL is the get-home-now action, so it carries the
                        // warning colour until it is the mode actually engaged.
                        alert = mode.label == "RTL",
                        onClick = { onSelect(mode) },
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(metrics.controlGap)) {
            ModeButton(
                label = FlightModes.planeGuidedMode.label,
                active = currentMode == FlightModes.planeGuidedMode.label,
                enabled = enabled,
                height = metrics.controlHeight,
                modifier = Modifier.weight(1f),
                onClick = { onSelect(FlightModes.planeGuidedMode) },
            )
            // Two units wide, filling the space the row would otherwise leave.
            Button(
                onClick = onFlyToLatLon,
                enabled = enabled,
                modifier = Modifier
                    .weight(2f)
                    .height(metrics.controlHeight),
                shape = ControlCorner,
                contentPadding = PaddingValues(horizontal = 4.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = FlyToBlue,
                    contentColor = Color.White,
                    disabledContainerColor = scheme.surfaceVariant.copy(alpha = 0.4f),
                    disabledContentColor = scheme.onSurfaceVariant.copy(alpha = 0.5f),
                ),
            ) {
                Icon(
                    imageVector = Icons.Filled.Public,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "FLY TO LAT / LON",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * The mission controls, on one ground rather than as loose chips: they are one
 * job -- collect points, send them, take them back -- and reading as a single
 * panel says so, the way the desktop's boxed group does.
 *
 * The actions appear only while they can do something. On a map overlay a dead
 * control is worse than an absent one, so the switch that carries the count is
 * the only permanent part.
 */
@Composable
private fun MissionControls(
    queueing: Boolean,
    queued: Int,
    hasSentMission: Boolean,
    onToggleQueue: () -> Unit,
    onStart: () -> Unit,
    onUpdate: () -> Unit,
    onClear: () -> Unit,
    onClearVehicle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(scheme.surface.copy(alpha = 0.9f))
            .padding(horizontal = 10.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Queueing takes over the map click, so it reads as a mode rather than
        // an action, and the count lives on the switch that produced it.
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable(onClick = onToggleQueue)
                .padding(horizontal = 2.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = if (queueing) {
                    Icons.Filled.CheckBox
                } else {
                    Icons.Filled.CheckBoxOutlineBlank
                },
                contentDescription = null,
                tint = if (queueing) scheme.primary else scheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = if (queued == 0) "Queue WPs" else "Queue WPs ($queued)",
                fontSize = 12.sp,
                color = scheme.onSurface,
            )
        }
        if (queued > 0) {
            MissionAction("Start Mission", onStart)
        }
        if (hasSentMission) {
            MissionAction("Update", onUpdate)
        }
        if (queued > 0 || hasSentMission) {
            MissionClearAction(onClick = onClear, onHold = onClearVehicle)
        }
    }
}

@Composable
private fun MissionAction(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 4.dp),
    )
}

/**
 * Clicking tidies the map; holding also erases the mission the vehicle is
 * actually storing, which is not something to be one stray tap away from.
 */
@Composable
private fun MissionClearAction(onClick: () -> Unit, onHold: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    var holdFired by remember { mutableStateOf(false) }
    val progress by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = if (pressed) {
            tween(durationMillis = HOLD_MILLIS, easing = LinearEasing)
        } else {
            snap()
        },
        finishedListener = { value ->
            if (value >= 1f) {
                holdFired = true
                onHold()
            }
        },
        label = "missionClear",
    )
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable(interactionSource = interaction, indication = null) {
                if (holdFired) {
                    holdFired = false
                } else {
                    onClick()
                }
            },
    ) {
        if (progress > 0f) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .fillMaxWidth(progress)
                    .background(scheme.error.copy(alpha = 0.35f)),
            )
        }
        Text(
            text = "Clear",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = scheme.primary,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        )
    }
}

/** One point of a mission the pilot has placed, with the altitude it flies at. */
private data class QueuedWaypoint(
    val point: GeoPoint,
    /** Null means the point has no altitude of its own and flies the mission's. */
    val altitudeM: Float? = null,
    /** The altitude the vehicle actually acknowledged, once it has. */
    val sentAltitudeM: Float? = null,
) {
    fun effectiveAltitude(missionAltitudeM: Float): Float = altitudeM ?: missionAltitudeM

    /** Edited since it was sent, so the aircraft is still flying the old one. */
    fun isPending(missionAltitudeM: Float): Boolean =
        sentAltitudeM != null && effectiveAltitude(missionAltitudeM) != sentAltitudeM
}

/**
 * The altitude of one waypoint. Editing a point that has already been sent
 * changes only what Update would upload -- the aircraft keeps flying what it
 * has until that is pressed.
 */
@Composable
private fun WaypointAltitudeDialog(
    number: Int,
    altitudeM: Float,
    onDismiss: () -> Unit,
    onApply: (Float) -> Unit,
) {
    var text by remember { mutableStateOf(altitudeM.roundToInt().toString()) }
    val entered = text.toFloatOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Waypoint $number") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CoordinateField("Altitude above home (m)", text) { text = it }
                Text(
                    text = "Press Update to send the changed altitudes to the aircraft.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { entered?.let(onApply) },
                enabled = entered != null && entered > 0f,
            ) {
                Text("Set")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** What a mission flies at until the pilot says otherwise. */
private const val DEFAULT_MISSION_ALTITUDE_M = 150f

/**
 * The one altitude the whole mission flies at. Asked for at the point of
 * sending rather than per waypoint, which is what the desktop does too.
 */
@Composable
private fun MissionAltitudeDialog(
    waypoints: Int,
    altitudeM: Float,
    onDismiss: () -> Unit,
    onStart: (Float) -> Unit,
) {
    var text by remember { mutableStateOf(altitudeM.roundToInt().toString()) }
    val entered = text.toFloatOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Start mission") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Fly through $waypoints waypoint" +
                        (if (waypoints == 1) "" else "s") + " at what altitude above home?",
                    fontSize = 13.sp,
                )
                CoordinateField("Altitude (m)", text) { text = it }
                Text(
                    text = "Uploads the mission to the aircraft and switches it to AUTO.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { entered?.let(onStart) },
                enabled = entered != null && entered > 0f,
            ) {
                Text("Start")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private val FlyToBlue = Color(0xFF1E6FD9)

/** The muted grey-blue the desktop uses for its corner readouts. */
private val MapReadoutColor = Color(0xFFCFD8E0)

/**
 * Flies to typed coordinates. The fields start from where the aircraft is, so
 * the usual edit is a small one, and the ranges are checked before the command
 * can be sent: a mistyped latitude would otherwise be a valid point somewhere
 * far away rather than an obvious error.
 */
@Composable
private fun FlyToLatLonDialog(
    vehicle: VehicleState,
    onDismiss: () -> Unit,
    onConfirm: (Double, Double, Float) -> Unit,
) {
    var latText by remember { mutableStateOf(vehicle.lat?.let { "%.6f".format(Locale.ROOT, it) } ?: "") }
    var lonText by remember { mutableStateOf(vehicle.lon?.let { "%.6f".format(Locale.ROOT, it) } ?: "") }
    var altText by remember {
        mutableStateOf(vehicle.altRelM?.takeIf { it > 1f }?.let { "%.0f".format(Locale.ROOT, it) } ?: "100")
    }
    val lat = latText.toDoubleOrNull()
    val lon = lonText.toDoubleOrNull()
    val altitude = altText.toFloatOrNull()
    val valid = lat != null && lat in -90.0..90.0 &&
        lon != null && lon in -180.0..180.0 &&
        altitude != null && altitude > 0f

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Fly to lat / lon") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CoordinateField("Latitude", latText) { latText = it }
                CoordinateField("Longitude", lonText) { lonText = it }
                CoordinateField("Altitude above home (m)", altText) { altText = it }
                Text(
                    text = "Switches the vehicle to GUIDED and flies to this point.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(lat!!, lon!!, altitude!!) },
                enabled = valid,
            ) {
                Text("Fly")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun CoordinateField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { entered ->
            onChange(entered.filter { it.isDigit() || it == '.' || it == '-' }.take(12))
        },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
}

@Composable
private fun GuidedControlPanel(
    enabled: Boolean,
    metrics: LayoutMetrics,
    onSend: (GuidedAction, Float) -> Unit,
) {
    var pending by remember { mutableStateOf<GuidedAction?>(null) }
    GroupBox(
        title = "Guided Control",
        modifier = Modifier.fillMaxWidth(),
        contentGap = metrics.controlGap,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(metrics.controlGap)) {
            GuidedAction.entries.forEach { action ->
                ModeButton(
                    label = action.label,
                    active = false,
                    enabled = enabled,
                    height = metrics.controlHeight,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    onClick = { pending = action },
                )
            }
        }
    }
    pending?.let { action ->
        GuidedValueDialog(
            action = action,
            onDismiss = { pending = null },
            onConfirm = { value ->
                pending = null
                onSend(action, value)
            },
        )
    }
}

@Composable
private fun GuidedValueDialog(
    action: GuidedAction,
    onDismiss: () -> Unit,
    onConfirm: (Float) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val value = text.toFloatOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(action.label) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { entered ->
                    text = entered.filter { it.isDigit() || it == '.' || it == '-' }.take(7)
                },
                label = { Text(action.unit) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { value?.let(onConfirm) },
                enabled = value != null,
            ) {
                Text("Send")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * The corner every command button shares.
 *
 * Arm, calibrate, disarm and connect had been left on the Material default,
 * which is a full pill, so they read as a different family to the mode buttons
 * sitting right beneath them.
 */
private val ControlCorner = RoundedCornerShape(6.dp)

@Composable
private fun ModeButton(
    label: String,
    active: Boolean,
    enabled: Boolean,
    height: Dp,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    alert: Boolean = false,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(height),
        shape = ControlCorner,
        contentPadding = PaddingValues(horizontal = 2.dp),
        border = if (active || alert) null else BorderStroke(1.dp, scheme.outline),
        colors = ButtonDefaults.buttonColors(
            // Green always means the engaged mode, so an alert button turns green
            // like any other once it is the one flying.
            containerColor = when {
                active -> scheme.primary
                alert -> scheme.error
                else -> scheme.surfaceVariant
            },
            contentColor = when {
                active -> scheme.onPrimary
                alert -> Color.White
                else -> scheme.onSurface
            },
            disabledContainerColor = scheme.surfaceVariant.copy(alpha = 0.4f),
            disabledContentColor = scheme.onSurfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = 13.sp,
            textAlign = TextAlign.Center,
            maxLines = maxLines,
        )
    }
}

/** Where the map sits until the first position fix arrives. */
private val DEFAULT_CENTRE = GeoPoint(39.92502382797436, 32.83690999712254)

/** Opening zoom, set in one place so the factory and the first fix agree. */
private const val DEFAULT_ZOOM = 16.0

/** How far around the aircraft the radar is drawn. */
private const val WEATHER_RADIUS_METRES = 50_000.0
private const val WEATHER_REFRESH_MS = 5L * 60L * 1000L

private const val METRES_PER_DEGREE_LAT = 111_320.0

/**
 * Radar drawn from tiles fetched at RainViewer's deepest supported zoom and
 * stretched to wherever they land on screen, then clipped to a circle around
 * the aircraft.
 *
 * A plain tile overlay cannot do this: above zoom 7 RainViewer answers with a
 * "Zoom Level Not Supported" placard rather than an error, so osmdroid would
 * cache and draw that, and its own upscaling only works from tiles already in
 * the cache -- which at flying zoom they never would be.
 */
private class WeatherOverlay : Overlay() {

    var tiles: List<RadarTile> = emptyList()
    var centre: GeoPoint? = null

    private val paint = Paint().apply {
        isFilterBitmap = true
        // Flying inside a cell fills the whole viewport with one colour, so the
        // wash has to stay light enough to read the ground through.
        alpha = 120
    }

    override fun draw(canvas: Canvas, projection: Projection) {
        val around = centre ?: return
        if (tiles.isEmpty()) {
            return
        }
        val middle = projection.toPixels(around, null)
        // Measured on screen rather than derived from the zoom, so it stays
        // 50km however the projection scales at this latitude.
        val edge = projection.toPixels(destination(around, 0f, WEATHER_RADIUS_METRES), null)
        val radiusPx = hypot(
            (edge.x - middle.x).toDouble(),
            (edge.y - middle.y).toDouble(),
        ).toFloat()
        if (radiusPx <= 0f) {
            return
        }
        canvas.save()
        canvas.clipPath(
            Path().apply {
                addCircle(middle.x.toFloat(), middle.y.toFloat(), radiusPx, Path.Direction.CW)
            },
        )
        tiles.forEach { tile ->
            val topLeft = projection.toPixels(
                GeoPoint(
                    RainViewer.tileNorthLat(tile.y, tile.zoom),
                    RainViewer.tileWestLon(tile.x, tile.zoom),
                ),
                null,
            )
            val bottomRight = projection.toPixels(
                GeoPoint(
                    RainViewer.tileNorthLat(tile.y + 1, tile.zoom),
                    RainViewer.tileWestLon(tile.x + 1, tile.zoom),
                ),
                null,
            )
            canvas.drawBitmap(
                tile.image,
                null,
                Rect(topLeft.x, topLeft.y, bottomRight.x, bottomRight.y),
                paint,
            )
        }
        canvas.restore()
    }
}

/**
 * Seconds of flight the predictive lines reach ahead of the aircraft.
 *
 * The floor and ceiling double with the horizon rather than staying put: they
 * are the same reach expressed for the standstill and flat out cases, and left
 * alone they would clamp the lines back to their old length at both ends.
 */
private const val GUIDE_HORIZON_SECONDS = 20.0
private const val GUIDE_MIN_METRES = 120.0
private const val GUIDE_MAX_METRES = 1200.0
private const val TRACK_STEPS = 24

/** The heading line overshoots the others so its tip stays visible. */
private const val HEADING_REACH_FACTOR = 1.25

private val HeadingLineColor = Color(0xFFFFFFFF)
private val GroundTrackColor = Color(0xFF4FC3F7)
private val TrajectoryColor = Color(0xFFFFD54F)

/** The line to whatever the navigation controller is steering for. */
private val NavTargetColor = Color(0xFFFF2FD0)

private fun guideLine(
    points: List<GeoPoint>,
    argb: Int,
    widthPx: Float,
    dashed: Boolean = false,
): Polyline = Polyline().apply {
    setPoints(points)
    outlinePaint.color = argb
    outlinePaint.strokeWidth = widthPx
    outlinePaint.strokeCap = if (dashed) Paint.Cap.BUTT else Paint.Cap.ROUND
    if (dashed) {
        // A round cap would smear the gaps closed at this width.
        outlinePaint.pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
    }
}

/** The point [distanceM] from [from] along [bearingDeg], on a spherical earth. */
private fun destination(from: GeoPoint, bearingDeg: Float, distanceM: Double): GeoPoint {
    val earthRadiusM = 6_371_000.0
    val angular = distanceM / earthRadiusM
    val bearing = Math.toRadians(bearingDeg.toDouble())
    val lat = Math.toRadians(from.latitude)
    val lon = Math.toRadians(from.longitude)
    val lat2 = asin(sin(lat) * cos(angular) + cos(lat) * sin(angular) * cos(bearing))
    val lon2 = lon + atan2(
        sin(bearing) * sin(angular) * cos(lat),
        cos(angular) - sin(lat) * sin(lat2),
    )
    return GeoPoint(Math.toDegrees(lat2), Math.toDegrees(lon2))
}

/**
 * Where the aircraft ends up if it holds this turn rate: the course is advanced
 * a step at a time, so a steady bank draws an arc and wings level draws a line.
 */
private fun predictedTrack(
    from: GeoPoint,
    courseDeg: Float,
    groundSpeedMs: Double,
    turnRateDegSec: Float,
): List<GeoPoint> {
    val step = GUIDE_HORIZON_SECONDS / TRACK_STEPS
    val leg = (groundSpeedMs * step).coerceAtLeast(GUIDE_MIN_METRES / TRACK_STEPS)
    var course = courseDeg
    var here = from
    val points = mutableListOf(from)
    repeat(TRACK_STEPS) {
        course += (turnRateDegSec * step).toFloat()
        here = destination(here, course, leg)
        points += here
    }
    return points
}

/** The leg still to be sent, and the one the aircraft already has. */
private val MissionLineColor = Color(0xFF33AAFF)
private val MissionSentLineColor = Color(0xB35B6B78)

/** An altitude edited since it was sent, so the aircraft is still on the old one. */
private val MissionPendingColor = Color(0xFFFFC107)

/** Any waypoint that is not the one being flown to. */
private val MissionIdleColor = Color(0xFF6B7480)

private const val WAYPOINT_ICON_DP = 24

/**
 * A numbered disc for one mission waypoint. Drawn rather than shipped, because
 * the number is the point's own place in the run and cannot be known up front.
 */
private fun waypointIcon(
    context: Context,
    number: Int,
    altitudeM: Float,
    pending: Boolean,
    active: Boolean,
): Drawable {
    val side = (WAYPOINT_ICON_DP * context.resources.displayMetrics.density).toInt()
    // An asterisk as well as the colour: on a bright field the amber alone is
    // not always the first thing the eye catches.
    val label = altitudeM.roundToInt().toString() + "m" + if (pending) " *" else ""
    val height = (side * 1.55f).toInt()
    val bitmap = Bitmap.createBitmap(side, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val centre = side / 2f
    val stroke = side * 0.09f
    val radius = centre - stroke
    // Only the point the aircraft is flying to is lit. The rest are the
    // route, not the target, and a map full of blue discs says nothing about
    // where the aeroplane is actually going.
    canvas.drawCircle(
        centre,
        centre,
        radius,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (active) FlyToBlue.toArgb() else MissionIdleColor.toArgb()
        },
    )
    canvas.drawCircle(
        centre,
        centre,
        radius,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            color = android.graphics.Color.WHITE
        },
    )
    val digits = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = side * 0.52f
        isFakeBoldText = true
    }
    // Centre the digits on the disc rather than on the text baseline.
    canvas.drawText(
        number.toString(),
        centre,
        centre - (digits.descent() + digits.ascent()) / 2f,
        digits,
    )
    val altitude = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (pending) {
            MissionPendingColor.toArgb()
        } else {
            android.graphics.Color.WHITE
        }
        textAlign = Paint.Align.CENTER
        textSize = side * 0.34f
        isFakeBoldText = true
        setShadowLayer(side * 0.08f, 0f, 0f, android.graphics.Color.BLACK)
    }
    canvas.drawText(label, centre, height - side * 0.08f, altitude)
    return BitmapDrawable(context.resources, bitmap)
}

private const val HOME_ICON_DP = 72

/** The home badge, scaled once the same way the aircraft marker is. */
private fun homeMarkerIcon(context: Context): Drawable {
    val source = BitmapFactory.decodeResource(context.resources, R.drawable.homeicon)
    val widthPx = (HOME_ICON_DP * context.resources.displayMetrics.density).toInt()
    val heightPx = (widthPx.toLong() * source.height / source.width).toInt()
    val scaled = Bitmap.createScaledBitmap(source, widthPx, heightPx, true)
    if (scaled !== source) {
        source.recycle()
    }
    return BitmapDrawable(context.resources, scaled)
}

private const val PLANE_ICON_DP = 96

/**
 * planeicon.png is 722x605, far too large to use as a marker directly. It
 * lives in drawable-nodpi so decodeResource returns those exact pixels rather
 * than density-scaling them, and is scaled once here to a fixed marker size.
 * The nose points north, which is what Marker.rotation expects at 0 degrees.
 */
private fun planeMarkerIcon(context: Context): Drawable {
    val source = BitmapFactory.decodeResource(context.resources, R.drawable.planeicon)
    val widthPx = (PLANE_ICON_DP * context.resources.displayMetrics.density).toInt()
    val heightPx = (widthPx.toLong() * source.height / source.width).toInt()
    val scaled = Bitmap.createScaledBitmap(source, widthPx, heightPx, true)
    if (scaled !== source) {
        source.recycle()
    }
    return BitmapDrawable(context.resources, scaled)
}

/**
 * A tile provider writing through the app's shared offline cache, so the map
 * imagery and the reference labels are held to one limit and one database.
 */
private fun cachingProvider(context: Context, source: ITileSource): MapTileProviderBasic {
    val cache = MapTileCache.fileCache
    return if (cache != null) {
        MapTileProviderBasic(context, source, cache)
    } else {
        MapTileProviderBasic(context, source)
    }
}

/**
 * ArcGIS serves tiles as {z}/{y}/{x} -- level/row/column -- not the {z}/{x}/{y}
 * that osmdroid's XYTileSource emits. Both orderings return a valid tile, so
 * getting it wrong renders imagery of the wrong place rather than failing.
 */
private fun esriTileSource(name: String, service: String) = object : OnlineTileSourceBase(
    name,
    0,
    19,
    256,
    "",
    arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/$service/MapServer/tile/"),
    "Esri, Maxar, Earthstar Geographics, and the GIS User Community",
) {
    override fun getTileURLString(pMapTileIndex: Long): String =
        baseUrl +
            MapTileIndex.getZoom(pMapTileIndex) + "/" +
            MapTileIndex.getY(pMapTileIndex) + "/" +
            MapTileIndex.getX(pMapTileIndex)
}

private val EsriWorldImagery = esriTileSource("ESRI World Imagery", "World_Imagery")

/**
 * Hybrid is the same imagery with ESRI's reference layers drawn over it; there
 * is no single hybrid service to point at.
 */
private val EsriReferenceLayers = listOf(
    esriTileSource("ESRI Boundaries and Places", "Reference/World_Boundaries_and_Places"),
    esriTileSource("ESRI Transportation", "Reference/World_Transportation"),
)

@Composable
private fun VehicleMap(
    vehicle: VehicleState,
    flyTarget: GeoPoint?,
    followUav: Boolean,
    hybrid: Boolean,
    showGuides: Boolean,
    clearTrailToken: Int,
    missionPoints: List<QueuedWaypoint>,
    queuedCount: Int,
    missionAltitudeM: Float,
    activeWaypoint: Int?,
    onWaypointTap: (Int) -> Unit,
    radarTiles: List<RadarTile>,
    onMapTap: (GeoPoint) -> Unit,
) {
    val trail = remember { mutableListOf<GeoPoint>() }
    // Held outside snapshot state on purpose: comparing the token here must not
    // itself schedule another recomposition on every frame of telemetry.
    val lastCleared = remember { intArrayOf(clearTrailToken) }
    val context = LocalContext.current
    val planeIcon = remember(context) { planeMarkerIcon(context) }
    val homeIcon = remember(context) { homeMarkerIcon(context) }
    val flyTargetIcon = remember(context) { context.getDrawable(R.drawable.ic_fly_target) }
    // The update lambda runs on every telemetry tick, so these are built
    // once per number rather than once per frame.
    val waypointIcons = remember(context) { mutableMapOf<String, Drawable>() }
    // Polyline paints through the android Paint API, so the themed colour has to
    // be resolved to an int out here rather than read inside the update lambda.
    val trailColor = MaterialTheme.colorScheme.error.toArgb()
    val headingColor = HeadingLineColor.toArgb()
    val courseColor = GroundTrackColor.toArgb()
    val trajectoryColor = TrajectoryColor.toArgb()
    val navTargetColor = NavTargetColor.toArgb()
    // Built once: each carries a tile provider and cache that should survive the
    // overlay rebuild that happens on every telemetry update.
    val referenceOverlays = remember(context) {
        EsriReferenceLayers.map { source ->
            TilesOverlay(cachingProvider(context, source), context).apply {
                loadingBackgroundColor = android.graphics.Color.TRANSPARENT
                loadingLineColor = android.graphics.Color.TRANSPARENT
            }
        }
    }
    val weatherOverlay = remember { WeatherOverlay() }
    // The overlay is built once in factory, so it captures whatever handler was
    // current at that moment; this keeps it pointing at the latest one.
    val currentTap by rememberUpdatedState(onMapTap)
    val currentWaypointTap by rememberUpdatedState(onWaypointTap)
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            MapView(context, cachingProvider(context, EsriWorldImagery)).apply {
                setTileSource(EsriWorldImagery)
                setMultiTouchControls(true)
                controller.setZoom(DEFAULT_ZOOM)
                controller.setCenter(DEFAULT_CENTRE)
                overlays.add(
                    MapEventsOverlay(
                        object : MapEventsReceiver {
                            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                                currentTap(p)
                                return true
                            }

                            override fun longPressHelper(p: GeoPoint): Boolean = false
                        },
                    ),
                )
                onResume()
            }
        },
        update = { map ->
            if (clearTrailToken != lastCleared[0]) {
                trail.clear()
                lastCleared[0] = clearTrailToken
            }
            // Rebuilt every update, so it has to happen whether or not there is a
            // fix, otherwise the target marker would never refresh without one.
            map.overlays.removeAll { it !is MapEventsOverlay && it !is TilesOverlay }
            referenceOverlays.forEach { overlay ->
                val shown = map.overlays.contains(overlay)
                if (hybrid && !shown) {
                    // Index 0 keeps the labels under the aircraft and its trail.
                    map.overlays.add(0, overlay)
                } else if (!hybrid && shown) {
                    map.overlays.remove(overlay)
                }
            }
            val homeLat = vehicle.homeLat
            val homeLon = vehicle.homeLon
            if (homeLat != null && homeLon != null) {
                map.overlays += Marker(map).apply {
                    position = GeoPoint(homeLat, homeLon)
                    icon = homeIcon
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    title = "Home"
                }
            }
            val lat = vehicle.lat
            val lon = vehicle.lon
            if (lat != null && lon != null && lat != 0.0 && lon != 0.0) {
                val point = GeoPoint(lat, lon)
                val firstFix = trail.isEmpty()
                if (trail.lastOrNull()?.distanceToAsDouble(point)?.let { it > 1.5 } != false) {
                    trail += point
                    if (trail.size > 400) trail.removeAt(0)
                }
                if (trail.size > 1) {
                    map.overlays += Polyline().apply {
                        setPoints(trail.toList())
                        outlinePaint.strokeWidth = 8f
                        outlinePaint.color = trailColor
                        outlinePaint.strokeCap = Paint.Cap.ROUND
                    }
                }
                if (radarTiles.isNotEmpty()) {
                    // Index 0 keeps the radar under the aircraft, its trail and
                    // the reference labels.
                    weatherOverlay.tiles = radarTiles
                    weatherOverlay.centre = point
                    map.overlays.add(0, weatherOverlay)
                }
                if (showGuides) {
                    val overGround = (vehicle.groundSpeedMs ?: 0f).toDouble()
                    val reach = (overGround * GUIDE_HORIZON_SECONDS)
                        .coerceIn(GUIDE_MIN_METRES, GUIDE_MAX_METRES)
                    val heading = vehicle.headingDeg ?: vehicle.yawDeg ?: 0f
                    val course = vehicle.groundCourseDeg ?: heading
                    // In still air the three nearly coincide, so they are drawn
                    // widest first and thinnest last, and the heading reaches a
                    // little further, leaving each one readable over the others.
                    map.overlays += guideLine(
                        predictedTrack(point, course, overGround, vehicle.yawRateDegSec),
                        trajectoryColor,
                        7f,
                    )
                    // Straight at whatever the navigation controller is
                    // steering for, drawn its whole reported length rather
                    // than a fixed reach: the point of it is that it ends on
                    // the target. Under RTL that is home, under AUTO the
                    // waypoint being flown to.
                    val navBearing = vehicle.navBearingDeg
                    val navDistance = (vehicle.distToWpM ?: 0f).toDouble()
                    if (navBearing != null && navDistance > 0.0) {
                        map.overlays += guideLine(
                            listOf(point, destination(point, navBearing, navDistance)),
                            navTargetColor,
                            5f,
                        )
                    }
                    map.overlays += guideLine(
                        listOf(point, destination(point, course, reach)),
                        courseColor,
                        4.5f,
                    )
                    map.overlays += guideLine(
                        listOf(point, destination(point, heading, reach * HEADING_REACH_FACTOR)),
                        headingColor,
                        3.5f,
                        dashed = true,
                    )
                }
                map.overlays += Marker(map).apply {
                    position = point
                    icon = planeIcon
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    title = vehicle.mode
                    // osmdroid negates the bearing before it reaches
                    // Canvas.rotate, so a compass heading must be negated
                    // here to turn the icon the right way.
                    rotation = -(vehicle.headingDeg ?: vehicle.yawDeg ?: 0f)
                }
                if (firstFix) {
                    map.controller.setZoom(DEFAULT_ZOOM)
                }
                // Recentre only while following, so panning by hand is not
                // fought by the next telemetry update a moment later.
                if (firstFix || followUav) {
                    map.controller.setCenter(point)
                }
            }
            // The run is drawn before the markers so the discs sit on top of
            // it. Queued legs are the live blue; legs already with the aircraft
            // are muted, so what is flying and what is merely drawn stay apart.
            val queuedLeg = missionPoints.take(queuedCount).map { it.point }
            val sentLeg = missionPoints.drop(queuedCount).map { it.point }
            if (sentLeg.size > 1) {
                map.overlays += guideLine(sentLeg, MissionSentLineColor.toArgb(), 2.5f, dashed = true)
            }
            if (queuedLeg.size > 1) {
                map.overlays += guideLine(queuedLeg, MissionLineColor.toArgb(), 2.5f, dashed = true)
            }
            missionPoints.forEachIndexed { index, waypoint ->
                map.overlays += Marker(map).apply {
                    position = waypoint.point
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    // Every point states its height, the default included: a
                    // blank label would read as "no altitude" rather than "the
                    // mission's".
                    val metres = waypoint.effectiveAltitude(missionAltitudeM)
                    val pending = waypoint.isPending(missionAltitudeM)
                    val active = index == activeWaypoint
                    icon = waypointIcons.getOrPut("${index + 1}@$metres@$pending@$active") {
                        waypointIcon(context, index + 1, metres, pending, active)
                    }
                    // No bubble: osmdroid's own info window pans the map to fit
                    // itself, which is what made a waypoint tap throw the view
                    // around. The tap opens the altitude editor instead, and is
                    // consumed so it cannot also drop a new point.
                    infoWindow = null
                    setOnMarkerClickListener { _, _ ->
                        currentWaypointTap(index)
                        true
                    }
                }
            }
            if (flyTarget != null) {
                map.overlays += Marker(map).apply {
                    position = flyTarget
                    // The pin's tip is the coordinate, so it hangs above the
                    // point rather than sitting centred on it.
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    flyTargetIcon?.let { icon = it }
                    title = "Fly to"
                }
            }
            map.invalidate()
        },
        onRelease = { map ->
            map.onPause()
            map.onDetach()
        },
    )
}

private fun Float?.format(digits: Int): String {
    val value = this ?: return NO_DATA
    return "%.${digits}f".format(Locale.ROOT, value)
}
