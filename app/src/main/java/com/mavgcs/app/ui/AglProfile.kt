package com.mavgcs.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mavgcs.app.mavlink.VehicleState
import com.mavgcs.app.terrain.TerrainSampler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Height above the ground along the track: behind on the left, ahead on the
 * right.
 *
 * The desktop's Live AGL panel, drawn the same way and reading the same. It
 * belongs beside the terrain radar because the two answer neighbouring
 * questions: the radar says where the ground is around the aircraft, this
 * says where it is along the line the aircraft is actually making good.
 *
 * Wider than tall because it is a side-on slice. Distance runs a long way;
 * height does not.
 */
@Composable
fun AglProfile(vehicle: VehicleState, width: Dp, height: Dp, modifier: Modifier = Modifier) {
    val slice = rememberAglProfile(vehicle)
    val amsl = vehicle.altMslM
    // Nothing worth drawing until some ground has arrived and the aircraft has
    // said how high it is. A terrain block is megabytes; on a fresh install
    // this can be a minute of honest waiting.
    if (slice == null || amsl == null || slice.elevations.none { !it.isNaN() }) return

    Box(
        modifier = modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xBF1E1E1E))
            .border(1.dp, Color(0x14FFFFFF), RoundedCornerShape(8.dp)),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawAglProfile(slice, amsl.toDouble())
        }
    }
}

/** What the panel needs to draw itself, sampled off the UI thread. */
class AglSlice(
    val elevations: FloatArray,
    val behindM: Double,
    val aheadM: Double,
    /** Metres of height per metre along the track, already clamped. */
    val slope: Double,
    /** The track flown, as (metres astern, height above the sea). */
    val flown: List<Pair<Double, Double>>,
)

/**
 * Samples the ground along the track, and keeps the track already flown.
 *
 * Resampled on the same terms as the radar's fan -- a fraction of a step
 * moved, a turn, a range change, or a stale picture -- because sampling can
 * mean downloading terrain, and doing that per telemetry frame would be far
 * too often. Between samples the panel is still redrawn from the elevations
 * in hand, so the marker keeps up with the aircraft.
 */
@Composable
private fun rememberAglProfile(vehicle: VehicleState): AglSlice? {
    val latest by rememberUpdatedState(vehicle)
    var slice by remember { mutableStateOf<AglSlice?>(null) }
    // Held outside snapshot state: this is written on every fix, and reading
    // it back into composition would redraw the map on each one.
    val flown = remember { ArrayDeque<Pair<Double, Double>>() }
    val walked = remember { doubleArrayOf(0.0) }
    val lastFix = remember { arrayOfNulls<Pair<Double, Double>>(1) }

    LaunchedEffect(Unit) {
        var rangeM = TerrainSampler.RANGE_STEPS.first()
        var atLat = 0.0
        var atLon = 0.0
        var atHeading = 0.0
        var atRange = 0.0
        var atMs = 0L
        while (true) {
            val state = latest
            val lat = state.lat
            val lon = state.lon
            // Track-up, like the radar: the course being made good is the
            // ground the aircraft will actually cross.
            val heading = (state.groundCourseDeg ?: state.headingDeg)?.toDouble()
            val amsl = state.altMslM?.toDouble()

            if (lat != null && lon != null && heading != null && amsl != null) {
                recordFlown(flown, walked, lastFix, lat, lon, amsl)
                rangeM = TerrainSampler.nextRange(rangeM, (state.groundSpeedMs ?: 0f).toDouble())
                val behind = rangeM * TerrainSampler.PROFILE_BEHIND_FRAC
                val step = (behind + rangeM) / TerrainSampler.PROFILE_SAMPLES
                val due = atMs == 0L ||
                    System.currentTimeMillis() - atMs > AGL_STALE_MS ||
                    TerrainSampler.distanceM(atLat, atLon, lat, lon) > step * 0.5 ||
                    TerrainSampler.angleDiff(heading, atHeading) > 2.0 ||
                    atRange != rangeM

                if (due) {
                    val ground = withContext(Dispatchers.IO) {
                        runCatching {
                            TerrainSampler.trackProfile(lat, lon, heading, behind, rangeM)
                        }.getOrNull()
                    }
                    if (ground != null && ground.isNotEmpty()) {
                        slice = AglSlice(
                            ground,
                            behind,
                            rangeM,
                            slopeOf(state),
                            track(flown, walked[0], behind),
                        )
                    }
                    // Recorded even when the sample failed, so a dead link is
                    // retried on the stale timer rather than on every tick.
                    atLat = lat
                    atLon = lon
                    atHeading = heading
                    atRange = rangeM
                    atMs = System.currentTimeMillis()
                } else {
                    slice?.let {
                        slice = AglSlice(
                            it.elevations,
                            it.behindM,
                            it.aheadM,
                            slopeOf(state),
                            track(flown, walked[0], it.behindM),
                        )
                    }
                }
            }
            delay(AGL_POLL_MS)
        }
    }
    return slice
}

/** One more point on the flown track, if the aircraft has moved far enough. */
private fun recordFlown(
    flown: ArrayDeque<Pair<Double, Double>>,
    walked: DoubleArray,
    lastFix: Array<Pair<Double, Double>?>,
    lat: Double,
    lon: Double,
    amsl: Double,
) {
    val previous = lastFix[0]
    if (previous == null) {
        lastFix[0] = lat to lon
        return
    }
    val step = TerrainSampler.distanceM(previous.first, previous.second, lat, lon)
    // Below this the aircraft has not really gone anywhere, and recording it
    // would stack a pile of points on one spot.
    if (step < AGL_HISTORY_STEP_M) return
    lastFix[0] = lat to lon
    walked[0] += step
    flown.addLast(walked[0] to amsl)
    val cutoff = walked[0] - AGL_HISTORY_MAX_M
    while (flown.isNotEmpty() && flown.first().first < cutoff) flown.removeFirst()
}

/** The flown track as the panel wants it: astern, oldest first. */
private fun track(
    flown: ArrayDeque<Pair<Double, Double>>,
    walkedNow: Double,
    behindM: Double,
): List<Pair<Double, Double>> {
    if (behindM <= 0.0) return emptyList()
    return flown.mapNotNull { (at, amsl) ->
        val astern = walkedNow - at
        if (astern in 0.0..behindM) astern to amsl else null
    }
}

/** Metres of height per metre along the track, or zero if unknowable. */
private fun slopeOf(state: VehicleState): Double {
    val speed = state.groundSpeedMs?.toDouble() ?: return 0.0
    // Sitting still, "where will I be in two kilometres" has no answer.
    if (speed < AGL_MIN_GROUNDSPEED) return 0.0
    val slope = (state.climbMs?.toDouble() ?: 0.0) / speed
    // Beyond about a thirty degree climb the aircraft is not flying a
    // trajectory any more, it is in trouble, and the line would leave the box.
    return max(-AGL_MAX_SLOPE, min(AGL_MAX_SLOPE, slope))
}

/** A round number of metres per gridline, whatever the span turns out to be. */
private fun niceStep(span: Double, want: Double = 5.0): Double {
    if (span <= 0.0) return 1.0
    val raw = span / want
    val magnitude = 10.0.pow(floor(log10(raw)))
    val n = raw / magnitude
    return (if (n <= 1) 1.0 else if (n <= 2) 2.0 else if (n <= 5) 5.0 else 10.0) * magnitude
}

/**
 * The panel, laid out in the desktop's own 300 by 140 frame and scaled to fit.
 *
 * Painted in order, so what comes last sits on top: grid, ground, path,
 * marker, then every piece of text. The flight path is scaled to the ground
 * rather than to itself, so a climb or descent runs out of the plot box -- and
 * it is allowed to carry on to the edge of the panel, passing behind the
 * readouts rather than stopping dead at an invisible line.
 */
private fun DrawScope.drawAglProfile(slice: AglSlice, amsl: Double) {
    val s = size.width / VIEW_W
    fun p(v: Float) = v * s
    fun pd(v: Double) = (v * s).toFloat()

    val elevations = slice.elevations
    val n = elevations.size
    val span = slice.behindM + slice.aheadM
    if (n < 2 || span <= 0.0) return

    // Relative to the aircraft: negative is below it, positive is ground
    // standing higher than it is flying.
    val rel = DoubleArray(n)
    var any = false
    var lo = 0.0
    var hi = 0.0
    for (i in 0 until n) {
        val e = elevations[i]
        if (e.isNaN()) {
            rel[i] = Double.NaN
            continue
        }
        val r = e - amsl
        rel[i] = r
        if (!any) {
            lo = r
            hi = r
            any = true
        }
        lo = min(lo, r)
        hi = max(hi, r)
    }
    if (!any) return

    // Always show the aircraft's own level, and never squash the picture into
    // a sliver when the ground happens to be flat. The track already flown has
    // to fit, because it happened. The projection ahead deliberately does not:
    // a steep climb would put its far end a kilometre above everything else
    // and press the ground -- the thing the panel is for -- into a few pixels.
    hi = max(hi, 0.0)
    lo = min(lo, 0.0)
    slice.flown.forEach { (_, flownAmsl) ->
        val r = flownAmsl - amsl
        hi = max(hi, r)
        lo = min(lo, r)
    }
    if (hi - lo < 60.0) lo = hi - 60.0
    val pad = (hi - lo) * 0.12
    hi += pad
    lo -= pad

    fun xOf(distance: Double) = AP_L + (AP_R - AP_L) * (distance + slice.behindM) / span
    fun yOf(r: Double) = AP_B - (AP_B - AP_T) * (r - lo) / (hi - lo)
    fun distOf(i: Int) = -slice.behindM + span * i / (n - 1)

    val bottom = pd(AP_B)
    val levelY = pd(yOf(0.0))

    // Gridlines under everything, so a flight path crossing them passes over.
    val vstep = niceStep(hi - lo)
    var v = ceil(lo / vstep) * vstep
    while (v <= hi) {
        val y = pd(yOf(v))
        drawLine(
            color = Color(0x24FFFFFF),
            start = Offset(pd(AP_L), y),
            end = Offset(pd(AP_R), y),
            strokeWidth = p(1f),
        )
        v += vstep
    }

    // The ground, filled down to the bottom of the box. Gaps where no tile has
    // arrived break it rather than being bridged with a straight line, which
    // would draw ground that was never measured.
    val runs = mutableListOf<List<Offset>>()
    var run = mutableListOf<Offset>()
    for (i in 0 until n) {
        if (rel[i].isNaN()) {
            if (run.size > 1) runs.add(run)
            run = mutableListOf()
            continue
        }
        run.add(Offset(pd(xOf(distOf(i))), pd(yOf(rel[i]))))
    }
    if (run.size > 1) runs.add(run)

    runs.forEach { segment ->
        val ground = Path().apply {
            moveTo(segment.first().x, bottom)
            segment.forEach { lineTo(it.x, it.y) }
            lineTo(segment.last().x, bottom)
            close()
        }
        drawPath(ground, Color(0x8CB5A07A))
        drawPath(ground, Color(0xFFB5A07A), style = Stroke(width = p(1f)))
    }

    // Ground standing above the aircraft is not scenery, it is the thing you
    // hit, so it gets its own colour, cut off at the aircraft's own level.
    runs.forEach { segment ->
        var above = mutableListOf<Offset>()
        fun spill() {
            if (above.size < 2) {
                above = mutableListOf()
                return
            }
            val path = Path().apply {
                moveTo(above.first().x, levelY)
                above.forEach { lineTo(it.x, it.y) }
                lineTo(above.last().x, levelY)
                close()
            }
            drawPath(path, Color(0x73C85050))
            drawPath(path, Color(0xFFE06060), style = Stroke(width = p(1f)))
            above = mutableListOf()
        }
        segment.forEach { point ->
            if (point.y <= levelY) above.add(point) else spill()
        }
        spill()
    }

    // The flight path: solid behind, where it has been, dashed ahead, because
    // ahead is a projection of the present climb rate rather than a fact.
    val x0 = pd(xOf(0.0))
    val y0 = levelY
    val behindPath = Path()
    var started = false
    // Oldest first, so this runs left to right and finishes at the aircraft.
    slice.flown.sortedByDescending { it.first }.forEach { (astern, flownAmsl) ->
        val x = pd(xOf(-astern))
        if (x < pd(AP_L)) return@forEach
        val y = pd(yOf(flownAmsl - amsl))
        if (started) behindPath.lineTo(x, y) else behindPath.moveTo(x, y)
        started = true
    }
    if (started) {
        behindPath.lineTo(x0, y0)
    } else {
        // Nothing flown yet -- just connected, or stationary. Fall back to the
        // gradient, which at least says which way it is going.
        behindPath.moveTo(pd(AP_L), pd(yOf(slice.slope * -slice.behindM)))
        behindPath.lineTo(x0, y0)
    }
    drawPath(behindPath, Color(0xFF37A8DB), style = Stroke(width = p(1.5f)))
    drawLine(
        color = Color(0xFF37A8DB),
        start = Offset(x0, y0),
        end = Offset(pd(AP_R), pd(yOf(slice.slope * slice.aheadM))),
        strokeWidth = p(1.5f),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(p(5f), p(4f))),
    )
    drawLine(
        color = Color(0x4DFFFFFF),
        start = Offset(x0, pd(AP_T)),
        end = Offset(x0, bottom),
        strokeWidth = p(1f),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(p(3f), p(3f))),
    )
    drawCircle(Color(0x4037A8DB), radius = p(6f), center = Offset(x0, y0))
    drawCircle(Color.White, radius = p(6f), center = Offset(x0, y0), style = Stroke(width = p(2f)))
    drawCircle(Color.White, radius = p(2f), center = Offset(x0, y0))

    // Every piece of text last, over the picture, so a path crossing the foot
    // of the panel passes behind the distances rather than through them.
    val canvas = drawContext.canvas.nativeCanvas
    val tick = android.graphics.Paint().apply {
        isAntiAlias = true
        color = Color(0xFF8E9AA4).toArgb()
        textSize = p(8f)
    }
    v = ceil(lo / vstep) * vstep
    tick.textAlign = android.graphics.Paint.Align.RIGHT
    while (v <= hi) {
        canvas.drawText(v.roundToInt().toString(), pd(AP_L - 4.0), pd(yOf(v) + 3.0), tick)
        v += vstep
    }
    val hstep = niceStep(span)
    var dm = -floor(slice.behindM / hstep) * hstep
    tick.textAlign = android.graphics.Paint.Align.CENTER
    while (dm <= slice.aheadM) {
        canvas.drawText(abs(dm).roundToInt().toString(), pd(xOf(dm)), pd(AP_B + 12.0), tick)
        dm += hstep
    }

    // The two numbers. AGL is the gap right here; the one on the right is the
    // smallest gap anywhere ahead -- measured against the path the aircraft is
    // on, not against its present height held level, so a descent towards
    // rising ground reads as the problem it is.
    var mid = -1
    var worst = -1
    var worstGap = 0.0
    for (i in 0 until n) {
        if (rel[i].isNaN()) continue
        if (mid < 0 || abs(distOf(i)) < abs(distOf(mid))) mid = i
        if (distOf(i) >= 0.0) {
            val gap = slice.slope * distOf(i) - rel[i]
            if (worst < 0 || gap < worstGap) {
                worstGap = gap
                worst = i
            }
        }
    }
    val caption = android.graphics.Paint().apply {
        isAntiAlias = true
        color = Color(0xFF37A8DB).toArgb()
        textSize = p(10f)
        textAlign = android.graphics.Paint.Align.RIGHT
        isFakeBoldText = true
    }
    canvas.drawText("AGL", p(96f), p(18f), caption)

    val here = android.graphics.Paint().apply {
        isAntiAlias = true
        color = android.graphics.Color.WHITE
        textSize = p(15f)
        isFakeBoldText = true
    }
    canvas.drawText(
        if (mid < 0) "--" else (-rel[mid]).roundToInt().toString() + " m",
        p(100f),
        p(19f),
        here,
    )

    val ahead = android.graphics.Paint().apply {
        isAntiAlias = true
        // Turns amber then red as the gap ahead closes. The number is the
        // point of the whole panel, so it should not need reading to alarm.
        color = when {
            worst < 0 -> Color(0xFFCFD8E0)
            worstGap <= 0.0 -> Color(0xFFFF6B6B)
            worstGap < 50.0 -> Color(0xFFE8C33A)
            else -> Color(0xFFCFD8E0)
        }.toArgb()
        textSize = p(13f)
        textAlign = android.graphics.Paint.Align.RIGHT
        isFakeBoldText = true
    }
    canvas.drawText(
        if (worst < 0) "--" else "▸ " + worstGap.roundToInt().toString() + " m",
        p(292f),
        p(18f),
        ahead,
    )
}

/** The desktop's own frame, so the picture keeps its proportions exactly. */
private const val VIEW_W = 300f

/** The plot box inside it. */
private const val AP_L = 34.0
private const val AP_R = 292.0
private const val AP_T = 30.0
private const val AP_B = 116.0

private const val AGL_POLL_MS = 500L
private const val AGL_STALE_MS = 20_000L

/** Past about a thirty degree climb the line would leave the box. */
private const val AGL_MAX_SLOPE = 0.55

/** Below this the gradient is meaningless. */
private const val AGL_MIN_GROUNDSPEED = 3.0

/** More than the longest look-behind, so a range change finds history kept. */
private const val AGL_HISTORY_MAX_M = 4000.0
private const val AGL_HISTORY_STEP_M = 2.0
