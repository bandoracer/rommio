package io.github.bandoracer.rommio.ui.screen.game

import io.github.bandoracer.rommio.ui.component.OverArt
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.github.bandoracer.rommio.domain.playdata.SaveKind
import io.github.bandoracer.rommio.ui.component.DisplayText
import io.github.bandoracer.rommio.ui.component.GlassSurface
import io.github.bandoracer.rommio.ui.component.ListDivider
import io.github.bandoracer.rommio.ui.component.ListGroup
import io.github.bandoracer.rommio.ui.component.ListRow
import io.github.bandoracer.rommio.ui.component.PageFieldStyle
import io.github.bandoracer.rommio.ui.component.Fmt
import io.github.bandoracer.rommio.ui.component.PlayFormat
import io.github.bandoracer.rommio.ui.component.RoleText
import io.github.bandoracer.rommio.ui.component.RowIcon
import io.github.bandoracer.rommio.ui.component.SecondaryButton
import io.github.bandoracer.rommio.ui.component.SectionHeader
import io.github.bandoracer.rommio.ui.component.StatusChip
import io.github.bandoracer.rommio.ui.component.SyncBadge
import io.github.bandoracer.rommio.ui.component.Tag
import io.github.bandoracer.rommio.ui.component.Tone
import io.github.bandoracer.rommio.ui.component.dropShadow
import io.github.bandoracer.rommio.ui.component.formatBytes
import io.github.bandoracer.rommio.ui.component.isRegularWidth
import io.github.bandoracer.rommio.ui.theme.RommioTheme
import java.io.File

/** Shifts content up by [amount] (overlapping what is above) without leaving a gap below. */
internal fun Modifier.overlapTop(amount: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val shift = amount.roundToPx()
    layout(placeable.width, (placeable.height - shift).coerceAtLeast(0)) { placeable.place(0, -shift) }
}

/**
 * Aurora Game: the cover fills the top of the page and the page takes its light; the title, where
 * the game stands and the main action follow, then the game's saves as a timeline, its file and
 * its story. On a regular width the hero spans the canvas, with progress in a glass panel beside
 * the title and the timeline running the full width.
 */
@Composable
fun AuroraGameLayout(content: GameContent, actions: GameActions, scroll: ScrollState) {
    val theme = RommioTheme.current
    val regular = isRegularWidth()
    // The page is lit by the screen (LitPage around the layouts); the sections are small and take
    // only what they draw from [GameContent], which the model builds.
    Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
        GameArtHero(content.coverUrl, content.rom.displayName, heroHeight(0.46f))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = 1100.dp)
                    .fillMaxWidth()
                    .overlapTop(64.dp)
                    .padding(horizontal = if (regular) 32.dp else theme.spacing.gutter)
                    .padding(bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(theme.spacing.section),
            ) {
                if (regular) {
                    Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                            Heading(content, regular = true)
                            GameFilePicker(content, actions)
                            GameActionDeck(content, actions)
                        }
                        Column(Modifier.width(340.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                            ProgressPanel(content)
                            FileGroup(content, actions)
                        }
                    }
                    Timeline(content, actions)
                    // Details and Screenshots side by side under the timeline.
                    val details = content.details
                    val shots = content.rom.metadata.screenshots
                    if (details.isNotEmpty() || shots.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                            Box(Modifier.weight(1f)) { DetailsSection(details) }
                            Box(Modifier.weight(1f)) { ScreenshotsSection(content) }
                        }
                    }
                    About(content)
                } else {
                    Heading(content, regular = false)
                    GameFilePicker(content, actions)
                    GameActionDeck(content, actions)
                    Timeline(content, actions)
                    DetailsSection(content.details)
                    ScreenshotsSection(content)
                    FileGroup(content, actions)
                    About(content)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Heading(content: GameContent, regular: Boolean) {
    val theme = RommioTheme.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RoleText(content.platformLine, theme.type.section, color = theme.colors.ink, maxLines = 1)
            RoleText(content.tierLabel, theme.type.section, color = theme.colors.inkSecondary, maxLines = 1)
        }
        DisplayText(content.rom.displayName, size = if (regular) 52f else 44f, maxLines = 3)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusChip(content.statusText, tone = content.statusTone)
            content.resumeSync?.let { SyncBadge("${it.label} · resume", it.tone()) }
            if (content.stops.isNotEmpty() && content.savesCount.isNotEmpty()) StatusChip(content.savesCount, tone = Tone.MUTE)
        }
        content.playLine?.let { RoleText(it, theme.type.secondary, color = theme.colors.inkSecondary) }
        content.controllerNote?.let { RoleText(it, theme.type.secondary, color = theme.colors.inkSecondary) }
    }
}

// ---- Saved progress --------------------------------------------------------------------------

@Composable
private fun Timeline(content: GameContent, actions: GameActions) {
    val theme = RommioTheme.current
    Column(Modifier.testTag("game.savedProgress"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader(title = "Saved progress", meta = content.stops.size.takeIf { it > 0 }?.toString())
        if (content.stops.isEmpty()) {
            GlassSurface(Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                RoleText(if (content.hasSaves) "Game-save data only" else "No saved progress yet", theme.type.bodyEmphasis, color = theme.colors.ink)
                RoleText("Play to make a resume point; save states appear here as you make them.", theme.type.secondary, color = theme.colors.inkSecondary)
            }
        } else {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
                content.stops.forEachIndexed { index, stop ->
                    TimelineStop(stop, content, first = index == 0, last = index == content.stops.lastIndex)
                }
            }
        }
        content.problemText?.let {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = theme.colors.wait, modifier = Modifier.size(16.dp))
                RoleText(it, theme.type.secondary, color = theme.colors.wait)
            }
            SecondaryButton(
                "Choose resume point",
                onClick = actions.chooseResume,
                modifier = Modifier.testTag("game.chooseResume"),
            )
        }
        if (content.hasSaves || content.selectedInstalled) {
            SecondaryButton(
                "Browse saved progress",
                onClick = actions.browseSaves,
                icon = Icons.Outlined.Layers,
                modifier = Modifier.testTag("game.viewSavedProgress"),
            )
        }
    }
}

/** One stop: when, a node on the line, and the save as a lit card (its preview when captured). */
@Composable
private fun TimelineStop(stop: SaveStop, content: GameContent, first: Boolean, last: Boolean) {
    val theme = RommioTheme.current
    val c = theme.colors
    // The timeline is drawn in the page's light (its marks, lit ground and lights).
    val light = RommioTheme.light
    val width = 164.dp
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.padding(end = 14.dp).width(width).semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RoleText(PlayFormat.short(stop.entry.capturedAtEpochMs), theme.type.numeral, size = 12.5f, color = c.inkSecondary)
        Box(Modifier.height(12.dp).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
            Box(
                Modifier
                    .offset(x = 6.dp)
                    .width(if (last) 8.dp else width + 14.dp)
                    .height(1.5.dp)
                    .background(light.mark1.copy(alpha = 0.6f)),
            )
            Box(
                Modifier
                    .size(11.dp)
                    .then(if (first) Modifier.dropShadow(light.mark1.copy(alpha = 0.7f), 999.dp, 5.dp, 0.dp) else Modifier)
                    .clip(CircleShape)
                    .background(if (first) light.mark1 else light.ground)
                    .border(1.5.dp, light.mark1, CircleShape),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                Modifier
                    .size(width, width * 0.62f)
                    .clip(shape)
                    .background(
                        if (stop.entry.thumbnailPath != null) {
                            Brush.linearGradient(listOf(theme.player.ground, theme.player.ground))
                        } else {
                            Brush.linearGradient(listOf(light.colors.accent1.copy(alpha = if (theme.isDark) 0.5f else 0.8f), light.colors.accent2.copy(alpha = if (theme.isDark) 0.5f else 0.8f)))
                        },
                    )
                    .border(0.5.dp, OverArt.rim.copy(alpha = if (theme.isDark) 0.1f else 0.5f), shape),
                contentAlignment = Alignment.Center,
            ) {
                val preview = stop.entry.thumbnailPath
                if (preview != null) {
                    // The frame as captured: never given the theme's art treatment.
                    AsyncImage(File(preview), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(kindIcon(stop), contentDescription = null, tint = c.ink.copy(alpha = 0.7f), modifier = Modifier.size(24.dp))
                }
            }
            RoleText(stop.kindTitle, theme.type.section, size = 10.5f, color = c.inkSecondary, maxLines = 1)
            RoleText(stop.title, theme.type.bodyEmphasis, size = 15f, color = c.ink, maxLines = 2)
            RoleText(
                "${PlayFormat.relative(stop.entry.capturedAtEpochMs)} · ${stop.entry.deviceLabel}",
                theme.type.caption,
                size = 12f,
                color = c.inkSecondary,
                maxLines = 1,
            )
            stop.entry.sync.takeIf { it != io.github.bandoracer.rommio.domain.playdata.SaveSyncStatus.UNKNOWN }?.let { SyncBadge(it.label, it.tone()) }
        }
    }
}

internal fun kindIcon(stop: SaveStop) = when {
    stop.entry.kind == SaveKind.SAVE_STATE -> Icons.Outlined.Layers
    else -> Icons.Outlined.History
}

/** Regular width: the progress kinds in a glass panel beside the title. */
@Composable
private fun ProgressPanel(content: GameContent) {
    if (!content.hasSaves && content.stops.isEmpty()) return
    ListGroup(title = "Progress", meta = content.savesCount.ifEmpty { null }) {
        content.stops.take(3).forEachIndexed { index, stop ->
            if (index > 0) ListDivider(inset = 58.dp)
            ListRow(
                title = stop.kindTitle,
                subtitle = "${PlayFormat.relative(stop.entry.capturedAtEpochMs)} · ${stop.entry.deviceLabel}",
                leading = { RowIcon(kindIcon(stop), tone = if (stop.isResume) Tone.WAIT else Tone.LIVE) },
                trailing = stop.entry.sync.takeIf { it != io.github.bandoracer.rommio.domain.playdata.SaveSyncStatus.UNKNOWN }?.let { sync -> { SyncBadge(sync.label, sync.tone()) } },
            )
        }
        if (content.stops.isEmpty()) ListRow(title = "Game-save data only")
    }
}

// ---- File and story --------------------------------------------------------------------------

/** The selected file; with more files than the picker holds, every file as a selectable row. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FileGroup(content: GameContent, actions: GameActions) {
    val theme = RommioTheme.current
    val file = content.selectedFile ?: return
    val many = content.rom.files.size > 4
    ListGroup(title = if (many) "Files" else "File", meta = if (many) content.rom.files.size.toString() else null) {
        if (many) {
            content.rom.files.forEachIndexed { index, f ->
                if (index > 0) ListDivider(inset = 58.dp)
                val selected = f.id == file.id
                val installed = content.state.installedFiles.any { it.fileId == f.id }
                ListRow(
                    title = f.fileName,
                    subtitle = "${f.effectiveFileExtension.ifBlank { "file" }.uppercase()} · ${formatBytes(f.fileSizeBytes)}",
                    leading = { RowIcon(Icons.Outlined.Description, tone = Tone.MUTE) },
                    trailing = {
                        when {
                            selected -> Tag(if (installed) "Selected · on this device" else "Selected", tone = Tone.TINT)
                            installed -> Tag("On this device", tone = Tone.OK)
                        }
                    },
                    onClick = { actions.selectFile(f.id) },
                    modifier = Modifier.semantics { this.selected = selected },
                )
            }
        } else {
            ListRow(title = file.fileName, subtitle = content.fileMeta, leading = { RowIcon(Icons.Outlined.Description, tone = Tone.MUTE) })
        }
        Column(
            Modifier.padding(horizontal = io.github.bandoracer.rommio.ui.component.LocalRowInset.current).padding(bottom = 12.dp, top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip(if (content.selectedInstalled) "On this device" else "Not downloaded", tone = if (content.selectedInstalled) Tone.OK else Tone.MUTE)
                StatusChip(
                    content.tierLabel,
                    tone = when (content.tier) {
                        io.github.bandoracer.rommio.domain.player.EmbeddedSupportTier.TOUCH_SUPPORTED -> Tone.OK
                        io.github.bandoracer.rommio.domain.player.EmbeddedSupportTier.CONTROLLER_SUPPORTED -> Tone.LIVE
                        io.github.bandoracer.rommio.domain.player.EmbeddedSupportTier.UNSUPPORTED -> Tone.MUTE
                    },
                )
            }
            content.state.downloadRecord?.lastError?.takeIf { it.isNotBlank() }?.let { RoleText(it, theme.type.caption, color = theme.colors.badText) }
        }
    }
}

/** RomM's details (docs/play-data-local.md §6 order) as a row group, `game.details`. */
@Composable
private fun DetailsSection(details: List<Pair<String, String>>) {
    if (details.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(title = "Details")
        GameDetailsGroup(details)
    }
}

/** RomM's screenshots, `game.screenshots` (frames as captured, never treated). */
@Composable
private fun ScreenshotsSection(content: GameContent) {
    val shots = content.rom.metadata.screenshots
    if (shots.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(title = "Screenshots", meta = shots.size.toString())
        ScreenshotsRow(shots, content.baseUrl, 0.dp)
    }
}

@Composable
private fun About(content: GameContent) {
    val theme = RommioTheme.current
    val c = theme.colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        content.rom.summary?.takeIf { it.isNotBlank() }?.let { summary ->
            SectionHeader(title = "About")
            RoleText(summary, theme.type.body, color = c.ink, maxLines = 10)
        }
        RoleText(
            "${Fmt.count(content.rom.files.size, "file")} available",
            theme.type.caption,
            color = c.inkTertiary,
        )
        if (content.state.isLocalOnly) {
            GlassSurface(Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.CloudOff, contentDescription = null, tint = c.wait)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        RoleText("Showing saved details", theme.type.bodyEmphasis, color = c.ink)
                        RoleText("This game was removed from RomM. The copy on this device stays until you delete it.", theme.type.caption, color = c.inkSecondary)
                    }
                }
            }
        }
    }
}
