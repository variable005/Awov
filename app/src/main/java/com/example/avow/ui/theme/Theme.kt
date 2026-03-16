package com.example.avow.ui.theme

import android.app.Activity
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
    primary = CyanPrimary,
    onPrimary = Color.Black,
    secondary = CyanSecondary,
    onSecondary = Color.Black,
    tertiary = NeonGreen,
    onTertiary = Color.Black,
    error = NeonRed,
    onError = Color.White,
    background = DeepSlate,
    onBackground = Color.White,
    surface = SurfaceSlate,
    onSurface = Color.White,
    surfaceVariant = SurfaceVariantSlate,
    onSurfaceVariant = Color.White.copy(alpha = 0.7f),
    outline = CyanPrimary.copy(alpha = 0.5f)
)

private val LightColorScheme = lightColorScheme(
    primary = CyanSecondary,
    onPrimary = Color.White,
    secondary = CyanPrimary,
    onSecondary = Color.White,
    tertiary = NeonGreen,
    onTertiary = Color.Black,
    error = NeonRed,
    onError = Color.White,
    background = Color.White,
    onBackground = Color.Black,
    surface = Color(0xFFF5F5F5),
    onSurface = Color.Black,
    surfaceVariant = Color(0xFFE0E0E0),
    onSurfaceVariant = Color.DarkGray,
    outline = CyanSecondary.copy(alpha = 0.5f)
)

@Composable
fun AvowTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Set dynamicColor to false to prioritize our custom "Cyber-Tech" Material UI theme
    dynamicColor: Boolean = false,
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