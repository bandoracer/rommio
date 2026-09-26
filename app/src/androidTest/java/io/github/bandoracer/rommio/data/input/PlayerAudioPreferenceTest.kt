package io.github.bandoracer.rommio.data.input

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.github.bandoracer.rommio.domain.input.PlayerControlsPreferences
import io.github.bandoracer.rommio.model.MigrationControlsPreferencesExport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * The pause menu's sound toggle: a device-local preference stored with the controls preferences,
 * sound on by default (existing installs included), kept by the migration bundle's snapshot and
 * restore. Mirrors iOS `PlayerAudioPreferenceTests`.
 */
class PlayerAudioPreferenceTest {
    private fun isolatedContext(): Context {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val root = File(base.cacheDir, "audio-${UUID.randomUUID()}").apply { mkdirs() }
        return object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        }
    }

    @Test fun soundIsOnByDefaultAndMutePersists() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PlayerControlsPreferencesStore(isolatedContext(), scope)
            assertFalse("Sound on by default", store.preferencesFlow.first().audioMuted)
            store.setAudioMuted(true)
            store.setHapticsEnabled(false)
            val saved = store.preferencesFlow.first()
            assertTrue(saved.audioMuted)
            assertFalse("Other preferences keep their own values", saved.hapticsEnabled)
            // The migration bundle's snapshot and restore carry it with the other preferences.
            val snapshot = store.snapshot()
            assertTrue(snapshot.audioMuted)
            store.setAudioMuted(false)
            store.restore(snapshot)
            assertTrue(store.preferencesFlow.first().audioMuted)
            store.setAudioMuted(false)
            assertFalse(store.preferencesFlow.first().audioMuted)
        } finally {
            scope.cancel()
        }
    }

    @Test fun preferencesExportedBeforeTheSoundToggleReadWithSoundOn() {
        val adapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build().adapter(MigrationControlsPreferencesExport::class.java)
        val old = adapter.toJson(MigrationControlsPreferencesExport(PlayerControlsPreferences(hapticsEnabled = false)))
            .replace(Regex(""","?audioMuted":(true|false)"""), "")
        assertFalse(old.contains("audioMuted"))
        val decoded = requireNotNull(adapter.fromJson(old)).preferences
        assertFalse(decoded.audioMuted)
        assertFalse(decoded.hapticsEnabled)
    }
}
