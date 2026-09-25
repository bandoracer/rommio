package io.github.bandoracer.rommio.ui.screen.search

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.bandoracer.rommio.data.repository.RommRepository
import io.github.bandoracer.rommio.data.stats.PlayActivityRepository
import io.github.bandoracer.rommio.domain.player.EmbeddedSupportTier
import io.github.bandoracer.rommio.domain.search.LibrarySearch
import io.github.bandoracer.rommio.model.PlatformDto
import io.github.bandoracer.rommio.model.RomDto
import io.github.bandoracer.rommio.model.RommCollectionDto
import io.github.bandoracer.rommio.ui.component.PlayFormat
import io.github.bandoracer.rommio.ui.screen.GameIndex
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

/** A platform or collection that matches the query (related results). */
data class SearchRelated(val platform: PlatformDto? = null, val collection: RommCollectionDto? = null) {
    val title: String get() = platform?.name ?: collection?.name.orEmpty()
    val count: Int get() = platform?.romCount ?: collection?.romCount ?: 0
    val kindTitle: String get() = if (platform != null) "Platform" else "Collection"
    /** `p<id>` or `c<id>`, as in the `search.related.*` identifiers. */
    val id: String get() = platform?.let { "p${it.id}" } ?: "c${collection?.id}"
}

/** One game in Search as both layouts draw it (strings formatted once per result change). */
@Immutable
data class SearchHit(
    val id: Int,
    val title: String,
    val coverUrl: String?,
    val platformSlug: String,
    /** "Super Nintendo · 1994". */
    val platformLine: String,
    /** "SNES · 1994 · on this device · played 2 hours ago". */
    val detail: String,
    /** "Played 2 hours ago" (best match), when played here. */
    val playedLine: String?,
    /** Among the games played recently: it can be resumed from the result. */
    val inProgress: Boolean,
    /** The installed file to start, when it can be played on this device. */
    val playableFileId: Int?,
)

/** A related platform or collection row. */
@Immutable
data class SearchRelatedRow(val related: SearchRelated, val id: String, val title: String, val kindTitle: String, val countText: String, val gamesText: String)

/** The Search page for a query, built off the main thread once per query or data change. */
@Immutable
data class SearchPage(
    val query: String = "",
    val hasQuery: Boolean = false,
    val catalogueSize: Int = 0,
    val games: List<SearchHit> = emptyList(),
    val related: List<SearchRelatedRow> = emptyList(),
    val recentlyPlayed: List<SearchHit> = emptyList(),
    /** Every platform (Riso's browse list before typing). */
    val allPlatforms: List<SearchRelatedRow> = emptyList(),
) {
    val bestMatch: SearchHit? get() = games.firstOrNull()

    companion object {
        fun build(state: SearchUiState, baseUrl: String?): SearchPage {
            val recentIds = state.recentlyPlayed.map { it.rom.id }.toSet()
            fun hit(result: SearchGameResult): SearchHit = SearchHit(
                id = result.rom.id,
                title = result.rom.displayName,
                coverUrl = resolveRemoteAssetUrl(baseUrl, result.rom.urlCover),
                platformSlug = result.rom.platformSlug,
                platformLine = listOfNotNull(result.rom.platformName.takeIf { it.isNotBlank() }, result.rom.metadata.releaseYear?.toString())
                    .joinToString(" · "),
                detail = listOfNotNull(
                    result.rom.platformSlug.uppercase(),
                    result.rom.metadata.releaseYear?.toString(),
                    "on this device".takeIf { result.installed },
                    result.lastPlayedAtEpochMs?.let { "played ${PlayFormat.relative(it)}" },
                ).joinToString(" · "),
                playedLine = result.lastPlayedAtEpochMs?.let { "Played ${PlayFormat.relative(it)}" },
                inProgress = result.rom.id in recentIds,
                playableFileId = result.installedFileId?.takeIf { result.supportTier != EmbeddedSupportTier.UNSUPPORTED },
            )
            fun related(item: SearchRelated) = SearchRelatedRow(
                related = item,
                id = item.id,
                title = item.title,
                kindTitle = item.kindTitle,
                countText = java.text.NumberFormat.getIntegerInstance().format(item.count),
                gamesText = PlayFormat.games(item.count),
            )
            return SearchPage(
                query = state.query.trim(),
                hasQuery = state.hasQuery,
                catalogueSize = state.catalogueSize,
                games = state.games.map(::hit),
                related = (state.platforms.map { SearchRelated(platform = it) } + state.collections.map { SearchRelated(collection = it) }).map(::related),
                recentlyPlayed = state.recentlyPlayed.map(::hit),
                allPlatforms = state.allPlatforms.map { related(SearchRelated(platform = it)) },
            )
        }
    }
}

/** Searches the cached library (works offline): best match first, then recently played. */
class SearchViewModel(
    private val repository: RommRepository,
    private val playActivity: PlayActivityRepository,
) : ViewModel() {
    private val _query = MutableStateFlow("")
    /** The field's text, published at once (results follow off the main thread). */
    val query: StateFlow<String> = _query.asStateFlow()
    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    /** The results page; published only when it changes. */
    val page: StateFlow<SearchPage> = combine(
        _uiState,
        repository.activeProfileFlow().map { it?.baseUrl }.distinctUntilChanged(),
    ) { state, baseUrl -> SearchPage.build(state, baseUrl) }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, SearchPage())

    init {
        viewModelScope.launch {
            val catalogue = combine(
                repository.observeCachedRoms(),
                repository.observeInstalledLibrary(),
                repository.observeCachedPlatforms(),
                repository.observeCachedCollections(),
            ) { roms, installed, platforms, collections -> Catalogue(roms, GameIndex(roms, installed), platforms, collections) }
            combine(_query, catalogue, playActivity.stats) { text, data, stats ->
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
            }.flowOn(Dispatchers.Default).collect { next -> _uiState.update { next.copy(query = _query.value) } }
        }
        refreshActivity()
    }

    fun setQuery(value: String) {
        _query.value = value
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
