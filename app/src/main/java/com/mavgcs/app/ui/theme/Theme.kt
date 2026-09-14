package com.mavgcs.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Neutral dark greys, matching MavGCS desktop. The previous palette was tinted
 * blue, which read as a different application beside it.
 */
private val GcsColors = darkColorScheme(
    primary = Color(0xFF3DDC97),
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
