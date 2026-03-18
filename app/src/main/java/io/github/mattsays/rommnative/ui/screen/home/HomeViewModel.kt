package io.github.mattsays.rommnative.ui.screen.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.mattsays.rommnative.data.repository.RommRepository
import io.github.mattsays.rommnative.model.PlatformDto
import io.github.mattsays.rommnative.model.RomDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val isLoading: Boolean = true,
    val platforms: List<PlatformDto> = emptyList(),
    val recentRoms: List<RomDto> = emptyList(),
    val errorMessage: String? = null,
)

class HomeViewModel(
    private val repository: RommRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            runCatching {
                repository.getPlatforms() to repository.getRecentlyAdded()
            }.fold(
                onSuccess = { (platforms, recentRoms) ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            platforms = platforms.sortedBy { platform -> platform.name.lowercase() },
                            recentRoms = recentRoms,
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = error.message ?: "Unable to load the RomM library.",
                        )
                    }
                },
            )
        }
    }
}
