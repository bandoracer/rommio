package io.github.bandoracer.rommio.ui.screen.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.bandoracer.rommio.data.repository.CachedHomeSnapshot
import io.github.bandoracer.rommio.data.repository.RommRepository
import io.github.bandoracer.rommio.domain.player.EmbeddedSupportTier
import io.github.bandoracer.rommio.model.DownloadRecord
import io.github.bandoracer.rommio.model.DownloadStatus
import io.github.bandoracer.rommio.model.LibraryStorageSummary
import io.github.bandoracer.rommio.model.OfflineState
import io.github.bandoracer.rommio.model.RomDto
import io.github.bandoracer.rommio.model.RommCollectionDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val continuePlaying: List<RomDto> = emptyList(),
    val continuePlayingSupport: Map<Int, EmbeddedSupportTier> = emptyMap(),
    val recentRoms: List<RomDto> = emptyList(),
    val recentRomsSupport: Map<Int, EmbeddedSupportTier> = emptyMap(),
    val featuredCollections: List<RommCollectionDto> = emptyList(),
    val collectionPreviewCoverUrls: Map<String, List<String>> = emptyMap(),
    val storageSummary: LibraryStorageSummary = LibraryStorageSummary(),
    val activeDownloads: List<DownloadRecord> = emptyList(),
    val offlineState: OfflineState = OfflineState(),
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
            repository.observeCachedHome().collect { snapshot ->
                _uiState.update {
                    it.copy(
                        continuePlaying = snapshot.continuePlaying,
                        continuePlayingSupport = snapshot.continuePlaying.associate { rom ->
                            rom.id to repository.embeddedSupportTier(rom)
                        },
                        recentRoms = snapshot.recentRoms,
                        recentRomsSupport = snapshot.recentRoms.associate { rom ->
                            rom.id to repository.embeddedSupportTier(rom)
                        },
                        featuredCollections = snapshot.featuredCollections,
                        collectionPreviewCoverUrls = snapshot.collectionPreviewCoverUrls,
                        isLoading = if (snapshot.hasContent()) false else it.isLoading,
                    )
                }
            }
        }
        viewModelScope.launch {
            repository.observeOfflineState().collect { offlineState ->
                _uiState.update {
                    it.copy(
                        offlineState = offlineState,
                        isRefreshing = offlineState.isRefreshing,
                        isLoading = if (!it.hasContent() && !offlineState.isRefreshing) false else it.isLoading,
                    )
                }
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
            _uiState.update {
                it.copy(
                    isLoading = !it.hasContent(),
                    isRefreshing = true,
                    errorMessage = null,
                )
            }
            if (!stateIsOnline()) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        errorMessage = if (it.hasContent()) null else "Offline. Home content will appear after this profile syncs once.",
                    )
                }
                return@launch
            }
            runCatching { repository.refreshActiveProfileCache(force = false) }.fold(
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
                            errorMessage = if (it.hasContent()) {
                                error.message ?: "Home content is stale until the next successful refresh."
                            } else {
                                error.message ?: "Unable to load the home dashboard."
                            },
                        )
                    }
                },
            )
        }
    }

    private fun stateIsOnline(): Boolean {
        return repository.currentConnectivityState() == io.github.bandoracer.rommio.model.ConnectivityState.ONLINE
    }
}

private fun CachedHomeSnapshot.hasContent(): Boolean {
    return continuePlaying.isNotEmpty() || recentRoms.isNotEmpty() || featuredCollections.isNotEmpty()
}

private fun HomeUiState.hasContent(): Boolean {
    return continuePlaying.isNotEmpty() || recentRoms.isNotEmpty() || featuredCollections.isNotEmpty()
}
