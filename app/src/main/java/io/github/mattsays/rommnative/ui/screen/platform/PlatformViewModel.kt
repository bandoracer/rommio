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
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            runCatching { repository.getRomsByPlatform(platformId) }.fold(
                onSuccess = { roms ->
                    val (unsupportedRoms, supportedRoms) = roms.partition(repository::isUnsupportedInApp)
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            supportedRoms = supportedRoms,
                            unsupportedRoms = unsupportedRoms,
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = error.message ?: "Unable to load platform ROMs.",
                        )
                    }
                },
            )
        }
    }
}
