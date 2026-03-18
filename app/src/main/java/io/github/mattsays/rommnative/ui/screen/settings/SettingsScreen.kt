package io.github.mattsays.rommnative.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.mattsays.rommnative.AppContainer
import io.github.mattsays.rommnative.model.ServerProfile
import io.github.mattsays.rommnative.ui.component.CompactPanel
import io.github.mattsays.rommnative.ui.component.FeaturePanel
import io.github.mattsays.rommnative.ui.component.MetricTile
import io.github.mattsays.rommnative.ui.component.SectionHeader
import io.github.mattsays.rommnative.ui.component.formatBytes
import io.github.mattsays.rommnative.ui.screen.collectAsStateWithLifecycleCompat
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    container: AppContainer,
    onReconfigureServer: () -> Unit,
    onReauthenticate: () -> Unit,
    onLogout: suspend () -> Unit,
    onActivateProfile: suspend (ServerProfile) -> Unit,
    onActiveProfileDeleted: suspend () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val viewModel: SettingsViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                SettingsViewModel(
                    repository = container.repository,
                    controlsRepository = container.playerControlsRepository,
                    libraryPath = container.libraryStore.rootDirectory().absolutePath,
                )
            }
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
                title = state.user?.username ?: "Connected server",
                subtitle = state.activeProfile?.baseUrl ?: "No active profile",
                badge = "Settings",
                eyebrow = "Account",
            ) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    maxItemsInEachRow = 2,
                ) {
                    MetricTile(
                        label = "Games",
                        value = state.installedGameCount.toString(),
                        icon = Icons.Outlined.Apps,
                        modifier = Modifier.fillMaxWidth(0.48f),
                    )
                    MetricTile(
                        label = "Files",
                        value = state.installedFileCount.toString(),
                        icon = Icons.Outlined.Folder,
                        modifier = Modifier.fillMaxWidth(0.48f),
                    )
                    MetricTile(
                        label = "Storage",
                        value = formatBytes(state.storageBytes),
                        icon = Icons.Outlined.Storage,
                        modifier = Modifier.fillMaxWidth(0.48f),
                    )
                    MetricTile(
                        label = "Profiles",
                        value = state.profiles.size.toString(),
                        icon = Icons.Outlined.Hub,
                        modifier = Modifier.fillMaxWidth(0.48f),
                    )
                }
            }
        }
        item {
            SectionHeader(
                title = "Offline readiness",
                supportingText = "Rommio caches the active profile so the shell, library, and installed games can keep working without a connection.",
            )
        }
        item {
            CompactPanel(
                title = if (state.offlineState.isOffline) "Offline mode active" else "Online and ready",
                subtitle = when {
                    state.offlineState.isOfflineReady && state.offlineState.isOffline ->
                        "This profile is hydrated and can browse offline with cached media."
                    state.offlineState.isOffline ->
                        "Offline browsing is limited until the active profile finishes a full sync."
                    state.offlineState.isRefreshing ->
                        "Refreshing metadata, collections, and thumbnail cache in the background."
                    else ->
                        "The active profile will refresh automatically whenever a network connection is available."
                },
                badge = if (state.offlineState.isOfflineReady) "Ready" else "Syncing",
                eyebrow = "Offline",
            ) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    maxItemsInEachRow = 2,
                ) {
                    MetricTile(
                        label = "Status",
                        value = if (state.offlineState.isOffline) "Offline" else "Online",
                        icon = if (state.offlineState.isOffline) Icons.Outlined.CloudOff else Icons.Outlined.CloudDone,
                        modifier = Modifier.fillMaxWidth(0.48f),
                    )
                    MetricTile(
                        label = "Cache",
                        value = formatBytes(state.offlineState.cacheBytes),
                        icon = Icons.Outlined.Storage,
                        modifier = Modifier.fillMaxWidth(0.48f),
                    )
                }
                Text(
                    text = "Last catalog sync: ${state.offlineState.lastFullSyncAtEpochMs?.let { java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it)) } ?: "Never"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Last media sync: ${state.offlineState.lastMediaSyncAtEpochMs?.let { java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it)) } ?: "Never"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.offlineState.lastError?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        if (state.isLoading) {
            item { CircularProgressIndicator() }
        }
        item {
            SectionHeader(
                title = "Account and server",
                supportingText = "Server access, RomM authentication, and the active profile all live here.",
            )
        }
        item {
            CompactPanel(
                title = state.activeProfile?.label ?: "No active server",
                subtitle = state.activeProfile?.baseUrl ?: "Configure a server profile to keep using Rommio.",
                badge = if (state.activeProfile != null) "Active" else "Needs setup",
                eyebrow = "Server",
            ) {
                Button(onClick = onReconfigureServer, modifier = Modifier.fillMaxWidth()) {
                    Text("Reconfigure server access")
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    AssistChip(
                        onClick = onReauthenticate,
                        label = { Text("Re-authenticate") },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            labelColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    )
                    AssistChip(
                        onClick = { scope.launch { onLogout() } },
                        label = { Text("Log out") },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            labelColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    )
                }
            }
        }
        if (state.profiles.isNotEmpty()) {
            item {
                SectionHeader(
                    title = "Saved profiles",
                    meta = state.profiles.size.toString(),
                    supportingText = "Switch between known RomM servers without re-entering all connection details.",
                )
            }
            items(state.profiles, key = { it.id }) { profile ->
                CompactPanel(
                    title = profile.label,
                    subtitle = profile.baseUrl,
                    badge = if (profile.id == state.activeProfile?.id) "Active" else "Saved",
                    eyebrow = "Profile",
                ) {
                    Button(
                        onClick = { scope.launch { onActivateProfile(profile) } },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (profile.id == state.activeProfile?.id) "Current profile" else "Activate profile")
                    }
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        AssistChip(
                            onClick = {
                                scope.launch {
                                    val deletedActive = viewModel.deleteProfile(profile.id)
                                    if (deletedActive) {
                                        onActiveProfileDeleted()
                                    }
                                }
                            },
                            label = { Text(if (profile.id == state.activeProfile?.id) "Remove active profile" else "Remove profile") },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                labelColor = MaterialTheme.colorScheme.error,
                            ),
                        )
                    }
                }
            }
        }
        item {
            SectionHeader(
                title = "Controls",
                supportingText = "These defaults are shared with the in-game controls sheet.",
            )
        }
        item {
            CompactPanel(
                title = "Player defaults",
                subtitle = "Set how touch controls and device rumble behave before you launch a game.",
                eyebrow = "Controls",
            ) {
                SettingToggle(
                    title = "Show touch controls",
                    checked = state.controlsPreferences.touchControlsEnabled,
                    onCheckedChange = viewModel::setTouchControlsEnabled,
                )
                SettingToggle(
                    title = "Auto-hide touch controls with a controller",
                    checked = state.controlsPreferences.autoHideTouchOnController,
                    onCheckedChange = viewModel::setAutoHideTouchOnController,
                )
                SettingToggle(
                    title = "Mirror rumble to the device",
                    checked = state.controlsPreferences.rumbleToDeviceEnabled,
                    onCheckedChange = viewModel::setRumbleToDeviceEnabled,
                )
            }
        }
        item {
            SectionHeader(
                title = "Storage",
                supportingText = "Everything Rommio downloads or generates stays under one managed library root.",
            )
        }
        item {
            CompactPanel(
                title = "Managed library location",
                subtitle = "ROMs, cores, saves, save states, screenshots, and BIOS files all stay under this path.",
                badge = "Managed",
                eyebrow = "Storage",
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    shape = MaterialTheme.shapes.large,
                ) {
                    Text(
                        text = state.libraryPath,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        state.errorMessage?.let { message ->
            item { Text(message, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun SettingToggle(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            title,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
