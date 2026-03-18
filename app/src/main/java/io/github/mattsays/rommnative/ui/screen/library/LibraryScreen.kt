package io.github.mattsays.rommnative.ui.screen.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import io.github.mattsays.rommnative.domain.player.EmbeddedSupportTier
import io.github.mattsays.rommnative.model.PlatformDto
import io.github.mattsays.rommnative.model.RomDto
import io.github.mattsays.rommnative.ui.component.LoadingSkeletonPanel
import io.github.mattsays.rommnative.ui.component.PlatformSpotlightCard
import io.github.mattsays.rommnative.ui.component.RomPosterCard
import io.github.mattsays.rommnative.ui.component.SectionHeader
import io.github.mattsays.rommnative.ui.screen.collectAsStateWithLifecycleCompat

@Composable
fun LibraryScreen(
    container: AppContainer,
    imageBaseUrl: String?,
    onPlatformSelected: (PlatformDto) -> Unit,
    onRomSelected: (RomDto) -> Unit,
) {
    val viewModel: LibraryViewModel = viewModel(
        factory = viewModelFactory {
            initializer { LibraryViewModel(container.repository) }
        },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycleCompat()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (state.recentInstalled.isNotEmpty()) {
            item {
                SectionHeader(title = "Installed recently", meta = state.recentInstalled.size.toString())
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(state.recentInstalled, key = { it.id }) { rom ->
                        RomPosterCard(
                            rom = rom,
                            imageBaseUrl = imageBaseUrl,
                            installed = true,
                            supportTier = state.recentInstalledSupport[rom.id] ?: EmbeddedSupportTier.UNSUPPORTED,
                            onClick = { onRomSelected(rom) },
                            modifier = Modifier.width(216.dp),
                        )
                    }
                }
            }
        }

        if (state.isLoading) {
            item { LoadingSkeletonPanel(lines = 4) }
        }

        if (state.touchSupportedPlatforms.isNotEmpty()) {
            item {
                SectionHeader(
                    title = "Touch-ready in app",
                    meta = state.touchSupportedPlatforms.size.toString(),
                    supportingText = "These platforms already have embedded runtimes and first-class mobile control layouts.",
                )
            }
            items(state.touchSupportedPlatforms, key = { it.id }) { platform ->
                PlatformSpotlightCard(
                    platform = platform,
                    imageBaseUrl = imageBaseUrl,
                    summary = state.installedPlatformSummaries.firstOrNull { it.platformSlug == platform.slug || it.platformSlug == platform.fsSlug },
                    supportTier = EmbeddedSupportTier.TOUCH_SUPPORTED,
                    onClick = { onPlatformSelected(platform) },
                )
            }
        }

        if (state.controllerSupportedPlatforms.isNotEmpty()) {
            item {
                SectionHeader(
                    title = "Controller play in app",
                    meta = state.controllerSupportedPlatforms.size.toString(),
                    supportingText = "These platforms now have embedded runtimes, but they still need a controller-first validation and touch pass.",
                )
            }
            items(state.controllerSupportedPlatforms, key = { it.id }) { platform ->
                PlatformSpotlightCard(
                    platform = platform,
                    imageBaseUrl = imageBaseUrl,
                    summary = state.installedPlatformSummaries.firstOrNull { it.platformSlug == platform.slug || it.platformSlug == platform.fsSlug },
                    supportTier = EmbeddedSupportTier.CONTROLLER_SUPPORTED,
                    onClick = { onPlatformSelected(platform) },
                )
            }
        }

        if (state.unsupportedPlatforms.isNotEmpty()) {
            item {
                SectionHeader(
                    title = "Not supported in app yet",
                    meta = state.unsupportedPlatforms.size.toString(),
                    supportingText = "These platforms stay browsable in Rommio, but the embedded player does not support them yet.",
                )
            }
            items(state.unsupportedPlatforms, key = { it.id }) { platform ->
                PlatformSpotlightCard(
                    platform = platform,
                    imageBaseUrl = imageBaseUrl,
                    summary = state.installedPlatformSummaries.firstOrNull { it.platformSlug == platform.slug || it.platformSlug == platform.fsSlug },
                    supportTier = EmbeddedSupportTier.UNSUPPORTED,
                    onClick = { onPlatformSelected(platform) },
                )
            }
        }

        state.errorMessage?.let { message ->
            item {
                Text(message, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
