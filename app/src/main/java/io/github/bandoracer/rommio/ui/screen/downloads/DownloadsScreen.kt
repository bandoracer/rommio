package io.github.bandoracer.rommio.ui.screen.downloads

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.bandoracer.rommio.AppContainer
import io.github.bandoracer.rommio.model.DownloadRecord
import io.github.bandoracer.rommio.model.DownloadStatus
import io.github.bandoracer.rommio.ui.component.CompactPanel
import io.github.bandoracer.rommio.ui.component.EmptyStatePanel
import io.github.bandoracer.rommio.ui.component.FeaturePanel
import io.github.bandoracer.rommio.ui.component.MetricTile
import io.github.bandoracer.rommio.ui.component.formatBytes
import io.github.bandoracer.rommio.ui.screen.collectAsStateWithLifecycleCompat

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DownloadsScreen(
    container: AppContainer,
) {
    val viewModel: DownloadsViewModel = viewModel(
        factory = viewModelFactory {
            initializer { DownloadsViewModel(container.repository) }
        },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycleCompat()
    val queuedCount = state.records.count { it.status == DownloadStatus.QUEUED }
    val runningCount = state.records.count { it.status == DownloadStatus.RUNNING }
    val failedCount = state.records.count { it.status == DownloadStatus.FAILED || it.status == DownloadStatus.CANCELED }
    val completedCount = state.records.count { it.status == DownloadStatus.COMPLETED }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            FeaturePanel(
                title = "Download manager",
                subtitle = "Queued, active, failed, canceled, and completed transfers are tracked here across app launches with sequential execution.",
                badge = "${state.records.size} tracked",
                eyebrow = "Downloads",
            ) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    maxItemsInEachRow = 2,
                ) {
                    MetricTile(
                        label = "Running",
                        value = runningCount.toString(),
                        icon = Icons.Outlined.Sync,
                        modifier = Modifier.fillMaxWidth(0.48f),
                    )
                    MetricTile(
                        label = "Queued",
                        value = queuedCount.toString(),
                        icon = Icons.Outlined.Schedule,
                        modifier = Modifier.fillMaxWidth(0.48f),
                    )
                    MetricTile(
                        label = "Attention",
                        value = failedCount.toString(),
                        icon = Icons.Outlined.WarningAmber,
                        modifier = Modifier.fillMaxWidth(0.48f),
                    )
                    MetricTile(
                        label = "Completed",
                        value = completedCount.toString(),
                        icon = Icons.Outlined.CheckCircle,
                        modifier = Modifier.fillMaxWidth(0.48f),
                    )
                }
            }
        }
        if (state.records.isEmpty()) {
            item {
                EmptyStatePanel(
                    title = "No downloads yet",
                    subtitle = "Queue a game from its detail screen to start building a download history here.",
                    badge = "Empty queue",
                    supportingText = "Once you queue a ROM, this screen will show progress, retries, failures, and completed local copies in one place.",
                )
            }
        }
        items(state.records, key = { "${it.romId}-${it.fileId}" }) { record ->
            DownloadRecordCard(
                record = record,
                onCancel = { viewModel.cancel(record) },
                onDownloadNow = { viewModel.downloadNow(record) },
                onRetry = { viewModel.retry(record) },
                onDeleteLocal = { viewModel.deleteLocal(record) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DownloadRecordCard(
    record: DownloadRecord,
    onCancel: () -> Unit,
    onDownloadNow: () -> Unit,
    onRetry: () -> Unit,
    onDeleteLocal: () -> Unit,
) {
    CompactPanel(
        title = record.romName,
        subtitle = "${record.fileName} • ${formatBytes(record.fileSizeBytes)}",
        badge = record.status.name.lowercase().replaceFirstChar(Char::uppercase),
        eyebrow = "Transfer",
    ) {
        if (record.status == DownloadStatus.RUNNING || record.status == DownloadStatus.QUEUED) {
            LinearProgressIndicator(
                progress = { (record.progressPercent / 100f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "${record.progressPercent}% • ${formatBytes(record.bytesDownloaded)} of ${formatBytes(record.totalBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        record.lastError?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.error)
        }
        if (record.localPath != null && record.status == DownloadStatus.COMPLETED) {
            Text(
                text = "Local copy available",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (record.status == DownloadStatus.RUNNING || record.status == DownloadStatus.QUEUED) {
                DownloadActionChip(label = "Cancel", onClick = onCancel)
            }
            if (record.status == DownloadStatus.QUEUED || record.status == DownloadStatus.FAILED || record.status == DownloadStatus.CANCELED) {
                DownloadActionChip(label = "Download now", onClick = onDownloadNow)
            }
            if (record.status == DownloadStatus.FAILED || record.status == DownloadStatus.CANCELED) {
                DownloadActionChip(label = "Retry", onClick = onRetry)
            }
            if (record.localPath != null) {
                DownloadActionChip(label = "Delete local", onClick = onDeleteLocal)
            }
        }
    }
}

@Composable
private fun DownloadActionChip(
    label: String,
    onClick: () -> Unit,
) {
    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            labelColor = MaterialTheme.colorScheme.onSurface,
        ),
        leadingIcon = {
            androidx.compose.material3.Icon(
                imageVector = Icons.Outlined.Download,
                contentDescription = null,
            )
        },
    )
}
