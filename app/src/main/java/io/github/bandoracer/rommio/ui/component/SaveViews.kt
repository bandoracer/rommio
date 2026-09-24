package io.github.bandoracer.rommio.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.github.bandoracer.rommio.domain.playdata.SaveEntry
import io.github.bandoracer.rommio.domain.playdata.SaveKind
import io.github.bandoracer.rommio.domain.playdata.SaveSyncStatus
import io.github.bandoracer.rommio.domain.playdata.SavedProgressRules
import io.github.bandoracer.rommio.ui.theme.RommioTheme
import java.io.File

/**
 * A save's preview: the gameplay frame captured with it (shown as captured, letterboxed on the
 * player ground, never given a theme art treatment), or the game's cover when there is none.
 */
@Composable
fun SavePreview(
    thumbnailPath: String?,
    coverUrl: String?,
    title: String,
    modifier: Modifier = Modifier,
    width: Dp = 64.dp,
) {
    val theme = RommioTheme.current
    // A frame, not a cover: a small, theme-bounded corner so the picture reads as a screen.
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(minOf(theme.radius.cover, 8.dp))
    Box(
        modifier = modifier
            .size(width = width, height = width * 3f / 4f)
            .clip(shape)
            .background(if (thumbnailPath != null) theme.player.ground else theme.solidSurfaceStrong)
            .border(theme.strokeWidth, theme.strokeColor, shape),
        contentAlignment = Alignment.Center,
    ) {
        when {
            thumbnailPath != null -> AsyncImage(
                model = File(thumbnailPath),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            coverUrl != null -> AsyncImage(
                model = coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            else -> RoleText(
                text = title.take(1).uppercase().ifBlank { "?" },
                role = theme.type.headline,
                color = theme.colors.inkSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(4.dp),
            )
        }
    }
}

/** "8:59 PM · This phone · Synced": when, where and whether it synced. */
fun saveWhereWhen(entry: SaveEntry): String = listOfNotNull(
    formatClockTime(entry.capturedAtEpochMs),
    entry.deviceLabel,
    entry.sync.takeIf { it != SaveSyncStatus.UNKNOWN }?.label,
).joinToString(" · ")

/** Recent saves row detail: the save's name or kind, then when, where and sync. */
fun recentSaveDetail(entry: SaveEntry): String = listOfNotNull(
    entry.label?.let { "“$it”" },
    when {
        entry.kind == SaveKind.SAVE_STATE && entry.slotNumber != null && entry.label == null -> "Save state · Slot ${entry.slotNumber}"
        else -> entry.kindLabel
    },
    saveWhereWhen(entry),
).joinToString(" · ")

/**
 * A game's save row detail. Save states lead with "Manual"; automatic resume points with why
 * they were captured ("Left game", "Paused", …); then day, time, device and sync.
 */
fun gameSaveDetail(entry: SaveEntry): String = listOfNotNull(
    if (entry.kind == SaveKind.GAME_SAVE) null else SavedProgressRules.reasonLabel(entry.captureReason),
    formatDayLabel(entry.capturedAtEpochMs),
    saveWhereWhen(entry),
    if (!entry.loadable && entry.kind != SaveKind.GAME_SAVE) "Not on this device" else null,
).joinToString(" · ")

/** Trailing rename button for a save-state row. */
@Composable
fun RenameSaveButton(label: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val theme = RommioTheme.current
    IconButton(onClick = onClick, modifier = modifier.size(48.dp)) {
        Icon(
            Icons.Outlined.Edit,
            contentDescription = if (label == null) "Name this save state" else "Rename $label",
            tint = theme.colors.inkSecondary,
        )
    }
}

/**
 * Names a save state. Blank removes the name. [tagPrefix] keeps each surface's selectors apart
 * (e.g. `player.states` in the player, `game.saves` on the Game page).
 */
@Composable
fun RenameSaveDialog(
    current: String?,
    tagPrefix: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember(current) { mutableStateOf(current.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (current == null) "Name this save state" else "Rename save state") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(SavedProgressRules.MAX_LABEL_LENGTH) },
                singleLine = true,
                label = { Text("Name") },
                placeholder = { Text("Before the boss") },
                supportingText = { Text("Only on this device. Leave empty to remove the name.") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSave(text) }),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("$tagPrefix.renameField"),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }, modifier = Modifier.testTag("$tagPrefix.renameSave")) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
