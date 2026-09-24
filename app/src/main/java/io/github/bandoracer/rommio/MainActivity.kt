package io.github.bandoracer.rommio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.bandoracer.rommio.model.AppearancePreferences
import io.github.bandoracer.rommio.ui.RommNativeApp
import io.github.bandoracer.rommio.ui.theme.RommioTheme
import kotlinx.coroutines.runBlocking

@OptIn(ExperimentalComposeUiApi::class)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val store = (application as RommNativeApplication).container.appearancePreferencesStore
        // Read the small appearance file once up front so the first frame is already themed.
        val initial = runCatching { runBlocking { store.snapshot() } }.getOrDefault(AppearancePreferences())
        setContent {
            val appearance by store.preferencesFlow.collectAsStateWithLifecycle(initialValue = initial)
            RommioTheme(preferences = appearance) {
                // Expose Compose test tags (shell.tab.*, player.*) to UI Automator as resource ids.
                Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                    RommNativeApp()
                }
            }
        }
    }
}
