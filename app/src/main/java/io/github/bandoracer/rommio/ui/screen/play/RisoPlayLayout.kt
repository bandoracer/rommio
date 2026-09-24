package io.github.bandoracer.rommio.ui.screen.play

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.bandoracer.rommio.ui.component.ColumnRule
import io.github.bandoracer.rommio.ui.component.ContextMenuBox
import io.github.bandoracer.rommio.ui.component.Dateline
import io.github.bandoracer.rommio.ui.component.DisplayText
import io.github.bandoracer.rommio.ui.component.EmptyState
import io.github.bandoracer.rommio.ui.component.FigureCaption
import io.github.bandoracer.rommio.ui.component.GameCover
import io.github.bandoracer.rommio.ui.component.Kicker
import io.github.bandoracer.rommio.ui.component.KickerFill
import io.github.bandoracer.rommio.ui.component.LoadingSkeletonPanel
import io.github.bandoracer.rommio.ui.component.MenuAction
import io.github.bandoracer.rommio.ui.component.PageArtSubject
import io.github.bandoracer.rommio.ui.component.PageFieldStyle
import io.github.bandoracer.rommio.ui.component.PlayFormat
import io.github.bandoracer.rommio.ui.component.PrimaryButton
import io.github.bandoracer.rommio.ui.component.PrintRule
import io.github.bandoracer.rommio.ui.component.RatioColumns
import io.github.bandoracer.rommio.ui.component.RoleText
import io.github.bandoracer.rommio.ui.component.RommioProgressBar
import io.github.bandoracer.rommio.ui.component.RuleWeight
import io.github.bandoracer.rommio.ui.component.SectionHeader
import io.github.bandoracer.rommio.ui.component.StatusChip
import io.github.bandoracer.rommio.ui.component.Tone
import io.github.bandoracer.rommio.ui.component.formatPlayTime
import io.github.bandoracer.rommio.ui.component.isRegularWidth
import io.github.bandoracer.rommio.ui.theme.RommioTheme
import java.time.LocalDate

/**
 * Riso Play: set like a front page. The game to resume leads; the other games in progress run as
 * briefs beside a pinned collection; recent saves close the page as a list by day (recent
 * sessions are on Stats). On a regular width it becomes one broadsheet: the lead across the
 * canvas, the briefs and recent saves in a rail.
 */
@Composable
fun RisoPlayLayout(state: PlayUiState, actions: PlayActions) {
    val theme = RommioTheme.current
    val regular = isRegularWidth()
    // Riso never lights a page; the field is paper.
    PageArtSubject(null, PageFieldStyle.QUIET)
    val gutter = if (regular) 28.dp else theme.spacing.gutter
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .testTag("shell.playList"),
    ) {
        PlayToolbar(state, actions, gutter)
        Box(Modifier.padding(horizontal = gutter).padding(bottom = 40.dp)) {
            if (regular) Broadsheet(state, actions) else OneColumn(state, actions)
        }
    }
}

private val PlayUiState.lead: InProgressGame? get() = continuePlaying.firstOrNull()
private val PlayUiState.briefs: List<InProgressGame> get() = continuePlaying.drop(1).take(4)

// ---- Compact: one column ---------------------------------------------------------------------

@Composable
private fun OneColumn(state: PlayUiState, actions: PlayActions) {
    Column {
        Masthead(regular = false)
        Dateline(PlayFormat.longDate(LocalDate.now()), Modifier.padding(top = 6.dp), trailing = state.serverName)
        Ears(state, actions)
        if (state.isLoading && !state.hasContent) {
            LoadingSkeletonPanel(showArtwork = true, lines = 3, modifier = Modifier.padding(top = 14.dp))
        }
        state.lead?.let { LeadStory(it, actions, big = false, modifier = Modifier.padding(top = 14.dp), conflictChip = state.conflictChip(it.rom.id)) }
        BriefsAndCollection(state, actions, Modifier.padding(top = 20.dp))
        RisoRecentSaves(state, actions, Modifier.padding(top = 26.dp))
        Notices(state, Modifier.padding(top = 20.dp))
    }
}

// ---- Regular: a broadsheet -------------------------------------------------------------------

@Composable
private fun Broadsheet(state: PlayUiState, actions: PlayActions) {
    val theme = RommioTheme.current
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Box(Modifier.weight(1f)) {
                Box(Modifier.widthIn(max = 240.dp).border(1.5.dp, theme.colors.ink).padding(10.dp)) { DownloadsEar(state, actions) }
            }
            Masthead(regular = true)
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                Box(Modifier.widthIn(max = 240.dp).border(1.5.dp, theme.colors.ink).padding(10.dp)) { SyncEar(state) }
            }
        }
        Dateline(PlayFormat.longDate(LocalDate.now()), Modifier.padding(top = 10.dp), trailing = state.serverName)
        // The rail is 330 wide on a broad canvas and gives way (to 36 %) on a narrower one; the lead
        // sets at `display` 76 only when its column is at least 520 wide.
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        val rail = minOf(330.dp, maxWidth * 0.36f)
        val big = maxWidth - rail - 41.dp >= 520.dp
        Row(Modifier.padding(top = 14.dp).height(IntrinsicSize.Min)) {
            Column(Modifier.weight(1f).padding(end = 20.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                state.lead?.let { LeadStory(it, actions, big = big, conflictChip = state.conflictChip(it.rom.id)) }
                state.pinnedCollection?.let { CollectionColumn(it, actions, wide = true, threeColumns = big) }
                Notices(state)
            }
            ColumnRule()
            Column(Modifier.width(rail).padding(start = 20.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                if (state.briefs.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SectionHeader(title = "In progress", meta = state.briefs.size.toString())
                        state.briefs.forEach { Brief(it, actions) }
                    }
                }
                RisoRecentSaves(state, actions)
            }
        }
        }
    }
}

// ---- Masthead and ears -----------------------------------------------------------------------

@Composable
private fun Masthead(regular: Boolean) {
    DisplayText("Play", size = if (regular) 88f else 52f, plateOffset = 1.25f, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
}

@Composable
private fun Ears(state: PlayUiState, actions: PlayActions) {
    val theme = RommioTheme.current
    Column {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(Modifier.weight(1f).padding(vertical = 9.dp).padding(end = 10.dp)) { DownloadsEar(state, actions) }
            ColumnRule()
            Box(Modifier.weight(1f).padding(vertical = 9.dp).padding(start = 12.dp)) { SyncEar(state) }
        }
        PrintRule(weight = RuleWeight.KEYLINE)
    }
}

@Composable
private fun EarLabel(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    val theme = RommioTheme.current
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = theme.colors.inkSecondary, modifier = Modifier.size(12.dp))
        RoleText(text, theme.type.label, size = 9.5f, color = theme.colors.inkSecondary, maxLines = 1)
    }
}

@Composable
private fun DownloadsEar(state: PlayUiState, actions: PlayActions) {
    val theme = RommioTheme.current
    val c = theme.colors
    val download = state.download
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = "Open downloads", onClick = actions.openDownloads)
            .testTag("play.downloadsEar"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        EarLabel("Downloads", Icons.Outlined.Download)
        when {
            download != null && download.name.isNotEmpty() -> {
                RoleText("${download.name} · ${(download.progress * 100).toInt()}%", theme.type.bodyEmphasis, size = 14f, color = c.ink, maxLines = 1)
                RoleText(queueLine(download), theme.type.label, size = 9.5f, color = c.inkSecondary, maxLines = 1)
                RommioProgressBar(download.progress, tone = Tone.LIVE, height = 5.dp)
            }
            download != null -> RoleText(queueLine(download), theme.type.bodyEmphasis, size = 14f, color = c.ink, maxLines = 1)
            else -> {
                RoleText("Nothing downloading", theme.type.bodyEmphasis, size = 14f, color = c.ink, maxLines = 1)
                RoleText("${PlayFormat.games(state.storageSummary.installedGameCount)} on this device", theme.type.label, size = 9.5f, color = c.inkSecondary, maxLines = 1)
            }
        }
    }
}

@Composable
private fun SyncEar(state: PlayUiState) {
    val theme = RommioTheme.current
    val c = theme.colors
    val offline = state.offlineState.isOffline
    Column(Modifier.semantics(mergeDescendants = true) {}.testTag("play.syncLine"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        EarLabel(if (offline) "Offline" else "Online", if (offline) Icons.Outlined.WifiOff else Icons.Outlined.Wifi)
        RoleText("${PlayFormat.games(state.storageSummary.installedGameCount)} ready", theme.type.bodyEmphasis, size = 14f, color = c.ink, maxLines = 1)
        RoleText(
            state.offlineState.lastFullSyncAtEpochMs?.let { "Synced ${PlayFormat.relative(it)}" }
                ?: if (offline) "Waiting for a connection" else "Library not synced yet",
            theme.type.label,
            size = 9.5f,
            color = c.inkSecondary,
            maxLines = 1,
        )
    }
}

private fun queueLine(download: PlayDownloadSummary): String {
    val parts = buildList {
        if (download.waiting > 0) add("${download.waiting} waiting")
        if (download.failed > 0) add("${download.failed} failed")
    }
    return if (parts.isEmpty()) "Downloading" else parts.joinToString(" · ")
}

// ---- The lead --------------------------------------------------------------------------------

@Composable
private fun LeadStory(game: InProgressGame, actions: PlayActions, big: Boolean, modifier: Modifier = Modifier, conflictChip: String? = null) {
    val theme = RommioTheme.current
    Column(modifier.testTag("play.continue"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Kicker(
            label = "Continue playing",
            detail = listOfNotNull(game.rom.platformSlug.uppercase(), game.lastPlayedAtEpochMs?.let { PlayFormat.relative(it) }).joinToString(" · "),
            fill = KickerFill.PINK,
        )
        DisplayText(
            game.rom.displayName,
            size = if (big) 76f else 60f,
            maxLines = 2,
            modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Open ${game.rom.displayName}") { actions.open(game.rom.id) },
        )
        RatioColumns(ratio = if (big) 0.63f else 0.6f, spacing = if (big) 18.dp else 14.dp, first = {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                // Tap opens the game; a long press offers Resume, Start options and Game details.
                ContextMenuBox(
                    onClick = { actions.open(game.rom.id) },
                    actions = actions.menu(game),
                    onClickLabel = "Open ${game.rom.displayName}",
                    modifier = Modifier.fillMaxWidth().testTag("play.leadCover.${game.rom.id}"),
                ) {
                    GameCover(actions.cover(game.rom), game.rom.displayName, Modifier.fillMaxWidth().aspectRatio(3f / 4f), aspect = null)
                }
                FigureCaption(
                    "${game.rom.platformName}. " +
                        (game.lastPlayedAtEpochMs?.let { "Played ${PlayFormat.relative(it)}." } ?: "Not played on this device yet."),
                )
            }
        }, second = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                RoleText(dek(game), theme.type.body, size = if (big) 17f else 16f, color = theme.colors.ink)
                if (game.sessionCount > 0) {
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        RoleText("On this device", theme.type.label, size = 9.5f, color = theme.colors.inkSecondary)
                        StatusChip(if (game.sessionCount == 1) "1 session" else "${game.sessionCount} sessions", tone = Tone.OK)
                    }
                }
                conflictChip?.let {
                    StatusChip(it, tone = Tone.BAD, modifier = Modifier.testTag("play.conflict.${game.rom.id}"))
                }
                PrimaryButton(
                    text = "Resume",
                    icon = Icons.Outlined.PlayArrow,
                    onClick = { actions.resume(game) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Resume ${game.rom.displayName}" }
                        .testTag("play.resume.${game.rom.id}"),
                )
            }
        })
    }
}

private fun dek(game: InProgressGame): String {
    val last = game.lastPlayedAtEpochMs ?: return "Pick up where you left off. Your resume point is waiting."
    return "You last played ${PlayFormat.relative(last)}, ${formatPlayTime(game.playedMs)} in so far on this device."
}

// ---- Briefs and the pinned collection --------------------------------------------------------

@Composable
private fun BriefsAndCollection(state: PlayUiState, actions: PlayActions, modifier: Modifier = Modifier) {
    val briefs = state.briefs
    val collection = state.pinnedCollection
    if (briefs.isEmpty() && collection == null) return
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        if (briefs.isNotEmpty()) {
            Column(
                Modifier.weight(1f).padding(end = if (collection == null) 0.dp else 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ColumnHeading("In progress")
                briefs.take(if (collection == null) 4 else 3).forEach { Brief(it, actions) }
            }
        }
        if (collection != null) {
            if (briefs.isNotEmpty()) ColumnRule()
            Box(Modifier.weight(1f).padding(start = if (briefs.isNotEmpty()) 14.dp else 0.dp)) {
                CollectionColumn(collection, actions, wide = briefs.isEmpty(), threeColumns = false)
            }
        }
    }
}

@Composable
private fun ColumnHeading(title: String) {
    val theme = RommioTheme.current
    RoleText(title, theme.type.section, size = 16f, color = theme.colors.ink, modifier = Modifier.semantics { heading() })
}

@Composable
private fun Brief(game: InProgressGame, actions: PlayActions) {
    val theme = RommioTheme.current
    ContextMenuBox(
        onClick = { actions.open(game.rom.id) },
        actions = actions.menu(game, details = false),
        onClickLabel = "Open ${game.rom.displayName}",
        modifier = Modifier.fillMaxWidth().testTag("play.quickResume.${game.rom.id}"),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GameCover(actions.cover(game.rom), game.rom.displayName, Modifier.size(48.dp, 64.dp), aspect = null)
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                RoleText(
                    listOfNotNull(game.rom.platformSlug.uppercase(), game.lastPlayedAtEpochMs?.let { PlayFormat.relative(it) }).joinToString(" · "),
                    theme.type.label,
                    size = 9.5f,
                    color = theme.colors.inkSecondary,
                    maxLines = 1,
                )
                RoleText(game.rom.displayName, theme.type.headline, size = 18f, color = theme.colors.ink, maxLines = 2)
                RoleText(
                    if (game.playedMs > 0) "${formatPlayTime(game.playedMs)} so far." else "Ready to resume.",
                    theme.type.secondary,
                    size = 13.5f,
                    color = theme.colors.ink,
                )
            }
        }
    }
}

@Composable
private fun CollectionColumn(item: PinnedCollection, actions: PlayActions, wide: Boolean, threeColumns: Boolean) {
    val theme = RommioTheme.current
    val c = theme.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClickLabel = "Open ${item.collection.name}") { actions.openCollection(item.collection) }
                .testTag("play.collection"),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f, fill = wide)) { ColumnHeading(item.collection.name) }
            if (wide) Box(Modifier.weight(1f).height(1.5.dp).padding(0.dp)) { PrintRule() }
            RoleText("${item.playedCount} of ${item.games.size} played", theme.type.label, size = 9.5f, color = c.inkSecondary, maxLines = 1)
        }
        RoleText(
            item.collection.description.ifBlank { "${formatPlayTime(item.playedMs)} played so far." },
            theme.type.caption,
            size = 13.5f,
            italic = true,
            color = c.ink,
            maxLines = 2,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        val rows = item.games.take(if (wide) 6 else 5).withIndex().toList()
        val perRow = if (threeColumns) 3 else 1
        rows.chunked(perRow).forEach { chunk ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                chunk.forEach { (index, game) ->
                    Row(
                        Modifier
                            .weight(1f)
                            .clickable(role = Role.Button, onClickLabel = "Open ${game.rom.displayName}") { actions.open(game.rom.id) }
                            .padding(top = 0.dp),
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            PrintRule(weight = RuleWeight.HAIRLINE)
                            Row(Modifier.padding(vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                RoleText(
                                    "${index + 1}",
                                    theme.type.statValue,
                                    size = 19f,
                                    color = if (game.playedMs > 0) c.ink else c.inkSecondary,
                                    modifier = Modifier.width(16.dp),
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                    RoleText(game.rom.displayName, theme.type.bodyEmphasis, size = 14f, color = c.ink, maxLines = 1)
                                    RoleText(
                                        if (game.playedMs > 0) formatPlayTime(game.playedMs) else "Not started",
                                        theme.type.label,
                                        size = 9.5f,
                                        color = c.inkSecondary,
                                    )
                                }
                            }
                        }
                    }
                }
                repeat(perRow - chunk.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

// ---- Notices and empty -----------------------------------------------------------------------

@Composable
private fun Notices(state: PlayUiState, modifier: Modifier = Modifier) {
    val theme = RommioTheme.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.staleMessage?.let { message ->
            Kicker("Showing your saved library", fill = KickerFill.YELLOW)
            RoleText(message, theme.type.caption, color = theme.colors.inkSecondary, maxLines = 3)
        }
        state.errorMessage?.let { RoleText(it, theme.type.secondary, color = theme.colors.ink) }
        if (state.continuePlaying.isEmpty() && state.recentSaves.isEmpty() && !state.isLoading && state.errorMessage == null) {
            EmptyState(
                title = "Nothing to continue yet",
                message = "Games you play show up here. Open the Library to start one.",
            )
        }
    }
}
