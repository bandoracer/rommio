package io.github.mattsays.rommnative.ui.screen.collections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.mattsays.rommnative.data.repository.RommRepository
import io.github.mattsays.rommnative.model.RommCollectionDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CollectionsUiState(
    val isLoading: Boolean = true,
    val collections: List<RommCollectionDto> = emptyList(),
    val collectionPreviewCoverUrls: Map<String, List<String>> = emptyMap(),
    val errorMessage: String? = null,
)

class CollectionsViewModel(
    private val repository: RommRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(CollectionsUiState())
    val uiState: StateFlow<CollectionsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeCachedCollections().collect { collections ->
                val previewCovers = collections.associate { collection ->
                    collection.cacheKey() to repository.getCollectionPreviewCoverUrls(collection)
                }
                _uiState.update {
                    it.copy(
                        collections = collections,
                        collectionPreviewCoverUrls = previewCovers,
                        isLoading = it.isLoading && collections.isEmpty(),
                    )
                }
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
            runCatching { repository.getCollections() }.fold(
                onSuccess = { collections ->
                    val previewCovers = collections.associate { collection ->
                        "${collection.kind}:${collection.id}" to repository.getCollectionPreviewCoverUrls(collection)
                    }
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            collections = collections,
                            collectionPreviewCoverUrls = previewCovers,
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = error.message ?: "Collections are unavailable on this server.",
                        )
                    }
                },
            )
        }
    }
}

private fun RommCollectionDto.cacheKey(): String = "${kind}:${id}"
