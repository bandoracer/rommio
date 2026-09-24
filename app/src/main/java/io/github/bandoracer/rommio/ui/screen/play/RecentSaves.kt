package io.github.bandoracer.rommio.ui.screen.play

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import io.github.bandoracer.rommio.domain.playdata.SaveDay
import io.github.bandoracer.rommio.domain.playdata.SaveEntry
import io.github.bandoracer.rommio.domain.playdata.SaveKind
import io.github.bandoracer.rommio.domain.playdata.SaveSyncStatus
import io.github.bandoracer.rommio.domain.playdata.SavedProgressRules
import io.github.bandoracer.rommio.ui.component.CoverPlaceholder
import io.github.bandoracer.rommio.ui.component.LightStrip
import io.github.bandoracer.rommio.ui.component.PlayFormat
import io.github.bandoracer.rommio.ui.component.PrintRule
import io.github.bandoracer.rommio.ui.component.RoleText
import io.github.bandoracer.rommio.ui.component.RuleWeight
import io.github.bandoracer.rommio.ui.component.SavePreview
import io.github.bandoracer.rommio.ui.component.SectionHeader
import io.github.bandoracer.rommio.ui.component.StatusChip
import io.github.bandoracer.rommio.ui.component.chipTone
import io.github.bandoracer.rommio.ui.component.dropShadow
import io.github.bandoracer.rommio.ui.component.formatClockTime
import io.github.bandoracer.rommio.ui.component.rememberCoverMark
import io.github.bandoracer.rommio.ui.theme.RommioTheme
import io.github.bandoracer.rommio.ui.component.OverArt
import java.io.File
import java.time.ZoneId

/**
 * Recent saves on Play (docs/theme-layouts.md "Play — two layouts", docs/play-data-local.md §3):
 * resume points and save states across games, newest first, grouped by local day. Recent
 * sessions live on Stats only. Previews are shown as captured (never art-treated); the game's
 * cover stands in when a save has none.
 */
internal fun PlayUiState.saveDays(zone: ZoneId = ZoneId.systemDefault()): List<SaveDay> =
    SavedProgressRules.groupByDay(recentSaves.map { it.entry }, zone)

/** Chip copy for a save's kind: "Automatic resume", "Save state · Slot 2", "Save state". */
internal fun SaveEntry.kindChip(): String = when (kind) {
    SaveKind.RESUME_POINT -> if (isCurrentResume) "Automatic resume" else "Earlier resume point"
    SaveKind.SAVE_STATE -> slotNumber?.let { "Save state · Slot $it" } ?: "Save state"
    SaveKind.GAME_SAVE -> "Game-save data"
}

/** "“Before the boss” · 7:07 PM · This phone": the name (when given), when and where. */
internal fun SaveEntry.whenWhere(): String = listOfNotNull(
    label?.let { "“$it”" },
    formatClockTime(capturedAtEpochMs),
    deviceLabel,
).joinToString(" · ")

private fun saveCount(n: Int) = "$n save${if (n == 1) "" else "s"}"

// ---- Aurora: a lit gallery by day ------------------------------------------------------------

/**
 * Aurora: per day a `headline` 27 day, the long date and "N saves", a 3 dp light strip of the
 * day's games' `k1`, then a two-column mosaic of captured frames (first tile 16:9.4 across both
 * columns when the day has more than two; the rest 4:3.3), each on #0b0a12 with a dark scrim, the
 * kind as a dark glass chip, game name, "time · device" and the sync state, glowing in the game's
 * `k1`. Regular width (the studio's open canvas): each day is one row — a 150 dp margin with the
 * day, date, count and a vertical light strip, then a mosaic of four columns (tiles ≥ 180 wide;
 * fewer on a narrower canvas) whose first day's first tile spans two columns and two rows.
 */
@Composable
internal fun AuroraRecentSaves(state: PlayUiState, actions: PlayActions, regular: Boolean, gutter: Dp) {
    val days = state.saveDays()
    if (days.isEmpty()) return
    val byKey = state.recentSaves.associateBy { it.entry.key }
    Column(Modifier.padding(horizontal = gutter), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionHeader(title = "Recent saves", meta = state.recentSaves.size.toString())
        if (regular) {
            Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                days.forEachIndexed { index, day -> AuroraSaveDayRow(day, byKey, actions, hero = index == 0) }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(26.dp)) {
                days.forEach { day -> AuroraSaveDay(day, byKey, actions) }
            }
        }
    }
}

@Composable
private fun AuroraSaveDay(day: SaveDay, byKey: Map<io.github.bandoracer.rommio.domain.playdata.SaveKey, RecentSaveItem>, actions: PlayActions, modifier: Modifier = Modifier) {
    val theme = RommioTheme.current
    val items = day.entries.mapNotNull { byKey[it.key] }
    if (items.isEmpty()) return
    val group = PlayFormat.dayGroup(day.day)
    val lights = items.distinctBy { it.rom.id }.map { rememberCoverMark(actions.cover(it.rom)) }
    Column(modifier.testTag("play.recentSaves"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            RoleText(group.title, theme.type.headline, size = 27f, color = theme.colors.ink)
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The full date only when the title isn't already the date (§4 day groups).
                RoleText(group.caption.orEmpty(), theme.type.caption, size = 13f, color = theme.colors.inkSecondary, modifier = Modifier.weight(1f))
                RoleText(saveCount(items.size), theme.type.numeral, size = 12.5f, color = theme.colors.inkSecondary)
            }
            LightStrip(lights, Modifier.padding(top = 5.dp))
        }
        val hero = items.size > 2
        if (hero) SaveTile(items.first(), actions, large = true, modifier = Modifier.fillMaxWidth())
        (if (hero) items.drop(1) else items).chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { item -> SaveTile(item, actions, large = false, modifier = Modifier.weight(1f)) }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun AuroraSaveDayRow(day: SaveDay, byKey: Map<io.github.bandoracer.rommio.domain.playdata.SaveKey, RecentSaveItem>, actions: PlayActions, hero: Boolean) {
    val theme = RommioTheme.current
    val items = day.entries.mapNotNull { byKey[it.key] }
    if (items.isEmpty()) return
    val lights = items.distinctBy { it.rom.id }.map { rememberCoverMark(actions.cover(it.rom)) }
    Row(Modifier.fillMaxWidth().testTag("play.recentSaves"), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
        Column(
            Modifier
                .width(150.dp)
                .drawBehind {
                    val stops = if (lights.size > 1) lights else List(2) { lights.firstOrNull() ?: Color.Transparent }
                    drawRoundRect(
                        Brush.verticalGradient(stops),
                        topLeft = androidx.compose.ui.geometry.Offset(0f, 2.dp.toPx()),
                        size = androidx.compose.ui.geometry.Size(4.dp.toPx(), size.height - 4.dp.toPx()),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
                        alpha = 0.9f,
                    )
                }
                .padding(start = 16.dp, top = 2.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            val group = PlayFormat.dayGroup(day.day)
            RoleText(group.title, theme.type.headline, size = 26f, color = theme.colors.ink,
                modifier = Modifier.padding(bottom = 5.dp))
            group.caption?.let { RoleText(it, theme.type.caption, size = 13f, color = theme.colors.inkSecondary) }
            RoleText("${saveCount(items.size)} · ${PlayFormat.games(items.distinctBy { it.rom.id }.size)}",
                theme.type.numeral, size = 13f, color = theme.colors.inkSecondary)
        }
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f)) {
            val gap = 10.dp
            // Four columns where tiles keep at least 180 wide (the studio's open canvas); on a
            // narrower canvas two or three, with the first tile across the row as on a phone.
            val cols = ((maxWidth + gap) / (180.dp + gap)).toInt().coerceIn(2, 4)
            val cell = (maxWidth - gap * (cols - 1)) / cols
            val cellH = cell * 3.3f / 4f
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                var rest = items
                if (cols < 4) {
                    if (items.size > 2) {
                        SaveTile(items.first(), actions, large = true, modifier = Modifier.fillMaxWidth())
                        rest = items.drop(1)
                    }
                    rest.chunked(cols).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                            row.forEach { SaveTile(it, actions, large = false, modifier = Modifier.size(cell, cellH), aspect = null) }
                        }
                    }
                    return@Column
                }
                if (hero && items.size > 2) {
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        SaveTile(items.first(), actions, large = true, modifier = Modifier.size(cell * 2 + gap, cellH * 2 + gap), aspect = null)
                        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                            items.drop(1).take(4).chunked(2).forEach { pair ->
                                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                                    pair.forEach { SaveTile(it, actions, large = false, modifier = Modifier.size(cell, cellH), aspect = null) }
                                }
                            }
                        }
                    }
                    rest = items.drop(5)
                }
                rest.chunked(4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        row.forEach { SaveTile(it, actions, large = false, modifier = Modifier.size(cell, cellH), aspect = null) }
                    }
                }
            }
        }
    }
}

/** Sync tones over a captured frame: Aurora's night `ok`/`wait`/`live`/`bad` in both schemes. */
private fun SaveSyncStatus.frameColor(): Color = when (this) {
    SaveSyncStatus.SYNCED -> OverArt.ok
    SaveSyncStatus.WAITING, SaveSyncStatus.SIGN_IN_NEEDED -> OverArt.wait
    SaveSyncStatus.SYNCING -> OverArt.live
    SaveSyncStatus.CONFLICT -> OverArt.bad
    SaveSyncStatus.LOCAL_ONLY, SaveSyncStatus.UNKNOWN -> OverArt.quiet(0.7f)
}

@Composable
private fun SaveTile(item: RecentSaveItem, actions: PlayActions, large: Boolean, modifier: Modifier = Modifier,
    aspect: Float? = if (large) 16f / 9.4f else 4f / 3.3f) {
    val theme = RommioTheme.current
    val entry = item.entry
    val cover = actions.cover(item.rom)
    val radius = if (large) 22.dp else 16.dp
    val shape = RoundedCornerShape(radius)
    val glow = rememberCoverMark(cover)
    Box(
        modifier
            .then(if (aspect != null) Modifier.aspectRatio(aspect) else Modifier)
            .dropShadow(glow.copy(alpha = 0.55f), radius, radius = 12.dp, y = 16.dp)
            .clip(shape)
            .background(OverArt.frameGround)
            .border(0.5.dp, OverArt.keyline, shape)
            .clickable { actions.openSave(item) }
            .semantics(mergeDescendants = true) {}
            .testTag("play.recentSave"),
    ) {
        val thumb = entry.thumbnailPath
        when {
            thumb != null -> AsyncImage(
                model = File(thumb),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.None,
                modifier = Modifier.fillMaxSize(),
            )
            cover != null -> AsyncImage(model = cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else -> CoverPlaceholder(item.rom.displayName, Modifier.fillMaxSize())
        }
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0f to OverArt.shade(0.42f),
                        0.26f to Color.Transparent,
                        0.34f to Color.Transparent,
                        0.60f to OverArt.shade(0.78f),
                        1f to OverArt.shade(0.94f),
                    ),
                ),
        )
        FrameChip(
            text = entry.kindChip(),
            icon = if (entry.kind == SaveKind.SAVE_STATE) Icons.Outlined.Layers else Icons.Outlined.History,
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
        )
        val sync = entry.sync.takeIf { it != SaveSyncStatus.UNKNOWN }
        if (large && sync != null) {
            FrameChip(sync.label, icon = null, dot = sync.frameColor(), modifier = Modifier.align(Alignment.TopEnd).padding(8.dp))
        }
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = if (large) 16.dp else 11.dp, end = 11.dp, bottom = if (large) 14.dp else 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            RoleText(item.rom.displayName, theme.type.headline, size = if (large) 28f else 18f, color = OverArt.ink, maxLines = if (large) 2 else 1)
            Text(
                entry.whenWhere(),
                color = OverArt.quiet(0.76f),
                fontSize = if (large) 13.sp else 11.5.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = theme.type.caption.style.fontFamily,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!large && sync != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (sync == SaveSyncStatus.CONFLICT) {
                        Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = sync.frameColor(), modifier = Modifier.size(11.dp))
                    } else {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(sync.frameColor()))
                    }
                    Text(sync.label, color = sync.frameColor(), fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = theme.type.caption.style.fontFamily, maxLines = 1)
                }
            }
        }
    }
}

/** A dark glass pill over a frame: kind (with its glyph) or sync (with its dot), 22 dp high. */
@Composable
private fun FrameChip(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector?, modifier: Modifier = Modifier, dot: Color? = null) {
    val theme = RommioTheme.current
    Row(
        modifier
            .height(22.dp)
            .clip(CircleShape)
            .background(OverArt.glass)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = OverArt.quiet(0.92f), modifier = Modifier.size(12.dp))
        if (dot != null) Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
        Text(text, color = dot ?: OverArt.quiet(0.92f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            fontFamily = theme.type.caption.style.fontFamily, maxLines = 1)
    }
}

// ---- Riso: a list by day ---------------------------------------------------------------------

/**
 * Riso: per day a `headline` 22 day with the italic long date and "N saves", a 1 pt rule, then
 * paper rows: the captured frame 64 × 48 in a 1 pt ink keyline (never printed), `label` 10
 * "SAVE STATE · SLOT 2", `headline` 18 game name, italic "“Name” · 7:07 PM · This phone", and the
 * sync state as a stamp; hairlines between.
 */
@Composable
internal fun RisoRecentSaves(state: PlayUiState, actions: PlayActions, modifier: Modifier = Modifier) {
    val days = state.saveDays()
    if (days.isEmpty()) return
    val theme = RommioTheme.current
    val byKey = state.recentSaves.associateBy { it.entry.key }
    Column(modifier.fillMaxWidth()) {
        SectionHeader(title = "Recent saves")
        days.forEach { day ->
            val items = day.entries.mapNotNull { byKey[it.key] }
            if (items.isEmpty()) return@forEach
            Column(Modifier.testTag("play.recentSaves").padding(top = 6.dp)) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val group = PlayFormat.dayGroup(day.day)
                    RoleText(group.title, theme.type.headline, size = 22f, color = theme.colors.ink)
                    RoleText(
                        io.github.bandoracer.rommio.ui.component.Fmt.join(group.caption, saveCount(items.size)),
                        theme.type.body, size = 14f, italic = true, color = theme.colors.inkSecondary,
                        modifier = Modifier.weight(1f).padding(bottom = 2.dp), maxLines = 1,
                    )
                }
                PrintRule(weight = RuleWeight.FINE, modifier = Modifier.padding(top = 6.dp))
                items.forEachIndexed { index, item ->
                    if (index > 0) PrintRule(weight = RuleWeight.HAIRLINE)
                    RisoSaveRow(item, actions)
                }
            }
        }
    }
}

@Composable
private fun RisoSaveRow(item: RecentSaveItem, actions: PlayActions) {
    val theme = RommioTheme.current
    val entry = item.entry
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { actions.openSave(item) }
            .semantics(mergeDescendants = true) {}
            .padding(vertical = 11.dp)
            .testTag("play.recentSave"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SavePreview(entry.thumbnailPath, actions.cover(item.rom), item.rom.displayName, width = 64.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            RoleText(entry.kindChip(), theme.type.label, size = 10f, color = theme.colors.inkSecondary, maxLines = 1)
            RoleText(item.rom.displayName, theme.type.headline, size = 18f, color = theme.colors.ink, maxLines = 2)
            RoleText(entry.whenWhere(), theme.type.body, size = 14f, italic = true, color = theme.colors.inkSecondary, maxLines = 2)
        }
        entry.sync.takeIf { it != SaveSyncStatus.UNKNOWN }?.let { sync ->
            StatusChip(sync.label, tone = sync.chipTone())
        }
    }
}
