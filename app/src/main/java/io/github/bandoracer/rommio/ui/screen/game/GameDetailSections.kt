package io.github.bandoracer.rommio.ui.screen.game

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.github.bandoracer.rommio.domain.playdata.GameSaves
import io.github.bandoracer.rommio.domain.playdata.SaveEntry
import io.github.bandoracer.rommio.domain.playdata.SaveKind
import io.github.bandoracer.rommio.model.RomMetadata
import io.github.bandoracer.rommio.ui.component.GlassSurface
import io.github.bandoracer.rommio.ui.component.ListDivider
import io.github.bandoracer.rommio.ui.component.ListGroup
import io.github.bandoracer.rommio.ui.component.ListRow
import io.github.bandoracer.rommio.ui.component.PrimaryButton
import io.github.bandoracer.rommio.ui.component.RenameSaveButton
import io.github.bandoracer.rommio.ui.component.RenameSaveDialog
import io.github.bandoracer.rommio.ui.component.RoleText
import io.github.bandoracer.rommio.ui.component.SavePreview
import io.github.bandoracer.rommio.ui.component.SecondaryButton
import io.github.bandoracer.rommio.ui.component.SectionHeader
import io.github.bandoracer.rommio.ui.component.formatDayLabel
import io.github.bandoracer.rommio.ui.component.gameSaveDetail
import io.github.bandoracer.rommio.ui.component.pluralize
import io.github.bandoracer.rommio.ui.component.saveWhereWhen
import io.github.bandoracer.rommio.ui.theme.RommioTheme
import io.github.bandoracer.rommio.util.resolveRemoteAssetUrl

/** Label/value pairs for the Game page's details, in reading order; empty fields are left out. */
internal fun metadataDetails(metadata: RomMetadata): List<Pair<String, String>> = buildList {
    metadata.releaseYear?.let { add("Released" to it.toString()) }
    metadata.genres.takeIf { it.isNotEmpty() }?.let { add((if (it.size == 1) "Genre" else "Genres") to it.joinToString(", ")) }
    metadata.publisherLine.takeIf { it.isNotEmpty() }?.let { add((if (metadata.publishers.isNotEmpty()) "Publisher" else "Companies") to it.joinToString(", ")) }
    metadata.developers.takeIf { it.isNotEmpty() }?.let { add("Developer" to it.joinToString(", ")) }
    metadata.franchises.takeIf { it.isNotEmpty() }?.let { add("Series" to it.joinToString(", ")) }
    metadata.playerCount?.let { add("Players" to it.replace("-", "–")) }
    metadata.ratingOutOf100?.let { add("Rating" to "$it / 100") }
    metadata.regions.takeIf { it.isNotEmpty() }?.let { add((if (it.size == 1) "Region" else "Regions") to it.joinToString(", ")) }
    metadata.languages.takeIf { it.isNotEmpty() }?.let { add((if (it.size == 1) "Language" else "Languages") to it.joinToString(", ")) }
}

/** Details as plain text: label on the left, value on the right. */
@Composable
internal fun GameDetailsGroup(details: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    val theme = RommioTheme.current
    ListGroup(modifier = modifier.testTag("game.details")) {
        details.forEachIndexed { index, (label, value) ->
            if (index > 0) ListDivider()
            if (theme.isRiso) {
                // Riso: label…value with dot leaders (`.ldr`).
                io.github.bandoracer.rommio.ui.component.LeaderLine(
                    label = label,
                    value = value,
                    labelColor = theme.colors.inkSecondary,
                    modifier = Modifier
                        .heightIn(min = 44.dp)
                        .semantics(mergeDescendants = true) {}
                        .padding(horizontal = io.github.bandoracer.rommio.ui.component.LocalRowInset.current, vertical = 10.dp),
                )
                return@forEachIndexed
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .semantics(mergeDescendants = true) {}
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoleText(label, theme.type.secondary, color = theme.colors.inkSecondary, maxLines = 1, modifier = Modifier.width(96.dp))
                RoleText(value, theme.type.body, color = theme.colors.ink, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** RomM screenshots, shown as delivered (no art treatment). */
@Composable
internal fun ScreenshotsRow(paths: List<String>, baseUrl: String?, gutter: androidx.compose.ui.unit.Dp) {
    val theme = RommioTheme.current
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(minOf(theme.radius.cover, 12.dp))
    // Aurora: a 0.5 dp keyline; Riso: a 1 dp ink keyline. Never printed or lit.
    val keyline = if (theme.isRiso) 1.dp else 0.5.dp
    val keylineColor = if (theme.isRiso) theme.colors.ink else theme.colors.ink.copy(alpha = if (theme.isDark) 0.14f else 0.1f)
    LazyRow(
        contentPadding = PaddingValues(horizontal = gutter),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.testTag("game.screenshots"),
    ) {
        items(paths, key = { it }) { path ->
            AsyncImage(
                model = resolveRemoteAssetUrl(baseUrl, path),
                contentDescription = "Screenshot",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .then(Modifier.width(176.dp).height(132.dp))
                    .clip(shape)
                    .background(theme.player.ground)
                    .border(keyline, keylineColor, shape),
            )
        }
    }
}

/**
 * Saved progress sheet: every resume point, save state and earlier save for this game with
 * previews, device and sync. Save states can be named here; loading one happens in the player
 * (Pause › Save states), where the current progress is kept in a safety copy first.
 */
@Composable
internal fun SavedProgressSheet(
    gameTitle: String,
    saves: GameSaves,
    coverUrl: String?,
    canPlay: Boolean,
    onResume: () -> Unit,
    onRename: (SaveEntry, String) -> Unit,
    onClose: () -> Unit,
) {
    val theme = RommioTheme.current
    var renaming by remember { mutableStateOf<SaveEntry?>(null) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .testTag("game.saves.sheet"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RoleText("Saved progress", theme.type.headline, color = theme.colors.ink)
        RoleText(
            "To load a save state, start the game and open Save states from the pause menu. Your current progress is kept in a safety copy first.",
            theme.type.secondary,
            color = theme.colors.inkSecondary,
        )
        saves.resume?.let { resume ->
            SectionHeader(title = "Resume point", modifier = Modifier.padding(top = 4.dp))
            SaveRows(listOf(resume), coverUrl, gameTitle) { renaming = it }
            if (canPlay) {
                PrimaryButton(text = "Resume", onClick = onResume, icon = Icons.Outlined.PlayArrow,
                    modifier = Modifier.fillMaxWidth().testTag("game.saves.resume"))
            }
        }
        SectionHeader(title = "Save states", modifier = Modifier.padding(top = 4.dp))
        if (saves.saveStates.isEmpty()) {
            RoleText("No save states yet.", theme.type.secondary, color = theme.colors.inkSecondary, modifier = Modifier.padding(horizontal = 4.dp))
        } else {
            SaveRows(saves.saveStates, coverUrl, gameTitle) { renaming = it }
        }
        if (saves.history.isNotEmpty()) {
            SectionHeader(title = "Earlier resume points", meta = saves.history.size.toString(), modifier = Modifier.padding(top = 4.dp))
            SaveRows(saves.history.take(HISTORY_SHOWN), coverUrl, gameTitle) { renaming = it }
        }
        saves.gameSaveData.firstOrNull()?.let { latest ->
            SectionHeader(
                title = "Game-save data",
                supportingText = "Saved with every capture; a cold boot starts from the latest.",
                modifier = Modifier.padding(top = 4.dp),
            )
            SaveRows(listOf(latest), coverUrl, gameTitle) { renaming = it }
            if (saves.gameSaveData.size > 1) {
                RoleText("${pluralize(saves.gameSaveData.size - 1, "earlier capture")} kept on this device.", theme.type.caption,
                    color = theme.colors.inkSecondary, modifier = Modifier.padding(horizontal = 4.dp))
            }
        }
        SecondaryButton(text = "Done", onClick = onClose, modifier = Modifier.fillMaxWidth())
    }
    renaming?.let { entry ->
        RenameSaveDialog(current = entry.label, tagPrefix = "game.saves", onDismiss = { renaming = null }, onSave = { text ->
            renaming = null
            onRename(entry, text)
        })
    }
}

private const val HISTORY_SHOWN = 20

@Composable
private fun SaveRows(entries: List<SaveEntry>, coverUrl: String?, gameTitle: String, onRename: (SaveEntry) -> Unit) {
    ListGroup {
        entries.forEachIndexed { index, entry ->
            if (index > 0) ListDivider(inset = 92.dp)
            ListRow(
                title = entry.title,
                subtitle = gameSaveDetail(entry),
                leading = { SavePreview(entry.thumbnailPath, coverUrl, gameTitle) },
                trailing = if (entry.kind == SaveKind.SAVE_STATE) { {
                    RenameSaveButton(entry.label, onClick = { onRename(entry) }, modifier = Modifier.testTag("game.saves.rename"))
                } } else null,
                modifier = Modifier.testTag("game.saves.row"),
            )
        }
    }
}
