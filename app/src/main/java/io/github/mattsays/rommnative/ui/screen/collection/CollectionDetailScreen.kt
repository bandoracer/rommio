package io.github.mattsays.rommnative.ui.screen.collection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.mattsays.rommnative.AppContainer
import io.github.mattsays.rommnative.model.RomDto
import io.github.mattsays.rommnative.ui.component.EmptyStatePanel
import io.github.mattsays.rommnative.ui.component.FeaturePanel
import io.github.mattsays.rommnative.ui.component.LoadingSkeletonPanel
import io.github.mattsays.rommnative.ui.component.RomPosterCard
import io.github.mattsays.rommnative.ui.component.SectionHeader
import io.github.mattsays.rommnative.ui.screen.collectAsStateWithLifecycleCompat
import java.net.URLDecoder

@Composable
fun CollectionDetailScreen(
    container: AppContainer,
    kind: String,
    collectionId: String,
    collectionName: String,
    imageBaseUrl: String?,
    onRomSelected: (RomDto) -> Unit,
) {
    val viewModel: CollectionDetailViewModel = viewModel(
        key = "collection-$kind-$collectionId",
        factory = viewModelFactory {
            initializer { CollectionDetailViewModel(container.repository, kind, collectionId) }
        },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycleCompat()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            FeaturePanel(
                title = state.collection?.name ?: URLDecoder.decode(collectionName, Charsets.UTF_8.name()),
                subtitle = state.collection?.description?.takeIf { it.isNotBlank() }
                    ?: "Collection view powered directly by the connected RomM server with denser poster browsing.",
                badge = when {
                    state.collection?.isVirtual == true -> "Auto-generated"
                    state.collection?.isSmart == true -> "Smart collection"
                    else -> "Collection"
                },
                eyebrow = "Collection",
            ) {
                Text(
                    "${state.supportedRoms.size} supported • ${state.unsupportedRoms.size} not supported in app yet",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (state.isLoading) {
            item { LoadingSkeletonPanel(showArtwork = true, lines = 4) }
        }
        if (state.supportedRoms.isNotEmpty()) {
            item {
                SectionHeader(
                    title = "Playable in app",
                    meta = state.supportedRoms.size.toString(),
                    supportingText = "These entries are compatible with the embedded player once the right file is installed locally.",
                )
            }
        }
        items(state.supportedRoms.chunked(2), key = { chunk -> chunk.joinToString("-") { it.id.toString() } }) { row ->
            CollectionRomGridRow(
                roms = row,
                imageBaseUrl = imageBaseUrl,
                unsupported = false,
                onRomSelected = onRomSelected,
            )
        }
        if (state.unsupportedRoms.isNotEmpty()) {
            item {
                SectionHeader(
                    title = "Not supported in app yet",
                    meta = state.unsupportedRoms.size.toString(),
                    supportingText = "These collection entries stay visible for browsing and download, but the embedded player does not support them yet.",
                )
            }
        }
        items(state.unsupportedRoms.chunked(2), key = { chunk -> "unsupported-" + chunk.joinToString("-") { it.id.toString() } }) { row ->
            CollectionRomGridRow(
                roms = row,
                imageBaseUrl = imageBaseUrl,
                unsupported = true,
                onRomSelected = onRomSelected,
            )
        }
        if (!state.isLoading && state.supportedRoms.isEmpty() && state.unsupportedRoms.isEmpty() && state.errorMessage == null) {
            item {
                EmptyStatePanel(
                    title = "Nothing here yet",
                    subtitle = "This collection is available, but it does not currently expose any ROMs to the app.",
                    badge = "Empty",
                    supportingText = "Add games on the server or pick a populated collection to browse artwork, download actions, and playable titles here.",
                )
            }
        }
        state.errorMessage?.let { message ->
            item { Text(message, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun CollectionRomGridRow(
    roms: List<RomDto>,
    imageBaseUrl: String?,
    unsupported: Boolean,
    onRomSelected: (RomDto) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        roms.forEach { rom ->
            RomPosterCard(
                rom = rom,
                imageBaseUrl = imageBaseUrl,
                installed = false,
                unsupported = unsupported,
                onClick = { onRomSelected(rom) },
                modifier = Modifier.weight(1f),
            )
        }
        if (roms.size == 1) {
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.weight(1f))
        }
    }
}
