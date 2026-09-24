package io.github.bandoracer.rommio.ui.screen.play

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.bandoracer.rommio.data.repository.RommRepository
import io.github.bandoracer.rommio.data.playdata.SavedProgressRepository
import io.github.bandoracer.rommio.data.stats.PlayActivityRepository
import io.github.bandoracer.rommio.domain.playdata.SaveEntry
import io.github.bandoracer.rommio.domain.player.EmbeddedSupportTier
import io.github.bandoracer.rommio.domain.stats.PlayStats
import io.github.bandoracer.rommio.model.ConnectivityState
import io.github.bandoracer.rommio.model.DownloadRecord
import io.github.bandoracer.rommio.model.DownloadStatus
import io.github.bandoracer.rommio.model.RommCollectionDto
import io.github.bandoracer.rommio.model.LibraryStorageSummary
import io.github.bandoracer.rommio.model.OfflineState
import io.github.bandoracer.rommio.model.RomDto
import io.github.bandoracer.rommio.ui.screen.GameIndex
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A game you have been playing, with what we know about it on this device. */
data class InProgressGame(
    val rom: RomDto,
    val lastPlayedAtEpochMs: Long?,
    val playedMs: Long,
    val installedFileId: Int?,
    val supportTier: EmbeddedSupportTier,
    val sessionCount: Int = 0,
)

/** The collection pinned on Play (Riso): the first one the user made, its known games played-first. */
data class PinnedCollection(
    val collection: RommCollectionDto,
    val games: List<InProgressGame>,
    val playedCount: Int,
    val playedMs: Long,
)

/** The download in progress (or none) and the queue behind it. */
data class PlayDownloadSummary(
    val name: String,
    val progress: Float,
    val waiting: Int,
    val failed: Int,
)

/** A resume point or save state from Recent saves, with its game. */
data class RecentSaveItem(
    val entry: SaveEntry,
    val rom: RomDto,
    /** The installed file to start when the game can be played here (the Conflict sheet needs it). */
    val playableFileId: Int? = null,
)

data class PlayUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val continuePlaying: List<InProgressGame> = emptyList(),
    val recentSaves: List<RecentSaveItem> = emptyList(),
    val recentlyAdded: List<RomDto> = emptyList(),
    val recentlyAddedSupport: Map<Int, EmbeddedSupportTier> = emptyMap(),
    val storageSummary: LibraryStorageSummary = LibraryStorageSummary(),
    val offlineState: OfflineState = OfflineState(),
    val downloadCount: Int = 0,
    val errorMessage: String? = null,
    /** Games whose current resume point is in conflict (from Recent saves), with their head counts. */
    val conflicts: Map<Int, Int> = emptyMap(),
    val pinnedCollection: PinnedCollection? = null,
    val download: PlayDownloadSummary? = null,
    val serverName: String = "RomM",
    /** A refresh failed while cached content is shown. */
    val staleMessage: String? = null,
) {
    val hasContent: Boolean get() = continuePlaying.isNotEmpty() || recentlyAdded.isNotEmpty() || recentSaves.isNotEmpty()
}

@OptIn(kotlinx.coroutines.FlowPreview::class)
class PlayViewModel(
    private val repository: RommRepository,
    private val playActivity: PlayActivityRepository,
    private val savedProgress: SavedProgressRepository,
    playDataChanges: Flow<Long> = emptyFlow(),
) : ViewModel() {
    private val _uiState = MutableStateFlow(PlayUiState())
    val uiState: StateFlow<PlayUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                repository.observeCachedHome(),
                repository.observeCachedRoms(),
                repository.observeInstalledLibrary(),
                playActivity.stats,
                savedProgress.recent,
            ) { home, cached, installed, stats, saves ->
                val index = GameIndex(cached, installed)
                val continuePlaying = buildContinuePlaying(stats, home.continuePlaying, index, repository::embeddedSupportTier)
                val recentSaves = saves.mapNotNull { entry ->
                    index[entry.romId.toInt()]?.let { rom ->
                        val fileId = index.installedFileId(rom.id)?.takeIf { repository.embeddedSupportTier(rom) != EmbeddedSupportTier.UNSUPPORTED }
                        RecentSaveItem(entry, rom, fileId)
                    }
                }.take(RECENT_SAVES_LIMIT)
                // Conflicts come from every current resume head read, not only the shown 12.
                val conflicted = saves.filter { it.isCurrentResume && it.sync == io.github.bandoracer.rommio.domain.playdata.SaveSyncStatus.CONFLICT }
                    .groupingBy { it.romId.toInt() }.eachCount().filterValues { it > 1 }
                PlayContentParts(continuePlaying, recentSaves, home.recentRoms, conflicted, index, stats)
            }.combine(repository.observeCachedCollections()) { parts, collections ->
                parts to pinnedCollection(collections, parts.index, parts.stats, repository::embeddedSupportTier)
            }.collect { (parts, pinned) ->
                _uiState.update {
                    it.copy(
                        continuePlaying = parts.continuePlaying,
                        recentSaves = parts.recentSaves,
                        recentlyAdded = parts.recentlyAdded,
                        recentlyAddedSupport = parts.recentlyAdded.associate { rom -> rom.id to repository.embeddedSupportTier(rom) },
                        conflicts = parts.conflicted,
                        pinnedCollection = pinned,
                        isLoading = if (parts.continuePlaying.isNotEmpty() || parts.recentlyAdded.isNotEmpty()) false else it.isLoading,
                    )
                }
            }
        }
        viewModelScope.launch {
            repository.observeDownloadHistory().collect { records ->
                _uiState.update { it.copy(download = downloadSummary(records)) }
            }
        }
        viewModelScope.launch {
            repository.activeProfileFlow().collect { profile ->
                _uiState.update { it.copy(serverName = profile?.label?.takeIf(String::isNotBlank) ?: "RomM") }
            }
        }
        viewModelScope.launch {
            repository.observeLibraryStorageSummary().collect { summary -> _uiState.update { it.copy(storageSummary = summary) } }
        }
        viewModelScope.launch {
            repository.observeDownloadAttentionCount().collect { count -> _uiState.update { it.copy(downloadCount = count) } }
        }
        viewModelScope.launch {
            repository.observeOfflineState().collect { offlineState ->
                _uiState.update {
                    it.copy(
                        offlineState = offlineState,
                        isRefreshing = offlineState.isRefreshing,
                        isLoading = if (!it.hasContent && !offlineState.isRefreshing) false else it.isLoading,
                    )
                }
            }
        }
        viewModelScope.launch {
            // Sessions closing and thumbnails landing after a game: re-read once things settle.
            playDataChanges.drop(1).debounce(400).collect { refreshActivity() }
        }
        refresh()
    }

    /** Re-reads local play activity and saves (after returning from a game). */
    fun refreshActivity() {
        viewModelScope.launch { playActivity.refresh() }
        viewModelScope.launch { savedProgress.refreshRecent() }
    }

    fun refresh() {
        refreshActivity()
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = !it.hasContent, isRefreshing = true, errorMessage = null) }
            if (repository.currentConnectivityState() != ConnectivityState.ONLINE) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        errorMessage = if (it.hasContent) null else "You're offline. Your games appear here after this server syncs once.",
                    )
                }
                return@launch
            }
            runCatching { repository.refreshActiveProfileCache(force = false) }.fold(
                onSuccess = { _uiState.update { it.copy(isLoading = false, isRefreshing = false, errorMessage = null, staleMessage = null) } },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            errorMessage = if (it.hasContent) null else error.message ?: "Couldn't load your games.",
                            staleMessage = if (it.hasContent) error.message ?: "Couldn't reach your server." else null,
                        )
                    }
                },
            )
        }
    }

    companion object {
        const val CONTINUE_LIMIT = 10
        const val RECENT_SAVES_LIMIT = 12
    }
}

private data class PlayContentParts(
    val continuePlaying: List<InProgressGame>,
    val recentSaves: List<RecentSaveItem>,
    val recentlyAdded: List<RomDto>,
    val conflicted: Map<Int, Int>,
    val index: GameIndex,
    val stats: PlayStats,
)

/** The running download (or the queue's counts), or null when nothing is queued. */
internal fun downloadSummary(records: List<DownloadRecord>): PlayDownloadSummary? {
    val waiting = records.count { it.status == DownloadStatus.QUEUED }
    val failed = records.count { it.status == DownloadStatus.FAILED }
    val running = records.firstOrNull { it.status == DownloadStatus.RUNNING }
    return when {
        running != null -> PlayDownloadSummary(running.romName, running.progressPercent / 100f, waiting, failed)
        waiting > 0 || failed > 0 -> PlayDownloadSummary("", 0f, waiting, failed)
        else -> null
    }
}

/**
 * The first collection the user made with known games (then any with games): its games, the
 * most played first, then by title.
 */
internal fun pinnedCollection(
    collections: List<RommCollectionDto>,
    index: GameIndex,
    stats: PlayStats,
    supportTier: (RomDto) -> EmbeddedSupportTier,
): PinnedCollection? {
    val byRom = stats.games.associateBy { it.romId.toInt() }
    fun games(collection: RommCollectionDto): List<InProgressGame> = collection.romIds.mapNotNull { id ->
        val rom = index[id] ?: return@mapNotNull null
        val played = byRom[id]
        InProgressGame(rom, played?.lastPlayedAtEpochMs, played?.playedMs ?: 0L, index.installedFileId(id), supportTier(rom), played?.sessionCount ?: 0)
    }.sortedWith(compareByDescending<InProgressGame> { it.playedMs }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.rom.displayName })
    val candidates = collections.filter { !it.isVirtual && !it.isSmart && it.romIds.isNotEmpty() } +
        collections.filter { it.romIds.isNotEmpty() }
    for (collection in candidates) {
        val known = games(collection)
        if (known.isNotEmpty()) {
            return PinnedCollection(collection, known, known.count { it.playedMs > 0 }, known.sumOf { it.playedMs })
        }
    }
    return null
}

/**
 * Games played on this device (most recent first), then RomM's own continue-playing list for
 * anything not already shown.
 */
internal fun buildContinuePlaying(
    stats: PlayStats,
    serverContinue: List<RomDto>,
    index: GameIndex,
    supportTier: (RomDto) -> EmbeddedSupportTier,
    limit: Int = PlayViewModel.CONTINUE_LIMIT,
): List<InProgressGame> {
    val local = stats.games
        .sortedByDescending { it.lastPlayedAtEpochMs }
        .mapNotNull { total ->
            val rom = index[total.romId.toInt()] ?: return@mapNotNull null
            InProgressGame(rom, total.lastPlayedAtEpochMs, total.playedMs, index.installedFileId(rom.id), supportTier(rom), total.sessionCount)
        }
    val seen = local.map { it.rom.id }.toMutableSet()
    val remote = serverContinue.filter { seen.add(it.id) }.map { rom ->
        InProgressGame(rom, null, 0L, index.installedFileId(rom.id), supportTier(rom))
    }
    return (local + remote).take(limit)
}
