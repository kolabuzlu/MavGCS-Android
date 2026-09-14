package com.mavgcs.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mavgcs.app.ui.theme.MavGreen
import com.mavgcs.app.mavlink.VehicleState
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import java.util.Locale

/**
 * Laid out in the desktop's 200-unit SVG space and scaled to whatever it is
 * given, so the geometry below reads straight across from map_view.py.
 */
private const val CP_SIZE = 200f
private const val CP_RADIUS = 94f

private val CompassFace = Color(0x38000000)
private val CompassNorth = Color(0xFFFF4D4D)
private val CompassTrack = Color(0xFFFFC83D)
private val CompassCourse = Color(0xFFFFA726)
private val CompassWindText = Color(0xFF4FC3F7)
private val CompassHome = MavGreen
private val CompassWind = Color(0xFF1E9FD6)

/** The wind arrow sits under the cardinals, which paint over it. */
private const val CP_WIND_ALPHA = 0.78f

/**
 * Heading-up compass rose. The card turns under a fixed white index, so
 * whatever sits at the top of the dial is straight ahead.
 *
 * Three bearings are shown at once: the nose (white, the index itself), the
 * course over ground (amber, riding the card), and the wind (blue). The gap
 * between the white index and the amber marker is the drift angle -- visible
 * as a separation rather than as the difference between two numbers.
 */
@Composable
fun CompassRose(
    vehicle: VehicleState,
    size: Dp = 200.dp,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val heading = vehicle.headingDeg ?: vehicle.yawDeg ?: 0f
    val course = vehicle.groundCourseDeg
    val homeBearing = bearingToHome(vehicle)

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            // Deliberately lighter than the radar below it: that is a data
            // display needing its own ground, this is a dial read against the
            // map showing through it.
            .background(Color(0x801E1E1E))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(8.dp)),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCompass(
                heading = heading,
                courseDeg = course,
                homeBearingDeg = homeBearing,
                windFromDeg = vehicle.windDirectionDeg,
                windSpeedMs = vehicle.windSpeedMs,
                textMeasurer = textMeasurer,
            )
        }
    }
}

/**
 * Which way home lies. Null until both the aircraft and its home are known,
 * which is what hides the arrow rather than leaving it pointing north.
 */
private fun bearingToHome(vehicle: VehicleState): Float? {
    val lat = vehicle.lat ?: return null
    val lon = vehicle.lon ?: return null
    val homeLat = vehicle.homeLat ?: return null
    val homeLon = vehicle.homeLon ?: return null
    val p1 = Math.toRadians(lat)
    val p2 = Math.toRadians(homeLat)
    val dl = Math.toRadians(homeLon - lon)
    val y = sin(dl) * cos(p2)
    val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
    return normalise(Math.toDegrees(atan2(y, x)).toFloat())
}

private fun normalise(degrees: Float): Float = ((degrees % 360f) + 360f) % 360f

private fun DrawScope.drawCompass(
    heading: Float,
    courseDeg: Float?,
    homeBearingDeg: Float?,
    windFromDeg: Float?,
    windSpeedMs: Float?,
    textMeasurer: TextMeasurer,
) {
    val unit = size.width / CP_SIZE
    val centre = Offset(100f * unit, 100f * unit)

    fun at(x: Float, y: Float) = Offset(x * unit, y * unit)

    drawCircle(CompassFace, radius = CP_RADIUS * unit, center = centre)
    drawCircle(
        color = Color.White.copy(alpha = 0.18f),
        radius = CP_RADIUS * unit,
        center = centre,
        style = Stroke(width = 2f * unit),
    )

    // The card turns under the index, so the rose is rotated against heading.
    rotate(-heading, centre) {
        for (deg in 0 until 360 step 10) {
            val major = deg % 30 == 0
            val inner = if (major) 80f else 87f
            val radians = ((deg - 90) * PI / 180.0).toFloat()
            drawLine(
                color = if (major) Color.White else Color.White.copy(alpha = 0.55f),
                start = at(
                    100f + CP_RADIUS * cos(radians),
                    100f + CP_RADIUS * sin(radians),
                ),
                end = at(100f + inner * cos(radians), 100f + inner * sin(radians)),
                strokeWidth = (if (major) 2.5f else 1.5f) * unit,
            )
        }

        // WIND reports where the wind comes FROM; the arrow shows where it is
        // pushing the aircraft, which is the opposite way.
        if (windFromDeg != null) {
            rotate(normalise(windFromDeg + 180f), centre) {
                drawLine(
                    color = CompassWind,
                    start = at(100f, 72f),
                    end = at(100f, 42f),
                    strokeWidth = 4f * unit,
                    cap = StrokeCap.Round,
                    alpha = CP_WIND_ALPHA,
                )
                drawPath(
                    path = arrowHead(unit, 100f to 28f, 91f to 46f, 100f to 41f, 109f to 46f),
                    color = CompassWind,
                    alpha = CP_WIND_ALPHA,
                )
            }
        }

        if (homeBearingDeg != null) {
            rotate(homeBearingDeg, centre) {
                drawLine(
                    color = CompassHome,
                    start = at(100f, 66f),
                    end = at(100f, 46f),
                    strokeWidth = 4f * unit,
                    cap = StrokeCap.Round,
                )
                drawPath(
                    path = arrowHead(unit, 100f to 34f, 92f to 50f, 100f to 45f, 108f to 50f),
                    color = CompassHome,
                )
            }
        }

        val cardinalStyle = TextStyle(
            color = Color.White,
            fontSize = (20f * unit).toSp(),
            fontWeight = FontWeight.Bold,
        )
        drawCentred(textMeasurer, "N", cardinalStyle.copy(color = CompassNorth), 100f, 34f, unit)
        drawCentred(textMeasurer, "E", cardinalStyle, 166f, 100f, unit)
        drawCentred(textMeasurer, "S", cardinalStyle, 100f, 166f, unit)
        drawCentred(textMeasurer, "W", cardinalStyle, 34f, 100f, unit)

        // Rides the card at its own bearing, so on screen it lands
        // (course - heading) from the top: the drift angle.
        if (courseDeg != null) {
            rotate(courseDeg, centre) {
                drawPath(
                    path = arrowHead(unit, 100f to 28f, 91f to 6f, 109f to 6f),
                    color = CompassTrack,
                )
            }
        }
    }

    // Fixed: the card turns beneath it, so it always marks the nose.
    drawPath(
        path = arrowHead(unit, 100f to 4f, 94f to 22f, 106f to 22f),
        color = Color.White,
    )

    // Course is blank rather than zero when the aircraft is too slow for the
    // GPS to have a direction -- a false 0 would read as due north.
    drawCentred(
        measurer = textMeasurer,
        text = courseDeg?.let { "${it.roundToInt() % 360}°" } ?: "---",
        style = TextStyle(
            color = CompassCourse,
            fontSize = (13f * unit).toSp(),
            fontWeight = FontWeight.SemiBold,
        ),
        x = 100f,
        y = 79f,
        unit = unit,
    )
    drawCentred(
        measurer = textMeasurer,
        text = "${heading.roundToInt() % 360}°",
        style = TextStyle(
            color = Color.White,
            fontSize = (25f * unit).toSp(),
            fontWeight = FontWeight.Bold,
        ),
        x = 100f,
        y = 103f,
        unit = unit,
    )
    // km/h, matching the wind readout in the telemetry grid.
    drawCentred(
        measurer = textMeasurer,
        text = windSpeedMs?.let { "%.1f km/h".format(Locale.ROOT, it * 3.6f) } ?: "--",
        style = TextStyle(
            color = CompassWindText,
            fontSize = (13f * unit).toSp(),
            fontWeight = FontWeight.SemiBold,
        ),
        x = 100f,
        y = 134f,
        unit = unit,
    )
}

private fun arrowHead(unit: Float, vararg points: Pair<Float, Float>): Path = Path().apply {
    points.forEachIndexed { index, (x, y) ->
        if (index == 0) moveTo(x * unit, y * unit) else lineTo(x * unit, y * unit)
    }
    close()
}

/** Draws [text] centred on the SVG point, matching text-anchor/baseline middle. */
private fun DrawScope.drawCentred(
    measurer: TextMeasurer,
    text: String,
    style: TextStyle,
    x: Float,
    y: Float,
    unit: Float,
) {
    val laid = measurer.measure(AnnotatedString(text), style = style)
    drawText(
        textLayoutResult = laid,
        topLeft = Offset(
            x * unit - laid.size.width / 2f,
            y * unit - laid.size.height / 2f,
        ),
    )
}
