package io.github.mattsays.rommnative.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkScheme = darkColorScheme(
    primary = BrandSeed,
    onPrimary = BrandText,
    background = BrandCanvas,
    onBackground = BrandText,
    surface = BrandPanel,
    onSurface = BrandText,
    surfaceVariant = BrandPanelAlt,
    onSurfaceVariant = BrandMuted,
)

private val LightScheme = lightColorScheme(
    primary = BrandSeed,
    onPrimary = BrandText,
    background = BrandCanvas,
    onBackground = BrandText,
    surface = BrandPanel,
    onSurface = BrandText,
    surfaceVariant = BrandPanelAlt,
    onSurfaceVariant = BrandMuted,
)

@Composable
fun RommNativeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        content = content,
    )
}
