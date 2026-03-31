package io.github.bandoracer.rommio.ui.screen.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.bandoracer.rommio.data.repository.PlayerControlsRepository
import io.github.bandoracer.rommio.data.repository.RommRepository
import io.github.bandoracer.rommio.domain.input.HardwareBindingProfile
import io.github.bandoracer.rommio.domain.input.PlayerControlsState
import io.github.bandoracer.rommio.domain.input.TouchLayoutProfile
import io.github.bandoracer.rommio.model.BrowsableGameState
import io.github.bandoracer.rommio.model.GameSyncPresentation
import io.github.bandoracer.rommio.model.GameStateRecovery
import io.github.bandoracer.rommio.model.GameSyncStatusKind
import io.github.bandoracer.rommio.model.DownloadedRomEntity
import io.github.bandoracer.rommio.model.RomDto
import io.github.bandoracer.rommio.model.RomFileDto
import io.github.bandoracer.rommio.model.PlayerLaunchPreparation
import io.github.bandoracer.rommio.model.PlayerLaunchTarget
import io.github.bandoracer.rommio.model.ResumeConflict
import io.github.bandoracer.rommio.model.ConnectivityState
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
    val stateRecovery: GameStateRecovery = GameStateRecovery(),
    val controls: PlayerControlsState? = null,
    val syncPresentation: GameSyncPresentation = GameSyncPresentation(),
    val resumeConflict: ResumeConflict? = null,
    val pendingLaunchTarget: PlayerLaunchTarget? = null,
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
    private var syncJob: Job? = null
    private var recoveryJob: Job? = null

    init {
        syncJob = viewModelScope.launch {
            repository.observeGameSyncPresentation(romId, fileId).collect { presentation ->
                _uiState.update { current ->
                    current.copy(
                        syncPresentation = if (current.resumeConflict != null &&
                            presentation.kind == GameSyncStatusKind.CLOUD_PROGRESS_AVAILABLE
                        ) {
                            presentation.copy(kind = GameSyncStatusKind.CONFLICT)
                        } else {
                            presentation
                        },
                    )
                }
            }
        }
        recoveryJob = viewModelScope.launch {
            repository.observeGameStateRecovery(romId, fileId).collect { recovery ->
                _uiState.update { it.copy(stateRecovery = recovery) }
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
            val initialRom = repository.findCachedRom(romId) ?: install.toFallbackRom()
            _uiState.update {
                it.copy(
                    installation = install,
                    rom = it.rom ?: initialRom,
                )
            }
            val explicitLaunchTarget = repository.consumePendingPlayerLaunchTarget(romId, fileId)

            if (repository.currentConnectivityState() != ConnectivityState.ONLINE) {
                _uiState.update {
                    it.copy(
                        installation = install,
                        isLoading = it.rom == null,
                        isRefreshing = false,
                        resumeConflict = null,
                        pendingLaunchTarget = explicitLaunchTarget,
                        errorMessage = null,
                    )
                }
                return@launch
            }

            if (explicitLaunchTarget != null) {
                runCatching { repository.refreshRomInBackground(romId) }
                _uiState.update {
                    it.copy(
                        installation = install,
                        isLoading = it.rom == null,
                        isRefreshing = false,
                        resumeConflict = null,
                        pendingLaunchTarget = explicitLaunchTarget,
                        errorMessage = null,
                    )
                }
                return@launch
            }

            val launchPreparation = runCatching {
                repository.preparePlayerLaunch(install, repository.findCachedRom(romId) ?: initialRom)
            }.getOrElse { error ->
                PlayerLaunchPreparation(
                    syncPresentation = GameSyncPresentation(
                        kind = GameSyncStatusKind.ERROR,
                        message = error.message ?: "Unable to check cloud progress. Continuing with local data.",
                    ),
                )
            }

            runCatching { repository.refreshRomInBackground(romId) }.fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            installation = install,
                            isLoading = it.rom == null,
                            isRefreshing = false,
                            syncPresentation = launchPreparation.syncPresentation,
                            resumeConflict = launchPreparation.resumeConflict,
                            pendingLaunchTarget = launchPreparation.launchTarget,
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
                            syncPresentation = launchPreparation.syncPresentation,
                            resumeConflict = launchPreparation.resumeConflict,
                            pendingLaunchTarget = launchPreparation.launchTarget,
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

    fun flushContinuity(sessionActive: Boolean, onFinished: ((String) -> Unit)? = null) {
        viewModelScope.launch {
            val state = _uiState.value
            val rom = state.rom ?: return@launch
            val install = state.installation ?: return@launch
            if (repository.currentConnectivityState() != ConnectivityState.ONLINE) {
                repository.enqueuePendingSync(install, rom)
                _uiState.update {
                    it.copy(
                        syncPresentation = GameSyncPresentation(
                            kind = GameSyncStatusKind.OFFLINE_PENDING,
                            message = "Offline changes pending.",
                        ),
                    )
                }
                onFinished?.invoke("Sync queued. It will run when you are back online.")
                return@launch
            }
            runCatching { repository.flushContinuity(install, rom, sessionActive = sessionActive) }.fold(
                onSuccess = { summary ->
                    if (summary.uploaded > 0 || summary.downloaded > 0) {
                        onFinished?.invoke("Synced ${summary.uploaded} uploads and ${summary.downloaded} downloads.")
                    }
                },
                onFailure = { error ->
                    onFinished?.invoke(error.message ?: "Continuity sync failed.")
                },
            )
        }
    }

    fun resumeCloudSession(onFinished: (String) -> Unit) {
        viewModelScope.launch {
            val state = _uiState.value
            val rom = state.rom ?: return@launch
            val install = state.installation ?: return@launch
            runCatching { repository.adoptRemoteContinuity(install, rom) }.fold(
                onSuccess = { prep ->
                    _uiState.update {
                        it.copy(
                            resumeConflict = null,
                            pendingLaunchTarget = prep.launchTarget,
                            syncPresentation = prep.syncPresentation,
                        )
                    }
                    onFinished("Cloud progress ready.")
                },
                onFailure = { error ->
                    onFinished(error.message ?: "Unable to resume cloud session.")
                },
            )
        }
    }

    fun keepLocalProgress() {
        _uiState.update { current ->
            current.copy(
                resumeConflict = null,
                syncPresentation = current.syncPresentation.copy(
                    kind = GameSyncStatusKind.OFFLINE_PENDING,
                    message = "Keeping this device's progress.",
                ),
            )
        }
    }

    fun consumePendingLaunchTarget() {
        _uiState.update { it.copy(pendingLaunchTarget = null) }
    }

    fun availableLoadStates(): List<BrowsableGameState> {
        val recovery = _uiState.value.stateRecovery
        return recovery.saveSlots + recovery.snapshots
    }

    fun hasBrowsableStates(): Boolean {
        val recovery = _uiState.value.stateRecovery
        return recovery.resume?.available == true || recovery.saveSlots.isNotEmpty() || recovery.snapshots.isNotEmpty()
    }

    fun deleteState(stateEntry: BrowsableGameState, onFinished: (String) -> Unit) {
        viewModelScope.launch {
            val installation = _uiState.value.installation ?: return@launch
            runCatching { repository.deleteBrowsableState(installation, stateEntry) }.fold(
                onSuccess = { onFinished("${stateEntry.label} removed.") },
                onFailure = { error -> onFinished(error.message ?: "Unable to remove ${stateEntry.label}.") },
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
