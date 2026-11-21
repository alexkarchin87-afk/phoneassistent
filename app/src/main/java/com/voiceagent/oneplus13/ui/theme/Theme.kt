package com.voiceagent.oneplus13.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

private val DarkScheme = darkColorScheme(
    primary = SparkWhite,
    onPrimary = DarkBg,
    primaryContainer = DarkSurfaceHighest,
    onPrimaryContainer = DarkOnSurface,
    secondary = DarkOnSurfaceVariant,
    onSecondary = DarkBg,
    secondaryContainer = DarkSurfaceHigh,
    onSecondaryContainer = DarkOnSurface,
    tertiary = DarkOnSurfaceVariant,
    onTertiary = DarkBg,
    background = DarkBg,
    onBackground = DarkOnSurface,
    surface = DarkBg,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceHigh,
    onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceContainerLowest = DarkSurfaceLowest,
    surfaceContainerLow = DarkSurfaceLow,
    surfaceContainer = DarkSurface,
    surfaceContainerHigh = DarkSurfaceHigh,
    surfaceContainerHighest = DarkSurfaceHighest,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    error = Color(0xFFFF8A80),
    onError = Color(0xFF1A0000),
    scrim = SparkBlack
)

private val LightScheme = lightColorScheme(
    primary = LightOnSurface,
    onPrimary = SparkWhite,
    primaryContainer = LightSurfaceHighest,
    onPrimaryContainer = LightOnSurface,
    secondary = LightOnSurfaceVariant,
    onSecondary = SparkWhite,
    secondaryContainer = LightSurfaceHigh,
    onSecondaryContainer = LightOnSurface,
    tertiary = LightOnSurfaceVariant,
    onTertiary = SparkWhite,
    background = LightBg,
    onBackground = LightOnSurface,
    surface = LightBg,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceHigh,
    onSurfaceVariant = LightOnSurfaceVariant,
    surfaceContainerLowest = LightSurfaceLowest,
    surfaceContainerLow = LightSurfaceLow,
    surfaceContainer = LightSurface,
    surfaceContainerHigh = LightSurfaceHigh,
    surfaceContainerHighest = LightSurfaceHighest,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    error = Color(0xFFB3261E),
    onError = SparkWhite,
    scrim = SparkBlack
)

private val SparkShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

/** Монохромная тема (Dark/Light по системной настройке). Сигнатура прежняя. */
@Composable
fun VoiceAgentTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = SparkTypography,
        shapes = SparkShapes,
        content = content
    )
}

@Composable
@ReadOnlyComposable
fun isDarkScheme(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f

/** Мягкий ч/б-градиент для заголовков и значков (onSurface → приглушённый onSurface). */
@Composable
fun monoGradient(): Brush {
    val c = MaterialTheme.colorScheme.onSurface
    return Brush.linearGradient(listOf(c, c.copy(alpha = 0.42f)))
}
