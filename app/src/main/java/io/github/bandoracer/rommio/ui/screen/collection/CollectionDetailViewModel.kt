package io.github.bandoracer.rommio.ui.screen.collection

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.bandoracer.rommio.data.repository.RommRepository
import io.github.bandoracer.rommio.model.CollectionKind
import io.github.bandoracer.rommio.model.RomDto
import io.github.bandoracer.rommio.model.RommCollectionDto
import io.github.bandoracer.rommio.ui.component.PlayFormat
import io.github.bandoracer.rommio.util.resolveRemoteAssetUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CollectionDetailUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val collection: RommCollectionDto? = null,
    /** The collection's games, A–Z. */
    val roms: List<RomDto> = emptyList(),
    val errorMessage: String? = null,
)

/** A game on a shelf (collection): title, cover and "Platform · year". */
@Immutable
data class ShelfGame(val id: Int, val title: String, val coverUrl: String?, val detail: String)

/** The Collection page, built once per data change. */
@Immutable
data class CollectionPage(
    val name: String = "",
    val countText: String = PlayFormat.games(0),
    val description: String? = null,
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
    val games: List<ShelfGame> = emptyList(),
    /** The first game lights the page (Aurora). */
    val leadCoverUrl: String? = null,
) {
    companion object {
        fun build(state: CollectionDetailUiState, routeName: String, baseUrl: String?): CollectionPage {
            val games = state.roms.map { rom ->
                ShelfGame(
                    id = rom.id,
                    title = rom.displayName,
                    coverUrl = resolveRemoteAssetUrl(baseUrl, rom.urlCover),
                    detail = listOfNotNull(rom.platformName, rom.metadata.releaseYear?.toString()).joinToString(" · "),
                )
            }
            return CollectionPage(
                name = state.collection?.name ?: routeName,
                countText = PlayFormat.games(maxOf(state.collection?.romCount ?: 0, games.size)),
                description = state.collection?.description?.takeIf { it.isNotBlank() },
                isLoading = state.isLoading,
                isRefreshing = state.isRefreshing,
                errorMessage = state.errorMessage,
                games = games,
                leadCoverUrl = games.firstOrNull()?.coverUrl,
            )
        }
    }
}

class CollectionDetailViewModel(
    private val repository: RommRepository,
    private val kind: String,
    private val collectionId: String,
    /** The name from the route, until the collection is read. */
    private val routeName: String = "",
) : ViewModel() {
    private val normalizedKind = runCatching { CollectionKind.valueOf(kind.uppercase()) }.getOrDefault(CollectionKind.REGULAR)
    private val _uiState = MutableStateFlow(CollectionDetailUiState())
    val uiState: StateFlow<CollectionDetailUiState> = _uiState.asStateFlow()

    /** The page; published only when it changes. */
    val page: StateFlow<CollectionPage> = combine(
        _uiState,
        repository.activeProfileFlow().map { it?.baseUrl }.distinctUntilChanged(),
    ) { state, baseUrl -> CollectionPage.build(state, routeName, baseUrl) }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, CollectionPage(name = routeName))

    init {
        viewModelScope.launch {
            combine(
                repository.observeCachedCollection(normalizedKind, collectionId),
                repository.observeCachedCollectionRoms(normalizedKind, collectionId),
            ) { collection, roms ->
                collection to roms.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName })
            }.distinctUntilChanged().collect { (collection, roms) ->
                _uiState.update {
                    it.copy(
                        collection = collection,
                        roms = roms,
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

private fun CollectionDetailUiState.hasContent(): Boolean = collection != null || roms.isNotEmpty()
