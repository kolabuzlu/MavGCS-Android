package com.mavgcs.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val GcsColors = darkColorScheme(
    primary = Color(0xFF3DDC97),
    onPrimary = Color(0xFF04210F),
    secondary = Color(0xFF7BD7FF),
    onSecondary = Color(0xFF04121C),
    background = Color(0xFF0B1220),
    onBackground = Color(0xFFE8EEF7),
    surface = Color(0xFF141C2B),
    onSurface = Color(0xFFE8EEF7),
    surfaceVariant = Color(0xFF1C2638),
    onSurfaceVariant = Color(0xFFB7C2D4),
    error = Color(0xFFFF6B6B),
    outline = Color(0xFF31415C),
)

@Composable
fun MavGcsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = GcsColors,
        content = content,
    )
}
