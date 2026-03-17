package com.vdown.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF14532D),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF065F46),
    tertiary = Color(0xFF0F766E),
    background = Color(0xFFF7FEE7),
    surface = Color(0xFFFFFFFF)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF86EFAC),
    onPrimary = Color(0xFF052E16),
    secondary = Color(0xFF6EE7B7),
    tertiary = Color(0xFF5EEAD4)
)

@Composable
fun VDownloadTheme(content: @Composable () -> Unit) {
    val colorScheme = if (androidx.compose.foundation.isSystemInDarkTheme()) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
