package com.mavgcs.app.ui

import android.graphics.Paint
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mavgcs.app.mavlink.GcsCommand
import com.mavgcs.app.mavlink.LinkType
import com.mavgcs.app.mavlink.VehicleState
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

@Composable
fun GcsScreen(viewModel: GcsViewModel = viewModel()) {
    val vehicle by viewModel.vehicle.collectAsStateWithLifecycle()
    val form by viewModel.form.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme

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
                    .padding(14.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("LINK", fontWeight = FontWeight.Bold, color = scheme.primary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = form.type == LinkType.UDP,
                        onClick = { viewModel.setType(LinkType.UDP) },
                        label = { Text("UDP listen") },
                    )
                    FilterChip(
                        selected = form.type == LinkType.TCP,
                        onClick = { viewModel.setType(LinkType.TCP) },
                        label = { Text("TCP") },
                    )
                }
                OutlinedTextField(
                    value = form.host,
                    onValueChange = viewModel::setHost,
                    label = { Text(if (form.type == LinkType.UDP) "Bind address" else "Host") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !form.listening,
                )
                OutlinedTextField(
                    value = form.port,
                    onValueChange = viewModel::setPort,
                    label = { Text("Port") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !form.listening,
                )
                Button(
                    onClick = viewModel::toggleConnection,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (form.listening) scheme.error else scheme.primary,
                    ),
                ) {
                    Text(if (form.listening) "Disconnect" else "Connect")
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(148.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Black),
                    ) {
                        AttitudeIndicator(rollDeg = vehicle.rollDeg, pitchDeg = vehicle.pitchDeg)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        TelemetryLine("MODE", vehicle.mode)
                        TelemetryLine("ARM", if (vehicle.armed) "ARMED" else "DISARMED", if (vehicle.armed) Color(0xFFFF6B6B) else scheme.primary)
                        TelemetryLine("FIX", "${vehicle.gpsFix}  ${vehicle.satellites}sats")
                        TelemetryLine("HDG", vehicle.headingDeg?.let { "${it.toInt()}°" } ?: "—")
                    }
                }

                TelemetryGrid(vehicle)
                CommandPad(enabled = vehicle.linkUp, onCommand = viewModel::command)

                Text("STATUS", fontWeight = FontWeight.Bold, color = scheme.primary)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(scheme.surfaceVariant)
                        .padding(8.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (vehicle.statusLog.isEmpty()) {
                        Text("Waiting for STATUSTEXT…", color = scheme.onSurfaceVariant, fontSize = 12.sp)
                    } else {
                        vehicle.statusLog.takeLast(8).forEach { line ->
                            Text(line, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        }
                    }
                }
            }
            Column(
                modifier = Modifier
                    .weight(1.35f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatusBar(vehicle)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .border(1.dp, scheme.outline, RoundedCornerShape(16.dp)),
                ) {
                    VehicleMap(vehicle)
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
}

@Composable
private fun StatusBar(vehicle: VehicleState) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(scheme.surface)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("MavGCS", fontWeight = FontWeight.Bold, fontSize = 22.sp, color = scheme.primary)
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusPill(if (vehicle.linkUp) "LINK UP" else "NO HEARTBEAT", if (vehicle.linkUp) scheme.primary else scheme.error)
            Text("SYS ${vehicle.systemId}", color = scheme.onSurfaceVariant)
            Text(vehicle.vehicleType, color = scheme.onSurfaceVariant)
            Text(vehicle.autopilot, color = scheme.onSurfaceVariant)
            Text("${vehicle.packetsIn} pkt", color = scheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Text(
        text = text,
        color = color,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

@Composable
private fun TelemetryLine(label: String, value: String, color: Color = MaterialTheme.colorScheme.onBackground) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.width(44.dp))
        Text(value, color = color, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

@Composable
private fun TelemetryGrid(vehicle: VehicleState) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCard("ALT REL", vehicle.altRelM.format(1, " m"), Modifier.weight(1f))
            MetricCard("ALT MSL", vehicle.altMslM.format(1, " m"), Modifier.weight(1f))
            MetricCard("CLIMB", vehicle.climbMs.format(1, " m/s"), Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCard("GS", vehicle.groundSpeedMs.format(1, " m/s"), Modifier.weight(1f))
            MetricCard("AS", vehicle.airSpeedMs.format(1, " m/s"), Modifier.weight(1f))
            MetricCard("THR", vehicle.throttlePct?.let { "$it %" } ?: "—", Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCard("BATT", vehicle.batteryV.format(1, " V"), Modifier.weight(1f))
            MetricCard("CUR", vehicle.batteryA.format(1, " A"), Modifier.weight(1f))
            MetricCard("REM", vehicle.batteryRemainingPct?.let { "$it %" } ?: "—", Modifier.weight(1f))
        }
        val lat = vehicle.lat
        val lon = vehicle.lon
        MetricCard(
            "GPS",
            if (lat != null && lon != null) "%.7f, %.7f".format(lat, lon) else "no position",
            Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(10.dp),
    ) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun CommandPad(enabled: Boolean, onCommand: (GcsCommand) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Text("COMMANDS", fontWeight = FontWeight.Bold, color = scheme.primary)
    val rows = listOf(
        listOf(GcsCommand.ARM to "ARM", GcsCommand.DISARM to "DISARM"),
        listOf(GcsCommand.TAKEOFF to "TAKEOFF 10m", GcsCommand.LAND to "LAND"),
        listOf(GcsCommand.LOITER to "LOITER", GcsCommand.RTL to "RTL"),
        listOf(GcsCommand.GUIDED to "GUIDED", GcsCommand.AUTO to "AUTO"),
        listOf(GcsCommand.STABILIZE to "STABILIZE"),
    )
    rows.forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { (command, label) ->
                val danger = command == GcsCommand.ARM || command == GcsCommand.DISARM || command == GcsCommand.LAND
                Button(
                    onClick = { onCommand(command) },
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (danger) scheme.error else scheme.secondary,
                        contentColor = if (danger) Color.White else scheme.onSecondary,
                    ),
                ) {
                    Text(label, fontSize = 13.sp)
                }
            }
        }
    }
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
private fun VehicleMap(vehicle: VehicleState) {
    val trail = remember { mutableListOf<GeoPoint>() }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            MapView(context).apply {
                setTileSource(EsriWorldImagery)
                setMultiTouchControls(true)
                controller.setZoom(18.0)
                controller.setCenter(GeoPoint(37.3349, -122.0090))
                onResume()
            }
        },
        update = { map ->
            val lat = vehicle.lat
            val lon = vehicle.lon
            if (lat != null && lon != null && lat != 0.0 && lon != 0.0) {
                val point = GeoPoint(lat, lon)
                val firstFix = trail.isEmpty()
                if (trail.lastOrNull()?.distanceToAsDouble(point)?.let { it > 1.5 } != false) {
                    trail += point
                    if (trail.size > 400) trail.removeAt(0)
                }
                map.overlays.removeAll { true }
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
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    title = vehicle.mode
                    rotation = vehicle.headingDeg ?: vehicle.yawDeg
                }
                if (firstFix) {
                    map.controller.setZoom(18.0)
                    map.controller.setCenter(point)
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

private fun Float?.format(digits: Int, suffix: String): String {
    val value = this ?: return "—"
    return "%.${digits}f%s".format(value, suffix)
}
