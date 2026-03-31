package io.github.bandoracer.rommio.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.bandoracer.rommio.model.BrowsableGameState
import io.github.bandoracer.rommio.model.BrowsableGameStateOrigin
import io.github.bandoracer.rommio.model.GameStateDeletePolicy
import io.github.bandoracer.rommio.model.ResumeStateSourceOrigin
import io.github.bandoracer.rommio.model.ResumeStateStatusKind
import io.github.bandoracer.rommio.model.ResumeStateSummary
import java.text.DateFormat
import java.util.Date

@Composable
fun StateBrowserContent(
    resume: ResumeStateSummary?,
    saveSlots: List<BrowsableGameState>,
    snapshots: List<BrowsableGameState>,
    onUseResume: (() -> Unit)?,
    onLoadState: (BrowsableGameState) -> Unit,
    onDeleteState: ((BrowsableGameState) -> Unit)?,
    onClose: (() -> Unit)? = null,
    closeLabel: String = "Done",
    modifier: Modifier = Modifier,
) {
    var pendingDelete by remember { mutableStateOf<BrowsableGameState?>(null) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Browse states", style = MaterialTheme.typography.headlineSmall)
        Text(
            "These are emulator states. In-game saves stay inside the game.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ResumeStateCard(
            resume = resume,
            onUseResume = onUseResume,
        )

        StateSection(
            title = "Save slots",
            entries = saveSlots,
            emptyMessage = "No save slots available.",
            onLoadState = onLoadState,
            onDeleteState = onDeleteState?.let { _ ->
                { entry -> pendingDelete = entry }
            },
        )

        StateSection(
            title = "Snapshots",
            entries = snapshots,
            emptyMessage = "No snapshots available.",
            onLoadState = onLoadState,
            onDeleteState = null,
        )

        onClose?.let { close ->
            Button(
                onClick = close,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(closeLabel)
            }
        }
    }

    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete ${entry.label}?") },
            text = {
                Text(
                    when (entry.deletePolicy) {
                        GameStateDeletePolicy.LOCAL_AND_REMOTE ->
                            "This removes the state from this device and queues the slot for remote deletion."
                        GameStateDeletePolicy.LOCAL_ONLY ->
                            "This removes the local imported copy from this device."
                        GameStateDeletePolicy.NONE ->
                            "${entry.label} cannot be deleted."
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteState?.invoke(entry)
                        pendingDelete = null
                    },
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun ResumeStateCard(
    resume: ResumeStateSummary?,
    onUseResume: (() -> Unit)?,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Resume", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                resume?.primaryStatusMessage ?: "No resume state yet.",
                style = MaterialTheme.typography.bodyMedium,
            )
            resume?.sourceDeviceName
                ?.takeIf { it.isNotBlank() && resume.sourceOrigin == ResumeStateSourceOrigin.REMOTE_DEVICE }
                ?.let { source ->
                    Text(
                        "From $source",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            resume?.updatedAtEpochMs?.let { updatedAt ->
                Text(
                    "Updated ${formatStateTimestamp(updatedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (resume?.available == true && onUseResume != null) {
                OutlinedButton(onClick = onUseResume) {
                    Text(
                        when (resume.statusKind) {
                            ResumeStateStatusKind.SYNCED_REMOTE_SOURCE,
                            ResumeStateStatusKind.CLOUD_AVAILABLE -> "Use resume"
                            else -> "Play from resume"
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun StateSection(
    title: String,
    entries: List<BrowsableGameState>,
    emptyMessage: String,
    onLoadState: (BrowsableGameState) -> Unit,
    onDeleteState: ((BrowsableGameState) -> Unit)?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (entries.isEmpty()) {
            Text(
                emptyMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            entries.forEach { entry ->
                StateEntryCard(
                    entry = entry,
                    onLoadState = { onLoadState(entry) },
                    onDeleteState = onDeleteState?.takeIf { entry.deletePolicy != GameStateDeletePolicy.NONE }?.let { callback ->
                        { callback(entry) }
                    },
                )
            }
        }
    }
}

@Composable
private fun StateEntryCard(
    entry: BrowsableGameState,
    onLoadState: () -> Unit,
    onDeleteState: (() -> Unit)?,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(entry.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                stateSubtitle(entry),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            entry.sourceDeviceName?.takeIf { it.isNotBlank() }?.let { source ->
                Text(
                    "From $source",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onLoadState) {
                    Text("Load")
                }
                onDeleteState?.let { deleteState ->
                    TextButton(onClick = deleteState) {
                        Text("Delete")
                    }
                }
            }
        }
    }
}

private fun stateSubtitle(entry: BrowsableGameState): String {
    val timestamp = formatStateTimestamp(entry.updatedAtEpochMs)
    val prefix = when (entry.originType) {
        BrowsableGameStateOrigin.MANUAL_SLOT -> "Save slot"
        BrowsableGameStateOrigin.IMPORTED_PLAYABLE -> "Imported cloud"
        BrowsableGameStateOrigin.AUTO_SNAPSHOT -> "Snapshot"
    }
    return "$prefix • $timestamp"
}

private fun formatStateTimestamp(epochMs: Long): String {
    return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(epochMs))
}
