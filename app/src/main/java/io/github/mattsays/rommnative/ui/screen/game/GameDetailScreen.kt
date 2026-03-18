package io.github.mattsays.rommnative.ui.screen.game

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import io.github.mattsays.rommnative.AppContainer
import io.github.mattsays.rommnative.domain.player.PlayerCapability
import io.github.mattsays.rommnative.model.DownloadStatus
import io.github.mattsays.rommnative.model.RomFileDto
import io.github.mattsays.rommnative.ui.component.CompactPanel
import io.github.mattsays.rommnative.ui.component.FeaturePanel
import io.github.mattsays.rommnative.ui.component.LoadingSkeletonPanel
import io.github.mattsays.rommnative.ui.component.SectionHeader
import io.github.mattsays.rommnative.ui.component.formatBytes
import io.github.mattsays.rommnative.ui.screen.collectAsStateWithLifecycleCompat
import io.github.mattsays.rommnative.ui.theme.BrandPanel
import io.github.mattsays.rommnative.ui.theme.BrandPanelAlt
import io.github.mattsays.rommnative.ui.theme.BrandSeed
import io.github.mattsays.rommnative.ui.theme.BrandText
import io.github.mattsays.rommnative.util.resolveRemoteAssetUrl

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GameDetailScreen(
    container: AppContainer,
    romId: Int,
    onBack: () -> Unit,
    onPlay: (romId: Int, fileId: Int) -> Unit,
) {
    val viewModel: GameDetailViewModel = viewModel(
        key = "game-$romId",
        factory = viewModelFactory {
            initializer { GameDetailViewModel(container.repository, romId) }
        },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycleCompat()
    val rom = state.rom
    val selectedFile = viewModel.selectedFile()
    val selectedInstall = viewModel.selectedInstall()
    val support = state.support
    val baseUrl = container.repository.activeProfileFlow().collectAsStateWithLifecycle(initialValue = null).value?.baseUrl

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (state.isLoading) {
            item { LoadingSkeletonPanel(showArtwork = true, lines = 5) }
        }
        rom?.let { game ->
            item {
                FeaturePanel(
                    title = game.displayName,
                    subtitle = game.platformName,
                    badge = if (selectedInstall != null) "Installed" else "Remote only",
                    eyebrow = "Game",
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        AsyncImage(
                            model = resolveRemoteAssetUrl(baseUrl, game.urlCover),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .width(124.dp)
                                .clip(MaterialTheme.shapes.extraLarge),
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            support?.message?.let { message ->
                                Text(
                                    text = message,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            game.summary?.takeIf { it.isNotBlank() }?.let { summary ->
                                Text(
                                    text = summary,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 6,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                text = "${game.files.size} files available",
                                style = MaterialTheme.typography.labelMedium,
                                color = BrandSeed,
                            )
                        }
                    }
                }
            }
            item {
                CompactPanel(
                    title = "Action deck",
                    subtitle = support?.message ?: "Choose the next action for this title.",
                    badge = support?.capability?.name?.replace('_', ' '),
                    eyebrow = "Play state",
                ) {
                    support?.runtimeProfile?.let { profile ->
                        Text(
                            "Recommended core: ${profile.displayName}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    state.downloadRecord?.let { record ->
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
                        } else {
                            Text(
                                "Download status: ${record.status.name.lowercase().replaceFirstChar(Char::uppercase)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        record.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                    if (state.actionPresentation.secondary.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            state.actionPresentation.secondary.forEach { action ->
                                AssistChip(
                                    onClick = { handleAction(action.kind, viewModel, romId, selectedFile?.id, onPlay) },
                                    enabled = action.enabled,
                                    label = { Text(action.label) },
                                    colors = AssistChipDefaults.assistChipColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                        labelColor = MaterialTheme.colorScheme.onSurface,
                                    ),
                                )
                            }
                        }
                    }
                    state.actionPresentation.primary?.let { action ->
                        Button(
                            onClick = { handleAction(action.kind, viewModel, romId, selectedFile?.id, onPlay) },
                            enabled = action.enabled,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(action.label)
                        }
                    }
                    state.coreMessage?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    state.syncMessage?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            item {
                SectionHeader(
                    title = "Files",
                    meta = game.files.size.toString(),
                    supportingText = "Select which file should drive downloads, play, and runtime checks.",
                )
            }
            items(game.files, key = { it.id }) { file ->
                FileRowCard(
                    file = file,
                    selected = file.id == selectedFile?.id,
                    installed = state.installedFiles.any { it.fileId == file.id },
                    onClick = { viewModel.selectFile(file.id) },
                )
            }
        }
        state.errorMessage?.let { message ->
            item { Text(message, color = MaterialTheme.colorScheme.error) }
        }
    }
}

private fun handleAction(
    kind: GameDetailActionKind,
    viewModel: GameDetailViewModel,
    romId: Int,
    selectedFileId: Int?,
    onPlay: (romId: Int, fileId: Int) -> Unit,
) {
    when (kind) {
        GameDetailActionKind.PLAY -> if (selectedFileId != null) onPlay(romId, selectedFileId)
        GameDetailActionKind.DOWNLOAD_NOW -> viewModel.downloadNow()
        GameDetailActionKind.DOWNLOAD_CORE -> viewModel.downloadRecommendedCore()
        GameDetailActionKind.QUEUE -> viewModel.enqueueDownload()
        GameDetailActionKind.CANCEL -> viewModel.cancelDownload()
        GameDetailActionKind.RETRY -> viewModel.retryDownload()
        GameDetailActionKind.SYNC -> viewModel.syncNow()
    }
}

@Composable
private fun FileRowCard(
    file: RomFileDto,
    selected: Boolean,
    installed: Boolean,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) BrandPanelAlt.copy(alpha = 0.96f) else BrandPanel.copy(alpha = 0.92f),
            contentColor = BrandText,
        ),
        border = when {
            selected -> BorderStroke(1.dp, BrandSeed.copy(alpha = 0.7f))
            installed -> BorderStroke(1.dp, BrandSeed.copy(alpha = 0.3f))
            else -> null
        },
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    file.fileName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${file.effectiveFileExtension.ifBlank { "file" }.uppercase()} • ${formatBytes(file.fileSizeBytes)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                text = when {
                    selected && installed -> "Selected + local"
                    selected -> "Selected"
                    installed -> "Local"
                    else -> "Remote"
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (selected || installed) BrandSeed else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
