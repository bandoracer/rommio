package io.github.mattsays.rommnative.ui.screen.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.mattsays.rommnative.data.repository.PlayerControlsRepository
import io.github.mattsays.rommnative.data.repository.RommRepository
import io.github.mattsays.rommnative.domain.input.PlayerControlsPreferences
import io.github.mattsays.rommnative.model.ServerProfile
import io.github.mattsays.rommnative.model.UserDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val isLoading: Boolean = true,
    val activeProfile: ServerProfile? = null,
    val profiles: List<ServerProfile> = emptyList(),
    val user: UserDto? = null,
    val controlsPreferences: PlayerControlsPreferences = PlayerControlsPreferences(),
    val installedGameCount: Int = 0,
    val installedFileCount: Int = 0,
    val storageBytes: Long = 0,
    val libraryPath: String = "",
    val errorMessage: String? = null,
)

class SettingsViewModel(
    private val repository: RommRepository,
    private val controlsRepository: PlayerControlsRepository,
    private val libraryPath: String,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SettingsUiState(libraryPath = libraryPath))
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.activeProfileFlow().collect { profile ->
                _uiState.update { it.copy(activeProfile = profile) }
            }
        }
        viewModelScope.launch {
            controlsRepository.observePreferences().collect { preferences ->
                _uiState.update { it.copy(controlsPreferences = preferences) }
            }
        }
        viewModelScope.launch {
            repository.observeLibraryStorageSummary().collect { summary ->
                _uiState.update {
                    it.copy(
                        installedGameCount = summary.installedGameCount,
                        installedFileCount = summary.installedFileCount,
                        storageBytes = summary.totalBytes,
                    )
                }
            }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            runCatching {
                Pair(repository.listProfiles(), runCatching { repository.getCurrentUser() }.getOrNull())
            }.fold(
                onSuccess = { (profiles, user) ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            profiles = profiles,
                            user = user,
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = error.message ?: "Unable to load settings.",
                        )
                    }
                },
            )
        }
    }

    fun setTouchControlsEnabled(enabled: Boolean) {
        viewModelScope.launch { controlsRepository.setTouchControlsEnabled(enabled) }
    }

    fun setAutoHideTouchOnController(enabled: Boolean) {
        viewModelScope.launch { controlsRepository.setAutoHideTouchOnController(enabled) }
    }

    fun setRumbleToDeviceEnabled(enabled: Boolean) {
        viewModelScope.launch { controlsRepository.setRumbleToDeviceEnabled(enabled) }
    }
}
