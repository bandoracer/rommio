package io.github.mattsays.rommnative.ui.screen.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Sync
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
import io.github.mattsays.rommnative.AppContainer
import io.github.mattsays.rommnative.model.RomDto
import io.github.mattsays.rommnative.model.RommCollectionDto
import io.github.mattsays.rommnative.ui.component.CollectionSpotlightCard
import io.github.mattsays.rommnative.ui.component.CompactPanel
import io.github.mattsays.rommnative.ui.component.EmptyStatePanel
import io.github.mattsays.rommnative.ui.component.FeaturePanel
import io.github.mattsays.rommnative.ui.component.LoadingSkeletonPanel
import io.github.mattsays.rommnative.ui.component.MetricTile
import io.github.mattsays.rommnative.ui.component.QuickActionTile
import io.github.mattsays.rommnative.ui.component.RomPosterCard
import io.github.mattsays.rommnative.ui.component.SectionHeader
import io.github.mattsays.rommnative.ui.component.formatBytes
import io.github.mattsays.rommnative.ui.screen.collectAsStateWithLifecycleCompat

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    container: AppContainer,
    imageBaseUrl: String?,
    onRomSelected: (RomDto) -> Unit,
    onCollectionSelected: (RommCollectionDto) -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenDownloads: () -> Unit,
) {
    val viewModel: HomeViewModel = viewModel(
        factory = viewModelFactory {
            initializer { HomeViewModel(container.repository) }
        },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycleCompat()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            val queuedCount = state.activeDownloads.count { it.status == io.github.mattsays.rommnative.model.DownloadStatus.QUEUED }
            val runningCount = state.activeDownloads.count { it.status == io.github.mattsays.rommnative.model.DownloadStatus.RUNNING }
            val failedCount = state.activeDownloads.count { it.status == io.github.mattsays.rommnative.model.DownloadStatus.FAILED }
            FeaturePanel(
                title = "Welcome back",
                subtitle = "Jump into the library, monitor transfers, and surface what is ready to play.",
                badge = "Console view",
                eyebrow = "Home",
            ) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    maxItemsInEachRow = 2,
                ) {
                    MetricTile(
                        label = "Installed",
                        value = state.storageSummary.installedGameCount.toString(),
                        icon = Icons.Outlined.Apps,
                        modifier = Modifier.fillMaxWidth(0.48f),
                    )
                    MetricTile(
                        label = "Storage",
                        value = formatBytes(state.storageSummary.totalBytes),
                        icon = Icons.Outlined.Storage,
                        modifier = Modifier.fillMaxWidth(0.48f),
                    )
                    MetricTile(
                        label = "Queue",
                        value = (queuedCount + runningCount).toString(),
                        icon = Icons.Outlined.Sync,
                        modifier = Modifier.fillMaxWidth(0.48f),
                    )
                    MetricTile(
                        label = "Attention",
                        value = failedCount.toString(),
                        icon = Icons.Outlined.Download,
                        modifier = Modifier.fillMaxWidth(0.48f),
                    )
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    maxItemsInEachRow = 2,
                ) {
                    QuickActionTile(
                        title = "Library",
                        icon = Icons.Outlined.FolderOpen,
                        onClick = onOpenLibrary,
                        modifier = Modifier.fillMaxWidth(0.48f),
                    )
                    QuickActionTile(
                        title = "Queue",
                        icon = Icons.Outlined.Download,
                        onClick = onOpenDownloads,
                        modifier = Modifier.fillMaxWidth(0.48f),
                    )
                }
            }
        }
        if (state.isLoading) {
            item { LoadingSkeletonPanel(showArtwork = true, lines = 4) }
        }
        if (state.continuePlaying.isNotEmpty()) {
            item {
                SectionHeader(title = "Continue playing", meta = state.continuePlaying.size.toString())
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(state.continuePlaying, key = { it.id }) { rom ->
                        RomPosterCard(
                            rom = rom,
                            imageBaseUrl = imageBaseUrl,
                            installed = true,
                            onClick = { onRomSelected(rom) },
                            modifier = Modifier.width(216.dp),
                        )
                    }
                }
            }
        }
        if (state.featuredCollections.isNotEmpty()) {
            item {
                SectionHeader(title = "Collection highlights", meta = state.featuredCollections.size.toString())
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(state.featuredCollections, key = { "${it.kind}:${it.id}" }) { collection ->
                        CollectionSpotlightCard(
                            collection = collection,
                            imageBaseUrl = imageBaseUrl,
                            fallbackCoverUrls = state.collectionPreviewCoverUrls["${collection.kind}:${collection.id}"].orEmpty(),
                            onClick = { onCollectionSelected(collection) },
                            modifier = Modifier.width(260.dp),
                        )
                    }
                }
            }
        }
        if (state.recentRoms.isNotEmpty()) {
            item {
                SectionHeader(title = "Recently added", meta = state.recentRoms.size.toString())
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(state.recentRoms, key = { it.id }) { rom ->
                        RomPosterCard(
                            rom = rom,
                            imageBaseUrl = imageBaseUrl,
                            installed = false,
                            onClick = { onRomSelected(rom) },
                            modifier = Modifier.width(216.dp),
                        )
                    }
                }
            }
        }
        if (state.activeDownloads.isNotEmpty()) {
            item {
                CompactPanel(
                    title = "Transfer queue",
                    subtitle = "Running, queued, and failed downloads stay visible here and in the dedicated manager.",
                    badge = "${state.activeDownloads.size} active",
                    eyebrow = "Downloads",
                ) {
                    state.activeDownloads.take(3).forEach { record ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = record.romName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                            )
                            Text(
                                text = record.status.name.lowercase().replaceFirstChar(Char::uppercase),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        if (!state.isLoading &&
            state.continuePlaying.isEmpty() &&
            state.featuredCollections.isEmpty() &&
            state.recentRoms.isEmpty() &&
            state.errorMessage == null
        ) {
            item {
                EmptyStatePanel(
                    title = "Your dashboard is clear",
                    subtitle = "As you install games, queue downloads, and build out collections, the home dashboard will surface the next best actions here.",
                    badge = "Fresh start",
                    actionLabel = "Browse library",
                    onAction = onOpenLibrary,
                )
            }
        }
        state.errorMessage?.let { message ->
            item {
                Text(message, color = MaterialTheme.colorScheme.error)
            }
        }
        item {
            Box(modifier = Modifier.height(8.dp))
        }
    }
}
