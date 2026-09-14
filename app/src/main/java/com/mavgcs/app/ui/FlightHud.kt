package com.mavgcs.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mavgcs.app.mavlink.VehicleState
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

private val SkyColor = Color(0xFF3A6EA5)
private val GroundColor = Color(0xFF8B5A2B)
private val HudYellow = Color(0xFFFFD54F)
private val TapeBackground = Color(0xCC141414)
private val HudTextColor = Color(0xFFE6E6E6)

/** Degrees of pitch from the centre of the horizon to the top of the HUD. */
private const val PITCH_HALF_RANGE_DEG = 35f

/** Degrees of heading visible across the width of the heading strip. */
private const val HEADING_SPAN_DEG = 90f

private const val NO_VALUE = "--"

private val hudLabelStyle = TextStyle(
    fontSize = 9.sp,
    color = HudTextColor,
    fontWeight = FontWeight.Medium,
)

/**
 * The desktop HUD: a full-width artificial horizon with an airspeed tape on the
 * left, an altitude tape on the right, a heading strip along the top, and the
 * battery and position readouts overlaid at the corners.
 */
@Composable
fun FlightHud(vehicle: VehicleState, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black),
    ) {
        // On a short tablet the HUD shrinks enough that the battery block and the
        // position readouts collide, so the overlays tighten with it.
        val compact = maxHeight < 130.dp
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawHorizon(vehicle.rollDeg, vehicle.pitchDeg, measurer)
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
            drawHeadingStrip(vehicle.headingDeg ?: vehicle.yawDeg, measurer)
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

        // Battery, where the desktop HUD keeps it.
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = if (compact) 10.dp else 24.dp, end = 54.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(TapeBackground)
                .padding(horizontal = 6.dp, vertical = if (compact) 2.dp else 5.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 0.dp else 1.dp),
        ) {
            HudReadout(vehicle.batteryV.oneDecimal(), "V", compact)
            HudReadout(vehicle.batteryA.oneDecimal(), "A", compact)
            HudReadout(vehicle.batteryRemainingPct?.toString() ?: NO_VALUE, "%", compact)
        }
    }
}

@Composable
private fun HudCaption(text: String, modifier: Modifier = Modifier) {
    Text(text = text, fontSize = 8.sp, color = HudTextColor.copy(alpha = 0.8f), modifier = modifier)
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
private fun DrawScope.drawVerticalTape(
    value: Float?,
    tickStep: Float,
    labelStep: Float,
    pxPerUnit: Float,
    onLeft: Boolean,
    measurer: TextMeasurer,
) {
    val width = 40.dp.toPx()
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
            val text = measurer.measure(AnnotatedString(tick.roundToInt().toString()), hudLabelStyle)
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
    val height = 18.dp.toPx()
    drawRect(TapeBackground, topLeft = Offset(0f, 0f), size = Size(size.width, height))

    val pxPerDeg = size.width / HEADING_SPAN_DEG
    val centerX = size.width / 2f
    val half = HEADING_SPAN_DEG / 2f
    var deg = floor((headingDeg - half) / 10f) * 10f
    while (deg <= headingDeg + half) {
        val x = centerX + (deg - headingDeg) * pxPerDeg
        drawLine(
            color = HudTextColor.copy(alpha = 0.75f),
            start = Offset(x, height - 5.dp.toPx()),
            end = Offset(x, height),
            strokeWidth = 1.5f,
        )
        // Labelled every 30 degrees as a compass reads: 000, 030 ... 330, then
        // 000 again. Ticks stay every 10 so the scale is still fine-grained.
        if (((deg % 30f) + 30f) % 30f < 0.01f) {
            val normalized = (((deg % 360f) + 360f) % 360f).roundToInt() % 360
            val text = measurer.measure(
                AnnotatedString(normalized.toString().padStart(3, '0')),
                hudLabelStyle,
            )
            drawText(
                textLayoutResult = text,
                topLeft = Offset(x - text.size.width / 2f, 1f),
            )
        }
        deg += 10f
    }

    // Fixed pointer at the centre of the strip.
    drawLine(
        color = HudYellow,
        start = Offset(centerX, 0f),
        end = Offset(centerX, height),
        strokeWidth = 2f,
    )
}

private fun Float?.oneDecimal(): String = this?.let { "%.1f".format(it) } ?: NO_VALUE
