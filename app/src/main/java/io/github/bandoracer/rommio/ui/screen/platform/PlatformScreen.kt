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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.bandoracer.rommio.AppContainer
import io.github.bandoracer.rommio.model.RomDto
import io.github.bandoracer.rommio.ui.component.DisplayText
import io.github.bandoracer.rommio.ui.component.DotLeader
import io.github.bandoracer.rommio.ui.component.EmptyState
import io.github.bandoracer.rommio.ui.component.GameCover
import io.github.bandoracer.rommio.ui.component.GlassSurface
import io.github.bandoracer.rommio.ui.component.PageArtSubject
import io.github.bandoracer.rommio.ui.component.PageFieldStyle
import io.github.bandoracer.rommio.ui.component.PageHeader
import io.github.bandoracer.rommio.ui.component.PlayFormat
import io.github.bandoracer.rommio.ui.component.PrintRule
import io.github.bandoracer.rommio.ui.component.RoleText
import io.github.bandoracer.rommio.ui.component.RuleWeight
import io.github.bandoracer.rommio.ui.component.SectionHeader
import io.github.bandoracer.rommio.ui.component.SegmentedChoice
import io.github.bandoracer.rommio.ui.component.StatusChip
import io.github.bandoracer.rommio.ui.component.SupportTierBadge
import io.github.bandoracer.rommio.ui.component.Tone
import io.github.bandoracer.rommio.ui.component.formatPlayTime
import io.github.bandoracer.rommio.ui.component.isRegularWidth
import io.github.bandoracer.rommio.ui.component.supportTierLabel
import io.github.bandoracer.rommio.ui.screen.collectAsStateWithLifecycleCompat
import io.github.bandoracer.rommio.ui.theme.RommioTheme
import io.github.bandoracer.rommio.util.resolveRemoteAssetUrl
import java.net.URLDecoder

/**
 * Platform (docs/theme-layouts.md "Platform — two layouts"): its games with what this device
 * knows about each. Aurora: a summary card and a cover grid lit by the most recently played game.
 * Riso: a platform opener with the most played covers, then an A–Z index. Reached from Library;
 * the detail column when split.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlatformScreen(
    container: AppContainer,
    platformId: Int,
    platformName: String,
    imageBaseUrl: String?,
    onRomSelected: (RomDto) -> Unit,
) {
    val viewModel: PlatformViewModel = viewModel(
        key = "platform-$platformId",
        factory = viewModelFactory {
            initializer { PlatformViewModel(container.repository, platformId, container.playActivity) }
        },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycleCompat()
    var filterKey by rememberSaveable { mutableStateOf(PlatformFilter.ALL.name) }
    var orderKey by rememberSaveable { mutableStateOf(PlatformOrder.ALPHABETICAL.name) }
    val content = PlatformContent(
        state = state,
        name = state.platform?.name ?: runCatching { URLDecoder.decode(platformName, Charsets.UTF_8.name()) }.getOrDefault(platformName),
        filter = PlatformFilter.valueOf(filterKey),
        order = PlatformOrder.valueOf(orderKey),
        onFilter = { filterKey = it.name },
        onToggleOrder = { orderKey = if (orderKey == PlatformOrder.ALPHABETICAL.name) PlatformOrder.RECENTLY_PLAYED.name else PlatformOrder.ALPHABETICAL.name },
        open = { onRomSelected(it.rom) },
        cover = { resolveRemoteAssetUrl(imageBaseUrl, it.rom.urlCover) },
    )
    io.github.bandoracer.rommio.ui.component.RefreshBox(
        refreshing = state.isRefreshing,
        onRefresh = viewModel::refresh,
        modifier = Modifier.fillMaxSize().testTag("screen.platform"),
    ) {
        if (RommioTheme.isRiso) RisoPlatformLayout(content) else AuroraPlatformLayout(content)
    }
}

/** The page's content and the choices both layouts share. */
class PlatformContent(
    val state: PlatformUiState,
    val name: String,
    val filter: PlatformFilter,
    val order: PlatformOrder,
    val onFilter: (PlatformFilter) -> Unit,
    val onToggleOrder: () -> Unit,
    val open: (PlatformGame) -> Unit,
    val cover: (PlatformGame) -> String?,
)

private fun emptyTitle(filter: PlatformFilter) = if (filter == PlatformFilter.ALL) "No games yet" else "Nothing here yet"

private fun emptyMessage(filter: PlatformFilter) = when (filter) {
    PlatformFilter.ALL -> "This platform has no games on your server yet."
    PlatformFilter.ON_DEVICE -> "Download a game from its page to play it offline."
    PlatformFilter.PLAYED -> "Games you play on this device show up here."
}

// ---- Aurora ----------------------------------------------------------------------------------

@Composable
private fun AuroraPlatformLayout(content: PlatformContent) {
    val theme = RommioTheme.current
    val state = content.state
    val games = state.filtered(content.filter, content.order)
    val lead = state.filtered(PlatformFilter.ALL, PlatformOrder.RECENTLY_PLAYED).firstOrNull()
    PageArtSubject(lead?.let(content.cover), PageFieldStyle.PAGE)
    val regular = isRegularWidth()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = 980.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = theme.spacing.gutter)
                .padding(top = 4.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(theme.spacing.section),
        ) {
            PageHeader(title = content.name)
            GlassSurface(contentPadding = PaddingValues(18.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Figure(java.text.NumberFormat.getIntegerInstance().format(state.totalCount), if (state.totalCount == 1) "game" else "games")
                    Figure(state.installedCount.toString(), "on this device")
                    Box(Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SupportTierBadge(state.tier)
                        if (state.playedCount > 0) StatusChip("${state.playedCount} played", tone = Tone.OK)
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SegmentedChoice(PlatformFilter.entries, content.filter, { it.title }, content.onFilter, Modifier.testTag("platform.filter"))
                OrderToggle(content, riso = false)
            }
            if (games.isEmpty()) {
                EmptyState(
                    title = if (state.isLoading) "Loading games" else emptyTitle(content.filter),
                    message = if (state.isLoading) "" else state.errorMessage ?: emptyMessage(content.filter),
                    icon = Icons.Outlined.SportsEsports,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SectionHeader(title = if (content.filter == PlatformFilter.ALL) "Games" else content.filter.title, meta = games.size.toString())
                    AdaptiveGrid(games, minCell = if (regular) 128.dp else 104.dp) { game -> GridTile(game, content) }
                }
            }
            state.staleMessage?.let { RoleText(it, theme.type.caption, color = theme.colors.inkSecondary) }
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
private fun OrderToggle(content: PlatformContent, riso: Boolean) {
    val theme = RommioTheme.current
    val label = when (content.order) {
        PlatformOrder.ALPHABETICAL -> "A–Z"
        PlatformOrder.RECENTLY_PLAYED -> "Recently played"
    }
    Row(
        Modifier
            .heightIn(min = if (riso) 44.dp else 32.dp)
            .clickable(role = Role.Button, onClickLabel = "Change order", onClick = content.onToggleOrder)
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

/** An adaptive grid (min cell width, 14 dp gaps, 20 dp rows) laid out in a column. */
@Composable
private fun <T> AdaptiveGrid(items: List<T>, minCell: androidx.compose.ui.unit.Dp, cell: @Composable (T) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = maxOf(1, ((maxWidth + 14.dp) / (minCell + 14.dp)).toInt())
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            items.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    row.forEach { Box(Modifier.weight(1f)) { cell(it) } }
                    repeat(columns - row.size) { Box(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** A cover with its title and when it was last played; a check when it's on this device. */
@Composable
private fun GridTile(game: PlatformGame, content: PlatformContent) {
    val theme = RommioTheme.current
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = "Open ${game.rom.displayName}") { content.open(game) }
            .semantics(mergeDescendants = true) {}
            .testTag("platform.game.${game.rom.id}"),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box {
            GameCover(content.cover(game), game.rom.displayName, Modifier.fillMaxWidth())
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
            RoleText(game.rom.displayName, theme.type.bodyEmphasis, size = 14f, color = theme.colors.ink, maxLines = 2)
            RoleText(
                game.lastPlayedAtEpochMs?.let { PlayFormat.relative(it) } ?: if (game.installed) "On this device" else "On your server",
                theme.type.caption,
                size = 12f,
                color = theme.colors.inkSecondary,
                maxLines = 1,
            )
        }
    }
}

// ---- Riso ------------------------------------------------------------------------------------

@Composable
private fun RisoPlatformLayout(content: PlatformContent) {
    val theme = RommioTheme.current
    val c = theme.colors
    val state = content.state
    val games = state.filtered(content.filter, content.order)
    PageArtSubject(null, PageFieldStyle.QUIET)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = 760.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = theme.spacing.gutter)
                .padding(top = 4.dp, bottom = 40.dp),
        ) {
            Opener(content)
            Plates(content)
            Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SegmentedChoice(PlatformFilter.entries, content.filter, { it.title }, content.onFilter, Modifier.weight(1f).testTag("platform.filter"))
                OrderToggle(content, riso = true)
            }
            RoleText(
                "Bold: on this device. Not downloaded entries are set lighter.",
                theme.type.caption,
                size = 12.5f,
                italic = true,
                color = c.inkSecondary,
                modifier = Modifier.padding(vertical = 6.dp),
            )
            PrintRule(weight = RuleWeight.KEYLINE)
            when {
                games.isEmpty() -> EmptyState(
                    title = if (state.isLoading) "Loading games" else "Nothing here yet",
                    message = if (state.isLoading) "" else state.errorMessage ?: "Try another filter, or pull to refresh.",
                )
                content.order == PlatformOrder.ALPHABETICAL -> letterGroups(games).forEach { (letter, group) ->
                    Column {
                        Row {
                            RoleText(
                                letter,
                                theme.type.statValue,
                                size = 30f,
                                color = c.accent2,
                                modifier = Modifier.width(30.dp).padding(top = 8.dp).clearAndSetSemantics {},
                            )
                            Column(Modifier.weight(1f)) {
                                group.forEachIndexed { index, game ->
                                    if (index > 0) PrintRule(weight = RuleWeight.HAIRLINE)
                                    Entry(game, content)
                                }
                            }
                        }
                        PrintRule(weight = RuleWeight.HAIRLINE)
                    }
                }
                else -> games.forEach { game ->
                    Entry(game, content)
                    PrintRule(weight = RuleWeight.HAIRLINE)
                }
            }
            state.staleMessage?.let { RoleText(it, theme.type.caption, color = c.inkSecondary, modifier = Modifier.padding(top = 16.dp)) }
        }
    }
}

@Composable
private fun Opener(content: PlatformContent) {
    val theme = RommioTheme.current
    val c = theme.colors
    val state = content.state
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column {
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                RoleText("Platform", theme.type.label, size = 10.5f, color = c.ink, modifier = Modifier.weight(1f))
                RoleText(supportTierLabel(state.tier), theme.type.label, size = 10.5f, color = c.inkSecondary)
            }
            PrintRule(weight = RuleWeight.HEAVY)
        }
        DisplayText(content.name, size = 56f, maxLines = 2, modifier = Modifier.padding(top = 4.dp))
        RoleText(
            "${java.text.NumberFormat.getIntegerInstance().format(state.totalCount)} games on your server, ${state.installedCount} on this device.",
            theme.type.body,
            size = 16.5f,
            color = c.ink,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SupportTierBadge(state.tier)
            if (state.playedCount > 0) StatusChip("${state.playedCount} played", tone = Tone.OK)
        }
    }
}

/** The most played covers, printed (or the first on this device). */
@Composable
private fun Plates(content: PlatformContent) {
    val theme = RommioTheme.current
    val c = theme.colors
    val played = content.state.games.filter { it.playedMs > 0 }.sortedByDescending { it.playedMs }
    val picks = (played.ifEmpty { content.state.games.filter { it.installed } }).take(4)
    if (picks.isEmpty()) return
    Column(Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            picks.forEach { game ->
                Column(
                    Modifier.weight(1f).clickable(role = Role.Button, onClickLabel = "Open ${game.rom.displayName}") { content.open(game) },
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    GameCover(content.cover(game), game.rom.displayName, Modifier.fillMaxWidth().aspectRatio(3f / 4f).padding(bottom = 4.dp), aspect = null)
                    RoleText(game.rom.displayName, theme.type.bodyEmphasis, size = 12.5f, color = c.ink, maxLines = 2)
                    RoleText(if (game.playedMs > 0) formatPlayTime(game.playedMs) else "On this device", theme.type.label, size = 9.5f, color = c.inkSecondary)
                }
            }
            repeat(4 - picks.size) { Box(Modifier.weight(1f)) }
        }
        Column {
            PrintRule(weight = RuleWeight.FINE)
            RoleText(
                if (played.isEmpty()) "On this device" else "Most played on this platform",
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
private fun Entry(game: PlatformGame, content: PlatformContent) {
    val theme = RommioTheme.current
    val c = theme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(role = Role.Button, onClickLabel = "Open ${game.rom.displayName}") { content.open(game) }
            .testTag("platform.game.${game.rom.id}"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        io.github.bandoracer.rommio.ui.component.LeaderLayout(
            modifier = Modifier.weight(1f),
            start = {
                RoleText(
                    game.rom.displayName,
                    if (game.installed) theme.type.bodyEmphasis else theme.type.body,
                    size = 16.5f,
                    color = if (game.installed) c.ink else c.inkSecondary,
                    maxLines = 1,
                )
            },
            end = {
                RoleText(
                    if (game.playedMs > 0) formatPlayTime(game.playedMs) else if (game.installed) "Ready" else " ",
                    theme.type.numeral,
                    size = 11.5f,
                    color = c.ink,
                    maxLines = 1,
                )
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

private fun letterGroups(games: List<PlatformGame>): List<Pair<String, List<PlatformGame>>> {
    val order = mutableListOf<String>()
    val groups = mutableMapOf<String, MutableList<PlatformGame>>()
    for (game in games) {
        val first = game.rom.displayName.firstOrNull()?.uppercaseChar()
        val letter = if (first != null && first.isLetter()) first.toString() else "#"
        if (letter !in groups) order += letter
        groups.getOrPut(letter) { mutableListOf() } += game
    }
    return order.map { it to groups.getValue(it) }
}
