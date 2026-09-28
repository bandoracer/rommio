package io.github.bandoracer.rommio.ui.screen.library

import android.content.SharedPreferences
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.bandoracer.rommio.data.playdata.LocalPlayState
import io.github.bandoracer.rommio.data.playdata.SavedProgressRepository
import io.github.bandoracer.rommio.data.repository.RommRepository
import io.github.bandoracer.rommio.domain.player.EmbeddedSupportTier
import io.github.bandoracer.rommio.domain.playdata.SaveEntry
import io.github.bandoracer.rommio.domain.stats.PlayStats
import io.github.bandoracer.rommio.model.CollectionKind
import io.github.bandoracer.rommio.model.ConnectivityState
import io.github.bandoracer.rommio.model.InstalledPlatformSummary
import io.github.bandoracer.rommio.model.PlatformDto
import io.github.bandoracer.rommio.model.RomDto
import io.github.bandoracer.rommio.model.RommCollectionDto
import io.github.bandoracer.rommio.ui.component.Fmt
import io.github.bandoracer.rommio.ui.component.PlayFormat
import io.github.bandoracer.rommio.ui.screen.play.inPlay
import io.github.bandoracer.rommio.util.resolveRemoteAssetUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
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
    /** The cached library, for the wall of covers (Aurora). */
    val roms: List<RomDto> = emptyList(),
    /** Games in play, most recent first (their covers carry a lit ring; the first lights the page). */
    val inProgress: List<RomDto> = emptyList(),
    /** Games with a copy on this device (a check on their covers, "N here"). */
    val installedRomIds: Set<Int> = emptySet(),
    /** The cached library grouped into eras by release year (the wall's Year lens). */
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

/** How the Aurora wall is ordered and how dense it is (kept on this device). */
data class LibraryWallChoice(val lens: LibraryLens = LibraryLens.CONSOLE, val columns: Int = 3)

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

/** A cover on the Aurora wall. */
@Immutable
data class LibraryWallCover(
    val id: Int,
    /** The title within its group (a series drops its own name). */
    val title: String,
    val coverUrl: String?,
    val inProgress: Boolean,
    val installed: Boolean,
    val browseOnly: Boolean,
    /** The quick look's line: "Super Nintendo · 1994". */
    val caption: String,
    /** Its full name and states, for accessibility. */
    val description: String,
)

/** A group of the Aurora wall: the tile that names it, then its covers. */
@Immutable
data class LibraryWallGroup(
    val id: String,
    val title: String,
    /** The index rail's label. */
    val index: String,
    val subtitle: String?,
    /** "11 games" (a console's count is the server's). */
    val countText: String,
    val installed: Int,
    /** A console's group opens its page. */
    val platform: PlatformDto?,
    /** "11 games · 3 on this device". */
    val detail: String,
    /** The group's first cover, blurred into its tile. */
    val tileArtUrl: String?,
    val covers: List<LibraryWallCover>,
)

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
    // Aurora's wall.
    val lens: LibraryLens = LibraryLens.CONSOLE,
    val columns: Int = 3,
    val wall: List<LibraryWallGroup> = emptyList(),
    /** The page's light: the game you're playing, else the library's first cover. */
    val lightUrl: String? = null,
) {
    val hasPlatforms: Boolean get() = platforms.isNotEmpty()
    val isEmpty: Boolean get() = platforms.isEmpty() && collections.isEmpty()

    companion object {
        fun build(state: LibraryUiState, choice: LibraryWallChoice, baseUrl: String?): LibraryPage {
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
            fun cover(rom: RomDto) = resolveRemoteAssetUrl(baseUrl, rom.urlCover)
            val inProgress = state.inProgress.map { it.id }.toSet()
            val browseOnly = state.platforms.filter { it.tier == EmbeddedSupportTier.UNSUPPORTED }.map { it.platform.slug }.toSet()
            val wall = LibraryWall.sections(state.roms, state.platforms.map { it.platform }, state.installedRomIds, state.eras, choice.lens).map { section ->
                LibraryWallGroup(
                    id = section.id,
                    title = section.title,
                    index = section.index,
                    subtitle = section.subtitle,
                    countText = PlayFormat.games(section.platform?.romCount ?: section.games.size),
                    installed = section.installed,
                    platform = section.platform,
                    detail = section.detail,
                    tileArtUrl = section.games.firstOrNull()?.let(::cover),
                    covers = section.games.map { rom ->
                        val playing = rom.id in inProgress
                        val here = rom.id in state.installedRomIds
                        val browse = rom.platformSlug in browseOnly
                        val caption = Fmt.join(rom.platformName.takeIf { it.isNotBlank() }, rom.metadata.releaseYear?.toString())
                        LibraryWallCover(
                            id = rom.id,
                            title = section.titleOf(rom),
                            coverUrl = cover(rom),
                            inProgress = playing,
                            installed = here,
                            browseOnly = browse,
                            caption = caption,
                            description = listOfNotNull(
                                rom.displayName,
                                caption.takeIf { it.isNotEmpty() },
                                "In progress".takeIf { playing },
                                "On this device".takeIf { here },
                                "Browse only".takeIf { browse },
                            ).joinToString(", "),
                        )
                    },
                )
            }
            val byId = state.roms.associateBy { it.id }
            val lightUrl = state.inProgress.firstNotNullOfOrNull { byId[it.id]?.let(::cover) }
                ?: state.roms.minByOrNull { it.id }?.let(::cover)
            return LibraryPage(
                isLoading = state.isLoading,
                isRefreshing = state.isRefreshing,
                errorMessage = state.errorMessage,
                staleMessage = state.staleMessage,
                summary = state.summary,
                platforms = state.platforms.map { items.getValue(it.platform.id) },
                sections = sections,
                collections = state.collections.map(::collection),
                collectionSections = collectionSections(state.collections).map { (title, list) -> title to list.map(::collection) },
                lens = choice.lens,
                columns = choice.columns,
                wall = wall,
                lightUrl = lightUrl,
            )
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
    private val savedProgress: SavedProgressRepository? = null,
    private val localPlay: LocalPlayState? = null,
    /** Where the wall's lens and density are kept on this device (none: not kept). */
    private val preferences: SharedPreferences? = null,
) : ViewModel() {
    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    private val choice = MutableStateFlow(
        LibraryWallChoice(
            lens = LibraryLens.of(preferences?.getString(LENS, null)),
            columns = preferences?.getInt(COLUMNS, 3)?.takeIf { it == 3 || it == 4 } ?: 3,
        ),
    )

    /** The page; published only when it changes. */
    val page: StateFlow<LibraryPage> = combine(
        _uiState,
        choice,
        repository.activeProfileFlow().map { it?.baseUrl }.distinctUntilChanged(),
    ) { state, choice, baseUrl -> LibraryPage.build(state, choice, baseUrl) }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, LibraryPage(lens = choice.value.lens, columns = choice.value.columns))

    init {
        localPlay?.let { viewModelScope.launch { it.ensureRead() } }
        viewModelScope.launch {
            val platformsFlow = combine(
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
            }
            val stats = playActivity?.stats ?: flowOf(PlayStats())
            val latest = savedProgress?.latest ?: flowOf(emptyMap<Long, SaveEntry>())
            val ready = localPlay?.ready ?: flowOf(true)
            val local = combine(stats, latest, ready) { played, saves, isReady -> Triple(played, saves, isReady) }
            val gamesFlow = combine(repository.observeCachedRoms(), repository.observeCachedHome(), local, repository.observeInstalledLibrary()) { roms, home, local, installed ->
                val (played, saves, isReady) = local
                val byId = roms.associateBy { it.id }
                // The same games in play as Play's Home (this device's saves and sessions, then RomM's).
                val inProgress = inPlay(home.continuePlaying, saves, played.games, byId::get)
                LibraryGames(roms, inProgress, installed.map { it.romId }.toSet(), LibraryEras.group(roms), isReady)
            }
            // The platforms and the wall of covers arrive together, once this device's play and
            // saves are in: the page draws whole instead of the wall dropping in after it.
            combine(platformsFlow, gamesFlow) { platforms, games -> platforms to games }
                .flowOn(Dispatchers.Default)
                .distinctUntilChanged()
                .collect { (platforms, games) ->
                    if (!games.ready) return@collect
                    val (entries, recentInstalled, installedCount) = platforms
                    _uiState.update {
                        it.copy(
                            platforms = entries,
                            recentInstalled = recentInstalled,
                            installedGameCount = installedCount,
                            roms = games.roms,
                            inProgress = games.inProgress,
                            installedRomIds = games.installed,
                            eras = games.eras,
                            isLoading = if (entries.isNotEmpty() || recentInstalled.isNotEmpty()) false else it.isLoading,
                        )
                    }
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

    /** Aurora: the wall's order (kept on this device). */
    fun selectLens(lens: LibraryLens) {
        choice.update { it.copy(lens = lens) }
        preferences?.edit()?.putString(LENS, lens.key)?.apply()
    }

    /** Aurora: three or four covers across (kept on this device). */
    fun toggleColumns() {
        val next = if (choice.value.columns == 3) 4 else 3
        choice.update { it.copy(columns = next) }
        preferences?.edit()?.putInt(COLUMNS, next)?.apply()
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

        /** The wall's choices on this device (`SharedPreferences` file and keys). */
        const val PREFERENCES = "library-wall"
        private const val LENS = "aurora.library.lens"
        private const val COLUMNS = "aurora.library.columns"
    }
}

private fun EmbeddedSupportTier.ordinalForLibrary(): Int = when (this) {
    EmbeddedSupportTier.TOUCH_SUPPORTED -> 0
    EmbeddedSupportTier.CONTROLLER_SUPPORTED -> 1
    EmbeddedSupportTier.UNSUPPORTED -> 2
}

private data class LibraryGames(
    val roms: List<RomDto>,
    val inProgress: List<RomDto>,
    val installed: Set<Int>,
    val eras: List<EraGroup>,
    /** This device's play and saves are in (the rings and the light come from them). */
    val ready: Boolean,
)
