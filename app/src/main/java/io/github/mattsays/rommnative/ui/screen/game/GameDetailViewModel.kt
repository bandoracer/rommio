package io.github.mattsays.rommnative.ui.screen.game

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.mattsays.rommnative.data.repository.RommRepository
import io.github.mattsays.rommnative.domain.player.CoreResolution
import io.github.mattsays.rommnative.domain.player.PlayerCapability
import io.github.mattsays.rommnative.model.DownloadRecord
import io.github.mattsays.rommnative.model.DownloadStatus
import io.github.mattsays.rommnative.model.DownloadedRomEntity
import io.github.mattsays.rommnative.model.RomDto
import io.github.mattsays.rommnative.model.RomFileDto
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GameDetailUiState(
    val isLoading: Boolean = true,
    val isDownloadingCore: Boolean = false,
    val rom: RomDto? = null,
    val installedFiles: List<DownloadedRomEntity> = emptyList(),
    val selectedFileId: Int? = null,
    val downloadRecord: DownloadRecord? = null,
    val support: CoreResolution? = null,
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

    init {
        viewModelScope.launch {
            repository.observeInstalledFiles(romId).collect { files ->
                _uiState.updateState { current ->
                    val selected = current.selectedFileId ?: current.rom?.files?.firstOrNull()?.id
                    val support = current.rom?.let { rom ->
                        val selectedFile = rom.files.firstOrNull { it.id == selected } ?: rom.files.firstOrNull()
                        repository.resolveCoreSupport(rom, selectedFile)
                    }
                    current.copy(installedFiles = files, selectedFileId = selected, support = support)
                }
            }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.updateState { it.copy(isLoading = true, errorMessage = null) }
            runCatching { repository.getRomById(romId) }.fold(
                onSuccess = { rom ->
                    val selectedFileId = _uiState.value.selectedFileId ?: rom.files.firstOrNull()?.id
                    val support = repository.resolveCoreSupport(
                        rom,
                        rom.files.firstOrNull { it.id == selectedFileId } ?: rom.files.firstOrNull(),
                    )
                    _uiState.updateState {
                        it.copy(
                            isLoading = false,
                            rom = rom,
                            selectedFileId = selectedFileId,
                            support = support,
                        )
                    }
                    selectedFileId?.let(::observeSelectedDownload)
                },
                onFailure = { error ->
                    _uiState.updateState {
                        it.copy(isLoading = false, errorMessage = error.message ?: "Unable to load this ROM.")
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
            val rom = state.rom ?: return@launch
            val installed = state.currentInstall() ?: return@launch
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

    fun selectedInstall(): DownloadedRomEntity? = _uiState.value.currentInstall()
    fun selectedFile(): RomFileDto? = _uiState.value.selectedFile()

    private fun observeSelectedDownload(fileId: Int) {
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
        else -> GameDetailAction(
            kind = GameDetailActionKind.DOWNLOAD_NOW,
            label = if (downloadStatus == DownloadStatus.RUNNING) "Download active" else "Download now",
            enabled = downloadStatus != DownloadStatus.RUNNING,
        )
    }

    val secondary = buildList {
        if (downloadStatus != DownloadStatus.RUNNING && downloadStatus != DownloadStatus.QUEUED) {
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
        if (installed && support?.runtimeProfile != null) {
            add(GameDetailAction(GameDetailActionKind.SYNC, "Sync now"))
        }
    }

    return GameActionPresentation(primary = primary, secondary = secondary)
}
