package com.codex.quota.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFFF5F5F7),
    onPrimary = Color(0xFF0D0D0F),
    background = Color(0xFF0D0D0F),
    onBackground = Color(0xFFF5F5F7),
    surface = Color(0xFF16161A),
    onSurface = Color(0xFFF5F5F7)
)

@Composable
fun QuotaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
