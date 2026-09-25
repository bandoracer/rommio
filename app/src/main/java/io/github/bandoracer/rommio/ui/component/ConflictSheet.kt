package io.github.bandoracer.rommio.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.github.bandoracer.rommio.domain.playdata.SaveConflict
import io.github.bandoracer.rommio.domain.playdata.SaveConflictKind
import io.github.bandoracer.rommio.domain.playdata.SaveEntry
import io.github.bandoracer.rommio.domain.playdata.SaveSyncStatus
import io.github.bandoracer.rommio.ui.theme.LitMaterialTheme
import androidx.compose.ui.graphics.Color
import io.github.bandoracer.rommio.ui.theme.RommioTheme
import java.io.File

/**
 * Conflict (docs/theme-layouts.md "Conflict — one layout", sheet from Play and Game): a game's
 * competing heads side by side — its resume points, or versions of its game-save data — newest
 * first, so the player can choose which to keep playing. Choosing opens the player with that head;
 * the player resolves the branch with the existing progress rules (the other stays in the game's
 * saved progress). Nothing here writes. There is no sync badge on a choice: every head of a
 * conflicted reference reads "Conflict". Previews are shown as captured (never art-treated); the
 * cover stands in when there is none.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResumeConflictSheet(
    gameTitle: String,
    heads: List<SaveEntry>,
    coverUrl: String?,
    canPlay: Boolean,
    onKeep: (SaveEntry) -> Unit,
    onDismiss: () -> Unit,
    kind: SaveConflictKind = SaveConflictKind.RESUME,
) {
    val theme = RommioTheme.current
    val c = theme.colors
    val conflict = SaveConflict(kind, heads)
    var selected by rememberSaveable(heads.map { it.key.checkpointId }) { mutableStateOf(0) }
    // A stock sheet: its drag handle and scrim take the page's lit scheme.
    LitMaterialTheme { ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.groundRaised,
        shape = if (theme.isRiso) RoundedCornerShape(0.dp) else RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = if (theme.isRiso) 16.dp else 16.dp)
                .padding(bottom = 24.dp)
                .testTag("conflict.sheet"),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ConflictHeader(gameTitle, conflict)
            val rows = heads.chunked(2)
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                rows.forEachIndexed { r, pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        pair.forEachIndexed { i, entry ->
                            val index = r * 2 + i
                            ConflictChoice(
                                entry = entry,
                                coverUrl = coverUrl,
                                gameTitle = gameTitle,
                                selected = index == selected,
                                onSelect = { selected = index },
                                modifier = Modifier.weight(1f).testTag("conflict.choice.$index"),
                            )
                        }
                        if (pair.size == 1) Box(Modifier.weight(1f))
                    }
                }
            }
            RoleText(
                "The other ${kind.noun} stays in this game's saved progress.",
                if (theme.isRiso) theme.type.body else theme.type.caption,
                size = if (theme.isRiso) 14.5f else null,
                italic = theme.isRiso,
                color = c.inkSecondary,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            PrimaryButton(
                text = "Keep playing",
                icon = Icons.Outlined.PlayArrow,
                enabled = canPlay && heads.isNotEmpty(),
                onClick = { heads.getOrNull(selected)?.let(onKeep) },
                modifier = Modifier.fillMaxWidth().testTag("conflict.keep"),
            )
            if (!canPlay) {
                RoleText(
                    "Download $gameTitle to this device to keep playing.",
                    theme.type.caption,
                    color = c.inkSecondary,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            TextAction("Not now", onClick = onDismiss, modifier = Modifier.align(Alignment.CenterHorizontally).testTag("conflict.dismiss"))
        }
    } }
}

@Composable
private fun ConflictHeader(gameTitle: String, conflict: SaveConflict) {
    val theme = RommioTheme.current
    val c = theme.colors
    Column(verticalArrangement = Arrangement.spacedBy(if (theme.isRiso) 6.dp else 8.dp)) {
        if (theme.isRiso) {
            Kicker("Conflict", detail = conflict.countLabel, fill = KickerFill.PINK)
            DisplayText(gameTitle, role = theme.type.display, size = 44f, maxLines = 2)
            PrintRule(weight = RuleWeight.KEYLINE)
            RoleText(conflict.prompt(gameTitle), theme.type.body, size = 17f, italic = true, color = c.ink)
        } else {
            StatusChip("Conflict", tone = Tone.BAD)
            DisplayText(gameTitle, role = theme.type.title, size = 34f, maxLines = 2)
            RoleText(conflict.prompt(gameTitle), theme.type.secondary, color = c.inkSecondary)
        }
    }
}

/**
 * One head to choose. Aurora: a glass card with the frame, a radio, the device in `headline` and
 * "Resume point · 7:07 PM · Yesterday"; selected = 2 dp `mark1` outline with its glow. Riso: paper
 * with a 1.5 dp ink keyline; selected = 2.5 dp ink outline on the pink plate.
 */
@Composable
private fun ConflictChoice(
    entry: SaveEntry,
    coverUrl: String?,
    gameTitle: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = RommioTheme.current
    val c = theme.colors
    val radius = if (theme.isRiso) 0.dp else theme.radius.card
    val shape = RoundedCornerShape(radius)
    val outer = if (theme.isRiso) {
        modifier
            .then(if (selected) Modifier.plateOffset(c.accent1, 3.dp) else Modifier)
            .background(c.ground)
            .border(if (selected) 2.5.dp else 1.5.dp, c.ink)
    } else {
        val mark = if (selected) RommioTheme.light.mark1 else Color.Unspecified
        modifier
            .then(if (selected) Modifier.dropShadow(mark.copy(alpha = 0.55f), radius, 14.dp, 12.dp) else Modifier)
            .themedSurface(theme, cornerRadius = radius)
            .then(if (selected) Modifier.border(2.dp, mark, shape) else Modifier)
    }
    Column(
        outer
            .clip(shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(8.dp)
            .padding(bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val frameShape = RoundedCornerShape(if (theme.isRiso) 0.dp else 12.dp)
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
                .clip(frameShape)
                .background(if (entry.thumbnailPath != null) theme.player.ground else OverArt.frameGround)
                .then(if (theme.isRiso) Modifier.border(1.dp, c.ink) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            val thumb = entry.thumbnailPath
            when {
                thumb != null -> AsyncImage(
                    model = File(thumb),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    filterQuality = FilterQuality.None,
                    modifier = Modifier.fillMaxSize(),
                )
                coverUrl != null -> AsyncImage(model = coverUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else -> CoverPlaceholder(gameTitle, Modifier.fillMaxSize())
            }
        }
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                RoleText(entry.deviceLabel, theme.type.headline, size = if (theme.isRiso) 20f else 19f, color = c.ink, maxLines = 2)
                RoleText(
                    Fmt.join(entry.label ?: entry.kindLabel, formatClockTime(entry.capturedAtEpochMs), formatDayLabel(entry.capturedAtEpochMs)),
                    theme.type.caption,
                    color = c.inkSecondary,
                    maxLines = 3,
                )
            }
            RadioMark(selected)
        }
    }
}

/**
 * A radio for a choice card. Aurora: a ring in `inkTertiary`; selected, a `mark1` disc with a
 * light centre. Riso: a square in a 1.5 dp ink box, ink-filled when selected.
 */
@Composable
internal fun RadioMark(selected: Boolean) {
    val theme = RommioTheme.current
    val c = theme.colors
    if (theme.isRiso) {
        Box(
            Modifier
                .size(18.dp)
                .border(1.5.dp, c.ink)
                .padding(4.dp)
                .then(if (selected) Modifier.background(c.ink) else Modifier),
        )
    } else {
        Box(
            Modifier
                .size(20.dp)
                .clip(CircleShape)
                .then(
                    if (selected) Modifier.background(RommioTheme.light.mark1).padding(6.dp).clip(CircleShape).background(OverArt.rim)
                    else Modifier.border(1.5.dp, c.inkTertiary, CircleShape),
                ),
        )
    }
}

/** Sync tone for a save's badge (save-sync-spec §11 words). */
fun SaveSyncStatus.chipTone(): Tone = when (this) {
    SaveSyncStatus.SYNCED -> Tone.OK
    SaveSyncStatus.SYNCING -> Tone.LIVE
    SaveSyncStatus.WAITING, SaveSyncStatus.SIGN_IN_NEEDED -> Tone.WAIT
    SaveSyncStatus.CONFLICT -> Tone.BAD
    SaveSyncStatus.LOCAL_ONLY, SaveSyncStatus.UNKNOWN -> Tone.MUTE
}
