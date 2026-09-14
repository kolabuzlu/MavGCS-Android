package com.mavgcs.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mavgcs.app.mavlink.VehicleState
import com.mavgcs.app.terrain.TerrainFan
import com.mavgcs.app.terrain.TerrainSampler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The widget is laid out in the desktop's 200-unit SVG space and scaled to
 * whatever it is given, so the geometry constants below can be read straight
 * across from map_view.py.
 */
private const val TR_SIZE = 200f
private const val TR_RING_R = 6f

/** The aircraft sits near the bottom edge; the fan opens upwards from it. */
private const val TR_APEX_Y = TR_SIZE - TR_RING_R - 3f

/** Radius the fan's outer arc is drawn at. */
private const val TR_R = TR_APEX_Y - 6f

private const val TR_SCALE_MIN = 5f
private const val TR_SCALE_MAX = 2000f
private const val TR_DEFAULT_SCALE_M = 120f

/** How often the sampler looks at the telemetry, matching the desktop worker. */
private const val TR_POLL_MS = 200L

/** A fan older than this is retaken even if nothing moved. */
private const val TR_STALE_MS = 5_000L

/** Climb-rate samples averaged into the predictive slope. */
private const val TR_VARIO_SAMPLES = 5

/**
 * How far, in the widget's own units, each cell is grown past its neighbours.
 * Two antialiased edges meeting on the same line each cover their pixel about
 * half way, so without an overlap the seams read as a dark grid over the fan.
 */
private const val TR_CELL_BLEED = 0.4f

private val TerrainRadarSize = 200.dp

/** Red through orange and yellow to green, over the clearance scale. */
private val TrRamp = listOf(
    Color(0xFFE74C3C),
    Color(0xFFE67E22),
    Color(0xFFF1C40F),
    Color(0xFF2ECC71),
)

private val TrChipBlue = Color(0xFF37A8DB)
private val TrGridColor = Color(0xFFFFFFFF)
private val TrLabelColor = Color(0xFFB8B8B8)

private val trLabelStyle = TextStyle(color = TrLabelColor, fontSize = 9.sp)
private val trChipStyle = TextStyle(
    color = TrChipBlue,
    fontSize = 12.sp,
    fontWeight = FontWeight.Bold,
)

/**
 * Forward-looking terrain awareness, EGPWS-style: a track-up fan of the ground
 * ahead, coloured by how much clearance there is beneath the aircraft rather
 * than by height, so the colour answers the only question that matters.
 *
 * Cells where the terrain is more than the scale below the aircraft are left
 * unpainted, which is why a safe picture is mostly empty.
 */
@Composable
fun TerrainRadar(
    vehicle: VehicleState,
    size: Dp = TerrainRadarSize,
    modifier: Modifier = Modifier,
) {
    var scaleM by remember { mutableStateOf(TR_DEFAULT_SCALE_M) }
    var predictive by remember { mutableStateOf(false) }
    var editingScale by remember { mutableStateOf(false) }

    val fan = rememberTerrainFan(vehicle)
    val slope = rememberGlideSlope(vehicle)
    val altMslM = rememberLastKnown(vehicle.altMslM)
    val textMeasurer = rememberTextMeasurer()

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xBF1E1E1E))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(8.dp)),
    ) {
        if (fan == null || altMslM == null) {
            Text(
                text = "Terrain Radar - no data",
                color = Color(0xFF888888),
                fontSize = 11.sp,
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawTerrainFan(
                    fan = fan,
                    altMslM = altMslM,
                    slope = slope,
                    predictive = predictive,
                    scaleM = scaleM,
                    textMeasurer = textMeasurer,
                )
            }
        }

        TrChip(
            text = "${scaleM.roundToInt()}m",
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(4.dp),
            onClick = { editingScale = true },
        )
        TrChip(
            text = if (predictive) "PRED" else "REL",
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp),
            onClick = { predictive = !predictive },
        )
    }

    if (editingScale) {
        TerrainScaleDialog(
            current = scaleM,
            onDismiss = { editingScale = false },
            onApply = {
                scaleM = it.coerceIn(TR_SCALE_MIN, TR_SCALE_MAX)
                editingScale = false
            },
        )
    }
}

/**
 * Holds the last value the vehicle actually reported.
 *
 * Clearance is measured from the aircraft's height, so a dropped link must not
 * quietly become an altitude of zero: every cell would then read as terrain
 * above the aircraft and the fan would go red, warning of a collision that is
 * not happening. Holding the last real reading keeps the picture honest, and
 * until there has been one there is nothing to colour against at all.
 */
@Composable
private fun rememberLastKnown(value: Float?): Float? {
    val held = remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(value) {
        if (value != null) {
            held.value = value
        }
    }
    return held.value
}

/**
 * Runs the desktop's sampling loop: it looks at the telemetry often, but only
 * takes a new fan when the aircraft has moved a meaningful fraction of a cell,
 * turned, changed range, or the picture has gone stale. Sampling can mean
 * downloading terrain, so taking one per telemetry update would be far too
 * often.
 */
@Composable
private fun rememberTerrainFan(vehicle: VehicleState): TerrainFan? {
    val latest by rememberUpdatedState(vehicle)
    var fan by remember { mutableStateOf<TerrainFan?>(null) }

    LaunchedEffect(Unit) {
        var rangeM = TerrainSampler.RANGE_STEPS.first()
        var last: SampledAt? = null
        while (true) {
            val state = latest
            val lat = state.lat
            val lon = state.lon
            // Track-up: the ground track is what the aircraft will actually
            // cross, and it parts from the nose in any crosswind.
            val heading = (state.groundCourseDeg ?: state.headingDeg)?.toDouble()

            if (lat != null && lon != null && heading != null) {
                rangeM = TerrainSampler.nextRange(rangeM, (state.groundSpeedMs ?: 0f).toDouble())
                val cellM = rangeM / TerrainSampler.RAD_CELLS
                val previous = last
                val due = previous == null ||
                    System.currentTimeMillis() - previous.atMs > TR_STALE_MS ||
                    TerrainSampler.distanceM(previous.lat, previous.lon, lat, lon) > cellM * 0.5 ||
                    TerrainSampler.angleDiff(heading, previous.headingDeg) > 2.0 ||
                    previous.rangeM != rangeM

                if (due) {
                    val sampled = withContext(Dispatchers.IO) {
                        runCatching { TerrainSampler.sample(lat, lon, heading, rangeM) }.getOrNull()
                    }
                    if (sampled != null && sampled.hasData) {
                        fan = sampled
                    }
                    // Recorded even when the sample failed, so a dead link is
                    // retried on the stale timer rather than every tick.
                    last = SampledAt(lat, lon, heading, rangeM, System.currentTimeMillis())
                }
            }
            delay(TR_POLL_MS)
        }
    }
    return fan
}

private class SampledAt(
    val lat: Double,
    val lon: Double,
    val headingDeg: Double,
    val rangeM: Double,
    val atMs: Long,
)

/**
 * Climb rate over ground speed: the gradient the aircraft is currently flying,
 * which projects its height forward along the fan in predictive mode. Averaged
 * over a few samples because a single vario reading is far too twitchy to aim
 * a terrain warning with.
 */
@Composable
private fun rememberGlideSlope(vehicle: VehicleState): Double {
    val samples = remember { ArrayDeque<Float>() }
    var slope by remember { mutableStateOf(0.0) }

    LaunchedEffect(vehicle.climbMs, vehicle.groundSpeedMs) {
        samples.addLast(vehicle.climbMs ?: 0f)
        while (samples.size > TR_VARIO_SAMPLES) {
            samples.removeFirst()
        }
        val speed = vehicle.groundSpeedMs ?: 0f
        slope = if (speed <= 2f || samples.isEmpty()) {
            0.0
        } else {
            samples.average() / speed
        }
    }
    return slope
}

// --- drawing ----------------------------------------------------------------

private fun DrawScope.drawTerrainFan(
    fan: TerrainFan,
    altMslM: Float,
    slope: Double,
    predictive: Boolean,
    scaleM: Float,
    textMeasurer: TextMeasurer,
) {
    val unit = size.width / TR_SIZE
    val half = Math.toRadians(TerrainSampler.HALF_ANGLE_DEG).toFloat()

    fun point(theta: Float, distance: Double): Offset {
        val radius = (distance / fan.rangeM).toFloat() * TR_R
        return Offset(
            (TR_SIZE / 2f + radius * sin(theta)) * unit,
            (TR_APEX_Y - radius * cos(theta)) * unit,
        )
    }

    val apex = Offset(TR_SIZE / 2f * unit, TR_APEX_Y * unit)

    // Cells, from the aircraft outwards. The whole group is composited at
    // 0.85 rather than each cell separately, as the desktop does: painted one
    // at a time, every shared edge would end up more transparent than the cells
    // it joins and the map would show through as a grid.
    val bleedM = TR_CELL_BLEED / TR_R * fan.rangeM
    drawContext.canvas.saveLayer(Rect(Offset.Zero, size), Paint().apply { alpha = 0.85f })
    for (a in 0 until fan.angCells) {
        val thetaA = -half + 2 * half * a / fan.angCells
        val thetaB = -half + 2 * half * (a + 1) / fan.angCells
        for (b in 0 until fan.radCells) {
            val fill = clearanceColour(
                elevation = fan.elevation(a, b),
                distance = fan.distanceOf(b),
                altMslM = altMslM,
                slope = slope,
                predictive = predictive,
                scaleM = scaleM,
            ) ?: continue

            val inner = (fan.rangeM * b / fan.radCells - bleedM).coerceAtLeast(0.0)
            val outer = fan.rangeM * (b + 1) / fan.radCells + bleedM
            // Angular bleed is taken at the outer edge so the overlap stays the
            // same width along the whole cell rather than fanning out with it.
            val spread = TR_CELL_BLEED / ((outer / fan.rangeM).toFloat() * TR_R)
            val path = Path().apply {
                val p0 = point(thetaA - spread, inner)
                moveTo(p0.x, p0.y)
                point(thetaB + spread, inner).let { lineTo(it.x, it.y) }
                point(thetaB + spread, outer).let { lineTo(it.x, it.y) }
                point(thetaA - spread, outer).let { lineTo(it.x, it.y) }
                close()
            }
            drawPath(path, fill)
        }
    }
    drawContext.canvas.restore()

    // Range arcs at thirds, with the distance written on each.
    for (k in 1..3) {
        val distance = fan.rangeM * k / 3.0
        val arc = Path()
        for (s in 0..fan.angCells) {
            val theta = -half + 2 * half * s / fan.angCells
            val p = point(theta, distance)
            if (s == 0) arc.moveTo(p.x, p.y) else arc.lineTo(p.x, p.y)
        }
        drawPath(
            path = arc,
            color = TrGridColor.copy(alpha = 0.22f),
            style = Stroke(width = unit),
        )

        val label = textMeasurer.measure(
            AnnotatedString(distance.roundToInt().toString()),
            style = trLabelStyle,
        )
        val radius = (distance / fan.rangeM).toFloat() * TR_R
        drawText(
            textLayoutResult = label,
            topLeft = Offset(
                (TR_SIZE / 2f + 3f) * unit,
                (TR_APEX_Y - radius) * unit - label.size.height / 2f,
            ),
        )
    }

    // The two edges of the fan, and the nose line straight up the middle. The
    // fan is already track-up, so the heading is always vertical.
    val edge = Stroke(width = unit)
    listOf(-half, half).forEach { theta ->
        val end = point(theta, fan.rangeM)
        drawLine(TrGridColor.copy(alpha = 0.28f), apex, end, strokeWidth = edge.width)
    }
    drawLine(
        color = TrGridColor.copy(alpha = 0.35f),
        start = apex,
        end = point(0f, fan.rangeM),
        strokeWidth = unit,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f * unit, 3f * unit)),
    )

    // The aircraft.
    drawCircle(Color(0x4037A8DB), radius = TR_RING_R * unit, center = apex)
    drawCircle(Color.White, radius = TR_RING_R * unit, center = apex, style = Stroke(2f * unit))
    drawCircle(Color.White, radius = 3f * unit, center = apex)
    drawCircle(Color(0xFF1A1A1A), radius = 3f * unit, center = apex, style = Stroke(unit))
}

/**
 * Colour for a cell, or null to leave it unpainted. Clearance is measured from
 * the aircraft's present height, or from where its current gradient would put
 * it by the time it got there when predictive mode is on.
 */
private fun clearanceColour(
    elevation: Float,
    distance: Double,
    altMslM: Float,
    slope: Double,
    predictive: Boolean,
    scaleM: Float,
): Color? {
    if (elevation.isNaN()) {
        return null
    }
    val reference = if (predictive) altMslM + (slope * distance).toFloat() else altMslM
    val clearance = reference - elevation
    if (clearance >= scaleM) {
        return null
    }
    return rampColour(clearance / scaleM)
}

private fun rampColour(fraction: Float): Color {
    val x = fraction.coerceIn(0f, 1f) * (TrRamp.size - 1)
    val index = floor(x).toInt().coerceAtMost(TrRamp.size - 2)
    return lerp(TrRamp[index], TrRamp[index + 1], x - index)
}

// --- chrome -----------------------------------------------------------------

@Composable
private fun TrChip(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.4f))
            .border(1.dp, TrChipBlue.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(text = text, style = trChipStyle)
    }
}

@Composable
private fun TerrainScaleDialog(
    current: Float,
    onDismiss: () -> Unit,
    onApply: (Float) -> Unit,
) {
    var text by remember { mutableStateOf(current.roundToInt().toString()) }
    val entered = text.toFloatOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clearance scale") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter { ch -> ch.isDigit() || ch == '.' }.take(6) },
                label = { Text("Metres (${TR_SCALE_MIN.roundToInt()}–${TR_SCALE_MAX.roundToInt()})") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { entered?.let(onApply) },
                enabled = entered != null && entered > 0f,
            ) {
                Text("Apply")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
