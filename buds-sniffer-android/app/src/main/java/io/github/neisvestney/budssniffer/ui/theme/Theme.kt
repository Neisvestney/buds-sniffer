package io.github.neisvestney.budssniffer.ui.theme

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
    primary = FoxPrimaryDark,
    onPrimary = FoxOnPrimaryDark,
    primaryContainer = FoxPrimaryContainerDark,
    onPrimaryContainer = FoxOnPrimaryContainerDark,
    secondary = FoxSecondaryDark,
    tertiary = FoxTertiaryDark
)

private val LightColorScheme = lightColorScheme(
    primary = FoxPrimaryLight,
    onPrimary = FoxOnPrimaryLight,
    primaryContainer = FoxPrimaryContainerLight,
    onPrimaryContainer = FoxOnPrimaryContainerLight,
    secondary = FoxSecondaryLight,
    tertiary = FoxTertiaryLight
)

@Composable
fun BudsSnifferTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Off by default so the fox accent wins over wallpaper colors.
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
