package io.github.bandoracer.rommio.ui.screen.platform

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.bandoracer.rommio.data.repository.RommRepository
import io.github.bandoracer.rommio.data.stats.PlayActivityRepository
import io.github.bandoracer.rommio.domain.player.EmbeddedSupportTier
import io.github.bandoracer.rommio.domain.stats.PlayStats
import io.github.bandoracer.rommio.model.ConnectivityState
import io.github.bandoracer.rommio.model.PlatformDto
import io.github.bandoracer.rommio.model.RomDto
import io.github.bandoracer.rommio.ui.component.PlayFormat
import io.github.bandoracer.rommio.ui.component.formatPlayTime
import io.github.bandoracer.rommio.ui.component.supportTierLabel
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

/** A platform's game with what this device knows about it. */
data class PlatformGame(
    val rom: RomDto,
    val installed: Boolean,
    val playedMs: Long,
    val lastPlayedAtEpochMs: Long?,
)

enum class PlatformFilter(val title: String) { ALL("All"), ON_DEVICE("On this device"), PLAYED("Played") }

enum class PlatformOrder { ALPHABETICAL, RECENTLY_PLAYED }

/** Everything the Platform page's data is made of (docs/theme-layouts.md "Platform — two layouts"). */
data class PlatformUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val platform: PlatformDto? = null,
    val tier: EmbeddedSupportTier = EmbeddedSupportTier.UNSUPPORTED,
    val games: List<PlatformGame> = emptyList(),
    val errorMessage: String? = null,
    val staleMessage: String? = null,
) {
    val installedCount: Int get() = games.count { it.installed }
    val playedCount: Int get() = games.count { it.playedMs > 0 }
    val totalCount: Int get() = maxOf(platform?.romCount ?: 0, games.size)

    fun count(filter: PlatformFilter): Int = when (filter) {
        PlatformFilter.ALL -> games.size
        PlatformFilter.ON_DEVICE -> installedCount
        PlatformFilter.PLAYED -> playedCount
    }

    fun filtered(filter: PlatformFilter, order: PlatformOrder): List<PlatformGame> {
        val chosen = when (filter) {
            PlatformFilter.ALL -> games
            PlatformFilter.ON_DEVICE -> games.filter { it.installed }
            PlatformFilter.PLAYED -> games.filter { it.playedMs > 0 }
        }
        val byName = compareBy<PlatformGame, String>(String.CASE_INSENSITIVE_ORDER) { it.rom.displayName }
        return when (order) {
            PlatformOrder.ALPHABETICAL -> chosen.sortedWith(byName)
            PlatformOrder.RECENTLY_PLAYED -> chosen.sortedWith(
                compareByDescending<PlatformGame> { it.lastPlayedAtEpochMs ?: Long.MIN_VALUE }.then(byName),
            )
        }
    }
}

/** One game as both Platform layouts draw it (strings formatted once per data change). */
@Immutable
data class PlatformGameItem(
    val id: Int,
    val title: String,
    val coverUrl: String?,
    val installed: Boolean,
    val played: Boolean,
    /** Aurora tile caption: when it was last played, else where it is. */
    val caption: String,
    /** Riso A–Z figure: playtime, "Ready", or a blank. */
    val figure: String,
    /** Riso plate caption: playtime, or "On this device". */
    val plateCaption: String,
)

/** A letter of the Riso A–Z index and its games. */
@Immutable
data class PlatformLetter(val letter: String, val games: List<PlatformGameItem>)

/** The Platform page, built once per data or choice change (never in composition). */
@Immutable
data class PlatformPage(
    val name: String = "",
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
    val staleMessage: String? = null,
    val tier: EmbeddedSupportTier = EmbeddedSupportTier.UNSUPPORTED,
    val tierLabel: String = supportTierLabel(EmbeddedSupportTier.UNSUPPORTED),
    val totalCountText: String = "0",
    val totalIsOne: Boolean = false,
    val installedCount: Int = 0,
    val playedCount: Int = 0,
    val filter: PlatformFilter = PlatformFilter.ALL,
    val order: PlatformOrder = PlatformOrder.ALPHABETICAL,
    /** The games the filter keeps, in the chosen order. */
    val games: List<PlatformGameItem> = emptyList(),
    /** Riso's A–Z index (alphabetical order only). */
    val letters: List<PlatformLetter> = emptyList(),
    /** Riso's plates: the most played, else the first on this device (up to four). */
    val plates: List<PlatformGameItem> = emptyList(),
    val platesArePlayed: Boolean = false,
    /** Aurora: the most recently played game lights the page. */
    val leadCoverUrl: String? = null,
) {
    val orderLabel: String
        get() = when (order) {
            PlatformOrder.ALPHABETICAL -> "A–Z"
            PlatformOrder.RECENTLY_PLAYED -> "Recently played"
        }

    companion object {
        /** Builds the page from the platform's data and the page's choices. */
        fun build(state: PlatformUiState, name: String, filter: PlatformFilter, order: PlatformOrder, baseUrl: String?): PlatformPage {
            val items = HashMap<Int, PlatformGameItem>(state.games.size)
            fun item(game: PlatformGame): PlatformGameItem = items.getOrPut(game.rom.id) {
                PlatformGameItem(
                    id = game.rom.id,
                    title = game.rom.displayName,
                    coverUrl = resolveRemoteAssetUrl(baseUrl, game.rom.urlCover),
                    installed = game.installed,
                    played = game.playedMs > 0,
                    caption = game.lastPlayedAtEpochMs?.let { PlayFormat.relative(it) } ?: if (game.installed) "On this device" else "On your server",
                    figure = if (game.playedMs > 0) formatPlayTime(game.playedMs) else if (game.installed) "Ready" else " ",
                    plateCaption = if (game.playedMs > 0) formatPlayTime(game.playedMs) else "On this device",
                )
            }
            val games = state.filtered(filter, order).map(::item)
            val played = state.games.filter { it.playedMs > 0 }.sortedByDescending { it.playedMs }
            val plates = played.ifEmpty { state.games.filter { it.installed } }.take(4).map(::item)
            return PlatformPage(
                name = name,
                isLoading = state.isLoading,
                isRefreshing = state.isRefreshing,
                errorMessage = state.errorMessage,
                staleMessage = state.staleMessage,
                tier = state.tier,
                tierLabel = supportTierLabel(state.tier),
                totalCountText = java.text.NumberFormat.getIntegerInstance().format(state.totalCount),
                totalIsOne = state.totalCount == 1,
                installedCount = state.installedCount,
                playedCount = state.playedCount,
                filter = filter,
                order = order,
                games = games,
                letters = if (order == PlatformOrder.ALPHABETICAL) letterGroups(games) else emptyList(),
                plates = plates,
                platesArePlayed = played.isNotEmpty(),
                leadCoverUrl = state.filtered(PlatformFilter.ALL, PlatformOrder.RECENTLY_PLAYED).firstOrNull()
                    ?.let { resolveRemoteAssetUrl(baseUrl, it.rom.urlCover) },
            )
        }

        /** Games under their first letter ("#" for anything else), in the games' order. */
        fun letterGroups(games: List<PlatformGameItem>): List<PlatformLetter> {
            val order = mutableListOf<String>()
            val groups = mutableMapOf<String, MutableList<PlatformGameItem>>()
            for (game in games) {
                val first = game.title.firstOrNull()?.uppercaseChar()
                val letter = if (first != null && first.isLetter()) first.toString() else "#"
                if (letter !in groups) order += letter
                groups.getOrPut(letter) { mutableListOf() } += game
            }
            return order.map { PlatformLetter(it, groups.getValue(it)) }
        }
    }
}

class PlatformViewModel(
    private val repository: RommRepository,
    private val platformId: Int,
    private val playActivity: PlayActivityRepository? = null,
    private val saved: SavedStateHandle = SavedStateHandle(),
    /** The name from the route, until the platform is read. */
    private val routeName: String = "",
) : ViewModel() {
    private val _uiState = MutableStateFlow(PlatformUiState())
    val uiState: StateFlow<PlatformUiState> = _uiState.asStateFlow()

    private val filter = MutableStateFlow(saved.get<String>(FILTER)?.let { runCatching { PlatformFilter.valueOf(it) }.getOrNull() } ?: PlatformFilter.ALL)
    private val order = MutableStateFlow(saved.get<String>(ORDER)?.let { runCatching { PlatformOrder.valueOf(it) }.getOrNull() } ?: PlatformOrder.ALPHABETICAL)

    /** The page both layouts draw; published only when it changes. */
    val page: StateFlow<PlatformPage> = combine(
        _uiState,
        filter,
        order,
        repository.activeProfileFlow().map { it?.baseUrl }.distinctUntilChanged(),
    ) { state, filter, order, baseUrl ->
        PlatformPage.build(state, state.platform?.name ?: routeName, filter, order, baseUrl)
    }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, PlatformPage(name = routeName))

    init {
        viewModelScope.launch {
            combine(
                repository.observeCachedPlatforms(),
                repository.observeCachedPlatformRoms(platformId),
                repository.observeInstalledLibrary(),
                playActivity?.stats ?: flowOf(PlayStats()),
            ) { platforms, roms, installed, stats ->
                val platform = platforms.firstOrNull { it.id == platformId }
                val installedIds = installed.map { it.romId }.toSet()
                val played = stats.games.associateBy { it.romId.toInt() }
                val games = roms.map { rom ->
                    PlatformGame(rom, rom.id in installedIds, played[rom.id]?.playedMs ?: 0L, played[rom.id]?.lastPlayedAtEpochMs)
                }
                val tier = platform?.let(repository::embeddedSupportTier)
                    ?: roms.firstOrNull()?.let(repository::embeddedSupportTier)
                    ?: EmbeddedSupportTier.UNSUPPORTED
                Triple(platform, tier, games)
            }.distinctUntilChanged().flowOn(Dispatchers.Default).collect { (platform, tier, games) ->
                _uiState.update {
                    it.copy(
                        platform = platform ?: it.platform,
                        tier = tier,
                        games = games,
                        isLoading = if (games.isNotEmpty()) false else it.isLoading,
                    )
                }
            }
        }
        refresh()
    }

    fun setFilter(value: PlatformFilter) {
        filter.value = value
        saved[FILTER] = value.name
    }

    fun toggleOrder() {
        val next = if (order.value == PlatformOrder.ALPHABETICAL) PlatformOrder.RECENTLY_PLAYED else PlatformOrder.ALPHABETICAL
        order.value = next
        saved[ORDER] = next.name
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = it.games.isEmpty(), isRefreshing = true, errorMessage = null) }
            if (repository.currentConnectivityState() != ConnectivityState.ONLINE) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        errorMessage = if (it.games.isNotEmpty()) null else "Offline. This platform will appear after the profile sync completes.",
                    )
                }
                return@launch
            }
            runCatching { repository.refreshPlatformInBackground(platformId) }.fold(
                onSuccess = { _uiState.update { it.copy(isLoading = false, isRefreshing = false, errorMessage = null, staleMessage = null) } },
                onFailure = { error ->
                    _uiState.update {
                        val message = error.message ?: "Unable to load platform ROMs."
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            errorMessage = if (it.games.isEmpty()) message else null,
                            staleMessage = if (it.games.isNotEmpty()) message else null,
                        )
                    }
                },
            )
        }
    }

    private companion object {
        const val FILTER = "platform.filter"
        const val ORDER = "platform.order"
    }
}
