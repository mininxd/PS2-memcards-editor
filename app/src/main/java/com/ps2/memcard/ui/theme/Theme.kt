package com.ps2.memcard.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = Ps2AccentCyan,
    onPrimary = Ps2DarkBackground,
    primaryContainer = Ps2Primary,
    onPrimaryContainer = Ps2PrimaryContainer,
    secondary = Ps2AccentGreen,
    onSecondary = Ps2DarkBackground,
    secondaryContainer = Ps2Secondary,
    onSecondaryContainer = Ps2SecondaryContainer,
    tertiary = Ps2AccentAmber,
    onTertiary = Ps2DarkBackground,
    background = Ps2DarkBackground,
    onBackground = Ps2DarkOnSurface,
    surface = Ps2DarkSurface,
    onSurface = Ps2DarkOnSurface,
    surfaceVariant = Ps2DarkSurfaceVariant,
    onSurfaceVariant = Ps2DarkOnSurfaceVariant
)

private val LightColorScheme = lightColorScheme(
    primary = Ps2Primary,
    onPrimary = Ps2OnPrimary,
    primaryContainer = Ps2PrimaryContainer,
    onPrimaryContainer = Ps2OnPrimaryContainer,
    secondary = Ps2Secondary,
    onSecondary = Ps2OnSecondary,
    secondaryContainer = Ps2SecondaryContainer,
    onSecondaryContainer = Ps2OnSecondaryContainer,
    tertiary = Ps2Tertiary,
    onTertiary = Ps2OnTertiary,
    background = Ps2LightBackground,
    onBackground = Ps2LightOnSurface,
    surface = Ps2LightSurface,
    onSurface = Ps2LightOnSurface,
    surfaceVariant = Ps2LightSurfaceVariant,
    onSurfaceVariant = Ps2LightOnSurfaceVariant
)

@Composable
fun PS2MemcardTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+ (SDK 31+)
    dynamicColor: Boolean = true,
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
