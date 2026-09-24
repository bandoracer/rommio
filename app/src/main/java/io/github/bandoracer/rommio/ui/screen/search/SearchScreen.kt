package io.github.bandoracer.rommio.ui.screen.search

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.bandoracer.rommio.AppContainer
import io.github.bandoracer.rommio.domain.player.EmbeddedSupportTier
import io.github.bandoracer.rommio.model.PlatformDto
import io.github.bandoracer.rommio.model.RommCollectionDto
import io.github.bandoracer.rommio.ui.component.Chevron
import io.github.bandoracer.rommio.ui.component.CoverThumb
import io.github.bandoracer.rommio.ui.component.DotLeader
import io.github.bandoracer.rommio.ui.component.EmptyState
import io.github.bandoracer.rommio.ui.component.GameCover
import io.github.bandoracer.rommio.ui.component.GameTile
import io.github.bandoracer.rommio.ui.component.GlassSurface
import io.github.bandoracer.rommio.ui.component.IconCircleButton
import io.github.bandoracer.rommio.ui.component.ListDivider
import io.github.bandoracer.rommio.ui.component.ListGroup
import io.github.bandoracer.rommio.ui.component.ListRow
import io.github.bandoracer.rommio.ui.component.PageArtSubject
import io.github.bandoracer.rommio.ui.component.PageFieldStyle
import io.github.bandoracer.rommio.ui.component.PageHeader
import io.github.bandoracer.rommio.ui.component.PlayFormat
import io.github.bandoracer.rommio.ui.component.PrimaryButton
import io.github.bandoracer.rommio.ui.component.PrintRule
import io.github.bandoracer.rommio.ui.component.RoleText
import io.github.bandoracer.rommio.ui.component.RuleWeight
import io.github.bandoracer.rommio.ui.component.SectionHeader
import io.github.bandoracer.rommio.ui.component.ThemedTextField
import io.github.bandoracer.rommio.ui.component.sized
import io.github.bandoracer.rommio.ui.screen.collectAsStateWithLifecycleCompat
import io.github.bandoracer.rommio.ui.theme.RoleStyle
import io.github.bandoracer.rommio.ui.theme.RommioTheme
import io.github.bandoracer.rommio.util.resolveRemoteAssetUrl

/** A platform or collection that matches the query (related results). */
data class SearchRelated(val platform: PlatformDto? = null, val collection: RommCollectionDto? = null) {
    val title: String get() = platform?.name ?: collection?.name.orEmpty()
    val count: Int get() = platform?.romCount ?: collection?.romCount ?: 0
    val kindTitle: String get() = if (platform != null) "Platform" else "Collection"
    /** `p<id>` or `c<id>`, as in the `search.related.*` identifiers. */
    val id: String get() = platform?.let { "p${it.id}" } ?: "c${collection?.id}"
}

/** What Search can do. */
class SearchActions(
    val openGame: (romId: Int) -> Unit,
    val openRelated: (SearchRelated) -> Unit,
    val resume: (SearchGameResult) -> Unit,
    val cover: (SearchGameResult) -> String?,
)

internal val SearchUiState.related: List<SearchRelated>
    get() = platforms.map { SearchRelated(platform = it) } + collections.map { SearchRelated(collection = it) }

/** A result is in progress when it's among the games played recently. */
internal fun SearchUiState.inProgress(hit: SearchGameResult): Boolean = recentlyPlayed.any { it.rom.id == hit.rom.id }

/** "SNES · on this device · played 2 hours ago". */
internal fun SearchGameResult.detail(): String = listOfNotNull(
    rom.platformSlug.uppercase(),
    rom.metadata.releaseYear?.toString(),
    "on this device".takeIf { installed },
    lastPlayedAtEpochMs?.let { "played ${PlayFormat.relative(it)}" },
).joinToString(" · ")

/**
 * Search (docs/theme-layouts.md "Search — two layouts"): the cached library, best match first and
 * recently played first within a match; platforms and collections that match follow. Aurora leads
 * with a lit best match; Riso sets an index lookup with "See also" entries.
 */
@Composable
fun SearchScreen(
    container: AppContainer,
    imageBaseUrl: String?,
    onOpenGame: (romId: Int) -> Unit,
    onPlatformSelected: (PlatformDto) -> Unit,
    onCollectionSelected: (RommCollectionDto) -> Unit,
    /** Resume on a result starts the game (asking first when the launch planner would). */
    launchFlow: io.github.bandoracer.rommio.ui.screen.LaunchFlow? = null,
) {
    val viewModel: SearchViewModel = viewModel(
        factory = viewModelFactory {
            initializer { SearchViewModel(container.repository, container.playActivity) }
        },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycleCompat()
    LaunchedEffect(Unit) { viewModel.refreshActivity() }
    val theme = RommioTheme.current
    val focus = LocalFocusManager.current
    val actions = SearchActions(
        openGame = onOpenGame,
        openRelated = { related ->
            related.platform?.let(onPlatformSelected)
            related.collection?.let(onCollectionSelected)
        },
        resume = { hit ->
            val fileId = hit.installedFileId
            if (fileId != null && hit.supportTier != EmbeddedSupportTier.UNSUPPORTED && launchFlow != null) {
                launchFlow.play(
                    io.github.bandoracer.rommio.ui.screen.LaunchTarget(hit.rom.id, hit.rom.displayName, fileId, resolveRemoteAssetUrl(imageBaseUrl, hit.rom.urlCover), platformSlug = hit.rom.platformSlug),
                )
            } else {
                onOpenGame(hit.rom.id)
            }
        },
        cover = { resolveRemoteAssetUrl(imageBaseUrl, it.rom.urlCover) },
    )
    val searching = state.hasQuery
    PageArtSubject(
        if (searching && !theme.isRiso) state.games.firstOrNull()?.let(actions.cover) else null,
        if (searching && state.games.isNotEmpty()) PageFieldStyle.PAGE else PageFieldStyle.QUIET,
    )
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = if (theme.isRiso) 760.dp else 900.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .testTag("shell.searchList")
                .padding(start = theme.spacing.gutter, end = theme.spacing.gutter, top = 8.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(if (theme.isRiso) 0.dp else theme.spacing.section),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = if (theme.isRiso) 16.dp else 0.dp)) {
                PageHeader(title = "Search")
                ThemedTextField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    modifier = Modifier.fillMaxWidth().testTag("search.field"),
                    placeholder = "Games and platforms",
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    trailingIcon = {
                        if (state.query.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setQuery("") }) {
                                Icon(Icons.Outlined.Close, contentDescription = "Clear search")
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                )
            }
            if (theme.isRiso) RisoSearch(state, actions) else AuroraSearch(state, actions)
        }
    }
}

/** The title with the query marked: a wash of the page's light (Aurora) or a yellow highlighter (Riso). */
@Composable
internal fun Highlighted(text: String, query: String, role: RoleStyle, size: Float? = null, maxLines: Int = 2, color: Color) {
    val theme = RommioTheme.current
    val needle = query.trim()
    val annotated: AnnotatedString = buildAnnotatedString {
        val start = if (needle.isEmpty()) -1 else text.indexOf(needle, ignoreCase = true)
        if (start < 0) {
            append(text)
        } else {
            append(text.substring(0, start))
            withStyle(
                if (theme.isRiso) SpanStyle(background = theme.colors.accent3, color = theme.colors.onAccent) else SpanStyle(background = theme.mark1.copy(alpha = 0.26f)),
            ) { append(text.substring(start, start + needle.length)) }
            append(text.substring(start + needle.length))
        }
    }
    val style = if (size != null) role.style.sized(size) else role.style
    Text(annotated, style = style, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

// ---- Aurora ----------------------------------------------------------------------------------

@Composable
private fun AuroraSearch(state: SearchUiState, actions: SearchActions) {
    val theme = RommioTheme.current
    val c = theme.colors
    val query = state.query.trim()
    if (!state.hasQuery) {
        if (state.recentlyPlayed.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeader(title = "Continue playing")
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    state.recentlyPlayed.forEach { hit ->
                        GameTile(hit.rom.displayName, actions.cover(hit), onClick = { actions.openGame(hit.rom.id) },
                            subtitle = listOfNotNull(hit.rom.platformName, hit.rom.metadata.releaseYear?.toString()).joinToString(" · "), width = 116.dp)
                    }
                }
            }
        }
        EmptyState(
            title = "Search your library",
            message = if (state.catalogueSize == 0) "Games appear here once your library has loaded." else "Find any of ${PlayFormat.games(state.catalogueSize)} by title or platform.",
            icon = Icons.Outlined.Search,
        )
        return
    }
    val best = state.games.firstOrNull()
    if (best != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = c.inkSecondary, modifier = Modifier.size(14.dp))
            RoleText("Ranked by recent play, then title", theme.type.caption, size = 12.5f, color = c.inkSecondary)
        }
        GlassSurface(
            Modifier
                .fillMaxWidth()
                .border(1.dp, theme.mark1.copy(alpha = 0.7f), theme.radius.cardShape)
                .testTag("search.bestMatch"),
            onClick = { actions.openGame(best.rom.id) },
            onClickLabel = "Open ${best.rom.displayName}",
            contentPadding = PaddingValues(14.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                GameCover(actions.cover(best), best.rom.displayName, Modifier.size(104.dp, 138.dp), aspect = null)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    RoleText("Best match", theme.type.section, color = c.ink)
                    best.lastPlayedAtEpochMs?.let { RoleText("Played ${PlayFormat.relative(it)}", theme.type.section, size = 10.5f, color = c.inkSecondary) }
                    Highlighted(best.rom.displayName, query, theme.type.headline, maxLines = 3, color = c.ink)
                    RoleText(
                        io.github.bandoracer.rommio.ui.component.Fmt.join(best.rom.platformName, best.rom.metadata.releaseYear?.toString()),
                        theme.type.secondary,
                        color = c.inkSecondary,
                    )
                    if (state.inProgress(best)) {
                        PrimaryButton(
                            "Resume",
                            onClick = { actions.resume(best) },
                            icon = Icons.Outlined.PlayArrow,
                            large = false,
                            modifier = Modifier.testTag("search.resume.${best.rom.id}"),
                        )
                    }
                }
            }
        }
    }
    if (state.games.size > 1) {
        ListGroup(title = "Games", meta = (state.games.size - 1).toString()) {
            state.games.drop(1).forEachIndexed { index, hit ->
                if (index > 0) ListDivider(inset = 68.dp)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = theme.spacing.rowHeight)
                        .clickable(role = Role.Button, onClickLabel = "Open ${hit.rom.displayName}") { actions.openGame(hit.rom.id) }
                        .semantics(mergeDescendants = true) {}
                        .padding(horizontal = 16.dp, vertical = 9.dp)
                        .testTag("search.result.${hit.rom.id}"),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CoverThumb(actions.cover(hit), hit.rom.displayName)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Highlighted(hit.rom.displayName, query, theme.type.bodyEmphasis, size = 16f, color = c.ink)
                        RoleText(hit.detail(), theme.type.secondary, size = 13f, color = c.inkSecondary, maxLines = 1)
                    }
                    Chevron()
                }
            }
        }
    }
    val related = state.related
    if (related.isNotEmpty()) {
        ListGroup(title = "Platforms and collections", meta = related.size.toString()) {
            related.forEachIndexed { index, item ->
                if (index > 0) ListDivider()
                ListRow(
                    title = item.title,
                    subtitle = "${item.kindTitle} · ${PlayFormat.games(item.count)}",
                    showChevron = true,
                    onClick = { actions.openRelated(item) },
                    modifier = Modifier.testTag("search.related.${item.id}"),
                )
            }
        }
    }
    if (state.games.isEmpty() && related.isEmpty()) {
        EmptyState(
            title = "No results for “$query”",
            message = "Search looks through the games Rommio has loaded from your server. Browse a platform in the Library to load more.",
            icon = Icons.Outlined.Search,
        )
    }
}

// ---- Riso ------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RisoSearch(state: SearchUiState, actions: SearchActions) {
    val theme = RommioTheme.current
    val c = theme.colors
    val query = state.query.trim()
    if (!state.hasQuery) {
        RoleText(
            if (state.catalogueSize == 0) "Games appear here once your library has loaded." else "Look up any of ${PlayFormat.games(state.catalogueSize)} by title or platform.",
            theme.type.body,
            size = 16f,
            italic = true,
            color = c.inkSecondary,
            modifier = Modifier.padding(bottom = 14.dp),
        )
        if (state.recentlyPlayed.isNotEmpty()) {
            SectionHeader(title = "Continue playing", meta = state.recentlyPlayed.size.toString())
            state.recentlyPlayed.forEach { RisoEntry(it, state, actions, Modifier) }
        }
        if (state.allPlatforms.isNotEmpty()) {
            SectionHeader(title = "Platforms", meta = state.allPlatforms.size.toString(), modifier = Modifier.padding(top = 26.dp))
            state.allPlatforms.forEach { platform ->
                RelatedLeaderRow(null, platform.name, java.text.NumberFormat.getIntegerInstance().format(platform.romCount)) {
                    actions.openRelated(SearchRelated(platform = platform))
                }
            }
        }
        return
    }
    val count = state.games.size
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("$count ${if (count == 1) "entry" else "entries"}") }
            append(" for “$query”. ")
            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append("Ranked by what you've played, then by title.") }
        },
        style = theme.type.body.style.sized(16f),
        color = c.ink,
        modifier = Modifier.padding(bottom = 12.dp),
    )
    val related = state.related
    if (related.isNotEmpty()) {
        Row(Modifier.padding(bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RoleText("See also", theme.type.label, size = 9.5f, color = c.inkSecondary, modifier = Modifier.padding(top = 10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                related.take(6).forEach { item ->
                    Box(
                        Modifier
                            .heightIn(min = 36.dp)
                            .border(1.5.dp, c.ink)
                            .clickable(role = Role.Button, onClickLabel = "Open ${item.title}") { actions.openRelated(item) }
                            .padding(horizontal = 10.dp)
                            .testTag("search.related.${item.id}"),
                        contentAlignment = Alignment.Center,
                    ) {
                        RoleText(item.title, theme.type.body, size = 14.5f, italic = true, color = c.ink, maxLines = 1)
                    }
                }
            }
        }
    }
    if (state.games.isNotEmpty()) {
        SectionHeader(title = "Games", meta = count.toString())
        state.games.forEachIndexed { index, hit ->
            RisoEntry(hit, state, actions, Modifier.testTag(if (index == 0) "search.bestMatch" else "search.result.${hit.rom.id}"))
        }
    }
    if (related.isNotEmpty()) {
        SectionHeader(title = "Platforms and collections", modifier = Modifier.padding(top = 26.dp))
        related.forEach { item ->
            RelatedLeaderRow(item.kindTitle, item.title, PlayFormat.games(item.count)) { actions.openRelated(item) }
        }
    }
    if (state.games.isEmpty() && related.isEmpty()) {
        EmptyState(
            title = "No results for “$query”",
            message = "Search looks through the games Rommio has loaded from your server. Browse a platform in the Library to load more.",
        )
    }
}

/** An index entry: a small print, the highlighted title, detail, and a play disc when in progress. */
@Composable
private fun RisoEntry(hit: SearchGameResult, state: SearchUiState, actions: SearchActions, modifier: Modifier) {
    val theme = RommioTheme.current
    val c = theme.colors
    Column(modifier) {
        Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .weight(1f)
                    .clickable(role = Role.Button, onClickLabel = "Open ${hit.rom.displayName}") { actions.openGame(hit.rom.id) }
                    .semantics(mergeDescendants = true) {},
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GameCover(actions.cover(hit), hit.rom.displayName, Modifier.size(44.dp, 58.dp), aspect = null)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Highlighted(hit.rom.displayName, state.query, theme.type.headline, size = 24f, color = c.ink)
                    RoleText(hit.detail(), theme.type.label, size = 9.5f, color = c.inkSecondary, maxLines = 1)
                }
            }
            if (state.inProgress(hit)) {
                IconCircleButton(
                    Icons.Outlined.PlayArrow,
                    contentDescription = "Resume ${hit.rom.displayName}",
                    onClick = { actions.resume(hit) },
                    prominent = true,
                    modifier = Modifier.testTag("search.resume.${hit.rom.id}"),
                )
            } else {
                Chevron()
            }
        }
        PrintRule(weight = RuleWeight.HAIRLINE)
    }
}

@Composable
private fun RelatedLeaderRow(kind: String?, title: String, value: String, onClick: () -> Unit) {
    val theme = RommioTheme.current
    val c = theme.colors
    Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Open $title", onClick = onClick)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp).semantics(mergeDescendants = true) { contentDescription = listOfNotNull(kind, title, value).joinToString(", ") },
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            kind?.let { RoleText(it, theme.type.label, size = 9.5f, color = c.inkSecondary, modifier = Modifier.width(84.dp).alignByBaseline()) }
            io.github.bandoracer.rommio.ui.component.LeaderLayout(
                modifier = Modifier.weight(1f).alignByBaseline(),
                start = { RoleText(title, theme.type.bodyEmphasis, size = 16f, color = c.ink, maxLines = 1) },
                end = { RoleText(value, theme.type.numeral, size = 11.5f, color = c.ink) },
            )
        }
        PrintRule(weight = RuleWeight.HAIRLINE)
    }
}
