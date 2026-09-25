package io.github.bandoracer.rommio.data.input

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.github.bandoracer.rommio.domain.input.PlayerControlsPreferences
import io.github.bandoracer.rommio.domain.input.PlayerStickTuning
import io.github.bandoracer.rommio.model.MigrationControlsPreferencesExport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * Touch-control haptics (docs/player-controls.md): a device-local preference stored with the
 * controls preferences, on by default (existing installs included), kept by the migration bundle's
 * snapshot and restore. Mirrors iOS `PlayerHapticsPreferenceTests`.
 */
class PlayerHapticsPreferenceTest {
    private fun isolatedContext(): Context {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val root = File(base.cacheDir, "haptics-${UUID.randomUUID()}").apply { mkdirs() }
        return object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        }
    }

    @Test fun hapticsAreOnByDefaultAndPersist() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PlayerControlsPreferencesStore(isolatedContext(), scope)
            assertTrue("On by default", store.preferencesFlow.first().hapticsEnabled)
            store.setHapticsEnabled(false)
            store.setConsoleColorsEnabled(false)
            val saved = store.preferencesFlow.first()
            assertFalse(saved.hapticsEnabled)
            assertFalse(saved.consoleColorsEnabled)
            // The migration bundle's snapshot and restore carry it with the other preferences.
            val snapshot = store.snapshot()
            assertFalse(snapshot.hapticsEnabled)
            store.setHapticsEnabled(true)
            store.restore(snapshot)
            assertFalse(store.preferencesFlow.first().hapticsEnabled)
            store.setHapticsEnabled(true)
            assertTrue(store.preferencesFlow.first().hapticsEnabled)
        } finally {
            scope.cancel()
        }
    }

    /** PlayStation's Analog controls and the Stick group (docs/player-controls.md): per game, device-local. */
    @Test fun analogControlsAndStickTuningArePerGame() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PlayerControlsPreferencesStore(isolatedContext(), scope)
            assertFalse("Analog controls off by default", store.analogControlsFlow(7).first())
            store.setAnalogControls(7, true)
            assertTrue(store.analogControlsFlow(7).first())
            assertFalse("Only that game", store.analogControlsFlow(8).first())
            store.setAnalogControls(7, false)
            assertFalse(store.analogControlsFlow(7).first())

            assertEquals("The console profile until set", PlayerStickTuning(), store.stickTuningFlow(7).first())
            val tuned = PlayerStickTuning(PlayerStickTuning.Sensitivity.HIGH, PlayerStickTuning.DeadZone.LARGE, PlayerStickTuning.Centre.FIXED)
            store.setStickTuning(7, tuned)
            assertEquals(tuned, store.stickTuningFlow(7).first())
            assertEquals(PlayerStickTuning(), store.stickTuningFlow(8).first())
            // Reset: back to the profile.
            store.setStickTuning(7, PlayerStickTuning())
            assertEquals(PlayerStickTuning(), store.stickTuningFlow(7).first())
            // Anything unreadable is the default.
            assertEquals(PlayerStickTuning(), PlayerControlsPreferencesStore.decodeStickTuning("nonsense"))
            assertEquals(tuned, PlayerControlsPreferencesStore.decodeStickTuning(PlayerControlsPreferencesStore.encodeStickTuning(tuned)))
        } finally {
            scope.cancel()
        }
    }

    @Test fun preferencesExportedBeforeHapticsReadWithHapticsOn() {
        val adapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build().adapter(MigrationControlsPreferencesExport::class.java)
        val old = adapter.toJson(MigrationControlsPreferencesExport(PlayerControlsPreferences(autoHideTouchOnController = false)))
            .replace(Regex(""","?hapticsEnabled":(true|false)"""), "")
        assertFalse(old.contains("hapticsEnabled"))
        val decoded = requireNotNull(adapter.fromJson(old)).preferences
        assertTrue(decoded.hapticsEnabled)
        assertFalse(decoded.autoHideTouchOnController)
    }
}
