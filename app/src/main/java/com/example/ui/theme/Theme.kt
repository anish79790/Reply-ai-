package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = InstaPink,
    onPrimary = Color.White,
    primaryContainer = InstaPurple,
    onPrimaryContainer = Color.White,
    secondary = InstaOrange,
    onSecondary = Color.White,
    secondaryContainer = DarkSurfaceVariant,
    onSecondaryContainer = TextPrimary,
    tertiary = InstaYellow,
    background = DarkBackground,
    onBackground = TextPrimary,
    surface = DarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    outline = TextTertiary,
    outlineVariant = Color(0xFF2E2E38)
)

private val LightColorScheme = lightColorScheme(
    primary = InstaPink,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD9E4),
    onPrimaryContainer = Color(0xFF3F001D),
    secondary = InstaPurple,
    onSecondary = Color.White,
    tertiary = InstaOrange,
    background = Color(0xFFFAF8FC),
    onBackground = Color(0xFF1D1B20),
    surface = Color.White,
    onSurface = Color(0xFF1D1B20),
    surfaceVariant = Color(0xFFF2ECF4),
    onSurfaceVariant = Color(0xFF49454E)
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else DarkColorScheme // Sleek dark default for Instagram aesthetic

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

