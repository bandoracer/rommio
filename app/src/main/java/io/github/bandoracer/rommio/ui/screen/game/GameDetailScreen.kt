package io.github.bandoracer.rommio.ui.screen.game

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import io.github.bandoracer.rommio.AppContainer
import io.github.bandoracer.rommio.domain.player.EmbeddedSupportTier
import io.github.bandoracer.rommio.model.GameSyncStatusKind
import io.github.bandoracer.rommio.model.ResumeStateStatusKind
import io.github.bandoracer.rommio.model.RomFileDto
import io.github.bandoracer.rommio.ui.component.CompactPanel
import io.github.bandoracer.rommio.ui.component.EmptyStatePanel
import io.github.bandoracer.rommio.ui.component.FeaturePanel
import io.github.bandoracer.rommio.ui.component.LoadingSkeletonPanel
import io.github.bandoracer.rommio.ui.component.SectionHeader
import io.github.bandoracer.rommio.ui.component.StateBrowserContent
import io.github.bandoracer.rommio.ui.component.SupportBadge
import io.github.bandoracer.rommio.ui.component.formatBytes
import io.github.bandoracer.rommio.ui.screen.collectAsStateWithLifecycleCompat
import io.github.bandoracer.rommio.ui.theme.BrandPanel
import io.github.bandoracer.rommio.ui.theme.BrandPanelAlt
import io.github.bandoracer.rommio.ui.theme.BrandSeed
import io.github.bandoracer.rommio.ui.theme.BrandText
import io.github.bandoracer.rommio.util.resolveRemoteAssetUrl

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
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
    var showStateBrowserSheet by remember { mutableStateOf(false) }

    androidx.compose.foundation.lazy.LazyColumn(
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
                    subtitle = if (state.isLocalOnly) "${game.platformName} • local only" else game.platformName,
                    badge = supportBadgeLabel(state.supportTier),
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
                            SupportBadge(state.supportTier)
                            Text(
                                text = when {
                                    state.isLocalOnly -> "Installed locally • removed from RomM"
                                    selectedInstall != null -> "Installed locally"
                                    else -> "Remote only"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = BrandSeed,
                            )
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
                        if (record.status == io.github.bandoracer.rommio.model.DownloadStatus.RUNNING ||
                            record.status == io.github.bandoracer.rommio.model.DownloadStatus.QUEUED
                        ) {
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
                    state.syncPresentation
                        .takeUnless {
                            it.kind in setOf(GameSyncStatusKind.IDLE, GameSyncStatusKind.SYNCED) &&
                                it.lastSuccessfulSyncAtEpochMs == null
                        }
                        ?.message
                        ?.takeIf { it.isNotBlank() }
                        ?.let { message ->
                            Text(
                                "Sync: $message",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                }
            }
            selectedInstall?.let {
                item {
                    CompactPanel(
                        title = "State recovery",
                        subtitle = "Resume is the default path. Save slots and snapshots stay available in the browser.",
                        eyebrow = "Resume",
                    ) {
                        Text(
                            state.stateRecovery.resume?.primaryStatusMessage ?: "No resume state yet.",
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        resumeDetailLine(state)?.let { detail ->
                            Text(
                                detail,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text(
                            stateCountSummary(
                                saveSlots = state.stateRecovery.saveSlots.size,
                                snapshots = state.stateRecovery.snapshots.size,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (shouldShowResumeProblem(state)) {
                            AssistChip(
                                onClick = viewModel::syncNow,
                                enabled = !state.isLocalOnly,
                                label = { Text("Retry sync") },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    labelColor = MaterialTheme.colorScheme.onSurface,
                                ),
                            )
                        }
                        Button(
                            onClick = { showStateBrowserSheet = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Browse states")
                        }
                    }
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
            item {
                if (rom == null) {
                    EmptyStatePanel(
                        title = "Title unavailable",
                        subtitle = message,
                        badge = "Unavailable",
                        supportingText = "If this game was removed from RomM, any remaining local copy can still be managed from Downloads until it is offloaded.",
                    )
                } else {
                    Text(message, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (showStateBrowserSheet && selectedInstall != null) {
        ModalBottomSheet(
            onDismissRequest = { showStateBrowserSheet = false },
        ) {
            StateBrowserContent(
                resume = state.stateRecovery.resume,
                saveSlots = state.stateRecovery.saveSlots,
                snapshots = state.stateRecovery.snapshots,
                onUseResume = {
                    showStateBrowserSheet = false
                    selectedFile?.id?.let { onPlay(romId, it) }
                },
                onLoadState = { entry ->
                    showStateBrowserSheet = false
                    viewModel.launchFromState(entry, onPlay)
                },
                onDeleteState = viewModel::deleteState,
                onClose = { showStateBrowserSheet = false },
            )
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
        GameDetailActionKind.DELETE_LOCAL -> viewModel.deleteLocal()
        GameDetailActionKind.CANCEL -> viewModel.cancelDownload()
        GameDetailActionKind.RETRY -> viewModel.retryDownload()
        GameDetailActionKind.SYNC -> viewModel.syncNow()
    }
}

private fun supportBadgeLabel(supportTier: EmbeddedSupportTier): String {
    return when (supportTier) {
        EmbeddedSupportTier.TOUCH_SUPPORTED -> "Touch"
        EmbeddedSupportTier.CONTROLLER_SUPPORTED -> "Controller"
        EmbeddedSupportTier.UNSUPPORTED -> "Unsupported"
    }
}

private fun resumeDetailLine(state: GameDetailUiState): String? {
    val resume = state.stateRecovery.resume ?: return null
    return when {
        resume.sourceDeviceName != null && resume.statusKind == ResumeStateStatusKind.SYNCED_REMOTE_SOURCE ->
            "From ${resume.sourceDeviceName}"
        resume.sourceDeviceName != null && resume.statusKind == ResumeStateStatusKind.CLOUD_AVAILABLE ->
            "Cloud resume from ${resume.sourceDeviceName}"
        state.isLocalOnly -> "Cloud sync is unavailable for this local-only install."
        else -> null
    }
}

private fun stateCountSummary(saveSlots: Int, snapshots: Int): String {
    val parts = buildList {
        add(if (saveSlots == 1) "1 save slot" else "$saveSlots save slots")
        add(if (snapshots == 1) "1 snapshot" else "$snapshots snapshots")
    }
    return parts.joinToString(" • ")
}

private fun shouldShowResumeProblem(state: GameDetailUiState): Boolean {
    if (state.isLocalOnly) return false
    val resumeStatus = state.stateRecovery.resume?.statusKind
    return state.syncPresentation.kind == GameSyncStatusKind.OFFLINE_PENDING ||
        resumeStatus in setOf(
            ResumeStateStatusKind.ERROR,
            ResumeStateStatusKind.CONFLICT,
            ResumeStateStatusKind.CLOUD_AVAILABLE,
        )
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
