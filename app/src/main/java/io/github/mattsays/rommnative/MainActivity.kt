package io.github.mattsays.rommnative

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import io.github.mattsays.rommnative.ui.RommNativeApp
import io.github.mattsays.rommnative.ui.theme.RommNativeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            RommNativeTheme {
                RommNativeApp()
            }
        }
    }
}
