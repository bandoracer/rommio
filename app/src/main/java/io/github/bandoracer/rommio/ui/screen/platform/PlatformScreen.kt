package io.github.bandoracer.rommio.ui.screen.platform

import io.github.bandoracer.rommio.ui.component.OverArt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.bandoracer.rommio.AppContainer
import io.github.bandoracer.rommio.ui.component.DisplayText
import io.github.bandoracer.rommio.ui.component.EmptyState
import io.github.bandoracer.rommio.ui.component.GameCover
import io.github.bandoracer.rommio.ui.component.GlassSurface
import io.github.bandoracer.rommio.ui.component.LitPage
import io.github.bandoracer.rommio.ui.component.PageFieldStyle
import io.github.bandoracer.rommio.ui.component.PageHeader
import io.github.bandoracer.rommio.ui.component.PrintRule
import io.github.bandoracer.rommio.ui.component.RoleText
import io.github.bandoracer.rommio.ui.component.RuleWeight
import io.github.bandoracer.rommio.ui.component.SectionHeader
import io.github.bandoracer.rommio.ui.component.SegmentedChoice
import io.github.bandoracer.rommio.ui.component.StatusChip
import io.github.bandoracer.rommio.ui.component.SupportTierBadge
import io.github.bandoracer.rommio.ui.component.Tone
import io.github.bandoracer.rommio.ui.component.isRegularWidth
import io.github.bandoracer.rommio.ui.theme.RommioTheme
import java.net.URLDecoder

/**
 * Platform (docs/theme-layouts.md "Platform — two layouts"): its games with what this device
 * knows about each. Aurora: a summary card and a cover grid lit by the most recently played game.
 * Riso: a platform opener with the most played covers, then an A–Z index. Reached from Library;
 * the detail column when split.
 *
 * The page is built by [PlatformViewModel] ([PlatformPage]); the games are lazy (a grid row or an
 * index entry is built only when it scrolls into view), so a platform with thousands of games
 * opens as fast as one with ten.
 */
@Composable
fun PlatformScreen(
    container: AppContainer,
    platformId: Int,
    platformName: String,
    @Suppress("UNUSED_PARAMETER") imageBaseUrl: String?,
    onOpenGame: (romId: Int) -> Unit,
) {
    val routeName = remember(platformName) { runCatching { URLDecoder.decode(platformName, Charsets.UTF_8.name()) }.getOrDefault(platformName) }
    val viewModel: PlatformViewModel = viewModel(
        key = "platform-$platformId",
        factory = viewModelFactory {
            initializer { PlatformViewModel(container.repository, platformId, container.playActivity, createSavedStateHandle(), routeName) }
        },
    )
    val page by viewModel.page.collectAsStateWithLifecycle()
    val actions = remember(viewModel, onOpenGame) {
        PlatformActions(open = onOpenGame, setFilter = viewModel::setFilter, toggleOrder = viewModel::toggleOrder)
    }
    LitPage(if (RommioTheme.isRiso) null else page.leadCoverUrl, if (RommioTheme.isRiso) PageFieldStyle.QUIET else PageFieldStyle.PAGE) {
        io.github.bandoracer.rommio.ui.component.RefreshBox(
            refreshing = page.isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize().testTag("screen.platform"),
        ) {
            if (RommioTheme.isRiso) RisoPlatformLayout(page, actions) else AuroraPlatformLayout(page, actions)
        }
    }
}

/** What the Platform layouts can do. */
class PlatformActions(
    val open: (romId: Int) -> Unit,
    val setFilter: (PlatformFilter) -> Unit,
    val toggleOrder: () -> Unit,
)

private fun emptyTitle(filter: PlatformFilter) = if (filter == PlatformFilter.ALL) "No games yet" else "Nothing here yet"

private fun emptyMessage(filter: PlatformFilter) = when (filter) {
    PlatformFilter.ALL -> "This platform has no games on your server yet."
    PlatformFilter.ON_DEVICE -> "Download a game from its page to play it offline."
    PlatformFilter.PLAYED -> "Games you play on this device show up here."
}

// ---- Aurora ----------------------------------------------------------------------------------

@Composable
private fun AuroraPlatformLayout(page: PlatformPage, actions: PlatformActions) {
    val theme = RommioTheme.current
    val regular = isRegularWidth()
    val gutter = theme.spacing.gutter
    val section = theme.spacing.section
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        BoxWithConstraints(Modifier.widthIn(max = 980.dp).fillMaxSize()) {
            // The adaptive grid's columns (min cell width, 14 dp gaps) for the content width.
            val minCell = if (regular) 128.dp else 104.dp
            val columns = maxOf(1, ((maxWidth - gutter * 2 + 14.dp) / (minCell + 14.dp)).toInt())
            val rows = remember(page.games, columns) { page.games.chunked(columns) }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = gutter, end = gutter, top = 4.dp, bottom = 40.dp),
            ) {
                item(key = "header", contentType = "header") { PageHeader(title = page.name) }
                item(key = "summary", contentType = "summary") { AuroraSummary(page, Modifier.padding(top = section)) }
                item(key = "filters", contentType = "filters") {
                    Column(Modifier.padding(top = section), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SegmentedChoice(PlatformFilter.entries, page.filter, { it.title }, actions.setFilter, Modifier.testTag("platform.filter"))
                        OrderToggle(page.orderLabel, actions.toggleOrder, riso = false)
                    }
                }
                if (page.games.isEmpty()) {
                    item(key = "empty", contentType = "empty") {
                        EmptyState(
                            title = if (page.isLoading) "Loading games" else emptyTitle(page.filter),
                            message = if (page.isLoading) "" else page.errorMessage ?: emptyMessage(page.filter),
                            icon = Icons.Outlined.SportsEsports,
                            modifier = Modifier.padding(top = section),
                        )
                    }
                } else {
                    item(key = "gamesHeader", contentType = "sectionHeader") {
                        SectionHeader(
                            title = if (page.filter == PlatformFilter.ALL) "Games" else page.filter.title,
                            meta = page.games.size.toString(),
                            modifier = Modifier.padding(top = section),
                        )
                    }
                    // One lazy item per grid row: 14 dp under the header, 20 dp between rows.
                    itemsIndexed(rows, key = { _, row -> "row/${row.first().id}" }, contentType = { _, _ -> "gridRow" }) { index, row ->
                        GridRow(row, columns, actions.open, Modifier.padding(top = if (index == 0) 14.dp else 20.dp))
                    }
                }
                page.staleMessage?.let { message ->
                    item(key = "stale", contentType = "stale") {
                        RoleText(message, theme.type.caption, color = theme.colors.inkSecondary, modifier = Modifier.padding(top = section))
                    }
                }
            }
        }
    }
}

@Composable
private fun AuroraSummary(page: PlatformPage, modifier: Modifier = Modifier) {
    GlassSurface(modifier, contentPadding = PaddingValues(18.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Figure(page.totalCountText, if (page.totalIsOne) "game" else "games")
            Figure(page.installedCount.toString(), "on this device")
            Box(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SupportTierBadge(page.tier)
                if (page.playedCount > 0) StatusChip("${page.playedCount} played", tone = Tone.OK)
            }
        }
    }
}

@Composable
private fun Figure(value: String, label: String) {
    val theme = RommioTheme.current
    Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        RoleText(value, theme.type.statValue, size = 32f, color = theme.colors.ink, maxLines = 1)
        RoleText(label, theme.type.caption, color = theme.colors.inkSecondary, maxLines = 1)
    }
}

@Composable
private fun OrderToggle(label: String, onToggle: () -> Unit, riso: Boolean) {
    val theme = RommioTheme.current
    Row(
        Modifier
            .heightIn(min = if (riso) 44.dp else 32.dp)
            .clickable(role = Role.Button, onClickLabel = "Change order", onClick = onToggle)
            .testTag("platform.order"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.SwapVert, contentDescription = null, tint = if (riso) theme.colors.ink else theme.colors.inkSecondary, modifier = Modifier.size(16.dp))
        RoleText(
            label,
            if (riso) theme.type.label else theme.type.bodyEmphasis,
            size = if (riso) 11f else 14f,
            color = if (riso) theme.colors.ink else theme.colors.inkSecondary,
        )
    }
}

/** A row of the adaptive grid (14 dp gaps; empty cells keep the columns). */
@Composable
private fun GridRow(row: List<PlatformGameItem>, columns: Int, open: (Int) -> Unit, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        row.forEach { Box(Modifier.weight(1f)) { GridTile(it, open) } }
        repeat(columns - row.size) { Box(Modifier.weight(1f)) }
    }
}

/** A cover with its title and when it was last played; a check when it's on this device. */
@Composable
private fun GridTile(game: PlatformGameItem, open: (Int) -> Unit) {
    val theme = RommioTheme.current
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = "Open ${game.title}") { open(game.id) }
            .semantics(mergeDescendants = true) {}
            .testTag("platform.game.${game.id}"),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box {
            GameCover(game.coverUrl, game.title, Modifier.fillMaxWidth())
            if (game.installed) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(17.dp)
                        .clip(CircleShape)
                        .background(OverArt.rim)
                        .semantics { contentDescription = "On this device" },
                ) {
                    Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = theme.colors.ok, modifier = Modifier.fillMaxSize())
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            RoleText(game.title, theme.type.bodyEmphasis, size = 14f, color = theme.colors.ink, maxLines = 2)
            RoleText(game.caption, theme.type.caption, size = 12f, color = theme.colors.inkSecondary, maxLines = 1)
        }
    }
}

// ---- Riso ------------------------------------------------------------------------------------

@Composable
private fun RisoPlatformLayout(page: PlatformPage, actions: PlatformActions) {
    val theme = RommioTheme.current
    val c = theme.colors
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.widthIn(max = 760.dp).fillMaxSize(),
            contentPadding = PaddingValues(start = theme.spacing.gutter, end = theme.spacing.gutter, top = 4.dp, bottom = 40.dp),
        ) {
            item(key = "opener", contentType = "opener") { Opener(page) }
            if (page.plates.isNotEmpty()) {
                item(key = "plates", contentType = "plates") { Plates(page, actions.open) }
            }
            item(key = "filters", contentType = "filters") {
                Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SegmentedChoice(PlatformFilter.entries, page.filter, { it.title }, actions.setFilter, Modifier.weight(1f).testTag("platform.filter"))
                    OrderToggle(page.orderLabel, actions.toggleOrder, riso = true)
                }
            }
            item(key = "key", contentType = "key") {
                Column {
                    RoleText(
                        "Bold: on this device. Not downloaded entries are set lighter.",
                        theme.type.caption,
                        size = 12.5f,
                        italic = true,
                        color = c.inkSecondary,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                    PrintRule(weight = RuleWeight.KEYLINE)
                }
            }
            when {
                page.games.isEmpty() -> item(key = "empty", contentType = "empty") {
                    EmptyState(
                        title = if (page.isLoading) "Loading games" else "Nothing here yet",
                        message = if (page.isLoading) "" else page.errorMessage ?: "Try another filter, or pull to refresh.",
                    )
                }
                page.order == PlatformOrder.ALPHABETICAL -> page.letters.forEach { group -> letterGroup(group, actions.open) }
                else -> itemsIndexed(page.games, key = { _, game -> "game/${game.id}" }, contentType = { _, _ -> "entry" }) { _, game ->
                    Column {
                        Entry(game, actions.open)
                        PrintRule(weight = RuleWeight.HAIRLINE)
                    }
                }
            }
            page.staleMessage?.let { message ->
                item(key = "stale", contentType = "stale") {
                    RoleText(message, theme.type.caption, color = c.inkSecondary, modifier = Modifier.padding(top = 16.dp))
                }
            }
        }
    }
}

/**
 * One letter of the A–Z index as lazy items: each entry is its own item (the letter sits beside
 * the first), hairlines between the entries and a hairline across the page after the letter.
 */
private fun LazyListScope.letterGroup(group: PlatformLetter, open: (Int) -> Unit) {
    itemsIndexed(group.games, key = { _, game -> "game/${game.id}" }, contentType = { _, _ -> "letterEntry" }) { index, game ->
        LetterEntry(if (index == 0) group.letter else null, first = index == 0, game, open)
    }
    item(key = "letterRule/${group.letter}", contentType = "letterRule") { PrintRule(weight = RuleWeight.HAIRLINE) }
}

@Composable
private fun LetterEntry(letter: String?, first: Boolean, game: PlatformGameItem, open: (Int) -> Unit) {
    val theme = RommioTheme.current
    Row {
        Box(Modifier.width(30.dp)) {
            if (letter != null) {
                RoleText(
                    letter,
                    theme.type.statValue,
                    size = 30f,
                    color = theme.colors.accent2,
                    modifier = Modifier.width(30.dp).padding(top = 8.dp).clearAndSetSemantics {},
                )
            }
        }
        Column(Modifier.weight(1f)) {
            if (!first) PrintRule(weight = RuleWeight.HAIRLINE)
            Entry(game, open)
        }
    }
}

@Composable
private fun Opener(page: PlatformPage) {
    val theme = RommioTheme.current
    val c = theme.colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column {
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                RoleText("Platform", theme.type.label, size = 10.5f, color = c.ink, modifier = Modifier.weight(1f))
                RoleText(page.tierLabel, theme.type.label, size = 10.5f, color = c.inkSecondary)
            }
            PrintRule(weight = RuleWeight.HEAVY)
        }
        DisplayText(page.name, size = 56f, maxLines = 2, modifier = Modifier.padding(top = 4.dp))
        RoleText(
            "${page.totalCountText} games on your server, ${page.installedCount} on this device.",
            theme.type.body,
            size = 16.5f,
            color = c.ink,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SupportTierBadge(page.tier)
            if (page.playedCount > 0) StatusChip("${page.playedCount} played", tone = Tone.OK)
        }
    }
}

/** The most played covers, printed (or the first on this device). */
@Composable
private fun Plates(page: PlatformPage, open: (Int) -> Unit) {
    val theme = RommioTheme.current
    val c = theme.colors
    val picks = page.plates
    Column(Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            picks.forEach { game ->
                Column(
                    Modifier.weight(1f).clickable(role = Role.Button, onClickLabel = "Open ${game.title}") { open(game.id) },
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    GameCover(game.coverUrl, game.title, Modifier.fillMaxWidth().aspectRatio(3f / 4f).padding(bottom = 4.dp), aspect = null)
                    RoleText(game.title, theme.type.bodyEmphasis, size = 12.5f, color = c.ink, maxLines = 2)
                    RoleText(game.plateCaption, theme.type.label, size = 9.5f, color = c.inkSecondary)
                }
            }
            repeat(4 - picks.size) { Box(Modifier.weight(1f)) }
        }
        Column {
            PrintRule(weight = RuleWeight.FINE)
            RoleText(
                if (page.platesArePlayed) "Most played on this platform" else "On this device",
                theme.type.caption,
                size = 12.5f,
                italic = true,
                color = c.inkSecondary,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** An A–Z entry: title (bold when downloaded), leaders, playtime / "Ready", a "not downloaded" glyph box. */
@Composable
private fun Entry(game: PlatformGameItem, open: (Int) -> Unit) {
    val theme = RommioTheme.current
    val c = theme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(role = Role.Button, onClickLabel = "Open ${game.title}") { open(game.id) }
            .testTag("platform.game.${game.id}"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        io.github.bandoracer.rommio.ui.component.LeaderLayout(
            modifier = Modifier.weight(1f),
            start = {
                RoleText(
                    game.title,
                    if (game.installed) theme.type.bodyEmphasis else theme.type.body,
                    size = 16.5f,
                    color = if (game.installed) c.ink else c.inkSecondary,
                    maxLines = 1,
                )
            },
            end = {
                RoleText(game.figure, theme.type.numeral, size = 11.5f, color = c.ink, maxLines = 1)
            },
        )
        Box(Modifier.width(34.dp), contentAlignment = Alignment.CenterEnd) {
            if (!game.installed) {
                Box(
                    Modifier.size(26.dp).background(c.ink).semantics { contentDescription = "Not downloaded" },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.FileDownload, contentDescription = null, tint = c.ground, modifier = Modifier.size(15.dp))
                }
            }
        }
    }
}
