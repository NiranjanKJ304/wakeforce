package com.wakeforce.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Accent = Color(0xFFFF9F43)

/** Always-dark, pure-black palette: AMOLED pixels stay off for most of the screen. */
private val WakeForceColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF3A2410),
    onPrimaryContainer = Color(0xFFFFDCC0),
    secondary = Color(0xFFFFC38A),
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF262019),
    onSecondaryContainer = Color(0xFFF5DDC8),
    background = Color.Black,
    onBackground = Color(0xFFF2F2F2),
    surface = Color.Black,
    onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Color(0xFF1C1C1C),
    onSurfaceVariant = Color(0xFFA6A6A6),
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0B0B0B),
    surfaceContainer = Color(0xFF111111),
    surfaceContainerHigh = Color(0xFF181818),
    surfaceContainerHighest = Color(0xFF202020),
    outline = Color(0xFF3D3D3D),
    outlineVariant = Color(0xFF2A2A2A),
    error = Color(0xFFFF6B6B),
    onError = Color.Black,
)

@Composable
fun WakeForceTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = WakeForceColors, content = content)
}
