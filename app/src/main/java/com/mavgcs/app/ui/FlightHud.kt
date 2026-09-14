package com.mavgcs.app.ui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mavgcs.app.ui.theme.MavGreen
import com.mavgcs.app.mavlink.HealthTint
import com.mavgcs.app.mavlink.VehicleState
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import java.util.Locale

private val SkyColor = Color(0xFF3A6EA5)
private val GroundColor = Color(0xFF8B5A2B)
private val HudYellow = Color(0xFFFFD54F)
private val TapeBackground = Color(0xCC141414)
private val HudTextColor = Color(0xFFE6E6E6)

/** Degrees of pitch from the centre of the horizon to the top of the HUD. */
private const val PITCH_HALF_RANGE_DEG = 35f

/** Height of the heading strip. The battery block is placed clear of it. */
private val HeadingStripHeight = 18.dp

/** The wind readout, in the corner the desktop HUD keeps it. */
private val WindArrowHalf = 11.dp
private val WindArrowColor = Color(0xFF78DCFF)

/** Width of each vertical tape. The heading strip runs between the two. */
private val TapeWidth = 40.dp

/** The throttle column, inboard of the airspeed tape. */
private val ThrottleBarWidth = 14.dp
private val ThrottleFill = MavGreen

/** The vertical speed column, inboard of the altitude tape. */
private val VsiBarWidth = 14.dp
private val VsiFill = HudYellow

/** Fixed, so the battery block can be held clear of it without measuring. */
private val VsiReadoutWidth = 40.dp

/** What a full deflection means, up or down. */
private const val VSI_FULL_SCALE_MS = 10f

/** Degrees of heading visible across the width of the heading strip. */
private const val HEADING_SPAN_DEG = 90f

private const val NO_VALUE = "--"

private val hudLabelStyle = TextStyle(
    fontSize = 9.sp,
    color = HudTextColor,
    fontWeight = FontWeight.Medium,
)

/**
 * The sliding scale numbers are context, not the reading, so they are drawn at
 * half strength. The value in the pointer box keeps the full weight.
 */
private val hudScaleStyle = hudLabelStyle.copy(color = HudTextColor.copy(alpha = 0.32f))

/**
 * The desktop HUD: a full-width artificial horizon with an airspeed tape on the
 * left, an altitude tape on the right, a heading strip along the top, and the
 * battery and position readouts overlaid at the corners.
 */
@Composable
fun FlightHud(vehicle: VehicleState, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val context = LocalContext.current
    // Remembered across runs: the pack on the aircraft does not change between
    // one launch of the app and the next.
    var cells by remember { mutableStateOf(loadCellCount(context)) }
    BoxWithConstraints(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black),
    ) {
        // On a short tablet the HUD shrinks enough that the battery block and the
        // position readouts collide, so the overlays tighten with it.
        val compact = maxHeight < 130.dp
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawHorizon(vehicle.rollDeg ?: 0f, vehicle.pitchDeg ?: 0f, measurer)
            drawVerticalTape(
                value = vehicle.airSpeedMs,
                tickStep = 2f,
                labelStep = 10f,
                pxPerUnit = 3.2.dp.toPx(),
                onLeft = true,
                measurer = measurer,
            )
            drawVerticalTape(
                value = vehicle.altRelM,
                tickStep = 5f,
                labelStep = 20f,
                pxPerUnit = 1.1.dp.toPx(),
                onLeft = false,
                measurer = measurer,
            )
            drawHeadingStrip(vehicle.headingDeg ?: vehicle.yawDeg ?: 0f, measurer)
            drawThrottleBar(vehicle.throttlePct, measurer)
            drawVerticalSpeedBar(vehicle.climbMs, measurer)
        }

        HudCaption(
            text = "IAS m/s",
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 4.dp, bottom = if (compact) 15.dp else 22.dp),
        )
        HudCaption(
            text = "ALT m",
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 4.dp, bottom = if (compact) 15.dp else 22.dp),
        )

        // Wind, in the top left corner the desktop puts it in. Below the
        // heading strip rather than beside it: the desktop's heading tape is
        // centred and only half the width, so it leaves that corner free,
        // while this one runs the whole way across.
        WindBox(
            fromDeg = vehicle.windDirectionDeg,
            speedMs = vehicle.windSpeedMs,
            headingDeg = vehicle.headingDeg ?: vehicle.yawDeg,
            compact = compact,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(
                    start = TapeWidth + ThrottleBarWidth + 6.dp,
                    top = HeadingStripHeight + 6.dp,
                ),
        )

        // Battery, where the desktop HUD keeps it.
        Column(
            modifier = Modifier
                // Centred in the band between the heading strip and the bottom
                // edge. Padding the top by the strip height and then centring
                // shifts the content down by half of it, which lands it in the
                // middle of what is left whatever height the HUD ends up.
                .align(Alignment.CenterEnd)
                .padding(
                    top = HeadingStripHeight,
                    // Still clear of the vertical speed column and its readout,
                    // just sitting a little closer to them.
                    end = 46.dp + VsiBarWidth + VsiReadoutWidth,
                )
                .clip(RoundedCornerShape(6.dp))
                .background(TapeBackground)
                .padding(horizontal = 6.dp, vertical = if (compact) 2.dp else 5.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 0.dp else 1.dp),
        ) {
            HudReadout(vehicle.batteryV.oneDecimal(), "V", compact)
            // Per cell, which is the number that says how much is really left:
            // a pack reads healthy long after its cells have sagged.
            HudReadout(vehicle.batteryV?.div(cells).twoDecimals(), "V/C", compact)
            HudReadout(vehicle.batteryA.oneDecimal(), "A", compact)
            HudReadout(vehicle.batteryRemainingPct?.toString() ?: NO_VALUE, "%", compact)
            CellCountSelector(
                cells = cells,
                compact = compact,
                onSelect = {
                    cells = it
                    saveCellCount(context, it)
                },
            )
        }

        // Mission Planner's own HUD convention: bottom middle, EKF left of
        // centre and VIBE right of it, just the coloured word and no value.
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = if (compact) 3.dp else 5.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            HudStatusFlag("EKF", vehicle.ekfTint, compact)
            HudStatusFlag("VIBE", vehicle.vibeTint, compact)
        }
    }
}

/**
 * One of the two HUD status words. White is the quiet state -- Mission Planner
 * shows the word whatever it has to say, so its absence would be a fault the
 * pilot could not see.
 */
@Composable
private fun HudStatusFlag(label: String, tint: HealthTint?, compact: Boolean) {
    val colour = when (tint) {
        HealthTint.RED -> Color(0xFFFF3C3C)
        HealthTint.YELLOW -> Color.Yellow
        else -> Color.White
    }
    Box(
        modifier = Modifier
            .width(if (compact) 40.dp else 48.dp)
            .background(Color(0xD20F0F0F))
            .border(1.dp, Color.White.copy(alpha = 0.85f))
            .padding(vertical = if (compact) 1.dp else 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = if (compact) 7.sp else 8.sp,
            fontWeight = FontWeight.Bold,
            color = colour,
            maxLines = 1,
        )
    }
}

@Composable
private fun HudCaption(text: String, modifier: Modifier = Modifier) {
    // Full white: these name the tapes, so they should read at a glance even
    // though the sliding numbers beside them are deliberately faint.
    Text(text = text, fontSize = 8.sp, color = Color.White, modifier = modifier)
}


/**
 * Wind speed and direction, with an arrow drawn in the aircraft's own frame.
 *
 * The arrow points where the wind is blowing TOWARD, not where it comes from:
 * flying into a headwind, it points down the screen, which is the direction
 * the air is pushing the aeroplane. WIND reports the bearing the wind comes
 * FROM, so that is a half turn, and subtracting the heading swings it out of
 * compass north and into the nose's frame.
 *
 * The figure stays in degrees true, because that is what gets read back on the
 * radio and compared with the forecast; only the arrow is relative.
 */
@Composable
private fun WindBox(
    fromDeg: Float?,
    speedMs: Float?,
    headingDeg: Float?,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(TapeBackground)
            .padding(horizontal = 6.dp, vertical = if (compact) 2.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(
            modifier = Modifier
                .width(WindArrowHalf * 2)
                .height(WindArrowHalf * 2),
        ) {
            // No reading yet: the arrow rests pointing up rather than
            // vanishing, so the box keeps its shape and its place.
            val turn = if (fromDeg == null) {
                0f
            } else {
                (fromDeg - (headingDeg ?: 0f) + 180f).mod(360f)
            }
            rotate(degrees = turn) {
                val half = WindArrowHalf.toPx()
                val cx = size.width / 2f
                val cy = size.height / 2f
                val tip = cy - half
                drawLine(
                    color = WindArrowColor,
                    start = Offset(cx, cy + half),
                    end = Offset(cx, tip),
                    strokeWidth = 2.dp.toPx(),
                )
                val wing = 4.dp.toPx()
                val barb = 8.dp.toPx()
                drawPath(
                    path = Path().apply {
                        moveTo(cx, tip)
                        lineTo(cx - wing, tip + barb)
                        lineTo(cx + wing, tip + barb)
                        close()
                    },
                    color = WindArrowColor,
                )
            }
        }
        Column(modifier = Modifier.padding(start = 5.dp)) {
            Text(
                text = fromDeg?.let { "%03d°".format(Locale.ROOT, it.roundToInt() % 360) }
                    ?: "---°",
                fontSize = if (compact) 9.sp else 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = HudTextColor,
            )
            Text(
                text = speedMs?.let { "%.1f kph".format(Locale.ROOT, it * 3.6f) } ?: "-- kph",
                fontSize = if (compact) 7.sp else 9.sp,
                color = HudTextColor.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun HudReadout(value: String, unit: String, compact: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = value,
            fontSize = if (compact) 9.sp else 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = HudTextColor,
        )
        Text(
            text = " $unit",
            fontSize = if (compact) 7.sp else 9.sp,
            color = HudTextColor.copy(alpha = 0.7f),
        )
    }
}

private fun DrawScope.drawHorizon(rollDeg: Float, pitchDeg: Float, measurer: TextMeasurer) {
    val center = Offset(size.width / 2f, size.height / 2f)
    val pxPerDeg = size.height / (2f * PITCH_HALF_RANGE_DEG)
    val pitchPx = pitchDeg.coerceIn(-90f, 90f) * pxPerDeg
    // Large enough that sky and ground still cover the HUD at any roll angle.
    val span = max(size.width, size.height) * 1.6f

    withTransform({
        // Rolling right drops the right wing, so the world tips the other way in
        // the aircraft's frame. Compose rotates clockwise for positive degrees.
        rotate(-rollDeg, center)
        // Nose up pushes the horizon down.
        translate(0f, pitchPx)
    }) {
        drawRect(SkyColor, topLeft = Offset(center.x - span, center.y - span), size = Size(span * 2f, span))
        drawRect(GroundColor, topLeft = Offset(center.x - span, center.y), size = Size(span * 2f, span))
        drawLine(
            color = Color.White,
            start = Offset(center.x - span, center.y),
            end = Offset(center.x + span, center.y),
            strokeWidth = 2f,
        )
        for (deg in intArrayOf(-30, -20, -10, 10, 20, 30)) {
            // Positive pitch marks sit above the horizon.
            val y = center.y - deg * pxPerDeg
            val half = if (deg % 20 == 0) size.width * 0.11f else size.width * 0.07f
            drawLine(
                color = Color.White.copy(alpha = 0.85f),
                start = Offset(center.x - half, y),
                end = Offset(center.x + half, y),
                strokeWidth = 1.5f,
            )
            val label = measurer.measure(AnnotatedString(deg.toString()), hudLabelStyle)
            drawText(
                textLayoutResult = label,
                topLeft = Offset(center.x - half - label.size.width - 4f, y - label.size.height / 2f),
            )
        }
    }

    // Fixed aircraft symbol.
    val wing = size.width * 0.13f
    drawLine(HudYellow, Offset(center.x - wing, center.y), Offset(center.x - 10f, center.y), strokeWidth = 3f)
    drawLine(HudYellow, Offset(center.x + 10f, center.y), Offset(center.x + wing, center.y), strokeWidth = 3f)
    drawCircle(HudYellow, radius = 2.5f, center = center)
}

/**
 * A sliding tape. Ticks are laid out around the current value so the scale moves
 * past a fixed pointer, rather than the pointer moving along a fixed scale.
 */
/**
 * Throttle as a column filling from the bottom, outboard of the airspeed tape.
 *
 * A bar rather than a number: what matters in the air is whether it is pinned
 * or backing off, and that is a shape, read without stopping to parse a figure.
 */
/**
 * Vertical speed as a needle either side of a centre line: level flight sits in
 * the middle, and a full deflection is ten metres a second.
 *
 * Deliberately not a number. What the pilot needs mid-circuit is whether the
 * aeroplane is going up or down and roughly how hard, and a needle either side
 * of a datum answers that without being read.
 */
private fun DrawScope.drawVerticalSpeedBar(climbMs: Float?, measurer: TextMeasurer) {
    val width = VsiBarWidth.toPx()
    val left = size.width - TapeWidth.toPx() - width
    drawRect(TapeBackground, topLeft = Offset(left, 0f), size = Size(width, size.height))

    val inset = 2.dp.toPx()
    val trackHeight = size.height - inset * 2
    val centreY = inset + trackHeight / 2f
    // The datum, drawn whether or not there is a reading: without it the column
    // would be a blank strip rather than an instrument waiting for data.
    drawLine(
        color = Color.White.copy(alpha = 0.35f),
        start = Offset(left, centreY),
        end = Offset(left + width, centreY),
        strokeWidth = 1f,
    )

    if (climbMs != null) {
        val travel = (climbMs.coerceIn(-VSI_FULL_SCALE_MS, VSI_FULL_SCALE_MS) /
            VSI_FULL_SCALE_MS) * (trackHeight / 2f)
        val y = centreY - travel
        drawRect(
            color = VsiFill.copy(alpha = 0.5f),
            topLeft = Offset(left + inset, minOf(centreY, y)),
            size = Size(width - inset * 2, abs(travel)),
        )
        drawLine(
            color = VsiFill,
            start = Offset(left + inset, y),
            end = Offset(left + width - inset, y),
            strokeWidth = 2.5f,
        )
    }

    // The figure sits outboard of the column at mid-height, boxed like the
    // throttle's. Signed, because which way it is going is the whole point,
    // and a dash rather than nothing when there is no reading yet.
    val readout = measurer.measure(
        AnnotatedString(
            climbMs?.let { (if (it > 0.05f) "+" else "") + "%.1f".format(Locale.ROOT, it) }
                ?: "--",
        ),
        hudLabelStyle,
    )
    val boxHeight = 16.dp.toPx()
    val boxWidth = VsiReadoutWidth.toPx()
    val boxLeft = left - boxWidth
    drawRect(
        color = Color.Black,
        topLeft = Offset(boxLeft, centreY - boxHeight / 2f),
        size = Size(boxWidth, boxHeight),
    )
    drawRect(
        color = VsiFill,
        topLeft = Offset(boxLeft, centreY - boxHeight / 2f),
        size = Size(boxWidth, boxHeight),
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f),
    )
    drawText(
        textLayoutResult = readout,
        topLeft = Offset(
            boxLeft + (boxWidth - readout.size.width) / 2f,
            centreY - readout.size.height / 2f,
        ),
    )
}

private fun DrawScope.drawThrottleBar(throttlePct: Int?, measurer: TextMeasurer) {
    val width = ThrottleBarWidth.toPx()
    val left = TapeWidth.toPx()
    drawRect(TapeBackground, topLeft = Offset(left, 0f), size = Size(width, size.height))
    val percent = throttlePct?.coerceIn(0, 100)
    if (percent != null) {
        val inset = 2.dp.toPx()
        val trackHeight = size.height - inset * 2
        val filled = trackHeight * percent / 100f
        drawRect(
            color = ThrottleFill,
            topLeft = Offset(left + inset, inset + trackHeight - filled),
            size = Size(width - inset * 2, filled),
        )
    }

    // The figure sits beside the column at mid-height, boxed like the tapes'
    // own readouts so the three read as one instrument. It stays up with no
    // reading, showing a dash: an instrument that is merely waiting looks like
    // a missing instrument if it disappears.
    val readout = measurer.measure(
        AnnotatedString(percent?.let { "$it%" } ?: "--"),
        hudLabelStyle,
    )
    val centreY = size.height / 2f
    val boxHeight = 16.dp.toPx()
    // Sized to the widest figure it will ever hold, so it does not resize as
    // the throttle moves or when the first reading lands.
    val boxWidth = measurer.measure(AnnotatedString("100%"), hudLabelStyle).size.width +
        8.dp.toPx()
    val boxLeft = left + width
    drawRect(
        color = Color.Black,
        topLeft = Offset(boxLeft, centreY - boxHeight / 2f),
        size = Size(boxWidth, boxHeight),
    )
    drawRect(
        color = ThrottleFill,
        topLeft = Offset(boxLeft, centreY - boxHeight / 2f),
        size = Size(boxWidth, boxHeight),
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f),
    )
    drawText(
        textLayoutResult = readout,
        topLeft = Offset(
            boxLeft + (boxWidth - readout.size.width) / 2f,
            centreY - readout.size.height / 2f,
        ),
    )
}

private fun DrawScope.drawVerticalTape(
    value: Float?,
    tickStep: Float,
    labelStep: Float,
    pxPerUnit: Float,
    onLeft: Boolean,
    measurer: TextMeasurer,
) {
    val width = TapeWidth.toPx()
    val left = if (onLeft) 0f else size.width - width
    val centerY = size.height / 2f
    drawRect(TapeBackground, topLeft = Offset(left, 0f), size = Size(width, size.height))

    val current = value ?: 0f
    val halfSpan = centerY / pxPerUnit
    var tick = floor((current - halfSpan) / tickStep) * tickStep
    while (tick <= current + halfSpan) {
        val y = centerY - (tick - current) * pxPerUnit
        val ratio = tick / labelStep
        val labelled = abs(ratio - ratio.roundToInt()) < 0.01f
        val tickLength = if (labelled) 9.dp.toPx() else 5.dp.toPx()
        val tickStart = if (onLeft) left + width - tickLength else left
        drawLine(
            color = HudTextColor.copy(alpha = 0.75f),
            start = Offset(tickStart, y),
            end = Offset(tickStart + tickLength, y),
            strokeWidth = 1.5f,
        )
        if (labelled && tick >= 0f) {
            val text = measurer.measure(AnnotatedString(tick.roundToInt().toString()), hudScaleStyle)
            val textX = if (onLeft) left + 3f else left + width - text.size.width - 3f
            drawText(textLayoutResult = text, topLeft = Offset(textX, y - text.size.height / 2f))
        }
        tick += tickStep
    }

    // Fixed pointer showing the current value.
    val readout = if (value == null) NO_VALUE else value.roundToInt().toString()
    val pointer = measurer.measure(AnnotatedString(readout), hudLabelStyle)
    val boxHeight = 16.dp.toPx()
    drawRect(
        color = Color.Black,
        topLeft = Offset(left, centerY - boxHeight / 2f),
        size = Size(width, boxHeight),
    )
    drawRect(
        color = HudYellow,
        topLeft = Offset(left, centerY - boxHeight / 2f),
        size = Size(width, boxHeight),
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f),
    )
    drawText(
        textLayoutResult = pointer,
        topLeft = Offset(
            left + (width - pointer.size.width) / 2f,
            centerY - pointer.size.height / 2f,
        ),
    )
}

private fun DrawScope.drawHeadingStrip(headingDeg: Float, measurer: TextMeasurer) {
    val height = HeadingStripHeight.toPx()
    // The strip runs between the tapes rather than over them, so the span it
    // covers is the width left in the middle, not the whole HUD.
    val left = (ThrottleBarWidth + TapeWidth).toPx()
    val right = size.width - (TapeWidth + VsiBarWidth).toPx()
    val spanWidth = right - left
    if (spanWidth <= 0f) {
        return
    }
    drawRect(TapeBackground, topLeft = Offset(left, 0f), size = Size(spanWidth, height))

    val pxPerDeg = spanWidth / HEADING_SPAN_DEG
    val centerX = left + spanWidth / 2f
    val half = HEADING_SPAN_DEG / 2f
    // Clipped so a label at the edge slides off under the tape rather than
    // being drawn across it.
    clipRect(left = left, top = 0f, right = right, bottom = height) {
        var deg = floor((headingDeg - half) / 10f) * 10f
        while (deg <= headingDeg + half) {
            val x = centerX + (deg - headingDeg) * pxPerDeg
            drawLine(
                color = HudTextColor.copy(alpha = 0.75f),
                start = Offset(x, height - 5.dp.toPx()),
                end = Offset(x, height),
                strokeWidth = 1.5f,
            )
            // Labelled every 30 degrees as a compass reads: N, 030, 060, E and so on.
            // Ticks stay every 10 so the scale keeps its resolution.
            if (((deg % 30f) + 30f) % 30f < 0.01f) {
                val normalized = (((deg % 360f) + 360f) % 360f).roundToInt() % 360
                val label = when (normalized) {
                    0 -> "N"
                    90 -> "E"
                    180 -> "S"
                    270 -> "W"
                    else -> normalized.toString().padStart(3, '0')
                }
                val text = measurer.measure(
                    AnnotatedString(label),
                    hudLabelStyle,
                )
                drawText(
                    textLayoutResult = text,
                    topLeft = Offset(x - text.size.width / 2f, 1f),
                )
            }
            deg += 10f
        }
    }

    // Fixed pointer at the centre of the strip.
    drawLine(
        color = HudYellow,
        start = Offset(centerX, 0f),
        end = Offset(centerX, height),
        strokeWidth = 2f,
    )
}

/** The pack sizes offered. */
private val CELL_COUNTS = listOf(3, 4, 6)

private const val CELLS_PREF_FILE = "mavgcs"
private const val CELLS_PREF_KEY = "battery_cells"
private const val DEFAULT_CELLS = 4

private fun loadCellCount(context: Context): Int =
    context.getSharedPreferences(CELLS_PREF_FILE, Context.MODE_PRIVATE)
        .getInt(CELLS_PREF_KEY, DEFAULT_CELLS)
        .takeIf { it in CELL_COUNTS }
        ?: DEFAULT_CELLS

private fun saveCellCount(context: Context, cells: Int) {
    context.getSharedPreferences(CELLS_PREF_FILE, Context.MODE_PRIVATE)
        .edit()
        .putInt(CELLS_PREF_KEY, cells)
        .apply()
}

/**
 * How many cells the pack has, which is the only thing the app cannot work out
 * for itself -- the vehicle reports a pack voltage and nothing about its make-up.
 */
@Composable
private fun CellCountSelector(cells: Int, compact: Boolean, onSelect: (Int) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.padding(top = if (compact) 1.dp else 3.dp),
    ) {
        CELL_COUNTS.forEach { count ->
            val chosen = count == cells
            Text(
                text = count.toString() + "S",
                fontSize = if (compact) 7.sp else 9.sp,
                fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal,
                color = if (chosen) Color.Black else HudTextColor.copy(alpha = 0.75f),
                modifier = Modifier
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (chosen) HudYellow else Color.White.copy(alpha = 0.12f))
                    .clickable { onSelect(count) }
                    .padding(horizontal = if (compact) 3.dp else 5.dp, vertical = 1.dp),
            )
        }
    }
}

private fun Float?.oneDecimal(): String = this?.let { "%.1f".format(Locale.ROOT, it) } ?: NO_VALUE

/** Cell voltages are read to the hundredth; a tenth hides the sag that matters. */
private fun Float?.twoDecimals(): String =
    this?.let { "%.2f".format(Locale.ROOT, it) } ?: NO_VALUE
