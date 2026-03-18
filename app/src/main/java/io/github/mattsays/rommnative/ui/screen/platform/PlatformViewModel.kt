package io.github.mattsays.rommnative.ui.screen.platform

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.mattsays.rommnative.data.repository.RommRepository
import io.github.mattsays.rommnative.model.RomDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PlatformUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val supportedRoms: List<RomDto> = emptyList(),
    val unsupportedRoms: List<RomDto> = emptyList(),
    val errorMessage: String? = null,
)

class PlatformViewModel(
    private val repository: RommRepository,
    private val platformId: Int,
) : ViewModel() {
    private val _uiState = MutableStateFlow(PlatformUiState())
    val uiState: StateFlow<PlatformUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeCachedPlatformRoms(platformId).collect { roms ->
                val (unsupportedRoms, supportedRoms) = roms.partition(repository::isUnsupportedInApp)
                _uiState.update {
                    it.copy(
                        supportedRoms = supportedRoms,
                        unsupportedRoms = unsupportedRoms,
                        isLoading = if (roms.isNotEmpty()) false else it.isLoading,
                    )
                }
            }
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
                        errorMessage = if (it.hasContent()) null else "Offline. This platform will appear after the profile sync completes.",
                    )
                }
                return@launch
            }
            runCatching { repository.refreshPlatformInBackground(platformId) }.fold(
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
                            errorMessage = error.message ?: "Unable to load platform ROMs.",
                        )
                    }
                },
            )
        }
    }
}

private fun PlatformUiState.hasContent(): Boolean {
    return supportedRoms.isNotEmpty() || unsupportedRoms.isNotEmpty()
}
