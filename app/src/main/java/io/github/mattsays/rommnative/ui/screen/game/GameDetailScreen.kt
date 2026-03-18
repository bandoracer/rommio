package io.github.mattsays.rommnative.ui.screen.game

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import io.github.mattsays.rommnative.AppContainer
import io.github.mattsays.rommnative.domain.player.PlayerCapability
import io.github.mattsays.rommnative.model.RomFileDto
import io.github.mattsays.rommnative.ui.screen.collectAsStateWithLifecycleCompat

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(rom?.displayName ?: "Game") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            rom?.let { game ->
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            AsyncImage(model = game.urlCover, contentDescription = null, modifier = Modifier.fillMaxWidth())
                            Text(game.displayName, style = MaterialTheme.typography.headlineSmall)
                            Text(game.platformName, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            game.summary?.takeIf { it.isNotBlank() }?.let { summary ->
                                Text(summary, modifier = Modifier.padding(top = 12.dp))
                            }
                        }
                    }
                }
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Embedded player status", style = MaterialTheme.typography.titleMedium)
                            Text(support?.message ?: "This game can be launched inside the app.")
                            support?.runtimeProfile?.let { profile ->
                                Text(
                                    "Recommended core: ${profile.displayName}",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Button(onClick = viewModel::enqueueDownload) {
                                    Text(if (selectedInstall != null) "Redownload" else "Download")
                                }
                                if (support?.capability == PlayerCapability.MISSING_CORE && support.runtimeProfile?.download != null) {
                                    Button(
                                        onClick = viewModel::downloadRecommendedCore,
                                        enabled = !state.isDownloadingCore,
                                    ) {
                                        Text(if (state.isDownloadingCore) "Downloading core..." else "Download recommended core")
                                    }
                                }
                                Button(
                                    onClick = { if (selectedFile != null) onPlay(game.id, selectedFile.id) },
                                    enabled = selectedInstall != null && support?.capability == PlayerCapability.READY,
                                ) {
                                    Text("Play")
                                }
                                Button(
                                    onClick = viewModel::syncNow,
                                    enabled = selectedInstall != null && support?.runtimeProfile != null,
                                ) {
                                    Text("Sync now")
                                }
                            }
                            state.coreMessage?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            state.syncMessage?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
                item {
                    Text("Files", style = MaterialTheme.typography.titleMedium)
                }
                items(game.files, key = { it.id }) { file ->
                    FileCard(
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
}

@Composable
private fun FileCard(
    file: RomFileDto,
    selected: Boolean,
    installed: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(file.fileName, style = MaterialTheme.typography.titleSmall)
            Text(
                "${file.effectiveFileExtension.ifBlank { "file" }.uppercase()} • ${file.fileSizeBytes / 1024 / 1024} MB",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                when {
                    selected && installed -> "Selected and installed"
                    selected -> "Selected"
                    installed -> "Installed"
                    else -> "Not installed"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
