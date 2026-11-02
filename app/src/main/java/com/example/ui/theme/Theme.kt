package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = HfYellow,
    onPrimary = Color(0xFF1E1E1E),
    primaryContainer = Color(0xFF78350F),
    onPrimaryContainer = Color(0xFFFEF3C7),
    secondary = CyanAccent,
    onSecondary = Color(0xFF0F172A),
    secondaryContainer = Color(0xFF075985),
    onSecondaryContainer = Color(0xFFE0F2FE),
    tertiary = VioletAgent,
    onTertiary = Color.White,
    background = HfDarkBg,
    onBackground = SlateTextPrimary,
    surface = HfDarkSurface,
    onSurface = SlateTextPrimary,
    surfaceVariant = HfDarkCard,
    onSurfaceVariant = SlateTextSecondary,
    outline = HfDarkBorder
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFFB45309),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFEF3C7),
    onPrimaryContainer = Color(0xFF78350F),
    secondary = Color(0xFF0284C7),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0F2FE),
    onSecondaryContainer = Color(0xFF0369A1),
    tertiary = Color(0xFF6366F1),
    onTertiary = Color.White,
    background = HfLightBg,
    onBackground = HfLightTextPrimary,
    surface = HfLightSurface,
    onSurface = HfLightTextPrimary,
    surfaceVariant = HfLightCard,
    onSurfaceVariant = HfLightTextSecondary,
    outline = HfLightBorder
)

@Composable
fun SpaceAgentTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // Keep branded Hugging Face & Agent visual identity
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
