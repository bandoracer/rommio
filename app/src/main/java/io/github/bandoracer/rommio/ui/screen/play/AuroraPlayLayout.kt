package io.github.bandoracer.rommio.ui.screen.play

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.bandoracer.rommio.ui.component.ContextMenuBox
import io.github.bandoracer.rommio.ui.component.DisplayText
import io.github.bandoracer.rommio.ui.component.EmptyState
import io.github.bandoracer.rommio.ui.component.GlassSurface
import io.github.bandoracer.rommio.ui.component.GlowingCover
import io.github.bandoracer.rommio.ui.component.IconCircleButton
import io.github.bandoracer.rommio.ui.component.LoadingSkeletonPanel
import io.github.bandoracer.rommio.ui.component.MenuAction
import io.github.bandoracer.rommio.ui.component.PageArtSubject
import io.github.bandoracer.rommio.ui.component.PageFieldStyle
import io.github.bandoracer.rommio.ui.component.PagerDots
import io.github.bandoracer.rommio.ui.component.PlayFormat
import io.github.bandoracer.rommio.ui.component.PrimaryButton
import io.github.bandoracer.rommio.ui.component.RoleText
import io.github.bandoracer.rommio.ui.component.RoomCard
import io.github.bandoracer.rommio.ui.component.SectionHeader
import io.github.bandoracer.rommio.ui.component.SyncStatusLine
import io.github.bandoracer.rommio.ui.component.isRegularWidth
import io.github.bandoracer.rommio.ui.component.rememberCoverMark
import io.github.bandoracer.rommio.ui.theme.RommioTheme
import kotlinx.coroutines.launch

/**
 * Aurora Play: the games in progress one room at a time, lit by the game in focus (swipe between
 * them), then recent saves as a lit gallery by day (recent sessions are on Stats). On a regular
 * width the room is one canvas: the cover and its caption side by side, the other games in a row
 * beneath, the save days two by two.
 */
@Composable
fun AuroraPlayLayout(state: PlayUiState, actions: PlayActions) {
    val theme = RommioTheme.current
    val regular = isRegularWidth()
    val games = state.continuePlaying
    var focusId by rememberSaveable { mutableIntStateOf(games.firstOrNull()?.rom?.id ?: -1) }
    val focused = games.firstOrNull { it.rom.id == focusId } ?: games.firstOrNull()
    val focusIndex = games.indexOfFirst { it.rom.id == focused?.rom?.id }.coerceAtLeast(0)
    // The game in focus lights the page (room field).
    PageArtSubject(focused?.let { actions.cover(it.rom) }, PageFieldStyle.ROOM)
    val gutter = if (regular) 32.dp else theme.spacing.gutter

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .testTag("shell.playList")
            .padding(bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(theme.spacing.section),
    ) {
        Column {
            PlayToolbar(state, actions, gutter)
            if (regular) {
                Canvas(state, actions, focusIndex, onFocus = { focusId = games[it].rom.id })
            } else {
                Compact(state, actions, focusIndex, onFocus = { focusId = games[it].rom.id }, gutter)
            }
        }
        if (state.isLoading && !state.hasContent) {
            LoadingSkeletonPanel(showArtwork = true, lines = 3, modifier = Modifier.padding(horizontal = gutter))
        }
        AuroraRecentSaves(state, actions, regular, gutter)
        Notices(state, gutter)
    }
}

// ---- Compact: paged rooms --------------------------------------------------------------------

@Composable
private fun Compact(state: PlayUiState, actions: PlayActions, focusIndex: Int, onFocus: (Int) -> Unit, gutter: Dp) {
    val games = state.continuePlaying
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        SyncStatusLine(state.statusLine(), state.offlineState.isOffline, Modifier.padding(horizontal = gutter).testTag("play.syncLine"))
        if (games.isEmpty()) return@Column
        val pager = rememberPagerState(initialPage = focusIndex.coerceIn(0, games.lastIndex)) { games.size }
        LaunchedEffect(pager.settledPage, games.size) {
            if (pager.settledPage in games.indices && pager.settledPage != focusIndex) onFocus(pager.settledPage)
        }
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxWidth().testTag("play.continue"),
            beyondViewportPageCount = 1,
        ) { page ->
            Room(games[page], actions, Modifier.padding(horizontal = gutter), conflictChip = state.conflictChip(games[page].rom.id))
        }
        if (games.size > 1) {
            val colours = games.map { rememberCoverMark(actions.cover(it.rom)) }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                PagerDots(colours, pager.currentPage, onSelect = { index -> scope.launch { pager.animateScrollToPage(index) } })
            }
        }
    }
}

@Composable
private fun Room(game: InProgressGame, actions: PlayActions, modifier: Modifier = Modifier, conflictChip: String? = null) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 14.dp), contentAlignment = Alignment.Center) {
            // Tap opens the game; a long press offers Resume, Start options and Game details.
            ContextMenuBox(
                onClick = { actions.open(game.rom.id) },
                actions = actions.menu(game),
                onClickLabel = "Open ${game.rom.displayName}",
                modifier = Modifier.testTag("play.cover.${game.rom.id}"),
            ) {
                GlowingCover(actions.cover(game.rom), game.rom.displayName, height = 270.dp)
            }
        }
        Caption(game, conflictChip = conflictChip)
        ActionRow(game, actions)
    }
}

@Composable
private fun Caption(game: InProgressGame, large: Boolean = false, conflictChip: String? = null) {
    val theme = RommioTheme.current
    Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            RoleText("Continue playing", theme.type.section, color = theme.colors.ink, maxLines = 1)
            RoleText(game.rom.platformName, theme.type.section, color = theme.colors.inkSecondary, maxLines = 1)
        }
        DisplayText(game.rom.displayName, role = theme.type.title, size = if (large) 48f else 36f, maxLines = 2)
        RoleText(game.playedLine() ?: "Resume where you left off", theme.type.secondary, color = theme.colors.inkSecondary)
        conflictChip?.let {
            io.github.bandoracer.rommio.ui.component.StatusChip(
                it,
                tone = io.github.bandoracer.rommio.ui.component.Tone.BAD,
                modifier = Modifier.testTag("play.conflict.${game.rom.id}"),
            )
        }
    }
}

@Composable
private fun ActionRow(game: InProgressGame, actions: PlayActions) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        PrimaryButton(
            text = "Resume",
            icon = Icons.Outlined.PlayArrow,
            onClick = { actions.resume(game) },
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = "Resume ${game.rom.displayName}" }
                .testTag("play.resume.${game.rom.id}"),
        )
        IconCircleButton(
            icon = Icons.Outlined.Info,
            contentDescription = "${game.rom.displayName} details",
            onClick = { actions.open(game.rom.id) },
            size = 54.dp,
        )
    }
}

// ---- Regular: one canvas ---------------------------------------------------------------------

@Composable
private fun Canvas(state: PlayUiState, actions: PlayActions, focusIndex: Int, onFocus: (Int) -> Unit) {
    val games = state.continuePlaying
    val focused = games.getOrNull(focusIndex)
    Column(Modifier.padding(horizontal = 32.dp).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(26.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SyncStatusLine(state.statusLine(), state.offlineState.isOffline, Modifier.weight(1f).testTag("play.syncLine"))
            if (games.size > 1) {
                PagerDots(games.map { rememberCoverMark(actions.cover(it.rom)) }, focusIndex, onSelect = onFocus)
            }
        }
        if (focused != null) {
            Row(
                Modifier.fillMaxWidth().testTag("play.continue"),
                horizontalArrangement = Arrangement.spacedBy(44.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f).padding(bottom = 24.dp), contentAlignment = Alignment.Center) {
                    ContextMenuBox(
                        onClick = { actions.open(focused.rom.id) },
                        actions = actions.menu(focused),
                        onClickLabel = "Open ${focused.rom.displayName}",
                        modifier = Modifier.testTag("play.cover.${focused.rom.id}"),
                    ) {
                        GlowingCover(actions.cover(focused.rom), focused.rom.displayName, height = 360.dp)
                    }
                }
                Column(Modifier.widthIn(max = 380.dp).weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                    Caption(focused, large = true, conflictChip = state.conflictChip(focused.rom.id))
                    ActionRow(focused, actions)
                }
            }
        }
        if (games.size > 1) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeader(title = "Continue playing", meta = PlayFormat.games(games.size))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(games, key = { it.rom.id }) { game ->
                        ContextMenuBox(
                            onClick = { onFocus(games.indexOf(game)) },
                            actions = actions.menu(game),
                            modifier = Modifier.padding(vertical = 6.dp).padding(bottom = 12.dp).testTag("play.quickResume.${game.rom.id}"),
                        ) {
                            RoomCard(
                                title = game.rom.displayName,
                                platformSlug = game.rom.platformSlug,
                                detail = game.lastPlayedAtEpochMs?.let { PlayFormat.relative(it) },
                                imageUrl = actions.cover(game.rom),
                                selected = game.rom.id == focused?.rom?.id,
                                onClick = { onFocus(games.indexOf(game)) },
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---- Notices and empty -----------------------------------------------------------------------

@Composable
private fun Notices(state: PlayUiState, gutter: Dp) {
    val theme = RommioTheme.current
    Column(Modifier.padding(horizontal = gutter), verticalArrangement = Arrangement.spacedBy(theme.spacing.stack)) {
        state.staleMessage?.let { message ->
            GlassSurface(Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Outlined.CloudOff, contentDescription = null, tint = theme.colors.wait)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        RoleText("Showing your saved library", theme.type.bodyEmphasis, color = theme.colors.ink)
                        RoleText(message, theme.type.caption, color = theme.colors.inkSecondary, maxLines = 3)
                    }
                }
            }
        }
        state.errorMessage?.let { RoleText(it, theme.type.secondary, color = theme.colors.badText) }
        if (state.continuePlaying.isEmpty() && state.recentSaves.isEmpty() && !state.isLoading && state.errorMessage == null) {
            EmptyState(
                title = "Nothing to continue yet",
                message = "Games you play show up here. Open the Library to start one.",
                icon = Icons.Outlined.PlayCircle,
            )
        }
    }
}
