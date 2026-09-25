package io.github.bandoracer.rommio.ui.component

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.bandoracer.rommio.domain.playdata.LaunchOptions
import io.github.bandoracer.rommio.domain.playdata.LaunchStart
import io.github.bandoracer.rommio.domain.playdata.SaveEntry
import io.github.bandoracer.rommio.domain.playdata.SaveKind
import io.github.bandoracer.rommio.ui.theme.LitMaterialTheme
import io.github.bandoracer.rommio.ui.theme.RommioTheme

/** "Automatic resume · 3 min ago · This phone" (or the save state it came from). */
fun launchResumeDetail(entry: SaveEntry, now: Long = System.currentTimeMillis()): String = Fmt.join(
    when {
        entry.kind == SaveKind.SAVE_STATE && entry.label != null -> "“${entry.label}”"
        entry.kind == SaveKind.SAVE_STATE -> entry.slotNumber?.let { "Save state · Slot $it" } ?: "Save state"
        else -> "Automatic resume"
    },
    Fmt.relative(entry.capturedAtEpochMs, now),
    entry.deviceLabel,
)

/** "Game-save data · 3 min ago · the game starts from its own save". */
fun launchGameSaveDetail(entry: SaveEntry, now: Long = System.currentTimeMillis()): String =
    Fmt.join("Game-save data", Fmt.relative(entry.capturedAtEpochMs, now), "the game starts from its own save")

/** The primary button follows the selection. */
fun launchStartLabel(start: LaunchStart): String = when (start) {
    LaunchStart.RESUME -> "Resume"
    LaunchStart.GAME_SAVE -> "Start with game save"
}

/**
 * Launch choice (docs/theme-layouts.md "Launch choice — one layout"): how to start a game that has
 * both a resume point and game-save data, chosen before the player opens. Kicker "Start", the game
 * as the title, one row per option (Resume with its frame; Start with game save with a save
 * glyph), a primary button whose label follows the selection, and "Not now". A bottom sheet at its
 * medium height; the theme's components draw it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LaunchChoiceSheet(
    gameTitle: String,
    options: LaunchOptions,
    coverUrl: String?,
    onStart: (LaunchStart) -> Unit,
    onDismiss: () -> Unit,
    /** Riso's kicker detail: the platform slug. */
    platformSlug: String? = null,
) {
    val theme = RommioTheme.current
    val c = theme.colors
    var selected by rememberSaveable(options.resume.key.checkpointId, options.gameSave.key.checkpointId) { mutableStateOf(options.default) }
    // A stock sheet: its drag handle and scrim take the page's lit scheme.
    LitMaterialTheme { ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        containerColor = c.groundRaised,
        shape = if (theme.isRiso) RoundedCornerShape(0.dp) else RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = theme.spacing.gutter)
                .padding(bottom = 24.dp)
                .testTag("launch.sheet"),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (theme.isRiso) {
                    Kicker("Start", detail = platformSlug?.uppercase())
                    DisplayText(gameTitle, role = theme.type.display, size = 40f, maxLines = 2, modifier = Modifier.semantics { heading() })
                } else {
                    RoleText("Start", theme.type.section, color = c.inkSecondary)
                    RoleText(gameTitle, theme.type.title, size = 30f, color = c.ink, maxLines = 2, modifier = Modifier.semantics { heading() })
                }
            }
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LaunchChoiceRow(
                    title = launchStartLabel(LaunchStart.RESUME),
                    detail = launchResumeDetail(options.resume),
                    selected = selected == LaunchStart.RESUME,
                    enabled = !options.resumeUnavailable,
                    onSelect = { selected = LaunchStart.RESUME },
                    leading = { SavePreview(options.resume.thumbnailPath, coverUrl, gameTitle, width = 72.dp) },
                    modifier = Modifier.testTag("launch.resume"),
                )
                LaunchChoiceRow(
                    title = launchStartLabel(LaunchStart.GAME_SAVE),
                    detail = launchGameSaveDetail(options.gameSave),
                    selected = selected == LaunchStart.GAME_SAVE,
                    enabled = true,
                    onSelect = { selected = LaunchStart.GAME_SAVE },
                    leading = { SaveGlyph() },
                    modifier = Modifier.testTag("launch.gameSave"),
                )
            }
            if (options.resumeUnavailable) {
                RoleText(
                    "This resume point can't be loaded on this device. The game can start from its game-save data.",
                    theme.type.caption,
                    color = c.inkSecondary,
                    modifier = Modifier.testTag("launch.note"),
                )
            }
            PrimaryButton(
                text = launchStartLabel(selected),
                icon = Icons.Outlined.PlayArrow,
                onClick = { onStart(selected) },
                modifier = Modifier.fillMaxWidth().testTag("launch.start"),
            )
            TextAction("Not now", onClick = onDismiss, modifier = Modifier.align(Alignment.CenterHorizontally).testTag("launch.dismiss"))
        }
    } }
}

/**
 * One way to start: its picture, title (`bodyEmphasis`), detail (`caption`) and a radio. Aurora:
 * a glass card, selected with a 2 dp `mark1` outline. Riso: paper in a 1.5 dp ink box, 2.5 dp when
 * selected.
 */
@Composable
private fun LaunchChoiceRow(
    title: String,
    detail: String,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    leading: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = RommioTheme.current
    val c = theme.colors
    val radius = if (theme.isRiso) 0.dp else theme.radius.card
    val shape = RoundedCornerShape(radius)
    val face = if (theme.isRiso) {
        Modifier.clip(shape).border(if (selected) 2.5.dp else theme.strokeWidth, c.ink, shape)
    } else {
        Modifier
            .themedSurface(theme, cornerRadius = radius)
            .then(if (selected) Modifier.border(2.dp, RommioTheme.light.mark1, shape) else Modifier)
    }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .then(face)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            RoleText(title, theme.type.bodyEmphasis, size = 16f, color = c.ink, maxLines = 1)
            RoleText(detail, theme.type.caption, color = c.inkSecondary, maxLines = 3)
        }
        RadioMark(selected)
    }
}

/** The game-save option's picture: a save glyph on the theme's tile (no frame). */
@Composable
private fun SaveGlyph() {
    val theme = RommioTheme.current
    Box(
        Modifier
            .size(width = 72.dp, height = 54.dp)
            .clip(RoundedCornerShape(if (theme.isRiso) 0.dp else minOf(theme.radius.cover, 8.dp)))
            .then(if (theme.isRiso) Modifier.border(theme.strokeWidth, theme.colors.ink) else Modifier.themedSurface(theme, 8.dp, fill = theme.colors.surfaceStrong, nested = true)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Outlined.SaveAlt, contentDescription = null, tint = theme.colors.ink, modifier = Modifier.size(24.dp))
    }
}
