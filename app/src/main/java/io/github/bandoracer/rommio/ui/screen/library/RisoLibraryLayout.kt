package io.github.bandoracer.rommio.ui.screen.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.bandoracer.rommio.domain.player.EmbeddedSupportTier
import io.github.bandoracer.rommio.model.CollectionKind
import io.github.bandoracer.rommio.model.PlatformDto
import io.github.bandoracer.rommio.model.RommCollectionDto
import io.github.bandoracer.rommio.ui.component.DotLeader
import io.github.bandoracer.rommio.ui.component.EmptyState
import io.github.bandoracer.rommio.ui.component.Kicker
import io.github.bandoracer.rommio.ui.component.KickerFill
import io.github.bandoracer.rommio.ui.component.LeaderLayout
import io.github.bandoracer.rommio.ui.component.PageArtSubject
import io.github.bandoracer.rommio.ui.component.PageFieldStyle
import io.github.bandoracer.rommio.ui.component.PageHeader
import io.github.bandoracer.rommio.ui.component.PlayFormat
import io.github.bandoracer.rommio.ui.component.PrintRule
import io.github.bandoracer.rommio.ui.component.RoleText
import io.github.bandoracer.rommio.ui.component.RuleWeight
import io.github.bandoracer.rommio.ui.component.SectionHeader
import io.github.bandoracer.rommio.ui.component.SupportTierBadge
import io.github.bandoracer.rommio.ui.theme.RommioTheme

/**
 * Riso Library: a contents page. Platforms are numbered entries grouped by how they play, each
 * with dot leaders to its game count and how many are on this device; collections follow as
 * ruled entries.
 */
@Composable
fun RisoLibraryLayout(state: LibraryUiState, actions: LibraryActions) {
    val theme = RommioTheme.current
    PageArtSubject(null, PageFieldStyle.QUIET)
    val numbers = state.platforms.map { it.platform.id }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = 760.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .testTag("shell.libraryList")
                .padding(horizontal = theme.spacing.gutter)
                .padding(top = 8.dp, bottom = 40.dp),
        ) {
            PageHeader(title = "Library", modifier = Modifier.padding(bottom = 6.dp))
            if (state.platforms.isEmpty()) {
                EmptyState(
                    title = if (state.isLoading) "Loading your library" else "No platforms yet",
                    message = if (state.isLoading) "" else state.errorMessage ?: "Pull to refresh once your server is reachable.",
                    icon = Icons.Outlined.VideoLibrary,
                )
                return@Column
            }
            RoleText(state.summary, theme.type.body, size = 15f, italic = true, color = theme.colors.inkSecondary, modifier = Modifier.padding(bottom = 14.dp))
            Key()
            state.sections().forEach { (tier, platforms) -> Part(tier, platforms, state, numbers, actions) }
            Collections(state.collections, actions)
            state.staleMessage?.let { message ->
                Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Kicker("Showing your saved library", fill = KickerFill.YELLOW)
                    RoleText(message, theme.type.caption, color = theme.colors.inkSecondary)
                }
            }
        }
    }
}

/** The column key above the contents: what the two figures mean. */
@Composable
private fun Key() {
    val theme = RommioTheme.current
    Row(
        Modifier.fillMaxWidth().padding(bottom = 6.dp).clearAndSetSemantics {},
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        RoleText("Platform", theme.type.label, size = 9.5f, color = theme.colors.inkSecondary, modifier = Modifier.weight(1f))
        RoleText("Games", theme.type.label, size = 9.5f, color = theme.colors.inkSecondary)
        RoleText("On this device", theme.type.label, size = 9.5f, color = theme.colors.inkSecondary, textAlign = TextAlign.End, modifier = Modifier.width(92.dp))
    }
}

@Composable
private fun Part(
    tier: EmbeddedSupportTier,
    platforms: List<LibraryPlatform>,
    state: LibraryUiState,
    numbers: List<Int>,
    actions: LibraryActions,
) {
    val theme = RommioTheme.current
    Column(Modifier.padding(bottom = 18.dp)) {
        PrintRule(weight = RuleWeight.HEAVY)
        Column(Modifier.padding(top = 10.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                RoleText(tierTitle(tier), theme.type.headline, size = 28f, color = theme.colors.ink, modifier = Modifier.weight(1f).semantics { heading() })
                SupportTierBadge(tier, Modifier.padding(start = 8.dp, bottom = 4.dp))
            }
            RoleText(tierDek(tier), theme.type.caption, size = 13.5f, italic = true, color = theme.colors.inkSecondary)
        }
        PrintRule(weight = RuleWeight.FINE)
        platforms.forEach { entry ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClickLabel = "Open ${entry.platform.name}") { actions.openPlatform(entry.platform) }
                    .testTag("library.platform.${entry.platform.slug}"),
            ) {
                ChapterRow(
                    number = numbers.indexOf(entry.platform.id) + 1,
                    platform = entry.platform,
                    installed = state.installed(entry),
                    dimmed = tier == EmbeddedSupportTier.UNSUPPORTED,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
                PrintRule(weight = RuleWeight.HAIRLINE)
            }
        }
    }
}

/**
 * A platform as a contents entry (Riso): a two-digit number in `accent2`, the name (`body` 17,
 * `label` slug), dot leaders, the games on the server (`numeral` 12.5) and those on this device
 * in an ink block (or "–").
 */
@Composable
fun ChapterRow(
    number: Int,
    platform: PlatformDto,
    installed: Int,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    dimmed: Boolean = false,
) {
    val theme = RommioTheme.current
    val c = theme.colors
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(vertical = 6.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "${platform.name}, ${PlayFormat.games(platform.romCount)}, $installed on this device"
            },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        RoleText("%02d".format(number), theme.type.headline, size = 19f, color = c.accent2, modifier = Modifier.width(28.dp).alignByBaseline())
        LeaderLayout(
            modifier = Modifier.weight(1f).alignByBaseline(),
            start = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    RoleText(platform.name, theme.type.body, size = if (compact) 16f else 17f, color = if (dimmed) c.inkSecondary else c.ink, maxLines = 2)
                    RoleText(platform.slug.uppercase(), theme.type.label, size = 9.5f, color = c.inkSecondary, maxLines = 1)
                }
            },
            end = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RoleText(
                        java.text.NumberFormat.getIntegerInstance().format(platform.romCount),
                        theme.type.numeral,
                        size = 12.5f,
                        color = c.ink,
                        textAlign = TextAlign.End,
                        modifier = Modifier.widthIn(min = 34.dp),
                    )
                    Box(Modifier.width(if (compact) 30.dp else 44.dp), contentAlignment = Alignment.CenterEnd) {
                        if (installed > 0) {
                            RoleText(
                                "$installed",
                                theme.type.numeral,
                                size = 11.5f,
                                color = c.ground,
                                modifier = Modifier.background(c.ink).padding(horizontal = 5.dp, vertical = 2.dp),
                            )
                        } else {
                            RoleText("–", theme.type.numeral, size = 11.5f, color = c.inkSecondary)
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun Collections(collections: List<RommCollectionDto>, actions: LibraryActions) {
    val theme = RommioTheme.current
    val c = theme.colors
    if (collections.isEmpty()) return
    Column {
        SectionHeader(title = "Collections", meta = collections.size.toString(), modifier = Modifier.padding(top = 10.dp))
        collections.forEach { collection ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClickLabel = "Open ${collection.name}") { actions.openCollection(collection) }
                    .semantics(mergeDescendants = true) { contentDescription = "${collection.name}, ${PlayFormat.games(collection.romCount)}" }
                    .testTag("library.collection.${collection.id}"),
            ) {
                Box(Modifier.fillMaxWidth().heightIn(min = 54.dp).padding(vertical = 6.dp), contentAlignment = Alignment.CenterStart) {
                    LeaderLayout(
                        gap = 10.dp,
                        start = {
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                RoleText(collection.name, theme.type.bodyEmphasis, size = 16f, color = c.ink, maxLines = 2)
                                RoleText(
                                    collection.description.ifBlank { collectionKind(collection) },
                                    theme.type.caption,
                                    size = 12.5f,
                                    italic = true,
                                    color = c.inkSecondary,
                                    maxLines = 1,
                                )
                            }
                        },
                        end = { RoleText("${collection.romCount}", theme.type.statValue, size = 26f, color = c.ink) },
                    )
                }
                PrintRule(weight = RuleWeight.HAIRLINE)
            }
        }
    }
}

private fun collectionKind(collection: RommCollectionDto): String = when (collection.kind) {
    CollectionKind.SMART -> "Smart collection"
    CollectionKind.VIRTUAL -> "Made by RomM"
    CollectionKind.REGULAR -> "Made by you"
}
