package io.github.bandoracer.rommio.data.input

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import io.github.bandoracer.rommio.domain.input.PlayerControlsPreferences
import io.github.bandoracer.rommio.domain.input.PlayerStickTuning
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class PlayerControlsPreferencesStore(
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO),
) {
    private val dataStore = PreferenceDataStoreFactory.create(
        scope = scope,
        produceFile = { context.preferencesDataStoreFile("player_controls.preferences_pb") },
    )

    val preferencesFlow: Flow<PlayerControlsPreferences> = dataStore.data.map { prefs ->
        PlayerControlsPreferences(
            touchControlsEnabled = prefs[TOUCH_CONTROLS_ENABLED] ?: true,
            autoHideTouchOnController = prefs[AUTO_HIDE_TOUCH_ON_CONTROLLER] ?: true,
            rumbleToDeviceEnabled = prefs[RUMBLE_TO_DEVICE_ENABLED] ?: true,
            oledBlackModeEnabled = prefs[OLED_BLACK_MODE_ENABLED] ?: false,
            consoleColorsEnabled = prefs[CONSOLE_COLORS_ENABLED] ?: true,
            hapticsEnabled = prefs[HAPTICS_ENABLED] ?: true,
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

    suspend fun setHapticsEnabled(enabled: Boolean) {
        dataStore.edit { it[HAPTICS_ENABLED] = enabled }
    }

    /**
     * PlayStation's per-game Analog controls (docs/player-controls.md, per console): off unless set
     * for [romId]. Device-local, like every controls preference; never part of a synced checkpoint.
     */
    fun analogControlsFlow(romId: Int): Flow<Boolean> = dataStore.data.map { prefs ->
        romId.toString() in (prefs[ANALOG_CONTROLS_ROMS] ?: emptySet())
    }

    suspend fun setAnalogControls(romId: Int, enabled: Boolean) {
        dataStore.edit { prefs ->
            val current = prefs[ANALOG_CONTROLS_ROMS] ?: emptySet()
            prefs[ANALOG_CONTROLS_ROMS] = if (enabled) current + romId.toString() else current - romId.toString()
        }
    }

    /**
     * A game's touch-stick tuning (docs/player-controls.md, "Per-game tuning"): device-local, per
     * game; the console profile as it is until set.
     */
    fun stickTuningFlow(romId: Int): Flow<PlayerStickTuning> = dataStore.data.map { prefs ->
        decodeStickTuning(prefs[stickTuningKey(romId)])
    }

    suspend fun setStickTuning(romId: Int, tuning: PlayerStickTuning) {
        dataStore.edit { prefs ->
            if (tuning.isDefault) prefs.remove(stickTuningKey(romId)) else prefs[stickTuningKey(romId)] = encodeStickTuning(tuning)
        }
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
            it[HAPTICS_ENABLED] = preferences.hapticsEnabled
        }
    }

    private fun Preferences.toModel(): PlayerControlsPreferences {
        return PlayerControlsPreferences(
            touchControlsEnabled = this[TOUCH_CONTROLS_ENABLED] ?: true,
            autoHideTouchOnController = this[AUTO_HIDE_TOUCH_ON_CONTROLLER] ?: true,
            rumbleToDeviceEnabled = this[RUMBLE_TO_DEVICE_ENABLED] ?: true,
            oledBlackModeEnabled = this[OLED_BLACK_MODE_ENABLED] ?: false,
            consoleColorsEnabled = this[CONSOLE_COLORS_ENABLED] ?: true,
            hapticsEnabled = this[HAPTICS_ENABLED] ?: true,
        )
    }

    companion object {
        private val TOUCH_CONTROLS_ENABLED = booleanPreferencesKey("touch_controls_enabled")
        private val AUTO_HIDE_TOUCH_ON_CONTROLLER = booleanPreferencesKey("auto_hide_touch_on_controller")
        private val RUMBLE_TO_DEVICE_ENABLED = booleanPreferencesKey("rumble_to_device_enabled")
        private val OLED_BLACK_MODE_ENABLED = booleanPreferencesKey("oled_black_mode_enabled")
        private val CONSOLE_COLORS_ENABLED = booleanPreferencesKey("console_colors_enabled")
        private val HAPTICS_ENABLED = booleanPreferencesKey("haptics_enabled")
        /** ROM ids with Analog controls on. */
        private val ANALOG_CONTROLS_ROMS = stringSetPreferencesKey("analog_controls_rom_ids")

        private fun stickTuningKey(romId: Int) = stringPreferencesKey("stick_tuning_$romId")

        /** "SENSITIVITY,DEAD_ZONE,CENTRE" (enum names); anything unreadable is the default. */
        internal fun encodeStickTuning(tuning: PlayerStickTuning): String =
            listOf(tuning.sensitivity.name, tuning.deadZone.name, tuning.centre.name).joinToString(",")

        internal fun decodeStickTuning(value: String?): PlayerStickTuning {
            val parts = value?.split(",") ?: return PlayerStickTuning()
            return PlayerStickTuning(
                sensitivity = PlayerStickTuning.Sensitivity.entries.firstOrNull { it.name == parts.getOrNull(0) } ?: PlayerStickTuning.Sensitivity.NORMAL,
                deadZone = PlayerStickTuning.DeadZone.entries.firstOrNull { it.name == parts.getOrNull(1) } ?: PlayerStickTuning.DeadZone.NORMAL,
                centre = PlayerStickTuning.Centre.entries.firstOrNull { it.name == parts.getOrNull(2) } ?: PlayerStickTuning.Centre.FLOATING,
            )
        }
    }
}
