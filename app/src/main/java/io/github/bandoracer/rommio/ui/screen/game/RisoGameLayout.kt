package io.github.bandoracer.rommio.ui.screen.game

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.bandoracer.rommio.domain.playdata.SaveSyncStatus
import io.github.bandoracer.rommio.ui.component.ColumnRule
import io.github.bandoracer.rommio.ui.component.DisplayText
import io.github.bandoracer.rommio.ui.component.InkBox
import io.github.bandoracer.rommio.ui.component.Kicker
import io.github.bandoracer.rommio.ui.component.KickerFill
import io.github.bandoracer.rommio.ui.component.PageFieldStyle
import io.github.bandoracer.rommio.ui.component.PlayFormat
import io.github.bandoracer.rommio.ui.component.PrintRule
import io.github.bandoracer.rommio.ui.component.RoleText
import io.github.bandoracer.rommio.ui.component.RuleWeight
import io.github.bandoracer.rommio.ui.component.SecondaryButton
import io.github.bandoracer.rommio.ui.component.SectionHeader
import io.github.bandoracer.rommio.ui.component.SpecRow
import io.github.bandoracer.rommio.ui.component.SyncBadge
import io.github.bandoracer.rommio.ui.component.formatPlayTime
import io.github.bandoracer.rommio.ui.component.isRegularWidth
import io.github.bandoracer.rommio.ui.theme.RommioTheme

/**
 * Riso Game: an article. The cover printed across the top; the title as a headline with its
 * standfirst; where you left off in an ink box; the main action; the story; and a details box. On
 * a regular width the headline spans the canvas and the body runs in three columns.
 */
@Composable
fun RisoGameLayout(content: GameContent, actions: GameActions, scroll: ScrollState) {
    val theme = RommioTheme.current
    val regular = isRegularWidth()
    // Riso never lights a page (the screen's LitPage passes no subject).
    Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
        GameArtHero(content.coverUrl, content.rom.displayName, heroHeight(0.32f))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = 1100.dp)
                    .fillMaxWidth()
                    .padding(horizontal = if (regular) 28.dp else theme.spacing.gutter)
                    .padding(top = 16.dp, bottom = 40.dp),
            ) {
                if (regular) Broadsheet(content, actions) else OneColumn(content, actions)
            }
        }
    }
}

@Composable
private fun OneColumn(content: GameContent, actions: GameActions) {
    Headline(content, regular = false)
    Byline(content, Modifier.padding(top = 12.dp))
    WhereYouLeftOff(content, actions, Modifier.padding(top = 22.dp))
    Column(Modifier.padding(top = 22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GameFilePicker(content, actions)
        GameActionDeck(content, actions, showsNotes = false)
    }
    Story(content, Modifier.padding(top = 26.dp))
    Extras(content)
    Details(content, actions, Modifier.padding(top = 26.dp))
    LocalOnlyNotice(content)
}

@Composable
private fun Broadsheet(content: GameContent, actions: GameActions) {
    val theme = RommioTheme.current
    Headline(content, regular = true)
    Byline(content, Modifier.padding(top = 12.dp))
    Row(Modifier.padding(top = 20.dp).height(IntrinsicSize.Min)) {
        Column(Modifier.width(230.dp).padding(end = 18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            content.standfirst?.let { RoleText(it, theme.type.body, size = 17f, italic = true, color = theme.colors.ink) }
            GameFilePicker(content, actions)
            GameActionDeck(content, actions, showsNotes = false)
        }
        ColumnRule()
        Box(Modifier.weight(1f).padding(horizontal = 18.dp)) { WhereYouLeftOff(content, actions, stacked = true) }
        ColumnRule()
        Column(Modifier.width(280.dp).padding(start = 18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Details(content, actions)
        }
    }
    Story(content, Modifier.padding(top = 26.dp))
    Extras(content)
    LocalOnlyNotice(content)
}

@Composable
private fun Headline(content: GameContent, regular: Boolean) {
    val theme = RommioTheme.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Kicker(content.rom.platformSlug.uppercase(), detail = content.platformLine, fill = KickerFill.PINK)
        DisplayText(content.rom.displayName, size = if (regular) 84f else 62f, maxLines = 3)
        if (!regular) content.standfirst?.let { RoleText(it, theme.type.body, size = 19f, italic = true, color = theme.colors.ink) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Byline(content: GameContent, modifier: Modifier = Modifier) {
    val theme = RommioTheme.current
    Column(modifier.fillMaxWidth()) {
        PrintRule(weight = RuleWeight.FINE)
        FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val metadata = content.rom.metadata
            val parts = listOfNotNull(
                content.rom.platformName,
                metadata.publisherLine.firstOrNull(),
            ) + metadata.genres.take(2) + listOfNotNull(
                content.tierLabel,
                content.sessionCount.takeIf { it > 0 }?.let { "${formatPlayTime(content.playedMs)} played" },
                content.statusText,
            )
            parts.forEach { RoleText(it, theme.type.label, size = 10f, color = theme.colors.inkSecondary, maxLines = 1) }
        }
    }
}

/**
 * "Where you left off" (from the saves reader): the resume point's frame (150 × 112, 1 dp ink
 * keyline, never printed; italic caption "“Name” · This phone") beside its entry, or the newest
 * save state's when there is no resume point; then Save states (up to three), Game-save data,
 * On this device, a conflict with "Choose resume point", and the browse button. [stacked] sets
 * the frame above the entry (the broadsheet's narrow column).
 */
@Composable
private fun WhereYouLeftOff(content: GameContent, actions: GameActions, modifier: Modifier = Modifier, stacked: Boolean = false) {
    val theme = RommioTheme.current
    val saves = content.state.saves
    val lead = saves.resume ?: saves.saveStates.firstOrNull()
    if (!content.hasSaves && content.sessionCount == 0 && !content.selectedInstalled) return
    val detail = (lead?.capturedAtEpochMs ?: content.lastPlayedAtEpochMs)?.let { PlayFormat.relative(it) }
    Box(modifier.testTag("game.savedProgress")) {
        InkBox("Where you left off", detail = detail) {
            Column {
                if (lead != null) {
                    val term = if (lead === saves.resume) "Automatic resume" else lead.slotNumber?.let { "Save state · Slot $it" } ?: "Save state"
                    // "3 minutes ago · today"; a relative time that already names the day stands alone.
                    val relative = PlayFormat.relative(lead.capturedAtEpochMs)
                    val today = PlayFormat.dayTitle(lead.capturedAtEpochMs) == "Today"
                    val value = relative.replaceFirstChar { it.uppercase() } + (if (today && relative.endsWith("ago")) " · today" else "")
                    val frame: @Composable () -> Unit = {
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            io.github.bandoracer.rommio.ui.component.SavePreview(lead.thumbnailPath, content.coverUrl, content.rom.displayName, width = 150.dp)
                            RoleText(listOfNotNull(lead.label?.let { "“$it”" }, lead.deviceLabel).joinToString(" · "),
                                theme.type.body, size = 13f, italic = true, color = theme.colors.inkSecondary, maxLines = 2,
                                modifier = Modifier.width(150.dp))
                        }
                    }
                    if (stacked) {
                        Column(Modifier.padding(top = 9.dp)) { frame(); Entry(term, value, lead.sync) }
                    } else {
                        Row(Modifier.padding(top = 9.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            frame()
                            Box(Modifier.weight(1f)) { Entry(term, value, lead.sync) }
                        }
                    }
                } else {
                    Entry("Automatic resume", if (content.hasSaves) "Game-save data only" else "No saved progress yet", null)
                }
                val states = saves.saveStates.filter { it !== lead }
                if (states.isNotEmpty()) {
                    PrintRule(weight = RuleWeight.HAIRLINE)
                    Entry("Save states", states.take(3).joinToString(" · ") { it.title }, null)
                }
                saves.gameSaveData.firstOrNull()?.let { latest ->
                    PrintRule(weight = RuleWeight.HAIRLINE)
                    Entry(
                        "Game-save data",
                        "Latest ${PlayFormat.relative(latest.capturedAtEpochMs)} · ${saves.gameSaveData.size} kept on this device",
                        latest.sync,
                    )
                }
                content.playLine?.let {
                    PrintRule(weight = RuleWeight.HAIRLINE)
                    Entry("On this device", it, null)
                }
                content.problemText?.let {
                    PrintRule(weight = RuleWeight.HAIRLINE)
                    RoleText(it, theme.type.caption, color = theme.colors.ink, modifier = Modifier.padding(vertical = 8.dp))
                    SecondaryButton(
                        "Choose resume point",
                        onClick = actions.chooseResume,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).testTag("game.chooseResume"),
                    )
                }
            }
            SecondaryButton(
                "Browse saved progress",
                onClick = actions.browseSaves,
                icon = Icons.Outlined.Layers,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp).testTag("game.viewSavedProgress"),
            )
        }
    }
}

@Composable
private fun Entry(term: String, value: String, sync: SaveSyncStatus?) {
    val theme = RommioTheme.current
    Column(Modifier.fillMaxWidth().padding(vertical = 9.dp).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RoleText(term, theme.type.label, size = 10f, color = theme.colors.ink)
        RoleText(value, theme.type.body, size = 14.5f, color = theme.colors.ink)
        sync?.takeIf { it != SaveSyncStatus.UNKNOWN }?.let { SyncBadge(it.label, it.tone()) }
    }
}

@Composable
private fun Story(content: GameContent, modifier: Modifier = Modifier) {
    val theme = RommioTheme.current
    val summary = content.rom.summary?.takeIf { it.isNotBlank() } ?: return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(title = "About")
        RoleText(summary, theme.type.body, size = 17f, color = theme.colors.ink)
    }
}

/** "Details": platform, how it plays, the file, status, readiness, problems, the core, notes and RomM's details. */
@Composable
private fun Details(content: GameContent, actions: GameActions, modifier: Modifier = Modifier) {
    val theme = RommioTheme.current
    val rows = buildList {
        // RomM's rows lead (play-data-local.md §6 order), then how it plays here.
        addAll(content.details)
        add("Platform" to content.rom.platformName)
        add("How it plays" to content.tierLabel)
        content.selectedFile?.let { file -> add("File" to "${file.fileName}\n${content.fileMeta.orEmpty()}") }
        add("Status" to content.statusText)
        content.controllerNote?.let { add("Ready" to it) }
        content.state.downloadRecord?.lastError?.takeIf { it.isNotBlank() }?.let { add("Problem" to it) }
        content.coreName?.let { add("Runs on" to it) }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        InkBox("Details", modifier = Modifier.testTag("game.details")) {
            Column {
                rows.forEachIndexed { index, (term, value) ->
                    if (index > 0) PrintRule(weight = RuleWeight.HAIRLINE)
                    SpecRow(term, value, Modifier.padding(vertical = 8.dp))
                }
                content.state.coreMessage?.let {
                    PrintRule(weight = RuleWeight.HAIRLINE)
                    RoleText(it, theme.type.caption, color = theme.colors.inkSecondary, modifier = Modifier.padding(vertical = 8.dp))
                }
            }
        }
        if (content.rom.files.size > 4) FileGroup(content, actions)
    }
}

/** Screenshots follow About (1 dp ink keyline, never printed). */
@Composable
private fun Extras(content: GameContent) {
    if (content.rom.metadata.screenshots.isNotEmpty()) {
        Column(Modifier.padding(top = 26.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionHeader(title = "Screenshots", meta = content.rom.metadata.screenshots.size.toString())
            ScreenshotsRow(content.rom.metadata.screenshots, content.baseUrl, 0.dp)
        }
    }
}

@Composable
private fun LocalOnlyNotice(content: GameContent) {
    val theme = RommioTheme.current
    if (content.state.isLocalOnly) {
        Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Kicker("Showing saved details", fill = KickerFill.YELLOW)
            RoleText("This game was removed from RomM. The copy on this device stays until you delete it.", theme.type.caption, color = theme.colors.inkSecondary)
        }
    }
}
