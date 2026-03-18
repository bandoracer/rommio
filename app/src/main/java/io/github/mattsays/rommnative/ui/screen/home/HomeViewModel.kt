package io.github.mattsays.rommnative.ui.screen.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.mattsays.rommnative.data.repository.RommRepository
import io.github.mattsays.rommnative.model.DownloadRecord
import io.github.mattsays.rommnative.model.DownloadStatus
import io.github.mattsays.rommnative.model.LibraryStorageSummary
import io.github.mattsays.rommnative.model.RomDto
import io.github.mattsays.rommnative.model.RommCollectionDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val isLoading: Boolean = true,
    val continuePlaying: List<RomDto> = emptyList(),
    val recentRoms: List<RomDto> = emptyList(),
    val featuredCollections: List<RommCollectionDto> = emptyList(),
    val collectionPreviewCoverUrls: Map<String, List<String>> = emptyMap(),
    val storageSummary: LibraryStorageSummary = LibraryStorageSummary(),
    val activeDownloads: List<DownloadRecord> = emptyList(),
    val errorMessage: String? = null,
)

class HomeViewModel(
    private val repository: RommRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeLibraryStorageSummary().collect { summary ->
                _uiState.update { it.copy(storageSummary = summary) }
            }
        }
        viewModelScope.launch {
            repository.observeDownloadHistory().collect { downloads ->
                _uiState.update {
                    it.copy(
                        activeDownloads = downloads.filter { record ->
                            record.status == DownloadStatus.RUNNING || record.status == DownloadStatus.QUEUED || record.status == DownloadStatus.FAILED
                        },
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
                Triple(
                    repository.getContinuePlaying(),
                    repository.getRecentlyAdded(),
                    repository.getCollections(),
                )
            }.fold(
                onSuccess = { (continuePlaying, recentRoms, collections) ->
                    val featuredCollections = collections.take(8)
                    val previewCovers = featuredCollections.associate { collection ->
                        "${collection.kind}:${collection.id}" to repository.getCollectionPreviewCoverUrls(collection)
                    }
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            continuePlaying = continuePlaying.take(10),
                            recentRoms = recentRoms.take(12),
                            featuredCollections = featuredCollections,
                            collectionPreviewCoverUrls = previewCovers,
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = error.message ?: "Unable to load the home dashboard.",
                        )
                    }
                },
            )
        }
    }
}
