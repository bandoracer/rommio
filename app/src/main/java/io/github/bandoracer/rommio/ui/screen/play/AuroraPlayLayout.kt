package io.github.bandoracer.rommio.ui.screen.play

import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import io.github.bandoracer.rommio.domain.playdata.SaveSyncStatus
import io.github.bandoracer.rommio.ui.art.ArtTone
import io.github.bandoracer.rommio.ui.art.CoverArt
import io.github.bandoracer.rommio.ui.component.ContextMenuBox
import io.github.bandoracer.rommio.ui.component.CoverPlaceholder
import io.github.bandoracer.rommio.ui.component.DisplayText
import io.github.bandoracer.rommio.ui.component.EmptyState
import io.github.bandoracer.rommio.ui.component.GameCover
import io.github.bandoracer.rommio.ui.component.GlassSurface
import io.github.bandoracer.rommio.ui.component.IconCircleButton
import io.github.bandoracer.rommio.ui.component.LayeredCrossfade
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
import io.github.bandoracer.rommio.ui.component.StatusChip
import io.github.bandoracer.rommio.ui.component.SyncBadge
import io.github.bandoracer.rommio.ui.component.SyncStatusLine
import io.github.bandoracer.rommio.ui.component.Tone
import io.github.bandoracer.rommio.ui.component.ToolbarIconButton
import io.github.bandoracer.rommio.ui.component.dropShadow
import io.github.bandoracer.rommio.ui.component.isRegularWidth
import io.github.bandoracer.rommio.ui.component.rememberCoverMark
import io.github.bandoracer.rommio.ui.component.themedSurface
import io.github.bandoracer.rommio.ui.screen.game.tone
import io.github.bandoracer.rommio.ui.theme.RommioTheme
import io.github.bandoracer.rommio.ui.theme.StatusBarOverArt
import java.io.File

/**
 * Aurora Play (docs/theme-layouts.md "Play"). Compact, the Home: the game you're playing fills the
 * top of the page edge to edge with its cover art (as the Game page does; a screenshot from RomM
 * when it has no cover), dissolving into the page's light, with its caption over the fade and
 * Resume under it; every game in play in one row of covers (tap to bring one to the top); three
 * picks with their reasons; then recent saves as a lit gallery by day. On a regular width the room
 * is one canvas: the screen beside its caption, the games in progress in a row beneath. Sections
 * are lazy items; the model ([PlayPage]) formats every line.
 */
@Composable
fun AuroraPlayLayout(page: PlayPage, actions: PlayActions) {
    val regular = isRegularWidth()
    val games = page.games
    var focusId by rememberSaveable { mutableIntStateOf(-1) }
    val focused = games.firstOrNull { it.id == focusId } ?: games.firstOrNull()
    // The game in focus lights the page (room field).
    LitPage(focused?.coverUrl, PageFieldStyle.ROOM) {
        if (regular) {
            RegularPlay(page, actions, focused, onFocus = { focusId = it })
        } else {
            AuroraHome(page, actions, focused, onFocus = { focusId = it })
        }
    }
}

// ---- Compact: the game you're playing, edge to edge --------------------------------------------

/** The Play toolbar's height under the status bar (the Downloads button over the art). */
private val ToolbarHeight = 52.dp

/** The caption's inset from the hero's bottom edge. */
private val CaptionInset = 14.dp

/**
 * The Home: its list runs under the status bar and the toolbar (the shell gives Aurora's compact
 * Play no bar), the hero at a fixed height, the status bar in the art's tone while the art is
 * under it, and every game in play prepared ahead of a tap.
 */
@Composable
private fun AuroraHome(page: PlayPage, actions: PlayActions, focused: PlayGame?, onFocus: (Int) -> Unit) {
    val theme = RommioTheme.current
    val gutter = theme.spacing.gutter
    val section = theme.spacing.section
    val density = LocalDensity.current
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val barInset = statusBar + ToolbarHeight
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // The hero keeps one height whatever the game, so Resume and everything below it stay put;
        // a longer caption grows upward into the art (revised 2026-09-27).
        val heroHeight = (maxHeight * 0.78f).coerceIn(480.dp, 720.dp)
        val heroPx = with(density) { heroHeight.toPx() }
        val list = rememberLazyListState()
        val scrolledPastArt by remember(list, heroPx) {
            derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > heroPx * 0.35f }
        }
        val scrolled by remember(list) { derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 0 } }
        val pull = remember { HeroPull() }
        // Over the art, the status bar reads in the art's tone: light text over a dark top.
        val artDark = rememberArtTopDark(focused?.coverUrl, maxWidth, statusBar)
        StatusBarOverArt(if (focused != null && !scrolledPastArt) artDark else null)
        PrepareInPlay(page.games, maxWidth)
        // Pulled down past the top: the rows follow the pull, and the hero's art grows from the top
        // of the page down to them (the list clips at its top, so the list itself never moves).
        val follow = Modifier.graphicsLayer { translationY = pull.offset }
        Box(Modifier.fillMaxSize().nestedScroll(pull).testTag("play.continue")) {
            LazyColumn(
                state = list,
                modifier = Modifier.fillMaxSize().testTag("shell.playList"),
                contentPadding = PaddingValues(bottom = 40.dp),
            ) {
                if (focused != null) {
                    item(key = "hero", contentType = "hero") { HomeHeroBlock(focused, actions, barInset, heroHeight, pull) }
                    item(key = "actions", contentType = "actions") {
                        Column(follow.padding(horizontal = gutter).padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            ActionRow(focused, actions)
                            SyncStatusLine(page.statusLine, page.offline, Modifier.testTag("play.syncLine"))
                        }
                    }
                } else if (!page.isLoading) {
                    // (While the games load, nothing: the line would sit at the top, then drop
                    // below the hero a moment later.)
                    item(key = "status", contentType = "status") {
                        SyncStatusLine(page.statusLine, page.offline, follow.padding(horizontal = gutter).padding(top = barInset + 8.dp).testTag("play.syncLine"))
                    }
                }
                homeSections(page, actions, focused, onFocus, gutter, section, follow)
            }
            HomeBar(page, actions, overArt = focused != null && !scrolledPastArt, scrolled = scrolled, gutter = gutter)
        }
    }
}

/** In play, the picks, recent saves and notices, `section` apart (a little more under the actions). */
private fun LazyListScope.homeSections(
    page: PlayPage,
    actions: PlayActions,
    focused: PlayGame?,
    onFocus: (Int) -> Unit,
    gutter: Dp,
    section: Dp,
    /** Each row follows a pull past the top. */
    follow: Modifier,
) {
    var first = true
    fun top(): Dp = if (first) (section + 4.dp).also { first = false } else section
    if (page.games.size > 1) {
        val gap = top()
        item(key = "inPlay", contentType = "inPlay") { HomeInPlayRow(page.games, focused?.id, onFocus, actions, gutter, follow.padding(top = gap)) }
    }
    if (page.picks.isNotEmpty()) {
        val gap = top()
        item(key = "picks", contentType = "picks") { PlayPicksRow(page.picks, actions, gutter, follow.padding(top = gap)) }
    }
    if (page.saveDays.isNotEmpty()) auroraRecentSaves(page, actions, regular = false, gutter = gutter, section = top(), itemModifier = follow)
    // Nothing while the games load (the notices are empty then): a lone item would hold the list's
    // place, and the hero arriving above it would open the page scrolled past the hero.
    if (page.isLoading) return
    val gap = top()
    item(key = "notices", contentType = "notices") { Notices(page, gutter, follow.padding(top = gap)) }
}

/**
 * The bar over the hero: the Downloads button at the trailing edge. Clear over the art; once the
 * page scrolls under it, the page ground fades in behind the status bar and eases out below it.
 */
@Composable
private fun HomeBar(page: PlayPage, actions: PlayActions, overArt: Boolean, scrolled: Boolean, gutter: Dp) {
    val grounded by animateFloatAsState(if (!overArt && scrolled) 1f else 0f, tween(200), label = "bar")
    val ground = RommioTheme.light.ground
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Box(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                if (grounded > 0f) {
                    val solid = statusBar.toPx() / size.height
                    drawRect(
                        Brush.verticalGradient(
                            0f to ground.copy(alpha = grounded),
                            solid to ground.copy(alpha = grounded),
                            1f to ground.copy(alpha = 0f),
                        ),
                    )
                }
            }
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Row(
            Modifier.fillMaxWidth().height(ToolbarHeight).padding(horizontal = gutter - 4.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(contentAlignment = Alignment.Center) {
                // Over the art the button sits on a disc of the page ground, so it reads on any cover.
                Box(Modifier.size(40.dp).graphicsLayer { alpha = 1f - grounded }.background(ground.copy(alpha = 0.6f), CircleShape))
                ToolbarIconButton(
                    icon = Icons.Outlined.Download,
                    contentDescription = "Downloads",
                    onClick = actions.openDownloads,
                    badgeCount = page.downloadCount,
                    modifier = Modifier.testTag("play.downloads"),
                )
            }
        }
    }
}

/**
 * Whether the top of the hero's art is dark where the status bar sits over it (null until known,
 * or without a cover). The poster fills a box as wide as the screen and 1.5 times as tall, from
 * the top (cropped about its centre); the bar covers its first [statusBar].
 */
@Composable
private fun rememberArtTopDark(coverUrl: String?, width: Dp, statusBar: Dp): Boolean? {
    val context = LocalContext.current
    val inset = (statusBar / width).toDouble()
    // The last tone stays until the next game's is known (no flicker to the scheme's on a tap).
    val tone by produceState<Boolean?>(initialValue = null, coverUrl, inset) {
        value = coverUrl?.let { url ->
            CoverArt.topIsDark(context.applicationContext, url) { w, h -> ArtTone.statusBarBand(w, h, 1.0, 1.5, inset) }
        }
    }
    return tone
}

/**
 * Every game in play is ready to be the hero: its art at the hero's size, its palette and its
 * blurred cover (the page light), or its trimmed screenshot, so a tap swaps without a placeholder
 * or a dip.
 */
@Composable
private fun PrepareInPlay(games: List<PlayGame>, width: Dp) {
    val context = LocalContext.current
    val dark = RommioTheme.isDark
    val widthPx = with(LocalDensity.current) { width.roundToPx() }
    LaunchedEffect(games.map { it.coverUrl to it.screenshotUrl }, dark, widthPx) {
        val app = context.applicationContext
        for (game in games) {
            val cover = game.coverUrl
            if (cover != null) {
                CoverArt.palette(app, cover)
                CoverArt.backdrop(app, cover, dark)
                CoverArt.prefetch(app, cover, widthPx, widthPx * 3 / 2)
            } else {
                game.screenshotUrl?.let { CoverArt.screen(app, it) }
            }
        }
    }
}

/**
 * Pulled down past the top, the hero's art grows up from its foot to fill the gap instead of
 * leaving the page showing above it; the rows below follow the pull, and both spring back.
 */
@Stable
private class HeroPull : NestedScrollConnection {
    var offset by mutableFloatStateOf(0f)
        private set

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        // Pushed back up while pulled: the pull gives way first.
        if (available.y < 0f && offset > 0f) {
            val used = maxOf(available.y, -offset)
            offset += used
            return Offset(0f, used)
        }
        return Offset.Zero
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        // Down past the top of the list: the hero stretches (with some resistance).
        if (available.y > 0f && source == NestedScrollSource.UserInput) {
            offset += available.y * 0.5f
            return Offset(0f, available.y)
        }
        return Offset.Zero
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        if (offset <= 0f) return Velocity.Zero
        animate(offset, 0f, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) { value, _ -> offset = value }
        return available
    }
}

/** What the hero draws for a game: its cover, else its RomM screenshot. */
private data class HeroArt(val id: Int, val title: String, val coverUrl: String?, val screenshotUrl: String?)

/**
 * The hero at a fixed height: the art dissolving between games, the caption anchored to its foot
 * and growing upward into the art.
 */
@Composable
private fun HomeHeroBlock(game: PlayGame, actions: PlayActions, barInset: Dp, heroHeight: Dp, pull: HeroPull) {
    val theme = RommioTheme.current
    val density = LocalDensity.current
    // The caption's height, in dp: the art fades out by its top. Read while drawing.
    val caption = remember { Animatable(150f) }
    var captionTarget by remember { mutableFloatStateOf(150f) }
    LaunchedEffect(captionTarget) { caption.animateTo(captionTarget, tween(350)) }
    val clearAtPx: () -> Float = { with(density) { maxOf(160.dp, heroHeight - CaptionInset - caption.value.dp + 8.dp).toPx() } }
    val firstId = remember { game.id }
    Box(Modifier.fillMaxWidth().height(heroHeight)) {
        // The next game's art dissolves over the last one's.
        LayeredCrossfade(
            HeroArt(game.id, game.title, game.coverUrl, game.screenshotUrl),
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // Pulled down past the top, the art grows to fill the gap: from the top of the
                    // page to its foot, which follows the pull (iOS scales it about its foot as the
                    // whole page moves down: the same picture).
                    val scale = 1f + pull.offset / size.height.coerceAtLeast(1f)
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0.5f, 0f)
                },
        ) { art ->
            HomeHero(art, barInset, clearAtPx)
        }
        // One layer, so a caption on its way out never stacks with the new one.
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = theme.spacing.gutter)
                .padding(bottom = CaptionInset)
                .graphicsLayer { translationY = pull.offset }
                .onSizeChanged { captionTarget = with(density) { it.height.toDp().value } },
        ) {
            key(game.id) {
                // The old caption is gone at once and the new one fades in: two titles never show at once.
                val alpha = remember { Animatable(if (game.id == firstId) 1f else 0f) }
                LaunchedEffect(Unit) { alpha.animateTo(1f, tween(300, easing = LinearOutSlowInEasing)) }
                HomeCaption(game, actions, Modifier.graphicsLayer { this.alpha = alpha.value })
            }
        }
    }
}

/**
 * The hero: the game's cover art from the top down (a screenshot from RomM when it has no cover),
 * filling its box and dissolving into the page's light by [clearAtPx] (the top of the caption over
 * it, so a cover's own logo never sits behind the title). No scrim at the top.
 */
@Composable
private fun HomeHero(art: HeroArt, barInset: Dp, clearAtPx: () -> Float) {
    Box(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(
                    Brush.verticalGradient(
                        0f to Color.Black,
                        0.5f to Color.Black,
                        0.76f to Color.Black.copy(alpha = 0.35f),
                        0.91f to Color.Black.copy(alpha = 0.06f),
                        1f to Color.Transparent,
                        startY = 0f,
                        endY = clearAtPx(),
                    ),
                    blendMode = BlendMode.DstIn,
                )
            }
            .semantics { contentDescription = "Cover art for ${art.title}" }
            .testTag("play.screen.${art.id}"),
    ) {
        // The game's artwork; saved screens stay in Recent saves (revised 2026-09-27).
        if (art.coverUrl == null && art.screenshotUrl != null) {
            HeroScreen(art.screenshotUrl, barInset) { HeroPoster(null, art.title) }
        } else {
            HeroPoster(art.coverUrl, art.title)
        }
    }
}

/**
 * Cover art from the top down (covers are usually 2:3 posters, taller than the hero): as wide as
 * the screen and 1.5 times as tall, cropped about its centre. Nothing while it loads (the page's
 * light shows); the stand-in only without art or when it can't be loaded.
 */
@Composable
private fun HeroPoster(url: String?, title: String) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = maxWidth
        var failed by remember(url) { androidx.compose.runtime.mutableStateOf(false) }
        Box(Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true).height(width * 1.5f)) {
            if (url == null || failed) CoverPlaceholder(title)
            if (url != null) {
                val context = LocalContext.current
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    onSuccess = { CoverArt.warmPalette(context, url) },
                    onError = { failed = true },
                )
            }
        }
    }
}

/**
 * RomM's screenshot of a game without cover art, at its own aspect, as large as the hero allows
 * (edge to edge when it fits the width) between the bar and the caption, over a blurred copy of
 * itself that lights the top of the page. Until it loads, and if it can't, [fallback] shows.
 */
@Composable
private fun HeroScreen(url: String, barInset: Dp, fallback: @Composable () -> Unit) {
    val theme = RommioTheme.current
    val context = LocalContext.current
    val screen by produceState(initialValue = CoverArt.cachedScreen(url), url) {
        if (value == null) value = CoverArt.screen(context.applicationContext, url)
    }
    Crossfade(targetState = screen, animationSpec = tween(250), label = "screen") { art ->
        if (art == null) {
            fallback()
            return@Crossfade
        }
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (theme.isDark) 0.35f else 0.12f))) {
            Image(
                bitmap = art.glow,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.High,
                alpha = if (theme.isDark) 0.9f else 0.75f,
                modifier = Modifier.fillMaxSize(),
            )
            // The screen sits in the space between the bars and the caption, centred.
            val boxWidth = maxWidth
            val boxHeight = maxOf(160.dp, maxHeight - barInset - 150.dp)
            val aspect = art.image.width.toFloat() / art.image.height.coerceAtLeast(1)
            val (width, height) = if (aspect >= boxWidth / boxHeight) boxWidth to boxWidth / aspect else boxHeight * aspect to boxHeight
            val edge = width >= maxWidth - 1.dp
            // Pixel art enlarged stays crisp; a large screenshot shrinks smoothly.
            val enlarged = art.image.width < width.value * 2
            val radius = if (edge) 0.dp else 16.dp
            Image(
                bitmap = art.image,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                filterQuality = if (enlarged) FilterQuality.None else FilterQuality.High,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = barInset + 4.dp + maxOf(0.dp, (boxHeight - height) / 2))
                    .size(width, height)
                    .dropShadow(Color.Black.copy(alpha = if (theme.isDark) 0.45f else 0.25f), radius, 24.dp, 12.dp)
                    .clip(RoundedCornerShape(radius)),
            )
        }
    }
}

/** Platform · year, the game, where it continues from, and its sync state or conflict. */
@Composable
private fun HomeCaption(game: PlayGame, actions: PlayActions, modifier: Modifier) {
    val theme = RommioTheme.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            RoleText(game.platformLine, theme.type.section, color = theme.colors.inkSecondary, maxLines = 1)
            DisplayText(game.title, role = theme.type.title, size = 34f, maxLines = 2)
            RoleText(game.captionLine, theme.type.secondary, color = theme.colors.inkSecondary, maxLines = 2)
        }
        HomeSyncState(game, actions, Modifier.padding(top = 4.dp))
    }
}

/** The hero's sync state on the page (§11 wording), or its conflict (opens the Conflict sheet). */
@Composable
private fun HomeSyncState(game: PlayGame, actions: PlayActions, modifier: Modifier) {
    val conflict = game.conflictChip
    val sync = game.screenSync
    when {
        conflict != null -> StatusChip(
            conflict,
            tone = Tone.BAD,
            icon = Icons.Outlined.WarningAmber,
            modifier = modifier
                .clip(CircleShape)
                .clickable(role = Role.Button, onClickLabel = "Choose resume point") { actions.resume(game.game) }
                .testTag("play.conflict.${game.id}"),
        )
        sync != null -> SyncBadge(sync.label, sync.tone(), modifier)
    }
}

/** Every game in play as a cover; the one at the top is ringed in its light. */
@Composable
private fun HomeInPlayRow(games: List<PlayGame>, focusedId: Int?, onFocus: (Int) -> Unit, actions: PlayActions, gutter: Dp, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader(title = "In play", meta = games.size.toString(), modifier = Modifier.padding(horizontal = gutter))
        LazyRow(
            contentPadding = PaddingValues(horizontal = gutter),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.testTag("play.inProgress"),
        ) {
            items(games, key = { it.id }) { game ->
                val selected = game.id == focusedId
                ContextMenuBox(
                    onClick = { onFocus(game.id) },
                    actions = actions.menu(game.game),
                    onClickLabel = "Show ${game.title}",
                    modifier = Modifier
                        .padding(vertical = 6.dp)
                        .semantics { this.selected = selected }
                        .testTag("play.inProgress.${game.id}"),
                ) {
                    HomeInPlayCover(game, selected)
                }
            }
        }
    }
}

private val InPlayWidth = 92.dp

@Composable
private fun HomeInPlayCover(game: PlayGame, selected: Boolean) {
    val theme = RommioTheme.current
    val shape = RoundedCornerShape(12.dp)
    val scale by animateFloatAsState(if (selected) 1f else 0.97f, tween(350), label = "scale")
    Column(
        Modifier.width(InPlayWidth).graphicsLayer { scaleX = scale; scaleY = scale }.semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(Modifier.size(InPlayWidth, InPlayWidth * 1.5f)) {
            // Only the selected cover reads the page light (its ring).
            val ring = if (selected) RommioTheme.light.mark1 else Color.Transparent
            GameCover(
                game.coverUrl,
                game.title,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (selected) Modifier.dropShadow(ring.copy(alpha = 0.65f), 12.dp, 10.dp, 0.dp) else Modifier),
                cornerRadius = 12.dp,
                aspect = null,
            )
            if (selected) Box(Modifier.fillMaxSize().border(2.5.dp, ring, shape))
            if (game.conflictChip != null) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(5.dp)
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(OverArt.shade(0.8f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.WarningAmber, contentDescription = "Two resume points", tint = OverArt.bad, modifier = Modifier.size(12.dp))
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            RoleText(
                game.title,
                theme.type.caption,
                size = 12.5f,
                color = if (selected) theme.colors.ink else theme.colors.inkSecondary,
                maxLines = 2,
                minLines = 2,
            )
            game.lastActive?.let { RoleText(it, theme.type.caption, size = 11f, color = theme.colors.inkTertiary, maxLines = 1) }
        }
    }
}

/** Keep going · Something new · Quick hit: a card each, with its reason. */
@Composable
private fun PlayPicksRow(picks: List<PlayPickItem>, actions: PlayActions, gutter: Dp, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader(title = "Pick something", modifier = Modifier.padding(horizontal = gutter))
        LazyRow(
            contentPadding = PaddingValues(start = gutter, end = gutter, top = 6.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(picks, key = { it.kind.key }) { pick -> PlayPickCard(pick, actions) }
        }
    }
}

@Composable
private fun PlayPickCard(pick: PlayPickItem, actions: PlayActions) {
    val theme = RommioTheme.current
    val c = theme.colors
    Row(
        Modifier
            .width(300.dp)
            .height(IntrinsicSize.Min)
            .heightIn(min = 150.dp)
            .themedSurface(theme, 22.dp)
            .clickable(role = Role.Button, onClickLabel = "Open ${pick.title}") { actions.open(pick.id) }
            .padding(12.dp)
            .testTag("play.pick.${pick.kind.key}"),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        GameCover(pick.coverUrl, pick.title, Modifier.size(84.dp, 126.dp), cornerRadius = 10.dp, aspect = null)
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Only the kind reads the page light (text in the readable tint).
                    val tint = RommioTheme.light.tintText
                    Icon(pick.kind.icon(), contentDescription = null, tint = tint, modifier = Modifier.size(13.dp))
                    RoleText(pick.kind.title, theme.type.section, color = tint, maxLines = 1)
                }
                RoleText(pick.title, theme.type.bodyEmphasis, size = 16f, color = c.ink, maxLines = 2)
                RoleText(pick.reason, theme.type.caption, size = 12.5f, color = c.inkSecondary, maxLines = 3)
            }
            Spacer(Modifier.weight(1f).heightIn(min = 6.dp))
            Row(
                Modifier
                    .height(34.dp)
                    .clip(CircleShape)
                    .background(c.buttonFill)
                    .clickable(role = Role.Button, onClickLabel = "${pick.actionTitle} ${pick.title}") {
                        if (pick.starts) actions.resume(pick.game) else actions.open(pick.id)
                    }
                    .padding(horizontal = 14.dp)
                    .testTag("play.pick.${pick.kind.key}.action"),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (pick.starts) Icons.Outlined.PlayArrow else Icons.Outlined.Download, contentDescription = null, tint = c.buttonInk, modifier = Modifier.size(16.dp))
                RoleText(pick.actionTitle, theme.type.bodyEmphasis, size = 14f, color = c.buttonInk, maxLines = 1)
            }
        }
    }
}

private fun PlayPick.Kind.icon() = when (this) {
    PlayPick.Kind.KEEP_GOING -> Icons.Outlined.Refresh
    PlayPick.Kind.SOMETHING_NEW -> Icons.Outlined.AutoAwesome
    PlayPick.Kind.QUICK_HIT -> Icons.Outlined.Bolt
}

// ---- Screens (the regular canvas) ------------------------------------------------------------

/** Transparent room around the blurred frame for its glow to spread into. */
private val GlowMargin = 48.dp

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

// ---- Regular: one canvas ---------------------------------------------------------------------

/** The room as one canvas: the status and dots, the screen beside its caption, the games in progress, recent saves. */
@Composable
private fun RegularPlay(page: PlayPage, actions: PlayActions, focused: PlayGame?, onFocus: (Int) -> Unit) {
    val gutter = 32.dp
    val section = RommioTheme.spacing.section
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("shell.playList"),
        contentPadding = PaddingValues(bottom = 40.dp),
    ) {
        item(key = "toolbar", contentType = "toolbar") { PlayToolbar(page.downloadCount, actions.openDownloads, gutter) }
        item(key = "room", contentType = "room") { Canvas(page, actions, focused, onFocus) }
        if (page.isLoading && !page.hasContent) {
            item(key = "skeleton", contentType = "skeleton") {
                LoadingSkeletonPanel(showArtwork = true, lines = 3, modifier = Modifier.padding(top = section).padding(horizontal = gutter))
            }
        }
        auroraRecentSaves(page, actions, regular = true, gutter = gutter, section = section)
        // The notices column is always part of the page (as the column it was), empty or not.
        item(key = "notices", contentType = "notices") { Notices(page, gutter, Modifier.padding(top = section)) }
    }
}


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
