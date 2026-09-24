package io.github.bandoracer.rommio.ui.screen.collection

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.bandoracer.rommio.AppContainer
import io.github.bandoracer.rommio.model.RomDto
import io.github.bandoracer.rommio.ui.component.EmptyState
import io.github.bandoracer.rommio.ui.component.GameCover
import io.github.bandoracer.rommio.ui.component.PageArtSubject
import io.github.bandoracer.rommio.ui.component.PageFieldStyle
import io.github.bandoracer.rommio.ui.component.PageHeader
import io.github.bandoracer.rommio.ui.component.PlayFormat
import io.github.bandoracer.rommio.ui.component.PrintRule
import io.github.bandoracer.rommio.ui.component.RoleText
import io.github.bandoracer.rommio.ui.component.RuleWeight
import io.github.bandoracer.rommio.ui.component.isRegularWidth
import io.github.bandoracer.rommio.ui.screen.collectAsStateWithLifecycleCompat
import io.github.bandoracer.rommio.ui.theme.RommioTheme
import io.github.bandoracer.rommio.util.resolveRemoteAssetUrl
import java.net.URLDecoder

/**
 * Collection (docs/theme-layouts.md "Collection — one layout"): its description and games, lit by
 * the first game. Aurora: a cover grid. Riso: numbered volumes under a 3 dp rule.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionDetailScreen(
    container: AppContainer,
    kind: String,
    collectionId: String,
    collectionName: String,
    imageBaseUrl: String?,
    onRomSelected: (RomDto) -> Unit,
) {
    val viewModel: CollectionDetailViewModel = viewModel(
        key = "collection-$kind-$collectionId",
        factory = viewModelFactory {
            initializer { CollectionDetailViewModel(container.repository, kind, collectionId) }
        },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycleCompat()
    val theme = RommioTheme.current
    val c = theme.colors
    val roms = (state.touchSupportedRoms + state.controllerSupportedRoms + state.unsupportedRoms)
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName })
    val cover: (RomDto) -> String? = { resolveRemoteAssetUrl(imageBaseUrl, it.urlCover) }
    PageArtSubject(roms.firstOrNull()?.let(cover), PageFieldStyle.PAGE)
    val name = state.collection?.name ?: runCatching { URLDecoder.decode(collectionName, Charsets.UTF_8.name()) }.getOrDefault(collectionName)

    io.github.bandoracer.rommio.ui.component.RefreshBox(
        refreshing = state.isRefreshing,
        onRefresh = viewModel::refresh,
        modifier = Modifier.fillMaxSize().testTag("screen.collection"),
    ) {
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
                PageHeader(title = name)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RoleText(PlayFormat.games(maxOf(state.collection?.romCount ?: 0, roms.size)), theme.type.section, color = c.inkSecondary)
                    state.collection?.description?.takeIf { it.isNotBlank() }?.let {
                        RoleText(it, theme.type.body, size = if (theme.isRiso) 18f else 16f, italic = theme.isRiso, color = c.ink)
                    }
                }
                if (roms.isEmpty()) {
                    EmptyState(
                        title = if (state.isLoading) "Loading games" else "Nothing here yet",
                        message = if (state.isLoading) "" else state.errorMessage ?: "This collection has no games on your server yet.",
                        icon = Icons.Outlined.Collections,
                    )
                } else {
                    GameShelf(roms, cover, onRomSelected, "collection.game")
                }
            }
        }
    }
}

/** Games as a cover grid (Aurora) or numbered volumes with a printed cover (Riso). */
@Composable
fun GameShelf(roms: List<RomDto>, cover: (RomDto) -> String?, onOpen: (RomDto) -> Unit, tagPrefix: String) {
    val theme = RommioTheme.current
    val c = theme.colors
    if (theme.isRiso) {
        Column {
            PrintRule(weight = RuleWeight.HEAVY)
            roms.forEachIndexed { index, rom ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button, onClickLabel = "Open ${rom.displayName}") { onOpen(rom) }
                        .testTag("$tagPrefix.${rom.id}"),
                ) {
                    Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        RoleText("${index + 1}", theme.type.statValue, size = 34f, color = c.accent2, modifier = Modifier.width(34.dp))
                        GameCover(cover(rom), rom.displayName, Modifier.size(56.dp, 75.dp), aspect = null)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            RoleText(rom.displayName, theme.type.headline, size = 22f, color = c.ink, maxLines = 2)
                            RoleText(listOfNotNull(rom.platformName, rom.metadata.releaseYear?.toString()).joinToString(" · "), theme.type.label, size = 10f, color = c.inkSecondary)
                        }
                    }
                    PrintRule(weight = RuleWeight.HAIRLINE)
                }
            }
        }
        return
    }
    val minCell = if (isRegularWidth()) 132.dp else 104.dp
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = maxOf(1, ((maxWidth + 14.dp) / (minCell + 14.dp)).toInt())
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            roms.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    row.forEach { rom ->
                        Column(
                            Modifier
                                .weight(1f)
                                .clickable(role = Role.Button, onClickLabel = "Open ${rom.displayName}") { onOpen(rom) }
                                .semantics(mergeDescendants = true) {}
                                .testTag("$tagPrefix.${rom.id}"),
                            verticalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            GameCover(cover(rom), rom.displayName, Modifier.fillMaxWidth())
                            RoleText(rom.displayName, theme.type.bodyEmphasis, size = 14f, color = c.ink, maxLines = 2)
                            RoleText(listOfNotNull(rom.platformName, rom.metadata.releaseYear?.toString()).joinToString(" · "), theme.type.caption, size = 12f, color = c.inkSecondary, maxLines = 1)
                        }
                    }
                    repeat(columns - row.size) { Box(Modifier.weight(1f)) }
                }
            }
        }
    }
}
