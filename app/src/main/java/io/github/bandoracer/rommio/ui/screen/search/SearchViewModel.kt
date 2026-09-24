package io.github.bandoracer.rommio.ui.screen.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.bandoracer.rommio.data.repository.RommRepository
import io.github.bandoracer.rommio.data.stats.PlayActivityRepository
import io.github.bandoracer.rommio.domain.player.EmbeddedSupportTier
import io.github.bandoracer.rommio.domain.search.LibrarySearch
import io.github.bandoracer.rommio.model.PlatformDto
import io.github.bandoracer.rommio.model.RomDto
import io.github.bandoracer.rommio.model.RommCollectionDto
import io.github.bandoracer.rommio.ui.screen.GameIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchGameResult(
    val rom: RomDto,
    val lastPlayedAtEpochMs: Long?,
    val installed: Boolean,
    val supportTier: EmbeddedSupportTier,
    val installedFileId: Int? = null,
)

data class SearchUiState(
    val query: String = "",
    val games: List<SearchGameResult> = emptyList(),
    val platforms: List<PlatformDto> = emptyList(),
    val collections: List<RommCollectionDto> = emptyList(),
    val recentlyPlayed: List<SearchGameResult> = emptyList(),
    val catalogueSize: Int = 0,
    /** Every platform, for browsing before typing (Riso). */
    val allPlatforms: List<PlatformDto> = emptyList(),
) {
    val hasQuery: Boolean get() = query.isNotBlank()
    val hasResults: Boolean get() = games.isNotEmpty() || platforms.isNotEmpty() || collections.isNotEmpty()
}

/** Searches the cached library (works offline): best match first, then recently played. */
class SearchViewModel(
    private val repository: RommRepository,
    private val playActivity: PlayActivityRepository,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val catalogue = combine(
                repository.observeCachedRoms(),
                repository.observeInstalledLibrary(),
                repository.observeCachedPlatforms(),
                repository.observeCachedCollections(),
            ) { roms, installed, platforms, collections -> Catalogue(roms, GameIndex(roms, installed), platforms, collections) }
            combine(query, catalogue, playActivity.stats) { text, data, stats ->
                val lastPlayed = stats.lastPlayedByRom
                fun result(rom: RomDto) = SearchGameResult(
                    rom = rom,
                    lastPlayedAtEpochMs = lastPlayed[rom.id.toLong()],
                    installed = data.index.installedFileId(rom.id) != null,
                    supportTier = repository.embeddedSupportTier(rom),
                    installedFileId = data.index.installedFileId(rom.id),
                )
                val recentlyPlayed = stats.games
                    .sortedByDescending { it.lastPlayedAtEpochMs }
                    .mapNotNull { data.index[it.romId.toInt()] }
                    .take(RECENT_LIMIT)
                    .map(::result)
                if (text.isBlank()) {
                    SearchUiState(query = text, recentlyPlayed = recentlyPlayed, catalogueSize = data.roms.size, allPlatforms = data.platforms)
                } else {
                    SearchUiState(
                        query = text,
                        games = LibrarySearch.rank(
                            query = text,
                            candidates = data.roms.map { LibrarySearch.Candidate(it, it.displayName) },
                            lastPlayed = { lastPlayed[it.id.toLong()] },
                            limit = GAME_LIMIT,
                        ).map { result(it.item) },
                        platforms = LibrarySearch.rank(
                            query = text,
                            candidates = data.platforms.map { LibrarySearch.Candidate(it, it.name) },
                            lastPlayed = { null },
                            limit = GROUP_LIMIT,
                        ).map { it.item },
                        collections = LibrarySearch.rank(
                            query = text,
                            candidates = data.collections.map { LibrarySearch.Candidate(it, it.name) },
                            lastPlayed = { null },
                            limit = GROUP_LIMIT,
                        ).map { it.item },
                        recentlyPlayed = recentlyPlayed,
                        catalogueSize = data.roms.size,
                        allPlatforms = data.platforms,
                    )
                }
            }.flowOn(Dispatchers.Default).collect { next -> _uiState.update { next.copy(query = query.value) } }
        }
        refreshActivity()
    }

    fun setQuery(value: String) {
        query.value = value
        _uiState.update { it.copy(query = value) }
    }

    fun refreshActivity() {
        viewModelScope.launch { playActivity.refresh() }
    }

    private data class Catalogue(
        val roms: List<RomDto>,
        val index: GameIndex,
        val platforms: List<PlatformDto>,
        val collections: List<RommCollectionDto>,
    )

    companion object {
        const val GAME_LIMIT = 40
        const val GROUP_LIMIT = 6
        const val RECENT_LIMIT = 8
    }
}
