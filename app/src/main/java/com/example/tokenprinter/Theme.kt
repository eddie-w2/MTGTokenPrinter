package com.example.tokenprinter

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val HeartRed = Color(0xFFE5484D)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFE9B949),
    onPrimary = Color(0xFF3A2A00),
    primaryContainer = Color(0xFF4F3B07),
    onPrimaryContainer = Color(0xFFFFE08A),
    secondary = Color(0xFFB9A9FF),
    onSecondary = Color(0xFF2A1B6B),
    secondaryContainer = Color(0xFF3F3578),
    onSecondaryContainer = Color(0xFFE5DEFF),
    tertiary = Color(0xFF7ED3B2),
    onTertiary = Color(0xFF00382A),
    background = Color(0xFF121019),
    onBackground = Color(0xFFE8E3F0),
    surface = Color(0xFF121019),
    onSurface = Color(0xFFE8E3F0),
    surfaceVariant = Color(0xFF2B2837),
    onSurfaceVariant = Color(0xFFCBC5D8),
    surfaceContainerLowest = Color(0xFF0D0B13),
    surfaceContainerLow = Color(0xFF171520),
    surfaceContainer = Color(0xFF1C1A26),
    surfaceContainerHigh = Color(0xFF262330),
    surfaceContainerHighest = Color(0xFF302D3B),
    outline = Color(0xFF948FA3),
    outlineVariant = Color(0xFF47434F),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF5C1A15),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF7A5900),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDEA0),
    onPrimaryContainer = Color(0xFF261900),
    secondary = Color(0xFF5B4EB8),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE5DEFF),
    onSecondaryContainer = Color(0xFF16074F),
    tertiary = Color(0xFF006B54),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFFCF8FF),
    onBackground = Color(0xFF1C1A22),
    surface = Color(0xFFFCF8FF),
    onSurface = Color(0xFF1C1A22),
    surfaceVariant = Color(0xFFE8E0F0),
    onSurfaceVariant = Color(0xFF49454F),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF6F1FA),
    surfaceContainer = Color(0xFFF1ECF6),
    surfaceContainerHigh = Color(0xFFEBE6F0),
    surfaceContainerHighest = Color(0xFFE5E0EA),
    outline = Color(0xFF7A7586),
    outlineVariant = Color(0xFFCBC4D2),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme
    MaterialTheme(colorScheme = scheme, shapes = AppShapes) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, content = content)
    }
}
