package com.mavgcs.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
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
import com.mavgcs.app.mavlink.VehicleState
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

@Composable
fun GcsScreen(viewModel: GcsViewModel = viewModel()) {
    val vehicle by viewModel.vehicle.collectAsStateWithLifecycle()
    val form by viewModel.form.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme
    var flyTarget by remember { mutableStateOf<GeoPoint?>(null) }
    var showFlyDialog by remember { mutableStateOf(false) }

    Surface(modifier = Modifier.fillMaxSize(), color = scheme.background) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier
                    .width(420.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(16.dp))
                    .background(scheme.surface)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ArmPad(enabled = vehicle.linkUp, onCommand = viewModel::command)
                FlightModePanel(
                    enabled = vehicle.linkUp,
                    currentMode = vehicle.mode,
                    onSelect = viewModel::setFlightMode,
                )

                GuidedControlPanel(
                    enabled = vehicle.linkUp,
                    onSend = viewModel::sendGuided,
                )
                FlightHud(
                    vehicle = vehicle,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(158.dp),
                )
                TelemetryGrid(vehicle)
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
                        onMapTap = { flyTarget = it },
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
private fun TelemetryGrid(vehicle: VehicleState) {
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
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        fields.chunked(4).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                row.forEach { field ->
                    TelemetryCell(field, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun TelemetryCell(field: TelemetryField, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = field.label,
            fontSize = 9.sp,
            lineHeight = 11.sp,
            color = MaterialTheme.colorScheme.secondary,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
        Text(
            text = field.value,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

@Composable
private fun ArmPad(enabled: Boolean, onCommand: (GcsCommand) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Text("COMMANDS", fontWeight = FontWeight.Bold, color = scheme.primary)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { onCommand(GcsCommand.ARM) },
            enabled = enabled,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(
                containerColor = scheme.primary,
                contentColor = scheme.onPrimary,
            ),
        ) {
            Text("ARM", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        // Monochrome, so the armed-state colour never reads as a second live action.
        Button(
            onClick = { onCommand(GcsCommand.DISARM) },
            enabled = enabled,
            modifier = Modifier.weight(1f),
            border = BorderStroke(1.dp, scheme.outline),
            colors = ButtonDefaults.buttonColors(
                containerColor = scheme.surfaceVariant,
                contentColor = scheme.onSurface,
            ),
        ) {
            Text("DISARM", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

private val VtolPurple = Color(0xFFB39DDB)
private val VtolPurpleText = Color(0xFF1B1033)

/**
 * A titled group box in the desktop's style. The border sits inside a top inset
 * so the title can straddle it; the title paints [titleBackground] over the line
 * behind it, so that colour has to match whatever the box is sitting on.
 */
@Composable
private fun GroupBox(
    title: String,
    modifier: Modifier = Modifier,
    titleBackground: Color = MaterialTheme.colorScheme.surface,
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
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
    onSelect: (PlaneModeButton) -> Unit,
) {
    GroupBox(title = "Flight Mode", modifier = Modifier.fillMaxWidth()) {
        FlightModes.planeModeRows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { mode ->
                    ModeButton(
                        label = mode.label,
                        active = currentMode == mode.label,
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                        onClick = { onSelect(mode) },
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModeButton(
                label = FlightModes.planeGuidedMode.label,
                active = currentMode == FlightModes.planeGuidedMode.label,
                enabled = enabled,
                modifier = Modifier.weight(1f),
                onClick = { onSelect(FlightModes.planeGuidedMode) },
            )
            VtolModeButton(
                enabled = enabled,
                currentMode = currentMode,
                onSelect = onSelect,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun GuidedControlPanel(
    enabled: Boolean,
    onSend: (GuidedAction, Float) -> Unit,
) {
    var pending by remember { mutableStateOf<GuidedAction?>(null) }
    GroupBox(title = "Guided Control", modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GuidedAction.entries.forEach { action ->
                ModeButton(
                    label = action.label,
                    active = false,
                    enabled = enabled,
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
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(38.dp),
        shape = RoundedCornerShape(6.dp),
        contentPadding = PaddingValues(horizontal = 2.dp),
        border = if (active) null else BorderStroke(1.dp, scheme.outline),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (active) scheme.error else scheme.surfaceVariant,
            contentColor = if (active) Color.White else scheme.onSurface,
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

@Composable
private fun VtolModeButton(
    enabled: Boolean,
    currentMode: String,
    onSelect: (PlaneModeButton) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val active = FlightModes.vtolModes.any { it.label == currentMode }
    Box(modifier = modifier) {
        Button(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp),
            shape = RoundedCornerShape(6.dp),
            contentPadding = PaddingValues(horizontal = 2.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = VtolPurple,
                contentColor = VtolPurpleText,
            ),
        ) {
            Text(
                text = if (active) currentMode else "VTOL",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = "VTOL modes",
                modifier = Modifier.size(18.dp),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            FlightModes.vtolModes.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.label, fontSize = 13.sp) },
                    onClick = {
                        expanded = false
                        onSelect(mode)
                    },
                )
            }
        }
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
 * ESRI World Imagery. The tile path is {z}/{y}/{x} -- ArcGIS orders it
 * level/row/column, not the {z}/{x}/{y} that osmdroid's XYTileSource emits.
 */
private val EsriWorldImagery = object : OnlineTileSourceBase(
    "ESRI World Imagery",
    0,
    19,
    256,
    "",
    arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"),
    "Esri, Maxar, Earthstar Geographics, and the GIS User Community",
) {
    override fun getTileURLString(pMapTileIndex: Long): String =
        baseUrl +
            MapTileIndex.getZoom(pMapTileIndex) + "/" +
            MapTileIndex.getY(pMapTileIndex) + "/" +
            MapTileIndex.getX(pMapTileIndex)
}

@Composable
private fun VehicleMap(
    vehicle: VehicleState,
    flyTarget: GeoPoint?,
    onMapTap: (GeoPoint) -> Unit,
) {
    val trail = remember { mutableListOf<GeoPoint>() }
    val context = LocalContext.current
    val planeIcon = remember(context) { planeMarkerIcon(context) }
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
            map.overlays.removeAll { it !is MapEventsOverlay }
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
