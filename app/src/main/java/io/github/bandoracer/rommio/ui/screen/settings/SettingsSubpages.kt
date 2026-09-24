package io.github.bandoracer.rommio.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.bandoracer.rommio.AppContainer
import io.github.bandoracer.rommio.BuildConfig
import io.github.bandoracer.rommio.domain.player.CoreInventoryStatus
import io.github.bandoracer.rommio.domain.player.EmbeddedSupportTier
import io.github.bandoracer.rommio.model.ServerProfile
import io.github.bandoracer.rommio.ui.component.FirmwareImportContent
import io.github.bandoracer.rommio.ui.component.LegacyProgressRecoveryContent
import io.github.bandoracer.rommio.ui.component.ListDivider
import io.github.bandoracer.rommio.ui.component.ListGroup
import io.github.bandoracer.rommio.ui.component.ListRow
import io.github.bandoracer.rommio.ui.component.LocalRowInset
import io.github.bandoracer.rommio.ui.component.MigrationExportContent
import io.github.bandoracer.rommio.ui.component.MigrationImportContent
import io.github.bandoracer.rommio.ui.component.RoleText
import io.github.bandoracer.rommio.ui.component.RowIcon
import io.github.bandoracer.rommio.ui.component.SecondaryButton
import io.github.bandoracer.rommio.ui.component.StatusChip
import io.github.bandoracer.rommio.ui.component.SupportTierBadge
import io.github.bandoracer.rommio.ui.component.ToggleRow
import io.github.bandoracer.rommio.ui.component.Tone
import io.github.bandoracer.rommio.ui.component.supportTierLabel
import io.github.bandoracer.rommio.ui.screen.collectAsStateWithLifecycleCompat
import io.github.bandoracer.rommio.ui.theme.RommioTheme
import kotlinx.coroutines.launch

/** Padding for a non-row child of a group (text, buttons), aligned with its rows. */
@Composable
private fun GroupContent(content: @Composable () -> Unit) {
    Box(Modifier.padding(horizontal = LocalRowInset.current, vertical = if (RommioTheme.isRiso) 11.dp else 9.dp)) { content() }
}

/** Settings › Profiles and servers: the saved servers (Active stamp; Switch / Remove…) and this server. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsProfilesScreen(
    container: AppContainer,
    onReconfigureServer: () -> Unit,
    onActivateProfile: suspend (ServerProfile) -> Unit,
    onActiveProfileDeleted: suspend () -> Unit,
) {
    val viewModel = rememberSettingsViewModel(container)
    val state by viewModel.uiState.collectAsStateWithLifecycleCompat()
    val scope = rememberCoroutineScope()
    val theme = RommioTheme.current
    var pendingRemoval by remember { mutableStateOf<ServerProfile?>(null) }
    val active = state.activeProfile

    SettingsPage("settings.profilesList", title = null) {
        ListGroup(title = "Servers", meta = state.profiles.size.toString()) {
            state.profiles.forEachIndexed { index, profile ->
                if (index > 0) ListDivider(inset = 58.dp)
                val isActive = profile.id == active?.id
                Column {
                    ListRow(
                        title = profile.label,
                        subtitle = profile.baseUrl,
                        leading = { RowIcon(Icons.Outlined.Dns, if (isActive) Tone.OK else Tone.MUTE) },
                        trailing = if (isActive) { { StatusChip("Active", tone = Tone.OK) } } else null,
                    )
                    Box(Modifier.padding(start = LocalRowInset.current, end = LocalRowInset.current, bottom = 12.dp)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!isActive) {
                                SecondaryButton("Switch to this server", onClick = { scope.launch { onActivateProfile(profile) } })
                            }
                            SecondaryButton("Remove…", onClick = { pendingRemoval = profile }, destructive = true)
                        }
                    }
                }
            }
            if (state.profiles.isEmpty()) {
                GroupContent { RoleText("No saved servers.", theme.type.secondary, color = theme.colors.inkSecondary) }
            }
        }
        if (active != null) {
            ListGroup(title = "This server", footer = "Switching servers keeps each server's games, saves and settings separate.") {
                ListRow(title = "Status", value = active.status.name.replace('_', ' ').lowercase().replaceFirstChar(Char::uppercase))
                state.user?.username?.let {
                    ListDivider()
                    ListRow(title = "Signed in as", value = it)
                }
                ListDivider()
                ActionRow("Set up server access again", theme.tintText, onReconfigureServer, Modifier.testTag("settings.reconfigure"))
            }
        }
    }

    pendingRemoval?.let { profile ->
        val isActive = profile.id == active?.id
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text("Remove ${profile.label}?") },
            text = {
                Text(
                    if (isActive) {
                        "This is the active server. You'll need to set up server access again."
                    } else {
                        "Saved connection details for this server will be removed."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingRemoval = null
                    scope.launch {
                        if (viewModel.deleteProfile(profile.id)) onActiveProfileDeleted()
                    }
                }) { Text("Remove server", color = theme.colors.badText) }
            },
            dismissButton = { TextButton(onClick = { pendingRemoval = null }) { Text("Cancel") } },
        )
    }
}

/** Settings › Player and controls (toggle rows). Shared with the in-game controls sheet. */
@Composable
fun SettingsPlayerScreen(container: AppContainer) {
    val viewModel = rememberSettingsViewModel(container)
    val state by viewModel.uiState.collectAsStateWithLifecycleCompat()
    val prefs = state.controlsPreferences

    SettingsPage("settings.playerList", title = null) {
        ListGroup(title = "Touch controls") {
            ToggleRow(title = "Show touch controls", checked = prefs.touchControlsEnabled, onCheckedChange = viewModel::setTouchControlsEnabled)
            ListDivider()
            ToggleRow(
                title = "Hide touch controls when a controller connects",
                checked = prefs.autoHideTouchOnController,
                onCheckedChange = viewModel::setAutoHideTouchOnController,
            )
        }
        ListGroup(
            title = "Display",
            footer = "Console colors give face buttons their original colors. OLED black turns the player's background fully black.",
        ) {
            ToggleRow(title = "Console colors", checked = prefs.consoleColorsEnabled, onCheckedChange = viewModel::setConsoleColorsEnabled)
            ListDivider()
            ToggleRow(title = "OLED black", checked = prefs.oledBlackModeEnabled, onCheckedChange = viewModel::setOledBlackModeEnabled)
        }
        ListGroup(title = "Controllers", footer = "These also appear in Controls while you play.") {
            ToggleRow(title = "Vibrate this device for rumble", checked = prefs.rumbleToDeviceEnabled, onCheckedChange = viewModel::setRumbleToDeviceEnabled)
        }
    }
}

/** Settings › Cores and BIOS: the BIOS import and how each platform plays in this build. */
@Composable
fun SettingsCoresScreen(container: AppContainer) {
    val viewModel = rememberSettingsViewModel(container)
    val state by viewModel.uiState.collectAsStateWithLifecycleCompat()

    SettingsPage("settings.coresList", title = null) {
        ListGroup(
            title = "BIOS",
            footer = "PlayStation needs scph5501.bin. Sega CD needs the US, Europe and Japan BIOS files. Import your own copies; Rommio checks and names them.",
        ) {
            GroupContent { FirmwareImportContent(container.firmwareStore) }
        }
        // The runtime inventory (iOS parity): one row per core with its status stamp; cores and
        // platforms this build can't play sit under a disclosure.
        LaunchedEffect(Unit) { viewModel.refreshCores() }
        val shipped = state.coreInventory.filter { it.status.inBuild }
        val unavailable = state.coreInventory.filterNot { it.status.inBuild }
        var showsUnavailable by rememberSaveable { mutableStateOf(false) }
        ListGroup(
            title = "Cores",
            meta = shipped.size.toString(),
            footer = "Rommio sets up each core the first time you play a game on its platform. ${supportTierLabel(EmbeddedSupportTier.UNSUPPORTED)} platforms can be downloaded but not played in the app yet.",
            modifier = Modifier.testTag("settings.cores.inventory"),
        ) {
            shipped.forEachIndexed { index, entry ->
                if (index > 0) ListDivider()
                CoreInventoryRow(entry)
            }
            val notIncluded = unavailable.size + state.browseOnlyPlatforms.size
            if (notIncluded > 0) {
                if (shipped.isNotEmpty()) ListDivider()
                ListRow(
                    title = "Not included in this build ($notIncluded)",
                    trailing = {
                        Icon(
                            if (showsUnavailable) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            contentDescription = null,
                            tint = RommioTheme.colors.inkSecondary,
                        )
                    },
                    onClick = { showsUnavailable = !showsUnavailable },
                    modifier = Modifier.testTag("settings.cores.notIncluded"),
                )
                if (showsUnavailable) {
                    unavailable.forEach { entry ->
                        ListDivider()
                        CoreInventoryRow(entry)
                    }
                    state.browseOnlyPlatforms.forEach { name ->
                        ListDivider()
                        ListRow(
                            title = name,
                            subtitle = "On your server · ${supportTierLabel(EmbeddedSupportTier.UNSUPPORTED)}",
                            trailing = { StatusChip(CoreInventoryStatus.NOT_INCLUDED.label, tone = Tone.MUTE) },
                        )
                    }
                }
            }
        }
    }
}

/** One core: its platform family, the core and how it plays, and a status stamp. */
@Composable
private fun CoreInventoryRow(entry: io.github.bandoracer.rommio.domain.player.CoreInventoryEntry) {
    ListRow(
        title = entry.familyName,
        subtitle = "${entry.runtimeName} · ${supportTierLabel(entry.supportTier)}" +
            (entry.missingBios.takeIf { it.isNotEmpty() }?.let { " · needs ${it.joinToString()}" } ?: ""),
        trailing = { StatusChip(entry.status.label, tone = entry.status.tone()) },
        modifier = Modifier.testTag("settings.core.${entry.familyId}"),
    )
}

private fun CoreInventoryStatus.tone(): Tone = when (this) {
    CoreInventoryStatus.READY -> Tone.OK
    CoreInventoryStatus.SETS_UP_ON_FIRST_PLAY -> Tone.LIVE
    CoreInventoryStatus.NEEDS_BIOS -> Tone.WAIT
    CoreInventoryStatus.NOT_INCLUDED, CoreInventoryStatus.UNSUPPORTED -> Tone.MUTE
}

/** Settings › Backup and recovery: move to another device, recover older saves. */
@Composable
fun SettingsBackupScreen(
    container: AppContainer,
    onMigrationImported: () -> Unit,
) {
    val context = LocalContext.current
    val theme = RommioTheme.current
    val legacyInstallDetected = remember(context.packageName) {
        BuildConfig.LEGACY_PACKAGE_ID != context.packageName &&
            runCatching { context.packageManager.getPackageInfo(BuildConfig.LEGACY_PACKAGE_ID, 0) }.isSuccess
    }

    SettingsPage("settings.backupList", title = null) {
        ListGroup(title = "Move your library", footer = "Theme and appearance stay with each device and aren't included.") {
            GroupContent {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (BuildConfig.IS_LEGACY_BRIDGE) {
                        RoleText("Export your library", theme.type.bodyEmphasis, size = 16f, color = theme.colors.ink)
                        RoleText(
                            "Save your games, saves and servers to a file, then import it in the new Rommio app.",
                            theme.type.secondary,
                            color = theme.colors.inkSecondary,
                        )
                        MigrationExportContent(repository = container.repository, buttonLabel = "Export library")
                    } else {
                        RoleText(
                            if (legacyInstallDetected) "Import from Rommio Legacy" else "Import a library file",
                            theme.type.bodyEmphasis,
                            size = 16f,
                            color = theme.colors.ink,
                        )
                        RoleText(
                            if (legacyInstallDetected) {
                                "Choose the file you exported from Rommio Legacy. Rommio restarts when the import finishes."
                            } else {
                                "Choose a library file exported from Rommio. It replaces the games, saves and servers on this device."
                            },
                            theme.type.secondary,
                            color = theme.colors.inkSecondary,
                        )
                        MigrationImportContent(
                            repository = container.repository,
                            buttonLabel = if (legacyInstallDetected) "Import from Rommio Legacy" else "Choose library file",
                            continueLabel = "Restart Rommio",
                            onContinueAfterSuccess = onMigrationImported,
                        )
                    }
                }
            }
        }
        ListGroup(title = "Recovery") {
            GroupContent { LegacyProgressRecoveryContent(container.legacyProgressRecovery) }
        }
    }
}
