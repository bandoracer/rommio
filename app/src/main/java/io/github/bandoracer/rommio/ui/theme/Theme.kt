package io.github.bandoracer.rommio.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

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
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                window.isStatusBarContrastEnforced = false
                window.isNavigationBarContrastEnforced = false
            }
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        content = content,
    )
}
