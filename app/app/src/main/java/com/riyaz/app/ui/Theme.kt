package com.riyaz.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Saffron = Color(0xFFF2A541)
private val Ink = Color(0xFF14161B)
private val Surface1 = Color(0xFF1D2027)
private val Surface2 = Color(0xFF262A33)

private val colors = darkColorScheme(
    primary = Saffron,
    onPrimary = Color(0xFF2A1A00),
    secondary = Color(0xFF8FB8DE),
    background = Ink,
    onBackground = Color(0xFFE8E6E1),
    surface = Surface1,
    onSurface = Color(0xFFE8E6E1),
    surfaceVariant = Surface2,
    onSurfaceVariant = Color(0xFFB3B0A9),
    surfaceContainer = Surface1,
    surfaceContainerHigh = Surface2,
    error = Color(0xFFFF8A80),
)

@Composable
fun RiyazTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}
