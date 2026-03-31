package io.github.bandoracer.rommio.ui.screen.collections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.bandoracer.rommio.data.repository.RommRepository
import io.github.bandoracer.rommio.model.RommCollectionDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CollectionsUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
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
                        isLoading = if (collections.isNotEmpty()) false else it.isLoading,
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
                    isLoading = it.collections.isEmpty(),
                    isRefreshing = true,
                    errorMessage = null,
                )
            }
            if (repository.currentConnectivityState() != io.github.bandoracer.rommio.model.ConnectivityState.ONLINE) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        errorMessage = if (it.collections.isEmpty()) {
                            "Offline. Collections will appear after this profile syncs once."
                        } else {
                            null
                        },
                    )
                }
                return@launch
            }
            runCatching { repository.refreshCollectionsInBackground() }.fold(
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
                            errorMessage = error.message ?: "Collections are unavailable on this server.",
                        )
                    }
                },
            )
        }
    }
}

private fun RommCollectionDto.cacheKey(): String = "${kind}:${id}"
