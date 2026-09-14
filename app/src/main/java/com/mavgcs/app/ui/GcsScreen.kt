package com.mavgcs.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mavgcs.app.R
import com.mavgcs.app.mavlink.FlightModes
import com.mavgcs.app.mavlink.GcsCommand
import com.mavgcs.app.mavlink.GuidedAction
import com.mavgcs.app.mavlink.LinkType
import com.mavgcs.app.mavlink.PlaneModeButton
import com.mavgcs.app.mavlink.VehicleState
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay

/**
 * The left column has to fit without scrolling, and tablets differ a lot in
 * height: the emulator is 800dp tall in landscape, a Galaxy Tab A9 only 601dp.
 * Anything that does not fit is clipped rather than reachable, so these sizes
 * come from the height actually available instead of being fixed.
 */
private data class LayoutMetrics(
    val outerPadding: Dp,
    val gap: Dp,
    val columnPadding: Dp,
    val armHeight: Dp,
    val controlHeight: Dp,
    val controlGap: Dp,
    val hudHeight: Dp,
    val gridLabel: TextUnit,
    val gridValue: TextUnit,
    val gridRowGap: Dp,
    val showSectionLabel: Boolean,
)

private fun metricsFor(available: Dp): LayoutMetrics =
    if (available < 700.dp) {
        LayoutMetrics(
            outerPadding = 8.dp,
            gap = 6.dp,
            columnPadding = 10.dp,
            armHeight = 34.dp,
            controlHeight = 30.dp,
            controlGap = 6.dp,
            hudHeight = 112.dp,
            gridLabel = 8.sp,
            gridValue = 12.sp,
            gridRowGap = 6.dp,
            showSectionLabel = false,
        )
    } else {
        LayoutMetrics(
            outerPadding = 12.dp,
            gap = 12.dp,
            columnPadding = 14.dp,
            armHeight = 44.dp,
            controlHeight = 38.dp,
            controlGap = 8.dp,
            hudHeight = 158.dp,
            gridLabel = 9.sp,
            gridValue = 14.sp,
            gridRowGap = 10.dp,
            showSectionLabel = true,
        )
    }

@Composable
fun GcsScreen(viewModel: GcsViewModel = viewModel()) {
    val vehicle by viewModel.vehicle.collectAsStateWithLifecycle()
    val form by viewModel.form.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme
    var flyTarget by remember { mutableStateOf<GeoPoint?>(null) }
    var showFlyDialog by remember { mutableStateOf(false) }
    var followUav by remember { mutableStateOf(true) }
    var hybridMap by remember { mutableStateOf(false) }
    val metrics = metricsFor(LocalConfiguration.current.screenHeightDp.dp)

    Surface(modifier = Modifier.fillMaxSize(), color = scheme.background) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(metrics.outerPadding),
            horizontalArrangement = Arrangement.spacedBy(metrics.gap),
        ) {
            Column(
                modifier = Modifier
                    .width(420.dp)
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
                )

                GuidedControlPanel(
                    enabled = vehicle.linkUp,
                    metrics = metrics,
                    onSend = viewModel::sendGuided,
                )
                FlightHud(
                    vehicle = vehicle,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(metrics.hudHeight),
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
                    MessagesPanel(
                        statusLog = vehicle.statusLog,
                        modifier = Modifier
                            .weight(1f)
                            .height(TopPanelHeight),
                    )
                    ConnectionPanel(
                        form = form,
                        onType = viewModel::setType,
                        onHost = viewModel::setHost,
                        onPort = viewModel::setPort,
                        onToggle = viewModel::toggleConnection,
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
                        onMapTap = { flyTarget = it },
                    )
                    Column(
                        modifier = Modifier.align(Alignment.TopStart),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        MapToggle("Follow UAV", followUav) { followUav = !followUav }
                        MapToggle("Hybrid", hybridMap) { hybridMap = !hybridMap }
                    }
                    MapCoordinates(
                        lat = vehicle.lat,
                        lon = vehicle.lon,
                        modifier = Modifier.align(Alignment.BottomStart),
                    )
                    flyTarget?.let { target ->
                        FlyHereBar(
                            target = target,
                            enabled = vehicle.linkUp,
                            onFly = { showFlyDialog = true },
                            onClear = { flyTarget = null },
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    }
                    Text(
                        text = "Esri, Maxar, Earthstar Geographics",
                        fontSize = 9.sp,
                        color = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .background(Color.Black.copy(alpha = 0.45f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }

    val target = flyTarget
    if (showFlyDialog && target != null) {
        FlyHereDialog(
            target = target,
            currentAltitude = vehicle.altRelM,
            onDismiss = { showFlyDialog = false },
            onConfirm = { altitude ->
                showFlyDialog = false
                viewModel.flyTo(target.latitude, target.longitude, altitude)
            },
        )
    }
}

@Composable
private fun MapCoordinates(lat: Double?, lon: Double?, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .padding(10.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        MapCoordinate("LAT", lat)
        MapCoordinate("LON", lon)
    }
}

@Composable
private fun MapCoordinate(label: String, value: Double?) {
    val scheme = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, fontSize = 11.sp, color = scheme.onSurfaceVariant)
        Text(
            text = value?.let { "%.6f".format(it) } ?: NO_DATA,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            color = scheme.onSurface,
        )
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
            text = "%.6f, %.6f".format(target.latitude, target.longitude),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = scheme.onSurface,
        )
        Button(
            onClick = onFly,
            enabled = enabled,
            modifier = Modifier.height(38.dp),
            shape = RoundedCornerShape(6.dp),
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
        mutableStateOf(currentAltitude?.takeIf { it > 1f }?.let { "%.0f".format(it) } ?: "100")
    }
    val altitude = text.toFloatOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Fly to here") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "%.6f, %.6f".format(target.latitude, target.longitude),
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
        TelemetryField("Sat Count", vehicle.satellites.takeIf { it > 0 }?.toString() ?: NO_DATA),
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
            color = MaterialTheme.colorScheme.secondary,
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

/** How long DISARM must be held before it fires. */
private const val DISARM_HOLD_MILLIS = 3000

@Composable
private fun ArmPad(
    enabled: Boolean,
    armed: Boolean,
    metrics: LayoutMetrics,
    onCommand: (GcsCommand) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    if (metrics.showSectionLabel) {
        Text("COMMANDS", fontWeight = FontWeight.Bold, color = scheme.primary)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(metrics.controlGap)) {
        // Red marks the state the vehicle is actually in, not the action the
        // button performs, so a glance says whether the props are live.
        Button(
            onClick = { onCommand(GcsCommand.ARM) },
            enabled = enabled,
            modifier = Modifier
                .weight(1f)
                .height(metrics.armHeight),
            border = if (armed) null else BorderStroke(1.dp, scheme.outline),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (armed) scheme.error else scheme.surfaceVariant,
                contentColor = if (armed) Color.White else scheme.onSurface,
            ),
        ) {
            Text("ARM", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        HoldToDisarmButton(
            enabled = enabled,
            armed = armed,
            metrics = metrics,
            onDisarm = { onCommand(GcsCommand.DISARM) },
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Disarming in flight cuts the motors, so it is deliberately not a single tap.
 * The command fires only once the press has been held for the full duration;
 * releasing early snaps the progress back and sends nothing.
 */
@Composable
private fun HoldToDisarmButton(
    enabled: Boolean,
    armed: Boolean,
    metrics: LayoutMetrics,
    onDisarm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val holding = pressed && enabled
    val progress by animateFloatAsState(
        targetValue = if (holding) 1f else 0f,
        animationSpec = if (holding) {
            tween(durationMillis = DISARM_HOLD_MILLIS, easing = LinearEasing)
        } else {
            snap()
        },
        finishedListener = { value -> if (value >= 1f) onDisarm() },
        label = "disarmHold",
    )
    Button(
        onClick = {},
        enabled = enabled,
        interactionSource = interaction,
        modifier = modifier.height(metrics.armHeight),
        border = if (armed) BorderStroke(1.dp, scheme.outline) else null,
        contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (armed) scheme.surfaceVariant else scheme.error,
            contentColor = if (armed) scheme.onSurface else Color.White,
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
                text = if (holding) "HOLD\u2026" else "DISARM",
                modifier = Modifier.align(Alignment.Center),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun GroupBox(
    title: String,
    modifier: Modifier = Modifier,
    titleBackground: Color = MaterialTheme.colorScheme.surface,
    contentGap: Dp = 8.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 7.dp)
                .border(1.dp, scheme.outline, RoundedCornerShape(8.dp))
                .padding(start = 10.dp, end = 10.dp, top = 12.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(contentGap),
            content = content,
        )
        Text(
            text = title,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = scheme.onSurfaceVariant,
            modifier = Modifier
                .offset(x = 12.dp)
                .background(titleBackground)
                .padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun ConnectionPanel(
    form: ConnectionForm,
    onType: (LinkType) -> Unit,
    onHost: (String) -> Unit,
    onPort: (String) -> Unit,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    GroupBox(
        title = "Connection",
        modifier = modifier,
        titleBackground = scheme.background,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = form.type == LinkType.UDP,
                onClick = { onType(LinkType.UDP) },
                label = { Text("UDP listen", fontSize = 12.sp) },
            )
            FilterChip(
                selected = form.type == LinkType.TCP,
                onClick = { onType(LinkType.TCP) },
                label = { Text("TCP", fontSize = 12.sp) },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = form.host,
                onValueChange = onHost,
                label = { Text(if (form.type == LinkType.UDP) "Bind address" else "Host", fontSize = 11.sp) },
                singleLine = true,
                modifier = Modifier.weight(1.6f),
                enabled = !form.listening,
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
            contentPadding = PaddingValues(horizontal = 8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (form.listening) scheme.error else scheme.primary,
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
        titleBackground = scheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            if (statusLog.isEmpty()) {
                Text(
                    text = "Waiting for STATUSTEXT…",
                    color = scheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            } else {
                statusLog.forEach { line ->
                    Text(line, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun FlightModePanel(
    enabled: Boolean,
    currentMode: String,
    metrics: LayoutMetrics,
    onSelect: (PlaneModeButton) -> Unit,
) {
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
            Spacer(Modifier.weight(2f))
        }
    }
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

@Composable
private fun ModeButton(
    label: String,
    active: Boolean,
    enabled: Boolean,
    height: Dp,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(height),
        shape = RoundedCornerShape(6.dp),
        contentPadding = PaddingValues(horizontal = 2.dp),
        border = if (active) null else BorderStroke(1.dp, scheme.outline),
        colors = ButtonDefaults.buttonColors(
            // Green marks the engaged mode. Red is kept for the link being down
            // and for a failed command, so it never doubles as "this is current".
            containerColor = if (active) scheme.primary else scheme.surfaceVariant,
            contentColor = if (active) scheme.onPrimary else scheme.onSurface,
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
    onMapTap: (GeoPoint) -> Unit,
) {
    val trail = remember { mutableListOf<GeoPoint>() }
    val context = LocalContext.current
    val planeIcon = remember(context) { planeMarkerIcon(context) }
    val homeIcon = remember(context) { ContextCompat.getDrawable(context, R.drawable.ic_home_marker) }
    // Built once: each carries a tile provider and cache that should survive the
    // overlay rebuild that happens on every telemetry update.
    val referenceOverlays = remember(context) {
        EsriReferenceLayers.map { source ->
            TilesOverlay(MapTileProviderBasic(context, source), context).apply {
                loadingBackgroundColor = android.graphics.Color.TRANSPARENT
                loadingLineColor = android.graphics.Color.TRANSPARENT
            }
        }
    }
    // The overlay is built once in factory, so it captures whatever handler was
    // current at that moment; this keeps it pointing at the latest one.
    val currentTap by rememberUpdatedState(onMapTap)
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            MapView(context).apply {
                setTileSource(EsriWorldImagery)
                setMultiTouchControls(true)
                controller.setZoom(18.0)
                controller.setCenter(GeoPoint(37.3349, -122.0090))
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
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
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
                        outlinePaint.color = 0xFF3DDC97.toInt()
                        outlinePaint.strokeCap = Paint.Cap.ROUND
                    }
                }
                map.overlays += Marker(map).apply {
                    position = point
                    icon = planeIcon
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    title = vehicle.mode
                    // osmdroid negates the bearing before it reaches
                    // Canvas.rotate, so a compass heading must be negated
                    // here to turn the icon the right way.
                    rotation = -(vehicle.headingDeg ?: vehicle.yawDeg)
                }
                if (firstFix) {
                    map.controller.setZoom(18.0)
                }
                // Recentre only while following, so panning by hand is not
                // fought by the next telemetry update a moment later.
                if (firstFix || followUav) {
                    map.controller.setCenter(point)
                }
            }
            if (flyTarget != null) {
                map.overlays += Marker(map).apply {
                    position = flyTarget
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
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
    return "%.${digits}f".format(value)
}
