package io.github.bandoracer.rommio.data.input

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStoreFile
import io.github.bandoracer.rommio.domain.input.PlayerControlsPreferences
import kotlinx.coroutines.flow.first
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
            oledBlackModeEnabled = prefs[OLED_BLACK_MODE_ENABLED] ?: false,
            consoleColorsEnabled = prefs[CONSOLE_COLORS_ENABLED] ?: false,
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

    suspend fun setOledBlackModeEnabled(enabled: Boolean) {
        dataStore.edit { it[OLED_BLACK_MODE_ENABLED] = enabled }
    }

    suspend fun setConsoleColorsEnabled(enabled: Boolean) {
        dataStore.edit { it[CONSOLE_COLORS_ENABLED] = enabled }
    }

    suspend fun snapshot(): PlayerControlsPreferences {
        return dataStore.data.first().toModel()
    }

    suspend fun restore(preferences: PlayerControlsPreferences) {
        dataStore.edit {
            it[TOUCH_CONTROLS_ENABLED] = preferences.touchControlsEnabled
            it[AUTO_HIDE_TOUCH_ON_CONTROLLER] = preferences.autoHideTouchOnController
            it[RUMBLE_TO_DEVICE_ENABLED] = preferences.rumbleToDeviceEnabled
            it[OLED_BLACK_MODE_ENABLED] = preferences.oledBlackModeEnabled
            it[CONSOLE_COLORS_ENABLED] = preferences.consoleColorsEnabled
        }
    }

    private fun Preferences.toModel(): PlayerControlsPreferences {
        return PlayerControlsPreferences(
            touchControlsEnabled = this[TOUCH_CONTROLS_ENABLED] ?: true,
            autoHideTouchOnController = this[AUTO_HIDE_TOUCH_ON_CONTROLLER] ?: true,
            rumbleToDeviceEnabled = this[RUMBLE_TO_DEVICE_ENABLED] ?: true,
            oledBlackModeEnabled = this[OLED_BLACK_MODE_ENABLED] ?: false,
            consoleColorsEnabled = this[CONSOLE_COLORS_ENABLED] ?: false,
        )
    }

    companion object {
        private val TOUCH_CONTROLS_ENABLED = booleanPreferencesKey("touch_controls_enabled")
        private val AUTO_HIDE_TOUCH_ON_CONTROLLER = booleanPreferencesKey("auto_hide_touch_on_controller")
        private val RUMBLE_TO_DEVICE_ENABLED = booleanPreferencesKey("rumble_to_device_enabled")
        private val OLED_BLACK_MODE_ENABLED = booleanPreferencesKey("oled_black_mode_enabled")
        private val CONSOLE_COLORS_ENABLED = booleanPreferencesKey("console_colors_enabled")
    }
}
