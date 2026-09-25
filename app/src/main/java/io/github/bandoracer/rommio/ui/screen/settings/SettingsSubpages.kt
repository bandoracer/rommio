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
import io.github.bandoracer.rommio.domain.input.PlayerControlsPreferences
import io.github.bandoracer.rommio.domain.player.CoreInventoryEntry
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
import io.github.bandoracer.rommio.ui.component.ToggleRow
import io.github.bandoracer.rommio.ui.component.Tone
import io.github.bandoracer.rommio.ui.component.lazyListGroup
import io.github.bandoracer.rommio.ui.component.rememberLazyCardGroups
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
    var pendingRemoval by remember { mutableStateOf<ServerProfile?>(null) }
    val active = state.activeProfile

    SettingsPage("settings.profilesList", title = null) {
        item(key = "servers", contentType = "servers") {
            ServersGroup(state.profiles, active, onSwitch = { profile -> scope.launch { onActivateProfile(profile) } }, onRemove = { pendingRemoval = it })
        }
        if (active != null) {
            item(key = "thisServer", contentType = "thisServer") { ThisServerGroup(active, state.user?.username, onReconfigureServer) }
        }
    }

    pendingRemoval?.let { profile ->
        RemoveServerDialog(
            profile = profile,
            isActive = profile.id == active?.id,
            onDismiss = { pendingRemoval = null },
            onRemove = {
                pendingRemoval = null
                scope.launch {
                    if (viewModel.deleteProfile(profile.id)) onActiveProfileDeleted()
                }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ServersGroup(profiles: List<ServerProfile>, active: ServerProfile?, onSwitch: (ServerProfile) -> Unit, onRemove: (ServerProfile) -> Unit) {
    val theme = RommioTheme.current
    ListGroup(title = "Servers", meta = profiles.size.toString()) {
        profiles.forEachIndexed { index, profile ->
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
                            SecondaryButton("Switch to this server", onClick = { onSwitch(profile) })
                        }
                        SecondaryButton("Remove…", onClick = { onRemove(profile) }, destructive = true)
                    }
                }
            }
        }
        if (profiles.isEmpty()) {
            GroupContent { RoleText("No saved servers.", theme.type.secondary, color = theme.colors.inkSecondary) }
        }
    }
}

@Composable
private fun ThisServerGroup(active: ServerProfile, username: String?, onReconfigureServer: () -> Unit) {
    ListGroup(title = "This server", footer = "Switching servers keeps each server's games, saves and settings separate.") {
        ListRow(title = "Status", value = active.status.name.replace('_', ' ').lowercase().replaceFirstChar(Char::uppercase))
        username?.let {
            ListDivider()
            ListRow(title = "Signed in as", value = it)
        }
        ListDivider()
        ActionRow("Set up server access again", RommioTheme.light.tintText, onReconfigureServer, Modifier.testTag("settings.reconfigure"))
    }
}

@Composable
private fun RemoveServerDialog(profile: ServerProfile, isActive: Boolean, onDismiss: () -> Unit, onRemove: () -> Unit) {
    val theme = RommioTheme.current
    AlertDialog(
        onDismissRequest = onDismiss,
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
            TextButton(onClick = onRemove) { Text("Remove server", color = theme.colors.badText) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Settings › Player and controls (toggle rows). Shared with the in-game controls sheet. */
@Composable
fun SettingsPlayerScreen(container: AppContainer) {
    val viewModel = rememberSettingsViewModel(container)
    val state by viewModel.uiState.collectAsStateWithLifecycleCompat()
    val prefs = state.controlsPreferences

    SettingsPage("settings.playerList", title = null) {
        item(key = "touch", contentType = "group") { TouchGroup(prefs, viewModel) }
        item(key = "display", contentType = "group") { DisplayGroup(prefs, viewModel) }
        item(key = "controllers", contentType = "group") { ControllersGroup(prefs, viewModel) }
    }
}

@Composable
private fun TouchGroup(prefs: PlayerControlsPreferences, viewModel: SettingsViewModel) {
    ListGroup(
        title = "Touch controls",
        footer = "Haptics give a light tap as you press a control. The system haptics setting still applies.",
    ) {
        ToggleRow(title = "Show touch controls", checked = prefs.touchControlsEnabled, onCheckedChange = viewModel::setTouchControlsEnabled)
        ListDivider()
        ToggleRow(
            title = "Hide touch controls when a controller connects",
            checked = prefs.autoHideTouchOnController,
            onCheckedChange = viewModel::setAutoHideTouchOnController,
        )
        ListDivider()
        ToggleRow(
            title = "Haptics",
            checked = prefs.hapticsEnabled,
            onCheckedChange = viewModel::setHapticsEnabled,
            modifier = Modifier.testTag("settings.player.haptics"),
        )
    }
}

@Composable
private fun DisplayGroup(prefs: PlayerControlsPreferences, viewModel: SettingsViewModel) {
    ListGroup(
        title = "Display",
        footer = "Console colors give face buttons their original colors. OLED black turns the player's background fully black.",
    ) {
        ToggleRow(title = "Console colors", checked = prefs.consoleColorsEnabled, onCheckedChange = viewModel::setConsoleColorsEnabled)
        ListDivider()
        ToggleRow(title = "OLED black", checked = prefs.oledBlackModeEnabled, onCheckedChange = viewModel::setOledBlackModeEnabled)
    }
}

@Composable
private fun ControllersGroup(prefs: PlayerControlsPreferences, viewModel: SettingsViewModel) {
    ListGroup(title = "Controllers", footer = "These also appear in Controls while you play.") {
        ToggleRow(title = "Vibrate this device for rumble", checked = prefs.rumbleToDeviceEnabled, onCheckedChange = viewModel::setRumbleToDeviceEnabled)
    }
}

/** Settings › Cores and BIOS: the BIOS import and how each platform plays in this build. */
@Composable
fun SettingsCoresScreen(container: AppContainer) {
    val viewModel = rememberSettingsViewModel(container)
    val state by viewModel.uiState.collectAsStateWithLifecycleCompat()
    // The runtime inventory (iOS parity): one row per core with its status stamp; cores and
    // platforms this build can't play sit under a disclosure.
    LaunchedEffect(Unit) { viewModel.refreshCores() }
    var showsUnavailable by rememberSaveable { mutableStateOf(false) }
    val rows = remember(state.coreInventory, state.browseOnlyPlatforms, showsUnavailable) {
        coreRows(state.coreInventory, state.browseOnlyPlatforms, showsUnavailable)
    }
    val theme = RommioTheme.current
    val groups = rememberLazyCardGroups()
    // The inventory's rows are lazy items of one group, so the page spaces its parts itself.
    SettingsPage("settings.coresList", title = null, groups = groups, spaced = false) {
        item(key = "bios", contentType = "group") {
            ListGroup(
                title = "BIOS",
                footer = "PlayStation needs scph5501.bin. Sega CD needs the US, Europe and Japan BIOS files. Import your own copies; Rommio checks and names them.",
            ) {
                GroupContent { FirmwareImportContent(container.firmwareStore) }
            }
        }
        lazyListGroup(
            groups = groups,
            riso = theme.isRiso,
            key = "cores",
            items = rows,
            itemKey = { it.key },
            title = "Cores",
            meta = rows.count { it is CoreRow.Shipped }.toString(),
            footer = "Rommio sets up each core the first time you play a game on its platform. ${supportTierLabel(EmbeddedSupportTier.UNSUPPORTED)} platforms can be downloaded but not played in the app yet.",
            top = theme.spacing.section,
            titleModifier = Modifier.testTag("settings.cores.inventory"),
        ) { _, row ->
            when (row) {
                is CoreRow.Shipped -> CoreInventoryRow(row.entry)
                is CoreRow.Unavailable -> CoreInventoryRow(row.entry)
                is CoreRow.Disclosure -> ListRow(
                    title = "Not included in this build (${row.count})",
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
                is CoreRow.BrowseOnly -> ListRow(
                    title = row.name,
                    subtitle = "On your server · ${supportTierLabel(EmbeddedSupportTier.UNSUPPORTED)}",
                    trailing = { StatusChip(CoreInventoryStatus.NOT_INCLUDED.label, tone = Tone.MUTE) },
                )
            }
        }
    }
}

/** A row of the Cores group: a core, the "Not included" disclosure, or a browse-only platform. */
private sealed interface CoreRow {
    val key: String
    data class Shipped(val entry: CoreInventoryEntry, override val key: String) : CoreRow
    data class Disclosure(val count: Int) : CoreRow {
        override val key: String get() = "notIncluded"
    }
    data class Unavailable(val entry: CoreInventoryEntry, override val key: String) : CoreRow
    data class BrowseOnly(val name: String, override val key: String) : CoreRow
}

/** The Cores group's rows: the cores in this build, then (when open) what it can't play. */
private fun coreRows(inventory: List<CoreInventoryEntry>, browseOnly: List<String>, showsUnavailable: Boolean): List<CoreRow> = buildList {
    val shipped = inventory.filter { it.status.inBuild }
    val unavailable = inventory.filterNot { it.status.inBuild }
    shipped.forEachIndexed { index, entry -> add(CoreRow.Shipped(entry, "core/$index/${entry.familyId}")) }
    val notIncluded = unavailable.size + browseOnly.size
    if (notIncluded > 0) {
        add(CoreRow.Disclosure(notIncluded))
        if (showsUnavailable) {
            unavailable.forEachIndexed { index, entry -> add(CoreRow.Unavailable(entry, "unavailable/$index/${entry.familyId}")) }
            browseOnly.forEachIndexed { index, name -> add(CoreRow.BrowseOnly(name, "browse/$index/$name")) }
        }
    }
}

/** One core: its platform family, the core and how it plays, and a status stamp. */
@Composable
private fun CoreInventoryRow(entry: CoreInventoryEntry) {
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
    val legacyInstallDetected = remember(context.packageName) {
        BuildConfig.LEGACY_PACKAGE_ID != context.packageName &&
            runCatching { context.packageManager.getPackageInfo(BuildConfig.LEGACY_PACKAGE_ID, 0) }.isSuccess
    }

    SettingsPage("settings.backupList", title = null) {
        item(key = "move", contentType = "group") { MoveGroup(container, legacyInstallDetected, onMigrationImported) }
        item(key = "recovery", contentType = "group") {
            ListGroup(title = "Recovery") {
                GroupContent { LegacyProgressRecoveryContent(container.legacyProgressRecovery) }
            }
        }
    }
}

@Composable
private fun MoveGroup(container: AppContainer, legacyInstallDetected: Boolean, onMigrationImported: () -> Unit) {
    val theme = RommioTheme.current
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
}
