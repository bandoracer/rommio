package io.github.mattsays.rommnative.ui.screen.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.mattsays.rommnative.data.repository.RommRepository
import io.github.mattsays.rommnative.domain.player.EmbeddedSupportTier
import io.github.mattsays.rommnative.model.InstalledPlatformSummary
import io.github.mattsays.rommnative.model.PlatformDto
import io.github.mattsays.rommnative.model.RomDto
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LibraryUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val touchSupportedPlatforms: List<PlatformDto> = emptyList(),
    val controllerSupportedPlatforms: List<PlatformDto> = emptyList(),
    val unsupportedPlatforms: List<PlatformDto> = emptyList(),
    val recentInstalled: List<RomDto> = emptyList(),
    val recentInstalledSupport: Map<Int, EmbeddedSupportTier> = emptyMap(),
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
            combine(
                repository.observeCachedPlatforms(),
                repository.observeInstalledPlatformSummaries(),
                repository.observeRecentInstalledRoms(),
            ) { platforms, summaries, recentInstalled ->
                val sortedPlatforms = platforms.sortedBy { platform -> platform.name.lowercase() }
                _uiState.update {
                    it.copy(
                        touchSupportedPlatforms = sortedPlatforms.filter { platform ->
                            repository.embeddedSupportTier(platform) == EmbeddedSupportTier.TOUCH_SUPPORTED
                        },
                        controllerSupportedPlatforms = sortedPlatforms.filter { platform ->
                            repository.embeddedSupportTier(platform) == EmbeddedSupportTier.CONTROLLER_SUPPORTED
                        },
                        unsupportedPlatforms = sortedPlatforms.filter { platform ->
                            repository.embeddedSupportTier(platform) == EmbeddedSupportTier.UNSUPPORTED
                        },
                        recentInstalled = recentInstalled,
                        recentInstalledSupport = recentInstalled.associate { rom ->
                            rom.id to repository.embeddedSupportTier(rom)
                        },
                        installedPlatformSummaries = summaries,
                        isLoading = if (sortedPlatforms.isNotEmpty() || recentInstalled.isNotEmpty()) false else it.isLoading,
                    )
                }
            }.collect {}
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = !it.hasContent(),
                    isRefreshing = true,
                    errorMessage = null,
                )
            }
            if (repository.currentConnectivityState() != io.github.mattsays.rommnative.model.ConnectivityState.ONLINE) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        errorMessage = if (it.hasContent()) null else "Offline. Library content will appear after this profile syncs once.",
                    )
                }
                return@launch
            }
            runCatching { repository.refreshPlatformsInBackground() }.fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            errorMessage = null,
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            errorMessage = error.message ?: "Unable to load the library.",
                        )
                    }
                },
            )
        }
    }
}

private fun LibraryUiState.hasContent(): Boolean {
    return touchSupportedPlatforms.isNotEmpty() ||
        controllerSupportedPlatforms.isNotEmpty() ||
        unsupportedPlatforms.isNotEmpty() ||
        recentInstalled.isNotEmpty()
}
