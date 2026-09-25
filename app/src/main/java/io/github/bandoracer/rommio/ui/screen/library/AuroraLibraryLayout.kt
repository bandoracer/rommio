package io.github.bandoracer.rommio.ui.screen.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.bandoracer.rommio.domain.player.EmbeddedSupportTier
import io.github.bandoracer.rommio.ui.component.CollectionTile
import io.github.bandoracer.rommio.ui.component.EmptyState
import io.github.bandoracer.rommio.ui.component.FilterPill
import io.github.bandoracer.rommio.ui.component.GameCover
import io.github.bandoracer.rommio.ui.component.ListDivider
import io.github.bandoracer.rommio.ui.component.ListGroup
import io.github.bandoracer.rommio.ui.component.ListRow
import io.github.bandoracer.rommio.ui.component.LitPage
import io.github.bandoracer.rommio.ui.component.PageFieldStyle
import io.github.bandoracer.rommio.ui.component.PageHeader
import io.github.bandoracer.rommio.ui.component.PlayFormat
import io.github.bandoracer.rommio.ui.component.RoleText
import io.github.bandoracer.rommio.ui.component.SecondaryButton
import io.github.bandoracer.rommio.ui.component.SectionHeader
import io.github.bandoracer.rommio.ui.component.dropShadow
import io.github.bandoracer.rommio.ui.component.rememberCoverMark
import io.github.bandoracer.rommio.ui.component.supportTierLabel
import io.github.bandoracer.rommio.ui.component.themedSurface
import io.github.bandoracer.rommio.ui.theme.RommioTheme
import kotlin.math.abs

/**
 * Aurora Library: an era ruler by release year (when years place games in two or more eras),
 * then covers lit by the cover in focus, with platforms and support tiers as filters above the
 * platform list and collections. The row shows the chosen era (games in progress first, then by
 * release year), or with "All years" games in progress first, then A–Z. The browse choices and the
 * row's games come from the model ([LibraryPage]); the sections are lazy items.
 */
@Composable
fun AuroraLibraryLayout(page: LibraryPage, actions: LibraryActions) {
    val theme = RommioTheme.current
    val gutter = theme.spacing.gutter
    val games = page.browse
    var focusId by remember(games) { mutableStateOf(games.firstOrNull()?.id) }
    val focused = games.firstOrNull { it.id == focusId } ?: games.firstOrNull()
    LitPage(focused?.coverUrl, PageFieldStyle.PAGE) {
        LazyColumn(
            Modifier.fillMaxSize().testTag("shell.libraryList"),
            contentPadding = PaddingValues(top = 8.dp, bottom = 40.dp),
        ) {
            // A column with 22 dp between its parts.
            val gap = Modifier.padding(top = 22.dp)
            item(key = "header", contentType = "header") {
                Column(Modifier.padding(horizontal = gutter), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    PageHeader(title = "Library")
                    if (page.hasPlatforms) RoleText(page.summary, theme.type.secondary, color = theme.colors.inkSecondary)
                }
            }
            if (!page.hasPlatforms) {
                item(key = "empty", contentType = "empty") {
                    EmptyState(
                        title = if (page.isLoading) "Loading your library" else "No platforms yet",
                        message = if (page.isLoading) "" else page.errorMessage ?: "Pull to refresh once your server is reachable.",
                        icon = Icons.Outlined.VideoLibrary,
                        modifier = gap,
                    )
                }
                return@LazyColumn
            }
            if (page.showsRuler) {
                item(key = "eras", contentType = "eras") { EraRuler(page, actions, gap.padding(horizontal = gutter)) }
            }
            item(key = "browseHeader", contentType = "browseHeader") { BrowseHeader(page, actions, gap.padding(horizontal = gutter)) }
            item(key = "covers", contentType = "covers") {
                CoverRow(page, games, focused, onFocus = { focusId = it }, actions, gap)
            }
            item(key = "facets", contentType = "facets") { Facets(page, actions, gap) }
            page.sections.filter { page.tier == null || it.tier == page.tier }.forEachIndexed { index, section ->
                item(key = "tier/${section.tier}", contentType = "tierGroup") {
                    val top = if (index == 0) 22.dp else theme.spacing.section
                    ListGroup(title = section.title, meta = section.platforms.size.toString(), modifier = Modifier.padding(top = top).padding(horizontal = gutter)) {
                        section.platforms.forEachIndexed { rowIndex, entry ->
                            if (rowIndex > 0) ListDivider()
                            ListRow(
                                title = entry.platform.name,
                                subtitle = entry.subtitle,
                                showChevron = true,
                                onClick = { actions.openPlatform(entry.platform) },
                                modifier = Modifier.testTag("library.platform.${entry.platform.slug}"),
                            )
                        }
                    }
                }
            }
            if (page.tier == null && page.platformFilter == null && page.collections.isNotEmpty()) {
                item(key = "collections", contentType = "collections") { CollectionsRow(page, actions, gap) }
            }
            page.staleMessage?.let { message ->
                item(key = "stale", contentType = "stale") {
                    RoleText(message, theme.type.caption, color = theme.colors.inkSecondary, modifier = gap.padding(horizontal = gutter))
                }
            }
        }
    }
}

/**
 * The era ruler: one segment per era with games, as wide as its share of the library (at least
 * 15 %): a 6 dp bar in a gradient of the era's games' `k1` at 40 % (the selected era 8 dp, full
 * strength, glowing), the years `bodyEmphasis` 12 and the count `caption` 11. Tapping the
 * selected era again shows all years.
 */
@Composable
private fun EraRuler(page: LibraryPage, actions: LibraryActions, modifier: Modifier) {
    val theme = RommioTheme.current
    val c = theme.colors
    val total = page.eraTotal
    Row(modifier.fillMaxWidth().padding(top = 2.dp).testTag("library.eras"), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        page.eras.forEach { era ->
            val on = era.id == page.selectedEra
            val lights = era.leadCovers.map { rememberCoverMark(it) }
            Column(
                Modifier
                    .weight(maxOf(era.count.toFloat(), total * 0.15f))
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Tab) { actions.selectEra(era.id, page.selectedEra) }
                    .semantics(mergeDescendants = true) {
                        this.selected = on
                        contentDescription = "${era.name}, ${era.short}, ${PlayFormat.games(era.count)}"
                    }
                    .padding(vertical = 4.dp)
                    .testTag("library.era.${era.id}"),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // An era without covers takes the page's tint and mark (the light is read only then).
                val first = lights.firstOrNull()
                val light = if (first == null) RommioTheme.light else null
                val single = first ?: light!!.tint
                val bar = if (lights.size > 1) Brush.horizontalGradient(lights) else Brush.horizontalGradient(listOf(single, single))
                // The selected bar grows about its centre line so the labels stay on one baseline.
                Box(Modifier.fillMaxWidth().height(8.dp), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(if (on) 8.dp else 6.dp)
                            .then(if (on) Modifier.dropShadow((first ?: light!!.mark1).copy(alpha = 0.6f), 4.dp, radius = 8.dp, y = 4.dp) else Modifier)
                            .clip(RoundedCornerShape(4.dp))
                            .graphicsLayer { alpha = if (on) 1f else 0.4f }
                            .background(bar),
                    )
                }
                RoleText(era.short, theme.type.bodyEmphasis, size = 12f, color = if (on) c.ink else c.inkSecondary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Visible)
                RoleText(era.countText, theme.type.caption, size = 11f, color = c.inkTertiary, maxLines = 1)
            }
        }
    }
}

@Composable
private fun BrowseHeader(page: LibraryPage, actions: LibraryActions, modifier: Modifier) {
    val theme = RommioTheme.current
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // With an era: its name (`headline` 24) and "N games · N here" for what the row draws from.
            RoleText(
                page.browseTitle,
                theme.type.headline,
                size = if (page.browseTitleIsEra) 24f else null,
                color = theme.colors.ink,
            )
            page.browseDetail?.let { detail ->
                RoleText(
                    detail,
                    theme.type.caption,
                    size = if (page.browseTitleIsEra) 12.5f else null,
                    color = theme.colors.inkSecondary,
                )
            }
        }
        page.platformFilter?.let {
            SecondaryButton("See all", onClick = { actions.openPlatform(it.platform) }, modifier = Modifier.testTag("library.seeAll"))
        }
    }
}

/**
 * 150 × 200 covers overlapping by 18, snapping with the focused cover centred; each cover turns
 * away with its distance from the centre (scale 1 − 0.22|p|, rotate Y −34°·p, opacity
 * 1 − 0.28|p|). The centred cover lights the page.
 */
@Composable
private fun CoverRow(page: LibraryPage, games: List<BrowseGame>, focused: BrowseGame?, onFocus: (Int) -> Unit, actions: LibraryActions, modifier: Modifier) {
    val theme = RommioTheme.current
    if (games.isEmpty()) {
        RoleText(
            page.browseEmpty,
            theme.type.secondary,
            color = theme.colors.inkSecondary,
            modifier = modifier.padding(horizontal = theme.spacing.gutter),
        )
        return
    }
    val density = LocalDensity.current
    val listState = rememberLazyListState()
    val itemWidth = 150.dp
    val overlap = 18.dp
    val stridePx = with(density) { (itemWidth - overlap).toPx() }
    val centred by remember(games) {
        derivedStateOf {
            val info = listState.layoutInfo
            val centre = (info.viewportStartOffset + info.viewportEndOffset) / 2f
            info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2f - centre) }?.index
        }
    }
    // The centred cover takes the focus (and lights the page) as the row moves onto it.
    LaunchedEffect(games) {
        snapshotFlow { centred }.collect { index -> index?.let { games.getOrNull(it)?.id }?.let(onFocus) }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(210.dp)) {
            val side = (maxWidth - itemWidth + overlap) / 2
            LazyRow(
                state = listState,
                contentPadding = PaddingValues(horizontal = side),
                flingBehavior = rememberSnapFlingBehavior(listState, SnapPosition.Center),
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                itemsIndexed(games, key = { _, rom -> rom.id }) { index, rom ->
                    val phase by remember(index) {
                        derivedStateOf {
                            val info = listState.layoutInfo
                            val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return@derivedStateOf 2f
                            val centre = (info.viewportStartOffset + info.viewportEndOffset) / 2f
                            (item.offset + item.size / 2f - centre) / stridePx
                        }
                    }
                    Box(
                        Modifier
                            .zIndex(10f - abs(phase))
                            // Covers overlap by 18 dp: each takes 132 dp of the row and draws 150.
                            .layout { measurable, constraints ->
                                val placeable = measurable.measure(constraints)
                                val take = placeable.width - overlap.roundToPx()
                                layout(take, placeable.height) { placeable.place(-overlap.roundToPx() / 2, 0) }
                            }
                            .graphicsLayer {
                                val p = phase
                                val s = 1f - minOf(abs(p), 1f) * 0.22f
                                scaleX = s
                                scaleY = s
                                rotationY = p * -34f
                                cameraDistance = 6f * density.density
                                alpha = 1f - minOf(abs(p), 1.5f) * 0.28f
                            }
                            .clickable(role = Role.Button, onClickLabel = "Open ${rom.title}") { actions.openGame(rom.id) }
                            .semantics { contentDescription = rom.title }
                            .testTag("library.cover.${rom.id}"),
                    ) {
                        GameCover(rom.coverUrl, rom.title, Modifier.size(itemWidth, itemWidth * 4f / 3f), aspect = null)
                    }
                }
            }
        }
        focused?.let { rom ->
            Column(
                Modifier.fillMaxWidth().padding(horizontal = theme.spacing.gutter).semantics(mergeDescendants = true) {},
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                RoleText(rom.title, theme.type.headline, size = 21f, color = theme.colors.ink, maxLines = 1, textAlign = TextAlign.Center)
                RoleText(rom.caption, theme.type.caption, size = 13f, color = theme.colors.inkSecondary, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun Facets(page: LibraryPage, actions: LibraryActions, modifier: Modifier) {
    val theme = RommioTheme.current
    val gutter = theme.spacing.gutter
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = gutter).padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            page.facets.forEach { entry ->
                PlatformFacet(entry, selected = page.platformFilter?.platform?.id == entry.platform.id) { actions.togglePlatform(entry.platform.id) }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = gutter), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterPill("All", selected = page.tier == null, onClick = { actions.selectTier(null) }, count = page.platforms.size, modifier = Modifier.testTag("library.filter.all"))
            page.sections.forEach { section ->
                FilterPill(
                    supportTierLabel(section.tier),
                    selected = page.tier == section.tier,
                    onClick = { actions.selectTier(section.tier) },
                    count = section.platforms.size,
                    icon = when (section.tier) {
                        EmbeddedSupportTier.TOUCH_SUPPORTED -> Icons.Outlined.TouchApp
                        EmbeddedSupportTier.CONTROLLER_SUPPORTED -> Icons.Outlined.SportsEsports
                        EmbeddedSupportTier.UNSUPPORTED -> Icons.Outlined.Visibility
                    },
                    modifier = Modifier.testTag("library.filter.${section.tier.name.lowercase()}"),
                )
            }
        }
    }
}

/** A platform as a filter: a dot, its slug, count and how many are here; outlined in `mark1` when selected. */
@Composable
private fun PlatformFacet(entry: LibraryPlatformItem, selected: Boolean, onClick: () -> Unit) {
    val theme = RommioTheme.current
    val c = theme.colors
    val shape = RoundedCornerShape(16.dp)
    // Only the selected facet reads the page light (its outline and dot).
    val mark = if (selected) RommioTheme.light.mark1 else Color.Transparent
    Column(
        Modifier
            .heightIn(min = 48.dp)
            .themedSurface(theme, 16.dp, fill = c.surface, nested = true)
            .border(if (selected) 1.5.dp else 0.dp, mark, shape)
            .clip(shape)
            .semantics(mergeDescendants = true) {
                this.selected = selected
                contentDescription = entry.description
            }
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag("library.facet.${entry.platform.slug}"),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (selected) mark else c.ink.copy(alpha = 0.7f)))
            RoleText(entry.platform.slug.uppercase(), theme.type.bodyEmphasis, size = 14f, color = c.ink, maxLines = 1)
            RoleText(entry.countText, theme.type.numeral, size = 13f, color = c.inkSecondary)
        }
        RoleText(entry.hereText, theme.type.caption, size = 11f, color = c.inkTertiary, modifier = Modifier.padding(start = 14.dp))
    }
}

@Composable
private fun CollectionsRow(page: LibraryPage, actions: LibraryActions, modifier: Modifier) {
    val theme = RommioTheme.current
    val gutter = theme.spacing.gutter
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader(title = "Collections", meta = page.collections.size.toString(), modifier = Modifier.padding(horizontal = gutter + 6.dp))
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = gutter).padding(top = 6.dp, bottom = 16.dp),
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
