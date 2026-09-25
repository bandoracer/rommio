package io.github.bandoracer.rommio.ui.screen.collection

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.bandoracer.rommio.AppContainer
import io.github.bandoracer.rommio.ui.component.EmptyState
import io.github.bandoracer.rommio.ui.component.GameCover
import io.github.bandoracer.rommio.ui.component.LitPage
import io.github.bandoracer.rommio.ui.component.PageFieldStyle
import io.github.bandoracer.rommio.ui.component.PageHeader
import io.github.bandoracer.rommio.ui.component.PrintRule
import io.github.bandoracer.rommio.ui.component.RoleText
import io.github.bandoracer.rommio.ui.component.RuleWeight
import io.github.bandoracer.rommio.ui.component.isRegularWidth
import io.github.bandoracer.rommio.ui.theme.RommioTheme
import java.net.URLDecoder

/**
 * Collection (docs/theme-layouts.md "Collection — one layout"): its description and games, lit by
 * the first game. Aurora: a cover grid. Riso: numbered volumes under a 3 dp rule. The page is built
 * by [CollectionDetailViewModel]; the games are lazy.
 */
@Composable
fun CollectionDetailScreen(
    container: AppContainer,
    kind: String,
    collectionId: String,
    collectionName: String,
    @Suppress("UNUSED_PARAMETER") imageBaseUrl: String?,
    onOpenGame: (romId: Int) -> Unit,
) {
    val routeName = remember(collectionName) { runCatching { URLDecoder.decode(collectionName, Charsets.UTF_8.name()) }.getOrDefault(collectionName) }
    val viewModel: CollectionDetailViewModel = viewModel(
        key = "collection-$kind-$collectionId",
        factory = viewModelFactory {
            initializer { CollectionDetailViewModel(container.repository, kind, collectionId, routeName) }
        },
    )
    val page by viewModel.page.collectAsStateWithLifecycle()
    LitPage(page.leadCoverUrl, PageFieldStyle.PAGE) {
        io.github.bandoracer.rommio.ui.component.RefreshBox(
            refreshing = page.isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize().testTag("screen.collection"),
        ) {
            CollectionLayout(page, onOpenGame)
        }
    }
}

@Composable
private fun CollectionLayout(page: CollectionPage, open: (Int) -> Unit) {
    val theme = RommioTheme.current
    val gutter = theme.spacing.gutter
    val section = theme.spacing.section
    val riso = theme.isRiso
    val regular = isRegularWidth()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        BoxWithConstraints(Modifier.widthIn(max = 980.dp).fillMaxSize()) {
            // Aurora's cover grid: columns for the content width (min cell, 14 dp gaps).
            val minCell = if (regular) 132.dp else 104.dp
            val columns = maxOf(1, ((maxWidth - gutter * 2 + 14.dp) / (minCell + 14.dp)).toInt())
            val rows = remember(page.games, columns, riso) { if (riso) emptyList() else page.games.chunked(columns) }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = gutter, end = gutter, top = 4.dp, bottom = 40.dp),
            ) {
                item(key = "header", contentType = "header") { PageHeader(title = page.name) }
                item(key = "about", contentType = "about") { About(page, Modifier.padding(top = section)) }
                when {
                    page.games.isEmpty() -> item(key = "empty", contentType = "empty") {
                        EmptyState(
                            title = if (page.isLoading) "Loading games" else "Nothing here yet",
                            message = if (page.isLoading) "" else page.errorMessage ?: "This collection has no games on your server yet.",
                            icon = Icons.Outlined.Collections,
                            modifier = Modifier.padding(top = section),
                        )
                    }
                    riso -> {
                        item(key = "rule", contentType = "rule") { PrintRule(weight = RuleWeight.HEAVY, modifier = Modifier.padding(top = section)) }
                        itemsIndexed(page.games, key = { _, game -> "game/${game.id}" }, contentType = { _, _ -> "volume" }) { index, game ->
                            Volume(index + 1, game, open, "collection.game")
                        }
                    }
                    else -> itemsIndexed(rows, key = { _, row -> "row/${row.first().id}" }, contentType = { _, _ -> "gridRow" }) { index, row ->
                        ShelfRow(row, columns, open, "collection.game", Modifier.padding(top = if (index == 0) section else 20.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun About(page: CollectionPage, modifier: Modifier) {
    val theme = RommioTheme.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RoleText(page.countText, theme.type.section, color = theme.colors.inkSecondary)
        page.description?.let {
            RoleText(it, theme.type.body, size = if (theme.isRiso) 18f else 16f, italic = theme.isRiso, color = theme.colors.ink)
        }
    }
}

/** A Riso volume: its number, the printed cover, the title and "Platform · year", a hairline under it. */
@Composable
private fun Volume(number: Int, game: ShelfGame, open: (Int) -> Unit, tagPrefix: String) {
    val theme = RommioTheme.current
    val c = theme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = "Open ${game.title}") { open(game.id) }
            .testTag("$tagPrefix.${game.id}"),
    ) {
        Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            RoleText("$number", theme.type.statValue, size = 34f, color = c.accent2, modifier = Modifier.width(34.dp))
            GameCover(game.coverUrl, game.title, Modifier.size(56.dp, 75.dp), aspect = null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                RoleText(game.title, theme.type.headline, size = 22f, color = c.ink, maxLines = 2)
                RoleText(game.detail, theme.type.label, size = 10f, color = c.inkSecondary)
            }
        }
        PrintRule(weight = RuleWeight.HAIRLINE)
    }
}

/** A row of Aurora's cover grid (14 dp gaps; empty cells keep the columns). */
@Composable
private fun ShelfRow(row: List<ShelfGame>, columns: Int, open: (Int) -> Unit, tagPrefix: String, modifier: Modifier) {
    val theme = RommioTheme.current
    val c = theme.colors
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        row.forEach { game ->
            Column(
                Modifier
                    .weight(1f)
                    .clickable(role = Role.Button, onClickLabel = "Open ${game.title}") { open(game.id) }
                    .semantics(mergeDescendants = true) {}
                    .testTag("$tagPrefix.${game.id}"),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                GameCover(game.coverUrl, game.title, Modifier.fillMaxWidth())
                RoleText(game.title, theme.type.bodyEmphasis, size = 14f, color = c.ink, maxLines = 2)
                RoleText(game.detail, theme.type.caption, size = 12f, color = c.inkSecondary, maxLines = 1)
            }
        }
        repeat(columns - row.size) { Box(Modifier.weight(1f)) }
    }
}
