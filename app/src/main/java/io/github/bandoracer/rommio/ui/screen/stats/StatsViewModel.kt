package io.github.bandoracer.rommio.ui.screen.stats

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.bandoracer.rommio.data.repository.RommRepository
import io.github.bandoracer.rommio.data.stats.PlayActivityRepository
import io.github.bandoracer.rommio.domain.stats.GamePlayTotal
import io.github.bandoracer.rommio.domain.stats.PlaySession
import io.github.bandoracer.rommio.domain.stats.PlayStats
import io.github.bandoracer.rommio.model.RomDto
import io.github.bandoracer.rommio.ui.component.Fmt
import io.github.bandoracer.rommio.ui.component.PlayFormat
import io.github.bandoracer.rommio.ui.component.formatClockTime
import io.github.bandoracer.rommio.ui.component.formatPlayTime
import io.github.bandoracer.rommio.ui.screen.GameIndex
import io.github.bandoracer.rommio.util.resolveRemoteAssetUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class StatsGame(
    val total: GamePlayTotal,
    val rom: RomDto,
    /** The installed file to start when the game can be played here (Resume on the Stats lead). */
    val playableFileId: Int? = null,
)
data class StatsSession(val session: PlaySession, val rom: RomDto)

data class StatsUiState(
    val isLoading: Boolean = true,
    val stats: PlayStats = PlayStats(),
    val games: List<StatsGame> = emptyList(),
    val sessions: List<StatsSession> = emptyList(),
)

/** The most played game (Aurora's lead, Riso's "Most played"). */
@Immutable
data class StatsTop(
    val id: Int,
    val title: String,
    val coverUrl: String?,
    val platformSlug: String,
    /** "1 h 05 min · 3 sessions". */
    val line: String,
    val playedText: String,
    val playableFileId: Int?,
)

/** A game in "Time by game". */
@Immutable
data class StatsGameRow(
    val id: Int,
    val title: String,
    val coverUrl: String?,
    /** "3 sessions · last 2 hours ago". */
    val subtitle: String,
    val playedText: String,
    /** Its share of the most played game's time (0.02–1). */
    val fraction: Float,
)

/** A session in "Recent sessions". */
@Immutable
data class StatsSessionRow(
    val key: String,
    val gameId: Int,
    val title: String,
    val coverUrl: String?,
    /** Aurora: "7:07 PM · Left with a resume point". */
    val subtitle: String,
    val clock: String,
    val playedText: String,
    /** Riso: "SNES · Left with a resume point". */
    val label: String,
)

/** A day of sessions, newest first. */
@Immutable
data class StatsDay(
    val key: String,
    /** Aurora's title ("Today", "Wednesday", …). */
    val title: String,
    /** Riso's day group: title and the full date when the title isn't it. */
    val groupTitle: String,
    val groupCaption: String,
    /** "3 sessions · 1 h 05 min". */
    val summary: String,
    /** "3 entries". */
    val entries: String,
    val sessions: List<StatsSessionRow>,
)

/** Riso's month: the last 28 days in a sentence, a bar per day and the figures. */
@Immutable
data class StatsMonth(
    val playedText: String,
    val sessionsText: String,
    val gamesText: String,
    val streakSentence: String,
    /** Playtime per day, oldest first, as a share of the longest day (0 = none). */
    val bars: List<Float>,
)

/** The Stats page, built once per data change. */
@Immutable
data class StatsPage(
    val isLoading: Boolean = true,
    val isEmpty: Boolean = true,
    val top: StatsTop? = null,
    val totalText: String = "0 min",
    val sessionsText: String = "0",
    val streakText: String = "0 days",
    val lastPlayedText: String = "—",
    val lastPlayedCaption: String? = null,
    val gameCount: Int = 0,
    val sessionCount: Int = 0,
    val sessionCountLine: String = "",
    val games: List<StatsGameRow> = emptyList(),
    val days: List<StatsDay> = emptyList(),
    val month: StatsMonth? = null,
    val footnote: String = "",
) {
    companion object {
        fun build(state: StatsUiState, baseUrl: String?, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): StatsPage {
            val stats = state.stats
            fun cover(rom: RomDto) = resolveRemoteAssetUrl(baseUrl, rom.urlCover)
            val top = state.games.firstOrNull()?.let { game ->
                StatsTop(
                    id = game.rom.id,
                    title = game.rom.displayName,
                    coverUrl = cover(game.rom),
                    platformSlug = game.rom.platformSlug,
                    line = "${formatPlayTime(game.total.playedMs)} · ${Fmt.count(game.total.sessionCount, "session")}",
                    playedText = formatPlayTime(game.total.playedMs),
                    playableFileId = game.playableFileId,
                )
            }
            val longest = maxOf(state.games.firstOrNull()?.total?.playedMs ?: 1L, 1L)
            val games = state.games.take(8).map { game ->
                StatsGameRow(
                    id = game.rom.id,
                    title = game.rom.displayName,
                    coverUrl = cover(game.rom),
                    subtitle = "${game.total.sessionCount} session${if (game.total.sessionCount == 1) "" else "s"} · last ${PlayFormat.relative(game.total.lastPlayedAtEpochMs, now, zone)}",
                    playedText = formatPlayTime(game.total.playedMs),
                    fraction = (game.total.playedMs.toFloat() / longest).coerceIn(0.02f, 1f),
                )
            }
            val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            val days = state.sessions.byDay(zone).take(6).map { (day, sessions) ->
                val total = sessions.sumOf { it.session.playedMs }
                val group = PlayFormat.dayGroup(day, today)
                StatsDay(
                    key = day.toString(),
                    title = PlayFormat.dayTitle(day.epochMs(zone), now, zone),
                    groupTitle = group.title,
                    groupCaption = group.caption.orEmpty(),
                    summary = "${sessions.size} session${if (sessions.size == 1) "" else "s"} · ${formatPlayTime(total)}",
                    entries = "${sessions.size} ${if (sessions.size == 1) "entry" else "entries"}",
                    sessions = sessions.mapIndexed { index, item ->
                        val end = item.session.endReason?.let(::sessionEndLabel)
                        StatsSessionRow(
                            key = "$day/$index",
                            gameId = item.rom.id,
                            title = item.rom.displayName,
                            coverUrl = cover(item.rom),
                            subtitle = listOfNotNull(formatClockTime(item.session.endedAtEpochMs, zone), end).joinToString(" · "),
                            clock = formatClockTime(item.session.endedAtEpochMs, zone),
                            playedText = formatPlayTime(item.session.playedMs),
                            label = listOfNotNull(item.rom.platformSlug.uppercase(), end).joinToString(" · "),
                        )
                    },
                )
            }
            val window = statsWindow(stats, now = now)
            val daily = dailyTotals(stats, now = now, zone = zone)
            val longestDay = maxOf(daily.maxOrNull() ?: 1L, 1L)
            return StatsPage(
                isLoading = state.isLoading,
                isEmpty = stats.isEmpty,
                top = top,
                totalText = compactDuration(stats.totalPlayedMs),
                sessionsText = java.text.NumberFormat.getIntegerInstance().format(stats.sessionCount),
                streakText = streakText(stats.currentStreakDays),
                lastPlayedText = stats.lastPlayedAtEpochMs?.let { PlayFormat.shortAgo(it, now, zone) } ?: "—",
                lastPlayedCaption = state.sessions.firstOrNull()?.rom?.displayName,
                gameCount = stats.games.size,
                sessionCount = stats.sessionCount,
                sessionCountLine = "${Fmt.count(stats.sessionCount, "session")} recorded · kept on this device",
                games = games,
                days = days,
                month = StatsMonth(
                    playedText = formatPlayTime(window.playedMs),
                    sessionsText = "${window.sessions} session${if (window.sessions == 1) "" else "s"}",
                    gamesText = PlayFormat.games(window.games),
                    streakSentence = if (stats.currentStreakDays > 0) ". Streak: ${streakText(stats.currentStreakDays)}." else ".",
                    bars = daily.map { if (it > 0) it.toFloat() / longestDay else 0f },
                ),
                footnote = footnote(stats),
            )
        }
    }
}

/** Recorded sessions are authoritative; play from before recording is estimated (docs/play-data-local.md). */
internal fun footnote(stats: PlayStats): String = if (stats.includesEstimates) {
    "Play time counts active play on this device; paused and background time is left out. Play from before sessions were recorded is estimated from automatic saves."
} else {
    "Play time counts active play on this device; paused and background time is left out."
}

private fun LocalDate.epochMs(zone: ZoneId): Long = atStartOfDay(zone).toInstant().toEpochMilli() + 43_200_000L

@OptIn(kotlinx.coroutines.FlowPreview::class)
class StatsViewModel(
    private val repository: RommRepository,
    private val playActivity: PlayActivityRepository,
    playDataChanges: kotlinx.coroutines.flow.Flow<Long> = kotlinx.coroutines.flow.emptyFlow(),
) : ViewModel() {
    private val _uiState = MutableStateFlow(StatsUiState())
    val uiState: StateFlow<StatsUiState> = _uiState.asStateFlow()

    /** The page; published only when it changes. */
    val page: StateFlow<StatsPage> = combine(
        _uiState,
        repository.activeProfileFlow().map { it?.baseUrl }.distinctUntilChanged(),
    ) { state, baseUrl -> StatsPage.build(state, baseUrl) }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, StatsPage())

    init {
        viewModelScope.launch {
            combine(
                playActivity.stats,
                repository.observeCachedRoms(),
                repository.observeInstalledLibrary(),
            ) { stats, cached, installed ->
                val index = GameIndex(cached, installed)
                fun rom(id: Long): RomDto = index[id.toInt()] ?: RomDto(id = id.toInt(), name = "Game $id")
                StatsUiState(
                    isLoading = false,
                    stats = stats,
                    games = stats.games.take(GAME_LIMIT).map { total ->
                        val rom = rom(total.romId)
                        val fileId = index.installedFileId(rom.id)
                            ?.takeIf { repository.embeddedSupportTier(rom) != io.github.bandoracer.rommio.domain.player.EmbeddedSupportTier.UNSUPPORTED }
                        StatsGame(total, rom, fileId)
                    },
                    sessions = stats.sessions.take(SESSION_LIMIT).map { StatsSession(it, rom(it.romId)) },
                )
            }.distinctUntilChanged().flowOn(Dispatchers.Default).collect { next -> _uiState.update { next.copy(isLoading = it.isLoading && next.stats.isEmpty) } }
        }
        viewModelScope.launch {
            playDataChanges.drop(1).debounce(400).collect { playActivity.refresh() }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            playActivity.refresh()
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    companion object {
        const val GAME_LIMIT = 8
        const val SESSION_LIMIT = 40
    }
}
