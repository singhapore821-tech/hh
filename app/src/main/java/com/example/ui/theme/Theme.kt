package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val VibeColorScheme = darkColorScheme(
    primary = VibePink,
    onPrimary = Color.White,
    primaryContainer = VibeRed,
    onPrimaryContainer = Color.White,
    secondary = VibeAccentCyan,
    onSecondary = Color.Black,
    background = VibePurpleDark,
    onBackground = VibeOnDark,
    surface = VibeSurface,
    onSurface = VibeOnDark,
    surfaceVariant = VibeSurfaceVariant,
    onSurfaceVariant = VibeTextSecondary,
    error = Color(0xFFFF5252),
    onError = Color.White
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true, // VIBE TV is built as a dark neon experience
    dynamicColor: Boolean = false, // Keep consistent branding
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = VibeColorScheme,
        typography = Typography,
        content = content
    )
}
