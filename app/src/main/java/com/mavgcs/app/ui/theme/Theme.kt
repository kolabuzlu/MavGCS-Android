package com.mavgcs.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The one green the interface is painted in.
 *
 * It had been written out in three slightly different mixes: a minty 3DDC97 on
 * the buttons and throttle, 6EE787 on the compass, and this flatter 5CCF5C on
 * the subsystem chips. Near-misses like that read as a rendering fault rather
 * than a palette, so the chips' green is now the only one.
 *
 * The terrain radar is deliberately not on it. Its green is the far end of a
 * red-orange-yellow-green ramp encoding clearance, chosen to sit with the
 * other three stops; that is data being coloured, not interface, and it
 * answers to the ramp rather than to this.
 */
val MavGreen = Color(0xFF5CCF5C)

/**
 * Neutral dark greys, matching MavGCS desktop. The previous palette was tinted
 * blue, which read as a different application beside it.
 */
private val GcsColors = darkColorScheme(
    primary = MavGreen,
    onPrimary = Color(0xFF04210F),
    secondary = Color(0xFF4FC3F7),
    onSecondary = Color(0xFF04121C),
    background = Color(0xFF1E1E1E),
    onBackground = Color(0xFFE6E6E6),
    surface = Color(0xFF252526),
    onSurface = Color(0xFFE6E6E6),
    surfaceVariant = Color(0xFF2E2E30),
    onSurfaceVariant = Color(0xFFB0B0B4),
    error = Color(0xFFC0392B),
    outline = Color(0xFF454549),
)

@Composable
fun MavGcsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = GcsColors,
        content = content,
    )
}
