package io.github.bandoracer.rommio.ui.screen.settings

import io.github.bandoracer.rommio.ui.component.OverArt
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.bandoracer.rommio.AppContainer
import io.github.bandoracer.rommio.BuildConfig
import io.github.bandoracer.rommio.model.AppearanceScheme
import io.github.bandoracer.rommio.model.AppearanceTheme
import io.github.bandoracer.rommio.ui.component.DisplayText
import io.github.bandoracer.rommio.ui.component.GlassSurface
import io.github.bandoracer.rommio.ui.component.ListDivider
import io.github.bandoracer.rommio.ui.component.ListGroup
import io.github.bandoracer.rommio.ui.component.ListRow
import io.github.bandoracer.rommio.ui.component.LazyCardGroups
import io.github.bandoracer.rommio.ui.component.LitPage
import io.github.bandoracer.rommio.ui.component.drawCardGroups
import io.github.bandoracer.rommio.ui.component.rememberLazyCardGroups
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import io.github.bandoracer.rommio.ui.component.PageField
import io.github.bandoracer.rommio.ui.component.PageFieldStyle
import io.github.bandoracer.rommio.ui.component.PageHeader
import io.github.bandoracer.rommio.ui.component.PlayFormat
import io.github.bandoracer.rommio.ui.component.PrintRule
import io.github.bandoracer.rommio.ui.component.RoleText
import io.github.bandoracer.rommio.ui.component.RowIcon
import io.github.bandoracer.rommio.ui.component.RuleWeight
import io.github.bandoracer.rommio.ui.component.SegmentedChoice
import io.github.bandoracer.rommio.ui.component.StatusChip
import io.github.bandoracer.rommio.ui.component.Tone
import io.github.bandoracer.rommio.ui.component.formatBytes
import io.github.bandoracer.rommio.ui.component.halftone
import io.github.bandoracer.rommio.ui.component.plateOffset
import io.github.bandoracer.rommio.ui.component.sized
import io.github.bandoracer.rommio.ui.screen.collectAsStateWithLifecycleCompat
import io.github.bandoracer.rommio.ui.theme.RommioTheme
import kotlinx.coroutines.launch

@Composable
internal fun rememberSettingsViewModel(container: AppContainer): SettingsViewModel = viewModel(
    factory = viewModelFactory {
        initializer {
            SettingsViewModel(
                repository = container.repository,
                controlsRepository = container.playerControlsRepository,
                appearanceStore = container.appearancePreferencesStore,
                libraryPath = container.libraryStore.rootDirectory().absolutePath,
            )
        }
    },
)

/**
 * A settings page (docs/theme-layouts.md "Settings and sub-pages"): the quiet field, max width
 * 760, a large title, and the page's groups as lazy items (`section` apart). Pass [groups] and
 * [listState] when the page has lazy row groups ([lazyListGroup]).
 */
@Composable
internal fun SettingsPage(
    testTag: String,
    title: String?,
    groups: LazyCardGroups = rememberLazyCardGroups(),
    listState: LazyListState = rememberLazyListState(),
    spaced: Boolean = true,
    content: LazyListScope.() -> Unit,
) {
    val theme = RommioTheme.current
    LitPage(null, PageFieldStyle.QUIET) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier
                    .widthIn(max = 760.dp)
                    .fillMaxSize()
                    .drawCardGroups(listState, groups, theme.spacing.gutter)
                    .testTag(testTag),
                state = listState,
                contentPadding = PaddingValues(start = theme.spacing.gutter, end = theme.spacing.gutter, top = if (title == null) 4.dp else 8.dp, bottom = 40.dp),
                verticalArrangement = if (spaced) Arrangement.spacedBy(theme.spacing.section) else Arrangement.Top,
            ) {
                title?.let { item(key = "title", contentType = "title") { PageHeader(title = it) } }
                content()
            }
        }
    }
}

/**
 * Settings: the account at the top, then Appearance, the four sub-pages, storage and the account
 * actions. One layout; the components carry the theme (glass groups, or paper rows between rules).
 */
@Composable
fun SettingsScreen(
    container: AppContainer,
    onOpenProfiles: () -> Unit,
    onOpenPlayer: () -> Unit,
    onOpenCores: () -> Unit,
    onOpenBackup: () -> Unit,
    onReauthenticate: () -> Unit = {},
    onLogout: suspend () -> Unit = {},
) {
    val viewModel = rememberSettingsViewModel(container)
    val state by viewModel.uiState.collectAsStateWithLifecycleCompat()
    val scope = rememberCoroutineScope()
    val offline = state.offlineState

    SettingsPage("shell.settingsList", "Settings") {
        item(key = "account", contentType = "account") { AccountCard(state.activeProfile, offline, onOpenProfiles) }
        item(key = "appearance", contentType = "appearance") { AppearanceGroup(state.appearance, viewModel::setTheme, viewModel::setScheme) }
        item(key = "rommio", contentType = "rommio") {
            RommioGroup(state.profiles.size, state.coreSummary, onOpenProfiles, onOpenPlayer, onOpenCores, onOpenBackup)
        }
        item(key = "storage", contentType = "storage") {
            StorageGroup(state.installedGameCount, state.installedFileCount, state.storageBytes, offline.cacheBytes, state.libraryPath)
        }
        (offline.lastError ?: state.errorMessage)?.let { message ->
            item(key = "stale", contentType = "stale") { StaleGroup(message) }
        }
        item(key = "accountActions", contentType = "accountActions") {
            AccountActions(onReauthenticate) { scope.launch { onLogout() } }
        }
        item(key = "version", contentType = "version") { Version() }
    }
}

@Composable
private fun AppearanceGroup(
    appearance: io.github.bandoracer.rommio.model.AppearancePreferences,
    setTheme: (AppearanceTheme) -> Unit,
    setScheme: (AppearanceScheme) -> Unit,
) {
    ListGroup(title = "Appearance", footer = "Saved on this device only. Backups don't include it.", modifier = Modifier.testTag("settings.appearance")) {
        Box(Modifier.padding(horizontal = RowPaddingH(), vertical = RowPaddingV())) { AppearancePreview() }
        ListDivider()
        Choice("Theme") {
            SegmentedChoice(AppearanceTheme.entries, appearance.theme, { it.label }, setTheme, Modifier.testTag("appearance.theme"))
        }
        ListDivider()
        Choice("Appearance") {
            SegmentedChoice(AppearanceScheme.entries, appearance.scheme, { it.label }, setScheme, Modifier.testTag("appearance.scheme"))
        }
    }
}

@Composable
private fun RommioGroup(
    profileCount: Int,
    coreSummary: String?,
    onOpenProfiles: () -> Unit,
    onOpenPlayer: () -> Unit,
    onOpenCores: () -> Unit,
    onOpenBackup: () -> Unit,
) {
        ListGroup(title = "Rommio") {
            ListRow(
                title = "Profiles and servers",
                subtitle = if (profileCount == 1) "1 server" else "$profileCount servers",
                leading = { RowIcon(Icons.Outlined.Dns, Tone.LIVE) },
                showChevron = true,
                onClick = onOpenProfiles,
                modifier = Modifier.testTag("settings.profiles"),
            )
            ListDivider(inset = 58.dp)
            ListRow(
                title = "Player and controls",
                subtitle = "Touch controls, controllers, display",
                leading = { RowIcon(Icons.Outlined.SportsEsports, Tone.OK) },
                showChevron = true,
                onClick = onOpenPlayer,
                modifier = Modifier.testTag("settings.player"),
            )
            ListDivider(inset = 58.dp)
            ListRow(
                title = "Cores and BIOS",
                subtitle = coreSummary ?: "Emulators and system files",
                leading = { RowIcon(Icons.Outlined.Memory, Tone.WAIT) },
                showChevron = true,
                onClick = onOpenCores,
                modifier = Modifier.testTag("settings.cores"),
            )
            ListDivider(inset = 58.dp)
            ListRow(
                title = "Backup and recovery",
                subtitle = "Game saves, resume points and recovery",
                leading = { RowIcon(Icons.Outlined.Backup, Tone.BAD) },
                showChevron = true,
                onClick = onOpenBackup,
                modifier = Modifier.testTag("settings.backup"),
            )
        }
}

@Composable
private fun StorageGroup(installedGames: Int, installedFiles: Int, storageBytes: Long, cacheBytes: Long, libraryPath: String) {
    ListGroup(title = "Storage") {
        ListRow(title = "Installed games", value = installedGames.toString())
        ListDivider()
        ListRow(title = "Installed files", value = installedFiles.toString())
        ListDivider()
        ListRow(title = "Library on this device", value = formatBytes(storageBytes))
        ListDivider()
        ListRow(title = "Catalog cache", value = formatBytes(cacheBytes))
        ListDivider()
        ListRow(title = "Library folder", subtitle = libraryPath, subtitleMaxLines = 3)
    }
}

@Composable
private fun StaleGroup(message: String) {
    val theme = RommioTheme.current
    ListGroup(title = "Showing saved settings") {
        Box(Modifier.padding(horizontal = RowPaddingH(), vertical = RowPaddingV())) {
            RoleText(message, theme.type.caption, color = theme.colors.inkSecondary)
        }
    }
}

@Composable
private fun AccountActions(onReauthenticate: () -> Unit, onLogout: () -> Unit) {
    val theme = RommioTheme.current
    ListGroup(title = "Account") {
        ActionRow("Sign in again", RommioTheme.light.tintText, onReauthenticate, Modifier.testTag("settings.reauthenticate"))
        ListDivider()
        ActionRow("Sign out", theme.colors.badText, onLogout, Modifier.testTag("settings.signOut"))
    }
}

@Composable
private fun Version() {
    val theme = RommioTheme.current
    RoleText(
        text = "Rommio ${BuildConfig.VERSION_NAME}",
        role = theme.type.caption,
        color = theme.colors.inkTertiary,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

@Composable
private fun RowPaddingH() = io.github.bandoracer.rommio.ui.component.LocalRowInset.current

@Composable
private fun RowPaddingV() = if (RommioTheme.isRiso) 11.dp else 9.dp

@Composable
private fun Choice(label: String, content: @Composable () -> Unit) {
    val theme = RommioTheme.current
    Column(
        Modifier.padding(horizontal = RowPaddingH(), vertical = RowPaddingV()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RoleText(label, theme.type.bodyEmphasis, size = 16f, color = theme.colors.ink)
        content()
    }
}

/** A row that is one action, in its colour ("Sign in again" in `tint`, "Sign out" in `bad`). */
@Composable
internal fun ActionRow(title: String, color: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val theme = RommioTheme.current
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = maxOf(theme.spacing.rowHeight, 48.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = RowPaddingH(), vertical = RowPaddingV()),
        contentAlignment = Alignment.CenterStart,
    ) {
        RoleText(title, theme.type.bodyEmphasis, size = 16f, color = color)
    }
}

/**
 * The active server, its connection and when the library last synced. Aurora: a glass card with
 * an avatar in the page's light. Riso: an imprint between heavy rules, the server's name set as a
 * headline.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccountCard(activeProfile: io.github.bandoracer.rommio.model.ServerProfile?, offline: io.github.bandoracer.rommio.model.OfflineState, onOpenProfiles: () -> Unit) {
    val theme = RommioTheme.current
    val c = theme.colors
    val label = activeProfile?.label ?: "No active server"
    val host = activeProfile?.baseUrl?.let { runCatching { java.net.URI(it).host }.getOrNull() ?: it }
        ?: "Set up a server to keep using Rommio."
    val syncLine = offline.lastFullSyncAtEpochMs?.let { "Library synced ${PlayFormat.relative(it)}" }
    val chips: @Composable () -> Unit = {
        if (offline.isOffline) StatusChip("Offline", tone = Tone.WAIT, icon = Icons.Outlined.WifiOff) else StatusChip("Online", tone = Tone.OK)
        StatusChip(if (offline.catalogReady) "Ready offline" else "Library syncing", tone = if (offline.catalogReady) Tone.OK else Tone.LIVE)
    }
    val modifier = Modifier
        .fillMaxWidth()
        .clickable(role = Role.Button, onClickLabel = "Profiles and servers", onClick = onOpenProfiles)
        .semantics(mergeDescendants = true) {}
        .testTag("settings.account")
    if (theme.isRiso) {
        Column(modifier) {
            PrintRule(weight = RuleWeight.HEAVY)
            Column(
                Modifier.fillMaxWidth().padding(vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RoleText("Server", theme.type.label, size = 10f, color = c.inkSecondary)
                DisplayText(label, size = 44f, plateOffset = 1.25f, maxLines = 2)
                RoleText(host, theme.type.body, size = 15f, italic = true, color = c.ink, textAlign = TextAlign.Center)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { chips() }
                syncLine?.let { RoleText(it, theme.type.label, size = 9.5f, color = c.inkSecondary) }
            }
            PrintRule(weight = RuleWeight.HEAVY)
        }
        return
    }
    GlassSurface(modifier, contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(58.dp)
                    .border(2.dp, OverArt.rim.copy(alpha = 0.6f), CircleShape)
                    .padding(3.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(RommioTheme.light.mark1, RommioTheme.light.mark2)))
                    .clearAndSetSemantics {},
                contentAlignment = Alignment.Center,
            ) {
                RoleText(label.take(1).uppercase(), theme.type.headline, color = OverArt.rim)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                RoleText(label, theme.type.headline, color = c.ink, maxLines = 1)
                RoleText(host, theme.type.secondary, color = c.inkSecondary, maxLines = 1)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { chips() }
        syncLine?.let { RoleText(it, theme.type.caption, color = c.inkSecondary) }
    }
}

/**
 * A small live preview of the current theme and appearance (138 dp): the page's welcome field or
 * paper, a stand-in cover, "Continue playing", "Super Metroid", a Synced badge and Resume.
 */
@Composable
private fun AppearancePreview() {
    val theme = RommioTheme.current
    val c = theme.colors
    val riso = theme.isRiso
    val description = "Preview of ${theme.theme.label}, ${if (theme.isDark) "dark" else "light"}"
    val shape = RoundedCornerShape(if (riso) 0.dp else 16.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(138.dp)
            .clip(shape)
            .then(if (riso) Modifier.border(1.5.dp, c.ink) else Modifier)
            .semantics { contentDescription = description }
            .testTag("appearance.preview"),
    ) {
        PageField(modifier = Modifier.matchParentSize(), style = PageFieldStyle.WELCOME)
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            val coverShape = RoundedCornerShape(if (riso) 0.dp else 12.dp)
            Box(
                Modifier
                    .size(70.dp, 94.dp)
                    .clip(coverShape)
                    .then(
                        if (riso) {
                            Modifier.background(c.accent2).drawBehind { halftone(c.accent1, 6.dp.toPx()) }
                        } else {
                            Modifier.background(Brush.linearGradient(listOf(c.accent1, c.accent2, c.accent3)))
                        },
                    )
                    .border(1.dp, if (riso) c.ink else OverArt.rim.copy(alpha = 0.4f), coverShape),
            )
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                RoleText("Continue playing", theme.type.section, size = if (riso) 12f else 10.5f, color = c.inkSecondary, maxLines = 1)
                DisplayText("Super Metroid", role = if (riso) theme.type.display else theme.type.headline, size = if (riso) 26f else 20f, maxLines = 1)
                StatusChip("Synced", tone = Tone.OK)
                Row(
                    Modifier
                        .heightIn(min = 30.dp)
                        .then(if (riso) Modifier.plateOffset(c.accent1, 3.dp) else Modifier)
                        .clip(if (riso) RoundedCornerShape(0.dp) else theme.radius.buttonShape)
                        .background(c.buttonFill)
                        .padding(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = null, tint = c.buttonInk, modifier = Modifier.size(14.dp))
                    io.github.bandoracer.rommio.ui.component.CasedText(
                        "Resume",
                        theme.type.button.uppercase,
                        theme.type.button.style.sized(if (riso) 14f else 13f),
                        color = c.buttonInk,
                    )
                }
            }
        }
    }
}
