package io.github.bandoracer.rommio.ui.screen.collection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.bandoracer.rommio.data.repository.RommRepository
import io.github.bandoracer.rommio.domain.player.EmbeddedSupportTier
import io.github.bandoracer.rommio.model.CollectionKind
import io.github.bandoracer.rommio.model.RomDto
import io.github.bandoracer.rommio.model.RommCollectionDto
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CollectionDetailUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val collection: RommCollectionDto? = null,
    val touchSupportedRoms: List<RomDto> = emptyList(),
    val controllerSupportedRoms: List<RomDto> = emptyList(),
    val unsupportedRoms: List<RomDto> = emptyList(),
    val errorMessage: String? = null,
)

class CollectionDetailViewModel(
    private val repository: RommRepository,
    private val kind: String,
    private val collectionId: String,
) : ViewModel() {
    private val normalizedKind = runCatching { CollectionKind.valueOf(kind.uppercase()) }.getOrDefault(CollectionKind.REGULAR)
    private val _uiState = MutableStateFlow(CollectionDetailUiState())
    val uiState: StateFlow<CollectionDetailUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                repository.observeCachedCollection(normalizedKind, collectionId),
                repository.observeCachedCollectionRoms(normalizedKind, collectionId),
            ) { collection, roms ->
                collection to roms
            }.collect { (collection, roms) ->
                _uiState.update {
                    it.copy(
                        collection = collection,
                        touchSupportedRoms = roms.filter { rom ->
                            repository.embeddedSupportTier(rom) == EmbeddedSupportTier.TOUCH_SUPPORTED
                        },
                        controllerSupportedRoms = roms.filter { rom ->
                            repository.embeddedSupportTier(rom) == EmbeddedSupportTier.CONTROLLER_SUPPORTED
                        },
                        unsupportedRoms = roms.filter { rom ->
                            repository.embeddedSupportTier(rom) == EmbeddedSupportTier.UNSUPPORTED
                        },
                        isLoading = if (collection != null || roms.isNotEmpty()) false else it.isLoading,
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
            if (repository.currentConnectivityState() != io.github.bandoracer.rommio.model.ConnectivityState.ONLINE) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        errorMessage = if (it.hasContent()) null else "Offline. This collection will appear after the profile sync completes.",
                    )
                }
                return@launch
            }
            runCatching { repository.refreshCollectionInBackground(normalizedKind, collectionId) }.fold(
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
                            errorMessage = error.message ?: "Unable to load this collection.",
                        )
                    }
                },
            )
        }
    }
}

private fun CollectionDetailUiState.hasContent(): Boolean {
    return collection != null ||
        touchSupportedRoms.isNotEmpty() ||
        controllerSupportedRoms.isNotEmpty() ||
        unsupportedRoms.isNotEmpty()
}
