package com.mavgcs.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.min

@Composable
fun AttitudeIndicator(
    rollDeg: Float,
    pitchDeg: Float,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val radius = min(size.width, size.height) / 2f
        val pitchPx = (pitchDeg.coerceIn(-30f, 30f) / 30f) * radius
        withTransform({
            rotate(rollDeg)
            translate(0f, pitchPx)
        }) {
            drawRect(Color(0xFF3A6EA5), topLeft = Offset(-size.width, -size.height * 2), size = Size(size.width * 3, size.height * 2))
            drawRect(Color(0xFF8B5A2B), topLeft = Offset(-size.width, 0f), size = Size(size.width * 3, size.height * 2))
            drawLine(Color.White, Offset(-size.width, 0f), Offset(size.width * 2, 0f), strokeWidth = 2f)
        }
        drawCircle(Color.Transparent, radius, style = Stroke(width = 0f))
        drawLine(Color(0xFF3DDC97), Offset(size.width / 2f - radius * 0.45f, size.height / 2f), Offset(size.width / 2f - 16f, size.height / 2f), 4f)
        drawLine(Color(0xFF3DDC97), Offset(size.width / 2f + 16f, size.height / 2f), Offset(size.width / 2f + radius * 0.45f, size.height / 2f), 4f)
        val wing = Path().apply {
            moveTo(size.width / 2f, size.height / 2f)
            lineTo(size.width / 2f - 10f, size.height / 2f + 12f)
            lineTo(size.width / 2f + 10f, size.height / 2f + 12f)
            close()
        }
        drawPath(wing, Color(0xFF3DDC97))
        drawCircle(Color.White, radius, style = Stroke(width = 3f))
    }
}
