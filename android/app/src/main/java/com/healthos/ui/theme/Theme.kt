package com.healthos.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// ── Health-Specific Extended Colors ──────────────────────────────────────────

@Immutable
data class HealthColors(
    val heartRate: Color,
    val heartRateContainer: Color,
    val steps: Color,
    val stepsContainer: Color,
    val calories: Color,
    val caloriesContainer: Color,
    val sleep: Color,
    val sleepContainer: Color,
    val aiInsight: Color,
    val aiInsightContainer: Color,
    val severityUrgent: Color,
    val severityImportant: Color,
    val severityAttention: Color,
    val severityInfo: Color,
    val glassSurface: Color,
    val glassBorder: Color,
    val chartLine: Color,
    val chartFill: Color,
    val chartBaseline: Color,
    val successGreen: Color
)

val DarkHealthColors = HealthColors(
    heartRate = Color(0xFFFF6B6B),
    heartRateContainer = Color(0xFF3D1F1F),
    steps = Color(0xFF51CF66),
    stepsContainer = Color(0xFF1F3D24),
    calories = Color(0xFFFF922B),
    caloriesContainer = Color(0xFF3D2A12),
    sleep = Color(0xFF845EF7),
    sleepContainer = Color(0xFF2A1F3D),
    aiInsight = Color(0xFFC084FC),
    aiInsightContainer = Color(0xFF2D1B4E),
    severityUrgent = Color(0xFFFF4444),
    severityImportant = Color(0xFFFFA726),
    severityAttention = Color(0xFF42A5F5),
    severityInfo = Color(0xFF90A4AE),
    glassSurface = Color(0x1AFFFFFF),
    glassBorder = Color(0x33FFFFFF),
    chartLine = Color(0xFF63B3ED),
    chartFill = Color(0x3363B3ED),
    chartBaseline = Color(0x80FFD54F),
    successGreen = Color(0xFF00E676)
)

val LightHealthColors = HealthColors(
    heartRate = Color(0xFFE53E3E),
    heartRateContainer = Color(0xFFFFF5F5),
    steps = Color(0xFF38A169),
    stepsContainer = Color(0xFFF0FFF4),
    calories = Color(0xFFDD6B20),
    caloriesContainer = Color(0xFFFFFAF0),
    sleep = Color(0xFF6B46C1),
    sleepContainer = Color(0xFFF5F0FF),
    aiInsight = Color(0xFF9F7AEA),
    aiInsightContainer = Color(0xFFF3EEFF),
    severityUrgent = Color(0xFFD32F2F),
    severityImportant = Color(0xFFF57C00),
    severityAttention = Color(0xFF1976D2),
    severityInfo = Color(0xFF757575),
    glassSurface = Color(0x0D000000),
    glassBorder = Color(0x1A000000),
    chartLine = Color(0xFF3182CE),
    chartFill = Color(0x333182CE),
    chartBaseline = Color(0x80F9A825),
    successGreen = Color(0xFF2E7D32)
)

val LocalHealthColors = staticCompositionLocalOf { LightHealthColors }

// ── Material3 Color Schemes ─────────────────────────────────────────────────

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF63B3ED),
    onPrimary = Color(0xFF0A1929),
    primaryContainer = Color(0xFF1A3A5C),
    onPrimaryContainer = Color(0xFFD6EBFF),
    secondary = Color(0xFF4FD1C5),
    onSecondary = Color(0xFF0A2925),
    secondaryContainer = Color(0xFF1A4040),
    onSecondaryContainer = Color(0xFFD6F5F0),
    tertiary = Color(0xFF90CDF4),
    background = Color(0xFF0F1419),
    surface = Color(0xFF1A2332),
    surfaceVariant = Color(0xFF222E3D),
    onBackground = Color(0xFFEDF2F7),
    onSurface = Color(0xFFEDF2F7),
    onSurfaceVariant = Color(0xFFA0AEC0),
    outline = Color(0xFF4A5568),
    outlineVariant = Color(0xFF2D3748),
    error = Color(0xFFFF6B6B),
    errorContainer = Color(0xFF3D1F1F)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF2B6CB0),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE8F4FD),
    onPrimaryContainer = Color(0xFF1A365D),
    secondary = Color(0xFF2C7A7B),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE6FFFA),
    onSecondaryContainer = Color(0xFF1D4044),
    tertiary = Color(0xFF2B6CB0),
    background = Color(0xFFF7FAFC),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF0F4F8),
    onBackground = Color(0xFF1A202C),
    onSurface = Color(0xFF1A202C),
    onSurfaceVariant = Color(0xFF718096),
    outline = Color(0xFFCBD5E0),
    outlineVariant = Color(0xFFE2E8F0),
    error = Color(0xFFE53E3E),
    errorContainer = Color(0xFFFFF5F5)
)

// ── Theme Entry Point ───────────────────────────────────────────────────────

@Composable
fun PersonalHealthOSTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val healthColors = if (darkTheme) DarkHealthColors else LightHealthColors

    CompositionLocalProvider(LocalHealthColors provides healthColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content
        )
    }
}
