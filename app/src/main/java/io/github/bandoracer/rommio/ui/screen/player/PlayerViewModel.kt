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
    val progressSession: io.github.bandoracer.rommio.domain.progress.ApplicationProgressSession? = null,
    val preparedSession: io.github.bandoracer.rommio.domain.player.PlayerSession? = null,
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
    private val progressSessions: io.github.bandoracer.rommio.domain.progress.ApplicationProgressSessions,
    private val romId: Int,
    private val fileId: Int,
) : ViewModel() {
    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()
    private var controlsJob: Job? = null
    private var romJob: Job? = null
    private var syncJob: Job? = null
    private var recoveryJob: Job? = null
    private var launchJob: Job? = null

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
        // A metadata refresh cannot rebuild a running core or choose a different baseline.
        if (launchJob?.isActive == true || _uiState.value.preparedSession != null) return
        launchJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, isRefreshing = true, errorMessage = null) }
            try {
                val install = repository.installedFileOrNull(romId, fileId)
                    ?: error("Download this ROM in the native app before launching it.")
                val rom = repository.findCachedRom(romId) ?: install.toFallbackRom()
                val cold = repository.buildProgressPlayerSession(install, rom)
                // Header routing is only a candidate check. The loaded core still verifies the effective mapper and every persistent region.
                val useProgress = cold.progressBinding != null && (cold.runtimeProfile.runtimeId in setOf("gambatte", "mgba", "snes9x", "melonds_ds", "genesis_plus_gx", "pcsx_rearmed", "mupen64plus_next_gles3", "ppsspp") ||
                    cold.runtimeProfile.runtimeId == "fceumm" && kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val header = cold.romPath.inputStream().use { input -> ByteArray(16).also { java.io.DataInputStream(input).readFully(it) } }
                        header.size == 16 && header.take(4) == listOf<Byte>(0x4e, 0x45, 0x53, 0x1a) &&
                            ((header[6].toInt() and 0xf0) shr 4 or (header[7].toInt() and 0xf0)) in setOf(0, 19)
                    })
                val explicitTarget = repository.consumePendingPlayerLaunchTarget(romId, fileId)
                val owner = if (useProgress) {
                    val active = progressSessions.active.value
                    if (active != null) {
                        check(active.workspace.identity.binding == cold.progressBinding) { "Close the current game before starting another." }
                        active
                    } else {
                        val legacy = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            cold.saveRamFile.isFile || cold.saveStatesDirectory.listFiles().orEmpty().any { it.isFile }
                        }
                        progressSessions.prepare(cold, legacy, publish = true).await()
                    }
                } else null
                val preparation = if (owner != null) PlayerLaunchPreparation()
                    else if (explicitTarget != null) PlayerLaunchPreparation(launchTarget = explicitTarget)
                    else repository.preparePlayerLaunch(install, rom)
                val session = if (owner != null) cold else repository.buildPlayerSession(install, rom)
                _uiState.update { it.copy(
                    installation = install, rom = rom, preparedSession = session, progressSession = owner,
                    isLoading = false, isRefreshing = false,
                    resumeConflict = preparation.resumeConflict,
                    pendingLaunchTarget = preparation.launchTarget,
                    syncPresentation = preparation.syncPresentation,
                ) }
                observeControls(install.platformSlug)
                observeRom(install)
                if (repository.currentConnectivityState() == ConnectivityState.ONLINE) {
                    repository.refreshRomInBackground(romId)
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update { current ->
                    // A late catalog failure must not unmount a prepared player.
                    if (current.preparedSession != null) current.copy(isRefreshing = false)
                    else current.copy(isLoading = false, isRefreshing = false,
                        errorMessage = error.message ?: "Unable to prepare player.")
                }
            }
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

    suspend fun recordState(slot: Int, localPath: java.io.File) {
        val installation = _uiState.value.installation ?: error("No local install found.")
        repository.recordSaveState(installation, slot, localPath)
    }

    fun syncNow(onFinished: (String) -> Unit) {
        viewModelScope.launch {
            val state = _uiState.value
            val rom = state.rom ?: return@launch
            val install = state.installation ?: return@launch
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

    suspend fun flushContinuity(sessionActive: Boolean, onFinished: ((String) -> Unit)? = null) {
        val state = _uiState.value
        val rom = state.rom ?: error("No ROM loaded.")
        val install = state.installation ?: error("No local install found.")
        try {
            val summary = repository.flushContinuity(install, rom, sessionActive = sessionActive)
            if (summary.uploaded > 0 || summary.downloaded > 0) {
                onFinished?.invoke("Synced ${summary.uploaded} uploads and ${summary.downloaded} downloads.")
            }
        } catch (_: io.github.bandoracer.rommio.domain.sync.LegacySyncUnavailableException) {
            // The legacy bridge throws this only after retaining local journal/recovery intent.
            // It is expected while cloud sync is disabled; actual storage errors still propagate.
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
