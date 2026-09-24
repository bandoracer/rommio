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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import io.github.bandoracer.rommio.AppContainer
import io.github.bandoracer.rommio.model.DownloadRecord
import io.github.bandoracer.rommio.model.DownloadStatus
import io.github.bandoracer.rommio.ui.component.CoverPlaceholder
import io.github.bandoracer.rommio.ui.component.CoverThumb
import io.github.bandoracer.rommio.ui.component.DisplayText
import io.github.bandoracer.rommio.ui.component.EmptyState
import io.github.bandoracer.rommio.ui.component.GameCover
import io.github.bandoracer.rommio.ui.component.Kicker
import io.github.bandoracer.rommio.ui.component.KickerFill
import io.github.bandoracer.rommio.ui.component.ListDivider
import io.github.bandoracer.rommio.ui.component.ListGroup
import io.github.bandoracer.rommio.ui.component.ListRow
import io.github.bandoracer.rommio.ui.component.PageArtSubject
import io.github.bandoracer.rommio.ui.component.PageFieldStyle
import io.github.bandoracer.rommio.ui.component.RoleText
import io.github.bandoracer.rommio.ui.component.RommioProgressBar
import io.github.bandoracer.rommio.ui.component.SecondaryButton
import io.github.bandoracer.rommio.ui.component.StatusChip
import io.github.bandoracer.rommio.ui.component.Tone
import io.github.bandoracer.rommio.ui.component.dropShadow
import io.github.bandoracer.rommio.ui.component.formatBytes
import io.github.bandoracer.rommio.ui.component.pageGutter
import io.github.bandoracer.rommio.ui.screen.collectAsStateWithLifecycleCompat
import io.github.bandoracer.rommio.ui.theme.RommioTheme
import io.github.bandoracer.rommio.util.resolveRemoteAssetUrl

/**
 * Downloads, pushed from the Play toolbar (docs/theme-layouts.md "Downloads — one layout"):
 * what's downloading, what's next, what needs attention, and what's finished. Lit by the running
 * download's cover; the components carry the theme.
 */
@Composable
fun DownloadsScreen(
    container: AppContainer,
    onOpenGame: (romId: Int) -> Unit = {},
    imageBaseUrl: String? = null,
) {
    val viewModel: DownloadsViewModel = viewModel(
        factory = viewModelFactory {
            initializer { DownloadsViewModel(container.repository) }
        },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycleCompat()
    val theme = RommioTheme.current
    val records = state.records
    val running = records.filter { it.status == DownloadStatus.RUNNING }
    val queued = records.filter { it.status == DownloadStatus.QUEUED }
    val attention = records.filter { it.status == DownloadStatus.FAILED || it.status == DownloadStatus.CANCELED }
    val finished = records.filter { it.status == DownloadStatus.COMPLETED }
    val upNext = running.drop(1) + queued
    val cover: (DownloadRecord) -> String? = { resolveRemoteAssetUrl(imageBaseUrl, state.covers[it.romId]) }
    PageArtSubject(running.firstOrNull()?.let(cover), PageFieldStyle.PAGE)
    val gutter = pageGutter()

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = 760.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .testTag("shell.downloadsList")
                .padding(start = gutter, end = gutter, top = 4.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(theme.spacing.section),
        ) {
            running.firstOrNull()?.let { current ->
                CurrentDownload(current, cover(current), onCancel = { viewModel.cancel(current) })
            }
            if (state.isOffline && queued.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.WifiOff, contentDescription = null, tint = theme.colors.inkSecondary, modifier = Modifier.size(16.dp))
                    RoleText(
                        "${io.github.bandoracer.rommio.ui.component.Fmt.count(queued.size, "download")} waiting for a connection.",
                        theme.type.secondary,
                        color = theme.colors.inkSecondary,
                    )
                }
            }
            if (upNext.isNotEmpty()) {
                ListGroup(title = "Up next", meta = upNext.size.toString()) {
                    upNext.forEachIndexed { index, record ->
                        if (index > 0) ListDivider(inset = 68.dp)
                        DownloadRow(record, cover(record), number = index + 1, onOpen = { onOpenGame(record.romId) }) {
                            if (record.status == DownloadStatus.QUEUED) SecondaryButton("Download now", onClick = { viewModel.downloadNow(record) })
                            SecondaryButton("Cancel", onClick = { viewModel.cancel(record) }, destructive = true)
                        }
                    }
                }
            }
            if (attention.isNotEmpty()) {
                ListGroup(title = "Didn't finish", meta = attention.size.toString()) {
                    attention.forEachIndexed { index, record ->
                        if (index > 0) ListDivider(inset = 68.dp)
                        DownloadRow(record, cover(record), onOpen = { onOpenGame(record.romId) }) {
                            SecondaryButton("Retry", onClick = { viewModel.retry(record) })
                            if (record.localPath != null) SecondaryButton("Delete download", onClick = { viewModel.deleteLocal(record) }, destructive = true)
                        }
                    }
                }
            }
            if (finished.isNotEmpty()) {
                ListGroup(title = "Downloaded", meta = finished.size.toString()) {
                    finished.forEachIndexed { index, record ->
                        if (index > 0) ListDivider(inset = 68.dp)
                        DownloadRow(record, cover(record), onOpen = { onOpenGame(record.romId) }) {
                            if (record.localPath != null) SecondaryButton("Delete download", onClick = { viewModel.deleteLocal(record) }, destructive = true)
                        }
                    }
                }
            }
            if (records.isEmpty()) {
                EmptyState(
                    title = "No downloads",
                    message = "Download a game from its page to play it on this device, even offline.",
                    icon = Icons.Outlined.Download,
                )
            }
            state.staleMessage?.takeIf { records.isNotEmpty() }?.let { message ->
                ListGroup(title = "Showing saved downloads", modifier = Modifier.testTag("downloads.stale")) {
                    RoleText(message, theme.type.caption, color = theme.colors.inkSecondary,
                        modifier = Modifier.padding(horizontal = io.github.bandoracer.rommio.ui.component.LocalRowInset.current, vertical = 12.dp))
                }
            }
        }
    }
}

private fun DownloadRecord.sizes(): String =
    "${formatBytes(bytesDownloaded)} of ${formatBytes(maxOf(totalBytes, fileSizeBytes))}"

/**
 * The download in progress, large. Aurora: the cover "develops" from frost to sharp as it
 * arrives, above the figures. Riso: the cover printed beside a headline, a halftone bar below.
 */
@Composable
private fun CurrentDownload(record: DownloadRecord, coverUrl: String?, onCancel: () -> Unit) {
    val theme = RommioTheme.current
    val c = theme.colors
    val fraction = record.progressPercent / 100f
    Column(
        Modifier.testTag("downloads.current"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (theme.isRiso) {
            Kicker("Downloading", detail = "${record.progressPercent}% · ${record.platformSlug.uppercase()}", fill = KickerFill.YELLOW)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                GameCover(coverUrl, record.romName, Modifier.size(76.dp, 101.dp), aspect = null)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DisplayText(record.romName, size = 34f, plateOffset = 1.25f, maxLines = 2)
                    RoleText(record.sizes(), theme.type.numeral, size = 11.5f, color = c.inkSecondary)
                    RoleText(record.fileName, theme.type.numeral, size = 11.5f, color = c.inkSecondary, maxLines = 1)
                }
            }
            RommioProgressBar(fraction, tone = Tone.LIVE, height = 24.dp)
            SecondaryButton("Cancel", onClick = onCancel, icon = Icons.Outlined.Close, destructive = true)
            return@Column
        }
        Developing(record, coverUrl, fraction)
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RoleText(record.platformSlug.uppercase(), theme.type.section, color = c.ink)
                    RoleText("Downloading", theme.type.section, color = c.inkSecondary)
                }
                RoleText(record.romName, theme.type.headline, color = c.ink, maxLines = 2)
                RoleText(record.sizes(), theme.type.secondary, color = c.inkSecondary)
            }
            RoleText("${record.progressPercent}%", theme.type.statValue, size = 34f, color = c.ink)
        }
        RommioProgressBar(fraction, tone = Tone.LIVE, height = 7.dp)
        SecondaryButton("Cancel", onClick = onCancel, destructive = true)
    }
}

/** Aurora: sharp art left of the progress, frosted (blur 18, saturation 0.6, ground 30 %) to the right, a 2 dp `mark1` edge. */
@Composable
private fun Developing(record: DownloadRecord, coverUrl: String?, fraction: Float) {
    val theme = RommioTheme.current
    val shape = RoundedCornerShape(theme.radius.card)
    val desaturate = remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0.6f) }) }
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(180.dp)
            .dropShadow(theme.mark1.copy(alpha = 0.35f), theme.radius.card, 22.dp, 12.dp)
            .clip(shape)
            .semantics { },
    ) {
        val width = maxWidth
        CoverPlaceholder(record.romName)
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
                Box(Modifier.fillMaxSize().background(theme.colors.ground.copy(alpha = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 0.3f else 0.6f)))
            }
        }
        Box(
            Modifier
                .offset(x = width * fraction - 1.dp)
                .width(2.dp)
                .fillMaxHeight()
                .dropShadow(theme.mark1, 0.dp, 8.dp, 0.dp)
                .background(theme.mark1),
        )
    }
}

/** One download with its state and actions. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DownloadRow(
    record: DownloadRecord,
    coverUrl: String?,
    number: Int? = null,
    onOpen: () -> Unit,
    actions: @Composable () -> Unit,
) {
    val theme = RommioTheme.current
    val c = theme.colors
    val (status, tone) = when (record.status) {
        DownloadStatus.QUEUED -> "Up next" to Tone.MUTE
        DownloadStatus.RUNNING -> "${record.progressPercent}%" to Tone.LIVE
        DownloadStatus.COMPLETED -> "Downloaded" to Tone.OK
        DownloadStatus.FAILED -> "Failed" to Tone.BAD
        DownloadStatus.CANCELED -> "Canceled" to Tone.WAIT
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ListRow(
            title = record.romName,
            subtitle = "${record.platformSlug.uppercase()} · ${formatBytes(maxOf(record.totalBytes, record.fileSizeBytes))}",
            leading = {
                if (theme.isRiso && number != null) {
                    Box(Modifier.size(32.dp).border(1.5.dp, c.ink), contentAlignment = Alignment.Center) {
                        RoleText("$number", theme.type.statValue, size = 22f, color = c.ink)
                    }
                } else {
                    CoverThumb(coverUrl, record.romName)
                }
            },
            trailing = { StatusChip(status, tone = tone) },
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
