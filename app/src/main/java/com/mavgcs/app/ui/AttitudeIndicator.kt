package com.mavgcs.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.min

private val SkyColor = Color(0xFF3A6EA5)
private val GroundColor = Color(0xFF8B5A2B)
private val AccentColor = Color(0xFF3DDC97)

/** Degrees of pitch between the horizon and the edge of the dial. */
private const val PITCH_RANGE_DEG = 30f

@Composable
fun AttitudeIndicator(
    rollDeg: Float,
    pitchDeg: Float,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = min(size.width, size.height) / 2f
        val pxPerDeg = radius / PITCH_RANGE_DEG
        val pitchPx = pitchDeg.coerceIn(-90f, 90f) * pxPerDeg
        // Wide enough that sky and ground still cover the dial at any roll angle.
        val span = radius * 3f

        clipPath(Path().apply { addOval(Rect(center = center, radius = radius)) }) {
            withTransform({
                // Rolling right drops the right wing, so the world tips the other
                // way in the aircraft's frame. Compose rotates clockwise for
                // positive degrees, hence the negation.
                rotate(-rollDeg, center)
                // Nose up pushes the horizon down the dial.
                translate(0f, pitchPx)
            }) {
                drawRect(
                    color = SkyColor,
                    topLeft = Offset(center.x - span, center.y - span),
                    size = Size(span * 2f, span),
                )
                drawRect(
                    color = GroundColor,
                    topLeft = Offset(center.x - span, center.y),
                    size = Size(span * 2f, span),
                )
                drawLine(
                    color = Color.White,
                    start = Offset(center.x - span, center.y),
                    end = Offset(center.x + span, center.y),
                    strokeWidth = 2f,
                )
                drawPitchLadder(center, radius, pxPerDeg)
            }
        }

        // Fixed aircraft reference, level with the centre of the dial.
        drawLine(
            color = AccentColor,
            start = Offset(center.x - radius * 0.45f, center.y),
            end = Offset(center.x - 14f, center.y),
            strokeWidth = 4f,
        )
        drawLine(
            color = AccentColor,
            start = Offset(center.x + 14f, center.y),
            end = Offset(center.x + radius * 0.45f, center.y),
            strokeWidth = 4f,
        )
        drawCircle(color = AccentColor, radius = 3f, center = center)
        drawCircle(color = Color.White, radius = radius, center = center, style = Stroke(width = 3f))
    }
}

private fun DrawScope.drawPitchLadder(center: Offset, radius: Float, pxPerDeg: Float) {
    for (deg in intArrayOf(-20, -10, 10, 20)) {
        // Positive pitch marks sit above the horizon line.
        val y = center.y - deg * pxPerDeg
        val half = if (deg % 20 == 0) radius * 0.26f else radius * 0.15f
        drawLine(
            color = Color.White.copy(alpha = 0.75f),
            start = Offset(center.x - half, y),
            end = Offset(center.x + half, y),
            strokeWidth = 1.5f,
        )
    }
}
