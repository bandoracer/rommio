package io.github.mattsays.rommnative.ui.screen.game

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.mattsays.rommnative.data.repository.RommRepository
import io.github.mattsays.rommnative.domain.player.CoreResolution
import io.github.mattsays.rommnative.domain.player.EmbeddedSupportTier
import io.github.mattsays.rommnative.domain.player.PlayerCapability
import io.github.mattsays.rommnative.model.DownloadRecord
import io.github.mattsays.rommnative.model.DownloadStatus
import io.github.mattsays.rommnative.model.DownloadedRomEntity
import io.github.mattsays.rommnative.model.ConnectivityState
import io.github.mattsays.rommnative.model.RomDto
import io.github.mattsays.rommnative.model.RomFileDto
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
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
    val coreMessage: String? = null,
    val syncMessage: String? = null,
    val errorMessage: String? = null,
    val actionPresentation: GameActionPresentation = GameActionPresentation(),
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

class GameDetailViewModel(
    private val repository: RommRepository,
    private val romId: Int,
) : ViewModel() {
    private val _uiState = MutableStateFlow(GameDetailUiState())
    val uiState: StateFlow<GameDetailUiState> = _uiState.asStateFlow()
    private var downloadJob: Job? = null
    private var observedDownloadFileId: Int? = null

    init {
        viewModelScope.launch {
            combine(
                repository.observeCachedRom(romId),
                repository.observeInstalledFiles(romId),
            ) { cachedRom, files ->
                cachedRom to files
            }.collect { (cachedRom, files) ->
                val rom = cachedRom ?: files.toFallbackRom()
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
                    repository.resolveCoreSupport(cachedRom, selectedFile)
                }
                val supportTier = rom?.let(repository::embeddedSupportTier) ?: EmbeddedSupportTier.UNSUPPORTED
                _uiState.updateState { current ->
                    current.copy(
                        rom = rom,
                        installedFiles = files,
                        selectedFileId = nextSelectedFileId,
                        support = support,
                        supportTier = supportTier,
                        isLocalOnly = cachedRom == null && files.isNotEmpty(),
                        isLoading = false,
                    )
                }
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

    fun refresh() {
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
                    )
                }
                return@launch
            }
            runCatching { repository.refreshRomInBackground(romId) }.fold(
                onSuccess = {
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
                support = repository.resolveCoreSupport(rom, file),
                supportTier = repository.embeddedSupportTier(rom),
                coreMessage = null,
            )
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
                    io.github.mattsays.rommnative.model.DownloadStatus.FAILED,
                    io.github.mattsays.rommnative.model.DownloadStatus.CANCELED,
                    io.github.mattsays.rommnative.model.DownloadStatus.COMPLETED,
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
                    io.github.mattsays.rommnative.model.DownloadStatus.FAILED,
                    io.github.mattsays.rommnative.model.DownloadStatus.CANCELED,
                    io.github.mattsays.rommnative.model.DownloadStatus.COMPLETED,
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
                    it.copy(syncMessage = "This local copy is no longer in RomM, so cloud sync is unavailable.")
                }
                return@launch
            }
            val rom = state.rom ?: return@launch
            val installed = state.currentInstall() ?: return@launch
            if (repository.currentConnectivityState() != ConnectivityState.ONLINE) {
                repository.enqueuePendingSync(installed, rom)
                _uiState.updateState {
                    it.copy(syncMessage = "Save sync queued. It will resume when you are back online.")
                }
                return@launch
            }
            runCatching { repository.syncGame(installed, rom) }.fold(
                onSuccess = { summary ->
                    _uiState.updateState {
                        it.copy(syncMessage = "Uploaded ${summary.uploaded}, downloaded ${summary.downloaded}.")
                    }
                },
                onFailure = { error ->
                    _uiState.updateState { it.copy(syncMessage = error.message ?: "Sync failed.") }
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
                    syncMessage = null,
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
}

private fun MutableStateFlow<GameDetailUiState>.updateState(
    transform: (GameDetailUiState) -> GameDetailUiState,
) {
    update { current -> transform(current).withDerivedActions() }
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
            add(GameDetailAction(GameDetailActionKind.SYNC, "Sync now"))
        }
    }

    return GameActionPresentation(primary = primary, secondary = secondary)
}

private fun List<DownloadedRomEntity>.toFallbackRom(): RomDto? {
    val primary = firstOrNull() ?: return null
    val fallbackFiles = distinctBy { it.fileId }
        .sortedByDescending { it.downloadedAtEpochMs }
        .map { install ->
            val fileExtension = install.fileName.substringAfterLast('.', "")
            RomFileDto(
                id = install.fileId,
                romId = install.romId,
                fileName = install.fileName,
                fileExtension = fileExtension,
                fileSizeBytes = install.fileSizeBytes,
            )
        }
    return RomDto(
        id = primary.romId,
        name = primary.romName,
        platformName = primary.platformSlug.replace('-', ' ').replaceFirstChar { it.uppercase() },
        platformSlug = primary.platformSlug,
        fsName = primary.fileName.substringBeforeLast('.'),
        files = fallbackFiles,
        urlCover = null,
    )
}
