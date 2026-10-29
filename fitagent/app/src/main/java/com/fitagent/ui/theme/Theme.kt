package com.fitagent.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fitagent.domain.Band
import com.fitagent.domain.WorkoutType

/*
 * Design language: near-black surfaces, one energetic accent (lime), one semantic colour per metric.
 * Inspired by WHOOP's single-score hierarchy and Apple Fitness rings; see docs/IMPLEMENTATION_PLAN.md §2.
 */

@Immutable
data class FitColors(
    val steps: Color,
    val move: Color,
    val workout: Color,
    val sleep: Color,
    val heart: Color,
    val push: Color,
    val steady: Color,
    val recover: Color,
    val track: Color,
    val cardHigh: Color,
    val muted: Color,
) {
    fun band(b: Band): Color = when (b) {
        Band.PUSH -> push
        Band.STEADY -> steady
        Band.RECOVER -> recover
    }
}

private val DarkFit = FitColors(
    steps = Color(0xFFC6F432),
    move = Color(0xFFFF6B4A),
    workout = Color(0xFF3DD9EB),
    sleep = Color(0xFF9B8CFF),
    heart = Color(0xFFFF5C8A),
    push = Color(0xFF2EE59D),
    steady = Color(0xFFFFC93C),
    recover = Color(0xFFFF5C5C),
    track = Color(0x1FFFFFFF),
    cardHigh = Color(0xFF1B222C),
    muted = Color(0xFF8A94A3),
)

private val LightFit = FitColors(
    steps = Color(0xFF5E9E00),
    move = Color(0xFFE5502F),
    workout = Color(0xFF0A9EB0),
    sleep = Color(0xFF6A58E0),
    heart = Color(0xFFD9336A),
    push = Color(0xFF0E9F6E),
    steady = Color(0xFFC98A00),
    recover = Color(0xFFD63B3B),
    track = Color(0x14000000),
    cardHigh = Color(0xFFEFF2EA),
    muted = Color(0xFF5E6672),
)

val LocalFitColors = staticCompositionLocalOf { DarkFit }

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFFC6F432),
    onPrimary = Color(0xFF0A0D12),
    primaryContainer = Color(0xFF2B3A0A),
    onPrimaryContainer = Color(0xFFE4FF9A),
    secondary = Color(0xFF3DD9EB),
    onSecondary = Color(0xFF00242A),
    secondaryContainer = Color(0xFF0E3A40),
    onSecondaryContainer = Color(0xFFB8F4FB),
    background = Color(0xFF0A0D12),
    onBackground = Color(0xFFEDF1F5),
    surface = Color(0xFF0A0D12),
    onSurface = Color(0xFFEDF1F5),
    surfaceVariant = Color(0xFF141A22),
    onSurfaceVariant = Color(0xFF9AA4B2),
    surfaceContainer = Color(0xFF141A22),
    surfaceContainerHigh = Color(0xFF1B222C),
    surfaceContainerHighest = Color(0xFF232B36),
    surfaceContainerLow = Color(0xFF10151B),
    outline = Color(0xFF2C3542),
    outlineVariant = Color(0xFF1F2731),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF2B0505),
)

private val LightScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF4C7A00),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7F58A),
    onPrimaryContainer = Color(0xFF142000),
    secondary = Color(0xFF00788A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC4F1F8),
    onSecondaryContainer = Color(0xFF002A31),
    background = Color(0xFFF6F8F3),
    onBackground = Color(0xFF14181D),
    surface = Color(0xFFF6F8F3),
    onSurface = Color(0xFF14181D),
    surfaceVariant = Color(0xFFFFFFFF),
    onSurfaceVariant = Color(0xFF55606C),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFEFF2EA),
    surfaceContainerHighest = Color(0xFFE6EAE1),
    surfaceContainerLow = Color(0xFFFAFBF8),
    outline = Color(0xFFCBD2C6),
    outlineVariant = Color(0xFFE2E7DD),
    error = Color(0xFFC62828),
    onError = Color.White,
)

private val Display = FontFamily.Default

private val AppTypography = Typography(
    displayLarge = TextStyle(fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 64.sp, lineHeight = 64.sp, letterSpacing = (-2).sp),
    displayMedium = TextStyle(fontFamily = Display, fontWeight = FontWeight.ExtraBold, fontSize = 44.sp, lineHeight = 48.sp, letterSpacing = (-1.2).sp),
    headlineLarge = TextStyle(fontWeight = FontWeight.ExtraBold, fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.6).sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.3).sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 24.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.8.sp),
)

private val AppShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(36.dp),
)

@Composable
fun FitAgentTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalFitColors provides if (dark) DarkFit else LightFit) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}

object Fit {
    val colors: FitColors
        @Composable get() = LocalFitColors.current
}

fun WorkoutType.color(c: FitColors): Color = when (this) {
    WorkoutType.WALK -> c.steps
    WorkoutType.RUN, WorkoutType.HIIT -> c.move
    WorkoutType.CYCLE, WorkoutType.SWIM -> c.workout
    WorkoutType.STRENGTH -> c.steady
    WorkoutType.YOGA -> c.sleep
    WorkoutType.SPORTS, WorkoutType.DANCE, WorkoutType.OTHER -> c.heart
}
