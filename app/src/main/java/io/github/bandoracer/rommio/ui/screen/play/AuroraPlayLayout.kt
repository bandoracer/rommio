package io.github.bandoracer.rommio.ui.screen.play

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import io.github.bandoracer.rommio.domain.playdata.SaveSyncStatus
import io.github.bandoracer.rommio.ui.art.CoverArt
import io.github.bandoracer.rommio.ui.component.ContextMenuBox
import io.github.bandoracer.rommio.ui.component.DisplayText
import io.github.bandoracer.rommio.ui.component.EmptyState
import io.github.bandoracer.rommio.ui.component.GameCover
import io.github.bandoracer.rommio.ui.component.GlassSurface
import io.github.bandoracer.rommio.ui.component.IconCircleButton
import io.github.bandoracer.rommio.ui.component.LitPage
import io.github.bandoracer.rommio.ui.component.LoadingSkeletonPanel
import io.github.bandoracer.rommio.ui.component.OverArt
import io.github.bandoracer.rommio.ui.component.PageFieldStyle
import io.github.bandoracer.rommio.ui.component.PagerDots
import io.github.bandoracer.rommio.ui.component.PlayFormat
import io.github.bandoracer.rommio.ui.component.PrimaryButton
import io.github.bandoracer.rommio.ui.component.RoleText
import io.github.bandoracer.rommio.ui.component.RoomCard
import io.github.bandoracer.rommio.ui.component.SectionHeader
import io.github.bandoracer.rommio.ui.component.SyncStatusLine
import io.github.bandoracer.rommio.ui.component.dropShadow
import io.github.bandoracer.rommio.ui.component.isRegularWidth
import io.github.bandoracer.rommio.ui.component.rememberCoverMark
import io.github.bandoracer.rommio.ui.theme.RommioTheme
import java.io.File

/**
 * Aurora Play v2 (docs/theme-layouts.md "Play", Aurora (compact), v2): the games in progress as a
 * paged carousel of their own screens — the latest resume point or save state as captured, never
 * art-treated — lit by the game in focus, its caption and actions, the rest of the games in
 * progress, then recent saves as a lit gallery by day (mosaic v2). On a regular width the room is
 * one canvas: the screen beside its caption, the games in progress in a row beneath. Sections are
 * lazy items; the model ([PlayPage]) formats every line.
 */
@Composable
fun AuroraPlayLayout(page: PlayPage, actions: PlayActions) {
    val theme = RommioTheme.current
    val regular = isRegularWidth()
    val games = page.games
    var focusId by rememberSaveable { mutableIntStateOf(games.firstOrNull()?.id ?: -1) }
    val focused = games.firstOrNull { it.id == focusId } ?: games.firstOrNull()
    val gutter = if (regular) 32.dp else theme.spacing.gutter
    val section = theme.spacing.section
    // The game in focus lights the page (room field).
    LitPage(focused?.coverUrl, PageFieldStyle.ROOM) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("shell.playList"),
            contentPadding = PaddingValues(bottom = 40.dp),
        ) {
            item(key = "toolbar", contentType = "toolbar") { PlayToolbar(page.downloadCount, actions.openDownloads, gutter) }
            item(key = "room", contentType = "room") {
                if (regular) {
                    Canvas(page, actions, focused, onFocus = { focusId = it })
                } else {
                    Rooms(page, actions, focused, onFocus = { focusId = it }, gutter)
                }
            }
            if (!regular && page.games.size > 1) {
                item(key = "inProgress", contentType = "inProgress") {
                    InProgressRow(page, actions, focused, onFocus = { focusId = it }, gutter, Modifier.padding(top = 28.dp))
                }
            }
            if (page.isLoading && !page.hasContent) {
                item(key = "skeleton", contentType = "skeleton") {
                    LoadingSkeletonPanel(showArtwork = true, lines = 3, modifier = Modifier.padding(top = section).padding(horizontal = gutter))
                }
            }
            auroraRecentSaves(page, actions, regular, gutter, section)
            // The notices column is always part of the page (as the column it was), empty or not.
            item(key = "notices", contentType = "notices") { Notices(page, gutter, Modifier.padding(top = section)) }
        }
    }
}

// ---- Compact: a carousel of screens ------------------------------------------------------------

/** Space between pages and the next page's peek at the trailing edge. */
private val PageSpacing = 12.dp
private val Peek = 16.dp

/** Transparent room around the blurred frame for its glow to spread into. */
private val GlowMargin = 48.dp

/** Room under the carousel for the glow, overlapped by the caption. */
private val GlowRoom = 56.dp

@Composable
private fun Rooms(page: PlayPage, actions: PlayActions, focused: PlayGame?, onFocus: (Int) -> Unit, gutter: Dp) {
    val carousel = page.carousel
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        SyncStatusLine(page.statusLine, page.offline, Modifier.padding(horizontal = gutter).testTag("play.syncLine"))
        if (carousel.isEmpty() || focused == null) return@Column
        val focusIndex = carousel.indexOfFirst { it.id == focused.id }.coerceAtLeast(0)
        val pager = rememberPagerState(initialPage = focusIndex) { carousel.size }
        LaunchedEffect(pager.settledPage, carousel.size) {
            carousel.getOrNull(pager.settledPage)?.let { if (it.id != focused.id) onFocus(it.id) }
        }
        // A focus set elsewhere (the pager dots, the in-progress row) moves the carousel.
        LaunchedEffect(focusIndex) { if (pager.currentPage != focusIndex && !pager.isScrollInProgress) pager.animateScrollToPage(focusIndex) }
        Column {
            // The pager (which clips its pages) is taller than it says by [GlowRoom], so the
            // screens' glow has room below them; the caption sits 14 dp under the screen, over it.
            BoxWithConstraints(
                Modifier.fillMaxWidth().layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    val room = GlowRoom.roundToPx()
                    layout(placeable.width, (placeable.height - room).coerceAtLeast(0)) { placeable.place(0, 0) }
                },
            ) {
                val pageWidth = (maxWidth - gutter - PageSpacing - Peek).coerceAtLeast(200.dp)
                HorizontalPager(
                    state = pager,
                    pageSize = PageSize.Fixed(pageWidth),
                    pageSpacing = PageSpacing,
                    contentPadding = PaddingValues(start = gutter, end = PageSpacing + Peek),
                    beyondViewportPageCount = 1,
                    modifier = Modifier.fillMaxWidth().testTag("play.continue"),
                ) { index ->
                    Box(Modifier.padding(bottom = 14.dp + GlowRoom)) { GameScreen(carousel[index], pageWidth, actions) }
                }
            }
            Column(Modifier.padding(horizontal = gutter), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Caption(focused, large = false)
                ActionRow(focused, actions)
            }
        }
    }
}

/**
 * A game's screen: its latest resume point (or save state) as captured, at its own aspect on a
 * black 4:3 screen with the frame blurred and glowing in the game's light behind it; without one,
 * the cover centred on its blurred backdrop. Never art-treated. The sync state (or the conflict)
 * sits bottom-left in a dark glass chip.
 */
@Composable
private fun GameScreen(game: PlayGame, width: Dp, actions: PlayActions) {
    val theme = RommioTheme.current
    val height = width * 3f / 4f
    val shape = RoundedCornerShape(22.dp)
    val glow = rememberCoverMark(game.coverUrl)
    Box(Modifier.size(width, height), contentAlignment = Alignment.Center) {
        // The frame, blurred, glowing in the game's light (a real blur needs API 31+). The blurred
        // layer is larger than the frame so the blur spreads inside it instead of ending at a
        // hard edge.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Box(
                Modifier
                    .requiredSize(width * 0.9f + GlowMargin * 2, height * 0.9f + GlowMargin * 2)
                    .offset(y = height * 0.08f)
                    .graphicsLayer { alpha = if (theme.isDark) 0.7f else 0.45f }
                    .blur(26.dp, BlurredEdgeTreatment.Unbounded),
                contentAlignment = Alignment.Center,
            ) {
                ScreenPicture(game, height, Modifier.size(width * 0.9f, height * 0.9f))
            }
        }
        ContextMenuBox(
            onClick = { actions.open(game.id) },
            actions = actions.menu(game.game),
            onClickLabel = "Open ${game.title}",
            modifier = Modifier
                .size(width, height)
                .dropShadow(glow.copy(alpha = if (theme.isDark) 0.55f else 0.5f), 22.dp, radius = 22.dp, y = 14.dp)
                .clip(shape)
                .testTag("play.cover.${game.id}"),
        ) {
            Box(Modifier.fillMaxSize().background(Color.Black).border(0.5.dp, OverArt.rim.copy(alpha = 0.22f), shape)) {
                ScreenPicture(game, height, Modifier.fillMaxSize())
                ScreenChip(game, actions, Modifier.align(Alignment.BottomStart).padding(12.dp))
            }
        }
    }
}

@Composable
private fun ScreenPicture(game: PlayGame, height: Dp, modifier: Modifier) {
    val preview = game.previewPath
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        if (preview != null) {
            // Pixel art stays crisp: nearest-neighbour, at its own aspect.
            AsyncImage(
                model = File(preview),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.None,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // No preview: the cover on its blurred backdrop.
            game.coverUrl?.let { url -> BlurredCover(url, Modifier.fillMaxSize()) }
            GameCover(
                imageUrl = game.coverUrl,
                title = game.title,
                cornerRadius = 10.dp,
                aspect = null,
                modifier = Modifier.size(height * 0.72f * 0.75f, height * 0.72f),
            )
        }
    }
}

/** The cover baked into light (§1.3), filling the screen behind the cover. */
@Composable
private fun BlurredCover(url: String, modifier: Modifier) {
    val context = LocalContext.current
    val art by produceState(initialValue = CoverArt.cachedBackdrop(url, true), url) {
        if (value == null) value = CoverArt.backdrop(context.applicationContext, url, true)
    }
    art?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, alpha = 0.9f, modifier = modifier) }
}

/** The screen's sync state in dark glass (§11 wording), or the conflict in `bad` (opens the Conflict sheet). */
@Composable
private fun ScreenChip(game: PlayGame, actions: PlayActions, modifier: Modifier) {
    val conflict = game.conflictChip
    val sync = game.screenSync
    val tone = when {
        conflict != null -> OverArt.bad
        sync != null -> sync.frameTone()
        else -> return
    }
    val theme = RommioTheme.current
    Row(
        modifier
            .height(28.dp)
            .clip(CircleShape)
            .background(OverArt.shade(0.62f))
            .then(
                if (conflict != null) {
                    Modifier
                        .clickable(role = Role.Button, onClickLabel = "Choose resume point") { actions.resume(game.game) }
                        .testTag("play.conflict.${game.id}")
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (conflict != null) {
            Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = tone, modifier = Modifier.size(12.dp))
        } else {
            Box(Modifier.size(7.dp).clip(CircleShape).background(tone))
        }
        Text(
            conflict ?: sync!!.label,
            color = tone,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = theme.type.caption.style.fontFamily,
            maxLines = 1,
        )
    }
}

/** Sync tones over a frame: Aurora's night `ok`/`wait`/`live`/`bad` in both schemes. */
internal fun SaveSyncStatus.frameTone(): Color = when (this) {
    SaveSyncStatus.SYNCED -> OverArt.ok
    SaveSyncStatus.WAITING, SaveSyncStatus.SIGN_IN_NEEDED -> OverArt.wait
    SaveSyncStatus.SYNCING -> OverArt.live
    SaveSyncStatus.CONFLICT -> OverArt.bad
    SaveSyncStatus.LOCAL_ONLY, SaveSyncStatus.UNKNOWN -> OverArt.quiet(0.7f)
}

/** Platform · year (`section`), the game's name (`title` 32, two lines) and where it continues from. */
@Composable
private fun Caption(game: PlayGame, large: Boolean) {
    val theme = RommioTheme.current
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(if (large) 10.dp else 6.dp)) {
        RoleText(game.platformLine, theme.type.section, color = theme.colors.inkSecondary, maxLines = 1)
        DisplayText(game.title, role = theme.type.title, size = if (large) 48f else 32f, maxLines = 2)
        RoleText(game.captionLine, theme.type.secondary, color = theme.colors.inkSecondary, maxLines = 2)
    }
}

/** Resume (primary, lit glow, fills the row) and Details (a 54 dp glass circle with the info glyph). */
@Composable
private fun ActionRow(game: PlayGame, actions: PlayActions) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        PrimaryButton(
            text = "Resume",
            icon = Icons.Outlined.PlayArrow,
            onClick = { actions.resume(game.game) },
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = "Resume ${game.title}" }
                .testTag("play.resume.${game.id}"),
        )
        IconCircleButton(
            icon = Icons.Outlined.Info,
            contentDescription = "${game.title} details",
            onClick = { actions.open(game.id) },
            size = 54.dp,
            iconSize = 20.dp,
            modifier = Modifier.testTag("play.details.${game.id}"),
        )
    }
}

/**
 * "In progress": the carousel's pager dots at the header's end (6 dp dots in each game's light,
 * the current 18 wide), then the games in progress beyond the carousel as 152 × 114 tiles (each
 * opens its Game page). The row is left out when there are no more games.
 */
@Composable
private fun InProgressRow(page: PlayPage, actions: PlayActions, focused: PlayGame?, onFocus: (Int) -> Unit, gutter: Dp, modifier: Modifier) {
    val theme = RommioTheme.current
    val carousel = page.carousel
    val more = page.more
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = gutter), verticalAlignment = Alignment.CenterVertically) {
            RoleText("In progress", theme.type.section, color = theme.colors.inkSecondary, modifier = Modifier.weight(1f))
            if (carousel.size > 1) {
                val colours = carousel.map { rememberCoverMark(it.coverUrl) }
                PagerDots(colours, carousel.indexOfFirst { it.id == focused?.id }.coerceAtLeast(0), onSelect = { onFocus(carousel[it].id) })
            }
        }
        if (more.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = gutter),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.testTag("play.inProgress"),
            ) {
                items(more, key = { it.id }) { game ->
                    ContextMenuBox(
                        onClick = { actions.open(game.id) },
                        actions = actions.menu(game.game),
                        modifier = Modifier.padding(vertical = 6.dp).padding(bottom = 12.dp).testTag("play.inProgress.${game.id}"),
                    ) {
                        RoomCard(
                            title = game.title,
                            platformSlug = game.platformSlug,
                            detail = game.relative,
                            imageUrl = game.coverUrl,
                            selected = false,
                            onClick = { actions.open(game.id) },
                            width = 152.dp,
                            height = 114.dp,
                            previewPath = game.previewPath,
                            showsPlatform = false,
                        )
                    }
                }
            }
        }
    }
}

// ---- Regular: one canvas ---------------------------------------------------------------------

@Composable
private fun Canvas(page: PlayPage, actions: PlayActions, focused: PlayGame?, onFocus: (Int) -> Unit) {
    val games = page.games
    Column(Modifier.padding(horizontal = 32.dp).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(26.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SyncStatusLine(page.statusLine, page.offline, Modifier.weight(1f).testTag("play.syncLine"))
            if (games.size > 1) {
                PagerDots(games.map { rememberCoverMark(it.coverUrl) }, games.indexOfFirst { it.id == focused?.id }.coerceAtLeast(0), onSelect = { onFocus(games[it].id) })
            }
        }
        if (focused != null) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 10.dp).testTag("play.continue"),
                horizontalArrangement = Arrangement.spacedBy(44.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BoxWithConstraints(Modifier.weight(1f).padding(bottom = 22.dp)) {
                    GameScreen(focused, minOf(480.dp, maxWidth), actions)
                }
                Column(Modifier.widthIn(max = 380.dp).weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                    Caption(focused, large = true)
                    ActionRow(focused, actions)
                }
            }
        }
        if (games.size > 1) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeader(title = "Continue playing", meta = PlayFormat.games(games.size))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(games, key = { it.id }) { game ->
                        ContextMenuBox(
                            onClick = { onFocus(game.id) },
                            actions = actions.menu(game.game),
                            modifier = Modifier.padding(vertical = 6.dp).padding(bottom = 12.dp).testTag("play.quickResume.${game.id}"),
                        ) {
                            RoomCard(
                                title = game.title,
                                platformSlug = game.platformSlug,
                                detail = game.relative,
                                imageUrl = game.coverUrl,
                                selected = game.id == focused?.id,
                                onClick = { onFocus(game.id) },
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
private fun Notices(page: PlayPage, gutter: Dp, modifier: Modifier) {
    val theme = RommioTheme.current
    Column(modifier.padding(horizontal = gutter), verticalArrangement = Arrangement.spacedBy(theme.spacing.stack)) {
        page.staleMessage?.let { message ->
            GlassSurface(Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Outlined.CloudOff, contentDescription = null, tint = theme.colors.wait)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        RoleText("Showing your saved library", theme.type.bodyEmphasis, color = theme.colors.ink)
                        RoleText(message, theme.type.caption, color = theme.colors.inkSecondary, maxLines = 3)
                    }
                }
            }
        }
        page.errorMessage?.let { RoleText(it, theme.type.secondary, color = theme.colors.badText) }
        if (page.showsEmpty) {
            Spacer(Modifier.height(8.dp))
            EmptyState(
                title = "Nothing to continue yet",
                message = "Games you play show up here. Open the Library to start one.",
                icon = Icons.Outlined.PlayCircle,
            )
        }
    }
}
