package io.github.bandoracer.rommio.ui.screen.game

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.bandoracer.rommio.data.playdata.SavedProgressRepository
import io.github.bandoracer.rommio.data.repository.RommRepository
import io.github.bandoracer.rommio.domain.playdata.GameSaves
import io.github.bandoracer.rommio.domain.playdata.SaveKey
import io.github.bandoracer.rommio.model.BrowsableGameState
import io.github.bandoracer.rommio.domain.player.CoreResolution
import io.github.bandoracer.rommio.domain.player.EmbeddedSupportTier
import io.github.bandoracer.rommio.domain.player.PlayerCapability
import io.github.bandoracer.rommio.model.GameStateRecovery
import io.github.bandoracer.rommio.model.GameSyncPresentation
import io.github.bandoracer.rommio.model.GameSyncStatusKind
import io.github.bandoracer.rommio.model.ResumeStateStatusKind
import io.github.bandoracer.rommio.model.DownloadRecord
import io.github.bandoracer.rommio.model.DownloadStatus
import io.github.bandoracer.rommio.model.DownloadedRomEntity
import io.github.bandoracer.rommio.model.ConnectivityState
import io.github.bandoracer.rommio.model.RomDto
import io.github.bandoracer.rommio.model.RomFileDto
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GameDetailUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isDownloadingCore: Boolean = false,
    val rom: RomDto? = null,
    val installedFiles: List<DownloadedRomEntity> = emptyList(),
    val selectedFileId: Int? = null,
    val downloadRecord: DownloadRecord? = null,
    val support: CoreResolution? = null,
    val supportTier: EmbeddedSupportTier = EmbeddedSupportTier.UNSUPPORTED,
    val isLocalOnly: Boolean = false,
    val syncPresentation: GameSyncPresentation = GameSyncPresentation(),
    val stateRecovery: GameStateRecovery = GameStateRecovery(),
    val coreMessage: String? = null,
    val errorMessage: String? = null,
    val actionPresentation: GameActionPresentation = GameActionPresentation(),
    /** Saved progress for this game from the current progress store (resume point, save states, history). */
    val saves: GameSaves = GameSaves(),
    val savesLoaded: Boolean = false,
)

data class GameActionPresentation(
    val primary: GameDetailAction? = null,
    val secondary: List<GameDetailAction> = emptyList(),
)

data class GameDetailAction(
    val kind: GameDetailActionKind,
    val label: String,
    val enabled: Boolean = true,
)

enum class GameDetailActionKind {
    PLAY,
    DOWNLOAD_NOW,
    DOWNLOAD_CORE,
    QUEUE,
    DELETE_LOCAL,
    CANCEL,
    RETRY,
    SYNC,
}

/** The Game page: loading and error, or the game's content (built once per data change). */
@androidx.compose.runtime.Immutable
data class GamePage(
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val content: GameContent? = null,
    /** The main action (the bar shows Play or Download). */
    val primary: GameDetailAction? = null,
)

@OptIn(kotlinx.coroutines.FlowPreview::class)
class GameDetailViewModel(
    private val repository: RommRepository,
    private val romId: Int,
    private val savedProgress: SavedProgressRepository? = null,
    playDataChanges: Flow<Long> = emptyFlow(),
    /** This game's play on this device (only this game's totals are read). */
    playActivity: io.github.bandoracer.rommio.data.stats.PlayActivityRepository? = null,
) : ViewModel() {
    private val _uiState = MutableStateFlow(GameDetailUiState())
    val uiState: StateFlow<GameDetailUiState> = _uiState.asStateFlow()

    /**
     * The page both layouts draw, built off the main thread and published only when it changes. It
     * reads this game's play totals, not all of Stats.
     */
    val page: StateFlow<GamePage> = kotlinx.coroutines.flow.combine(
        _uiState,
        (playActivity?.stats ?: kotlinx.coroutines.flow.flowOf(io.github.bandoracer.rommio.domain.stats.PlayStats()))
            .map { stats -> stats.games.firstOrNull { it.romId.toInt() == romId } }
            .distinctUntilChanged(),
        repository.activeProfileFlow().map { it?.baseUrl }.distinctUntilChanged(),
    ) { state, played, baseUrl ->
        GamePage(
            isLoading = state.isLoading,
            errorMessage = state.errorMessage,
            content = state.rom?.let { rom ->
                GameContent.build(
                    state = state,
                    rom = rom,
                    baseUrl = baseUrl,
                    selectedFile = state.selectedFile(),
                    selectedInstalled = state.currentInstall() != null,
                    played = played,
                )
            },
            primary = state.actionPresentation.primary,
        )
    }
        .distinctUntilChanged()
        .flowOn(kotlinx.coroutines.Dispatchers.Default)
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, GamePage())
    private var downloadJob: Job? = null
    private var syncJob: Job? = null
    private var recoveryJob: Job? = null
    private var observedDownloadFileId: Int? = null
    private var observedSyncFileId: Int? = null
    private var observedRecoveryFileId: Int? = null
    private var savesJob: Job? = null
    private var savesScope: String? = null

    init {
        viewModelScope.launch {
            val labels = savedProgress?.labelChanges ?: emptyFlow()
            merge(playDataChanges, labels).drop(1).debounce(300).collect { refreshSaves() }
        }
        viewModelScope.launch {
            combine(
                repository.observeCachedRom(romId),
                repository.observeInstalledFiles(romId),
                repository.observeRuntimeAssets(),
            ) { cachedRom, files, _ ->
                cachedRom to files
            }.collect { (cachedRom, files) ->
                val rom = cachedRom ?: files.toFallbackRom()
                val before = _uiState.value.selectedFileId
                val nextSelectedFileId = _uiState.value.let { current ->
                    current.selectedFileId?.takeIf { selectedId ->
                        rom?.files?.any { it.id == selectedId } == true
                    }
                        ?: files.firstOrNull { installed ->
                            rom?.files?.any { it.id == installed.fileId } == true
                        }?.fileId
                        ?: rom?.files?.firstOrNull()?.id
                }
                val support = rom?.let { cachedRom ->
                    val selectedFile = cachedRom.files.firstOrNull { it.id == nextSelectedFileId }
                        ?: cachedRom.files.firstOrNull()
                    // Resolving reads the disk (BIOS, the installed core): on the IO dispatcher.
                    repository.coreSupport(cachedRom, selectedFile)
                }
                val supportTier = rom?.let(repository::embeddedSupportTier) ?: EmbeddedSupportTier.UNSUPPORTED
                _uiState.updateState { current ->
                    // A file chosen while the core resolved stays chosen; selectFile resolves its support.
                    val chosen = current.selectedFileId?.takeIf { id -> id != before && rom?.files?.any { it.id == id } == true }
                    current.copy(
                        rom = rom,
                        installedFiles = files,
                        selectedFileId = chosen ?: nextSelectedFileId,
                        support = if (chosen != null) current.support else support,
                        supportTier = supportTier,
                        isLocalOnly = cachedRom == null && files.isNotEmpty(),
                        isLoading = false,
                    )
                }
                val nextSyncFileId = files.firstOrNull { it.fileId == nextSelectedFileId }?.fileId
                    ?: files.firstOrNull()?.fileId
                if (nextSyncFileId != null) {
                    observeSyncPresentation(nextSyncFileId)
                    observeStateRecovery(nextSyncFileId)
                } else {
                    observedSyncFileId = null
                    syncJob?.cancel()
                    observedRecoveryFileId = null
                    recoveryJob?.cancel()
                    _uiState.updateState {
                        it.copy(
                            syncPresentation = if (it.isLocalOnly) {
                                GameSyncPresentation(
                                    kind = GameSyncStatusKind.LOCAL_ONLY,
                                    message = "Local-only install.",
                                )
                            } else {
                                GameSyncPresentation()
                            },
                            stateRecovery = GameStateRecovery(),
                        )
                    }
                }
                val scope = files.firstOrNull { it.fileId == nextSelectedFileId }?.progressScope ?: files.firstOrNull()?.progressScope
                if (!_uiState.value.savesLoaded || scope != savesScope) refreshSaves()
                if (nextSelectedFileId != null) {
                    observeSelectedDownload(nextSelectedFileId)
                } else {
                    observedDownloadFileId = null
                    downloadJob?.cancel()
                    _uiState.updateState { it.copy(downloadRecord = null) }
                }
            }
        }
        refresh()
    }

    /** Re-reads this game's saved progress from the progress store (read-only). */
    fun refreshSaves() {
        val saves = savedProgress ?: return
        val scope = _uiState.value.currentInstall()?.progressScope?.takeIf { it != io.github.bandoracer.rommio.model.InstallIdentity.LEGACY }
        savesScope = _uiState.value.currentInstall()?.progressScope
        savesJob?.cancel()
        savesJob = viewModelScope.launch {
            val result = saves.gameSaves(romId.toLong(), scope)
            _uiState.updateState { it.copy(saves = result, savesLoaded = true) }
        }
    }

    fun renameSave(key: SaveKey, name: String) {
        val saves = savedProgress ?: return
        viewModelScope.launch { saves.rename(key, name); refreshSaves() }
    }

    fun refresh() {
        refreshSaves()
        viewModelScope.launch {
            _uiState.updateState {
                it.copy(
                    isLoading = it.rom == null,
                    isRefreshing = true,
                    errorMessage = null,
                )
            }
            if (repository.currentConnectivityState() != ConnectivityState.ONLINE) {
                _uiState.updateState {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        errorMessage = if (it.rom == null) {
                            "Offline. This title will appear after the profile sync completes."
                        } else if (it.isLocalOnly) {
                            "This local copy is no longer available in RomM. You can still play it locally or remove it from the device."
                        } else {
                            null
                        },
                        syncPresentation = if (it.isLocalOnly) {
                            GameSyncPresentation(
                                kind = GameSyncStatusKind.LOCAL_ONLY,
                                message = "Local-only install.",
                            )
                        } else {
                            it.syncPresentation
                        },
                    )
                }
                return@launch
            }
            runCatching { repository.refreshRomInBackground(romId) }.fold(
                onSuccess = {
                    _uiState.value.currentInstall()?.let { install ->
                        _uiState.value.rom?.let { rom ->
                            runCatching { repository.refreshGameStateRecovery(install, rom) }
                        }
                    }
                    _uiState.updateState {
                        it.copy(
                            isLoading = it.rom == null,
                            isRefreshing = false,
                            errorMessage = null,
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.updateState {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            errorMessage = if (it.rom != null) {
                                error.message ?: "This title is showing cached data until the next successful refresh."
                            } else {
                                error.message ?: "Unable to load this ROM."
                            },
                        )
                    }
                },
            )
        }
    }

    fun selectFile(fileId: Int) {
        val rom = _uiState.value.rom ?: return
        val file = rom.files.firstOrNull { it.id == fileId }
        _uiState.updateState {
            it.copy(
                selectedFileId = fileId,
                supportTier = repository.embeddedSupportTier(rom),
                coreMessage = null,
            )
        }
        // The file's core support reads the disk: resolved off the main thread, kept if still chosen.
        viewModelScope.launch {
            val support = repository.coreSupport(rom, file)
            _uiState.updateState { if (it.selectedFileId == fileId) it.copy(support = support) else it }
        }
        refreshSaves()
        if (_uiState.value.installedFiles.any { it.fileId == fileId }) {
            observeSyncPresentation(fileId)
            observeStateRecovery(fileId)
            if (repository.currentConnectivityState() == ConnectivityState.ONLINE) {
                viewModelScope.launch {
                    val install = _uiState.value.currentInstall() ?: return@launch
                    val selectedRom = _uiState.value.rom ?: return@launch
                    runCatching { repository.refreshGameStateRecovery(install, selectedRom) }
                }
            }
        }
        observeSelectedDownload(fileId)
    }

    fun enqueueDownload() {
        val state = _uiState.value
        val rom = state.rom ?: return
        val file = state.selectedFile() ?: return
        viewModelScope.launch {
            repository.enqueueDownload(
                rom = rom,
                file = file,
                replaceExisting = state.downloadRecord?.status in setOf(
                    io.github.bandoracer.rommio.model.DownloadStatus.FAILED,
                    io.github.bandoracer.rommio.model.DownloadStatus.CANCELED,
                    io.github.bandoracer.rommio.model.DownloadStatus.COMPLETED,
                ) || state.installedFiles.any { it.fileId == file.id },
            )
        }
    }

    fun downloadNow() {
        val state = _uiState.value
        val rom = state.rom ?: return
        val file = state.selectedFile() ?: return
        viewModelScope.launch {
            repository.downloadNow(
                rom = rom,
                file = file,
                replaceExisting = state.downloadRecord?.status in setOf(
                    io.github.bandoracer.rommio.model.DownloadStatus.FAILED,
                    io.github.bandoracer.rommio.model.DownloadStatus.CANCELED,
                    io.github.bandoracer.rommio.model.DownloadStatus.COMPLETED,
                ) || state.installedFiles.any { it.fileId == file.id },
            )
        }
    }

    fun cancelDownload() {
        val record = _uiState.value.downloadRecord ?: return
        viewModelScope.launch { repository.cancelDownload(record) }
    }

    fun retryDownload() {
        val record = _uiState.value.downloadRecord ?: return
        viewModelScope.launch { repository.retryDownload(record) }
    }

    fun downloadRecommendedCore() {
        val state = _uiState.value
        val rom = state.rom ?: return
        val file = state.selectedFile()

        viewModelScope.launch {
            if (repository.currentConnectivityState() != ConnectivityState.ONLINE) {
                val runtime = state.support?.runtimeProfile
                    ?: return@launch _uiState.updateState {
                        it.copy(coreMessage = "Connect to the internet before downloading this core.")
                    }
                repository.enqueuePendingCoreDownload(runtime)
                _uiState.updateState {
                    it.copy(coreMessage = "${runtime.displayName} will download when you are back online.")
                }
                return@launch
            }
            _uiState.updateState { it.copy(isDownloadingCore = true, coreMessage = null, errorMessage = null) }
            runCatching { repository.installRecommendedCore(rom, file) }.fold(
                onSuccess = { updatedSupport ->
                    _uiState.updateState {
                        it.copy(
                            isDownloadingCore = false,
                            support = updatedSupport,
                            coreMessage = updatedSupport.runtimeProfile?.let { profile ->
                                "${profile.displayName} is ready for in-app play."
                            } ?: "Recommended core installed.",
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.updateState {
                        it.copy(
                            isDownloadingCore = false,
                            coreMessage = error.message ?: "Core download failed.",
                        )
                    }
                },
            )
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            val state = _uiState.value
            if (state.isLocalOnly) {
                _uiState.updateState {
                    it.copy(
                        syncPresentation = GameSyncPresentation(
                            kind = GameSyncStatusKind.LOCAL_ONLY,
                            message = "This local copy is no longer in RomM, so cloud sync is unavailable.",
                        ),
                    )
                }
                return@launch
            }
            val rom = state.rom ?: return@launch
            val installed = state.currentInstall() ?: return@launch
            if (repository.currentConnectivityState() != ConnectivityState.ONLINE) {
                repository.enqueuePendingSync(installed, rom)
                _uiState.updateState {
                    it.copy(
                        syncPresentation = GameSyncPresentation(
                            kind = GameSyncStatusKind.OFFLINE_PENDING,
                            message = "Offline changes pending.",
                        ),
                    )
                }
                return@launch
            }
            runCatching { repository.syncGame(installed, rom) }.fold(
                onSuccess = {
                    _uiState.updateState {
                        it.copy(
                            syncPresentation = GameSyncPresentation(
                                kind = GameSyncStatusKind.SYNCED,
                                message = "Synced just now.",
                            ),
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.updateState {
                        it.copy(
                            syncPresentation = GameSyncPresentation(
                                kind = GameSyncStatusKind.ERROR,
                                message = error.message ?: "Sync failed.",
                            ),
                        )
                    }
                },
            )
        }
    }

    fun deleteLocal() {
        viewModelScope.launch {
            val installation = _uiState.value.currentInstall() ?: return@launch
            repository.deleteInstalledFile(installation)
            _uiState.updateState {
                it.copy(
                    coreMessage = null,
                    errorMessage = if (it.isLocalOnly && it.installedFiles.size <= 1) {
                        "The local copy was removed from this device."
                    } else {
                        null
                    },
                )
            }
        }
    }

    fun selectedInstall(): DownloadedRomEntity? = _uiState.value.currentInstall()
    fun selectedFile(): RomFileDto? = _uiState.value.selectedFile()

    fun launchFromState(
        stateEntry: BrowsableGameState,
        onReady: (romId: Int, fileId: Int) -> Unit,
    ) {
        viewModelScope.launch {
            val installation = _uiState.value.currentInstall() ?: return@launch
            repository.setPendingPlayerLaunchTarget(installation, stateEntry)
            onReady(installation.romId, installation.fileId)
        }
    }

    fun deleteState(stateEntry: BrowsableGameState) {
        viewModelScope.launch {
            val installation = _uiState.value.currentInstall() ?: return@launch
            repository.deleteBrowsableState(installation, stateEntry)
        }
    }

    private fun observeSelectedDownload(fileId: Int) {
        if (observedDownloadFileId == fileId) return
        observedDownloadFileId = fileId
        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            repository.observeDownloadRecord(romId, fileId).collect { record ->
                _uiState.updateState { it.copy(downloadRecord = record) }
            }
        }
    }

    private fun observeSyncPresentation(fileId: Int) {
        if (observedSyncFileId == fileId) return
        observedSyncFileId = fileId
        syncJob?.cancel()
        syncJob = viewModelScope.launch {
            repository.observeGameSyncPresentation(romId, fileId).collect { presentation ->
                _uiState.updateState { current ->
                    current.copy(
                        syncPresentation = if (current.isLocalOnly) {
                            GameSyncPresentation(
                                kind = GameSyncStatusKind.LOCAL_ONLY,
                                message = "Local-only install.",
                            )
                        } else {
                            presentation
                        },
                    )
                }
            }
        }
    }

    private fun observeStateRecovery(fileId: Int) {
        if (observedRecoveryFileId == fileId) return
        observedRecoveryFileId = fileId
        recoveryJob?.cancel()
        recoveryJob = viewModelScope.launch {
            repository.observeGameStateRecovery(romId, fileId).collect { recovery ->
                _uiState.updateState { current ->
                    current.copy(
                        stateRecovery = if (current.isLocalOnly) {
                            recovery.asLocalOnly()
                        } else {
                            recovery
                        },
                    )
                }
            }
        }
    }
}

private fun MutableStateFlow<GameDetailUiState>.updateState(
    transform: (GameDetailUiState) -> GameDetailUiState,
) {
    update { current -> transform(current).withDerivedActions() }
}

private fun GameStateRecovery.asLocalOnly(): GameStateRecovery {
    val summary = resume
    return if (summary == null) {
        copy()
    } else {
        copy(
            resume = summary.copy(
                statusKind = ResumeStateStatusKind.LOCAL_ONLY,
                primaryStatusMessage = "Local-only install.",
            ),
        )
    }
}

private fun GameDetailUiState.selectedFile(): RomFileDto? {
    return rom?.files?.firstOrNull { it.id == selectedFileId } ?: rom?.files?.firstOrNull()
}

private fun GameDetailUiState.currentInstall(): DownloadedRomEntity? {
    return installedFiles.firstOrNull { it.fileId == selectedFileId } ?: installedFiles.firstOrNull()
}

private fun GameDetailUiState.withDerivedActions(): GameDetailUiState {
    return copy(actionPresentation = deriveActionPresentation())
}

private fun GameDetailUiState.deriveActionPresentation(): GameActionPresentation {
    val downloadStatus = downloadRecord?.status
    val installed = currentInstall() != null
    val localOnly = isLocalOnly
    val supportCapability = support?.capability
    val canPlay = installed && supportCapability == PlayerCapability.READY
    val canDownloadCore = supportCapability == PlayerCapability.MISSING_CORE && support?.runtimeProfile?.download != null
    val primary = when {
        canPlay -> GameDetailAction(GameDetailActionKind.PLAY, "Play")
        canDownloadCore -> GameDetailAction(
            kind = GameDetailActionKind.DOWNLOAD_CORE,
            label = if (isDownloadingCore) "Downloading core…" else "Download core",
            enabled = !isDownloadingCore,
        )
        localOnly -> null
        else -> GameDetailAction(
            kind = GameDetailActionKind.DOWNLOAD_NOW,
            label = if (downloadStatus == DownloadStatus.RUNNING) "Download active" else "Download now",
            enabled = downloadStatus != DownloadStatus.RUNNING,
        )
    }

    val secondary = buildList {
        if (!localOnly && downloadStatus != DownloadStatus.RUNNING && downloadStatus != DownloadStatus.QUEUED) {
            add(
                GameDetailAction(
                    kind = GameDetailActionKind.QUEUE,
                    label = if (installed) "Queue redownload" else "Add to queue",
                ),
            )
        }
        if (downloadStatus == DownloadStatus.RUNNING || downloadStatus == DownloadStatus.QUEUED) {
            add(GameDetailAction(GameDetailActionKind.CANCEL, "Cancel"))
        }
        if (downloadStatus == DownloadStatus.FAILED || downloadStatus == DownloadStatus.CANCELED) {
            add(GameDetailAction(GameDetailActionKind.RETRY, "Retry"))
        }
        if (installed) {
            add(GameDetailAction(GameDetailActionKind.DELETE_LOCAL, "Delete local"))
        }
        if (!localOnly && installed && support?.runtimeProfile != null) {
            add(GameDetailAction(GameDetailActionKind.SYNC, "Advanced sync"))
        }
    }

    return GameActionPresentation(primary = primary, secondary = secondary)
}

private fun List<DownloadedRomEntity>.toFallbackRom(): RomDto? {
    val first = firstOrNull() ?: return null
    return RomDto(
        id = first.romId,
        name = first.romName,
        platformName = first.platformSlug.replace('-', ' ').replaceFirstChar { it.uppercase() },
        platformSlug = first.platformSlug,
        fsName = first.fileName.substringBeforeLast('.'),
        urlCover = null,
    )
}
