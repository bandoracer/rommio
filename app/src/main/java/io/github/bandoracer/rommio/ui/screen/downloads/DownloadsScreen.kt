package io.github.bandoracer.rommio.ui.screen.downloads

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import io.github.bandoracer.rommio.AppContainer
import io.github.bandoracer.rommio.model.DownloadStatus
import io.github.bandoracer.rommio.ui.component.CoverPlaceholder
import io.github.bandoracer.rommio.ui.component.CoverThumb
import io.github.bandoracer.rommio.ui.component.DisplayText
import io.github.bandoracer.rommio.ui.component.EmptyState
import io.github.bandoracer.rommio.ui.component.GameCover
import io.github.bandoracer.rommio.ui.component.Kicker
import io.github.bandoracer.rommio.ui.component.KickerFill
import io.github.bandoracer.rommio.ui.component.ListGroup
import io.github.bandoracer.rommio.ui.component.ListRow
import io.github.bandoracer.rommio.ui.component.LitPage
import io.github.bandoracer.rommio.ui.component.PageFieldStyle
import io.github.bandoracer.rommio.ui.component.RoleText
import io.github.bandoracer.rommio.ui.component.RommioProgressBar
import io.github.bandoracer.rommio.ui.component.SecondaryButton
import io.github.bandoracer.rommio.ui.component.StatusChip
import io.github.bandoracer.rommio.ui.component.Tone
import io.github.bandoracer.rommio.ui.component.dropShadow
import io.github.bandoracer.rommio.ui.component.drawCardGroups
import io.github.bandoracer.rommio.ui.component.lazyListGroup
import io.github.bandoracer.rommio.ui.component.pageGutter
import io.github.bandoracer.rommio.ui.component.rememberLazyCardGroups
import io.github.bandoracer.rommio.ui.theme.RommioTheme

/** What Downloads can do. */
private class DownloadActions(val viewModel: DownloadsViewModel, val open: (Int) -> Unit)

/**
 * Downloads, pushed from the Play toolbar (docs/theme-layouts.md "Downloads — one layout"):
 * what's downloading, what's next, what needs attention, and what's finished. Lit by the running
 * download's cover; the components carry the theme. The page is built by [DownloadsViewModel]
 * ([DownloadsPage]); every list is lazy (rows in a group share one card, drawn by the list).
 */
@Composable
fun DownloadsScreen(
    container: AppContainer,
    onOpenGame: (romId: Int) -> Unit = {},
    @Suppress("UNUSED_PARAMETER") imageBaseUrl: String? = null,
) {
    val viewModel: DownloadsViewModel = viewModel(
        factory = viewModelFactory {
            initializer { DownloadsViewModel(container.repository) }
        },
    )
    val page by viewModel.page.collectAsStateWithLifecycle()
    val actions = remember(viewModel, onOpenGame) { DownloadActions(viewModel, onOpenGame) }
    LitPage(page.current?.coverUrl, PageFieldStyle.PAGE) { DownloadsLayout(page, actions) }
}

@Composable
private fun DownloadsLayout(page: DownloadsPage, actions: DownloadActions) {
    val theme = RommioTheme.current
    val riso = theme.isRiso
    val gutter = pageGutter()
    val section = theme.spacing.section
    val listState = rememberLazyListState()
    val groups = rememberLazyCardGroups()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 760.dp)
                .fillMaxSize()
                .drawCardGroups(listState, groups, gutter)
                .testTag("shell.downloadsList"),
            state = listState,
            contentPadding = PaddingValues(start = gutter, end = gutter, top = 4.dp, bottom = 40.dp),
        ) {
            // The page is a column with `section` between its parts; the first part has none above it.
            var first = true
            fun gap() = if (first) 0.dp.also { first = false } else section
            page.current?.let { current ->
                val top = gap()
                item(key = "current", contentType = "current") {
                    CurrentDownload(current, onCancel = { actions.viewModel.cancel(current.record) }, Modifier.padding(top = top))
                }
            }
            if (page.isOffline && page.queuedCount > 0) {
                val top = gap()
                item(key = "offline", contentType = "offline") { OfflineNotice(page.queuedCount, Modifier.padding(top = top)) }
            }
            if (page.upNext.isNotEmpty()) {
                lazyListGroup(groups, riso, "upNext", page.upNext, { it.key }, title = "Up next", meta = page.upNext.size.toString(), top = gap(), dividerInset = 68.dp) { index, item ->
                    DownloadRow(item, number = index + 1, onOpen = { actions.open(item.record.romId) }) {
                        if (item.record.status == DownloadStatus.QUEUED) SecondaryButton("Download now", onClick = { actions.viewModel.downloadNow(item.record) })
                        SecondaryButton("Cancel", onClick = { actions.viewModel.cancel(item.record) }, destructive = true)
                    }
                }
            }
            if (page.attention.isNotEmpty()) {
                lazyListGroup(groups, riso, "attention", page.attention, { it.key }, title = "Didn't finish", meta = page.attention.size.toString(), top = gap(), dividerInset = 68.dp) { _, item ->
                    DownloadRow(item, onOpen = { actions.open(item.record.romId) }) {
                        SecondaryButton("Retry", onClick = { actions.viewModel.retry(item.record) })
                        if (item.record.localPath != null) SecondaryButton("Delete download", onClick = { actions.viewModel.deleteLocal(item.record) }, destructive = true)
                    }
                }
            }
            if (page.finished.isNotEmpty()) {
                lazyListGroup(groups, riso, "finished", page.finished, { it.key }, title = "Downloaded", meta = page.finished.size.toString(), top = gap(), dividerInset = 68.dp) { _, item ->
                    DownloadRow(item, onOpen = { actions.open(item.record.romId) }) {
                        if (item.record.localPath != null) SecondaryButton("Delete download", onClick = { actions.viewModel.deleteLocal(item.record) }, destructive = true)
                    }
                }
            }
            if (page.isEmpty) {
                val top = gap()
                item(key = "empty", contentType = "empty") {
                    EmptyState(
                        title = "No downloads",
                        message = "Download a game from its page to play it on this device, even offline.",
                        icon = Icons.Outlined.Download,
                        modifier = Modifier.padding(top = top),
                    )
                }
            }
            page.staleMessage?.takeIf { !page.isEmpty }?.let { message ->
                val top = gap()
                item(key = "stale", contentType = "stale") {
                    ListGroup(title = "Showing saved downloads", modifier = Modifier.padding(top = top).testTag("downloads.stale")) {
                        RoleText(message, theme.type.caption, color = theme.colors.inkSecondary,
                            modifier = Modifier.padding(horizontal = io.github.bandoracer.rommio.ui.component.LocalRowInset.current, vertical = 12.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun OfflineNotice(queued: Int, modifier: Modifier) {
    val theme = RommioTheme.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.WifiOff, contentDescription = null, tint = theme.colors.inkSecondary, modifier = Modifier.size(16.dp))
        RoleText(
            "${io.github.bandoracer.rommio.ui.component.Fmt.count(queued, "download")} waiting for a connection.",
            theme.type.secondary,
            color = theme.colors.inkSecondary,
        )
    }
}

/**
 * The download in progress, large. Aurora: the cover "develops" from frost to sharp as it
 * arrives, above the figures. Riso: the cover printed beside a headline, a halftone bar below.
 */
@Composable
private fun CurrentDownload(item: DownloadItem, onCancel: () -> Unit, modifier: Modifier) {
    val theme = RommioTheme.current
    val c = theme.colors
    val record = item.record
    val fraction = record.progressPercent / 100f
    Column(
        modifier.testTag("downloads.current"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (theme.isRiso) {
            Kicker("Downloading", detail = "${record.progressPercent}% · ${record.platformSlug.uppercase()}", fill = KickerFill.YELLOW)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                GameCover(item.coverUrl, record.romName, Modifier.size(76.dp, 101.dp), aspect = null)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DisplayText(record.romName, size = 34f, plateOffset = 1.25f, maxLines = 2)
                    RoleText(item.sizes, theme.type.numeral, size = 11.5f, color = c.inkSecondary)
                    RoleText(record.fileName, theme.type.numeral, size = 11.5f, color = c.inkSecondary, maxLines = 1)
                }
            }
            RommioProgressBar(fraction, tone = Tone.LIVE, height = 24.dp)
            SecondaryButton("Cancel", onClick = onCancel, icon = Icons.Outlined.Close, destructive = true)
            return@Column
        }
        Developing(record.romName, item.coverUrl, fraction)
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RoleText(record.platformSlug.uppercase(), theme.type.section, color = c.ink)
                    RoleText("Downloading", theme.type.section, color = c.inkSecondary)
                }
                RoleText(record.romName, theme.type.headline, color = c.ink, maxLines = 2)
                RoleText(item.sizes, theme.type.secondary, color = c.inkSecondary)
            }
            RoleText("${record.progressPercent}%", theme.type.statValue, size = 34f, color = c.ink)
        }
        RommioProgressBar(fraction, tone = Tone.LIVE, height = 7.dp)
        SecondaryButton("Cancel", onClick = onCancel, destructive = true)
    }
}

/** Aurora: sharp art left of the progress, frosted (blur 18, saturation 0.6, ground 30 %) to the right, a 2 dp `mark1` edge. */
@Composable
private fun Developing(title: String, coverUrl: String?, fraction: Float) {
    val theme = RommioTheme.current
    // The frost settles into the page's lit ground; the edge glows in its mark.
    val light = RommioTheme.light
    val shape = RoundedCornerShape(theme.radius.card)
    val desaturate = remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0.6f) }) }
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(180.dp)
            .dropShadow(light.mark1.copy(alpha = 0.35f), theme.radius.card, 22.dp, 12.dp)
            .clip(shape)
            .semantics { },
    ) {
        val width = maxWidth
        CoverPlaceholder(title)
        coverUrl?.let { AsyncImage(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        // Still arriving: the rest of the art is frosted.
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .width(width * (1f - fraction))
                .fillMaxHeight()
                .clip(RoundedCornerShape(0.dp)),
        ) {
            Box(Modifier.width(width).fillMaxHeight().align(Alignment.CenterEnd)) {
                coverUrl?.let {
                    AsyncImage(
                        it,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        colorFilter = desaturate,
                        modifier = Modifier
                            .fillMaxSize()
                            .then(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(18.dp) else Modifier),
                    )
                }
                Box(Modifier.fillMaxSize().background(light.ground.copy(alpha = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 0.3f else 0.6f)))
            }
        }
        Box(
            Modifier
                .offset(x = width * fraction - 1.dp)
                .width(2.dp)
                .fillMaxHeight()
                .dropShadow(light.mark1, 0.dp, 8.dp, 0.dp)
                .background(light.mark1),
        )
    }
}

/** One download with its state and actions. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DownloadRow(
    item: DownloadItem,
    number: Int? = null,
    onOpen: () -> Unit,
    actions: @Composable () -> Unit,
) {
    val theme = RommioTheme.current
    val c = theme.colors
    val record = item.record
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ListRow(
            title = record.romName,
            subtitle = item.subtitle,
            leading = {
                if (theme.isRiso && number != null) {
                    Box(Modifier.size(32.dp).border(1.5.dp, c.ink), contentAlignment = Alignment.Center) {
                        RoleText("$number", theme.type.statValue, size = 22f, color = c.ink)
                    }
                } else {
                    CoverThumb(item.coverUrl, record.romName)
                }
            },
            trailing = { StatusChip(item.status, tone = item.tone) },
            onClick = onOpen,
        )
        val inset = io.github.bandoracer.rommio.ui.component.LocalRowInset.current
        Column(Modifier.padding(start = inset, end = inset, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (record.status == DownloadStatus.RUNNING || record.status == DownloadStatus.QUEUED) {
                RommioProgressBar(record.progressPercent / 100f, tone = Tone.LIVE, height = if (theme.isRiso) 10.dp else 5.dp)
            }
            if (record.status == DownloadStatus.FAILED) {
                record.lastError?.takeIf { it.isNotBlank() }?.let { RoleText(it, theme.type.caption, color = c.badText) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
        }
    }
}
