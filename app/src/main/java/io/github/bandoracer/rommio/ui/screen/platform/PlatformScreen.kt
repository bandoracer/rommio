package io.github.bandoracer.rommio.ui.screen.platform

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.bandoracer.rommio.AppContainer
import io.github.bandoracer.rommio.domain.player.EmbeddedSupportTier
import io.github.bandoracer.rommio.model.RomDto
import io.github.bandoracer.rommio.ui.component.EmptyStatePanel
import io.github.bandoracer.rommio.ui.component.FeaturePanel
import io.github.bandoracer.rommio.ui.component.LoadingSkeletonPanel
import io.github.bandoracer.rommio.ui.component.RomPosterCard
import io.github.bandoracer.rommio.ui.component.SectionHeader
import io.github.bandoracer.rommio.ui.screen.collectAsStateWithLifecycleCompat
import java.net.URLDecoder

@Composable
fun PlatformScreen(
    container: AppContainer,
    platformId: Int,
    platformName: String,
    imageBaseUrl: String?,
    onBack: () -> Unit,
    onRomSelected: (RomDto) -> Unit,
) {
    val viewModel: PlatformViewModel = viewModel(
        key = "platform-$platformId",
        factory = viewModelFactory {
            initializer { PlatformViewModel(container.repository, platformId) }
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
                title = URLDecoder.decode(platformName, Charsets.UTF_8.name()),
                subtitle = "Browse the full platform catalog with explicit touch, controller, and unsupported embedded-player bands.",
                badge = "${state.touchSupportedRoms.size + state.controllerSupportedRoms.size + state.unsupportedRoms.size} games",
                eyebrow = "Platform",
            ) {
                Text(
                    "${state.touchSupportedRoms.size} touch • ${state.controllerSupportedRoms.size} controller • ${state.unsupportedRoms.size} unsupported",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (state.isLoading) {
            item { LoadingSkeletonPanel(showArtwork = true, lines = 4) }
        }
        if (state.touchSupportedRoms.isEmpty() && state.controllerSupportedRoms.isEmpty() && state.unsupportedRoms.isEmpty() && state.errorMessage == null) {
            item {
                EmptyStatePanel(
                    title = "No games surfaced yet",
                    subtitle = "This platform is visible in RomM, but it does not currently expose any ROMs to the Android client.",
                    badge = "Empty platform",
                    supportingText = "Add titles on the server or choose a populated platform to browse artwork, download actions, and embedded-player support.",
                )
            }
        }
        if (state.touchSupportedRoms.isNotEmpty()) {
            item {
                SectionHeader(
                    title = "Touch-ready in app",
                    meta = state.touchSupportedRoms.size.toString(),
                    supportingText = "These games already have embedded runtime support plus touch-friendly controls.",
                )
            }
        }
        items(state.touchSupportedRoms.chunked(2), key = { chunk -> chunk.joinToString("-") { it.id.toString() } }) { row ->
            RomGridRow(
                roms = row,
                imageBaseUrl = imageBaseUrl,
                supportTier = EmbeddedSupportTier.TOUCH_SUPPORTED,
                onRomSelected = onRomSelected,
            )
        }
        if (state.controllerSupportedRoms.isNotEmpty()) {
            item {
                SectionHeader(
                    title = "Controller play in app",
                    meta = state.controllerSupportedRoms.size.toString(),
                    supportingText = "These games can run in the embedded player, but they still rely on a controller-first setup.",
                )
            }
        }
        items(state.controllerSupportedRoms.chunked(2), key = { chunk -> "controller-" + chunk.joinToString("-") { it.id.toString() } }) { row ->
            RomGridRow(
                roms = row,
                imageBaseUrl = imageBaseUrl,
                supportTier = EmbeddedSupportTier.CONTROLLER_SUPPORTED,
                onRomSelected = onRomSelected,
            )
        }
        if (state.unsupportedRoms.isNotEmpty()) {
            item {
                SectionHeader(
                    title = "Not supported in app yet",
                    meta = state.unsupportedRoms.size.toString(),
                    supportingText = "These games stay browsable for downloads and catalog review, but they cannot be launched inside the embedded player yet.",
                )
            }
        }
        items(state.unsupportedRoms.chunked(2), key = { chunk -> "unsupported-" + chunk.joinToString("-") { it.id.toString() } }) { row ->
            RomGridRow(
                roms = row,
                imageBaseUrl = imageBaseUrl,
                supportTier = EmbeddedSupportTier.UNSUPPORTED,
                onRomSelected = onRomSelected,
            )
        }
        state.errorMessage?.let { message ->
            item {
                Text(message, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun RomGridRow(
    roms: List<RomDto>,
    imageBaseUrl: String?,
    supportTier: EmbeddedSupportTier,
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
                supportTier = supportTier,
                onClick = { onRomSelected(rom) },
                modifier = Modifier.weight(1f),
            )
        }
        if (roms.size == 1) {
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.weight(1f))
        }
    }
}
