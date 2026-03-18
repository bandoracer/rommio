package io.github.mattsays.rommnative.ui.screen.game

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.mattsays.rommnative.data.repository.RommRepository
import io.github.mattsays.rommnative.domain.player.CoreResolution
import io.github.mattsays.rommnative.model.DownloadedRomEntity
import io.github.mattsays.rommnative.model.RomDto
import io.github.mattsays.rommnative.model.RomFileDto
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
    val support: CoreResolution? = null,
    val coreMessage: String? = null,
    val syncMessage: String? = null,
    val errorMessage: String? = null,
)

class GameDetailViewModel(
    private val repository: RommRepository,
    private val romId: Int,
) : ViewModel() {
    private val _uiState = MutableStateFlow(GameDetailUiState())
    val uiState: StateFlow<GameDetailUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeInstalledFiles(romId).collect { files ->
                _uiState.update { current ->
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
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            runCatching { repository.getRomById(romId) }.fold(
                onSuccess = { rom ->
                    val selectedFileId = _uiState.value.selectedFileId ?: rom.files.firstOrNull()?.id
                    val support = repository.resolveCoreSupport(
                        rom,
                        rom.files.firstOrNull { it.id == selectedFileId } ?: rom.files.firstOrNull(),
                    )
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            rom = rom,
                            selectedFileId = selectedFileId,
                            support = support,
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(isLoading = false, errorMessage = error.message ?: "Unable to load this ROM.")
                    }
                },
            )
        }
    }

    fun selectFile(fileId: Int) {
        val rom = _uiState.value.rom ?: return
        val file = rom.files.firstOrNull { it.id == fileId }
        _uiState.update {
            it.copy(
                selectedFileId = fileId,
                support = repository.resolveCoreSupport(rom, file),
                coreMessage = null,
            )
        }
    }

    fun enqueueDownload() {
        val state = _uiState.value
        val rom = state.rom ?: return
        val file = state.selectedFile() ?: return
        repository.enqueueDownload(rom, file)
    }

    fun downloadRecommendedCore() {
        val state = _uiState.value
        val rom = state.rom ?: return
        val file = state.selectedFile()

        viewModelScope.launch {
            _uiState.update { it.copy(isDownloadingCore = true, coreMessage = null, errorMessage = null) }
            runCatching { repository.installRecommendedCore(rom, file) }.fold(
                onSuccess = { updatedSupport ->
                    _uiState.update {
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
                    _uiState.update {
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
                    _uiState.update {
                        it.copy(syncMessage = "Uploaded ${summary.uploaded}, downloaded ${summary.downloaded}.")
                    }
                },
                onFailure = { error ->
                    _uiState.update { it.copy(syncMessage = error.message ?: "Sync failed.") }
                },
            )
        }
    }

    fun selectedInstall(): DownloadedRomEntity? = _uiState.value.currentInstall()
    fun selectedFile(): RomFileDto? = _uiState.value.selectedFile()
}

private fun GameDetailUiState.selectedFile(): RomFileDto? {
    return rom?.files?.firstOrNull { it.id == selectedFileId } ?: rom?.files?.firstOrNull()
}

private fun GameDetailUiState.currentInstall(): DownloadedRomEntity? {
    return installedFiles.firstOrNull { it.fileId == selectedFileId } ?: installedFiles.firstOrNull()
}
