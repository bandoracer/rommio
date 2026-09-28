package io.github.bandoracer.rommio.ui.screen.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.SortByAlpha
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.TheaterComedy
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.github.bandoracer.rommio.ui.art.CoverArt
import io.github.bandoracer.rommio.ui.component.CollectionTile
import io.github.bandoracer.rommio.ui.component.EmptyState
import io.github.bandoracer.rommio.ui.component.FilterPill
import io.github.bandoracer.rommio.ui.component.GameCover
import io.github.bandoracer.rommio.ui.component.GlassSurface
import io.github.bandoracer.rommio.ui.component.IconCircleButton
import io.github.bandoracer.rommio.ui.component.LitPage
import io.github.bandoracer.rommio.ui.component.PageFieldStyle
import io.github.bandoracer.rommio.ui.component.PageHeader
import io.github.bandoracer.rommio.ui.component.RoleText
import io.github.bandoracer.rommio.ui.component.SecondaryButton
import io.github.bandoracer.rommio.ui.component.SectionHeader
import io.github.bandoracer.rommio.ui.component.dropShadow
import io.github.bandoracer.rommio.ui.component.glassEdge
import io.github.bandoracer.rommio.ui.theme.RommioTheme
import kotlinx.coroutines.launch

/**
 * Aurora Library: every game on one wall of covers (docs/theme-layouts.md, Library; iOS
 * `AuroraLibraryLayout`). One choice orders it (Console · Series · Year · Genre · A–Z). The covers
 * flow continuously; each group starts with a tile that names it (a console's tile opens its
 * page), so a library of small groups stays dense. An index rail jumps between groups, density
 * switches between three and four across, and a press and hold gives a quick look. Lit by the game
 * you're playing; collections close the wall. The model ([LibraryPage]) groups the wall.
 */
@Composable
fun AuroraLibraryLayout(page: LibraryPage, actions: LibraryActions) {
    val theme = RommioTheme.current
    val gutter = theme.spacing.gutter
    val compact = page.columns != 3
    val rail = page.wall.size >= 4
    val end = if (rail) gutter + 24.dp else gutter
    val spacing = if (compact) 9.dp else 12.dp
    val rowSpacing = if (compact) 12.dp else 18.dp
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    var quickLook by remember { mutableStateOf<LibraryWallCover?>(null) }
    // Each group's tile as a grid index (the header comes first), for the rail.
    val tiles = remember(page.wall) {
        var next = 1
        page.wall.map { group -> next.also { next += 1 + group.covers.size } }
    }
    // A new order starts from its first group, with the lens still in view.
    var shownLens by remember { mutableStateOf(page.lens) }
    LaunchedEffect(page.lens) {
        if (page.lens != shownLens) {
            shownLens = page.lens
            grid.animateScrollToItem(0)
        }
    }
    LitPage(page.lightUrl, PageFieldStyle.PAGE) {
        Box(Modifier.fillMaxSize().testTag("library.wall")) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(page.columns),
                state = grid,
                modifier = Modifier.fillMaxSize().testTag("shell.libraryList"),
                contentPadding = PaddingValues(start = gutter, end = end, top = 8.dp, bottom = 40.dp),
                horizontalArrangement = Arrangement.spacedBy(spacing),
                verticalArrangement = Arrangement.spacedBy(rowSpacing),
            ) {
                item(key = "header", contentType = "header", span = { GridItemSpan(maxLineSpan) }) {
                    LibraryHeader(page, actions, Modifier.bleed(gutter, end))
                }
                if (page.isEmpty) {
                    item(key = "empty", contentType = "empty", span = { GridItemSpan(maxLineSpan) }) {
                        EmptyState(
                            title = if (page.isLoading) "Loading your library" else "No platforms yet",
                            message = if (page.isLoading) "" else page.errorMessage ?: "Pull to refresh once your server is reachable.",
                            icon = Icons.Outlined.VideoLibrary,
                        )
                    }
                    return@LazyVerticalGrid
                }
                page.wall.forEach { group ->
                    item(key = "tile/${group.id}", contentType = "tile") { LibraryGroupTile(group, compact, actions) }
                    items(group.covers, key = { "${group.id}/${it.id}" }, contentType = { "cover" }) { cover ->
                        LibraryWallCoverView(cover, compact, actions, onQuickLook = { quickLook = it })
                    }
                }
                if (page.collections.isNotEmpty()) {
                    item(key = "collections", contentType = "collections", span = { GridItemSpan(maxLineSpan) }) {
                        CollectionsRow(page, actions, Modifier.bleed(gutter, end).padding(top = 30.dp - rowSpacing))
                    }
                }
                page.staleMessage?.let { message ->
                    item(key = "stale", contentType = "stale", span = { GridItemSpan(maxLineSpan) }) {
                        RoleText(message, theme.type.caption, color = theme.colors.inkSecondary)
                    }
                }
            }
            if (rail) {
                LibraryIndexRail(page.wall, Modifier.align(Alignment.CenterEnd).padding(end = 4.dp)) { index ->
                    scope.launch { grid.animateScrollToItem(tiles[index]) }
                }
            }
        }
    }
    quickLook?.let { cover ->
        QuickLook(cover, onDismiss = { quickLook = null }, onOpen = {
            quickLook = null
            actions.openGame(cover.id)
        })
    }
}

/** Lets a full-width row run under the grid's side margins (the lens bar and collections scroll edge to edge). */
private fun Modifier.bleed(start: Dp, end: Dp): Modifier = layout { measurable, constraints ->
    val left = start.roundToPx()
    val width = constraints.maxWidth + left + end.roundToPx()
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-left, 0) }
}

/** The page's title and summary, then the lens. */
@Composable
private fun LibraryHeader(page: LibraryPage, actions: LibraryActions, modifier: Modifier) {
    val theme = RommioTheme.current
    val gutter = theme.spacing.gutter
    Column(modifier) {
        Column(Modifier.padding(horizontal = gutter), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PageHeader(title = "Library")
            if (page.hasPlatforms) RoleText(page.summary, theme.type.secondary, color = theme.colors.inkSecondary)
        }
        if (!page.isEmpty) LibraryLensBar(page.lens, page.columns, actions, Modifier.padding(top = 14.dp))
    }
}

/** The lens: one glass chip per order, and the density switch beside them. */
@Composable
private fun LibraryLensBar(lens: LibraryLens, columns: Int, actions: LibraryActions, modifier: Modifier) {
    val gutter = RommioTheme.spacing.gutter
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                // The chips fade out before the density switch rather than slide under it.
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(Brush.horizontalGradient(0f to Color.Black, 0.9f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
                }
                .horizontalScroll(rememberScrollState())
                .padding(start = gutter, end = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LibraryLens.entries.forEach { option ->
                FilterPill(
                    option.title,
                    selected = option == lens,
                    onClick = { actions.selectLens(option) },
                    icon = option.icon(),
                    modifier = Modifier.testTag("library.lens.${option.key}"),
                )
            }
        }
        IconCircleButton(
            icon = if (columns == 3) Icons.Outlined.GridOn else Icons.Outlined.GridView,
            contentDescription = if (columns == 3) "Show more games" else "Show larger covers",
            onClick = actions.toggleColumns,
            size = 44.dp,
            iconSize = 18.dp,
            modifier = Modifier.padding(end = gutter - 2.dp).testTag("library.density"),
        )
    }
}

private fun LibraryLens.icon(): ImageVector = when (this) {
    LibraryLens.CONSOLE -> Icons.Outlined.SportsEsports
    LibraryLens.SERIES -> Icons.Outlined.Layers
    LibraryLens.YEAR -> Icons.Outlined.CalendarMonth
    LibraryLens.GENRE -> Icons.Outlined.TheaterComedy
    LibraryLens.ALPHABETICAL -> Icons.Outlined.SortByAlpha
}

/**
 * A group's first cell: its name and counts on glass, the size of a cover, lit by the group's first
 * cover so the tile belongs to the wall; a console's tile opens its page.
 */
@Composable
private fun LibraryGroupTile(group: LibraryWallGroup, compact: Boolean, actions: LibraryActions) {
    val theme = RommioTheme.current
    val c = theme.colors
    val light = RommioTheme.light
    val radius = if (compact) 9.dp else 12.dp
    val shape = RoundedCornerShape(radius)
    val platform = group.platform
    Column(
        Modifier
            .then(
                if (platform != null) {
                    Modifier
                        .clickable(role = Role.Button, onClickLabel = "Open ${platform.name}") { actions.openPlatform(platform) }
                        .semantics(mergeDescendants = true) { contentDescription = "${platform.name}, ${group.detail}" }
                        .testTag("library.platform.${platform.slug}")
                } else {
                    Modifier.clearAndSetSemantics {
                        heading()
                        contentDescription = "${group.title}, ${group.detail}"
                    }
                },
            ),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(shape)
                .drawWithContent {
                    drawContent()
                    glassEdge(radius.toPx(), theme.isDark)
                },
        ) {
            group.tileArtUrl?.let { TileArt(it, theme.isDark) }
            Box(Modifier.fillMaxSize().background(c.surface))
            Column(Modifier.fillMaxSize().padding(if (compact) 9.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp)) {
                Box(Modifier.size(if (compact) 18.dp else 24.dp, 3.dp).clip(CircleShape).background(light.mark1))
                // A non-breaking hyphen keeps "16-bit" whole in a narrow tile.
                RoleText(group.title.replace("-", "‑"), theme.type.headline, size = if (compact) 14f else 18f, color = c.ink, maxLines = 4)
                group.subtitle?.let { RoleText(it, theme.type.numeral, size = if (compact) 11f else 13f, color = c.inkSecondary, maxLines = 1) }
                Spacer(Modifier.weight(1f))
                RoleText(group.countText, theme.type.caption, size = if (compact) 11f else 12.5f, color = c.inkSecondary, maxLines = 1)
                if (group.installed > 0) {
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Check, contentDescription = null, tint = c.inkSecondary, modifier = Modifier.size(if (compact) 10.dp else 12.dp))
                        RoleText("${group.installed} here", theme.type.caption, size = if (compact) 10.5f else 12f, color = c.inkSecondary, maxLines = 1)
                    }
                }
                if (platform != null && !compact) {
                    Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(1.dp), verticalAlignment = Alignment.CenterVertically) {
                        RoleText("Open", theme.type.bodyEmphasis, size = 12.5f, color = light.tintText, maxLines = 1)
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = light.tintText, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
        if (!compact) {
            // Keeps the tile's row the same height as the covers' titles.
            RoleText(" ", theme.type.caption, size = 12.5f, color = Color.Transparent, maxLines = 2, minLines = 2)
        }
    }
}

/** A group's first cover baked into light (the page field's blurred cover), behind its tile's glass. */
@Composable
private fun TileArt(url: String, dark: Boolean) {
    val context = LocalContext.current
    val art by produceState(initialValue = CoverArt.cachedBackdrop(url, dark), url, dark) {
        if (value == null) value = CoverArt.backdrop(context.applicationContext, url, dark)
    }
    art?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, alpha = if (dark) 0.75f else 0.6f, modifier = Modifier.fillMaxSize()) }
}

/**
 * One cover on the wall: the poster at 2:3, a lit ring while in play, a check when on this device,
 * an eye when browse only; its title under it at three across. Press and hold for a quick look.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryWallCoverView(cover: LibraryWallCover, compact: Boolean, actions: LibraryActions, onQuickLook: (LibraryWallCover) -> Unit) {
    val theme = RommioTheme.current
    val radius = if (compact) 9.dp else 12.dp
    val shape = RoundedCornerShape(radius)
    Column(
        Modifier
            .combinedClickable(
                role = Role.Button,
                onClickLabel = "Open ${cover.title}",
                onLongClickLabel = "Quick look",
                onLongClick = { onQuickLook(cover) },
                onClick = { actions.openGame(cover.id) },
            )
            .testTag("library.game.${cover.id}")
            .clearAndSetSemantics {
                contentDescription = cover.description
                role = Role.Button
            },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
            // Only a cover in play reads the page light (its ring).
            val ring = if (cover.inProgress) RommioTheme.light.mark1 else Color.Transparent
            GameCover(
                cover.coverUrl,
                cover.title,
                modifier = Modifier.fillMaxSize().then(if (cover.inProgress) Modifier.dropShadow(ring.copy(alpha = 0.6f), radius, 8.dp, 0.dp) else Modifier),
                cornerRadius = radius,
                aspect = null,
            )
            if (cover.inProgress) Box(Modifier.fillMaxSize().border(2.5.dp, ring, shape))
            if (cover.installed || cover.browseOnly) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .size(if (compact) 17.dp else 20.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f))
                        .border(0.5.dp, Color.White.copy(alpha = 0.35f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (cover.installed) Icons.Outlined.Check else Icons.Outlined.Visibility,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(if (compact) 10.dp else 12.dp),
                    )
                }
            }
        }
        if (!compact) {
            RoleText(cover.title, theme.type.caption, size = 12.5f, color = theme.colors.ink, maxLines = 2, minLines = 2)
        }
    }
}

/** The trailing index: one label per group; tap to jump. Labels shrink to fit a tall list. */
@Composable
private fun LibraryIndexRail(groups: List<LibraryWallGroup>, modifier: Modifier, jump: (Int) -> Unit) {
    val theme = RommioTheme.current
    BoxWithConstraints(modifier.fillMaxHeight().width(30.dp), contentAlignment = Alignment.Center) {
        val rowHeight = minOf(19.dp, (maxHeight - 12.dp) / groups.size.coerceAtLeast(1))
        Column(Modifier.padding(vertical = 6.dp).testTag("library.index")) {
            groups.forEachIndexed { index, group ->
                Box(
                    Modifier
                        .size(30.dp, rowHeight)
                        .clickable(role = Role.Button, onClickLabel = "Jump to ${group.title}") { jump(index) }
                        .semantics { contentDescription = "Jump to ${group.title}" },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        group.index,
                        style = theme.type.numeral.style.copy(fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold),
                        color = theme.colors.inkSecondary,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

/** A cover pressed and held: the poster large, its name, and the way to its page. */
@Composable
private fun QuickLook(cover: LibraryWallCover, onDismiss: () -> Unit, onOpen: () -> Unit) {
    val theme = RommioTheme.current
    Dialog(onDismissRequest = onDismiss) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            GameCover(
                cover.coverUrl,
                cover.title,
                modifier = Modifier.size(280.dp, 420.dp).clickable(role = Role.Button, onClickLabel = "Open ${cover.title}", onClick = onOpen),
                cornerRadius = 18.dp,
                aspect = null,
            )
            // The strong surface: in its own window the card sits over the dimmed page, not the field.
            GlassSurface(Modifier.width(280.dp), strong = true, contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RoleText(cover.title, theme.type.headline, size = 18f, color = theme.colors.ink, maxLines = 2)
                if (cover.caption.isNotEmpty()) RoleText(cover.caption, theme.type.caption, color = theme.colors.inkSecondary, maxLines = 1)
                SecondaryButton("Game details", onClick = onOpen, icon = Icons.Outlined.Info, modifier = Modifier.testTag("library.quickLook.details"))
            }
        }
    }
}

@Composable
private fun CollectionsRow(page: LibraryPage, actions: LibraryActions, modifier: Modifier) {
    val theme = RommioTheme.current
    val gutter = theme.spacing.gutter
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader(title = "Collections", meta = page.collections.size.toString(), modifier = Modifier.padding(horizontal = gutter + 6.dp))
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = gutter).padding(top = 6.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            page.collections.forEach { item ->
                CollectionTile(
                    name = item.collection.name,
                    count = item.collection.romCount,
                    imageUrl = item.coverUrl,
                    onClick = { actions.openCollection(item.collection) },
                    modifier = Modifier.testTag("library.collection.${item.collection.id}"),
                )
            }
        }
    }
}
