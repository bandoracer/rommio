package io.github.bandoracer.rommio.data.repository

import io.github.bandoracer.rommio.data.input.ControlsJsonCodec
import io.github.bandoracer.rommio.data.input.ExternalControllerMonitor
import io.github.bandoracer.rommio.data.input.PlayerControlsPreferencesStore
import io.github.bandoracer.rommio.data.local.HardwareBindingProfileDao
import io.github.bandoracer.rommio.data.local.HardwareBindingProfileEntity
import io.github.bandoracer.rommio.data.local.TouchLayoutProfileDao
import io.github.bandoracer.rommio.data.local.TouchLayoutProfileEntity
import io.github.bandoracer.rommio.domain.input.ActiveInputMode
import io.github.bandoracer.rommio.domain.input.ControlProfileResolver
import io.github.bandoracer.rommio.domain.input.HardwareBindingProfile
import io.github.bandoracer.rommio.domain.input.PlatformControlProfile
import io.github.bandoracer.rommio.domain.input.PlayerControlsPreferences
import io.github.bandoracer.rommio.domain.input.PlayerControlsState
import io.github.bandoracer.rommio.domain.input.TouchLayoutProfile
import io.github.bandoracer.rommio.domain.input.TouchSupportMode
import io.github.bandoracer.rommio.domain.input.defaultHardwareBinding
import io.github.bandoracer.rommio.domain.input.defaultTouchLayout
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class PlayerControlsRepository(
    private val resolver: ControlProfileResolver,
    private val touchLayoutDao: TouchLayoutProfileDao,
    private val hardwareBindingDao: HardwareBindingProfileDao,
    private val preferencesStore: PlayerControlsPreferencesStore,
    private val controllerMonitor: ExternalControllerMonitor,
    private val codec: ControlsJsonCodec,
) {
    fun resolveProfile(platformSlug: String): PlatformControlProfile = resolver.resolve(platformSlug)

    fun observePreferences(): Flow<PlayerControlsPreferences> = preferencesStore.preferencesFlow

    fun observeControls(platformSlug: String): Flow<PlayerControlsState> {
        val profile = resolver.resolve(platformSlug)
        return combine(
            observeTouchLayout(profile),
            observeHardwareBindings(profile),
            preferencesStore.preferencesFlow,
            controllerMonitor.observeControllers(),
        ) { touchLayout, hardwareBinding, preferences, controllers ->
            val inputMode = resolveInputMode(profile, preferences, controllers.isNotEmpty())
            PlayerControlsState(
                platformProfile = profile,
                touchLayout = touchLayout,
                hardwareBinding = hardwareBinding,
                preferences = preferences,
                connectedControllers = controllers,
                inputMode = inputMode,
                showTouchControls = shouldShowTouchControls(profile, preferences, controllers.isNotEmpty()),
            )
        }
    }

    suspend fun saveTouchLayout(profile: TouchLayoutProfile) {
        touchLayoutDao.upsert(
            TouchLayoutProfileEntity(
                platformFamilyId = profile.platformFamilyId,
                presetId = profile.presetId,
                layoutJson = codec.encodeTouchElementStates(profile.elementStates),
                opacity = profile.opacity,
                globalScale = profile.globalScale,
                leftHanded = profile.leftHanded,
                updatedAtEpochMs = profile.updatedAtEpochMs,
            ),
        )
    }

    suspend fun resetTouchLayout(platformSlug: String, presetId: String? = null) {
        val profile = resolver.resolve(platformSlug)
        val defaultLayout = profile.defaultTouchLayout() ?: run {
            touchLayoutDao.delete(profile.familyId)
            return
        }
        val layout = defaultLayout.copy(
            presetId = presetId ?: defaultLayout.presetId,
            updatedAtEpochMs = System.currentTimeMillis(),
        )
        saveTouchLayout(layout)
    }

    suspend fun saveHardwareBinding(profile: HardwareBindingProfile) {
        hardwareBindingDao.upsert(
            HardwareBindingProfileEntity(
                platformFamilyId = profile.platformFamilyId,
                controllerTypeId = profile.controllerTypeId,
                deadzone = profile.deadzone,
                bindingsJson = codec.encodeBindingMap(profile),
                updatedAtEpochMs = profile.updatedAtEpochMs,
            ),
        )
    }

    suspend fun setTouchControlsEnabled(enabled: Boolean) {
        preferencesStore.setTouchControlsEnabled(enabled)
    }

    suspend fun setAutoHideTouchOnController(enabled: Boolean) {
        preferencesStore.setAutoHideTouchOnController(enabled)
    }

    suspend fun setRumbleToDeviceEnabled(enabled: Boolean) {
        preferencesStore.setRumbleToDeviceEnabled(enabled)
    }

    suspend fun setOledBlackModeEnabled(enabled: Boolean) {
        preferencesStore.setOledBlackModeEnabled(enabled)
    }

    suspend fun setConsoleColorsEnabled(enabled: Boolean) {
        preferencesStore.setConsoleColorsEnabled(enabled)
    }

    private fun observeTouchLayout(profile: PlatformControlProfile): Flow<TouchLayoutProfile?> {
        return touchLayoutDao.observeByFamilyId(profile.familyId).map { entity ->
            when {
                profile.touchSupportMode == TouchSupportMode.CONTROLLER_FIRST -> null
                entity == null -> profile.defaultTouchLayout()
                else -> {
                    val defaultLayout = profile.defaultTouchLayout()
                    if (defaultLayout == null) {
                        null
                    } else {
                        defaultLayout.copy(
                            platformFamilyId = entity.platformFamilyId,
                            presetId = entity.presetId,
                            opacity = entity.opacity,
                            globalScale = entity.globalScale,
                            leftHanded = entity.leftHanded,
                            updatedAtEpochMs = entity.updatedAtEpochMs,
                        )
                    }
                }
            }
        }
    }

    private fun observeHardwareBindings(profile: PlatformControlProfile): Flow<HardwareBindingProfile> {
        return hardwareBindingDao.observeByFamilyId(profile.familyId).map { entity ->
            if (entity == null) {
                profile.defaultHardwareBinding()
            } else {
                HardwareBindingProfile(
                    platformFamilyId = entity.platformFamilyId,
                    controllerTypeId = entity.controllerTypeId,
                    deadzone = entity.deadzone,
                    updatedAtEpochMs = entity.updatedAtEpochMs,
                )
            }
        }
    }

    private fun resolveInputMode(
        profile: PlatformControlProfile,
        preferences: PlayerControlsPreferences,
        controllerConnected: Boolean,
    ): ActiveInputMode {
        return when {
            profile.touchSupportMode == TouchSupportMode.CONTROLLER_FIRST && controllerConnected -> ActiveInputMode.CONTROLLER
            profile.touchSupportMode == TouchSupportMode.CONTROLLER_FIRST -> ActiveInputMode.CONTROLLER_REQUIRED
            controllerConnected && preferences.autoHideTouchOnController -> ActiveInputMode.CONTROLLER
            controllerConnected -> ActiveInputMode.HYBRID
            else -> ActiveInputMode.TOUCH
        }
    }

    private fun shouldShowTouchControls(
        profile: PlatformControlProfile,
        preferences: PlayerControlsPreferences,
        controllerConnected: Boolean,
    ): Boolean {
        if (profile.touchSupportMode == TouchSupportMode.CONTROLLER_FIRST) return false
        if (!preferences.touchControlsEnabled) return false
        if (controllerConnected && preferences.autoHideTouchOnController) return false
        return true
    }
}
