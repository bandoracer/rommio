package io.github.bandoracer.rommio.ui.screen.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.remember
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.bandoracer.rommio.AppContainer
import io.github.bandoracer.rommio.domain.stats.PlayStats
import io.github.bandoracer.rommio.ui.component.ColumnRule
import io.github.bandoracer.rommio.ui.component.CoverThumb
import io.github.bandoracer.rommio.ui.component.EmptyState
import io.github.bandoracer.rommio.ui.component.GlowingCover
import io.github.bandoracer.rommio.ui.component.LightStrip
import io.github.bandoracer.rommio.ui.component.ListDivider
import io.github.bandoracer.rommio.ui.component.ListGroup
import io.github.bandoracer.rommio.ui.component.ListRow
import io.github.bandoracer.rommio.ui.component.LitPage
import io.github.bandoracer.rommio.ui.component.MountedPrint
import io.github.bandoracer.rommio.ui.component.PageFieldStyle
import io.github.bandoracer.rommio.ui.component.PageHeader
import io.github.bandoracer.rommio.ui.component.PlayFormat
import io.github.bandoracer.rommio.ui.component.PrintRule
import io.github.bandoracer.rommio.ui.component.RoleText
import io.github.bandoracer.rommio.ui.component.RuleWeight
import io.github.bandoracer.rommio.ui.component.SectionHeader
import io.github.bandoracer.rommio.ui.component.StatCard
import io.github.bandoracer.rommio.ui.component.dropShadow
import io.github.bandoracer.rommio.ui.component.formatPlayTime
import io.github.bandoracer.rommio.ui.component.isRegularWidth
import io.github.bandoracer.rommio.ui.component.rememberCoverMark
import io.github.bandoracer.rommio.ui.component.sized
import io.github.bandoracer.rommio.ui.theme.RommioTheme
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

/** What Stats can do. */
private class StatsActions(val open: (Int) -> Unit, val resume: ((StatsTop) -> Unit)?)

/**
 * Stats (docs/theme-layouts.md "Stats — two layouts"), from play recorded on this device. Aurora:
 * summary cards and time by game, lit by the most played game. Riso: the month in ink, then
 * recent sessions by day. The page is built by [StatsViewModel] ([StatsPage]); sessions are lazy.
 */
@Composable
fun StatsScreen(
    container: AppContainer,
    @Suppress("UNUSED_PARAMETER") imageBaseUrl: String?,
    onOpenGame: (romId: Int) -> Unit,
    /** Resume on the lead starts the most played game (asking first when the launch planner would). */
    launchFlow: io.github.bandoracer.rommio.ui.screen.LaunchFlow? = null,
) {
    val viewModel: StatsViewModel = viewModel(
        factory = viewModelFactory {
            initializer { StatsViewModel(container.repository, container.playActivity, container.playDataChanges) }
        },
    )
    val page by viewModel.page.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refresh() }
    // The player is reachable from Stats: Resume on the most played game when it can start here.
    val actions = remember(onOpenGame, launchFlow) {
        StatsActions(
            open = onOpenGame,
            resume = launchFlow?.let { flow ->
                { game: StatsTop ->
                    val fileId = game.playableFileId
                    if (fileId != null) {
                        flow.play(io.github.bandoracer.rommio.ui.screen.LaunchTarget(game.id, game.title, fileId, game.coverUrl, platformSlug = game.platformSlug))
                    } else {
                        onOpenGame(game.id)
                    }
                }
            },
        )
    }
    if (RommioTheme.isRiso) {
        LitPage(null, PageFieldStyle.QUIET) { RisoStats(page, actions) }
    } else {
        LitPage(page.top?.coverUrl, PageFieldStyle.PAGE) { AuroraStats(page, actions) }
    }
}

@Composable
private fun StatsEmpty(page: StatsPage) {
    EmptyState(
        title = if (page.isLoading) "Adding up your play" else "No play recorded yet",
        message = if (page.isLoading) "" else "Play a game on this device and your time, sessions and streak show up here.",
        icon = Icons.Outlined.BarChart,
        modifier = Modifier.padding(top = 40.dp),
    )
}

// ---- Aurora ----------------------------------------------------------------------------------

@Composable
private fun AuroraStats(page: StatsPage, actions: StatsActions) {
    val theme = RommioTheme.current
    val regular = isRegularWidth()
    val gutter = if (regular) 32.dp else theme.spacing.gutter
    val section = theme.spacing.section
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.widthIn(max = 1000.dp).fillMaxSize().testTag("shell.statsList"),
            contentPadding = PaddingValues(start = gutter, end = gutter, top = 8.dp, bottom = 40.dp),
        ) {
            item(key = "header", contentType = "header") { PageHeader(title = "Stats") }
            if (page.isEmpty) {
                item(key = "empty", contentType = "empty") { Box(Modifier.padding(top = section)) { StatsEmpty(page) } }
                return@LazyColumn
            }
            page.top?.let { top -> item(key = "top", contentType = "top") { AuroraTop(top, actions, Modifier.padding(top = section)) } }
            item(key = "summary", contentType = "summary") { SummaryCards(page, regular, Modifier.padding(top = section)) }
            item(key = "games", contentType = "games") { TimeByGame(page, actions.open, Modifier.padding(top = section)) }
            item(key = "sessionsHeader", contentType = "sectionHeader") {
                SectionHeader(title = "Recent sessions", meta = page.sessionCount.toString(), modifier = Modifier.padding(top = section))
            }
            // A day of sessions per lazy item (a day is one glass group).
            itemsIndexed(page.days.take(5), key = { _, day -> "day/${day.key}" }, contentType = { _, _ -> "day" }) { _, day ->
                AuroraDay(day, actions.open, Modifier.padding(top = 18.dp))
            }
            item(key = "footnote", contentType = "footnote") {
                RoleText(page.footnote, theme.type.caption, color = theme.colors.inkTertiary, modifier = Modifier.padding(top = section).testTag("stats.footnote"))
            }
        }
    }
}

@Composable
private fun AuroraTop(game: StatsTop, actions: StatsActions, modifier: Modifier) {
    val theme = RommioTheme.current
    val c = theme.colors
    Row(
        modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = "Open ${game.title}") { actions.open(game.id) }
            .padding(vertical = 6.dp)
            .testTag("stats.top"),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlowingCover(game.coverUrl, game.title, height = 150.dp, reflection = false)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            RoleText("Most played", theme.type.section, color = c.inkSecondary)
            RoleText(game.title, theme.type.title, size = 30f, color = c.ink, maxLines = 3)
            RoleText(game.line, theme.type.secondary, color = c.inkSecondary)
            val resume = actions.resume
            if (resume != null && game.playableFileId != null) {
                io.github.bandoracer.rommio.ui.component.PrimaryButton(
                    text = "Resume",
                    icon = Icons.Outlined.PlayArrow,
                    onClick = { resume(game) },
                    large = false,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .semantics { contentDescription = "Resume ${game.title}" }
                        .testTag("stats.resume"),
                )
            }
        }
    }
}

/** Summary cards: two columns (four on regular width). */
@Composable
private fun SummaryCards(page: StatsPage, regular: Boolean, modifier: Modifier) {
    val cards: List<@Composable (Modifier) -> Unit> = listOf(
        { m -> StatCard("Total playtime", page.totalText, m.testTag("stats.total"), icon = Icons.Outlined.Schedule) },
        { m -> StatCard("Sessions", page.sessionsText, m, icon = Icons.Outlined.PlayCircle) },
        { m -> StatCard("Streak", page.streakText, m, icon = Icons.Outlined.LocalFireDepartment) },
        { m -> StatCard("Last played", page.lastPlayedText, m, caption = page.lastPlayedCaption, icon = Icons.Outlined.CalendarToday) },
    )
    Column(modifier.testTag("stats.summary"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        cards.chunked(if (regular) 4 else 2).forEach { row ->
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { card -> card(Modifier.weight(1f).fillMaxHeight()) }
            }
        }
    }
}

@Composable
private fun TimeByGame(page: StatsPage, open: (Int) -> Unit, modifier: Modifier) {
    val theme = RommioTheme.current
    ListGroup(title = "Time by game", meta = page.gameCount.toString(), modifier = modifier) {
        page.games.forEachIndexed { index, game ->
            if (index > 0) ListDivider(inset = 68.dp)
            GameTimeRow(game, open, theme)
        }
    }
}

@Composable
private fun GameTimeRow(game: StatsGameRow, open: (Int) -> Unit, theme: io.github.bandoracer.rommio.ui.theme.RommioThemeValue) {
    val mark = rememberCoverMark(game.coverUrl)
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = "Open ${game.title}") { open(game.id) }
            .padding(bottom = 10.dp)
            .testTag("stats.game.${game.id}"),
    ) {
        ListRow(
            title = game.title,
            subtitle = game.subtitle,
            leading = { CoverThumb(game.coverUrl, game.title) },
            trailing = { RoleText(game.playedText, theme.type.numeral, color = theme.colors.ink) },
        )
        Box(Modifier.fillMaxWidth().padding(start = 68.dp, end = 16.dp)) {
            Box(
                Modifier
                    .fillMaxWidth(game.fraction)
                    .height(4.dp)
                    .dropShadow(mark.copy(alpha = 0.6f), 999.dp, 5.dp, 0.dp)
                    .clip(CircleShape)
                    .background(mark),
            )
        }
    }
}

@Composable
private fun AuroraDay(day: StatsDay, open: (Int) -> Unit, modifier: Modifier) {
    val theme = RommioTheme.current
    val c = theme.colors
    val lights = day.sessions.distinctBy { it.gameId }.map { rememberCoverMark(it.coverUrl) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            RoleText(day.title, theme.type.headline, color = c.ink, modifier = Modifier.weight(1f).semantics { heading() })
            RoleText(day.summary, theme.type.numeral, size = 12.5f, color = c.inkSecondary)
        }
        LightStrip(lights)
        ListGroup {
            day.sessions.forEachIndexed { index, item ->
                if (index > 0) ListDivider(inset = 68.dp)
                ListRow(
                    title = item.title,
                    subtitle = item.subtitle,
                    leading = { CoverThumb(item.coverUrl, item.title) },
                    trailing = { RoleText(item.playedText, theme.type.numeral, color = c.inkSecondary) },
                    onClick = { open(item.gameId) },
                )
            }
        }
    }
}

// ---- Riso ------------------------------------------------------------------------------------

@Composable
private fun RisoStats(page: StatsPage, actions: StatsActions) {
    val theme = RommioTheme.current
    val c = theme.colors
    val regular = isRegularWidth()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.widthIn(max = 1100.dp).fillMaxSize().testTag("shell.statsList"),
            contentPadding = PaddingValues(start = theme.spacing.gutter, end = theme.spacing.gutter, top = 8.dp, bottom = 40.dp),
        ) {
            item(key = "header", contentType = "header") { PageHeader(title = "Stats", modifier = Modifier.padding(bottom = 6.dp)) }
            if (page.isEmpty) {
                item(key = "empty", contentType = "empty") { StatsEmpty(page) }
                return@LazyColumn
            }
            item(key = "count", contentType = "count") {
                RoleText(page.sessionCountLine, theme.type.body, size = 15f, italic = true, color = c.inkSecondary, modifier = Modifier.padding(bottom = 12.dp))
            }
            if (regular) {
                // The broadsheet's two ruled columns are one item (they share their height).
                item(key = "columns", contentType = "columns") {
                    Row(Modifier.height(IntrinsicSize.Min)) {
                        Column(Modifier.weight(1f).padding(end = 20.dp)) {
                            page.month?.let { Month(page, it) }
                            RisoLead(page.top, actions.resume)
                            Games(page, actions.open)
                        }
                        ColumnRule()
                        Column(Modifier.weight(1f).padding(start = 20.dp)) {
                            page.days.forEach { day ->
                                DiaryDayHeader(day)
                                day.sessions.forEachIndexed { index, item -> DiaryEntry(item, index, actions.open) }
                            }
                        }
                    }
                }
            } else {
                page.month?.let { month -> item(key = "month", contentType = "month") { Month(page, month) } }
                if (page.top?.playableFileId != null && actions.resume != null) {
                    item(key = "lead", contentType = "lead") { RisoLead(page.top, actions.resume) }
                }
                item(key = "games", contentType = "games") { Games(page, actions.open) }
                page.days.forEach { day -> diaryDay(day, actions.open) }
            }
            item(key = "footnote", contentType = "footnote") {
                RoleText(page.footnote, theme.type.caption, color = c.inkSecondary, modifier = Modifier.padding(top = 20.dp).testTag("stats.footnote"))
            }
        }
    }
}

/** A day of the diary as lazy items: its heading, then each session. */
private fun LazyListScope.diaryDay(day: StatsDay, open: (Int) -> Unit) {
    item(key = "day/${day.key}", contentType = "dayHeader") { DiaryDayHeader(day) }
    itemsIndexed(day.sessions, key = { _, item -> "session/${item.key}" }, contentType = { _, _ -> "session" }) { index, item ->
        DiaryEntry(item, index, open)
    }
}

/** The month in ink: a sentence of figures, a bar per day, and the figures in a ruled box. */
@Composable
private fun Month(page: StatsPage, month: StatsMonth) {
    val theme = RommioTheme.current
    val c = theme.colors
    Column {
        PrintRule(weight = RuleWeight.HEAVY)
        Column(Modifier.padding(top = 10.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                RoleText("This month", theme.type.section, size = 18f, color = c.ink, modifier = Modifier.weight(1f).semantics { heading() })
                RoleText("Last 28 days", theme.type.label, size = 10f, color = c.inkSecondary)
            }
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(month.playedText) }
                    append(" across ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(month.sessionsText) }
                    append(" and ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(month.gamesText) }
                    append(month.streakSentence)
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
                month.bars.forEachIndexed { index, value ->
                    if (value > 0f) {
                        Box(
                            Modifier
                                .weight(1f)
                                .height(maxOf(4.dp, 54.dp * value))
                                .background(if (index >= month.bars.size - 7) c.accent1 else c.accent2),
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
                    "Total" to page.totalText,
                    "Sessions" to page.sessionsText,
                    "Streak" to page.streakText,
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
private fun RisoLead(game: StatsTop?, resume: ((StatsTop) -> Unit)?) {
    if (game == null || resume == null || game.playableFileId == null) return
    val theme = RommioTheme.current
    Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        io.github.bandoracer.rommio.ui.component.Kicker("Most played", detail = game.playedText, fill = io.github.bandoracer.rommio.ui.component.KickerFill.PINK)
        io.github.bandoracer.rommio.ui.component.DisplayText(game.title, size = 34f, maxLines = 2)
        io.github.bandoracer.rommio.ui.component.PrimaryButton(
            text = "Resume",
            icon = Icons.Outlined.PlayArrow,
            onClick = { resume(game) },
            large = false,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Resume ${game.title}" }
                .testTag("stats.resume"),
        )
        PrintRule(weight = RuleWeight.FINE, modifier = Modifier.padding(top = 6.dp))
    }
    Spacer(Modifier.height(theme.spacing.stack))
}

@Composable
private fun Games(page: StatsPage, open: (Int) -> Unit) {
    val theme = RommioTheme.current
    val c = theme.colors
    Column {
        SectionHeader(title = "Time by game", meta = page.gameCount.toString(), modifier = Modifier.padding(top = 20.dp))
        page.games.take(6).forEachIndexed { index, game ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClickLabel = "Open ${game.title}") { open(game.id) }
                    .testTag("stats.game.${game.id}"),
            ) {
                Row(Modifier.fillMaxWidth().heightIn(min = 46.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    RoleText("${index + 1}", theme.type.statValue, size = 22f, color = c.accent2, modifier = Modifier.width(22.dp).alignByBaseline())
                    io.github.bandoracer.rommio.ui.component.LeaderLayout(
                        modifier = Modifier.weight(1f).alignByBaseline(),
                        start = { RoleText(game.title, theme.type.bodyEmphasis, size = 16f, color = c.ink, maxLines = 1) },
                        end = { RoleText(game.playedText, theme.type.numeral, size = 12f, color = c.ink) },
                    )
                }
                PrintRule(weight = RuleWeight.HAIRLINE)
            }
        }
    }
}

/** A diary day's heading: the day, its date when the title isn't it, the entries, a keyline. */
@Composable
private fun DiaryDayHeader(day: StatsDay) {
    val theme = RommioTheme.current
    val c = theme.colors
    Column(Modifier.padding(top = 18.dp)) {
        Row(Modifier.padding(bottom = 7.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
            RoleText(day.groupTitle, theme.type.headline, size = 26f, color = c.ink, modifier = Modifier.semantics { heading() })
            RoleText(day.groupCaption, theme.type.caption, size = 14f, italic = true, color = c.inkSecondary, maxLines = 1, modifier = Modifier.weight(1f))
            RoleText(day.entries, theme.type.label, size = 10f, color = c.inkSecondary)
        }
        PrintRule(weight = RuleWeight.KEYLINE)
    }
}

@Composable
private fun DiaryEntry(item: StatsSessionRow, index: Int, open: (Int) -> Unit) {
    val theme = RommioTheme.current
    val c = theme.colors
    Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Open ${item.title}") { open(item.gameId) }) {
        Row(Modifier.padding(vertical = 11.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RoleText(item.clock, theme.type.numeral, size = 11.5f, color = c.ink, maxLines = 1, modifier = Modifier.width(62.dp).padding(top = 3.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                RoleText(item.title, theme.type.headline, size = 19f, color = c.ink, maxLines = 2)
                RoleText("${item.playedText} on this device.", theme.type.body, size = 15f, color = c.ink)
                RoleText(item.label, theme.type.label, size = 9.5f, color = c.inkSecondary)
            }
            MountedPrint(item.coverUrl, item.title, 52.dp, 68.dp, rotation = if (index % 2 == 0) -1.2f else 1.4f)
        }
        PrintRule(weight = RuleWeight.HAIRLINE)
    }
}

internal fun sessionEndLabel(reason: io.github.bandoracer.rommio.domain.playdata.PlaySessionEndReason): String = when (reason) {
    io.github.bandoracer.rommio.domain.playdata.PlaySessionEndReason.RESUME -> "Left with a resume point"
    io.github.bandoracer.rommio.domain.playdata.PlaySessionEndReason.STATE -> "Left after a save state"
    io.github.bandoracer.rommio.domain.playdata.PlaySessionEndReason.QUIT -> "Left without a resume point"
    io.github.bandoracer.rommio.domain.playdata.PlaySessionEndReason.BACKGROUND -> "Ended in the background"
}
