package com.healthos.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF63B3ED),
    secondary = Color(0xFF4FD1C5),
    tertiary = Color(0xFF90CDF4),
    background = Color(0xFF1A202C),
    surface = Color(0xFF2D3748),
    onPrimary = Color(0xFF1A202C),
    onSecondary = Color(0xFF1A202C),
    onBackground = Color(0xFFEDF2F7),
    onSurface = Color(0xFFEDF2F7)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF3182CE),
    secondary = Color(0xFF319795),
    tertiary = Color(0xFF2B6CB0),
    background = Color(0xFFF7FAFC),
    surface = Color(0xFFFFFFFF),
    onPrimary = Color(0xFFFFFFFF),
    onSecondary = Color(0xFFFFFFFF),
    onBackground = Color(0xFF1A202C),
    onSurface = Color(0xFF2D3748)
)

@Composable
fun PersonalHealthOSTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
