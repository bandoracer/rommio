package io.github.bandoracer.rommio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import io.github.bandoracer.rommio.ui.RommNativeApp
import io.github.bandoracer.rommio.ui.theme.RommNativeTheme

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
