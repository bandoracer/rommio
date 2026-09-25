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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.remember
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.bandoracer.rommio.AppContainer
import io.github.bandoracer.rommio.model.PlatformDto
import io.github.bandoracer.rommio.model.RommCollectionDto
import io.github.bandoracer.rommio.ui.component.Chevron
import io.github.bandoracer.rommio.ui.component.CoverThumb
import io.github.bandoracer.rommio.ui.component.EmptyState
import io.github.bandoracer.rommio.ui.component.GameCover
import io.github.bandoracer.rommio.ui.component.GameTile
import io.github.bandoracer.rommio.ui.component.GlassSurface
import io.github.bandoracer.rommio.ui.component.IconCircleButton
import io.github.bandoracer.rommio.ui.component.LazyCardGroups
import io.github.bandoracer.rommio.ui.component.LitPage
import io.github.bandoracer.rommio.ui.component.ListDivider
import io.github.bandoracer.rommio.ui.component.ListGroup
import io.github.bandoracer.rommio.ui.component.ListRow
import io.github.bandoracer.rommio.ui.component.PageFieldStyle
import io.github.bandoracer.rommio.ui.component.PageHeader
import io.github.bandoracer.rommio.ui.component.PlayFormat
import io.github.bandoracer.rommio.ui.component.PrimaryButton
import io.github.bandoracer.rommio.ui.component.PrintRule
import io.github.bandoracer.rommio.ui.component.RoleText
import io.github.bandoracer.rommio.ui.component.RuleWeight
import io.github.bandoracer.rommio.ui.component.SectionHeader
import io.github.bandoracer.rommio.ui.component.ThemedTextField
import io.github.bandoracer.rommio.ui.component.drawCardGroups
import io.github.bandoracer.rommio.ui.component.lazyListGroup
import io.github.bandoracer.rommio.ui.component.rememberLazyCardGroups
import io.github.bandoracer.rommio.ui.component.sized
import io.github.bandoracer.rommio.ui.theme.RoleStyle
import io.github.bandoracer.rommio.ui.theme.RommioTheme

/** What Search can do. */
class SearchActions(
    val openGame: (romId: Int) -> Unit,
    val openRelated: (SearchRelated) -> Unit,
    val resume: (SearchHit) -> Unit,
)

/**
 * Search (docs/theme-layouts.md "Search — two layouts"): the cached library, best match first and
 * recently played first within a match; platforms and collections that match follow. Aurora leads
 * with a lit best match; Riso sets an index lookup with "See also" entries. Results are built by
 * [SearchViewModel] off the main thread ([SearchPage]) and listed lazily.
 */
@Composable
fun SearchScreen(
    container: AppContainer,
    @Suppress("UNUSED_PARAMETER") imageBaseUrl: String?,
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
    val page by viewModel.page.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refreshActivity() }
    val actions = remember(viewModel, onOpenGame, onPlatformSelected, onCollectionSelected, launchFlow) {
        SearchActions(
            openGame = onOpenGame,
            openRelated = { related ->
                related.platform?.let(onPlatformSelected)
                related.collection?.let(onCollectionSelected)
            },
            resume = { hit ->
                val fileId = hit.playableFileId
                if (fileId != null && launchFlow != null) {
                    launchFlow.play(io.github.bandoracer.rommio.ui.screen.LaunchTarget(hit.id, hit.title, fileId, hit.coverUrl, platformSlug = hit.platformSlug))
                } else {
                    onOpenGame(hit.id)
                }
            },
        )
    }
    val riso = RommioTheme.isRiso
    val lit = page.hasQuery && page.games.isNotEmpty()
    LitPage(
        if (page.hasQuery && !riso) page.bestMatch?.coverUrl else null,
        if (lit) PageFieldStyle.PAGE else PageFieldStyle.QUIET,
    ) {
        SearchLayout(page, actions, field = { SearchField(viewModel) })
    }
}

/** The search field: reads the query straight from the model, so typing never waits for results. */
@Composable
private fun SearchField(viewModel: SearchViewModel) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    ThemedTextField(
        value = query,
        onValueChange = viewModel::setQuery,
        modifier = Modifier.fillMaxWidth().testTag("search.field"),
        placeholder = "Games and platforms",
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { viewModel.setQuery("") }) {
                    Icon(Icons.Outlined.Close, contentDescription = "Clear search")
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
    )
}

@Composable
private fun SearchLayout(page: SearchPage, actions: SearchActions, field: @Composable () -> Unit) {
    val theme = RommioTheme.current
    val riso = theme.isRiso
    val gutter = theme.spacing.gutter
    val listState = rememberLazyListState()
    val groups = rememberLazyCardGroups()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier
                .widthIn(max = if (riso) 760.dp else 900.dp)
                .fillMaxSize()
                .drawCardGroups(listState, groups, gutter)
                .testTag("shell.searchList"),
            state = listState,
            contentPadding = PaddingValues(start = gutter, end = gutter, top = 8.dp, bottom = 40.dp),
        ) {
            item(key = "field", contentType = "field") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = if (riso) 16.dp else 0.dp)) {
                    PageHeader(title = "Search")
                    field()
                }
            }
            if (riso) risoSearch(page, actions) else auroraSearch(page, actions, groups, theme.spacing.section)
        }
    }
}

/** The title with the query marked: a wash of the page's light (Aurora) or a yellow highlighter (Riso). */
@Composable
internal fun Highlighted(text: String, query: String, role: RoleStyle, size: Float? = null, maxLines: Int = 2, color: Color) {
    val theme = RommioTheme.current
    val needle = query.trim()
    // The wash is the page light's mark: read only when there is a match to mark.
    val start = if (needle.isEmpty()) -1 else text.indexOf(needle, ignoreCase = true)
    val mark = if (start >= 0 && !theme.isRiso) RommioTheme.light.mark1 else Color.Unspecified
    val annotated: AnnotatedString = remember(text, needle, start, mark, theme) {
        buildAnnotatedString {
            if (start < 0) {
                append(text)
            } else {
                append(text.substring(0, start))
                withStyle(
                    if (theme.isRiso) SpanStyle(background = theme.colors.accent3, color = theme.colors.onAccent) else SpanStyle(background = mark.copy(alpha = 0.26f)),
                ) { append(text.substring(start, start + needle.length)) }
                append(text.substring(start + needle.length))
            }
        }
    }
    val style = if (size != null) role.style.sized(size) else role.style
    Text(annotated, style = style, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

// ---- Aurora ----------------------------------------------------------------------------------

private fun LazyListScope.auroraSearch(page: SearchPage, actions: SearchActions, groups: LazyCardGroups, section: Dp) {
    if (!page.hasQuery) {
        if (page.recentlyPlayed.isNotEmpty()) {
            item(key = "recent", contentType = "recent") { AuroraRecent(page.recentlyPlayed, actions, Modifier.padding(top = section)) }
        }
        item(key = "idle", contentType = "empty") {
            EmptyState(
                title = "Search your library",
                message = if (page.catalogueSize == 0) "Games appear here once your library has loaded." else "Find any of ${PlayFormat.games(page.catalogueSize)} by title or platform.",
                icon = Icons.Outlined.Search,
                modifier = Modifier.padding(top = section),
            )
        }
        return
    }
    val best = page.bestMatch
    if (best != null) {
        item(key = "ranked", contentType = "ranked") { RankedLine(Modifier.padding(top = section)) }
        item(key = "best", contentType = "best") { BestMatch(best, page.query, actions, Modifier.padding(top = section)) }
    }
    if (page.games.size > 1) {
        // The other results are lazy rows in one glass card (drawn behind them by the list).
        lazyListGroup(
            groups = groups,
            riso = false,
            key = "games",
            items = page.games.drop(1),
            itemKey = { it.id },
            title = "Games",
            meta = (page.games.size - 1).toString(),
            top = section,
            dividerInset = 68.dp,
        ) { _, hit -> AuroraResultRow(hit, page.query, actions) }
    }
    if (page.related.isNotEmpty()) {
        item(key = "related", contentType = "related") {
            ListGroup(title = "Platforms and collections", meta = page.related.size.toString(), modifier = Modifier.padding(top = section)) {
                page.related.forEachIndexed { index, item ->
                    if (index > 0) ListDivider()
                    ListRow(
                        title = item.title,
                        subtitle = "${item.kindTitle} · ${item.gamesText}",
                        showChevron = true,
                        onClick = { actions.openRelated(item.related) },
                        modifier = Modifier.testTag("search.related.${item.id}"),
                    )
                }
            }
        }
    }
    if (page.games.isEmpty() && page.related.isEmpty()) {
        item(key = "none", contentType = "empty") {
            EmptyState(
                title = "No results for “${page.query}”",
                message = "Search looks through the games Rommio has loaded from your server. Browse a platform in the Library to load more.",
                icon = Icons.Outlined.Search,
                modifier = Modifier.padding(top = section),
            )
        }
    }
}

@Composable
private fun AuroraRecent(recent: List<SearchHit>, actions: SearchActions, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader(title = "Continue playing")
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            recent.forEach { hit ->
                GameTile(hit.title, hit.coverUrl, onClick = { actions.openGame(hit.id) }, subtitle = hit.platformLine, width = 116.dp)
            }
        }
    }
}

@Composable
private fun RankedLine(modifier: Modifier) {
    val theme = RommioTheme.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = theme.colors.inkSecondary, modifier = Modifier.size(14.dp))
        RoleText("Ranked by recent play, then title", theme.type.caption, size = 12.5f, color = theme.colors.inkSecondary)
    }
}

/** The best match, outlined in the page's light. */
@Composable
private fun BestMatch(best: SearchHit, query: String, actions: SearchActions, modifier: Modifier) {
    val theme = RommioTheme.current
    val c = theme.colors
    GlassSurface(
        modifier
            .fillMaxWidth()
            .border(1.dp, RommioTheme.light.mark1.copy(alpha = 0.7f), theme.radius.cardShape)
            .testTag("search.bestMatch"),
        onClick = { actions.openGame(best.id) },
        onClickLabel = "Open ${best.title}",
        contentPadding = PaddingValues(14.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            GameCover(best.coverUrl, best.title, Modifier.size(104.dp, 138.dp), aspect = null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                RoleText("Best match", theme.type.section, color = c.ink)
                best.playedLine?.let { RoleText(it, theme.type.section, size = 10.5f, color = c.inkSecondary) }
                Highlighted(best.title, query, theme.type.headline, maxLines = 3, color = c.ink)
                RoleText(best.platformLine, theme.type.secondary, color = c.inkSecondary)
                if (best.inProgress) {
                    PrimaryButton(
                        "Resume",
                        onClick = { actions.resume(best) },
                        icon = Icons.Outlined.PlayArrow,
                        large = false,
                        modifier = Modifier.testTag("search.resume.${best.id}"),
                    )
                }
            }
        }
    }
}

@Composable
private fun AuroraResultRow(hit: SearchHit, query: String, actions: SearchActions) {
    val theme = RommioTheme.current
    val c = theme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = theme.spacing.rowHeight)
            .clickable(role = Role.Button, onClickLabel = "Open ${hit.title}") { actions.openGame(hit.id) }
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 16.dp, vertical = 9.dp)
            .testTag("search.result.${hit.id}"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverThumb(hit.coverUrl, hit.title)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Highlighted(hit.title, query, theme.type.bodyEmphasis, size = 16f, color = c.ink)
            RoleText(hit.detail, theme.type.secondary, size = 13f, color = c.inkSecondary, maxLines = 1)
        }
        Chevron()
    }
}

// ---- Riso ------------------------------------------------------------------------------------

private fun LazyListScope.risoSearch(page: SearchPage, actions: SearchActions) {
    if (!page.hasQuery) {
        item(key = "idle", contentType = "idle") { RisoIdleLine(page.catalogueSize) }
        if (page.recentlyPlayed.isNotEmpty()) {
            item(key = "recentHeader", contentType = "sectionHeader") { SectionHeader(title = "Continue playing", meta = page.recentlyPlayed.size.toString()) }
            items(page.recentlyPlayed, key = { "recent/${it.id}" }, contentType = { "entry" }) { RisoEntry(it, page.query, actions, Modifier) }
        }
        if (page.allPlatforms.isNotEmpty()) {
            item(key = "platformsHeader", contentType = "sectionHeader") {
                SectionHeader(title = "Platforms", meta = page.allPlatforms.size.toString(), modifier = Modifier.padding(top = 26.dp))
            }
            items(page.allPlatforms, key = { "platform/${it.id}" }, contentType = { "related" }) { row ->
                RelatedLeaderRow(null, row.title, row.countText) { actions.openRelated(row.related) }
            }
        }
        return
    }
    item(key = "count", contentType = "count") { RisoCountLine(page.games.size, page.query) }
    if (page.related.isNotEmpty()) {
        item(key = "seeAlso", contentType = "seeAlso") { SeeAlso(page.related, actions) }
    }
    if (page.games.isNotEmpty()) {
        item(key = "gamesHeader", contentType = "sectionHeader") { SectionHeader(title = "Games", meta = page.games.size.toString()) }
        itemsIndexed(page.games, key = { _, hit -> "game/${hit.id}" }, contentType = { _, _ -> "entry" }) { index, hit ->
            RisoEntry(hit, page.query, actions, Modifier.testTag(if (index == 0) "search.bestMatch" else "search.result.${hit.id}"))
        }
    }
    if (page.related.isNotEmpty()) {
        item(key = "relatedHeader", contentType = "sectionHeader") {
            SectionHeader(title = "Platforms and collections", modifier = Modifier.padding(top = 26.dp))
        }
        items(page.related, key = { "related/${it.id}" }, contentType = { "related" }) { row ->
            RelatedLeaderRow(row.kindTitle, row.title, row.gamesText) { actions.openRelated(row.related) }
        }
    }
    if (page.games.isEmpty() && page.related.isEmpty()) {
        item(key = "none", contentType = "empty") {
            EmptyState(
                title = "No results for “${page.query}”",
                message = "Search looks through the games Rommio has loaded from your server. Browse a platform in the Library to load more.",
            )
        }
    }
}

@Composable
private fun RisoIdleLine(catalogueSize: Int) {
    val theme = RommioTheme.current
    RoleText(
        if (catalogueSize == 0) "Games appear here once your library has loaded." else "Look up any of ${PlayFormat.games(catalogueSize)} by title or platform.",
        theme.type.body,
        size = 16f,
        italic = true,
        color = theme.colors.inkSecondary,
        modifier = Modifier.padding(bottom = 14.dp),
    )
}

@Composable
private fun RisoCountLine(count: Int, query: String) {
    val theme = RommioTheme.current
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("$count ${if (count == 1) "entry" else "entries"}") }
            append(" for “$query”. ")
            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append("Ranked by what you've played, then by title.") }
        },
        style = theme.type.body.style.sized(16f),
        color = theme.colors.ink,
        modifier = Modifier.padding(bottom = 12.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeeAlso(related: List<SearchRelatedRow>, actions: SearchActions) {
    val theme = RommioTheme.current
    val c = theme.colors
    Row(Modifier.padding(bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        RoleText("See also", theme.type.label, size = 9.5f, color = c.inkSecondary, modifier = Modifier.padding(top = 10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            related.take(6).forEach { item ->
                Box(
                    Modifier
                        .heightIn(min = 36.dp)
                        .border(1.5.dp, c.ink)
                        .clickable(role = Role.Button, onClickLabel = "Open ${item.title}") { actions.openRelated(item.related) }
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

/** An index entry: a small print, the highlighted title, detail, and a play disc when in progress. */
@Composable
private fun RisoEntry(hit: SearchHit, query: String, actions: SearchActions, modifier: Modifier) {
    val theme = RommioTheme.current
    val c = theme.colors
    Column(modifier) {
        Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .weight(1f)
                    .clickable(role = Role.Button, onClickLabel = "Open ${hit.title}") { actions.openGame(hit.id) }
                    .semantics(mergeDescendants = true) {},
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GameCover(hit.coverUrl, hit.title, Modifier.size(44.dp, 58.dp), aspect = null)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Highlighted(hit.title, query, theme.type.headline, size = 24f, color = c.ink)
                    RoleText(hit.detail, theme.type.label, size = 9.5f, color = c.inkSecondary, maxLines = 1)
                }
            }
            if (hit.inProgress) {
                IconCircleButton(
                    Icons.Outlined.PlayArrow,
                    contentDescription = "Resume ${hit.title}",
                    onClick = { actions.resume(hit) },
                    prominent = true,
                    modifier = Modifier.testTag("search.resume.${hit.id}"),
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
