package io.github.mattsays.rommnative.ui.screen.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.mattsays.rommnative.data.repository.RommRepository
import io.github.mattsays.rommnative.model.InstalledPlatformSummary
import io.github.mattsays.rommnative.model.PlatformDto
import io.github.mattsays.rommnative.model.RomDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LibraryUiState(
    val isLoading: Boolean = true,
    val supportedPlatforms: List<PlatformDto> = emptyList(),
    val unsupportedPlatforms: List<PlatformDto> = emptyList(),
    val recentInstalled: List<RomDto> = emptyList(),
    val installedPlatformSummaries: List<InstalledPlatformSummary> = emptyList(),
    val errorMessage: String? = null,
)

class LibraryViewModel(
    private val repository: RommRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeCachedPlatforms().collect { platforms ->
                val sortedPlatforms = platforms.sortedBy { platform -> platform.name.lowercase() }
                _uiState.update {
                    it.copy(
                        supportedPlatforms = sortedPlatforms.filter(repository::supportsEmbeddedPlayer),
                        unsupportedPlatforms = sortedPlatforms.filterNot(repository::supportsEmbeddedPlayer),
                        isLoading = it.isLoading && sortedPlatforms.isEmpty(),
                    )
                }
            }
        }
        viewModelScope.launch {
            repository.observeInstalledPlatformSummaries().collect { summaries ->
                _uiState.update { it.copy(installedPlatformSummaries = summaries) }
            }
        }
        viewModelScope.launch {
            repository.observeInstalledLibrary().collect { installed ->
                if (installed.isEmpty()) {
                    _uiState.update { it.copy(recentInstalled = emptyList()) }
                    return@collect
                }
                val roms = installed
                    .take(8)
                    .mapNotNull { record ->
                        runCatching { repository.getRomById(record.romId) }.getOrNull()
                    }
                _uiState.update { it.copy(recentInstalled = roms.distinctBy { rom -> rom.id }) }
            }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            if (repository.currentConnectivityState() != io.github.mattsays.rommnative.model.ConnectivityState.ONLINE) {
                _uiState.update { it.copy(isLoading = false) }
                return@launch
            }
            runCatching { repository.getPlatforms() }.fold(
                onSuccess = { platforms ->
                    val sortedPlatforms = platforms.sortedBy { platform -> platform.name.lowercase() }
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            supportedPlatforms = sortedPlatforms.filter(repository::supportsEmbeddedPlayer),
                            unsupportedPlatforms = sortedPlatforms.filterNot(repository::supportsEmbeddedPlayer),
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = error.message ?: "Unable to load the library.",
                        )
                    }
                },
            )
        }
    }
}
