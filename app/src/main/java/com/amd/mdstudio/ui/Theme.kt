package com.amd.mdstudio.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightPalette = lightColorScheme(
    primary = Color(0xFF2563EB), onPrimary = Color.White,
    secondary = Color(0xFF16A34A), background = Color(0xFFF6F8FC),
    surface = Color.White, onSurface = Color(0xFF172033)
)

private val DarkPalette = darkColorScheme(
    primary = Color(0xFF8AB4FF), secondary = Color(0xFF75D69A),
    background = Color(0xFF101827), surface = Color(0xFF182334),
    onSurface = Color(0xFFE7EDF7)
)

@Composable fun MDTheme(themeMode: String = "system", content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(colorScheme = if (dark) DarkPalette else LightPalette, content = content)
}
