package io.github.mattsays.rommnative.ui.screen.collections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
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
import io.github.mattsays.rommnative.model.RommCollectionDto
import io.github.mattsays.rommnative.ui.component.CollectionSpotlightCard
import io.github.mattsays.rommnative.ui.component.EmptyStatePanel
import io.github.mattsays.rommnative.ui.component.FeaturePanel
import io.github.mattsays.rommnative.ui.component.LoadingSkeletonPanel
import io.github.mattsays.rommnative.ui.screen.collectAsStateWithLifecycleCompat

@Composable
fun CollectionsScreen(
    container: AppContainer,
    imageBaseUrl: String?,
    onCollectionSelected: (RommCollectionDto) -> Unit,
) {
    val viewModel: CollectionsViewModel = viewModel(
        factory = viewModelFactory {
            initializer { CollectionsViewModel(container.repository) }
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
                title = "RomM collections",
                subtitle = "Manual, smart, and auto-generated collections from the connected server are surfaced here directly with artwork-first cards.",
                badge = "Remote source",
                eyebrow = "Collections",
            ) {
                Text(
                    "If the server does not expose collections to this account, this screen stays empty instead of inventing local stand-ins.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (state.isLoading) {
            items(3) { LoadingSkeletonPanel(showArtwork = true, lines = 2) }
        }
        items(state.collections, key = { "${it.kind}:${it.id}" }) { collection ->
            CollectionSpotlightCard(
                collection = collection,
                imageBaseUrl = imageBaseUrl,
                fallbackCoverUrls = state.collectionPreviewCoverUrls["${collection.kind}:${collection.id}"].orEmpty(),
                onClick = { onCollectionSelected(collection) },
            )
        }
        if (!state.isLoading && state.collections.isEmpty() && state.errorMessage == null) {
            item {
                EmptyStatePanel(
                    title = "No collections available",
                    subtitle = "This account is connected, but the server is not exposing any collections to the Android client right now.",
                    badge = "Remote empty",
                    supportingText = "Create collections on the server or switch to a profile that exposes manual, smart, or generated sets.",
                )
            }
        }
        state.errorMessage?.let { message ->
            item { Text(message, color = MaterialTheme.colorScheme.error) }
        }
    }
}
