package io.github.mattsays.rommnative.data.input

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStoreFile
import io.github.mattsays.rommnative.domain.input.PlayerControlsPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class PlayerControlsPreferencesStore(context: Context) {
    private val dataStore = PreferenceDataStoreFactory.create(
        produceFile = { context.preferencesDataStoreFile("player_controls.preferences_pb") },
    )

    val preferencesFlow: Flow<PlayerControlsPreferences> = dataStore.data.map { prefs ->
        PlayerControlsPreferences(
            touchControlsEnabled = prefs[TOUCH_CONTROLS_ENABLED] ?: true,
            autoHideTouchOnController = prefs[AUTO_HIDE_TOUCH_ON_CONTROLLER] ?: true,
            rumbleToDeviceEnabled = prefs[RUMBLE_TO_DEVICE_ENABLED] ?: true,
        )
    }

    suspend fun setTouchControlsEnabled(enabled: Boolean) {
        dataStore.edit { it[TOUCH_CONTROLS_ENABLED] = enabled }
    }

    suspend fun setAutoHideTouchOnController(enabled: Boolean) {
        dataStore.edit { it[AUTO_HIDE_TOUCH_ON_CONTROLLER] = enabled }
    }

    suspend fun setRumbleToDeviceEnabled(enabled: Boolean) {
        dataStore.edit { it[RUMBLE_TO_DEVICE_ENABLED] = enabled }
    }

    companion object {
        private val TOUCH_CONTROLS_ENABLED = booleanPreferencesKey("touch_controls_enabled")
        private val AUTO_HIDE_TOUCH_ON_CONTROLLER = booleanPreferencesKey("auto_hide_touch_on_controller")
        private val RUMBLE_TO_DEVICE_ENABLED = booleanPreferencesKey("rumble_to_device_enabled")
    }
}
