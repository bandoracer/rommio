package io.github.bandoracer.rommio.ui.screen.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.bandoracer.rommio.AppContainer
import io.github.bandoracer.rommio.domain.stats.PlayStats
import io.github.bandoracer.rommio.model.RomDto
import io.github.bandoracer.rommio.ui.component.ColumnRule
import io.github.bandoracer.rommio.ui.component.CoverThumb
import io.github.bandoracer.rommio.ui.component.DotLeader
import io.github.bandoracer.rommio.ui.component.EmptyState
import io.github.bandoracer.rommio.ui.component.GlowingCover
import io.github.bandoracer.rommio.ui.component.LightStrip
import io.github.bandoracer.rommio.ui.component.ListDivider
import io.github.bandoracer.rommio.ui.component.ListGroup
import io.github.bandoracer.rommio.ui.component.ListRow
import io.github.bandoracer.rommio.ui.component.MountedPrint
import io.github.bandoracer.rommio.ui.component.PageArtSubject
import io.github.bandoracer.rommio.ui.component.PageFieldStyle
import io.github.bandoracer.rommio.ui.component.PageHeader
import io.github.bandoracer.rommio.ui.component.PlayFormat
import io.github.bandoracer.rommio.ui.component.PrintRule
import io.github.bandoracer.rommio.ui.component.RoleText
import io.github.bandoracer.rommio.ui.component.RuleWeight
import io.github.bandoracer.rommio.ui.component.SectionHeader
import io.github.bandoracer.rommio.ui.component.StatCard
import io.github.bandoracer.rommio.ui.component.dropShadow
import io.github.bandoracer.rommio.ui.component.formatClockTime
import io.github.bandoracer.rommio.ui.component.formatPlayTime
import io.github.bandoracer.rommio.ui.component.isRegularWidth
import io.github.bandoracer.rommio.ui.component.rememberCoverMark
import io.github.bandoracer.rommio.ui.component.sized
import io.github.bandoracer.rommio.ui.screen.collectAsStateWithLifecycleCompat
import io.github.bandoracer.rommio.ui.theme.RommioTheme
import io.github.bandoracer.rommio.util.resolveRemoteAssetUrl
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Durations in figures: "12 h 05 min", "2 min", "0 min" (never "2 m"). */
internal fun compactDuration(ms: Long): String = if (ms < 60_000L) "0 min" else formatPlayTime(ms)

internal fun streakText(days: Int): String = if (days == 1) "1 day" else "$days days"

/** Playtime per local day for the last [days] days, oldest first. */
internal fun dailyTotals(stats: PlayStats, days: Int = 28, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): List<Long> {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val totals = LongArray(days)
    stats.sessions.forEach { session ->
        val day = Instant.ofEpochMilli(session.endedAtEpochMs).atZone(zone).toLocalDate()
        val offset = ChronoUnit.DAYS.between(day, today)
        if (offset in 0 until days) totals[(days - 1 - offset).toInt()] += session.playedMs
    }
    return totals.toList()
}

/** Playtime, sessions and games within the last [days] days. */
internal data class StatsWindow(val playedMs: Long, val sessions: Int, val games: Int)

internal fun statsWindow(stats: PlayStats, days: Int = 28, now: Long = System.currentTimeMillis()): StatsWindow {
    val start = now - days * 86_400_000L
    val recent = stats.sessions.filter { it.endedAtEpochMs >= start }
    return StatsWindow(recent.sumOf { it.playedMs }, recent.size, recent.map { it.romId }.toSet().size)
}

/** Sessions grouped by the local day they ended, newest first. */
internal fun List<StatsSession>.byDay(zone: ZoneId = ZoneId.systemDefault()): List<Pair<LocalDate, List<StatsSession>>> =
    groupBy { Instant.ofEpochMilli(it.session.endedAtEpochMs).atZone(zone).toLocalDate() }
        .toSortedMap(compareByDescending { it })
        .map { (day, sessions) -> day to sessions.sortedByDescending { it.session.endedAtEpochMs } }

private fun LocalDate.epochMs(zone: ZoneId = ZoneId.systemDefault()): Long = atStartOfDay(zone).toInstant().toEpochMilli() + 43_200_000L

/**
 * Stats (docs/theme-layouts.md "Stats — two layouts"), from play recorded on this device. Aurora:
 * summary cards and time by game, lit by the most played game. Riso: the month in ink, then
 * recent sessions by day.
 */
@Composable
fun StatsScreen(
    container: AppContainer,
    imageBaseUrl: String?,
    onOpenGame: (romId: Int) -> Unit,
    /** Resume on the lead starts the most played game (asking first when the launch planner would). */
    launchFlow: io.github.bandoracer.rommio.ui.screen.LaunchFlow? = null,
) {
    val viewModel: StatsViewModel = viewModel(
        factory = viewModelFactory {
            initializer { StatsViewModel(container.repository, container.playActivity, container.playDataChanges) }
        },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycleCompat()
    LaunchedEffect(Unit) { viewModel.refresh() }
    val cover: (RomDto) -> String? = { resolveRemoteAssetUrl(imageBaseUrl, it.urlCover) }
    // The player is reachable from Stats: Resume on the most played game when it can start here.
    val resume: ((StatsGame) -> Unit)? = launchFlow?.let { flow ->
        { game ->
            val fileId = game.playableFileId
            if (fileId != null) {
                flow.play(io.github.bandoracer.rommio.ui.screen.LaunchTarget(game.rom.id, game.rom.displayName, fileId, cover(game.rom), platformSlug = game.rom.platformSlug))
            } else {
                onOpenGame(game.rom.id)
            }
        }
    }
    if (RommioTheme.isRiso) RisoStats(state, cover, onOpenGame, resume) else AuroraStats(state, cover, onOpenGame, resume)
}

/** Recorded sessions are authoritative; play from before recording is estimated (docs/play-data-local.md). */
private fun footnote(stats: PlayStats): String = if (stats.includesEstimates) {
    "Play time counts active play on this device; paused and background time is left out. Play from before sessions were recorded is estimated from automatic saves."
} else {
    "Play time counts active play on this device; paused and background time is left out."
}

@Composable
private fun StatsEmpty(state: StatsUiState) {
    EmptyState(
        title = if (state.isLoading) "Adding up your play" else "No play recorded yet",
        message = if (state.isLoading) "" else "Play a game on this device and your time, sessions and streak show up here.",
        icon = Icons.Outlined.BarChart,
        modifier = Modifier.padding(top = 40.dp),
    )
}

// ---- Aurora ----------------------------------------------------------------------------------

@Composable
private fun AuroraStats(state: StatsUiState, cover: (RomDto) -> String?, open: (Int) -> Unit, resume: ((StatsGame) -> Unit)?) {
    val theme = RommioTheme.current
    val c = theme.colors
    val stats = state.stats
    val top = state.games.firstOrNull()
    PageArtSubject(top?.rom?.let(cover), PageFieldStyle.PAGE)
    val regular = isRegularWidth()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = 1000.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .testTag("shell.statsList")
                .padding(horizontal = if (regular) 32.dp else theme.spacing.gutter)
                .padding(top = 8.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(theme.spacing.section),
        ) {
            PageHeader(title = "Stats")
            if (stats.isEmpty) {
                StatsEmpty(state)
                return@Column
            }
            top?.let { game ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button, onClickLabel = "Open ${game.rom.displayName}") { open(game.rom.id) }
                        .padding(vertical = 6.dp)
                        .testTag("stats.top"),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GlowingCover(cover(game.rom), game.rom.displayName, height = 150.dp, reflection = false)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        RoleText("Most played", theme.type.section, color = c.inkSecondary)
                        RoleText(game.rom.displayName, theme.type.title, size = 30f, color = c.ink, maxLines = 3)
                        RoleText(
                            "${formatPlayTime(game.total.playedMs)} · ${io.github.bandoracer.rommio.ui.component.Fmt.count(game.total.sessionCount, "session")}",
                            theme.type.secondary,
                            color = c.inkSecondary,
                        )
                        if (resume != null && game.playableFileId != null) {
                            io.github.bandoracer.rommio.ui.component.PrimaryButton(
                                text = "Resume",
                                icon = Icons.Outlined.PlayArrow,
                                onClick = { resume(game) },
                                large = false,
                                modifier = Modifier
                                    .padding(top = 6.dp)
                                    .semantics { contentDescription = "Resume ${game.rom.displayName}" }
                                    .testTag("stats.resume"),
                            )
                        }
                    }
                }
            }
            // Summary cards: two columns (four on regular width).
            val cards: List<@Composable (Modifier) -> Unit> = listOf(
                { m -> StatCard("Total playtime", compactDuration(stats.totalPlayedMs), m.testTag("stats.total"), icon = Icons.Outlined.Schedule) },
                { m -> StatCard("Sessions", java.text.NumberFormat.getIntegerInstance().format(stats.sessionCount), m, icon = Icons.Outlined.PlayCircle) },
                { m -> StatCard("Streak", streakText(stats.currentStreakDays), m, icon = Icons.Outlined.LocalFireDepartment) },
                { m ->
                    StatCard(
                        "Last played",
                        stats.lastPlayedAtEpochMs?.let { PlayFormat.shortAgo(it) } ?: "—",
                        m,
                        caption = state.sessions.firstOrNull()?.rom?.displayName,
                        icon = Icons.Outlined.CalendarToday,
                    )
                },
            )
            Column(Modifier.testTag("stats.summary"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                cards.chunked(if (regular) 4 else 2).forEach { row ->
                    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { card -> card(Modifier.weight(1f).fillMaxHeight()) }
                    }
                }
            }
            TimeByGame(state, cover, open)
            RecentByDay(state, cover, open)
            RoleText(footnote(stats), theme.type.caption, color = c.inkTertiary, modifier = Modifier.testTag("stats.footnote"))
        }
    }
}

@Composable
private fun TimeByGame(state: StatsUiState, cover: (RomDto) -> String?, open: (Int) -> Unit) {
    val theme = RommioTheme.current
    val games = state.games.take(8)
    val longest = maxOf(games.firstOrNull()?.total?.playedMs ?: 1L, 1L)
    ListGroup(title = "Time by game", meta = state.stats.games.size.toString()) {
        games.forEachIndexed { index, game ->
            if (index > 0) ListDivider(inset = 68.dp)
            val mark = rememberCoverMark(cover(game.rom))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClickLabel = "Open ${game.rom.displayName}") { open(game.rom.id) }
                    .padding(bottom = 10.dp)
                    .testTag("stats.game.${game.rom.id}"),
            ) {
                ListRow(
                    title = game.rom.displayName,
                    subtitle = "${game.total.sessionCount} session${if (game.total.sessionCount == 1) "" else "s"} · last ${PlayFormat.relative(game.total.lastPlayedAtEpochMs)}",
                    leading = { CoverThumb(cover(game.rom), game.rom.displayName) },
                    trailing = { RoleText(formatPlayTime(game.total.playedMs), theme.type.numeral, color = theme.colors.ink) },
                )
                val fraction = (game.total.playedMs.toFloat() / longest).coerceIn(0.02f, 1f)
                Box(Modifier.fillMaxWidth().padding(start = 68.dp, end = 16.dp)) {
                    Box(
                        Modifier
                            .fillMaxWidth(fraction)
                            .height(4.dp)
                            .dropShadow(mark.copy(alpha = 0.6f), 999.dp, 5.dp, 0.dp)
                            .clip(CircleShape)
                            .background(mark),
                    )
                }
            }
        }
    }
}

@Composable
private fun RecentByDay(state: StatsUiState, cover: (RomDto) -> String?, open: (Int) -> Unit) {
    val theme = RommioTheme.current
    val c = theme.colors
    val days = state.sessions.byDay().take(5)
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        SectionHeader(title = "Recent sessions", meta = state.stats.sessionCount.toString())
        days.forEach { (day, sessions) ->
            val total = sessions.sumOf { it.session.playedMs }
            val lights = sessions.distinctBy { it.rom.id }.map { rememberCoverMark(cover(it.rom)) }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    RoleText(PlayFormat.dayTitle(day.epochMs()), theme.type.headline, color = c.ink, modifier = Modifier.weight(1f).semantics { heading() })
                    RoleText(
                        "${sessions.size} session${if (sessions.size == 1) "" else "s"} · ${formatPlayTime(total)}",
                        theme.type.numeral,
                        size = 12.5f,
                        color = c.inkSecondary,
                    )
                }
                LightStrip(lights)
                ListGroup {
                    sessions.forEachIndexed { index, item ->
                        if (index > 0) ListDivider(inset = 68.dp)
                        ListRow(
                            title = item.rom.displayName,
                            subtitle = listOfNotNull(formatClockTime(item.session.endedAtEpochMs), item.session.endReason?.let(::sessionEndLabel)).joinToString(" · "),
                            leading = { CoverThumb(cover(item.rom), item.rom.displayName) },
                            trailing = { RoleText(formatPlayTime(item.session.playedMs), theme.type.numeral, color = c.inkSecondary) },
                            onClick = { open(item.rom.id) },
                        )
                    }
                }
            }
        }
    }
}

// ---- Riso ------------------------------------------------------------------------------------

@Composable
private fun RisoStats(state: StatsUiState, cover: (RomDto) -> String?, open: (Int) -> Unit, resume: ((StatsGame) -> Unit)?) {
    val theme = RommioTheme.current
    val c = theme.colors
    val stats = state.stats
    PageArtSubject(null, PageFieldStyle.QUIET)
    val regular = isRegularWidth()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = 1100.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .testTag("shell.statsList")
                .padding(horizontal = theme.spacing.gutter)
                .padding(top = 8.dp, bottom = 40.dp),
        ) {
            PageHeader(title = "Stats", modifier = Modifier.padding(bottom = 6.dp))
            if (stats.isEmpty) {
                StatsEmpty(state)
                return@Column
            }
            RoleText(
                "${io.github.bandoracer.rommio.ui.component.Fmt.count(stats.sessionCount, "session")} recorded · kept on this device",
                theme.type.body,
                size = 15f,
                italic = true,
                color = c.inkSecondary,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            if (regular) {
                Row(Modifier.height(IntrinsicSize.Min)) {
                    Column(Modifier.weight(1f).padding(end = 20.dp)) {
                        Month(stats)
                        RisoLead(state, resume)
                        Games(state, open)
                    }
                    ColumnRule()
                    Column(Modifier.weight(1f).padding(start = 20.dp)) { Diary(state, cover, open) }
                }
            } else {
                Month(stats)
                RisoLead(state, resume)
                Games(state, open)
                Diary(state, cover, open)
            }
            RoleText(footnote(stats), theme.type.caption, color = c.inkSecondary, modifier = Modifier.padding(top = 20.dp).testTag("stats.footnote"))
        }
    }
}

/** The month in ink: a sentence of figures, a bar per day, and the figures in a ruled box. */
@Composable
private fun Month(stats: PlayStats) {
    val theme = RommioTheme.current
    val c = theme.colors
    val window = statsWindow(stats)
    val daily = dailyTotals(stats)
    val longest = maxOf(daily.maxOrNull() ?: 1L, 1L)
    Column {
        PrintRule(weight = RuleWeight.HEAVY)
        Column(Modifier.padding(top = 10.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                RoleText("This month", theme.type.section, size = 18f, color = c.ink, modifier = Modifier.weight(1f).semantics { heading() })
                RoleText("Last 28 days", theme.type.label, size = 10f, color = c.inkSecondary)
            }
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(formatPlayTime(window.playedMs)) }
                    append(" across ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("${window.sessions} session${if (window.sessions == 1) "" else "s"}") }
                    append(" and ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(PlayFormat.games(window.games)) }
                    append(if (stats.currentStreakDays > 0) ". Streak: ${streakText(stats.currentStreakDays)}." else ".")
                },
                style = theme.type.body.style.sized(17f),
                color = c.ink,
                modifier = Modifier.testTag("stats.total"),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .semantics(mergeDescendants = true) { contentDescription = "Playtime per day for the last 28 days" },
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                daily.forEachIndexed { index, value ->
                    if (value > 0) {
                        Box(
                            Modifier
                                .weight(1f)
                                .height(maxOf(4.dp, 54.dp * (value.toFloat() / longest)))
                                .background(if (index >= daily.size - 7) c.accent1 else c.accent2),
                        )
                    } else {
                        Box(Modifier.weight(1f).height(1.5.dp).background(c.hairline))
                    }
                }
            }
            Row {
                RoleText("4 weeks ago", theme.type.label, size = 9.5f, color = c.inkSecondary, modifier = Modifier.weight(1f))
                RoleText("This week", theme.type.label, size = 9.5f, color = c.inkSecondary)
            }
            Row(
                Modifier
                    .padding(top = 6.dp)
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .border(2.dp, c.ink)
                    .testTag("stats.summary"),
            ) {
                listOf(
                    "Total" to compactDuration(stats.totalPlayedMs),
                    "Sessions" to java.text.NumberFormat.getIntegerInstance().format(stats.sessionCount),
                    "Streak" to streakText(stats.currentStreakDays),
                ).forEachIndexed { index, (label, value) ->
                    if (index > 0) ColumnRule()
                    StatCard(label, value, Modifier.weight(1f))
                }
            }
        }
    }
}

/** Under the month: the most played game with Resume (when it can start on this device). */
@Composable
private fun RisoLead(state: StatsUiState, resume: ((StatsGame) -> Unit)?) {
    val game = state.games.firstOrNull() ?: return
    if (resume == null || game.playableFileId == null) return
    val theme = RommioTheme.current
    Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        io.github.bandoracer.rommio.ui.component.Kicker("Most played", detail = formatPlayTime(game.total.playedMs), fill = io.github.bandoracer.rommio.ui.component.KickerFill.PINK)
        io.github.bandoracer.rommio.ui.component.DisplayText(game.rom.displayName, size = 34f, maxLines = 2)
        io.github.bandoracer.rommio.ui.component.PrimaryButton(
            text = "Resume",
            icon = Icons.Outlined.PlayArrow,
            onClick = { resume(game) },
            large = false,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Resume ${game.rom.displayName}" }
                .testTag("stats.resume"),
        )
        PrintRule(weight = RuleWeight.FINE, modifier = Modifier.padding(top = 6.dp))
    }
    Spacer(Modifier.height(theme.spacing.stack))
}

@Composable
private fun Games(state: StatsUiState, open: (Int) -> Unit) {
    val theme = RommioTheme.current
    val c = theme.colors
    Column {
        SectionHeader(title = "Time by game", meta = state.stats.games.size.toString(), modifier = Modifier.padding(top = 20.dp))
        state.games.take(6).forEachIndexed { index, game ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClickLabel = "Open ${game.rom.displayName}") { open(game.rom.id) }
                    .testTag("stats.game.${game.rom.id}"),
            ) {
                Row(Modifier.fillMaxWidth().heightIn(min = 46.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    RoleText("${index + 1}", theme.type.statValue, size = 22f, color = c.accent2, modifier = Modifier.width(22.dp).alignByBaseline())
                    io.github.bandoracer.rommio.ui.component.LeaderLayout(
                        modifier = Modifier.weight(1f).alignByBaseline(),
                        start = { RoleText(game.rom.displayName, theme.type.bodyEmphasis, size = 16f, color = c.ink, maxLines = 1) },
                        end = { RoleText(formatPlayTime(game.total.playedMs), theme.type.numeral, size = 12f, color = c.ink) },
                    )
                }
                PrintRule(weight = RuleWeight.HAIRLINE)
            }
        }
    }
}

/** Recent sessions by day, as diary entries. */
@Composable
private fun Diary(state: StatsUiState, cover: (RomDto) -> String?, open: (Int) -> Unit) {
    val theme = RommioTheme.current
    val c = theme.colors
    Column {
        state.sessions.byDay().take(6).forEach { (day, sessions) ->
            Column(Modifier.padding(top = 18.dp)) {
                Row(Modifier.padding(bottom = 7.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                    // The full date only when the title isn't already the date (§4 day groups).
                    val group = PlayFormat.dayGroup(day)
                    RoleText(group.title, theme.type.headline, size = 26f, color = c.ink, modifier = Modifier.semantics { heading() })
                    RoleText(group.caption.orEmpty(), theme.type.caption, size = 14f, italic = true, color = c.inkSecondary, maxLines = 1, modifier = Modifier.weight(1f))
                    RoleText("${sessions.size} ${if (sessions.size == 1) "entry" else "entries"}", theme.type.label, size = 10f, color = c.inkSecondary)
                }
                PrintRule(weight = RuleWeight.KEYLINE)
            }
            sessions.forEachIndexed { index, item ->
                Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Open ${item.rom.displayName}") { open(item.rom.id) }) {
                    Row(Modifier.padding(vertical = 11.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        RoleText(formatClockTime(item.session.endedAtEpochMs), theme.type.numeral, size = 11.5f, color = c.ink, maxLines = 1, modifier = Modifier.width(62.dp).padding(top = 3.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            RoleText(item.rom.displayName, theme.type.headline, size = 19f, color = c.ink, maxLines = 2)
                            RoleText("${formatPlayTime(item.session.playedMs)} on this device.", theme.type.body, size = 15f, color = c.ink)
                            RoleText(listOfNotNull(item.rom.platformSlug.uppercase(), item.session.endReason?.let(::sessionEndLabel)).joinToString(" · "),
                                theme.type.label, size = 9.5f, color = c.inkSecondary)
                        }
                        MountedPrint(cover(item.rom), item.rom.displayName, 52.dp, 68.dp, rotation = if (index % 2 == 0) -1.2f else 1.4f)
                    }
                    PrintRule(weight = RuleWeight.HAIRLINE)
                }
            }
        }
    }
}

internal fun sessionEndLabel(reason: io.github.bandoracer.rommio.domain.playdata.PlaySessionEndReason): String = when (reason) {
    io.github.bandoracer.rommio.domain.playdata.PlaySessionEndReason.RESUME -> "Left with a resume point"
    io.github.bandoracer.rommio.domain.playdata.PlaySessionEndReason.STATE -> "Left after a save state"
    io.github.bandoracer.rommio.domain.playdata.PlaySessionEndReason.QUIT -> "Left without a resume point"
    io.github.bandoracer.rommio.domain.playdata.PlaySessionEndReason.BACKGROUND -> "Ended in the background"
}
