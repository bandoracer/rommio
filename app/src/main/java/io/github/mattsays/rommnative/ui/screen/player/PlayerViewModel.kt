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
import io.github.mattsays.rommnative.model.SaveStateEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PlayerUiState(
    val isLoading: Boolean = true,
    val rom: RomDto? = null,
    val installation: DownloadedRomEntity? = null,
    val saveStates: List<SaveStateEntity> = emptyList(),
    val controls: PlayerControlsState? = null,
    val errorMessage: String? = null,
)

class PlayerViewModel(
    private val repository: RommRepository,
    private val controlsRepository: PlayerControlsRepository,
    private val romId: Int,
    private val fileId: Int,
) : ViewModel() {
    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()
    private var controlsJob: Job? = null

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
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            runCatching {
                val rom = repository.getRomById(romId)
                val install = repository.installedFileOrNull(romId, fileId)
                    ?: error("Download this ROM in the native app before launching it.")
                rom to install
            }.fold(
                onSuccess = { (rom, install) ->
                    observeControls(rom.platformSlug)
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            rom = rom,
                            installation = install,
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(isLoading = false, errorMessage = error.message ?: "Unable to prepare player.")
                    }
                },
            )
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
}
