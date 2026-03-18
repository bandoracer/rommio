package io.github.mattsays.rommnative.ui.screen.collection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.mattsays.rommnative.data.repository.RommRepository
import io.github.mattsays.rommnative.model.CollectionKind
import io.github.mattsays.rommnative.model.RomDto
import io.github.mattsays.rommnative.model.RommCollectionDto
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
    val supportedRoms: List<RomDto> = emptyList(),
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
                val (unsupportedRoms, supportedRoms) = roms.partition(repository::isUnsupportedInApp)
                _uiState.update {
                    it.copy(
                        collection = collection,
                        supportedRoms = supportedRoms,
                        unsupportedRoms = unsupportedRoms,
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
            if (repository.currentConnectivityState() != io.github.mattsays.rommnative.model.ConnectivityState.ONLINE) {
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
    return collection != null || supportedRoms.isNotEmpty() || unsupportedRoms.isNotEmpty()
}
