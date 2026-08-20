package com.codex.quota.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFFF5F5F7),
    onPrimary = Color(0xFF0D0D0F),
    background = Color(0xFF09090B),
    onBackground = Color(0xFFF5F5F7),
    surface = Color(0xFF18181B),
    onSurface = Color(0xFFF5F5F7),
    surfaceVariant = Color(0xFF202024),
    onSurfaceVariant = Color(0xFF8E8E93),
    outline = Color(0xFF2C2C2E),
    error = Color(0xFFFF453A)
)

@Composable
fun QuotaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
