package com.jepongdevxyz.remote.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import com.jepongdevxyz.remote.core.Prefs

private val DarkScheme = darkColorScheme(
    primary = Neon,
    onPrimary = Color.White,
    secondary = NeonDark,
    background = Navy,
    onBackground = Color.White,
    surface = Card,
    onSurface = Color.White,
    surfaceVariant = NavyDeep,
    onSurfaceVariant = Muted,
    error = Decline,
    tertiary = Accept
)

private val LightScheme = lightColorScheme(
    primary = NeonDark,
    onPrimary = Color.White,
    secondary = Neon,
    background = Color(0xFFF6F9FC),
    onBackground = Color(0xFF0B1D33),
    surface = Color.White,
    onSurface = Color(0xFF0B1D33),
    surfaceVariant = CardLight,
    onSurfaceVariant = Color(0xFF5B6B7F),
    error = Decline,
    tertiary = Accept
)

@Composable
fun DevxyzRemoteTheme(content: @Composable () -> Unit) {
    val mode by Prefs.themeMode.collectAsState(initial = "SYSTEM")
    val dark = when (mode) {
        "LIGHT" -> false
        "DARK" -> true
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        typography = AppTypography,
        content = content
    )
}
