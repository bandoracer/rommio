package io.github.bandoracer.rommio.ui.screen.library

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.bandoracer.rommio.data.repository.RommRepository
import io.github.bandoracer.rommio.domain.player.EmbeddedSupportTier
import io.github.bandoracer.rommio.model.CollectionKind
import io.github.bandoracer.rommio.model.ConnectivityState
import io.github.bandoracer.rommio.model.InstalledPlatformSummary
import io.github.bandoracer.rommio.model.PlatformDto
import io.github.bandoracer.rommio.model.RomDto
import io.github.bandoracer.rommio.model.RommCollectionDto
import io.github.bandoracer.rommio.ui.component.PlayFormat
import io.github.bandoracer.rommio.ui.component.supportTierLabel
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

data class LibraryPlatform(
    val platform: PlatformDto,
    val tier: EmbeddedSupportTier,
    val installed: InstalledPlatformSummary?,
)

data class LibraryUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val platforms: List<LibraryPlatform> = emptyList(),
    val recentInstalled: List<RomDto> = emptyList(),
    val installedGameCount: Int = 0,
    val collections: List<RommCollectionDto> = emptyList(),
    val collectionPreviewCoverUrls: Map<String, List<String>> = emptyMap(),
    val errorMessage: String? = null,
    /** The cached library, for browsing covers (Aurora). */
    val roms: List<RomDto> = emptyList(),
    /** Games in progress, most recent first (their covers lead the browse row). */
    val inProgress: List<RomDto> = emptyList(),
    /** Games with a copy on this device (for "N here"). */
    val installedRomIds: Set<Int> = emptySet(),
    /** The cached library grouped into eras by release year (Aurora's era ruler). */
    val eras: List<EraGroup> = emptyList(),
    /** A refresh failed while cached content is shown. */
    val staleMessage: String? = null,
) {
    fun installed(platform: LibraryPlatform): Int = platform.installed?.installedGameCount ?: 0

    /** "2,164 games · 22 platforms · 91 on this device". */
    val summary: String
        get() = "${java.text.NumberFormat.getIntegerInstance().format(totalGames)} game${if (totalGames == 1) "" else "s"} · " +
            "${platforms.size} platform${if (platforms.size == 1) "" else "s"} · $installedGameCount on this device"

    val totalGames: Int get() = platforms.sumOf { it.platform.romCount }
    fun platforms(tier: EmbeddedSupportTier?): List<LibraryPlatform> = if (tier == null) platforms else platforms.filter { it.tier == tier }
    fun count(tier: EmbeddedSupportTier): Int = platforms.count { it.tier == tier }
    val hasContent: Boolean get() = platforms.isNotEmpty() || recentInstalled.isNotEmpty()

    /** A tier section of the library: its platforms in order. */
    fun sections(): List<Pair<EmbeddedSupportTier, List<LibraryPlatform>>> =
        listOf(EmbeddedSupportTier.TOUCH_SUPPORTED, EmbeddedSupportTier.CONTROLLER_SUPPORTED, EmbeddedSupportTier.UNSUPPORTED)
            .map { tier -> tier to platforms.filter { it.tier == tier } }
            .filter { it.second.isNotEmpty() }

    /** The collection's cover: its own art, else the first of its games. */
    fun collectionCover(collection: RommCollectionDto): String? =
        collection.pathCoverLarge ?: collection.pathCoverSmall ?: collection.pathCoversLarge.firstOrNull()
            ?: collectionPreviewCoverUrls["${collection.kind}:${collection.id}"]?.firstOrNull()
}

/** What the Aurora browse row draws from: an era, a platform or a tier (or everything). */
data class LibraryBrowse(
    val platformId: Int? = null,
    val tier: EmbeddedSupportTier? = null,
    /** "auto" = open on the era of the game in progress; "" = all years; else an era id. */
    val eraChoice: String = "auto",
)

// ---- The page (built once per data or choice change, off the main thread) ------------------------

/** A platform as the Library draws it. */
@Immutable
data class LibraryPlatformItem(
    val platform: PlatformDto,
    val tier: EmbeddedSupportTier,
    val installed: Int,
    /** Riso's contents number. */
    val number: Int,
    /** "12 games · 3 on this device". */
    val subtitle: String,
    /** Sidebar: "12 games · 3 here". */
    val shortSubtitle: String,
    /** Facet: the count and "N here" / "none here". */
    val countText: String,
    val hereText: String,
    val description: String,
)

@Immutable
data class LibrarySection(val tier: EmbeddedSupportTier, val title: String, val dek: String, val platforms: List<LibraryPlatformItem>)

@Immutable
data class LibraryCollectionItem(
    val collection: RommCollectionDto,
    val coverUrl: String?,
    val countText: String,
    /** Riso: the description, or who made it. */
    val line: String,
)

/** An era on the ruler. */
@Immutable
data class LibraryEraItem(
    val id: String,
    val name: String,
    val short: String,
    val count: Int,
    val countText: String,
    /** Up to three covers (games in progress first): the bar's lights. */
    val leadCovers: List<String?>,
)

/** A cover in the browse row. */
@Immutable
data class BrowseGame(val id: Int, val title: String, val coverUrl: String?, val caption: String)

/** The Library page. */
@Immutable
data class LibraryPage(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
    val staleMessage: String? = null,
    val summary: String = "",
    val platforms: List<LibraryPlatformItem> = emptyList(),
    val sections: List<LibrarySection> = emptyList(),
    val collections: List<LibraryCollectionItem> = emptyList(),
    val collectionSections: List<Pair<String, List<LibraryCollectionItem>>> = emptyList(),
    // Aurora's browse.
    val eras: List<LibraryEraItem> = emptyList(),
    val eraTotal: Int = 1,
    val showsRuler: Boolean = false,
    val selectedEra: String? = null,
    val tier: EmbeddedSupportTier? = null,
    val platformFilter: LibraryPlatformItem? = null,
    val browseTitle: String = "All platforms",
    val browseTitleIsEra: Boolean = false,
    val browseDetail: String? = null,
    val browse: List<BrowseGame> = emptyList(),
    val browseEmpty: String = "",
    val facets: List<LibraryPlatformItem> = emptyList(),
) {
    val hasPlatforms: Boolean get() = platforms.isNotEmpty()

    companion object {
        fun build(state: LibraryUiState, browse: LibraryBrowse, baseUrl: String?): LibraryPage {
            val numbers = state.platforms.map { it.platform.id }
            val items = state.platforms.associate { entry ->
                val installed = state.installed(entry)
                entry.platform.id to LibraryPlatformItem(
                    platform = entry.platform,
                    tier = entry.tier,
                    installed = installed,
                    number = numbers.indexOf(entry.platform.id) + 1,
                    subtitle = "${PlayFormat.games(entry.platform.romCount)} · $installed on this device",
                    shortSubtitle = "${PlayFormat.games(entry.platform.romCount)} · $installed here",
                    countText = java.text.NumberFormat.getIntegerInstance().format(entry.platform.romCount),
                    hereText = if (installed == 0) "none here" else "$installed here",
                    description = "${entry.platform.name}, ${PlayFormat.games(entry.platform.romCount)}, $installed on this device",
                )
            }
            val sections = state.sections().map { (tier, platforms) ->
                LibrarySection(tier, tierTitle(tier), tierDek(tier), platforms.map { items.getValue(it.platform.id) })
            }
            fun collection(item: RommCollectionDto) = LibraryCollectionItem(
                collection = item,
                coverUrl = resolveRemoteAssetUrl(baseUrl, state.collectionCover(item)),
                countText = PlayFormat.games(item.romCount),
                line = item.description.ifBlank { collectionKind(item) },
            )
            val collections = state.collections.map(::collection)
            // Aurora's browse: the era, platform or tier the row draws from.
            val platformFilter = state.platforms.firstOrNull { it.platform.id == browse.platformId }
            val tier = browse.tier
            val showsRuler = LibraryEras.showsRuler(state.eras)
            val eraId = when {
                !showsRuler -> null
                browse.eraChoice == "auto" -> LibraryEras.defaultEra(state.eras, state.inProgress)
                browse.eraChoice.isEmpty() -> null
                else -> browse.eraChoice.takeIf { id -> state.eras.any { it.era.id == id } }
            }
            val era = state.eras.firstOrNull { it.era.id == eraId }
            val pool = browsePool(state, platformFilter, tier, era)
            val inProgressIds = state.inProgress.map { it.id }.toSet()
            val eras = state.eras.map { group ->
                val lead = (group.games.filter { it.id in inProgressIds } + group.games).distinctBy { it.id }.take(3)
                LibraryEraItem(
                    id = group.era.id,
                    name = group.era.name,
                    short = group.short,
                    count = group.games.size,
                    countText = java.text.NumberFormat.getIntegerInstance().format(group.games.size),
                    leadCovers = lead.map { resolveRemoteAssetUrl(baseUrl, it.urlCover) },
                )
            }
            val browseDetail = when {
                era != null -> listOfNotNull(
                    platformFilter?.platform?.slug?.uppercase() ?: tier?.let(::supportTierLabel),
                    PlayFormat.games(pool.size),
                    "${pool.count { it.id in state.installedRomIds }} here",
                ).joinToString(" · ")
                platformFilter != null -> "${PlayFormat.games(platformFilter.platform.romCount)} · ${state.installed(platformFilter)} here"
                else -> null
            }
            return LibraryPage(
                isLoading = state.isLoading,
                isRefreshing = state.isRefreshing,
                errorMessage = state.errorMessage,
                staleMessage = state.staleMessage,
                summary = state.summary,
                platforms = state.platforms.map { items.getValue(it.platform.id) },
                sections = sections,
                collections = collections,
                collectionSections = collectionSections(state.collections).map { (title, list) -> title to list.map(::collection) },
                eras = eras,
                eraTotal = state.eras.sumOf { it.games.size }.coerceAtLeast(1),
                showsRuler = showsRuler,
                selectedEra = eraId,
                tier = tier,
                platformFilter = platformFilter?.let { items.getValue(it.platform.id) },
                browseTitle = era?.era?.name ?: platformFilter?.platform?.name ?: tier?.let(::tierTitle) ?: "All platforms",
                browseTitleIsEra = era != null,
                browseDetail = browseDetail,
                browse = pool.take(18).map { rom ->
                    BrowseGame(
                        id = rom.id,
                        title = rom.displayName,
                        coverUrl = resolveRemoteAssetUrl(baseUrl, rom.urlCover),
                        caption = buildList {
                            add(rom.platformSlug.uppercase())
                            rom.metadata.releaseYear?.let { add(it.toString()) }
                            if (rom.id in inProgressIds) add("In progress")
                        }.joinToString(" · "),
                    )
                },
                browseEmpty = if (era != null && state.roms.isNotEmpty()) "No games here from ${era.short}." else "Covers appear here as your library loads.",
                facets = state.platforms.filter { tier == null || it.tier == tier }.map { items.getValue(it.platform.id) },
            )
        }

        /**
         * The browse pool: filter (era, platform or tier), games in progress first, then the era's games
         * by release year (the era group's order) or, with all years, A–Z. The row shows the first 18.
         */
        internal fun browsePool(state: LibraryUiState, platform: LibraryPlatform?, tier: EmbeddedSupportTier?, era: EraGroup?): List<RomDto> {
            val tierSlugs = tier?.let { t -> state.platforms.filter { it.tier == t }.flatMap { listOf(it.platform.slug, it.platform.fsSlug) }.toSet() }
            val source = era?.games ?: state.roms.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName })
            val pool = source.filter { rom ->
                when {
                    platform != null -> rom.platformId == platform.platform.id || rom.platformSlug == platform.platform.slug
                    tierSlugs != null -> rom.platformSlug in tierSlugs
                    else -> true
                }
            }
            val poolIds = pool.map { it.id }.toSet()
            val progress = state.inProgress.filter { it.id in poolIds }
            val progressIds = progress.map { it.id }.toSet()
            return progress + pool.filter { it.id !in progressIds }
        }

        private fun collectionKind(collection: RommCollectionDto): String = when (collection.kind) {
            CollectionKind.SMART -> "Smart collection"
            CollectionKind.VIRTUAL -> "Made by RomM"
            CollectionKind.REGULAR -> "Made by you"
        }
    }
}

class LibraryViewModel(
    private val repository: RommRepository,
    private val playActivity: io.github.bandoracer.rommio.data.stats.PlayActivityRepository? = null,
    private val saved: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    private val browse = MutableStateFlow(
        LibraryBrowse(
            platformId = saved.get<Int>(PLATFORM),
            tier = saved.get<String>(TIER)?.let { key -> EmbeddedSupportTier.entries.firstOrNull { it.name == key } },
            eraChoice = saved.get<String>(ERA) ?: "auto",
        ),
    )

    /** The page; published only when it changes. */
    val page: StateFlow<LibraryPage> = combine(
        _uiState,
        browse,
        repository.activeProfileFlow().map { it?.baseUrl }.distinctUntilChanged(),
    ) { state, browse, baseUrl -> LibraryPage.build(state, browse, baseUrl) }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, LibraryPage())

    init {
        viewModelScope.launch {
            combine(
                repository.observeCachedPlatforms(),
                repository.observeInstalledPlatformSummaries(),
                repository.observeRecentInstalledRoms(limit = 12),
                repository.observeLibraryStorageSummary(),
            ) { platforms, summaries, recentInstalled, storage ->
                val entries = platforms
                    .sortedBy { it.name.lowercase() }
                    .map { platform ->
                        LibraryPlatform(
                            platform = platform,
                            tier = repository.embeddedSupportTier(platform),
                            installed = summaries.firstOrNull { it.platformSlug == platform.slug || it.platformSlug == platform.fsSlug },
                        )
                    }
                    .sortedWith(compareBy<LibraryPlatform> { it.tier.ordinalForLibrary() }.thenBy { it.platform.name.lowercase() })
                Triple(entries, recentInstalled, storage.installedGameCount)
            }.distinctUntilChanged().collect { (entries, recentInstalled, installedCount) ->
                _uiState.update {
                    it.copy(
                        platforms = entries,
                        recentInstalled = recentInstalled,
                        installedGameCount = installedCount,
                        isLoading = if (entries.isNotEmpty() || recentInstalled.isNotEmpty()) false else it.isLoading,
                    )
                }
            }
        }
        viewModelScope.launch {
            val stats = playActivity?.stats ?: kotlinx.coroutines.flow.flowOf(io.github.bandoracer.rommio.domain.stats.PlayStats())
            combine(repository.observeCachedRoms(), repository.observeCachedHome(), stats, repository.observeInstalledLibrary()) { roms, home, played, installed ->
                val byId = roms.associateBy { it.id }
                val local = played.games.sortedByDescending { it.lastPlayedAtEpochMs }.mapNotNull { byId[it.romId.toInt()] }
                val seen = local.map { it.id }.toMutableSet()
                LibraryGames(roms, local + home.continuePlaying.filter { seen.add(it.id) }, installed.map { it.romId }.toSet(), LibraryEras.group(roms))
            }.flowOn(Dispatchers.Default).distinctUntilChanged().collect { games ->
                _uiState.update { it.copy(roms = games.roms, inProgress = games.inProgress, installedRomIds = games.installed, eras = games.eras) }
            }
        }
        viewModelScope.launch {
            repository.observeCachedCollections().collect { collections ->
                val ordered = collections.sortedWith(compareBy<RommCollectionDto> { it.kind.ordinal }.thenBy { it.name.lowercase() })
                val previews = ordered.take(COLLECTION_ROW_LIMIT).associate { collection ->
                    "${collection.kind}:${collection.id}" to runCatching { repository.getCollectionPreviewCoverUrls(collection) }.getOrDefault(emptyList())
                }
                _uiState.update { it.copy(collections = ordered, collectionPreviewCoverUrls = previews) }
            }
        }
        refresh()
    }

    /** Aurora: tapping a platform facet filters the row to it (again: all platforms). */
    fun togglePlatform(id: Int) = updateBrowse { it.copy(platformId = if (it.platformId == id) null else id) }

    /** Aurora: a tier pill (again, or "All": every tier); a platform outside the tier is dropped. */
    fun selectTier(next: EmbeddedSupportTier?) = updateBrowse { current ->
        val platform = _uiState.value.platforms.firstOrNull { it.platform.id == current.platformId }
        current.copy(
            tier = if (current.tier == next) null else next,
            platformId = if (platform != null && next != null && platform.tier != next) null else current.platformId,
        )
    }

    /** Aurora: an era segment (the selected one again: all years). */
    fun selectEra(id: String, selected: String?) = updateBrowse { it.copy(eraChoice = if (id == selected) "" else id) }

    private fun updateBrowse(transform: (LibraryBrowse) -> LibraryBrowse) {
        val next = transform(browse.value)
        browse.value = next
        saved[PLATFORM] = next.platformId
        saved[TIER] = next.tier?.name
        saved[ERA] = next.eraChoice
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = !it.hasContent, isRefreshing = true, errorMessage = null) }
            if (repository.currentConnectivityState() != ConnectivityState.ONLINE) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        errorMessage = if (it.hasContent) null else "You're offline. Your library appears here after this server syncs once.",
                    )
                }
                return@launch
            }
            runCatching {
                repository.refreshPlatformsInBackground()
                repository.refreshCollectionsInBackground()
            }.fold(
                onSuccess = { _uiState.update { it.copy(isLoading = false, isRefreshing = false, errorMessage = null, staleMessage = null) } },
                onFailure = { error ->
                    _uiState.update {
                        val message = error.message ?: "Couldn't load the library."
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            errorMessage = if (it.hasContent) null else message,
                            staleMessage = if (it.hasContent) message else null,
                        )
                    }
                },
            )
        }
    }

    companion object {
        const val COLLECTION_ROW_LIMIT = 8
        private const val PLATFORM = "library.platform"
        private const val TIER = "library.tier"
        private const val ERA = "library.era"
    }
}

private fun EmbeddedSupportTier.ordinalForLibrary(): Int = when (this) {
    EmbeddedSupportTier.TOUCH_SUPPORTED -> 0
    EmbeddedSupportTier.CONTROLLER_SUPPORTED -> 1
    EmbeddedSupportTier.UNSUPPORTED -> 2
}

private data class LibraryGames(val roms: List<RomDto>, val inProgress: List<RomDto>, val installed: Set<Int>, val eras: List<EraGroup>)
