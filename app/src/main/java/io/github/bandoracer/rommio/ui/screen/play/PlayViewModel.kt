package io.github.bandoracer.rommio.ui.screen.play

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.bandoracer.rommio.data.repository.RommRepository
import io.github.bandoracer.rommio.data.playdata.SavedProgressRepository
import io.github.bandoracer.rommio.data.stats.PlayActivityRepository
import io.github.bandoracer.rommio.domain.playdata.SaveEntry
import io.github.bandoracer.rommio.domain.playdata.SaveKind
import io.github.bandoracer.rommio.domain.playdata.SaveSyncStatus
import io.github.bandoracer.rommio.domain.playdata.SavedProgressRules
import io.github.bandoracer.rommio.domain.player.EmbeddedSupportTier
import io.github.bandoracer.rommio.domain.stats.PlayStats
import io.github.bandoracer.rommio.model.ConnectivityState
import io.github.bandoracer.rommio.model.DownloadRecord
import io.github.bandoracer.rommio.model.DownloadStatus
import io.github.bandoracer.rommio.model.RommCollectionDto
import io.github.bandoracer.rommio.model.LibraryStorageSummary
import io.github.bandoracer.rommio.model.OfflineState
import io.github.bandoracer.rommio.model.RomDto
import io.github.bandoracer.rommio.ui.component.Fmt
import io.github.bandoracer.rommio.ui.component.PlayFormat
import io.github.bandoracer.rommio.ui.component.formatPlayTime
import io.github.bandoracer.rommio.ui.screen.GameIndex
import io.github.bandoracer.rommio.util.resolveRemoteAssetUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId

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
    /** Each game's latest place to continue from (resume point, else a save state), by ROM id. */
    val latest: Map<Long, SaveEntry> = emptyMap(),
) {
    val hasContent: Boolean get() = continuePlaying.isNotEmpty() || recentlyAdded.isNotEmpty() || recentSaves.isNotEmpty()
}

// ---- The page, as both layouts draw it (built once per data change, off the main thread) -------

/** A game in progress with its lines formatted. */
@Immutable
data class PlayGame(
    /** For the actions (resume, start options, the long-press menu). */
    val game: InProgressGame,
    val id: Int,
    val title: String,
    val coverUrl: String?,
    val platformName: String,
    val platformSlug: String,
    /** "12 h ago", when played here. */
    val relative: String?,
    /** Aurora caption: "Played 12 h ago · 11 min on this device", or "Resume where you left off". */
    val playedLine: String,
    /** Riso kicker detail: "NES · 12 h ago". */
    val kickerDetail: String,
    /** Riso figure caption: "Nintendo Entertainment System. Played 12 h ago." */
    val figureCaption: String,
    /** Riso dek. */
    val dek: String,
    /** "18 sessions", when played here. */
    val sessionsText: String?,
    /** Riso brief: "11 min so far." or "Ready to resume." */
    val briefLine: String,
    /** "Two resume points" when its resume point is in conflict. */
    val conflictChip: String?,
    /** Aurora caption heading: "Super Nintendo · 1994". */
    val platformLine: String = platformName,
    /**
     * Aurora caption line: where it continues from ("Automatic resume · 1 min ago · This phone",
     * a save state's name or kind), else how it was played ([playedLine]).
     */
    val captionLine: String = playedLine,
    /** The game's screen: its latest resume point's (or save state's) captured frame. */
    val previewPath: String? = null,
    /** The latest's sync state, for the screen's chip (none when unknown). */
    val screenSync: SaveSyncStatus? = null,
)

/** A save in Recent saves. */
@Immutable
data class PlaySave(
    val key: String,
    val item: RecentSaveItem,
    val gameId: Int,
    val title: String,
    val coverUrl: String?,
    val thumbnailPath: String?,
    /** "Automatic resume", "Save state · Slot 2". */
    val kindChip: String,
    val isState: Boolean,
    /** "“Before the boss” · 7:07 PM · This phone". */
    val whenWhere: String,
    /** Shown sync state (none when unknown). */
    val sync: SaveSyncStatus?,
)

/** A day of Recent saves. */
@Immutable
data class PlaySaveDay(
    val key: String,
    val title: String,
    /** The full date when the title isn't it. */
    val caption: String?,
    /** "3 saves". */
    val countText: String,
    /** Regular width: "3 saves · 2 games". */
    val countAndGames: String,
    /** Riso: the caption and the count ("Wednesday, September 23 · 3 saves"). */
    val risoLine: String,
    val saves: List<PlaySave>,
    /** One cover per game (the day's lights). */
    val gameCovers: List<String?>,
    /** The compact mosaic (two columns): rows of indices into [saves] ([SaveMosaic]). */
    val rows: List<List<Int>> = SaveMosaic.rows(saves.size, 2),
)

/** A row of the pinned collection. */
@Immutable
data class PinnedRow(val id: Int, val number: Int, val title: String, val played: Boolean, val text: String)

/** The pinned collection (Riso). */
@Immutable
data class PinnedContent(
    val collection: RommCollectionDto,
    val name: String,
    /** The description, or "1 h 05 min played so far." */
    val line: String,
    /** "2 of 5 played". */
    val countLine: String,
    val rows: List<PinnedRow>,
)

/** The Play page. */
@Immutable
data class PlayPage(
    val isLoading: Boolean = true,
    val hasContent: Boolean = false,
    val errorMessage: String? = null,
    val staleMessage: String? = null,
    val games: List<PlayGame> = emptyList(),
    val offline: Boolean = false,
    /** Aurora: "Online · synced 2 min ago · 12 games on this device". */
    val statusLine: String = "",
    val downloadCount: Int = 0,
    val download: PlayDownloadSummary? = null,
    /** "3 games". */
    val installedGames: String = PlayFormat.games(0),
    /** Riso sync ear: "Synced 2 min ago", or why not. */
    val syncLine: String = "",
    val serverName: String = "RomM",
    val dateline: String = "",
    val saveDays: List<PlaySaveDay> = emptyList(),
    val saveCount: Int = 0,
    val pinned: PinnedContent? = null,
) {
    val lead: PlayGame? get() = games.firstOrNull()
    /** Aurora's carousel: the first games in progress; [more] go in the row under "In progress". */
    val carousel: List<PlayGame> get() = games.take(CAROUSEL_LIMIT)
    val more: List<PlayGame> get() = games.drop(CAROUSEL_LIMIT)
    val briefs: List<PlayGame> get() = games.drop(1).take(4)
    val showsEmpty: Boolean get() = games.isEmpty() && saveCount == 0 && !isLoading && errorMessage == null

    companion object {
        /** Games in Aurora's carousel (docs/theme-layouts.md "Play", Aurora v2). */
        const val CAROUSEL_LIMIT = 6

        fun build(state: PlayUiState, baseUrl: String?, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): PlayPage {
            fun cover(rom: RomDto) = resolveRemoteAssetUrl(baseUrl, rom.urlCover)
            fun relative(ms: Long) = PlayFormat.relative(ms, now, zone)
            val games = state.continuePlaying.map { game ->
                val rel = game.lastPlayedAtEpochMs?.let(::relative)
                val latest = state.latest[game.rom.id.toLong()]
                val playedLine = rel?.let { "Played $it · ${formatPlayTime(game.playedMs)} on this device" } ?: "Resume where you left off"
                PlayGame(
                    game = game,
                    id = game.rom.id,
                    title = game.rom.displayName,
                    coverUrl = cover(game.rom),
                    platformName = game.rom.platformName,
                    platformSlug = game.rom.platformSlug,
                    relative = rel,
                    playedLine = playedLine,
                    kickerDetail = listOfNotNull(game.rom.platformSlug.uppercase(), rel).joinToString(" · "),
                    figureCaption = "${game.rom.platformName}. " + (rel?.let { "Played $it." } ?: "Not played on this device yet."),
                    dek = rel?.let { "You last played $it, ${formatPlayTime(game.playedMs)} in so far on this device." }
                        ?: "Pick up where you left off. Your resume point is waiting.",
                    sessionsText = game.sessionCount.takeIf { it > 0 }?.let { if (it == 1) "1 session" else "$it sessions" },
                    briefLine = if (game.playedMs > 0) "${formatPlayTime(game.playedMs)} so far." else "Ready to resume.",
                    conflictChip = state.conflicts[game.rom.id]?.let { count ->
                        io.github.bandoracer.rommio.domain.playdata.SaveConflict.chipTitle(io.github.bandoracer.rommio.domain.playdata.SaveConflictKind.RESUME, count)
                    },
                    platformLine = Fmt.join(game.rom.platformName.takeIf { it.isNotBlank() }, game.rom.metadata.releaseYear?.toString()),
                    captionLine = latest?.let { resumeLine(it, now, zone) } ?: playedLine,
                    previewPath = latest?.thumbnailPath,
                    screenSync = latest?.sync?.takeIf { it != SaveSyncStatus.UNKNOWN },
                )
            }
            val offline = state.offlineState.isOffline
            var status = if (offline) "Offline" else "Online"
            state.offlineState.lastFullSyncAtEpochMs?.let { status += " · synced ${relative(it)}" }
            val installed = state.storageSummary.installedGameCount
            val byKey = state.recentSaves.associateBy { it.entry.key }
            val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            val days = SavedProgressRules.groupByDay(state.recentSaves.map { it.entry }, zone).mapNotNull { day ->
                val items = day.entries.mapNotNull { byKey[it.key] }
                if (items.isEmpty()) return@mapNotNull null
                val group = PlayFormat.dayGroup(day.day, today)
                val count = saveCount(items.size)
                PlaySaveDay(
                    key = day.day.toString(),
                    title = group.title,
                    caption = group.caption,
                    countText = count,
                    countAndGames = "$count · ${PlayFormat.games(items.distinctBy { it.rom.id }.size)}",
                    risoLine = Fmt.join(group.caption, count),
                    saves = items.map { item ->
                        PlaySave(
                            key = item.entry.key.toString(),
                            item = item,
                            gameId = item.rom.id,
                            title = item.rom.displayName,
                            coverUrl = cover(item.rom),
                            thumbnailPath = item.entry.thumbnailPath,
                            kindChip = item.entry.kindChip(),
                            isState = item.entry.kind == SaveKind.SAVE_STATE,
                            whenWhere = item.entry.whenWhere(zone),
                            sync = item.entry.sync.takeIf { it != SaveSyncStatus.UNKNOWN },
                        )
                    },
                    gameCovers = items.distinctBy { it.rom.id }.map { cover(it.rom) },
                )
            }
            val pinned = state.pinnedCollection?.let { item ->
                PinnedContent(
                    collection = item.collection,
                    name = item.collection.name,
                    line = item.collection.description.ifBlank { "${formatPlayTime(item.playedMs)} played so far." },
                    countLine = "${item.playedCount} of ${item.games.size} played",
                    rows = item.games.take(6).mapIndexed { index, game ->
                        PinnedRow(game.rom.id, index + 1, game.rom.displayName, game.playedMs > 0, if (game.playedMs > 0) formatPlayTime(game.playedMs) else "Not started")
                    },
                )
            }
            return PlayPage(
                isLoading = state.isLoading,
                hasContent = state.hasContent,
                errorMessage = state.errorMessage,
                staleMessage = state.staleMessage,
                games = games,
                offline = offline,
                statusLine = "$status · ${PlayFormat.games(installed)} on this device",
                downloadCount = state.downloadCount,
                download = state.download,
                installedGames = PlayFormat.games(installed),
                syncLine = state.offlineState.lastFullSyncAtEpochMs?.let { "Synced ${relative(it)}" }
                    ?: if (offline) "Waiting for a connection" else "Library not synced yet",
                serverName = state.serverName,
                dateline = PlayFormat.longDate(today),
                saveDays = days,
                saveCount = state.recentSaves.size,
                pinned = pinned,
            )
        }

        private fun saveCount(n: Int) = "$n save${if (n == 1) "" else "s"}"
    }
}

/**
 * Aurora Play's caption line for a game's latest save (docs/theme-layouts.md "Play" v2):
 * "Automatic resume · 1 min ago · This phone", or the save state's name ("“Before the boss”") or
 * kind ("Save state · Slot 2") in place of "Automatic resume".
 */
internal fun resumeLine(entry: SaveEntry, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
    val what = if (entry.kind == SaveKind.SAVE_STATE) entry.label?.let { "“$it”" } ?: entry.kindChip() else "Automatic resume"
    return Fmt.join(what, PlayFormat.relative(entry.capturedAtEpochMs, now, zone), entry.deviceLabel)
}

/**
 * Recent saves mosaic v2 (docs/theme-layouts.md "Play"): never a hole or a lone half tile. Two
 * columns: one save is one full-width tile, two are a pair, three or more open with a full-width
 * tile, then pairs, and an odd last save goes full width. More columns (regular width): rows of
 * that many, a short last row stretching its tiles to fill it. As iOS `SaveMosaic`.
 */
object SaveMosaic {
    fun rows(count: Int, columns: Int): List<List<Int>> {
        if (count <= 0) return emptyList()
        if (columns <= 2) {
            if (count == 2) return listOf(listOf(0, 1))
            val rows = mutableListOf(listOf(0))
            var index = 1
            while (index < count) {
                if (index + 1 < count) {
                    rows += listOf(index, index + 1)
                    index += 2
                } else {
                    rows += listOf(index)
                    index += 1
                }
            }
            return rows
        }
        return (0 until count step columns).map { start -> (start until minOf(start + columns, count)).toList() }
    }

    /** Columns on a regular-width canvas: four while tiles keep 180 dp, else fewer. */
    fun columns(width: Float, gap: Float = 10f): Int {
        for (columns in listOf(4, 3)) if ((width - (columns - 1) * gap) / columns >= 180f) return columns
        return 2
    }
}

/** Chip copy for a save's kind: "Automatic resume", "Save state · Slot 2", "Save state". */
internal fun SaveEntry.kindChip(): String = when (kind) {
    SaveKind.RESUME_POINT -> if (isCurrentResume) "Automatic resume" else "Earlier resume point"
    SaveKind.SAVE_STATE -> slotNumber?.let { "Save state · Slot $it" } ?: "Save state"
    SaveKind.GAME_SAVE -> "Game-save data"
}

/** "“Before the boss” · 7:07 PM · This phone": the name (when given), when and where. */
internal fun SaveEntry.whenWhere(zone: ZoneId = ZoneId.systemDefault()): String = listOfNotNull(
    label?.let { "“$it”" },
    io.github.bandoracer.rommio.ui.component.formatClockTime(capturedAtEpochMs, zone),
    deviceLabel,
).joinToString(" · ")

@OptIn(kotlinx.coroutines.FlowPreview::class)
class PlayViewModel(
    private val repository: RommRepository,
    private val playActivity: PlayActivityRepository,
    private val savedProgress: SavedProgressRepository,
    playDataChanges: Flow<Long> = emptyFlow(),
) : ViewModel() {
    private val _uiState = MutableStateFlow(PlayUiState())
    val uiState: StateFlow<PlayUiState> = _uiState.asStateFlow()

    /** The page; published only when it changes. */
    val page: StateFlow<PlayPage> = combine(
        _uiState,
        repository.activeProfileFlow().map { it?.baseUrl }.distinctUntilChanged(),
    ) { state, baseUrl -> PlayPage.build(state, baseUrl) }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, PlayPage())

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
                val conflicted = saves.filter { it.isCurrentResume && it.sync == SaveSyncStatus.CONFLICT }
                    .groupingBy { it.romId.toInt() }.eachCount().filterValues { it > 1 }
                PlayContentParts(continuePlaying, recentSaves, home.recentRoms, conflicted, index, stats)
            }.combine(repository.observeCachedCollections()) { parts, collections ->
                parts to pinnedCollection(collections, parts.index, parts.stats, repository::embeddedSupportTier)
            }.flowOn(Dispatchers.Default).collect { (parts, pinned) ->
                _uiState.update {
                    it.copy(
                        continuePlaying = parts.continuePlaying,
                        recentSaves = parts.recentSaves,
                        recentlyAdded = parts.recentlyAdded,
                        conflicts = parts.conflicted,
                        pinnedCollection = pinned,
                        isLoading = if (parts.continuePlaying.isNotEmpty() || parts.recentlyAdded.isNotEmpty()) false else it.isLoading,
                    )
                }
            }
        }
        viewModelScope.launch {
            // The page's smaller inputs arrive together: one update, not five.
            combine(
                repository.observeDownloadHistory().map(::downloadSummary).distinctUntilChanged(),
                repository.activeProfileFlow().map { profile -> profile?.label?.takeIf(String::isNotBlank) ?: "RomM" }.distinctUntilChanged(),
                repository.observeLibraryStorageSummary(),
                repository.observeDownloadAttentionCount(),
                repository.observeOfflineState(),
            ) { download, serverName, storage, downloadCount, offlineState ->
                ShellParts(download, serverName, storage, downloadCount, offlineState)
            }.distinctUntilChanged().collect { parts ->
                _uiState.update {
                    it.copy(
                        download = parts.download,
                        serverName = parts.serverName,
                        storageSummary = parts.storage,
                        downloadCount = parts.downloadCount,
                        offlineState = parts.offlineState,
                        isRefreshing = parts.offlineState.isRefreshing,
                        isLoading = if (!it.hasContent && !parts.offlineState.isRefreshing) false else it.isLoading,
                    )
                }
            }
        }
        viewModelScope.launch {
            // Each game's latest resume point or save state: the screens on Aurora Play.
            savedProgress.latest.collect { latest -> _uiState.update { it.copy(latest = latest) } }
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

private data class ShellParts(
    val download: PlayDownloadSummary?,
    val serverName: String,
    val storage: LibraryStorageSummary,
    val downloadCount: Int,
    val offlineState: OfflineState,
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
