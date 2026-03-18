package io.github.mattsays.rommnative.ui.screen.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.mattsays.rommnative.data.repository.PlayerControlsRepository
import io.github.mattsays.rommnative.data.repository.RommRepository
import io.github.mattsays.rommnative.domain.input.HardwareBindingProfile
import io.github.mattsays.rommnative.domain.input.PlayerControlsState
import io.github.mattsays.rommnative.domain.input.TouchLayoutProfile
import io.github.mattsays.rommnative.model.DownloadedRomEntity
import io.github.mattsays.rommnative.model.RomDto
import io.github.mattsays.rommnative.model.RomFileDto
import io.github.mattsays.rommnative.model.SaveStateEntity
import io.github.mattsays.rommnative.model.ConnectivityState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PlayerUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val rom: RomDto? = null,
    val installation: DownloadedRomEntity? = null,
    val saveStates: List<SaveStateEntity> = emptyList(),
    val controls: PlayerControlsState? = null,
    val overlay: PlayerOverlayState = PlayerOverlayState(),
    val errorMessage: String? = null,
)

data class PlayerOverlayState(
    val primaryPhase: PlayerOverlayPhase = PlayerOverlayPhase.ACTIVE,
    val tertiaryPhase: PlayerOverlayPhase = PlayerOverlayPhase.ACTIVE,
    val primaryLastInteractionEpochMs: Long = System.currentTimeMillis(),
    val tertiaryLastInteractionEpochMs: Long = System.currentTimeMillis(),
    val fadeSuspended: Boolean = false,
)

enum class PlayerOverlayPhase {
    ACTIVE,
    IDLE,
}

class PlayerViewModel(
    private val repository: RommRepository,
    private val controlsRepository: PlayerControlsRepository,
    private val romId: Int,
    private val fileId: Int,
) : ViewModel() {
    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()
    private var controlsJob: Job? = null
    private var romJob: Job? = null

    init {
        viewModelScope.launch {
            repository.observeSaveStates(romId).collect { states ->
                _uiState.update { it.copy(saveStates = states) }
            }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { current ->
                current.copy(
                    isLoading = current.installation == null && current.rom == null,
                    isRefreshing = true,
                    errorMessage = null,
                )
            }

            val install = repository.installedFileOrNull(romId, fileId)
                ?: run {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            errorMessage = "Download this ROM in the native app before launching it.",
                        )
                    }
                    return@launch
                }

            observeControls(install.platformSlug)
            observeRom(install)

            if (repository.currentConnectivityState() != ConnectivityState.ONLINE) {
                _uiState.update {
                    it.copy(
                        installation = install,
                        isLoading = it.rom == null,
                        isRefreshing = false,
                        errorMessage = null,
                    )
                }
                return@launch
            }

            runCatching { repository.refreshRomInBackground(romId) }.fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            installation = install,
                            isLoading = it.rom == null,
                            isRefreshing = false,
                            errorMessage = null,
                        )
                    }
                },
                onFailure = {
                    _uiState.update {
                        it.copy(
                            installation = install,
                            isLoading = it.rom == null,
                            isRefreshing = false,
                            errorMessage = null,
                        )
                    }
                },
            )
        }
    }

    private fun observeRom(installation: DownloadedRomEntity) {
        romJob?.cancel()
        romJob = viewModelScope.launch {
            repository.observeCachedRom(romId).collect { cachedRom ->
                _uiState.update { current ->
                    current.copy(
                        rom = cachedRom ?: installation.toFallbackRom(),
                        installation = installation,
                        isLoading = false,
                    )
                }
            }
        }
    }

    private fun observeControls(platformSlug: String) {
        controlsJob?.cancel()
        controlsJob = viewModelScope.launch {
            controlsRepository.observeControls(platformSlug).collect { controls ->
                _uiState.update { it.copy(controls = controls) }
            }
        }
    }

    suspend fun buildSession() = repository.buildPlayerSession(
        installation = _uiState.value.installation ?: error("No local install found."),
        rom = _uiState.value.rom ?: error("No ROM loaded."),
    )

    fun recordState(slot: Int, localPath: java.io.File) {
        viewModelScope.launch {
            val installation = _uiState.value.installation ?: return@launch
            repository.recordSaveState(installation, slot, localPath)
        }
    }

    fun syncNow(onFinished: (String) -> Unit) {
        viewModelScope.launch {
            val state = _uiState.value
            val rom = state.rom ?: return@launch
            val install = state.installation ?: return@launch
            if (repository.currentConnectivityState() != ConnectivityState.ONLINE) {
                repository.enqueuePendingSync(install, rom)
                onFinished("Save sync queued. It will run when you are back online.")
                return@launch
            }
            runCatching { repository.syncGame(install, rom) }.fold(
                onSuccess = { summary ->
                    onFinished("Uploaded ${summary.uploaded}, downloaded ${summary.downloaded}.")
                },
                onFailure = { error ->
                    onFinished(error.message ?: "Sync failed.")
                },
            )
        }
    }

    fun saveTouchLayout(profile: TouchLayoutProfile) {
        viewModelScope.launch {
            controlsRepository.saveTouchLayout(profile)
        }
    }

    fun resetTouchLayout(presetId: String? = null) {
        val platformSlug = _uiState.value.rom?.platformSlug ?: return
        viewModelScope.launch {
            controlsRepository.resetTouchLayout(platformSlug, presetId)
        }
    }

    fun saveHardwareBinding(profile: HardwareBindingProfile) {
        viewModelScope.launch {
            controlsRepository.saveHardwareBinding(profile)
        }
    }

    fun setTouchControlsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            controlsRepository.setTouchControlsEnabled(enabled)
        }
    }

    fun setAutoHideTouchOnController(enabled: Boolean) {
        viewModelScope.launch {
            controlsRepository.setAutoHideTouchOnController(enabled)
        }
    }

    fun setRumbleToDeviceEnabled(enabled: Boolean) {
        viewModelScope.launch {
            controlsRepository.setRumbleToDeviceEnabled(enabled)
        }
    }

    fun setOledBlackModeEnabled(enabled: Boolean) {
        viewModelScope.launch {
            controlsRepository.setOledBlackModeEnabled(enabled)
        }
    }

    fun setConsoleColorsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            controlsRepository.setConsoleColorsEnabled(enabled)
        }
    }

    fun markPrimaryOverlayInteraction() {
        _uiState.update { current ->
            current.copy(
                overlay = current.overlay.copy(
                    primaryPhase = PlayerOverlayPhase.ACTIVE,
                    primaryLastInteractionEpochMs = System.currentTimeMillis(),
                ),
            )
        }
    }

    fun markTertiaryOverlayInteraction() {
        val now = System.currentTimeMillis()
        _uiState.update { current ->
            current.copy(
                overlay = current.overlay.copy(
                    primaryPhase = PlayerOverlayPhase.ACTIVE,
                    tertiaryPhase = PlayerOverlayPhase.ACTIVE,
                    primaryLastInteractionEpochMs = now,
                    tertiaryLastInteractionEpochMs = now,
                ),
            )
        }
    }

    fun setOverlayFadeSuspended(suspended: Boolean) {
        _uiState.update { current ->
            if (current.overlay.fadeSuspended == suspended) {
                current
            } else {
                val now = System.currentTimeMillis()
                current.copy(
                    overlay = current.overlay.copy(
                        fadeSuspended = suspended,
                        primaryPhase = PlayerOverlayPhase.ACTIVE,
                        tertiaryPhase = PlayerOverlayPhase.ACTIVE,
                        primaryLastInteractionEpochMs = now,
                        tertiaryLastInteractionEpochMs = now,
                    ),
                )
            }
        }
    }

    fun setPrimaryOverlayIdle() {
        _uiState.update { current ->
            if (current.overlay.fadeSuspended || current.overlay.primaryPhase == PlayerOverlayPhase.IDLE) {
                current
            } else {
                current.copy(
                    overlay = current.overlay.copy(
                        primaryPhase = PlayerOverlayPhase.IDLE,
                    ),
                )
            }
        }
    }

    fun setTertiaryOverlayIdle() {
        _uiState.update { current ->
            if (current.overlay.fadeSuspended || current.overlay.tertiaryPhase == PlayerOverlayPhase.IDLE) {
                current
            } else {
                current.copy(
                    overlay = current.overlay.copy(
                        tertiaryPhase = PlayerOverlayPhase.IDLE,
                    ),
                )
            }
        }
    }
}

private fun DownloadedRomEntity.toFallbackRom(): RomDto {
    val fileExtension = fileName.substringAfterLast('.', "")
    val fallbackFile = RomFileDto(
        id = fileId,
        romId = romId,
        fileName = fileName,
        fileExtension = fileExtension,
        fileSizeBytes = fileSizeBytes,
    )
    return RomDto(
        id = romId,
        name = romName,
        platformName = platformSlug.replace('-', ' ').replaceFirstChar { it.uppercase() },
        platformSlug = platformSlug,
        fsName = fileName.substringBeforeLast('.'),
        files = listOf(fallbackFile),
        urlCover = null,
    )
}
