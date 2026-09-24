package io.github.bandoracer.rommio.ui.screen.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import io.github.bandoracer.rommio.model.RomDto
import io.github.bandoracer.rommio.ui.component.CollectionTile
import io.github.bandoracer.rommio.ui.component.EmptyState
import io.github.bandoracer.rommio.ui.component.FilterPill
import io.github.bandoracer.rommio.ui.component.GameCover
import io.github.bandoracer.rommio.ui.component.ListDivider
import io.github.bandoracer.rommio.ui.component.ListGroup
import io.github.bandoracer.rommio.ui.component.ListRow
import io.github.bandoracer.rommio.ui.component.PageArtSubject
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
 * release year), or with "All years" games in progress first, then A–Z.
 */
@Composable
fun AuroraLibraryLayout(state: LibraryUiState, actions: LibraryActions) {
    val theme = RommioTheme.current
    val gutter = theme.spacing.gutter
    var platformId by rememberSaveable { mutableStateOf<Int?>(null) }
    var tierKey by rememberSaveable { mutableStateOf<String?>(null) }
    val tier = tierKey?.let { key -> EmbeddedSupportTier.entries.firstOrNull { it.name == key } }
    val platformFilter = state.platforms.firstOrNull { it.platform.id == platformId }
    // "auto" = open on the era of the game in progress; "" = all years; else an era id.
    var eraChoice by rememberSaveable { mutableStateOf("auto") }
    val showsRuler = LibraryEras.showsRuler(state.eras)
    val eraId = when {
        !showsRuler -> null
        eraChoice == "auto" -> LibraryEras.defaultEra(state.eras, state.inProgress)
        eraChoice.isEmpty() -> null
        else -> eraChoice.takeIf { id -> state.eras.any { it.era.id == id } }
    }
    val era = state.eras.firstOrNull { it.era.id == eraId }
    val pool = remember(state.roms, state.inProgress, platformFilter, tier, era) { browsePool(state, platformFilter, tier, era) }
    val games = remember(pool) { pool.take(18) }
    var focusId by remember(games) { mutableStateOf(games.firstOrNull()?.id) }
    val focused = games.firstOrNull { it.id == focusId } ?: games.firstOrNull()
    PageArtSubject(actions.cover(focused?.urlCover), PageFieldStyle.PAGE)

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .testTag("shell.libraryList")
            .padding(top = 8.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Column(Modifier.padding(horizontal = gutter), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PageHeader(title = "Library")
            if (state.platforms.isNotEmpty()) RoleText(state.summary, theme.type.secondary, color = theme.colors.inkSecondary)
        }
        if (state.platforms.isEmpty()) {
            EmptyState(
                title = if (state.isLoading) "Loading your library" else "No platforms yet",
                message = if (state.isLoading) "" else state.errorMessage ?: "Pull to refresh once your server is reachable.",
                icon = Icons.Outlined.VideoLibrary,
            )
            return@Column
        }
        if (showsRuler) {
            EraRuler(state, eraId, onSelect = { id -> eraChoice = if (id == eraId) "" else id }, actions, Modifier.padding(horizontal = gutter))
        }
        BrowseHeader(state, platformFilter, tier, era, pool, actions, Modifier.padding(horizontal = gutter))
        CoverRow(state, games, focused, era, onFocus = { focusId = it }, actions)
        Facets(state, platformFilter, tier, onPlatform = { id ->
            platformId = if (platformId == id) null else id
        }, onTier = { next ->
            tierKey = if (tier == next) null else next?.name
            if (platformFilter != null && next != null && platformFilter.tier != next) platformId = null
        })
        Platforms(state, tier, actions, Modifier.padding(horizontal = gutter))
        if (tier == null && platformFilter == null && state.collections.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeader(title = "Collections", meta = state.collections.size.toString(), modifier = Modifier.padding(horizontal = gutter + 6.dp))
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = gutter).padding(top = 6.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    state.collections.forEach { collection ->
                        CollectionTile(
                            name = collection.name,
                            count = collection.romCount,
                            imageUrl = actions.cover(state.collectionCover(collection)),
                            onClick = { actions.openCollection(collection) },
                            modifier = Modifier.testTag("library.collection.${collection.id}"),
                        )
                    }
                }
            }
        }
        state.staleMessage?.let { RoleText(it, theme.type.caption, color = theme.colors.inkSecondary, modifier = Modifier.padding(horizontal = gutter)) }
    }
}

/**
 * The browse pool: filter (era, platform or tier), games in progress first, then the era's games
 * by release year (the era group's order) or, with all years, A–Z. The row shows the first 18.
 */
private fun browsePool(state: LibraryUiState, platform: LibraryPlatform?, tier: EmbeddedSupportTier?, era: EraGroup?): List<RomDto> {
    val tierSlugs = tier?.let { t -> state.platforms.filter { it.tier == t }.flatMap { listOf(it.platform.slug, it.platform.fsSlug) }.toSet() }
    val source = era?.games ?: state.roms.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName })
    val pool = source.filter { rom ->
        when {
            platform != null -> rom.platformId == platform.platform.id || rom.platformSlug == platform.platform.slug
            tierSlugs != null -> rom.platformSlug in tierSlugs
            else -> true
        }
    }
    val poolIds = pool.map { it.id }.toSet()
    val progress = state.inProgress.filter { it.id in poolIds }
    val progressIds = progress.map { it.id }.toSet()
    return progress + pool.filter { it.id !in progressIds }
}

/**
 * The era ruler: one segment per era with games, as wide as its share of the library (at least
 * 15 %): a 6 dp bar in a gradient of the era's games' `k1` at 40 % (the selected era 8 dp, full
 * strength, glowing), the years `bodyEmphasis` 12 and the count `caption` 11. Tapping the
 * selected era again shows all years.
 */
@Composable
private fun EraRuler(state: LibraryUiState, selected: String?, onSelect: (String) -> Unit, actions: LibraryActions, modifier: Modifier) {
    val theme = RommioTheme.current
    val c = theme.colors
    val total = state.eras.sumOf { it.games.size }.coerceAtLeast(1)
    Row(modifier.fillMaxWidth().padding(top = 2.dp).testTag("library.eras"), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        state.eras.forEach { group ->
            val on = group.era.id == selected
            val inProgressIds = state.inProgress.map { it.id }.toSet()
            val lead = (group.games.filter { it.id in inProgressIds } + group.games).distinctBy { it.id }.take(3)
            val lights = lead.map { rememberCoverMark(actions.cover(it.urlCover)) }
            Column(
                Modifier
                    .weight(maxOf(group.games.size.toFloat(), total * 0.15f))
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Tab) { onSelect(group.era.id) }
                    .semantics(mergeDescendants = true) {
                        this.selected = on
                        contentDescription = "${group.era.name}, ${group.short}, ${PlayFormat.games(group.games.size)}"
                    }
                    .padding(vertical = 4.dp)
                    .testTag("library.era.${group.era.id}"),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val bar = if (lights.size > 1) Brush.horizontalGradient(lights) else Brush.horizontalGradient(listOf(lights.firstOrNull() ?: c.tint, lights.firstOrNull() ?: c.tint))
                // The selected bar grows about its centre line so the labels stay on one baseline.
                Box(Modifier.fillMaxWidth().height(8.dp), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(if (on) 8.dp else 6.dp)
                            .then(if (on) Modifier.dropShadow((lights.firstOrNull() ?: theme.mark1).copy(alpha = 0.6f), 4.dp, radius = 8.dp, y = 4.dp) else Modifier)
                            .clip(RoundedCornerShape(4.dp))
                            .graphicsLayer { alpha = if (on) 1f else 0.4f }
                            .background(bar),
                    )
                }
                RoleText(group.short, theme.type.bodyEmphasis, size = 12f, color = if (on) c.ink else c.inkSecondary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Visible)
                RoleText(java.text.NumberFormat.getIntegerInstance().format(group.games.size), theme.type.caption, size = 11f, color = c.inkTertiary, maxLines = 1)
            }
        }
    }
}

@Composable
private fun BrowseHeader(
    state: LibraryUiState,
    platform: LibraryPlatform?,
    tier: EmbeddedSupportTier?,
    era: EraGroup?,
    pool: List<RomDto>,
    actions: LibraryActions,
    modifier: Modifier,
) {
    val theme = RommioTheme.current
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // With an era: its name (`headline` 24) and "N games · N here" for what the row draws from.
            RoleText(
                era?.era?.name ?: platform?.platform?.name ?: tier?.let(::tierTitle) ?: "All platforms",
                theme.type.headline,
                size = if (era != null) 24f else null,
                color = theme.colors.ink,
            )
            when {
                era != null -> RoleText(
                    listOfNotNull(
                        platform?.platform?.slug?.uppercase() ?: tier?.let(::supportTierLabel),
                        PlayFormat.games(pool.size),
                        "${pool.count { it.id in state.installedRomIds }} here",
                    ).joinToString(" · "),
                    theme.type.caption,
                    size = 12.5f,
                    color = theme.colors.inkSecondary,
                )
                platform != null -> RoleText("${PlayFormat.games(platform.platform.romCount)} · ${state.installed(platform)} here", theme.type.caption, color = theme.colors.inkSecondary)
            }
        }
        platform?.let {
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
private fun CoverRow(state: LibraryUiState, games: List<RomDto>, focused: RomDto?, era: EraGroup?, onFocus: (Int) -> Unit, actions: LibraryActions) {
    val theme = RommioTheme.current
    if (games.isEmpty()) {
        RoleText(
            if (era != null && state.roms.isNotEmpty()) "No games here from ${era.short}." else "Covers appear here as your library loads.",
            theme.type.secondary,
            color = theme.colors.inkSecondary,
            modifier = Modifier.padding(horizontal = theme.spacing.gutter),
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
    centred?.let { index -> games.getOrNull(index)?.id?.let { if (it != focused?.id) onFocus(it) } }
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
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
                            .clickable(role = Role.Button, onClickLabel = "Open ${rom.displayName}") { actions.openGame(rom.id) }
                            .semantics { contentDescription = rom.displayName }
                            .testTag("library.cover.${rom.id}"),
                    ) {
                        GameCover(actions.cover(rom.urlCover), rom.displayName, Modifier.size(itemWidth, itemWidth * 4f / 3f), aspect = null)
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
                RoleText(rom.displayName, theme.type.headline, size = 21f, color = theme.colors.ink, maxLines = 1, textAlign = TextAlign.Center)
                val parts = buildList {
                    add(rom.platformSlug.uppercase())
                    rom.metadata.releaseYear?.let { add(it.toString()) }
                    if (state.inProgress.any { it.id == rom.id }) add("In progress")
                }
                RoleText(parts.joinToString(" · "), theme.type.caption, size = 13f, color = theme.colors.inkSecondary, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun Facets(
    state: LibraryUiState,
    platform: LibraryPlatform?,
    tier: EmbeddedSupportTier?,
    onPlatform: (Int) -> Unit,
    onTier: (EmbeddedSupportTier?) -> Unit,
) {
    val theme = RommioTheme.current
    val gutter = theme.spacing.gutter
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = gutter).padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.platforms.filter { tier == null || it.tier == tier }.forEach { entry ->
                PlatformFacet(entry, state.installed(entry), selected = platform?.platform?.id == entry.platform.id) { onPlatform(entry.platform.id) }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = gutter), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterPill("All", selected = tier == null, onClick = { onTier(null) }, count = state.platforms.size, modifier = Modifier.testTag("library.filter.all"))
            state.sections().forEach { (sectionTier, platforms) ->
                FilterPill(
                    supportTierLabel(sectionTier),
                    selected = tier == sectionTier,
                    onClick = { onTier(sectionTier) },
                    count = platforms.size,
                    icon = when (sectionTier) {
                        EmbeddedSupportTier.TOUCH_SUPPORTED -> Icons.Outlined.TouchApp
                        EmbeddedSupportTier.CONTROLLER_SUPPORTED -> Icons.Outlined.SportsEsports
                        EmbeddedSupportTier.UNSUPPORTED -> Icons.Outlined.Visibility
                    },
                    modifier = Modifier.testTag("library.filter.${sectionTier.name.lowercase()}"),
                )
            }
        }
    }
}

/** A platform as a filter: a dot, its slug, count and how many are here; outlined in `mark1` when selected. */
@Composable
private fun PlatformFacet(entry: LibraryPlatform, installed: Int, selected: Boolean, onClick: () -> Unit) {
    val theme = RommioTheme.current
    val c = theme.colors
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .heightIn(min = 48.dp)
            .themedSurface(theme, 16.dp, fill = c.surface, nested = true)
            .border(if (selected) 1.5.dp else 0.dp, if (selected) theme.mark1 else Color.Transparent, shape)
            .clip(shape)
            .semantics(mergeDescendants = true) {
                this.selected = selected
                contentDescription = "${entry.platform.name}, ${PlayFormat.games(entry.platform.romCount)}, $installed on this device"
            }
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag("library.facet.${entry.platform.slug}"),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (selected) theme.mark1 else c.ink.copy(alpha = 0.7f)))
            RoleText(entry.platform.slug.uppercase(), theme.type.bodyEmphasis, size = 14f, color = c.ink, maxLines = 1)
            RoleText(java.text.NumberFormat.getIntegerInstance().format(entry.platform.romCount), theme.type.numeral, size = 13f, color = c.inkSecondary)
        }
        RoleText(if (installed == 0) "none here" else "$installed here", theme.type.caption, size = 11f, color = c.inkTertiary, modifier = Modifier.padding(start = 14.dp))
    }
}

@Composable
private fun Platforms(state: LibraryUiState, tier: EmbeddedSupportTier?, actions: LibraryActions, modifier: Modifier) {
    val theme = RommioTheme.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(theme.spacing.section)) {
        state.sections().filter { tier == null || it.first == tier }.forEach { (sectionTier, platforms) ->
            ListGroup(title = tierTitle(sectionTier), meta = platforms.size.toString()) {
                platforms.forEachIndexed { index, entry ->
                    if (index > 0) ListDivider()
                    ListRow(
                        title = entry.platform.name,
                        subtitle = "${PlayFormat.games(entry.platform.romCount)} · ${state.installed(entry)} on this device",
                        showChevron = true,
                        onClick = { actions.openPlatform(entry.platform) },
                        modifier = Modifier.testTag("library.platform.${entry.platform.slug}"),
                    )
                }
            }
        }
    }
}
